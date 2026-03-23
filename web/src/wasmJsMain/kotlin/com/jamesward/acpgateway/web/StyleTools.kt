package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

// ---- Tool Calls ----

@Composable
internal fun IComponent.toolStyles() {
    globalStyle(selector = ".msg-tools") {
        alignSelf = AlignItems.FlexStart
        width = 85.perc
    }
    globalStyle(selector = ".msg-tools > details") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderLeft = Border(3.px, BorderStyle.Solid, accentGreen)
        borderRadius = radiusLg
        overflow = Overflow.Hidden
    }
    globalStyle(selector = ".msg-tools > details > summary") {
        setStyle("padding", "8px 14px")
        cursor = Cursor.Pointer
        color = textSecondary
        fontSize = 13.px
        setStyle("user-select", "none")
        setStyle("list-style", "none")
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "4px")
    }
    globalStyle(selector = ".msg-tools > details > summary::-webkit-details-marker") {
        display = Display.None
    }
    globalStyle(selector = ".msg-tools > details > summary .tool-summary-label") {
        setStyle("flex", "1")
    }
    globalStyle(selector = ".msg-tools > details > summary .tool-summary-active") {
        color = textMuted
        fontSize = 12.px
    }
    globalStyle(selector = ".msg-tools > details > summary::before") {
        setStyle("content", "\"\\25B6\"")
        fontSize = 9.px
        color = textMuted
        transition = Transition("transform", 150.milliseconds)
    }
    globalStyle(selector = ".msg-tools > details[open] > summary::before") {
        setStyle("transform", "rotate(90deg)")
    }
    globalStyle(selector = ".msg-tools .tools-list") {
        borderTop = Border(1.px, BorderStyle.Solid, borderSubtle)
    }
    globalStyle(selector = ".tool-item") {
        setStyle("padding", "6px 14px")
        display = Display.Flex
        alignItems = AlignItems.Baseline
        setStyle("gap", "6px")
        fontSize = 13.px
        borderBottom = Border(1.px, BorderStyle.Solid, borderSubtle)
    }
    globalStyle(selector = ".tool-item:last-child") {
        borderBottom = Border(style = BorderStyle.None)
    }
    globalStyle(selector = "details.tool-item") {
        display = Display.Block
    }
    globalStyle(selector = "details.tool-item > summary") {
        display = Display.Flex
        alignItems = AlignItems.Baseline
        setStyle("gap", "6px")
        cursor = Cursor.Pointer
        setStyle("list-style", "none")
        setStyle("user-select", "none")
    }
    globalStyle(selector = "details.tool-item > summary::-webkit-details-marker") {
        display = Display.None
    }
    globalStyle(selector = "details.tool-item > summary::before") {
        setStyle("content", "\"\\25B6\"")
        fontSize = 8.px
        color = textMuted
        transition = Transition("transform", 150.milliseconds)
    }
    globalStyle(selector = "details.tool-item[open] > summary::before") {
        setStyle("transform", "rotate(90deg)")
    }
    globalStyle(selector = ".tool-icon-ok") {
        color = accentGreen
    }
    globalStyle(selector = ".tool-icon-fail") {
        color = accentRed
    }
    globalStyle(selector = ".tool-icon-pending") {
        color = textMuted
    }
    globalStyle(selector = ".tool-name") {
        color = textPrimary
    }
    globalStyle(selector = ".tool-location") {
        color = textMuted
        fontSize = 12.px
    }
    globalStyle(selector = ".tool-content") {
        setStyle("padding", "4px 14px 8px 26px")
        fontSize = 12.px
        color = textSecondary
    }
    globalStyle(selector = ".tool-content pre") {
        background = Background(color = bgBody)
        padding = 8.px
        borderRadius = radius
        overflowX = Overflow.Auto
        fontFamily = FONT_MONO
        fontSize = 12.px
        whiteSpace = WhiteSpace.PreWrap
        wordBreak = WordBreak.BreakAll
    }
}

// ---- Plan View ----

@Composable
internal fun IComponent.planStyles() {
    globalStyle(selector = ".plan-view") {
        alignSelf = AlignItems.FlexStart
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        setStyle("padding", "12px 16px")
        setStyle("margin", "0")
        maxWidth = 700.px
        width = 100.perc
    }
    globalStyle(selector = ".plan-entry") {
        display = Display.Flex
        alignItems = AlignItems.FlexStart
        setStyle("gap", "8px")
        setStyle("padding", "4px 0")
        fontSize = 14.px
        color = textSecondary
    }
    globalStyle(selector = ".plan-icon") {
        flexShrink = 0
        width = 18.px
        textAlign = TextAlign.Center
        fontSize = 13.px
    }
    globalStyle(selector = ".plan-content") {
        setStyle("flex", "1")
    }
    globalStyle(selector = ".plan-completed .plan-icon") {
        color = accentGreen
    }
    globalStyle(selector = ".plan-in-progress .plan-icon") {
        color = accentBlue
    }
    globalStyle(selector = ".plan-in-progress .plan-content") {
        color = textPrimary
        setStyle("font-weight", "500")
    }
    globalStyle(selector = ".plan-pending .plan-icon") {
        color = textMuted
    }
    globalStyle(selector = ".plan-completed .plan-content") {
        color = textMuted
    }
}
