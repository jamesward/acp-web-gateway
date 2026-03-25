package com.jamesward.acpgateway.mcp

import com.jamesward.acpgateway.shared.WsMessage
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Provides access to a relay session's state for the MCP executor.
 * Mirrors the relevant parts of RelaySession without depending on the server module.
 */
interface RelaySessionAccess {
    val backendWs: WebSocketSession?
    val rpcChannels: MutableSet<SendChannel<WsMessage>>
    val messageCache: java.util.Queue<String>
}

/**
 * TaskExecutor for relay/proxy mode — sends prompts through the relay WebSocket
 * and collects responses from the rpcChannels.
 */
class RelayTaskExecutor(
    private val relayAccess: RelaySessionAccess,
    private val scope: CoroutineScope,
) : TaskExecutor {

    private val logger = LoggerFactory.getLogger(RelayTaskExecutor::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun executePrompt(prompt: String): ReceiveChannel<WsMessage> {
        val backend = relayAccess.backendWs
            ?: throw IllegalStateException("No CLI backend connected")

        val output = Channel<WsMessage>(Channel.UNLIMITED)

        // Register our channel to receive relayed messages from the CLI backend
        relayAccess.rpcChannels.add(output)

        // Send the prompt to the CLI backend via the relay WebSocket
        // Note: the CLI backend will broadcast UserMessage back through the relay,
        // and Server.kt will cache it and forward to all rpcChannels.
        scope.launch {
            try {
                val promptMsg = WsMessage.Prompt(text = prompt)
                val encoded = json.encodeToString(WsMessage.serializer(), promptMsg)
                backend.send(Frame.Text(encoded))
            } catch (e: Exception) {
                logger.error("Failed to send prompt via relay", e)
                try { output.send(WsMessage.Error(e.message ?: "Failed to send prompt")) } catch (_: Exception) {}
                relayAccess.rpcChannels.remove(output)
                output.close()
            }
        }

        return output
    }

    override suspend fun cancel() {
        val backend = relayAccess.backendWs ?: return
        try {
            val cancelMsg = WsMessage.Cancel
            val encoded = json.encodeToString(WsMessage.serializer(), cancelMsg)
            backend.send(Frame.Text(encoded))
        } catch (e: Exception) {
            logger.debug("Cancel via relay failed", e)
        }
    }

    override suspend fun respondToPermission(toolCallId: String, optionId: String) {
        val backend = relayAccess.backendWs ?: return
        try {
            val responseMsg = WsMessage.PermissionResponse(toolCallId = toolCallId, optionId = optionId)
            val encoded = json.encodeToString(WsMessage.serializer(), responseMsg)
            backend.send(Frame.Text(encoded))
        } catch (e: Exception) {
            logger.debug("Permission response via relay failed", e)
        }
    }
}
