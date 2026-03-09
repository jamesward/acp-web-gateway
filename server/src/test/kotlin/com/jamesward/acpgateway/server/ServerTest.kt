package com.jamesward.acpgateway.server

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.WsMessage
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun testApp(
        mode: GatewayMode = GatewayMode.LOCAL,
        block: suspend ApplicationTestBuilder.(sessionId: UUID, fakeSession: FakeClientSession) -> Unit,
    ) = testApplication {
        val command = ProcessCommand("echo", listOf("test"))
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))
        val fakeClientSession = FakeClientSession()
        val session = GatewaySession(
            id = UUID.randomUUID(),
            clientSession = fakeClientSession,
            clientOps = GatewayClientOperations(),
            cwd = System.getProperty("user.dir"),
        )
        session.ready = true
        manager.sessions[session.id] = session
        application {
            module(manager, "test-agent", mode)
        }
        block(session.id, fakeClientSession)
    }

    @Test
    fun healthEndpoint() = testApp { _, _ ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun rootServesChatInLocalMode() = testApp(GatewayMode.LOCAL) { sessionId, _ ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains(sessionId.toString()), "Local mode should not expose session UUID")
    }

    @Test
    fun rootShowsLandingInProxyMode() = testApp(GatewayMode.PROXY) { _, _ ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains("New Session"), "Proxy mode should not offer session creation")
    }

    @Test
    fun sessionPageReturnsHtml() = testApp(GatewayMode.PROXY) { sessionId, _ ->
        val response = client.get("/s/$sessionId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(body.contains("agent-info"))
        assertTrue(body.contains("prompt-form"))
        assertTrue(body.contains("permission-dialog"))
        assertTrue(body.contains(sessionId.toString()))
    }

    @Test
    fun sessionPageIncludesWasmScript() = testApp(GatewayMode.PROXY) { sessionId, _ ->
        val response = client.get("/s/$sessionId")
        val body = response.bodyAsText()
        assertTrue(body.contains("""src="/static/web.js""""))
    }

    @Test
    fun sessionPageUsesTailwindWebjar() = testApp(GatewayMode.PROXY) { sessionId, _ ->
        val response = client.get("/s/$sessionId")
        val body = response.bodyAsText()
        assertTrue(body.contains("/webjars/tailwindcss__browser/dist/index.global.js"))
    }

    @Test
    fun sessionPageHasTailwindStyleTag() = testApp(GatewayMode.PROXY) { sessionId, _ ->
        val response = client.get("/s/$sessionId")
        val body = response.bodyAsText()
        assertTrue(body.contains("""type="text/tailwindcss""""), "Should have text/tailwindcss style tag")
    }

    @Test
    fun tailwindWebjarServed() = testApp { _, _ ->
        val response = client.get("/webjars/tailwindcss__browser/dist/index.global.js")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().isNotEmpty())
    }

    @Test
    fun localModeWebSocketSendsConnectedMessage() = testApp(GatewayMode.LOCAL) { _, _ ->
        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/ws") {
            val frame = incoming.receive()
            assertIs<Frame.Text>(frame)
            val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
            assertIs<WsMessage.Connected>(msg)
        }
    }

    @Test
    fun proxyModeWebSocketSendsConnectedMessage() = testApp(GatewayMode.PROXY) { sessionId, _ ->
        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/s/$sessionId/ws") {
            val frame = incoming.receive()
            assertIs<Frame.Text>(frame)
            val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
            assertIs<WsMessage.Connected>(msg)
        }
    }

    @Test
    fun unknownSessionReturns404() = testApp(GatewayMode.PROXY) { _, _ ->
        val response = client.get("/s/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- File attachment and screenshot tests ---

    private suspend fun ApplicationTestBuilder.sendPromptAndGetContentBlocks(
        prompt: WsMessage.Prompt,
        fakeSession: FakeClientSession,
    ): List<ContentBlock> {
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            // Receive Connected message
            incoming.receive()
            // Send the prompt
            val encoded = json.encodeToString(WsMessage.serializer(), prompt)
            send(Frame.Text(encoded))
            // Wait for TurnComplete
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                    if (msg is WsMessage.TurnComplete) break
                }
            }
        }
        assertTrue(fakeSession.promptHistory.isNotEmpty(), "Agent should have received a prompt")
        return fakeSession.promptHistory.last()
    }

    @Test
    fun promptWithImageFileSendsContentBlockImage() = testApp { _, fakeSession ->
        val prompt = WsMessage.Prompt(
            text = "what is this?",
            files = listOf(FileAttachment("photo.png", "image/png", "iVBORw0KGgo=")),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession)
        // Should have: Image (from file) + Text
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(1, images.size, "Should have 1 image content block")
        assertEquals("iVBORw0KGgo=", images[0].data)
        assertEquals("image/png", images[0].mimeType)
        assertEquals(1, texts.size)
        assertEquals("what is this?", texts[0].text)
    }

    @Test
    fun promptWithScreenshotSendsContentBlockImage() = testApp { _, fakeSession ->
        val prompt = WsMessage.Prompt(
            text = "describe this page",
            screenshot = "c2NyZWVuc2hvdA==",
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession)
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        assertEquals(1, images.size, "Should have 1 image content block for screenshot")
        assertEquals("c2NyZWVuc2hvdA==", images[0].data)
        assertEquals("image/png", images[0].mimeType)
    }

    @Test
    fun promptWithNonImageFileSendsContentBlockResource() = testApp { _, fakeSession ->
        val prompt = WsMessage.Prompt(
            text = "analyze this",
            files = listOf(FileAttachment("data.csv", "text/csv", "bmFtZSxhZ2U=")),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession)
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(1, resources.size, "Should have 1 resource content block")
        val blob = assertIs<EmbeddedResourceResource.BlobResourceContents>(resources[0].resource)
        assertEquals("bmFtZSxhZ2U=", blob.blob)
        assertEquals("text/csv", blob.mimeType)
        assertEquals("file:///data.csv", blob.uri)
    }

    @Test
    fun promptWithScreenshotAndFilesSendsAllContentBlocks() = testApp { _, fakeSession ->
        val prompt = WsMessage.Prompt(
            text = "compare these",
            screenshot = "c2NyZWVuc2hvdA==",
            files = listOf(
                FileAttachment("chart.jpg", "image/jpeg", "Y2hhcnQ="),
                FileAttachment("report.pdf", "application/pdf", "cmVwb3J0"),
            ),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession)
        // Should have: screenshot Image + file Image + file Resource + Text = 4 blocks
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(2, images.size, "Should have 2 image blocks (screenshot + jpg)")
        assertEquals(1, resources.size, "Should have 1 resource block (pdf)")
        assertEquals(1, texts.size)
        // Verify screenshot comes first (it's added before files)
        assertEquals("c2NyZWVuc2hvdA==", images[0].data)
        assertEquals("image/png", images[0].mimeType)
        // Verify jpg file
        assertEquals("Y2hhcnQ=", images[1].data)
        assertEquals("image/jpeg", images[1].mimeType)
        // Verify pdf resource
        val blob = assertIs<EmbeddedResourceResource.BlobResourceContents>(resources[0].resource)
        assertEquals("cmVwb3J0", blob.blob)
        assertEquals("application/pdf", blob.mimeType)
    }

    @Test
    fun promptWithNoAttachmentsSendsTextOnly() = testApp { _, fakeSession ->
        val prompt = WsMessage.Prompt(text = "hello")
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession)
        assertEquals(1, blocks.size, "Should have only 1 content block")
        val text = assertIs<ContentBlock.Text>(blocks[0])
        assertEquals("hello", text.text)
    }

}
