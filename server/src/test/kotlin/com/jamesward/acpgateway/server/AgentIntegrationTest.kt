package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.PermissionOptionKind
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests that spawn a real ACP agent (claude-code) and verify
 * that file attachments and screenshots are properly delivered.
 *
 * Skipped automatically if npx is not available or the agent cannot start.
 *
 * Run with: ./gradlew :server:test --tests "*AgentIntegrationTest*"
 */
class AgentIntegrationTest {

    // Minimal 1x1 white PNG, valid base64
    private val tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="

    companion object {
        private val agentId = System.getProperty("test.acp.agent") ?: "claude-acp"
        private val agentEnv: AgentEnv? by lazy { initAgent() }

        private var skipReason: String? = null

        private fun initAgent(): AgentEnv? {
            // Skip if the agent requires auth credentials that aren't available.
            // For claude-acp, ANTHROPIC_API_KEY must be set.
            // Custom agents specified via -Dtest.acp.agent may have their own auth,
            // so we only gate on the default agent or known agents that need a key.
            if (agentId == "claude-acp" && System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
                skipReason = "ANTHROPIC_API_KEY not set (required for $agentId)"
                return null
            }

            // Check npx
            val npxAvailable = try {
                ProcessBuilder("npx", "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            } catch (_: Exception) { false }
            if (!npxAvailable) {
                skipReason = "npx not available"
                return null
            }

            // Resolve agent from registry
            val agents = try {
                runBlocking { fetchRegistry() }
            } catch (e: Exception) {
                skipReason = "Registry fetch failed: ${e.message}"
                return null
            }

            val agent = agents.find { it.id == agentId }
            if (agent == null) {
                skipReason = "$agentId not found in registry"
                return null
            }
            val command = resolveAgentCommand(agent)

            val manager = AgentProcessManager(command, System.getProperty("user.dir"))

            // Use a thread-based timeout because coroutine withTimeout may not
            // cancel SDK-internal blocking I/O (e.g. waiting for session/new response).
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "agent-init").apply { isDaemon = true }
            }
            val session: GatewaySession
            try {
                val future = executor.submit(java.util.concurrent.Callable {
                    runBlocking {
                        manager.start()
                        manager.createSession()
                    }
                })
                session = try {
                    future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    skipReason = "Agent init timed out (30s)"
                    manager.close()
                    return null
                } catch (e: java.util.concurrent.ExecutionException) {
                    skipReason = "Agent init failed: ${e.cause?.message}"
                    manager.close()
                    return null
                }
            } finally {
                executor.shutdownNow()
            }

            // Auto-approve all permission requests in background
            val scope = CoroutineScope(Dispatchers.Default)
            val approveJob = scope.launch {
                for (pending in session.clientOps.pendingPermissions) {
                    val allowOption = pending.options.firstOrNull {
                        it.kind == PermissionOptionKind.ALLOW_ALWAYS
                    } ?: pending.options.firstOrNull {
                        it.kind == PermissionOptionKind.ALLOW_ONCE
                    }
                    if (allowOption != null) {
                        session.clientOps.completePermission(
                            pending.toolCallId,
                            allowOption.optionId.value,
                        )
                    }
                }
            }

            Runtime.getRuntime().addShutdownHook(Thread {
                approveJob.cancel()
                manager.close()
                scope.cancel()
            })

            return AgentEnv(manager, session)
        }
    }

    private data class AgentEnv(
        val manager: AgentProcessManager,
        val session: GatewaySession,
    )

    private fun skipIfNoAgent(): AgentEnv? {
        if (agentEnv == null) {
            System.err.println("SKIP: $skipReason")
        }
        return agentEnv
    }

    @Test
    fun promptWithImageFileReachesAgent() {
        val env = skipIfNoAgent() ?: return
        runBlocking {
            withTimeout(120_000) {
                val events = env.session.prompt(
                    text = "Respond with ONLY the word: received",
                    files = listOf(FileAttachment("test.png", "image/png", tinyPng)),
                )
                val collected = events.toList()
                val responses = collected.filterIsInstance<Event.PromptResponseEvent>()
                assertTrue(responses.isNotEmpty(), "Should receive a PromptResponseEvent")
            }
        }
    }

    @Test
    fun promptWithScreenshotReachesAgent() {
        val env = skipIfNoAgent() ?: return
        runBlocking {
            withTimeout(120_000) {
                val events = env.session.prompt(
                    text = "Respond with ONLY the word: received",
                    screenshot = tinyPng,
                )
                val collected = events.toList()
                val responses = collected.filterIsInstance<Event.PromptResponseEvent>()
                assertTrue(responses.isNotEmpty(), "Should receive a PromptResponseEvent")
            }
        }
    }

    @Test
    fun promptWithNonImageFileReachesAgent() {
        val env = skipIfNoAgent() ?: return
        runBlocking {
            withTimeout(120_000) {
                val csvData = java.util.Base64.getEncoder()
                    .encodeToString("name,age\nAlice,30\n".toByteArray())
                val events = env.session.prompt(
                    text = "Respond with ONLY the word: received",
                    files = listOf(FileAttachment("data.csv", "text/csv", csvData)),
                )
                val collected = events.toList()
                val responses = collected.filterIsInstance<Event.PromptResponseEvent>()
                assertTrue(responses.isNotEmpty(), "Should receive a PromptResponseEvent")
            }
        }
    }

    @Test
    fun promptWithScreenshotAndFilesReachesAgent() {
        val env = skipIfNoAgent() ?: return
        runBlocking {
            withTimeout(120_000) {
                val csvData = java.util.Base64.getEncoder()
                    .encodeToString("x,y\n1,2\n".toByteArray())
                val events = env.session.prompt(
                    text = "Respond with ONLY the word: received",
                    screenshot = tinyPng,
                    files = listOf(
                        FileAttachment("photo.png", "image/png", tinyPng),
                        FileAttachment("data.csv", "text/csv", csvData),
                    ),
                )
                val collected = events.toList()
                val responses = collected.filterIsInstance<Event.PromptResponseEvent>()
                assertTrue(responses.isNotEmpty(), "Should receive a PromptResponseEvent")
            }
        }
    }

    @Test
    fun reloadShutdownCompletesWithinTenSeconds() {
        // Standalone agent — not shared with other tests.
        if (agentId == "claude-acp" && System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
            System.err.println("SKIP: ANTHROPIC_API_KEY not set (required for $agentId)")
            return
        }

        val npxAvailable = try {
            ProcessBuilder("npx", "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) { false }
        if (!npxAvailable) {
            System.err.println("SKIP: npx not available")
            return
        }

        val agents = try {
            runBlocking { fetchRegistry() }
        } catch (e: Exception) {
            System.err.println("SKIP: Registry fetch failed: ${e.message}")
            return
        }

        val agent = agents.find { it.id == agentId }
        if (agent == null) {
            System.err.println("SKIP: $agentId not found in registry")
            return
        }

        val command = resolveAgentCommand(agent)
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))

        // Start the agent and create a session with a 30s timeout
        val initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "close-test-init").apply { isDaemon = true }
        }
        try {
            val future = initExecutor.submit(java.util.concurrent.Callable {
                runBlocking {
                    manager.start()
                    manager.createSession()
                }
            })
            try {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                System.err.println("SKIP: Agent start timed out (30s)")
                manager.close()
                return
            } catch (e: java.util.concurrent.ExecutionException) {
                System.err.println("SKIP: Agent start failed: ${e.cause?.message}")
                manager.close()
                return
            }
        } finally {
            initExecutor.shutdownNow()
        }

        // Start a Ktor server with debug=true to enable /reload.
        // onReload does orderly shutdown (same as production, minus halt).
        val port = java.net.ServerSocket(0).use { it.localPort }
        val shutdownComplete = java.util.concurrent.CountDownLatch(1)
        lateinit var server: io.ktor.server.engine.EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration>
        server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port) {
            val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), GatewayMode.LOCAL)
            holder.manager = manager
            holder.currentAgent = RegistryAgent(id = "test-agent", name = "test-agent", version = "1.0.0")
            module(holder, GatewayMode.LOCAL, debug = true, dev = true, onReload = {
                manager.close()
                server.stop(100, 500)
                shutdownComplete.countDown()
            })
        }
        server.start(wait = false)

        // Verify server is up via health check
        val httpClient = java.net.http.HttpClient.newHttpClient()
        val healthRequest = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI("http://localhost:$port/health"))
            .GET()
            .build()
        val healthResponse = httpClient.send(healthRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
        assertTrue(healthResponse.statusCode() == 200, "Health check should return 200")

        // Trigger reload via HTTP POST
        val reloadRequest = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI("http://localhost:$port/reload"))
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(reloadRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() == 200, "Reload should return 200")

        // Shutdown must complete within 10 seconds
        assertTrue(
            shutdownComplete.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "Reload shutdown did not complete within 10 seconds",
        )
    }

    @Test
    fun fullWebSocketRoundTripWithAttachment() {
        val env = skipIfNoAgent() ?: return
        runBlocking {
            withTimeout(120_000) {
                val input = kotlinx.coroutines.channels.Channel<WsMessage>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val output = kotlinx.coroutines.channels.Channel<WsMessage>(kotlinx.coroutines.channels.Channel.UNLIMITED)

                val handlerJob = launch {
                    handleChatChannels(input, output, env.session, env.manager)
                }

                // Receive Connected
                val connMsg = output.receive()
                assertIs<WsMessage.Connected>(connMsg)

                // Send prompt with image file
                input.send(WsMessage.Prompt(
                    text = "Respond with ONLY the word: received",
                    files = listOf(FileAttachment("test.png", "image/png", tinyPng)),
                ))

                // Collect until TurnComplete
                var gotAgentMessage = false
                var gotTurnComplete = false
                for (msg in output) {
                    when (msg) {
                        is WsMessage.AgentText -> gotAgentMessage = true
                        is WsMessage.ToolCall -> gotAgentMessage = true
                        is WsMessage.TurnComplete -> {
                            gotTurnComplete = true
                            break
                        }
                        else -> {}
                    }
                }
                assertTrue(gotTurnComplete, "Should receive TurnComplete")
                assertTrue(gotAgentMessage, "Agent should produce messages")

                input.close()
                handlerJob.cancel()
            }
        }
    }
}
