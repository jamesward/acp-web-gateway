package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun IComponent.headerStyles() {
    globalStyle(selector = "header") {
        display = Display.Flex
        alignItems = AlignItems.Center
        setStyle("gap", "8px")
        setStyle("padding", "8px 16px")
        background = Background(color = bgHeader)
        borderBottom = Border(1.px, BorderStyle.Solid, borderSubtle)
        flexShrink = 0
        minHeight = 44.px
        setStyle("flex-wrap", "wrap")
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
    globalStyle(selector = "header .header-mode") {
        fontSize = 11.px
        setStyle("padding", "2px 8px")
        borderRadius = 4.px
        background = Background(color = borderSubtle)
        color = textSecondary
        setStyle("font-weight", "500")
        whiteSpace = WhiteSpace.Nowrap
    }
    globalStyle(selector = "header .header-info") {
        color = textSecondary
        fontSize = 12.px
        whiteSpace = WhiteSpace.Nowrap
        overflow = Overflow.Hidden
        textOverflow = TextOverflow.Ellipsis
        minWidth = 0.px
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
    globalStyle(selector = "header .btn-github") {
        setStyle("display", "inline-flex")
        setStyle("align-items", "center")
        setStyle("padding", "4px 12px")
        borderRadius = radius
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        background = Background(color = Color("transparent"))
        color = textSecondary
        fontSize = 14.px
        cursor = Cursor.Pointer
        textDecoration = TextDecoration(TextDecorationLine.None)
        transitionList = listOf(
            Transition("background", 150.milliseconds),
            Transition("color", 150.milliseconds),
            Transition("border-color", 150.milliseconds),
        )
    }
    globalStyle(selector = "header .btn-github:hover") {
        background = Background(color = bgCardHover)
        color = textPrimary
        setStyle("border-color", "var(--text-secondary)")
    }
    globalStyle(selector = "header .btn-theme") {
        fontSize = 14.px
    }
    globalStyle(selector = "header .mcp-container") {
        position = Position.Relative
        setStyle("display", "inline-flex")
        setStyle("margin-left", "auto")
    }
    globalStyle(selector = "header .btn-mcp") {
        setStyle("display", "inline-flex")
        setStyle("align-items", "center")
        fontSize = 14.px
    }
    globalStyle(selector = "header .mcp-url-popup") {
        position = Position.Absolute
        setStyle("top", "100%")
        setStyle("right", "0")
        marginTop = 4.px
        setStyle("padding", "6px 12px")
        background = Background(color = bgCard)
        border = Border(1.px, BorderStyle.Solid, borderSubtle)
        borderRadius = radius
        setStyle("box-shadow", "0 4px 12px rgba(0,0,0,0.3)")
        whiteSpace = WhiteSpace.Nowrap
        fontSize = 12.px
        color = textPrimary
        zIndex = 50
    }
}

@Composable
internal fun IComponent.agentSelectorStyles() {
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
