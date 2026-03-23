package com.jamesward.acpgateway.web

import androidx.compose.runtime.Composable
import com.jamesward.acpgateway.shared.CommandInfo
import com.jamesward.acpgateway.shared.FileAttachment
import com.jamesward.acpgateway.shared.ResourceLinkInfo
import dev.kilua.core.IComponent
import dev.kilua.form.text.textArea
import dev.kilua.html.*
import web.keyboard.KeyboardEvent

@Composable
fun IComponent.inputBar(
    pendingFiles: List<FileAttachment>,
    pendingResourceLinks: List<ResourceLinkInfo>,
    autocompleteFiltered: List<CommandInfo>,
    autocompleteSelectedIndex: Int,
    fileAutocompleteResults: List<String>,
    fileAutocompleteSelectedIndex: Int,
    promptText: String,
    agentWorking: Boolean,
    debugMode: Boolean,
    screenshotEnabled: Boolean,
    onRemoveFile: (index: Int) -> Unit,
    onRemoveResourceLink: (index: Int) -> Unit,
    onCompleteCommand: (name: String) -> Unit,
    onCompleteFile: (path: String) -> Unit,
    onAttachClick: () -> Unit,
    onSubmit: () -> Unit,
    onPromptInput: (String) -> Unit,
    onKeydown: (KeyboardEvent) -> Unit,
    onDropFiles: (web.file.FileList) -> Unit,
    onPaste: (web.clipboard.ClipboardEvent) -> Unit,
    onScreenshotToggle: () -> Unit,
    onDownloadLog: () -> Unit,
) {
    div(className = "input-bar") {
        // File preview
        if (pendingFiles.isNotEmpty() || pendingResourceLinks.isNotEmpty()) {
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
                for ((index, link) in pendingResourceLinks.withIndex()) {
                    span(className = "file-chip file-ref") {
                        +"@${link.name}"
                        button("\u00d7") {
                            title("Remove")
                            onClick { onRemoveResourceLink(index) }
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

        // File reference autocomplete
        if (fileAutocompleteResults.isNotEmpty()) {
            div(className = "file-autocomplete") {
                for ((i, file) in fileAutocompleteResults.withIndex()) {
                    val lastSlash = file.lastIndexOf('/')
                    val fileName = if (lastSlash >= 0) file.substring(lastSlash + 1) else file
                    val dirPath = if (lastSlash >= 0) file.substring(0, lastSlash + 1) else ""
                    button {
                        className(if (i == fileAutocompleteSelectedIndex) "file-ref-btn selected" else "file-ref-btn")
                        type(ButtonType.Button)
                        onClick { onCompleteFile(file) }
                        span(className = "file-ref-name") { +fileName }
                        if (dirPath.isNotEmpty()) {
                            span(className = "file-ref-path") { +dirPath }
                        }
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
                    id("prompt-input")
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
