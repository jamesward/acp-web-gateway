package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("SimulationServer")

private const val AUTO_PROMPT = "Explain how Kotlin coroutines work, show examples, and walk me through advanced patterns."

// buildSimulationResponse() is now in main sources (SimulationData.kt)

/**
 * A ControllableFakeClientSession that automatically re-enqueues
 * the simulation response after each prompt, enabling re-runs.
 */
class ReplayableFakeClientSession(private val clientOps: GatewayClientOperations? = null) : ControllableFakeClientSession() {
    init {
        enqueueResponse(buildSimulationResponse(clientOps))
    }

    override suspend fun prompt(content: List<ContentBlock>, _meta: kotlinx.serialization.json.JsonElement?): kotlinx.coroutines.flow.Flow<Event> {
        val result = super.prompt(content, _meta)
        // Always have a response ready for the next prompt
        enqueueResponse(buildSimulationResponse(clientOps))
        return result
    }
}

private fun Application.simulationModule(
    manager: AgentProcessManager,
    session: GatewaySession,
    autoPromptText: String,
) {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
    }

    routing {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                chatPage("Simulated Agent", debug = true)
            }
        }

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        webSocket("/ws") {
            handleChatWebSocket(session, manager, autoPromptText, debug = true)
        }

        staticResources("/static", "static")
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val command = ProcessCommand("fake", emptyList())
    val manager = AgentProcessManager(command, System.getProperty("user.dir"))
    manager.agentName = "Simulated Agent"
    manager.agentVersion = "1.0.0"

    val clientOps = GatewayClientOperations()
    val fakeSession = ReplayableFakeClientSession(clientOps)

    val testScope = CoroutineScope(Dispatchers.Default)
    val session = GatewaySession(
        id = UUID.randomUUID(),
        clientSession = fakeSession,
        clientOps = clientOps,
        cwd = System.getProperty("user.dir"),
        scope = testScope,
        store = manager.store,
    )
    session.ready = true
    session.startEventForwarding()
    manager.sessions[session.id] = session

    logger.info("Starting simulation server on port {}", port)

    val server = embeddedServer(CIO, configure = {
        connector { this.port = port }
    }) {
        simulationModule(manager, session, AUTO_PROMPT)
    }
    server.start(wait = false)

    logger.info("Simulation server started on http://localhost:{}", port)
    logger.info("Connect a browser to see the simulated agent interaction")
    logger.info("Send any message to re-run the simulation")

    Runtime.getRuntime().addShutdownHook(Thread({
        logger.info("Shutting down simulation server")
        server.stop(100, 500)
    }, "sim-shutdown"))

    Thread.currentThread().join()
}
