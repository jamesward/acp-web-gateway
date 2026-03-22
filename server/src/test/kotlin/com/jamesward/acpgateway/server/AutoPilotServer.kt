package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.jamesward.acpgateway.shared.CommandInfo
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AutoPilotServer")

/**
 * Runs the dev server with test classpath, enabling the `/autopilot` command.
 * When `/autopilot` is sent in the chat, a Playwright container starts (via Testcontainers)
 * and the agent receives a screenshot of its own web UI to analyze and improve.
 *
 * Usage: ./gradlew :server:runAutoPilot --args="--agent claude-code --debug --dev"
 */
fun main(args: Array<String>) {
    val config = parseDevServerConfig(args)
    val autoPilot = AutoPilot(config.port)

    Runtime.getRuntime().addShutdownHook(Thread({
        autoPilot.close()
    }, "autopilot-shutdown"))

    logger.info("Starting dev server with autopilot support (send /autopilot in chat to activate)")

    startDevServer(config.copy(
        commandHandler = { prompt, session -> autoPilot.handleCommand(prompt, session) },
        internalCommands = listOf(CommandInfo("autopilot", "Take a screenshot of the UI and send it to the agent for analysis")),
    ))
}
