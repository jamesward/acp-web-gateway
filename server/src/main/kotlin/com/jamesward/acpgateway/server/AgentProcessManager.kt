package com.jamesward.acpgateway.server

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.jamesward.acpgateway.shared.ChatEntry
import com.jamesward.acpgateway.shared.FileAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ToolCallInfo(val title: String, val status: String, val startTime: Long)

class GatewaySession(
    val id: UUID,
    private val clientSession: ClientSession,
    val clientOps: GatewayClientOperations,
    val cwd: String,
) {
    private val promptMutex = Mutex()
    val history = mutableListOf<ChatEntry>()

    @Volatile
    var ready: Boolean = false

    val activeToolCalls = ConcurrentHashMap<String, ToolCallInfo>()
    val promptStartTime = AtomicLong(0L)

    suspend fun cancelPrompt() {
        try {
            clientSession.cancel()
        } catch (e: Exception) {
            LoggerFactory.getLogger(GatewaySession::class.java)
                .debug("cancelPrompt failed (may be expected)", e)
        }
    }

    suspend fun prompt(text: String, screenshot: String? = null, files: List<FileAttachment> = emptyList()): Flow<Event> {
        return promptMutex.withLock {
            history.add(ChatEntry(role = "user", content = text, timestamp = System.currentTimeMillis()))
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

    fun buildDiagnosticContext(): String {
        val now = System.currentTimeMillis()
        val elapsed = (now - promptStartTime.get()) / 1000

        val toolCallLines = activeToolCalls.entries.joinToString("\n") { (id, info) ->
            val tcElapsed = (now - info.startTime) / 1000
            "  - $id: ${info.title} (${info.status}) — ${tcElapsed}s"
        }.ifEmpty { "  (none)" }

        val pendingPerms = clientOps.pendingPermissionsSummary()

        val recentHistory = history.takeLast(6).joinToString("\n") { entry ->
            val content = entry.content.take(200)
            "[${entry.role}] $content"
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
    private lateinit var protocol: Protocol
    private lateinit var client: Client

    val sessions = ConcurrentHashMap<UUID, GatewaySession>()

    var agentName: String = ""
        private set
    var agentVersion: String = ""
        private set

    suspend fun start() {
        logger.info("Starting agent: {} {}", processCommand.command, processCommand.args)

        val pb = ProcessBuilder(listOf(processCommand.command) + processCommand.args)
        pb.directory(java.io.File(workingDir))
        pb.environment().putAll(processCommand.env)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        process = pb.start()

        val source = process.inputStream.asSource().buffered()
        val sink = process.outputStream.asSink().buffered()

        val transport = StdioTransport(
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
        val session = GatewaySession(sessionId, clientSession, clientOps, cwd)
        session.ready = true
        sessions[sessionId] = session
        logger.info("Session created: {} (acp={})", sessionId, clientSession.sessionId)
        return session
    }

    fun getSession(id: UUID): GatewaySession? = sessions[id]

    fun close() {
        logger.info("Closing agent process manager")
        try { protocol.close() } catch (_: Exception) {}
        try { process.destroyForcibly() } catch (_: Exception) {}
    }
}
