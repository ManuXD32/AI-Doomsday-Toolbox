package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundMediaProcessingTimeoutSupportTest {

    @Test
    fun `media processing timeout is detected from exact or combined type mask`() {
        val mediaProcessing = 1 shl 13
        val dataSync = 1

        assertTrue(isMediaProcessingForegroundTimeout(mediaProcessing, mediaProcessing))
        assertTrue(isMediaProcessingForegroundTimeout(mediaProcessing or dataSync, mediaProcessing))
    }

    @Test
    fun `unrelated foreground service timeout is ignored`() {
        val mediaProcessing = 1 shl 13
        val dataSync = 1

        assertFalse(isMediaProcessingForegroundTimeout(dataSync, mediaProcessing))
        assertFalse(isMediaProcessingForegroundTimeout(0, mediaProcessing))
    }

    @Test
    fun `timeout gate permits shutdown only once`() {
        val gate = ForegroundTimeoutGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertFalse(gate.tryEnter())
    }

    @Test
    fun `timeout cancellation carries user-facing message`() {
        val cancellation = MediaProcessingForegroundTimeoutCancellation("quota exhausted")

        assertEquals("quota exhausted", cancellation.message)
        assertEquals("quota exhausted", cancellation.userMessage)
    }
}
