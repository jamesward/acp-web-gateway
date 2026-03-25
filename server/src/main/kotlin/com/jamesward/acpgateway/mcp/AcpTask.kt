package com.jamesward.acpgateway.mcp

import com.jamesward.acpgateway.shared.PermissionOptionInfo
import com.jamesward.acpgateway.shared.ToolKind
import com.jamesward.acpgateway.shared.ToolStatus
import kotlinx.serialization.Serializable
import java.util.*

enum class TaskState { Created, Working, Completed, Failed, Cancelled }

@Serializable
data class TaskToolCall(
    val toolCallId: String,
    val title: String,
    val status: ToolStatus,
    val content: String? = null,
    val kind: ToolKind? = null,
    val location: String? = null,
)

@Serializable
data class TaskPermission(
    val toolCallId: String,
    val title: String,
    val options: List<PermissionOptionInfo>,
    val description: String? = null,
)

/**
 * Represents a simulated ACP task — a prompt round-trip tracked as a stateful object.
 * Mutable fields are only written by the single coroutine consuming the message channel.
 */
class AcpTask(
    val id: UUID,
    val prompt: String,
) {
    @Volatile var state: TaskState = TaskState.Created
    val createdAt: Long = System.currentTimeMillis()
    val responseText: StringBuilder = StringBuilder()
    val thoughtText: StringBuilder = StringBuilder()
    val toolCalls: MutableList<TaskToolCall> = mutableListOf()
    @Volatile var pendingPermission: TaskPermission? = null
    @Volatile var error: String? = null

    /** Snapshot of the current task state for serialization. */
    fun snapshot(): TaskSnapshot = TaskSnapshot(
        taskId = id.toString(),
        prompt = prompt,
        state = state.name,
        createdAt = createdAt,
        responseText = responseText.toString(),
        thoughtText = thoughtText.toString(),
        toolCalls = toolCalls.toList(),
        pendingPermission = pendingPermission,
        error = error,
    )
}

@Serializable
data class TaskSnapshot(
    val taskId: String,
    val prompt: String,
    val state: String,
    val createdAt: Long,
    val responseText: String,
    val thoughtText: String,
    val toolCalls: List<TaskToolCall>,
    val pendingPermission: TaskPermission? = null,
    val error: String? = null,
)
