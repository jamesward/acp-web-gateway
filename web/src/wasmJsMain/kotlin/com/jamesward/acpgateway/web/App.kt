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
    data class User(val text: String) : ChatMessage()
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

    // Conversation state
    private var messages by mutableStateOf(listOf<ChatMessage>())
    private var currentThought by mutableStateOf<Pair<String, String>?>(null)
    private var currentResponse by mutableStateOf<Pair<String, String>?>(null)
    private var currentToolCalls by mutableStateOf(listOf<ToolCallState>())

    // Permission state
    private var permissionRequest by mutableStateOf<WsMessage.PermissionRequest?>(null)

    // Agent selector state
    private var availableAgents by mutableStateOf(listOf<AgentInfo>())
    private var currentAgentId by mutableStateOf<String?>(null)
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

    private fun onMessage(msg: WsMessage) {
        when (msg) {
            is WsMessage.Connected -> {
                agentName = msg.agentName
                agentVersion = msg.agentVersion
                cwd = msg.cwd
                messages = emptyList()
                currentThought = null
                currentResponse = null
                currentToolCalls = emptyList()
                stopStatusTimer()
                agentWorking = msg.agentWorking
                if (msg.agentWorking) startStatusTimer()
            }
            is WsMessage.UserMessage -> {
                messages = messages + ChatMessage.User(msg.text)
            }
            is WsMessage.AgentText -> {
                currentResponse = Pair(msg.markdown, msg.usage ?: "")
            }
            is WsMessage.AgentThought -> {
                currentThought = Pair(msg.markdown, msg.usage ?: "")
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
            is WsMessage.TurnComplete -> {
                val newMessages = mutableListOf<ChatMessage>()
                newMessages.addAll(messages)
                currentThought?.let { (html, usage) ->
                    newMessages.add(ChatMessage.Thought(html, usage.ifEmpty { null }))
                }
                if (currentToolCalls.isNotEmpty()) {
                    newMessages.add(ChatMessage.ToolBlock(currentToolCalls.toList()))
                }
                currentResponse?.let { (html, usage) ->
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
                sendWs(WsMessage.BrowserStateResponse(msg.requestId, """{"status":"minimal client"}"""))
            }
            else -> {}
        }
    }

    // ---- Compose UI ----

    @Composable
    private fun IComponent.chatApp() {
        // Header
        header {
            span { +(agentName.ifEmpty { "ACP Gateway" }) }
            val cwdVal = cwd
            if (cwdVal != null) {
                span { +" \u00b7 $cwdVal" }
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

        // Messages container
        div {
            id("messages")
            for ((index, msg) in messages.withIndex()) {
                key(index) {
                    when (msg) {
                        is ChatMessage.User -> userMessageView(msg.text)
                        is ChatMessage.Assistant -> assistantMessageView(msg.markdown, msg.usage)
                        is ChatMessage.Thought -> thoughtMessageView(msg.markdown, msg.usage)
                        is ChatMessage.ToolBlock -> toolBlockView(msg.tools)
                        is ChatMessage.Error -> errorMessageView(msg.message)
                    }
                }
            }
            // Current turn in-progress
            currentThought?.let { (html, usage) ->
                thoughtMessageView(html, usage.ifEmpty { null })
            }
            if (currentToolCalls.isNotEmpty()) {
                toolBlockView(currentToolCalls)
            }
            currentResponse?.let { (html, usage) ->
                assistantMessageView(html, usage.ifEmpty { null })
            }
        }

        // Status timer
        if (agentWorking && elapsedSeconds > 0) {
            div {
                +formatElapsed(elapsedSeconds)
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
    private fun IComponent.userMessageView(text: String) {
        div {
            div { +text }
        }
    }

    @Composable
    private fun IComponent.assistantMessageView(markdown: String, usage: String?) {
        div {
            details {
                attribute("open", "")
                summary {
                    span { +"Response" }
                    if (usage != null) {
                        span { +" \u00b7 $usage" }
                    }
                }
                div {
                    rawHtml(dev.kilua.marked.parseMarkdown(markdown))
                }
            }
        }
    }

    @Composable
    private fun IComponent.thoughtMessageView(markdown: String, usage: String?) {
        div {
            details {
                attribute("open", "")
                summary {
                    span { +"Thinking" }
                    if (usage != null) {
                        span { +" \u00b7 $usage" }
                    }
                }
                div {
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

        div {
            details {
                summary {
                    val label = if (total == 1) "1 tool call" else "$total tool calls"
                    +label
                    val parts = mutableListOf<String>()
                    if (done > 0) parts.add("$done done")
                    if (failed > 0) parts.add("$failed failed")
                    if (running > 0) parts.add("$running running")
                    if (parts.isNotEmpty()) {
                        +" (${parts.joinToString(", ")})"
                    }
                    if (activeName != null) {
                        +" \u00b7 $activeName"
                    }
                }
                div {
                    for (tc in tools) {
                        toolRow(tc)
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.toolRow(tc: ToolCallState) {
        div {
            val icon = when (tc.status) {
                ToolStatus.Completed -> "\u2713"
                ToolStatus.Failed -> "\u2717"
                else -> "\u25CB"
            }
            span { +icon }
            span { +" ${tc.title}" }
            if (tc.location != null) {
                span { +" \u00b7 ${tc.location.substringAfterLast('/')}" }
            }
            val contentHtml = tc.contentHtml
            val contentText = tc.content
            if (contentHtml != null) {
                div { rawHtml(contentHtml) }
            } else if (!contentText.isNullOrEmpty()) {
                pre(contentText)
            }
        }
    }

    @Composable
    private fun IComponent.agentSelectorView() {
        div {
            h3 { +"Select an Agent" }
            div {
                for (agent in availableAgents) {
                    val isCurrent = agent.id == currentAgentId
                    button(agent.name) {
                        if (isCurrent) attribute("disabled", "")
                        onClick {
                            sendWs(WsMessage.ChangeAgent(agent.id))
                            showAgentSelector = false
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IComponent.errorMessageView(message: String) {
        div { +message }
    }

    @Composable
    private fun IComponent.permissionDialog(perm: WsMessage.PermissionRequest) {
        div {
            h3 { +"Permission Required" }
            p { +perm.title }
            div {
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

    @Composable
    private fun IComponent.inputBar() {
        // File preview
        if (pendingFiles.isNotEmpty()) {
            div {
                for ((index, file) in pendingFiles.withIndex()) {
                    span {
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

        // Autocomplete popup (positioned above the input bar)
        if (autocompleteFiltered.isNotEmpty()) {
            div {
                for ((i, cmd) in autocompleteFiltered.withIndex()) {
                    div {
                        attribute("style", if (i == autocompleteSelectedIndex) "background: #e0e0e0; cursor: pointer; padding: 4px 8px;" else "cursor: pointer; padding: 4px 8px;")
                        attribute("title", cmd.description)
                        onClick {
                            completeCommand(cmd.name)
                        }
                        span { +"/${cmd.name}" }
                    }
                }
            }
        }

        div {
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

                button("Attach") {
                    type(ButtonType.Button)
                    onClick {
                        scope.launch {
                            val fileList: web.file.FileList = pickFiles(true).await()
                            if (fileList.length > 0) {
                                addFiles(readFileList(fileList))
                            }
                        }
                    }
                }

                if (debugMode) {
                    label {
                        tag("input") {
                            attribute("type", "checkbox")
                            if (screenshotEnabled) attribute("checked", "")
                            onEvent<web.events.Event>("change") {
                                screenshotEnabled = !screenshotEnabled
                            }
                        }
                        +"Screenshot"
                    }
                }

                val btnLabel = if (agentWorking) "Cancel" else "Send"
                button(btnLabel) {
                    type(ButtonType.Submit)
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

    // ---- Actions ----

    private fun sendPrompt() {
        val text = promptText.trim()
        if (text.isEmpty() && pendingFiles.isEmpty()) return

        val files = pendingFiles.toList()
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
