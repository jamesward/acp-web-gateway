@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.mcp.DevTaskExecutor
import com.jamesward.acpgateway.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that DevTaskExecutor broadcasts UserMessage to browser connections
 * when an MCP task is created. This verifies that MCP-initiated prompts
 * appear in the web UI.
 */
class DevTaskExecutorTest {

    @Test
    fun mcpTaskBroadcastsUserMessageToBrowserConnections() = runTest {
        val fakeSession = ControllableFakeClientSession()
        fakeSession.enqueueTextResponse("Agent reply")

        val command = ProcessCommand("echo", listOf("test"))
        val manager = AgentProcessManager(command, System.getProperty("user.dir"))
        val testScope = CoroutineScope(Dispatchers.Default)
        val session = GatewaySession(
            id = java.util.UUID.randomUUID(),
            clientSession = fakeSession,
            clientOps = GatewayClientOperations(),
            cwd = System.getProperty("user.dir"),
            scope = testScope,
            store = manager.store,
        )
        session.ready = true
        manager.sessions[session.id] = session

        // Simulate a browser client connected to the session
        val browserChannel = Channel<WsMessage>(Channel.UNLIMITED)
        session.connections.add(browserChannel)

        val executor = DevTaskExecutor(
            sessionProvider = { session },
            scope = testScope,
        )

        // Execute a prompt via MCP (like create_acp_task does)
        val output = executor.executePrompt("what is this project?")

        // Collect all messages from the browser channel
        val browserMessages = mutableListOf<WsMessage>()
        // Wait for messages to arrive — the executor runs in a coroutine
        kotlinx.coroutines.delay(1000)

        // Drain whatever is in the browser channel
        while (true) {
            val msg = browserChannel.tryReceive().getOrNull() ?: break
            browserMessages.add(msg)
        }

        // Verify that a UserMessage was broadcast to the browser
        val userMessages = browserMessages.filterIsInstance<WsMessage.UserMessage>()
        assertTrue(userMessages.isNotEmpty(), "Expected UserMessage to be broadcast to browser connections, but got: ${browserMessages.map { it::class.simpleName }}")
        assertEquals("what is this project?", userMessages.first().text)
    }
}
