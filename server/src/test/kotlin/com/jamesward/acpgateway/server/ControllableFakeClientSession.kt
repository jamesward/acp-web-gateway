@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.server

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentLinkedQueue

open class ControllableFakeClientSession : ClientSession {
    override val sessionId = SessionId("fake-controllable-session")
    override val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())
    override val client: Client get() = error("Not available in test")
    override val operations: ClientSessionOperations get() = error("Not available in test")

    val promptHistory = mutableListOf<List<ContentBlock>>()

    private val responseQueue = ConcurrentLinkedQueue<suspend () -> Flow<Event>>()

    @Volatile
    var cancelled = false
        private set

    fun enqueueTextResponse(text: String) {
        responseQueue.add {
            flow {
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))))
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
    }

    fun enqueueDelayedResponse(gate: CompletableDeferred<List<Event>>) {
        responseQueue.add {
            flow {
                val events = gate.await()
                for (event in events) {
                    emit(event)
                }
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
            }
        }
    }

    fun enqueueResponse(producer: suspend () -> Flow<Event>) {
        responseQueue.add(producer)
    }

    override open suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> {
        promptHistory.add(content)
        val producer = responseQueue.poll()
        return if (producer != null) {
            producer()
        } else {
            flowOf(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
        }
    }

    override suspend fun cancel() {
        cancelled = true
    }

    override val modesSupported = false
    override val availableModes: List<SessionMode> = emptyList()
    override val currentMode: StateFlow<SessionModeId> = MutableStateFlow(SessionModeId("default"))
    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?) = SetSessionModeResponse()

    override val modelsSupported = false
    override val availableModels: List<ModelInfo> = emptyList()
    override val currentModel: StateFlow<ModelId> = MutableStateFlow(ModelId("default"))
    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?) = SetSessionModelResponse()

    override val configOptionsSupported = false
    override val configOptions: StateFlow<List<SessionConfigOption>> = MutableStateFlow(emptyList())
    override suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement?) = SetSessionConfigOptionResponse(emptyList())
}
