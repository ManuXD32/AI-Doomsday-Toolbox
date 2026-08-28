package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperVadSupportTest {
    @Test
    fun `template VAD configuration round trips`() {
        val original = WhisperVadConfig(
            enabled = true,
            modelPath = "/models/vad.bin",
            threshold = 0.63f,
            minSpeechDurationMs = 320,
            minSilenceDurationMs = 420,
            maxSpeechDurationSeconds = 75f,
            speechPaddingMs = 45,
            samplesOverlap = 0.25f
        )
        val json = JSONObject().putWhisperVadConfig("whisperVad", original)
        val restored = json.readWhisperVadConfigOrNull("whisperVad")

        assertEquals(original.normalized(), restored)
    }

    @Test
    fun `null model path round trips as null rather than the word null`() {
        val json = JSONObject().putWhisperVadConfig(
            "whisperVad",
            WhisperVadConfig(enabled = true, modelPath = null)
        )

        assertNull(json.readWhisperVadConfig("whisperVad").modelPath)
    }

    @Test
    fun `old template without VAD defaults to disabled`() {
        val oldTemplate = JSONObject().put("whisperModel", "/models/base.bin")

        assertNull(oldTemplate.readWhisperVadConfigOrNull("whisperVad"))
        assertFalse(oldTemplate.readWhisperVadConfig("whisperVad").enabled)
    }

    @Test
    fun `normalization clamps invalid persisted values`() {
        val normalized = WhisperVadConfig(
            enabled = true,
            modelPath = "  /models/vad.bin  ",
            threshold = 5f,
            minSpeechDurationMs = -50,
            minSilenceDurationMs = 100_000,
            maxSpeechDurationSeconds = -2f,
            speechPaddingMs = 10_000,
            samplesOverlap = -1f
        ).normalized()

        assertEquals("/models/vad.bin", normalized.modelPath)
        assertEquals(1f, normalized.threshold)
        assertEquals(0, normalized.minSpeechDurationMs)
        assertEquals(WhisperVadConfig.MAX_DURATION_MS, normalized.minSilenceDurationMs)
        assertNull(normalized.maxSpeechDurationSeconds)
        assertEquals(WhisperVadConfig.MAX_PADDING_MS, normalized.speechPaddingMs)
        assertEquals(0f, normalized.samplesOverlap)
        assertTrue(normalized.enabled)
    }

    @Test
    fun `latency sensitive purposes force VAD off without discarding tuning`() {
        val configured = WhisperVadConfig(
            enabled = true,
            modelPath = "/models/vad.bin",
            threshold = 0.72f
        )
        val effective = configured.forPurpose(WhisperInvocationPurpose.CALL_TRANSCRIPTION)

        assertFalse(effective.enabled)
        assertEquals(0.72f, effective.threshold)
        assertEquals("/models/vad.bin", effective.modelPath)
    }
}
