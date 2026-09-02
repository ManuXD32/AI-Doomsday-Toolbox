package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerSessionLogBatcherTest {

    @Test
    fun `drain returns bounded batches and reports dropped lines`() {
        val batcher = LlamaServerSessionLogBatcher(maxPending = 3, maxBatch = 2)
        batcher.append("one")
        batcher.append("two")
        batcher.append("three")
        batcher.append("four")

        assertEquals(listOf("[log buffer dropped 1 line(s)]", "two"), batcher.drain())
        assertEquals(listOf("three", "four"), batcher.drain())
        assertTrue(!batcher.hasPending())
    }

    @Test
    fun `native control characters are removed before queueing`() {
        val batcher = LlamaServerSessionLogBatcher(maxPending = 2, maxBatch = 2)
        batcher.append("loading\r\u0000done")

        assertEquals(listOf("loadingdone"), batcher.drain())
    }
}
