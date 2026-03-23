package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

// ---- Error Messages ----

@Composable
internal fun IComponent.errorStyles() {
    globalStyle(selector = ".msg-error") {
        alignSelf = AlignItems.FlexStart
        background = Background(color = errorBg)
        border = Border(1.px, BorderStyle.Solid, accentRed)
        borderRadius = radiusLg
        setStyle("padding", "10px 16px")
        color = accentRedHover
        fontSize = 14.px
    }
}

// ---- Status Timer ----

@Composable
internal fun IComponent.statusTimerStyles() {
    globalStyle(selector = ".status-timer") {
        textAlign = TextAlign.Center
        padding = 4.px
        color = textMuted
        fontSize = 12.px
        flexShrink = 0
    }
}

// ---- Scroll-to-bottom ----

@Composable
internal fun IComponent.scrollButtonStyles() {
    globalStyle(selector = ".scroll-btn") {
        position = Position.Absolute
        bottom = 110.px
        left = 50.perc
        setStyle("transform", "translateX(-50%)")
        width = 36.px
        height = 36.px
        borderRadius = 50.perc
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = bgCard)
        color = textSecondary
        cursor = Cursor.Pointer
        display = Display.Flex
        alignItems = AlignItems.Center
        justifyContent = JustifyContent.Center
        fontSize = 18.px
        zIndex = 10
        transitionList = listOf(
            Transition("background", 150.milliseconds),
            Transition("border-color", 150.milliseconds),
        )
        boxShadow = BoxShadow(0.px, 2.px, 8.px, color = shadowColor)
    }
    globalStyle(selector = ".scroll-btn:hover") {
        background = Background(color = bgCardHover)
        setStyle("border-color", "var(--text-secondary)")
    }
}

// ---- Input Bar ----

@Composable
internal fun IComponent.inputBarStyles() {
    globalStyle(selector = ".input-bar") {
        flexShrink = 0
        setStyle("padding", "8px 16px 12px")
        background = Background(color = bgHeader)
        borderTop = Border(1.px, BorderStyle.Solid, borderSubtle)
        maxWidth = 100.perc
        overflowX = Overflow.Hidden
    }
    globalStyle(selector = ".file-preview") {
        display = Display.Flex
        setStyle("flex-wrap", "wrap")
        setStyle("gap", "6px")
        marginBottom = 6.px
    }
    globalStyle(selector = ".file-chip") {
        display = Display.InlineFlex
        alignItems = AlignItems.Center
        setStyle("gap", "4px")
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        setStyle("padding", "2px 8px")
        fontSize = 12.px
        color = textSecondary
    }
    globalStyle(selector = ".file-chip button") {
        setStyle("background", "none")
        border = Border(style = BorderStyle.None)
        color = textMuted
        cursor = Cursor.Pointer
        fontSize = 14.px
        setStyle("padding", "0 2px")
        lineHeight = 1.units
    }
    globalStyle(selector = ".file-chip button:hover") {
        color = accentRed
    }

    // Slash command buttons
    globalStyle(selector = ".command-buttons") {
        display = Display.Flex
        setStyle("flex-wrap", "wrap")
        setStyle("gap", "6px")
        marginBottom = 6.px
    }
    globalStyle(selector = ".command-btn") {
        setStyle("padding", "4px 12px")
        borderRadius = radius
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = bgCard)
        color = accentBlue
        fontFamily = FONT_MONO
        fontSize = 12.px
        cursor = Cursor.Pointer
        transitionList = listOf(
            Transition("background", 100.milliseconds),
            Transition("border-color", 100.milliseconds),
        )
        whiteSpace = WhiteSpace.Nowrap
    }
    globalStyle(selector = ".command-btn:hover, .command-btn.selected") {
        background = Background(color = bgCardHover)
        setStyle("border-color", "var(--accent-blue)")
    }

    // File reference autocomplete
    globalStyle(selector = ".file-autocomplete") {
        display = Display.Flex
        flexDirection = FlexDirection.Column
        marginBottom = 6.px
        maxHeight = 240.px
        overflowY = Overflow.Auto
        overflowX = Overflow.Hidden
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        padding = 4.px
        width = 100.perc
    }
    globalStyle(selector = ".file-autocomplete::-webkit-scrollbar") {
        width = 6.px
    }
    globalStyle(selector = ".file-autocomplete::-webkit-scrollbar-track") {
        background = Background(color = Color("transparent"))
    }
    globalStyle(selector = ".file-autocomplete::-webkit-scrollbar-thumb") {
        background = Background(color = borderSubtle)
        borderRadius = 3.px
    }
    globalStyle(selector = ".file-ref-btn") {
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("padding", "6px 10px")
        borderRadius = 4.px
        border = Border(style = BorderStyle.None)
        background = Background(color = Color("transparent"))
        color = textPrimary
        fontFamily = FONT_MONO
        fontSize = 14.px
        lineHeight = 1.4.units
        cursor = Cursor.Pointer
        textAlign = TextAlign.Left
        whiteSpace = WhiteSpace.Nowrap
        transition = Transition("background", 100.milliseconds)
        width = 100.perc
        setStyle("min-width", "0")
    }
    globalStyle(selector = ".file-ref-name") {
        color = textPrimary
        setStyle("flex-shrink", "0")
        setStyle("font-weight", "600")
    }
    globalStyle(selector = ".file-ref-path") {
        color = textSecondary
        marginLeft = 8.px
        overflow = Overflow.Hidden
        textOverflow = TextOverflow.Ellipsis
        fontSize = 12.px
        setStyle("min-width", "0")
    }
    globalStyle(selector = ".file-ref-btn:hover, .file-ref-btn.selected") {
        background = Background(color = bgCardHover)
    }
    globalStyle(selector = ".file-chip.file-ref") {
        fontFamily = FONT_MONO
    }

    // Input row
    globalStyle(selector = ".input-row") {
        display = Display.Flex
        alignItems = AlignItems.FlexEnd
        setStyle("gap", "8px")
    }
    globalStyle(selector = ".input-row form") {
        display = Display.Flex
        alignItems = AlignItems.FlexEnd
        setStyle("gap", "8px")
        setStyle("flex", "1")
        minWidth = 0.px
        margin = 0.px
    }
    globalStyle(selector = ".input-row form textarea") {
        setStyle("flex", "1")
    }
    globalStyle(selector = ".input-row textarea") {
        setStyle("flex", "1")
        minWidth = 0.px
        background = Background(color = bgInput)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        setStyle("padding", "10px 14px")
        color = textPrimary
        fontFamily = FONT_SANS
        fontSize = 16.px
        lineHeight = 1.4.units
        resize = Resize.None
        outline = Outline(style = OutlineStyle.None)
        transition = Transition("border-color", 150.milliseconds)
    }
    globalStyle(selector = ".input-row textarea:focus") {
        setStyle("border-color", "var(--accent-blue)")
    }
    globalStyle(selector = ".input-row textarea::placeholder") {
        color = textMuted
    }
    globalStyle(selector = ".input-row textarea:disabled") {
        opacity = 0.5
    }

    // Input actions
    globalStyle(selector = ".input-actions") {
        display = Display.Flex
        flexDirection = FlexDirection.Column
        alignItems = AlignItems.FlexEnd
        setStyle("gap", "4px")
        flexShrink = 0
        minWidth = 0.px
    }
    globalStyle(selector = ".input-actions .btn-row") {
        display = Display.Flex
        setStyle("gap", "6px")
    }
    globalStyle(selector = ".btn-download-log") {
        setStyle("padding", "2px 8px")
        borderRadius = radius
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = Color("transparent"))
        color = textMuted
        fontSize = 11.px
        cursor = Cursor.Pointer
        whiteSpace = WhiteSpace.Nowrap
    }
    globalStyle(selector = ".btn-download-log:hover") {
        background = Background(color = bgCardHover)
        color = textSecondary
    }
}

// ---- Buttons ----

@Composable
internal fun IComponent.buttonStyles() {
    globalStyle(selector = ".btn-attach") {
        width = 36.px
        height = 36.px
        flexShrink = 0
        borderRadius = 50.perc
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = Color("transparent"))
        color = textSecondary
        fontSize = 18.px
        cursor = Cursor.Pointer
        display = Display.Flex
        alignItems = AlignItems.Center
        justifyContent = JustifyContent.Center
        transitionList = listOf(
            Transition("background", 150.milliseconds),
            Transition("color", 150.milliseconds),
            Transition("border-color", 150.milliseconds),
        )
        alignSelf = AlignItems.FlexEnd
        marginBottom = 2.px
    }
    globalStyle(selector = ".btn-attach:hover") {
        background = Background(color = bgCardHover)
        color = textPrimary
        setStyle("border-color", "var(--text-secondary)")
    }
    globalStyle(selector = ".btn-send") {
        setStyle("padding", "6px 20px")
        borderRadius = radius
        border = Border(style = BorderStyle.None)
        background = Background(color = accentBlue)
        color = Color.White
        fontSize = 13.px
        setStyle("font-weight", "600")
        cursor = Cursor.Pointer
        transition = Transition("opacity", 150.milliseconds)
    }
    globalStyle(selector = ".btn-send:hover") {
        opacity = 0.85
    }
    globalStyle(selector = ".btn-cancel") {
        setStyle("padding", "6px 20px")
        borderRadius = radius
        border = Border(style = BorderStyle.None)
        background = Background(color = accentRed)
        color = Color.White
        fontSize = 13.px
        setStyle("font-weight", "600")
        cursor = Cursor.Pointer
        transition = Transition("opacity", 150.milliseconds)
    }
    globalStyle(selector = ".btn-cancel:hover") {
        opacity = 0.85
    }
    globalStyle(selector = ".screenshot-label") {
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "4px")
        fontSize = 11.px
        color = textMuted
        cursor = Cursor.Pointer
    }
    globalStyle(selector = ".screenshot-label input[type=\"checkbox\"]") {
        setStyle("accent-color", "var(--accent-blue)")
    }
}
