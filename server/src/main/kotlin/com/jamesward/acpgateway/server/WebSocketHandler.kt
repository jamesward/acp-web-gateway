package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.jamesward.acpgateway.shared.ChatEntry
import com.jamesward.acpgateway.shared.PermissionOptionInfo
import com.jamesward.acpgateway.shared.WsMessage
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.PermissionOptionId
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebSocketHandler")
private val json = Json { ignoreUnknownKeys = true }
private val markdownParser = Parser.builder().build()
private val htmlRenderer = HtmlRenderer.builder().build()

private fun renderMarkdown(markdown: String): String {
    val document = markdownParser.parse(markdown)
    return htmlRenderer.render(document)
}

suspend fun WebSocketServerSession.handleChatWebSocket(session: GatewaySession, manager: AgentProcessManager) {
    while (!session.ready) {
        delay(100)
    }

    sendWsMessage(WsMessage.Connected(manager.agentName, manager.agentVersion, session.cwd))

    for (entry in session.history) {
        val msg = if (entry.role == "user") {
            WsMessage.Prompt(entry.content)
        } else {
            WsMessage.AgentText(entry.content)
        }
        sendWsMessage(msg)
    }

    coroutineScope {
        var promptJob: Job? = null

        launch {
            for (pending in session.clientOps.pendingPermissions) {
                val wsMsg = WsMessage.PermissionRequest(
                    toolCallId = pending.toolCallId,
                    title = pending.title,
                    options = pending.options.map { opt ->
                        PermissionOptionInfo(
                            optionId = opt.optionId.value,
                            name = opt.name,
                            kind = opt.kind.name.lowercase(),
                        )
                    },
                )
                sendWsMessage(wsMsg)
            }
        }

        incoming.consumeEach { frame ->
            if (frame is Frame.Text) {
                val text = frame.readText()
                val wsMsg = json.decodeFromString(WsMessage.serializer(), text)
                when (wsMsg) {
                    is WsMessage.Prompt -> {
                        promptJob = launch { handlePrompt(wsMsg, session) }
                    }
                    is WsMessage.Cancel -> {
                        session.cancelPrompt()
                        withTimeoutOrNull(5000) { promptJob?.join() }
                        promptJob?.cancel()
                        promptJob = null
                        session.activeToolCalls.clear()
                        session.promptStartTime.set(0L)
                        sendWsMessage(WsMessage.TurnComplete("cancelled"))
                    }
                    is WsMessage.Diagnose -> {
                        val diagnosticText = session.buildDiagnosticContext()
                        session.cancelPrompt()
                        withTimeoutOrNull(5000) { promptJob?.join() }
                        promptJob?.cancel()
                        promptJob = null
                        session.activeToolCalls.clear()
                        session.promptStartTime.set(0L)
                        sendWsMessage(WsMessage.TurnComplete("cancelled"))
                        promptJob = launch {
                            handlePrompt(WsMessage.Prompt(diagnosticText), session)
                        }
                    }
                    is WsMessage.PermissionResponse -> handlePermissionResponse(wsMsg, session)
                    else -> logger.warn("Unexpected message from browser: {}", wsMsg)
                }
            }
        }
    }
}

private suspend fun WebSocketServerSession.handlePrompt(
    prompt: WsMessage.Prompt,
    session: GatewaySession,
) {
    try {
        session.promptStartTime.set(System.currentTimeMillis())
        session.activeToolCalls.clear()

        val events = session.prompt(prompt.text, prompt.screenshot, prompt.files)
        val responseText = StringBuilder()

        events.collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    when (val update = event.update) {
                        is SessionUpdate.AgentMessageChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                responseText.append(content.text)
                                sendWsMessage(WsMessage.AgentText(content.text))
                            }
                        }
                        is SessionUpdate.AgentThoughtChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                sendWsMessage(WsMessage.AgentThought(content.text))
                            }
                        }
                        is SessionUpdate.ToolCall -> {
                            val tcId = update.toolCallId.value
                            val status = update.status?.name?.lowercase() ?: "pending"
                            session.activeToolCalls[tcId] = ToolCallInfo(
                                title = update.title,
                                status = status,
                                startTime = System.currentTimeMillis(),
                            )
                            sendWsMessage(WsMessage.ToolCall(
                                toolCallId = tcId,
                                title = update.title,
                                status = status,
                            ))
                        }
                        is SessionUpdate.ToolCallUpdate -> {
                            val tcId = update.toolCallId.value
                            val status = update.status?.name?.lowercase() ?: "in_progress"
                            val existing = session.activeToolCalls[tcId]
                            if (status == "completed" || status == "failed") {
                                session.activeToolCalls.remove(tcId)
                            } else {
                                session.activeToolCalls[tcId] = ToolCallInfo(
                                    title = update.title ?: existing?.title ?: "",
                                    status = status,
                                    startTime = existing?.startTime ?: System.currentTimeMillis(),
                                )
                            }
                            sendWsMessage(WsMessage.ToolCall(
                                toolCallId = tcId,
                                title = update.title ?: "",
                                status = status,
                            ))
                        }
                        else -> logger.debug("Unhandled session update: {}", update)
                    }
                }
                is Event.PromptResponseEvent -> {
                    val response = event.response
                    session.activeToolCalls.clear()
                    session.promptStartTime.set(0L)
                    if (responseText.isNotEmpty()) {
                        session.history.add(
                            ChatEntry(
                                role = "assistant",
                                content = responseText.toString(),
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    }
                    val rendered = if (responseText.isNotEmpty()) renderMarkdown(responseText.toString()) else null
                    sendWsMessage(WsMessage.TurnComplete(response.stopReason.name.lowercase(), rendered))
                }
            }
        }
    } catch (e: CancellationException) {
        session.activeToolCalls.clear()
        session.promptStartTime.set(0L)
        throw e
    } catch (e: Exception) {
        session.activeToolCalls.clear()
        session.promptStartTime.set(0L)
        logger.error("Error processing prompt", e)
        sendWsMessage(WsMessage.Error(e.message ?: "Unknown error"))
    }
}

private fun handlePermissionResponse(
    response: WsMessage.PermissionResponse,
    session: GatewaySession,
) {
    logger.info("Permission response: toolCallId={}, optionId={}", response.toolCallId, response.optionId)
    session.clientOps.completePermission(response.toolCallId, response.optionId)
}

private suspend fun WebSocketServerSession.sendWsMessage(msg: WsMessage) {
    val text = json.encodeToString(WsMessage.serializer(), msg)
    send(Frame.Text(text))
}
