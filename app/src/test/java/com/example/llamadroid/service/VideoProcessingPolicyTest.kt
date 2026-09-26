package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProcessingPolicyTest {
    @Test
    fun `defaults use auto multimodal and bounded evidence limits`() {
        val policy = VideoProcessingPolicy()

        assertEquals(VideoProcessingMode.AUTO_MULTIMODAL, policy.mode)
        assertEquals(12, policy.segmentSeconds)
        assertEquals(24, policy.maxFrames)
        assertEquals(2f, policy.maxFps, 0.0001f)
        assertTrue(policy.directAudioEnabled)
        assertFalse(policy.nativeChatParallelWhisperEnabled)
        assertFalse(policy.shouldCollectIndependentTranscript)
    }

    @Test
    fun `normalization enforces shared processing bounds`() {
        val normalized = VideoProcessingPolicy(
            segmentSeconds = 1,
            maxFrames = 99,
            maxFps = 0.01f
        ).normalized()

        assertEquals(5, normalized.segmentSeconds)
        assertEquals(24, normalized.maxFrames)
        assertEquals(0.1f, normalized.maxFps, 0.0001f)
        assertEquals(
            60,
            VideoProcessingPolicy(segmentSeconds = 999).normalized().segmentSeconds
        )
        assertEquals(
            2f,
            VideoProcessingPolicy(maxFps = 99f).normalized().maxFps,
            0.0001f
        )
    }

    @Test
    fun `mode parsing is stable and transcript modes request independent audio`() {
        assertEquals(
            VideoProcessingMode.VISUAL_WHISPER,
            VideoProcessingMode.fromStorage(" visual_whisper ")
        )
        assertEquals(
            VideoProcessingMode.AUTO_MULTIMODAL,
            VideoProcessingMode.fromStorage("future-mode")
        )
        assertTrue(
            VideoProcessingPolicy(mode = VideoProcessingMode.VISUAL_WHISPER)
                .shouldCollectIndependentTranscript
        )
        assertTrue(
            VideoProcessingPolicy(mode = VideoProcessingMode.LEGACY_TRANSCRIPT)
                .shouldCollectIndependentTranscript
        )
        assertTrue(
            VideoProcessingPolicy(nativeChatParallelWhisperEnabled = true)
                .shouldCollectIndependentTranscript
        )
    }
}
