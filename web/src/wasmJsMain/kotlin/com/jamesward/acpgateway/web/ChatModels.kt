package com.jamesward.acpgateway.web

import com.jamesward.acpgateway.shared.ToolKind
import com.jamesward.acpgateway.shared.ToolStatus

data class ToolCallState(
    val id: String,
    val title: String,
    val status: ToolStatus,
    val content: String? = null,
    val contentHtml: String? = null,
    val kind: ToolKind? = null,
    val location: String? = null,
)

sealed class ChatMessage {
    data class User(val text: String, val fileNames: List<String> = emptyList()) : ChatMessage()
    data class Assistant(val markdown: String, val usage: String? = null) : ChatMessage()
    data class Thought(val markdown: String, val usage: String? = null, val elapsedSeconds: Int = 0) : ChatMessage()
    data class ToolBlock(val tools: List<ToolCallState>) : ChatMessage()
    data class Error(val message: String) : ChatMessage()
}
