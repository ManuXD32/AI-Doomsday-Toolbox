package com.example.llamadroid.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import java.util.concurrent.atomic.AtomicInteger

class MemoryTelemetryTest {
    @Test
    fun `subscribers share sampling and transient failures recover off caller thread`() = runBlocking {
        val calls = AtomicInteger()
        val caller = Thread.currentThread().id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val flow = MemoryTelemetry.createFlow(MemoryTelemetrySampler {
                assertTrue(Thread.currentThread().id != caller)
                val count = calls.incrementAndGet()
                if (count == 1) error("temporary binder failure")
                MemoryTelemetrySnapshot(count.toLong(), 8192, 4096)
            }, scope, intervalMs = 20, started = SharingStarted.WhileSubscribed(0))
            delay(30)
            assertEquals(0, calls.get())
            val first = async { withTimeout(2000) { flow.first { it.sampledAtEpochMs >= 2 } } }
            val second = async { withTimeout(2000) { flow.first { it.sampledAtEpochMs >= 2 } } }
            assertEquals(first.await(), second.await())
            delay(60)
            val stoppedAt = calls.get()
            delay(60)
            assertEquals(stoppedAt, calls.get())
        } finally { scope.cancel() }
    }
    @Test
    fun `fake sampler emits memory-only snapshot through shared flow`() = runBlocking {
        val expected = MemoryTelemetrySnapshot(
            sampledAtEpochMs = 123L,
            totalBytes = 8L * MemoryTelemetrySnapshot.GB_BYTES,
            availableBytes = 3L * MemoryTelemetrySnapshot.GB_BYTES
        )
        val sampler = MemoryTelemetrySampler { expected }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val flow = MemoryTelemetry.createFlow(
                sampler = sampler,
                scope = scope,
                intervalMs = 1L
            )
            val actual = withTimeout(1_000L) {
                flow.first { it.sampledAtEpochMs == expected.sampledAtEpochMs }
            }

            assertEquals(expected, actual)
            assertEquals(8L * 1024L, actual.totalMiB)
            assertEquals(3L * 1024L, actual.availableMiB)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty snapshot reports no memory until first sample`() {
        assertEquals(0L, MemoryTelemetrySnapshot.Empty.totalMiB)
        assertEquals(0L, MemoryTelemetrySnapshot.Empty.availableMiB)
        assertTrue(MemoryTelemetrySnapshot.Empty.sampledAtEpochMs == 0L)
    }
}
