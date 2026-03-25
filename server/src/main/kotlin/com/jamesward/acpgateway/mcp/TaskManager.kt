package com.jamesward.acpgateway.mcp

import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ACP tasks for a single session. Each task maps to a prompt round-trip.
 * The TaskManager launches a coroutine per task that consumes WsMessages from the
 * TaskExecutor and accumulates state on the AcpTask.
 */
class TaskManager(
    private val executorFactory: () -> TaskExecutor,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(TaskManager::class.java)
    private val tasks = ConcurrentHashMap<UUID, AcpTask>()
    private val taskJobs = ConcurrentHashMap<UUID, Job>()

    fun listTasks(): List<TaskSnapshot> = tasks.values.map { it.snapshot() }

    fun getTask(taskId: UUID): TaskSnapshot? = tasks[taskId]?.snapshot()

    fun createTask(prompt: String): UUID {
        val task = AcpTask(id = UUID.randomUUID(), prompt = prompt)
        tasks[task.id] = task
        logger.info("Task created: {} prompt={}", task.id, prompt.take(100))

        val job = scope.launch {
            val executor = executorFactory()
            try {
                task.state = TaskState.Working
                val channel = executor.executePrompt(prompt)
                for (msg in channel) {
                    processMessage(task, msg)
                    if (task.state == TaskState.Completed || task.state == TaskState.Failed) break
                }
                // If channel closed without TurnComplete, mark completed
                if (task.state == TaskState.Working) {
                    task.state = TaskState.Completed
                }
            } catch (e: Exception) {
                if (task.state != TaskState.Cancelled) {
                    logger.error("Task {} failed", task.id, e)
                    task.error = e.message ?: "Unknown error"
                    task.state = TaskState.Failed
                }
            }
        }
        taskJobs[task.id] = job

        return task.id
    }

    fun cancelTask(taskId: UUID): Boolean {
        val task = tasks[taskId] ?: return false
        if (task.state != TaskState.Working && task.state != TaskState.Created) return false

        task.state = TaskState.Cancelled
        taskJobs[taskId]?.cancel()
        scope.launch {
            try {
                executorFactory().cancel()
            } catch (e: Exception) {
                logger.debug("Cancel failed (may be expected)", e)
            }
        }
        logger.info("Task cancelled: {}", taskId)
        return true
    }

    private fun processMessage(task: AcpTask, msg: WsMessage) {
        when (msg) {
            is WsMessage.AgentText -> task.responseText.append(msg.markdown)
            is WsMessage.AgentThought -> task.thoughtText.append(msg.markdown)
            is WsMessage.ToolCall -> {
                val existing = task.toolCalls.indexOfFirst { it.toolCallId == msg.toolCallId }
                val toolCall = TaskToolCall(
                    toolCallId = msg.toolCallId,
                    title = msg.title,
                    status = msg.status,
                    content = msg.content,
                    kind = msg.kind,
                    location = msg.location,
                )
                if (existing >= 0) {
                    task.toolCalls[existing] = toolCall
                } else {
                    task.toolCalls.add(toolCall)
                }
            }
            is WsMessage.PermissionRequest -> {
                task.pendingPermission = TaskPermission(
                    toolCallId = msg.toolCallId,
                    title = msg.title,
                    options = msg.options,
                    description = msg.description,
                )
            }
            is WsMessage.TurnComplete -> {
                task.state = TaskState.Completed
            }
            is WsMessage.Error -> {
                task.error = msg.message
                task.state = TaskState.Failed
            }
            // Ignore other message types (Connected, AvailableAgents, etc.)
            else -> {}
        }
    }
}
