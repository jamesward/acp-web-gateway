package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.jamesward.acpgateway.shared.WsMessage
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import io.orangebuffalo.testcontainers.playwright.PlaywrightApi
import io.orangebuffalo.testcontainers.playwright.PlaywrightContainer
import org.slf4j.LoggerFactory
import org.testcontainers.Testcontainers
import java.io.Closeable
import java.util.Base64

/**
 * Manages a Playwright browser container that points at the server's own web UI.
 * Lazily starts on first `/autopilot` command. Takes screenshots for the agent to analyze.
 */
class AutoPilot(private val port: Int) : Closeable {
    private val logger = LoggerFactory.getLogger(AutoPilot::class.java)

    private var container: PlaywrightContainer? = null
    private var playwrightApi: PlaywrightApi? = null
    private var browser: Browser? = null
    private var page: Page? = null

    private val baseUrl get() = "http://host.testcontainers.internal:$port"

    val isRunning: Boolean get() = container != null

    /**
     * Start the Playwright container and open a browser page pointing at the server UI.
     */
    fun start() {
        if (isRunning) return
        logger.info("Starting Playwright container for autopilot...")

        Testcontainers.exposeHostPorts(port)

        val c = PlaywrightContainer()
        c.start()
        container = c

        val api = c.getPlaywrightApi()
        playwrightApi = api

        val b = api.chromium()
        browser = b

        val p = b.newPage()
        page = p

        p.navigate(baseUrl)
        // Wait for the page to connect to the WebSocket
        p.waitForFunction(
            "() => { const el = document.getElementById('agent-info'); return el && !el.textContent.includes('Connecting'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(15000.0),
        )

        logger.info("Autopilot browser connected to {}", baseUrl)
    }

    /**
     * Take a PNG screenshot of the current page and return it as base64.
     */
    fun takeScreenshot(): String {
        val p = page ?: error("AutoPilot not started")
        val bytes = p.screenshot()
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Command handler for `/autopilot`. Starts the container on first call,
     * takes a screenshot, and returns a modified prompt for the agent.
     */
    fun handleCommand(prompt: WsMessage.Prompt, session: GatewaySession): WsMessage.Prompt? {
        if (prompt.text.trim() != "/autopilot") return null

        if (!isRunning) {
            start()
        }

        // Refresh the page to see latest state
        page?.reload(Page.ReloadOptions().setTimeout(10000.0))
        page?.waitForFunction(
            "() => { const el = document.getElementById('agent-info'); return el && !el.textContent.includes('Connecting'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(15000.0),
        )

        val screenshot = takeScreenshot()

        val autopilotPrompt = """
            |You are now in autopilot mode. A real browser is connected to your own web UI.
            |The attached screenshot shows the current state of the UI.
            |
            |Your task: Use this screenshot to evaluate the web UI as a real user would. Look for:
            |- UI bugs or visual glitches
            |- Confusing or unintuitive interactions
            |- Missing features that would improve the user experience
            |- Visual inconsistencies or alignment issues
            |- Accessibility problems
            |- Any rough edges that could be polished
            |
            |Pick the single most impactful issue you can find and fix it.
            |After making changes, the user can send /autopilot again to get a fresh screenshot.
        """.trimMargin()

        return WsMessage.Prompt(text = autopilotPrompt, screenshot = screenshot)
    }

    override fun close() {
        logger.info("Shutting down autopilot")
        try { page?.close() } catch (_: Exception) {}
        try { container?.close() } catch (_: Exception) {}
        page = null
        browser = null
        playwrightApi = null
        container = null
    }
}
