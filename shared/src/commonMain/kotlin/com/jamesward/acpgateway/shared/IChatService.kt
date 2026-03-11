package com.jamesward.acpgateway.shared

import dev.kilua.rpc.annotations.RpcService
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

@RpcService
interface IChatService {
    suspend fun chat(input: ReceiveChannel<WsMessage>, output: SendChannel<WsMessage>) {}
    suspend fun chat(handler: suspend (SendChannel<WsMessage>, ReceiveChannel<WsMessage>) -> Unit) {}
}
