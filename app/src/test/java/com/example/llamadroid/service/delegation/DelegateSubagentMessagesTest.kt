package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.service.OllamaService.ChatMessage
import com.example.llamadroid.service.OllamaService.ToolCall
import com.example.llamadroid.service.compaction.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateSubagentMessagesTest {
    private fun row(seq: Int, role: String, content: String = "c$seq", calls: String? = null, callId: String? = null,
                    summary: Boolean = false, out: Boolean = false) =
        NativeChatDelegateMessageEntity(id = seq.toLong(), taskId = 1, seq = seq, role = role, content = content,
            toolCallsJson = calls, toolCallId = callId, isSummary = summary, compactedOut = out)

    @Test
    fun summaryRowComesFirstAndCompactedRowsAreDropped() {
        val rows = listOf(
            row(1, NativeChatDelegateMessageRole.USER, out = true),
            row(2, NativeChatDelegateMessageRole.ASSISTANT),
            row(3, NativeChatDelegateMessageRole.USER, "sum", summary = true),
            row(4, NativeChatDelegateMessageRole.NOTICE),
            row(5, NativeChatDelegateMessageRole.SUB_TO_MAIN)
        )
        val model = DelegateSubagentMessages.modelRows(rows)
        assertEquals(listOf(3, 2), model.map { it.seq })
        val merged = DelegateSubagentMessages.mergeConsecutiveUsers(
            listOf(ChatMessage("user", "a"), ChatMessage("user", "b"), ChatMessage("assistant", "c")))
        assertEquals(2, merged.size)
        assertEquals("a\n\nb", merged[0].content)
    }

    @Test
    fun toolCallsRoundTripAndMissingResultsAreFound() {
        val calls = listOf(
            ToolCall("harness_wait", mapOf("max_seconds" to "60"), id = "c1", rawArgumentsJson = "{\"max_seconds\":60}"),
            ToolCall("harness_read", mapOf("what" to "list"), id = "c2")
        )
        val json = DelegateSubagentMessages.toolCallsJson(calls)
        val back = DelegateSubagentMessages.parseToolCalls(json)
        assertEquals(listOf("harness_wait", "harness_read"), back.map { it.name })
        assertEquals("60", back[0].arguments["max_seconds"])
        assertEquals("list", back[1].arguments["what"])
        val rows = listOf(
            row(1, NativeChatDelegateMessageRole.USER),
            row(2, NativeChatDelegateMessageRole.ASSISTANT, calls = json),
            row(3, NativeChatDelegateMessageRole.TOOL, callId = "c1")
        )
        assertEquals(listOf("c2"), DelegateSubagentMessages.missingToolResults(rows).map { it.id })
    }

    @Test
    fun compactionCutNeverSplitsToolPairsAndKeepsTwoSteps() {
        val msgs = mutableListOf(ChatMessage("user", "brief ".repeat(500)))
        repeat(6) { i ->
            msgs += ChatMessage("assistant", "", toolCalls = listOf(ToolCall("t", emptyMap(), id = "i$i")))
            msgs += ChatMessage("tool", "result ".repeat(400), toolCallId = "i$i")
        }
        val cut = DelegateSubagentCompaction.planCut(msgs, TokenEstimator())
        assertNotNull(cut)
        assertTrue(msgs[cut!!].role != "tool")
        assertTrue(msgs.subList(cut, msgs.size).count { it.role == "assistant" } >= 2)
        assertTrue(cut >= 1)
    }

    @Test
    fun tooShortConversationsAreNotCompacted() {
        val msgs = listOf(ChatMessage("user", "x"), ChatMessage("assistant", "y"), ChatMessage("user", "z"))
        assertNull(DelegateSubagentCompaction.planCut(msgs, TokenEstimator()))
    }

    @Test
    fun promptKeepsSystemTextStableAndPolicyAware() {
        val a = DelegateSubagentPrompt.systemPrompt()
        assertEquals(a, DelegateSubagentPrompt.systemPrompt())
        assertTrue(DelegateSubagentPrompt.policyRules(DelegationApprovalPolicy.ASK_ME).contains("answered by the user"))
        assertTrue(DelegateSubagentPrompt.inboxMessage(" hi ").startsWith("[Main agent] hi"))
    }
}
