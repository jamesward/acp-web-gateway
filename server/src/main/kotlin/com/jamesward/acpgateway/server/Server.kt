package com.jamesward.acpgateway.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import com.jamesward.acpgateway.shared.WsMessage
import com.jamesward.acpgateway.shared.appStylesheet
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

enum class GatewayMode { LOCAL, PROXY }

private val logger = LoggerFactory.getLogger("Server")

/**
 * A command handler intercepts slash-commands (e.g. `/autopilot`) before they reach the agent.
 * Return a modified [WsMessage.Prompt] to send to the agent instead, or `null` to use the original prompt.
 */
typealias CommandHandler = suspend (prompt: WsMessage.Prompt, session: GatewaySession) -> WsMessage.Prompt?

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
) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
    }

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
                    handleChatWebSocket(session, manager, debug = debug, commandHandler = commandHandler)
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
                    val manager = holder.manager
                    val sessionId = call.parameters["sessionId"]?.let {
                        try { UUID.fromString(it) } catch (_: Exception) { null }
                    }
                    if (manager == null || sessionId == null || manager.getSession(sessionId) == null) {
                        call.respondText("Session not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    val agentName = holder.currentAgentName ?: "ACP Gateway"
                    call.respondHtml(HttpStatusCode.OK) {
                        chatPage(
                            agentName = agentName,
                            sessionId = sessionId,
                            debug = debug,
                            dev = dev,
                            agents = holder.registry,
                            currentAgentId = holder.currentAgentId,
                        )
                    }
                }

                webSocket("/s/{sessionId}/ws") {
                    val manager = holder.manager ?: run {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No agent selected"))
                        return@webSocket
                    }
                    val sessionId = call.parameters["sessionId"]?.let {
                        try { UUID.fromString(it) } catch (_: Exception) { null }
                    }
                    val session = sessionId?.let { manager.getSession(it) }
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Session not found"))
                        return@webSocket
                    }
                    handleChatWebSocket(session, manager, debug = debug, commandHandler = commandHandler)
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
