package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.mcp.*
import com.jamesward.acpgateway.shared.*
import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.initRpc
import dev.kilua.rpc.registerService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("DevServer")

/**
 * Holds the current agent process and supports switching agents at runtime.
 * Used in dev mode (test scope) only.
 */
class AgentHolder(
    val registry: List<RegistryAgent>,
    private val workingDir: String,
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

    /** Error message from a failed agent start, cleared on successful switch. */
    @Volatile
    var startupError: String? = null

    suspend fun switchAgent(agentId: String) {
        lock.withLock {
            val oldManager = manager
            val agent = registry.find { it.id == agentId }
                ?: error("Agent '$agentId' not found in registry. Available: ${registry.map { it.id }}")

            logger.info("Switching to agent: {} ({})", agent.name, agent.version)
            val command = resolveAgentCommand(agent)
            val mgr = AgentProcessManager(command, workingDir)
            mgr.start()
            mgr.createSession()

            manager = mgr
            currentAgent = agent
            startupError = null

            oldManager?.close()
            logger.info("Agent switched to: {} ({})", agent.name, agent.version)
        }
    }

    suspend fun switchAgentCommand(command: ProcessCommand) {
        lock.withLock {
            val oldManager = manager
            val displayName = command.command.substringAfterLast('/')

            logger.info("Switching to custom agent command: {}", command.command)
            val mgr = AgentProcessManager(command, workingDir)
            mgr.start()
            mgr.createSession()

            manager = mgr
            currentAgent = RegistryAgent(
                id = displayName,
                name = displayName,
                version = "custom",
            )
            startupError = null

            oldManager?.close()
            logger.info("Custom agent started: {}", displayName)
        }
    }

    /**
     * Start in simulated agent mode using a fake session.
     * No real agent process is spawned — use /simulate-<name> commands to replay JSON data.
     */
    suspend fun setupSimulatedAgent() {
        lock.withLock {
            val oldManager = manager
            val command = ProcessCommand("fake", emptyList())
            val mgr = AgentProcessManager(command, workingDir)
            mgr.agentName = "Simulated Agent"
            mgr.agentVersion = "1.0.0"

            val clientOps = GatewayClientOperations()
            val fakeSession = ControllableFakeClientSession()
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val session = GatewaySession(
                id = java.util.UUID.randomUUID(),
                clientSession = fakeSession,
                clientOps = clientOps,
                cwd = workingDir,
                scope = testScope,
                store = mgr.store,
            )
            session.ready = true
            mgr.sessions[session.id] = session

            manager = mgr
            currentAgent = RegistryAgent(
                id = "simulate",
                name = "Simulated Agent",
                version = "1.0.0",
            )
            startupError = null

            oldManager?.close()
            logger.info("Simulated agent started")
        }
    }

    fun close() {
        manager?.close()
    }
}

/**
 * Dev mode Ktor module with in-process agent (LOCAL mode).
 * Chat page at `/`, Kilua RPC at root level, no session ID prefix.
 */
fun Application.devModule(
    holder: AgentHolder,
    debug: Boolean = false,
    dev: Boolean = false,
    onReload: () -> Unit = {},
    commandHandler: CommandHandler? = null,
    internalCommands: List<CommandInfo> = emptyList(),
    promptInterceptor: PromptInterceptor? = null,
    captureCallback: CaptureCallback? = null,
) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
    }

    installMcpJsonSerializer()

    initRpc {
        registerService<IChatService> { _, _ ->
            DevChatServiceImpl(holder, debug, commandHandler, internalCommands, promptInterceptor, captureCallback)
        }
    }

    val mcpScope = CoroutineScope(Dispatchers.Default)
    val mcpTaskManager = TaskManager(
        executorFactory = {
            DevTaskExecutor(
                sessionProvider = { holder.manager?.sessions?.values?.firstOrNull() },
                scope = mcpScope,
            )
        },
        scope = mcpScope,
    )

    installMcp(path = "/mcp", mcpTaskManager)

    routing {
        get("/") {
            val agentName = holder.currentAgentName ?: "ACP Gateway"
            call.respondHtml(HttpStatusCode.OK) {
                chatPage(
                    agentName = agentName,
                    debug = debug,
                    dev = dev,
                )
            }
        }

        applyRoutes(ChatServiceManager)

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
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

/**
 * ChatServiceImpl for dev mode — direct in-process agent, no relay.
 */
class DevChatServiceImpl(
    private val holder: AgentHolder,
    private val debug: Boolean,
    private val commandHandler: CommandHandler?,
    private val internalCommands: List<CommandInfo>,
    private val promptInterceptor: PromptInterceptor? = null,
    private val captureCallback: CaptureCallback? = null,
) : IChatService {
    override suspend fun chat(
        input: kotlinx.coroutines.channels.ReceiveChannel<WsMessage>,
        output: kotlinx.coroutines.channels.SendChannel<WsMessage>,
    ) {
        while (true) {
            val manager = holder.manager
            if (manager == null) {
                val err = holder.startupError
                if (err != null) {
                    output.send(WsMessage.Connected("Agent failed to start", "", agentWorking = false))
                    output.send(WsMessage.Error(err))
                    holder.startupError = null
                }
                if (err == null) {
                    output.send(WsMessage.Connected("No agent selected", "", agentWorking = false))
                }
                if (holder.registry.isNotEmpty()) {
                    output.send(WsMessage.AvailableAgents(
                        agents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                        currentAgentId = null,
                    ))
                    output.send(WsMessage.AvailableCommands(emptyList()))
                }
                val agentId = waitForChangeAgent(input)
                    ?: return
                try {
                    holder.switchAgent(agentId)
                } catch (e: Exception) {
                    logger.error("Failed to start agent '{}'", agentId, e)
                    output.send(WsMessage.Error("Failed to start agent '$agentId': ${e.message}"))
                }
                continue
            }
            val session = manager.sessions.values.firstOrNull()
            if (session == null) {
                output.send(WsMessage.Error("No session available"))
                return
            }
            try {
                handleChatChannels(
                    input, output, session, manager,
                    debug = debug,
                    commandHandler = commandHandler,
                    internalCommands = internalCommands,
                    availableAgents = holder.registry.map { AgentInfo(it.id, it.name, it.icon, it.description) },
                    currentAgentId = holder.currentAgentId,
                    promptInterceptor = promptInterceptor,
                    captureCallback = captureCallback,
                )
                return
            } catch (e: AgentSwitchException) {
                try {
                    holder.switchAgent(e.agentId)
                } catch (startErr: Exception) {
                    logger.error("Failed to switch to agent '{}'", e.agentId, startErr)
                    output.send(WsMessage.Error("Failed to start agent '${e.agentId}': ${startErr.message}"))
                }
            }
        }
    }

    private suspend fun waitForChangeAgent(
        input: kotlinx.coroutines.channels.ReceiveChannel<WsMessage>,
    ): String? {
        for (msg in input) {
            if (msg is WsMessage.ChangeAgent) return msg.agentId
        }
        return null
    }
}

data class DevServerConfig(
    val agentId: String?,
    val agentCommand: String?,
    val port: Int,
    val debug: Boolean,
    val dev: Boolean,
    val commandHandler: CommandHandler? = null,
    val internalCommands: List<CommandInfo> = emptyList(),
    val promptInterceptor: PromptInterceptor? = null,
    val captureCallback: CaptureCallback? = null,
)

fun parseDevServerConfig(args: Array<String>): DevServerConfig {
    val agentId = args.indexOf("--agent").let { idx ->
        if (idx == -1 || idx + 1 >= args.size) null else args[idx + 1]
    }
    val agentCommand = args.indexOf("--agent-command").let { idx ->
        if (idx == -1 || idx + 1 >= args.size) null else args[idx + 1]
    }
    require(agentId == null || agentCommand == null) {
        "--agent and --agent-command are mutually exclusive"
    }
    val port = args.indexOf("--port").let { idx ->
        if (idx != -1 && idx + 1 < args.size) {
            args[idx + 1].toIntOrNull() ?: error("Invalid port '${args[idx + 1]}'")
        } else {
            System.getenv("PORT")?.toIntOrNull() ?: 8080
        }
    }
    return DevServerConfig(
        agentId = agentId,
        agentCommand = agentCommand,
        port = port,
        debug = args.contains("--debug"),
        dev = args.contains("--dev"),
    )
}

fun startDevServer(config: DevServerConfig) {
    val agentDisplay = config.agentId ?: config.agentCommand ?: "<none>"
    logger.info("Starting ACP Web Gateway DEV (agent={}, port={}, debug={}, dev={})",
        agentDisplay, config.port, config.debug, config.dev)

    val holder = runBlocking {
        if (config.agentId == "simulate") {
            // Simulated agent mode — no real agent, uses canned responses
            val h = AgentHolder(emptyList(), System.getProperty("user.dir"))
            h.setupSimulatedAgent()
            h
        } else {
            val registry = fetchRegistry()
            val h = AgentHolder(registry, System.getProperty("user.dir"))
            try {
                if (config.agentCommand != null) {
                    h.switchAgentCommand(parseCommandString(config.agentCommand))
                } else if (config.agentId != null) {
                    h.switchAgent(config.agentId)
                }
            } catch (e: Exception) {
                val display = config.agentId ?: config.agentCommand ?: "unknown"
                logger.error("Failed to start agent '{}': {}", display, e.message, e)
                h.startupError = "Failed to start agent '$display': ${e.message}"
            }
            h
        }
    }

    // Build simulation interceptor and commands when debug mode is on
    val interceptor = if (config.debug) {
        config.promptInterceptor ?: buildSimulationInterceptor()
    } else config.promptInterceptor
    val captureCallback = if (config.debug) {
        config.captureCallback ?: defaultCaptureCallback
    } else config.captureCallback
    val allCommands = if (config.debug) {
        simulationCommands + config.internalCommands
    } else config.internalCommands

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    server = embeddedServer(CIO, configure = {
        connector {
            this.port = config.port
        }
    }) {
        devModule(holder, config.debug, config.dev,
            onReload = {
                holder.close()
                server.stop(100, 500)
                Runtime.getRuntime().halt(0)
            },
            commandHandler = config.commandHandler,
            internalCommands = allCommands,
            promptInterceptor = interceptor,
            captureCallback = captureCallback,
        )
    }
    server.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread({
        logger.info("Shutdown hook — closing agent and server")
        holder.close()
        server.stop(100, 500)
    }, "shutdown-hook"))

    logger.info("Dev server started on http://localhost:{}", config.port)

    Thread.currentThread().join()
}

fun main(args: Array<String>) {
    startDevServer(parseDevServerConfig(args))
}
