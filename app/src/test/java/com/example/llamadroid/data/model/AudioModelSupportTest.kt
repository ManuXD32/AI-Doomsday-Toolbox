package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdArtifactFormat
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdInspectionConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelSupportTest {
    private fun inspection(metadata: Map<String, String>, prefixes: Set<String> = emptySet()) =
        SdArtifactInspection(
            format = SdArtifactFormat.GGUF,
            tensorCount = 12,
            confidence = SdInspectionConfidence.HIGH,
            metadata = metadata,
            tensorNamePrefixes = prefixes,
            headerValid = true
        )

    @Test
    fun `known family comes from GGUF evidence and not filename`() {
        val qwen = AudioModelSupport.recognize(
            inspection(mapOf("general.architecture" to "qwen3_tts")),
            "renamed-model.gguf"
        )
        assertEquals(AudioModelSupport.FAMILY_QWEN3_TTS, qwen?.family)
        assertEquals(ModelType.LLAMA_TTS, qwen?.modelType)

        val filenameOnly = AudioModelSupport.recognize(
            inspection(mapOf("general.architecture" to "qwen2")),
            "Qwen3-TTS-renamed.gguf"
        )
        assertNull(filenameOnly)
    }

    @Test
    fun `custom speech architecture is accepted without curated name`() {
        val custom = AudioModelSupport.recognize(
            inspection(
                mapOf("general.architecture" to "custom_tts"),
                prefixes = setOf("decoder.audio", "speaker_embedding")
            ),
            "voice-model.gguf"
        )
        assertEquals(AudioModelSupport.FAMILY_CUSTOM_TTS, custom?.family)
        assertEquals(AudioModelSupport.ROLE_MAIN, custom?.role)
    }

    @Test
    fun `renamed qwen and pocket companion metadata identifies the component`() {
        val qwenCompanion = AudioModelSupport.recognize(
            inspection(
                mapOf(
                    "general.architecture" to "clip",
                    "clip.projector_type" to "qwen3tts_spkenc"
                )
            ),
            "renamed-projector.gguf"
        )
        assertEquals(AudioModelSupport.FAMILY_QWEN3_TTS, qwenCompanion?.family)
        assertEquals(AudioModelSupport.ROLE_MMProj, qwenCompanion?.role)
        assertEquals(ModelType.LLAMA_TTS_COMPANION, qwenCompanion?.modelType)

        val pocketSpanishCompanion = AudioModelSupport.recognize(
            inspection(
                mapOf(
                    "general.architecture" to "clip",
                    "clip.projector_type" to "pockettts_gen",
                    "model_variant" to "spanish"
                )
            ),
            "component.bin"
        )
        assertEquals(AudioModelSupport.FAMILY_POCKET_TTS, pocketSpanishCompanion?.family)
        assertEquals(AudioModelSupport.LANGUAGE_SPANISH, pocketSpanishCompanion?.language)
        assertEquals(AudioModelSupport.ROLE_MMProj, pocketSpanishCompanion?.role)
    }

    @Test
    fun `portable audio metadata is whitelisted`() {
        val raw = AudioModelSupport.portableMetadata(
            AudioModelSupport.Descriptor(AudioModelSupport.FAMILY_POCKET_TTS, language = "es"),
            "sha256:test"
        )
        assertTrue(raw.contains("audioFamily"))
        assertTrue(raw.contains("audioLanguage"))
        assertTrue(raw.contains("audioArtifactIdentity"))
    }

    @Test
    fun `verified digest restores curated family after renamed import`() {
        val curated = AudioCuratedBundleCatalog.bundles
            .single { it.id == "audio-pocket-tts-spanish" }
            .files
            .single { it.type == ModelType.LLAMA_TTS }
        val descriptor = AudioModelSupport.descriptorForPayload(
            type = ModelType.LLAMA_TTS,
            digest = "sha256:${curated.sha256}",
            repoId = "unrelated/repository",
            filename = "renamed-speech.gguf",
            sourceUrl = "https://example.invalid/source"
        )
        assertEquals(AudioModelSupport.FAMILY_POCKET_TTS, descriptor?.family)
        assertEquals(AudioModelSupport.LANGUAGE_SPANISH, descriptor?.language)

        val restored = AudioModelSupport.descriptorForModel(
            com.example.llamadroid.data.db.ModelEntity(
                filename = "renamed-speech.gguf",
                path = "/tmp/renamed-speech.gguf",
                sizeBytes = curated.sizeBytes,
                type = ModelType.LLAMA_TTS,
                repoId = "unrelated/repository",
                audioArtifactIdentity = curated.artifactIdentity
            )
        )
        assertEquals(AudioModelSupport.FAMILY_POCKET_TTS, restored?.family)
        assertEquals(AudioModelSupport.LANGUAGE_SPANISH, restored?.language)
    }
}
