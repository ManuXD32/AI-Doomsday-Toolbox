package com.example.llamadroid.ui.ai.llama.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingPreviewTest {
    @Test
    fun shortTextKeepsMarkdownAndIsUntouched() {
        val p = thinkingPreview("hello", streaming = false)
        assertEquals("hello", p.text)
        assertFalse(p.plain)
        assertFalse(p.truncated)
    }

    @Test
    fun longTextIsCutAndPlain() {
        val p = thinkingPreview("a".repeat(10_000), streaming = false)
        assertTrue(p.plain)
        assertTrue(p.truncated)
        assertEquals(THINKING_PREVIEW_CHARS + 1, p.text.length)
        assertEquals(10_000 - THINKING_PREVIEW_CHARS, p.hiddenChars)
    }

    @Test
    fun streamingKeepsOnlyTail() {
        val text = "x".repeat(5_000) + "END"
        val p = thinkingPreview(text, streaming = true)
        assertTrue(p.text.endsWith("END"))
        assertEquals(THINKING_STREAMING_TAIL_CHARS, p.text.length)
        assertEquals(text.length - THINKING_STREAMING_TAIL_CHARS, p.hiddenChars)
        assertTrue(p.truncated)
    }

    @Test
    fun cutDoesNotSplitSurrogatePair() {
        val text = "a".repeat(THINKING_PREVIEW_CHARS - 1) + "😀" + "b".repeat(50)
        val p = thinkingPreview(text, streaming = false)
        assertFalse(p.text.dropLast(1).any { Character.isHighSurrogate(it) })
    }
}
