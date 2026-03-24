package com.jamesward.acpgateway.server

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.jamesward.acpgateway.shared.*
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ServerTest {

    /** Helper for tests that need a dev-mode (local) server with a fake agent. */
    private fun testDevApp(
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
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"))
        holder.manager = manager
        holder.currentAgent = RegistryAgent(id = "test-agent", name = "test-agent", version = "1.0.0")
        application {
            devModule(holder)
        }
        block(session.id, fakeClientSession, session, manager)
    }

    /** Helper for tests that need a proxy-mode server. */
    private fun testProxyApp(
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module(emptyList())
        }
        block()
    }

    @Test
    fun healthEndpoint() = testDevApp { _, _, _, _ ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun rootServesChatInDevMode() = testDevApp { sessionId, _, _, _ ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains(sessionId.toString()), "Dev mode should not expose session UUID")
    }

    @Test
    fun rootShowsLandingInProxyMode() = testProxyApp {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(!body.contains("New Session"), "Proxy mode should not offer session creation")
    }

    @Test
    fun sessionPageReturnsHtml() = testProxyApp {
        // In proxy-only mode, session page renders even without relay session
        val sessionId = UUID.randomUUID()
        val response = client.get("/s/$sessionId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ACP Gateway"))
        assertTrue(body.contains("""id="root""""))
    }

    @Test
    fun sessionPageIncludesWasmScript() = testProxyApp {
        val sessionId = UUID.randomUUID()
        val response = client.get("/s/$sessionId")
        val body = response.bodyAsText()
        assertTrue(body.contains("""src="/static/web.js""""))
    }


    @Test
    fun devModeChannelSendsConnectedMessage() = testDevApp { _, _, session, manager ->
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
    fun promptWithImageFileSendsContentBlockImage() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithScreenshotSendsContentBlockImage() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithTextFileInlinesContentIntoTextBlock() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithTxtFileInlinesContent() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithBinaryNonImageFileSendsContentBlockResource() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithScreenshotAndFilesSendsAllContentBlocks() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithMixedTextAndBinaryFiles() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithTextExtensionAndOctetStreamMimeTypeInlinesAsText() = testDevApp { _, fakeSession, session, manager ->
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
    fun promptWithNoAttachmentsSendsTextOnly() = testDevApp { _, fakeSession, session, manager ->
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
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        val connectedMsg = WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), connectedMsg))

        val registry = listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0", icon = "https://example.com/icon.png"))

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        val msg = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(msg)
        assertEquals("Test Agent", msg.agentName)

        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals("test-agent", agents.currentAgentId)
        assertEquals("https://example.com/icon.png", agents.agents.first().icon)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelayForwardsInputToBackend() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

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
            @Deprecated("", level = DeprecationLevel.ERROR)
            override fun terminate() {}
        }
        relay.backendWs = fakeBackendWs

        val connectedMsg = WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), connectedMsg))

        val impl = ChatServiceImpl(
            emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        withTimeout(5000) { output.receive() }

        input.send(WsMessage.Prompt("hello from browser"))

        input.close()
        job.join()

        assertTrue(sentFrames.isNotEmpty(), "Should forward messages to relay backend")
        val forwarded = testJson.decodeFromString(WsMessage.serializer(), sentFrames.first())
        assertIs<WsMessage.Prompt>(forwarded)
        assertEquals("hello from browser", forwarded.text)
    }

    @Test
    fun proxyModeRelaySetsSwitchInProgressOnChangeAgent() = runTest {
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
            @Deprecated("", level = DeprecationLevel.ERROR)
            override fun terminate() {}
        }
        relay.backendWs = fakeBackendWs

        val registry = listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0"))

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        withTimeout(5000) { output.receive() }

        assertFalse(relay.switchInProgress, "switchInProgress should start false")

        input.send(WsMessage.ChangeAgent("test-agent"))

        input.close()
        job.join()

        assertTrue(relay.switchInProgress, "switchInProgress should be set after ChangeAgent")

        assertTrue(sentFrames.isNotEmpty(), "ChangeAgent should be forwarded to backend")
        val forwarded = testJson.decodeFromString(WsMessage.serializer(), sentFrames.last())
        assertIs<WsMessage.ChangeAgent>(forwarded)
        assertEquals("test-agent", forwarded.agentId)
    }

    @Test
    fun proxyModeRelaySupplementsLiveConnectedWithAvailableAgents() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        val registry = listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0", icon = "https://example.com/icon.png"))

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        withTimeout(5000) { output.receive() } // Connected
        withTimeout(5000) { output.receive() } // AvailableAgents
        withTimeout(5000) { output.receive() } // AvailableCommands

        relay.rpcChannels.first().trySend(
            WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        )

        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)

        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals("test-agent", agents.currentAgentId)
        assertEquals("https://example.com/icon.png", agents.agents.first().icon)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelaySecondClientPreservesAvailableCommands() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"

        // Simulate CLI having sent Connected + AvailableCommands with real commands
        val connectedMsg = WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)
        val commandsMsg = WsMessage.AvailableCommands(listOf(
            CommandInfo("plan", "Create a plan"),
            CommandInfo("help", "Show help"),
        ))
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), connectedMsg))
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(), commandsMsg))

        val registry = listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0"))

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // Collect all initial messages until we stop receiving
        val msgs = mutableListOf<WsMessage>()
        while (true) {
            val msg = withTimeoutOrNull(500) { output.receive() } ?: break
            msgs.add(msg)
        }

        // Find all AvailableCommands messages
        val commandsMsgs = msgs.filterIsInstance<WsMessage.AvailableCommands>()
        assertTrue(commandsMsgs.isNotEmpty(), "Should have at least one AvailableCommands")

        // The LAST AvailableCommands seen by the client determines what's shown
        val lastCommands = commandsMsgs.last()
        assertEquals(2, lastCommands.commands.size,
            "Second client should see the CLI's commands, not an empty list. Got: ${commandsMsgs.map { it.commands.size }}")
        assertEquals("plan", lastCommands.commands[0].name)
        assertEquals("help", lastCommands.commands[1].name)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelayWithEmptyCacheSendsAgentSelector() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)

        val registry = listOf(
            RegistryAgent(id = "agent-a", name = "Agent A", version = "1.0"),
            RegistryAgent(id = "agent-b", name = "Agent B", version = "2.0"),
        )

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)
        assertEquals("No agent selected", connected.agentName)

        val agents = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableAgents>(agents)
        assertEquals(2, agents.agents.size)

        val commands = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AvailableCommands>(commands)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelaySecondClientSeesAgentWorkingDuringActiveTurn() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"
        // Simulate a turn in progress: Connected was cached, then streaming content started
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(),
            WsMessage.Connected("Test Agent", "1.0.0", agentWorking = false)))
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(),
            WsMessage.AgentText("1", "hello ")))
        relay.turnActive = true

        val impl = ChatServiceImpl(
            emptyList(), sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)
        assertTrue(connected.agentWorking, "Second client should see agentWorking=true during active turn")

        val text = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AgentText>(text)

        input.close()
        job.join()
    }

    @Test
    fun proxyModeRelaySynthesizedConnectedReflectsTurnActive() = runTest {
        val sessionId = UUID.randomUUID()
        val relay = RelaySession(sessionId)
        relay.agentId = "test-agent"
        // No Connected in cache, but turn is active
        relay.messageCache.add(testJson.encodeToString(WsMessage.serializer(),
            WsMessage.AgentText("1", "streaming ")))
        relay.turnActive = true

        val registry = listOf(RegistryAgent(id = "test-agent", name = "Test Agent", version = "1.0.0"))

        val impl = ChatServiceImpl(
            registry, sessionId,
            relayLookup = { if (it == sessionId) relay else null },
        )

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val job = launch { impl.chat(input, output) }

        // First message should be the cached AgentText
        val text = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.AgentText>(text)

        // Then the synthesized Connected (no Connected was in cache)
        val connected = withTimeout(5000) { output.receive() }
        assertIs<WsMessage.Connected>(connected)
        assertTrue(connected.agentWorking, "Synthesized Connected should reflect turnActive=true")

        input.close()
        job.join()
    }

    @Test
    fun noAgentSelectedSendsConnectedAndWaits() = testApplication {
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"))

        application {
            devModule(holder)
        }

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val serviceImpl = DevChatServiceImpl(holder, false, null, emptyList())

        val job = CoroutineScope(Dispatchers.Default).launch {
            serviceImpl.chat(input, output)
        }

        withTimeout(5000) {
            val msg = output.receive()
            assertIs<WsMessage.Connected>(msg)
            assertEquals("No agent selected", msg.agentName)

            input.close()
        }

        job.join()
    }

    @Test
    fun idleShutdownMonitorTriggersAfterGracePeriod() = runTest {
        val relaySessions = java.util.concurrent.ConcurrentHashMap<UUID, RelaySession>()
        var shutdownCalled = false
        val monitor = IdleShutdownMonitor(
            relaySessions,
            gracePeriod = 100.milliseconds,
            startupGracePeriod = 100.milliseconds,
            onShutdown = { shutdownCalled = true },
        )

        // No sessions at startup — trigger startup grace timer
        monitor.onSessionCountChanged()
        Thread.sleep(200)
        assertTrue(shutdownCalled, "Should have shut down after startup grace period with no sessions")
        monitor.close()
    }

    @Test
    fun idleShutdownMonitorCancelledByNewSession() = runTest {
        val relaySessions = java.util.concurrent.ConcurrentHashMap<UUID, RelaySession>()
        var shutdownCalled = false
        val monitor = IdleShutdownMonitor(
            relaySessions,
            gracePeriod = 100.milliseconds,
            startupGracePeriod = 200.milliseconds,
            onShutdown = { shutdownCalled = true },
        )

        // Start with no sessions
        monitor.onSessionCountChanged()

        // Add a session before grace period expires
        Thread.sleep(50)
        val id = UUID.randomUUID()
        relaySessions[id] = RelaySession(id)
        monitor.onSessionCountChanged()

        // Wait past the original grace period
        Thread.sleep(250)
        assertFalse(shutdownCalled, "Should NOT have shut down — a session was added")
        monitor.close()
    }

    @Test
    fun idleShutdownMonitorTriggersAfterLastSessionRemoved() = runTest {
        val relaySessions = java.util.concurrent.ConcurrentHashMap<UUID, RelaySession>()
        var shutdownCalled = false
        val monitor = IdleShutdownMonitor(
            relaySessions,
            gracePeriod = 100.milliseconds,
            startupGracePeriod = 5.seconds,
            onShutdown = { shutdownCalled = true },
        )

        // Start with a session already present
        val id = UUID.randomUUID()
        relaySessions[id] = RelaySession(id)
        monitor.onSessionCountChanged()

        // Remove the session
        relaySessions.remove(id)
        monitor.onSessionCountChanged()

        Thread.sleep(200)
        assertTrue(shutdownCalled, "Should have shut down after last session was removed")
        monitor.close()
    }

    @Test
    fun parseServerConfigShutdownOnIdle() {
        val config = parseServerConfig(arrayOf("--shutdown-on-idle", "--debug"))
        assertTrue(config.shutdownOnIdle)
        assertTrue(config.debug)

        val config2 = parseServerConfig(arrayOf("--debug"))
        assertFalse(config2.shutdownOnIdle)
    }

}
