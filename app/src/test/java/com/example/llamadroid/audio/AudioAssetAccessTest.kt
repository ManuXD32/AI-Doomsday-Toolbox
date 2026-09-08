package com.example.llamadroid.audio

import com.example.llamadroid.audio.music.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioAssetAccessTest {
    @Test fun `unconditional music is allowed while speech still requires text`() {
        val request = AudioGenerationRequest(
            model = AudioModelDescriptor("music", AudioModelFamilies.STABLE_AUDIO_MUSIC, "/models/dit.tflite"), text = "")
        request.validate()
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(model = request.model.copy(family = AudioModelFamilies.QWEN3_TTS)).validate()
        }
    }

    @Test fun `queued music claims every graph lora and conditioning input`() {
        val typed = StableAudio3Request(
            kind = StableAudio3Kind.MUSIC, operation = StableAudio3Operation.REMIX,
            components = StableAudio3Components(
                StableAudio3ComponentRef("/models/tokenizer.model"), StableAudio3ComponentRef("/models/text.tflite"),
                StableAudio3ComponentRef("/models/dit.tflite"), StableAudio3ComponentRef("/models/decoder.tflite"),
                StableAudio3ComponentRef("/models/encoder.tflite")),
            prompt = "Music", initAudioPath = "/audio/source.wav",
            loras = listOf(StableAudio3Lora("/models/adapter.safetensors", 0.5f)), outputPath = "/output/new.wav")
        val request = AudioGenerationRequest(
            model = AudioModelDescriptor("music", AudioModelFamilies.STABLE_AUDIO_MUSIC, "/models/dit.tflite"),
            text = "Music", metadataJson = JSONObject().put("stableAudio", typed.toJson()).toString())
        assertEquals(setOf("/models/tokenizer.model", "/models/text.tflite", "/models/dit.tflite",
            "/models/decoder.tflite", "/models/encoder.tflite", "/models/adapter.safetensors", "/audio/source.wav"),
            request.localAssetPaths())
        assertFalse(request.localAssetPaths().contains("/output/new.wav"))
    }

    @Test fun `missing queued input is rejected even when model is installed`() {
        val model = File.createTempFile("audio-model", ".gguf")
        try {
            val request = AudioGenerationRequest(
                model = AudioModelDescriptor("speech", AudioModelFamilies.QWEN3_TTS, model.path),
                text = "Speech", referenceAudioPath = File(model.parentFile, "missing-${model.name}.wav").path)
            assertThrows(IllegalArgumentException::class.java) { request.requireAvailableAssets() }
        } finally { model.delete() }
    }
}
