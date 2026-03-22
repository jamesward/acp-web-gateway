@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jamesward.acpgateway.web

import androidx.compose.runtime.*
import com.jamesward.acpgateway.shared.*
import dev.kilua.Application
import dev.kilua.compose.root
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.rpc.getService
import dev.kilua.startApplication
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import web.console.console
import web.dom.document
import web.file.File
import web.html.HTMLCanvasElement
import web.keyboard.KeyboardEvent
import web.location.location

class App : Application() {

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    // Theme state
    private var themePreference by mutableStateOf(getThemePreference())

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
    private var agentError by mutableStateOf<String?>(null)

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
        pendingFiles = pendingFiles + toFileAttachments(files)
    }

    /** Reads files from a FileList and adds them to pendingFiles */
    private fun readAndAddFiles(files: web.file.FileList) {
        scope.launch {
            addFiles(readFileList(files))
        }
    }

    override fun start() {
        installTheme()
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
                if (msg.agentName != "No agent selected" && msg.agentName != "Agent failed to start") {
                    switchingAgent = null
                    agentError = null
                }
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
                val existing = currentResponse
                if (existing != null && existing.first == msg.msgId) {
                    currentResponse = Triple(msg.msgId, existing.second + msg.markdown, msg.usage ?: existing.third)
                } else {
                    currentResponse = Triple(msg.msgId, msg.markdown, msg.usage ?: "")
                }
            }
            is WsMessage.AgentThought -> {
                val existing = currentThought
                if (existing != null && existing.first == msg.thoughtId) {
                    currentThought = Triple(msg.thoughtId, existing.second + msg.markdown, msg.usage ?: existing.third)
                } else {
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
                    newMessages.add(ChatMessage.Thought(html, usage.ifEmpty { null }, elapsedSeconds = elapsedSeconds))
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
                agentError = msg.message
                switchingAgent = null
                agentWorking = false
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

            if (availableAgents.size > 1) {
                button("\u21C4") {
                    title("Switch agent")
                    onClick { showAgentSelector = !showAgentSelector }
                }
            }

            val cwdVal = cwd
            if (cwdVal != null) {
                span(className = "header-info") { +cwdVal }
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

            // GitHub link
            tag("a") {
                className("btn-github")
                attribute("href", "https://github.com/jamesward/acp-web-gateway")
                attribute("target", "_blank")
                title("View on GitHub")
                rawHtml("""<svg height="16" width="16" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>""")
            }

            // Theme toggle
            val themeIcon = when (themePreference) {
                "light" -> "\u2600\uFE0F"
                "dark" -> "\uD83C\uDF19"
                else -> "\uD83D\uDCBB"
            }
            val themeLabel = when (themePreference) {
                "light" -> "Light mode"
                "dark" -> "Dark mode"
                else -> "System theme"
            }
            button(themeIcon) {
                className("btn-theme")
                title(themeLabel)
                onClick { themePreference = cycleTheme(themePreference) }
            }
        }

        // Agent selector
        if (showAgentSelector && availableAgents.isNotEmpty()) {
            agentSelectorView(
                agents = availableAgents,
                currentAgentId = currentAgentId,
                agentError = agentError,
                onDismiss = { showAgentSelector = false },
                onSelect = { agent ->
                    sendWs(WsMessage.ChangeAgent(agent.id))
                    messages = listOf()
                    currentThought = null
                    currentResponse = null
                    currentToolCalls = listOf()
                    permissionRequest = null
                    agentWorking = false
                    showAgentSelector = false
                    switchingAgent = agent.name
                },
            )
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
                        is ChatMessage.Thought -> thoughtMessageView(msg.markdown, msg.usage, elapsedSeconds = msg.elapsedSeconds, expanded = expanded)
                        is ChatMessage.ToolBlock -> toolBlockView(msg.tools)
                        is ChatMessage.Error -> errorMessageView(msg.message)
                    }
                }
            }
            // Current turn in-progress
            currentThought?.let { (_, html, usage) ->
                thoughtMessageView(html, usage.ifEmpty { null }, showTimer = true, elapsedSeconds = elapsedSeconds)
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
            delay(100)
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
        inputBar(
            pendingFiles = pendingFiles,
            autocompleteFiltered = autocompleteFiltered,
            autocompleteSelectedIndex = autocompleteSelectedIndex,
            promptText = promptText,
            agentWorking = agentWorking,
            debugMode = debugMode,
            screenshotEnabled = screenshotEnabled,
            onRemoveFile = { index -> pendingFiles = pendingFiles.filterIndexed { i, _ -> i != index } },
            onCompleteCommand = { name -> completeCommand(name) },
            onAttachClick = {
                scope.launch {
                    val fileList: web.file.FileList = pickFiles(true).await()
                    if (fileList.length > 0) {
                        addFiles(readFileList(fileList))
                    }
                }
            },
            onSubmit = {
                dismissAutocomplete()
                if (agentWorking) sendWs(WsMessage.Cancel) else sendPrompt()
            },
            onPromptInput = { value ->
                promptText = value
                updateAutocomplete(value)
            },
            onKeydown = onKeydown@{ e ->
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
            },
            onDropFiles = { files -> readAndAddFiles(files) },
            onPaste = { e -> handlePasteFiles(e) },
            onDiagnose = { sendWs(WsMessage.Diagnose) },
            onScreenshotToggle = { screenshotEnabled = !screenshotEnabled },
            onDownloadLog = { downloadChatLog() },
        )

        // Permission dialog
        val perm = permissionRequest
        if (perm != null) {
            permissionDialog(perm) { toolCallId, optionId ->
                sendWs(WsMessage.PermissionResponse(toolCallId, optionId))
                permissionRequest = null
            }
        }
    }

    // ---- Paste file handling ----

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
