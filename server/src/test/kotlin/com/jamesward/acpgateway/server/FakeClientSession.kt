@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.jamesward.acpgateway.server

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

class FakeClientSession : ClientSession {
    override val sessionId = SessionId("fake-session")
    override val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())
    override val client: Client get() = error("Not available in test")
    override val operations: ClientSessionOperations get() = error("Not available in test")

    val promptHistory = mutableListOf<List<ContentBlock>>()

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> {
        promptHistory.add(content)
        return kotlinx.coroutines.flow.flowOf(
            Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN))
        )
    }
    override suspend fun cancel() {}

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
