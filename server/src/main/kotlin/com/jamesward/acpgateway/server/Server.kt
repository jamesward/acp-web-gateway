package com.jamesward.acpgateway.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import com.jamesward.acpgateway.shared.*
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
    val messageCache = java.util.concurrent.ConcurrentLinkedQueue<String>()
}

enum class GatewayMode { LOCAL, PROXY }

private val logger = LoggerFactory.getLogger("Server")

/**
 * Holds the current agent process and supports switching agents at runtime.
 */
class AgentHolder(
    val registry: List<RegistryAgent>,
    private val workingDir: String,
    private val mode: GatewayMode,
) {
    private val lock = Mutex()

    @Volatile
    var manager: AgentProcessManager? = null
        internal set

    @Volatile
    var currentAgent: RegistryAgent? = null
        internal set

    val currentAgentId: String? get() = currentAgent?.id
    val currentAgentName: String? get() = currentAgent?.name

    suspend fun switchAgent(agentId: String) {
        lock.withLock {
            val oldManager = manager
            val agent = registry.find { it.id == agentId }
                ?: error("Agent '$agentId' not found in registry. Available: ${registry.map { it.id }}")

            logger.info("Switching to agent: {} ({})", agent.name, agent.version)
            val command = resolveAgentCommand(agent)
            val mgr = AgentProcessManager(command, workingDir)
            mgr.start()
            if (mode == GatewayMode.LOCAL) {
                mgr.createSession()
            }

            manager = mgr
            currentAgent = agent

            // Close old manager after swapping (so WS connections fail and clients reconnect)
            oldManager?.close()
            logger.info("Agent switched to: {} ({})", agent.name, agent.version)
        }
    }

    fun close() {
        manager?.close()
    }
}

@Serializable
data class ChangeAgentRequest(val agentId: String)

private val apiJson = Json { ignoreUnknownKeys = true }

fun Application.module(
    holder: AgentHolder,
    mode: GatewayMode,
    debug: Boolean = false,
    dev: Boolean = false,
    onReload: () -> Unit = {},
    commandHandler: CommandHandler? = null,
    internalCommands: List<CommandInfo> = emptyList(),
) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
    }

    val relaySessions = ConcurrentHashMap<UUID, RelaySession>()

    routing {
        when (mode) {
            GatewayMode.LOCAL -> {
                get("/") {
                    val agentName = holder.currentAgentName ?: "ACP Gateway"
                    call.respondHtml(HttpStatusCode.OK) {
                        chatPage(
                            agentName = agentName,
                            debug = debug,
                            dev = dev,
                            agents = holder.registry,
                            currentAgentId = holder.currentAgentId,
                        )
                    }
                }

                webSocket("/ws") {
                    val manager = holder.manager
                    if (manager == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No agent selected"))
                        return@webSocket
                    }
                    val session = manager.sessions.values.firstOrNull()
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No session available"))
                        return@webSocket
                    }
                    handleChatWebSocket(session, manager, debug = debug, commandHandler = commandHandler, internalCommands = internalCommands)
                }
            }

            GatewayMode.PROXY -> {

                get("/") {
                    val agentName = holder.currentAgentName ?: "ACP Gateway"
                    call.respondHtml(HttpStatusCode.OK) {
                        landingPage(agentName)
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
                    val manager = holder.manager
                    val hasAgentSession = manager != null && manager.getSession(sessionId) != null
                    val hasRelaySession = relaySessions.containsKey(sessionId)
                    if (!hasAgentSession && !hasRelaySession) {
                        call.respondText("Session not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    val relayAgentId = if (hasRelaySession) relaySessions[sessionId]?.agentId else null
                    val effectiveAgentId = relayAgentId ?: holder.currentAgentId
                    val agentName = if (relayAgentId != null) {
                        holder.registry.find { it.id == relayAgentId }?.name ?: relayAgentId
                    } else {
                        holder.currentAgentName ?: "ACP Gateway"
                    }
                    call.respondHtml(HttpStatusCode.OK) {
                        chatPage(
                            agentName = agentName,
                            sessionId = sessionId,
                            debug = debug,
                            dev = dev,
                            agents = holder.registry,
                            currentAgentId = effectiveAgentId,
                            canSwapAgent = true,
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
                                val dead = mutableListOf<WebSocketSession>()
                                for (conn in relay.frontendConnections) {
                                    try {
                                        conn.send(Frame.Text(text))
                                    } catch (_: Exception) {
                                        dead.add(conn)
                                    }
                                }
                                relay.frontendConnections.removeAll(dead.toSet())
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

                webSocket("/s/{sessionId}/ws") {
                    val sessionId = call.parameters["sessionId"]?.let {
                        try { UUID.fromString(it) } catch (_: Exception) { null }
                    }

                    // Check for relay session first
                    val relay = sessionId?.let { relaySessions[it] }
                    if (relay != null) {
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
                        return@webSocket
                    }

                    // Normal proxy mode: use handleChatWebSocket
                    val manager = holder.manager ?: run {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No agent selected"))
                        return@webSocket
                    }
                    val session = sessionId?.let { manager.getSession(it) }
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Session not found"))
                        return@webSocket
                    }
                    handleChatWebSocket(session, manager, debug = debug, commandHandler = commandHandler, internalCommands = internalCommands)
                }
            }
        }

        post("/api/change-agent") {
            try {
                val body = call.receiveText()
                val request = apiJson.decodeFromString(ChangeAgentRequest.serializer(), body)
                holder.switchAgent(request.agentId)
                call.respondText("ok", ContentType.Text.Plain)
            } catch (e: Exception) {
                logger.error("Failed to change agent", e)
                call.respondText(e.message ?: "Unknown error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
        }

        post("/s/{sessionId}/api/change-agent") {
            try {
                val sessionId = call.parameters["sessionId"]?.let {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                }
                val body = call.receiveText()
                val request = apiJson.decodeFromString(ChangeAgentRequest.serializer(), body)

                // For relay sessions, forward ChangeAgent to CLI backend
                val relay = sessionId?.let { relaySessions[it] }
                if (relay != null) {
                    val backend = relay.backendWs
                    if (backend != null) {
                        relay.switchInProgress = true
                        relay.agentId = request.agentId
                        val msg = apiJson.encodeToString(WsMessage.serializer(), WsMessage.ChangeAgent(request.agentId))
                        backend.send(Frame.Text(msg))
                        call.respondText("ok", ContentType.Text.Plain)
                    } else {
                        call.respondText("CLI backend not connected", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    }
                } else {
                    holder.switchAgent(request.agentId)
                    call.respondText("ok", ContentType.Text.Plain)
                }
            } catch (e: Exception) {
                logger.error("Failed to change agent", e)
                call.respondText(e.message ?: "Unknown error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
        }

        get("/styles.css") {
            call.respondText(appStylesheet(), ContentType.Text.CSS)
        }

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        if (dev) {
            post("/reload") {
                call.respondText("reloading", ContentType.Text.Plain)
                // Run shutdown on a plain thread — not a coroutine that would be
                // cancelled when the server stops.
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
    val agentId: String?,
    val mode: GatewayMode,
    val port: Int,
    val debug: Boolean,
    val dev: Boolean,
    val commandHandler: CommandHandler? = null,
    val internalCommands: List<CommandInfo> = emptyList(),
)

fun parseServerConfig(args: Array<String>): ServerConfig = ServerConfig(
    agentId = parseAgentId(args),
    mode = parseMode(args),
    port = parsePort(args),
    debug = args.contains("--debug"),
    dev = args.contains("--dev"),
)

/**
 * Starts the server with the given config. Returns the manager and server for lifecycle control.
 * Blocks the calling thread until the server is shut down.
 */
fun startServer(config: ServerConfig) {
    logger.info("Starting ACP Web Gateway (agent={}, mode={}, port={}, debug={}, dev={})",
        config.agentId ?: "<none>", config.mode, config.port, config.debug, config.dev)

    val holder = runBlocking {
        val registry = fetchRegistry()
        val h = AgentHolder(registry, System.getProperty("user.dir"), config.mode)
        if (config.agentId != null) {
            h.switchAgent(config.agentId)
        }
        h
    }

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    server = embeddedServer(CIO, configure = {
        connector {
            this.port = config.port
        }
    }) {
        module(holder, config.mode, config.debug, config.dev,
            onReload = {
                holder.close()
                server.stop(100, 500)
                Runtime.getRuntime().halt(0)
            },
            commandHandler = config.commandHandler,
            internalCommands = config.internalCommands,
        )
    }
    server.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread({
        logger.info("Shutdown hook — closing agent and server")
        holder.close()
        server.stop(100, 500)
    }, "shutdown-hook"))

    if (config.mode == GatewayMode.LOCAL) {
        logger.info("Server started on http://localhost:{}", config.port)
    } else {
        logger.info("Server started on http://localhost:{} (proxy mode)", config.port)
    }

    Thread.currentThread().join()
}

fun main(args: Array<String>) {
    startServer(parseServerConfig(args))
}

private fun parseAgentId(args: Array<String>): String? {
    val idx = args.indexOf("--agent")
    if (idx == -1 || idx + 1 >= args.size) return null
    return args[idx + 1]
}

private fun parseMode(args: Array<String>): GatewayMode =
    if (args.contains("--proxy")) GatewayMode.PROXY else GatewayMode.LOCAL


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
