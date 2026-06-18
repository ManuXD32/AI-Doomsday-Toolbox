package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCallAudioTest {

    @Test
    fun `vad submits after speech followed by configured silence`() {
        val detector = LlamaCallVoiceActivityDetector(
            sampleRate = 10,
            speechThreshold = 0.1f,
            silenceAfterSpeechMs = 500,
            noSpeechTimeoutMs = 10_000
        )
        val speech = ShortArray(10) { 10_000 }
        val silence = ShortArray(10) { 0 }

        val speechDecision = detector.accept(speech, speech.size)
        assertTrue(speechDecision.hasSpeechStarted)
        assertFalse(speechDecision.shouldSubmit)

        val submitDecision = detector.accept(silence, silence.size)
        assertTrue(submitDecision.shouldSubmit)
        assertFalse(submitDecision.shouldTimeout)
    }

    @Test
    fun `vad times out when no speech starts`() {
        val detector = LlamaCallVoiceActivityDetector(
            sampleRate = 10,
            speechThreshold = 0.1f,
            silenceAfterSpeechMs = 500,
            noSpeechTimeoutMs = 1_000
        )
        val silence = ShortArray(10) { 0 }

        val decision = detector.accept(silence, silence.size)

        assertFalse(decision.hasSpeechStarted)
        assertTrue(decision.shouldTimeout)
        assertFalse(decision.shouldSubmit)
    }

    @Test
    fun `wav bytes include riff header and pcm payload size`() {
        val wav = buildLlamaCallWavBytes(
            samples = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE),
            sampleRate = 16_000
        )

        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        assertEquals(44 + 6, wav.size)
        assertEquals(6, littleEndianInt(wav, 40))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
