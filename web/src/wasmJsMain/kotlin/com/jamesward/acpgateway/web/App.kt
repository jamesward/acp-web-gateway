@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.jamesward.acpgateway.web

import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.PermissionOptionInfo
import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// JS interop external declarations
@JsFun("() => document")
private external fun jsDocument(): JsAny

@JsFun("(id) => document.getElementById(id)")
private external fun getElementById(id: JsString): JsAny?

@JsFun("(tag) => document.createElement(tag)")
private external fun createElement(tag: JsString): JsAny

@JsFun("(el, text) => { el.textContent = text; }")
private external fun setTextContent(el: JsAny, text: JsString)

@JsFun("(el) => el.textContent || ''")
private external fun getTextContent(el: JsAny): JsString

@JsFun("(el, cls) => { el.className = cls; }")
private external fun setClassName(el: JsAny, cls: JsString)

@JsFun("(el, key, value) => { el.setAttribute(key, value); }")
private external fun setAttribute(el: JsAny, key: JsString, value: JsString)

@JsFun("(el, child) => el.appendChild(child)")
private external fun appendChild(el: JsAny, child: JsAny)

@JsFun("(parent, child) => parent.removeChild(child)")
private external fun removeChild(parent: JsAny, child: JsAny)

@JsFun("(el, html) => { el.innerHTML = html; }")
private external fun setInnerHTML(el: JsAny, html: JsString)

@JsFun("(el) => el.scrollHeight")
private external fun getScrollHeight(el: JsAny): JsNumber

@JsFun("(el, v) => { el.scrollTop = v; }")
private external fun setScrollTop(el: JsAny, v: JsNumber)

@JsFun("(el) => el.scrollTop")
private external fun getScrollTop(el: JsAny): JsNumber

@JsFun("(el) => el.clientHeight")
private external fun getClientHeight(el: JsAny): JsNumber

@JsFun("(el, handler) => { el.addEventListener('scroll', handler); }")
private external fun onScroll(el: JsAny, handler: JsAny)

@JsFun("(el, behavior) => { el.scrollTo({ top: el.scrollHeight, behavior: behavior }); }")
private external fun scrollToBottomSmooth(el: JsAny, behavior: JsString)

@JsFun("(el) => el.value || ''")
private external fun getValue(el: JsAny): JsString

@JsFun("(el, v) => { el.value = v; }")
private external fun setValue(el: JsAny, v: JsString)

@JsFun("(el, v) => { el.disabled = v; }")
private external fun setDisabled(el: JsAny, v: JsBoolean)

@JsFun("(el) => el.focus()")
private external fun focus(el: JsAny)

@JsFun("(el, cls) => el.classList.add(cls)")
private external fun classListAdd(el: JsAny, cls: JsString)

@JsFun("(el, cls) => el.classList.remove(cls)")
private external fun classListRemove(el: JsAny, cls: JsString)

@JsFun("(el, id) => { el.id = id; }")
private external fun setId(el: JsAny, id: JsString)

@JsFun("(url) => new WebSocket(url)")
private external fun newWebSocket(url: JsString): JsAny

@JsFun("(ws, msg) => ws.send(msg)")
private external fun wsSend(ws: JsAny, msg: JsString)

@JsFun("(ws, handler) => { ws.onopen = handler; }")
private external fun wsOnOpen(ws: JsAny, handler: JsAny)

@JsFun("(ws, handler) => { ws.onmessage = (e) => handler(e.data); }")
private external fun wsOnMessage(ws: JsAny, handler: JsAny)

@JsFun("(ws, handler) => { ws.onclose = handler; }")
private external fun wsOnClose(ws: JsAny, handler: JsAny)

@JsFun("(ws, handler) => { ws.onerror = handler; }")
private external fun wsOnError(ws: JsAny, handler: JsAny)

@JsFun("() => window.location.protocol")
private external fun getProtocol(): JsString

@JsFun("() => window.location.host")
private external fun getHost(): JsString

@JsFun("() => window.location.pathname")
private external fun getPathname(): JsString

@JsFun("(el, handler) => { el.onsubmit = (e) => { e.preventDefault(); handler(); }; }")
private external fun onSubmit(el: JsAny, handler: JsAny)

@JsFun("(el, handler) => { el.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handler(); } }; }")
private external fun onEnterKey(el: JsAny, handler: JsAny)

@JsFun("(el, handler) => { el.onclick = handler; }")
private external fun onClick(el: JsAny, handler: JsAny)

@JsFun("(el) => el.checked")
private external fun isChecked(el: JsAny): JsBoolean

@JsFun("(callback) => { if (typeof html2canvas === 'undefined') { callback(''); return; } html2canvas(document.body, { useCORS: true, logging: false }).then(function(canvas) { try { callback(canvas.toDataURL('image/png').replace('data:image/png;base64,', '')); } catch(e) { callback(''); } }).catch(function() { callback(''); }); }")
private external fun captureScreenshot(callback: JsAny)

@JsFun("(callback) => (data) => callback(data)")
private external fun wrapScreenshotCallback(callback: (JsString) -> Unit): JsAny

@JsFun("() => document.body.hasAttribute('data-debug')")
private external fun isDebugMode(): JsBoolean

@JsFun("(callback, ms) => setInterval(callback, ms)")
private external fun jsSetInterval(callback: JsAny, ms: JsNumber): JsNumber

@JsFun("(id) => clearInterval(id)")
private external fun jsClearInterval(id: JsNumber)

@JsFun("() => Date.now()")
private external fun dateNow(): JsNumber

@JsFun("(el) => el.files ? el.files.length : 0")
private external fun getFileCount(el: JsAny): JsNumber

@JsFun("(el, i, nameCallback, dataCallback) => { const f = el.files[i]; nameCallback(f.name); nameCallback(f.type || 'application/octet-stream'); const r = new FileReader(); r.onload = () => { const b64 = r.result.split(',')[1] || ''; dataCallback(b64); }; r.readAsDataURL(f); }")
private external fun readFileAt(el: JsAny, index: JsNumber, nameCallback: JsAny, dataCallback: JsAny)

@JsFun("(el, handler) => { el.onchange = handler; }")
private external fun onChange(el: JsAny, handler: JsAny)

@JsFun("(el) => { el.value = ''; }")
private external fun resetFileInput(el: JsAny)

@JsFun("(el, handler) => { el.ondragover = (e) => { e.preventDefault(); e.stopPropagation(); }; el.ondrop = (e) => { e.preventDefault(); e.stopPropagation(); handler(e.dataTransfer); }; }")
private external fun onDrop(el: JsAny, handler: JsAny)

@JsFun("(callback) => (dt) => callback(dt)")
private external fun wrapDropCallback(callback: (JsAny) -> Unit): JsAny

@JsFun("(dt) => dt.files ? dt.files.length : 0")
private external fun dtFileCount(dt: JsAny): JsNumber

@JsFun("(dt, i, nameCallback, dataCallback) => { const f = dt.files[i]; nameCallback(f.name); nameCallback(f.type || 'application/octet-stream'); const r = new FileReader(); r.onload = () => { const b64 = r.result.split(',')[1] || ''; dataCallback(b64); }; r.readAsDataURL(f); }")
private external fun readDtFileAt(dt: JsAny, index: JsNumber, nameCallback: JsAny, dataCallback: JsAny)

@JsFun("(el, handler) => { el.addEventListener('paste', (e) => { if (e.clipboardData) { if (e.clipboardData.files && e.clipboardData.files.length > 0) { e.preventDefault(); handler(e.clipboardData); return; } if (e.clipboardData.items) { const dt = new DataTransfer(); for (let i = 0; i < e.clipboardData.items.length; i++) { if (e.clipboardData.items[i].kind === 'file') { dt.items.add(e.clipboardData.items[i].getAsFile()); } } if (dt.files.length > 0) { e.preventDefault(); handler(dt); } } } }); }")
private external fun onPasteFiles(el: JsAny, handler: JsAny)

@JsFun("(el) => el.childElementCount")
private external fun childElementCount(el: JsAny): JsNumber

@JsFun("(el) => el.click()")
private external fun clickElement(el: JsAny)

private data class QueuedPermission(
    val toolCallId: String,
    val title: String,
    val options: List<PermissionOptionInfo>,
)

// Global state
private var ws: JsAny? = null
private var currentAssistantDiv: JsAny? = null
private var currentThoughtDiv: JsAny? = null
private var taskStartTime: Double = 0.0
private var statusTimerId: JsNumber? = null
private var currentActivity: String = "Thinking"
private val permissionQueue = ArrayDeque<QueuedPermission>()
private var permissionDialogVisible = false
private val pendingFiles = mutableListOf<FileAttachment>()

fun main() {
    connect()
}

private fun connect() {
    val protocol = if (getProtocol().toString() == "https:") "wss" else "ws"
    val host = getHost().toString()
    val pathname = getPathname().toString().trimEnd('/')
    val url = "$protocol://$host$pathname/ws"
    val socket = newWebSocket(url.toJsString())
    ws = socket

    wsOnOpen(socket, noopHandler())
    wsOnMessage(socket, createMessageHandler())
    wsOnClose(socket, createCloseHandler())
    wsOnError(socket, noopHandler())

    setupForm()
}

@JsFun("() => () => {}")
private external fun noopHandler(): JsAny

@JsFun("(callback) => (data) => callback(data)")
private external fun wrapStringCallback(callback: (JsString) -> Unit): JsAny

@JsFun("(callback) => () => callback()")
private external fun wrapVoidCallback(callback: () -> Unit): JsAny

private fun createMessageHandler(): JsAny {
    return wrapStringCallback { data -> onMessage(data.toString()) }
}

private fun createCloseHandler(): JsAny {
    return wrapVoidCallback {
        updateAgentInfo("Disconnected. Refresh to reconnect.")
        setInputEnabled(false)
    }
}

private fun setupForm() {
    val form = getElementById("prompt-form".toJsString()) ?: return
    val input = getElementById("prompt-input".toJsString()) ?: return
    val handler = wrapVoidCallback {
        if (agentWorking) {
            sendCancel()
        } else {
            sendPrompt()
        }
    }
    onSubmit(form, handler)
    onEnterKey(input, handler)

    // Attach button triggers hidden file input
    val attachBtn = getElementById("attach-btn".toJsString())
    val fileInput = getElementById("file-input".toJsString())
    if (attachBtn != null && fileInput != null) {
        onClick(attachBtn, wrapVoidCallback { clickElement(fileInput) })
        onChange(fileInput, wrapVoidCallback { onFilesSelected(fileInput) })
    }

    // Drag-and-drop on textarea
    onDrop(input, wrapDropCallback { dt -> onFilesDropped(dt) })

    // Paste files on textarea
    onPasteFiles(input, wrapDropCallback { clipboard -> onFilesDropped(clipboard) })

    if (isDebugMode().toBoolean()) {
        val diagnoseBtn = getElementById("diagnose-btn".toJsString())
        if (diagnoseBtn != null) {
            onClick(diagnoseBtn, wrapVoidCallback { sendDiagnose() })
        }
    }

    // Scroll-to-bottom button
    val scrollBtn = getElementById("scroll-to-bottom-btn".toJsString())
    if (scrollBtn != null) {
        onClick(scrollBtn, wrapVoidCallback {
            val messages = getElementById("messages".toJsString()) ?: return@wrapVoidCallback
            scrollToBottomSmooth(messages, "smooth".toJsString())
        })
    }

    // Listen for scroll events on messages to show/hide scroll button
    val messages = getElementById("messages".toJsString())
    if (messages != null) {
        onScroll(messages, wrapVoidCallback { updateScrollButtonVisibility() })
    }
}

private fun sendCancel() {
    val msg = WsMessage.Cancel
    val encoded = json.encodeToString(WsMessage.serializer(), msg)
    ws?.let { wsSend(it, encoded.toJsString()) }
}

private fun sendDiagnose() {
    val msg = WsMessage.Diagnose
    val encoded = json.encodeToString(WsMessage.serializer(), msg)
    ws?.let { wsSend(it, encoded.toJsString()) }
}

private fun onFilesSelected(fileInput: JsAny) {
    val count = getFileCount(fileInput).toInt()
    for (i in 0 until count) {
        var fileName = ""
        var fileMimeType = ""
        val nameCallback = wrapStringCallback { s ->
            val str = s.toString()
            if (fileName.isEmpty()) fileName = str else fileMimeType = str
        }
        val dataCallback = wrapStringCallback { data ->
            pendingFiles.add(FileAttachment(name = fileName, mimeType = fileMimeType, data = data.toString()))
            renderFilePreview()
        }
        readFileAt(fileInput, i.toJsNumber(), nameCallback, dataCallback)
    }
    resetFileInput(fileInput)
}

private fun onFilesDropped(dt: JsAny) {
    val count = dtFileCount(dt).toInt()
    for (i in 0 until count) {
        var fileName = ""
        var fileMimeType = ""
        val nameCallback = wrapStringCallback { s ->
            val str = s.toString()
            if (fileName.isEmpty()) fileName = str else fileMimeType = str
        }
        val dataCallback = wrapStringCallback { data ->
            pendingFiles.add(FileAttachment(name = fileName, mimeType = fileMimeType, data = data.toString()))
            renderFilePreview()
        }
        readDtFileAt(dt, i.toJsNumber(), nameCallback, dataCallback)
    }
}

private fun renderFilePreview() {
    val preview = getElementById("file-preview".toJsString()) ?: return
    setInnerHTML(preview, "".toJsString())

    if (pendingFiles.isEmpty()) {
        classListAdd(preview, "hidden".toJsString())
        return
    }
    classListRemove(preview, "hidden".toJsString())

    for ((index, file) in pendingFiles.withIndex()) {
        val chip = createElement("div".toJsString())
        setClassName(chip, "flex items-center gap-1.5 bg-gray-700 text-gray-300 text-sm px-3 py-1 rounded-lg border border-gray-600".toJsString())

        val nameSpan = createElement("span".toJsString())
        setTextContent(nameSpan, file.name.toJsString())
        appendChild(chip, nameSpan)

        val removeBtn = createElement("button".toJsString())
        setClassName(removeBtn, "text-gray-400 hover:text-white font-bold ml-1".toJsString())
        setTextContent(removeBtn, "\u00d7".toJsString())
        val idx = index
        onClick(removeBtn, wrapVoidCallback {
            if (idx < pendingFiles.size) {
                pendingFiles.removeAt(idx)
                renderFilePreview()
            }
        })
        appendChild(chip, removeBtn)
        appendChild(preview, chip)
    }
}

private fun clearFiles() {
    pendingFiles.clear()
    val preview = getElementById("file-preview".toJsString()) ?: return
    setInnerHTML(preview, "".toJsString())
    classListAdd(preview, "hidden".toJsString())
}

private fun sendPrompt() {
    val input = getElementById("prompt-input".toJsString()) ?: return
    val text = getValue(input).toString().trim()
    if (text.isEmpty()) return

    setValue(input, "".toJsString())
    createMessageDiv("user", text)
    currentAssistantDiv = null
    currentThoughtDiv = null
    setInputEnabled(false)
    startStatusTimer()

    val files = pendingFiles.toList()
    clearFiles()

    val screenshotToggle = getElementById("screenshot-toggle".toJsString())
    if (screenshotToggle != null && isChecked(screenshotToggle).toBoolean()) {
        captureScreenshot(wrapScreenshotCallback { screenshotData ->
            val screenshot = screenshotData.toString().ifEmpty { null }
            val msg = WsMessage.Prompt(text, screenshot, files)
            val encoded = json.encodeToString(WsMessage.serializer(), msg)
            ws?.let { wsSend(it, encoded.toJsString()) }
        })
    } else {
        val msg = WsMessage.Prompt(text, files = files)
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        ws?.let { wsSend(it, encoded.toJsString()) }
    }
}

private fun onMessage(data: String) {
    val msg = json.decodeFromString(WsMessage.serializer(), data)
    when (msg) {
        is WsMessage.Connected -> {
            val info = buildString {
                append("${msg.agentName} v${msg.agentVersion}")
                val cwd = msg.cwd
                if (cwd != null) append(" — ${cwd.substringAfterLast('/')}")
            }
            updateAgentInfo(info)
            setInputEnabled(true)
        }
        is WsMessage.AgentText -> {
            if (currentAssistantDiv == null) {
                currentAssistantDiv = createMessageDiv("assistant")
            }
            appendToDiv(currentAssistantDiv!!, msg.text)
            if (taskStartTime != 0.0) {
                currentActivity = "Writing"
                updateStatusDisplay()
            }
        }
        is WsMessage.AgentThought -> {
            if (currentThoughtDiv == null) {
                currentThoughtDiv = createThoughtDiv()
            }
            appendToDiv(currentThoughtDiv!!, msg.text)
            if (taskStartTime != 0.0) {
                currentActivity = "Thinking"
                updateStatusDisplay()
            }
        }
        is WsMessage.ToolCall -> {
            showToolCall(msg.toolCallId, msg.title, msg.status)
            if (taskStartTime != 0.0 && msg.title.isNotEmpty()) {
                currentActivity = when (msg.status) {
                    "completed", "failed" -> "Thinking"
                    else -> msg.title
                }
                updateStatusDisplay()
            }
        }
        is WsMessage.PermissionRequest -> {
            showPermissionDialog(msg.toolCallId, msg.title, msg.options)
        }
        is WsMessage.TurnComplete -> {
            val html = msg.renderedHtml
            if (html != null && currentAssistantDiv != null) {
                setInnerHTML(currentAssistantDiv!!, html.toJsString())
            }
            currentAssistantDiv = null
            currentThoughtDiv = null
            stopStatusTimer()
            setInputEnabled(true)
            scrollToBottom()
        }
        is WsMessage.Error -> {
            createMessageDiv("error", msg.message)
            stopStatusTimer()
            setInputEnabled(true)
        }
        is WsMessage.Prompt -> {
            createMessageDiv("user", msg.text)
        }
        is WsMessage.PermissionResponse -> {}
        is WsMessage.Cancel -> {}
        is WsMessage.Diagnose -> {}
    }
}

private fun createMessageDiv(role: String, text: String? = null): JsAny {
    val messages = getElementById("messages".toJsString()) ?: error("messages div not found")
    val wrapper = createElement("div".toJsString())
    val alignClass = when (role) {
        "user" -> "flex justify-end"
        "error" -> "flex justify-center"
        else -> "flex justify-start"
    }
    setClassName(wrapper, alignClass.toJsString())

    val bubble = createElement("div".toJsString())
    val bubbleClass = when (role) {
        "user" -> "bg-blue-600 text-white rounded-2xl rounded-br-md px-4 py-2 max-w-[80%] whitespace-pre-wrap"
        "error" -> "bg-red-900/50 text-red-300 rounded-xl px-4 py-2 max-w-[80%]"
        else -> "bg-gray-700 text-gray-100 rounded-2xl rounded-bl-md px-4 py-2 max-w-[80%] message-content whitespace-pre-wrap"
    }
    setClassName(bubble, bubbleClass.toJsString())
    if (text != null) {
        setTextContent(bubble, text.toJsString())
    }

    appendChild(wrapper, bubble)
    appendChild(messages, wrapper)
    ensureStatusAtBottom()
    scrollToBottom()
    return bubble
}

private fun createThoughtDiv(): JsAny {
    val messages = getElementById("messages".toJsString()) ?: error("messages div not found")
    val wrapper = createElement("div".toJsString())
    setClassName(wrapper, "flex justify-start".toJsString())

    val bubble = createElement("div".toJsString())
    setClassName(bubble, "bg-gray-800 text-gray-400 italic rounded-xl px-4 py-2 max-w-[80%] text-sm border border-gray-700 whitespace-pre-wrap".toJsString())
    appendChild(wrapper, bubble)
    appendChild(messages, wrapper)
    ensureStatusAtBottom()
    return bubble
}

private fun appendToDiv(div: JsAny, text: String) {
    val current = getTextContent(div).toString()
    setTextContent(div, (current + text).toJsString())
    scrollToBottom()
}

private fun showToolCall(toolCallId: String, title: String, status: String) {
    if (title.isEmpty()) return

    val existingId = "tool-$toolCallId"
    var indicator = getElementById(existingId.toJsString())
    if (indicator == null) {
        if (status == "completed") return
        val messages = getElementById("messages".toJsString()) ?: return
        val wrapper = createElement("div".toJsString())
        setClassName(wrapper, "flex justify-start".toJsString())
        setId(wrapper, "tool-wrap-$toolCallId".toJsString())

        indicator = createElement("div".toJsString())
        setId(indicator, existingId.toJsString())
        appendChild(wrapper, indicator)
        appendChild(messages, wrapper)
        ensureStatusAtBottom()
    }

    when (status) {
        "completed" -> {
            val wrapper = getElementById("tool-wrap-$toolCallId".toJsString())
            if (wrapper != null) {
                setClassName(wrapper, "hidden".toJsString())
            }
        }
        "failed" -> {
            setClassName(indicator, "bg-red-900/30 text-red-400 rounded-lg px-3 py-1.5 text-sm border border-red-800".toJsString())
            setTextContent(indicator, "Failed: $title".toJsString())
        }
        else -> {
            setClassName(indicator, "bg-gray-800 text-gray-400 rounded-lg px-3 py-1.5 text-sm border border-gray-700 animate-pulse".toJsString())
            setTextContent(indicator, title.toJsString())
        }
    }
    scrollToBottom()
}

private fun showPermissionDialog(
    toolCallId: String,
    title: String,
    options: List<PermissionOptionInfo>,
) {
    permissionQueue.addLast(QueuedPermission(toolCallId, title, options))
    if (!permissionDialogVisible) {
        showNextPermission()
    }
}

private fun showNextPermission() {
    val queued = permissionQueue.removeFirstOrNull()
    if (queued == null) {
        permissionDialogVisible = false
        val dialog = getElementById("permission-dialog".toJsString()) ?: return
        classListAdd(dialog, "hidden".toJsString())
        return
    }

    permissionDialogVisible = true
    val dialog = getElementById("permission-dialog".toJsString()) ?: return
    val content = getElementById("permission-content".toJsString()) ?: return

    setInnerHTML(content, "".toJsString())

    val heading = createElement("h3".toJsString())
    setClassName(heading, "text-lg font-semibold mb-2".toJsString())
    setTextContent(heading, "Permission Required".toJsString())
    appendChild(content, heading)

    val desc = createElement("p".toJsString())
    setClassName(desc, "text-gray-300 mb-4 break-all".toJsString())
    setTextContent(desc, queued.title.toJsString())
    appendChild(content, desc)

    val btnContainer = createElement("div".toJsString())
    setClassName(btnContainer, "flex flex-wrap gap-2".toJsString())

    for (opt in queued.options) {
        val btn = createElement("button".toJsString())
        val btnClass = if (opt.kind.startsWith("allow")) {
            "bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded-lg font-medium"
        } else {
            "bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded-lg font-medium"
        }
        setClassName(btn, btnClass.toJsString())
        setTextContent(btn, opt.name.toJsString())

        val optionId = opt.optionId
        val tcId = queued.toolCallId
        onClick(btn, wrapVoidCallback {
            val response = WsMessage.PermissionResponse(tcId, optionId)
            val encoded = json.encodeToString(WsMessage.serializer(), response)
            ws?.let { wsSend(it, encoded.toJsString()) }
            showNextPermission()
        })
        appendChild(btnContainer, btn)
    }

    appendChild(content, btnContainer)
    classListRemove(dialog, "hidden".toJsString())
}

private fun updateAgentInfo(text: String) {
    val el = getElementById("agent-info".toJsString()) ?: return
    setTextContent(el, text.toJsString())
}

private var agentWorking = false

private fun setInputEnabled(enabled: Boolean) {
    val input = getElementById("prompt-input".toJsString()) ?: return
    val btn = getElementById("send-btn".toJsString()) ?: return
    val diagnoseBtn = getElementById("diagnose-btn".toJsString())
    setDisabled(input, (!enabled).toJsBoolean())
    agentWorking = !enabled
    if (enabled) {
        setTextContent(btn, "Send".toJsString())
        setClassName(btn, "bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-xl font-medium disabled:opacity-50 disabled:cursor-not-allowed".toJsString())
        setDisabled(btn, false.toJsBoolean())
        diagnoseBtn?.let { classListAdd(it, "hidden".toJsString()) }
        focus(input)
        currentThoughtDiv = null
    } else {
        setTextContent(btn, "Cancel".toJsString())
        setClassName(btn, "bg-red-600 hover:bg-red-700 text-white px-6 py-3 rounded-xl font-medium".toJsString())
        setDisabled(btn, false.toJsBoolean())
        diagnoseBtn?.let { classListRemove(it, "hidden".toJsString()) }
    }
}

private fun startStatusTimer() {
    stopStatusTimer()
    taskStartTime = dateNow().toDouble()
    currentActivity = "Thinking"

    // Create inline status element in messages area
    val messages = getElementById("messages".toJsString()) ?: return
    val wrapper = createElement("div".toJsString())
    setClassName(wrapper, "flex justify-start".toJsString())
    setId(wrapper, "task-status-wrap".toJsString())
    val indicator = createElement("div".toJsString())
    setId(indicator, "task-status".toJsString())
    setClassName(indicator, "text-gray-400 text-sm py-1".toJsString())
    appendChild(wrapper, indicator)
    appendChild(messages, wrapper)

    updateStatusDisplay()
    statusTimerId = jsSetInterval(wrapVoidCallback { updateStatusDisplay() }, 1000.toJsNumber())
}

private fun stopStatusTimer() {
    statusTimerId?.let { jsClearInterval(it) }
    statusTimerId = null
    taskStartTime = 0.0
    val wrapper = getElementById("task-status-wrap".toJsString()) ?: return
    setClassName(wrapper, "hidden".toJsString())
}

private fun updateStatusDisplay() {
    val statusEl = getElementById("task-status".toJsString()) ?: return
    if (taskStartTime == 0.0) return
    val elapsed = ((dateNow().toDouble() - taskStartTime) / 1000).toInt()
    val timeStr = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
    setTextContent(statusEl, "$currentActivity \u00b7 $timeStr".toJsString())
    scrollToBottom()
}

private fun ensureStatusAtBottom() {
    val messages = getElementById("messages".toJsString()) ?: return
    val wrapper = getElementById("task-status-wrap".toJsString()) ?: return
    removeChild(messages, wrapper)
    appendChild(messages, wrapper)
}

private fun isNearBottom(): Boolean {
    val messages = getElementById("messages".toJsString()) ?: return true
    val scrollTop = getScrollTop(messages).toDouble()
    val clientHeight = getClientHeight(messages).toDouble()
    val scrollHeight = getScrollHeight(messages).toDouble()
    return scrollHeight - scrollTop - clientHeight < 100
}

private fun updateScrollButtonVisibility() {
    val btn = getElementById("scroll-to-bottom-btn".toJsString()) ?: return
    if (isNearBottom()) {
        classListAdd(btn, "hidden".toJsString())
    } else {
        classListRemove(btn, "hidden".toJsString())
    }
}

private fun scrollToBottom() {
    if (!isNearBottom()) {
        updateScrollButtonVisibility()
        return
    }
    val messages = getElementById("messages".toJsString()) ?: return
    val height = getScrollHeight(messages)
    setScrollTop(messages, height)
}
