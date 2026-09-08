package com.example.llamadroid.audio

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioBoundaryRegressionTest {
    @Test
    fun truncatedChunkIsRejectedBeforeConcatenation() = withWav { input ->
        AudioWavSupport.writePcmWav(input, 24_000, 1, ByteArray(480))
        RandomAccessFile(input, "rw").use { it.setLength(input.length() - 1) }
        withWav { output ->
            assertThrows(IllegalArgumentException::class.java) {
                AudioWavSupport.concatPcm16(listOf(input), output)
            }
        }
    }

    @Test
    fun differentSampleRatesCannotBeSilentlyJoined() = withWav { first ->
        withWav { second ->
            withWav { output ->
                AudioWavSupport.writePcmWav(first, 24_000, 1, ByteArray(480))
                AudioWavSupport.writePcmWav(second, 48_000, 1, ByteArray(960))
                assertThrows(IllegalArgumentException::class.java) {
                    AudioWavSupport.concatPcm16(listOf(first, second), output)
                }
            }
        }
    }

    @Test
    fun silentReferenceIsRejectedButAudiblePcmIsAccepted() = withWav { input ->
        val pcm = ByteArray(48_000)
        AudioWavSupport.writePcmWav(input, 24_000, 1, pcm)
        assertThrows(IllegalArgumentException::class.java) {
            AudioVoiceAssetStore.validateReference(input)
        }
        for (index in pcm.indices step 2) {
            pcm[index] = 0
            pcm[index + 1] = if (index % 96 < 48) 16 else -16
        }
        AudioWavSupport.writePcmWav(input, 24_000, 1, pcm)
        AudioVoiceAssetStore.validateReference(input)
        assertEquals(1_000L, AudioFileInspector.inspectWav(input).durationMs)
    }

    private fun withWav(block: (File) -> Unit) {
        val file = File.createTempFile("audio_boundary_", ".wav")
        try {
            block(file)
        } finally {
            file.delete()
        }
    }
}
