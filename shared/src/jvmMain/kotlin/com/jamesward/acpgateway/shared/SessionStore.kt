package com.jamesward.acpgateway.shared

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of a turn's in-progress state for replay to reconnecting clients.
 */
data class TurnState(
    val thoughtId: String,
    val msgId: String,
    val toolBlockId: String,
    val thoughtText: String,
    val responseText: String,
    val toolEntries: List<ToolCallDisplay>,
    val planEntries: List<PlanEntryInfo> = emptyList(),
)

interface SessionStore {
    suspend fun addHistory(sessionId: UUID, entry: ChatEntry)
    suspend fun getHistory(sessionId: UUID): List<ChatEntry>

    suspend fun setToolCall(sessionId: UUID, id: String, info: ToolCallInfo)
    suspend fun removeToolCall(sessionId: UUID, id: String)
    suspend fun clearToolCalls(sessionId: UUID)
    suspend fun getToolCalls(sessionId: UUID): Map<String, ToolCallInfo>

    suspend fun setPromptStartTime(sessionId: UUID, time: Long)
    suspend fun getPromptStartTime(sessionId: UUID): Long

    suspend fun setTurnState(sessionId: UUID, state: TurnState)
    suspend fun clearTurnState(sessionId: UUID)
    suspend fun getTurnState(sessionId: UUID): TurnState?
}

class InMemorySessionStore : SessionStore {
    private val histories = ConcurrentHashMap<UUID, MutableList<ChatEntry>>()
    private val toolCalls = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ToolCallInfo>>()
    private val promptStartTimes = ConcurrentHashMap<UUID, AtomicLong>()
    private val turnStates = ConcurrentHashMap<UUID, TurnState>()

    override suspend fun addHistory(sessionId: UUID, entry: ChatEntry) {
        histories.getOrPut(sessionId) { mutableListOf() }.add(entry)
    }

    override suspend fun getHistory(sessionId: UUID): List<ChatEntry> {
        return histories[sessionId]?.toList() ?: emptyList()
    }

    override suspend fun setToolCall(sessionId: UUID, id: String, info: ToolCallInfo) {
        toolCalls.getOrPut(sessionId) { ConcurrentHashMap() }[id] = info
    }

    override suspend fun removeToolCall(sessionId: UUID, id: String) {
        toolCalls[sessionId]?.remove(id)
    }

    override suspend fun clearToolCalls(sessionId: UUID) {
        toolCalls[sessionId]?.clear()
    }

    override suspend fun getToolCalls(sessionId: UUID): Map<String, ToolCallInfo> {
        return toolCalls[sessionId]?.toMap() ?: emptyMap()
    }

    override suspend fun setPromptStartTime(sessionId: UUID, time: Long) {
        promptStartTimes.getOrPut(sessionId) { AtomicLong(0L) }.set(time)
    }

    override suspend fun getPromptStartTime(sessionId: UUID): Long {
        return promptStartTimes[sessionId]?.get() ?: 0L
    }

    override suspend fun setTurnState(sessionId: UUID, state: TurnState) {
        turnStates[sessionId] = state
    }

    override suspend fun clearTurnState(sessionId: UUID) {
        turnStates.remove(sessionId)
    }

    override suspend fun getTurnState(sessionId: UUID): TurnState? {
        return turnStates[sessionId]
    }
}
