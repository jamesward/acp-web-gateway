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
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun promptWithTextFileInlinesContentIntoTextBlock() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "analyze this",
            files = listOf(FileAttachment("data.csv", "text/csv", "bmFtZSxhZ2U=")), // "name,age"
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        // Text files should be inlined into the Text block, not sent as Resource
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(0, resources.size, "Text files should not produce Resource blocks")
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(1, texts.size)
        assertTrue(texts[0].text.contains("[File: data.csv]"), "Should contain file header")
        assertTrue(texts[0].text.contains("name,age"), "Should contain decoded file content")
        assertTrue(texts[0].text.contains("analyze this"), "Should contain original prompt")
    }

    @Test
    fun promptWithTxtFileInlinesContent() = testApp { _, fakeSession, session, manager ->
        // "Hello world" in base64
        val base64Content = java.util.Base64.getEncoder().encodeToString("Hello world".toByteArray())
        val prompt = WsMessage.Prompt(
            text = "read this file",
            files = listOf(FileAttachment("notes.txt", "text/plain", base64Content)),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(0, resources.size, "text/plain files should not produce Resource blocks")
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(1, texts.size)
        assertTrue(texts[0].text.contains("[File: notes.txt]"))
        assertTrue(texts[0].text.contains("Hello world"))
        assertTrue(texts[0].text.contains("read this file"))
    }

    @Test
    fun promptWithBinaryNonImageFileSendsContentBlockResource() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(
            text = "analyze this",
            files = listOf(FileAttachment("report.pdf", "application/pdf", "cmVwb3J0")),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(1, resources.size, "Should have 1 resource content block")
        val blob = assertIs<EmbeddedResourceResource.BlobResourceContents>(resources[0].resource)
        assertEquals("cmVwb3J0", blob.blob)
        assertEquals("application/pdf", blob.mimeType)
        assertEquals("file:///report.pdf", blob.uri)
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
    fun promptWithMixedTextAndBinaryFiles() = testApp { _, fakeSession, session, manager ->
        val csvBase64 = java.util.Base64.getEncoder().encodeToString("name,age".toByteArray())
        val prompt = WsMessage.Prompt(
            text = "analyze all",
            files = listOf(
                FileAttachment("data.csv", "text/csv", csvBase64),
                FileAttachment("photo.png", "image/png", "iVBORw0KGgo="),
                FileAttachment("doc.pdf", "application/pdf", "cmVwb3J0"),
            ),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(1, images.size, "Should have 1 image block (png)")
        assertEquals(1, resources.size, "Should have 1 resource block (pdf)")
        assertEquals(1, texts.size, "Should have 1 text block with inlined csv")
        assertTrue(texts[0].text.contains("[File: data.csv]"))
        assertTrue(texts[0].text.contains("name,age"))
        assertTrue(texts[0].text.contains("analyze all"))
    }

    @Test
    fun promptWithTextExtensionAndOctetStreamMimeTypeInlinesAsText() = testApp { _, fakeSession, session, manager ->
        // Browsers often report application/octet-stream for .md, .json, .yaml etc.
        val mdContent = java.util.Base64.getEncoder().encodeToString("# Hello\nWorld".toByteArray())
        val prompt = WsMessage.Prompt(
            text = "summarize the contents",
            files = listOf(FileAttachment("effect-intro.md", "application/octet-stream", mdContent)),
        )
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        val resources = blocks.filterIsInstance<ContentBlock.Resource>()
        assertEquals(0, resources.size, "Text-like files should not produce Resource blocks even with octet-stream MIME")
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        assertEquals(1, texts.size)
        assertTrue(texts[0].text.contains("[File: effect-intro.md]"))
        assertTrue(texts[0].text.contains("# Hello\nWorld"))
        assertTrue(texts[0].text.contains("summarize the contents"))
    }

    @Test
    fun promptWithNoAttachmentsSendsTextOnly() = testApp { _, fakeSession, session, manager ->
        val prompt = WsMessage.Prompt(text = "hello")
        val blocks = sendPromptAndGetContentBlocks(prompt, fakeSession, session, manager)
        assertEquals(1, blocks.size, "Should have only 1 content block")
        val text = assertIs<ContentBlock.Text>(blocks[0])
        assertEquals("hello", text.text)
    }

    // --- Proxy mode relay bridging tests ---

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun proxyModeWithRelaySessionBridgesCachedMessages() = runTest {
        // Scenario: CLI backend connected and sent messages (cached in relay).
        // Browser connects via RPC. ChatServiceImpl should replay cached messages
        // AND supplement with AvailableAgents (which the CLI doesn't send).
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        // Simulate CLI having sent Connected (CLI doesn't send AvailableAgents)
        val connectedMsg = WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), connectedMsg))

        val holder = AgentHolder(
            listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0", icon = "https://example.com/icon.png")),
            System.getProperty("user.dir"),
            GatewayMode.PROXY,
        )

        val impl = ChatServiceImpl(
            holder, GatewayMode.PROXY, false, null, emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Should get cached Connected
        val msg = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(msg)
        assertEquals("Test Agent", msg.agentName)

        // Should get supplemented AvailableAgents with currentAgentId set
        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals("test-agent", agents.currentAgentId)
        assertEquals("https://example.com/icon.png", agents.agents.first().icon)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelayForwardsInputToBackend() = runTest {
        // Scenario: Browser sends a message via RPC. ChatServiceImpl should forward it
        // to the relay's backend WebSocket (the CLI).
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        // Set up a fake backend WS that captures sent frames
        val sentFrames = mutableListOf<String>()
        val fakeBackendWs = object : WebSocketSession {
            override val incoming get() = throw NotImplementedError()
            override val outgoing get() = throw NotImplementedError()
            override val extensions get() = emptyList<WebSocketExtension<*>>()
            override val coroutineContext get() = this@runTest.coroutineContext
            override var masking: Boolean = false
            override var maxFrameSize: Long = Long.MAX_VALUE
            override suspend fun send(frame: Frame) {
                if (frame is Frame.Text) sentFrames.add(frame.readText())
            }
            override suspend fun flush() {}
            override fun terminate() {}
        }
        relay.backendWs = fakeBackendWs

        // Pre-populate cache so chat() doesn't exit immediately
        val connectedMsg = WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), connectedMsg))

        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"), GatewayMode.PROXY)

        val impl = ChatServiceImpl(
            holder, GatewayMode.PROXY, false, null, emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Consume the cached Connected
        withTimeout(5000) { output.receive() }

        // Browser sends a prompt
        input.send(WsMessage.Prompt("hello from browser"))

        // Close input to end the bridging loop
        input.close()
        job.join()

        // The prompt should have been forwarded to the backend WS as JSON
        assertTrue(sentFrames.isNotEmpty(), "Should forward messages to relay backend")
        val forwarded = testJson.decodeFromString(WsMessage.serializer(), sentFrames.first())
        assertIs<WsMessage.Prompt>(forwarded)
        assertEquals("hello from browser", forwarded.text)
    }

    @Test
    fun proxyModeRelaySetsSwitchInProgressOnChangeAgent() = runTest {
        // When the browser sends ChangeAgent via the relay bridge,
        // switchInProgress should be set so the relay survives CLI disconnect/reconnect.
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)

        val sentFrames = mutableListOf<String>()
        val fakeBackendWs = object : WebSocketSession {
            override val incoming get() = throw NotImplementedError()
            override val outgoing get() = throw NotImplementedError()
            override val extensions get() = emptyList<WebSocketExtension<*>>()
            override val coroutineContext get() = this@runTest.coroutineContext
            override var masking: Boolean = false
            override var maxFrameSize: Long = Long.MAX_VALUE
            override suspend fun send(frame: Frame) {
                if (frame is Frame.Text) sentFrames.add(frame.readText())
            }
            override suspend fun flush() {}
            override fun terminate() {}
        }
        relay.backendWs = fakeBackendWs

        val holder = AgentHolder(
            listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0")),
            System.getProperty("user.dir"),
            GatewayMode.PROXY,
        )

        val impl = ChatServiceImpl(
            holder, GatewayMode.PROXY, false, null, emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Consume the synthesized Connected
        withTimeout(5000) { output.receive() }

        assertFalse(relay.switchInProgress, "switchInProgress should start false")

        // Browser selects an agent
        input.send(WsMessage.ChangeAgent("test-agent"))

        // Give time for message to be processed
        input.close()
        job.join()

        assertTrue(relay.switchInProgress, "switchInProgress should be set after ChangeAgent")

        // ChangeAgent should have been forwarded to CLI backend
        assertTrue(sentFrames.isNotEmpty(), "ChangeAgent should be forwarded to backend")
        val forwarded = testJson.decodeFromString(WsMessage.serializer(), sentFrames.last())
        assertIs<WsMessage.ChangeAgent>(forwarded)
        assertEquals("test-agent", forwarded.agentId)
    }

    @Test
    fun proxyModeRelaySupplementsLiveConnectedWithAvailableAgents() = runTest {
        // When the CLI sends a new Connected message (e.g., after agent switch),
        // the bridge should inject AvailableAgents so the browser gets agent icon info.
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        val holder = AgentHolder(
            listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0", icon = "https://example.com/icon.png")),
            System.getProperty("user.dir"),
            GatewayMode.PROXY,
        )

        val impl = ChatServiceImpl(
            holder, GatewayMode.PROXY, false, null, emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Consume the synthesized initial Connected + AvailableAgents + AvailableCommands
        withTimeout(5000) { output.receive() } // Connected
        withTimeout(5000) { output.receive() } // AvailableAgents
        withTimeout(5000) { output.receive() } // AvailableCommands

        // Simulate CLI sending a live Connected after agent switch
        relay.rpcChannels.first().trySend(
            WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        )

        // Should get the Connected
        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)

        // Should get supplemented AvailableAgents right after
        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals("test-agent", agents.currentAgentId)
        assertEquals("https://example.com/icon.png", agents.agents.first().icon)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelayWithEmptyCacheSendsAgentSelector() = runTest {
        // Scenario: CLI connected without --agent. No messages cached yet.
        // Browser connects via RPC. Should see agent selector, not a blank screen.
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        // relay.agentId is null — no agent selected yet
        // relay.messageCache is empty

        val holder = AgentHolder(
            listOf(
                RegistryAgent(id = "agent-a", name = "Agent A", version = "1.0"),
                RegistryAgent(id = "agent-b", name = "Agent B", version = "2.0"),
            ),
            System.getProperty("user.dir"),
            GatewayMode.PROXY,
        )

        val impl = ChatServiceImpl(
            holder, GatewayMode.PROXY, false, null, emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Should synthesize a Connected message
        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)
        assertEquals("No agent selected", connected.agentName)

        // Should send available agents
        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals(2, agents.agents.size)

        // Should send available commands
        val commands = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableCommands>(commands)

        input.close()
        job.join()
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
