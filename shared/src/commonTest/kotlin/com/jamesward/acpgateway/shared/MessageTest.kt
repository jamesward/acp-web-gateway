package com.jamesward.acpgateway.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageTest {

    private val json = Json

    @Test
    fun promptRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt("hello")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("hello", decoded.text)
    }

    @Test
    fun agentTextRoundTrip() {
        val msg: WsMessage = WsMessage.AgentText("response chunk")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentText>(decoded)
        assertEquals("response chunk", decoded.text)
    }

    @Test
    fun permissionRequestRoundTrip() {
        val msg: WsMessage = WsMessage.PermissionRequest(
            toolCallId = "tc-1",
            title = "Read file",
            options = listOf(
                PermissionOptionInfo("allow", "Allow", "allow_once"),
                PermissionOptionInfo("deny", "Deny", "reject_once"),
            ),
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.PermissionRequest>(decoded)
        assertEquals("tc-1", decoded.toolCallId)
        assertEquals(2, decoded.options.size)
    }

    @Test
    fun connectedRoundTrip() {
        val msg: WsMessage = WsMessage.Connected("test-agent", "1.0.0")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Connected>(decoded)
        assertEquals("test-agent", decoded.agentName)
    }

    @Test
    fun chatEntryRoundTrip() {
        val entry = ChatEntry("user", "hello", 1234567890L)
        val encoded = json.encodeToString(ChatEntry.serializer(), entry)
        val decoded = json.decodeFromString(ChatEntry.serializer(), encoded)
        assertEquals(entry, decoded)
    }
}
