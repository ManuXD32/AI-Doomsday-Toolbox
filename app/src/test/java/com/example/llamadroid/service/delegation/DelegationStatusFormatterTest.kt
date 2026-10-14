package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationStatusFormatterTest {
    private fun task(state: String, summary: String? = null, files: String? = null) = NativeChatDelegateTaskEntity(
        id = 1, chatId = 2, state = state, title = "Build a thing", request = "x", runtimeId = "rt",
        progressNote = "p".repeat(2_000), resultSummary = summary, resultFilesJson = files,
        checkCadenceMinutes = 15, createdAt = 0L
    )

    @Test
    fun activeStatusHasFooterAndRespectsBounds() {
        val live = DelegationLiveStatus(1, 2, NativeChatDelegateTaskState.RUNNING, "harness_wait",
            subagentContextUsed = 6_100, subagentContextWindow = 10_000, pendingAttention = 2)
        for ((detail, limit) in listOf(
            DelegationStatusDetail.BRIEF to DelegationStatusFormatter.BRIEF_MAX_CHARS,
            DelegationStatusDetail.FULL to DelegationStatusFormatter.FULL_MAX_CHARS
        )) {
            val text = DelegationStatusFormatter.statusText(task(NativeChatDelegateTaskState.RUNNING), live, detail, nowMs = 60_000L)
            assertTrue(text.length <= limit)
            assertTrue(text.contains("deepseek_schedule_check(minutes=N)"))
            assertTrue(text.contains("last cadence: 15 min"))
            assertTrue(text.contains("61%"))
        }
    }

    @Test
    fun finishedStatusHasResultAndNoFooter() {
        val files = """[{"path":"out/report.pdf","name":"report.pdf","attachmentId":12}]"""
        val text = DelegationStatusFormatter.statusText(
            task(NativeChatDelegateTaskState.COMPLETED, "Done well", files), null, DelegationStatusDetail.BRIEF)
        assertFalse(text.contains("deepseek_schedule_check"))
        assertTrue(text.contains("Done well"))
        assertTrue(text.contains("f12 report.pdf"))
        assertTrue(text.length <= DelegationStatusFormatter.BRIEF_MAX_CHARS)
    }

    @Test
    fun resultNoticeIsBoundedAndTagged() {
        val files = (1..20).map { DelegationResultFileRef("file_number_$it.txt", it.toLong()) }
        val notice = DelegationResultNotice.finished("T".repeat(200), "success", "s".repeat(10_000), files)
        assertTrue(notice.length <= DelegationResultNotice.MAX_CHARS)
        assertTrue(notice.startsWith("[DeepSeek task finished]"))
        assertTrue(DelegationResultNotice.needsDirection("why").contains("deepseek_task_cancel"))
        assertEquals("[DeepSeek subagent question] q Answer with deepseek_task_message.", DelegationResultNotice.question("q"))
    }
}
