package com.example.llamadroid.ui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWorkspaceModelsTest {
    private val qwen = AudioModelOption(
        id = "qwen",
        displayName = "Qwen",
        family = AudioModelFamily.QWEN3_TTS,
        backendLabel = "llama.cpp",
        installState = AudioModelInstallState.INSTALLED,
        description = "",
        componentSummary = "",
        capabilities = AudioModelCapabilities(
            supportsReferenceAudio = true,
            supportedLanguages = listOf("en", "es")
        ),
        companionOptions = listOf("/models/mmproj.gguf")
    )

    @Test
    fun `invalid advanced values are explained before generation`() {
        val model = qwen.copy(capabilities = qwen.capabilities.copy(supportsSeed = true))
        val valid = AudioWorkspaceDraft(modelId = model.id, text = "Hello")
        listOf(
            valid.copy(seed = "99999999999999999999999999999"),
            valid.copy(batchSize = 1, microBatchSize = 2),
            valid.copy(voiceProfileId = "voice", trimStartMs = 5000, trimEndMs = 1000)
        ).forEach { draft ->
            assertTrue(AudioDraftValidationError.ADVANCED_SETTINGS_INVALID in draft.validationErrors(model, emptyList()))
        }
    }

    @Test
    fun `inactive advanced reference and sampler values do not block Supertonic`() {
        val model = qwen.copy(family = AudioModelFamily.SUPERTONIC)
        val draft = AudioWorkspaceDraft(modelId = model.id, text = "Hello", seed = "invalid", batchSize = 1,
            microBatchSize = 2, voiceProfileId = "voice", trimStartMs = 5000, trimEndMs = 1000)
        assertTrue(AudioDraftValidationError.ADVANCED_SETTINGS_INVALID !in draft.validationErrors(model, emptyList()))
    }

    @Test
    fun `route sections fall back to speech`() {
        assertEquals(AudioWorkspaceSection.SPEECH, AudioWorkspaceSection.fromRoute(null))
        assertEquals(AudioWorkspaceSection.SPEECH, AudioWorkspaceSection.fromRoute("unknown"))
        assertEquals(AudioWorkspaceSection.VOICES, AudioWorkspaceSection.fromRoute("voices"))
        assertEquals(AudioWorkspaceSection.HISTORY, AudioWorkspaceSection.fromRoute("history"))
    }

    @Test
    fun `draft requires model and input`() {
        val errors = AudioWorkspaceDraft().validationErrors(null, emptyList())

        assertTrue(AudioDraftValidationError.MODEL_REQUIRED in errors)
        assertTrue(AudioDraftValidationError.TEXT_REQUIRED in errors)
    }

    @Test
    fun `qwen reference audio remains optional`() {
        val draft = AudioWorkspaceDraft(modelId = qwen.id, text = "Hello")
        val errors = draft.validationErrors(qwen, emptyList())

        assertTrue(AudioDraftValidationError.REFERENCE_AUDIO_REQUIRED !in errors)
        assertTrue(AudioDraftValidationError.LANGUAGE_UNSUPPORTED !in errors)
    }

    @Test
    fun `pocket requires a reference and compatible companion`() {
        val pocket = qwen.copy(
            id = "pocket",
            family = AudioModelFamily.POCKET_TTS,
            capabilities = qwen.capabilities.copy(
                supportsLanguageSelection = false,
                referenceAudioRequired = true,
                supportedLanguages = listOf("en")
            ),
            companionOptions = emptyList()
        )
        val draft = AudioWorkspaceDraft(modelId = pocket.id, text = "Hello")
        val errors = draft.validationErrors(pocket, emptyList())

        assertTrue(AudioDraftValidationError.REFERENCE_AUDIO_REQUIRED in errors)
        assertTrue(AudioDraftValidationError.COMPANION_REQUIRED in errors)
    }

    @Test
    fun `pocket without checkpoint language metadata is blocked`() {
        val pocket = qwen.copy(
            id = "pocket-unknown-language",
            family = AudioModelFamily.POCKET_TTS,
            capabilities = qwen.capabilities.copy(
                supportsLanguageSelection = false,
                referenceAudioRequired = true,
                supportedLanguages = emptyList()
            ),
            companionOptions = listOf("/models/pocket-mmproj.gguf")
        )
        val draft = AudioWorkspaceDraft(
            modelId = pocket.id,
            text = "Hello",
            voiceProfileId = "voice-1",
            companionPath = "/models/pocket-mmproj.gguf"
        )

        assertTrue(AudioDraftValidationError.MODEL_LANGUAGE_REQUIRED in draft.validationErrors(pocket, listOf(
            AudioVoiceProfileUi("voice-1", "Voice", "en", 4f, "recording.wav")
        )))
    }

    @Test
    fun `valid reference draft has no validation errors`() {
        val draft = AudioWorkspaceDraft(modelId = qwen.id, text = "Hello", language = "es", voiceProfileId = "voice-1", companionPath = "/models/mmproj.gguf")
        val voice = AudioVoiceProfileUi("voice-1", "Voice", "es", 4f, "recording.wav")

        assertTrue(draft.validationErrors(qwen, listOf(voice)).isEmpty())
    }

}
