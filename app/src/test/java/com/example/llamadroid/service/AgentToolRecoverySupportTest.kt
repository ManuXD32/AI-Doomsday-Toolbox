package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRecoverySupportTest {
    @Test
    fun `iteration four plain tool line is reported as malformed attempted tool`() {
        val fixtureExcerpt = """
            Action rationale: Finalize the implementation plan based on verified research.
            Tool call: propose_plan
            Action rationale: Finalize the implementation plan based on verified research.

            **Plan Summary:** Build a static web UI for prime number calculation.
        """.trimIndent()

        val attempt = detectExplicitPlainTextToolCallAttempt(
            text = fixtureExcerpt,
            availableToolNames = listOf("fetch_url", "propose_plan")
        )

        assertEquals("propose_plan", attempt?.suspectedToolName)
        assertEquals("assistant text", attempt?.source)
        assertTrue(attempt?.error?.startsWith("MALFORMED_TOOL_CALL_PLAIN_TEXT:") == true)

        val correctedShape = detectExplicitPlainTextToolCallAttempt(
            text = "Tool call: propose_plan (plan=Plan for PrimeSpark)",
            availableToolNames = listOf("fetch_url", "propose_plan")
        )
        assertEquals("propose_plan", correctedShape?.suspectedToolName)
        assertTrue(correctedShape?.error?.startsWith("MALFORMED_TOOL_CALL_PLAIN_TEXT:") == true)
    }

    @Test
    fun `detector ignores embedded unknown and decorated plain text`() {
        val available = listOf("propose_plan", "fetch_url")

        assertNull(
            detectExplicitPlainTextToolCallAttempt(
                "Action rationale: Tool call: propose_plan",
                available
            )
        )
        assertNull(
            detectExplicitPlainTextToolCallAttempt(
                "Tool call: unknown_tool",
                available
            )
        )
        assertNull(
            detectExplicitPlainTextToolCallAttempt(
                "Tool call: propose_plan - wait for approval",
                available
            )
        )
        assertNull(
            detectExplicitPlainTextToolCallAttempt(
                "- Tool call: propose_plan",
                available
            )
        )
    }

    @Test
    fun `local directory path failures give root discovery guidance`() {
        val absolutePathHint = localPathRecoveryHint(
            toolName = "list_directory",
            localBackend = true,
            error = IllegalArgumentException("Absolute paths are not allowed: /workspace/Counter")
        )
        assertTrue(absolutePathHint?.contains("`.") == true)
        assertTrue(absolutePathHint?.contains("selected project root") == true)
        assertTrue(absolutePathHint?.contains("/workspace") == true)
        assertTrue(absolutePathHint?.contains("observed child paths") == true)
        assertTrue(absolutePathHint.orEmpty().length <= 200)

        val missingDirectoryHint = localPathRecoveryHint(
            toolName = "list_directory",
            localBackend = true,
            error = IllegalStateException("Workspace directory is unavailable: /local_workspace/Counter/src")
        )
        assertEquals(absolutePathHint, missingDirectoryHint)

        val missingArgumentHint = localPathRecoveryHint(
            toolName = "list_directory",
            localBackend = true,
            error = IllegalArgumentException("Tool `list_directory` is missing required arguments: path.")
        )
        assertEquals(absolutePathHint, missingArgumentHint)
    }

    @Test
    fun `local file path failures explain project relative lookup`() {
        val hint = localPathRecoveryHint(
            toolName = "read_file",
            localBackend = true,
            error = IllegalArgumentException("File not found: /local_workspace/Counter/src/app.js")
        )

        assertTrue(hint?.contains("relative to the project root") == true)
        assertTrue(hint?.contains("list_directory") == true)
        assertTrue(hint?.contains("/workspace") == true)
        assertTrue(hint.orEmpty().length <= 200)
    }

    @Test
    fun `path hint is absent for remote backend unrelated errors and unknown markers`() {
        assertNull(
            localPathRecoveryHint(
                toolName = "list_directory",
                localBackend = false,
                error = IllegalArgumentException("Absolute paths are not allowed: /workspace/Counter")
            )
        )
        assertNull(
            localPathRecoveryHint(
                toolName = "run_command",
                localBackend = true,
                error = IllegalArgumentException("Absolute paths are not allowed: /workspace/Counter")
            )
        )
        assertNull(
            localPathRecoveryHint(
                toolName = "list_directory",
                localBackend = true,
                error = IllegalStateException("Temporary local runtime unavailable")
            )
        )
        assertNull(localPathRecoveryHint("read_file", true, null))
    }

    @Test
    fun `path marker in a wrapped local error is still recognized`() {
        val hint = localPathRecoveryHint(
            toolName = "edit_lines",
            localBackend = true,
            error = IllegalStateException(
                "Tool failed",
                IllegalArgumentException("Path traversal is not allowed: ../app.js")
            )
        )

        assertTrue(hint?.contains("relative to the project root") == true)
    }
}
