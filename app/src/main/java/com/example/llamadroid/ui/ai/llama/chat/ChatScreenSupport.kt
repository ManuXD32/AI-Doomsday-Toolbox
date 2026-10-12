package com.example.llamadroid.ui.ai.llama.chat

import android.content.Context
import android.media.MediaRecorder
import android.provider.OpenableColumns
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaMessageEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LITERT_CHAT_MIN_CONTEXT_TOKENS = 512
internal const val LITERT_CHAT_FALLBACK_CONTEXT_MAX = 4000
internal const val LITERT_CHAT_GPU_HIGH_CONTEXT_WARNING_TOKENS = 16_384
internal const val LLAMA_MESSAGE_ACTION_TOUCH_TARGET_DP = 48

internal fun formatLlamaChatString(template: String, vararg args: Any?): String =
    String.format(Locale.getDefault(), template, *args)

internal data class LlamaChatLocalizedStrings(
    val exportSuccess: String,
    val exportFailedFormat: String,
    val errorGeneric: String,
    val noteTranscriptSystem: String,
    val noteTranscriptImage: String,
    val noteTranscriptAudio: String,
    val noteTranscriptVideo: String,
    val notesImportSourceNativeChat: String,
    val saveChatAsNoteSuccess: String,
    val saveChatAsNoteFailedFormat: String,
    val engineLiteRt: String,
    val engineOllama: String,
    val engineLlamaServer: String,
    val noServers: String,
    val samplerInvalidNumber: String,
    val samplerInvalidRangeFormat: String,
    val noServerSelected: String,
    val attachMediaFailed: String,
    val kbUploadNeedsEmbedding: String,
    val documentQueueFailedFormat: String,
    val documentQueuedForChatFormat: String,
    val recordPermissionDenied: String,
    val unpinAiHubDone: String,
    val pinAiHubRequiresServer: String,
    val pinAiHubDone: String,
    val clearChatDone: String
)

@Composable
internal fun llamaChatLocalizedStrings() = LlamaChatLocalizedStrings(
    exportSuccess = stringResource(R.string.llama_export_success),
    exportFailedFormat = stringResource(R.string.llama_export_failed),
    errorGeneric = stringResource(R.string.error_generic),
    noteTranscriptSystem = stringResource(R.string.llama_note_transcript_system),
    noteTranscriptImage = stringResource(R.string.llama_note_transcript_image),
    noteTranscriptAudio = stringResource(R.string.llama_note_transcript_audio),
    noteTranscriptVideo = stringResource(R.string.llama_video_note_label),
    notesImportSourceNativeChat = stringResource(R.string.notes_import_source_native_chat),
    saveChatAsNoteSuccess = stringResource(R.string.llama_save_chat_as_note_success),
    saveChatAsNoteFailedFormat = stringResource(R.string.llama_save_chat_as_note_failed),
    engineLiteRt = stringResource(R.string.llama_engine_litert),
    engineOllama = stringResource(R.string.llama_engine_ollama),
    engineLlamaServer = stringResource(R.string.llama_engine_llama_server),
    noServers = stringResource(R.string.llama_no_servers),
    samplerInvalidNumber = stringResource(R.string.llama_sampler_invalid_number),
    samplerInvalidRangeFormat = stringResource(R.string.llama_sampler_invalid_range),
    noServerSelected = stringResource(R.string.llama_no_server_selected),
    attachMediaFailed = stringResource(R.string.llama_attach_media_failed),
    kbUploadNeedsEmbedding = stringResource(R.string.kb_upload_needs_embedding),
    documentQueueFailedFormat = stringResource(R.string.llama_document_queue_failed),
    documentQueuedForChatFormat = stringResource(R.string.llama_document_queued_for_chat),
    recordPermissionDenied = stringResource(R.string.llama_record_permission_denied),
    unpinAiHubDone = stringResource(R.string.native_chat_unpin_watch_done),
    pinAiHubRequiresServer = stringResource(R.string.native_chat_pin_watch_requires_server),
    pinAiHubDone = stringResource(R.string.native_chat_pin_watch_done),
    clearChatDone = stringResource(R.string.llama_clear_chat_done)
)

internal fun startLlamaRecording(
    context: Context,
    onRecorderReady: (MediaRecorder, File) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val recordingDir = File(context.filesDir, "llama_chat_audio").apply { mkdirs() }
        val tempFile = File(recordingDir, "recording_in_progress.m4a")
        tempFile.delete()
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(tempFile.absolutePath)
            prepare()
            start()
        }
        onRecorderReady(recorder, tempFile)
    } catch (e: Exception) {
        onError(context.getString(R.string.llama_record_error, e.message ?: context.getString(R.string.error_generic)))
    }
}

internal fun stopLlamaRecording(recorder: MediaRecorder?) {
    if (recorder == null) return
    runCatching { recorder.stop() }
    runCatching { recorder.release() }
}

internal fun nativeChatDocumentMimeTypes(): Array<String> = arrayOf(
    "application/pdf",
    "text/*",
    "application/json",
    "application/xml",
    "text/markdown",
    "text/csv",
    "*/*"
)

internal fun queryDocumentDisplayName(context: Context, uri: Uri): String {
    val fromCursor = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromCursor
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        ?: "document"
}

internal fun persistContentUriToAppPrivateFile(
    context: Context,
    uri: Uri,
    prefix: String,
    defaultExtension: String
): String? {
    val mimeType = context.contentResolver.getType(uri)
    val extension = mimeTypeToExtension(mimeType, defaultExtension)
    val mediaDir = File(context.filesDir, "llama_chat_media").apply { mkdirs() }
    val cacheFile = java.io.File(
        mediaDir,
        "${prefix}_${System.currentTimeMillis()}.$extension"
    )

    context.contentResolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: return null

    return cacheFile.absolutePath
}

internal fun persistRecordedAudioToAppPrivateFile(
    context: Context,
    recordingFile: File
): String? {
    if (!recordingFile.exists()) return null
    val mediaDir = File(context.filesDir, "llama_chat_audio").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
    val savedFile = File(mediaDir, "recording_$timestamp.m4a")
    recordingFile.copyTo(savedFile, overwrite = true)
    recordingFile.delete()
    return savedFile.absolutePath
}

internal fun mimeTypeToExtension(mimeType: String?, defaultExtension: String): String {
    return when {
        mimeType.isNullOrBlank() -> defaultExtension
        mimeType.startsWith("audio/") -> when (mimeType) {
            "audio/wav" -> "wav"
            "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/3gpp" -> "3gp"
            "audio/3gpp2" -> "3gpp"
            else -> defaultExtension
        }
        mimeType.startsWith("image/") -> when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/gif" -> "gif"
            else -> defaultExtension
        }
        mimeType.startsWith("video/") -> when (mimeType) {
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/avi", "video/x-msvideo" -> "avi"
            "video/3gpp" -> "3gp"
            "video/3gpp2" -> "3gpp"
            else -> defaultExtension
        }
        else -> defaultExtension
    }
}

internal fun parseParam(jsonStr: String?, key: String, default: Float): Float {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Number)?.toFloat() ?: default
    } catch (e: Exception) {
        default
    }
}

internal fun parseParam(jsonStr: String?, key: String, default: Boolean): Boolean {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Boolean) ?: default
    } catch (e: Exception) {
        default
    }
}

internal fun parseParam(jsonStr: String?, key: String, default: Int): Int {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        when (val value = map[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

internal fun parseParam(jsonStr: String?, key: String, default: String): String {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? String)?.takeIf { it.isNotBlank() } ?: default
    } catch (e: Exception) {
        default
    }
}

internal fun formatSamplerText(value: Float, integer: Boolean = false): String = if (integer) {
    value.toInt().toString()
} else {
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
}

internal fun parseOptionalIntParam(jsonStr: String?, key: String): String {
    if (jsonStr.isNullOrBlank()) return ""
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        when (val value = map[key]) {
            is Number -> value.toInt().toString()
            is String -> value.trim().takeIf { it.toIntOrNull() != null }.orEmpty()
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}

internal fun llamaMessagesToNoteMarkdown(
    systemPrompt: String?,
    messages: List<LlamaMessageEntity>,
    systemLabel: String,
    imageLabel: String,
    audioLabel: String,
    videoLabel: String
): String {
    return buildString {
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            append("## ")
            append(systemLabel)
            append("\n\n")
            append(it.trim())
            append("\n\n")
        }
        messages.forEach { message ->
            append("## ")
            append(message.role.replaceFirstChar { it.titlecase(Locale.getDefault()) })
            append("\n\n")
            append(message.content.trim())
            message.imagePath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(imageLabel).append(": ").append(it) }
            message.audioPath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(audioLabel).append(": ").append(it) }
            message.videoPath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(videoLabel).append(": ").append(it) }
            append("\n\n")
        }
    }.trim()
}
