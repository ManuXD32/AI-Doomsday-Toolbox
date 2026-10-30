package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessHeartbeatScheduleTest {
    @Test
    fun waitsUntilIntervalSinceLastFrame() {
        assertEquals(10_000L, harnessHeartbeatDelayMs(nowMs = 5_000L, lastFrameMs = 0L, interval = 15_000L))
    }

    @Test
    fun dueWhenIntervalElapsed() {
        assertEquals(0L, harnessHeartbeatDelayMs(nowMs = 15_000L, lastFrameMs = 0L, interval = 15_000L))
        assertEquals(0L, harnessHeartbeatDelayMs(nowMs = 600_000L, lastFrameMs = 0L, interval = 15_000L))
    }

    @Test
    fun clockGoingBackwardsNeverWaitsMoreThanOneInterval() {
        assertEquals(15_000L, harnessHeartbeatDelayMs(nowMs = 0L, lastFrameMs = 90_000L, interval = 15_000L))
    }
}
