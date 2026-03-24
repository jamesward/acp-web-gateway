package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import com.jamesward.acpgateway.shared.PlanEntryInfo
import com.jamesward.acpgateway.shared.PlanEntryStatus
import com.jamesward.acpgateway.shared.ToolKind
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
                rawHtml(renderMarkdown(markdown))
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
                rawHtml(renderMarkdown(markdown))
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
    val hasContent = tc.contentHtml != null || !tc.content.isNullOrEmpty() || !tc.images.isNullOrEmpty()
    if (hasContent) {
        details(className = "tool-item") {
            summary {
                toolRowSummary(tc)
            }
            val contentHtml = tc.contentHtml
            val contentText = tc.content
            if (contentHtml != null) {
                div(className = "tool-content") { rawHtml(contentHtml) }
            } else if ((tc.kind == ToolKind.Read || tc.kind == ToolKind.Edit) && !contentText.isNullOrEmpty() && !tc.location.isNullOrEmpty()) {
                div(className = "tool-content") {
                    rawHtml(formatReadContent(contentText, tc.location))
                }
            } else if (!contentText.isNullOrEmpty()) {
                div(className = "tool-content msg-body") {
                    rawHtml(renderMarkdown(contentText))
                }
            }
            val images = tc.images
            if (!images.isNullOrEmpty()) {
                div(className = "tool-content") {
                    val imgHtml = images.joinToString("") { img ->
                        "<img src=\"data:${img.mimeType};base64,${img.data}\" alt=\"Tool result image\" class=\"agent-image\">"
                    }
                    rawHtml(imgHtml)
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
fun IComponent.imageMessageView(data: String, mimeType: String) {
    div(className = "msg msg-assistant") {
        div(className = "msg-body") {
            img(src = "data:$mimeType;base64,$data", alt = "Agent image", className = "agent-image")
        }
    }
}

@Composable
fun IComponent.planView(entries: List<PlanEntryInfo>) {
    div(className = "plan-view") {
        for (entry in entries) {
            val statusClass = when (entry.status) {
                PlanEntryStatus.Completed -> "plan-completed"
                PlanEntryStatus.InProgress -> "plan-in-progress"
                PlanEntryStatus.Pending -> "plan-pending"
            }
            val icon = when (entry.status) {
                PlanEntryStatus.Completed -> "\u2713"
                PlanEntryStatus.InProgress -> "\u25B6"
                PlanEntryStatus.Pending -> "\u25CB"
            }
            div(className = "plan-entry $statusClass") {
                span(className = "plan-icon") { +icon }
                span(className = "plan-content") { +entry.content }
            }
        }
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
