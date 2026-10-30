package com.example.llamadroid.ui.ai.llama.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationThreadMessagesTest {
    @Test fun keepsLastTwoAndBoundsPreview() {
        val long = "x".repeat(2_000)
        val out = DelegationThreadMessages.bound(listOf("USER" to "a", "ASSISTANT" to "b", "USER" to long))
        assertEquals(2, out.size)
        assertEquals("b", out[0].text)
        assertFalse(out[0].truncated)
        assertEquals(DelegationThreadMessages.MAX_CHARS, out[1].preview.length)
        assertEquals(2_000, out[1].text.length)
        assertTrue(out[1].truncated)
    }
}
