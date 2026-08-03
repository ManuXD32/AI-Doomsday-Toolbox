package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInterpolateUpscaleStateTest {
    @Test
    fun `combined progress maps interpolation and upscale halves`() {
        VideoInterpolateUpscaleStateHolder.reset()

        VideoInterpolateUpscaleStateHolder.setRunning(
            VideoInterpolateUpscaleState.Interpolating,
            "Interpolating",
            0.25f
        )
        assertTrue(VideoInterpolateUpscaleStateHolder.isProcessing.value)
        assertEquals(0.25f, VideoInterpolateUpscaleStateHolder.progress.value)

        VideoInterpolateUpscaleStateHolder.setRunning(
            VideoInterpolateUpscaleState.Upscaling,
            "Upscaling",
            0.75f
        )
        assertTrue(VideoInterpolateUpscaleStateHolder.state.value is VideoInterpolateUpscaleState.Upscaling)
        assertEquals(0.75f, VideoInterpolateUpscaleStateHolder.progress.value)
    }

    @Test
    fun `combined completion is explicit and terminal`() {
        VideoInterpolateUpscaleStateHolder.reset()

        VideoInterpolateUpscaleStateHolder.setCompleted(
            outputPath = "/tmp/final.mp4",
            galleryId = "interpolation_1",
            status = "Done"
        )

        val state = VideoInterpolateUpscaleStateHolder.state.value
        assertTrue(state is VideoInterpolateUpscaleState.Completed)
        assertFalse(VideoInterpolateUpscaleStateHolder.isProcessing.value)
        assertEquals(1f, VideoInterpolateUpscaleStateHolder.progress.value)
        assertEquals("/tmp/final.mp4", VideoInterpolateUpscaleStateHolder.resultPath.value)
        assertEquals("interpolation_1", VideoInterpolateUpscaleStateHolder.galleryId.value)
    }

    @Test
    fun `combined error never looks completed`() {
        VideoInterpolateUpscaleStateHolder.reset()

        VideoInterpolateUpscaleStateHolder.setError("ffprobe failed")

        assertTrue(VideoInterpolateUpscaleStateHolder.state.value is VideoInterpolateUpscaleState.Error)
        assertFalse(VideoInterpolateUpscaleStateHolder.isProcessing.value)
        assertEquals(null, VideoInterpolateUpscaleStateHolder.resultPath.value)
        assertEquals("ffprobe failed", VideoInterpolateUpscaleStateHolder.error.value)
    }
}
