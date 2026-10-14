package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationPoliciesTest {
    @Test
    fun alarmMinutesClampAndFireTime() {
        assertEquals(2, DelegationAlarmPolicy.clampMinutes(1))
        assertEquals(1440, DelegationAlarmPolicy.clampMinutes(99_999))
        assertEquals(1_000L + 600_000L, DelegationAlarmPolicy.fireAt(1_000L, 10))
    }

    @Test
    fun alarmDecisions() {
        assertEquals(DelegationAlarmPolicy.Decision.Cancel, DelegationAlarmPolicy.decide(0, 0, true, 0L))
        assertEquals(DelegationAlarmPolicy.Decision.Refused, DelegationAlarmPolicy.decide(10, 96, true, 0L))
        val exact = DelegationAlarmPolicy.decide(10, 95, true, 100L) as DelegationAlarmPolicy.Decision.Schedule
        assertTrue(exact.exact)
        assertEquals(600_100L, exact.fireAt)
        val inexact = DelegationAlarmPolicy.decide(1, 0, false, 0L) as DelegationAlarmPolicy.Decision.Schedule
        assertFalse(inexact.exact)
        assertEquals(2, inexact.minutes)
    }

    @Test
    fun requestCodeIsStablePerTask() {
        assertEquals(DelegationAlarmPolicy.requestCode(7), DelegationAlarmPolicy.requestCode(7))
        assertTrue(DelegationAlarmPolicy.requestCode(7) != DelegationAlarmPolicy.requestCode(8))
    }

    @Test
    fun wakeMinIntervalAndCaps() {
        assertEquals(0L, DelegationWakePolicy.waitMs(null, 1_000L))
        assertEquals(40_000L, DelegationWakePolicy.waitMs(0L, 20_000L))
        assertEquals(0L, DelegationWakePolicy.waitMs(0L, 70_000L))
        assertFalse(DelegationWakePolicy.wakeCapReached(59))
        assertTrue(DelegationWakePolicy.wakeCapReached(60))
        assertFalse(DelegationWakePolicy.queueTooDeep(8))
        assertTrue(DelegationWakePolicy.queueTooDeep(9))
    }

    @Test
    fun coalesceDedupesAndBounds() {
        assertEquals("a\n\nb", DelegationWakePolicy.coalesce(listOf("a", " a ", "b", "")))
        val long = DelegationWakePolicy.coalesce(listOf("x".repeat(1_500), "y".repeat(1_500)))
        assertTrue(long.length <= DelegationWakePolicy.MAX_NOTICE_CHARS)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun mergeKeepsMostImportantKind() {
        assertEquals(
            NativeChatDelegationWakeKind.TASK_FINISHED,
            DelegationWakePolicy.mergeKind(NativeChatDelegationWakeKind.TASK_FINISHED, NativeChatDelegationWakeKind.CHECK_ALARM)
        )
        assertEquals(
            NativeChatDelegationWakeKind.TASK_FINISHED,
            DelegationWakePolicy.mergeKind(NativeChatDelegationWakeKind.CHECK_ALARM, NativeChatDelegationWakeKind.TASK_FINISHED)
        )
        assertEquals(
            NativeChatDelegationWakeKind.CHECK_ALARM,
            DelegationWakePolicy.mergeKind(null, NativeChatDelegationWakeKind.CHECK_ALARM)
        )
        assertFalse(DelegationWakePolicy.notifiesUser(NativeChatDelegationWakeKind.CHECK_ALARM))
        assertTrue(DelegationWakePolicy.notifiesUser(NativeChatDelegationWakeKind.TASK_FINISHED))
    }

    @Test
    fun scheduledCheckNoticeText() {
        val text = DelegationNotices.scheduledCheck("Build site", "look at logs", 20)
        assertTrue(text.startsWith("[DeepSeek scheduled check]"))
        assertTrue(text.contains("\"Build site\""))
        assertTrue(text.contains("note: look at logs"))
        assertTrue(text.contains("last cadence: 20 minutes"))
        assertFalse(DelegationNotices.scheduledCheck("t", null, null).contains("note:"))
    }
}
