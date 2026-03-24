package com.jamesward.acpgateway.cli

import ch.qos.logback.classic.Level
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.options.versionOption
import com.jamesward.acpgateway.shared.*
import io.github.z4kn4fein.semver.toVersionOrNull
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import java.util.*

private val logger = LoggerFactory.getLogger("acp2web")
private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_REMOTE_URL = "https://www.acp2web.com"
private const val DOCKER_IMAGE = "ghcr.io/jamesward/acp-web-gateway"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/jamesward/acp-web-gateway/releases/latest"

internal val cliVersion: String by lazy {
    val props = Properties()
    Acp2Web::class.java.getResourceAsStream("/version.properties")?.use { props.load(it) }
    props.getProperty("version", "unknown").removePrefix("v")
}

/**
 * Strips leading 'v' and any git-describe suffix (e.g. "v0.0.10-3-gabcdef" → "0.0.10")
 * to produce a clean semver string.
 */
internal fun cleanVersionString(raw: String): String {
    val stripped = raw.removePrefix("v")
    // git-describe appends "-<N>-g<hash>" for dirty versions
    val dashParts = stripped.split("-")
    return if (dashParts.size >= 3 && dashParts.last().startsWith("g")) {
        dashParts.dropLast(2).joinToString("-")
    } else {
        stripped
    }
}

@Serializable
private data class ServerState(val port: Int, val containerId: String)

private val stateDir = File(System.getProperty("user.home"), ".acp2web")
private val stateFile = File(stateDir, "server.json")

class Acp2Web : CliktCommand(name = "acp2web") {
    init {
        versionOption(cliVersion, names = setOf("--version", "-V"))
    }

    val agent by option("--agent", help = "Agent ID from the ACP registry")
    val agentCommand by option("--agent-command", help = "Custom agent command (e.g. \"kiro-cli acp\")")
    val remote by option("--remote", help = "Connect to a remote gateway server (default: $DEFAULT_REMOTE_URL)")
        .optionalValue(DEFAULT_REMOTE_URL)
    val debug by option("--debug", help = "Enable debug logging").flag()

    override fun run() {
        if (debug) {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            root.level = Level.DEBUG
        }

        require(agent == null || agentCommand == null) {
            "--agent and --agent-command are mutually exclusive"
        }

        runBlocking {
            val registry = fetchRegistry()
            val workingDir = System.getProperty("user.dir")
            val sessionId = UUID.randomUUID()

            val httpClient = HttpClient(CIO) {
                install(WebSockets)
            }

            val gatewayUrl: String
            val isDocker: Boolean

            if (remote != null) {
                // Remote mode — connect to specified or default remote server
                gatewayUrl = remote!!
                isDocker = false
            } else {
                // Docker mode (default) — ensure local Docker container is running
                gatewayUrl = ensureDockerServer(httpClient)
                isDocker = true
            }

            val wsBase = gatewayUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://") + "/s/$sessionId/agent"
            val pageUrl = "$gatewayUrl/s/$sessionId"

            echo("ACP Web Gateway CLI $cliVersion")
            checkForUpdate(httpClient)
            echo("Session: $pageUrl")
            echo("Open this URL in your browser.")
            echo()

            var currentAgentId: String? = agent
            var currentManager: AgentProcessManager? = null

            // Start custom agent command immediately if specified
            if (agentCommand != null) {
                currentManager = startAgentFromCommand(agentCommand!!, workingDir)
                echo("Agent: ${currentManager.agentName} ${currentManager.agentVersion}")
            }

            // Shutdown hook cleans up the current manager and optionally stops Docker
            val shutdownHook = Thread({
                currentManager?.close()
            }, "acp2web-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            try {
                // Main agent lifecycle loop: handles initial selection and switching
                while (true) {
                    // Start agent if we know which one to use (skip if custom command already started)
                    if (currentAgentId != null && currentManager == null) {
                        currentManager = startAgent(currentAgentId, registry, workingDir)
                        echo("Agent: ${currentManager.agentName} ${currentManager.agentVersion}")
                    }

                    val wsUrl = if (currentAgentId != null || agentCommand != null) "$wsBase?agent=${currentAgentId ?: "custom"}" else wsBase
                    var justSelectedAgent = false

                    try {
                        httpClient.webSocket(urlString = wsUrl) {
                            val mgr = currentManager
                            if (mgr == null) {
                                // No agent yet — wait for user to select one in the browser
                                echo("Waiting for agent selection in browser...")
                                val selectedAgentId = waitForChangeAgent()
                                currentAgentId = selectedAgentId
                                currentManager = startAgent(selectedAgentId, registry, workingDir)
                                echo("Agent: ${currentManager!!.agentName} ${currentManager!!.agentVersion}")
                                // Reconnect with the agent query param so the server knows
                                justSelectedAgent = true
                                return@webSocket
                            }

                            val session = mgr.createSession()
                            handleChatWebSocket(session, mgr)
                        }

                        // After initial agent selection, loop to reconnect with agent query param
                        if (justSelectedAgent) continue
                        // Normal WebSocket close — exit
                        break
                    } catch (e: AgentSwitchException) {
                        echo("Switching to agent: ${e.agentId}")
                        currentManager?.close()
                        currentManager = null
                        currentAgentId = e.agentId
                        // Loop continues — will start new agent and reconnect
                    }
                }
            } catch (e: Exception) {
                logger.error("WebSocket connection failed", e)
                echo("Error: Failed to connect to gateway at $gatewayUrl")
                if (remote != null) {
                    echo("Make sure the gateway is running.")
                } else {
                    echo("Make sure Docker is running.")
                }
            } finally {
                httpClient.close()
                currentManager?.close()
            }
        }
    }
}

/**
 * Ensures a Docker gateway server is running.
 * Returns the base URL (e.g., "http://localhost:12345").
 */
private suspend fun ensureDockerServer(httpClient: HttpClient): String {
    // Check existing state file
    if (stateFile.exists()) {
        try {
            val state = json.decodeFromString(ServerState.serializer(), stateFile.readText())
            // Ping health endpoint
            if (isServerAlive(httpClient, state.port)) {
                logger.info("Reusing existing Docker container {} on port {}", state.containerId, state.port)
                return "http://localhost:${state.port}"
            }
        } catch (_: Exception) {
            // State file corrupt or server not responding — start fresh
        }
        stateFile.delete()
    }

    // Find a free port
    val port = ServerSocket(0).use { it.localPort }

    // Start Docker container
    logger.info("Starting Docker container on port {}", port)
    val process = ProcessBuilder(
        "docker", "run", "-d", "--rm",
        "-p", "$port:8080",
        DOCKER_IMAGE,
        "--shutdown-on-idle",
    ).redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Failed to start Docker container (exit $exitCode): $output")
    }

    val containerId = output.take(12)

    // Write state file
    stateDir.mkdirs()
    val state = ServerState(port, containerId)
    stateFile.writeText(json.encodeToString(ServerState.serializer(), state))

    // Wait for server to be healthy
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
        if (isServerAlive(httpClient, port)) {
            logger.info("Docker container {} started on port {}", containerId, port)
            return "http://localhost:$port"
        }
        Thread.sleep(500)
    }
    error("Docker container started but health check timed out after 30s")
}

private suspend fun isServerAlive(httpClient: HttpClient, port: Int): Boolean {
    return try {
        val response = httpClient.get("http://localhost:$port/health")
        response.status.value == 200
    } catch (_: Exception) {
        false
    }
}


private suspend fun DefaultClientWebSocketSession.waitForChangeAgent(): String {
    for (frame in incoming) {
        if (frame is Frame.Text) {
            val text = frame.readText()
            try {
                val msg = json.decodeFromString(WsMessage.serializer(), text)
                if (msg is WsMessage.ChangeAgent) {
                    return msg.agentId
                }
            } catch (_: Exception) {
                // Ignore non-WsMessage frames
            }
        }
    }
    error("WebSocket closed without receiving agent selection")
}

private fun startAgent(agentId: String, registry: List<RegistryAgent>, workingDir: String): AgentProcessManager {
    val registryAgent = registry.find { it.id == agentId }
        ?: error("Agent '$agentId' not found in registry. Available: ${registry.map { it.id }}")
    val command = resolveAgentCommand(registryAgent)
    val manager = AgentProcessManager(command, workingDir)
    runBlocking { manager.start() }
    return manager
}

private fun startAgentFromCommand(commandString: String, workingDir: String): AgentProcessManager {
    val command = parseCommandString(commandString)
    val manager = AgentProcessManager(command, workingDir)
    runBlocking { manager.start() }
    return manager
}

private suspend fun Acp2Web.checkForUpdate(httpClient: HttpClient) {
    try {
        val currentClean = cleanVersionString(cliVersion)
        val current = currentClean.toVersionOrNull() ?: return

        val response = httpClient.get(GITHUB_RELEASES_URL) {
            header("Accept", "application/vnd.github.v3+json")
        }
        if (response.status.value != 200) return

        val body = json.decodeFromString(JsonObject.serializer(), response.bodyAsText())
        val tagName = body["tag_name"]?.jsonPrimitive?.content ?: return
        val latestClean = cleanVersionString(tagName)
        val latest = latestClean.toVersionOrNull() ?: return

        if (current < latest) {
            echo("Update available: $currentClean → $latestClean (https://github.com/jamesward/acp-web-gateway/releases/latest)")
        }
    } catch (e: Exception) {
        logger.debug("Version check failed", e)
    }
}

fun main(args: Array<String>) = Acp2Web().main(args)
