package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkCooldownSupportTest {

    @Test
    fun waitsBetweenThreadTestsWhenEnabledAndMoreThreadsRemain() {
        assertTrue(
            shouldWaitBetweenThreadTests(
                waitBetweenTestsSeconds = 30,
                currentThreads = 4,
                maxThreads = 8,
                isCancelled = false
            )
        )
    }

    @Test
    fun skipsCooldownWhenDisabledOrOnFinalThread() {
        assertFalse(
            shouldWaitBetweenThreadTests(
                waitBetweenTestsSeconds = 0,
                currentThreads = 4,
                maxThreads = 8,
                isCancelled = false
            )
        )
        assertFalse(
            shouldWaitBetweenThreadTests(
                waitBetweenTestsSeconds = 30,
                currentThreads = 8,
                maxThreads = 8,
                isCancelled = false
            )
        )
    }

    @Test
    fun skipsCooldownAfterCancellation() {
        assertFalse(
            shouldWaitBetweenThreadTests(
                waitBetweenTestsSeconds = 30,
                currentThreads = 4,
                maxThreads = 8,
                isCancelled = true
            )
        )
    }
}
