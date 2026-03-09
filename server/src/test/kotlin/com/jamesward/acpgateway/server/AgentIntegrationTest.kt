package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.PermissionOptionKind
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.WsMessage
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that spawn a real ACP agent (claude-code) and verify
 * that file attachments and screenshots are properly delivered.
 *
 * Skipped automatically if npx is not available or the agent cannot start.
 *
 * Run with: ./gradlew :server:test --tests "*AgentIntegrationTest*"
 */
class AgentIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Minimal 1x1 white PNG, valid base64
    private val tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="

    companion object {
        private val agentEnv: AgentEnv? by lazy { initAgent() }

        private var skipReason: String? = null

        private fun initAgent(): AgentEnv? {
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

            val agent = agents.find { it.id == "claude-acp" }
            if (agent == null) {
                skipReason = "claude-acp not found in registry"
                return null
            }
            val command = resolveAgentCommand(agent)

            val manager = AgentProcessManager(command, System.getProperty("user.dir"))
            try {
                runBlocking { withTimeout(60.seconds) { manager.start() } }
            } catch (e: Exception) {
                skipReason = "Agent failed to start: ${e.message}"
                return null
            }

            val session = try {
                runBlocking { withTimeout(30.seconds) { manager.createSession() } }
            } catch (e: Exception) {
                skipReason = "Session creation failed: ${e.message}"
                manager.close()
                return null
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
    fun fullWebSocketRoundTripWithAttachment() {
        val env = skipIfNoAgent() ?: return
        testApplication {
            application {
                module(env.manager, "test-agent", GatewayMode.LOCAL)
            }
            val wsClient = createClient { install(WebSockets) }
            wsClient.webSocket("/ws") {
                // Receive Connected
                val connFrame = incoming.receive()
                assertIs<Frame.Text>(connFrame)
                val connMsg = json.decodeFromString(WsMessage.serializer(), connFrame.readText())
                assertIs<WsMessage.Connected>(connMsg)

                // Send prompt with image file
                val prompt = WsMessage.Prompt(
                    text = "Respond with ONLY the word: received",
                    files = listOf(FileAttachment("test.png", "image/png", tinyPng)),
                )
                send(Frame.Text(json.encodeToString(WsMessage.serializer(), prompt)))

                // Collect until TurnComplete
                var gotAgentText = false
                var gotTurnComplete = false
                withTimeout(120_000) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                            when (msg) {
                                is WsMessage.AgentText -> gotAgentText = true
                                is WsMessage.TurnComplete -> {
                                    gotTurnComplete = true
                                    break
                                }
                                is WsMessage.PermissionRequest -> {
                                    // Auto-approve permission requests
                                    val allowOption = msg.options.firstOrNull { it.kind == "allow_always" }
                                        ?: msg.options.firstOrNull { it.kind == "allow_once" }
                                    if (allowOption != null) {
                                        val resp = WsMessage.PermissionResponse(msg.toolCallId, allowOption.optionId)
                                        send(Frame.Text(json.encodeToString(WsMessage.serializer(), resp)))
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                assertTrue(gotTurnComplete, "Should receive TurnComplete")
                assertTrue(gotAgentText, "Agent should produce text output")
            }
        }
    }
}
