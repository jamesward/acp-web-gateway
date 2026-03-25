package com.jamesward.acpgateway.server

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.websocket.*
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.time.Duration
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the MCP Streamable HTTP server using the Java MCP SDK client.
 *
 * Starts a real proxy-mode gateway, connects a fake CLI backend to create
 * a relay session, then verifies the Java MCP client can connect and call tools.
 */
class McpServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startProxyServer(port: Int): io.ktor.server.engine.EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration> {
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port) {
            module(emptyList())
        }
        server.start(wait = false)
        return server
    }

    private fun newWsClient(): HttpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }

    @Test
    fun listTasksReturnsEmptyViaStreamableHttp() = runBlocking {
        val port = freePort()
        val sessionId = UUID.randomUUID()
        val server = startProxyServer(port)

        try {
            // Connect a fake CLI backend so the relay session exists
            val cliClient = newWsClient()
            val cliJob = launch {
                cliClient.clientWebSocket(urlString = "ws://localhost:$port/s/$sessionId/agent") {
                    try { awaitCancellation() } catch (_: CancellationException) {}
                }
            }

            // Wait for relay session to be established
            delay(500)

            // Create Java MCP SDK client with Streamable HTTP transport
            val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$port")
                .endpoint("/s/$sessionId/mcp")
                .build()

            val mcpClient: McpSyncClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build()

            try {
                // Initialize performs the MCP handshake
                val initResult = mcpClient.initialize()
                assertNotNull(initResult)

                // Verify tools are listed
                val tools = mcpClient.listTools()
                val toolNames = tools.tools.map { it.name }
                assertTrue("list_acp_tasks" in toolNames, "Expected list_acp_tasks tool, got: $toolNames")
                assertTrue("create_acp_task" in toolNames, "Expected create_acp_task tool, got: $toolNames")
                assertTrue("get_acp_task" in toolNames, "Expected get_acp_task tool, got: $toolNames")
                assertTrue("cancel_acp_task" in toolNames, "Expected cancel_acp_task tool, got: $toolNames")

                // Call list_acp_tasks — should return empty list
                val result = mcpClient.callTool(CallToolRequest("list_acp_tasks", emptyMap()))
                val text = (result.content.first() as TextContent).text
                assertEquals("[]", text.trim(), "Expected empty task list")
            } finally {
                mcpClient.closeGracefully()
            }

            cliJob.cancelAndJoin()
        } finally {
            server.stop(100, 500)
        }
    }
}
