package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLlamaAudioSupportTest {

    @Test
    fun `native llama audio conversion is skipped for wav and mp3`() {
        assertFalse(requiresNativeLlamaAudioConversion("/tmp/example.wav"))
        assertFalse(requiresNativeLlamaAudioConversion("/tmp/example.MP3"))
    }

    @Test
    fun `native llama audio conversion is required for android recorder formats`() {
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.m4a"))
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.aac"))
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.ogg"))
    }

    @Test
    fun `explicit video audio profile value overrides compatibility default`() {
        assertTrue(
            nativeLlamaVideoAudioEnabled(
                "{\"videoAudioEnabled\":true,\"videoEnabled\":false}",
                compatibilityDefault = false
            )
        )
        assertFalse(
            nativeLlamaVideoAudioEnabled(
                "{\"videoAudioEnabled\":false,\"videoEnabled\":true}",
                compatibilityDefault = true
            )
        )
    }

    @Test
    fun `video audio policy retains legacy fallback and fails closed on malformed profile`() {
        assertTrue(nativeLlamaVideoAudioEnabled("{\"videoEnabled\":true}", true))
        assertFalse(nativeLlamaVideoAudioEnabled("{\"videoEnabled\":false}", false))
        assertFalse(nativeLlamaVideoAudioEnabled("not-json", true))
        assertTrue(nativeLlamaVideoAudioEnabled(null, true))
    }

    @Test
    fun `video audio extraction duration is bounded for direct requests`() {
        assertEquals(1L, nativeLlamaVideoAudioDurationMs(1L))
        assertEquals(
            NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS,
            nativeLlamaVideoAudioDurationMs(NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS)
        )
        assertEquals(null, nativeLlamaVideoAudioDurationMs(0L))
        assertEquals(null, nativeLlamaVideoAudioDurationMs(NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS + 1L))
        assertEquals(
            384_044L,
            NATIVE_LLAMA_VIDEO_AUDIO_MAX_WAV_BYTES
        )
    }
}
