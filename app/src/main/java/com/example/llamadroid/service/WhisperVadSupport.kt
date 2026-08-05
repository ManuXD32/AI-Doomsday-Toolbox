package com.example.llamadroid.service

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Identifies the Whisper caller so latency-sensitive flows can explicitly keep
 * using their existing streaming speech detector instead of a second native VAD pass.
 */
enum class WhisperInvocationPurpose(val allowsNativeVad: Boolean) {
    BATCH_TRANSCRIPTION(true),
    VIDEO_SUMMARY(true),
    MEDIA_WORKFLOW(true),
    AUDIO_ATTACHMENT(true),
    CALL_TRANSCRIPTION(false),
    LIVE_TRANSLATOR(false),
    LANGUAGE_SAMPLE(false)
}

data class WhisperVadConfig(
    val enabled: Boolean = false,
    val modelPath: String? = null,
    val threshold: Float = DEFAULT_THRESHOLD,
    val minSpeechDurationMs: Int = DEFAULT_MIN_SPEECH_MS,
    val minSilenceDurationMs: Int = DEFAULT_MIN_SILENCE_MS,
    val maxSpeechDurationSeconds: Float? = null,
    val speechPaddingMs: Int = DEFAULT_SPEECH_PADDING_MS,
    val samplesOverlap: Float = DEFAULT_SAMPLES_OVERLAP
) {
    fun normalized(): WhisperVadConfig = copy(
        modelPath = modelPath?.trim()?.ifBlank { null },
        threshold = threshold.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: DEFAULT_THRESHOLD,
        minSpeechDurationMs = minSpeechDurationMs.coerceIn(0, MAX_DURATION_MS),
        minSilenceDurationMs = minSilenceDurationMs.coerceIn(0, MAX_DURATION_MS),
        maxSpeechDurationSeconds = maxSpeechDurationSeconds
            ?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(1f, MAX_SPEECH_SECONDS),
        speechPaddingMs = speechPaddingMs.coerceIn(0, MAX_PADDING_MS),
        samplesOverlap = samplesOverlap.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: DEFAULT_SAMPLES_OVERLAP
    )

    fun forPurpose(purpose: WhisperInvocationPurpose): WhisperVadConfig {
        val normalized = normalized()
        return if (purpose.allowsNativeVad) normalized else normalized.copy(enabled = false)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_MIN_SPEECH_MS = 250
        const val DEFAULT_MIN_SILENCE_MS = 100
        const val DEFAULT_SPEECH_PADDING_MS = 30
        const val DEFAULT_SAMPLES_OVERLAP = 0.1f
        const val MAX_DURATION_MS = 60_000
        const val MAX_PADDING_MS = 5_000
        const val MAX_SPEECH_SECONDS = 3_600f

        fun fromJson(value: JSONObject): WhisperVadConfig {
            val modelPath = if (value.has("modelPath") && !value.isNull("modelPath")) {
                value.optString("modelPath").trim().ifBlank { null }
            } else {
                null
            }
            return WhisperVadConfig(
                enabled = value.optBoolean("enabled", false),
                modelPath = modelPath,
                threshold = value.optDouble(
                    "threshold",
                    DEFAULT_THRESHOLD.toDouble()
                ).toFloat(),
                minSpeechDurationMs = value.optInt(
                    "minSpeechDurationMs",
                    DEFAULT_MIN_SPEECH_MS
                ),
                minSilenceDurationMs = value.optInt(
                    "minSilenceDurationMs",
                    DEFAULT_MIN_SILENCE_MS
                ),
                maxSpeechDurationSeconds = value
                    .takeIf {
                        it.has("maxSpeechDurationSeconds") &&
                            !it.isNull("maxSpeechDurationSeconds")
                    }
                    ?.optDouble("maxSpeechDurationSeconds")
                    ?.toFloat(),
                speechPaddingMs = value.optInt(
                    "speechPaddingMs",
                    DEFAULT_SPEECH_PADDING_MS
                ),
                samplesOverlap = value.optDouble(
                    "samplesOverlap",
                    DEFAULT_SAMPLES_OVERLAP.toDouble()
                ).toFloat()
            ).normalized()
        }
    }
}

class WhisperVadUnavailableException(message: String) : IllegalStateException(message)

data class WhisperVadModelSpec(
    val id: String,
    val filename: String,
    val displayName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val recommended: Boolean
)

object WhisperVadModelCatalog {
    val models: List<WhisperVadModelSpec> = listOf(
        WhisperVadModelSpec(
            id = "silero-v6.2.0",
            filename = "ggml-silero-v6.2.0.bin",
            displayName = "Silero VAD v6.2.0",
            downloadUrl = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
            sizeBytes = 885_098L,
            recommended = true
        ),
        WhisperVadModelSpec(
            id = "silero-v5.1.2",
            filename = "ggml-silero-v5.1.2.bin",
            displayName = "Silero VAD v5.1.2",
            downloadUrl = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
            sizeBytes = 885_098L,
            recommended = false
        )
    )

    fun byFilename(filename: String): WhisperVadModelSpec? =
        models.firstOrNull { it.filename.equals(filename, ignoreCase = true) }
}

/** App-owned storage for the small converted Silero models; deliberately not a Room model type. */
object WhisperVadAssetStore {
    private const val EXTERNAL_RELATIVE_DIRECTORY = "models/whisper/vad"
    private const val INTERNAL_DIRECTORY = "whisper_vad_models"
    private const val MIN_MODEL_BYTES = 128L * 1024L
    private const val MAX_MODEL_BYTES = 64L * 1024L * 1024L

    fun directory(context: Context): File {
        val external = context.getExternalFilesDir(null)?.let {
            File(it, EXTERNAL_RELATIVE_DIRECTORY)
        }
        val selected = external?.takeIf { it.exists() || it.mkdirs() }
            ?: File(context.filesDir, INTERNAL_DIRECTORY).apply { mkdirs() }
        require(selected.isDirectory || selected.mkdirs()) {
            "Unable to create Whisper VAD model directory"
        }
        return selected
    }

    fun isReadableModel(file: File): Boolean =
        file.isFile &&
            file.canRead() &&
            file.extension.equals("bin", ignoreCase = true) &&
            file.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES

    fun installedModels(context: Context): List<File> =
        directory(context)
            .listFiles()
            .orEmpty()
            .filter(::isReadableModel)
            .sortedWith(
                compareByDescending<File> {
                    WhisperVadModelCatalog.byFilename(it.name)?.recommended == true
                }.thenBy { it.name.lowercase(Locale.US) }
            )

    fun targetFile(context: Context, filename: String): File =
        File(directory(context), sanitizeFilename(filename))

    fun resolvePath(context: Context, preferredPath: String?): String? {
        preferredPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isReadableModel)
            ?.let { return it.absolutePath }
        return installedModels(context).firstOrNull()?.absolutePath
    }

    fun effectiveConfig(
        context: Context,
        config: WhisperVadConfig,
        purpose: WhisperInvocationPurpose
    ): WhisperVadConfig {
        val purposeConfig = config.forPurpose(purpose)
        if (!purposeConfig.enabled) return purposeConfig
        val resolvedPath = resolvePath(context, purposeConfig.modelPath)
            ?: throw WhisperVadUnavailableException(
                "Voice activity detection is enabled, but no readable Whisper VAD model is installed."
            )
        return purposeConfig.copy(modelPath = resolvedPath)
    }

    suspend fun importModel(
        context: Context,
        sourceUri: Uri,
        requestedFilename: String
    ): File = withContext(Dispatchers.IO) {
        val destination = uniqueTargetFile(context, requestedFilename)
        val temporary = File(destination.parentFile, ".importing-${destination.name}")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open the selected VAD model")
            require(isReadableModel(temporary)) {
                "The selected file is not a readable converted Whisper VAD .bin model"
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            require(isReadableModel(destination)) {
                "The imported Whisper VAD model could not be verified"
            }
            destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deleteModel(context: Context, path: String?): Boolean {
        val requested = path?.let(::File) ?: return false
        val root = runCatching { directory(context).canonicalFile }.getOrNull() ?: return false
        val file = runCatching { requested.canonicalFile }.getOrNull() ?: return false
        if (file.parentFile != root || !file.isFile) return false
        return file.delete()
    }

    private fun uniqueTargetFile(context: Context, requestedFilename: String): File {
        val clean = sanitizeFilename(requestedFilename).let { name ->
            if (name.endsWith(".bin", ignoreCase = true)) name else "$name.bin"
        }
        val root = directory(context)
        var candidate = File(root, clean)
        var index = 1
        while (candidate.exists()) {
            val stem = clean.substringBeforeLast('.', clean)
            candidate = File(root, "$stem-$index.bin")
            index += 1
        }
        return candidate
    }

    private fun sanitizeFilename(filename: String): String =
        filename
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "whisper-vad.bin" }
}

fun JSONObject.putWhisperVadConfig(
    key: String,
    config: WhisperVadConfig
): JSONObject = apply {
    val normalized = config.normalized()
    put(
        key,
        JSONObject().apply {
            put("enabled", normalized.enabled)
            put("modelPath", normalized.modelPath ?: JSONObject.NULL)
            put("threshold", normalized.threshold.toDouble())
            put("minSpeechDurationMs", normalized.minSpeechDurationMs)
            put("minSilenceDurationMs", normalized.minSilenceDurationMs)
            put(
                "maxSpeechDurationSeconds",
                normalized.maxSpeechDurationSeconds?.toDouble() ?: JSONObject.NULL
            )
            put("speechPaddingMs", normalized.speechPaddingMs)
            put("samplesOverlap", normalized.samplesOverlap.toDouble())
        }
    )
}

fun JSONObject.readWhisperVadConfigOrNull(key: String): WhisperVadConfig? {
    val value = optJSONObject(key) ?: return null
    return WhisperVadConfig.fromJson(value)
}

fun JSONObject.readWhisperVadConfig(
    key: String,
    fallback: WhisperVadConfig = WhisperVadConfig()
): WhisperVadConfig = readWhisperVadConfigOrNull(key) ?: fallback.normalized()
