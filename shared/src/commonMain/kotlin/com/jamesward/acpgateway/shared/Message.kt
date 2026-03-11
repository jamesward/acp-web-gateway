package com.jamesward.acpgateway.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WsMessage {

    @Serializable
    @SerialName("prompt")
    data class Prompt(
        val text: String,
        val screenshot: String? = null,
        val files: List<FileAttachment> = emptyList(),
    ) : WsMessage()

    /** Server → client: streamed agent response with SSR markdown HTML. */
    @Serializable
    @SerialName("agent_text")
    data class AgentText(
        val msgId: String,
        val markdown: String,
        val usage: String? = null,
    ) : WsMessage()

    /** Server → client: streamed agent thought with SSR markdown HTML. */
    @Serializable
    @SerialName("agent_thought")
    data class AgentThought(
        val thoughtId: String,
        val markdown: String,
        val usage: String? = null,
    ) : WsMessage()

    /** Server → client: tool call start or update. */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val title: String,
        val status: ToolStatus,
        val content: String? = null,
        val contentHtml: String? = null,
        val kind: ToolKind? = null,
        val location: String? = null,
    ) : WsMessage()

    @Serializable
    @SerialName("permission_request")
    data class PermissionRequest(
        val toolCallId: String,
        val title: String,
        val options: List<PermissionOptionInfo>,
    ) : WsMessage()

    @Serializable
    @SerialName("permission_response")
    data class PermissionResponse(
        val toolCallId: String,
        val optionId: String,
    ) : WsMessage()

    @Serializable
    @SerialName("turn_complete")
    data class TurnComplete(val stopReason: String) : WsMessage()

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : WsMessage()

    @Serializable
    @SerialName("connected")
    data class Connected(
        val agentName: String,
        val agentVersion: String,
        val cwd: String? = null,
        val agentWorking: Boolean = false,
    ) : WsMessage()

    @Serializable
    @SerialName("cancel")
    data object Cancel : WsMessage()

    @Serializable
    @SerialName("diagnose")
    data object Diagnose : WsMessage()

    /** Server → client: replay a completed user message from history. */
    @Serializable
    @SerialName("user_message")
    data class UserMessage(val text: String) : WsMessage()

    @Serializable
    @SerialName("browser_state_request")
    data class BrowserStateRequest(val requestId: String, val query: String = "all") : WsMessage()

    @Serializable
    @SerialName("browser_state_response")
    data class BrowserStateResponse(val requestId: String, val state: String) : WsMessage()

    @Serializable
    @SerialName("available_commands")
    data class AvailableCommands(val commands: List<CommandInfo>) : WsMessage()

    @Serializable
    @SerialName("change_agent")
    data class ChangeAgent(val agentId: String) : WsMessage()

    @Serializable
    @SerialName("available_agents")
    data class AvailableAgents(
        val agents: List<AgentInfo>,
        val currentAgentId: String? = null,
    ) : WsMessage()
}

@Serializable
enum class ToolStatus {
    @SerialName("pending") Pending,
    @SerialName("in_progress") InProgress,
    @SerialName("completed") Completed,
    @SerialName("failed") Failed;

    val isTerminal: Boolean get() = this == Completed || this == Failed
}

@Serializable
enum class ToolKind {
    @SerialName("read") Read,
    @SerialName("edit") Edit,
    @SerialName("delete") Delete,
    @SerialName("move") Move,
    @SerialName("search") Search,
    @SerialName("execute") Execute,
    @SerialName("think") Think,
    @SerialName("fetch") Fetch,
    @SerialName("switch_mode") SwitchMode,
    @SerialName("other") Other,
}

@Serializable
enum class PermissionKind {
    @SerialName("allow_once") AllowOnce,
    @SerialName("allow_always") AllowAlways,
    @SerialName("reject_once") RejectOnce,
    @SerialName("reject_always") RejectAlways;

    val isAllow: Boolean get() = this == AllowOnce || this == AllowAlways
}

@Serializable
data class FileAttachment(
    val name: String,
    val mimeType: String,
    val data: String,
)

@Serializable
data class PermissionOptionInfo(
    val optionId: String,
    val name: String,
    val kind: PermissionKind,
)

@Serializable
data class CommandInfo(
    val name: String,
    val description: String,
    val inputHint: String? = null,
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
)

@Serializable
data class ChatEntry(
    val role: String,
    val content: String,
    val timestamp: Long,
)

@Serializable
data class ToolCallDisplay(
    val id: String,
    val title: String,
    val status: ToolStatus,
    val content: String = "",
    val contentHtml: String = "",
    val kind: ToolKind? = null,
    val location: String? = null,
)
