package com.jamesward.acpgateway.shared

import kotlinx.serialization.json.Json
import kotlin.test.*

class WsMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun promptRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt("hello world")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"prompt\""))
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("hello world", decoded.text)
        assertNull(decoded.screenshot)
    }

    @Test
    fun promptWithScreenshotRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt("hello world", "iVBORw0KGgo=")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("hello world", decoded.text)
        assertEquals("iVBORw0KGgo=", decoded.screenshot)
    }

    @Test
    fun agentTextRoundTrip() {
        val msg: WsMessage = WsMessage.AgentText(msgId = "msg-1", markdown = "chunk")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"agent_text\""))
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentText>(decoded)
        assertEquals("msg-1", decoded.msgId)
        assertEquals("chunk", decoded.markdown)
    }

    @Test
    fun agentTextWithUsage() {
        val msg: WsMessage = WsMessage.AgentText(msgId = "msg-1", markdown = "hi", usage = "10K / 100K tokens (10%)")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentText>(decoded)
        assertEquals("10K / 100K tokens (10%)", decoded.usage)
    }

    @Test
    fun agentThoughtRoundTrip() {
        val msg: WsMessage = WsMessage.AgentThought(thoughtId = "thought-1", markdown = "thinking...")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"agent_thought\""))
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentThought>(decoded)
        assertEquals("thought-1", decoded.thoughtId)
        assertEquals("thinking...", decoded.markdown)
    }

    @Test
    fun connectedRoundTrip() {
        val msg: WsMessage = WsMessage.Connected("Claude Agent", "0.20.2")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Connected>(decoded)
        assertEquals("Claude Agent", decoded.agentName)
        assertEquals("0.20.2", decoded.agentVersion)
    }

    @Test
    fun permissionRequestRoundTrip() {
        val msg: WsMessage = WsMessage.PermissionRequest(
            toolCallId = "tc-123",
            title = "Read file /etc/passwd",
            options = listOf(
                PermissionOptionInfo("allow", "Allow Once", PermissionKind.AllowOnce),
                PermissionOptionInfo("deny", "Deny", PermissionKind.RejectOnce),
            ),
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.PermissionRequest>(decoded)
        assertEquals("tc-123", decoded.toolCallId)
        assertEquals(2, decoded.options.size)
        assertEquals(PermissionKind.AllowOnce, decoded.options[0].kind)
    }

    @Test
    fun permissionResponseRoundTrip() {
        val msg: WsMessage = WsMessage.PermissionResponse("tc-123", "allow")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.PermissionResponse>(decoded)
        assertEquals("tc-123", decoded.toolCallId)
        assertEquals("allow", decoded.optionId)
    }

    @Test
    fun turnCompleteRoundTrip() {
        val msg: WsMessage = WsMessage.TurnComplete("end_turn")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.TurnComplete>(decoded)
        assertEquals("end_turn", decoded.stopReason)
    }

    @Test
    fun errorRoundTrip() {
        val msg: WsMessage = WsMessage.Error("something went wrong")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Error>(decoded)
        assertEquals("something went wrong", decoded.message)
    }

    @Test
    fun promptWithFilesRoundTrip() {
        val files = listOf(
            FileAttachment("image.png", "image/png", "iVBORw0KGgo="),
            FileAttachment("data.csv", "text/csv", "bmFtZSxhZ2U="),
        )
        val msg: WsMessage = WsMessage.Prompt("analyze these", files = files)
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("analyze these", decoded.text)
        assertEquals(2, decoded.files.size)
        assertEquals("image.png", decoded.files[0].name)
        assertEquals("image/png", decoded.files[0].mimeType)
        assertEquals("data.csv", decoded.files[1].name)
    }

    @Test
    fun toolCallRoundTrip() {
        val msg: WsMessage = WsMessage.ToolCall("tc-1", "Read file", ToolStatus.InProgress)
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.ToolCall>(decoded)
        assertEquals("tc-1", decoded.toolCallId)
        assertEquals("Read file", decoded.title)
        assertEquals(ToolStatus.InProgress, decoded.status)
    }

    @Test
    fun toolCallWithContent() {
        val msg: WsMessage = WsMessage.ToolCall(
            "tc-1", "Read file", ToolStatus.Completed,
            content = "file contents here",
            kind = ToolKind.Read,
            location = "/src/main.kt",
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.ToolCall>(decoded)
        assertEquals("file contents here", decoded.content)
        assertEquals(ToolKind.Read, decoded.kind)
        assertEquals("/src/main.kt", decoded.location)
    }

    @Test
    fun userMessageRoundTrip() {
        val msg: WsMessage = WsMessage.UserMessage("hello from user")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.UserMessage>(decoded)
        assertEquals("hello from user", decoded.text)
    }
}
