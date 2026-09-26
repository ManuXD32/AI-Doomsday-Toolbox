package com.example.llamadroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioModelsTest {
    @Test
    fun `request normalization clamps runtime values`() {
        val request = AudioGenerationRequest(
            model = AudioModelDescriptor("qwen", AudioModelFamilies.QWEN3_TTS, "/tmp/model"),
            text = " hello ",
            speed = 99f,
            topP = -1f,
            topK = 9_999,
            maxFrames = 0,
            runtimeThreads = 0,
            outputFormat = "mp3"
        ).normalized()
        assertEquals("hello", request.text)
        assertEquals(4.0f, request.speed)
        assertEquals(0.0f, request.topP)
        assertEquals(4096, request.topK)
        assertEquals(1, request.maxFrames)
        assertEquals(1, request.runtimeThreads)
        assertEquals("mp3", request.outputFormat)
    }

    @Test
    fun `request requires text or source`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioGenerationRequest(
                model = AudioModelDescriptor("qwen", AudioModelFamilies.QWEN3_TTS, "/tmp/model")
            ).validate()
        }
    }

    @Test
    fun `supertonic normalization clamps speed and clears stale reference settings`() {
        val normalized = AudioGenerationRequest(
            model = AudioModelDescriptor("supertonic", AudioModelFamilies.SUPERTONIC, "/models/supertonic"),
            text = "hello",
            speed = 4.0f,
            voiceProfileId = "voice",
            referenceAudioPath = "/voice.wav",
            normalizeReference = true,
            denoiseReference = true,
            trimStartMs = 100,
            trimEndMs = 200
        ).validate()

        assertEquals(2.0f, normalized.speed)
        assertNull(normalized.voiceProfileId)
        assertNull(normalized.referenceAudioPath)
        assertEquals(false, normalized.normalizeReference)
        assertEquals(false, normalized.denoiseReference)
        assertEquals(0L, normalized.trimStartMs)
        assertNull(normalized.trimEndMs)
    }

    @Test
    fun `invalid trim range and microbatch settings are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioGenerationRequest(
                model = AudioModelDescriptor("qwen", AudioModelFamilies.QWEN3_TTS, "/models/model"),
                text = "hello",
                voiceProfileId = "voice",
                trimStartMs = 500,
                trimEndMs = 500
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioGenerationRequest(
                model = AudioModelDescriptor("qwen", AudioModelFamilies.QWEN3_TTS, "/models/model"),
                text = "hello",
                batchSize = 1,
                microBatchSize = 2
            ).validate()
        }
    }

    @Test
    fun `Pocket effective language follows checkpoint and clears inactive voice style`() {
        val normalized = AudioGenerationRequest(
            model = AudioModelDescriptor("pocket", AudioModelFamilies.POCKET_TTS, "/models/pocket", language = "es"),
            text = "Hola", language = "en", voiceStyle = "F1"
        ).validate()
        assertEquals("es", normalized.language)
        assertNull(normalized.voiceStyle)
    }

    @Test
    fun `job snapshot preserves persisted chunk progress`() {
        val snapshot = AudioGenerationJobEntity(
            id = "job",
            adapterId = "adapter",
            family = AudioModelFamilies.QWEN3_TTS,
            modelId = "model",
            modelPath = "/models/model",
            modelDisplayName = "Model",
            completedChunks = 3,
            totalChunks = 8
        ).snapshot()

        assertEquals(3, snapshot.completedChunks)
        assertEquals(8, snapshot.totalChunks)
    }
}
