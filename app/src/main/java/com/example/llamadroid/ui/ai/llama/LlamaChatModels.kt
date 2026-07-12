package com.example.llamadroid.ui.ai.llama

import androidx.annotation.Keep

internal fun displayLlamaModelName(modelName: String): String {
    val trimmed = modelName.trim()
    if (trimmed.isEmpty()) return trimmed
    return trimmed.substringAfterLast('/').substringAfterLast('\\').ifBlank { trimmed }
}

@Keep
data class LlamaChatExportPayload(
    val title: String,
    val systemPrompt: String? = null,
    val apiParams: String? = null,
    val messages: List<LlamaChatSerializedMessage> = emptyList()
)

@Keep
data class LlamaChatSerializedMessage(
    val role: String,
    val content: String,
    val imagePath: String? = null,
    val audioPath: String? = null
)

@Keep
data class NoteExportPayload(
    val title: String = "",
    val content: String = "",
    val type: String = "MANUAL",
    val sourceFile: String? = null,
    val language: String? = null,
    val audioPath: String? = null,
    val isLlmWhitelisted: Boolean = false
)

@Keep
data class NotesExportPayload(
    val notes: List<NoteExportPayload> = emptyList()
)
