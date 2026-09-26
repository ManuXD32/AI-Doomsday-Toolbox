package com.example.llamadroid.ui.audio

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow

/** User-facing areas in the local Audio workspace; stored ordinals remain stable. */
enum class AudioWorkspaceSection {
    SPEECH,
    VOICES,
    HISTORY,
    MUSIC,
    SOUND_EFFECTS;

    companion object {
        fun fromRoute(value: String?): AudioWorkspaceSection = when (value?.lowercase()) {
            "voices", "voice" -> VOICES
            "history" -> HISTORY
            "music" -> MUSIC
            "sfx", "sound-effects" -> SOUND_EFFECTS
            else -> SPEECH
        }
    }
}

/** Advanced controls remain available beside the guided four-step flow. */
enum class AudioAdvancedTab {
    COMPONENTS,
    GENERATION,
    VOICE_PROCESSING,
    RUNTIME,
    OUTPUT
}

enum class AudioGuidedStep(val ordinalValue: Int) {
    MODEL(1),
    TEXT(2),
    VOICE(3),
    GENERATE(4)
}

enum class AudioModelFamily {
    QWEN3_TTS,
    POCKET_TTS,
    SUPERTONIC,
    CUSTOM
}

enum class AudioModelInstallState {
    INSTALLED,
    AVAILABLE,
    UNRESOLVED
}

/** A capability flag is rendered as an explanatory unavailable state when false. */
data class AudioModelCapabilities(
    val supportsLanguageSelection: Boolean = true,
    val supportsReferenceAudio: Boolean = false,
    /** Whether generation cannot proceed without a saved reference recording. */
    val referenceAudioRequired: Boolean = false,
    val supportsTemperature: Boolean = true,
    val supportsTopP: Boolean = true,
    val supportsTopK: Boolean = true,
    val supportsSeed: Boolean = true,
    val supportsSpeed: Boolean = true,
    val supportsMaxTokens: Boolean = true,
    val supportsOutputSampleRate: Boolean = true,
    val supportedLanguages: List<String> = emptyList()
)

data class AudioModelOption(
    val id: String,
    val displayName: String,
    val family: AudioModelFamily,
    val backendLabel: String,
    val installState: AudioModelInstallState,
    val description: String,
    val componentSummary: String,
    val capabilities: AudioModelCapabilities = AudioModelCapabilities(),
    val components: List<String> = emptyList(),
    /** Verified companion paths compatible with this main checkpoint. */
    val companionOptions: List<String> = emptyList(),
    val bundledVoiceStyles: List<String> = emptyList(),
    val unresolvedReason: String? = null
)

data class AudioVoiceProfileUi(
    val id: String,
    val name: String,
    val language: String,
    val durationSeconds: Float,
    val sourceLabel: String,
    val referenceAudioUri: String? = null,
    val isCompatible: Boolean = true
)

/** Lifecycle of the single voice preview owned by the Audio workspace controller. */
enum class AudioVoicePlaybackState {
    IDLE,
    PLAYING,
    PAUSED
}

data class AudioHistoryItemUi(
    val id: String,
    val title: String,
    val modelName: String,
    val voiceName: String?,
    val language: String,
    val durationSeconds: Float,
    val createdAtLabel: String,
    val audioPath: String? = null,
    val status: AudioHistoryStatus = AudioHistoryStatus.COMPLETE
)

enum class AudioHistoryStatus {
    COMPLETE,
    INTERRUPTED,
    FAILED
}

enum class AudioWorkspaceLoadError {
    MODELS,
    VOICES,
    HISTORY,
    JOBS
}

data class AudioWorkspaceDraft(
    val modelId: String = "",
    val text: String = "",
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val language: String = "en",
    val voiceProfileId: String? = null,
    val voiceStyle: String? = null,
    val companionPath: String? = null,
    val speed: Float = 1.0f,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: String = "",
    val maxTokens: Int = 512,
    /** Diffusion steps exposed by Supertonic and compatible custom adapters. */
    val totalSteps: Int = 8,
    val batchSize: Int = 1,
    val microBatchSize: Int = 1,
    val outputFormat: String = "wav",
    val outputSampleRate: Int = 24_000,
    val normalizeReference: Boolean = true,
    val denoiseReference: Boolean = false,
    val trimStartMs: Int = 0,
    val trimEndMs: Int = 10_000,
    val runtimeThreads: Int = 4,
    val chunkSize: Int = 800,
    val componentIds: List<String> = emptyList(),
    val includeMetadata: Boolean = true
)

enum class AudioJobStatus {
    IDLE,
    PREPARING,
    RUNNING,
    CANCELLING,
    COMPLETE,
    INTERRUPTED,
    ERROR
}

data class AudioJobUiState(
    val status: AudioJobStatus = AudioJobStatus.IDLE,
    val stageLabel: String? = null,
    val progress: Float? = null,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val message: String? = null,
    val resultId: String? = null
)

data class AudioWorkspaceUiState(
    val models: List<AudioModelOption> = emptyList(),
    val voiceProfiles: List<AudioVoiceProfileUi> = emptyList(),
    val history: List<AudioHistoryItemUi> = emptyList(),
    val job: AudioJobUiState = AudioJobUiState(),
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val pendingRecordedUri: String? = null,
    val activeVoiceProfileId: String? = null,
    val voicePlaybackState: AudioVoicePlaybackState = AudioVoicePlaybackState.IDLE,
    val loadError: AudioWorkspaceLoadError? = null,
    val operationError: Boolean = false
)

data class AudioVoiceImportOptions(
    val name: String,
    val language: String,
    val trimStartMs: Int,
    val trimEndMs: Int,
    val normalize: Boolean,
    val denoise: Boolean
)

/**
 * Boundary between the Compose workspace and the audio runtime.
 *
 * Implementations can be backed by Room/WorkManager/native jobs without making the UI depend on
 * their storage or JNI types. The default controller below keeps the route previewable while the
 * optional audio runtime is unavailable.
 */
interface AudioWorkspaceController {
    val state: StateFlow<AudioWorkspaceUiState>

    fun generate(draft: AudioWorkspaceDraft)
    fun retryGeneration() {}
    fun cancelGeneration()

    fun importTextDocument(uri: Uri) {}

    fun startVoiceRecording() {}
    fun stopVoiceRecording() {}
    fun importVoice(uri: Uri, options: AudioVoiceImportOptions) {}
    fun deleteVoice(profileId: String) {}
    fun renameVoice(profileId: String, name: String) {}
    fun previewVoice(profileId: String) {}
    fun pauseVoicePreview(profileId: String) {}
    fun resumeVoicePreview(profileId: String) {}
    fun stopVoicePreview() {}

    fun playHistory(itemId: String) {}
    fun pauseHistory(itemId: String) {}
    fun renameHistory(itemId: String, title: String) {}
    fun shareHistory(itemId: String) {}
    /** Copy the selected result to a user-selected SAF destination. */
    fun exportHistory(itemId: String, destination: Uri) {}
    fun deleteHistory(itemId: String) {}
}

/**
 * Navigation state for the workspace. The small section/step/tab selections use SavedStateHandle;
 * the complete draft, including long document text, is persisted by AudioWorkspaceDraftStore.
 */
class AudioWorkspaceViewModel(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : androidx.lifecycle.ViewModel() {
    private var sectionState by mutableStateOf(
        savedStateHandle.get<String>(KEY_SECTION)?.let { value ->
            runCatching { AudioWorkspaceSection.valueOf(value) }.getOrDefault(AudioWorkspaceSection.SPEECH)
        } ?: AudioWorkspaceSection.SPEECH
    )
    var section: AudioWorkspaceSection
        get() = sectionState
        set(value) {
            sectionState = value
            savedStateHandle[KEY_SECTION] = value.name
        }

    private var guidedStepState by mutableStateOf(
        savedStateHandle.get<String>(KEY_GUIDED_STEP)?.let { value ->
            runCatching { AudioGuidedStep.valueOf(value) }.getOrDefault(AudioGuidedStep.MODEL)
        } ?: AudioGuidedStep.MODEL
    )
    var guidedStep: AudioGuidedStep
        get() = guidedStepState
        set(value) {
            guidedStepState = value
            savedStateHandle[KEY_GUIDED_STEP] = value.name
        }

    private var advancedTabState by mutableStateOf(
        savedStateHandle.get<String>(KEY_ADVANCED_TAB)?.let { value ->
            runCatching { AudioAdvancedTab.valueOf(value) }.getOrDefault(AudioAdvancedTab.COMPONENTS)
        } ?: AudioAdvancedTab.COMPONENTS
    )
    var advancedTab: AudioAdvancedTab
        get() = advancedTabState
        set(value) {
            advancedTabState = value
            savedStateHandle[KEY_ADVANCED_TAB] = value.name
        }
    var draft: AudioWorkspaceDraft by mutableStateOf(AudioWorkspaceDraft())
        private set
    var isDraftRestored: Boolean by mutableStateOf(false)
        private set

    fun restoreDraft(value: AudioWorkspaceDraft?) {
        if (isDraftRestored) return
        if (value != null) draft = value
        isDraftRestored = true
    }

    fun updateDraft(transform: (AudioWorkspaceDraft) -> AudioWorkspaceDraft) {
        draft = transform(draft)
    }

    companion object {
        private const val KEY_SECTION = "audio_workspace_section"
        private const val KEY_GUIDED_STEP = "audio_workspace_guided_step"
        private const val KEY_ADVANCED_TAB = "audio_workspace_advanced_tab"
    }
}

fun AudioWorkspaceDraft.validationErrors(
    selectedModel: AudioModelOption?,
    voices: List<AudioVoiceProfileUi>
): List<AudioDraftValidationError> {
    val errors = mutableListOf<AudioDraftValidationError>()
    if (selectedModel == null) errors += AudioDraftValidationError.MODEL_REQUIRED
    if (text.isBlank() && sourceUri.isNullOrBlank()) errors += AudioDraftValidationError.TEXT_REQUIRED
    if (selectedModel?.capabilities?.referenceAudioRequired == true &&
        voices.none { it.id == voiceProfileId }) {
        errors += AudioDraftValidationError.REFERENCE_AUDIO_REQUIRED
    }
    if (selectedModel?.family == AudioModelFamily.POCKET_TTS &&
        selectedModel.capabilities.supportedLanguages.isEmpty()) {
        errors += AudioDraftValidationError.MODEL_LANGUAGE_REQUIRED
    }
    if (selectedModel?.capabilities?.supportsLanguageSelection == true &&
        selectedModel.capabilities.supportedLanguages.isNotEmpty() &&
        language !in selectedModel.capabilities.supportedLanguages) {
        errors += AudioDraftValidationError.LANGUAGE_UNSUPPORTED
    }
    if (selectedModel != null && selectedModel.family != AudioModelFamily.SUPERTONIC) {
        val selectedCompanion = companionPath ?: selectedModel.companionOptions.singleOrNull()
        if (selectedCompanion.isNullOrBlank() || selectedCompanion !in selectedModel.companionOptions) {
            errors += AudioDraftValidationError.COMPANION_REQUIRED
        }
    }
    if (selectedModel != null && selectedModel.family != AudioModelFamily.SUPERTONIC) {
        val invalidSeed = selectedModel.capabilities.supportsSeed && seed.isNotBlank() && seed.toLongOrNull() == null
        val invalidTrim = voiceProfileId != null && trimEndMs > 0 && trimEndMs <= trimStartMs
        if (invalidSeed || invalidTrim || microBatchSize > batchSize) {
            errors += AudioDraftValidationError.ADVANCED_SETTINGS_INVALID
        }
    }
    return errors
}


enum class AudioDraftValidationError {
    MODEL_REQUIRED,
    TEXT_REQUIRED,
    REFERENCE_AUDIO_REQUIRED,
    LANGUAGE_UNSUPPORTED,
    COMPANION_REQUIRED,
    MODEL_LANGUAGE_REQUIRED,
    ADVANCED_SETTINGS_INVALID
}
