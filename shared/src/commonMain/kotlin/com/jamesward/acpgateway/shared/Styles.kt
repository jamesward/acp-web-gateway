package com.jamesward.acpgateway.shared

import kotlinx.css.*
import kotlinx.css.properties.*

// Element ID constants — used by both server HTML templates and browser client
object Id {
    const val AGENT_INFO = "agent-info"
    const val PROMPT_FORM = "prompt-form"
    const val PROMPT_INPUT = "prompt-input"
    const val SEND_BTN = "send-btn"
    const val ATTACH_BTN = "attach-btn"
    const val FILE_INPUT = "file-input"
    const val FILE_PREVIEW = "file-preview"
    const val DIAGNOSE_BTN = "diagnose-btn"
    const val RELOAD_BTN = "reload-btn"
    const val SCROLL_BTN = "scroll-to-bottom-btn"
    const val PERMISSION_DIALOG = "permission-dialog"
    const val PERMISSION_CONTENT = "permission-content"
    const val SCREENSHOT_TOGGLE = "screenshot-toggle"
    const val TASK_STATUS_WRAP = "task-status-wrap"
    const val TASK_STATUS = "task-status"
}

// CSS class name constants — used by both server HTML templates and shared fragment builders
object Css {
    // Layout
    const val BODY = "body"
    const val HEADER = "header-bar"
    const val HEADER_TITLE = "header-title"
    const val HEADER_INFO = "agent-info"
    const val MESSAGES = "messages"
    const val INPUT_BAR = "input-bar"
    const val INPUT_FORM = "input-form"

    // Messages
    const val MSG_WRAP_USER = "msg-wrap-user"
    const val MSG_WRAP_ASSISTANT = "msg-wrap-assistant"
    const val MSG_WRAP_ERROR = "msg-wrap-error"
    const val MSG_USER = "msg-user"
    const val MSG_ASSISTANT = "msg-assistant"
    const val MSG_ERROR = "msg-error"
    const val MSG_THOUGHT = "msg-thought"
    const val MSG_CONTENT = "message-content"

    // Tool calls
    const val TOOL_BLOCK = "tool-block"
    const val TOOL_HEADER = "tool-header"
    const val TOOL_HEADER_LEFT = "tool-header-left"
    const val TOOL_SUMMARY = "tool-summary"
    const val TOOL_DOT = "tool-dot"
    const val TOOL_ACTIVE = "tool-active"
    const val TOOL_CHEVRON = "tool-chevron"
    const val TOOL_LIST = "tool-list"
    const val TOOL_ROW = "tool-row"
    const val TOOL_ROW_HEADER = "tool-row-header"
    const val TOOL_ROW_CLICKABLE = "tool-row-clickable"
    const val TOOL_ICON = "tool-icon"
    const val TOOL_ICON_DONE = "tool-icon-done"
    const val TOOL_ICON_FAIL = "tool-icon-fail"
    const val TOOL_ICON_RUNNING = "tool-icon-running"
    const val TOOL_TITLE = "tool-title"
    const val TOOL_TITLE_DONE = "tool-title-done"
    const val TOOL_TITLE_FAIL = "tool-title-fail"
    const val TOOL_TITLE_RUNNING = "tool-title-running"
    const val TOOL_RESULT_CHEVRON = "tool-result-chevron"
    const val TOOL_RESULT = "tool-result"
    const val TOOL_LOCATION = "tool-location"

    // Permission dialog
    const val PERM_OVERLAY = "perm-overlay"
    const val PERM_CARD = "perm-card"
    const val PERM_HEADING = "perm-heading"
    const val PERM_DESC = "perm-desc"
    const val PERM_BUTTONS = "perm-buttons"
    const val PERM_BTN_ALLOW = "perm-btn-allow"
    const val PERM_BTN_DENY = "perm-btn-deny"

    // Form elements
    const val ATTACH_BTN = "attach-btn"
    const val PROMPT_INPUT = "prompt-input"
    const val SEND_BTN = "send-btn"
    const val SEND_BTN_CANCEL = "send-btn-cancel"
    const val BTN_GROUP = "btn-group"
    const val SCREENSHOT_LABEL = "screenshot-label"
    const val SCREENSHOT_CHECK = "screenshot-check"
    const val DIAGNOSE_BTN = "diagnose-btn"
    const val RELOAD_BTN = "reload-btn"

    // File preview
    const val FILE_PREVIEW = "file-preview"
    const val FILE_CHIP = "file-chip"
    const val FILE_REMOVE = "file-remove"

    // Status & scroll
    const val STATUS_WRAP = "status-wrap"
    const val STATUS_TEXT = "status-text"
    const val SCROLL_BTN = "scroll-btn"

    // Content blocks (assistant output + thinking)
    const val CONTENT_BLOCK = "content-block"
    const val CONTENT_THOUGHT = "content-thought"
    const val CONTENT_HEADER = "content-header"
    const val CONTENT_LABEL = "content-label"
    const val CONTENT_BODY = "content-body"
    const val CONTENT_META = "content-meta"

    // Utility
    const val HIDDEN = "hidden"
    const val PULSE = "pulse"
    const val TRUNCATE = "truncate"
    const val SHRINK0 = "shrink-0"
    const val COLLAPSED = "collapsed"
}

// Color palette — dark theme
private object Colors {
    val gray900 = Color("#111827")
    val gray800 = Color("#1f2937")
    val gray700 = Color("#374151")
    val gray600 = Color("#4b5563")
    val gray500 = Color("#6b7280")
    val gray400 = Color("#9ca3af")
    val gray300 = Color("#d1d5db")
    val gray100 = Color("#f3f4f6")
    val white = Color.white
    val blue600 = Color("#2563eb")
    val blue700 = Color("#1d4ed8")
    val blue500 = Color("#3b82f6")
    val green600 = Color("#16a34a")
    val green700 = Color("#15803d")
    val green500 = Color("#22c55e")
    val red600 = Color("#dc2626")
    val red700 = Color("#b91c1c")
    val red400 = Color("#f87171")
    val red300 = Color("#fca5a5")
    val red900_50 = Color("rgba(127, 29, 29, 0.5)")
    val yellow600 = Color("#ca8a04")
    val yellow700 = Color("#a16207")
    val transparent = Color.transparent
}

fun appStylesheet(): String = CssBuilder().apply {
    // ---- Reset & base ----
    universal {
        boxSizing = BoxSizing.borderBox
        margin = Margin(0.px)
        padding = Padding(0.px)
    }

    body {
        backgroundColor = Colors.gray900
        color = Colors.gray100
        fontFamily = "system-ui, -apple-system, sans-serif"
        display = Display.flex
        flexDirection = FlexDirection.column
        height = 100.vh
        overflow = Overflow.hidden
    }

    // ---- Utility classes ----
    ".${Css.TRUNCATE}" {
        overflow = Overflow.hidden
        whiteSpace = WhiteSpace.nowrap
        put("text-overflow", "ellipsis")
    }

    ".${Css.SHRINK0}" {
        flexShrink = 0.0
    }

    // ---- Pulse animation ----
    keyframes("pulse") {
        50.0 {
            opacity = 0.5
        }
    }
    ".${Css.PULSE}" {
        put("animation", "pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite")
    }

    // ---- Header ----
    ".${Css.HEADER}" {
        backgroundColor = Colors.gray800
        borderBottom = Border(1.px, BorderStyle.solid, Colors.gray700)
        padding = Padding(12.px, 16.px)
        display = Display.flex
        alignItems = Align.center
        gap = 12.px
        flexShrink = 0.0
    }
    ".${Css.HEADER_TITLE}" {
        fontSize = 18.px
        fontWeight = FontWeight.w600
    }
    ".${Css.HEADER_INFO}" {
        fontSize = 14.px
        color = Colors.gray400
    }

    // ---- Messages area ----
    "#${Css.MESSAGES}" {
        flex = Flex(1, 1, 0.px)
        overflowY = Overflow.auto
        padding = Padding(16.px)
        display = Display.flex
        flexDirection = FlexDirection.column
        gap = 16.px
    }
    "#${Css.MESSAGES}::-webkit-scrollbar" {
        width = 6.px
    }
    "#${Css.MESSAGES}::-webkit-scrollbar-thumb" {
        backgroundColor = Color("#475569")
        borderRadius = 3.px
    }

    // ---- Message wrappers ----
    ".${Css.MSG_WRAP_USER}" {
        display = Display.flex
        justifyContent = JustifyContent.flexEnd
    }
    ".${Css.MSG_WRAP_ASSISTANT}" {
        display = Display.flex
        justifyContent = JustifyContent.flexStart
    }
    ".${Css.MSG_WRAP_ERROR}" {
        display = Display.flex
        justifyContent = JustifyContent.center
    }

    // ---- Message bubbles ----
    ".${Css.MSG_USER}" {
        backgroundColor = Colors.blue600
        color = Colors.white
        put("border-radius", "16px 16px 6px 16px")
        padding = Padding(8.px, 16.px)
        maxWidth = 80.pct
        whiteSpace = WhiteSpace.preWrap
    }
    ".${Css.MSG_ASSISTANT}" {
        backgroundColor = Colors.gray700
        color = Colors.gray100
        put("border-radius", "16px 16px 16px 6px")
        padding = Padding(8.px, 16.px)
        maxWidth = 80.pct
        whiteSpace = WhiteSpace.preWrap
    }
    ".${Css.MSG_ERROR}" {
        backgroundColor = Colors.red900_50
        color = Colors.red300
        borderRadius = 12.px
        padding = Padding(8.px, 16.px)
        maxWidth = 80.pct
    }
    ".${Css.MSG_THOUGHT}" {
        backgroundColor = Colors.gray800
        color = Colors.gray400
        fontStyle = FontStyle.italic
        borderRadius = 12.px
        padding = Padding(8.px, 16.px)
        maxWidth = 80.pct
        fontSize = 14.px
        border = Border(1.px, BorderStyle.solid, Colors.gray700)
        whiteSpace = WhiteSpace.preWrap
    }

    // ---- Message content (rendered markdown) ----
    ".${Css.MSG_CONTENT}" {
        whiteSpace = WhiteSpace.normal
    }
    ".${Css.MSG_CONTENT} pre" {
        backgroundColor = Color("#030712")
        padding = Padding(16.px)
        borderRadius = 8.px
        overflowX = Overflow.auto
        margin = Margin(8.px, 0.px)
    }
    ".${Css.MSG_CONTENT} code" {
        fontSize = 14.px
    }
    ".${Css.MSG_CONTENT} p" {
        margin = Margin(4.px, 0.px)
    }
    ".${Css.MSG_CONTENT} h1" {
        fontSize = 24.px
        fontWeight = FontWeight.w700
        margin = Margin(8.px, 0.px)
    }
    ".${Css.MSG_CONTENT} h2" {
        fontSize = 20.px
        fontWeight = FontWeight.w700
        margin = Margin(8.px, 0.px)
    }
    ".${Css.MSG_CONTENT} h3" {
        fontSize = 18.px
        fontWeight = FontWeight.w600
        margin = Margin(4.px, 0.px)
    }
    ".${Css.MSG_CONTENT} ul" {
        put("list-style", "disc")
        paddingLeft = 20.px
        margin = Margin(4.px, 0.px)
    }
    ".${Css.MSG_CONTENT} ol" {
        put("list-style", "decimal")
        paddingLeft = 20.px
        margin = Margin(4.px, 0.px)
    }
    ".${Css.MSG_CONTENT} li" {
        margin = Margin(2.px, 0.px)
    }
    ".${Css.MSG_CONTENT} a" {
        color = Color("#60a5fa")
        put("text-decoration", "underline")
    }
    ".${Css.MSG_CONTENT} blockquote" {
        borderLeft = Border(4.px, BorderStyle.solid, Colors.gray600)
        paddingLeft = 16.px
        fontStyle = FontStyle.italic
        color = Colors.gray400
        margin = Margin(8.px, 0.px)
    }

    // ---- Tool call blocks ----
    ".${Css.TOOL_BLOCK}" {
        backgroundColor = Colors.gray800
        border = Border(1.px, BorderStyle.solid, Colors.gray700)
        borderRadius = 12.px
        fontSize = 14.px
        put("overflow", "clip")
        width = 80.pct
    }
    ".${Css.TOOL_HEADER}" {
        padding = Padding(8.px, 12.px)
        display = Display.flex
        alignItems = Align.center
        justifyContent = JustifyContent.spaceBetween
        cursor = Cursor.pointer
        put("user-select", "none")
    }
    ".${Css.TOOL_HEADER}:hover" {
        backgroundColor = Color("rgba(55, 65, 81, 0.5)")
    }
    ".${Css.TOOL_HEADER_LEFT}" {
        display = Display.flex
        alignItems = Align.center
        gap = 8.px
        minWidth = 0.px
    }
    ".${Css.TOOL_SUMMARY}" {
        color = Colors.gray400
        flexShrink = 0.0
    }
    ".${Css.TOOL_DOT}" {
        color = Colors.gray600
    }
    ".${Css.TOOL_ACTIVE}" {
        color = Colors.gray300
        overflow = Overflow.hidden
        whiteSpace = WhiteSpace.nowrap
        put("text-overflow", "ellipsis")
    }
    ".${Css.TOOL_CHEVRON}" {
        color = Colors.gray500
        marginLeft = 8.px
        flexShrink = 0.0
        display = Display.inlineBlock
        put("transition", "transform 0.15s ease")
    }
    "details[open] > .${Css.TOOL_HEADER} .${Css.TOOL_CHEVRON}" {
        put("transform", "rotate(90deg)")
    }
    ".${Css.TOOL_ROW}" {
        borderTop = Border(1.px, BorderStyle.solid, Colors.gray700)
    }
    ".${Css.TOOL_ROW_HEADER}" {
        padding = Padding(6.px, 12.px)
        display = Display.flex
        alignItems = Align.center
        gap = 8.px
    }
    ".${Css.TOOL_ROW_CLICKABLE}" {
        cursor = Cursor.pointer
    }
    ".${Css.TOOL_ROW_CLICKABLE}:hover" {
        backgroundColor = Color("rgba(55, 65, 81, 0.3)")
    }
    ".${Css.TOOL_ICON}" {
        flexShrink = 0.0
    }
    ".${Css.TOOL_ICON_DONE}" {
        color = Colors.green500
    }
    ".${Css.TOOL_ICON_FAIL}" {
        color = Colors.red400
    }
    ".${Css.TOOL_ICON_RUNNING}" {
        color = Colors.gray400
    }
    ".${Css.TOOL_TITLE}" {
        overflow = Overflow.hidden
        whiteSpace = WhiteSpace.nowrap
        put("text-overflow", "ellipsis")
    }
    ".${Css.TOOL_TITLE_DONE}" {
        color = Colors.gray500
    }
    ".${Css.TOOL_TITLE_FAIL}" {
        color = Colors.red400
    }
    ".${Css.TOOL_TITLE_RUNNING}" {
        color = Colors.gray300
    }
    ".${Css.TOOL_LOCATION}" {
        color = Colors.gray500
        fontSize = 12.px
        marginLeft = LinearDimension.auto
        overflow = Overflow.hidden
        whiteSpace = WhiteSpace.nowrap
        put("text-overflow", "ellipsis")
        maxWidth = 150.px
        flexShrink = 1.0
    }
    ".${Css.TOOL_RESULT_CHEVRON}" {
        color = Colors.gray500
        marginLeft = LinearDimension.auto
        flexShrink = 0.0
        display = Display.inlineBlock
        put("transition", "transform 0.15s ease")
    }
    "details[open] > .${Css.TOOL_ROW_CLICKABLE} .${Css.TOOL_RESULT_CHEVRON}" {
        put("transform", "rotate(90deg)")
    }
    ".${Css.TOOL_RESULT}" {
        backgroundColor = Colors.gray900
        color = Colors.gray400
        fontSize = 12.px
        fontFamily = "monospace"
        padding = Padding(8.px, 12.px)
        margin = Margin(0.px, 8.px, 8.px, 8.px)
        borderRadius = 4.px
        maxHeight = 160.px
        overflowY = Overflow.auto
        whiteSpace = WhiteSpace.preWrap
    }

    // ---- Content blocks (assistant output + thinking) ----
    ".${Css.CONTENT_BLOCK}" {
        backgroundColor = Colors.gray800
        border = Border(1.px, BorderStyle.solid, Colors.gray700)
        borderRadius = 12.px
        fontSize = 14.px
        put("overflow", "clip")
        width = 80.pct
    }
    ".${Css.CONTENT_HEADER}" {
        padding = Padding(8.px, 12.px)
        display = Display.flex
        alignItems = Align.center
        justifyContent = JustifyContent.spaceBetween
        cursor = Cursor.pointer
        put("user-select", "none")
    }
    ".${Css.CONTENT_HEADER}:hover" {
        backgroundColor = Color("rgba(55, 65, 81, 0.5)")
    }
    ".${Css.CONTENT_LABEL}" {
        color = Colors.gray400
    }
    ".${Css.CONTENT_THOUGHT} .${Css.CONTENT_LABEL}" {
        fontStyle = FontStyle.italic
    }
    ".${Css.CONTENT_META}" {
        color = Colors.gray500
        fontSize = 12.px
        marginLeft = 8.px
    }
    "details[open] > .${Css.CONTENT_HEADER} .${Css.TOOL_CHEVRON}" {
        put("transform", "rotate(90deg)")
    }
    ".${Css.CONTENT_BODY}" {
        padding = Padding(8.px, 16.px, 16.px, 16.px)
    }
    // Override bubble styling when inside content-block (padding comes from .content-body)
    ".${Css.CONTENT_BLOCK} .${Css.MSG_ASSISTANT}" {
        backgroundColor = Colors.transparent
        put("border-radius", "0")
        maxWidth = 100.pct
    }
    ".${Css.CONTENT_BLOCK} .${Css.MSG_THOUGHT}" {
        backgroundColor = Colors.transparent
        border = Border.none
        put("border-radius", "0")
        maxWidth = 100.pct
        put("font-size", "inherit")
    }

    // ---- Diff rendering ----
    ".diff-add" {
        color = Colors.green500
    }
    ".diff-del" {
        color = Colors.red400
    }
    ".diff-hunk" {
        color = Color("#60a5fa")
    }

    // ---- Permission dialog ----
    ".${Css.PERM_OVERLAY}" {
        position = Position.fixed
        top = 0.px
        left = 0.px
        right = 0.px
        bottom = 0.px
        backgroundColor = Color("rgba(0, 0, 0, 0.5)")
        display = Display.flex
        alignItems = Align.center
        justifyContent = JustifyContent.center
        put("z-index", "50")
    }
    ".${Css.PERM_CARD}" {
        backgroundColor = Colors.gray800
        borderRadius = 12.px
        padding = Padding(24.px)
        maxWidth = 448.px
        width = 100.pct
        margin = Margin(0.px, 16.px)
        border = Border(1.px, BorderStyle.solid, Colors.gray600)
    }
    ".${Css.PERM_HEADING}" {
        fontSize = 18.px
        fontWeight = FontWeight.w600
        marginBottom = 8.px
    }
    ".${Css.PERM_DESC}" {
        color = Colors.gray300
        marginBottom = 16.px
        put("word-break", "break-all")
    }
    ".${Css.PERM_BUTTONS}" {
        display = Display.flex
        flexWrap = FlexWrap.wrap
        gap = 8.px
    }
    val permBtnBase: RuleSet = {
        color = Colors.white
        padding = Padding(8.px, 16.px)
        borderRadius = 8.px
        fontWeight = FontWeight.w500
        border = Border.none
        cursor = Cursor.pointer
        fontSize = 14.px
    }
    ".${Css.PERM_BTN_ALLOW}" {
        permBtnBase()
        backgroundColor = Colors.green600
    }
    ".${Css.PERM_BTN_ALLOW}:hover" {
        backgroundColor = Colors.green700
    }
    ".${Css.PERM_BTN_DENY}" {
        permBtnBase()
        backgroundColor = Colors.red600
    }
    ".${Css.PERM_BTN_DENY}:hover" {
        backgroundColor = Colors.red700
    }

    // ---- Input bar ----
    ".${Css.INPUT_BAR}" {
        flexShrink = 0.0
        borderTop = Border(1.px, BorderStyle.solid, Colors.gray700)
        backgroundColor = Colors.gray800
        padding = Padding(16.px)
    }
    ".${Css.INPUT_FORM}" {
        display = Display.flex
        alignItems = Align.center
        gap = 12.px
        width = 100.pct
    }

    // ---- Form elements ----
    ".${Css.ATTACH_BTN}" {
        flexShrink = 0.0
        alignSelf = Align.center
        backgroundColor = Colors.gray700
        color = Colors.gray300
        width = 40.px
        height = 40.px
        borderRadius = 12.px
        display = Display.flex
        alignItems = Align.center
        justifyContent = JustifyContent.center
        fontSize = 20.px
        fontWeight = FontWeight.bold
        border = Border(1.px, BorderStyle.solid, Colors.gray600)
        cursor = Cursor.pointer
    }
    ".${Css.ATTACH_BTN}:hover" {
        backgroundColor = Colors.gray600
    }
    ".${Css.PROMPT_INPUT}" {
        flex = Flex(1, 1, 0.px)
        minWidth = 0.px
        backgroundColor = Colors.gray700
        color = Colors.gray100
        border = Border(1.px, BorderStyle.solid, Colors.gray600)
        borderRadius = 12.px
        padding = Padding(12.px, 16.px)
        put("resize", "none")
        fontSize = 16.px
        lineHeight = LineHeight("1.625")
        fontFamily = "inherit"
    }
    ".${Css.PROMPT_INPUT}:focus" {
        put("outline", "none")
        put("box-shadow", "0 0 0 2px ${Colors.blue500}")
    }
    ".${Css.PROMPT_INPUT}::placeholder" {
        color = Colors.gray400
    }
    ".${Css.BTN_GROUP}" {
        flexShrink = 0.0
        alignSelf = Align.center
        display = Display.flex
        flexDirection = FlexDirection.column
        alignItems = Align.center
        gap = 8.px
    }
    val sendBtnBase: RuleSet = {
        color = Colors.white
        padding = Padding(12.px, 24.px)
        borderRadius = 12.px
        fontWeight = FontWeight.w500
        border = Border.none
        cursor = Cursor.pointer
        fontSize = 14.px
    }
    ".${Css.SEND_BTN}" {
        sendBtnBase()
        backgroundColor = Colors.blue600
    }
    ".${Css.SEND_BTN}:hover" {
        backgroundColor = Colors.blue700
    }
    ".${Css.SEND_BTN}:disabled" {
        opacity = 0.5
        cursor = Cursor.notAllowed
    }
    ".${Css.SEND_BTN_CANCEL}" {
        sendBtnBase()
        backgroundColor = Colors.red600
    }
    ".${Css.SEND_BTN_CANCEL}:hover" {
        backgroundColor = Colors.red700
    }
    ".${Css.DIAGNOSE_BTN}" {
        sendBtnBase()
        backgroundColor = Colors.yellow600
        fontSize = 14.px
        padding = Padding(12.px, 16.px)
    }
    ".${Css.DIAGNOSE_BTN}:hover" {
        backgroundColor = Colors.yellow700
    }
    ".${Css.RELOAD_BTN}" {
        sendBtnBase()
        backgroundColor = Colors.gray600
        fontSize = 14.px
        padding = Padding(8.px, 12.px)
    }
    ".${Css.RELOAD_BTN}:hover" {
        backgroundColor = Colors.gray500
    }
    ".${Css.SCREENSHOT_LABEL}" {
        display = Display.flex
        alignItems = Align.center
        gap = 6.px
        fontSize = 12.px
        color = Colors.gray400
        cursor = Cursor.pointer
        put("user-select", "none")
    }
    ".${Css.SCREENSHOT_CHECK}" {
        put("accent-color", Colors.blue500.value)
    }

    // ---- File preview ----
    ".${Css.FILE_PREVIEW}" {
        display = Display.flex
        flexWrap = FlexWrap.wrap
        gap = 8.px
        marginBottom = 8.px
    }
    ".${Css.FILE_CHIP}" {
        display = Display.flex
        alignItems = Align.center
        gap = 6.px
        backgroundColor = Colors.gray700
        color = Colors.gray300
        fontSize = 14.px
        padding = Padding(4.px, 12.px)
        borderRadius = 8.px
        border = Border(1.px, BorderStyle.solid, Colors.gray600)
    }
    ".${Css.FILE_REMOVE}" {
        color = Colors.gray400
        fontWeight = FontWeight.bold
        marginLeft = 4.px
        cursor = Cursor.pointer
        backgroundColor = Colors.transparent
        border = Border.none
        fontSize = 14.px
    }
    ".${Css.FILE_REMOVE}:hover" {
        color = Colors.white
    }

    // ---- Status timer ----
    ".${Css.STATUS_WRAP}" {
        display = Display.flex
        justifyContent = JustifyContent.flexStart
    }
    ".${Css.STATUS_TEXT}" {
        color = Colors.gray400
        fontSize = 14.px
        padding = Padding(4.px, 0.px)
    }

    // ---- Scroll-to-bottom button ----
    ".${Css.SCROLL_BTN}" {
        position = Position.absolute
        top = (-48).px
        left = 50.pct
        put("transform", "translateX(-50%)")
        backgroundColor = Colors.gray700
        color = Colors.gray300
        width = 36.px
        height = 36.px
        borderRadius = 50.pct
        display = Display.flex
        alignItems = Align.center
        justifyContent = JustifyContent.center
        border = Border(1.px, BorderStyle.solid, Colors.gray600)
        put("box-shadow", "0 10px 15px -3px rgba(0, 0, 0, 0.1)")
        put("z-index", "10")
        cursor = Cursor.pointer
    }
    ".${Css.SCROLL_BTN}:hover" {
        backgroundColor = Colors.gray600
    }

    // ---- Collapsed blocks (auto-collapse older content to save vertical space) ----
    ".${Css.COLLAPSED}" {
        put("max-height", "80px")
        overflow = Overflow.hidden
        cursor = Cursor.pointer
        put("-webkit-mask-image", "linear-gradient(to bottom, transparent, black 30px)")
        put("mask-image", "linear-gradient(to bottom, transparent, black 30px)")
    }

    // ---- Hidden (must be last to override display set by other classes) ----
    ".${Css.HIDDEN}" {
        display = Display.none
    }
}.toString()
