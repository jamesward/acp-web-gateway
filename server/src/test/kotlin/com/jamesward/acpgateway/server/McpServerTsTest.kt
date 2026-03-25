package com.jamesward.acpgateway.server

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.images.builder.Transferable
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the MCP Streamable HTTP server using the TypeScript MCP SDK in a Testcontainer.
 * Reproduces client compatibility issues that the Java SDK might not surface.
 */
class McpServerTsTest {

    private val logger = LoggerFactory.getLogger(McpServerTsTest::class.java)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startDevServer(port: Int): io.ktor.server.engine.EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration> {
        val holder = AgentHolder(emptyList(), System.getProperty("user.dir"))
        runBlocking { holder.setupSimulatedAgent() }
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port) {
            devModule(holder, debug = true)
        }
        server.start(wait = false)
        return server
    }

    private val mcpClientScript = """
const { StreamableHTTPClientTransport } = require("@modelcontextprotocol/sdk/client/streamableHttp.js");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");

const baseUrl = process.env.MCP_URL;

async function main() {
    console.log("Connecting to MCP server at:", baseUrl);

    const transport = new StreamableHTTPClientTransport(
        new URL(baseUrl)
    );

    const client = new Client({
        name: "test-ts-client",
        version: "1.0.0",
    });

    await client.connect(transport);
    console.log("Connected and initialized");

    // List tools
    const toolsResult = await client.listTools();
    const toolNames = toolsResult.tools.map(t => t.name);
    console.log("Tools:", JSON.stringify(toolNames));

    if (!toolNames.includes("list_acp_tasks")) {
        throw new Error("Missing list_acp_tasks tool, got: " + JSON.stringify(toolNames));
    }
    if (!toolNames.includes("create_acp_task")) {
        throw new Error("Missing create_acp_task tool, got: " + JSON.stringify(toolNames));
    }

    // Call list_acp_tasks
    const result = await client.callTool({ name: "list_acp_tasks", arguments: {} });
    console.log("list_acp_tasks result:", JSON.stringify(result));

    const text = result.content[0].text;
    if (text.trim() !== "[]") {
        throw new Error("Expected empty task list, got: " + text);
    }

    console.log("SUCCESS");
    await client.close();
}

main().catch(err => {
    console.error("FAILURE:", err);
    process.exit(1);
});
""".trimIndent()

    /**
     * Sends a raw MCP initialize request to the dev mode /mcp endpoint and checks
     * that no null fields are serialized. The TS MCP SDK rejects "_meta":null.
     */
    @Test
    fun devModeInitializeResponseMustNotContainMetaNull() = runBlocking {
        val port = freePort()
        val server = startDevServer(port)

        try {
            delay(500)

            val httpClient = HttpClient(ClientCIO)
            val response = httpClient.post("http://localhost:$port/mcp") {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json, text/event-stream")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}""")
            }

            val body = response.bodyAsText()
            logger.info("Dev mode raw initialize response: {}", body)

            assertFalse(
                body.contains(""""_meta":null""") || body.contains(""""_meta": null"""),
                "Dev mode initialize response contains \"_meta\":null which breaks the TypeScript MCP SDK. Response:\n$body"
            )
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun devModeListTasksViaTypeScriptSdk() = runBlocking {
        val port = freePort()
        val server = startDevServer(port)

        try {
            delay(500)

            Testcontainers.exposeHostPorts(port)

            val container = GenericContainer("node:22-slim")
                .withCommand("sh", "-c", "cd /test && npm install @modelcontextprotocol/sdk && node mcp-client.js")
                .withEnv("MCP_URL", "http://host.testcontainers.internal:$port/mcp")
                .withLogConsumer(Slf4jLogConsumer(logger))
                .withStartupTimeout(java.time.Duration.ofSeconds(120))

            container.withCopyToContainer(
                Transferable.of(mcpClientScript),
                "/test/mcp-client.js"
            )

            try {
                container.start()

                val exitCode = container.dockerClient
                    .waitContainerCmd(container.containerId)
                    .start()
                    .awaitStatusCode()

                val logs = container.logs
                logger.info("Container logs:\n{}", logs)

                assertTrue(logs.contains("SUCCESS"), "TypeScript MCP client did not succeed. Logs:\n$logs")
                assertTrue(exitCode == 0, "Container exited with code $exitCode. Logs:\n$logs")
            } finally {
                container.stop()
            }
        } finally {
            server.stop(100, 500)
        }
    }
}
