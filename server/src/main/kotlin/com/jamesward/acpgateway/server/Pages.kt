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
                    @keyframes spin {
                        to { transform: rotate(360deg); }
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
        title { +"$agentName - Web Interface for AI Agents" }
        style {
            unsafe {
                raw("""
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0d1117; color: #e6edf3; line-height: 1.6; }
                    a { color: #58a6ff; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    code { background: #161b22; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; color: #79c0ff; }
                    pre { background: #161b22; padding: 16px; border-radius: 8px; overflow-x: auto; margin: 12px 0; }
                    pre code { background: none; padding: 0; }
                    .container { max-width: 800px; margin: 0 auto; padding: 40px 24px 80px; }
                    .hero { text-align: center; padding: 60px 0 40px; }
                    .hero h1 { font-size: 2.5rem; font-weight: 700; margin-bottom: 12px; }
                    .hero p { font-size: 1.2rem; color: #8b949e; max-width: 600px; margin: 0 auto; }
                    h2 { font-size: 1.5rem; font-weight: 600; margin: 48px 0 16px; padding-bottom: 8px; border-bottom: 1px solid #21262d; }
                    h3 { font-size: 1.1rem; font-weight: 600; margin: 24px 0 8px; }
                    p { margin: 8px 0; color: #c9d1d9; }
                    ul, ol { margin: 8px 0 8px 24px; color: #c9d1d9; }
                    li { margin: 4px 0; }
                    .warning { background: #1c1305; border: 1px solid #5a4a1a; border-radius: 8px; padding: 16px; margin: 16px 0; }
                    .warning strong { color: #e3b341; }
                    .step { display: flex; gap: 12px; margin: 12px 0; }
                    .step-num { flex-shrink: 0; width: 28px; height: 28px; background: #21262d; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 0.85rem; color: #58a6ff; }
                    .step-body { flex: 1; }
                    .tabs { display: flex; gap: 0; margin-top: 16px; border-bottom: 1px solid #21262d; }
                    .tab { padding: 8px 16px; cursor: pointer; border-bottom: 2px solid transparent; color: #8b949e; font-size: 0.9rem; }
                    .tab.active { color: #e6edf3; border-bottom-color: #58a6ff; }
                    .tab-content { display: none; }
                    .tab-content.active { display: block; }
                """.trimIndent())
            }
        }
    }
    body {
        div(classes = "container") {
            div(classes = "hero") {
                h1 { +"ACP Web Gateway" }
                p {
                    +"A web interface to AI agents via the "
                    a(href = "https://agentclientprotocol.com/") { +"Agent Client Protocol" }
                    +"."
                }
            }

            h2 { +"What is this?" }
            p {
                +"ACP Web Gateway lets you use AI coding agents (like Claude Code, GitHub Copilot CLI, Kiro CLI, etc) through your browser. "
                +"The "
                code { +"acp2web" }
                +" CLI runs the agent locally on your machine and connects it to a web UI, either via a local Docker server or a remote relay."
            }

            h2 { +"Install" }
            p {
                +"Download the latest "
                code { +"acp2web" }
                +" binary for your platform from "
                a(href = "https://github.com/jamesward/acp-web-gateway/releases/latest") { +"GitHub Releases" }
                +"."
            }

            div(classes = "tabs") {
                div(classes = "tab active") { attributes["onclick"] = "switchTab(event, 'tab-macos')"; +"macOS" }
                div(classes = "tab") { attributes["onclick"] = "switchTab(event, 'tab-linux')"; +"Linux" }
                div(classes = "tab") { attributes["onclick"] = "switchTab(event, 'tab-windows')"; +"Windows" }
            }

            div {
                id = "tab-macos"
                attributes["class"] = "tab-content active"
                pre {
                    code { +"""
                        curl -fsSL https://github.com/jamesward/acp-web-gateway/releases/latest/download/acp2web-macos-arm64 -o acp2web
                        chmod +x acp2web
                        """.trimIndent()
                    }
                }
            }
            div {
                id = "tab-linux"
                attributes["class"] = "tab-content"
                pre {
                    code { +"""
                        curl -fsSL https://github.com/jamesward/acp-web-gateway/releases/latest/download/acp2web-linux-amd64 -o acp2web
                        chmod +x acp2web
                        """.trimIndent()
                    }
                }
            }
            div {
                id = "tab-windows"
                attributes["class"] = "tab-content"
                pre {
                    code { +"""
                        # Download acp2web-windows-amd64.zip from:
                        # https://github.com/jamesward/acp-web-gateway/releases/latest
                        # Extact the contents
                        """.trimIndent()
                    }
                }
            }

            h2 { +"Quick Start" }

            h3 { +"Local Mode (Docker)" }
            p {
                +"By default, "
                code { +"acp2web" }
                +" starts a local Docker container running the gateway server. The agent runs on your machine and connects to the server over localhost."
            }
            pre {
                code { +"""
                    # Start acp2web
                    acp2web
                    
                    # Or, start with an agent from the ACP registry, like:
                    acp2web --agent claude-code

                    # Or use a custom agent command, like:
                    acp2web --agent-command "kiro-cli acp"
                    """.trimIndent()
                }
            }
            p { +"This will print a URL \u2014 open it in your browser to start chatting." }
            p {
                +"Requires "
                a(href = "https://www.docker.com/") { +"Docker" }
                +" to be installed and running."
            }

            h3 { +"Remote Mode (acp2web.com)" }
            p {
                +"Connect to a remote gateway server instead of running Docker locally. The default remote server is "
                code { +"acp2web.com" }
                +"."
            }
            pre {
                code { +"""
                    # Start acp2web in remote mode
                    acp2web --remote
                    
                    # Or start with the default remote and specify the agent, like:
                    acp2web --remote --agent claude-code

                    # Or specify your own server
                    acp2web --remote https://my-server.com
                    """.trimIndent()
                }
            }

            div(classes = "warning") {
                p {
                    strong { +"Security note: " }
                    +"In remote mode, your conversation messages are relayed through the remote server. "
                    +"The agent still runs locally on your machine (with access to your files and tools), but all prompts and responses pass through the relay. "
                    +"Only use remote servers you trust."
                }
            }

            h2 { +"Specifying an Agent" }
            p { +"There are two ways to specify which agent to use:" }

            h3 { +"From the ACP Registry" }
            p {
                +"Use "
                code { +"--agent <id>" }
                +" with an agent ID from the "
                a(href = "https://agentclientprotocol.com/get-started/registry") { +"ACP agent registry" }
                +". Examples:"
            }
            pre { code { +"""acp2web --agent claude-code
acp2web --agent github-copilot-cli""" } }

            h3 { +"Custom Command" }
            p {
                +"Use "
                code { +"--agent-command <command>" }
                +" for any ACP-compatible agent not in the registry:"
            }
            pre { code { +"""acp2web --agent-command "kiro-cli acp"""" } }
            p {
                +"The "
                code { +"--agent" }
                +" and "
                code { +"--agent-command" }
                +" flags are mutually exclusive."
            }

            h2 { +"How It Works" }
            ol {
                li {
                    +"The "
                    code { +"acp2web" }
                    +" CLI spawns the agent as a local subprocess, communicating via the ACP stdio protocol."
                }
                li { +"The CLI connects to the gateway server (local Docker or remote) over WebSocket." }
                li { +"You open the session URL in your browser to interact with the agent." }
                li { +"Messages are relayed between the browser and the agent through the gateway." }
                li { +"The agent runs in your project directory with full local access \u2014 file reads, edits, and tool calls all happen on your machine." }
            }

            p {
                attributes["style"] = "margin-top: 48px; text-align: center; color: #8b949e; font-size: 0.9rem"
                a(href = "https://github.com/jamesward/acp-web-gateway") { +"GitHub" }
                +" \u00b7 "
                a(href = "https://agentclientprotocol.com/") { +"Agent Client Protocol" }
            }
        }

        script {
            unsafe {
                raw("""
                    function switchTab(event, tabId) {
                        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                        document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
                        event.target.classList.add('active');
                        document.getElementById(tabId).classList.add('active');
                    }
                """.trimIndent())
            }
        }
    }
}
