package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationMailboxTest {
    private fun row(seq: Int, kind: String?, delivered: Boolean = false, role: String = NativeChatDelegateMessageRole.SUB_TO_MAIN) =
        NativeChatDelegateMessageEntity(id = seq.toLong(), taskId = 1, seq = seq, role = role, content = "c$seq", toolName = kind, delivered = delivered)

    @Test
    fun waiterTakesFirstUndeliveredAfterPostedSeq() {
        val rows = listOf(
            row(3, "reply"), row(5, "progress"), row(7, "question"), row(8, "result"), row(9, "reply", delivered = true)
        )
        assertEquals(7, DelegationMailbox.pickReply(rows, 5)?.seq)
        assertEquals(3, DelegationMailbox.pickReply(rows, 0)?.seq)
        assertEquals(listOf(7, 8), DelegationMailbox.orderedReplies(rows, 5).map { it.seq })
        assertNull(DelegationMailbox.pickReply(rows, 8))
    }

    @Test
    fun otherRolesAndProgressNeverMatch() {
        val rows = listOf(row(2, "progress"), row(3, null, role = NativeChatDelegateMessageRole.MAIN_TO_SUB), row(4, "reply", delivered = true))
        assertNull(DelegationMailbox.pickReply(rows, 0))
    }

    @Test
    fun reopenResetsPerRoundStateAndKeepsConversation() {
        val task = NativeChatDelegateTaskEntity(
            id = 4, chatId = 2, state = NativeChatDelegateTaskState.COMPLETED, title = "t", request = "r", runtimeId = "rt",
            workspaceId = "ws", harnessSessionId = "s1", resultStatus = "success", resultSummary = "done", resultFilesJson = "[]",
            subagentModelCalls = 120, harnessPrompts = 30, alarmCount = 5, wakeCount = 9, compactionCount = 2,
            finishedAt = 99L, nextCheckAt = 5L, pendingWakeKind = "TASK_FINISHED", followUpCount = 1
        )
        val r = DelegationMailbox.reopened(task, "{\"x\":1}", 1000L)
        assertEquals(NativeChatDelegateTaskState.STARTING_RUNTIME, r.state)
        assertEquals(2, r.followUpCount)
        assertNull(r.finishedAt); assertNull(r.resultStatus); assertNull(r.resultSummary); assertNull(r.resultFilesJson)
        assertEquals(0, r.subagentModelCalls); assertEquals(0, r.harnessPrompts)
        assertEquals(0, r.alarmCount); assertEquals(0, r.wakeCount)
        assertNull(r.nextCheckAt); assertNull(r.pendingWakeKind)
        assertEquals("ws", r.workspaceId); assertEquals("s1", r.harnessSessionId); assertEquals(2, r.compactionCount)
        assertEquals("{\"x\":1}", r.optionsJson)
    }

    @Test
    fun wakeDedupe() {
        assertTrue(DelegationMailbox.shouldEnqueueWake(waiterActive = false, claimedRows = 1))
        assertFalse(DelegationMailbox.shouldEnqueueWake(waiterActive = true, claimedRows = 1))
        assertFalse(DelegationMailbox.shouldEnqueueWake(waiterActive = false, claimedRows = 0))
        assertEquals(NativeChatDelegationWakeKind.SUBAGENT_REPLY, DelegationMailbox.wakeKindFor("reply"))
        assertEquals(NativeChatDelegationWakeKind.SUBAGENT_QUESTION, DelegationMailbox.wakeKindFor("question"))
        assertNull(DelegationMailbox.wakeKindFor("result"))
        assertNull(DelegationMailbox.wakeKindFor("progress"))
    }

    @Test
    fun waiterRegistryCounts() {
        assertFalse(DelegationMailbox.hasWaiter(777L))
        DelegationMailbox.beginWait(777L)
        DelegationMailbox.beginWait(777L)
        DelegationMailbox.endWait(777L)
        assertTrue(DelegationMailbox.hasWaiter(777L))
        DelegationMailbox.endWait(777L)
        assertFalse(DelegationMailbox.hasWaiter(777L))
    }

    @Test
    fun followUpContentAndRefs() {
        val c = DelegationMailbox.followUpContent(" change it ", false, listOf("report.pdf"), reopened = true)
        assertTrue(c.startsWith(DelegateSubagentPrompt.INBOX_PREFIX + "change it"))
        assertTrue(c.contains("New input files: report.pdf"))
        assertTrue(c.contains("follow-up on the finished task"))
        assertTrue(DelegationMailbox.followUpContent("x", true, emptyList(), false).startsWith("[User] x"))
        assertEquals(listOf("f1", "f2"), DelegationMailbox.mergeRefs(listOf("f1"), listOf("f1", "f2")))
        assertTrue(DelegationMailbox.stalled(NativeChatDelegateTaskState.INTERRUPTED))
        assertFalse(DelegationMailbox.stalled(NativeChatDelegateTaskState.RUNNING))
    }

    @Test
    fun replyNoticeIsTaggedAndBounded() {
        val n = DelegationResultNotice.reply("x".repeat(5000))
        assertTrue(n.startsWith("[DeepSeek subagent reply] "))
        assertTrue(n.contains("Reply from the DeepSeek subagent (not the user): "))
        assertTrue(n.length < 1_500)
    }
}
