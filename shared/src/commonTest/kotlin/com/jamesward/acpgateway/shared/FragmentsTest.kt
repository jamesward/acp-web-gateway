package com.jamesward.acpgateway.shared

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class FragmentsTest {

    // ---- userMessageHtml ----

    @Test
    fun userMessageHasCorrectClasses() {
        val html = userMessageHtml("hello")
        assertTrue(html.contains("""class="${Css.MSG_WRAP_USER}""""))
        assertTrue(html.contains("""class="${Css.MSG_USER}""""))
    }

    @Test
    fun userMessageContainsText() {
        val html = userMessageHtml("hello world")
        assertTrue(html.contains("hello world"))
    }

    @Test
    fun userMessageEscapesHtml() {
        val html = userMessageHtml("<script>alert('xss')</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    // ---- assistantMessageHtml ----

    @Test
    fun assistantMessageHasCorrectClasses() {
        val html = assistantMessageHtml("hi")
        assertTrue(html.contains(Css.MSG_WRAP_ASSISTANT))
        assertTrue(html.contains(Css.CONTENT_BLOCK))
        assertTrue(html.contains(Css.CONTENT_HEADER))
        assertTrue(html.contains(Css.MSG_ASSISTANT))
        assertTrue(html.contains(Css.MSG_CONTENT))
        assertTrue(html.contains("Response"))
        assertTrue(html.contains("<details"))
    }

    @Test
    fun assistantMessageSetsIdWhenProvided() {
        val html = assistantMessageHtml("hi", msgId = "msg-123")
        assertTrue(html.contains("""id="msg-123""""))
    }

    @Test
    fun assistantMessageOmitsIdWhenNull() {
        val html = assistantMessageHtml("hi")
        assertFalse(html.contains("""id="""))
    }

    @Test
    fun assistantMessageHasPreWrapStyle() {
        val html = assistantMessageHtml("hi")
        assertTrue(html.contains("white-space: pre-wrap"))
    }

    // ---- assistantRenderedHtml ----

    @Test
    fun assistantRenderedHasCorrectClasses() {
        val html = assistantRenderedHtml("<p>hello</p>")
        assertTrue(html.contains(Css.CONTENT_BLOCK))
        assertTrue(html.contains(Css.CONTENT_HEADER))
        assertTrue(html.contains(Css.MSG_ASSISTANT))
        assertTrue(html.contains(Css.MSG_CONTENT))
        assertTrue(html.contains("Response"))
        assertTrue(html.contains("<details"))
    }

    @Test
    fun assistantRenderedInjectsRawHtml() {
        val html = assistantRenderedHtml("<strong>bold</strong>")
        assertTrue(html.contains("<strong>bold</strong>"))
    }

    @Test
    fun assistantRenderedSetsIdWhenProvided() {
        val html = assistantRenderedHtml("<p>hi</p>", msgId = "msg-456")
        assertTrue(html.contains("""id="msg-456""""))
    }

    @Test
    fun assistantRenderedDoesNotHavePreWrap() {
        val html = assistantRenderedHtml("<p>hi</p>")
        assertFalse(html.contains("white-space: pre-wrap"))
    }

    @Test
    fun assistantRenderedPreservesNewlinesInRawHtml() {
        // CommonMark renders soft breaks as \n within <p> tags
        // These must be preserved so browsers render them as spaces
        val rendered = "<p>First sentence.\nSecond sentence.</p>\n"
        val html = assistantRenderedHtml(rendered)
        assertFalse(html.contains("sentence.Second"), "Newline between sentences must not be stripped")
    }

    // ---- thoughtMessageHtml ----

    @Test
    fun thoughtMessageHasCorrectClasses() {
        val html = thoughtMessageHtml("thinking...")
        assertTrue(html.contains(Css.MSG_WRAP_ASSISTANT))
        assertTrue(html.contains(Css.CONTENT_BLOCK))
        assertTrue(html.contains(Css.CONTENT_THOUGHT))
        assertTrue(html.contains(Css.CONTENT_HEADER))
        assertTrue(html.contains(Css.MSG_THOUGHT))
        assertTrue(html.contains(Css.MSG_CONTENT))
        assertTrue(html.contains("Thinking"))
        assertTrue(html.contains("<details"))
    }

    @Test
    fun thoughtMessageSetsIdWhenProvided() {
        val html = thoughtMessageHtml("thinking...", thoughtId = "thought-1")
        assertTrue(html.contains("""id="thought-1""""))
    }

    @Test
    fun thoughtMessageHasElapsedSpan() {
        val html = thoughtMessageHtml("thinking...")
        assertTrue(html.contains("""id="${Id.THOUGHT_ELAPSED}""""))
        assertTrue(html.contains(Css.THOUGHT_ELAPSED))
    }

    @Test
    fun thoughtRenderedHasElapsedSpan() {
        val html = thoughtRenderedHtml("<p>thinking</p>")
        assertTrue(html.contains("""id="${Id.THOUGHT_ELAPSED}""""))
        assertTrue(html.contains(Css.THOUGHT_ELAPSED))
    }

    // ---- errorMessageHtml ----

    @Test
    fun errorMessageHasCorrectClasses() {
        val html = errorMessageHtml("something broke")
        assertTrue(html.contains("""class="${Css.MSG_WRAP_ERROR}""""))
        assertTrue(html.contains("""class="${Css.MSG_ERROR}""""))
    }

    @Test
    fun errorMessageContainsText() {
        val html = errorMessageHtml("connection lost")
        assertTrue(html.contains("connection lost"))
    }

    // ---- statusTimerHtml ----

    @Test
    fun statusTimerHasCorrectStructure() {
        val html = statusTimerHtml("Running", "45s")
        assertTrue(html.contains("""id="${Id.TASK_STATUS_WRAP}""""))
        assertTrue(html.contains("""id="${Id.TASK_STATUS}""""))
        assertTrue(html.contains("""class="${Css.STATUS_WRAP}""""))
        assertTrue(html.contains("""class="${Css.STATUS_TEXT}""""))
    }

    @Test
    fun statusTimerFormatsActivityAndElapsed() {
        val html = statusTimerHtml("Running", "1m 30s")
        assertTrue(html.contains("Running \u00b7 1m 30s"))
    }

    // ---- toolBlockHtml ----

    @Test
    fun toolBlockSetsIdWhenProvided() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries, blockId = "tools-1")
        assertTrue(html.contains("""id="tools-1""""))
    }

    @Test
    fun toolBlockHasCorrectWrapperClass() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("""class="${Css.MSG_WRAP_ASSISTANT}""""))
        assertTrue(html.contains("""class="${Css.TOOL_BLOCK}""""))
    }

    @Test
    fun toolBlockShowsSummaryForSingleCompletedTool() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("1 tool call"))
        assertTrue(html.contains("1 done"))
        assertFalse(html.contains("tool calls")) // singular
    }

    @Test
    fun toolBlockShowsSummaryForMultipleTools() {
        val entries = listOf(
            ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed),
            ToolCallDisplay("tc-2", "Write file", ToolStatus.InProgress),
            ToolCallDisplay("tc-3", "Delete file", ToolStatus.Failed),
        )
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("3 tool calls"))
        assertTrue(html.contains("1 done"))
        assertTrue(html.contains("1 failed"))
        assertTrue(html.contains("1 running"))
    }

    @Test
    fun toolBlockShowsActiveToolName() {
        val entries = listOf(
            ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed),
            ToolCallDisplay("tc-2", "Write file", ToolStatus.InProgress),
        )
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("Write file"))
        assertTrue(html.contains(Css.TOOL_ACTIVE))
        assertTrue(html.contains(Css.PULSE))
    }

    @Test
    fun toolBlockDoesNotShowActiveToolWhenAllDone() {
        val entries = listOf(
            ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed),
            ToolCallDisplay("tc-2", "Write file", ToolStatus.Completed),
        )
        val html = toolBlockHtml(entries)
        assertFalse(html.contains(Css.TOOL_ACTIVE))
        assertFalse(html.contains(Css.PULSE))
    }

    @Test
    fun toolBlockCompletedEntryHasCheckIcon() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains(Css.TOOL_ICON_DONE))
        assertTrue(html.contains("\u2713")) // checkmark
    }

    @Test
    fun toolBlockFailedEntryHasCrossIcon() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Failed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains(Css.TOOL_ICON_FAIL))
        assertTrue(html.contains("\u2717")) // cross mark
    }

    @Test
    fun toolBlockRunningEntryHasCircleIcon() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.InProgress))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains(Css.TOOL_ICON_RUNNING))
        assertTrue(html.contains("\u25CB")) // circle
    }

    @Test
    fun toolBlockEntryWithContentHasNestedDetails() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed, content = "file contents here"))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains(Css.TOOL_ROW_CLICKABLE))
        assertTrue(html.contains(Css.TOOL_RESULT))
        assertTrue(html.contains("file contents here"))
        assertTrue(html.contains(Css.TOOL_RESULT_CHEVRON))
    }

    @Test
    fun toolBlockEntryWithoutContentHasNoNestedDetails() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertFalse(html.contains(Css.TOOL_ROW_CLICKABLE))
        assertFalse(html.contains(Css.TOOL_RESULT))
    }

    @Test
    fun toolBlockEntryHasIdAttribute() {
        val entries = listOf(ToolCallDisplay("tc-abc", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("""id="tc-tc-abc""""))
    }

    @Test
    fun toolBlockHasDetailsAndSummaryElements() {
        val entries = listOf(ToolCallDisplay("tc-1", "Read file", ToolStatus.Completed))
        val html = toolBlockHtml(entries)
        assertTrue(html.contains("<details>"))
        assertTrue(html.contains("<summary"))
        assertTrue(html.contains("</details>"))
    }

    @Test
    fun toolBlockWithManyEntriesRendersAllRows() {
        val entries = (1..45).map { i ->
            ToolCallDisplay("tc-$i", "Tool $i", ToolStatus.Completed, content = "result $i")
        }
        val html = toolBlockHtml(entries, blockId = "tools-test")
        // Summary should show 45
        assertTrue(html.contains("45 tool calls"))
        assertTrue(html.contains("45 done"))
        // Every entry should have its own row with unique ID
        for (i in 1..45) {
            assertTrue(html.contains("""id="tc-tc-$i""""), "Missing row for tc-$i")
            assertTrue(html.contains("Tool $i"), "Missing title for Tool $i")
            assertTrue(html.contains("result $i"), "Missing content for result $i")
        }
        // Count tool-row occurrences
        val rowCount = Regex("""class="${Css.TOOL_ROW}"""").findAll(html).count()
        assertEquals(45, rowCount, "Should have exactly 45 tool rows")
    }

    @Test
    fun toolBlockWithManyEntriesHasUniqueRowIds() {
        val entries = (1..10).map { ToolCallDisplay("id-$it", "Tool $it", ToolStatus.Completed) }
        val html = toolBlockHtml(entries)
        val ids = Regex("""id="tc-id-(\d+)"""").findAll(html).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..10).toList(), ids, "Tool row IDs should appear in order")
    }

    // ---- permissionContentHtml ----

    @Test
    fun permissionHasCorrectStructure() {
        val html = permissionContentHtml(
            toolCallId = "tc-1",
            title = "Read /etc/passwd",
            options = listOf(
                PermissionOptionInfo(optionId = "allow_once", name = "Allow Once", kind = PermissionKind.AllowOnce),
                PermissionOptionInfo(optionId = "reject_once", name = "Deny Once", kind = PermissionKind.RejectOnce),
            ),
        )
        assertTrue(html.contains(Css.PERM_HEADING))
        assertTrue(html.contains("Permission Required"))
        assertTrue(html.contains(Css.PERM_DESC))
        assertTrue(html.contains("Read /etc/passwd"))
    }

    @Test
    fun permissionButtonsHaveDataAttributes() {
        val html = permissionContentHtml(
            toolCallId = "tc-42",
            title = "Write file",
            options = listOf(
                PermissionOptionInfo(optionId = "opt-allow", name = "Allow", kind = PermissionKind.AllowOnce),
                PermissionOptionInfo(optionId = "opt-deny", name = "Deny", kind = PermissionKind.RejectOnce),
            ),
        )
        assertTrue(html.contains("""data-tool-call-id="tc-42""""))
        assertTrue(html.contains("""data-option-id="opt-allow""""))
        assertTrue(html.contains("""data-option-id="opt-deny""""))
    }

    @Test
    fun permissionAllowButtonHasAllowClass() {
        val html = permissionContentHtml(
            toolCallId = "tc-1",
            title = "test",
            options = listOf(PermissionOptionInfo(optionId = "opt-1", name = "Allow", kind = PermissionKind.AllowOnce)),
        )
        assertTrue(html.contains(Css.PERM_BTN_ALLOW))
    }

    @Test
    fun permissionDenyButtonHasDenyClass() {
        val html = permissionContentHtml(
            toolCallId = "tc-1",
            title = "test",
            options = listOf(PermissionOptionInfo(optionId = "opt-1", name = "Deny", kind = PermissionKind.RejectOnce)),
        )
        assertTrue(html.contains(Css.PERM_BTN_DENY))
    }

    // ---- filePreviewHtml ----

    @Test
    fun filePreviewRendersChips() {
        val html = filePreviewHtml(listOf("photo.png", "data.csv"))
        // Two file chips
        val chipCount = Regex(Css.FILE_CHIP).findAll(html).count()
        assertEquals(2, chipCount)
        assertTrue(html.contains("photo.png"))
        assertTrue(html.contains("data.csv"))
    }

    @Test
    fun filePreviewRemoveButtonsHaveDataIndex() {
        val html = filePreviewHtml(listOf("a.txt", "b.txt", "c.txt"))
        assertTrue(html.contains("""data-file-index="0""""))
        assertTrue(html.contains("""data-file-index="1""""))
        assertTrue(html.contains("""data-file-index="2""""))
    }

    @Test
    fun filePreviewRemoveButtonHasCorrectClass() {
        val html = filePreviewHtml(listOf("test.txt"))
        assertTrue(html.contains(Css.FILE_REMOVE))
        assertTrue(html.contains("\u00d7")) // × symbol
    }
}
