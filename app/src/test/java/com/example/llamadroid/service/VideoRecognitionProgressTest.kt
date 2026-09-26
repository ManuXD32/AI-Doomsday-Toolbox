package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecognitionProgressTest {
    @Test
    fun `processed fraction is weighted by source duration`() {
        assertEquals(0.25f, VideoRecognitionSummaryService.durationWeightedProgress(120.0, 30.0), 0.0001f)
        assertEquals(1.0f, VideoRecognitionSummaryService.durationWeightedProgress(120.0, 160.0), 0.0001f)
        assertEquals(0.0f, VideoRecognitionSummaryService.durationWeightedProgress(0.0, 2.0), 0.0001f)
    }

    @Test
    fun `audio capability is explicit on targets`() {
        val visualOnly = VideoRecognitionTarget(
            id = "visual-only",
            label = "Visual only",
            kind = VideoRecognitionTarget.Kind.REMOTE_SERVER,
            remoteEndpoint = "http://127.0.0.1:8080",
            remoteModel = "model",
            remoteVideoEnabled = true
        )
        val multimodal = visualOnly.copy(supportsAudio = true)

        assertFalse(visualOnly.supportsAudio)
        assertTrue(multimodal.supportsAudio)
    }
}
