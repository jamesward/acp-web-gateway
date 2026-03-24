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

// ---- Scroll & DOM utilities (JS bridges) ----

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

/** Focus the prompt input textarea. */
@JsFun("""() => {
    requestAnimationFrame(() => {
        const el = document.getElementById('prompt-input');
        if (el) el.focus();
    });
}""")
internal external fun focusPromptInput()

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
    "var d='--bg-body:#0d1117;--bg-header:#161b22;--bg-card:#161b22;--bg-card-hover:#1c2128;--bg-input:#0d1117;--bg-user-msg:#1f6feb;--bg-overlay:rgba(0,0,0,0.6);--border-subtle:#30363d;--text-primary:#e6edf3;--text-secondary:#8b949e;--text-muted:#6e7681;--text-on-blue:#fff;--accent-blue:#1f6feb;--accent-red:#da3633;--accent-yellow:#d29922;--accent-green:#3fb950;--accent-red-hover:#f85149;--code-bg:rgba(110,118,129,0.2);--file-tag-bg:rgba(255,255,255,0.15);--file-tag-color:rgba(255,255,255,0.9);--shadow-color:rgba(0,0,0,0.3);--icon-filter:brightness(0) invert(1);--error-bg:rgba(218,54,51,0.1);--hljs-keyword:#ff7b72;--hljs-string:#a5d6ff;--hljs-number:#79c0ff;--hljs-comment:#8b949e;--hljs-function:#d2a8ff;--hljs-type:#ffa657;--hljs-literal:#79c0ff;--hljs-attr:#79c0ff;--hljs-meta:#8b949e;--hljs-title:#d2a8ff;--hljs-tag:#7ee787;--hljs-name:#7ee787;--hljs-selector:#7ee787;--diff-add-bg:rgba(63,185,80,0.15);--diff-add-color:#3fb950;--diff-del-bg:rgba(218,54,51,0.15);--diff-del-color:#f85149;--diff-hunk-color:#79c0ff;--diff-header-color:#8b949e;color-scheme:dark;';" +
    "var l='--bg-body:#ffffff;--bg-header:#f6f8fa;--bg-card:#f6f8fa;--bg-card-hover:#eaeef2;--bg-input:#ffffff;--bg-user-msg:#1f6feb;--bg-overlay:rgba(0,0,0,0.4);--border-subtle:#d0d7de;--text-primary:#1f2328;--text-secondary:#656d76;--text-muted:#8c959f;--text-on-blue:#fff;--accent-blue:#1f6feb;--accent-red:#cf222e;--accent-yellow:#bf8700;--accent-green:#1a7f37;--accent-red-hover:#a40e26;--code-bg:rgba(175,184,193,0.2);--file-tag-bg:rgba(0,0,0,0.08);--file-tag-color:rgba(0,0,0,0.7);--shadow-color:rgba(0,0,0,0.1);--icon-filter:none;--error-bg:rgba(207,34,46,0.1);--hljs-keyword:#cf222e;--hljs-string:#0a3069;--hljs-number:#0550ae;--hljs-comment:#6e7781;--hljs-function:#8250df;--hljs-type:#953800;--hljs-literal:#0550ae;--hljs-attr:#0550ae;--hljs-meta:#6e7781;--hljs-title:#8250df;--hljs-tag:#116329;--hljs-name:#116329;--hljs-selector:#116329;--diff-add-bg:rgba(26,127,55,0.1);--diff-add-color:#1a7f37;--diff-del-bg:rgba(207,34,46,0.1);--diff-del-color:#cf222e;--diff-hunk-color:#0550ae;--diff-header-color:#6e7781;color-scheme:light;';" +
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

// ---- Syntax highlighting (highlight.js) ----

/**
 * highlight.js highlight function — wraps the npm module.
 * Takes (code, options) where options is {language: string}.
 * Returns an object with .value containing highlighted HTML.
 */
@JsModule("highlight.js/lib/common")
external object hljs : JsAny

@JsFun("(m) => { globalThis.__hljs = m.default || m; }")
private external fun storeHljs(m: JsAny)

/** Initialize highlight.js — stores on globalThis for @JsFun bridges. Call once at startup. */
internal fun initHighlightJs() {
    storeHljs(hljs)
}

/**
 * Post-process HTML from marked to add syntax highlighting and diff rendering.
 * - code.language-diff: custom line-by-line coloring (no hljs)
 * - code.language-*: highlight.js syntax highlighting
 */
@JsFun("""(html) => {
    const hljs = globalThis.__hljs;
    if (!hljs) return html;
    const parser = new DOMParser();
    const doc = parser.parseFromString('<div>' + html + '</div>', 'text/html');
    const codeBlocks = doc.querySelectorAll('pre code[class*="language-"]');
    for (let i = 0; i < codeBlocks.length; i++) {
        const el = codeBlocks[i];
        const classes = el.className;
        if (classes.indexOf('language-diff') !== -1) {
            const text = el.textContent || '';
            const lines = text.split('\n');
            const result = [];
            for (let j = 0; j < lines.length; j++) {
                const line = lines[j];
                const escaped = line.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
                if (line.startsWith('+++') || line.startsWith('---')) {
                    result.push('<span class="diff-header">' + escaped + '</span>');
                } else if (line.startsWith('+')) {
                    result.push('<span class="diff-add">' + escaped + '</span>');
                } else if (line.startsWith('-')) {
                    result.push('<span class="diff-del">' + escaped + '</span>');
                } else if (line.startsWith('@@')) {
                    result.push('<span class="diff-hunk">' + escaped + '</span>');
                } else {
                    result.push('<span>' + escaped + '</span>');
                }
            }
            el.innerHTML = result.join('\n');
            el.parentElement.classList.add('diff-block');
        } else {
            hljs.highlightElement(el);
        }
    }
    return doc.body.firstChild.innerHTML;
}""")
internal external fun highlightCodeBlocks(html: String): String

/** Render markdown with syntax highlighting and diff coloring. */
internal fun renderMarkdown(text: String): String =
    highlightCodeBlocks(dev.kilua.marked.parseMarkdown(text))

/**
 * Format Read tool content: strip cat-n line numbers, syntax-highlight, render with line number gutter.
 * Falls back to plain pre block if parsing fails or no highlight.js available.
 */
@JsFun("""(content, location) => {
    const hljs = globalThis.__hljs;

    // Extension → highlight.js language
    const extMap = {
        kt:'kotlin',kts:'kotlin',java:'java',py:'python',js:'javascript',jsx:'javascript',
        mjs:'javascript',ts:'typescript',tsx:'typescript',rs:'rust',go:'go',rb:'ruby',
        sh:'bash',bash:'bash',zsh:'bash',json:'json',yaml:'yaml',yml:'yaml',xml:'xml',
        html:'html',htm:'html',css:'css',scss:'scss',sql:'sql',md:'markdown',toml:'ini',
        gradle:'groovy',groovy:'groovy',c:'c',h:'c',cpp:'cpp',cc:'cpp',cxx:'cpp',hpp:'cpp',
        cs:'csharp',swift:'swift',dockerfile:'dockerfile',graphql:'graphql',gql:'graphql',
        properties:'properties',conf:'ini',cfg:'ini',txt:'plaintext'
    };

    // Strip markdown code fences if present (e.g. from Write tool diffs)
    let rawContent = content;
    let fenceLang = null;
    const fenceMatch = content.match(/^```(\w*)\n([\s\S]*?)\n```\s*$/);
    if (fenceMatch) {
        fenceLang = fenceMatch[1] || null;
        rawContent = fenceMatch[2];
    }

    // Also handle diff fenced blocks — pass through to renderMarkdown instead
    if (fenceLang === 'diff') {
        const escaped = content.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        return '<pre><code>' + escaped + '</code></pre>';
    }

    // Parse cat-n lines: "  123\tcontent" or "  123→content"
    const lines = rawContent.split('\n');
    const parsed = [];
    let startLine = 1;
    let hasCatN = false;
    for (let i = 0; i < lines.length; i++) {
        const m = lines[i].match(/^\s*(\d+)[\t\u2192](.*)$/);
        if (m) {
            if (i === 0) startLine = parseInt(m[1], 10);
            parsed.push(m[2]);
            hasCatN = true;
        } else if (hasCatN && lines[i] === '' && i === lines.length - 1) {
            // skip trailing empty line
        } else {
            parsed.push(lines[i]);
        }
    }

    const code = parsed.join('\n');

    // Determine language: fence hint > file extension
    let lang = fenceLang || null;
    if (location) {
        const dot = location.lastIndexOf('.');
        const slash = location.lastIndexOf('/');
        if (dot > slash) {
            const ext = location.substring(dot + 1).toLowerCase();
            lang = extMap[ext] || null;
        }
        // Handle Dockerfile (no extension)
        if (!lang) {
            const fname = location.substring(slash + 1).toLowerCase();
            if (fname === 'dockerfile' || fname.startsWith('dockerfile.')) lang = 'dockerfile';
            else if (fname === 'makefile' || fname === 'gnumakefile') lang = 'makefile';
        }
    }

    // Syntax highlight
    let highlighted;
    if (hljs && lang) {
        try {
            const result = hljs.highlight(code, {language: lang, ignoreIllegals: true});
            highlighted = result.value;
        } catch(e) {
            highlighted = code.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        }
    } else if (hljs) {
        try {
            const result = hljs.highlightAuto(code);
            highlighted = result.value;
        } catch(e) {
            highlighted = code.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        }
    } else {
        highlighted = code.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    // Build table rows with line numbers
    const hLines = highlighted.split('\n');
    const rows = [];
    for (let i = 0; i < hLines.length; i++) {
        const num = startLine + i;
        const lineContent = hLines[i] || ' ';
        rows.push('<tr><td class="line-num">' + num + '</td><td class="line-code">' + lineContent + '</td></tr>');
    }

    return '<div class="code-read"><table class="code-table"><tbody>' + rows.join('') + '</tbody></table></div>';
}""")
internal external fun formatReadContent(content: String, location: String): String

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

internal fun toFileAttachments(files: List<FileData>): List<FileAttachment> =
    files.map { FileAttachment(it.name, it.mimeType, it.base64) }
