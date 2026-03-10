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

    @Serializable
    @SerialName("agent_text")
    data class AgentText(val text: String) : WsMessage()

    @Serializable
    @SerialName("agent_thought")
    data class AgentThought(val text: String) : WsMessage()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val title: String,
        val status: ToolStatus,
        val content: String? = null,
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
    data class TurnComplete(
        val stopReason: String,
        val renderedHtml: String? = null,
    ) : WsMessage()

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

    @Serializable
    @SerialName("html_update")
    data class HtmlUpdate(
        val target: String,
        val swap: Swap,
        val html: String,
    ) : WsMessage()

    @Serializable
    @SerialName("browser_state_request")
    data class BrowserStateRequest(val requestId: String, val query: String = "all") : WsMessage()

    @Serializable
    @SerialName("browser_state_response")
    data class BrowserStateResponse(val requestId: String, val state: String) : WsMessage()
}

@Serializable
enum class Swap {
    @SerialName("morph") Morph,
    @SerialName("beforeend") BeforeEnd,
    @SerialName("innerHTML") InnerHTML,
    @SerialName("show") Show,
    @SerialName("hide") Hide,
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
data class ChatEntry(
    val role: String,
    val content: String,
    val timestamp: Long,
)
