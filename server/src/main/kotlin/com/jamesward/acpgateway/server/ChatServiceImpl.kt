package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.AgentInfo
import com.jamesward.acpgateway.shared.IChatService
import com.jamesward.acpgateway.shared.RegistryAgent
import com.jamesward.acpgateway.shared.WsMessage
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

private val relayJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val logger = LoggerFactory.getLogger("ChatServiceImpl")

class ChatServiceImpl(
    private val registry: List<RegistryAgent>,
    private val sessionId: UUID?,
    private val relayLookup: ((UUID) -> RelaySession?)? = null,
) : IChatService {
    override suspend fun chat(input: ReceiveChannel<WsMessage>, output: SendChannel<WsMessage>) {
        if (sessionId == null) {
            output.send(WsMessage.Error("No session ID"))
            return
        }

        val relay = relayLookup?.invoke(sessionId)
        if (relay != null) {
            bridgeRelay(relay, input, output)
            return
        }

        // No relay session yet — send waiting state and available agents
        output.send(WsMessage.Connected("Waiting for CLI connection", "", agentWorking = false))
        if (registry.isNotEmpty()) {
            output.send(WsMessage.AvailableAgents(
                agents = registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                currentAgentId = null,
            ))
            output.send(WsMessage.AvailableCommands(emptyList()))
        }

        // Wait for a ChangeAgent (browser-side selection) or just consume until close
        for (msg in input) {
            if (msg is WsMessage.ChangeAgent) {
                // Forward the change agent to relay if it appeared while we were waiting
                val newRelay = relayLookup?.invoke(sessionId)
                if (newRelay != null) {
                    val backend = newRelay.backendWs
                    if (backend != null) {
                        try {
                            val text = relayJson.encodeToString(WsMessage.serializer(), msg)
                            backend.send(Frame.Text(text))
                        } catch (_: Exception) {}
                    }
                    bridgeRelay(newRelay, input, output)
                    return
                }
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
            var hasAvailableCommands = false
            for (cached in relay.messageCache) {
                try {
                    var msg = relayJson.decodeFromString(WsMessage.serializer(), cached)
                    if (msg is WsMessage.Connected) {
                        hasConnected = true
                        // Override agentWorking if a turn is currently active
                        if (relay.turnActive && !msg.agentWorking) {
                            msg = msg.copy(agentWorking = true)
                        }
                    }
                    // Mark completed-turn TurnComplete as "history" so client doesn't reset agentWorking
                    if (msg is WsMessage.TurnComplete && relay.turnActive && msg.stopReason != "history") {
                        msg = msg.copy(stopReason = "history")
                    }
                    if (msg is WsMessage.AvailableAgents) hasAvailableAgents = true
                    if (msg is WsMessage.AvailableCommands) hasAvailableCommands = true
                    output.send(msg)
                } catch (e: Exception) {
                    logger.warn("Failed to decode cached relay message: {}", e.message)
                }
            }

            // If no Connected message was cached (CLI hasn't sent one yet),
            // synthesize one so the browser isn't left with a blank screen.
            if (!hasConnected) {
                val agentName = if (relay.agentId != null) {
                    registry.find { it.id == relay.agentId }?.name ?: relay.agentId!!
                } else {
                    "No agent selected"
                }
                output.send(WsMessage.Connected(agentName, "", agentWorking = relay.turnActive))
            }

            // The CLI doesn't send AvailableAgents (it has no registry).
            // Always send it from the server so the browser has agent info and icon.
            if (!hasAvailableAgents && registry.isNotEmpty()) {
                output.send(WsMessage.AvailableAgents(
                    agents = registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                    currentAgentId = relay.agentId,
                ))
                if (!hasAvailableCommands) {
                    output.send(WsMessage.AvailableCommands(emptyList()))
                }
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
                            if (msg is WsMessage.Connected && registry.isNotEmpty()) {
                                output.send(WsMessage.AvailableAgents(
                                    agents = registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
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
                        val text = relayJson.encodeToString(WsMessage.serializer(), msg)
                        // Broadcast Cancel/PermissionResponse to other frontends so they stay in sync
                        if (msg is WsMessage.Cancel || msg is WsMessage.PermissionResponse) {
                            if (msg is WsMessage.PermissionResponse) relay.messageCache.add(text)
                            relay.broadcastToFrontends(msg, text, exclude = relayChannel)
                        }
                        val backend = relay.backendWs ?: continue
                        try {
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
}
