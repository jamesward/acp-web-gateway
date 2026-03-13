@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jamesward.acpgateway.web

import androidx.compose.runtime.*
import com.jamesward.acpgateway.shared.*
import dev.kilua.Application
import dev.kilua.compose.root
import dev.kilua.core.IComponent
import dev.kilua.form.text.textArea
import dev.kilua.html.*
import dev.kilua.rpc.getService
import dev.kilua.startApplication
import io.ktor.client.engine.js.*
import io.ktor.client.request.*
import js.typedarrays.Uint8Array
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import web.blob.arrayBuffer
import web.console.console
import web.dom.document
import web.file.File
import web.html.HTMLCanvasElement
import web.html.HTMLElement
import web.location.location
import kotlin.js.Promise


/** Required by Kilua RPC to set the WebSocket URL prefix */
fun setRpcUrlPrefix(prefix: String): Unit = js("globalThis.rpc_url_prefix = prefix")

// ---- Console capture & browser state collection (JS bridges) ----

/** Install console.log/warn/error interceptors, buffering last 50 entries. */
@JsFun("""() => {
    if (globalThis.__consoleCaptured) return;
    globalThis.__consoleCaptured = true;
    globalThis.__consoleBuffer = [];
    const MAX = 50;
    ['log','warn','error'].forEach(level => {
        const orig = console[level].bind(console);
        console[level] = function() {
            const args = Array.from(arguments).map(a => {
                try { return typeof a === 'string' ? a : JSON.stringify(a); } catch(e) { return String(a); }
            }).join(' ');
            globalThis.__consoleBuffer.push({ ts: new Date().toISOString(), level: level, msg: args });
            if (globalThis.__consoleBuffer.length > MAX) globalThis.__consoleBuffer.shift();
            orig.apply(console, arguments);
        };
    });
}""")
private external fun installConsoleCapture()

/** Return captured console entries as JSON string. */
@JsFun("""() => {
    return JSON.stringify(globalThis.__consoleBuffer || []);
}""")
private external fun getConsoleLogs(): String

/** Collect DOM state summary as JSON string. */
@JsFun("""() => {
    const msgs = document.getElementById('messages');
    const msgCount = msgs ? msgs.children.length : -1;
    const permDialog = document.querySelector('.permission-overlay');
    const state = {
        messageCount: msgCount,
        viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight,
        permissionDialogVisible: permDialog !== null,
        title: document.title,
        bodyClasses: document.body.className,
        url: location.href
    };
    return JSON.stringify(state);
}""")
private external fun getDomState(): String

/** Check if #messages is scrolled to (near) the bottom. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (!el) return true;
    return (el.scrollHeight - el.scrollTop - el.clientHeight) < 40;
}""")
private external fun isMessagesAtBottom(): Boolean

/** Scroll #messages to the bottom. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (el) el.scrollTop = el.scrollHeight;
}""")
private external fun scrollMessagesToBottom()

/** Trigger a client-side file download with the given filename and text content. */
@JsFun("""(filename, content) => {
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}""")
private external fun downloadTextFile(filename: String, content: String)

/** Install a scroll listener on #messages that tracks atBottom in a JS global. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (!el) return;
    globalThis.__messagesAtBottom = true;
    el.addEventListener('scroll', () => {
        globalThis.__messagesAtBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) < 40;
    });
}""")
private external fun installScrollListener()

/** Read the current atBottom state from the JS global. */
@JsFun("() => { return globalThis.__messagesAtBottom !== false; }")
private external fun readScrollAtBottom(): Boolean

/** Opens a file picker dialog and returns the selected FileList via a Promise. */
@JsFun("""(multiple) => new Promise(function(resolve) {
    var input = document.createElement('input');
    input.type = 'file';
    input.multiple = multiple;
    input.addEventListener('change', function() { resolve(input.files); });
    input.click();
})""")
private external fun pickFiles(multiple: Boolean): Promise<JsAny?>

/** Shared ktor HttpClient for simple requests */
private val httpClient = io.ktor.client.HttpClient(Js)

@JsModule("html2canvas")
external fun html2canvas(htmlElement: HTMLElement): Promise<HTMLCanvasElement>

private data class FileData(val name: String, val mimeType: String, val base64: String)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private suspend fun readFile(file: File): FileData {
    val buffer = file.arrayBuffer()
    val uint8 = Uint8Array(buffer)
    val bytes = ByteArray(uint8.length) { uint8[it].toInt().toByte() }
    val base64 = kotlin.io.encoding.Base64.encode(bytes)
    return FileData(file.name, file.type.ifEmpty { "application/octet-stream" }, base64)
}

private suspend fun readFileList(files: web.file.FileList): List<FileData> {
    val result = mutableListOf<FileData>()
    for (i in 0 until files.length) {
        val file = files.item(i) ?: continue
        result.add(readFile(file))
    }
    return result
}

// ---- Client-side data models ----

data class ToolCallState(
    val id: String,
    val title: String,
    val status: ToolStatus,
    val content: String? = null,
    val contentHtml: String? = null,
    val kind: ToolKind? = null,
    val location: String? = null,
)

sealed class ChatMessage {
    data class User(val text: String, val fileNames: List<String> = emptyList()) : ChatMessage()
    data class Assistant(val markdown: String, val usage: String? = null) : ChatMessage()
    data class Thought(val markdown: String, val usage: String? = null) : ChatMessage()
    data class ToolBlock(val tools: List<ToolCallState>) : ChatMessage()
    data class Error(val message: String) : ChatMessage()
}

// ---- Kilua Application ----

class App : Application() {

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    // Connection state
    private var wsSendChannel: SendChannel<WsMessage>? = null
    private var connected by mutableStateOf(false)
    private var agentName by mutableStateOf("")
    private var agentVersion by mutableStateOf("")
    private var cwd by mutableStateOf<String?>(null)
    private var agentWorking by mutableStateOf(false)
    private var reconnectDelay = 1000
    private var lastSeq: Long = 0

    // Conversation state
    private var messages by mutableStateOf(listOf<ChatMessage>())
    /** Triple(id, accumulatedMarkdown, usage) for current thought. */
    private var currentThought by mutableStateOf<Triple<String, String, String>?>(null)
    /** Triple(msgId, accumulatedMarkdown, usage) for current response. */
    private var currentResponse by mutableStateOf<Triple<String, String, String>?>(null)
    private var currentToolCalls by mutableStateOf(listOf<ToolCallState>())

    // Permission state
    private var permissionRequest by mutableStateOf<WsMessage.PermissionRequest?>(null)

    // Agent selector state
    private var availableAgents by mutableStateOf(listOf<AgentInfo>())
    private var currentAgentId by mutableStateOf<String?>(null)
    private var switchingAgent by mutableStateOf<String?>(null)
    private var showAgentSelector by mutableStateOf(false)

    // File attachment state
    private var pendingFiles by mutableStateOf(listOf<FileAttachment>())

    // Debug/dev state
    private var debugMode = false
    private var devMode = false
    private var screenshotEnabled by mutableStateOf(false)
    private var reloading by mutableStateOf(false)

    // Autocomplete state
    private var availableCommands by mutableStateOf(listOf<CommandInfo>())
    private var autocompleteFiltered by mutableStateOf(listOf<CommandInfo>())
    private var autocompleteSelectedIndex by mutableStateOf(-1)

    // Scroll state
    private var atBottom by mutableStateOf(true)

    // Turn collapse state — messages before this index render collapsed
    private var collapseBeforeIndex by mutableStateOf(0)

    // Input state
    private var promptText by mutableStateOf("")

    // Timer state
    private var elapsedSeconds by mutableStateOf(0)
    private var timerJob: kotlinx.coroutines.Job? = null

    private fun addFiles(files: List<FileData>) {
        pendingFiles = pendingFiles + files.map { FileAttachment(it.name, it.mimeType, it.base64) }
    }

    /** Reads files from a FileList and adds them to pendingFiles */
    private fun readAndAddFiles(files: web.file.FileList) {
        scope.launch {
            addFiles(readFileList(files))
        }
    }

    override fun start() {
        installConsoleCapture()
        val body = document.body
        debugMode = body.hasAttribute("data-debug")
        devMode = body.hasAttribute("data-dev")
        root("root") {
            chatApp()
        }
        connect()
    }

    // ---- RPC Connection ----

    private fun connect() {
        setRpcUrlPrefix(location.pathname.trimEnd('/'))

        scope.launch {
            while (true) {
                try {
                    val chatService = getService<IChatService>()
                    chatService.chat { sendChannel, receiveChannel ->
                        wsSendChannel = sendChannel
                        reconnectDelay = 1000
                        connected = true

                        // Send ResumeFrom if we have a prior seq position
                        didResume = lastSeq > 0
                        if (didResume) {
                            sendChannel.send(WsMessage.ResumeFrom(lastSeq))
                        }

                        try {
                            for (msg in receiveChannel) {
                                onMessage(msg)
                            }
                        } finally {
                            wsSendChannel = null
                            connected = false
                        }
                    }
                } catch (_: Exception) {
                    wsSendChannel = null
                    connected = false
                }

                agentName = "Reconnecting\u2026"
                agentWorking = false
                stopStatusTimer()

                val d = reconnectDelay
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000)
                delay(d.toLong())
            }
        }
    }

    private fun sendWs(msg: WsMessage) {
        wsSendChannel?.trySend(msg)
    }

    // ---- Message handler ----

    /** Extract seq from a server message, or 0 if not present. */
    private fun extractSeq(msg: WsMessage): Long = when (msg) {
        is WsMessage.Connected -> msg.seq
        is WsMessage.AgentText -> msg.seq
        is WsMessage.AgentThought -> msg.seq
        is WsMessage.ToolCall -> msg.seq
        is WsMessage.TurnComplete -> msg.seq
        is WsMessage.UserMessage -> msg.seq
        is WsMessage.Error -> msg.seq
        else -> 0
    }

    /** Whether we sent a ResumeFrom on this connection. */
    private var didResume = false

    private fun onMessage(msg: WsMessage) {
        val seq = extractSeq(msg)
        if (seq > 0) lastSeq = seq

        when (msg) {
            is WsMessage.Connected -> {
                agentName = msg.agentName
                agentVersion = msg.agentVersion
                cwd = msg.cwd
                // Clear switching modal when the new agent is ready
                if (switchingAgent != null && msg.agentName != "No agent selected") {
                    switchingAgent = null
                }
                // Reset state on fresh connect (not a delta resume)
                if (!didResume) {
                    messages = emptyList()
                    collapseBeforeIndex = 0
                    currentThought = null
                    currentResponse = null
                    currentToolCalls = emptyList()
                }
                stopStatusTimer()
                agentWorking = msg.agentWorking
                if (msg.agentWorking) startStatusTimer()
            }
            is WsMessage.UserMessage -> {
                messages = messages + ChatMessage.User(msg.text, msg.fileNames)
            }
            is WsMessage.AgentText -> {
                // Accumulate delta chunks by msgId
                val existing = currentResponse
                if (existing != null && existing.first == msg.msgId) {
                    // Same msgId — append delta
                    currentResponse = Triple(msg.msgId, existing.second + msg.markdown, msg.usage ?: existing.third)
                } else if (msg.msgId.startsWith("history-")) {
                    // History replay sends full text, not delta
                    currentResponse = Triple(msg.msgId, msg.markdown, msg.usage ?: "")
                } else {
                    // New msgId — start fresh
                    currentResponse = Triple(msg.msgId, msg.markdown, msg.usage ?: "")
                }
            }
            is WsMessage.AgentThought -> {
                // Accumulate delta chunks by thoughtId
                val existing = currentThought
                if (existing != null && existing.first == msg.thoughtId) {
                    // Same thoughtId — append delta
                    currentThought = Triple(msg.thoughtId, existing.second + msg.markdown, msg.usage ?: existing.third)
                } else if (msg.thoughtId.startsWith("history-")) {
                    // History replay sends full text, not delta
                    currentThought = Triple(msg.thoughtId, msg.markdown, msg.usage ?: "")
                } else {
                    // New thoughtId — start fresh
                    currentThought = Triple(msg.thoughtId, msg.markdown, msg.usage ?: "")
                }
            }
            is WsMessage.ToolCall -> {
                val existing = currentToolCalls.find { it.id == msg.toolCallId }
                val tc = ToolCallState(
                    id = msg.toolCallId,
                    title = msg.title,
                    status = msg.status,
                    content = msg.content ?: existing?.content,
                    contentHtml = msg.contentHtml ?: existing?.contentHtml,
                    kind = msg.kind ?: existing?.kind,
                    location = msg.location ?: existing?.location,
                )
                currentToolCalls = if (existing != null) {
                    currentToolCalls.map { if (it.id == msg.toolCallId) tc else it }
                } else {
                    currentToolCalls + tc
                }
            }
            is WsMessage.PermissionRequest -> {
                permissionRequest = msg
            }
            is WsMessage.PermissionResponse -> {
                permissionRequest = null
            }
            is WsMessage.TurnComplete -> {
                val newMessages = mutableListOf<ChatMessage>()
                newMessages.addAll(messages)
                currentThought?.let { (_, html, usage) ->
                    newMessages.add(ChatMessage.Thought(html, usage.ifEmpty { null }))
                }
                if (currentToolCalls.isNotEmpty()) {
                    newMessages.add(ChatMessage.ToolBlock(currentToolCalls.toList()))
                }
                currentResponse?.let { (_, html, usage) ->
                    newMessages.add(ChatMessage.Assistant(html, usage.ifEmpty { null }))
                }
                messages = newMessages
                currentThought = null
                currentResponse = null
                currentToolCalls = emptyList()
                stopStatusTimer()
                agentWorking = false
            }
            is WsMessage.Error -> {
                messages = messages + ChatMessage.Error(msg.message)
            }
            is WsMessage.AvailableAgents -> {
                availableAgents = msg.agents
                currentAgentId = msg.currentAgentId
                if (msg.currentAgentId == null && msg.agents.isNotEmpty()) {
                    showAgentSelector = true
                }
            }
            is WsMessage.AvailableCommands -> {
                availableCommands = msg.commands
            }
            is WsMessage.BrowserStateRequest -> {
                val state = collectBrowserState(msg.query)
                sendWs(WsMessage.BrowserStateResponse(msg.requestId, state))
            }
            else -> {}
        }
    }

    // ---- Compose UI ----

    @Composable
    private fun IComponent.chatApp() {
        appStyles()

        // Header
        header {
            val agentIcon = currentAgentId?.let { id -> availableAgents.find { it.id == id }?.icon }
            if (agentIcon != null) {
                img(src = agentIcon, alt = agentName) { className("header-icon") }
            }
            span(className = "header-title") { +(agentName.ifEmpty { "ACP Gateway" }) }
            val cwdVal = cwd
            if (cwdVal != null) {
                span(className = "header-info") { +" \u00b7 $cwdVal" }
            }
            if (availableAgents.size > 1) {
                button("\u21C4") {
                    title("Switch agent")
                    onClick { showAgentSelector = !showAgentSelector }
                }
            }
            if (devMode) {
                button(if (reloading) "Reloading\u2026" else "\u21BB") {
                    title("Reload server")
                    disabled(reloading)
                    onClick {
                        reloading = true
                        agentName = "Reloading\u2026"
                        doReload()
                    }
                }
            }
        }

        // Agent selector
        if (showAgentSelector && availableAgents.isNotEmpty()) {
            agentSelectorView()
        }

        val switching = switchingAgent
        if (switching != null) {
            switchingAgentModal(switching)
        }

        // Messages container
        div {
            id("messages")
            for ((index, msg) in messages.withIndex()) {
                val expanded = index >= collapseBeforeIndex
                key(index) {
                    when (msg) {
                        is ChatMessage.User -> userMessageView(msg.text, msg.fileNames)
                        is ChatMessage.Assistant -> assistantMessageView(msg.markdown, msg.usage, expanded)
                        is ChatMessage.Thought -> thoughtMessageView(msg.markdown, msg.usage, expanded = expanded)
                        is ChatMessage.ToolBlock -> toolBlockView(msg.tools)
                        is ChatMessage.Error -> errorMessageView(msg.message)
                    }
                }
            }
            // Current turn in-progress
            currentThought?.let { (_, html, usage) ->
                thoughtMessageView(html, usage.ifEmpty { null }, showTimer = true)
            }
            if (currentToolCalls.isNotEmpty()) {
                toolBlockView(currentToolCalls)
            }
            currentResponse?.let { (_, html, usage) ->
                assistantMessageView(html, usage.ifEmpty { null })
            }
        }

        // Install scroll listener after DOM is committed, then poll scroll state
        LaunchedEffect(Unit) {
            delay(100) // ensure DOM is flushed
            installScrollListener()
            while (true) {
                atBottom = readScrollAtBottom()
                delay(200)
            }
        }
        val contentKey = messages.size to (currentResponse?.second?.length ?: 0)
        LaunchedEffect(contentKey, currentToolCalls.size, currentThought) {
            if (readScrollAtBottom()) scrollMessagesToBottom()
        }

        // Scroll-to-bottom button
        if (!atBottom) {
            button("\u2193") {
                className("scroll-btn")
                onClick {
                    scrollMessagesToBottom()
                    atBottom = true
                }
            }
        }

        // Input bar
        inputBar()

        // Permission dialog
        val perm = permissionRequest
        if (perm != null) {
            permissionDialog(perm)
        }
    }

    @Composable
    private fun IComponent.userMessageView(text: String, fileNames: List<String> = emptyList()) {
        div(className = "msg msg-user") {
            div(className = "msg-content") {
                if (text.isNotEmpty()) +text
                if (fileNames.isNotEmpty()) {
                    div(className = "msg-files") {
                        for (name in fileNames) {
                            span(className = "msg-file-tag") { +name }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.assistantMessageView(markdown: String, usage: String?, expanded: Boolean = true) {
        div(className = "msg msg-assistant") {
            details {
                if (expanded) attribute("open", "")
                summary {
                    span { +"Response" }
                    if (usage != null) {
                        span { +" \u00b7 $usage" }
                    }
                }
                div(className = "msg-body") {
                    rawHtml(dev.kilua.marked.parseMarkdown(markdown))
                }
            }
        }
    }

    @Composable
    private fun IComponent.thoughtMessageView(markdown: String, usage: String?, showTimer: Boolean = false, expanded: Boolean = true) {
        div(className = "msg msg-thought") {
            details {
                if (expanded) attribute("open", "")
                summary {
                    span { +"Thinking" }
                    if (showTimer && elapsedSeconds > 0) {
                        span { +" ${formatElapsed(elapsedSeconds)}" }
                    }
                }
                div(className = "msg-body") {
                    rawHtml(dev.kilua.marked.parseMarkdown(markdown))
                }
            }
        }
    }

    @Composable
    private fun IComponent.toolBlockView(tools: List<ToolCallState>) {
        val done = tools.count { it.status == ToolStatus.Completed }
        val failed = tools.count { it.status == ToolStatus.Failed }
        val running = tools.count { it.status == ToolStatus.InProgress }
        val total = tools.size
        val activeName = tools.lastOrNull { it.status == ToolStatus.InProgress }?.title

        div(className = "msg msg-tools") {
            details {
                summary {
                    span(className = "tool-summary-label") {
                        val label = if (total == 1) "1 tool call" else "$total tool calls"
                        +label
                        val parts = mutableListOf<String>()
                        if (done > 0) parts.add("$done done")
                        if (failed > 0) parts.add("$failed failed")
                        if (running > 0) parts.add("$running running")
                        if (parts.isNotEmpty()) {
                            +" (${parts.joinToString(", ")})"
                        }
                    }
                    if (activeName != null) {
                        span(className = "tool-summary-active") { +" \u00b7 $activeName" }
                    }
                }
                div(className = "tools-list") {
                    for (tc in tools) {
                        toolRow(tc)
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.toolRow(tc: ToolCallState) {
        val hasContent = tc.contentHtml != null || !tc.content.isNullOrEmpty()
        if (hasContent) {
            details(className = "tool-item") {
                summary {
                    toolRowSummary(tc)
                }
                val contentHtml = tc.contentHtml
                val contentText = tc.content
                if (contentHtml != null) {
                    // Legacy: pre-rendered HTML from older sessions
                    div(className = "tool-content") { rawHtml(contentHtml) }
                } else if (!contentText.isNullOrEmpty()) {
                    // Render through markdown for syntax-highlighted diffs and code blocks
                    div(className = "tool-content msg-body") {
                        rawHtml(dev.kilua.marked.parseMarkdown(contentText))
                    }
                }
            }
        } else {
            div(className = "tool-item") {
                toolRowSummary(tc)
            }
        }
    }

    @Composable
    private fun IComponent.toolRowSummary(tc: ToolCallState) {
        val iconClass = when (tc.status) {
            ToolStatus.Completed -> "tool-icon-ok"
            ToolStatus.Failed -> "tool-icon-fail"
            else -> "tool-icon-pending"
        }
        val icon = when (tc.status) {
            ToolStatus.Completed -> "\u2713"
            ToolStatus.Failed -> "\u2717"
            else -> "\u25CB"
        }
        span(className = iconClass) { +icon }
        span(className = "tool-name") { +" ${tc.title}" }
        if (tc.location != null) {
            span(className = "tool-location") { +" \u00b7 ${tc.location.substringAfterLast('/')}" }
        }
    }

    @Composable
    private fun IComponent.agentSelectorView() {
        div(className = "agent-selector-overlay") {
            onClick { showAgentSelector = false }
            div(className = "agent-selector-dialog") {
                onClick { it.stopPropagation() }
                h3 { +"Select an Agent" }
                div(className = "agent-selector-list") {
                    for (agent in availableAgents) {
                        val isCurrent = agent.id == currentAgentId
                        div(className = if (isCurrent) "agent-selector-item current" else "agent-selector-item") {
                            if (!isCurrent) {
                                onClick {
                                    sendWs(WsMessage.ChangeAgent(agent.id))
                                    messages = listOf()
                                    currentThought = null
                                    currentResponse = null
                                    currentToolCalls = listOf()
                                    permissionRequest = null
                                    agentWorking = false
                                    showAgentSelector = false
                                    switchingAgent = agent.name
                                }
                            }
                            if (agent.icon != null) {
                                img(src = agent.icon, alt = agent.name) { className("agent-selector-icon") }
                            } else {
                                div(className = "agent-selector-icon-placeholder") {
                                    +(agent.name.firstOrNull()?.uppercase() ?: "?")
                                }
                            }
                            div(className = "agent-selector-info") {
                                div(className = "agent-selector-name") {
                                    +agent.name
                                    if (isCurrent) span(className = "agent-selector-badge") { +"current" }
                                }
                                if (agent.description.isNotEmpty()) {
                                    div(className = "agent-selector-desc") { +agent.description }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.switchingAgentModal(name: String) {
        div(className = "agent-selector-overlay") {
            div(className = "switching-agent-dialog") {
                div(className = "switching-spinner")
                p { +"Switching to $name\u2026" }
            }
        }
    }

    @Composable
    private fun IComponent.errorMessageView(message: String) {
        div(className = "msg msg-error") { +message }
    }

    @Composable
    private fun IComponent.permissionDialog(perm: WsMessage.PermissionRequest) {
        div(className = "permission-overlay") {
            div(className = "permission-dialog") {
                h3 { +"Permission Required" }
                p { +perm.title }
                div(className = "perm-actions") {
                    for (opt in perm.options) {
                        button(opt.name) {
                            onClick {
                                sendWs(WsMessage.PermissionResponse(perm.toolCallId, opt.optionId))
                                permissionRequest = null
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.inputBar() {
        div(className = "input-bar") {
            // File preview
            if (pendingFiles.isNotEmpty()) {
                div(className = "file-preview") {
                    for ((index, file) in pendingFiles.withIndex()) {
                        span(className = "file-chip") {
                            +file.name
                            button("\u00d7") {
                                title("Remove")
                                onClick {
                                    pendingFiles = pendingFiles.filterIndexed { i, _ -> i != index }
                                }
                            }
                        }
                    }
                }
            }

            // Autocomplete popup
            if (autocompleteFiltered.isNotEmpty()) {
                div(className = "autocomplete-popup") {
                    for ((i, cmd) in autocompleteFiltered.withIndex()) {
                        div(className = if (i == autocompleteSelectedIndex) "autocomplete-item selected" else "autocomplete-item") {
                            attribute("title", cmd.description)
                            onClick {
                                completeCommand(cmd.name)
                            }
                            span { +"/${cmd.name}" }
                        }
                    }
                }
            }

            div(className = "input-row") {
                button("+") {
                    className("btn-attach")
                    type(ButtonType.Button)
                    title("Attach files")
                    onClick {
                        scope.launch {
                            val fileList: web.file.FileList = pickFiles(true).await()
                            if (fileList.length > 0) {
                                addFiles(readFileList(fileList))
                            }
                        }
                    }
                }

                tag("form") {
                    onEvent<web.events.Event>("submit") { e ->
                        e.preventDefault()
                        dismissAutocomplete()
                        if (agentWorking) sendWs(WsMessage.Cancel) else sendPrompt()
                    }

                    textArea(
                        value = promptText,
                        rows = 3,
                        placeholder = "Send a message...",
                        disabled = if (agentWorking) true else null,
                    ) {
                        onInput {
                            promptText = this.value ?: ""
                            updateAutocomplete(promptText)
                        }
                        onKeydown { e ->
                            if (autocompleteFiltered.isNotEmpty()) {
                                when (e.key) {
                                    "Tab" -> {
                                        e.preventDefault()
                                        val idx = if (autocompleteSelectedIndex >= 0) autocompleteSelectedIndex else 0
                                        if (idx < autocompleteFiltered.size) {
                                            completeCommand(autocompleteFiltered[idx].name)
                                        }
                                        return@onKeydown
                                    }
                                    "Escape" -> {
                                        e.preventDefault()
                                        dismissAutocomplete()
                                        return@onKeydown
                                    }
                                    "ArrowDown" -> {
                                        e.preventDefault()
                                        autocompleteSelectedIndex = ((autocompleteSelectedIndex + 1) % autocompleteFiltered.size)
                                        return@onKeydown
                                    }
                                    "ArrowUp" -> {
                                        e.preventDefault()
                                        autocompleteSelectedIndex = if (autocompleteSelectedIndex <= 0) autocompleteFiltered.size - 1 else autocompleteSelectedIndex - 1
                                        return@onKeydown
                                    }
                                }
                            }
                            if (e.key == "Enter" && !e.shiftKey) {
                                e.preventDefault()
                                dismissAutocomplete()
                                if (agentWorking) sendWs(WsMessage.Cancel) else sendPrompt()
                            }
                        }
                        setDropTarget { e ->
                            val files = e.dataTransfer?.files ?: return@setDropTarget
                            if (files.length > 0) {
                                readAndAddFiles(files)
                            }
                        }
                        onEvent<web.clipboard.ClipboardEvent>("paste") { e ->
                            handlePasteFiles(e)
                        }
                    }

                    div(className = "input-actions") {
                        div(className = "btn-row") {
                            if (agentWorking) {
                                button("Cancel") {
                                    className("btn-cancel")
                                    type(ButtonType.Submit)
                                }
                                if (debugMode) {
                                    button("Diagnose") {
                                        className("btn-diagnose")
                                        type(ButtonType.Button)
                                        onClick {
                                            sendWs(WsMessage.Diagnose)
                                        }
                                    }
                                }
                            } else {
                                button("Send") {
                                    className("btn-send")
                                    type(ButtonType.Submit)
                                }
                            }
                        }
                        if (debugMode) {
                            label(className = "screenshot-label") {
                                tag("input") {
                                    attribute("type", "checkbox")
                                    if (screenshotEnabled) attribute("checked", "")
                                    onEvent<web.events.Event>("change") {
                                        screenshotEnabled = !screenshotEnabled
                                    }
                                }
                                +"Screenshot"
                            }
                            button("Download Log") {
                                className("btn-download-log")
                                type(ButtonType.Button)
                                onClick { downloadChatLog() }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Paste file handling (Kotlin using web.clipboard.ClipboardEvent) ----

    private fun handlePasteFiles(e: web.clipboard.ClipboardEvent) {
        val clipboardData = e.clipboardData ?: return
        val items = clipboardData.items
        val files = mutableListOf<File>()
        for (i in 0 until items.length) {
            val item = items[i]
            if (item.kind == "file") {
                val file = item.getAsFile() ?: continue
                files.add(file)
            }
        }
        if (files.isEmpty()) return
        e.preventDefault()
        scope.launch {
            addFiles(files.map { readFile(it) })
        }
    }

    // ---- Autocomplete ----

    private fun updateAutocomplete(text: String) {
        if (availableCommands.isEmpty() || text.isEmpty() || !text.startsWith("/") || text.contains('\n')) {
            dismissAutocomplete()
            return
        }
        val query = text.removePrefix("/").lowercase()
        val filtered = if (query.isEmpty()) {
            availableCommands
        } else {
            availableCommands.filter { it.name.lowercase().contains(query) }
        }
        if (filtered.isEmpty()) {
            dismissAutocomplete()
            return
        }
        autocompleteFiltered = filtered
        autocompleteSelectedIndex = 0
    }

    private fun dismissAutocomplete() {
        autocompleteFiltered = emptyList()
        autocompleteSelectedIndex = -1
    }

    private fun completeCommand(name: String) {
        promptText = "/$name "
        dismissAutocomplete()
    }

    // ---- Browser state collection ----

    private fun collectBrowserState(query: String): String {
        return when (query) {
            "console" -> getConsoleLogs()
            "dom" -> getDomState()
            else -> {
                // "all" or unrecognized — return both
                """{"console":${getConsoleLogs()},"dom":${getDomState()}}"""
            }
        }
    }

    // ---- Actions ----

    private fun downloadChatLog() {
        val sb = StringBuilder()
        sb.appendLine("# Chat Log — ${agentName.ifEmpty { "ACP Gateway" }}")
        sb.appendLine()

        fun appendMessages(msgs: List<ChatMessage>) {
            for (msg in msgs) {
                when (msg) {
                    is ChatMessage.User -> {
                        sb.appendLine("## User")
                        sb.appendLine(msg.text)
                        if (msg.fileNames.isNotEmpty()) {
                            sb.appendLine("Files: ${msg.fileNames.joinToString(", ")}")
                        }
                        sb.appendLine()
                    }
                    is ChatMessage.Assistant -> {
                        sb.appendLine("## Assistant")
                        if (msg.usage != null) sb.appendLine("_${msg.usage}_")
                        sb.appendLine(msg.markdown)
                        sb.appendLine()
                    }
                    is ChatMessage.Thought -> {
                        sb.appendLine("## Thinking")
                        if (msg.usage != null) sb.appendLine("_${msg.usage}_")
                        sb.appendLine(msg.markdown)
                        sb.appendLine()
                    }
                    is ChatMessage.ToolBlock -> {
                        sb.appendLine("## Tool Calls")
                        for (tc in msg.tools) {
                            val icon = when (tc.status) {
                                ToolStatus.Completed -> "[ok]"
                                ToolStatus.Failed -> "[fail]"
                                else -> "[pending]"
                            }
                            sb.appendLine("- $icon ${tc.title}${tc.location?.let { " · $it" } ?: ""}")
                            if (!tc.content.isNullOrEmpty()) {
                                sb.appendLine("  ```")
                                for (line in tc.content.lines()) sb.appendLine("  $line")
                                sb.appendLine("  ```")
                            }
                        }
                        sb.appendLine()
                    }
                    is ChatMessage.Error -> {
                        sb.appendLine("## Error")
                        sb.appendLine(msg.message)
                        sb.appendLine()
                    }
                }
            }
        }

        appendMessages(messages)

        // Include in-progress turn if any
        val inProgress = mutableListOf<ChatMessage>()
        currentThought?.let { (_, md, usage) -> inProgress.add(ChatMessage.Thought(md, usage.ifEmpty { null })) }
        if (currentToolCalls.isNotEmpty()) inProgress.add(ChatMessage.ToolBlock(currentToolCalls))
        currentResponse?.let { (_, md, usage) -> inProgress.add(ChatMessage.Assistant(md, usage.ifEmpty { null })) }
        if (inProgress.isNotEmpty()) {
            sb.appendLine("---")
            sb.appendLine("_In progress:_")
            sb.appendLine()
            appendMessages(inProgress)
        }

        downloadTextFile("chat-log.md", sb.toString())
    }

    private fun sendPrompt() {
        val text = promptText.trim()
        if (text.isEmpty() && pendingFiles.isEmpty()) return

        val files = pendingFiles.toList()
        collapseBeforeIndex = messages.size
        promptText = ""
        pendingFiles = emptyList()
        agentWorking = true
        startStatusTimer()

        if (screenshotEnabled) {
            scope.launch {
                val canvasElement = html2canvas(document.documentElement).await<HTMLCanvasElement>()
                val base64 = canvasElement.toDataURL("image/png").split(',')[1]
                // todo: if base64 is empty let the user know
                val screenshot = base64.ifEmpty { throw Exception("Screenshot failed") }
                sendWs(WsMessage.Prompt(text, screenshot = screenshot, files = files))
            }
        } else {
            sendWs(WsMessage.Prompt(text, files = files))
        }
    }

    /** POST /reload, poll /health, then reload page */
    private fun doReload() {
        scope.launch {
            try {
                httpClient.post("${location.origin}/reload")
            } catch (e: Throwable) {
                console.error("doReload: error: $e")
            }
            // Poll until server is back
            while (true) {
                delay(1000)
                try {
                    val resp = httpClient.get("${location.origin}/health")
                    if (resp.status.value == 200) {
                        location.reload()
                        return@launch
                    }
                } catch (_: Throwable) { }
            }
        }
    }

    // ---- Status timer ----

    private fun formatElapsed(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "\u00b7 ${minutes}m ${seconds}s" else "\u00b7 ${seconds}s"
    }

    private fun startStatusTimer() {
        stopStatusTimer()
        elapsedSeconds = 0
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    private fun stopStatusTimer() {
        elapsedSeconds = 0
        timerJob?.cancel()
        timerJob = null
    }
}

// ---- Entry point ----

fun main() {
    startApplication(::App)
}
