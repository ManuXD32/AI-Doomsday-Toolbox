package com.example.llamadroid.service.ram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelStartPolicyTest {
    private fun c(id: String, mb: Long, idle: Boolean, at: Long) =
        LocalModelCandidate(id, mb * 1024 * 1024, idle, at)
    private val mb = 1024L * 1024L

    @Test fun fitsWhenEnoughAndNotLow() {
        val plan = LocalModelStartPolicy.plan(100 * mb, 200 * mb, false, listOf(c("a", 50, true, 1)))
        assertEquals(LocalModelStartPlan.Fits, plan)
    }

    @Test fun stopsOldestIdleFirstAndOnlyEnough() {
        val plan = LocalModelStartPolicy.plan(
            300 * mb, 100 * mb, false,
            listOf(c("new", 150, true, 50), c("old", 150, true, 10), c("busy", 500, false, 1), c("mid", 150, true, 30))
        )
        plan as LocalModelStartPlan.StopThenStart
        assertEquals(listOf("old", "mid"), plan.stop.map { it.sessionId })
    }

    @Test fun neverStopsBusySessions() {
        val plan = LocalModelStartPolicy.plan(300 * mb, 100 * mb, false, listOf(c("busy", 900, false, 1)))
        assertEquals(LocalModelStartPlan.Refuse(300 * mb, 100 * mb), plan)
    }

    @Test fun refusesWhenIdleIsNotEnough() {
        val plan = LocalModelStartPolicy.plan(500 * mb, 100 * mb, false, listOf(c("a", 100, true, 1)))
        assertEquals(LocalModelStartPlan.Refuse(500 * mb, 200 * mb), plan)
    }

    @Test fun lowMemoryWithNothingToStopRefuses() {
        val plan = LocalModelStartPolicy.plan(10 * mb, 1000 * mb, true, emptyList())
        assertTrue(plan is LocalModelStartPlan.Refuse)
    }

    @Test fun lowMemoryStopsOneIdleEvenIfBytesFit() {
        val plan = LocalModelStartPolicy.plan(10 * mb, 1000 * mb, true, listOf(c("a", 100, true, 1), c("b", 100, true, 2)))
        plan as LocalModelStartPlan.StopThenStart
        assertEquals(listOf("a"), plan.stop.map { it.sessionId })
    }

    @Test fun lastActivityUsesNewestSignal() {
        // updatedAt 5 s ago on a clock whose elapsed time is 100_000.
        assertEquals(95_000L, estimateLastActivityElapsedMs(null, 0L, 995_000L, 1_000_000L, 100_000L))
        assertEquals(99_000L, estimateLastActivityElapsedMs(99_000L, 0L, 995_000L, 1_000_000L, 100_000L))
        assertEquals(97_000L, estimateLastActivityElapsedMs(10L, 97_000L, 0L, 1_000_000L, 100_000L))
    }
}
