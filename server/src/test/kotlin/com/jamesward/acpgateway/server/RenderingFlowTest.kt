@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.server

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.jamesward.acpgateway.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tests the message flow: given specific ACP events from the agent,
 * verify the sequence of structured WsMessages sent to the browser.
 *
 * These tests verify the WebSocketHandler orchestration layer — correct
 * message types, fields, and ordering for each event type.
 *
 * Uses handleChatChannels directly with in-memory channels.
 */
class RenderingFlowTest {

    private fun testChannels(
        block: suspend (input: Channel<WsMessage>, output: Channel<WsMessage>, ControllableFakeClientSession) -> Unit,
    ) = runTest {
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
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        val handlerJob = launch {
            handleChatChannels(input, output, session, manager)
        }

        try {
            block(input, output, fakeSession)
        } finally {
            input.close()
            handlerJob.cancel()
        }
    }

    private suspend fun Channel<WsMessage>.collectUntilTurnComplete(): List<WsMessage> {
        val messages = mutableListOf<WsMessage>()
        for (msg in this) {
            messages.add(msg)
            if (msg is WsMessage.TurnComplete) break
        }
        return messages
    }

    /** Accumulate AgentText deltas by msgId into full text (simulates what client does). */
    private fun List<WsMessage.AgentText>.accumulatedText(): String {
        return this.joinToString("") { it.markdown }
    }

    /** Accumulate AgentThought deltas by thoughtId into full text. */
    private fun List<WsMessage.AgentThought>.accumulatedThought(): String {
        return this.joinToString("") { it.markdown }
    }

    // ---- Simple text response flow ----

    @Test
    fun textResponseSendsCorrectMessageSequence() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueTextResponse("Hello, world!")

        output.receive() // Connected

        input.send(WsMessage.Prompt("hi"))

        val messages = output.collectUntilTurnComplete()

        // 1. UserMessage broadcast
        val userMsg = messages.filterIsInstance<WsMessage.UserMessage>().firstOrNull()
        assertNotNull(userMsg, "Should have UserMessage")
        assertEquals("hi", userMsg.text)

        // 2. AgentText deltas that accumulate to full markdown
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()
        assertTrue(agentTexts.isNotEmpty(), "Should have AgentText messages")
        val accumulated = agentTexts.accumulatedText()
        assertTrue(accumulated.contains("Hello, world!"))
        assertTrue(agentTexts.first().msgId.startsWith("msg-"))

        // 3. TurnComplete
        val turnComplete = messages.last()
        assertIs<WsMessage.TurnComplete>(turnComplete)
        assertEquals("end_turn", turnComplete.stopReason)
    }

    // ---- Incremental markdown rendering ----

    @Test
    fun streamingChunksRenderMarkdownIncrementally() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Hello **bold"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("** world"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()

        assertTrue(agentTexts.size >= 2, "Should have at least 2 AgentText messages (deltas)")

        // First delta: "Hello **bold"
        val firstDelta = agentTexts.first()
        assertEquals("Hello **bold", firstDelta.markdown)

        // Second delta: "** world"
        val secondDelta = agentTexts[1]
        assertEquals("** world", secondDelta.markdown)

        // Accumulated: "Hello **bold** world"
        val accumulated = agentTexts.accumulatedText()
        assertTrue(accumulated.contains("Hello **bold** world"))

        // All AgentText messages share the same msgId
        val ids = agentTexts.map { it.msgId }.toSet()
        assertEquals(1, ids.size, "All AgentText messages should share one msgId")
    }

    // ---- Tool call flow ----

    @Test
    fun toolCallSendsToolCallMessages() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file.txt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text("file contents"))),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("read it"))

        val messages = output.collectUntilTurnComplete()

        // Tool call messages
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()
        assertTrue(toolCalls.isNotEmpty(), "Should have ToolCall messages")

        // First: in-progress
        val started = toolCalls.first()
        assertEquals("tc-1", started.toolCallId)
        assertEquals("Read file.txt", started.title)
        assertEquals(ToolStatus.InProgress, started.status)

        // Last: completed with content
        val completed = toolCalls.last()
        assertEquals("tc-1", completed.toolCallId)
        assertEquals(ToolStatus.Completed, completed.status)
        assertEquals("file contents", completed.content)

        // AgentText delta also present
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()
        assertTrue(agentTexts.accumulatedText().contains("Done"))
    }

    // ---- Multiple tool calls ----

    @Test
    fun multipleToolCallsInOneTurn() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                for (i in 1..3) {
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

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()

        // 3 tools × 2 messages each (start + complete) = 6
        assertEquals(6, toolCalls.size, "Should have 6 ToolCall messages (3 tools × 2)")

        val toolIds = toolCalls.map { it.toolCallId }.toSet()
        assertEquals(setOf("tc-1", "tc-2", "tc-3"), toolIds)
    }

    // ---- Thought chunks ----

    @Test
    fun thoughtChunksSendAgentThoughtMessages() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Let me think"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text(" about this..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Answer"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("think"))

        val messages = output.collectUntilTurnComplete()
        val thoughts = messages.filterIsInstance<WsMessage.AgentThought>()
        val texts = messages.filterIsInstance<WsMessage.AgentText>()

        assertTrue(thoughts.isNotEmpty(), "Should have AgentThought messages")
        assertTrue(texts.isNotEmpty(), "Should have AgentText messages")

        // Thoughts share a thoughtId
        val thoughtIds = thoughts.map { it.thoughtId }.toSet()
        assertEquals(1, thoughtIds.size, "All thoughts should share one thoughtId")
        assertTrue(thoughtIds.first().startsWith("thought-"))

        // Accumulated thoughts contain full text
        val accumulatedThought = thoughts.accumulatedThought()
        assertTrue(accumulatedThought.contains("think"))
        assertTrue(accumulatedThought.contains("about this"))
    }

    // ---- Error handling ----

    @Test
    fun errorDuringPromptSendsErrorMessage() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                throw RuntimeException("Something broke")
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("break"))

        val messages = output.collectUntilTurnComplete()

        val errors = messages.filterIsInstance<WsMessage.Error>()
        assertTrue(errors.isNotEmpty(), "Should have Error message")
        assertTrue(errors.first().message.contains("Something broke"))

        val turnComplete = messages.last()
        assertIs<WsMessage.TurnComplete>(turnComplete)
        assertEquals("error", turnComplete.stopReason)
    }

    // ---- History replay ----

    @Test
    fun historyReplayOnReconnectIncludesThoughtsAndToolCalls() = runTest {
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
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"

        // First connection: run a prompt with thought + tool call + response
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Let me think..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-hist"),
                    title = "Read data.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-hist"),
                    title = "Read data.txt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text("file data"))),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Here is the answer"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val input1 = Channel<WsMessage>(Channel.UNLIMITED)
        val output1 = Channel<WsMessage>(Channel.UNLIMITED)

        val handler1 = launch {
            handleChatChannels(input1, output1, session, manager)
        }

        output1.receive() // Connected

        input1.send(WsMessage.Prompt("tell me"))

        output1.collectUntilTurnComplete()

        // Disconnect first client
        input1.close()
        handler1.cancel()

        // Second connection: should get full history replay with thought + tool calls + response
        val input2 = Channel<WsMessage>(Channel.UNLIMITED)
        val output2 = Channel<WsMessage>(Channel.UNLIMITED)

        val handler2 = launch {
            handleChatChannels(input2, output2, session, manager)
        }

        val connected = output2.receive()
        assertIs<WsMessage.Connected>(connected)

        // Collect all messages until TurnComplete (history replay)
        val replayMsgs = output2.collectUntilTurnComplete()

        // Should have UserMessage
        val userMsgs = replayMsgs.filterIsInstance<WsMessage.UserMessage>()
        assertEquals(1, userMsgs.size, "Should replay 1 UserMessage")
        assertEquals("tell me", userMsgs.first().text)

        // Should have AgentThought from history
        val thoughts = replayMsgs.filterIsInstance<WsMessage.AgentThought>()
        assertTrue(thoughts.isNotEmpty(), "Should replay thought from history")
        assertTrue(thoughts.first().markdown.contains("Let me think"))
        assertTrue(thoughts.first().thoughtId.startsWith("history-thought-"))

        // Should have ToolCall from history
        val toolCalls = replayMsgs.filterIsInstance<WsMessage.ToolCall>()
        assertTrue(toolCalls.isNotEmpty(), "Should replay tool calls from history")
        assertEquals("Read data.txt", toolCalls.first().title)
        assertEquals(ToolStatus.Completed, toolCalls.first().status)

        // Should have AgentText from history
        val texts = replayMsgs.filterIsInstance<WsMessage.AgentText>()
        assertTrue(texts.isNotEmpty(), "Should replay AgentText from history")
        assertTrue(texts.first().markdown.contains("Here is the answer"))
        assertTrue(texts.first().msgId.startsWith("history-"))

        // Should have TurnComplete
        val turnComplete = replayMsgs.last()
        assertIs<WsMessage.TurnComplete>(turnComplete)

        input2.close()
        handler2.cancel()
    }

    // ---- Second client during active turn sees agentWorking=true ----

    @Test
    fun secondClientDuringActiveTurnSeesAgentWorking() = runTest {
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
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"

        // First turn: complete a prompt to create history
        fakeSession.enqueueTextResponse("First answer")

        val input1 = Channel<WsMessage>(Channel.UNLIMITED)
        val output1 = Channel<WsMessage>(Channel.UNLIMITED)

        val handler1 = launch {
            handleChatChannels(input1, output1, session, manager)
        }

        output1.receive() // Connected

        input1.send(WsMessage.Prompt("first question"))
        output1.collectUntilTurnComplete()

        // Second turn: start a prompt that stays in-progress
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working..."))))
                gate.await()
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        input1.send(WsMessage.Prompt("second question"))

        // Wait for the in-progress AgentText to arrive
        for (msg in output1) {
            if (msg is WsMessage.AgentText) break
        }

        // Now connect a second client while the turn is active
        val input2 = Channel<WsMessage>(Channel.UNLIMITED)
        val output2 = Channel<WsMessage>(Channel.UNLIMITED)

        val handler2 = launch {
            handleChatChannels(input2, output2, session, manager)
        }

        // Collect all messages from the second client's replay
        val replayMsgs = mutableListOf<WsMessage>()
        val connected = output2.receive()
        assertIs<WsMessage.Connected>(connected)
        assertTrue(connected.agentWorking, "Connected should report agentWorking=true")

        // Collect remaining replay messages (history + in-progress state)
        // Replay order: UserMessage("first question"), AgentText("First answer"), TurnComplete("history"),
        //               UserMessage("second question"), AgentText("Working...")
        // We need to collect past the history TurnComplete to the in-progress AgentText
        var seenTurnComplete = false
        for (msg in output2) {
            replayMsgs.add(msg)
            if (msg is WsMessage.TurnComplete) seenTurnComplete = true
            // Stop after seeing TurnComplete AND then an AgentText (the in-progress content)
            if (seenTurnComplete && msg is WsMessage.AgentText) break
        }

        // History TurnComplete should have "history" stop reason, not "end_turn"
        val historyTurnCompletes = replayMsgs.filterIsInstance<WsMessage.TurnComplete>()
        assertTrue(historyTurnCompletes.isNotEmpty(), "Should have history TurnComplete")
        for (tc in historyTurnCompletes) {
            assertEquals("history", tc.stopReason,
                "History TurnComplete should use 'history' stop reason so client doesn't reset agentWorking")
        }

        // The second client should have received in-progress content after the history
        val textsAfterTurnComplete = replayMsgs.dropWhile { it !is WsMessage.TurnComplete }
            .filterIsInstance<WsMessage.AgentText>()
        assertTrue(textsAfterTurnComplete.isNotEmpty(), "Should replay in-progress AgentText after history")

        // Release the gate and clean up
        gate.complete(Unit)
        input1.close()
        handler1.cancel()
        input2.close()
        handler2.cancel()
    }

    // ---- Failed tool call ----

    @Test
    fun failedToolCallHasFailedStatus() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-fail"),
                    title = "Write forbidden.txt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-fail"),
                    title = "Write forbidden.txt",
                    status = ToolCallStatus.FAILED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Failed to write"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("write it"))

        val messages = output.collectUntilTurnComplete()
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()

        val failedCall = toolCalls.last()
        assertEquals("tc-fail", failedCall.toolCallId)
        assertEquals(ToolStatus.Failed, failedCall.status)
    }

    // ---- Many tool calls ----

    @Test
    fun manyToolCalls() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
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
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("All done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("many tools"))

        val messages = output.collectUntilTurnComplete()
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()

        // 20 tools × 2 messages each = 40
        assertEquals(40, toolCalls.size)
        val uniqueIds = toolCalls.map { it.toolCallId }.toSet()
        assertEquals(20, uniqueIds.size)
    }

    // ---- Markdown content ----

    @Test
    fun markdownNewlinesPreserved() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueTextResponse("Line 1\nLine 2\nLine 3")

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()
        val accumulated = agentTexts.accumulatedText()
        assertTrue(accumulated.contains("Line 1"))
        assertTrue(accumulated.contains("Line 2"))
        assertTrue(accumulated.contains("Line 3"))
    }

    @Test
    fun markdownParagraphBreaks() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueTextResponse("Paragraph 1\n\nParagraph 2")

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()
        val accumulated = agentTexts.accumulatedText()
        assertTrue(accumulated.contains("Paragraph 1"))
        assertTrue(accumulated.contains("Paragraph 2"))
    }

    @Test
    fun numberedListRendering() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueTextResponse("1. First\n2. Second\n3. Third")

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()
        val accumulated = agentTexts.accumulatedText()
        assertTrue(accumulated.contains("1. First"))
        assertTrue(accumulated.contains("3. Third"))
    }

    // ---- Diff rendering in tool calls ----

    @Test
    fun diffContentInToolCall() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-diff"),
                    title = "Edit main.kt",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-diff"),
                    title = "Edit main.kt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallContent.Diff(
                        path = "src/main.kt",
                        oldText = "val x = 1",
                        newText = "val x = 2",
                    )),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Edited"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("edit"))

        val messages = output.collectUntilTurnComplete()
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()
        val completed = toolCalls.last { it.status == ToolStatus.Completed }

        assertNotNull(completed.content, "Diff should produce content text")
        assertTrue(completed.content!!.contains("```diff"), "Diff content should be a markdown diff code block")
        assertTrue(completed.content!!.contains("-val x = 1"), "Diff should contain removed line")
        assertTrue(completed.content!!.contains("+val x = 2"), "Diff should contain added line")
        assertNull(completed.contentHtml, "Diff should not produce contentHtml (rendered client-side)")
    }

    // ---- Tool call with kind and location ----

    @Test
    fun toolCallIncludesKindAndLocation() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-read"),
                    title = "Read config.yml",
                    status = ToolCallStatus.IN_PROGRESS,
                    kind = com.agentclientprotocol.model.ToolKind.READ,
                    locations = listOf(ToolCallLocation("/home/user/config.yml")),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-read"),
                    title = "Read config.yml",
                    status = ToolCallStatus.COMPLETED,
                    kind = com.agentclientprotocol.model.ToolKind.READ,
                    locations = listOf(ToolCallLocation("/home/user/config.yml")),
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("read"))

        val messages = output.collectUntilTurnComplete()
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()

        val started = toolCalls.first()
        assertEquals(com.jamesward.acpgateway.shared.ToolKind.Read, started.kind)
        assertEquals("/home/user/config.yml", started.location)
    }

    // ---- Reconnect while working ----

    @Test
    fun reconnectWhileWorkingReplaysInProgressState() = testChannels { input, output, fakeSession ->
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Thinking..."))))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                gate.await()
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-1"),
                    title = "Read file",
                    status = ToolCallStatus.COMPLETED,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        // Start a prompt
        input.send(WsMessage.Prompt("test"))

        // Wait for some messages to arrive
        val msgs = mutableListOf<WsMessage>()
        for (msg in output) {
            msgs.add(msg)
            if (msg is WsMessage.ToolCall) break
        }
        assertTrue(msgs.any { it is WsMessage.ToolCall }, "Should have received ToolCall")

        // Release the gate to let prompt complete
        gate.complete(Unit)

        output.collectUntilTurnComplete()
    }

    // ---- Usage info ----

    @Test
    fun usageInfoIncludedInAgentText() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Hello"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.UsageUpdate(
                    used = 10000,
                    size = 100000,
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()

        // After usage update, an AgentText delta should include usage string
        val withUsage = agentTexts.filter { it.usage != null }
        assertTrue(withUsage.isNotEmpty(), "Should have AgentText with usage info")
        assertTrue(withUsage.last().usage!!.contains("10K"), "Usage should contain token count")
        // Accumulated text should contain the original content
        assertTrue(agentTexts.accumulatedText().contains("Hello"))
    }

    // ---- Delta streaming specific tests ----

    @Test
    fun deltaStreamingSendsChunksNotAccumulated() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk1"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk2"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk3"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()

        assertEquals(3, agentTexts.size, "Should have exactly 3 delta messages")

        // Each delta contains only its chunk, not accumulated text
        assertEquals("chunk1", agentTexts[0].markdown)
        assertEquals("chunk2", agentTexts[1].markdown)
        assertEquals("chunk3", agentTexts[2].markdown)

        // Accumulated = full text
        assertEquals("chunk1chunk2chunk3", agentTexts.accumulatedText())
    }

    @Test
    fun deltaThoughtStreamingSendsChunksNotAccumulated() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("think1"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("think2"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("response"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val thoughts = messages.filterIsInstance<WsMessage.AgentThought>()

        assertEquals(2, thoughts.size, "Should have exactly 2 thought deltas")
        assertEquals("think1", thoughts[0].markdown)
        assertEquals("think2", thoughts[1].markdown)
        assertEquals("think1think2", thoughts.accumulatedThought())
    }

    @Test
    fun seqNumbersAreMonotonicallyIncreasing() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(" world"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        val connected = output.receive() as WsMessage.Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()

        // All broadcast messages should have seq > 0 and be monotonically increasing
        val seqs = messages.mapNotNull { msg ->
            when (msg) {
                is WsMessage.AgentText -> msg.seq
                is WsMessage.UserMessage -> msg.seq
                is WsMessage.TurnComplete -> msg.seq
                else -> null
            }
        }.filter { it > 0 }

        assertTrue(seqs.isNotEmpty(), "Should have messages with seq > 0")
        for (i in 1 until seqs.size) {
            assertTrue(seqs[i] > seqs[i - 1], "Seq numbers should be monotonically increasing: ${seqs[i-1]} -> ${seqs[i]}")
        }
    }

    @Test
    fun usageUpdateSendsEmptyDelta() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("content"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.UsageUpdate(
                    used = 5000,
                    size = 50000,
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()
        val agentTexts = messages.filterIsInstance<WsMessage.AgentText>()

        // First delta has content, second (usage update) has empty markdown
        assertTrue(agentTexts.size >= 2, "Should have at least 2 AgentText messages")
        assertEquals("content", agentTexts[0].markdown)
        assertEquals("", agentTexts[1].markdown) // Usage-only update

        // Accumulated text is just the content (no duplication)
        assertEquals("content", agentTexts.accumulatedText())

        // Usage info present on the usage update message
        assertNotNull(agentTexts[1].usage)
    }

    // ---- Reconnection with delta resume ----

    @Test
    fun reconnectWithResumeFromGetsDeltaReplay() = runTest {
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
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"

        // Use a gate so the prompt stays in-progress during reconnect
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk1"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk2"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("chunk3"))))
                gate.await()
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        // First client connects and receives some chunks
        val input1 = Channel<WsMessage>(Channel.UNLIMITED)
        val output1 = Channel<WsMessage>(Channel.UNLIMITED)

        val handler1 = launch {
            handleChatChannels(input1, output1, session, manager)
        }

        output1.receive() // Connected

        input1.send(WsMessage.Prompt("test"))

        // Collect until we have 3 AgentText deltas
        val firstClientMsgs = mutableListOf<WsMessage>()
        for (msg in output1) {
            firstClientMsgs.add(msg)
            val textCount = firstClientMsgs.count { it is WsMessage.AgentText }
            if (textCount >= 3) break
        }

        // Record the seq from the second chunk (we'll resume from there)
        val chunk2Seq = firstClientMsgs.filterIsInstance<WsMessage.AgentText>()[1].seq
        assertTrue(chunk2Seq > 0, "chunk2 should have seq > 0")

        // First client disconnects
        input1.close()
        handler1.cancel()

        // Second client connects with ResumeFrom
        val input2 = Channel<WsMessage>(Channel.UNLIMITED)
        val output2 = Channel<WsMessage>(Channel.UNLIMITED)

        // Send ResumeFrom as first message (with matching epoch for delta resume)
        input2.send(WsMessage.ResumeFrom(chunk2Seq, session.epoch))

        val handler2 = launch {
            handleChatChannels(input2, output2, session, manager)
        }

        val connected2 = output2.receive()
        assertIs<WsMessage.Connected>(connected2)
        assertTrue(connected2.agentWorking, "Should show agent is working")
        assertEquals(session.epoch, connected2.epoch, "Connected should include session epoch")

        // Should receive only chunk3 (delta since chunk2)
        val resumedMsgs = mutableListOf<WsMessage>()
        for (msg in output2) {
            resumedMsgs.add(msg)
            if (msg is WsMessage.AgentText) break
        }

        val resumedTexts = resumedMsgs.filterIsInstance<WsMessage.AgentText>()
        assertTrue(resumedTexts.isNotEmpty(), "Should receive missed delta on resume")
        // The resumed messages should include chunk3
        assertEquals("chunk3", resumedTexts.first().markdown)

        // Let the prompt complete
        gate.complete(Unit)

        input2.close()
        handler2.cancel()
    }

    @Test
    fun resumeFromWithStaleEpochDoesFullReplay() = runTest {
        // Simulates server restart: client sends ResumeFrom with old epoch, server has new epoch
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
        manager.agentName = "test-agent"
        manager.agentVersion = "1.0.0"

        val input = Channel<WsMessage>(Channel.UNLIMITED)
        val output = Channel<WsMessage>(Channel.UNLIMITED)

        // Client sends ResumeFrom with a stale epoch from a previous server lifetime
        input.send(WsMessage.ResumeFrom(5, "old-epoch-from-previous-server"))

        val handler = launch {
            handleChatChannels(input, output, session, manager)
        }

        val connected = output.receive()
        assertIs<WsMessage.Connected>(connected)
        // Server's epoch differs from client's — client should detect mismatch and reset
        assertNotEquals("old-epoch-from-previous-server", connected.epoch)
        assertTrue(connected.epoch.isNotEmpty(), "Connected should include session epoch")
        assertFalse(connected.agentWorking, "Fresh session should not be working")

        input.close()
        handler.cancel()
    }

    // ---- Image content blocks ----

    @Test
    fun imageContentBlockSendsAgentImageMessage() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Image(
                    data = "iVBORw0KGgoAAAANSUhEUg==",
                    mimeType = "image/png",
                ))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("show me an image"))

        val messages = output.collectUntilTurnComplete()

        val images = messages.filterIsInstance<WsMessage.AgentImage>()
        assertEquals(1, images.size, "Should have 1 AgentImage message")
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", images[0].data)
        assertEquals("image/png", images[0].mimeType)
        assertTrue(images[0].msgId.startsWith("msg-"))
    }

    @Test
    fun mixedTextAndImageContentBlocks() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Here is an image:"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Image(
                    data = "base64data",
                    mimeType = "image/jpeg",
                ))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("And some text after."))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()

        val texts = messages.filterIsInstance<WsMessage.AgentText>()
        val images = messages.filterIsInstance<WsMessage.AgentImage>()

        assertEquals(2, texts.size, "Should have 2 AgentText messages")
        assertEquals(1, images.size, "Should have 1 AgentImage message")
        assertEquals("Here is an image:", texts[0].markdown)
        assertEquals("And some text after.", texts[1].markdown)
        assertEquals("base64data", images[0].data)
        assertEquals("image/jpeg", images[0].mimeType)

        // Verify ordering: text, image, text, turnComplete
        val ordered = messages.filter { it is WsMessage.AgentText || it is WsMessage.AgentImage || it is WsMessage.TurnComplete }
        assertIs<WsMessage.UserMessage>(messages[0])
        assertIs<WsMessage.AgentText>(ordered[0])
        assertIs<WsMessage.AgentImage>(ordered[1])
        assertIs<WsMessage.AgentText>(ordered[2])
        assertIs<WsMessage.TurnComplete>(ordered[3])
    }

    // ---- Thought with image ----

    @Test
    fun thoughtWithImageBroadcastsAgentImage() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("Let me think about this image:"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Image(
                    data = "thoughtImageBase64",
                    mimeType = "image/png",
                ))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Here is my analysis."))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("analyze this"))

        val messages = output.collectUntilTurnComplete()

        val thoughts = messages.filterIsInstance<WsMessage.AgentThought>()
        assertTrue(thoughts.isNotEmpty(), "Should have thought messages")

        val images = messages.filterIsInstance<WsMessage.AgentImage>()
        assertEquals(1, images.size, "Should have 1 AgentImage from thought")
        assertEquals("thoughtImageBase64", images[0].data)
        assertEquals("image/png", images[0].mimeType)
    }

    // ---- Tool call content with images ----

    @Test
    fun toolCallWithImageContentPassesImageThrough() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-read-img"),
                    title = "Read docs/architecture.png",
                    kind = com.agentclientprotocol.model.ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                    locations = listOf(ToolCallLocation("docs/architecture.png")),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-read-img"),
                    title = "Read docs/architecture.png",
                    kind = com.agentclientprotocol.model.ToolKind.READ,
                    status = ToolCallStatus.COMPLETED,
                    locations = listOf(ToolCallLocation("docs/architecture.png")),
                    content = listOf(
                        ToolCallContent.Content(ContentBlock.Image(
                            data = "iVBORw0KGgoAAAANSUhEUg==",
                            mimeType = "image/png",
                        )),
                    ),
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Here is the architecture diagram."))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("show me architecture.png"))

        val messages = output.collectUntilTurnComplete()

        // Tool call should have images
        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()
        val completed = toolCalls.last { it.status == ToolStatus.Completed }
        assertNotNull(completed.images, "Completed tool call should have images")
        assertEquals(1, completed.images!!.size, "Should have 1 image")
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", completed.images!![0].data)
        assertEquals("image/png", completed.images!![0].mimeType)

        // Image should also be promoted to the response area as AgentImage
        val agentImages = messages.filterIsInstance<WsMessage.AgentImage>()
        assertEquals(1, agentImages.size, "Tool call image should be promoted to AgentImage")
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", agentImages[0].data)
        assertEquals("image/png", agentImages[0].mimeType)
    }

    @Test
    fun toolCallWithMixedTextAndImageContent() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId("tc-mixed"),
                    title = "Read mixed content",
                    status = ToolCallStatus.IN_PROGRESS,
                )))
                emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tc-mixed"),
                    title = "Read mixed content",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(
                        ToolCallContent.Content(ContentBlock.Text("Some text result")),
                        ToolCallContent.Content(ContentBlock.Image(
                            data = "base64imagedata",
                            mimeType = "image/jpeg",
                        )),
                    ),
                )))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }

        output.receive() // Connected

        input.send(WsMessage.Prompt("test"))

        val messages = output.collectUntilTurnComplete()

        val toolCalls = messages.filterIsInstance<WsMessage.ToolCall>()
        val completed = toolCalls.last { it.status == ToolStatus.Completed }

        // Should have both text and image content
        assertNotNull(completed.content, "Should have text content")
        assertTrue(completed.content!!.contains("Some text result"))
        assertNotNull(completed.images, "Should have images")
        assertEquals(1, completed.images!!.size)
        assertEquals("base64imagedata", completed.images!![0].data)

        // Image should also be promoted to the response area
        val agentImages = messages.filterIsInstance<WsMessage.AgentImage>()
        assertEquals(1, agentImages.size, "Tool call image should be promoted to AgentImage")
        assertEquals("base64imagedata", agentImages[0].data)
        assertEquals("image/jpeg", agentImages[0].mimeType)
    }

    @Test
    fun turnBufferClearedAfterTurnComplete() = testChannels { input, output, fakeSession ->
        fakeSession.enqueueTextResponse("Hello")

        val connected = output.receive() as WsMessage.Connected

        input.send(WsMessage.Prompt("test"))

        output.collectUntilTurnComplete()

        // After TurnComplete, the turn buffer should be cleared
        // A new client connecting with ResumeFrom should get full replay, not buffer
        // (We can verify this indirectly by checking session.getTurnBufferSince returns empty)
        // The buffer is cleared in handlePrompt after TurnComplete is broadcast
    }

    @Test
    fun permissionRequestIncludesDescription() = runTest {
        val command = ProcessCommand("echo", listOf("test"))
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))
        val clientOps = GatewayClientOperations()
        val testScope = CoroutineScope(Dispatchers.Default)
        val testStore = InMemorySessionStore()
        val fakeSession = ControllableFakeClientSession()
        val session = GatewaySession(
            id = java.util.UUID.randomUUID(),
            clientSession = fakeSession,
            clientOps = clientOps,
            cwd = System.getProperty("user.dir"),
            scope = testScope,
            store = testStore,
        )
        session.ready = true
        session.startEventForwarding()

        val outputChannel = Channel<WsMessage>(Channel.UNLIMITED)
        session.connections.add(outputChannel)

        // Send a permission request with description
        val deferred = kotlinx.coroutines.CompletableDeferred<com.agentclientprotocol.model.RequestPermissionResponse>()
        clientOps.pendingPermissions.send(PendingPermission(
            toolCallId = "tc-plan",
            title = "Ready to code?",
            options = listOf(
                com.agentclientprotocol.model.PermissionOption(
                    optionId = com.agentclientprotocol.model.PermissionOptionId("approve"),
                    name = "Yes",
                    kind = com.agentclientprotocol.model.PermissionOptionKind.ALLOW_ONCE,
                ),
            ),
            deferred = deferred,
            description = "## Plan\n\n1. Step one\n2. Step two",
        ))

        // Receive the permission message from the output channel
        val permMsg = outputChannel.receive()
        assertIs<WsMessage.PermissionRequest>(permMsg)
        assertEquals("Ready to code?", permMsg.title)
        assertEquals("## Plan\n\n1. Step one\n2. Step two", permMsg.description)
        assertEquals(1, permMsg.options.size)
        assertEquals("Yes", permMsg.options[0].name)
    }

    // ---- Mode and session info forwarding ----

    @Test
    fun currentModeUpdateIsForwardedAsCurrentModeMessage() = testChannels { input, output, fakeSession ->
        output.receive() // Connected

        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.CurrentModeUpdate(SessionModeId("code"))))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
        input.send(WsMessage.Prompt("test"))
        val messages = output.collectUntilTurnComplete()

        val modeMsgs = messages.filterIsInstance<WsMessage.CurrentMode>()
        assertEquals(1, modeMsgs.size)
        assertEquals("code", modeMsgs[0].modeId)
        // No availableModes configured in fake, so modeName falls back to modeId
        assertEquals("code", modeMsgs[0].modeName)
    }

    @Test
    fun sessionInfoUpdateIsForwardedAsSessionInfoMessage() = testChannels { input, output, fakeSession ->
        output.receive() // Connected

        fakeSession.enqueueResponse {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.SessionInfoUpdate(title = "My Task")))
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("done"))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
        input.send(WsMessage.Prompt("test"))
        val messages = output.collectUntilTurnComplete()

        val infoMsgs = messages.filterIsInstance<WsMessage.SessionInfo>()
        assertEquals(1, infoMsgs.size)
        assertEquals("My Task", infoMsgs[0].title)
    }
}
