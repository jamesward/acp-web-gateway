package com.jamesward.acpgateway.web

import dev.kilua.html.Color
import dev.kilua.html.CssSize
import dev.kilua.html.px

// ---- Theme Colors (CSS custom properties set by installTheme() in App.kt) ----

internal fun v(name: String) = Color("var($name)")

internal val bgBody = v("--bg-body")
internal val bgHeader = v("--bg-header")
internal val bgCard = v("--bg-card")
internal val bgCardHover = v("--bg-card-hover")
internal val bgInput = v("--bg-input")
internal val bgUserMsg = v("--bg-user-msg")
internal val bgOverlay = v("--bg-overlay")
internal val borderSubtle = v("--border-subtle")
internal val textPrimary = v("--text-primary")
internal val textSecondary = v("--text-secondary")
internal val textMuted = v("--text-muted")
internal val textOnBlue = v("--text-on-blue")
internal val accentBlue = v("--accent-blue")
internal val accentRed = v("--accent-red")
internal val accentYellow = v("--accent-yellow")
internal val accentGreen = v("--accent-green")
internal val accentRedHover = v("--accent-red-hover")
internal val codeBg = v("--code-bg")
internal val fileTagBg = v("--file-tag-bg")
internal val fileTagColor = v("--file-tag-color")
internal val shadowColor = v("--shadow-color")
internal val errorBg = v("--error-bg")
internal val radius: CssSize = 8.px
internal val radiusLg: CssSize = 12.px
internal const val FONT_SANS =
    "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"Noto Sans\", Helvetica, Arial, sans-serif"
internal const val FONT_MONO =
    "ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, \"Liberation Mono\", monospace"
