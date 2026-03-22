@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.jamesward.acpgateway.shared.*
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.orangebuffalo.testcontainers.playwright.PlaywrightApi
import io.orangebuffalo.testcontainers.playwright.PlaywrightContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.Testcontainers
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

            val holder = AgentHolder(emptyList(), System.getProperty("user.dir"))
            holder.manager = manager
            holder.currentAgent = RegistryAgent(id = "test-agent", name = "test-agent", version = "1.0.0")
            server = embeddedServer(CIO, port = port) {
                devModule(holder, debug = true)
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

    /** Navigate and wait for the WASM app to fully load and connect via WebSocket. */
    private fun navigateAndWait(p: Page = page) {
        p.navigate(baseUrl)
        // Wait for textarea (WASM app rendered) then give time for WebSocket Connected message
        p.locator("textarea").waitFor()
        p.waitForTimeout(1500.0)
    }

    /** Submit a prompt and wait for the turn to complete (Send button reappears). */
    private fun submitAndWait(text: String, p: Page = page) {
        p.locator("textarea").fill(text)
        p.locator(".btn-send").click()
        // Wait for the response to appear and turn to complete
        p.locator(".btn-send").waitFor()
        p.waitForTimeout(500.0)
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

    @Test
    fun thinkingAndResponseExpandedByDefault() {
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Thinking about it"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Here is my response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()
        submitAndWait("test")

        // Verify thinking is expanded (has open attribute)
        val thought = page.locator(".msg-thought details").first()
        thought.waitFor()
        assertNotNull(thought.getAttribute("open"), "Thinking should be expanded")

        // Verify response is expanded
        val response = page.locator(".msg-assistant details").first()
        response.waitFor()
        assertNotNull(response.getAttribute("open"), "Response should be expanded")
    }

    @Test
    fun toolCallsCollapsedByDefault() {
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()
        submitAndWait("test")

        val toolBlock = page.locator(".msg-tools > details").first()
        toolBlock.waitFor()
        assertNull(toolBlock.getAttribute("open"), "Tool calls should be collapsed by default")
    }

    @Test
    fun previousTurnCollapsesOnNewMessage() {
        // Enqueue two responses
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("First thought"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("First response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Second thought"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Second response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()

        // First prompt
        submitAndWait("first")

        // Verify first turn is expanded
        val firstThought = page.locator(".msg-thought details").nth(0)
        assertNotNull(firstThought.getAttribute("open"), "First turn thinking should be expanded before second prompt")

        // Second prompt
        submitAndWait("second")

        // First turn's sections should be collapsed
        assertNull(
            page.locator(".msg-thought details").nth(0).getAttribute("open"),
            "First turn's thinking should be collapsed after second prompt"
        )
        assertNull(
            page.locator(".msg-assistant details").nth(0).getAttribute("open"),
            "First turn's response should be collapsed after second prompt"
        )

        // Second turn's sections should be expanded
        assertNotNull(
            page.locator(".msg-thought details").nth(1).getAttribute("open"),
            "Second turn's thinking should be expanded"
        )
        assertNotNull(
            page.locator(".msg-assistant details").nth(1).getAttribute("open"),
            "Second turn's response should be expanded"
        )
    }

    @Test
    fun autoScrollKeepsUserAtBottom() {
        val longText = (1..100).joinToString("\n\n") { "Paragraph $it with enough content to overflow." }
        fakeSession.enqueueTextResponse(longText)

        // Small viewport to guarantee overflow
        page.setViewportSize(800, 300)
        navigateAndWait()
        page.addStyleTag(Page.AddStyleTagOptions().setContent("#messages { scroll-behavior: auto !important; }"))

        submitAndWait("test")
        // Wait for response to render fully
        page.locator(".msg-assistant .msg-body").first().waitFor()
        page.waitForTimeout(1000.0)

        // Verify user is at the bottom after response
        val atBottom = page.evaluate(
            "(() => { const el = document.getElementById('messages'); return (el.scrollHeight - el.scrollTop - el.clientHeight) < 40; })()"
        ) as Boolean
        assertTrue(atBottom, "Should auto-scroll to bottom when new content arrives")
    }

    @Test
    fun scrollButtonAppearsWhenScrolledUp() {
        val longText = (1..100).joinToString("\n\n") { "Paragraph $it with enough content to overflow." }
        fakeSession.enqueueTextResponse(longText)

        // Small viewport to guarantee overflow
        page.setViewportSize(800, 300)
        navigateAndWait()
        page.addStyleTag(Page.AddStyleTagOptions().setContent("#messages { scroll-behavior: auto !important; }"))

        submitAndWait("test")
        page.locator(".msg-assistant").first().waitFor()
        page.waitForTimeout(2000.0)

        // Scroll to top
        page.evaluate("document.getElementById('messages').scrollTop = 0")
        // Wait for scroll event to fire and Kotlin poll to detect it (polls every 200ms)
        page.waitForTimeout(1500.0)

        // Scroll button should be visible
        assertTrue(page.locator(".scroll-btn").isVisible(), "Scroll-to-bottom button should appear when scrolled up")

        // Click scroll button
        page.locator(".scroll-btn").click()
        page.waitForTimeout(1000.0)

        // Should be at bottom now
        val atBottom = page.evaluate(
            "(() => { const el = document.getElementById('messages'); return (el.scrollHeight - el.scrollTop - el.clientHeight) < 40; })()"
        ) as Boolean
        assertTrue(atBottom, "Should be at bottom after clicking scroll button")

        // Scroll button should be gone
        assertFalse(page.locator(".scroll-btn").isVisible(), "Scroll button should disappear after scrolling to bottom")
    }

    @Test
    fun scrollButtonHiddenWhenContentDoesNotOverflow() {
        // Short response that won't overflow the viewport
        fakeSession.enqueueTextResponse("Short answer.")

        navigateAndWait()
        submitAndWait("test")
        page.locator(".msg-assistant").first().waitFor()
        // Wait for scroll polling to settle (polls every 200ms)
        page.waitForTimeout(1500.0)

        // Content doesn't overflow — scroll button must NOT appear
        val hasOverflow = page.evaluate(
            "(() => { const el = document.getElementById('messages'); return el.scrollHeight > el.clientHeight; })()"
        ) as Boolean
        assertFalse(hasOverflow, "Content should not overflow the messages container")
        assertFalse(page.locator(".scroll-btn").isVisible(), "Scroll button should not appear when content does not overflow")
    }

    @Test
    fun sectionOrderIsThinkingToolsResponse() {
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Thinking"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()
        submitAndWait("test")

        // Get all message sections in order (skip user message)
        val sections = page.locator("#messages > div.msg:not(.msg-user)")
        val count = sections.count()
        assertTrue(count >= 3, "Should have at least 3 sections (thought, tools, response), got $count")

        // Verify order: thought, tools, response
        val classes = (0 until count).map { sections.nth(it).getAttribute("class") ?: "" }
        val thoughtIdx = classes.indexOfFirst { "msg-thought" in it }
        val toolsIdx = classes.indexOfFirst { "msg-tools" in it }
        val responseIdx = classes.indexOfFirst { "msg-assistant" in it }

        assertTrue(thoughtIdx >= 0, "Should have thinking section")
        assertTrue(toolsIdx >= 0, "Should have tools section")
        assertTrue(responseIdx >= 0, "Should have response section")
        assertTrue(thoughtIdx < toolsIdx, "Thinking should come before tools")
        assertTrue(toolsIdx < responseIdx, "Tools should come before response")
    }

    @Test
    fun deltaStreamingAccumulatesInClient() {
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Hello "))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("world "))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("from deltas!"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()
        submitAndWait("test")

        // The client should accumulate all delta chunks into a single response
        val response = page.locator(".msg-assistant .msg-body").first()
        response.waitFor()
        val text = response.textContent() ?: ""
        assertTrue(text.contains("Hello world from deltas!"), "Client should accumulate deltas: got '$text'")
    }

    @Test
    fun deltaThoughtStreamingAccumulatesInClient() {
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Thinking "))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("deeply "))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("about this"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        navigateAndWait()
        submitAndWait("test")

        // The client should accumulate thought deltas
        val thought = page.locator(".msg-thought .msg-body").first()
        thought.waitFor()
        val text = thought.textContent() ?: ""
        assertTrue(text.contains("Thinking deeply about this"), "Client should accumulate thought deltas: got '$text'")
    }

    @Test
    fun fileUploadSendsFileToAgent() {
        fakeSession.enqueueTextResponse("Got your file")

        navigateAndWait()

        // Create a temp text file to upload
        val tmpFile = Files.createTempFile("test-upload", ".txt")
        Files.writeString(tmpFile, "hello from test file")

        // Click the attach button and use Playwright's FileChooser to set the file
        val fileChooser = page.waitForFileChooser {
            page.locator(".btn-attach").click()
        }
        fileChooser.setFiles(tmpFile)

        // Verify file chip appears in the UI
        val fileChip = page.locator(".file-chip")
        fileChip.waitFor()
        assertTrue(fileChip.textContent()?.contains("test-upload") == true, "File chip should show filename")

        // Submit the prompt with the attached file
        submitAndWait("check this file")

        // File chip should be cleared after sending
        assertEquals(0, page.locator(".file-chip").count(), "File chips should be cleared after sending")

        // Verify the fake session received the file inlined into the text block
        assertTrue(fakeSession.promptHistory.isNotEmpty(), "Should have received a prompt")
        val contentBlocks = fakeSession.promptHistory.last()
        val textBlocks = contentBlocks.filterIsInstance<ContentBlock.Text>()
        assertTrue(textBlocks.isNotEmpty(), "Prompt should contain a Text content block")
        val textContent = textBlocks.last().text
        assertTrue(textContent.contains("hello from test file"), "Text block should contain inlined file content")
        assertTrue(textContent.contains("check this file"), "Text block should contain the prompt text")

        // Clean up
        Files.deleteIfExists(tmpFile)
    }

    @Test
    fun fileOnlyPromptSendsToAgent() {
        fakeSession.enqueueTextResponse("Got your file")

        navigateAndWait()

        // Create a temp file to upload
        val tmpFile = Files.createTempFile("test-upload", ".txt")
        Files.writeString(tmpFile, "file only content")

        // Attach file
        val fileChooser = page.waitForFileChooser {
            page.locator(".btn-attach").click()
        }
        fileChooser.setFiles(tmpFile)
        page.locator(".file-chip").waitFor()

        // Submit with empty text (file-only prompt)
        page.locator(".btn-send").click()
        page.locator(".btn-send").waitFor()
        page.waitForTimeout(500.0)

        // Verify the agent received the file inlined into the text block
        assertTrue(fakeSession.promptHistory.isNotEmpty(), "Should have received a prompt")
        val contentBlocks = fakeSession.promptHistory.last()
        val textBlocks = contentBlocks.filterIsInstance<ContentBlock.Text>()
        assertTrue(textBlocks.isNotEmpty(), "File-only prompt should contain a Text content block")
        assertTrue(textBlocks.last().text.contains("file only content"), "Text block should contain inlined file content")

        Files.deleteIfExists(tmpFile)
    }

    @Test
    fun userMessageShowsAttachedFileNames() {
        fakeSession.enqueueTextResponse("Got it")

        navigateAndWait()

        val tmpFile = Files.createTempFile("report", ".csv")
        Files.writeString(tmpFile, "name,age\nAlice,30")

        val fileChooser = page.waitForFileChooser {
            page.locator(".btn-attach").click()
        }
        fileChooser.setFiles(tmpFile)
        page.locator(".file-chip").waitFor()

        submitAndWait("analyze this data")

        // The user message in the chat should show the attached filename
        val userMsg = page.locator(".msg-user").last()
        val userMsgText = userMsg.textContent() ?: ""
        assertTrue(
            userMsgText.contains("report") && userMsgText.contains(".csv"),
            "User message should show attached filename, got: '$userMsgText'"
        )

        Files.deleteIfExists(tmpFile)
    }

    @Test
    fun imageFileUploadSendsAsImageContentBlock() {
        fakeSession.enqueueTextResponse("Nice image")

        navigateAndWait()

        // Create a small valid PNG (1x1 pixel, red)
        val tmpFile = Files.createTempFile("test-image", ".png")
        val pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="
        )
        Files.write(tmpFile, pngBytes)

        // Attach image file
        val fileChooser = page.waitForFileChooser {
            page.locator(".btn-attach").click()
        }
        fileChooser.setFiles(tmpFile)
        page.locator(".file-chip").waitFor()

        // Submit
        submitAndWait("what is this image?")

        // Verify the agent received an Image content block (not Resource)
        assertTrue(fakeSession.promptHistory.isNotEmpty(), "Should have received a prompt")
        val contentBlocks = fakeSession.promptHistory.last()
        val imageBlocks = contentBlocks.filterIsInstance<ContentBlock.Image>()
        assertTrue(imageBlocks.isNotEmpty(), "Image file should be sent as ContentBlock.Image, not Resource")
        assertEquals("image/png", imageBlocks[0].mimeType, "Image MIME type should be image/png")

        Files.deleteIfExists(tmpFile)
    }
}
