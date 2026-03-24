package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent
import dev.kilua.html.Display
import dev.kilua.html.perc
import dev.kilua.html.style.globalStyle

// ---- Syntax Highlighting (highlight.js tokens) ----

@Composable
internal fun IComponent.highlightStyles() {
    // highlight.js token colors — use CSS custom properties from installTheme()
    globalStyle(selector = ".hljs-keyword, .hljs-selector-tag") {
        color = v("--hljs-keyword")
    }
    globalStyle(selector = ".hljs-string, .hljs-regexp") {
        color = v("--hljs-string")
    }
    globalStyle(selector = ".hljs-number") {
        color = v("--hljs-number")
    }
    globalStyle(selector = ".hljs-comment, .hljs-quote") {
        color = v("--hljs-comment")
        setStyle("font-style", "italic")
    }
    globalStyle(selector = ".hljs-title.function_, .hljs-section") {
        color = v("--hljs-function")
    }
    globalStyle(selector = ".hljs-type, .hljs-built_in") {
        color = v("--hljs-type")
    }
    globalStyle(selector = ".hljs-literal, .hljs-variable.constant_") {
        color = v("--hljs-literal")
    }
    globalStyle(selector = ".hljs-attr, .hljs-attribute") {
        color = v("--hljs-attr")
    }
    globalStyle(selector = ".hljs-meta, .hljs-doctag") {
        color = v("--hljs-meta")
    }
    globalStyle(selector = ".hljs-title, .hljs-title.class_") {
        color = v("--hljs-title")
    }
    globalStyle(selector = ".hljs-tag") {
        color = v("--hljs-tag")
    }
    globalStyle(selector = ".hljs-name") {
        color = v("--hljs-name")
    }
    globalStyle(selector = ".hljs-selector-class, .hljs-selector-id, .hljs-selector-pseudo") {
        color = v("--hljs-selector")
    }
    globalStyle(selector = ".hljs-addition") {
        color = v("--diff-add-color")
    }
    globalStyle(selector = ".hljs-deletion") {
        color = v("--diff-del-color")
    }

    // ---- Diff Rendering (custom line-by-line coloring) ----

    globalStyle(selector = ".diff-add") {
        setStyle("background", "var(--diff-add-bg)")
        color = v("--diff-add-color")
        display = Display.InlineBlock
        width = 100.perc
    }
    globalStyle(selector = ".diff-del") {
        setStyle("background", "var(--diff-del-bg)")
        color = v("--diff-del-color")
        display = Display.InlineBlock
        width = 100.perc
    }
    globalStyle(selector = ".diff-hunk") {
        color = v("--diff-hunk-color")
    }
    globalStyle(selector = ".diff-header") {
        color = v("--diff-header-color")
        setStyle("font-weight", "600")
    }
}
