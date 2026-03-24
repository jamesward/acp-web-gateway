package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.GatewaySession
import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SimulationCapture")
private val captureJson = Json { prettyPrint = true }

private const val PACE_MS = 100L
private const val FAST_PACE_MS = 1L

/**
 * A single captured message for simulation replay.
 */
@Serializable
data class CapturedMessage(
    val message: WsMessage,
)

/**
 * Replay a list of captured messages by broadcasting them with fixed pacing.
 * Uses 100ms between messages (or 1ms in fast mode).
 */
suspend fun replayCapturedSimulation(
    messages: List<CapturedMessage>,
    session: GatewaySession,
    fast: Boolean = false,
) {
    session.store.setPromptStartTime(session.id, System.currentTimeMillis())
    session.store.clearToolCalls(session.id)
    val pace = if (fast) FAST_PACE_MS else PACE_MS
    try {
        for (captured in messages) {
            delay(pace)
            session.broadcast(captured.message)
        }
        val hasTurnComplete = messages.any { it.message is WsMessage.TurnComplete }
        if (!hasTurnComplete) {
            session.broadcast(WsMessage.TurnComplete("end_turn"))
        }
    } finally {
        session.store.clearToolCalls(session.id)
        session.store.setPromptStartTime(session.id, 0L)
        session.store.clearTurnState(session.id)
        session.clearTurnBuffer()
    }
}

/**
 * Load a captured simulation from a classpath resource.
 * Resource path: /simulations/<name>.json
 */
fun loadCapturedSimulation(name: String): List<CapturedMessage>? {
    val resourcePath = "/simulations/$name.json"
    val stream = object {}.javaClass.getResourceAsStream(resourcePath)
    if (stream == null) {
        logger.warn("Captured simulation not found: {}", resourcePath)
        return null
    }
    val jsonText = stream.bufferedReader().use { it.readText() }
    return captureJson.decodeFromString<List<CapturedMessage>>(jsonText)
}

/**
 * Serialize captured messages to JSON for saving to a file.
 */
fun serializeCapturedSimulation(messages: List<CapturedMessage>): String {
    return captureJson.encodeToString<List<CapturedMessage>>(messages)
}
