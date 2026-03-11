package com.jamesward.acpgateway.server
import com.jamesward.acpgateway.shared.*

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.WsMessage
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerTest {

    private fun testApp(
        mode: GatewayMode = GatewayMode.LOCAL,
        block: suspend ApplicationTestBuilder.(sessionId: UUID, fakeSession: FakeClientSession, session: GatewaySession, manager: AgentProcessManager) -> Unit,
    ) = testApplication {
        val command = ProcessCommand("echo", listOf("test"))
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"
        val fakeClientSession = FakeClientSession()
        val testScope = CoroutineScope(Dispatchers.Default)
        val testStore = InMemorySessionStore()
        val session = GatewaySession(
            id = UUID.randomUUID(),
            clientSession = fakeClientSession,
            clientOps = GatewayClientOperations(),
            cwd = System.getProperty("user.dir"),
            scope = testScope,
            store = testStore,
        )
        session.ready = true
        session.startEventForwarding()
        manager.sessions[session.id] = session
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), mode)
        holder.manager = manager
        holder.currentAgent = RegistryAgent(id = "test-agent", name = "test-agent", version = "1.0.0")
        application {
            module(holder, mode)
        }
        block(session.id, fakeClientSession, session, manager)
    }

    @Test
    fun healthEndpoint() = testApp { _, _, _, _ ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun rootServesChatInLocalMode() = testApp(GatewayMode.LOCAL) { sessionId, _, _, _ ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains(sessionId.toString()), "Local mode should not expose session UUID")
    }

    @Test
    fun rootShowsLandingInProxyMode() = testApp(GatewayMode.PROXY) { _, _, _, _ ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains("New Session"), "Proxy mode should not offer session creation")
    }

    @Test
    fun sessionPageReturnsHtml() = testApp(GatewayMode.PROXY) { sessionId, _, _, _ ->
        val response = client.get("/s/$sessionId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(body.contains("""id="root""""))
        assertTrue(body.contains(sessionId.toString()))
    }

    @Test
    fun sessionPageIncludesWasmScript() = testApp(GatewayMode.PROXY) { sessionId, _, _, _ ->
        val response = client.get("/s/$sessionId")
        val body = response.bodyAsText()
        assertTrue(body.contains("""src="/static/web.js""""))
    }


    @Test
    fun localModeChannelSendsConnectedMessage() = testApp(GatewayMode.LOCAL) { _, _, session, manager ->
        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val handlerJob = CoroutineScope(Dispatchers.Default).launch {
            handleChatChannels(input, output, session, manager)
        }

        val msg = output.receive()
        assertIs<WsMessage.Connected>(msg)

        input.close()
        handlerJob.cancel()
    }

    @Test
    fun unknownSessionReturns404() = testApp(GatewayMode.PROXY) { _, _, _, _ ->
        val response = client.get("/s/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- File attachment and screenshot tests ---

    private suspend fun sendPromptAndGetContentBlocks(
        prompt: WsMessage.Prompt,
        fakeSession: FakeClientSession,
        session: GatewaySession,
        manager: AgentProcessManager,
    ): List<ContentBlock> {
        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val handlerJob = CoroutineScope(Dispatchers.Default).launch {
            handleChatChannels(input, output, session, manager)
        }

        output.receive() // Connected

        input.send(prompt)

        // Wait for TurnComplete
        for (msg in output) {
            if (msg is WsMessage.TurnComplete) break
        }

        input.close()
        handlerJob.cancel()

        assertTrue(fakeSession.promptHistory.isNotEmpty(), "Agent should have received a prompt")
        return fakeSession.promptHistory.last()
    }

    @Test
    fun promptWithImageFileSendsContentBlockImage() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "what is this?",
            files = listOf(FileAttachment("photo.png", "image/png", "iVBORw0KGgo=")),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
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
    fun promptWithScreenshotSendsContentBlockImage() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "describe this page",
            screenshot = "c2NyZWVuc2hvdA==",
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        assertEquals(1, images.size, "Should have 1 image content block for screenshot")
        assertEquals("c2NyZWVuc2hvdA==", images[0].data)
        assertEquals("image/png", images[0].mimeType)
    }

    @Test
    fun promptWithNonImageFileSendsContentBlockResource() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "analyze this",
            files = listOf(FileAttachment("data.csv", "text/csv", "bmFtZSxhZ2U=")),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(1, resources.size, "Should have 1 resource content block")
        val blob = assertIs<EmbeddedResourceResource.BlobResourceContents>(resources[0].resource)
        assertEquals("bmFtZSxhZ2U=", blob.blob)
        assertEquals("text/csv", blob.mimeType)
        assertEquals("file:///data.csv", blob.uri)
    }

    @Test
    fun promptWithScreenshotAndFilesSendsAllContentBlocks() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "compare these",
            screenshot = "c2NyZWVuc2hvdA==",
            files = listOf(
                FileAttachment("chart.jpg", "image/jpeg", "Y2hhcnQ="),
                FileAttachment("report.pdf", "application/pdf", "cmVwb3J0"),
            ),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
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
    fun promptWithNoAttachmentsSendsTextOnly() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(text = "hello")
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        assertEquals(1, blocks.size, "Should have only 1 content block")
        val text = assertIs<ContentBlock.Text>(blocks[0])
        assertEquals("hello", text.text)
    }

    @Test
    fun noAgentSelectedSendsConnectedAndWaits() = testApplication {
        // Set up holder with no agent (manager = null)
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), GatewayMode.LOCAL)

        application {
            module(holder, GatewayMode.LOCAL)
        }

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val serviceImpl = ChatServiceImpl(holder, GatewayMode.LOCAL, false, null, emptyList(), null)

        val job = CoroutineScope(Dispatchers.Default).launch {
            serviceImpl.chat(input, output)
        }

        withTimeout(5000) {
            // Should get a Connected message indicating no agent
            val msg = output.receive()
            assertIs<WsMessage.Connected>(msg)
            assertEquals("No agent selected", msg.agentName)

            // Connection should stay open — closing input should end cleanly
            input.close()
        }

        job.join()
    }

}
