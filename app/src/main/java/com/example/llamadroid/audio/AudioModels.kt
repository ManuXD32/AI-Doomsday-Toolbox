package com.example.llamadroid.audio

import java.io.File

/** Stable identifiers used by the Audio workspace and by prefixed model bundles. */
object AudioAdapterIds {
    const val SUPERTONIC = "supertonic"
    const val LLAMA_CLI = "llama_cli"
    const val STABLE_AUDIO = "stable_audio_litert"
}

object AudioModelFamilies {
    const val SUPERTONIC = "supertonic_tts"
    const val QWEN3_TTS = "qwen3_tts"
    const val POCKET_TTS = "pocket_tts"
    const val CUSTOM = "custom_tts"
    const val STABLE_AUDIO_MUSIC = "stable_audio_music"
    const val STABLE_AUDIO_SFX = "stable_audio_sfx"

    fun isMusicOrSfx(family: String): Boolean = family in setOf(STABLE_AUDIO_MUSIC, STABLE_AUDIO_SFX)
}

object AudioJobStatuses {
    const val QUEUED = "queued"
    const val PREPARING = "preparing"
    const val RUNNING = "running"
    const val CANCELLING = "cancelling"
    const val COMPLETE = "complete"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
    const val ERROR = "error"

    val terminal: Set<String> = setOf(COMPLETE, CANCELLED, INTERRUPTED, ERROR)
}

/**
 * Adapter-neutral description of an installed TTS model.
 *
 * [modelPath] and [companionPath] are local paths owned by the model library.
 * The audio runtime never deletes either path. This lets several prefixed
 * bundles share a component and keeps model provenance outside this package.
 */
data class AudioModelDescriptor(
    val id: String,
    val family: String,
    val modelPath: String,
    val companionPath: String? = null,
    val displayName: String = id,
    val adapterId: String = AudioAdapterIds.LLAMA_CLI,
    val language: String? = null,
    val metadataJson: String = "{}"
) {
    fun modelFile(): File = File(modelPath)
    fun companionFile(): File? = companionPath?.takeIf { it.isNotBlank() }?.let(::File)

    fun normalized(): AudioModelDescriptor = copy(
        id = id.trim(),
        family = family.trim().lowercase(),
        modelPath = modelPath.trim(),
        companionPath = companionPath?.trim()?.takeIf { it.isNotBlank() },
        displayName = displayName.trim().ifBlank { id.trim() },
        adapterId = adapterId.trim().lowercase(),
        language = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    )
}

data class AudioVoiceProfile(
    val id: String,
    val name: String,
    val language: String = "en",
    val originalPath: String,
    val normalizedPath: String? = null,
    val denoisedPath: String? = null,
    val sourceUri: String? = null,
    val adapterId: String? = null,
    val family: String? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val metadataJson: String = "{}"
) {
    val preferredAudioPath: String
        get() = denoisedPath ?: normalizedPath ?: originalPath
}

data class AudioVoiceImportOptions(
    val name: String,
    val language: String = "en",
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val normalize: Boolean = true,
    val denoise: Boolean = false,
    val adapterId: String? = null,
    val family: String? = null
) {
    fun normalized(): AudioVoiceImportOptions = copy(
        name = name.trim(),
        language = language.trim().lowercase().ifBlank { "en" },
        trimStartMs = trimStartMs.coerceAtLeast(0L),
        trimEndMs = trimEndMs,
        adapterId = adapterId?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        family = family?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    )

    fun validate(): AudioVoiceImportOptions {
        val normalized = normalized()
        require(normalized.trimEndMs == null || normalized.trimEndMs > normalized.trimStartMs) {
            "Voice trim end must be after trim start"
        }
        return normalized
    }
}

data class AudioGenerationRequest(
    val model: AudioModelDescriptor,
    val text: String? = null,
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val voiceProfileId: String? = null,
    val referenceAudioPath: String? = null,
    val language: String? = null,
    val voiceStyle: String? = null,
    val speed: Float = 1.0f,
    val totalSteps: Int = 8,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: Long? = null,
    /** llama.cpp calls this the number of semantic audio frames. */
    val maxFrames: Int = 512,
    val runtimeThreads: Int = 4,
    val batchSize: Int = 1,
    val microBatchSize: Int = 1,
    val outputFormat: String = "wav",
    val outputSampleRate: Int = 24_000,
    val chunkSize: Int = 800,
    val normalizeReference: Boolean = true,
    val denoiseReference: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val includeMetadata: Boolean = true,
    val metadataJson: String = "{}"
) {
    fun normalized(): AudioGenerationRequest {
        val normalizedModel = model.normalized()
        val isSupertonic = normalizedModel.family == AudioModelFamilies.SUPERTONIC
        val normalizedTrimStartMs = if (isSupertonic) 0L else trimStartMs.coerceAtLeast(0L)
        return copy(
            model = normalizedModel,
            text = text?.trim()?.takeIf { it.isNotBlank() },
            sourceUri = sourceUri?.trim()?.takeIf { it.isNotBlank() },
            sourceName = sourceName?.trim()?.takeIf { it.isNotBlank() },
            // Supertonic uses a style file rather than speaker-reference
            // conditioning. Clear stale reference fields when a draft is
            // switched from a reference-capable model.
            voiceProfileId = if (isSupertonic) null else voiceProfileId?.trim()?.takeIf { it.isNotBlank() },
            referenceAudioPath = if (isSupertonic) null else referenceAudioPath?.trim()?.takeIf { it.isNotBlank() },
            language = (if (normalizedModel.family == AudioModelFamilies.POCKET_TTS) normalizedModel.language else language ?: normalizedModel.language)
                ?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
            voiceStyle = if (isSupertonic) voiceStyle?.trim()?.takeIf { it.isNotBlank() } else null,
            speed = if (normalizedModel.family == AudioModelFamilies.SUPERTONIC) {
                speed.coerceIn(0.5f, 2.0f)
            } else {
                speed.coerceIn(0.25f, 4.0f)
            },
            totalSteps = totalSteps.coerceIn(1, 64),
            temperature = temperature.coerceIn(0.0f, 2.0f),
            topP = topP.coerceIn(0.0f, 1.0f),
            topK = topK.coerceIn(0, 4096),
            maxFrames = maxFrames.coerceIn(1, 100_000),
            runtimeThreads = runtimeThreads.coerceIn(1, 256),
            batchSize = batchSize.coerceIn(1, 256),
            microBatchSize = microBatchSize.coerceIn(1, 256),
            outputFormat = outputFormat.trim().lowercase().removePrefix(".").let {
                if (it == "mp3") "mp3" else "wav"
            },
            outputSampleRate = outputSampleRate.coerceIn(8_000, 96_000),
            chunkSize = chunkSize.coerceIn(80, 10_000),
            normalizeReference = normalizeReference && !isSupertonic,
            denoiseReference = denoiseReference && !isSupertonic,
            trimStartMs = normalizedTrimStartMs,
            trimEndMs = if (isSupertonic) null else trimEndMs?.takeIf { it > normalizedTrimStartMs }
        )
    }

    fun validate(): AudioGenerationRequest {
        val normalizedModel = model.normalized()
        val isSupertonic = normalizedModel.family == AudioModelFamilies.SUPERTONIC
        val rawTrimStartMs = trimStartMs.coerceAtLeast(0L)
        if (!isSupertonic && (voiceProfileId != null || referenceAudioPath != null) && trimEndMs != null) {
            require(trimEndMs > rawTrimStartMs) {
                "Voice trim end must be after trim start"
            }
        }
        val normalized = normalized()
        require(normalized.model.id.isNotBlank()) { "Audio model id is required" }
        require(normalized.model.modelPath.isNotBlank()) { "Audio model path is required" }
        require(AudioModelFamilies.isMusicOrSfx(normalized.model.family) || !normalized.text.isNullOrBlank() || !normalized.sourceUri.isNullOrBlank()) {
            "Text or a source document is required"
        }
        require(isSupertonic || normalized.microBatchSize <= normalized.batchSize) {
            "Audio microbatch size cannot exceed batch size"
        }
        return normalized
    }
}

enum class AudioProgressUnit { CHUNKS, STEPS, STAGE }

data class AudioProgress(
    val progress: Float,
    val stage: String,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val unit: AudioProgressUnit = AudioProgressUnit.CHUNKS
)

data class AudioAdapterResult(
    val wavFile: File,
    val playableFile: File = wavFile,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val chunkCount: Int = 1,
    val metadataJson: String = "{}"
)

data class AudioJobSnapshot(
    val id: String,
    val status: String,
    val model: AudioModelDescriptor,
    val voiceProfileId: String?,
    val progress: Float,
    val stageMessage: String,
    val outputPath: String?,
    val durationMs: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0
)

data class AudioHistoryItem(
    val id: String,
    val title: String,
    val modelName: String,
    val voiceName: String? = null,
    val language: String = "en",
    val durationMs: Long = 0L,
    val createdAt: Long,
    val audioPath: String,
    val metadataPath: String? = null,
    val status: String = AudioJobStatuses.COMPLETE,
    val source: String = "audio_workspace"
)
