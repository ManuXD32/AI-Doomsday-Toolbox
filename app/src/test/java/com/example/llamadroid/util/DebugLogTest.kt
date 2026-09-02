package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DebugLogTest {
    @Test
    fun persistedLogTrimKeepsOnlyNewestLines() {
        val lines = (1..1_250).map { "line-$it" }

        val trimmed = debugLogTrimPersistedLines(lines, maxLines = 1_000)

        assertEquals(1_000, trimmed.size)
        assertEquals("line-251", trimmed.first())
        assertEquals("line-1250", trimmed.last())
    }

    @Test
    fun persistedLogTrimHandlesZeroLimit() {
        assertTrue(debugLogTrimPersistedLines(listOf("one", "two"), maxLines = 0).isEmpty())
    }

    @Test
    fun boundedBufferTrimsOldestEntriesInOrder() {
        val buffer = BoundedLogBuffer<Int>(3)

        buffer.addAll(1..5)

        assertEquals(listOf(3, 4, 5), buffer.snapshot())
    }

    @Test
    fun boundedBufferRemainsBoundedWithConcurrentWriters() {
        val buffer = BoundedLogBuffer<Int>(100)
        val executor = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)

        repeat(4) { worker ->
            executor.execute {
                repeat(250) { index -> buffer.add(worker * 250 + index) }
                done.countDown()
            }
        }
        done.await()
        executor.shutdownNow()

        assertEquals(100, buffer.snapshot().size)
        assertEquals(100, buffer.snapshot().distinct().size)
    }
}
