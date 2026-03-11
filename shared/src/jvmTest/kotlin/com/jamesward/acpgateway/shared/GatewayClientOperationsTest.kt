package com.jamesward.acpgateway.shared

import com.agentclientprotocol.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatewayClientOperationsTest {

    @Test
    fun permissionRequestFlowThroughChannel() = runTest {
        val ops = GatewayClientOperations()

        val toolCall = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-1"),
            title = "Read file",
        )
        val permissions = listOf(
            PermissionOption(
                optionId = PermissionOptionId("allow"),
                name = "Allow",
                kind = PermissionOptionKind.ALLOW_ONCE,
            ),
        )

        // Launch the permission request in background (it will suspend until completed)
        val job = launch {
            val response = ops.requestPermissions(toolCall, permissions)
            assertIs<RequestPermissionOutcome.Selected>(response.outcome)
            assertEquals("allow", (response.outcome as RequestPermissionOutcome.Selected).optionId.value)
        }

        // Read from the pending permissions channel
        val pending = ops.pendingPermissions.receive()
        assertEquals("tc-1", pending.toolCallId)
        assertEquals("Read file", pending.title)
        assertEquals(1, pending.options.size)

        // Complete the permission (simulating browser response)
        ops.completePermission("tc-1", "allow")

        job.join()
    }

    @Test
    fun fsReadTextFile() = runTest {
        val ops = GatewayClientOperations()
        val tmpFile = kotlin.io.path.createTempFile(prefix = "test", suffix = ".txt").toFile()
        try {
            tmpFile.writeText("line1\nline2\nline3")
            val response = ops.fsReadTextFile(tmpFile.absolutePath)
            assertEquals("line1\nline2\nline3", response.content)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun fsReadTextFileWithLineAndLimit() = runTest {
        val ops = GatewayClientOperations()
        val tmpFile = kotlin.io.path.createTempFile(prefix = "test", suffix = ".txt").toFile()
        try {
            tmpFile.writeText("line1\nline2\nline3\nline4\nline5")
            val response = ops.fsReadTextFile(tmpFile.absolutePath, line = 2u, limit = 2u)
            assertEquals("line2\nline3", response.content)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun fsWriteTextFile() = runTest {
        val ops = GatewayClientOperations()
        val tmpFile = kotlin.io.path.createTempFile(prefix = "test", suffix = ".txt").toFile()
        try {
            ops.fsWriteTextFile(tmpFile.absolutePath, "written content")
            assertEquals("written content", tmpFile.readText())
        } finally {
            tmpFile.delete()
        }
    }
}
