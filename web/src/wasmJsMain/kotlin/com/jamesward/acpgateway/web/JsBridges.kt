@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jamesward.acpgateway.web

import com.jamesward.acpgateway.shared.FileAttachment
import js.typedarrays.Uint8Array
import web.blob.arrayBuffer
import web.file.File
import web.html.HTMLCanvasElement
import web.html.HTMLElement
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
internal external fun installConsoleCapture()

/** Return captured console entries as JSON string. */
@JsFun("""() => {
    return JSON.stringify(globalThis.__consoleBuffer || []);
}""")
internal external fun getConsoleLogs(): String

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
internal external fun getDomState(): String

/** Check if #messages is scrolled to (near) the bottom. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (!el) return true;
    return (el.scrollHeight - el.scrollTop - el.clientHeight) < 40;
}""")
internal external fun isMessagesAtBottom(): Boolean

/** Scroll #messages to the bottom. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (el) el.scrollTop = el.scrollHeight;
}""")
internal external fun scrollMessagesToBottom()

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
internal external fun downloadTextFile(filename: String, content: String)

/** Install a scroll listener on #messages that tracks atBottom in a JS global. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (!el) return;
    globalThis.__messagesAtBottom = true;
    el.addEventListener('scroll', () => {
        globalThis.__messagesAtBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) < 40;
    });
}""")
internal external fun installScrollListener()

/** Read the current atBottom state from the JS global, treating no-overflow as at-bottom. */
@JsFun("""() => {
    const el = document.getElementById('messages');
    if (el && el.scrollHeight <= el.clientHeight) return true;
    return globalThis.__messagesAtBottom !== false;
}""")
internal external fun readScrollAtBottom(): Boolean

// ---- Theme management (JS bridges) ----

/** Inject theme CSS custom properties and apply saved preference from localStorage. */
@JsFun("() => {" +
    "if(document.getElementById('theme-vars'))return;" +
    "var s=document.createElement('style');s.id='theme-vars';" +
    "var d='--bg-body:#0d1117;--bg-header:#161b22;--bg-card:#161b22;--bg-card-hover:#1c2128;--bg-input:#0d1117;--bg-user-msg:#1f6feb;--bg-overlay:rgba(0,0,0,0.6);--border-subtle:#30363d;--text-primary:#e6edf3;--text-secondary:#8b949e;--text-muted:#6e7681;--text-on-blue:#fff;--accent-blue:#1f6feb;--accent-red:#da3633;--accent-yellow:#d29922;--accent-green:#3fb950;--accent-red-hover:#f85149;--code-bg:rgba(110,118,129,0.2);--file-tag-bg:rgba(255,255,255,0.15);--file-tag-color:rgba(255,255,255,0.9);--shadow-color:rgba(0,0,0,0.3);--icon-filter:brightness(0) invert(1);--error-bg:rgba(218,54,51,0.1);color-scheme:dark;';" +
    "var l='--bg-body:#ffffff;--bg-header:#f6f8fa;--bg-card:#f6f8fa;--bg-card-hover:#eaeef2;--bg-input:#ffffff;--bg-user-msg:#1f6feb;--bg-overlay:rgba(0,0,0,0.4);--border-subtle:#d0d7de;--text-primary:#1f2328;--text-secondary:#656d76;--text-muted:#8c959f;--text-on-blue:#fff;--accent-blue:#1f6feb;--accent-red:#cf222e;--accent-yellow:#bf8700;--accent-green:#1a7f37;--accent-red-hover:#a40e26;--code-bg:rgba(175,184,193,0.2);--file-tag-bg:rgba(0,0,0,0.08);--file-tag-color:rgba(0,0,0,0.7);--shadow-color:rgba(0,0,0,0.1);--icon-filter:none;--error-bg:rgba(207,34,46,0.1);color-scheme:light;';" +
    "s.textContent=':root{'+d+'}@media(prefers-color-scheme:light){:root:not([data-theme=dark]){'+l+'}}[data-theme=light]{'+l+'}[data-theme=dark]{'+d+'}';" +
    "document.head.insertBefore(s,document.head.firstChild);" +
    "try{var v=localStorage.getItem('acp-theme');if(v)document.documentElement.setAttribute('data-theme',v);}catch(e){}" +
    "}")
internal external fun installTheme()

/** Get current theme preference: "auto", "light", or "dark". */
@JsFun("() => { try{return localStorage.getItem('acp-theme')||'auto';}catch(e){return 'auto';} }")
internal external fun getThemePreference(): String

/** Cycle theme: auto -> opposite-of-system -> same-as-system -> auto. Returns new preference. */
@JsFun("""(current) => {
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const opposite = systemDark ? 'light' : 'dark';
    const same = systemDark ? 'dark' : 'light';
    const next = current === 'auto' ? opposite : current === opposite ? same : 'auto';
    if (next === 'auto') {
        try{localStorage.removeItem('acp-theme');}catch(e){}
        document.documentElement.removeAttribute('data-theme');
    } else {
        try{localStorage.setItem('acp-theme', next);}catch(e){}
        document.documentElement.setAttribute('data-theme', next);
    }
    return next;
}""")
internal external fun cycleTheme(current: String): String

/** Opens a file picker dialog and returns the selected FileList via a Promise. */
@JsFun("""(multiple) => new Promise(function(resolve) {
    var input = document.createElement('input');
    input.type = 'file';
    input.multiple = multiple;
    input.addEventListener('change', function() { resolve(input.files); });
    input.click();
})""")
internal external fun pickFiles(multiple: Boolean): Promise<JsAny?>

/** Shared ktor HttpClient for simple requests */
internal val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.js.Js)

@JsModule("html2canvas")
external fun html2canvas(htmlElement: HTMLElement): Promise<HTMLCanvasElement>

// ---- File reading utilities ----

internal data class FileData(val name: String, val mimeType: String, val base64: String)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal suspend fun readFile(file: File): FileData {
    val buffer = file.arrayBuffer()
    val uint8 = Uint8Array(buffer)
    val bytes = ByteArray(uint8.length) { uint8[it].toInt().toByte() }
    val base64 = kotlin.io.encoding.Base64.encode(bytes)
    return FileData(file.name, file.type.ifEmpty { "application/octet-stream" }, base64)
}

internal suspend fun readFileList(files: web.file.FileList): List<FileData> {
    val result = mutableListOf<FileData>()
    for (i in 0 until files.length) {
        val file = files.item(i) ?: continue
        result.add(readFile(file))
    }
    return result
}

internal fun collectBrowserState(query: String): String {
    return when (query) {
        "console" -> getConsoleLogs()
        "dom" -> getDomState()
        else -> {
            // "all" or unrecognized — return both
            """{"console":${getConsoleLogs()},"dom":${getDomState()}}"""
        }
    }
}

internal fun toFileAttachments(files: List<FileData>): List<FileAttachment> =
    files.map { FileAttachment(it.name, it.mimeType, it.base64) }
