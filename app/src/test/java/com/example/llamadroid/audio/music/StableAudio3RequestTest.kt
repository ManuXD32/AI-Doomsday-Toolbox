package com.example.llamadroid.audio.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StableAudio3RequestTest {
    private fun request(operation: StableAudio3Operation = StableAudio3Operation.GENERATE) = StableAudio3Request(
        kind = StableAudio3Kind.MUSIC, operation = operation,
        components = StableAudio3Components(StableAudio3ComponentRef("tokenizer"), StableAudio3ComponentRef("text"),
            StableAudio3ComponentRef("dit"), StableAudio3ComponentRef("decoder"), StableAudio3ComponentRef("encoder")),
        prompt = "", durationSeconds = 30.0, outputPath = "result.wav")

    @Test fun generatedAudioAllowsEmptyPromptAndRequiresNoInput() {
        assertEquals(30.0, request().validate().durationSeconds, 0.0)
        assertTrue(runCatching { request().copy(durationSeconds = 120.01).validate() }.isFailure)
        assertTrue(runCatching { request().copy(durationSeconds = Double.NaN).validate() }.isFailure)
    }

    @Test fun editingOperationsRequireInputAndEncoder() {
        for (operation in listOf(StableAudio3Operation.REMIX, StableAudio3Operation.INPAINT, StableAudio3Operation.EXTEND)) {
            assertTrue(runCatching { request(operation).validate(supportsExtend = true) }.isFailure)
        }
        val remix = request(StableAudio3Operation.REMIX).copy(initAudioPath = "source.wav")
        assertEquals(remix, remix.validate())
        assertTrue(runCatching { remix.copy(components = remix.components.copy(codecEncoder = null)).validate() }.isFailure)
    }

    @Test fun inpaintMaskMustStayInsideRequestedDuration() {
        val edit = request(StableAudio3Operation.INPAINT).copy(initAudioPath = "source.wav", maskStartSeconds = 4.0, maskEndSeconds = 8.0)
        assertEquals(edit, edit.validate())
        assertTrue(runCatching { edit.copy(maskEndSeconds = 31.0).validate() }.isFailure)
        assertTrue(runCatching { edit.copy(maskStartSeconds = 9.0).validate() }.isFailure)
    }

    @Test fun extendRequiresCapabilityAndPreservesPreparedMaskAndSeed() {
        val edit = request(StableAudio3Operation.EXTEND).copy(initAudioPath = "source.wav", maskStartSeconds = 8.0,
            maskEndSeconds = 30.0, seed = 197L)
        assertTrue(runCatching { edit.validate() }.isFailure)
        val restored = StableAudio3Request.fromJsonString(edit.toJsonString()).validate(supportsExtend = true)
        assertEquals(197L, restored.seed)
        assertEquals(8.0, restored.maskStartSeconds!!, 0.0)
        assertEquals(30.0, restored.maskEndSeconds!!, 0.0)
    }

    @Test fun incompatiblePrecisionAndMalformedDigestsAreRejected() {
        val base = request().copy(loras = listOf(StableAudio3Lora("adapter.safetensors")))
        assertTrue(runCatching { base.copy(ditPrecision = StableAudio3DitPrecision.W8A32).validate() }.isFailure)
        assertTrue(runCatching { StableAudio3ComponentRef("dit", sha256 = "invalid").validate("dit") }.isFailure)
        assertTrue(runCatching { request().copy(threads = 0).validate() }.isFailure)
    }

    @Test fun componentAliasesAreCanonicalizedWhenRestoringAJob() {
        val components = StableAudio3Components.fromJson(JSONObject().apply {
            put("stable_audio_tokenizer", JSONObject().put("path", "tokenizer"))
            put("text_encoder", JSONObject().put("path", "text"))
            put("stable_audio_dit", JSONObject().put("path", "dit"))
            put("codec_decoder", JSONObject().put("path", "decoder"))
            put("codec_encoder", JSONObject().put("path", "encoder"))
        })
        assertEquals("tokenizer", components.tokenizer.path)
        assertEquals("text", components.textEncoder.path)
        assertEquals("dit", components.dit.path)
        assertEquals("decoder", components.codecDecoder.path)
        assertEquals("encoder", components.codecEncoder?.path)
    }
}
