package com.jamesward.acpgateway.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.webjars.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

enum class GatewayMode { LOCAL, PROXY }

private val logger = LoggerFactory.getLogger("Server")

fun Application.module(manager: AgentProcessManager, agentName: String, mode: GatewayMode, debug: Boolean = false) {
    install(ContentNegotiation) {
        json()
    }
    install(Webjars)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
    }

    routing {
        when (mode) {
            GatewayMode.LOCAL -> {
                get("/") {
                    val session = manager.sessions.values.firstOrNull()
                    if (session != null) {
                        call.respondHtml(HttpStatusCode.OK) {
                            chatPage(agentName, debug = debug)
                        }
                    } else {
                        call.respondText("No session available", status = HttpStatusCode.ServiceUnavailable)
                    }
                }

                webSocket("/ws") {
                    val session = manager.sessions.values.firstOrNull()
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No session available"))
                        return@webSocket
                    }
                    handleChatWebSocket(session, manager)
                }
            }

            GatewayMode.PROXY -> {
                get("/") {
                    call.respondHtml(HttpStatusCode.OK) {
                        landingPage(agentName)
                    }
                }

                get("/s/{sessionId}") {
                    val sessionId = call.parameters["sessionId"]?.let {
                        try { UUID.fromString(it) } catch (_: Exception) { null }
                    }
                    if (sessionId == null || manager.getSession(sessionId) == null) {
                        call.respondText("Session not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    call.respondHtml(HttpStatusCode.OK) {
                        chatPage(agentName, sessionId, debug)
                    }
                }

                webSocket("/s/{sessionId}/ws") {
                    val sessionId = call.parameters["sessionId"]?.let {
                        try { UUID.fromString(it) } catch (_: Exception) { null }
                    }
                    val session = sessionId?.let { manager.getSession(it) }
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Session not found"))
                        return@webSocket
                    }
                    handleChatWebSocket(session, manager)
                }
            }
        }

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        staticResources("/static", "static")
    }
}

fun main(args: Array<String>) {
    val agentId = parseAgentId(args)
    val mode = parseMode(args)
    val port = parsePort(args)
    val debug = args.contains("--debug")
    logger.info("Starting ACP Web Gateway for agent: {} (mode={}, port={}, debug={})", agentId, mode, port, debug)

    val (manager, agentDisplayName) = runBlocking {
        val registry = fetchRegistry()
        val agent = registry.find { it.id == agentId }
            ?: error("Agent '$agentId' not found in registry. Available: ${registry.map { it.id }}")
        logger.info("Found agent: {} ({})", agent.name, agent.version)

        val command = resolveAgentCommand(agent)
        val mgr = AgentProcessManager(command, System.getProperty("user.dir"))
        mgr.start()
        mgr to agent.name
    }

    Runtime.getRuntime().addShutdownHook(Thread { manager.close() })

    if (mode == GatewayMode.LOCAL) {
        runBlocking { manager.createSession() }
    }

    val server = embeddedServer(CIO, port = port) {
        module(manager, agentDisplayName, mode, debug)
    }
    server.start(wait = false)

    if (mode == GatewayMode.LOCAL) {
        logger.info("Server started on http://localhost:{} (redirects to session)", port)
    } else {
        logger.info("Server started on http://localhost:{} (proxy mode, create sessions via UI)", port)
    }

    Thread.currentThread().join()
}

private fun parseAgentId(args: Array<String>): String {
    val idx = args.indexOf("--agent")
    if (idx == -1 || idx + 1 >= args.size) {
        error("Usage: --agent <agent-id>")
    }
    return args[idx + 1]
}

private fun parseMode(args: Array<String>): GatewayMode {
    val idx = args.indexOf("--mode")
    if (idx == -1 || idx + 1 >= args.size) return GatewayMode.LOCAL
    return when (args[idx + 1].lowercase()) {
        "proxy" -> GatewayMode.PROXY
        "local" -> GatewayMode.LOCAL
        else -> error("Unknown mode '${args[idx + 1]}'. Use 'local' or 'proxy'.")
    }
}

private fun parsePort(args: Array<String>): Int {
    val idx = args.indexOf("--port")
    if (idx != -1 && idx + 1 < args.size) {
        return args[idx + 1].toIntOrNull() ?: error("Invalid port '${args[idx + 1]}'")
    }
    val envPort = System.getenv("PORT")
    if (envPort != null) {
        return envPort.toIntOrNull() ?: error("Invalid PORT env var '$envPort'")
    }
    return 8080
}
