package com.jamesward.acpgateway.server

import kotlinx.html.*

fun HTML.chatPage(
    agentName: String,
    debug: Boolean = false,
    dev: Boolean = false,
) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
        style {
            unsafe {
                raw("""
                    @keyframes msg-in {
                        from { opacity: 0; transform: translateY(4px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                """.trimIndent())
            }
        }
    }
    body {
        if (debug) {
            attributes["data-debug"] = "true"
        }
        if (dev) {
            attributes["data-dev"] = "true"
        }

        div { id = "root" }

        script { src = "/static/web.js" }
    }
}

fun HTML.landingPage(agentName: String) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
    }
    body {
        div {
            attributes["style"] = "display: flex; align-items: center; justify-content: center; height: 100vh; text-align: center"
            div {
                h1 { attributes["style"] = "font-size: 1.875rem; font-weight: bold; margin-bottom: 1.5rem"; +"ACP Gateway" }
                p { attributes["style"] = "color: #9ca3af"; +"Agent: $agentName" }
            }
        }
    }
}
