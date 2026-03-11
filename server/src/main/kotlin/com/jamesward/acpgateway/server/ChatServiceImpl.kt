package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.util.UUID

class ChatServiceImpl(
    private val holder: AgentHolder,
    private val mode: GatewayMode,
    private val debug: Boolean,
    private val commandHandler: CommandHandler?,
    private val internalCommands: List<CommandInfo>,
    private val sessionId: UUID?,
) : IChatService {
    override suspend fun chat(input: ReceiveChannel<WsMessage>, output: SendChannel<WsMessage>) {
        while (true) {
            val manager = holder.manager
            if (manager == null) {
                // No agent selected — wait for a ChangeAgent message
                output.send(WsMessage.Connected("No agent selected", "", agentWorking = false))
                if (holder.registry.isNotEmpty()) {
                    output.send(WsMessage.AvailableAgents(
                        agents = holder.registry.map { AgentInfo(it.id, it.name) },
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
                    availableAgents = holder.registry.map { AgentInfo(it.id, it.name) },
                    currentAgentId = holder.currentAgentId,
                )
                return // Normal exit (client disconnected)
            } catch (e: AgentSwitchException) {
                holder.switchAgent(e.agentId)
                // Loop continues — new manager/session will be resolved at top of loop
            }
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
