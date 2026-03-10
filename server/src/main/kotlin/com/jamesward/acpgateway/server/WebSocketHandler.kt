package com.jamesward.acpgateway.server

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.jamesward.acpgateway.shared.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebSocketHandler")
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

private val markdownParser = Parser.builder().build()
private val htmlRenderer = HtmlRenderer.builder().build()

private data class ToolContent(val text: String? = null, val html: String? = null)

private fun extractToolContent(content: List<ToolCallContent>?): ToolContent? {
    if (content.isNullOrEmpty()) return null
    val textParts = mutableListOf<String>()
    val htmlParts = mutableListOf<String>()
    var hasDiff = false
    for (tc in content) {
        when (tc) {
            is ToolCallContent.Content -> {
                when (val block = tc.content) {
                    is ContentBlock.Text -> textParts.add(block.text)
                    else -> {}
                }
            }
            is ToolCallContent.Diff -> {
                hasDiff = true
                htmlParts.add(renderDiffHtml(tc.path, tc.oldText, tc.newText))
            }
            is ToolCallContent.Terminal -> textParts.add("Terminal: ${tc.terminalId}")
        }
    }
    if (textParts.isEmpty() && htmlParts.isEmpty()) return null
    val text = if (textParts.isNotEmpty()) {
        val joined = textParts.joinToString("\n")
        if (joined.length > 2000) joined.take(2000) + "..." else joined
    } else null
    val html = if (htmlParts.isNotEmpty()) htmlParts.joinToString("") else null
    return if (hasDiff) ToolContent(html = (html.orEmpty()) + (text?.let { "<pre>$it</pre>" }.orEmpty()))
    else ToolContent(text = text)
}

private fun renderDiffHtml(path: String, oldText: String?, newText: String): String {
    val oldLines = oldText?.lines() ?: emptyList()
    val newLines = newText.lines()
    val patch = DiffUtils.diff(oldLines, newLines)
    val fileName = path.substringAfterLast('/')
    val unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(fileName, fileName, oldLines, patch, 3)
    if (unifiedDiff.isEmpty()) return "<div>No changes</div>"
    val sb = StringBuilder()
    for (line in unifiedDiff) {
        val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val cls = when {
            line.startsWith("+++") || line.startsWith("---") -> "diff-hunk"
            line.startsWith("@@") -> "diff-hunk"
            line.startsWith("+") -> "diff-add"
            line.startsWith("-") -> "diff-del"
            else -> ""
        }
        if (cls.isNotEmpty()) {
            sb.append("<span class=\"$cls\">$escaped</span>\n")
        } else {
            sb.append("$escaped\n")
        }
    }
    return sb.toString()
}

private fun renderMarkdown(markdown: String): String {
    val document = markdownParser.parse(markdown)
    return htmlRenderer.render(document)
}

// Per-prompt tool block state
private class ServerToolBlockState(val blockId: String) {
    val entries = mutableListOf<ToolCallDisplay>()
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

suspend fun WebSocketServerSession.handleChatWebSocket(session: GatewaySession, manager: AgentProcessManager, autoPromptText: String? = null, debug: Boolean = false, commandHandler: CommandHandler? = null, internalCommands: List<CommandInfo> = emptyList()) {
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

    session.connections.add(this)

    try {
        val promptStartTime = session.store.getPromptStartTime(session.id)
        val isWorking = promptStartTime > 0L

        sendWsMessage(WsMessage.Connected(manager.agentName, manager.agentVersion, session.cwd, agentWorking = isWorking))

        // Send available commands (internal + agent-provided)
        if (session.allCommands.isNotEmpty()) {
            sendWsMessage(WsMessage.AvailableCommands(session.allCommands))
        }

        // Replay history as HTML fragments
        for (entry in session.store.getHistory(session.id)) {
            val html = if (entry.role == "user") {
                userMessageHtml(entry.content)
            } else {
                assistantRenderedHtml(renderMarkdown(entry.content))
            }
            sendWsMessage(WsMessage.HtmlUpdate(target = Css.MESSAGES, swap = Swap.BeforeEnd, html = html))
        }

        // Replay current turn's in-progress state for reconnecting clients
        if (isWorking) {
            val turnState = session.store.getTurnState(session.id)
            if (turnState != null) {
                // Re-create placeholders with the same IDs so future morphs target them
                for (placeholderId in listOf(turnState.thoughtId, turnState.msgId, turnState.toolBlockId)) {
                    sendWsMessage(WsMessage.HtmlUpdate(
                        target = Css.MESSAGES,
                        swap = Swap.BeforeEnd,
                        html = "<div id=\"$placeholderId\" class=\"${Css.HIDDEN}\"></div>",
                    ))
                }
                // Replay thought text
                if (turnState.thoughtText.isNotBlank()) {
                    val rendered = renderMarkdown(turnState.thoughtText)
                    sendWsMessage(WsMessage.HtmlUpdate(
                        target = turnState.thoughtId,
                        swap = Swap.Morph,
                        html = thoughtRenderedHtml(rendered, turnState.thoughtId),
                    ))
                }
                // Replay response text
                if (turnState.responseText.isNotBlank()) {
                    val rendered = renderMarkdown(turnState.responseText)
                    sendWsMessage(WsMessage.HtmlUpdate(
                        target = turnState.msgId,
                        swap = Swap.Morph,
                        html = assistantRenderedHtml(rendered, turnState.msgId),
                    ))
                }
                // Replay tool block
                if (turnState.toolEntries.isNotEmpty()) {
                    sendWsMessage(WsMessage.HtmlUpdate(
                        target = turnState.toolBlockId,
                        swap = Swap.Morph,
                        html = toolBlockHtml(turnState.toolEntries, turnState.toolBlockId),
                    ))
                }
            } else {
                // Fallback: replay active tool calls from store (pre-TurnState compat)
                val toolCalls = session.store.getToolCalls(session.id)
                if (toolCalls.isNotEmpty()) {
                    val entries = toolCalls.map { (id, info) ->
                        ToolCallDisplay(id = id, title = info.title, status = info.status)
                    }
                    sendWsMessage(WsMessage.HtmlUpdate(
                        target = Css.MESSAGES,
                        swap = Swap.BeforeEnd,
                        html = toolBlockHtml(entries),
                    ))
                }
            }
        }

        // Replay pending permission dialog if one is active
        val permHtml = session.activePermissionHtml
        if (permHtml != null) {
            sendWsMessage(WsMessage.HtmlUpdate(target = Id.PERMISSION_CONTENT, swap = Swap.InnerHTML, html = permHtml))
            sendWsMessage(WsMessage.HtmlUpdate(target = Id.PERMISSION_DIALOG, swap = Swap.Show, html = ""))
        }

        if (autoPromptText != null) {
            session.promptJob = session.scope.launch {
                handlePrompt(WsMessage.Prompt(autoPromptText), session, debug)
            }
        }

        incoming.consumeEach { frame ->
            if (frame is Frame.Text) {
                val text = frame.readText()
                val wsMsg = json.decodeFromString(WsMessage.serializer(), text)
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
                        session.activePermissionHtml = null
                        session.store.clearToolCalls(session.id)
                        session.store.setPromptStartTime(session.id, 0L)
                        session.store.clearTurnState(session.id)
                        session.broadcast(WsMessage.TurnComplete("cancelled"))
                    }
                    is WsMessage.Diagnose -> {
                        val diagnosticText = session.buildDiagnosticContext()
                        session.cancelPrompt()
                        withTimeoutOrNull(5000) { session.promptJob?.join() }
                        session.promptJob?.cancel()
                        session.promptJob = null
                        session.activePermissionHtml = null
                        session.store.clearToolCalls(session.id)
                        session.store.setPromptStartTime(session.id, 0L)
                        session.store.clearTurnState(session.id)
                        session.broadcast(WsMessage.TurnComplete("cancelled"))
                        session.promptJob = session.scope.launch {
                            handlePrompt(WsMessage.Prompt(diagnosticText), session, debug)
                        }
                    }
                    is WsMessage.PermissionResponse -> {
                        handlePermissionResponse(wsMsg, session)
                        session.activePermissionHtml = null
                        session.broadcast(WsMessage.HtmlUpdate(target = Id.PERMISSION_DIALOG, swap = Swap.Hide, html = ""))
                    }
                    is WsMessage.BrowserStateResponse -> {
                        session.clientOps.completeBrowserState(wsMsg.requestId, wsMsg.state)
                    }
                    else -> logger.warn("Unexpected message from browser: {}", wsMsg)
                }
            }
        }
    } finally {
        session.connections.remove(this)
        // Do NOT cancel promptJob — agent continues working even when all clients disconnect
    }
}

@OptIn(UnstableApi::class)
private suspend fun handlePrompt(
    prompt: WsMessage.Prompt,
    session: GatewaySession,
    debug: Boolean = false,
    commandHandler: CommandHandler? = null,
) {
    try {
        session.store.setPromptStartTime(session.id, System.currentTimeMillis())
        session.store.clearToolCalls(session.id)

        // Allow command handler to intercept slash-commands and modify the prompt
        val effectivePrompt = if (commandHandler != null && prompt.text.trim().startsWith("/")) {
            commandHandler(prompt, session) ?: prompt
        } else {
            prompt
        }

        // Send user message HTML to all clients
        session.broadcast(WsMessage.HtmlUpdate(
            target = Css.MESSAGES,
            swap = Swap.BeforeEnd,
            html = userMessageHtml(prompt.text),
        ))

        val events = if (debug && effectivePrompt.text.trim() == "/simulate") {
            buildSimulationResponse()()
        } else {
            session.prompt(effectivePrompt.text, effectivePrompt.screenshot, effectivePrompt.files)
        }
        val responseText = StringBuilder()
        val thoughtText = StringBuilder()
        val turnCounter = System.currentTimeMillis()
        val toolBlock = ServerToolBlockState(blockId = "tools-$turnCounter")
        val usage = UsageState()
        val msgId = "msg-$turnCounter"
        val thoughtId = "thought-$turnCounter"

        // Store initial turn state so reconnecting clients get the IDs
        session.store.setTurnState(session.id, TurnState(
            thoughtId = thoughtId,
            msgId = msgId,
            toolBlockId = toolBlock.blockId,
            thoughtText = "",
            responseText = "",
            toolEntries = emptyList(),
        ))

        // Pre-create ordered placeholders so thought → tools → message order is guaranteed
        for (placeholderId in listOf(thoughtId, msgId, toolBlock.blockId)) {
            session.broadcast(WsMessage.HtmlUpdate(
                target = Css.MESSAGES,
                swap = Swap.BeforeEnd,
                html = "<div id=\"$placeholderId\" class=\"${Css.HIDDEN}\"></div>",
            ))
        }

        events.collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    when (val update = event.update) {
                        is SessionUpdate.AgentMessageChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                responseText.append(content.text)
                                if (responseText.isNotBlank()) {
                                    val rendered = renderMarkdown(responseText.toString())
                                    val usageStr = usage.formatUsage()
                                    val html = assistantRenderedHtml(rendered, msgId, usageStr)
                                    session.broadcast(WsMessage.HtmlUpdate(
                                        target = msgId,
                                        swap = Swap.Morph,
                                        html = html,
                                    ))
                                    updateTurnState(session, thoughtId, msgId, toolBlock, thoughtText, responseText)
                                }
                            }
                        }
                        is SessionUpdate.AgentThoughtChunk -> {
                            val content = update.content
                            if (content is ContentBlock.Text) {
                                thoughtText.append(content.text)
                                if (thoughtText.isNotBlank()) {
                                    val rendered = renderMarkdown(thoughtText.toString())
                                    val usageStr = usage.formatUsage()
                                    val html = thoughtRenderedHtml(rendered, thoughtId, usageStr)
                                    session.broadcast(WsMessage.HtmlUpdate(
                                        target = thoughtId,
                                        swap = Swap.Morph,
                                        html = html,
                                    ))
                                    updateTurnState(session, thoughtId, msgId, toolBlock, thoughtText, responseText)
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
                            toolBlock.entries.add(ToolCallDisplay(
                                id = tcId,
                                title = update.title,
                                status = status,
                                content = toolContent?.text ?: "",
                                contentHtml = toolContent?.html ?: "",
                                kind = update.kind.toToolKind(),
                                location = update.locations.firstOrNull()?.path,
                            ))
                            sendToolBlockUpdate(toolBlock, session)
                            updateTurnState(session, thoughtId, msgId, toolBlock, thoughtText, responseText)
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
                            val entry = toolBlock.entries.find { it.id == tcId }
                            if (entry != null) {
                                val idx = toolBlock.entries.indexOf(entry)
                                val updateContent = extractToolContent(update.content)
                                toolBlock.entries[idx] = entry.copy(
                                    title = if (update.title.isNullOrEmpty()) entry.title else update.title!!,
                                    status = status,
                                    content = updateContent?.text ?: entry.content,
                                    contentHtml = updateContent?.html ?: entry.contentHtml,
                                    kind = update.kind.toToolKind() ?: entry.kind,
                                    location = update.locations?.firstOrNull()?.path ?: entry.location,
                                )
                            }
                            sendToolBlockUpdate(toolBlock, session)
                            updateTurnState(session, thoughtId, msgId, toolBlock, thoughtText, responseText)
                        }
                        is SessionUpdate.UsageUpdate -> {
                            usage.used = update.used
                            usage.size = update.size
                            val cost = update.cost
                            if (cost != null) {
                                usage.cost = CostInfo(cost.amount, cost.currency)
                            }
                            // Re-render response/thought headers with updated usage
                            val usageStr = usage.formatUsage()
                            if (responseText.isNotBlank()) {
                                val rendered = renderMarkdown(responseText.toString())
                                session.broadcast(WsMessage.HtmlUpdate(
                                    target = msgId,
                                    swap = Swap.Morph,
                                    html = assistantRenderedHtml(rendered, msgId, usageStr),
                                ))
                            }
                            if (thoughtText.isNotBlank()) {
                                val rendered = renderMarkdown(thoughtText.toString())
                                session.broadcast(WsMessage.HtmlUpdate(
                                    target = thoughtId,
                                    swap = Swap.Morph,
                                    html = thoughtRenderedHtml(rendered, thoughtId, usageStr),
                                ))
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
                    session.store.clearToolCalls(session.id)
                    session.store.setPromptStartTime(session.id, 0L)
                    session.store.clearTurnState(session.id)
                    if (responseText.isNotEmpty()) {
                        session.store.addHistory(session.id,
                            ChatEntry(
                                role = "assistant",
                                content = responseText.toString(),
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                        // Send final rendered markdown with usage info
                        val rendered = renderMarkdown(responseText.toString())
                        val usageStr = usage.formatUsage()
                        val html = assistantRenderedHtml(rendered, msgId, usageStr)
                        session.broadcast(WsMessage.HtmlUpdate(
                            target = msgId,
                            swap = Swap.Morph,
                            html = html,
                        ))
                    }
                    session.broadcast(WsMessage.TurnComplete(response.stopReason.name.lowercase()))
                }
            }
        }
    } catch (e: CancellationException) {
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        throw e
    } catch (e: Exception) {
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        logger.error("Error processing prompt", e)
        session.broadcast(WsMessage.HtmlUpdate(
            target = Css.MESSAGES,
            swap = Swap.BeforeEnd,
            html = errorMessageHtml(e.message ?: "Unknown error"),
        ))
        session.broadcast(WsMessage.TurnComplete("error"))
    }
}

private suspend fun updateTurnState(
    session: GatewaySession,
    thoughtId: String,
    msgId: String,
    toolBlock: ServerToolBlockState,
    thoughtText: StringBuilder,
    responseText: StringBuilder,
) {
    session.store.setTurnState(session.id, TurnState(
        thoughtId = thoughtId,
        msgId = msgId,
        toolBlockId = toolBlock.blockId,
        thoughtText = thoughtText.toString(),
        responseText = responseText.toString(),
        toolEntries = toolBlock.entries.toList(),
    ))
}

private suspend fun sendToolBlockUpdate(toolBlock: ServerToolBlockState, session: GatewaySession) {
    val html = toolBlockHtml(toolBlock.entries, toolBlock.blockId)
    session.broadcast(WsMessage.HtmlUpdate(
        target = toolBlock.blockId,
        swap = Swap.Morph,
        html = html,
    ))
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
