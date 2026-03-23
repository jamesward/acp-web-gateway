package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import dev.kilua.core.IComponent

@Composable
fun IComponent.appStyles() {
    resetStyles()
    headerStyles()
    agentSelectorStyles()
    messagesStyles()
    messageBubbleStyles()
    toolStyles()
    planStyles()
    errorStyles()
    statusTimerStyles()
    scrollButtonStyles()
    inputBarStyles()
    buttonStyles()
    permissionDialogStyles()
    markdownStyles()
    highlightStyles()
}
