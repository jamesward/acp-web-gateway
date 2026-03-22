package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import com.jamesward.acpgateway.shared.CommandInfo
import com.jamesward.acpgateway.shared.FileAttachment
import dev.kilua.core.IComponent
import dev.kilua.form.text.textArea
import dev.kilua.html.*
import web.keyboard.KeyboardEvent

@Composable
fun IComponent.inputBar(
    pendingFiles: List<FileAttachment>,
    autocompleteFiltered: List<CommandInfo>,
    autocompleteSelectedIndex: Int,
    promptText: String,
    agentWorking: Boolean,
    debugMode: Boolean,
    screenshotEnabled: Boolean,
    onRemoveFile: (index: Int) -> Unit,
    onCompleteCommand: (name: String) -> Unit,
    onAttachClick: () -> Unit,
    onSubmit: () -> Unit,
    onPromptInput: (String) -> Unit,
    onKeydown: (KeyboardEvent) -> Unit,
    onDropFiles: (web.file.FileList) -> Unit,
    onPaste: (web.clipboard.ClipboardEvent) -> Unit,
    onDiagnose: () -> Unit,
    onScreenshotToggle: () -> Unit,
    onDownloadLog: () -> Unit,
) {
    div(className = "input-bar") {
        // File preview
        if (pendingFiles.isNotEmpty()) {
            div(className = "file-preview") {
                for ((index, file) in pendingFiles.withIndex()) {
                    span(className = "file-chip") {
                        +file.name
                        button("\u00d7") {
                            title("Remove")
                            onClick { onRemoveFile(index) }
                        }
                    }
                }
            }
        }

        // Slash command buttons
        if (autocompleteFiltered.isNotEmpty()) {
            div(className = "command-buttons") {
                for ((i, cmd) in autocompleteFiltered.withIndex()) {
                    button("/${cmd.name}") {
                        className(if (i == autocompleteSelectedIndex) "command-btn selected" else "command-btn")
                        type(ButtonType.Button)
                        title(cmd.description)
                        onClick { onCompleteCommand(cmd.name) }
                    }
                }
            }
        }

        div(className = "input-row") {
            button("+") {
                className("btn-attach")
                type(ButtonType.Button)
                title("Attach files")
                onClick { onAttachClick() }
            }

            tag("form") {
                onEvent<web.events.Event>("submit") { e ->
                    e.preventDefault()
                    onSubmit()
                }

                textArea(
                    value = promptText,
                    rows = 3,
                    placeholder = "Send a message...",
                    disabled = if (agentWorking) true else null,
                ) {
                    onInput {
                        onPromptInput(this.value ?: "")
                    }
                    onKeydown { e -> onKeydown(e) }
                    setDropTarget { e ->
                        val files = e.dataTransfer?.files ?: return@setDropTarget
                        if (files.length > 0) {
                            onDropFiles(files)
                        }
                    }
                    onEvent<web.clipboard.ClipboardEvent>("paste") { e ->
                        onPaste(e)
                    }
                }

                div(className = "input-actions") {
                    div(className = "btn-row") {
                        if (agentWorking) {
                            button("Cancel") {
                                className("btn-cancel")
                                type(ButtonType.Submit)
                            }
                            if (debugMode) {
                                button("Diagnose") {
                                    className("btn-diagnose")
                                    type(ButtonType.Button)
                                    onClick { onDiagnose() }
                                }
                            }
                        } else {
                            button("Send") {
                                className("btn-send")
                                type(ButtonType.Submit)
                            }
                        }
                    }
                    if (debugMode) {
                        label(className = "screenshot-label") {
                            tag("input") {
                                attribute("type", "checkbox")
                                if (screenshotEnabled) attribute("checked", "")
                                onEvent<web.events.Event>("change") {
                                    onScreenshotToggle()
                                }
                            }
                            +"Screenshot"
                        }
                        button("Download Log") {
                            className("btn-download-log")
                            type(ButtonType.Button)
                            onClick { onDownloadLog() }
                        }
                    }
                }
            }
        }
    }
}
