@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.jamesward.acpgateway.web

import com.jamesward.acpgateway.shared.CommandInfo
import com.jamesward.acpgateway.shared.Css
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.Id
import com.jamesward.acpgateway.shared.Swap
import com.jamesward.acpgateway.shared.WsMessage
import com.jamesward.acpgateway.shared.filePreviewHtml
import kotlinx.serialization.json.Json
import web.dom.document
import web.html.HTMLButtonElement
import web.html.HTMLElement
import web.html.HTMLFormElement
import web.html.HTMLInputElement
import web.html.HTMLTextAreaElement
import web.location.location
import web.sockets.WebSocket

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ---- DOM helpers for kotlin-browser branded string types ----

@JsFun("(id) => document.getElementById(id)")
private external fun getEl(id: JsString): JsAny?

@JsFun("(el, cls) => el.classList.add(cls)")
private external fun addCls(el: JsAny, cls: JsString)

@JsFun("(el, cls) => el.classList.remove(cls)")
private external fun rmCls(el: JsAny, cls: JsString)

@JsFun("(el, cls) => el.classList.contains(cls)")
private external fun hasCls(el: JsAny, cls: JsString): JsBoolean

@JsFun("(el, cls) => { el.className = cls; }")
private external fun setCls(el: JsAny, cls: JsString)

@JsFun("(el, html) => { el.innerHTML = html; }")
private external fun setHtml(el: JsAny, html: JsString)

@JsFun("(el, pos, html) => el.insertAdjacentHTML(pos, html)")
private external fun insertHtml(el: JsAny, pos: JsString, html: JsString)

// ---- Event binding (Kotlin/Wasm lambdas aren't JS functions, so @JsFun bridges are needed) ----

@JsFun("(el, handler) => { el.onsubmit = (e) => { e.preventDefault(); handler(); }; }")
private external fun onSubmit(el: JsAny, handler: () -> Unit)

@JsFun("(el, handler) => { el.onkeydown = (e) => handler(e); }")
private external fun onKeyDown(el: JsAny, handler: (JsAny) -> Unit)

@JsFun("(el, handler) => { el.onclick = () => handler(); }")
private external fun onClick(el: JsAny, handler: () -> Unit)

@JsFun("(el, handler) => { el.onchange = () => handler(); }")
private external fun onChange(el: JsAny, handler: () -> Unit)

@JsFun("(el, handler) => { el.addEventListener('input', () => handler()); }")
private external fun onInput(el: JsAny, handler: () -> Unit)

@JsFun("(el, handler) => { el.onscroll = () => handler(); }")
private external fun onScroll(el: JsAny, handler: () -> Unit)

@JsFun("(ws, handler) => { ws.onopen = () => handler(); }")
private external fun wsOnOpen(ws: JsAny, handler: () -> Unit)

@JsFun("(ws, handler) => { ws.onmessage = (e) => handler(e.data); }")
private external fun wsOnMessage(ws: JsAny, handler: (JsString) -> Unit)

@JsFun("(ws, handler) => { ws.onclose = () => handler(); }")
private external fun wsOnClose(ws: JsAny, handler: () -> Unit)

@JsFun("(ws, handler) => { ws.onerror = () => handler(); }")
private external fun wsOnError(ws: JsAny, handler: () -> Unit)

// ---- Keyboard event helpers ----

@JsFun("(e) => e.key")
private external fun eventKey(e: JsAny): JsString

@JsFun("(e) => e.shiftKey")
private external fun eventShiftKey(e: JsAny): JsBoolean

@JsFun("(e) => e.preventDefault()")
private external fun preventDefault(e: JsAny)

// ---- Scroll helpers ----

@JsFun("(el, top) => el.scrollTo({ top: top, behavior: 'smooth' })")
private external fun scrollToSmooth(el: JsAny, top: JsNumber)

// ---- Idiomorph interop ----
// Only our custom beforeNodeMorphed is needed here — idiomorph (patched) deep-merges
// partial callbacks onto its defaults, so unspecified callbacks retain default behavior.

@JsFun("""(el, html) => Idiomorph.morph(el, html, {
    morphStyle: 'outerHTML',
    callbacks: {
        beforeNodeMorphed: function(oldNode, newNode) {
            if (oldNode.tagName === 'DETAILS') {
                if (oldNode.open) newNode.setAttribute('open', '');
                else newNode.removeAttribute('open');
                if (oldNode.hasAttribute('data-user-toggled')) newNode.setAttribute('data-user-toggled', '');
            }
            if (oldNode.hasAttribute && oldNode.hasAttribute('data-user-expanded')) {
                newNode.setAttribute('data-user-expanded', '');
            }
            return true;
        }
    }
})""")
private external fun morphElement(el: JsAny, html: JsString)

// ---- File reading ----

@JsFun("(el, i, nameCallback, dataCallback) => { const f = el.files[i]; nameCallback(f.name); nameCallback(f.type || 'application/octet-stream'); const r = new FileReader(); r.onload = () => { const b64 = r.result.split(',')[1] || ''; dataCallback(b64); }; r.readAsDataURL(f); }")
private external fun readFileAt(el: JsAny, index: JsNumber, nameCallback: JsAny, dataCallback: JsAny)

@JsFun("(dt, i, nameCallback, dataCallback) => { const f = dt.files[i]; nameCallback(f.name); nameCallback(f.type || 'application/octet-stream'); const r = new FileReader(); r.onload = () => { const b64 = r.result.split(',')[1] || ''; dataCallback(b64); }; r.readAsDataURL(f); }")
private external fun readDtFileAt(dt: JsAny, index: JsNumber, nameCallback: JsAny, dataCallback: JsAny)

// ---- Drag & drop / paste ----

@JsFun("(el, handler) => { el.ondragover = (e) => { e.preventDefault(); e.stopPropagation(); }; el.ondrop = (e) => { e.preventDefault(); e.stopPropagation(); handler(e.dataTransfer); }; }")
private external fun onDrop(el: JsAny, handler: JsAny)

@JsFun("(callback) => (dt) => callback(dt)")
private external fun wrapDropCallback(callback: (JsAny) -> Unit): JsAny

@JsFun("(dt) => dt.files ? dt.files.length : 0")
private external fun dtFileCount(dt: JsAny): JsNumber

@JsFun("(el, handler) => { el.addEventListener('paste', (e) => { if (e.clipboardData) { if (e.clipboardData.files && e.clipboardData.files.length > 0) { e.preventDefault(); handler(e.clipboardData); return; } if (e.clipboardData.items) { const dt = new DataTransfer(); for (let i = 0; i < e.clipboardData.items.length; i++) { if (e.clipboardData.items[i].kind === 'file') { dt.items.add(e.clipboardData.items[i].getAsFile()); } } if (dt.files.length > 0) { e.preventDefault(); handler(dt); } } } }); }")
private external fun onPasteFiles(el: JsAny, handler: JsAny)

// ---- Event delegation ----

@JsFun("(el, callback) => { el.addEventListener('click', (e) => { const btn = e.target.closest('[data-tool-call-id]'); if (btn) { callback(btn.getAttribute('data-tool-call-id'), btn.getAttribute('data-option-id')); } }); }")
private external fun onPermissionClick(el: JsAny, callback: JsAny)

@JsFun("(callback) => (a, b) => callback(a, b)")
private external fun wrapTwoStringCallback(callback: (JsString, JsString) -> Unit): JsAny

@JsFun("(el, callback) => { el.addEventListener('click', (e) => { const btn = e.target.closest('[data-cmd-index]'); if (btn) { callback(btn.getAttribute('data-cmd-index')); } }); }")
private external fun onAutocompleteClick(el: JsAny, callback: JsAny)

@JsFun("(el, callback) => { el.addEventListener('click', (e) => { const btn = e.target.closest('[data-file-index]'); if (btn) { callback(btn.getAttribute('data-file-index')); } }); }")
private external fun onFileRemoveClick(el: JsAny, callback: JsAny)

@JsFun("(callback) => (data) => callback(data)")
private external fun wrapStringCallback(callback: (JsString) -> Unit): JsAny

// ---- Debug: console capture & browser state ----

@JsFun("() => { window.__acpConsoleLogs = []; const orig = { log: console.log, warn: console.warn, error: console.error }; ['log','warn','error'].forEach(function(level) { console[level] = function() { var args = Array.prototype.slice.call(arguments); window.__acpConsoleLogs.push({ level: level, message: args.map(String).join(' '), ts: Date.now() }); if (window.__acpConsoleLogs.length > 200) window.__acpConsoleLogs.shift(); orig[level].apply(console, args); }; }); }")
private external fun installConsoleCapture()

@JsFun("() => { if (!window.__acpConsoleLogs) return '[]'; return JSON.stringify(window.__acpConsoleLogs.slice(-50)); }")
private external fun getConsoleLogs(): JsString

// Inspect DOM elements: dumps HTML structure and computed styles for content blocks
@JsFun("""(selector) => {
    var els = document.querySelectorAll(selector);
    if (!els.length) return JSON.stringify({error: 'no elements match: ' + selector, count: 0});
    var results = [];
    for (var i = 0; i < Math.min(els.length, 5); i++) {
        var el = els[i];
        var cs = window.getComputedStyle(el);
        var info = {
            tag: el.tagName.toLowerCase(),
            id: el.id || null,
            classes: el.className,
            padding: cs.padding,
            margin: cs.margin,
            backgroundColor: cs.backgroundColor,
            borderRadius: cs.borderRadius,
            maxWidth: cs.maxWidth,
            width: cs.width,
            display: cs.display,
            fontSize: cs.fontSize,
            outerHTMLTruncated: el.outerHTML.substring(0, 500),
            childCount: el.childElementCount,
            children: []
        };
        for (var j = 0; j < Math.min(el.children.length, 10); j++) {
            var child = el.children[j];
            var ccs = window.getComputedStyle(child);
            info.children.push({
                tag: child.tagName.toLowerCase(),
                classes: child.className,
                padding: ccs.padding,
                margin: ccs.margin,
                backgroundColor: ccs.backgroundColor,
                display: ccs.display
            });
        }
        results.push(info);
    }
    return JSON.stringify({count: els.length, elements: results}, null, 2);
}""")
private external fun inspectElements(selector: JsString): JsString

@JsFun("() => { var msgs = document.getElementById('messages'); var msgCount = msgs ? msgs.childElementCount : 0; var ws = window.__acpWsReadyState !== undefined ? window.__acpWsReadyState : -1; var permDlg = document.getElementById('permission-dialog'); var ssToggle = document.getElementById('screenshot-toggle'); var filePrev = document.getElementById('file-preview'); return JSON.stringify({ messageCount: msgCount, webSocketReadyState: ws, bodyAttributes: Array.from(document.body.attributes).reduce(function(o,a){ o[a.name]=a.value; return o; }, {}), permissionDialogVisible: permDlg ? !permDlg.classList.contains('hidden') : false, screenshotChecked: ssToggle ? ssToggle.checked : false, pendingFileCount: filePrev ? filePrev.childElementCount : 0, viewportWidth: window.innerWidth, viewportHeight: window.innerHeight, userAgent: navigator.userAgent }); }")
private external fun getDomState(): JsString

@JsFun("(v) => { window.__acpWsReadyState = v; }")
private external fun setWsReadyState(v: JsNumber)

@JsFun("(ms, callback) => setTimeout(callback, ms)")
private external fun setTimeout(ms: JsNumber, callback: () -> Unit)

// ---- Audio recording (MediaRecorder API) ----

@JsFun("""() => !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)""")
private external fun hasMediaRecorder(): JsBoolean

@JsFun("""(onData, onEnd) => {
    navigator.mediaDevices.getUserMedia({ audio: true }).then(function(stream) {
        var recorder = new MediaRecorder(stream, { mimeType: MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '' });
        var chunks = [];
        recorder.ondataavailable = function(e) { if (e.data.size > 0) chunks.push(e.data); };
        recorder.onstop = function() {
            stream.getTracks().forEach(function(t) { t.stop(); });
            var blob = new Blob(chunks, { type: recorder.mimeType });
            var reader = new FileReader();
            reader.onload = function() {
                var b64 = reader.result.split(',')[1] || '';
                onData(b64, recorder.mimeType);
            };
            reader.readAsDataURL(blob);
        };
        recorder.onerror = function() {
            stream.getTracks().forEach(function(t) { t.stop(); });
            onEnd();
        };
        recorder.start();
        window.__acpMediaRecorder = recorder;
    }).catch(function() { onEnd(); });
}""")
private external fun startAudioRecording(onData: JsAny, onEnd: () -> Unit)

@JsFun("() => { if (window.__acpMediaRecorder) { window.__acpMediaRecorder.stop(); window.__acpMediaRecorder = null; } }")
private external fun stopAudioRecording()

// ---- Date.now() / timers ----

@JsFun("() => Date.now()")
private external fun dateNow(): JsNumber

@JsFun("(handler, ms) => setInterval(() => handler(), ms)")
private external fun jsSetInterval(handler: () -> Unit, ms: JsNumber): JsNumber

@JsFun("(id) => clearInterval(id)")
private external fun jsClearInterval(id: JsNumber)

// ---- Reload: POST then poll /health until server is back ----

@JsFun("""(url, callback) => {
  fetch(url, { method: 'POST' }).then(() => callback()).catch(() => callback());
}""")
private external fun postRequest(url: JsString, callback: () -> Unit)

@JsFun("""(callback) => {
  var poll = setInterval(function() {
    fetch('/health').then(function(r) {
      if (r.ok) { clearInterval(poll); callback(); }
    }).catch(function() {});
  }, 1000);
}""")
private external fun pollUntilHealthy(callback: () -> Unit)

@JsFun("() => location.reload()")
private external fun reloadPage()

// ---- Agent selection: POST with JSON body ----

@JsFun("""(url, body, callback) => {
  fetch(url, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: body })
    .then(function(r) { callback(r.ok ? 'ok' : 'error: ' + r.status); })
    .catch(function(e) { callback('error: ' + e.message); });
}""")
private external fun postJsonRequest(url: JsString, body: JsString, callback: JsAny)

// ---- Agent dropdown: close on outside click ----

@JsFun("""(modalId, cardCls, hiddenCls) => {
  var m = document.getElementById(modalId);
  if (!m) return;
  m.addEventListener('click', function(e) {
    if (!e.target.closest('.' + cardCls)) {
      m.classList.add(hiddenCls);
    }
  });
}""")
private external fun setupModalOutsideClick(modalId: JsString, cardCls: JsString, hiddenCls: JsString)

// ---- Agent modal/dropdown: click delegation on data-agent-id ----

@JsFun("""(el, callback) => {
  el.addEventListener('click', function(e) {
    var btn = e.target.closest('[data-agent-id]');
    if (btn) { callback(btn.getAttribute('data-agent-id')); }
  });
}""")
private external fun onAgentClick(el: JsAny, callback: JsAny)

// ---- Screenshot via SVG foreignObject → Canvas → base64 PNG ----

@JsFun("""(callback) => {
  try {
    var msgs = document.getElementById('messages');
    if (!msgs) { callback(''); return; }
    var clone = msgs.cloneNode(true);
    clone.removeAttribute('id');
    var css = '';
    for (var i = 0; i < document.styleSheets.length; i++) {
      try {
        var rules = document.styleSheets[i].cssRules;
        for (var j = 0; j < rules.length; j++) css += rules[j].cssText + '\n';
      } catch(e) {}
    }
    var linkEl = document.querySelector('link[href="/styles.css"]');
    if (linkEl && linkEl.sheet) {
      try {
        var rules = linkEl.sheet.cssRules;
        for (var j = 0; j < rules.length; j++) css += rules[j].cssText + '\n';
      } catch(e) {}
    }
    var width = msgs.offsetWidth || 800;
    var height = msgs.scrollHeight || 600;
    var xhtml = new XMLSerializer().serializeToString(clone);
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '">'
      + '<foreignObject width="100%" height="100%">'
      + '<div xmlns="http://www.w3.org/1999/xhtml">'
      + '<style>' + css.replace(/</g, '\\u003c') + '</style>'
      + xhtml
      + '</div></foreignObject></svg>';
    var dataUri = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
    var img = new Image();
    img.onload = function() {
      var canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = Math.min(height, 4096);
      var ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0);
      var pngDataUrl = canvas.toDataURL('image/png');
      var base64 = pngDataUrl.split(',')[1] || '';
      callback(base64);
    };
    img.onerror = function() { callback(''); };
    img.src = dataUri;
  } catch(e) { console.error('Screenshot error:', e); callback(''); }
}""")
private external fun captureScreenshot(callback: JsAny)

// ---- Auto-collapse older blocks to save vertical space ----
// Shows recent (bottom) lines via scrollTop trick + mask-image fade at top.
// Skips user messages and blocks the user manually expanded.

@JsFun("""(messagesId, collapsedCls) => {
    var m = document.getElementById(messagesId);
    if (!m) return;
    var ch = m.children;
    var n = ch.length;
    if (n <= 2) return;
    // Don't collapse if user is scrolled up reading history
    if (m.scrollHeight - m.scrollTop - m.clientHeight > 200) return;
    // Only collapse if overflowing
    if (m.scrollHeight <= m.clientHeight + 100) return;
    for (var i = 0; i < n - 2; i++) {
        if (m.scrollHeight <= m.clientHeight + 100) break;
        var c = ch[i];
        if (c.hasAttribute('data-user-expanded')) continue;
        if (c.classList.contains('msg-wrap-user')) continue;
        var cb = c.querySelector('.content-block > details');
        if (cb && !cb.hasAttribute('data-user-toggled') && cb.open) { cb.removeAttribute('open'); continue; }
        var d = c.querySelector('.tool-block > details');
        if (d && !d.hasAttribute('data-user-toggled') && d.open) { d.removeAttribute('open'); }
    }
}""")
private external fun autoCollapseOlderBlocks(messagesId: JsString, collapsedCls: JsString)

@JsFun("""(messagesId, collapsedCls) => {
    var m = document.getElementById(messagesId);
    if (!m) return;
    m.addEventListener('click', function(e) {
        var el = e.target.closest('.' + collapsedCls);
        if (el) {
            el.classList.remove(collapsedCls);
            var wrap = el.closest('.msg-wrap-assistant');
            if (wrap) wrap.setAttribute('data-user-expanded', '');
        }
    });
    m.addEventListener('toggle', function(e) {
        if (e.target.tagName === 'DETAILS') {
            e.target.setAttribute('data-user-toggled', '');
            if (e.target.open) {
                var wrap = e.target.closest('.msg-wrap-assistant');
                if (wrap) wrap.setAttribute('data-user-expanded', '');
            }
        }
    }, true);
}""")
private external fun setupCollapseClickHandler(messagesId: JsString, collapsedCls: JsString)

// ---- Typed element accessors ----

private fun byId(id: String): HTMLElement? = getEl(id.toJsString())?.unsafeCast<HTMLElement>()
private fun <T : HTMLElement> byId(id: String, type: Unit = Unit): T? = getEl(id.toJsString())?.unsafeCast<T>()

// ---- Global state ----

private var ws: WebSocket? = null
private var agentWorking = false
private var reloading = false
private var taskStartTime: Double = 0.0
private var timerIntervalId: JsNumber? = null
private val pendingFiles = mutableListOf<FileAttachment>()
private var formInitialized = false
private var reconnectDelay = 1000 // ms, doubles on each failure up to 30s
private var switchingAgent = false
private var recording = false
private var availableCommands = listOf<CommandInfo>()
private var autocompleteFiltered = listOf<CommandInfo>()
private var autocompleteSelectedIndex = -1

// ---- Entry point ----

fun main() {
    if (document.body.hasAttribute("data-debug") == true) {
        installConsoleCapture()
    }
    setupAgentSelection()
    // Connect WebSocket if an agent is selected OR if we have a session ID (relay mode)
    if (document.body.hasAttribute("data-agent-id") == true || document.body.hasAttribute("data-session-id") == true) {
        connect()
    }
}

// ---- WebSocket ----

private fun connect() {
    val protocol = if (location.protocol == "https:") "wss" else "ws"
    val host = location.host
    val pathname = location.pathname.trimEnd('/')
    val url = "$protocol://$host$pathname/ws"
    val socket = WebSocket(url)
    ws = socket

    wsOnOpen(socket) {
        setWsReadyState(1.toJsNumber())
        reconnectDelay = 1000
    }
    wsOnMessage(socket) { data -> onMessage(data.toString()) }
    wsOnClose(socket) {
        setWsReadyState(3.toJsNumber())
        if (reloading || switchingAgent) return@wsOnClose
        val el = byId(Id.AGENT_INFO)
        if (el != null) el.textContent = "Reconnecting\u2026"
        setInputEnabled(false)
        stopStatusTimer()
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000)
        setTimeout(delay.toJsNumber()) { connect() }
    }
    wsOnError(socket) { }

    if (!formInitialized) {
        formInitialized = true
        setupForm()
    }
}

private fun sendWs(msg: WsMessage) {
    val encoded = json.encodeToString(WsMessage.serializer(), msg)
    ws?.send(encoded)
}

// ---- Message handler ----

private fun onMessage(data: String) {
    val msg = json.decodeFromString(WsMessage.serializer(), data)
    when (msg) {
        is WsMessage.Connected -> {
            val info = "${msg.agentName} v${msg.agentVersion}"
            val el = byId(Id.AGENT_INFO) ?: return
            el.textContent = info

            // Show CWD
            val cwd = msg.cwd
            if (cwd != null) {
                val sepEl = byId("header-sep")
                if (sepEl != null) rmCls(sepEl, Css.HIDDEN.toJsString())
                val cwdEl = byId(Id.HEADER_CWD)
                if (cwdEl != null) {
                    cwdEl.textContent = cwd
                    rmCls(cwdEl, Css.HIDDEN.toJsString())
                }
            }

            // Hide agent modal and loading overlay (agent is now connected)
            val modal = byId(Id.AGENT_MODAL)
            if (modal != null) addCls(modal, Css.HIDDEN.toJsString())
            val loading = byId(Id.AGENT_LOADING)
            if (loading != null) addCls(loading, Css.HIDDEN.toJsString())
            switchingAgent = false

            // Clear messages before server replays history
            val messages = byId(Css.MESSAGES)
            if (messages != null) setHtml(messages, "".toJsString())
            stopStatusTimer()
            if (msg.agentWorking) {
                setInputEnabled(false)
                startStatusTimer()
            } else {
                setInputEnabled(true)
            }
        }
        is WsMessage.HtmlUpdate -> applyHtmlUpdate(msg)
        is WsMessage.TurnComplete -> {
            stopStatusTimer()
            setInputEnabled(true)
            if (isNearBottom()) scrollToBottom()
        }
        is WsMessage.BrowserStateRequest -> {
            val state = collectBrowserState(msg.query)
            sendWs(WsMessage.BrowserStateResponse(msg.requestId, state))
        }
        is WsMessage.AgentText -> {}
        is WsMessage.AgentThought -> {}
        is WsMessage.ToolCall -> {}
        is WsMessage.PermissionRequest -> {}
        is WsMessage.Error -> {}
        is WsMessage.Prompt -> {}
        is WsMessage.BrowserStateResponse -> {}
        is WsMessage.PermissionResponse -> {}
        is WsMessage.AvailableCommands -> {
            availableCommands = msg.commands
        }
        is WsMessage.Cancel -> {}
        is WsMessage.Diagnose -> {}
        is WsMessage.ChangeAgent -> {}
    }
}

private fun applyHtmlUpdate(msg: WsMessage.HtmlUpdate) {
    val wasAtBottom = isNearBottom()
    when (msg.swap) {
        Swap.Show -> {
            val el = byId(msg.target) ?: return
            rmCls(el, Css.HIDDEN.toJsString())
        }
        Swap.Hide -> {
            val el = byId(msg.target) ?: return
            addCls(el, Css.HIDDEN.toJsString())
        }
        Swap.BeforeEnd -> {
            val el = byId(msg.target) ?: return
            insertHtml(el, "beforeend".toJsString(), msg.html.toJsString())
        }
        Swap.InnerHTML -> {
            val el = byId(msg.target) ?: return
            setHtml(el, msg.html.toJsString())
        }
        Swap.Morph -> {
            val el = getEl(msg.target.toJsString())
            if (el != null) {
                morphElement(el, msg.html.toJsString())
            } else {
                val messages = byId(Css.MESSAGES) ?: return
                insertHtml(messages, "beforeend".toJsString(), msg.html.toJsString())
            }
            // Re-populate the elapsed timer after morph (morph resets it to empty)
            if (taskStartTime > 0.0) updateStatusTimerText()
        }
    }
    // Auto-scroll when the user was already at the bottom before the update.
    // This keeps the user pinned to the bottom as new content streams in,
    // but doesn't fight their scroll position if they scrolled up.
    if (wasAtBottom && (msg.swap == Swap.BeforeEnd || msg.swap == Swap.Morph)) {
        scrollToBottom()
    }
    // Auto-collapse only on new appended blocks
    if (msg.swap == Swap.BeforeEnd) {
        autoCollapseOlderBlocks(Css.MESSAGES.toJsString(), Css.COLLAPSED.toJsString())
    }
}

// ---- Form setup ----

private fun setupForm() {
    val form = byId(Id.PROMPT_FORM)?.unsafeCast<HTMLFormElement>() ?: return
    val input = byId(Id.PROMPT_INPUT)?.unsafeCast<HTMLTextAreaElement>() ?: return

    onSubmit(form) {
        hideAutocomplete()
        if (agentWorking) sendWs(WsMessage.Cancel) else sendPrompt()
    }
    onKeyDown(input) { e ->
        val key = eventKey(e).toString()
        if (autocompleteFiltered.isNotEmpty() && !isAutocompleteHidden()) {
            when (key) {
                "Tab" -> {
                    preventDefault(e)
                    val idx = if (autocompleteSelectedIndex >= 0) autocompleteSelectedIndex else 0
                    if (idx < autocompleteFiltered.size) {
                        completeCommand(autocompleteFiltered[idx].name)
                    }
                    return@onKeyDown
                }
                "Escape" -> {
                    preventDefault(e)
                    hideAutocomplete()
                    return@onKeyDown
                }
                "ArrowDown", "ArrowUp" -> {
                    // Don't intercept — let cursor move in textarea
                }
            }
        }
        if (key == "Enter" && !eventShiftKey(e).toBoolean()) {
            preventDefault(e)
            hideAutocomplete()
            if (agentWorking) sendWs(WsMessage.Cancel) else sendPrompt()
        }
    }
    onInput(input) { onPromptInput() }

    // Attach button
    val attachBtn = byId(Id.ATTACH_BTN)?.unsafeCast<HTMLButtonElement>()
    val fileInput = byId(Id.FILE_INPUT)?.unsafeCast<HTMLInputElement>()
    if (attachBtn != null && fileInput != null) {
        onClick(attachBtn) { fileInput.click() }
        onChange(fileInput) { onFilesSelected(fileInput) }
    }

    // Voice input button (audio recording)
    val voiceBtn = byId(Id.VOICE_BTN)
    if (voiceBtn != null) {
        if (hasMediaRecorder().toBoolean()) {
            onClick(voiceBtn) { toggleVoiceInput() }
        } else {
            addCls(voiceBtn, Css.HIDDEN.toJsString())
        }
    }

    // Drag-and-drop + paste on textarea
    onDrop(input, wrapDropCallback { dt -> onFilesDropped(dt) })
    onPasteFiles(input, wrapDropCallback { cb -> onFilesDropped(cb) })

    // Debug mode buttons
    if (document.body.hasAttribute("data-debug") == true) {
        val diagnoseBtn = byId(Id.DIAGNOSE_BTN)
        if (diagnoseBtn != null) onClick(diagnoseBtn) { sendWs(WsMessage.Diagnose) }

        val reloadBtn = byId(Id.RELOAD_BTN)
        if (reloadBtn != null) onClick(reloadBtn) {
            val info = byId(Id.AGENT_INFO)
            if (info != null) info.textContent = "Reloading\u2026"
            reloading = true
            postRequest("/reload".toJsString()) {
                pollUntilHealthy { reloadPage() }
            }
        }
    }

    // Scroll-to-bottom button
    val scrollBtn = byId(Id.SCROLL_BTN)
    if (scrollBtn != null) onClick(scrollBtn) {
        val messages = byId(Css.MESSAGES) ?: return@onClick
        scrollToSmooth(messages, messages.scrollHeight.toJsNumber())
    }

    // Collapse click handler (expand collapsed blocks on click)
    setupCollapseClickHandler(Css.MESSAGES.toJsString(), Css.COLLAPSED.toJsString())

    // Scroll events
    val messages = byId(Css.MESSAGES)
    if (messages != null) onScroll(messages) { updateScrollButtonVisibility() }

    // Permission event delegation
    val permContent = getEl(Id.PERMISSION_CONTENT.toJsString())
    if (permContent != null) {
        onPermissionClick(permContent, wrapTwoStringCallback { toolCallId, optionId ->
            sendWs(WsMessage.PermissionResponse(toolCallId.toString(), optionId.toString()))
            val dialog = byId(Id.PERMISSION_DIALOG)
            if (dialog != null) addCls(dialog, Css.HIDDEN.toJsString())
        })
    }

    // Autocomplete click delegation
    val acEl = getEl(Id.AUTOCOMPLETE.toJsString())
    if (acEl != null) {
        onAutocompleteClick(acEl, wrapStringCallback { indexStr ->
            val idx = indexStr.toString().toIntOrNull() ?: return@wrapStringCallback
            if (idx < autocompleteFiltered.size) {
                completeCommand(autocompleteFiltered[idx].name)
            }
        })
    }

    // File preview remove event delegation
    val filePreview = getEl(Id.FILE_PREVIEW.toJsString())
    if (filePreview != null) {
        onFileRemoveClick(filePreview, wrapStringCallback { indexStr ->
            val idx = indexStr.toString().toIntOrNull() ?: return@wrapStringCallback
            if (idx < pendingFiles.size) {
                pendingFiles.removeAt(idx)
                renderFilePreview()
            }
        })
    }
}

// ---- Voice input (audio recording) ----

private fun toggleVoiceInput() {
    val btn = byId(Id.VOICE_BTN) ?: return
    if (recording) {
        stopAudioRecording()
        // UI reset happens in the onData/onEnd callbacks
        return
    }
    recording = true
    setCls(btn, "${Css.VOICE_BTN} ${Css.VOICE_BTN_ACTIVE}".toJsString())
    startAudioRecording(
        wrapTwoStringCallback { base64, mimeType ->
            recording = false
            val b = byId(Id.VOICE_BTN)
            if (b != null) setCls(b, Css.VOICE_BTN.toJsString())

            val data = base64.toString()
            if (data.isEmpty()) return@wrapTwoStringCallback

            val ext = if (mimeType.toString().contains("webm")) "webm" else "ogg"
            val file = FileAttachment(name = "recording.$ext", mimeType = mimeType.toString(), data = data)

            setInputEnabled(false)
            startStatusTimer()
            sendWs(WsMessage.Prompt("Do what is described in this audio file", null, listOf(file)))
        },
        {
            recording = false
            val b = byId(Id.VOICE_BTN)
            if (b != null) setCls(b, Css.VOICE_BTN.toJsString())
        },
    )
}

// ---- Prompt ----

private fun sendPrompt() {
    val input = byId(Id.PROMPT_INPUT)?.unsafeCast<HTMLTextAreaElement>() ?: return
    val text = input.value.trim()
    if (text.isEmpty()) return

    input.value = ""
    setInputEnabled(false)
    startStatusTimer()

    val files = pendingFiles.toList()
    clearFiles()

    val screenshotToggle = byId(Id.SCREENSHOT_TOGGLE)?.unsafeCast<HTMLInputElement>()
    val wantScreenshot = screenshotToggle?.checked == true

    if (wantScreenshot) {
        captureScreenshot(wrapStringCallback { base64 ->
            val screenshot = base64.toString().ifEmpty { null }
            sendWs(WsMessage.Prompt(text, screenshot, files))
        })
    } else {
        sendWs(WsMessage.Prompt(text, null, files))
    }
}

// ---- File handling ----

private fun onFilesSelected(fileInput: HTMLInputElement) {
    val fileList = fileInput.files ?: return
    for (i in 0 until fileList.length) {
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
    fileInput.value = ""
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
    val preview = byId(Id.FILE_PREVIEW) ?: return
    if (pendingFiles.isEmpty()) {
        setHtml(preview, "".toJsString())
        addCls(preview, Css.HIDDEN.toJsString())
        return
    }
    rmCls(preview, Css.HIDDEN.toJsString())
    setHtml(preview, filePreviewHtml(pendingFiles.map { it.name }).toJsString())
}

private fun clearFiles() {
    pendingFiles.clear()
    val preview = byId(Id.FILE_PREVIEW) ?: return
    setHtml(preview, "".toJsString())
    addCls(preview, Css.HIDDEN.toJsString())
}

// ---- Input state ----

private fun setInputEnabled(enabled: Boolean) {
    val input = byId(Id.PROMPT_INPUT)?.unsafeCast<HTMLTextAreaElement>() ?: return
    val btn = byId(Id.SEND_BTN)?.unsafeCast<HTMLButtonElement>() ?: return
    val diagnoseBtn = byId(Id.DIAGNOSE_BTN)
    input.disabled = !enabled
    agentWorking = !enabled
    if (enabled) {
        btn.textContent = "Send"
        setCls(btn, Css.SEND_BTN.toJsString())
        btn.disabled = false
        if (diagnoseBtn != null) addCls(diagnoseBtn, Css.HIDDEN.toJsString())
        input.focus()
    } else {
        btn.textContent = "Cancel"
        setCls(btn, "${Css.SEND_BTN} ${Css.SEND_BTN_CANCEL}".toJsString())
        btn.disabled = false
        if (diagnoseBtn != null) rmCls(diagnoseBtn, Css.HIDDEN.toJsString())
    }
}

// ---- Status timer ----

private fun formatElapsed(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun updateStatusTimerText() {
    val elapsed = dateNow().toDouble() - taskStartTime
    val elapsedStr = formatElapsed(elapsed)
    // Update the elapsed span inside the thinking block header
    val thoughtEl = byId(Id.THOUGHT_ELAPSED)
    if (thoughtEl != null) {
        thoughtEl.unsafeCast<HTMLElement>().textContent = elapsedStr
    }
}

private fun startStatusTimer() {
    stopStatusTimer()
    taskStartTime = dateNow().toDouble()
    updateStatusTimerText()
    timerIntervalId = jsSetInterval(::updateStatusTimerText, 1000.toJsNumber())
}

private fun stopStatusTimer() {
    taskStartTime = 0.0
    val id = timerIntervalId
    if (id != null) {
        jsClearInterval(id)
        timerIntervalId = null
    }
    // Clear the elapsed text in the thinking block header
    val thoughtEl = byId(Id.THOUGHT_ELAPSED)
    if (thoughtEl != null) {
        thoughtEl.unsafeCast<HTMLElement>().textContent = ""
    }
}

// ---- Scroll management ----

private fun isNearBottom(): Boolean {
    val messages = byId(Css.MESSAGES) ?: return true
    return messages.scrollHeight - messages.scrollTop - messages.clientHeight < 100
}

private fun updateScrollButtonVisibility() {
    val btn = byId(Id.SCROLL_BTN) ?: return
    if (isNearBottom()) addCls(btn, Css.HIDDEN.toJsString()) else rmCls(btn, Css.HIDDEN.toJsString())
}

private fun scrollToBottom() {
    val messages = byId(Css.MESSAGES) ?: return
    messages.scrollTop = messages.scrollHeight.toDouble()
    updateScrollButtonVisibility()
}

// ---- Agent selection ----

private fun setupAgentSelection() {
    // Agent modal: click on agent rows
    val modal = getEl(Id.AGENT_MODAL.toJsString())
    if (modal != null) {
        onAgentClick(modal, wrapStringCallback { agentId ->
            changeAgent(agentId.toString())
        })
    }

    // Swap button: toggle modal visibility
    val swapBtn = getEl(Id.AGENT_SWAP_BTN.toJsString())
    if (swapBtn != null) {
        onClick(swapBtn) {
            val m = byId(Id.AGENT_MODAL) ?: return@onClick
            if (hasCls(m, Css.HIDDEN.toJsString()).toBoolean()) {
                rmCls(m, Css.HIDDEN.toJsString())
            } else {
                addCls(m, Css.HIDDEN.toJsString())
            }
        }
    }

    // Close modal when clicking the overlay background
    if (modal != null) {
        setupModalOutsideClick(
            Id.AGENT_MODAL.toJsString(),
            Css.AGENT_MODAL_CARD.toJsString(),
            Css.HIDDEN.toJsString(),
        )
    }
}

private fun changeAgent(agentId: String) {
    // Don't switch to the already-selected agent
    if (document.body.getAttribute("data-agent-id") == agentId) return

    switchingAgent = true

    // Show loading overlay
    val loading = byId(Id.AGENT_LOADING)
    if (loading != null) rmCls(loading, Css.HIDDEN.toJsString())

    // Hide modal if visible
    val modal = byId(Id.AGENT_MODAL)
    if (modal != null) addCls(modal, Css.HIDDEN.toJsString())

    // Close existing WebSocket
    ws?.close()
    ws = null

    // POST to server to switch agent
    val body = """{"agentId":"$agentId"}"""
    val apiPath = "${location.pathname.trimEnd('/')}/api/change-agent"
    postJsonRequest(apiPath.toJsString(), body.toJsString(), wrapStringCallback { result ->
        if (result.toString().startsWith("ok")) {
            // Reload page to get fresh state with new agent
            reloadPage()
        } else {
            // Error - hide loading, show modal again
            switchingAgent = false
            val loadingEl = byId(Id.AGENT_LOADING)
            if (loadingEl != null) addCls(loadingEl, Css.HIDDEN.toJsString())
            val modalEl = byId(Id.AGENT_MODAL)
            if (modalEl != null && document.body.hasAttribute("data-agent-id") != true) {
                rmCls(modalEl, Css.HIDDEN.toJsString())
            }
        }
    })
}

// ---- Autocomplete ----

private fun onPromptInput() {
    val input = byId(Id.PROMPT_INPUT)?.unsafeCast<HTMLTextAreaElement>() ?: return
    val text = input.value
    if (availableCommands.isEmpty() || text.isEmpty() || !text.startsWith("/") || text.contains('\n')) {
        hideAutocomplete()
        return
    }
    val query = text.removePrefix("/").lowercase()
    val filtered = if (query.isEmpty()) {
        availableCommands
    } else {
        availableCommands.filter { cmd ->
            cmd.name.lowercase().contains(query)
        }
    }
    if (filtered.isEmpty()) {
        hideAutocomplete()
        return
    }
    autocompleteFiltered = filtered
    autocompleteSelectedIndex = 0
    showAutocomplete()
}

private fun isAutocompleteHidden(): Boolean {
    val el = byId(Id.AUTOCOMPLETE) ?: return true
    return hasCls(el, Css.HIDDEN.toJsString()).toBoolean()
}

private fun showAutocomplete() {
    val el = byId(Id.AUTOCOMPLETE) ?: return
    val html = buildString {
        for ((i, cmd) in autocompleteFiltered.withIndex()) {
            val activeCls = if (i == autocompleteSelectedIndex) " ${Css.AUTOCOMPLETE_ACTIVE}" else ""
            val escapedName = cmd.name.replace("&", "&amp;").replace("<", "&lt;")
            val escapedDesc = cmd.description.replace("&", "&amp;").replace("<", "&lt;")
            append("<div class=\"${Css.AUTOCOMPLETE_ITEM}$activeCls\" data-cmd-index=\"$i\" title=\"$escapedDesc\">")
            append("<div class=\"${Css.AUTOCOMPLETE_NAME}\">/$escapedName</div>")
            append("</div>")
        }
    }
    setHtml(el, html.toJsString())
    rmCls(el, Css.HIDDEN.toJsString())
}

private fun hideAutocomplete() {
    val el = byId(Id.AUTOCOMPLETE) ?: return
    addCls(el, Css.HIDDEN.toJsString())
    autocompleteFiltered = emptyList()
    autocompleteSelectedIndex = -1
}

private fun completeCommand(name: String) {
    val input = byId(Id.PROMPT_INPUT)?.unsafeCast<HTMLTextAreaElement>() ?: return
    input.value = "/$name "
    hideAutocomplete()
    input.focus()
}

// ---- Browser state (debug) ----

private fun collectBrowserState(query: String): String {
    return when {
        query == "console" -> getConsoleLogs().toString()
        query == "dom" -> getDomState().toString()
        query == "all" -> {
            val console = getConsoleLogs().toString()
            val dom = getDomState().toString()
            """{"console":$console,"dom":$dom}"""
        }
        query.startsWith("inspect:") -> {
            val selector = query.removePrefix("inspect:")
            inspectElements(selector.toJsString()).toString()
        }
        else -> """{"error":"unknown query: $query"}"""
    }
}
