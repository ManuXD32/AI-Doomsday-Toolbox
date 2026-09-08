package com.example.llamadroid.audio

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** Imports reference audio while keeping the user-selected original immutable. */
object AudioVoiceAssetStore {
    private val audioExtensions = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus")

    suspend fun import(
        context: Context,
        uri: Uri,
        options: AudioVoiceImportOptions,
        isCancelled: () -> Boolean = { false }
    ): AudioVoiceProfile = withContext(Dispatchers.IO) {
        val normalizedOptions = options.validate()
        require(normalizedOptions.name.isNotBlank()) { "Voice name is required" }
        val sourceName = context.queryAudioDisplayName(uri).orEmpty()
        val extension = extensionFor(context, uri, sourceName)
        require(extension in audioExtensions) { "Unsupported voice audio format: $extension" }
        val id = UUID.randomUUID().toString()
        val original = AudioWorkspaceStorage.voiceOriginal(context, id, extension)
        var normalizedPath: String? = null
        var denoisedPath: String? = null
        try {
            copyUriToFile(context, uri, original)
            validateReference(original)
            if (isCancelled()) throw CancellationException("Voice import cancelled")

            // Keep the immutable source, but always create a compatible
            // PCM16/WAV derivative for a saved voice. This also lets the
            // silence check run on the exact file the native adapters use.
            val requiresProcessing = true
            if (requiresProcessing) {
                val normalizedFile = AudioWorkspaceStorage.voiceNormalized(context, id)
                AudioFfmpeg.processVoice(
                    context = context,
                    input = original,
                    output = normalizedFile,
                    trimStartMs = normalizedOptions.trimStartMs,
                    trimEndMs = normalizedOptions.trimEndMs,
                    normalize = normalizedOptions.normalize,
                    denoise = false,
                    isCancelled = isCancelled
                )
                validateReference(normalizedFile)
                normalizedPath = normalizedFile.absolutePath
            }
            if (normalizedOptions.denoise) {
                val denoiseInput = normalizedPath?.let(::File) ?: original
                val denoisedFile = AudioWorkspaceStorage.voiceDenoised(context, id)
                AudioFfmpeg.processVoice(
                    context = context,
                    input = denoiseInput,
                    output = denoisedFile,
                    trimStartMs = if (normalizedPath == null) normalizedOptions.trimStartMs else 0L,
                    trimEndMs = if (normalizedPath == null) normalizedOptions.trimEndMs else null,
                    normalize = false,
                    denoise = true,
                    isCancelled = isCancelled
                )
                validateReference(denoisedFile)
                denoisedPath = denoisedFile.absolutePath
            }

            val preferred = denoisedPath?.let(::File) ?: normalizedPath?.let(::File) ?: original
            val info = AudioFileInspector.inspect(preferred)
            require(info.durationMs > 0L) { "Voice reference has no duration" }
            val metadata = JSONObject()
                .put("originalFileName", sourceName)
                .put("trimStartMs", normalizedOptions.trimStartMs)
                .put("trimEndMs", normalizedOptions.trimEndMs)
                .put("normalize", normalizedOptions.normalize)
                .put("denoise", normalizedOptions.denoise)
                .toString()
            AudioVoiceProfile(
                id = id,
                name = normalizedOptions.name,
                language = normalizedOptions.language,
                originalPath = original.absolutePath,
                normalizedPath = normalizedPath,
                denoisedPath = denoisedPath,
                sourceUri = uri.toString(),
                adapterId = normalizedOptions.adapterId,
                family = normalizedOptions.family,
                durationMs = info.durationMs,
                sampleRate = info.sampleRate,
                channels = info.channels,
                metadataJson = metadata
            )
        } catch (cancelled: CancellationException) {
            cleanupFailedImport(context, id, original, normalizedPath, denoisedPath)
            throw cancelled
        } catch (error: Throwable) {
            // A requested derivative is part of the user's import contract.
            // Do not silently fall back to the unprocessed original and expose
            // a profile whose effective settings were never applied.
            cleanupFailedImport(context, id, original, normalizedPath, denoisedPath)
            throw IllegalStateException("Voice processing failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    fun validateReference(file: File) {
        require(file.isFile && file.length() > 44L) { "Voice reference is empty" }
        val info = runCatching { AudioFileInspector.inspect(file) }
            .getOrElse { throw IllegalArgumentException("Voice reference could not be read") }
        require(info.durationMs > 0L) { "Voice reference has no duration" }
        if (info.format == "wav") {
            require(info.channels in 1..2) { "Voice reference has unsupported channels" }
            require(info.sampleRate in 8_000..96_000) { "Voice reference has unsupported sample rate" }
            require(info.bitsPerSample in setOf(8, 16, 24, 32)) { "Voice reference has unsupported sample depth" }
            require(!isSilentWav(file, info)) { "Voice reference is silent" }
        }
    }

    /** Applies the generation request's reference settings without mutating a saved voice. */
    suspend fun prepareReference(
        context: Context,
        input: File,
        output: File,
        trimStartMs: Long,
        trimEndMs: Long?,
        normalize: Boolean,
        denoise: Boolean,
        isCancelled: () -> Boolean = { false }
    ): File {
        require(input.isFile) { "Voice reference is missing" }
        val compatiblePcm = input.extension.equals("wav", ignoreCase = true) &&
            runCatching {
                val info = AudioFileInspector.inspectWav(input)
                info.bitsPerSample == 16 && info.channels == 1
            }.getOrDefault(false)
        val needsProcessing = normalize || denoise || trimStartMs > 0L || trimEndMs != null || !compatiblePcm
        if (!needsProcessing) {
            validateReference(input)
            return input
        }
        AudioFfmpeg.processVoice(
            context = context,
            input = input,
            output = output,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            normalize = normalize,
            denoise = denoise,
            isCancelled = isCancelled
        )
        validateReference(output)
        return output
    }

    private fun isSilentWav(file: File, info: AudioFileInfo): Boolean {
        if (info.bitsPerSample != 16) return false
        return AudioWavSupport.isPcm16Silent(file)
    }

    private fun cleanupFailedImport(
        context: Context,
        id: String,
        original: File,
        normalizedPath: String?,
        denoisedPath: String?
    ) {
        setOf(
            original,
            normalizedPath?.let(::File),
            denoisedPath?.let(::File),
            AudioWorkspaceStorage.voiceNormalized(context, id),
            AudioWorkspaceStorage.voiceDenoised(context, id)
        ).filterNotNull().forEach(File::delete)
    }

    private fun extensionFor(context: Context, uri: Uri, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName in audioExtensions) return fromName
        val mime = context.contentResolver.getType(uri).orEmpty()
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase(Locale.US)
            .orEmpty()
    }
}
