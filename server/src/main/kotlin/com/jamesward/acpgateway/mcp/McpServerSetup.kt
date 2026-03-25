package com.jamesward.acpgateway.mcp

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.util.*

private val taskJson = Json { prettyPrint = true }

fun createMcpServer(taskManager: TaskManager): Server {
    val server = Server(
        serverInfo = Implementation(
            name = "acp-web-gateway",
            version = "0.1.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.addTool(
        name = "list_acp_tasks",
        description = "List all ACP tasks in this session",
    ) {
        val tasks = taskManager.listTasks()
        val json = taskJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(TaskSnapshot.serializer()), tasks)
        CallToolResult(content = listOf(TextContent(text = json)))
    }

    server.addTool(
        name = "create_acp_task",
        description = "Send a prompt to the ACP agent and create a task to track it. Returns a task ID. Poll get_acp_task with this ID to monitor progress until the task state is 'Completed'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "The prompt to send to the ACP agent")
                }
            },
            required = listOf("prompt"),
        ),
    ) { request ->
        val prompt = request.arguments?.get("prompt")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'prompt' parameter is required")),
                isError = true,
            )
        val taskId = taskManager.createTask(prompt)
        CallToolResult(
            content = listOf(
                TextContent(
                    text = """Task created: $taskId
The task is now running. Use get_acp_task with taskId "$taskId" to poll for updates.
The task will be in "Working" state until the agent completes its response."""
                )
            )
        )
    }

    server.addTool(
        name = "get_acp_task",
        description = "Get the current state of an ACP task including response text, thinking content, tool calls, and pending permissions",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("taskId") {
                    put("type", "string")
                    put("description", "The UUID of the task to retrieve")
                }
            },
            required = listOf("taskId"),
        ),
    ) { request ->
        val taskIdStr = request.arguments?.get("taskId")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'taskId' parameter is required")),
                isError = true,
            )
        val taskId = try {
            UUID.fromString(taskIdStr)
        } catch (_: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: Invalid taskId format")),
                isError = true,
            )
        }
        val snapshot = taskManager.getTask(taskId)
            ?: return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: Task not found: $taskIdStr")),
                isError = true,
            )
        val json = taskJson.encodeToString(TaskSnapshot.serializer(), snapshot)
        CallToolResult(content = listOf(TextContent(text = json)))
    }

    server.addTool(
        name = "cancel_acp_task",
        description = "Cancel a running ACP task",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("taskId") {
                    put("type", "string")
                    put("description", "The UUID of the task to cancel")
                }
            },
            required = listOf("taskId"),
        ),
    ) { request ->
        val taskIdStr = request.arguments?.get("taskId")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'taskId' parameter is required")),
                isError = true,
            )
        val taskId = try {
            UUID.fromString(taskIdStr)
        } catch (_: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: Invalid taskId format")),
                isError = true,
            )
        }
        val cancelled = taskManager.cancelTask(taskId)
        if (cancelled) {
            CallToolResult(content = listOf(TextContent(text = "Task $taskIdStr cancelled")))
        } else {
            CallToolResult(
                content = listOf(TextContent(text = "Error: Task not found or not cancellable: $taskIdStr")),
                isError = true,
            )
        }
    }

    return server
}

/**
 * Install MCP Streamable HTTP endpoint at the given path.
 * Used in dev mode with a single TaskManager.
 */
fun Application.installMcp(path: String, taskManager: TaskManager) {
    mcpStatelessStreamableHttp(path = path) {
        createMcpServer(taskManager)
    }
}

/**
 * Install MCP Streamable HTTP endpoint at a path containing {sessionId}.
 * The [lookupTaskManager] function receives the session ID and returns the TaskManager.
 * Used in relay mode where each session has its own TaskManager.
 *
 * The RoutingContext block has access to call.parameters for extracting the session ID.
 */
fun Application.installMcpWithLookup(
    path: String,
    lookupTaskManager: (sessionId: String) -> TaskManager?,
) {
    mcpStatelessStreamableHttp(path = path) {
        val sessionIdStr = call.parameters["sessionId"] ?: ""
        val taskManager = lookupTaskManager(sessionIdStr)
        if (taskManager != null) {
            createMcpServer(taskManager)
        } else {
            createMcpServer(TaskManager(
                executorFactory = { throw IllegalStateException("No active session") },
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            ))
        }
    }
}

/**
 * Installs a Ktor response interceptor that serializes MCP JSON-RPC message types
 * using [McpJson] (which has explicitNulls = false). The MCP Kotlin SDK's
 * StreamableHttpServerTransport calls call.respond(payload) where payload is typed
 * as Any, so Ktor's ContentNegotiation can't resolve the kotlinx-serialization
 * serializer. This interceptor catches those types before ContentNegotiation and
 * serializes them correctly, preventing "_meta": null from being sent.
 */
fun Application.installMcpJsonSerializer() {
    install(createApplicationPlugin("McpJsonSerializer") {
        onCallRespond { _ ->
            transformBody { body ->
                when (body) {
                    is JSONRPCMessage -> {
                        val json = McpJson.encodeToString(JSONRPCMessage.serializer(), body)
                        io.ktor.http.content.TextContent(json, ContentType.Application.Json)
                    }
                    is List<*> if body.isNotEmpty() && body.all { it is JSONRPCMessage } -> {
                        @Suppress("UNCHECKED_CAST")
                        val messages = body as List<JSONRPCMessage>
                        val json = McpJson.encodeToString(ListSerializer(JSONRPCMessage.serializer()), messages)
                        io.ktor.http.content.TextContent(json, ContentType.Application.Json)
                    }
                    else -> body
                }
            }
        }
    })
}
