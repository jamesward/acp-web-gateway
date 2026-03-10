package com.jamesward.acpgateway.shared

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.serialization.Serializable

@Serializable
data class ToolCallDisplay(
    val id: String,
    val title: String,
    val status: ToolStatus,
    val content: String = "",
    val contentHtml: String = "",
    val kind: ToolKind? = null,
    val location: String? = null,
)

// ---- Message fragments ----

fun userMessageHtml(text: String): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_USER) {
    div(classes = Css.MSG_USER) { +text }
}

fun assistantMessageHtml(text: String, msgId: String? = null): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ASSISTANT) {
    if (msgId != null) id = msgId
    div(classes = Css.CONTENT_BLOCK) {
        details {
            attributes["open"] = ""
            summary(classes = Css.CONTENT_HEADER) {
                span(classes = Css.CONTENT_LABEL) { +"Response" }
                span(classes = Css.TOOL_CHEVRON) { +"\u25B8" }
            }
            div(classes = "${Css.CONTENT_BODY} ${Css.MSG_ASSISTANT} ${Css.MSG_CONTENT}") {
                attributes["style"] = "white-space: pre-wrap"
                +text
            }
        }
    }
}

fun assistantRenderedHtml(html: String, msgId: String? = null, usage: String? = null): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ASSISTANT) {
    if (msgId != null) id = msgId
    div(classes = Css.CONTENT_BLOCK) {
        details {
            attributes["open"] = ""
            summary(classes = Css.CONTENT_HEADER) {
                span(classes = Css.CONTENT_LABEL) { +"Response" }
                if (usage != null) {
                    span(classes = Css.CONTENT_META) { +usage }
                }
                span(classes = Css.TOOL_CHEVRON) { +"\u25B8" }
            }
            div(classes = "${Css.CONTENT_BODY} ${Css.MSG_ASSISTANT} ${Css.MSG_CONTENT}") {
                unsafe { raw(html) }
            }
        }
    }
}

fun thoughtMessageHtml(text: String, thoughtId: String? = null): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ASSISTANT) {
    if (thoughtId != null) id = thoughtId
    div(classes = "${Css.CONTENT_BLOCK} ${Css.CONTENT_THOUGHT}") {
        details {
            attributes["open"] = ""
            summary(classes = Css.CONTENT_HEADER) {
                span(classes = Css.CONTENT_LABEL) { +"Thinking" }
                span(classes = Css.THOUGHT_ELAPSED) { id = Id.THOUGHT_ELAPSED }
                span(classes = Css.TOOL_CHEVRON) { +"\u25B8" }
            }
            div(classes = "${Css.CONTENT_BODY} ${Css.MSG_THOUGHT} ${Css.MSG_CONTENT}") { +text }
        }
    }
}

fun thoughtRenderedHtml(html: String, thoughtId: String? = null, usage: String? = null): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ASSISTANT) {
    if (thoughtId != null) id = thoughtId
    div(classes = "${Css.CONTENT_BLOCK} ${Css.CONTENT_THOUGHT}") {
        details {
            attributes["open"] = ""
            summary(classes = Css.CONTENT_HEADER) {
                span(classes = Css.CONTENT_LABEL) { +"Thinking" }
                span(classes = Css.THOUGHT_ELAPSED) { id = Id.THOUGHT_ELAPSED }
                span(classes = Css.TOOL_CHEVRON) { +"\u25B8" }
            }
            div(classes = "${Css.CONTENT_BODY} ${Css.MSG_THOUGHT} ${Css.MSG_CONTENT}") {
                unsafe { raw(html) }
            }
        }
    }
}

fun errorMessageHtml(message: String): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ERROR) {
    div(classes = Css.MSG_ERROR) { +message }
}

// ---- Status timer ----

fun statusTimerHtml(activity: String, elapsed: String, usage: String? = null): String = createHTML(prettyPrint = false).div(classes = Css.STATUS_WRAP) {
    id = Id.TASK_STATUS_WRAP
    div(classes = Css.STATUS_TEXT) {
        id = Id.TASK_STATUS
        val text = buildString {
            append("$activity \u00b7 $elapsed")
            if (usage != null) append("  \u00b7  $usage")
        }
        +text
    }
}

// ---- Tool call block ----

fun toolBlockHtml(
    entries: List<ToolCallDisplay>,
    blockId: String? = null,
): String = createHTML(prettyPrint = false).div(classes = Css.MSG_WRAP_ASSISTANT) {
    if (blockId != null) id = blockId
    div(classes = Css.TOOL_BLOCK) {

        val activeTools = entries.filter { !it.status.isTerminal }
        val completedCount = entries.count { it.status == ToolStatus.Completed }
        val failedCount = entries.count { it.status == ToolStatus.Failed }
        val latestActive = activeTools.lastOrNull()

        // Use <details>/<summary> for CSS-only expand/collapse
        details {
            summary(classes = Css.TOOL_HEADER) {
                div(classes = Css.TOOL_HEADER_LEFT) {
                    // Summary text
                    span(classes = Css.TOOL_SUMMARY) {
                        val total = entries.size
                        val countText = "$total tool call${if (total != 1) "s" else ""}"
                        val parts = buildList {
                            if (completedCount > 0) add("$completedCount done")
                            if (failedCount > 0) add("$failedCount failed")
                            if (activeTools.isNotEmpty()) add("${activeTools.size} running")
                        }
                        +if (parts.isNotEmpty()) "$countText (${parts.joinToString(", ")})" else countText
                    }

                    // Latest active tool name
                    if (latestActive != null) {
                        span(classes = Css.TOOL_DOT) { +"\u00b7" }
                        span(classes = "${Css.TOOL_ACTIVE} ${Css.TRUNCATE} ${Css.PULSE}") {
                            +latestActive.title
                        }
                        val loc = latestActive.location
                        if (loc != null) {
                            span(classes = Css.TOOL_DOT) { +"\u00b7" }
                            span(classes = "${Css.TOOL_LOCATION} ${Css.PULSE}") {
                                +loc.substringAfterLast('/')
                            }
                        }
                    }
                }
                span(classes = Css.TOOL_CHEVRON) { +"\u25B8" }
            }

            // Expanded list
            div(classes = Css.TOOL_LIST) {
                for (entry in entries) {
                    div(classes = Css.TOOL_ROW) {
                        id = "tc-${entry.id}"
                        val hasContent = entry.content.isNotEmpty() || entry.contentHtml.isNotEmpty()
                        // Use nested <details> for entries with content
                        if (hasContent) {
                            details {
                                summary(classes = "${Css.TOOL_ROW_HEADER} ${Css.TOOL_ROW_CLICKABLE}") {
                                    toolEntryIcon(entry.status, entry.kind)
                                    toolEntryTitle(entry)
                                    span(classes = Css.TOOL_RESULT_CHEVRON) { +"\u25B8" }
                                }
                                if (entry.contentHtml.isNotEmpty()) {
                                    div(classes = Css.TOOL_RESULT) { unsafe { raw(entry.contentHtml) } }
                                } else {
                                    div(classes = Css.TOOL_RESULT) { +entry.content }
                                }
                            }
                        } else {
                            div(classes = Css.TOOL_ROW_HEADER) {
                                toolEntryIcon(entry.status, entry.kind)
                                toolEntryTitle(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toolKindIcon(kind: ToolKind): String = when (kind) {
    ToolKind.Read -> "\uD83D\uDCC4"          // page
    ToolKind.Edit -> "\u270F\uFE0F"           // pencil
    ToolKind.Delete -> "\uD83D\uDDD1\uFE0F"  // wastebasket
    ToolKind.Move -> "\u2194\uFE0F"           // left-right arrow
    ToolKind.Search -> "\uD83D\uDD0D"         // magnifying glass
    ToolKind.Execute -> "\u25B6\uFE0F"        // play
    ToolKind.Think -> "\uD83D\uDCA1"          // light bulb
    ToolKind.Fetch -> "\uD83C\uDF10"          // globe
    ToolKind.SwitchMode -> "\uD83D\uDD00"     // shuffle
    ToolKind.Other -> "\u2022"                 // bullet
}

private fun FlowContent.toolEntryIcon(status: ToolStatus, kind: ToolKind? = null) {
    if (!status.isTerminal && kind != null) {
        span(classes = "${Css.TOOL_ICON} ${Css.TOOL_ICON_RUNNING} ${Css.PULSE}") { +toolKindIcon(kind) }
        return
    }
    val (iconText, iconClass) = when (status) {
        ToolStatus.Completed -> "\u2713" to "${Css.TOOL_ICON} ${Css.TOOL_ICON_DONE}"
        ToolStatus.Failed -> "\u2717" to "${Css.TOOL_ICON} ${Css.TOOL_ICON_FAIL}"
        else -> "\u25CB" to "${Css.TOOL_ICON} ${Css.TOOL_ICON_RUNNING} ${Css.PULSE}"
    }
    span(classes = iconClass) { +iconText }
}

private fun FlowContent.toolEntryTitle(entry: ToolCallDisplay) {
    val titleClass = when (entry.status) {
        ToolStatus.Completed -> "${Css.TOOL_TITLE} ${Css.TOOL_TITLE_DONE} ${Css.TRUNCATE}"
        ToolStatus.Failed -> "${Css.TOOL_TITLE} ${Css.TOOL_TITLE_FAIL} ${Css.TRUNCATE}"
        else -> "${Css.TOOL_TITLE} ${Css.TOOL_TITLE_RUNNING} ${Css.TRUNCATE}"
    }
    span(classes = titleClass) { +entry.title }
}

// ---- Permission dialog ----

fun permissionContentHtml(
    toolCallId: String,
    title: String,
    options: List<PermissionOptionInfo>,
): String = createHTML(prettyPrint = false).div {
    h3(classes = Css.PERM_HEADING) { +"Permission Required" }
    p(classes = Css.PERM_DESC) { +title }
    div(classes = Css.PERM_BUTTONS) {
        for (opt in options) {
            val btnClass = if (opt.kind.isAllow) Css.PERM_BTN_ALLOW else Css.PERM_BTN_DENY
            button(classes = btnClass) {
                attributes["data-tool-call-id"] = toolCallId
                attributes["data-option-id"] = opt.optionId
                +opt.name
            }
        }
    }
}

// ---- File preview ----

fun filePreviewHtml(files: List<String>): String = createHTML(prettyPrint = false).div {
    for ((index, name) in files.withIndex()) {
        div(classes = Css.FILE_CHIP) {
            span { +name }
            button(classes = Css.FILE_REMOVE) {
                attributes["data-file-index"] = index.toString()
                +"\u00d7"
            }
        }
    }
}
