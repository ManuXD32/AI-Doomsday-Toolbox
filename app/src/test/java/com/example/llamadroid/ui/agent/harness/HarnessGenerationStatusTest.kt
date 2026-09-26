package com.example.llamadroid.ui.agent.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessGenerationStatusTest {
    @Test
    fun elapsedPartsClampNegativeDurationsAndKeepMinutesBounded() {
        assertEquals(HarnessElapsedParts(0L, 0L), harnessElapsedParts(-1L))
        assertEquals(HarnessElapsedParts(1L, 5L), harnessElapsedParts(65_000L))
    }
}
