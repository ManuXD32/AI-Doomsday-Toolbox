package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentPromptMessageOrderingTest {
    @Test
    fun `final ordering moves digest and packet after atomic history before recovery tail`() {
        val input = listOf(
            message("stable", "system", "Stable policy and tool contract."),
            message("packet", "system", "# Project Control Packet\n- decision: current"),
            message("user-1", "user", "Implement the approved change."),
            message("assistant-1", "assistant", "I will edit the file."),
            message("tool-1", "tool", "status: ok"),
            message(
                id = "linked-packet",
                role = "system",
                content = "# Project Control Packet\n- stale child state",
                invocationId = "child-1"
            ),
            message("digest", "system", "CONTEXT DIGEST: Older turns were compacted."),
            message("recovery-help", "system", "RECOVERY TOOL HELP: correct the rejected call."),
            message("recovery", "system", "RECOVERY MODE: retry from current state."),
            message("mode", "system", "CURRENT MODE: BUILD. Finish with evidence.")
        )

        val ordered = reorderOptimizedAgentPromptMessages(input)

        assertEquals(
            listOf(
                "stable",
                "user-1",
                "assistant-1",
                "tool-1",
                "linked-packet",
                "digest",
                "packet",
                "recovery-help",
                "recovery",
                "mode"
            ),
            ordered.map { it.id }
        )
        assertEquals(input.size, ordered.size)
        assertEquals(input.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        assertEquals(
            listOf("user-1", "assistant-1", "tool-1"),
            ordered.filter { it.id in setOf("user-1", "assistant-1", "tool-1") }
                .map { it.id }
        )
        assertSame(input[0], ordered[0])
        assertSame(input[2], ordered[1])
        assertSame(input[3], ordered[2])
        assertSame(input[4], ordered[3])
        assertSame(input[5], ordered[4])
        assertEquals("stable", ordered.first().id)
    }

    @Test
    fun `final ordering is idempotent and leaves ordinary system messages in place`() {
        val input = listOf(
            message("stable", "system", "Stable policy."),
            message("ordinary", "system", "The current file is authoritative."),
            message("user", "user", "Keep this request unchanged."),
            message("digest", "system", "CONTEXT DIGEST: prior evidence."),
            message("packet", "system", "# Project Control Packet\n- decision: new"),
            message("mode", "system", "CURRENT MODE: VERIFY.")
        )

        val once = reorderOptimizedAgentPromptMessages(input)
        val twice = reorderOptimizedAgentPromptMessages(once)

        assertEquals(once, twice)
        assertEquals(
            listOf("stable", "ordinary", "user", "digest", "packet", "mode"),
            once.map { it.id }
        )
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        invocationId: String? = null
    ): AgentService.Companion.ChatMessage = AgentService.Companion.ChatMessage(
        id = id,
        role = role,
        content = content,
        invocationId = invocationId
    )
}
