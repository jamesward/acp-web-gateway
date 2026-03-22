package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import com.jamesward.acpgateway.shared.ToolStatus
import dev.kilua.core.IComponent
import dev.kilua.html.*

@Composable
fun IComponent.userMessageView(text: String, fileNames: List<String> = emptyList()) {
    div(className = "msg msg-user") {
        div(className = "msg-content") {
            if (text.isNotEmpty()) +text
            if (fileNames.isNotEmpty()) {
                div(className = "msg-files") {
                    for (name in fileNames) {
                        span(className = "msg-file-tag") { +name }
                    }
                }
            }
        }
    }
}

@Composable
fun IComponent.assistantMessageView(markdown: String, usage: String?, expanded: Boolean = true) {
    div(className = "msg msg-assistant") {
        details {
            if (expanded) attribute("open", "")
            summary {
                span { +"Response" }
                if (usage != null) {
                    span { +" \u00b7 $usage" }
                }
            }
            div(className = "msg-body") {
                rawHtml(dev.kilua.marked.parseMarkdown(markdown))
            }
        }
    }
}

@Composable
fun IComponent.thoughtMessageView(markdown: String, usage: String?, showTimer: Boolean = false, elapsedSeconds: Int = 0, expanded: Boolean = true) {
    div(className = "msg msg-thought") {
        details {
            if (expanded) attribute("open", "")
            summary {
                if (showTimer) {
                    span { +"Thinking" }
                    if (elapsedSeconds > 0) {
                        span { +" ${formatElapsed(elapsedSeconds)}" }
                    }
                } else {
                    span { +"Thought" }
                    if (elapsedSeconds > 0) {
                        span { +" ${formatElapsed(elapsedSeconds)}" }
                    }
                }
            }
            div(className = "msg-body") {
                rawHtml(dev.kilua.marked.parseMarkdown(markdown))
            }
        }
    }
}

@Composable
fun IComponent.toolBlockView(tools: List<ToolCallState>) {
    val done = tools.count { it.status == ToolStatus.Completed }
    val failed = tools.count { it.status == ToolStatus.Failed }
    val running = tools.count { it.status == ToolStatus.InProgress }
    val total = tools.size
    val activeName = tools.lastOrNull { it.status == ToolStatus.InProgress }?.title

    div(className = "msg msg-tools") {
        details {
            summary {
                span(className = "tool-summary-label") {
                    val label = if (total == 1) "1 tool call" else "$total tool calls"
                    +label
                    val parts = mutableListOf<String>()
                    if (done > 0) parts.add("$done done")
                    if (failed > 0) parts.add("$failed failed")
                    if (running > 0) parts.add("$running running")
                    if (parts.isNotEmpty()) {
                        +" (${parts.joinToString(", ")})"
                    }
                }
                if (activeName != null) {
                    span(className = "tool-summary-active") { +" \u00b7 $activeName" }
                }
            }
            div(className = "tools-list") {
                for (tc in tools) {
                    toolRow(tc)
                }
            }
        }
    }
}

@Composable
private fun IComponent.toolRow(tc: ToolCallState) {
    val hasContent = tc.contentHtml != null || !tc.content.isNullOrEmpty()
    if (hasContent) {
        details(className = "tool-item") {
            summary {
                toolRowSummary(tc)
            }
            val contentHtml = tc.contentHtml
            val contentText = tc.content
            if (contentHtml != null) {
                div(className = "tool-content") { rawHtml(contentHtml) }
            } else if (!contentText.isNullOrEmpty()) {
                div(className = "tool-content msg-body") {
                    rawHtml(dev.kilua.marked.parseMarkdown(contentText))
                }
            }
        }
    } else {
        div(className = "tool-item") {
            toolRowSummary(tc)
        }
    }
}

@Composable
private fun IComponent.toolRowSummary(tc: ToolCallState) {
    val iconClass = when (tc.status) {
        ToolStatus.Completed -> "tool-icon-ok"
        ToolStatus.Failed -> "tool-icon-fail"
        else -> "tool-icon-pending"
    }
    val icon = when (tc.status) {
        ToolStatus.Completed -> "\u2713"
        ToolStatus.Failed -> "\u2717"
        else -> "\u25CB"
    }
    span(className = iconClass) { +icon }
    span(className = "tool-name") { +" ${tc.title}" }
    if (tc.location != null) {
        span(className = "tool-location") { +" \u00b7 ${tc.location.substringAfterLast('/')}" }
    }
}

@Composable
fun IComponent.errorMessageView(message: String) {
    div(className = "msg msg-error") { +message }
}

fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "\u00b7 ${minutes}m ${seconds}s" else "\u00b7 ${seconds}s"
}
