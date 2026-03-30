package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.jamesward.acpgateway.shared.*
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.util.*
import kotlin.test.*

/**
 * Tests that multiple browser clients connected to the same session stay in sync.
 *
 * Covers:
 * - Cancel from one client dismisses permission dialog on all clients (direct + relay mode)
 * - PermissionResponse from one client dismisses permission dialog on all clients (direct + relay mode)
 * - Late-joining client receives current PlanUpdate from TurnState replay
 * - Late-joining client receives SessionInfo title on reconnect
 * - Late-joining relay client does not see stale PermissionRequest after it was answered
 */
class MultiClientStateSyncTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Helpers ----

    private fun makeSession(fakeClientSession: ControllableFakeClientSession = ControllableFakeClientSession()): Triple<GatewaySession, AgentProcessManager, ControllableFakeClientSession> {
        val clientOps = GatewayClientOperations()
        val testScope = CoroutineScope(Dispatchers.Default)
        val session = GatewaySession(
            id = UUID.randomUUID(),
            clientSession = fakeClientSession,
            clientOps = clientOps,
            cwd = System.getProperty("user.dir"),
            scope = testScope,
            store = InMemorySessionStore(),
        )
        session.ready = true
        session.startEventForwarding()
        val manager = AgentProcessManager(ProcessCommand("echo", listOf("test")), System.getProperty("user.dir"))
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"
        manager.sessions[session.id] = session
        return Triple(session, manager, fakeClientSession)
    }

    private fun connectClient(session: GatewaySession, manager: AgentProcessManager): Pair<Channel<WsMessage>, Channel<WsMessage>> {
        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)
        CoroutineScope(Dispatchers.Default).launch {
            handleChatChannels(input, output, session, manager)
        }
        return input to output
    }

    private suspend fun Channel<WsMessage>.skipConnected() {
        val msg = receive()
        assertIs<WsMessage.Connected>(msg)
    }

    private suspend fun Channel<WsMessage>.receiveOfType(type: String, timeoutMs: Long = 3000): WsMessage =
        withTimeout(timeoutMs) {
            for (msg in this@receiveOfType) {
                if (msg::class.simpleName == type) return@withTimeout msg
            }
            error("Channel closed before receiving $type")
        }

    private suspend fun Channel<WsMessage>.drainAvailable(timeoutMs: Long = 300): List<WsMessage> {
        val result = mutableListOf<WsMessage>()
        withTimeoutOrNull(timeoutMs) {
            for (msg in this@drainAvailable) result.add(msg)
        }
        return result
    }

    private fun sendPermission(session: GatewaySession, toolCallId: String = "tc-1"): CompletableDeferred<RequestPermissionResponse> {
        val deferred = CompletableDeferred<RequestPermissionResponse>()
        session.clientOps.pendingPermissions.trySend(
            PendingPermission(
                toolCallId = toolCallId,
                title = "Allow operation?",
                options = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("allow"),
                        name = "Allow",
                        kind = PermissionOptionKind.ALLOW_ONCE,
                    ),
                ),
                deferred = deferred,
            )
        )
        return deferred
    }

    // ---- Direct mode: Cancel ----

    @Test
    fun cancelFromOneClientDismissesPermissionDialogOnOtherClient(): Unit = runBlocking {
        val (session, manager) = makeSession().let { it.first to it.second }

        val (input1, output1) = connectClient(session, manager)
        val (input2, output2) = connectClient(session, manager)
        output1.skipConnected()
        output2.skipConnected()

        sendPermission(session)
        delay(100)

        val perm1 = output1.receiveOfType("PermissionRequest")
        val perm2 = output2.receiveOfType("PermissionRequest")
        assertIs<WsMessage.PermissionRequest>(perm1)
        assertIs<WsMessage.PermissionRequest>(perm2)

        // Client 1 cancels
        input1.send(WsMessage.Cancel)
        delay(100)

        // Client 2 should receive Cancel (broadcast from handleClientMessage)
        val msgs2 = output2.drainAvailable()
        assertTrue(msgs2.any { it is WsMessage.Cancel }, "Client 2 should receive Cancel to dismiss dialog")

        input1.close()
        input2.close()
    }

    @Test
    fun permissionResponseFromOneClientDismissesDialogOnOtherClient(): Unit = runBlocking {
        val (session, manager) = makeSession().let { it.first to it.second }

        val (input1, output1) = connectClient(session, manager)
        val (input2, output2) = connectClient(session, manager)
        output1.skipConnected()
        output2.skipConnected()

        sendPermission(session, "tc-2")
        delay(100)

        val perm1 = output1.receiveOfType("PermissionRequest")
        val perm2 = output2.receiveOfType("PermissionRequest")
        assertIs<WsMessage.PermissionRequest>(perm1)
        assertIs<WsMessage.PermissionRequest>(perm2)

        // Client 1 responds
        input1.send(WsMessage.PermissionResponse("tc-2", "allow"))
        delay(100)

        // Client 2 should receive PermissionResponse (broadcast from handleClientMessage)
        val msgs2 = output2.drainAvailable()
        assertTrue(
            msgs2.any { it is WsMessage.PermissionResponse },
            "Client 2 should receive PermissionResponse to dismiss dialog",
        )

        input1.close()
        input2.close()
    }

    // ---- Direct mode: late-join replay ----

    @Test
    fun lateJoiningClientReceivesPlanUpdateFromTurnState(): Unit = runBlocking {
        val fakeSession = ControllableFakeClientSession()
        // Gate holds the turn open — we complete it only after client 2 joins
        val planSent = CompletableDeferred<Unit>()
        val turnCanFinish = CompletableDeferred<Unit>()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.PlanUpdate(listOf(
                    PlanEntry(content = "Step 1", priority = PlanEntryPriority.HIGH, status = com.agentclientprotocol.model.PlanEntryStatus.IN_PROGRESS),
                    PlanEntry(content = "Step 2", priority = PlanEntryPriority.MEDIUM, status = com.agentclientprotocol.model.PlanEntryStatus.PENDING),
                ))))
                planSent.complete(Unit)
                turnCanFinish.await() // hold turn open until client 2 has joined
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
        val (session, manager) = makeSession(fakeSession).let { it.first to it.second }

        val (input1, output1) = connectClient(session, manager)
        output1.skipConnected()

        input1.send(WsMessage.Prompt("plan something"))
        planSent.await() // wait until PlanUpdate has been broadcast
        delay(200) // allow updateTurnState to persist the plan before client 2 joins

        // Client 2 joins while turn is in progress (plan emitted, turn not yet complete)
        val (input2, output2) = connectClient(session, manager)
        output2.skipConnected()

        val msgs2 = output2.drainAvailable(500)

        // Now let the turn finish
        turnCanFinish.complete(Unit)

        assertTrue(
            msgs2.any { it is WsMessage.PlanUpdate },
            "Late-joining client should receive PlanUpdate from TurnState replay. Got: ${msgs2.map { it::class.simpleName }}",
        )
        val plan = msgs2.filterIsInstance<WsMessage.PlanUpdate>().first()
        assertEquals(2, plan.entries.size)
        assertEquals("Step 1", plan.entries[0].content)

        input1.close()
        input2.close()
    }

    @Test
    fun lateJoiningClientReceivesSessionTitle(): Unit = runBlocking {
        val fakeSession = ControllableFakeClientSession()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.SessionInfoUpdate(title = "My Session")))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("hi"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
        val (session, manager) = makeSession(fakeSession).let { it.first to it.second }

        val (input1, output1) = connectClient(session, manager)
        output1.skipConnected()
        input1.send(WsMessage.Prompt("hello"))

        // Wait for turn to complete
        withTimeout(5000) {
            for (msg in output1) { if (msg is WsMessage.TurnComplete) break }
        }

        // Client 2 joins after turn is done
        val (input2, output2) = connectClient(session, manager)
        output2.skipConnected()

        val msgs2 = output2.drainAvailable(500)
        assertTrue(
            msgs2.any { it is WsMessage.SessionInfo && (it as WsMessage.SessionInfo).title == "My Session" },
            "Late-joining client should receive SessionInfo title. Got: ${msgs2.map { it::class.simpleName }}",
        )

        input1.close()
        input2.close()
    }

    // ---- Relay mode: multi-client sync ----

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startRelayServer(port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val server = embeddedServer(CIO, port = port) { module(emptyList()) }
        server.start(wait = false)
        return server
    }

    private fun newWsClient(): HttpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }

    private fun connectRelayBackend(
        scope: CoroutineScope,
        port: Int,
        sessionId: UUID,
        session: GatewaySession,
        manager: AgentProcessManager,
    ): Job {
        val client = newWsClient()
        return scope.launch {
            client.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/agent") {
                handleChatWebSocket(session, manager)
            }
            client.close()
        }
    }

    private suspend fun collectRelayMessages(
        port: Int,
        sessionId: UUID,
        collectUntil: (WsMessage) -> Boolean = { false },
        timeoutMs: Long = 3000,
    ): List<WsMessage> {
        val client = newWsClient()
        val collected = mutableListOf<WsMessage>()
        try {
            withTimeout(timeoutMs) {
                client.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/ws") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                            collected.add(msg)
                            if (collectUntil(msg)) break
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Expected — timed out collecting
        } finally {
            client.close()
        }
        return collected
    }

    @Test
    fun relayCancelFromOneBrowserDismissesDialogOnOther(): Unit = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startRelayServer(port)
        val scope = CoroutineScope(Dispatchers.Default)

        try {
            val (session, manager) = makeSession().let { it.first to it.second }
            connectRelayBackend(scope, port, sessionId, session, manager)
            delay(300)

            sendPermission(session, "tc-relay-cancel")
            delay(200)

            // Browser 2 starts collecting — will wait for Cancel broadcast
            val msgs2Job = scope.async {
                collectRelayMessages(port, sessionId, collectUntil = { it is WsMessage.Cancel })
            }
            delay(200)

            // Browser 1 sends Cancel
            val cancelClient = newWsClient()
            cancelClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/ws") {
                send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Cancel)))
                delay(100)
            }
            cancelClient.close()

            val msgs2 = msgs2Job.await()
            assertTrue(
                msgs2.any { it is WsMessage.Cancel },
                "Browser 2 should receive Cancel broadcast. Got: ${msgs2.map { it::class.simpleName }}",
            )
        } finally {
            server.stop(100, 500)
            scope.cancel()
        }
    }

    @Test
    fun relayPermissionResponseFromOneBrowserDismissesDialogOnOther(): Unit = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startRelayServer(port)
        val scope = CoroutineScope(Dispatchers.Default)

        try {
            val (session, manager) = makeSession().let { it.first to it.second }
            connectRelayBackend(scope, port, sessionId, session, manager)
            delay(300)

            sendPermission(session, "tc-relay-resp")
            delay(200)

            // Browser 2 starts collecting — will wait for PermissionResponse broadcast
            val msgs2Job = scope.async {
                collectRelayMessages(port, sessionId, collectUntil = { it is WsMessage.PermissionResponse })
            }
            delay(200)

            // Browser 1 sends PermissionResponse
            val respClient = newWsClient()
            respClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/ws") {
                send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.PermissionResponse("tc-relay-resp", "allow"))))
                delay(100)
            }
            respClient.close()

            val msgs2 = msgs2Job.await()
            assertTrue(
                msgs2.any { it is WsMessage.PermissionResponse },
                "Browser 2 should receive PermissionResponse broadcast. Got: ${msgs2.map { it::class.simpleName }}",
            )
        } finally {
            server.stop(100, 500)
            scope.cancel()
        }
    }

    @Test
    fun relayLateJoiningClientDoesNotSeeStalePermissionRequest(): Unit = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startRelayServer(port)
        val scope = CoroutineScope(Dispatchers.Default)

        try {
            val (session, manager) = makeSession().let { it.first to it.second }
            connectRelayBackend(scope, port, sessionId, session, manager)
            delay(300)

            sendPermission(session, "tc-relay-stale")
            delay(200)

            // Browser 1 waits for PermissionRequest then answers it
            val respClient = newWsClient()
            respClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/ws") {
                withTimeout(3000) {
                    for (frame in incoming) {
                        if (frame is Frame.Text &&
                            json.decodeFromString(WsMessage.serializer(), frame.readText()) is WsMessage.PermissionRequest
                        ) break
                    }
                }
                send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.PermissionResponse("tc-relay-stale", "allow"))))
                delay(100)
            }
            respClient.close()
            delay(200)

            // Late-joining browser 2 connects after permission was answered
            val msgs2 = collectRelayMessages(port, sessionId, timeoutMs = 1000)

            val permReqIdx = msgs2.indexOfFirst { it is WsMessage.PermissionRequest }
            val permRespIdx = msgs2.indexOfFirst { it is WsMessage.PermissionResponse }

            // If PermissionRequest is in cache, PermissionResponse must follow it
            if (permReqIdx >= 0) {
                assertTrue(
                    permRespIdx > permReqIdx,
                    "PermissionResponse must follow PermissionRequest in cache replay. " +
                        "permReqIdx=$permReqIdx, permRespIdx=$permRespIdx. " +
                        "Messages: ${msgs2.map { it::class.simpleName }}",
                )
            }
        } finally {
            server.stop(100, 500)
            scope.cancel()
        }
    }
}
