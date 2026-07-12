package com.example.llamadroid.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "llama_servers")
data class LlamaServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val engine: String = ENGINE_LLAMA_SERVER,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val modelName: String? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = LITERT_BACKEND_AUTO,
    val whisperModelPath: String? = null,
    val whisperLanguage: String = DEFAULT_WHISPER_LANGUAGE,
    val preferWhisperAudioTranscription: Boolean = false,
    val defaultApiParams: String? = null,
    val lastUsed: Long = System.currentTimeMillis()
) {
    fun normalizedEngine(): String = normalizeLlamaServerEngine(engine)

    fun isOllamaEngine(): Boolean = normalizedEngine() == ENGINE_OLLAMA

    fun isLlamaServerEngine(): Boolean = normalizedEngine() == ENGINE_LLAMA_SERVER

    fun isLlamaSwapEngine(): Boolean = normalizedEngine() == ENGINE_LLAMA_SWAP

    fun isLiteRtEngine(): Boolean = normalizedEngine() == ENGINE_LITERT_LM

    fun usesOpenAiCompatibleEngine(): Boolean = isLlamaServerEngine() || isLlamaSwapEngine()

    fun hasDirectAudioInputCapability(): Boolean =
        (usesOpenAiCompatibleEngine() || isLiteRtEngine()) && supportsAudio

    fun supportsDirectAudioInput(): Boolean =
        hasDirectAudioInputCapability() && !preferWhisperAudioTranscription

    fun requiresAudioTranscriptionFallback(): Boolean = !supportsDirectAudioInput()

    fun baseUrl(): String = buildLlamaServerBaseUrl(host, port)

    companion object {
        const val ENGINE_LLAMA_SERVER = "llama-server"
        const val ENGINE_OLLAMA = "ollama"
        const val ENGINE_LLAMA_SWAP = "llama-swap"
        const val ENGINE_LITERT_LM = "litert-lm"
        const val DEFAULT_WHISPER_LANGUAGE = "auto"
    }
}

@Entity(
    tableName = "llama_chat_folders",
    indices = [Index(value = ["name"], unique = true)]
)
data class LlamaChatFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "llama_chat_prompt_profiles",
    indices = [
        Index(value = ["name"], unique = true),
        Index("updatedAt")
    ]
)
data class LlamaChatPromptProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "llama_chats",
    indices = [
        Index("lastModified"),
        Index("folderId"),
        Index("pinnedToAiHub"),
        Index("pinnedServerId"),
        Index("pinnedAt")
    ]
)
data class LlamaChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val lastModified: Long = System.currentTimeMillis(),
    val contextSize: Int = 8192,
    val systemPrompt: String? = null,
    val apiParams: String? = null, // JSON string for overrides like temperature, etc.
    val folderId: Long? = null,
    val pinnedToAiHub: Boolean = false,
    val pinnedServerId: Long? = null,
    val pinnedAt: Long? = null
)

@Entity(
    tableName = "llama_speculative_runs",
    indices = [
        Index("createdAt"),
        Index("savedForever"),
        Index("speculativeMode")
    ]
)
data class LlamaSpeculativeRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    val savedForever: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelPath: String,
    val modelName: String,
    val speculativeMode: String,
    val draftModelPath: String? = null,
    val draftModelName: String? = null,
    val acceptanceRate: Double? = null,
    val promptTokensPerSecond: Double? = null,
    val generationTokensPerSecond: Double? = null,
    val sampleCount: Int = 0,
    val rawMetrics: String = ""
)

@Entity(
    tableName = "llama_messages",
    foreignKeys = [
        ForeignKey(
            entity = LlamaChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class LlamaMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isTruncated: Boolean = false,
    val thinking: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val tps: Double = 0.0,
    val generationTimeMs: Long = 0L
)

object LlamaScheduledTaskScheduleType {
    const val ONE_TIME = "ONE_TIME"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
}

object LlamaScheduledTaskLogStatus {
    const val PENDING_CATCH_UP = "PENDING_CATCH_UP"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val SKIPPED = "SKIPPED"
}

@Entity(
    tableName = "llama_scheduled_tasks",
    foreignKeys = [
        ForeignKey(
            entity = LlamaServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("enabled"),
        Index("nextRunAtMillis"),
        Index("serverId"),
        Index("updatedAt")
    ]
)
data class LlamaScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val serverId: Long? = null,
    val contextSize: Int = 8192,
    val systemPrompt: String? = null,
    val taskPrompt: String,
    val apiParams: String? = null,
    val scheduleType: String = LlamaScheduledTaskScheduleType.ONE_TIME,
    val oneTimeAtMillis: Long? = null,
    val timeOfDayMinutes: Int = 7 * 60,
    val weekdaysMask: Int = 0,
    val dayOfMonth: Int = 1,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val nextRunAtMillis: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunAtMillis: Long? = null
)

@Entity(
    tableName = "llama_scheduled_task_logs",
    foreignKeys = [
        ForeignKey(
            entity = LlamaScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("taskId"),
        Index("scheduledAtMillis"),
        Index("status"),
        Index("createdAt")
    ]
)
data class LlamaScheduledTaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long? = null,
    val taskName: String,
    val scheduledAtMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val durationMs: Long? = null,
    val status: String = LlamaScheduledTaskLogStatus.QUEUED,
    val serverId: Long? = null,
    val serverName: String? = null,
    val serverBaseUrl: String? = null,
    val finalOutput: String = "",
    val error: String? = null,
    val toolActivity: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

private const val AUDIO_TRANSCRIPT_PREFIX = "This is the transcription of an audio sent by the user: "
private const val DOCUMENT_TEXT_PREFIX = "Here is the document the user is refering to: "
private const val DOCUMENT_TEXT_END_MARKER = "[End of document]"

data class EmbeddedDocumentText(
    val name: String,
    val text: String
)

data class LlamaServerCapabilityState(
    val supportsVision: Boolean,
    val supportsAudio: Boolean
)

fun normalizeLlamaServerEngine(engine: String?): String =
    when (engine?.trim()?.lowercase(java.util.Locale.US)?.replace('_', '-')) {
        LlamaServerEntity.ENGINE_OLLAMA -> LlamaServerEntity.ENGINE_OLLAMA
        LlamaServerEntity.ENGINE_LLAMA_SWAP, "llamaswap" -> LlamaServerEntity.ENGINE_LLAMA_SWAP
        LlamaServerEntity.ENGINE_LITERT_LM,
        "litert",
        "litertlm",
        "lite-rt",
        "lite-rt-lm",
        "qnn" -> LlamaServerEntity.ENGINE_LITERT_LM
        else -> LlamaServerEntity.ENGINE_LLAMA_SERVER
    }

fun buildLlamaServerBaseUrl(host: String, port: Int): String {
    val trimmedHost = host.trim()
    return if (trimmedHost.startsWith("http://") || trimmedHost.startsWith("https://")) {
        "$trimmedHost:$port/"
    } else {
        "http://$trimmedHost:$port/"
    }
}

fun mergeUserTextWithAudioTranscript(userText: String, transcript: String): String {
    val typedText = userText.trim()
    val trimmedTranscript = transcript.trim()
    val parts = buildList {
        if (typedText.isNotBlank()) add(typedText)
        if (trimmedTranscript.isNotBlank()) add("$AUDIO_TRANSCRIPT_PREFIX$trimmedTranscript")
    }
    return parts.joinToString(separator = "\n\n")
}

fun mergeUserTextWithDocumentText(userText: String, documentName: String, documentText: String): String {
    val typedText = userText.trim()
    val cleanName = documentName.trim().ifBlank { "document" }
    val cleanText = documentText.trim()
    val documentBlock = buildString {
        append(DOCUMENT_TEXT_PREFIX)
        append(cleanName)
        append("\n\n")
        append(cleanText)
        append("\n\n")
        append(DOCUMENT_TEXT_END_MARKER)
    }
    return buildList {
        if (typedText.isNotBlank()) add(typedText)
        if (cleanText.isNotBlank()) add(documentBlock)
    }.joinToString(separator = "\n\n")
}

fun hasEmbeddedAudioTranscript(content: String): Boolean =
    content.contains(AUDIO_TRANSCRIPT_PREFIX)

fun extractEmbeddedAudioTranscript(content: String): String? {
    val startIndex = content.indexOf(AUDIO_TRANSCRIPT_PREFIX)
    if (startIndex == -1) return null
    return content
        .substring(startIndex + AUDIO_TRANSCRIPT_PREFIX.length)
        .trim()
        .ifBlank { null }
}

fun stripEmbeddedAudioTranscript(content: String): String {
    val startIndex = content.indexOf(AUDIO_TRANSCRIPT_PREFIX)
    if (startIndex == -1) return content
    return content.substring(0, startIndex).trimEnd()
}

fun shouldEmbedAudioAttachment(content: String, audioPath: String?): Boolean =
    !audioPath.isNullOrBlank() && !hasEmbeddedAudioTranscript(content)

fun hasEmbeddedDocumentText(content: String): Boolean =
    content.contains(DOCUMENT_TEXT_PREFIX)

fun extractEmbeddedDocumentText(content: String): EmbeddedDocumentText? {
    val startIndex = content.indexOf(DOCUMENT_TEXT_PREFIX)
    if (startIndex == -1) return null
    val afterPrefix = content.substring(startIndex + DOCUMENT_TEXT_PREFIX.length)
    val nameEnd = afterPrefix.indexOf('\n')
    val rawName = if (nameEnd == -1) afterPrefix else afterPrefix.substring(0, nameEnd)
    val bodyStart = if (nameEnd == -1) afterPrefix.length else nameEnd
    val afterName = afterPrefix.substring(bodyStart).trimStart('\n', '\r')
    val bodyEnd = afterName.indexOf(DOCUMENT_TEXT_END_MARKER)
    val body = if (bodyEnd == -1) afterName else afterName.substring(0, bodyEnd)
    return EmbeddedDocumentText(
        name = rawName.trim().ifBlank { "document" },
        text = body.trim()
    )
}

fun stripEmbeddedDocumentText(content: String): String {
    val startIndex = content.indexOf(DOCUMENT_TEXT_PREFIX)
    if (startIndex == -1) return content
    val markerStart = content.indexOf(DOCUMENT_TEXT_END_MARKER, startIndex)
    val endIndex = if (markerStart == -1) {
        content.length
    } else {
        markerStart + DOCUMENT_TEXT_END_MARKER.length
    }
    return (content.substring(0, startIndex) + content.substring(endIndex)).trim()
}

fun estimateNativeChatTextTokens(text: String): Int {
    val clean = text.trim()
    if (clean.isBlank()) return 0
    return (clean.split("\\s+".toRegex()).size * 1.3).toInt().coerceAtLeast(1)
}

fun deriveOllamaCapabilityState(capabilities: List<String>): LlamaServerCapabilityState {
    val normalized = capabilities.map { it.trim().lowercase() }
    val supportsVision = normalized.any { it in setOf("vision", "image", "images") }
    val supportsAudio = normalized.any { it in setOf("audio", "speech") }
    return LlamaServerCapabilityState(
        supportsVision = supportsVision,
        supportsAudio = supportsAudio
    )
}
