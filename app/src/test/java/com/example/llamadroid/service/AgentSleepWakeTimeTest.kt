package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSleepWakeTimeTest {
    @Test
    fun resolvesRelativeDelayWithinBounds() {
        assertEquals(25_000L, AgentSleepWakeTime.resolveEpochMillis(mapOf("delay_seconds" to "15"), 10_000L))
    }

    @Test
    fun rejectsAmbiguousOrTooShortRequests() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentSleepWakeTime.resolveEpochMillis(
                mapOf("wake_at" to "2026-09-09T10:00:00Z", "delay_seconds" to "30"),
                0L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSleepWakeTime.resolveEpochMillis(mapOf("delay_seconds" to "9"), 0L)
        }
    }
}
