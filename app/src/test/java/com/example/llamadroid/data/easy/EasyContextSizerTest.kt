package com.example.llamadroid.data.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyContextSizerTest {
    /** 1 byte per token, so the budget is the token count that fits. */
    private val linear: (Int) -> Long = { it.toLong() }

    @Test
    fun `picks the largest candidate that fits`() {
        val c = chooseEasyContext(131072, 17000L, linear)
        assertEquals(16384, c.contextSize)
        assertEquals(16384, c.recommendedMax)
        assertTrue(c.fitsBudget)
    }

    @Test
    fun `plenty of budget gives 32k`() {
        assertEquals(32768, chooseEasyContext(null, Long.MAX_VALUE, linear).contextSize)
    }

    @Test
    fun `trained context caps the candidates`() {
        val c = chooseEasyContext(16384, Long.MAX_VALUE, linear)
        assertEquals(16384, c.contextSize)
    }

    @Test
    fun `trained context below 8k becomes the floor`() {
        val c = chooseEasyContext(4096, Long.MAX_VALUE, linear)
        assertEquals(4096, c.contextSize)
        assertTrue(c.fitsBudget)
    }

    @Test
    fun `floor is 8k when budget only fits 8k`() {
        val c = chooseEasyContext(null, 8192L, linear)
        assertEquals(8192, c.contextSize)
        assertTrue(c.fitsBudget)
    }

    @Test
    fun `none fitting returns the floor flagged as not fitting`() {
        val c = chooseEasyContext(null, 100L, linear)
        assertEquals(8192, c.contextSize)
        assertFalse(c.fitsBudget)
    }

    @Test
    fun `none fitting with small trained context uses trained as floor`() {
        val c = chooseEasyContext(2048, 100L, linear)
        assertEquals(2048, c.contextSize)
        assertFalse(c.fitsBudget)
    }

    @Test
    fun `override above recommendation is flagged`() {
        val e = evaluateOverride(32768, 131072, 17000L, linear)
        assertTrue(e.exceedsRecommendation)
        assertFalse(e.fitsBudget)
        assertFalse(e.exceedsTrainedContext)
    }

    @Test
    fun `override at or below recommendation is not flagged`() {
        val e = evaluateOverride(12288, 131072, 17000L, linear)
        assertFalse(e.exceedsRecommendation)
        assertTrue(e.fitsBudget)
    }

    @Test
    fun `override above trained context is flagged`() {
        val e = evaluateOverride(32768, 8192, Long.MAX_VALUE, linear)
        assertTrue(e.exceedsRecommendation)
        assertTrue(e.exceedsTrainedContext)
    }
}
