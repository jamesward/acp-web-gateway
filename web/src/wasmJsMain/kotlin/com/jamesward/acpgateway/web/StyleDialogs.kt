package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

// ---- Permission Dialog ----

@Composable
internal fun IComponent.permissionDialogStyles() {
    globalStyle(selector = ".permission-bar") {
        position = Position.Sticky
        setStyle("bottom", "0")
        background = Background(color = bgCard)
        borderTop = Border(1.px, BorderStyle.Solid, borderSubtle)
        padding = 16.px
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "12px")
        zIndex = 100
        setStyle("flex-wrap", "wrap")
    }
    globalStyle(selector = ".permission-bar .perm-title") {
        setStyle("flex", "1 1 auto")
        setStyle("min-width", "0")
    }
    globalStyle(selector = ".permission-bar h3") {
        fontSize = 14.px
        margin = 0.px
        color = textPrimary
    }
    globalStyle(selector = ".permission-bar p") {
        color = textSecondary
        fontSize = 13.px
        margin = 0.px
        lineHeight = 1.4.units
    }
    globalStyle(selector = ".permission-bar .perm-description") {
        overflow = Overflow.Auto
        maxHeight = 40.vh
        background = Background(color = bgBody)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        padding = 12.px
        fontSize = 13.px
        color = textSecondary
        lineHeight = 1.5.units
        setStyle("flex-basis", "100%")
    }
    globalStyle(selector = ".permission-bar .perm-actions") {
        display = Display.Flex
        setStyle("gap", "8px")
        setStyle("flex-wrap", "wrap")
        setStyle("flex", "1 1 auto")
    }
    globalStyle(selector = ".permission-bar button") {
        setStyle("padding", "6px 16px")
        borderRadius = radius
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = bgCardHover)
        color = textPrimary
        fontSize = 13.px
        cursor = Cursor.Pointer
        transitionList = listOf(
            Transition("background", 150.milliseconds),
            Transition("border-color", 150.milliseconds),
        )
    }
    globalStyle(selector = ".permission-bar button:hover") {
        background = Background(color = borderSubtle)
    }
    globalStyle(selector = ".permission-bar button:first-child") {
        background = Background(color = accentGreen)
        setStyle("border-color", "var(--accent-green)")
        color = Color("#000000")
        setStyle("font-weight", "600")
    }
}

// ---- Markdown Content ----

@Composable
internal fun IComponent.markdownStyles() {
    globalStyle(selector = ".msg-body h1, .msg-body h2, .msg-body h3, .msg-body h4, .msg-body h5, .msg-body h6") {
        marginTop = 16.px
        marginBottom = 8.px
        setStyle("font-weight", "600")
        color = textPrimary
    }
    globalStyle(selector = ".msg-body h1") {
        fontSize = 1.4.em
    }
    globalStyle(selector = ".msg-body h2") {
        fontSize = 1.2.em
    }
    globalStyle(selector = ".msg-body h3") {
        fontSize = 1.1.em
    }
    globalStyle(selector = ".msg-body p") {
        marginBottom = 8.px
    }
    globalStyle(selector = ".msg-body p:last-child") {
        marginBottom = 0.px
    }
    globalStyle(selector = ".msg-body ul, .msg-body ol") {
        setStyle("margin", "8px 0")
        setStyle("padding-left", "24px")
    }
    globalStyle(selector = ".msg-body li") {
        marginBottom = 4.px
    }
    globalStyle(selector = ".msg-body code") {
        background = Background(color = codeBg)
        setStyle("padding", "2px 6px")
        borderRadius = 4.px
        fontFamily = FONT_MONO
        fontSize = 0.9.em
    }
    globalStyle(selector = ".msg-body pre") {
        background = Background(color = bgBody)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        padding = 12.px
        setStyle("margin", "8px 0")
        overflowX = Overflow.Auto
        fontFamily = FONT_MONO
        fontSize = 13.px
        lineHeight = 1.45.units
    }
    globalStyle(selector = ".msg-body pre code") {
        setStyle("background", "none")
        padding = 0.px
        borderRadius = 0.px
    }
    globalStyle(selector = ".msg-body blockquote") {
        borderLeft = Border(3.px, BorderStyle.Solid, borderSubtle)
        setStyle("padding-left", "12px")
        color = textSecondary
        setStyle("margin", "8px 0")
    }
    globalStyle(selector = ".msg-body a") {
        color = accentBlue
        textDecoration = TextDecoration(line = TextDecorationLine.None)
    }
    globalStyle(selector = ".msg-body a:hover") {
        textDecoration = TextDecoration(line = TextDecorationLine.Underline)
    }
    globalStyle(selector = ".msg-body table") {
        setStyle("border-collapse", "collapse")
        width = 100.perc
        setStyle("margin", "8px 0")
    }
    globalStyle(selector = ".msg-body th, .msg-body td") {
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        setStyle("padding", "6px 12px")
        textAlign = TextAlign.Left
        fontSize = 13.px
    }
    globalStyle(selector = ".msg-body th") {
        background = Background(color = bgCardHover)
    }
}
