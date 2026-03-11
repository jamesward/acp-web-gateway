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
        val msg: WsMessage = WsMessage.AgentText(msgId = "msg-1", markdown = "response chunk")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentText>(decoded)
        assertEquals("msg-1", decoded.msgId)
        assertEquals("response chunk", decoded.markdown)
    }

    @Test
    fun permissionRequestRoundTrip() {
        val msg: WsMessage = WsMessage.PermissionRequest(
            toolCallId = "tc-1",
            title = "Read file",
            options = listOf(
                PermissionOptionInfo("allow", "Allow", PermissionKind.AllowOnce),
                PermissionOptionInfo("deny", "Deny", PermissionKind.RejectOnce),
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
    fun promptWithFilesRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt(
            text = "analyze this",
            files = listOf(
                FileAttachment("doc.pdf", "application/pdf", "cGRmZGF0YQ=="),
                FileAttachment("photo.png", "image/png", "iVBORw0KGgo="),
            ),
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("analyze this", decoded.text)
        assertEquals(2, decoded.files.size)
        assertEquals("doc.pdf", decoded.files[0].name)
        assertEquals("application/pdf", decoded.files[0].mimeType)
        assertEquals("cGRmZGF0YQ==", decoded.files[0].data)
        assertEquals("photo.png", decoded.files[1].name)
        assertEquals("image/png", decoded.files[1].mimeType)
        assertEquals("iVBORw0KGgo=", decoded.files[1].data)
    }

    @Test
    fun promptWithScreenshotRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt(
            text = "describe this page",
            screenshot = "c2NyZWVuc2hvdA==",
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("describe this page", decoded.text)
        assertEquals("c2NyZWVuc2hvdA==", decoded.screenshot)
        assertEquals(0, decoded.files.size)
    }

    @Test
    fun promptWithScreenshotAndFilesRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt(
            text = "compare",
            screenshot = "c2NyZWVuc2hvdA==",
            files = listOf(FileAttachment("chart.png", "image/png", "Y2hhcnQ=")),
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("compare", decoded.text)
        assertEquals("c2NyZWVuc2hvdA==", decoded.screenshot)
        assertEquals(1, decoded.files.size)
        assertEquals("chart.png", decoded.files[0].name)
    }

    @Test
    fun promptWithFilesAndNoTextRoundTrip() {
        val msg: WsMessage = WsMessage.Prompt(
            text = "",
            files = listOf(FileAttachment("data.csv", "text/csv", "bmFtZSxhZ2U=")),
        )
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("", decoded.text)
        assertEquals(1, decoded.files.size)
    }

    @Test
    fun chatEntryRoundTrip() {
        val entry = ChatEntry("user", "hello", 1234567890L)
        val encoded = json.encodeToString(ChatEntry.serializer(), entry)
        val decoded = json.decodeFromString(ChatEntry.serializer(), encoded)
        assertEquals(entry, decoded)
    }
}
