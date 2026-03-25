package com.jamesward.acpgateway.mcp

import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Abstraction for executing prompts against an ACP agent.
 * Implementations handle the differences between dev mode (direct GatewaySession)
 * and relay mode (forwarding through RelaySession WebSocket).
 */
interface TaskExecutor {
    /**
     * Send a prompt to the agent and return a channel of WsMessages.
     * The channel produces messages until TurnComplete (or an error).
     */
    suspend fun executePrompt(prompt: String): ReceiveChannel<WsMessage>

    /** Cancel the currently running prompt. */
    suspend fun cancel()

    /** Respond to a pending permission request. */
    suspend fun respondToPermission(toolCallId: String, optionId: String)
}
