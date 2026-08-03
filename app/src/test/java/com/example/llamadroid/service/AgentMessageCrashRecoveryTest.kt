package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageCrashRecoveryTest {
    @Test
    fun `post approval activity survives entity round trip without replaying pending mutation`() {
        val messages = listOf(
            AgentService.Companion.ChatMessage(
                id = "plan",
                role = "assistant",
                content = "Implementation plan",
                isPlan = true,
                isPlanApproved = true,
                sequenceNumber = 1
            ),
            AgentService.Companion.ChatMessage(
                id = "todo",
                role = "assistant",
                content = "Updated todo state",
                toolName = "todo_write",
                toolCallId = "todo-call",
                sequenceNumber = 2
            ),
            AgentService.Companion.ChatMessage(
                id = "delegation",
                role = "assistant",
                content = "Delegated implementation",
                toolName = "call_agent",
                toolCallId = "delegate-call",
                isDelegation = true,
                sequenceNumber = 3
            ),
            AgentService.Companion.ChatMessage(
                id = "read-result",
                role = "tool",
                content = "Read completed",
                toolName = "read_file",
                toolCallId = "read-call",
                sequenceNumber = 4
            )
        )

        val restored = messages.map { message ->
            AgentService.chatMessageFromEntity(
                AgentService.chatMessageToEntity(message, conversationId = 47L)
            )
        }

        assertEquals(messages.map { it.id }, restored.map { it.id })
        assertTrue(restored.first().isPlanApproved == true)
        assertTrue(restored[2].isDelegation)
        assertFalse(restored.any { it.needsApproval || it.pendingToolCall != null })
        assertEquals(listOf(1, 2, 3, 4), restored.map { it.sequenceNumber })
    }
}
