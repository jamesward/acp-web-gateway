package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.*
import dev.kilua.html.style.globalStyle

@Composable
internal fun IComponent.resetStyles() {
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
