package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.*
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.orangebuffalo.testcontainers.playwright.PlaywrightApi
import io.orangebuffalo.testcontainers.playwright.PlaywrightContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.Testcontainers
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserIntegrationTest {

    companion object {
        private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
        private lateinit var manager: AgentProcessManager
        private lateinit var container: PlaywrightContainer
        private lateinit var playwrightApi: PlaywrightApi
        private lateinit var browser: Browser
        private var port: Int = 0
        private val baseUrl get() = "http://host.testcontainers.internal:$port"

        @JvmStatic
        @BeforeClass
        fun startServer() {
            port = ServerSocket(0).use { it.localPort }
            manager = AgentProcessManager(ProcessCommand("echo", listOf("test")), System.getProperty("user.dir"))
            manager.agentName = "test-agent"
            manager.agentVersion = "1.0.0"

            val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), GatewayMode.LOCAL)
            holder.manager = manager
            holder.currentAgent = RegistryAgent(id = "test-agent", name = "test-agent", version = "1.0.0")
            server = embeddedServer(CIO, port = port) {
                module(holder, GatewayMode.LOCAL, debug = true)
            }
            server.start(wait = false)

            Testcontainers.exposeHostPorts(port)

            container = PlaywrightContainer()
            container.start()

            playwrightApi = container.getPlaywrightApi()
            browser = playwrightApi.chromium()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            container.close()
            server.stop(500, 1000)
        }
    }

    private lateinit var fakeSession: ControllableFakeClientSession
    private lateinit var clientOps: GatewayClientOperations
    private lateinit var page: Page

    @Before
    fun setUp() {
        fakeSession = ControllableFakeClientSession()
        clientOps = GatewayClientOperations()
        val sessionId = UUID.randomUUID()
        val testScope = CoroutineScope(Dispatchers.Default)
        val session = GatewaySession(sessionId, fakeSession, clientOps, System.getProperty("user.dir"), testScope, manager.store)
        session.ready = true
        session.startEventForwarding()
        manager.sessions.clear()
        manager.sessions[sessionId] = session

        val context = browser.newContext(Browser.NewContextOptions().setLocale("en-US"))
        page = context.newPage()
    }

    @After
    fun tearDown() {
        page.close()
    }

    @Test
    fun pageLoadsAndConnects() {
        // Use Playwright's network-level WebSocket detection — no DOM dependency
        val ws = page.waitForWebSocket {
            page.navigate(baseUrl)
        }

        // Kilua RPC uses /rpcws/<auto_name> path
        assertTrue(ws.url().contains("/rpcws/"), "WebSocket URL should contain /rpcws/, got: ${ws.url()}")
        assertTrue(!ws.isClosed, "WebSocket should be open")
    }
}
