package com.example.llamadroid.data

import android.content.Context
import android.net.Uri
import com.example.llamadroid.util.ContentUriMetadataResolver
import com.example.llamadroid.util.DocumentUriDisplayName
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SharedFileTarget(val legacyId: String) {
    AUDIO_TRANSCRIPTION("audio_transcription"),
    WORKFLOWS("workflows"),
    IMAGE_GENERATION("image_gen"),
    VIDEO_GENERATION("video_gen"),
    VIDEO_INTERPOLATION("video_interpolation"),
    VIDEO_UPSCALER("video_upscaler"),
    PDF_TOOLBOX("pdf_toolbox"),
    PDF_SUMMARY("pdf_summary"),
    LEGACY_IMAGE_UPSCALER("legacy_image_upscaler");

    companion object {
        fun fromLegacyId(value: String?): SharedFileTarget? = when (value?.trim()) {
            AUDIO_TRANSCRIPTION.legacyId -> AUDIO_TRANSCRIPTION
            WORKFLOWS.legacyId -> WORKFLOWS
            IMAGE_GENERATION.legacyId,
            "imagegen_img2img",
            "imagegen_upscale" -> IMAGE_GENERATION
            VIDEO_GENERATION.legacyId,
            "videogen_img2vid" -> VIDEO_GENERATION
            VIDEO_INTERPOLATION.legacyId,
            "interpolate_then_upscale" -> VIDEO_INTERPOLATION
            VIDEO_UPSCALER.legacyId -> VIDEO_UPSCALER
            PDF_TOOLBOX.legacyId -> PDF_TOOLBOX
            PDF_SUMMARY.legacyId -> PDF_SUMMARY
            LEGACY_IMAGE_UPSCALER.legacyId -> LEGACY_IMAGE_UPSCALER
            else -> null
        }
    }
}

data class SharedFileRequest(
    val id: String,
    val uri: Uri,
    val mimeType: String,
    val target: SharedFileTarget?,
    /** Preserves mode/workflow hints used by older integrations without using them for ownership. */
    val sourceTag: String? = target?.legacyId
) {
    val targetScreen: String?
        get() = sourceTag ?: target?.legacyId
}

/**
 * Global holder for shared file URIs from Intent.ACTION_SEND.
 * Screens can check this and consume the pending file.
 */
object SharedFileHolder {
    /** Existing string constants remain available while call sites move to [SharedFileTarget]. */
    object Target {
        const val AUDIO_TRANSCRIPTION = "audio_transcription"
        const val WORKFLOWS = "workflows"
        const val IMAGE_GEN = "image_gen"
        const val IMAGE_GEN_IMG2IMG = "imagegen_img2img"
        const val IMAGE_GEN_UPSCALE = "imagegen_upscale"
        const val VIDEO_GEN_IMG2VID = "videogen_img2vid"
        const val PDF_TOOLBOX = "pdf_toolbox"
        const val PDF_SUMMARY = "pdf_summary"
    }

    private val _pendingFile = MutableStateFlow<SharedFileRequest?>(null)
    private val pendingFileLock = Any()
    val pendingFile = _pendingFile.asStateFlow()

    fun setPendingFile(
        uri: Uri,
        mimeType: String,
        target: SharedFileTarget,
        sourceTag: String? = target.legacyId
    ): SharedFileRequest = setPendingFile(
        SharedFileRequest(
            id = UUID.randomUUID().toString(),
            uri = uri,
            mimeType = mimeType,
            target = target,
            sourceTag = sourceTag
        )
    )

    /** Compatibility overload for existing internal producers. */
    fun setPendingFile(
        uri: Uri,
        mimeType: String,
        targetScreen: String? = null
    ): SharedFileRequest = setPendingFile(
        SharedFileRequest(
            id = UUID.randomUUID().toString(),
            uri = uri,
            mimeType = mimeType,
            target = SharedFileTarget.fromLegacyId(targetScreen),
            sourceTag = targetScreen
        )
    )

    fun setPendingFile(request: SharedFileRequest): SharedFileRequest {
        synchronized(pendingFileLock) {
            _pendingFile.value = request
        }
        return request
    }

    /**
     * Consumes only a request owned by [target]. Requests for other screens stay pending, so
     * composition order cannot make the wrong destination steal a share.
     */
    fun consumeFor(target: SharedFileTarget): SharedFileRequest? = synchronized(pendingFileLock) {
        val pending = _pendingFile.value ?: return@synchronized null
        if (pending.target == target) {
            _pendingFile.value = null
            pending
        } else {
            null
        }
    }

    /** Compatibility adapter for call sites that still identify their owner with a route string. */
    fun consumePendingFileFor(targetScreen: String): SharedFileRequest? {
        val target = SharedFileTarget.fromLegacyId(targetScreen) ?: return null
        return consumeFor(target)
    }

    /** Compatibility escape hatch. New screen consumers must use [consumeFor]. */
    fun consumePendingFile(): SharedFileRequest? = synchronized(pendingFileLock) {
        _pendingFile.value.also { _pendingFile.value = null }
    }

    /**
     * Copies a shared provider URI into app-private cache while the temporary grant is valid.
     * PDF destinations use the returned file URI for subsequent work, so revoked provider grants
     * become an explicit, recoverable import error instead of a later opaque service failure.
     */
    fun importToCache(
        context: Context,
        pendingFile: SharedFileRequest,
        fallbackDisplayName: String,
        filePrefix: String
    ): ImportedFile {
        val metadata = ContentUriMetadataResolver.resolve(context, pendingFile.uri)
        val displayName = metadata.displayName
            ?: DocumentUriDisplayName.fallbackName(pendingFile.uri, fallbackDisplayName)
        val safeName = displayName
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .trim('_')
            .ifBlank { "document.pdf" }
            .let(::shortenCacheFileName)
        val cacheFile = File(context.cacheDir, "${filePrefix}_${pendingFile.id}_$safeName")
        val input = context.contentResolver.openInputStream(pendingFile.uri)
            ?: throw IOException("Unable to open shared document")
        input.use { source ->
            cacheFile.outputStream().use { target -> source.copyTo(target) }
        }
        if (!cacheFile.isFile || cacheFile.length() == 0L) {
            cacheFile.delete()
            throw IOException("Shared document is empty")
        }

        val cachedUri = Uri.fromFile(cacheFile)
        DocumentUriDisplayName.remember(
            uri = cachedUri,
            displayName = displayName,
            sizeBytes = cacheFile.length(),
            mimeType = metadata.mimeType ?: pendingFile.mimeType
        )
        return ImportedFile(cachedUri, displayName)
    }

    data class ImportedFile(val uri: Uri, val displayName: String)

    private fun shortenCacheFileName(fileName: String, maximumCharacters: Int = 48): String {
        if (fileName.length <= maximumCharacters) return fileName
        val extensionStart = fileName.lastIndexOf('.')
        val extension = if (extensionStart > 0) fileName.substring(extensionStart) else ""
        val baseLimit = (maximumCharacters - extension.length).coerceAtLeast(1)
        return fileName.take(baseLimit) + extension.take(maximumCharacters - baseLimit)
    }

    fun clear() {
        synchronized(pendingFileLock) {
            _pendingFile.value = null
        }
    }
}
