package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.jamesward.acpgateway.shared.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests the rendering flow: given specific ACP events from the agent,
 * verify the sequence of HtmlUpdate messages sent to the browser.
 *
 * These tests verify the WebSocketHandler orchestration layer — correct
 * targets, swap modes, and HTML content for each event type.
 */
class RenderingFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun testApp(
        block: suspend ApplicationTestBuilder.(ControllableFakeClientSession) -> Unit,
    ) = testApplication {
        val command = ProcessCommand("echo", listOf("test"))
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))
        val fakeSession = ControllableFakeClientSession()
        val testScope = CoroutineScope(Dispatchers.Default)
        val testStore = InMemorySessionStore()
        val session = GatewaySession(
            id = java.util.UUID.randomUUID(),
            clientSession = fakeSession,
            clientOps = GatewayClientOperations(),
            cwd = System.getProperty("user.dir"),
            scope = testScope,
            store = testStore,
        )
        session.ready = true
        session.startEventForwarding()
        manager.sessions[session.id] = session
        application {
            module(manager, "test-agent", GatewayMode.LOCAL)
        }
        block(fakeSession)
    }

    private fun decodeWsMessage(frame: Frame): WsMessage {
        val text = (frame as Frame.Text).readText()
        return json.decodeFromString(WsMessage.serializer(), text)
    }

    private suspend fun DefaultClientWebSocketSession.collectUpdatesUntilTurnComplete(): List<WsMessage> {
        val messages = mutableListOf<WsMessage>()
        for (frame in incoming) {
            if (frame is Frame.Text) {
                val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                messages.add(msg)
                if (msg is WsMessage.TurnComplete) break
            }
        }
        return messages
    }

    // ---- Ordering: placeholders ensure thought → tools → message order ----

    @Test
    fun placeholdersSentInCorrectOrder() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                // Tool call arrives BEFORE thought — but placeholders ensure order
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Thinking..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val htmlUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()

            // After user message, 3 hidden placeholders should be sent in order
            val userMsgIdx = htmlUpdates.indexOfFirst { it.html.contains(Css.MSG_USER) }
            val placeholders = htmlUpdates.drop(userMsgIdx + 1)
                .takeWhile { it.swap == Swap.BeforeEnd && it.html.contains(Css.HIDDEN) }

            assertEquals(3, placeholders.size, "Should have 3 placeholders after user message")

            // Extract IDs from placeholder HTML
            val placeholderIds = placeholders.map {
                Regex("""id="([^"]+)"""").find(it.html)!!.groupValues[1]
            }

            // Order: thought → message → tools
            assertTrue(placeholderIds[0].startsWith("thought-"), "First placeholder should be thought")
            assertTrue(placeholderIds[1].startsWith("msg-"), "Second placeholder should be message")
            assertTrue(placeholderIds[2].startsWith("tools-"), "Third placeholder should be tools")

            // Verify thought morph targets the thought placeholder
            val thoughtUpdate = htmlUpdates.first { it.html.contains(Css.MSG_THOUGHT) }
            assertEquals(placeholderIds[0], thoughtUpdate.target)

            // Verify message morph targets the msg placeholder
            val msgUpdate = htmlUpdates.first { it.html.contains(Css.MSG_ASSISTANT) && !it.html.contains(Css.MSG_THOUGHT) }
            assertEquals(placeholderIds[1], msgUpdate.target)

            // Verify tool block morph targets the tools placeholder
            val toolUpdate = htmlUpdates.first { it.html.contains(Css.TOOL_BLOCK) }
            assertEquals(placeholderIds[2], toolUpdate.target)
        }
    }

    // ---- Simple text response flow ----

    @Test
    fun textResponseSendsCorrectHtmlUpdateSequence() = testApp { fakeSession ->
        fakeSession.enqueueTextResponse("Hello, world!")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            // Skip Connected message
            incoming.receive()

            // Send prompt
            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("hi"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()

            // 1. User message (BeforeEnd to messages container)
            val userUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .first { it.html.contains(Css.MSG_USER) }
            assertEquals(Css.MESSAGES, userUpdate.target)
            assertEquals(Swap.BeforeEnd, userUpdate.swap)
            assertTrue(userUpdate.html.contains("hi"))

            // 2. Assistant message chunk (Morph to msg-id)
            val assistantUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
            assertTrue(assistantUpdates.isNotEmpty(), "Should have assistant message updates")

            // The streaming update uses Morph
            val streamUpdate = assistantUpdates.first { it.swap == Swap.Morph }
            assertTrue(streamUpdate.target.startsWith("msg-"), "Target should be message ID")
            assertTrue(streamUpdate.html.contains("Hello, world!"))

            // 3. Final rendered markdown (Morph, replaces streaming with rendered)
            val finalUpdate = assistantUpdates.last()
            assertEquals(Swap.Morph, finalUpdate.swap)

            // 4. TurnComplete
            val turnComplete = messages.last()
            assertIs<WsMessage.TurnComplete>(turnComplete)
            assertEquals("end_turn", turnComplete.stopReason)
        }
    }

    // ---- Incremental markdown rendering ----

    @Test
    fun streamingChunksRenderMarkdownIncrementally() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Hello **bold"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("** world"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val assistantUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.swap == Swap.Morph && it.html.contains(Css.MSG_ASSISTANT) }

            // First chunk: "Hello **bold" — incomplete markdown, no <strong> yet
            val firstUpdate = assistantUpdates.first()
            assertFalse(firstUpdate.html.contains("<strong>"), "Incomplete bold should not render as <strong>")
            assertTrue(firstUpdate.html.contains("Hello"))

            // Second chunk: "Hello **bold** world" — complete markdown, should have <strong>
            val secondUpdate = assistantUpdates[1]
            assertTrue(secondUpdate.html.contains("<strong>bold</strong>"), "Complete bold should render as <strong>")
            assertTrue(secondUpdate.html.contains("world"))
        }
    }

    // ---- Tool call flow ----

    @Test
    fun toolCallSendsToolBlockUpdates() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                // Tool call starts
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                // Tool call completes
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file.txt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text("file contents"))),
                )))
                // Agent responds
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("read it"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val htmlUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()

            // Tool block updates use Morph and target the block ID
            val toolUpdates = htmlUpdates.filter { it.html.contains(Css.TOOL_BLOCK) }
            assertTrue(toolUpdates.size >= 2, "Should have at least 2 tool block updates (create + complete)")

            // All tool block updates use Morph
            toolUpdates.forEach { update ->
                assertEquals(Swap.Morph, update.swap)
                assertTrue(update.target.startsWith("tools-"), "Target should be tool block ID")
            }

            // First tool update shows running state
            val firstToolUpdate = toolUpdates.first()
            assertTrue(firstToolUpdate.html.contains("Read file.txt"))
            assertTrue(firstToolUpdate.html.contains(Css.TOOL_ICON_RUNNING))

            // Last tool update shows completed state
            val lastToolUpdate = toolUpdates.last()
            assertTrue(lastToolUpdate.html.contains(Css.TOOL_ICON_DONE))
            assertTrue(lastToolUpdate.html.contains("file contents"))
        }
    }

    // ---- Multiple tool calls accumulate in one block ----

    @Test
    fun multipleToolCallsAccumulateInOneBlock() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read A",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-2"),
                    title = "Read B",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read A",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-2"),
                    title = "Read B",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("read both"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val toolUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.TOOL_BLOCK) }

            // All tool updates should target the same block ID
            val blockIds = toolUpdates.map { it.target }.distinct()
            assertEquals(1, blockIds.size, "All tool updates should share one block ID")

            // Final update should show both tools
            val lastUpdate = toolUpdates.last()
            assertTrue(lastUpdate.html.contains("Read A"))
            assertTrue(lastUpdate.html.contains("Read B"))
            assertTrue(lastUpdate.html.contains("2 tool calls"))
        }
    }

    // ---- Thought messages ----

    @Test
    fun thoughtChunksSendThoughtHtmlUpdates() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Let me think..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Answer"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("think"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val htmlUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()

            // Thought update
            val thoughtUpdate = htmlUpdates.first { it.html.contains(Css.MSG_THOUGHT) }
            assertEquals(Swap.Morph, thoughtUpdate.swap)
            assertTrue(thoughtUpdate.target.startsWith("thought-"))
            assertTrue(thoughtUpdate.html.contains("Let me think..."))
        }
    }

    // ---- Error handling ----

    @Test
    fun errorDuringPromptSendsErrorHtmlUpdate() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                throw RuntimeException("Agent crashed")
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("crash"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val htmlUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()

            // Error message
            val errorUpdate = htmlUpdates.first { it.html.contains(Css.MSG_ERROR) }
            assertEquals(Css.MESSAGES, errorUpdate.target)
            assertEquals(Swap.BeforeEnd, errorUpdate.swap)
            assertTrue(errorUpdate.html.contains("Agent crashed"))

            // TurnComplete with error
            val turnComplete = messages.last()
            assertIs<WsMessage.TurnComplete>(turnComplete)
            assertEquals("error", turnComplete.stopReason)
        }
    }

    // ---- History replay ----

    @Test
    fun historyReplayedOnConnect() = testApp { fakeSession ->
        // First: send a prompt to build up history
        fakeSession.enqueueTextResponse("I'm fine, thanks!")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("how are you"))
            send(Frame.Text(prompt))
            collectUpdatesUntilTurnComplete()
        }

        // Second: reconnect and verify history is replayed
        wsClient.webSocket("/ws") {
            val connected = decodeWsMessage(incoming.receive())
            assertIs<WsMessage.Connected>(connected)

            // History entries: user message + assistant message
            val msg1 = decodeWsMessage(incoming.receive())
            assertIs<WsMessage.HtmlUpdate>(msg1)
            assertEquals(Css.MESSAGES, msg1.target)
            assertEquals(Swap.BeforeEnd, msg1.swap)
            assertTrue(msg1.html.contains(Css.MSG_USER), "First replay should be user message")
            assertTrue(msg1.html.contains("how are you"))

            val msg2 = decodeWsMessage(incoming.receive())
            assertIs<WsMessage.HtmlUpdate>(msg2)
            assertEquals(Css.MESSAGES, msg2.target)
            assertEquals(Swap.BeforeEnd, msg2.swap)
            assertTrue(msg2.html.contains(Css.MSG_ASSISTANT), "Second replay should be assistant message")
        }
    }

    // ---- User message HTML structure ----

    @Test
    fun userMessageHtmlInUpdateHasCorrectStructure() = testApp { fakeSession ->
        fakeSession.enqueueTextResponse("ok")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test message"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val userUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .first { it.html.contains(Css.MSG_USER) }

            // Verify the HTML has the expected fragment structure
            assertTrue(userUpdate.html.contains(Css.MSG_WRAP_USER))
            assertTrue(userUpdate.html.contains(Css.MSG_USER))
            assertTrue(userUpdate.html.contains("test message"))
        }
    }

    // ---- Assistant message morphing uses consistent ID ----

    @Test
    fun assistantMessageMorphUsesConsistentMsgId() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk1"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(" chunk2"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("stream"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val assistantUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.swap == Swap.Morph && it.html.contains(Css.MSG_ASSISTANT) }

            assertTrue(assistantUpdates.size >= 2, "Should have at least 2 morph updates (streaming + final)")

            // All morph updates target the same msg-id
            val targets = assistantUpdates.map { it.target }.distinct()
            assertEquals(1, targets.size, "All assistant morph updates should target the same msg-id")
            assertTrue(targets[0].startsWith("msg-"))

            // Accumulated text: "chunk1 chunk2"
            val lastStreaming = assistantUpdates.dropLast(1).last()
            assertTrue(lastStreaming.html.contains("chunk1 chunk2"))
        }
    }

    // ---- Tool call with failed status ----

    @Test
    fun failedToolCallShowsFailedState() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-fail"),
                    title = "Write protected.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-fail"),
                    title = "Write protected.txt",
                    status = ToolCallStatus.FAILED,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text("Permission denied"))),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Failed"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("write it"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val toolUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.TOOL_BLOCK) }

            val lastToolUpdate = toolUpdates.last()
            assertTrue(lastToolUpdate.html.contains(Css.TOOL_ICON_FAIL))
            assertTrue(lastToolUpdate.html.contains(Css.TOOL_TITLE_FAIL))
            assertTrue(lastToolUpdate.html.contains("1 failed"))
            assertTrue(lastToolUpdate.html.contains("Permission denied"))
        }
    }

    // ---- Many tool calls render all entries ----

    @Test
    fun manyToolCallsRenderAllEntriesInFinalUpdate() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                // Create 20 tool calls and complete them all
                for (i in 1..20) {
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
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("do all"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val toolUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.TOOL_BLOCK) }

            // The last tool block update should contain ALL 20 tool entries
            val lastUpdate = toolUpdates.last()
            assertTrue(lastUpdate.html.contains("20 tool calls"))
            assertTrue(lastUpdate.html.contains("20 done"))

            // Verify all 20 tool rows are present in the HTML
            for (i in 1..20) {
                assertTrue(lastUpdate.html.contains("Tool $i"), "Missing Tool $i in final update")
            }

            // Count tool-row divs
            val rowCount = Regex("""class="${Css.TOOL_ROW}"""").findAll(lastUpdate.html).count()
            assertEquals(20, rowCount, "Final update should have 20 tool rows")
        }
    }

    // ---- Markdown newlines preserved in assistant messages ----

    @Test
    fun markdownNewlinesRenderAsSeparateParagraphs() = testApp { fakeSession ->
        // Simulate text with double newlines (paragraph breaks)
        val text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph."
        fakeSession.enqueueTextResponse(text)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            // Each paragraph should be in its own <p> tag
            val pCount = Regex("<p>").findAll(finalUpdate.html).count()
            assertTrue(pCount >= 3, "Should have at least 3 <p> tags for 3 paragraphs, got $pCount")
            assertTrue(finalUpdate.html.contains("First paragraph."))
            assertTrue(finalUpdate.html.contains("Second paragraph."))
            assertTrue(finalUpdate.html.contains("Third paragraph."))
        }
    }

    @Test
    fun softBreaksPreservedWhenChunkedAtNewline() = testApp { fakeSession ->
        // Simulate chunks that split at newline boundaries (how real agents stream)
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("First sentence."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("\nSecond sentence."))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            // The newline between chunks must not be stripped
            assertFalse(finalUpdate.html.contains("sentence.Second"), "Newline between chunks should not be stripped")
        }
    }

    @Test
    fun paragraphBreaksPreservedInChunkedText() = testApp { fakeSession ->
        // Simulate paragraph breaks (\n\n) split across chunks
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("First paragraph.\n\n"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Second paragraph."))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            // Should render as two separate <p> tags
            val pCount = Regex("<p>").findAll(finalUpdate.html).count()
            assertTrue(pCount >= 2, "Paragraph break should produce at least 2 <p> tags, got $pCount")
            assertTrue(finalUpdate.html.contains("First paragraph."))
            assertTrue(finalUpdate.html.contains("Second paragraph."))
        }
    }

    @Test
    fun newlinesNotStrippedInWebSocketJsonSerialization() = testApp { fakeSession ->
        // Verify that HtmlUpdate with newlines in html field survives JSON round-trip
        val htmlWithNewlines = "<p>line1\nline2</p>"
        val msg = WsMessage.HtmlUpdate(target = "test", swap = Swap.Morph, html = htmlWithNewlines)
        val serialized = json.encodeToString(WsMessage.serializer(), msg)
        val deserialized = json.decodeFromString(WsMessage.serializer(), serialized) as WsMessage.HtmlUpdate
        assertTrue(deserialized.html.contains("\n"), "Newline must survive JSON serialization")
        assertFalse(deserialized.html.contains("line1line2"), "Newline must not be stripped in JSON round-trip")
    }

    @Test
    fun singleNewlinesNotJammedTogether() = testApp { fakeSession ->
        // Agent output with single newlines between sentences should not be concatenated
        fakeSession.enqueueTextResponse("First sentence.\nSecond sentence.\nThird sentence.")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            // Single newlines (soft breaks) should produce \n in HTML, rendered as spaces by browsers
            assertFalse(finalUpdate.html.contains("sentence.Second"), "Soft breaks should not jam sentences together")
        }
    }

    @Test
    fun markdownSoftBreaksPreserveSpaceBetweenWords() = testApp { fakeSession ->
        // Simulate text with single newlines (soft breaks in markdown)
        val text = "word1\nword2\nword3"
        fakeSession.enqueueTextResponse(text)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            // Soft breaks in CommonMark render as \n in HTML, which browsers display as spaces.
            // The HTML should have newline chars (soft breaks) between words, NOT concatenated.
            // In the rendered HTML, words must not be jammed together like "word1word2word3"
            assertFalse(finalUpdate.html.contains("word1word2"), "Soft breaks should not jam words together")
        }
    }

    @Test
    fun markdownNumberedListRendersAsOlLi() = testApp { fakeSession ->
        val text = "Steps:\n\n1. First step\n2. Second step\n3. Third step"
        fakeSession.enqueueTextResponse(text)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("test"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val finalUpdate = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.MSG_ASSISTANT) }
                .last()

            assertTrue(finalUpdate.html.contains("<ol>"), "Should render numbered list as <ol>")
            assertTrue(finalUpdate.html.contains("<li>"), "Should have <li> elements")
            assertTrue(finalUpdate.html.contains("First step"))
            assertTrue(finalUpdate.html.contains("Second step"))
            assertTrue(finalUpdate.html.contains("Third step"))
        }
    }

    // ---- Diff rendering in tool calls ----

    @Test
    fun diffToolCallRendersColoredDiff() = testApp { fakeSession ->
        val oldText = "line1\nline2\nline3"
        val newText = "line1\nmodified line2\nline3\nline4"

        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-edit"),
                    title = "Edit file.kt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-edit"),
                    title = "Edit file.kt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Diff(
                        path = "/src/file.kt",
                        oldText = oldText,
                        newText = newText,
                    )),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("edit it"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val toolUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.TOOL_BLOCK) }

            val lastToolUpdate = toolUpdates.last()

            // Should have colored diff lines
            assertTrue(lastToolUpdate.html.contains("diff-del"), "Should have deletion line styling")
            assertTrue(lastToolUpdate.html.contains("diff-add"), "Should have addition line styling")
            assertTrue(lastToolUpdate.html.contains("diff-hunk"), "Should have hunk header styling")

            // Should contain the actual diff content
            assertTrue(lastToolUpdate.html.contains("line2"), "Should show old line content")
            assertTrue(lastToolUpdate.html.contains("modified line2"), "Should show new line content")
            assertTrue(lastToolUpdate.html.contains("line4"), "Should show added line")
        }
    }

    @Test
    fun diffWithNoOldTextShowsAllAsAdditions() = testApp { fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-new"),
                    title = "Write new.kt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-new"),
                    title = "Write new.kt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Diff(
                        path = "/src/new.kt",
                        oldText = null,
                        newText = "package com.example\n\nfun main() {}",
                    )),
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("create it"))
            send(Frame.Text(prompt))

            val messages = collectUpdatesUntilTurnComplete()
            val toolUpdates = messages.filterIsInstance<WsMessage.HtmlUpdate>()
                .filter { it.html.contains(Css.TOOL_BLOCK) }

            val lastToolUpdate = toolUpdates.last()

            // New file: all lines should be additions
            assertTrue(lastToolUpdate.html.contains("diff-add"), "New file should have addition lines")
            assertFalse(lastToolUpdate.html.contains("diff-del"), "New file should not have deletion lines")
            assertTrue(lastToolUpdate.html.contains("package com.example"))
            assertTrue(lastToolUpdate.html.contains("fun main()"))
        }
    }

    // ---- Reconnect replay for tool blocks ----

    @Test
    fun reconnectWhileWorkingReplaysMidPromptToolCalls() = testApp { fakeSession ->
        val gate = kotlinx.coroutines.CompletableDeferred<List<Event>>()
        fakeSession.enqueueDelayedResponse(gate)

        val wsClient = createClient { install(WebSockets) }

        // First connection: send prompt, wait for some tool calls to appear
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("work"))
            send(Frame.Text(prompt))

            // The prompt is now blocked waiting on gate — agent is "working"
            // Disconnect (WebSocket closes when this block exits)
        }

        // At this point the session is in "working" state with no tool calls yet.
        // The gate is still pending, so promptStartTime > 0 and the agent is working.

        // Second connection: should get Connected with agentWorking=true
        wsClient.webSocket("/ws") {
            val connected = decodeWsMessage(incoming.receive())
            assertIs<WsMessage.Connected>(connected)
            assertTrue(connected.agentWorking, "Should indicate agent is working")

            // Complete the gate so the prompt finishes
            gate.complete(listOf(
                Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("done"))),
            ))

            // Wait for the response to come through
            val messages = collectUpdatesUntilTurnComplete()
            val turnComplete = messages.last()
            assertIs<WsMessage.TurnComplete>(turnComplete)
        }
    }

    // ---- Reconnect replays in-progress turn state ----

    @Test
    fun reconnectReplaysTurnStateWithThoughtResponseAndTools() = testApp { fakeSession ->
        val gate = kotlinx.coroutines.CompletableDeferred<List<Event>>()

        // Emit thought + tool call + response text, then block on gate
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Let me think..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Starting response"))))
                // Block — agent is mid-turn
                val events = gate.await()
                for (event in events) emit(event)
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val wsClient = createClient { install(WebSockets) }

        // First connection: send prompt, wait for thought/tool/response to stream
        wsClient.webSocket("/ws") {
            incoming.receive() // Connected

            val prompt = json.encodeToString(WsMessage.serializer(), WsMessage.Prompt("work"))
            send(Frame.Text(prompt))

            // Wait for thought, tool, and response updates to arrive
            val messages = mutableListOf<WsMessage>()
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = json.decodeFromString(WsMessage.serializer(), frame.readText())
                    messages.add(msg)
                    // Wait until we see the response text
                    if (msg is WsMessage.HtmlUpdate && msg.html.contains("Starting response")) break
                }
            }
            // Disconnect while agent is still working
        }

        // Small delay to ensure turn state is persisted
        kotlinx.coroutines.delay(100)

        // Second connection: should replay the full in-progress turn state
        wsClient.webSocket("/ws") {
            val connected = decodeWsMessage(incoming.receive())
            assertIs<WsMessage.Connected>(connected)
            assertTrue(connected.agentWorking)

            // Collect replayed messages (history + turn state)
            val replayMessages = mutableListOf<WsMessage>()
            for (i in 0 until 20) {
                val frame = kotlinx.coroutines.withTimeoutOrNull(500) { incoming.receive() } ?: break
                if (frame is Frame.Text) {
                    replayMessages.add(json.decodeFromString(WsMessage.serializer(), frame.readText()))
                }
            }

            val htmlUpdates = replayMessages.filterIsInstance<WsMessage.HtmlUpdate>()

            // Should have replayed the user message from history
            val userMsg = htmlUpdates.find { it.html.contains(Css.MSG_USER) }
            assertTrue(userMsg != null, "Should replay user message from history")

            // Should have replayed thought text
            val thoughtReplay = htmlUpdates.find { it.html.contains("Let me think...") }
            assertTrue(thoughtReplay != null, "Should replay in-progress thought text")
            assertTrue(thoughtReplay!!.swap == Swap.Morph, "Thought replay should use Morph")

            // Should have replayed response text
            val responseReplay = htmlUpdates.find { it.html.contains("Starting response") }
            assertTrue(responseReplay != null, "Should replay in-progress response text")
            assertTrue(responseReplay!!.swap == Swap.Morph, "Response replay should use Morph")

            // Should have replayed tool block
            val toolReplay = htmlUpdates.find { it.html.contains(Css.TOOL_BLOCK) }
            assertTrue(toolReplay != null, "Should replay in-progress tool block")
            assertTrue(toolReplay!!.html.contains("Read file.txt"), "Tool replay should contain tool title")

            // Placeholders should use the same IDs so future morphs work
            val placeholders = htmlUpdates.filter { it.html.contains(Css.HIDDEN) && it.swap == Swap.BeforeEnd }
            assertTrue(placeholders.size >= 3, "Should have 3 placeholders for thought/msg/tools")

            // The thought and response morph targets should match placeholder IDs
            val thoughtPlaceholder = placeholders.find {
                Regex("""id="(thought-[^"]+)"""").find(it.html) != null
            }
            assertTrue(thoughtPlaceholder != null, "Should have thought placeholder")
            val thoughtPlaceholderId = Regex("""id="([^"]+)"""").find(thoughtPlaceholder!!.html)!!.groupValues[1]
            assertEquals(thoughtPlaceholderId, thoughtReplay.target, "Thought morph should target thought placeholder")

            // Complete the gate so the prompt finishes
            gate.complete(listOf(
                Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(" finished"))),
            ))

            val remaining = collectUpdatesUntilTurnComplete()
            val turnComplete = remaining.last()
            assertIs<WsMessage.TurnComplete>(turnComplete)
        }
    }
}
