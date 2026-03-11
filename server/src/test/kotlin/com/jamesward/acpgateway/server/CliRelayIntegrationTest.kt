package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.*

/**
 * Integration test for the CLI relay flow.
 *
 * Starts a real gateway server in proxy mode, then:
 * 1. A "CLI backend" connects to /s/{sessionId}/agent and runs handleChatWebSocket
 *    with a fake ACP session (no real agent process needed).
 * 2. A "browser" connects to /s/{sessionId}/ws and exchanges WsMessage JSON.
 *
 * Verifies that messages relay correctly between browser and CLI.
 */
class CliRelayIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startProxyServer(port: Int): io.ktor.server.engine.EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration> {
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), GatewayMode.PROXY)
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port) {
            module(holder, GatewayMode.PROXY)
        }
        server.start(wait = false)
        return server
    }

    private fun newWsClient(): HttpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }
    private fun newHttpClient(): HttpClient = HttpClient(ClientCIO)

    @Test
    fun browserReceivesConnectedAndResponseViaRelay() = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startProxyServer(port)

        try {
            // --- Set up fake ACP session (what the CLI would create via AgentProcessManager) ---
            val fakeClientSession = ControllableFakeClientSession()
            fakeClientSession.enqueueTextResponse("Hello from agent!")
            val testScope = CoroutineScope(Dispatchers.Default)
            val session = GatewaySession(
                id = sessionId,
                clientSession = fakeClientSession,
                clientOps = GatewayClientOperations(),
                cwd = System.getProperty("user.dir"),
                scope = testScope,
                store = InMemorySessionStore(),
            )
            session.ready = true
            session.startEventForwarding()

            // Fake AgentProcessManager (just needs agentName/agentVersion and session registration)
            val command = ProcessCommand("echo", listOf("test"))
            val manager = AgentProcessManager(command, System.getProperty("user.dir"))
            manager.agentName = "test-cli-agent"
            manager.agentVersion = "1.0.0"
            manager.sessions[sessionId] = session

            // --- CLI backend: connect to /s/{sessionId}/agent, run handleChatWebSocket ---
            val cliClient = newWsClient()
            val cliJob = launch {
                cliClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/agent") {
                    handleChatWebSocket(session, manager)
                }
            }

            // Give CLI backend time to connect and register the relay session
            delay(500)

            // --- Browser: connect to /s/{sessionId}/ws ---
            val browserClient = newWsClient()
            val receivedMessages = mutableListOf<WsMessage>()

            browserClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/ws") {
                // 1. Should receive Connected message (relayed from CLI's handleChatWebSocket)
                val connFrame = incoming.receive()
                assertIs<Frame.Text>(connFrame)
                val connMsg = json.decodeFromString(WsMessage.serializer(), connFrame.readText())
                assertIs<WsMessage.Connected>(connMsg)
                assertEquals("test-cli-agent", connMsg.agentName)
                assertEquals("1.0.0", connMsg.agentVersion)
                receivedMessages.add(connMsg)

                // 2. Send a prompt
                val prompt = WsMessage.Prompt("test question")
                send(Frame.Text(json.encodeToString(WsMessage.serializer(), prompt)))

                // 3. Collect messages until TurnComplete
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    val frame = withTimeoutOrNull(5000) { incoming.receive() } ?: break
                    if (frame is Frame.Text) {
                        val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                        receivedMessages.add(msg)
                        if (msg is WsMessage.TurnComplete) break
                    }
                }
            }

            // --- Verify ---
            assertTrue(receivedMessages.any { it is WsMessage.Connected }, "Should receive Connected")
            assertTrue(receivedMessages.any { it is WsMessage.TurnComplete }, "Should receive TurnComplete")

            // Should have at least one AgentText containing the agent's response
            val agentTexts = receivedMessages.filterIsInstance<WsMessage.AgentText>()
            assertTrue(agentTexts.isNotEmpty(), "Should receive AgentText messages")
            assertTrue(
                agentTexts.any { it.markdown.contains("Hello from agent!") },
                "AgentText should contain agent response text. Got: ${agentTexts.map { it.markdown.take(100) }}",
            )

            // Should have a UserMessage for the user's prompt
            val userMessages = receivedMessages.filterIsInstance<WsMessage.UserMessage>()
            assertTrue(
                userMessages.any { it.text.contains("test question") },
                "UserMessage should contain user prompt text",
            )

            // Verify the fake session actually received the prompt
            assertTrue(fakeClientSession.promptHistory.isNotEmpty(), "Fake ACP session should have received a prompt")

            // Clean up
            cliJob.cancelAndJoin()
            cliClient.close()
            browserClient.close()
            testScope.cancel()
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun sessionPageServesHtmlForRelaySession() = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startProxyServer(port)

        try {
            // Connect a CLI backend to create the relay session
            val cliClient = newWsClient()
            val cliJob = launch {
                cliClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/agent") {
                    // Just stay connected
                    try { incoming.receive() } catch (_: Exception) {}
                }
            }
            delay(300)

            // Fetch the session page via HTTP
            val client = newHttpClient()
            val response = client.get("http://localhost:$port/s/$sessionId")
            assertEquals(200, response.status.value)

            val body = response.bodyAsText()
            assertTrue(body.contains("""id="root""""), "Page should contain root mount point")

            cliJob.cancelAndJoin()
            cliClient.close()
            client.close()
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun relaySessionDisappearsWhenCliDisconnects() = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startProxyServer(port)

        try {
            // Connect CLI backend
            val cliClient = newWsClient()
            val gate = CompletableDeferred<Unit>()
            val cliJob = launch {
                cliClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/agent") {
                    gate.await() // Stay connected until we signal
                }
            }
            delay(300)

            // Session page should be available
            val client = newHttpClient()
            val response1 = client.get("http://localhost:$port/s/$sessionId")
            assertEquals(200, response1.status.value)

            // Disconnect CLI
            gate.complete(Unit)
            cliJob.join()
            delay(300)

            // Session page should now return 404
            val response2 = client.get("http://localhost:$port/s/$sessionId")
            assertEquals(404, response2.status.value)

            cliClient.close()
            client.close()
        } finally {
            server.stop(100, 500)
        }
    }
}
