package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.NativeChatMediaRouting.VideoRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatMediaRoutingTest {
    @Test
    fun audioRoutePerModeAndFailure() {
        assertTrue(NativeChatMediaRouting.audioUsesWhisper("whisper", false, false))
        assertFalse(NativeChatMediaRouting.audioUsesWhisper("direct", true, false))
        assertTrue(NativeChatMediaRouting.audioUsesWhisper("direct", false, true))
        assertTrue(NativeChatMediaRouting.audioUsesWhisper("server", true, false))
        assertFalse(NativeChatMediaRouting.audioUsesWhisper(null, false, true))
        assertTrue(NativeChatMediaRouting.forcesDirectAudio("direct", false))
        assertFalse(NativeChatMediaRouting.forcesDirectAudio("direct", true))
        assertFalse(NativeChatMediaRouting.forcesDirectAudio("server", false))
    }

    @Test
    fun videoModeDegradesAfterFailure() {
        assertEquals("transcript", NativeChatMediaRouting.effectiveVideoMode("native", true))
        assertEquals("native", NativeChatMediaRouting.effectiveVideoMode("native", false))
        assertEquals("server", NativeChatMediaRouting.effectiveVideoMode("bogus", true))
        assertTrue(NativeChatMediaRouting.forcesNativeVideo("native"))
        assertFalse(NativeChatMediaRouting.forcesNativeVideo("transcript"))
    }

    @Test
    fun videoRouteMatrix() {
        assertEquals(VideoRoute.NATIVE, NativeChatMediaRouting.videoRoute("native", false, true))
        assertEquals(VideoRoute.TRANSCRIPT, NativeChatMediaRouting.videoRoute("transcript", true, false))
        assertEquals(VideoRoute.TRANSCRIPT, NativeChatMediaRouting.videoRoute("server", true, true))
        assertEquals(VideoRoute.NATIVE, NativeChatMediaRouting.videoRoute("server", true, false))
        assertEquals(VideoRoute.UNSUPPORTED, NativeChatMediaRouting.videoRoute("server", false, false))
    }

    @Test
    fun errorMatcher() {
        assertTrue(NativeChatMediaRouting.looksLikeMediaRejection("Server Error 500: audio input is not supported by this model"))
        assertTrue(NativeChatMediaRouting.looksLikeMediaRejection("Server Error 400: x"))
        assertTrue(NativeChatMediaRouting.looksLikeMediaRejection("image input is not supported - hint: provide the mmproj"))
        assertTrue(NativeChatMediaRouting.looksLikeMediaRejection("unsupported content type input_audio"))
        assertFalse(NativeChatMediaRouting.looksLikeMediaRejection("Connection refused"))
        assertFalse(NativeChatMediaRouting.looksLikeMediaRejection(null))
    }

    @Test
    fun fallbackGate() {
        assertTrue(NativeChatMediaRouting.shouldFallBack(true, true, false, false, "Server Error 500"))
        assertFalse(NativeChatMediaRouting.shouldFallBack(false, true, false, false, "Server Error 500"))
        assertFalse(NativeChatMediaRouting.shouldFallBack(true, false, false, false, "Server Error 500"))
        assertFalse(NativeChatMediaRouting.shouldFallBack(true, true, true, false, "Server Error 500"))
        assertFalse(NativeChatMediaRouting.shouldFallBack(true, true, false, true, "Server Error 500"))
        assertFalse(NativeChatMediaRouting.shouldFallBack(true, true, false, false, "timeout"))
    }

    @Test
    fun failureMemoryIsPerChat() {
        NativeChatMediaFailures.clear(901L)
        assertFalse(NativeChatMediaFailures.audioRejected(901L))
        NativeChatMediaFailures.markAudioRejected(901L)
        assertTrue(NativeChatMediaFailures.audioRejected(901L))
        assertFalse(NativeChatMediaFailures.videoRejected(901L))
        NativeChatMediaFailures.clear(901L)
        assertFalse(NativeChatMediaFailures.audioRejected(901L))
    }

    @Test
    fun videoOverrideForcesFlagOnLlamaEngines() {
        val s = LlamaServerEntity(name = "s", host = "h", port = 1)
        assertTrue(s.withChatVideoOverrides("native").supportsVideo)
        assertSame(s, s.withChatVideoOverrides("server"))
    }
}
