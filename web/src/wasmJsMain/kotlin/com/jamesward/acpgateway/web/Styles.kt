package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.PClass
import dev.kilua.html.style.PElement
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

// ---- Theme Colors (CSS custom properties set by installTheme() in App.kt) ----

private fun v(name: String) = Color("var($name)")

private val bgBody = v("--bg-body")
private val bgHeader = v("--bg-header")
private val bgCard = v("--bg-card")
private val bgCardHover = v("--bg-card-hover")
private val bgInput = v("--bg-input")
private val bgUserMsg = v("--bg-user-msg")
private val bgOverlay = v("--bg-overlay")
private val borderSubtle = v("--border-subtle")
private val textPrimary = v("--text-primary")
private val textSecondary = v("--text-secondary")
private val textMuted = v("--text-muted")
private val textOnBlue = v("--text-on-blue")
private val accentBlue = v("--accent-blue")
private val accentRed = v("--accent-red")
private val accentYellow = v("--accent-yellow")
private val accentGreen = v("--accent-green")
private val accentRedHover = v("--accent-red-hover")
private val codeBg = v("--code-bg")
private val fileTagBg = v("--file-tag-bg")
private val fileTagColor = v("--file-tag-color")
private val shadowColor = v("--shadow-color")
private val errorBg = v("--error-bg")
private val radius = 8.px
private val radiusLg = 12.px
private const val FONT_SANS =
    "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"Noto Sans\", Helvetica, Arial, sans-serif"
private const val FONT_MONO =
    "ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, \"Liberation Mono\", monospace"

@Composable
fun IComponent.appStyles() {
    resetStyles()
    headerStyles()
    agentSelectorStyles()
    messagesStyles()
    messageBubbleStyles()
    toolStyles()
    errorStyles()
    statusTimerStyles()
    scrollButtonStyles()
    inputBarStyles()
    buttonStyles()
    permissionDialogStyles()
    markdownStyles()
}

// ---- Reset & Base ----

@Composable
private fun IComponent.resetStyles() {
    globalStyle(selector = "*, *::before, *::after") {
        setStyle("box-sizing", "border-box")
        margin = 0.px
        padding = 0.px
    }
    globalStyle(selector = "html, body") {
        height = 100.perc
        overflow = Overflow.Hidden
        overflowX = Overflow.Hidden
        maxWidth = 100.vw
        fontFamily = FONT_SANS
        fontSize = 14.px
        lineHeight = 1.5.units
        color = textPrimary
        background = Background(color = bgBody)
        setStyle("-webkit-font-smoothing", "antialiased")
    }
    globalStyle(selector = "#root") {
        display = Display.Flex
        flexDirection = FlexDirection.Column
        height = 100.vh
        overflowX = Overflow.Hidden
        maxWidth = 100.vw
        position = Position.Relative
    }
}

// ---- Header ----

@Composable
private fun IComponent.headerStyles() {
    globalStyle(selector = "header") {
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "8px")
        setStyle("padding", "8px 16px")
        background = Background(color = bgHeader)
        borderBottom = Border(1.px, BorderStyle.Solid, borderSubtle)
        flexShrink = 0
        minHeight = 44.px
    }
    globalStyle(selector = "header .header-icon") {
        width = 24.px
        height = 24.px
        borderRadius = radius
        setStyle("object-fit", "contain")
        setStyle("filter", "var(--icon-filter)")
    }
    globalStyle(selector = "header .header-title") {
        setStyle("font-weight", "700")
        fontSize = 15.px
        color = textPrimary
        whiteSpace = WhiteSpace.Nowrap
    }
    globalStyle(selector = "header .header-info") {
        color = textSecondary
        fontSize = 12.px
        whiteSpace = WhiteSpace.Nowrap
        overflow = Overflow.Hidden
        textOverflow = TextOverflow.Ellipsis
    }
    globalStyle(selector = "header button") {
        setStyle("padding", "4px 12px")
        borderRadius = radius
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = Color("transparent"))
        color = textSecondary
        fontSize = 12.px
        cursor = Cursor.Pointer
        transitionList = listOf(
            Transition("background", 150.milliseconds),
            Transition("color", 150.milliseconds),
            Transition("border-color", 150.milliseconds),
        )
        whiteSpace = WhiteSpace.Nowrap
    }
    globalStyle(selector = "header button:hover") {
        background = Background(color = bgCardHover)
        color = textPrimary
        setStyle("border-color", "var(--text-secondary)")
    }
    globalStyle(selector = "header .btn-theme") {
        setStyle("margin-left", "auto")
        fontSize = 14.px
    }
}

// ---- Agent Selector ----

@Composable
private fun IComponent.agentSelectorStyles() {
    globalStyle(selector = ".agent-selector-overlay") {
        position = Position.Fixed
        setStyle("inset", "0")
        background = Background(color = bgOverlay)
        display = Display.Flex
        alignItems = AlignItems.Center
        justifyContent = JustifyContent.Center
        zIndex = 100
    }
    globalStyle(selector = ".agent-selector-dialog") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        padding = 24.px
        maxWidth = 520.px
        width = 90.perc
        maxHeight = 80.vh
        display = Display.Flex
        flexDirection = FlexDirection.Column
    }
    globalStyle(selector = ".agent-selector-dialog h3") {
        fontSize = 16.px
        marginBottom = 16.px
        color = textPrimary
        flexShrink = 0
    }
    globalStyle(selector = ".agent-selector-list") {
        overflowY = Overflow.Auto
        display = Display.Flex
        flexDirection = FlexDirection.Column
        setStyle("gap", "4px")
    }
    globalStyle(selector = ".agent-selector-list::-webkit-scrollbar") {
        width = 6.px
    }
    globalStyle(selector = ".agent-selector-list::-webkit-scrollbar-track") {
        background = Background(color = Color("transparent"))
    }
    globalStyle(selector = ".agent-selector-list::-webkit-scrollbar-thumb") {
        background = Background(color = borderSubtle)
        borderRadius = 3.px
    }
    globalStyle(selector = ".agent-selector-item") {
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "12px")
        setStyle("padding", "10px 12px")
        borderRadius = radius
        cursor = Cursor.Pointer
        transitionList = listOf(
            Transition("background", 150.milliseconds),
        )
    }
    globalStyle(selector = ".agent-selector-item:hover:not(.current)") {
        background = Background(color = bgCardHover)
    }
    globalStyle(selector = ".agent-selector-item.current") {
        opacity = 0.5
        cursor = Cursor.Default
    }
    globalStyle(selector = ".agent-selector-icon") {
        width = 32.px
        height = 32.px
        borderRadius = radius
        setStyle("object-fit", "contain")
        setStyle("filter", "var(--icon-filter)")
        flexShrink = 0
    }
    globalStyle(selector = ".agent-selector-icon-placeholder") {
        width = 32.px
        height = 32.px
        borderRadius = radius
        background = Background(color = borderSubtle)
        display = Display.Flex
        alignItems = AlignItems.Center
        justifyContent = JustifyContent.Center
        fontSize = 14.px
        setStyle("font-weight", "600")
        color = textSecondary
        flexShrink = 0
    }
    globalStyle(selector = ".agent-selector-info") {
        minWidth = 0.px
        setStyle("flex", "1")
    }
    globalStyle(selector = ".agent-selector-name") {
        fontSize = 14.px
        setStyle("font-weight", "600")
        color = textPrimary
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "8px")
    }
    globalStyle(selector = ".agent-selector-badge") {
        fontSize = 10.px
        setStyle("padding", "1px 6px")
        borderRadius = 4.px
        background = Background(color = borderSubtle)
        color = textMuted
        setStyle("font-weight", "500")
    }
    globalStyle(selector = ".agent-selector-desc") {
        fontSize = 12.px
        color = textSecondary
        marginTop = 2.px
        lineHeight = 1.4.units
        overflow = Overflow.Hidden
        textOverflow = TextOverflow.Ellipsis
        setStyle("display", "-webkit-box")
        setStyle("-webkit-line-clamp", "2")
        setStyle("-webkit-box-orient", "vertical")
    }

    // Switching agent modal
    globalStyle(selector = ".switching-agent-dialog") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        setStyle("padding", "32px 40px")
        display = Display.Flex
        flexDirection = FlexDirection.Column
        alignItems = AlignItems.Center
        setStyle("gap", "16px")
        setStyle("text-align", "center")
    }
    globalStyle(selector = ".switching-agent-dialog p") {
        fontSize = 14.px
        color = textSecondary
        margin = 0.px
    }
    globalStyle(selector = ".switching-spinner") {
        width = 24.px
        height = 24.px
        border = Border(3.px, BorderStyle.Solid, borderSubtle)
        setStyle("border-top-color", "var(--text-primary)")
        borderRadius = 50.perc
        setStyle("animation", "spin 0.8s linear infinite")
    }
}

// ---- Messages Area ----

@Composable
private fun IComponent.messagesStyles() {
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
private fun IComponent.messageBubbleStyles() {
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

// ---- Tool Calls ----

@Composable
private fun IComponent.toolStyles() {
    globalStyle(selector = ".msg-tools") {
        alignSelf = AlignItems.FlexStart
        width = 85.perc
    }
    globalStyle(selector = ".msg-tools > details") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
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

// ---- Error Messages ----

@Composable
private fun IComponent.errorStyles() {
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
private fun IComponent.statusTimerStyles() {
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
private fun IComponent.scrollButtonStyles() {
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
private fun IComponent.inputBarStyles() {
    globalStyle(selector = ".input-bar") {
        flexShrink = 0
        setStyle("padding", "8px 16px 12px")
        background = Background(color = bgHeader)
        borderTop = Border(1.px, BorderStyle.Solid, borderSubtle)
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

    // Autocomplete
    globalStyle(selector = ".autocomplete-popup") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        marginBottom = 4.px
        maxHeight = 200.px
        overflowY = Overflow.Auto
    }
    globalStyle(selector = ".autocomplete-item") {
        setStyle("padding", "6px 12px")
        cursor = Cursor.Pointer
        fontSize = 13.px
        color = textPrimary
        transition = Transition("background", 100.milliseconds)
    }
    globalStyle(selector = ".autocomplete-item:hover, .autocomplete-item.selected") {
        background = Background(color = bgCardHover)
    }
    globalStyle(selector = ".autocomplete-item span") {
        color = accentBlue
        fontFamily = FONT_MONO
        fontSize = 12.px
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
        background = Background(color = bgInput)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        setStyle("padding", "10px 14px")
        color = textPrimary
        fontFamily = FONT_SANS
        fontSize = 14.px
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
    }
    globalStyle(selector = ".input-actions .btn-row") {
        display = Display.Flex
        setStyle("gap", "6px")
    }
}

// ---- Buttons ----

@Composable
private fun IComponent.buttonStyles() {
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
    globalStyle(selector = ".btn-diagnose") {
        setStyle("padding", "6px 16px")
        borderRadius = radius
        border = Border(style = BorderStyle.None)
        background = Background(color = accentYellow)
        color = Color("#000000")
        fontSize = 13.px
        setStyle("font-weight", "600")
        cursor = Cursor.Pointer
        transition = Transition("opacity", 150.milliseconds)
    }
    globalStyle(selector = ".btn-diagnose:hover") {
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

// ---- Permission Dialog ----

@Composable
private fun IComponent.permissionDialogStyles() {
    globalStyle(selector = ".permission-overlay") {
        position = Position.Fixed
        setStyle("inset", "0")
        background = Background(color = bgOverlay)
        display = Display.Flex
        alignItems = AlignItems.Center
        justifyContent = JustifyContent.Center
        zIndex = 100
    }
    globalStyle(selector = ".permission-dialog") {
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radiusLg
        padding = 24.px
        maxWidth = 480.px
        width = 90.perc
    }
    globalStyle(selector = ".permission-dialog h3") {
        fontSize = 16.px
        marginBottom = 12.px
        color = textPrimary
    }
    globalStyle(selector = ".permission-dialog p") {
        color = textSecondary
        fontSize = 14.px
        marginBottom = 16.px
        lineHeight = 1.5.units
    }
    globalStyle(selector = ".permission-dialog .perm-actions") {
        display = Display.Flex
        setStyle("gap", "8px")
        justifyContent = JustifyContent.FlexEnd
    }
    globalStyle(selector = ".permission-dialog button") {
        setStyle("padding", "8px 20px")
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
    globalStyle(selector = ".permission-dialog button:hover") {
        background = Background(color = borderSubtle)
    }
    globalStyle(selector = ".permission-dialog button:first-child") {
        background = Background(color = accentGreen)
        setStyle("border-color", "var(--accent-green)")
        color = Color("#000000")
        setStyle("font-weight", "600")
    }
}

// ---- Markdown Content ----

@Composable
private fun IComponent.markdownStyles() {
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
