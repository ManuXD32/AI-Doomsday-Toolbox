package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
