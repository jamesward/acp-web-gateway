package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

// ---- Messages Area ----

@Composable
internal fun IComponent.messagesStyles() {
    globalStyle(selector = "#messages") {
        setStyle("flex", "1")
        overflowY = Overflow.Auto
        overflowX = Overflow.Hidden
        padding = 16.px
        display = Display.Flex
        flexDirection = FlexDirection.Column
        setStyle("gap", "12px")
        setStyle("scroll-behavior", "smooth")
        minWidth = 0.px
    }
    globalStyle(selector = "#messages::-webkit-scrollbar") {
        width = 6.px
    }
    globalStyle(selector = "#messages::-webkit-scrollbar-track") {
        background = Background(color = Color("transparent"))
    }
    globalStyle(selector = "#messages::-webkit-scrollbar-thumb") {
        background = Background(color = borderSubtle)
        borderRadius = 3.px
    }
    globalStyle(selector = "#messages::-webkit-scrollbar-thumb:hover") {
        background = Background(color = textMuted)
    }
}

// ---- Message Bubbles ----

@Composable
internal fun IComponent.messageBubbleStyles() {
    globalStyle(selector = ".msg") {
        maxWidth = 85.perc
        minWidth = 0.px
        overflow = Overflow.Hidden
        flexShrink = 0
        setStyle("animation", "msg-in 0.15s ease-out")
    }

    // User messages
    globalStyle(selector = ".msg-user") {
        alignSelf = AlignItems.FlexEnd
    }
    globalStyle(selector = ".msg-user .msg-content") {
        background = Background(color = bgUserMsg)
        color = textOnBlue
        setStyle("padding", "10px 16px")
        setStyle("border-radius", "12px 12px 4px 12px")
        fontSize = 14.px
        lineHeight = 1.5.units
        whiteSpace = WhiteSpace.PreWrap
        setStyle("word-break", "break-word")
    }

    // File attachments in user messages
    globalStyle(selector = ".msg-files") {
        display = Display.Flex
        setStyle("flex-wrap", "wrap")
        setStyle("gap", "4px")
        marginTop = 6.px
    }
    globalStyle(selector = ".msg-file-tag") {
        display = Display.InlineFlex
        alignItems = AlignItems.Center
        background = Background(color = fileTagBg)
        borderRadius = 4.px
        setStyle("padding", "2px 8px")
        fontSize = 12.px
        color = fileTagColor
    }

    // Assistant messages
    globalStyle(selector = ".msg-assistant") {
        alignSelf = AlignItems.FlexStart
        width = 85.perc
    }
    globalStyle(selector = ".msg-assistant > details") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderLeft = Border(3.px, BorderStyle.Solid, accentBlue)
        borderRadius = radiusLg
        overflow = Overflow.Hidden
    }
    globalStyle(selector = ".msg-assistant > details > summary") {
        setStyle("padding", "8px 14px")
        cursor = Cursor.Pointer
        color = textSecondary
        fontSize = 12.px
        setStyle("user-select", "none")
        setStyle("list-style", "none")
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "6px")
        borderBottom = Border(1.px, BorderStyle.Solid, borderSubtle)
    }
    globalStyle(selector = ".msg-assistant > details > summary::-webkit-details-marker") {
        display = Display.None
    }
    globalStyle(selector = ".msg-assistant > details > summary::before") {
        setStyle("content", "\"\\25B6\"")
        fontSize = 8.px
        transition = Transition("transform", 150.milliseconds)
        color = textMuted
    }
    globalStyle(selector = ".msg-assistant > details[open] > summary::before") {
        setStyle("transform", "rotate(90deg)")
    }
    globalStyle(selector = ".msg-assistant .msg-body") {
        setStyle("padding", "12px 16px")
        color = textPrimary
        fontSize = 14.px
        lineHeight = 1.6.units
    }
    globalStyle(selector = ".agent-image") {
        setStyle("max-width", "100%")
        setStyle("height", "auto")
        setStyle("border-radius", "8px")
        setStyle("display", "block")
        setStyle("margin", "12px 0")
    }

    // Thought messages
    globalStyle(selector = ".msg-thought") {
        alignSelf = AlignItems.FlexStart
        width = 85.perc
    }
    globalStyle(selector = ".msg-thought > details") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderLeft = Border(3.px, BorderStyle.Solid, accentYellow)
        borderRadius = radiusLg
        overflow = Overflow.Hidden
    }
    globalStyle(selector = ".msg-thought > details > summary") {
        setStyle("padding", "8px 14px")
        cursor = Cursor.Pointer
        color = textSecondary
        fontSize = 12.px
        setStyle("user-select", "none")
        setStyle("list-style", "none")
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "6px")
        borderBottom = Border(1.px, BorderStyle.Solid, borderSubtle)
    }
    globalStyle(selector = ".msg-thought > details > summary::-webkit-details-marker") {
        display = Display.None
    }
    globalStyle(selector = ".msg-thought > details > summary::before") {
        setStyle("content", "\"\\25B6\"")
        fontSize = 8.px
        transition = Transition("transform", 150.milliseconds)
        color = textMuted
    }
    globalStyle(selector = ".msg-thought > details[open] > summary::before") {
        setStyle("transform", "rotate(90deg)")
    }
    globalStyle(selector = ".msg-thought .msg-body") {
        setStyle("padding", "12px 16px")
        color = textSecondary
        fontSize = 14.px
        lineHeight = 1.6.units
        fontStyle = FontStyle.Italic
    }
}
