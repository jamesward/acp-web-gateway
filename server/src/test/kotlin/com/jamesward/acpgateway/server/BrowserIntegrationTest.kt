package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.agentclientprotocol.model.ContentBlock
import com.jamesward.acpgateway.shared.Id
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.FilePayload
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.orangebuffalo.testcontainers.playwright.PlaywrightApi
import io.orangebuffalo.testcontainers.playwright.PlaywrightContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.Testcontainers
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        page = browser.newPage()
    }

    @After
    fun tearDown() {
        page.close()
    }

    private fun waitForConnected() {
        page.navigate(baseUrl)
        page.waitForFunction(
            "() => { const el = document.getElementById('agent-info'); return el && !el.textContent.includes('Connecting'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
    }

    @Test
    fun pageLoadsAndConnects() {
        waitForConnected()

        val agentInfo = page.textContent("#agent-info")
        assertTrue(agentInfo.contains("test-agent"), "Agent info should contain 'test-agent', got: $agentInfo")

        assertNotNull(page.querySelector("#prompt-form"))
        assertNotNull(page.querySelector("#prompt-input"))
        assertNotNull(page.querySelector("#send-btn"))
        assertNotNull(page.querySelector("#messages"))

        val permDialogClasses = page.getAttribute("#permission-dialog", "class") ?: ""
        assertTrue(permDialogClasses.contains("hidden"), "Permission dialog should be hidden")
    }

    @Test
    fun sendPromptShowsAgentResponse() {
        fakeSession.enqueueTextResponse("Hello from agent")

        waitForConnected()

        page.fill("#prompt-input", "test message")
        page.click("#send-btn")

        page.waitForFunction(
            "() => { const el = document.querySelector('.message-content'); return el && el.textContent.includes('Hello from agent'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        assertEquals(1, fakeSession.promptHistory.size)
    }

    @Test
    fun sendPromptViaEnter() {
        fakeSession.enqueueTextResponse("Enter response")

        waitForConnected()

        page.fill("#prompt-input", "enter test")
        page.press("#prompt-input", "Enter")

        page.waitForFunction(
            "() => { const el = document.querySelector('.message-content'); return el && el.textContent.includes('Enter response'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        assertEquals(1, fakeSession.promptHistory.size)
    }

    @Test
    fun statusTimerVisibleWhileWorking() {
        val gate = CompletableDeferred<List<Event>>()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Pondering..."))))
                val events = gate.await()
                for (event in events) emit(event)
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        waitForConnected()

        page.fill("#prompt-input", "slow task")
        page.click("#send-btn")

        // Wait for the thinking block's elapsed timer to appear in the header
        page.waitForFunction(
            "() => { const el = document.getElementById('${Id.THOUGHT_ELAPSED}'); return el && el.textContent.length > 0; }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
        val elapsedText = page.textContent("#${Id.THOUGHT_ELAPSED}")
        assertTrue(elapsedText.contains("\u00b7"), "Elapsed should contain separator, got: $elapsedText")

        val btnText = page.textContent("#send-btn")
        assertEquals("Cancel", btnText)

        gate.complete(emptyList())

        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
    }

    @Test
    fun cancelStopsAgentWork() {
        val gate = CompletableDeferred<List<Event>>()
        fakeSession.enqueueDelayedResponse(gate)

        waitForConnected()

        page.fill("#prompt-input", "cancel me")
        page.click("#send-btn")

        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Cancel'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
        page.click("#send-btn")

        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        gate.complete(emptyList())
    }

    @Test
    fun permissionDialogShowsAndResolves() {
        fakeSession.enqueueResponse {
            flow {
                clientOps.requestPermissions(
                    toolCall = SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId("test-tc-1"),
                        title = "Allow read access to /foo",
                    ),
                    permissions = listOf(
                        PermissionOption(PermissionOptionId("allow-once"), "Allow Once", PermissionOptionKind.ALLOW_ONCE),
                        PermissionOption(PermissionOptionId("deny"), "Deny", PermissionOptionKind.REJECT_ONCE),
                    ),
                )
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        waitForConnected()

        page.fill("#prompt-input", "perm test")
        page.click("#send-btn")

        page.waitForFunction(
            "() => { const el = document.getElementById('permission-dialog'); return el && !el.classList.contains('hidden'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        val descText = page.textContent(".perm-desc")
        assertTrue(descText.contains("Allow read access to /foo"), "Permission desc should contain title, got: $descText")

        page.click("[data-option-id='allow-once']")

        page.waitForFunction(
            "() => { const el = document.getElementById('permission-dialog'); return el && el.classList.contains('hidden'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )
    }

    @Test
    fun screenshotCheckboxSendsImage() {
        fakeSession.enqueueTextResponse("got it")

        waitForConnected()

        // Stub captureScreenshot to return a known base64 PNG so we don't depend on
        // SVG foreignObject rendering (which may fail in headless Chromium).
        page.evaluate("""() => {
            window.__origCapture = window.captureScreenshot;
            // The WASM client calls captureScreenshot(callback) where callback is a
            // JsFun-wrapped Kotlin function. We override the global so the next call
            // returns our tiny 1x1 PNG immediately.
            const tinyPng = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==';
            // The function is not on window directly — it's compiled into WASM. Instead,
            // we intercept at the HTMLCanvasElement.toDataURL level so the real capture
            // path produces our known data.
            HTMLCanvasElement.prototype._origToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = function(type) {
                return 'data:image/png;base64,' + tinyPng;
            };
        }""")

        page.check("#screenshot-toggle")
        page.fill("#prompt-input", "screenshot test")
        page.click("#send-btn")

        page.waitForFunction(
            "() => { const el = document.querySelector('.message-content'); return el && el.textContent.includes('got it'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        assertEquals(1, fakeSession.promptHistory.size)
        val blocks = fakeSession.promptHistory[0]
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        assertTrue(images.isNotEmpty(), "Prompt should contain a screenshot Image block, got: ${blocks.map { it::class.simpleName }}")
        assertEquals("image/png", images[0].mimeType)
    }

    @Test
    fun multipleToolCallsAllVisibleInExpandedBlock() {
        fakeSession.enqueueResponse {
            flow {
                for (i in 1..5) {
                    emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                        toolCallId = ToolCallId("tc-$i"),
                        title = "Tool $i",
                        status = ToolCallStatus.IN_PROGRESS,
                    )))
                    emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId("tc-$i"),
                        title = "Tool $i",
                        status = ToolCallStatus.COMPLETED,
                    )))
                }
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("All done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        waitForConnected()

        page.fill("#prompt-input", "do tools")
        page.click("#send-btn")

        // Wait for turn to complete
        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        // Verify summary shows 5 tool calls
        page.waitForFunction(
            "() => { const s = document.querySelector('.tool-summary'); return s && s.textContent.includes('5 tool calls'); }",
            null,
            Page.WaitForFunctionOptions().setTimeout(5000.0),
        )

        // Expand the tool block
        page.click(".tool-header")

        // Count visible tool rows
        val toolRows = page.querySelectorAll(".tool-row")
        assertEquals(5, toolRows.size, "Should have 5 tool rows in expanded block")

        // Verify each tool title is present
        for (i in 1..5) {
            val found = page.querySelectorAll(".tool-title").any { it.textContent().contains("Tool $i") }
            assertTrue(found, "Tool $i should be visible")
        }
    }

    @Test
    fun userToggledBlockStatePersistsAcrossNewMessages() {
        // First response: thinking + response
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking about it"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("First response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        // Second response
        fakeSession.enqueueTextResponse("Second response")

        waitForConnected()

        // Send first prompt
        page.fill("#prompt-input", "first message")
        page.click("#send-btn")

        // Wait for turn to complete
        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        // Verify thinking block is open (default)
        val thinkingDetails = page.locator(".content-thought > details")
        assertTrue(
            page.evaluate("el => el.open", thinkingDetails.elementHandle()).toString().toBoolean(),
            "Thinking block should be open initially",
        )

        // User collapses the thinking block
        page.click(".content-thought .content-header")

        // Verify it's now closed
        assertTrue(
            !page.evaluate("el => el.open", thinkingDetails.elementHandle()).toString().toBoolean(),
            "Thinking block should be closed after user click",
        )

        // Verify data-user-toggled is set
        assertTrue(
            page.evaluate("el => el.hasAttribute('data-user-toggled')", thinkingDetails.elementHandle()).toString().toBoolean(),
            "Thinking block should have data-user-toggled after user toggle",
        )

        // Send second prompt (triggers BeforeEnd + autoCollapse)
        page.fill("#prompt-input", "second message")
        page.click("#send-btn")

        page.waitForFunction(
            "() => document.querySelector('#send-btn').textContent === 'Send'",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        // Verify the thinking block is STILL closed
        assertTrue(
            !page.evaluate("el => el.open", thinkingDetails.elementHandle()).toString().toBoolean(),
            "Thinking block should remain closed after new messages",
        )
    }

    @Test
    fun fileAttachmentShowsPreview() {
        waitForConnected()

        val fileInput = page.locator("input[type=file]")
        fileInput.setInputFiles(FilePayload("test.txt", "text/plain", "hello world".toByteArray()))

        page.waitForFunction(
            "() => document.querySelector('.file-chip') !== null",
            null,
            Page.WaitForFunctionOptions().setTimeout(10000.0),
        )

        val chipText = page.textContent(".file-chip")
        assertTrue(chipText.contains("test.txt"), "Chip should contain filename, got: $chipText")
    }
}
