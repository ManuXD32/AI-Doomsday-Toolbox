package com.example.llamadroid.service

import com.example.llamadroid.util.NsdHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdDistributedProbeTest {
    @Test
    fun offlineWorkersAreProbedEveryTick() {
        assertTrue(SdDistributedService.shouldProbeWorker(false, 0L, 1_000L))
    }

    @Test
    fun confirmedWorkersAreProbedOnlyAfterTheInterval() {
        assertFalse(SdDistributedService.shouldProbeWorker(true, 1_000L, 6_000L))
        assertTrue(SdDistributedService.shouldProbeWorker(true, 1_000L, 31_000L))
    }

    @Test
    fun resolveRetriesAreCapped() {
        assertTrue(NsdHelper.shouldRetryResolve(3, 1))
        assertFalse(NsdHelper.shouldRetryResolve(3, NsdHelper.MAX_RESOLVE_ATTEMPTS))
    }

    @Test
    fun counterRegexesMatchRealBinaryLines() {
        assertTrue(SdDistributedService.CONNECTED_REGEX.containsMatchIn("Accepted client connection"))
        assertTrue(SdDistributedService.DISCONNECTED_REGEX.containsMatchIn("Client connection closed"))
        assertFalse(SdDistributedService.DISCONNECTED_REGEX.containsMatchIn("Accepted client connection"))
        assertFalse(SdDistributedService.CONNECTED_REGEX.containsMatchIn("Client connection closed"))
    }
}
