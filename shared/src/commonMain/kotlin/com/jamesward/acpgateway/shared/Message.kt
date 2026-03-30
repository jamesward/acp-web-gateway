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
        val resourceLinks: List<ResourceLinkInfo> = emptyList(),
    ) : WsMessage()

    /** Server → client: streamed agent response chunk (delta). Client accumulates by msgId. */
    @Serializable
    @SerialName("agent_text")
    data class AgentText(
        val msgId: String,
        val markdown: String,
        val usage: String? = null,
        val seq: Long = 0,
    ) : WsMessage()

    /** Server → client: image content from agent response. */
    @Serializable
    @SerialName("agent_image")
    data class AgentImage(
        val msgId: String,
        val data: String,
        val mimeType: String,
        val seq: Long = 0,
    ) : WsMessage()

    /** Server → client: streamed agent thought chunk (delta). Client accumulates by thoughtId. */
    @Serializable
    @SerialName("agent_thought")
    data class AgentThought(
        val thoughtId: String,
        val markdown: String,
        val usage: String? = null,
        val seq: Long = 0,
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
        val images: List<ToolCallImage>? = null,
        val seq: Long = 0,
    ) : WsMessage()

    @Serializable
    @SerialName("permission_request")
    data class PermissionRequest(
        val toolCallId: String,
        val title: String,
        val options: List<PermissionOptionInfo>,
        val description: String? = null,
    ) : WsMessage()

    @Serializable
    @SerialName("permission_response")
    data class PermissionResponse(
        val toolCallId: String,
        val optionId: String,
    ) : WsMessage()

    @Serializable
    @SerialName("turn_complete")
    data class TurnComplete(val stopReason: String, val seq: Long = 0) : WsMessage()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val seq: Long = 0) : WsMessage()

    @Serializable
    @SerialName("connected")
    data class Connected(
        val agentName: String,
        val agentVersion: String,
        val cwd: String? = null,
        val agentWorking: Boolean = false,
        val seq: Long = 0,
        val epoch: String = "",
    ) : WsMessage()

    @Serializable
    @SerialName("cancel")
    data object Cancel : WsMessage()

    /** Server → client: replay a completed user message from history. */
    @Serializable
    @SerialName("user_message")
    data class UserMessage(val text: String, val fileNames: List<String> = emptyList(), val seq: Long = 0) : WsMessage()

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

    /** Client → server: request file listing for @-reference autocomplete. */
    @Serializable
    @SerialName("file_list_request")
    data class FileListRequest(val query: String = "") : WsMessage()

    /** Server → client: file listing response for @-reference autocomplete. */
    @Serializable
    @SerialName("file_list_response")
    data class FileListResponse(val files: List<String>) : WsMessage()

    /** Server → client: agent's execution plan update. */
    @Serializable
    @SerialName("plan_update")
    data class PlanUpdate(val entries: List<PlanEntryInfo>, val seq: Long = 0) : WsMessage()

    /** Server → client: current agent mode changed. */
    @Serializable
    @SerialName("current_mode")
    data class CurrentMode(val modeId: String, val modeName: String) : WsMessage()

    /** Server → client: session info updated (e.g., title). */
    @Serializable
    @SerialName("session_info")
    data class SessionInfo(val title: String? = null) : WsMessage()

    /** Client → server: resume from a given sequence number on reconnect. */
    @Serializable
    @SerialName("resume_from")
    data class ResumeFrom(val lastSeq: Long, val epoch: String = "") : WsMessage()
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
enum class PlanEntryStatus {
    @SerialName("pending") Pending,
    @SerialName("in_progress") InProgress,
    @SerialName("completed") Completed,
}

@Serializable
data class PlanEntryInfo(
    val content: String,
    val status: PlanEntryStatus,
)

@Serializable
data class ToolCallImage(
    val data: String,
    val mimeType: String,
)

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
data class ResourceLinkInfo(
    val name: String,
    val uri: String,
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val icon: String? = null,
    val description: String = "",
)

@Serializable
data class ChatEntry(
    val role: String,
    val content: String,
    val timestamp: Long,
    val thought: String? = null,
    val toolCalls: List<ToolCallDisplay>? = null,
    val usage: String? = null,
    val fileNames: List<String>? = null,
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
