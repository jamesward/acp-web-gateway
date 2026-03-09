package com.jamesward.acpgateway.server

import kotlinx.html.*
import java.util.UUID

fun HTML.chatPage(agentName: String, sessionId: UUID? = null, debug: Boolean = false) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
        script { src = "/webjars/tailwindcss__browser/dist/index.global.js" }
        style {
            attributes["type"] = "text/tailwindcss"
        }
        style {
            unsafe {
                raw("""
                    .message-content pre { background: #030712; padding: 1rem; border-radius: 0.5rem; overflow-x: auto; margin: 0.5rem 0; }
                    .message-content code { font-size: 0.875rem; }
                    .message-content p { margin: 0.25rem 0; }
                    .message-content h1 { font-size: 1.5rem; font-weight: 700; margin: 0.5rem 0; }
                    .message-content h2 { font-size: 1.25rem; font-weight: 700; margin: 0.5rem 0; }
                    .message-content h3 { font-size: 1.125rem; font-weight: 600; margin: 0.25rem 0; }
                    .message-content ul { list-style: disc; padding-left: 1.25rem; margin: 0.25rem 0; }
                    .message-content ol { list-style: decimal; padding-left: 1.25rem; margin: 0.25rem 0; }
                    .message-content li { margin: 0.125rem 0; }
                    .message-content a { color: #60a5fa; text-decoration: underline; }
                    .message-content blockquote { border-left: 4px solid #4b5563; padding-left: 1rem; font-style: italic; color: #9ca3af; margin: 0.5rem 0; }
                    #messages::-webkit-scrollbar { width: 6px; }
                    #messages::-webkit-scrollbar-thumb { background: #475569; border-radius: 3px; }
                """.trimIndent())
            }
        }
    }
    body("bg-gray-900 text-gray-100 flex flex-col h-screen") {
        if (sessionId != null) {
            attributes["data-session-id"] = sessionId.toString()
        }
        if (debug) {
            attributes["data-debug"] = "true"
        }
        div("bg-gray-800 border-b border-gray-700 px-4 py-3 flex items-center gap-3 shrink-0") {
            div("text-lg font-semibold") { +"ACP Gateway" }
            div("text-sm text-gray-400") { id = "agent-info"; +"Connecting..." }
        }
        div("flex-1 overflow-y-auto p-4 space-y-4") {
            id = "messages"
        }
        div("relative shrink-0") {
            button(classes = "hidden absolute -top-12 left-1/2 -translate-x-1/2 bg-gray-700 hover:bg-gray-600 text-gray-300 w-9 h-9 rounded-full flex items-center justify-center border border-gray-600 shadow-lg z-10 cursor-pointer") {
                id = "scroll-to-bottom-btn"
                type = ButtonType.button
                title = "Scroll to bottom"
                // Down arrow SVG
                unsafe {
                    raw("""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>""")
                }
            }
        }
        div("shrink-0 border-t border-gray-700 bg-gray-800 p-4") {
            div("hidden flex flex-wrap gap-2 mb-2") {
                id = "file-preview"
            }
            form(classes = "flex items-center gap-3 w-full max-w-full") {
                id = "prompt-form"
                button(classes = "shrink-0 self-center bg-gray-700 hover:bg-gray-600 text-gray-300 w-10 h-10 rounded-xl flex items-center justify-center text-xl font-bold border border-gray-600") {
                    id = "attach-btn"
                    type = ButtonType.button
                    title = "Attach files"
                    +"+"
                }
                input(classes = "hidden") {
                    id = "file-input"
                    type = InputType.file
                    attributes["multiple"] = "true"
                }
                textArea(classes = "flex-1 min-w-0 bg-gray-700 text-gray-100 border border-gray-600 rounded-xl px-4 py-3 resize-none focus:outline-none focus:ring-2 focus:ring-blue-500 text-base leading-relaxed placeholder-gray-400") {
                    id = "prompt-input"
                    attributes["rows"] = "3"
                    placeholder = "Send a message..."
                }
                div("shrink-0 self-center flex flex-col items-center gap-2") {
                    div("flex items-center gap-2") {
                        button(classes = "bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-xl font-medium disabled:opacity-50 disabled:cursor-not-allowed") {
                            id = "send-btn"
                            type = ButtonType.submit
                            +"Send"
                        }
                        if (debug) {
                            button(classes = "hidden bg-yellow-600 hover:bg-yellow-700 text-white px-4 py-3 rounded-xl font-medium text-sm") {
                                id = "diagnose-btn"
                                type = ButtonType.button
                                +"Diagnose"
                            }
                        }
                    }
                    label(classes = "flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer select-none") {
                        input(classes = "accent-blue-500") {
                            id = "screenshot-toggle"
                            type = InputType.checkBox
                        }
                        +"Screenshot"
                    }
                }
            }
        }
        div("hidden fixed inset-0 bg-black/50 flex items-center justify-center z-50") {
            id = "permission-dialog"
            div("bg-gray-800 rounded-xl p-6 max-w-md w-full mx-4 border border-gray-600") {
                id = "permission-content"
            }
        }
        script { src = "https://html2canvas.hertzen.com/dist/html2canvas.min.js" }
        script { src = "/static/web.js" }
    }
}

fun HTML.landingPage(agentName: String) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
        script { src = "/webjars/tailwindcss__browser/dist/index.global.js" }
    }
    body("bg-gray-900 text-gray-100 flex items-center justify-center h-screen") {
        div("text-center space-y-6") {
            h1("text-3xl font-bold") { +"ACP Gateway" }
            p("text-gray-400") { +"Agent: $agentName" }
        }
    }
}
