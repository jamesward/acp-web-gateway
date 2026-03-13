package com.jamesward.acpgateway.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffRenderingTest {

    @Test
    fun singleLineChange() {
        val result = renderDiffMarkdown("src/main.kt", "val x = 1", "val x = 2")
        assertTrue(result.startsWith("```diff\n"), "Should start with diff code fence")
        assertTrue(result.endsWith("\n```"), "Should end with code fence")
        assertTrue(result.contains("-val x = 1"), "Should contain removed line")
        assertTrue(result.contains("+val x = 2"), "Should contain added line")
    }

    @Test
    fun multiLineWithContext() {
        val old = "line1\nline2\nline3\nline4\nline5"
        val new = "line1\nline2\nchanged\nline4\nline5"
        val result = renderDiffMarkdown("file.txt", old, new)
        assertTrue(result.contains("-line3"))
        assertTrue(result.contains("+changed"))
        assertTrue(result.contains(" line2"), "Should contain context line")
        assertTrue(result.contains(" line4"), "Should contain context line")
    }

    @Test
    fun newFileNoOldText() {
        val result = renderDiffMarkdown("new.kt", null, "val x = 1\nval y = 2")
        assertTrue(result.contains("+val x = 1"))
        assertTrue(result.contains("+val y = 2"))
    }

    @Test
    fun noChanges() {
        val result = renderDiffMarkdown("same.kt", "val x = 1", "val x = 1")
        assertEquals("No changes", result)
    }

    @Test
    fun usesFileNameInHeader() {
        val result = renderDiffMarkdown("src/main/kotlin/App.kt", "a", "b")
        assertTrue(result.contains("App.kt"), "Should use short filename from path")
    }
}
