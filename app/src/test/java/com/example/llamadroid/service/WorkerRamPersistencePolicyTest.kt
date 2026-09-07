package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRamPersistencePolicyTest {
    @Test
    fun `unchanged effective contribution is not written again`() {
        assertFalse(
            WorkerRamPersistencePolicy.shouldPersist(
                previousContributionMiB = 2_048L,
                effectiveContributionMiB = 2_048L,
                hasUsableSnapshot = true
            )
        )
    }

    @Test
    fun `changed effective contribution is persisted`() {
        assertTrue(
            WorkerRamPersistencePolicy.shouldPersist(
                previousContributionMiB = 2_048L,
                effectiveContributionMiB = 1_792L,
                hasUsableSnapshot = true
            )
        )
    }

    @Test
    fun `unknown memory snapshot never persists a fallback`() {
        assertFalse(
            WorkerRamPersistencePolicy.shouldPersist(
                previousContributionMiB = null,
                effectiveContributionMiB = 4_096L,
                hasUsableSnapshot = false
            )
        )
    }
}
