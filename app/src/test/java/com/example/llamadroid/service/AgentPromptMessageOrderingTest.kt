package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `direct control capsule stays at the tail after append only history`() {
        val input = listOf(
            message("stable", "system", "Stable direct policy."),
            message("capsule", "system", "CONTROL_CAPSULE v=1\nnext_action=write index.html"),
            message("user", "user", "Build the approved project."),
            message("assistant", "assistant", "Writing the first artifact."),
            message("tool", "tool", "write_file completed"),
            message("checkpoint", "system", "CHECKPOINT phase=BUILD; perform the exact next action.")
        )

        val ordered = reorderOptimizedAgentPromptMessages(input)

        assertEquals(
            listOf("stable", "user", "assistant", "tool", "checkpoint", "capsule"),
            ordered.map { it.id }
        )
        assertEquals(ordered, reorderOptimizedAgentPromptMessages(ordered))
    }

    @Test
    fun `historical direct capsules remain byte ordered while newest capsule is authoritative tail`() {
        val input = listOf(
            message("stable", "system", "Stable direct policy."),
            message(
                "old-checkpoint",
                "system",
                "CHECKPOINT phase=PLAN; return the plan."
            ),
            message(
                "old-capsule",
                "system",
                "# Project Control Packet — Direct Control Capsule\n- state_revision: 1"
            ),
            message("assistant", "assistant", "Approved plan response."),
            message("tool", "tool", "write_file completed"),
            message(
                "new-checkpoint",
                "system",
                "CHECKPOINT phase=BUILD; perform the exact next action."
            ),
            message(
                "new-capsule",
                "system",
                "# Project Control Packet — Direct Control Capsule\n- state_revision: 2"
            )
        )

        val ordered = reorderOptimizedAgentPromptMessages(input)

        assertEquals(input.map { it.id }, ordered.map { it.id })
        assertEquals("new-capsule", ordered.last().id)
    }

    @Test
    fun `persisted direct request tail is idempotent for one durable boundary`() {
        val first = buildDirectRequestControlTail(
            conversationId = 7L,
            boundaryMessageId = "tool-12",
            recoveryInstruction = null,
            checkpoint = "CHECKPOINT phase=BUILD",
            capsule = "# Project Control Packet — Direct Control Capsule\n- revision: 8"
        )
        val retry = buildDirectRequestControlTail(
            conversationId = 7L,
            boundaryMessageId = "tool-12",
            recoveryInstruction = null,
            checkpoint = "CHECKPOINT phase=BUILD",
            capsule = "# Project Control Packet — Direct Control Capsule\n- revision: 8"
        )
        val nextBoundary = buildDirectRequestControlTail(
            conversationId = 7L,
            boundaryMessageId = "tool-13",
            recoveryInstruction = null,
            checkpoint = "CHECKPOINT phase=BUILD",
            capsule = "# Project Control Packet — Direct Control Capsule\n- revision: 9"
        )

        assertEquals(first.map { it.id }, retry.map { it.id })
        assertEquals(listOf("system", "system"), first.map { it.role })
        assertTrue(first.last().content.contains("Direct Control Capsule"))
        assertTrue(first.map { it.id }.toSet().intersect(nextBoundary.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `next direct request retains the complete previous request prefix`() {
        val stable = message("stable", "system", "Stable direct policy and tools.")
        val user = message("user", "user", "Build a greenfield WebUI.")
        val firstTail = buildDirectRequestControlTail(
            conversationId = 11L,
            boundaryMessageId = user.id,
            recoveryInstruction = null,
            checkpoint = "CHECKPOINT phase=PLAN",
            capsule = "# Project Control Packet — Direct Control Capsule\n- revision: 1"
        )
        val firstRequest = reorderOptimizedAgentPromptMessages(
            listOf(stable, user) + firstTail
        )
        val assistant = message("assistant", "assistant", "# Plan\n1. Create files")
        val tool = message("tool", "tool", "plan approved")
        val secondTail = buildDirectRequestControlTail(
            conversationId = 11L,
            boundaryMessageId = tool.id,
            recoveryInstruction = null,
            checkpoint = "CHECKPOINT phase=BUILD",
            capsule = "# Project Control Packet — Direct Control Capsule\n- revision: 2"
        )
        val secondRequest = reorderOptimizedAgentPromptMessages(
            firstRequest + assistant + tool + secondTail
        )

        assertEquals(firstRequest, secondRequest.take(firstRequest.size))
        assertTrue(secondRequest.last().content.contains("revision: 2"))
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
