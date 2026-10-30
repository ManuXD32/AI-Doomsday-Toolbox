package com.example.llamadroid.ui.ai.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.example.llamadroid.service.chat.LlamaSamplerKeys
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

    @Test
    fun catalogCoversEveryKeyExactlyOnce() {
        val keys = LlamaSamplerField.entries.map { it.key }
        assertEquals(21, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(LlamaSamplerKeys.EXTENDED_KEYS.toSet() + setOf(
            LlamaSamplerKeys.TEMPERATURE, LlamaSamplerKeys.TOP_P, LlamaSamplerKeys.TOP_K,
            LlamaSamplerKeys.MIN_P, LlamaSamplerKeys.REPEAT_PENALTY,
            LlamaSamplerKeys.PRESENCE_PENALTY, LlamaSamplerKeys.FREQUENCY_PENALTY
        ), keys.toSet())
        LlamaSamplerField.entries.forEach {
            assertEquals(it.key, it.integer, it.isIntegerKey)
        }
    }

    @Test
    fun llamaRangesFollowThePlan() {
        assertEquals(40f, LlamaChatSamplerValidation.parse("40", LlamaSamplerField.TOP_K, false))
        assertEquals(0f, LlamaChatSamplerValidation.parse("0", LlamaSamplerField.TOP_K, false))
        assertEquals(200f, LlamaChatSamplerValidation.parse("200", LlamaSamplerField.TOP_K, false))
        assertNull(LlamaChatSamplerValidation.parse("201", LlamaSamplerField.TOP_K, false))
        assertEquals(-1f, LlamaChatSamplerValidation.parse("-1", LlamaSamplerField.DRY_PENALTY_LAST_N, false))
        assertNull(LlamaChatSamplerValidation.parse("0.4", LlamaSamplerField.REPETITION_PENALTY, false))
        assertNull(LlamaChatSamplerValidation.parse("0.5", LlamaSamplerField.DRY_BASE, false))
        assertEquals(2f, LlamaChatSamplerValidation.parse("2", LlamaSamplerField.MIROSTAT, false))
        assertNull(LlamaChatSamplerValidation.parse("3", LlamaSamplerField.MIROSTAT, false))
        assertNull(LlamaChatSamplerValidation.parse("1.5", LlamaSamplerField.MIROSTAT, false))
    }

    @Test
    fun liteRtOnlySupportsTheThreeBasics() {
        assertEquals(
            listOf(LlamaSamplerField.TEMPERATURE, LlamaSamplerField.TOP_P, LlamaSamplerField.TOP_K),
            LlamaSamplerField.visible(true)
        )
        assertNull(LlamaChatSamplerValidation.range(LlamaSamplerField.DRY_BASE, true))
        assertEquals(40f, LlamaChatSamplerValidation.startValue(LlamaSamplerField.TOP_K, true))
        assertEquals(0.8f, LlamaChatSamplerValidation.startValue(LlamaSamplerField.TEMPERATURE, true))
    }
}
