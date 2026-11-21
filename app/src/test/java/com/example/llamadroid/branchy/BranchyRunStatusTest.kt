package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchyRunStatusTest {
    @Test
    fun statusValuesRoundTrip() {
        BranchyRunStatus.entries.forEach { assertEquals(it, BranchyRunStatus.fromValue(it.value)) }
        assertEquals(BranchyRunStatus.FAILED, BranchyRunStatus.fromValue("bogus"))
        assertEquals(
            listOf("draft", "preparing", "training", "paused", "done", "failed"),
            BranchyRunStatus.entries.map { it.value }
        )
    }
}
