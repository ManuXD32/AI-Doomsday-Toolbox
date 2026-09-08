package com.example.llamadroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextChunkerTest {
    @Test
    fun `long text breaks at natural boundaries`() {
        val chunks = AudioTextChunker.chunk("One sentence. Two sentence! Three sentence?", maxCharacters = 20)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 20 })
        assertEquals(
            "One sentence. Two sentence! Three sentence?",
            chunks.joinToString(" ")
        )
    }

    @Test
    fun `speech chunks ignore overlap to avoid repeated words`() {
        val chunks = AudioTextChunker.chunk("alpha beta gamma delta epsilon", maxCharacters = 12, overlap = 8)
        assertEquals("alpha beta gamma delta epsilon", chunks.joinToString(" "))
    }

    @Test
    fun `hard boundary never splits surrogate pair`() {
        val text = "a".repeat(80) + "😀" + "b".repeat(80)
        val chunks = AudioTextChunker.chunk(text, maxCharacters = 81)
        assertTrue(chunks.isNotEmpty())
        assertFalse(chunks.any { it.firstOrNull()?.isLowSurrogate() == true })
        assertFalse(chunks.any { it.lastOrNull()?.isHighSurrogate() == true })
        assertEquals(text, chunks.joinToString(""))
    }
}
