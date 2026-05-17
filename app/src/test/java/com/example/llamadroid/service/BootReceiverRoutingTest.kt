package com.example.llamadroid.service

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class BootReceiverRoutingTest {

    @Test
    fun `llama scheduler boot receiver only reschedules`() {
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.RESCHEDULE_ALL,
            resolveLlamaScheduledTaskBootRoute(Intent.ACTION_BOOT_COMPLETED)
        )
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.RESCHEDULE_ALL,
            resolveLlamaScheduledTaskBootRoute(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.IGNORE,
            resolveLlamaScheduledTaskRuntimeRoute(Intent.ACTION_BOOT_COMPLETED, taskId = -1L, logId = -1L)
        )
    }

    @Test
    fun `llama scheduler runtime receiver keeps foreground work off boot route`() {
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.CATCH_UP_RUN,
            resolveLlamaScheduledTaskRuntimeRoute(
                LlamaScheduledTaskScheduler.ACTION_CATCH_UP_RUN,
                taskId = -1L,
                logId = 42L
            )
        )
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.CATCH_UP_SKIP,
            resolveLlamaScheduledTaskRuntimeRoute(
                LlamaScheduledTaskScheduler.ACTION_CATCH_UP_SKIP,
                taskId = -1L,
                logId = 42L
            )
        )
        assertEquals(
            LlamaScheduledTaskBroadcastRoute.DELIVER_DUE_TASK,
            resolveLlamaScheduledTaskRuntimeRoute("custom-fire", taskId = 7L, logId = -1L)
        )
    }

    @Test
    fun `organizer alarm boot receiver only reschedules`() {
        assertEquals(
            OrganizerAlarmBroadcastRoute.RESCHEDULE_ALL,
            resolveOrganizerAlarmBootRoute(Intent.ACTION_BOOT_COMPLETED)
        )
        assertEquals(
            OrganizerAlarmBroadcastRoute.RESCHEDULE_ALL,
            resolveOrganizerAlarmBootRoute(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        assertEquals(
            OrganizerAlarmBroadcastRoute.IGNORE,
            resolveOrganizerAlarmRuntimeRoute(Intent.ACTION_BOOT_COMPLETED, alarmId = -1L)
        )
    }

    @Test
    fun `organizer alarm runtime receiver keeps ringing service off boot route`() {
        assertEquals(
            OrganizerAlarmBroadcastRoute.DELIVER_ALARM,
            resolveOrganizerAlarmRuntimeRoute("custom-fire", alarmId = 99L)
        )
        assertEquals(
            OrganizerAlarmBroadcastRoute.IGNORE,
            resolveOrganizerAlarmRuntimeRoute("custom-fire", alarmId = -1L)
        )
    }
}
