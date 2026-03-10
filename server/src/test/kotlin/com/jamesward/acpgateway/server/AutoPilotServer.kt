package com.jamesward.acpgateway.server

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AutoPilotServer")

/**
 * Runs the real server with test classpath, enabling the `/autopilot` command.
 * When `/autopilot` is sent in the chat, a Playwright container starts (via Testcontainers)
 * and the agent receives a screenshot of its own web UI to analyze and improve.
 *
 * Usage: ./gradlew :server:runAutoPilot --args="--agent claude-code --debug --dev"
 */
fun main(args: Array<String>) {
    val config = parseServerConfig(args)
    val autoPilot = AutoPilot(config.port)

    Runtime.getRuntime().addShutdownHook(Thread({
        autoPilot.close()
    }, "autopilot-shutdown"))

    logger.info("Starting server with autopilot support (send /autopilot in chat to activate)")

    startServer(config.copy(
        commandHandler = { prompt, session -> autoPilot.handleCommand(prompt, session) },
    ))
}
