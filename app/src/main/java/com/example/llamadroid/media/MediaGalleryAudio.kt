package com.example.llamadroid.media

import com.example.llamadroid.onnx.OnnxTtsStorage
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** Scans generated-audio roots into [MediaGalleryItem]s of type [MediaGalleryType.AUDIO]. */
internal object MediaGalleryAudioScanner {
    private val audioExtensions = setOf("mp3", "wav")

    /** ONNX TTS outputs: one item per stem (mp3 preferred over wav), metadata in `<name>.json`. */
    fun scanOnnxTts(root: File, add: (MediaGalleryItem) -> Unit) {
        if (!root.isDirectory) return
        OnnxTtsStorage.listGeneratedAudio(root).forEach { file ->
            val metadata = OnnxTtsStorage.readMetadata(file)
            add(
                MediaGalleryItem(
                    identity = MediaGalleryIdentity("onnx-tts:${canonical(file)}"),
                    file = file,
                    type = MediaGalleryType.AUDIO,
                    source = MediaGallerySource.ONNX_TTS,
                    location = MediaGalleryLocation.LOCAL,
                    mimeType = mimeFor(file),
                    createdAt = metadata?.createdAtEpochMs?.takeIf { it > 0L } ?: file.lastModified(),
                    title = metadata?.textPreview?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                    prompt = metadata?.textPreview.orEmpty(),
                    mode = metadata?.modelName.orEmpty(),
                    previewFile = null
                )
            )
        }
    }

    /** Audio workspace outputs: `outputs/<jobId>/audio.(wav|mp3)` with optional `metadata.json`. */
    fun scanAudioWorkspace(root: File, add: (MediaGalleryItem) -> Unit) {
        if (!root.isDirectory) return
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { jobDir ->
            val siblings = jobDir.listFiles().orEmpty()
                .filter { it.isFile && it.nameWithoutExtension == "audio" && it.extension.lowercase(Locale.US) in audioExtensions }
            val file = siblings.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
                ?: siblings.firstOrNull()
                ?: return@forEach
            val metadata = runCatching {
                JSONObject(File(jobDir, "metadata.json").takeIf { it.isFile }?.readText().orEmpty())
            }.getOrNull()
            val request = metadata?.optJSONObject("requestMetadata")
            val title = request?.optString("title").orEmpty()
                .ifBlank { request?.optString("text").orEmpty().take(80) }
                .ifBlank { metadata?.optString("modelName").orEmpty() }
                .ifBlank { jobDir.name }
            add(
                MediaGalleryItem(
                    identity = MediaGalleryIdentity("audio-workspace:${canonical(file)}"),
                    file = file,
                    type = MediaGalleryType.AUDIO,
                    source = MediaGallerySource.AUDIO_WORKSPACE,
                    location = MediaGalleryLocation.LOCAL,
                    mimeType = mimeFor(file),
                    createdAt = metadata?.optLong("createdAt", 0L)?.takeIf { it > 0L } ?: file.lastModified(),
                    title = title,
                    mode = metadata?.optString("family").orEmpty(),
                    previewFile = null
                )
            )
        }
    }

    private fun mimeFor(file: File): String =
        if (file.extension.equals("mp3", ignoreCase = true)) "audio/mpeg" else "audio/wav"

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}

/** The filters implied by `all_media_gallery?type=&source=` route arguments. */
data class MediaGalleryRouteFilters(
    val type: MediaGalleryTypeFilter = MediaGalleryTypeFilter.ALL,
    val source: MediaGallerySourceFilter = MediaGallerySourceFilter.ALL,
    val location: MediaGalleryLocationFilter = MediaGalleryLocationFilter.ALL
)

/**
 * Parses the optional route arguments. Types are `image|video|audio`; sources are the source
 * filter names lowercased (`fast_sd`, `onnx_tts`, ...). `distributed` selects the DISTRIBUTED
 * location instead of a source. Unknown or blank values fall back to "all".
 */
fun parseMediaGalleryRouteFilters(type: String?, source: String?): MediaGalleryRouteFilters {
    val typeFilter = when (type?.trim()?.lowercase(Locale.US)) {
        "image", "images" -> MediaGalleryTypeFilter.IMAGES
        "video", "videos" -> MediaGalleryTypeFilter.VIDEOS
        "audio" -> MediaGalleryTypeFilter.AUDIO
        else -> MediaGalleryTypeFilter.ALL
    }
    val key = source?.trim()?.lowercase(Locale.US).orEmpty()
    if (key == "distributed") {
        return MediaGalleryRouteFilters(typeFilter, MediaGallerySourceFilter.ALL, MediaGalleryLocationFilter.DISTRIBUTED)
    }
    val sourceFilter = MediaGallerySourceFilter.entries.firstOrNull { it.name.lowercase(Locale.US) == key }
        ?: MediaGallerySourceFilter.ALL
    return MediaGalleryRouteFilters(typeFilter, sourceFilter)
}
