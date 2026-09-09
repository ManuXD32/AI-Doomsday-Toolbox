package com.example.llamadroid.service

import java.io.File
import java.util.Base64
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.shouldEmbedAudioAttachment
import com.example.llamadroid.data.api.LlamaChatRequest
import com.google.gson.Gson
import okhttp3.RequestBody

internal const val LLAMA_VIDEO_STREAM_MARKER_PREFIX = "__LLAMA_VIDEO_STREAM_"

internal fun llamaVideoStreamMarker(index: Int): String =
    "$LLAMA_VIDEO_STREAM_MARKER_PREFIX${index}__"

internal fun buildNativeLlamaUserContent(
    userMessage: String,
    imagePath: String? = null,
    audioPath: String? = null,
    videoPath: String? = null,
    videoData: String? = null,
    videoFrameData: List<String> = emptyList(),
    videoSamplingDisclosure: String? = null,
    videoObservation: String? = null,
    includeVideo: Boolean = true,
    additionalAudioPaths: List<String> = emptyList()
): Any {
    val embeddedAudioPaths = buildList {
        audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        additionalAudioPaths
            .asSequence()
            .filter { it.isNotBlank() }
            .forEach { path ->
                if (path !in this) add(path)
            }
    }
    val hasAttachments = !imagePath.isNullOrBlank() ||
        embeddedAudioPaths.isNotEmpty() ||
        (includeVideo && !videoPath.isNullOrBlank()) ||
        videoFrameData.isNotEmpty() || !videoObservation.isNullOrBlank()
    if (!hasAttachments) {
        return userMessage
    }

    val parts = mutableListOf<Map<String, Any>>()
    if (userMessage.isNotBlank()) {
        parts += mapOf("type" to "text", "text" to userMessage)
    }

    imagePath
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            parts += mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to fileToDataUrl(path, inferImageMimeType(path)))
            )
        }

    videoPath
        ?.takeIf { includeVideo }
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            parts += mapOf(
                "type" to "input_video",
                "input_video" to mapOf(
                    // Local llama-server reads this relative file URL from its
                    // private --media-path. Remote requests replace this value
                    // with a stream-only marker before writing the JSON body.
                    "data" to (videoData ?: localLlamaVideoFileUrl(path))
                )
            )
        }

    if (videoFrameData.isNotEmpty()) {
        videoSamplingDisclosure
            ?.takeIf { it.isNotBlank() }
            ?.let { disclosure ->
                parts += mapOf("type" to "text", "text" to disclosure)
            }
        videoFrameData.forEach { frameData ->
            parts += mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to frameData)
            )
        }
    }

    videoObservation
        ?.takeIf { it.isNotBlank() }
        ?.let { observation ->
            parts += mapOf("type" to "text", "text" to observation)
        }

    embeddedAudioPaths.forEach { path ->
        parts += mapOf(
            "type" to "input_audio",
            "input_audio" to mapOf(
                "data" to fileToBase64(path),
                "format" to inferLlamaInputAudioFormat(path)
            )
        )
    }

    return parts
}

internal fun LlamaMessageEntity.toNativeLlamaContent(): Any {
    return toNativeLlamaContent(videoData = null)
}

internal fun LlamaMessageEntity.toNativeLlamaContent(
    videoData: String?,
    videoFrameData: List<String> = emptyList(),
    videoSamplingDisclosure: String? = null,
    videoObservation: String? = null,
    includeVideo: Boolean = true,
    additionalAudioPaths: List<String> = emptyList()
): Any {
    return buildNativeLlamaUserContent(
        userMessage = content,
        imagePath = imagePath,
        audioPath = audioPath.takeIf { shouldEmbedAudioAttachment(content, audioPath) },
        videoPath = videoPath,
        videoData = videoData,
        videoFrameData = videoFrameData,
        videoSamplingDisclosure = videoSamplingDisclosure,
        videoObservation = videoObservation,
        includeVideo = includeVideo,
        additionalAudioPaths = additionalAudioPaths
    )
}

internal fun fileToDataUrl(filePath: String, mimeType: String): String {
    return "data:$mimeType;base64,${fileToBase64(filePath)}"
}

internal fun fileToBase64(filePath: String): String =
    Base64.getEncoder().encodeToString(File(filePath).readBytes())

internal fun inferImageMimeType(filePath: String): String =
    when (File(filePath).extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }

internal fun inferLlamaInputAudioFormat(filePath: String): String =
    when (File(filePath).extension.lowercase()) {
        "wav" -> "wav"
        else -> "mp3"
    }

/** The local transport reference used by llama.cpp's private media directory. */
internal fun localLlamaVideoFileUrl(filePath: String): String =
    NativeLlamaVideoSupport.relativeFileUrl(File(filePath).name)

/**
 * Build a request body whose video marker values are encoded directly into the
 * OkHttp sink. The request envelope and all non-video fields are serialized once;
 * video bytes never become a Kotlin String or byte array.
 */
internal fun buildStreamingLlamaChatRequestBody(
    request: LlamaChatRequest,
    videoPaths: List<String>,
    gson: Gson = Gson()
): RequestBody {
    require(videoPaths.isNotEmpty()) { "At least one video attachment is required" }
    val json = gson.toJson(request)
    return buildStreamingJsonRequestBody(json, videoPaths)
}
