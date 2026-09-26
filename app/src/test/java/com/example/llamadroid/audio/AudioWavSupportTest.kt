package com.example.llamadroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioWavSupportTest {
    @Test
    fun `streaming concatenation preserves pcm duration`() {
        val first = File.createTempFile("audio_first", ".wav")
        val second = File.createTempFile("audio_second", ".wav")
        val output = File.createTempFile("audio_combined", ".wav")
        try {
            AudioWavSupport.writePcmWav(first, 24_000, 1, ByteArray(2_400))
            AudioWavSupport.writePcmWav(second, 24_000, 1, ByteArray(4_800))
            val info = AudioWavSupport.concatPcm16(listOf(first, second), output)
            assertEquals(24_000, info.sampleRate)
            assertEquals(1, info.channels)
            assertEquals(150L, info.durationMs)
            assertEquals(7_200L + 44L, output.length())
            assertTrue(AudioFileInspector.inspectWav(output).durationMs > 0L)
        } finally {
            first.delete()
            second.delete()
            output.delete()
        }
    }
}
