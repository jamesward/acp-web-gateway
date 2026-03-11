package com.jamesward.acpgateway.shared

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.jamesward.acpgateway.shared.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ToolCallInfo(val title: String, val status: ToolStatus, val startTime: Long)

private val broadcastJson = Json { ignoreUnknownKeys = true }

class GatewaySession(
    val id: UUID,
    private val clientSession: ClientSession,
    val clientOps: GatewayClientOperations,
    val cwd: String,
    val scope: CoroutineScope,
    val store: SessionStore,
) {
    private val logger = LoggerFactory.getLogger(GatewaySession::class.java)
    private val promptMutex = Mutex()

    @Volatile
    var ready: Boolean = false

    @Volatile
    var promptJob: Job? = null

    val connections: MutableSet<WebSocketSession> = ConcurrentHashMap.newKeySet()

    // Track the currently displayed permission dialog so reconnecting clients can see it
    @Volatile
    var activePermissionHtml: String? = null

    // Gateway-internal slash commands (e.g. /simulate, /autopilot)
    @Volatile
    var internalCommands: List<CommandInfo> = emptyList()

    // Available slash commands from the ACP agent
    @Volatile
    var availableCommands: List<CommandInfo> = emptyList()

    /** All commands: internal + agent-provided. */
    val allCommands: List<CommandInfo> get() = internalCommands + availableCommands

    suspend fun broadcast(msg: WsMessage) {
        val text = broadcastJson.encodeToString(WsMessage.serializer(), msg)
        val frame = Frame.Text(text)
        val dead = mutableListOf<WebSocketSession>()
        for (conn in connections) {
            try {
                conn.send(frame.copy())
            } catch (_: Exception) {
                dead.add(conn)
            }
        }
        connections.removeAll(dead.toSet())
    }

    fun startEventForwarding() {
        // Handle agent notifications that arrive outside of prompt flows
        clientOps.onNotification = { notification ->
            when (notification) {
                is SessionUpdate.AvailableCommandsUpdate -> {
                    val commands = notification.availableCommands.map { cmd ->
                        CommandInfo(
                            name = cmd.name,
                            description = cmd.description,
                            inputHint = (cmd.input as? AvailableCommandInput.Unstructured)?.hint,
                        )
                    }
                    availableCommands = commands
                    broadcast(WsMessage.AvailableCommands(allCommands))
                }
                else -> {}
            }
        }

        // Replay any notifications that arrived before the handler was set
        // (e.g. AvailableCommandsUpdate sent during session creation)
        scope.launch {
            clientOps.drainBufferedNotifications()
        }

        scope.launch {
            for (pending in clientOps.pendingPermissions) {
                val html = permissionContentHtml(
                    toolCallId = pending.toolCallId,
                    title = pending.title,
                    options = pending.options.map { opt ->
                        PermissionOptionInfo(
                            optionId = opt.optionId.value,
                            name = opt.name,
                            kind = opt.kind.toGatewayKind(),
                        )
                    },
                )
                activePermissionHtml = html
                broadcast(WsMessage.HtmlUpdate(target = Id.PERMISSION_CONTENT, swap = Swap.InnerHTML, html = html))
                broadcast(WsMessage.HtmlUpdate(target = Id.PERMISSION_DIALOG, swap = Swap.Show, html = ""))
            }
        }

        scope.launch {
            for (req in clientOps.pendingBrowserStateRequests) {
                // Send to first available connection
                val conn = connections.firstOrNull()
                if (conn != null) {
                    try {
                        val text = broadcastJson.encodeToString(
                            WsMessage.serializer(),
                            WsMessage.BrowserStateRequest(req.requestId, req.query),
                        )
                        conn.send(Frame.Text(text))
                    } catch (e: Exception) {
                        logger.debug("Failed to send browser state request", e)
                    }
                }
            }
        }
    }

    suspend fun cancelPrompt() {
        try {
            clientSession.cancel()
        } catch (e: Exception) {
            logger.debug("cancelPrompt failed (may be expected)", e)
        }
    }

    suspend fun prompt(text: String, screenshot: String? = null, files: List<FileAttachment> = emptyList()): Flow<Event> {
        return promptMutex.withLock {
            store.addHistory(id, ChatEntry(role = "user", content = text, timestamp = System.currentTimeMillis()))
            val contentBlocks = buildList {
                if (screenshot != null) {
                    add(ContentBlock.Image(data = screenshot, mimeType = "image/png"))
                }
                for (file in files) {
                    if (file.mimeType.startsWith("image/")) {
                        add(ContentBlock.Image(data = file.data, mimeType = file.mimeType))
                    } else {
                        add(ContentBlock.Resource(
                            resource = EmbeddedResourceResource.BlobResourceContents(
                                blob = file.data,
                                uri = "file:///${file.name}",
                                mimeType = file.mimeType,
                            )
                        ))
                    }
                }
                add(ContentBlock.Text(text))
            }
            clientSession.prompt(contentBlocks)
        }
    }

    suspend fun buildDiagnosticContext(): String {
        val now = System.currentTimeMillis()
        val elapsed = (now - store.getPromptStartTime(id)) / 1000

        val toolCalls = store.getToolCalls(id)
        val toolCallLines = toolCalls.entries.joinToString("\n") { (tcId, info) ->
            val tcElapsed = (now - info.startTime) / 1000
            "  - $tcId: ${info.title} (${info.status}) — ${tcElapsed}s"
        }.ifEmpty { "  (none)" }

        val pendingPerms = clientOps.pendingPermissionsSummary()

        val history = store.getHistory(id)
        val recentHistory = history.takeLast(6).joinToString("\n") { entry ->
            val content = entry.content.take(200)
            "[${entry.role}] $content"
        }

        val browserState = try {
            clientOps.requestBrowserState("all")
        } catch (e: Exception) {
            "(failed to collect: ${e.message})"
        }

        return """
            |[SYSTEM DIAGNOSTIC] The user reports that the previous task appears stuck. Analyze the situation and suggest how to proceed.
            |
            |Session state:
            |- Time elapsed: ${elapsed}s
            |- Active tool calls:
            |$toolCallLines
            |- Pending permissions: $pendingPerms
            |
            |Browser state:
            |$browserState
            |
            |Recent history:
            |$recentHistory
            |
            |Diagnose what is causing the issue and suggest next steps.
        """.trimMargin()
    }
}

class AgentProcessManager(
    private val processCommand: ProcessCommand,
    private val workingDir: String,
) {
    private val logger = LoggerFactory.getLogger(AgentProcessManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    private lateinit var process: Process
    private lateinit var transport: StdioTransport
    private lateinit var protocol: Protocol
    private lateinit var client: Client

    val sessions = ConcurrentHashMap<UUID, GatewaySession>()
    val store: SessionStore = InMemorySessionStore()

    var agentName: String = ""
    var agentVersion: String = ""

    suspend fun start() {
        logger.info("Starting agent: {} {}", processCommand.command, processCommand.args)

        val pb = ProcessBuilder(listOf(processCommand.command) + processCommand.args)
        pb.directory(java.io.File(workingDir))
        pb.environment().putAll(processCommand.env)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        process = pb.start()

        val source = process.inputStream.asSource().buffered()
        val sink = process.outputStream.asSink().buffered()

        transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = source,
            output = sink,
        )

        protocol = Protocol(parentScope = scope, transport = transport)
        protocol.start()

        client = Client(protocol)
        val agentInfo = client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                    terminal = true,
                ),
                implementation = Implementation(
                    name = "acp-web-gateway",
                    version = "0.1.0",
                ),
            )
        )

        agentName = agentInfo.implementation?.name ?: "unknown"
        agentVersion = agentInfo.implementation?.version ?: "0.0.0"
        logger.info("Agent initialized: {} {}", agentName, agentVersion)
    }

    suspend fun createSession(cwd: String = workingDir): GatewaySession {
        val sessionId = UUID.randomUUID()
        val clientOps = GatewayClientOperations()
        logger.info("Creating session {}...", sessionId)
        val operationsFactory = ClientOperationsFactory { _, _ -> clientOps }
        val clientSession = client.newSession(
            SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
            operationsFactory,
        )
        val session = GatewaySession(sessionId, clientSession, clientOps, cwd, scope, store)
        session.ready = true
        session.startEventForwarding()
        sessions[sessionId] = session
        logger.info("Session created: {} (acp={})", sessionId, clientSession.sessionId)
        return session
    }

    fun getSession(id: UUID): GatewaySession? = sessions[id]

    fun close() {
        logger.info("Closing agent process manager")

        // 1. Destroy the subprocess first so its stdio streams unblock.
        try { process.destroy() } catch (_: Exception) {}
        try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (process.isAlive) {
            try { process.destroyForcibly() } catch (_: Exception) {}
        }
        // 2. Close transport — now that the process is gone, stream closes won't hang.
        try { transport.close() } catch (_: Exception) {}
        // 3. Close protocol — cancels pending requests and internal scope.
        try { protocol.close() } catch (_: Exception) {}
        // 4. Cancel our scope to clean up any remaining coroutines.
        scope.cancel()
        logger.info("Agent process manager closed")
    }
}
