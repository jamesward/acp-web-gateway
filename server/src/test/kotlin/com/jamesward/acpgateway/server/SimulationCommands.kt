package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SimulationCommands")

/**
 * Internal commands registered when debug mode is enabled.
 * These appear in the client's slash-command autocomplete.
 * Simulation commands are discovered dynamically from classpath JSON files in /simulations/.
 */
val simulationCommands: List<CommandInfo> by lazy {
    val simNames = discoverSimulationNames()
    simNames.map { name ->
        CommandInfo("simulate-$name", "Replay the $name simulation")
    } + CommandInfo("capture", "Capture agent response for future simulation replay", inputHint = "prompt to send to agent")
}

private fun discoverSimulationNames(): List<String> {
    val url = object {}.javaClass.getResource("/simulations/") ?: return emptyList()
    return java.io.File(url.toURI()).listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?: emptyList()
}

/**
 * Prompt interceptor that handles /simulate-<name> and /capture commands.
 * Pass as `promptInterceptor` to [handleChatChannels].
 */
fun buildSimulationInterceptor(): PromptInterceptor = { command, session ->
    when {
        // /simulate-<name> [fast]
        command.startsWith("/simulate-") -> {
            val rest = command.removePrefix("/simulate-").trim()
            val fast = rest.endsWith(" fast")
            val name = if (fast) rest.removeSuffix(" fast").trim() else rest
            val captured = loadCapturedSimulation(name)
            if (captured != null) {
                replayCapturedSimulation(captured, session, fast)
            } else {
                session.broadcast(WsMessage.Error("No captured simulation found: $name"))
                session.broadcast(WsMessage.TurnComplete("error"))
            }
            PromptAction.Handled
        }

        // Capture mode: /capture <prompt>
        command.startsWith("/capture ") -> {
            val realPrompt = command.removePrefix("/capture ").trim()
            PromptAction.Capture(realPrompt)
        }

        else -> PromptAction.Normal
    }
}

/**
 * Capture callback that serializes captured messages to a JSON file.
 * Pass as `captureCallback` to [handleChatChannels].
 */
val defaultCaptureCallback: CaptureCallback = { captured, session ->
    val messages = captured.map { (_, msg) -> CapturedMessage(msg) }
    val jsonOutput = serializeCapturedSimulation(messages)
    val outFile = java.io.File(session.cwd, "simulation-capture-${System.currentTimeMillis()}.json")
    outFile.writeText(jsonOutput)
    logger.info("Captured simulation saved: {} ({} messages)", outFile.absolutePath, messages.size)
    session.broadcast(WsMessage.AgentText(
        msgId = "capture-info",
        markdown = "Captured ${messages.size} messages to `${outFile.name}`. Move to `server/src/test/resources/simulations/<name>.json` to use with `/simulate-<name>`.",
    ))
    session.broadcast(WsMessage.TurnComplete("end_turn"))
}
