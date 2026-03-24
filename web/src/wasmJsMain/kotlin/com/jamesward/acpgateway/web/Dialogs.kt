package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import com.jamesward.acpgateway.shared.AgentInfo
import com.jamesward.acpgateway.shared.WsMessage
import dev.kilua.core.IComponent
import dev.kilua.html.*

@Composable
fun IComponent.agentSelectorView(
    agents: List<AgentInfo>,
    currentAgentId: String?,
    agentError: String?,
    onDismiss: () -> Unit,
    onSelect: (AgentInfo) -> Unit,
) {
    div(className = "agent-selector-overlay") {
        onClick { onDismiss() }
        div(className = "agent-selector-dialog") {
            onClick { it.stopPropagation() }
            h3 { +"Select an Agent" }
            if (agentError != null) {
                div(className = "msg-error") { +agentError }
            }
            div(className = "agent-selector-list") {
                for (agent in agents) {
                    val isCurrent = agent.id == currentAgentId
                    div(className = if (isCurrent) "agent-selector-item current" else "agent-selector-item") {
                        if (!isCurrent) {
                            onClick { onSelect(agent) }
                        }
                        if (agent.icon != null) {
                            img(src = agent.icon, alt = agent.name) { className("agent-selector-icon") }
                        } else {
                            div(className = "agent-selector-icon-placeholder") {
                                +(agent.name.firstOrNull()?.uppercase() ?: "?")
                            }
                        }
                        div(className = "agent-selector-info") {
                            div(className = "agent-selector-name") {
                                +agent.name
                                if (isCurrent) span(className = "agent-selector-badge") { +"current" }
                            }
                            if (agent.description.isNotEmpty()) {
                                div(className = "agent-selector-desc") { +agent.description }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IComponent.switchingAgentModal(name: String) {
    div(className = "agent-selector-overlay") {
        div(className = "switching-agent-dialog") {
            div(className = "switching-spinner")
            p { +"Switching to $name\u2026" }
        }
    }
}

@Composable
fun IComponent.permissionDialog(perm: WsMessage.PermissionRequest, planContent: String? = null, onRespond: (toolCallId: String, optionId: String) -> Unit) {
    div(className = "permission-bar") {
        val desc = perm.description
        if (desc != null) {
            div(className = "perm-description msg-body") {
                rawHtml(renderMarkdown(desc))
            }
        }
        if (planContent != null) {
            // Strip wrapping code fence if the content is ```markdown ... ```
            val plan = planContent.trim().let { raw ->
                if (raw.startsWith("```")) {
                    raw.substringAfter('\n').removeSuffix("```").trim()
                } else {
                    raw
                }
            }
            div(className = "perm-description msg-body") {
                rawHtml(renderMarkdown(plan))
            }
        }
        div(className = "perm-title") {
            h3 { +perm.title }
        }
        div(className = "perm-actions") {
            for (opt in perm.options) {
                button(opt.name) {
                    onClick {
                        onRespond(perm.toolCallId, opt.optionId)
                    }
                }
            }
        }
    }
}
