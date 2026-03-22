package com.jamesward.acpgateway.shared

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebSocketHandler")

/** Thrown when a ChangeAgent message is received, signaling the caller to switch agents. */
class AgentSwitchException(val agentId: String) : Exception("Switch to agent: $agentId")
private val json = Json { ignoreUnknownKeys = true }

// ---- ACP SDK → shared enum mappings ----

fun ToolCallStatus?.toToolStatus(): ToolStatus = when (this) {
    ToolCallStatus.COMPLETED -> ToolStatus.Completed
    ToolCallStatus.FAILED -> ToolStatus.Failed
    ToolCallStatus.IN_PROGRESS -> ToolStatus.InProgress
    ToolCallStatus.PENDING, null -> ToolStatus.Pending
}

fun com.agentclientprotocol.model.ToolKind?.toToolKind(): ToolKind? = when (this) {
    com.agentclientprotocol.model.ToolKind.READ -> ToolKind.Read
    com.agentclientprotocol.model.ToolKind.EDIT -> ToolKind.Edit
    com.agentclientprotocol.model.ToolKind.DELETE -> ToolKind.Delete
    com.agentclientprotocol.model.ToolKind.MOVE -> ToolKind.Move
    com.agentclientprotocol.model.ToolKind.SEARCH -> ToolKind.Search
    com.agentclientprotocol.model.ToolKind.EXECUTE -> ToolKind.Execute
    com.agentclientprotocol.model.ToolKind.THINK -> ToolKind.Think
    com.agentclientprotocol.model.ToolKind.FETCH -> ToolKind.Fetch
    com.agentclientprotocol.model.ToolKind.SWITCH_MODE -> ToolKind.SwitchMode
    com.agentclientprotocol.model.ToolKind.OTHER -> ToolKind.Other
    null -> null
}

fun PermissionOptionKind.toGatewayKind(): PermissionKind = when (this) {
    PermissionOptionKind.ALLOW_ONCE -> PermissionKind.AllowOnce
    PermissionOptionKind.ALLOW_ALWAYS -> PermissionKind.AllowAlways
    PermissionOptionKind.REJECT_ONCE -> PermissionKind.RejectOnce
    PermissionOptionKind.REJECT_ALWAYS -> PermissionKind.RejectAlways
}

private data class ToolContent(val text: String? = null)

private fun extractToolContent(content: List<ToolCallContent>?): ToolContent? {
    if (content.isNullOrEmpty()) return null
    val textParts = mutableListOf<String>()
    for (tc in content) {
        when (tc) {
            is ToolCallContent.Content -> {
                when (val block = tc.content) {
                    is ContentBlock.Text -> textParts.add(block.text)
                    else -> {}
                }
            }
            is ToolCallContent.Diff -> {
                textParts.add(renderDiffMarkdown(tc.path, tc.oldText, tc.newText))
            }
            is ToolCallContent.Terminal -> textParts.add("Terminal: ${tc.terminalId}")
        }
    }
    if (textParts.isEmpty()) return null
    val joined = textParts.joinToString("\n")
    val text = if (joined.length > 2000) joined.take(2000) + "..." else joined
    return ToolContent(text = text)
}

internal fun renderDiffMarkdown(path: String, oldText: String?, newText: String): String {
    val oldLines = oldText?.lines() ?: emptyList()
    val newLines = newText.lines()
    val patch = DiffUtils.diff(oldLines, newLines)
    val fileName = path.substringAfterLast('/')
    val unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(fileName, fileName, oldLines, patch, 3)
    if (unifiedDiff.isEmpty()) return "No changes"
    return "```diff\n${unifiedDiff.joinToString("\n")}\n```"
}

// Per-prompt usage tracking
private data class CostInfo(val amount: Double, val currency: String)

private class UsageState {
    var used: Long = 0L
    var size: Long = 0L
    var cost: CostInfo? = null

    fun formatUsage(): String? {
        if (size == 0L) return null
        val pct = if (size > 0) (used * 100.0 / size) else 0.0
        val usedK = "%.0f".format(used / 1000.0)
        val sizeK = "%.0f".format(size / 1000.0)
        val base = "${usedK}K / ${sizeK}K tokens (${"%.0f".format(pct)}%)"
        val c = cost
        return if (c != null && c.amount > 0) {
            val symbol = if (c.currency == "USD") "$" else c.currency
            "$base \u00b7 $symbol${"%.2f".format(c.amount)}"
        } else base
    }
}

/**
 * A command handler intercepts slash-commands (e.g. `/autopilot`) before they reach the agent.
 * Return a modified [WsMessage.Prompt] to send to the agent instead, or `null` to use the original prompt.
 */
typealias CommandHandler = suspend (prompt: WsMessage.Prompt, session: GatewaySession) -> WsMessage.Prompt?

/**
 * Core chat handler that works with typed channels instead of raw WebSocket.
 * Used by Kilua RPC's ChatServiceImpl and by the WebSocket wrapper.
 */
suspend fun handleChatChannels(
    input: ReceiveChannel<WsMessage>,
    output: SendChannel<WsMessage>,
    session: GatewaySession,
    manager: AgentProcessManager,
    autoPromptText: String? = null,
    debug: Boolean = false,
    commandHandler: CommandHandler? = null,
    internalCommands: List<CommandInfo> = emptyList(),
    availableAgents: List<AgentInfo> = emptyList(),
    currentAgentId: String? = null,
) {
    while (!session.ready) {
        delay(100)
    }

    // Seed internal commands on first connect (idempotent — same list every time)
    if (session.internalCommands.isEmpty()) {
        session.internalCommands = buildList {
            if (debug) add(CommandInfo("simulate", "Run a simulated agent response for UI testing"))
            addAll(internalCommands)
        }
    }

    // Check for ResumeFrom as first message (with short timeout)
    var resumeFromSeq = 0L
    var pendingMessage: WsMessage? = null
    val firstMsg = withTimeoutOrNull(500) { input.receiveCatching().getOrNull() }
    if (firstMsg is WsMessage.ResumeFrom) {
        resumeFromSeq = firstMsg.lastSeq
        logger.info("Client resuming from seq={}", resumeFromSeq)
    } else if (firstMsg != null) {
        // Not a ResumeFrom — save it so we don't lose it
        pendingMessage = firstMsg
    }

    try {
        val promptStartTime = session.store.getPromptStartTime(session.id)
        val isWorking = promptStartTime > 0L

        output.send(WsMessage.Connected(manager.agentName, manager.agentVersion, session.cwd, agentWorking = isWorking, seq = session.currentSeq))

        if (availableAgents.isNotEmpty()) {
            output.send(WsMessage.AvailableAgents(availableAgents, currentAgentId))
        }

        // Send available commands (internal + agent-provided)
        if (session.allCommands.isNotEmpty()) {
            output.send(WsMessage.AvailableCommands(session.allCommands))
        }

        // Check if we can do a delta resume from the turn buffer
        val bufferedMessages = if (resumeFromSeq > 0) session.getTurnBufferSince(resumeFromSeq) else emptyList()
        val canResume = resumeFromSeq > 0 && bufferedMessages.isNotEmpty()

        if (canResume) {
            // Delta resume: replay only missed messages from the turn buffer
            for (seqMsg in bufferedMessages) {
                output.send(seqMsg.message)
            }
        } else {
            // Full replay: send complete history
            for (entry in session.store.getHistory(session.id)) {
                if (entry.role == "user") {
                    output.send(WsMessage.UserMessage(entry.content, fileNames = entry.fileNames ?: emptyList()))
                } else {
                    if (entry.thought != null) {
                        output.send(WsMessage.AgentThought(thoughtId = "history-thought-${entry.timestamp}", markdown = entry.thought))
                    }
                    if (entry.toolCalls != null) {
                        for (tc in entry.toolCalls) {
                            output.send(WsMessage.ToolCall(
                                toolCallId = tc.id,
                                title = tc.title,
                                status = tc.status,
                                content = tc.content.ifEmpty { null },
                                contentHtml = tc.contentHtml.ifEmpty { null },
                                kind = tc.kind,
                                location = tc.location,
                            ))
                        }
                    }
                    if (entry.content.isNotEmpty()) {
                        output.send(WsMessage.AgentText(msgId = "history-${entry.timestamp}", markdown = entry.content, usage = entry.usage))
                    }
                    output.send(WsMessage.TurnComplete("end_turn"))
                }
            }

            // Replay current turn's in-progress state for reconnecting clients (full replay only)
            if (isWorking) {
                val turnState = session.store.getTurnState(session.id)
                if (turnState != null) {
                    if (turnState.thoughtText.isNotBlank()) {
                        output.send(WsMessage.AgentThought(thoughtId = turnState.thoughtId, markdown = turnState.thoughtText))
                    }
                    if (turnState.responseText.isNotBlank()) {
                        output.send(WsMessage.AgentText(msgId = turnState.msgId, markdown = turnState.responseText))
                    }
                    for (toolEntry in turnState.toolEntries) {
                        output.send(WsMessage.ToolCall(
                            toolCallId = toolEntry.id,
                            title = toolEntry.title,
                            status = toolEntry.status,
                            content = toolEntry.content.ifEmpty { null },
                            contentHtml = toolEntry.contentHtml.ifEmpty { null },
                            kind = toolEntry.kind,
                            location = toolEntry.location,
                        ))
                    }
                } else {
                    val toolCalls = session.store.getToolCalls(session.id)
                    for ((id, info) in toolCalls) {
                        output.send(WsMessage.ToolCall(toolCallId = id, title = info.title, status = info.status))
                    }
                }
            }
        }

        // Replay pending permission dialog
        val pendingPerm = session.activePermission
        if (pendingPerm != null) {
            output.send(pendingPerm)
        }

        // Add to broadcast set AFTER replay to avoid interleaving live broadcasts with history
        session.connections.add(output)

        // Check if the prompt completed during replay — if so, send the missed TurnComplete
        val stillWorking = session.store.getPromptStartTime(session.id) > 0L
        if (isWorking && !stillWorking) {
            output.send(WsMessage.TurnComplete("end_turn"))
        }

        if (autoPromptText != null) {
            session.promptJob = session.scope.launch {
                handlePrompt(WsMessage.Prompt(autoPromptText), session, debug)
            }
        }

        // Process any message that was read during ResumeFrom check
        if (pendingMessage != null) {
            handleClientMessage(pendingMessage, session, debug, commandHandler, output)
        }

        for (wsMsg in input) {
            handleClientMessage(wsMsg, session, debug, commandHandler, output)
        }
    } finally {
        session.connections.remove(output)
    }
}

/**
 * WebSocket wrapper for [handleChatChannels]. Bridges raw WebSocket frames
 * to/from typed channels. Used by CLI and simulation server.
 */
suspend fun WebSocketSession.handleChatWebSocket(
    session: GatewaySession,
    manager: AgentProcessManager,
    autoPromptText: String? = null,
    debug: Boolean = false,
    commandHandler: CommandHandler? = null,
    internalCommands: List<CommandInfo> = emptyList(),
) {
    val output = Channel<WsMessage>(Channel.UNLIMITED)
    val input = Channel<WsMessage>(Channel.UNLIMITED)

    coroutineScope {
        // Forward output channel messages to WebSocket frames
        launch {
            try {
                for (msg in output) {
                    val text = json.encodeToString(WsMessage.serializer(), msg)
                    send(Frame.Text(text))
                }
            } catch (_: Exception) {}
        }
        // Forward incoming WebSocket frames to input channel
        launch {
            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val wsMsg = json.decodeFromString(WsMessage.serializer(), text)
                        input.send(wsMsg)
                    }
                }
            } catch (_: Exception) {
            } finally {
                input.close()
            }
        }
        // Run the core channel-based handler
        launch {
            try {
                handleChatChannels(input, output, session, manager, autoPromptText, debug, commandHandler, internalCommands)
            } finally {
                output.close()
            }
        }
    }
}

private suspend fun handleClientMessage(
    wsMsg: WsMessage,
    session: GatewaySession,
    debug: Boolean,
    commandHandler: CommandHandler?,
    output: SendChannel<WsMessage>,
) {
    when (wsMsg) {
        is WsMessage.Prompt -> {
            logger.info("Received prompt: screenshot={}, files={}", wsMsg.screenshot != null, wsMsg.files.size)
            session.promptJob = session.scope.launch { handlePrompt(wsMsg, session, debug, commandHandler) }
        }
        is WsMessage.Cancel -> {
            session.cancelPrompt()
            withTimeoutOrNull(5000) { session.promptJob?.join() }
            session.promptJob?.cancel()
            session.promptJob = null
            session.activePermission = null
            session.store.clearToolCalls(session.id)
            session.store.setPromptStartTime(session.id, 0L)
            session.store.clearTurnState(session.id)
            session.broadcast(WsMessage.TurnComplete("cancelled"))
            session.clearTurnBuffer()
        }
        is WsMessage.Diagnose -> {
            val diagnosticText = session.buildDiagnosticContext()
            session.cancelPrompt()
            withTimeoutOrNull(5000) { session.promptJob?.join() }
            session.promptJob?.cancel()
            session.promptJob = null
            session.activePermission = null
            session.store.clearToolCalls(session.id)
            session.store.setPromptStartTime(session.id, 0L)
            session.store.clearTurnState(session.id)
            session.broadcast(WsMessage.TurnComplete("cancelled"))
            session.clearTurnBuffer()
            session.promptJob = session.scope.launch {
                handlePrompt(WsMessage.Prompt(diagnosticText), session, debug)
            }
        }
        is WsMessage.PermissionResponse -> {
            handlePermissionResponse(wsMsg, session)
            session.activePermission = null
            session.broadcast(wsMsg)
        }
        is WsMessage.BrowserStateResponse -> {
            session.clientOps.completeBrowserState(wsMsg.requestId, wsMsg.state)
        }
        is WsMessage.ChangeAgent -> {
            logger.info("Received ChangeAgent: {}", wsMsg.agentId)
            session.cancelPrompt()
            withTimeoutOrNull(5000) { session.promptJob?.join() }
            session.promptJob?.cancel()
            session.promptJob = null
            session.store.clearToolCalls(session.id)
            session.store.setPromptStartTime(session.id, 0L)
            session.store.clearTurnState(session.id)
            session.clearTurnBuffer()
            throw AgentSwitchException(wsMsg.agentId)
        }
        is WsMessage.FileListRequest -> {
            val files = listProjectFiles(session.cwd, wsMsg.query)
            output.send(WsMessage.FileListResponse(files))
        }
        is WsMessage.ResumeFrom -> {
            logger.debug("Ignoring ResumeFrom mid-session: lastSeq={}", wsMsg.lastSeq)
        }
        else -> logger.warn("Unexpected message from browser: {}", wsMsg)
    }
}

@OptIn(UnstableApi::class)
private suspend fun handlePrompt(
    prompt: WsMessage.Prompt,
    session: GatewaySession,
    debug: Boolean = false,
    commandHandler: CommandHandler? = null,
) {
    session.promptMutex.withLock {
    try {
        session.store.setPromptStartTime(session.id, System.currentTimeMillis())
        session.store.clearToolCalls(session.id)

        val effectivePrompt = if (commandHandler != null && prompt.text.trim().startsWith("/")) {
            commandHandler(prompt, session) ?: prompt
        } else {
            prompt
        }

        // Send user message to all clients
        val fileNames = prompt.files.map { it.name } + prompt.resourceLinks.map { it.name }
        session.broadcast(WsMessage.UserMessage(prompt.text, fileNames = fileNames))

        val events = if (debug && effectivePrompt.text.trim() == "/simulate") {
            buildSimulationResponse()()
        } else {
            session.prompt(effectivePrompt.text, effectivePrompt.screenshot, effectivePrompt.files, effectivePrompt.resourceLinks)
        }
        val responseText = StringBuilder()
        val thoughtText = StringBuilder()
        val turnCounter = System.currentTimeMillis()
        val toolEntries = mutableListOf<ToolCallDisplay>()
        val usage = UsageState()
        var stopReason: String? = null
        val msgId = "msg-$turnCounter"
        val thoughtId = "thought-$turnCounter"
        val toolBlockId = "tools-$turnCounter"

        session.store.setTurnState(session.id, TurnState(
            thoughtId = thoughtId,
            msgId = msgId,
            toolBlockId = toolBlockId,
            thoughtText = "",
            responseText = "",
            toolEntries = emptyList(),
        ))

        events.collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    when (val update = event.update) {
                        is SessionUpdate.AgentMessageChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                responseText.append(content.text)
                                if (responseText.isNotBlank()) {
                                    val usageStr = usage.formatUsage()
                                    // Send only the new chunk (delta), not full accumulated text
                                    session.broadcast(WsMessage.AgentText(msgId = msgId, markdown = content.text, usage = usageStr))
                                    updateTurnState(session, thoughtId, msgId, toolBlockId, toolEntries, thoughtText, responseText)
                                }
                            }
                        }
                        is SessionUpdate.AgentThoughtChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                thoughtText.append(content.text)
                                if (thoughtText.isNotBlank()) {
                                    val usageStr = usage.formatUsage()
                                    // Send only the new chunk (delta), not full accumulated text
                                    session.broadcast(WsMessage.AgentThought(thoughtId = thoughtId, markdown = content.text, usage = usageStr))
                                    updateTurnState(session, thoughtId, msgId, toolBlockId, toolEntries, thoughtText, responseText)
                                }
                            }
                        }
                        is SessionUpdate.ToolCall -> {
                            val tcId = update.toolCallId.value
                            val status = update.status.toToolStatus()
                            session.store.setToolCall(session.id, tcId, ToolCallInfo(
                                title = update.title,
                                status = status,
                                startTime = System.currentTimeMillis(),
                            ))
                            val toolContent = extractToolContent(update.content)
                            toolEntries.add(ToolCallDisplay(
                                id = tcId,
                                title = update.title,
                                status = status,
                                content = toolContent?.text ?: "",
                                kind = update.kind.toToolKind(),
                                location = update.locations.firstOrNull()?.path,
                            ))
                            session.broadcast(WsMessage.ToolCall(
                                toolCallId = tcId,
                                title = update.title,
                                status = status,
                                content = toolContent?.text,
                                kind = update.kind.toToolKind(),
                                location = update.locations.firstOrNull()?.path,
                            ))
                            updateTurnState(session, thoughtId, msgId, toolBlockId, toolEntries, thoughtText, responseText)
                        }
                        is SessionUpdate.ToolCallUpdate -> {
                            val tcId = update.toolCallId.value
                            val status = update.status.toToolStatus()
                            val existing = session.store.getToolCalls(session.id)[tcId]
                            if (status.isTerminal) {
                                session.store.removeToolCall(session.id, tcId)
                            } else {
                                session.store.setToolCall(session.id, tcId, ToolCallInfo(
                                    title = update.title ?: existing?.title ?: "",
                                    status = status,
                                    startTime = existing?.startTime ?: System.currentTimeMillis(),
                                ))
                            }
                            val entry = toolEntries.find { it.id == tcId }
                            if (entry != null) {
                                val idx = toolEntries.indexOf(entry)
                                val updateContent = extractToolContent(update.content)
                                toolEntries[idx] = entry.copy(
                                    title = if (update.title.isNullOrEmpty()) entry.title else update.title!!,
                                    status = status,
                                    content = updateContent?.text ?: entry.content,
                                    kind = update.kind.toToolKind() ?: entry.kind,
                                    location = update.locations?.firstOrNull()?.path ?: entry.location,
                                )
                            }
                            val resolvedTitle = if (update.title.isNullOrEmpty()) entry?.title ?: "" else update.title!!
                            val updateContent = extractToolContent(update.content)
                            session.broadcast(WsMessage.ToolCall(
                                toolCallId = tcId,
                                title = resolvedTitle,
                                status = status,
                                content = updateContent?.text ?: entry?.content?.ifEmpty { null },
                                kind = update.kind.toToolKind() ?: entry?.kind,
                                location = update.locations?.firstOrNull()?.path ?: entry?.location,
                            ))
                            updateTurnState(session, thoughtId, msgId, toolBlockId, toolEntries, thoughtText, responseText)
                        }
                        is SessionUpdate.UsageUpdate -> {
                            usage.used = update.used
                            usage.size = update.size
                            val cost = update.cost
                            if (cost != null) {
                                usage.cost = CostInfo(cost.amount, cost.currency)
                            }
                            val usageStr = usage.formatUsage()
                            // Send empty delta with updated usage
                            if (responseText.isNotBlank()) {
                                session.broadcast(WsMessage.AgentText(msgId = msgId, markdown = "", usage = usageStr))
                            }
                            if (thoughtText.isNotBlank()) {
                                session.broadcast(WsMessage.AgentThought(thoughtId = thoughtId, markdown = "", usage = usageStr))
                            }
                        }
                        is SessionUpdate.AvailableCommandsUpdate -> {
                            val commands = update.availableCommands.map { cmd ->
                                CommandInfo(
                                    name = cmd.name,
                                    description = cmd.description,
                                    inputHint = (cmd.input as? AvailableCommandInput.Unstructured)?.hint,
                                )
                            }
                            session.availableCommands = commands
                            session.broadcast(WsMessage.AvailableCommands(session.allCommands))
                        }
                        else -> logger.debug("Unhandled session update: {}", update)
                    }
                }
                is Event.PromptResponseEvent -> {
                    val response = event.response
                    stopReason = response.stopReason.name.lowercase()
                    val usageStr = usage.formatUsage()
                    session.store.addHistory(session.id,
                        ChatEntry(
                            role = "assistant",
                            content = responseText.toString(),
                            thought = thoughtText.toString().ifEmpty { null },
                            toolCalls = toolEntries.toList().ifEmpty { null },
                            usage = usageStr,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
        // Flow is fully consumed — now safe to signal turn complete and release the mutex
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        session.broadcast(WsMessage.TurnComplete(stopReason ?: "end_turn"))
        session.clearTurnBuffer()
    } catch (e: CancellationException) {
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        session.clearTurnBuffer()
        throw e
    } catch (e: Exception) {
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        logger.error("Error processing prompt", e)
        session.broadcast(WsMessage.Error(e.message ?: "Unknown error"))
        session.broadcast(WsMessage.TurnComplete("error"))
        session.clearTurnBuffer()
    }
    } // promptMutex.withLock
}

private suspend fun updateTurnState(
    session: GatewaySession,
    thoughtId: String,
    msgId: String,
    toolBlockId: String,
    toolEntries: List<ToolCallDisplay>,
    thoughtText: StringBuilder,
    responseText: StringBuilder,
) {
    session.store.setTurnState(session.id, TurnState(
        thoughtId = thoughtId,
        msgId = msgId,
        toolBlockId = toolBlockId,
        thoughtText = thoughtText.toString(),
        responseText = responseText.toString(),
        toolEntries = toolEntries.toList(),
    ))
}

private fun handlePermissionResponse(
    response: WsMessage.PermissionResponse,
    session: GatewaySession,
) {
    logger.info("Permission response: toolCallId={}, optionId={}", response.toolCallId, response.optionId)
    session.clientOps.completePermission(response.toolCallId, response.optionId)
}
