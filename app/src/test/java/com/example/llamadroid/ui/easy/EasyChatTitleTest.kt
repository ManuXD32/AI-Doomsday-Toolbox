package com.example.llamadroid.ui.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyChatTitleTest {
    private val names = setOf("Qwen3-4B", "New chat")

    @Test fun `short message becomes the title with whitespace collapsed`() {
        assertEquals("Hello there", easyChatTitleFromMessage("  Hello\n  there \n", "Qwen3-4B", names))
    }

    @Test fun `long message is cut with an ellipsis`() {
        val t = easyChatTitleFromMessage("a".repeat(100), "New chat", names)!!
        assertEquals(EASY_CHAT_TITLE_MAX + 1, t.length)
        assertEquals('…', t.last())
    }

    @Test fun `custom titles and blank messages are left alone`() {
        assertNull(easyChatTitleFromMessage("hi", "My trip", names))
        assertNull(easyChatTitleFromMessage("   ", "Qwen3-4B", names))
    }
}
