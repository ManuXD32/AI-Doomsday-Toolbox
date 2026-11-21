package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingProgressTextTest {
    @Test
    fun hostileModelTextNeverThrowsAndAppearsVerbatim() {
        listOf("width:100%;", "...ends with 100%", "50% opacity", "%n %s %d").forEach { text ->
            val out = streamingProgressText(12, 3.5, text)
            val tail = text.trim().split(Regex("\\s+")).joinToString(" ")
            assertTrue("missing '$tail' in '$out'", out.endsWith("| ...$tail"))
            assertTrue(out.startsWith("12 tok"))
        }
    }

    @Test
    fun emptyContextHasNoWordsSuffix() {
        val out = streamingProgressText(5, 1.0, "")
        assertTrue(out.startsWith("5 tok"))
        assertTrue(!out.contains("|"))
        assertEquals(out, streamingProgressText(5, 1.0, "   \n "))
    }

    @Test
    fun keepsOnlyLastSevenWords() {
        val out = streamingProgressText(1, 1.0, "a b c d e f g h i")
        assertTrue(out.endsWith("| ...c d e f g h i"))
    }
}
