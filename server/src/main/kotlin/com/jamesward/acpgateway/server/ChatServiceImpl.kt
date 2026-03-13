package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val relayJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val logger = LoggerFactory.getLogger("ChatServiceImpl")

class ChatServiceImpl(
    private val holder: AgentHolder,
    private val mode: GatewayMode,
    private val debug: Boolean,
    private val commandHandler: CommandHandler?,
    private val internalCommands: List<CommandInfo>,
    private val sessionId: UUID?,
    private val relayLookup: ((UUID) -> RelaySession?)? = null,
) : IChatService {
    override suspend fun chat(input: ReceiveChannel<WsMessage>, output: SendChannel<WsMessage>) {
        // In proxy mode, check if a relay session exists (CLI backend connected)
        if (mode == GatewayMode.PROXY && sessionId != null) {
            val relay = relayLookup?.invoke(sessionId)
            if (relay != null) {
                bridgeRelay(relay, input, output)
                return
            }
        }

        while (true) {
            val manager = holder.manager
            if (manager == null) {
                // No agent selected — wait for a ChangeAgent message
                output.send(WsMessage.Connected("No agent selected", "", agentWorking = false))
                if (holder.registry.isNotEmpty()) {
                    output.send(WsMessage.AvailableAgents(
                        agents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                        currentAgentId = null,
                    ))
                    output.send(WsMessage.AvailableCommands(emptyList()))
                }
                val agentId = waitForChangeAgent(input)
                    ?: return // Client disconnected
                holder.switchAgent(agentId)
                continue
            }
            val session = if (mode == GatewayMode.LOCAL) {
                manager.sessions.values.firstOrNull()
            } else {
                sessionId?.let { manager.getSession(it) }
            }
            if (session == null) {
                output.send(WsMessage.Error("No session available"))
                return
            }
            try {
                handleChatChannels(
                    input, output, session, manager,
                    debug = debug,
                    commandHandler = commandHandler,
                    internalCommands = internalCommands,
                    availableAgents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                    currentAgentId = holder.currentAgentId,
                )
                return // Normal exit (client disconnected)
            } catch (e: AgentSwitchException) {
                holder.switchAgent(e.agentId)
                // Loop continues — new manager/session will be resolved at top of loop
            }
        }
    }

    /**
     * Bridges RPC channels to a relay session.
     * Replays cached messages, then forwards messages bidirectionally between
     * the RPC client (browser) and the relay backend WS (CLI).
     */
    private suspend fun bridgeRelay(
        relay: RelaySession,
        input: ReceiveChannel<WsMessage>,
        output: SendChannel<WsMessage>,
    ) {
        logger.info("Bridging RPC client to relay session {}", relay.sessionId)

        // Register a typed channel so the relay forwards new messages to us
        val relayChannel = Channel<WsMessage>(Channel.UNLIMITED)
        relay.rpcChannels.add(relayChannel)

        try {
            // Replay cached messages
            var hasConnected = false
            var hasAvailableAgents = false
            for (cached in relay.messageCache) {
                try {
                    val msg = relayJson.decodeFromString(WsMessage.serializer(), cached)
                    if (msg is WsMessage.Connected) hasConnected = true
                    if (msg is WsMessage.AvailableAgents) hasAvailableAgents = true
                    output.send(msg)
                } catch (e: Exception) {
                    logger.warn("Failed to decode cached relay message: {}", e.message)
                }
            }

            // If no Connected message was cached (CLI hasn't sent one yet),
            // synthesize one so the browser isn't left with a blank screen.
            if (!hasConnected) {
                val agentName = if (relay.agentId != null) {
                    holder.registry.find { it.id == relay.agentId }?.name ?: relay.agentId!!
                } else {
                    "No agent selected"
                }
                output.send(WsMessage.Connected(agentName, "", agentWorking = false))
            }

            // The CLI doesn't send AvailableAgents (it has no registry).
            // Always send it from the server so the browser has agent info and icon.
            if (!hasAvailableAgents && holder.registry.isNotEmpty()) {
                output.send(WsMessage.AvailableAgents(
                    agents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                    currentAgentId = relay.agentId,
                ))
                output.send(WsMessage.AvailableCommands(emptyList()))
            }

            // Bidirectional forwarding — when either side closes, terminate the other
            coroutineScope {
                // Relay → browser: forward new messages from CLI backend
                launch {
                    for (msg in relayChannel) {
                        try {
                            output.send(msg)
                            // The CLI doesn't send AvailableAgents — supplement after
                            // each Connected so the browser has agent info and icon.
                            if (msg is WsMessage.Connected && holder.registry.isNotEmpty()) {
                                output.send(WsMessage.AvailableAgents(
                                    agents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                                    currentAgentId = relay.agentId,
                                ))
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }
                    // Relay channel closed (CLI disconnected) — cancel scope to stop input reading
                    throw kotlinx.coroutines.CancellationException("Relay closed")
                }

                // Browser → CLI: forward messages from browser to relay backend (runs in scope body)
                try {
                    for (msg in input) {
                        // Signal the relay to survive CLI disconnect/reconnect during agent switch
                        if (msg is WsMessage.ChangeAgent) {
                            relay.switchInProgress = true
                        }
                        val backend = relay.backendWs ?: continue
                        try {
                            val text = relayJson.encodeToString(WsMessage.serializer(), msg)
                            backend.send(Frame.Text(text))
                        } catch (e: Exception) {
                            logger.warn("Failed to forward message to relay backend: {}", e.message)
                        }
                    }
                } finally {
                    // Input closed (browser disconnected) — close relay channel to stop relay coroutine
                    relayChannel.close()
                }
            }
        } finally {
            relay.rpcChannels.remove(relayChannel)
            relayChannel.close()
        }
    }

    /** Reads from [input] until a [WsMessage.ChangeAgent] arrives. Returns the agentId, or null if channel closed. */
    private suspend fun waitForChangeAgent(input: ReceiveChannel<WsMessage>): String? {
        for (msg in input) {
            if (msg is WsMessage.ChangeAgent) return msg.agentId
            // Ignore other messages while waiting for agent selection
        }
        return null
    }
}
