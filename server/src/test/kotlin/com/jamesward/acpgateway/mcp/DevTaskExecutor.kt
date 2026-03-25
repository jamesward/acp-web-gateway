@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.mcp

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.jamesward.acpgateway.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * TaskExecutor for dev mode — directly uses GatewaySession to prompt the agent.
 * Calls session.broadcast() so browser clients also see MCP-initiated activity.
 */
class DevTaskExecutor(
    private val sessionProvider: () -> GatewaySession?,
    private val scope: CoroutineScope,
) : TaskExecutor {

    private val logger = LoggerFactory.getLogger(DevTaskExecutor::class.java)

    override suspend fun executePrompt(prompt: String): ReceiveChannel<WsMessage> {
        val session = sessionProvider()
            ?: throw IllegalStateException("No active session")

        val output = Channel<WsMessage>(Channel.UNLIMITED)

        // Register our channel to receive broadcasts from the session.
        // When we call session.broadcast(), it sends to all connections including this one.
        session.connections.add(output)

        // Broadcast the user prompt to all connected browsers so it appears in the UI
        session.broadcast(WsMessage.UserMessage(text = prompt))

        // Launch the prompt processing in a coroutine so we can return the channel immediately
        scope.launch {
            try {
                val events = session.prompt(prompt)
                processEventFlow(events, session)
            } catch (e: Exception) {
                logger.error("MCP prompt failed", e)
                try { output.send(WsMessage.Error(e.message ?: "Unknown error")) } catch (_: Exception) {}
            } finally {
                session.connections.remove(output)
                output.close()
            }
        }

        return output
    }

    override suspend fun cancel() {
        sessionProvider()?.cancelPrompt()
    }

    override suspend fun respondToPermission(toolCallId: String, optionId: String) {
        val session = sessionProvider() ?: return
        session.clientOps.completePermission(toolCallId, optionId)
    }

    private suspend fun processEventFlow(
        events: kotlinx.coroutines.flow.Flow<Event>,
        session: GatewaySession,
    ) {
        val turnCounter = System.currentTimeMillis()
        val msgId = "mcp-msg-$turnCounter"
        val thoughtId = "mcp-thought-$turnCounter"

        events.collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    when (val update = event.update) {
                        is SessionUpdate.AgentMessageChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                session.broadcast(WsMessage.AgentText(msgId = msgId, markdown = content.text))
                            }
                        }
                        is SessionUpdate.AgentThoughtChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                session.broadcast(WsMessage.AgentThought(thoughtId = thoughtId, markdown = content.text))
                            }
                        }
                        is SessionUpdate.ToolCall -> {
                            session.broadcast(WsMessage.ToolCall(
                                toolCallId = update.toolCallId.value,
                                title = update.title,
                                status = update.status.toToolStatus(),
                                kind = update.kind.toToolKind(),
                                location = update.locations.firstOrNull()?.path,
                            ))
                        }
                        is SessionUpdate.ToolCallUpdate -> {
                            session.broadcast(WsMessage.ToolCall(
                                toolCallId = update.toolCallId.value,
                                title = update.title ?: "",
                                status = update.status.toToolStatus(),
                                kind = update.kind.toToolKind(),
                                location = update.locations?.firstOrNull()?.path,
                            ))
                        }
                        else -> {}
                    }
                }
                is Event.PromptResponseEvent -> {
                    session.broadcast(WsMessage.TurnComplete(event.response.stopReason.name.lowercase()))
                }
            }
        }
    }
}
