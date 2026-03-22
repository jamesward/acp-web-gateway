package com.jamesward.acpgateway.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import com.jamesward.acpgateway.shared.*
import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.initRpc
import dev.kilua.rpc.registerService
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Tracks a relay session where a CLI backend connects to the gateway
 * and the gateway relays messages between the CLI and browser clients.
 */
class RelaySession(val sessionId: UUID) {
    @Volatile
    var backendWs: WebSocketSession? = null
    @Volatile
    var agentId: String? = null
    @Volatile
    var switchInProgress: Boolean = false
    val frontendConnections: MutableSet<WebSocketSession> = ConcurrentHashMap.newKeySet()
    /** Typed channels for RPC clients bridging to this relay (used by ChatServiceImpl). */
    val rpcChannels: MutableSet<SendChannel<WsMessage>> = ConcurrentHashMap.newKeySet()
    val messageCache = java.util.concurrent.ConcurrentLinkedQueue<String>()
}

private val logger = LoggerFactory.getLogger("Server")

fun Application.module(
    registry: List<RegistryAgent>,
    debug: Boolean = false,
    dev: Boolean = false,
    onReload: () -> Unit = {},
) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
    }

    val relaySessions = ConcurrentHashMap<UUID, RelaySession>()

    initRpc {
        registerService<IChatService> { call, _ ->
            val sessionId = call.parameters["sessionId"]?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            }
            ChatServiceImpl(registry, debug, sessionId,
                relayLookup = { id -> relaySessions[id] })
        }
    }

    routing {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                landingPage("ACP Gateway")
            }
        }

        get("/s/{sessionId}") {
            val sessionId = call.parameters["sessionId"]?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            }
            if (sessionId == null) {
                call.respondText("Session not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val hasRelaySession = relaySessions.containsKey(sessionId)
            if (!hasRelaySession) {
                // Session doesn't exist yet — render the page anyway so the browser
                // can connect via RPC and wait for the CLI to attach.
                call.respondHtml(HttpStatusCode.OK) {
                    chatPage(
                        agentName = "ACP Gateway",
                        debug = debug,
                        dev = dev,
                    )
                }
                return@get
            }
            val relayAgentId = relaySessions[sessionId]?.agentId
            val agentName = if (relayAgentId != null) {
                registry.find { it.id == relayAgentId }?.name ?: relayAgentId
            } else {
                "ACP Gateway"
            }
            call.respondHtml(HttpStatusCode.OK) {
                chatPage(
                    agentName = agentName,
                    debug = debug,
                    dev = dev,
                )
            }
        }

        // CLI backend connects here to relay messages to/from browsers
        webSocket("/s/{sessionId}/agent") {
            val sessionId = call.parameters["sessionId"]?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            } ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid session ID"))
                return@webSocket
            }
            val relay = relaySessions.getOrPut(sessionId) { RelaySession(sessionId) }
            relay.backendWs = this
            relay.switchInProgress = false
            val agentParam = call.request.queryParameters["agent"]
            if (agentParam != null) {
                relay.agentId = agentParam
            }
            logger.info("CLI agent connected for relay session {}", sessionId)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        relay.messageCache.add(text)
                        // Forward to raw WebSocket frontend connections
                        val dead = mutableListOf<WebSocketSession>()
                        for (conn in relay.frontendConnections) {
                            try {
                                conn.send(Frame.Text(text))
                            } catch (_: Exception) {
                                dead.add(conn)
                            }
                        }
                        relay.frontendConnections.removeAll(dead.toSet())
                        // Forward to RPC channel listeners (browser via Kilua RPC)
                        if (relay.rpcChannels.isNotEmpty()) {
                            try {
                                val msg = Json.decodeFromString(WsMessage.serializer(), text)
                                val deadChannels = mutableListOf<SendChannel<WsMessage>>()
                                for (ch in relay.rpcChannels) {
                                    try {
                                        ch.trySend(msg)
                                    } catch (_: Exception) {
                                        deadChannels.add(ch)
                                    }
                                }
                                relay.rpcChannels.removeAll(deadChannels.toSet())
                            } catch (_: Exception) {
                                // Ignore decode failures
                            }
                        }
                    }
                }
            } finally {
                relay.backendWs = null
                if (relay.switchInProgress) {
                    // Keep session alive — CLI will reconnect with new agent
                    logger.info("CLI disconnected during agent switch for relay session {}", sessionId)
                } else {
                    for (conn in relay.frontendConnections) {
                        try { conn.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Agent disconnected")) } catch (_: Exception) {}
                    }
                    relaySessions.remove(sessionId)
                    logger.info("CLI agent disconnected from relay session {}", sessionId)
                }
            }
        }

        // Browser relay WS (for relay sessions only)
        webSocket("/s/{sessionId}/ws") {
            val sessionId = call.parameters["sessionId"]?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            }

            val relay = sessionId?.let { relaySessions[it] }
            if (relay == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Use RPC endpoint"))
                return@webSocket
            }

            relay.frontendConnections.add(this)
            logger.info("Browser connected to relay session {}", sessionId)

            // Replay cached messages so the browser gets full state
            for (cachedMsg in relay.messageCache) {
                try {
                    send(Frame.Text(cachedMsg))
                } catch (_: Exception) {
                    break
                }
            }

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val backend = relay.backendWs
                        if (backend != null) {
                            try {
                                backend.send(Frame.Text(frame.readText()))
                            } catch (_: Exception) {}
                        }
                    }
                }
            } finally {
                relay.frontendConnections.remove(this)
                logger.info("Browser disconnected from relay session {}", sessionId)
            }
        }

        // Kilua RPC for browser connections
        route("/s/{sessionId}") {
            applyRoutes(ChatServiceManager)
        }

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        get("/api/sessions/count") {
            call.respondText(relaySessions.size.toString(), ContentType.Text.Plain)
        }

        if (dev) {
            post("/reload") {
                call.respondText("reloading", ContentType.Text.Plain)
                Thread({
                    Thread.sleep(200)
                    logger.info("Reload requested — shutting down")
                    onReload()
                }, "reload-shutdown").start()
            }
        }

        staticResources("/static", "static")
    }
}

data class ServerConfig(
    val port: Int,
    val debug: Boolean,
    val dev: Boolean,
)

fun parseServerConfig(args: Array<String>): ServerConfig {
    return ServerConfig(
        port = parsePort(args),
        debug = args.contains("--debug"),
        dev = args.contains("--dev"),
    )
}

/**
 * Starts the proxy-only server. Blocks the calling thread until shutdown.
 */
fun startServer(config: ServerConfig) {
    logger.info("Starting ACP Web Gateway (proxy mode, port={}, debug={}, dev={})",
        config.port, config.debug, config.dev)

    val registry = runBlocking { fetchRegistry() }

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    server = embeddedServer(CIO, configure = {
        connector {
            this.port = config.port
        }
    }) {
        module(registry, config.debug, config.dev,
            onReload = {
                server.stop(100, 500)
                Runtime.getRuntime().halt(0)
            },
        )
    }
    server.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread({
        logger.info("Shutdown hook — stopping server")
        server.stop(100, 500)
    }, "shutdown-hook"))

    logger.info("Server started on http://localhost:{} (proxy mode)", config.port)

    Thread.currentThread().join()
}

fun main(args: Array<String>) {
    startServer(parseServerConfig(args))
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
