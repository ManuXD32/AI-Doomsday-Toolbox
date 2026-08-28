package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerLogBufferTest {

    @Test
    fun `buffer keeps a bounded tail and reports lines dropped while pending queue is full`() {
        val buffer = LlamaServerLogBuffer(maxTail = 3, maxPending = 3, maxBatch = 3)
        buffer.reset(7L)

        listOf("one", "two", "three", "four", "five").forEach { line ->
            assertTrue(buffer.append(7L, line))
        }

        val first = requireNotNull(buffer.drain())
        val second = requireNotNull(buffer.drain())

        assertEquals(listOf("one", "two", "three"), first.lines)
        assertEquals(listOf("[log buffer dropped 2 line(s)]"), second.lines)
        assertEquals(3, second.tail.size)
        assertEquals(listOf("two", "three", "[log buffer dropped 2 line(s)]"), second.tail)
    }

    @Test
    fun `buffer coalesces consecutive repeated native log lines`() {
        val buffer = LlamaServerLogBuffer(maxTail = 8, maxPending = 8, maxBatch = 8)
        buffer.reset(3L)

        repeat(4) { assertTrue(buffer.append(3L, "loading layer")) }

        val flush = requireNotNull(buffer.drain())

        assertEquals(listOf("loading layer [repeated 4 times]"), flush.lines)
        assertEquals(flush.lines, flush.tail)
    }

    @Test
    fun `reset discards old generation and invalidates an already drained flush`() {
        val buffer = LlamaServerLogBuffer(maxTail = 8, maxPending = 8, maxBatch = 8)
        buffer.reset(11L)
        buffer.append(11L, "old generation")
        val oldFlush = requireNotNull(buffer.drain())

        buffer.reset(12L)

        assertFalse(buffer.isCurrent(oldFlush))
        assertFalse(buffer.append(11L, "late callback"))
        assertTrue(buffer.append(12L, "new generation"))
        val newFlush = requireNotNull(buffer.drain())
        assertEquals(listOf("new generation"), newFlush.lines)
    }
}
