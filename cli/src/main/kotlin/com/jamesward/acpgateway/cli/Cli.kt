package com.jamesward.acpgateway.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.jamesward.acpgateway.shared.AgentProcessManager
import com.jamesward.acpgateway.shared.AgentSwitchException
import com.jamesward.acpgateway.shared.RegistryAgent
import com.jamesward.acpgateway.shared.WsMessage
import com.jamesward.acpgateway.shared.fetchRegistry
import com.jamesward.acpgateway.shared.handleChatWebSocket
import com.jamesward.acpgateway.shared.resolveAgentCommand
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("acp2web")
private val json = Json { ignoreUnknownKeys = true }

class Acp2Web : CliktCommand(name = "acp2web") {
    val agent by option("--agent", help = "Agent ID from the ACP registry")
    val gateway by option("--gateway", help = "Gateway server URL").default("https://www.acp2web.com")

    override fun run() {
        runBlocking {
            val registry = fetchRegistry()
            val workingDir = System.getProperty("user.dir")
            val sessionId = UUID.randomUUID()
            val wsBase = gateway
                .replace("http://", "ws://")
                .replace("https://", "wss://") + "/s/$sessionId/agent"
            val pageUrl = "$gateway/s/$sessionId"

            echo("ACP Web Gateway CLI")
            echo("Session: $pageUrl")
            echo("Open this URL in your browser.")
            echo()

            var currentAgentId: String? = agent
            var currentManager: AgentProcessManager? = null

            // Shutdown hook cleans up the current manager
            val shutdownHook = Thread({
                currentManager?.close()
            }, "acp2web-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            val httpClient = HttpClient(CIO) {
                install(WebSockets)
            }

            try {
                // Main agent lifecycle loop: handles initial selection and switching
                while (true) {
                    // Start agent if we know which one to use
                    if (currentAgentId != null && currentManager == null) {
                        currentManager = startAgent(currentAgentId!!, registry, workingDir)
                        echo("Agent: ${currentManager!!.agentName} ${currentManager!!.agentVersion}")
                    }

                    val wsUrl = if (currentAgentId != null) "$wsBase?agent=$currentAgentId" else wsBase
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
                echo("Error: Failed to connect to gateway at $gateway")
                echo("Make sure the gateway is running in proxy mode.")
            } finally {
                httpClient.close()
                currentManager?.close()
            }
        }
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

fun main(args: Array<String>) = Acp2Web().main(args)
