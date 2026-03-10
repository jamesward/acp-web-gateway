package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingPermission(
    val toolCallId: String,
    val title: String,
    val options: List<PermissionOption>,
    val deferred: CompletableDeferred<RequestPermissionResponse>,
)

data class BrowserStateRequestInternal(val requestId: String, val query: String)

class GatewayClientOperations : ClientSessionOperations {

    private val logger = LoggerFactory.getLogger(GatewayClientOperations::class.java)

    val pendingPermissions = Channel<PendingPermission>(Channel.BUFFERED)
    private val pendingDeferreds = ConcurrentHashMap<String, CompletableDeferred<RequestPermissionResponse>>()

    val pendingBrowserStateRequests = Channel<BrowserStateRequestInternal>(Channel.BUFFERED)
    private val browserStateDeferreds = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val terminals = ConcurrentHashMap<String, Process>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val deferred = CompletableDeferred<RequestPermissionResponse>()
        val pending = PendingPermission(
            toolCallId = toolCall.toolCallId.value,
            title = toolCall.title ?: "Permission requested",
            options = permissions,
            deferred = deferred,
        )
        pendingDeferreds[toolCall.toolCallId.value] = deferred
        pendingPermissions.send(pending)
        return deferred.await()
    }

    fun pendingPermissionsSummary(): String {
        val ids = pendingDeferreds.keys().toList()
        return if (ids.isEmpty()) "(none)" else ids.joinToString(", ")
    }

    fun completePermission(toolCallId: String, optionId: String) {
        val deferred = pendingDeferreds.remove(toolCallId)
        if (deferred != null) {
            deferred.complete(
                RequestPermissionResponse(
                    outcome = RequestPermissionOutcome.Selected(PermissionOptionId(optionId))
                )
            )
        } else {
            logger.warn("No pending permission for toolCallId: {}", toolCallId)
        }
    }

    suspend fun requestBrowserState(query: String): String {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        browserStateDeferreds[requestId] = deferred
        pendingBrowserStateRequests.send(BrowserStateRequestInternal(requestId, query))
        return withTimeoutOrNull(10_000) { deferred.await() }
            ?: """{"error":"browser state request timed out"}"""
    }

    fun completeBrowserState(requestId: String, state: String) {
        val deferred = browserStateDeferreds.remove(requestId)
        if (deferred != null) {
            deferred.complete(state)
        } else {
            logger.warn("No pending browser state request for requestId: {}", requestId)
        }
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        logger.info("Agent notification: {}", notification)
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        if (path.startsWith("browser://")) {
            val query = path.removePrefix("browser://").ifEmpty { "all" }
            logger.info("Browser state request: query={}", query)
            val state = requestBrowserState(query)
            return ReadTextFileResponse(content = state)
        }
        logger.debug("fsReadTextFile: path={}, line={}, limit={}", path, line, limit)
        val file = File(path)
        val lines = file.readLines()
        val startLine = (line?.toInt() ?: 1) - 1
        val count = limit?.toInt() ?: lines.size
        val content = lines.drop(startLine.coerceAtLeast(0)).take(count).joinToString("\n")
        return ReadTextFileResponse(content = content)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        logger.debug("fsWriteTextFile: path={}", path)
        File(path).writeText(content)
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        val terminalId = UUID.randomUUID().toString()
        logger.debug("terminalCreate: id={}, command={}, args={}", terminalId, command, args)
        val pb = ProcessBuilder(listOf(command) + args)
        if (cwd != null) pb.directory(File(cwd))
        pb.redirectErrorStream(true)
        for (e in env) {
            pb.environment()[e.name] = e.value
        }
        val process = pb.start()
        terminals[terminalId] = process
        return CreateTerminalResponse(terminalId = terminalId)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        val process = terminals[terminalId] ?: error("Unknown terminal: $terminalId")
        val output = process.inputStream.readBytes().decodeToString()
        val exitStatus = if (!process.isAlive) {
            TerminalExitStatus(exitCode = process.exitValue().toUInt())
        } else null
        return TerminalOutputResponse(output = output, truncated = false, exitStatus = exitStatus)
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        logger.debug("terminalRelease: {}", terminalId)
        terminals.remove(terminalId)?.destroy()
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
        val process = terminals[terminalId] ?: error("Unknown terminal: $terminalId")
        val exitCode = process.waitFor()
        return WaitForTerminalExitResponse(exitCode = exitCode.toUInt())
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        logger.debug("terminalKill: {}", terminalId)
        terminals[terminalId]?.destroyForcibly()
        return KillTerminalCommandResponse()
    }
}
