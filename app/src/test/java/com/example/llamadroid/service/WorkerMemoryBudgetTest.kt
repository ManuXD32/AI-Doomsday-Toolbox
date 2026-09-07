package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerMemoryBudgetTest {
    @Test
    fun `two gigabyte device reserves floor and caps at available headroom`() {
        val budget = WorkerMemoryBudget.calculate(
            totalMiB = 2_048L,
            availableMiB = 1_800L,
            requestedMiB = 4_096L
        )

        assertEquals(512L, budget.reservedMiB)
        assertEquals(1_288L, budget.maximumMiB)
        assertEquals(1_288L, budget.contributionMiB)
        assertTrue(budget.canLaunch)
    }

    @Test
    fun `four and eight gigabyte devices round ten percent upward`() {
        val fourGb = WorkerMemoryBudget.calculate(4_096L, 3_000L, 2_000L)
        val eightGb = WorkerMemoryBudget.calculate(8_192L, 7_000L, 2_000L)
        val sixteenGb = WorkerMemoryBudget.calculate(16_384L, 12_000L, 2_000L)

        assertEquals(512L, fourGb.reservedMiB)
        assertEquals(2_488L, fourGb.maximumMiB)
        assertEquals(820L, eightGb.reservedMiB)
        assertEquals(6_180L, eightGb.maximumMiB)
        assertEquals(1_639L, sixteenGb.reservedMiB)
        assertEquals(2_000L, sixteenGb.contributionMiB)
    }

    @Test
    fun `low available memory produces zero contribution and disables launch`() {
        val budget = WorkerMemoryBudget.calculate(
            totalMiB = 4_096L,
            availableMiB = 400L,
            requestedMiB = 2_048L
        )

        assertEquals(0L, budget.maximumMiB)
        assertEquals(0L, budget.contributionMiB)
        assertFalse(budget.canLaunch)
    }

    @Test
    fun `oversized saved contribution is clamped to current maximum`() {
        val budget = WorkerMemoryBudget.calculate(
            totalMiB = 4_096L,
            availableMiB = 2_000L,
            requestedMiB = 99_999L
        )

        assertEquals(1_488L, budget.maximumMiB)
        assertEquals(1_488L, budget.contributionMiB)
        assertEquals(99_999L, budget.requestedMiB)
    }

    @Test
    fun `recreated snapshot preserves sanitized saved contribution`() {
        val initial = WorkerMemoryBudget.calculate(8_192L, 6_000L, 5_000L)
        val recreated = WorkerMemoryBudget.calculate(
            totalMiB = initial.totalMiB,
            availableMiB = initial.availableMiB,
            requestedMiB = initial.contributionMiB
        )

        assertEquals(initial.contributionMiB, recreated.contributionMiB)
        assertEquals(initial.maximumMiB, recreated.maximumMiB)
    }

    @Test
    fun `launch time snapshot clamps a stale contribution without mutating prior snapshot`() {
        val stoppedSnapshot = WorkerMemoryBudget.calculate(4_096L, 3_000L, 2_000L)
        val launchSnapshot = WorkerMemoryBudget.calculate(
            totalMiB = stoppedSnapshot.totalMiB,
            availableMiB = 600L,
            requestedMiB = stoppedSnapshot.contributionMiB
        )

        assertEquals(2_000L, stoppedSnapshot.contributionMiB)
        assertEquals(88L, launchSnapshot.maximumMiB)
        assertEquals(88L, launchSnapshot.contributionMiB)
        assertFalse(launchSnapshot.canLaunch)
    }

    @Test
    fun `byte snapshot ceilings reservation before converting to MiB`() {
        val totalBytes = 8_190L * WorkerMemoryBudget.MIB_BYTES + 1L
        val budget = WorkerMemoryBudget.fromBytes(
            totalBytes = totalBytes,
            availableBytes = totalBytes,
            requestedMiB = Long.MAX_VALUE
        )

        assertEquals(820L, budget.reservedMiB)
        assertEquals(7_370L, budget.maximumMiB)
    }
}
