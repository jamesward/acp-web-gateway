package com.jamesward.acpgateway.web

import com.jamesward.acpgateway.shared.WsMessage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppTest {

    private val json = Json

    @Test
    fun promptSerialization() {
        val msg: WsMessage = WsMessage.Prompt("hello")
        val encoded = json.encodeToString(WsMessage.serializer(), msg)
        val decoded = json.decodeFromString(WsMessage.serializer(), encoded)
        assertIs<WsMessage.Prompt>(decoded)
        assertEquals("hello", decoded.text)
    }
}
