package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.Css
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.PermissionKind
import com.jamesward.acpgateway.shared.PermissionOptionInfo
import com.jamesward.acpgateway.shared.Swap
import com.jamesward.acpgateway.shared.ToolStatus
import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.serialization.json.Json
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val msg: WsMessage = WsMessage.AgentText("chunk")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"agent_text\""))
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.AgentText>(decoded)
        assertEquals("chunk", decoded.text)
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
        assertNull(decoded.renderedHtml)
    }

    @Test
    fun turnCompleteWithRenderedHtml() {
        val msg: WsMessage = WsMessage.TurnComplete("end_turn", "<p>Hello <strong>world</strong></p>\n")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.TurnComplete>(decoded)
        assertEquals("<p>Hello <strong>world</strong></p>\n", decoded.renderedHtml)
    }

    @Test
    fun markdownRendering() {
        val parser = Parser.builder().build()
        val renderer = HtmlRenderer.builder().build()
        fun render(md: String) = renderer.render(parser.parse(md))

        assertEquals("<p>Hello <strong>world</strong></p>\n", render("Hello **world**"))
        assertTrue(render("# Heading").contains("<h1>"))
        assertTrue(render("- item 1\n- item 2").contains("<li>"))
        assertTrue(render("`code`").contains("<code>"))
        assertTrue(render("```\nfoo\n```").contains("<pre>"))
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
    fun htmlUpdateRoundTrip() {
        val msg: WsMessage = WsMessage.HtmlUpdate(Css.MESSAGES, Swap.BeforeEnd, "<div>hello</div>")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"html_update\""))
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.HtmlUpdate>(decoded)
        assertEquals(Css.MESSAGES, decoded.target)
        assertEquals(Swap.BeforeEnd, decoded.swap)
        assertEquals("<div>hello</div>", decoded.html)
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
}
