package com.example.llamadroid.ui.ai.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaChatSamplerValidationTest {
    @Test
    fun nativeRangesAcceptValuesAndRejectOutOfRangeOrPartialText() {
        assertEquals(0.8f, LlamaChatSamplerValidation.parse("0.8", LlamaSamplerField.TEMPERATURE, false))
        assertEquals(40f, LlamaChatSamplerValidation.parse("40", LlamaSamplerField.TOP_K, false))
        assertNull(LlamaChatSamplerValidation.parse("", LlamaSamplerField.TOP_P, false))
        assertNull(LlamaChatSamplerValidation.parse("2.1", LlamaSamplerField.TEMPERATURE, false))
        assertNull(LlamaChatSamplerValidation.parse("40.5", LlamaSamplerField.TOP_K, false))
    }

    @Test
    fun liteRtUsesItsNarrowerSamplerLimitsAndDoesNotExposeUnsupportedFields() {
        assertEquals(0.95f, LlamaChatSamplerValidation.parse("0.95", LlamaSamplerField.TOP_P, true))
        assertEquals(64f, LlamaChatSamplerValidation.parse("64", LlamaSamplerField.TOP_K, true))
        assertNull(LlamaChatSamplerValidation.parse("0.96", LlamaSamplerField.TOP_P, true))
        assertNull(LlamaChatSamplerValidation.parse("4", LlamaSamplerField.TOP_K, true))
        assertNull(LlamaChatSamplerValidation.range(LlamaSamplerField.MIN_P, true))
        assertNull(LlamaChatSamplerValidation.range(LlamaSamplerField.REPETITION_PENALTY, true))
    }
}
