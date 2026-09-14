package com.example.llamadroid.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StableAudioDownloadCoordinatorTest {
    @Test
    fun `stable audio transfers are serialized and cancellation can release the lane`() = runBlocking {
        val active = AtomicInteger(0)
        val overlapped = AtomicBoolean(false)
        val first = async {
            StableAudioDownloadCoordinator.withTransferLock {
                active.incrementAndGet()
                delay(25)
                if (active.get() > 1) overlapped.set(true)
                active.decrementAndGet()
            }
        }
        val second = async {
            StableAudioDownloadCoordinator.withTransferLock {
                active.incrementAndGet()
                delay(1)
                if (active.get() > 1) overlapped.set(true)
                active.decrementAndGet()
            }
        }
        first.await()
        second.await()
        assertFalse(overlapped.get())
        assertEquals(0, active.get())

        val cancelled = async {
            StableAudioDownloadCoordinator.withTransferLock { delay(1000) }
        }
        delay(5)
        cancelled.cancel()
        assertTrue(cancelled.isCancelled)
    }
}
