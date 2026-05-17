package com.example.llamadroid.tama.game

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.RemoteSummaryProtection
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.service.RemoteSummaryRequest
import com.example.llamadroid.service.fileToDataUrl
import com.example.llamadroid.service.inferImageMimeType
import com.example.llamadroid.service.WhisperConfig
import com.example.llamadroid.service.WhisperOutputFormat
import com.example.llamadroid.service.WhisperService
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.Personality
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.isEffectivelyMad
import com.example.llamadroid.tama.db.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

/**
 * TamaAgentService - Handles AI interactions for the Tama pet.
 * Mirroring the multi-agent logic but tailored for the pet's personality and history.
 */
class TamaAgentService(
    private val context: Context,
    private val dao: TamaDao,
    val settingsRepo: SettingsRepository,
    val ollamaService: OllamaService,
    private val scope: CoroutineScope
) {
    private val llamaServerChatService = LlamaServerChatService()
    private val whisperBindingIntent = Intent(context, WhisperService::class.java)

    init {
        syncOllamaSettings()
    }

    private val _messages = MutableStateFlow<List<OllamaService.ChatMessage>>(emptyList())
    val messages: StateFlow<List<OllamaService.ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isBackendConnected = MutableStateFlow(false)
    val isBackendConnected: StateFlow<Boolean> = _isBackendConnected.asStateFlow()

    private var messageCount = 0
    private var lastSummarizedMessageTimestamp: Long = 0L
    private var activeAssistantMessage: ActiveTamaAssistantMessage? = null

    private data class PreparedTamaUserMessage(
        val message: OllamaService.ChatMessage,
        val normalizedPromptText: String
    )

    private fun latestSummaryId(petId: String): String = "${petId}_latest"

    private fun dailySummaryId(petId: String, date: String): String = "${petId}_day_$date"

    /**
     * Load chat history from the database for a specific pet.
     */
    fun loadHistory(petId: String) {
        scope.launch {
            val dbMessages = dao.getChatHistory(petId)
            val converted = dbMessages.map { it.toChatMessage() }
            _messages.value = mergeTamaMessages(
                converted,
                activeAssistantMessage?.takeIf { it.petId == petId }
            )

            // Load watermark from the latest summary
            val recentSummaries = dao.getRecentSummaries(petId, 1)
            lastSummarizedMessageTimestamp = recentSummaries.firstOrNull()?.lastChatMessageTimestamp ?: 0L

            // Approximate message count since last summary for auto-trigger
            val newMsgsSinceSummary = converted.count { (it.timestamp ?: 0L) > lastSummarizedMessageTimestamp }
            messageCount = newMsgsSinceSummary
        }
    }

    /**
     * Clear the current chat history
     */
    fun clearMessages() {
        _messages.value = emptyList()
        messageCount = 0
        // We don't reset lastSummarizedMessageTimestamp here as the summary still exists
    }

    fun requestSummaryRefresh(pet: TamaPet) {
        scheduleSummary(pet, force = true)
    }

    fun observeLatestSummary(petId: String): Flow<TamaSummaryEntity?> {
        return dao.getSummaryForPet(petId).map { it.firstOrNull() }
    }

    /**
     * Send a message to the Pet AI.
     * Launches in the service scope to persist background generation.
     */
    fun sendMessage(
        pet: TamaPet,
        userContent: String,
        audioPath: String? = null,
        audioDurationMs: Long? = null,
        imagePath: String? = null,
        onChunk: (String) -> Unit = {}
    ) {
        scope.launch {
            syncOllamaSettings()
            val preparedMessage = createAndPersistUserMessage(
                pet = pet,
                userContent = userContent,
                audioPath = audioPath,
                audioDurationMs = audioDurationMs,
                imagePath = imagePath
            )
            messageCount++

            if (audioPath != null) {
                processPendingAudioMessage(pet, preparedMessage, onChunk)
            } else if (preparedMessage.normalizedPromptText.isNotBlank() || imagePath != null) {
                generateAssistantReply(pet, preparedMessage, onChunk)
            }
        }
    }

    private suspend fun createAndPersistUserMessage(
        pet: TamaPet,
        userContent: String,
        audioPath: String?,
        audioDurationMs: Long?,
        imagePath: String?
    ): PreparedTamaUserMessage {
        val now = System.currentTimeMillis()
        val userMsgId = UUID.randomUUID().toString()
        val sanitizedImagePath = imagePath?.takeIf {
            settingsRepo.tamaChatImageInputEnabled.value
        }
        val imageData = sanitizedImagePath?.takeIf { path -> java.io.File(path).exists() }?.let { path ->
            listOf(fileToDataUrl(path, inferImageMimeType(path)))
        }
        val transcriptionStatus = if (audioPath != null) TamaTranscriptionStatus.PENDING else null
        val userMsg = OllamaService.ChatMessage(
            id = userMsgId,
            role = "user",
            content = userContent,
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            imagePath = sanitizedImagePath,
            images = imageData,
            transcriptionStatus = transcriptionStatus,
            timestamp = now
        )
        _messages.value = upsertTamaMessage(_messages.value, userMsg)
        dao.saveChatMessage(
            TamaChatMessageEntity(
                id = userMsgId,
                petId = pet.id,
                role = "user",
                content = userContent,
                timestamp = now,
                audioPath = audioPath,
                audioDurationMs = audioDurationMs,
                imagePath = sanitizedImagePath,
                transcriptionStatus = transcriptionStatus
            )
        )
        return PreparedTamaUserMessage(
            message = userMsg,
            normalizedPromptText = userContent.trim()
        )
    }

    private suspend fun processPendingAudioMessage(
        pet: TamaPet,
        preparedMessage: PreparedTamaUserMessage,
        onChunk: (String) -> Unit
    ) {
        val audioPath = preparedMessage.message.audioPath ?: return
        val transcriptionResult = transcribeAudioAttachment(audioPath)
        transcriptionResult.onSuccess { whisperResult ->
            val transcription = whisperResult.text.trim()
            val updatedMessage = preparedMessage.message.copy(
                transcriptionStatus = TamaTranscriptionStatus.COMPLETED,
                transcribedText = transcription,
                transcriptionError = null
            )
            updateStoredUserMessage(pet.id, updatedMessage)
            val normalizedPrompt = mergeUserTextWithTranscript(
                preparedMessage.message.content,
                transcription
            )
            if (normalizedPrompt.isBlank() && updatedMessage.imagePath == null) {
                markTranscriptionFailure(
                    petId = pet.id,
                    message = updatedMessage,
                    error = context.getString(R.string.tama_chat_voice_empty_result)
                )
                return
            }
            generateAssistantReply(
                pet = pet,
                preparedMessage = PreparedTamaUserMessage(
                    message = updatedMessage,
                    normalizedPromptText = normalizedPrompt
                ),
                onChunk = onChunk
            )
        }.onFailure { error ->
            markTranscriptionFailure(
                petId = pet.id,
                message = preparedMessage.message,
                error = error.message ?: context.getString(R.string.error_generic)
            )
        }
    }

    private suspend fun generateAssistantReply(
        pet: TamaPet,
        preparedMessage: PreparedTamaUserMessage,
        onChunk: (String) -> Unit = {}
    ) {
        _isLoading.value = true
        val userMsg = preparedMessage.message

        val recentEvents = dao.getRecentEvents(pet.id, 30)
        val recentSummaries = dao.getRecentSummaries(pet.id, 1)
        val memory = recentSummaries.firstOrNull()?.toStructuredMemory()
            ?: TamaStructuredMemory("", "", emptyList())
        val systemPrompt = buildSystemPrompt(pet, memory, recentEvents)
        val historyContext = _messages.value
            .takeLast(21)
            .filter { it.id != userMsg.id && it.role != "system" }
            .map(::toLlmChatMessage)
        val llmUserMessage = userMsg.copy(
            content = preparedMessage.normalizedPromptText,
            audioPath = null,
            audioDurationMs = null,
            transcriptionStatus = TamaTranscriptionStatus.COMPLETED,
            transcribedText = userMsg.transcribedText
        )
        val fullMessages = listOf(OllamaService.ChatMessage(role = "system", content = systemPrompt)) +
            historyContext +
            llmUserMessage

        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)
        val thinkingEnabled = settingsRepo.tamaThinkingEnabled.value
        var assistantContent = ""
        var thinkingContent = ""
        val assistantId = UUID.randomUUID().toString()
        val placeholderMsg = OllamaService.ChatMessage(
            role = "assistant",
            content = "",
            id = assistantId,
            timestamp = System.currentTimeMillis()
        )
        activeAssistantMessage = ActiveTamaAssistantMessage(
            petId = pet.id,
            assistantId = assistantId,
            timestamp = placeholderMsg.timestamp ?: System.currentTimeMillis()
        )
        _messages.value = mergeTamaMessages(_messages.value + placeholderMsg, activeAssistantMessage)

        val response = if (SettingsRepository.usesOpenAiChatBackend(backend)) {
            withTamaLlamaServerProtection {
                llamaServerChatService.chatWithToolsStreaming(
                    baseUrl = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                        settingsRepo.tamaLlamaSwapUrl.value
                    } else {
                        settingsRepo.tamaLlamaServerUrl.value
                    },
                    messages = fullMessages,
                    modelLabel = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                        settingsRepo.tamaPetModel.value
                    } else {
                        settingsRepo.tamaLlamaServerModelLabel.value
                    },
                    thinkingEnabled = thinkingEnabled,
                    numCtx = settingsRepo.tamaOllamaNumCtx.value,
                    onChunk = { chunk, thinking ->
                        chunk?.takeUnless { it.equals("null", ignoreCase = true) }?.let {
                            assistantContent += sanitizeTamaReplyChunk(it, assistantContent.isBlank())
                            updateActiveAssistantMessage(pet.id, assistantId, assistantContent, thinkingContent)
                            onChunk(it)
                        }
                        thinking?.takeUnless { it.equals("null", ignoreCase = true) }?.let {
                            thinkingContent += sanitizeTamaReplyChunk(it, thinkingContent.isBlank())
                            updateActiveAssistantMessage(pet.id, assistantId, assistantContent, thinkingContent)
                        }
                    }
                )
            }
        } else {
            ollamaService.chatWithToolsStreaming(
                model = settingsRepo.tamaPetModel.value,
                messages = fullMessages,
                thinkingEnabled = thinkingEnabled,
                onChunk = { chunk, thinking ->
                    chunk?.takeUnless { it.equals("null", ignoreCase = true) }?.let {
                        assistantContent += sanitizeTamaReplyChunk(it, assistantContent.isBlank())
                        updateActiveAssistantMessage(pet.id, assistantId, assistantContent, thinkingContent)
                        onChunk(it)
                    }
                    thinking?.takeUnless { it.equals("null", ignoreCase = true) }?.let {
                        thinkingContent += sanitizeTamaReplyChunk(it, thinkingContent.isBlank())
                        updateActiveAssistantMessage(pet.id, assistantId, assistantContent, thinkingContent)
                    }
                }
            )
        }

        _isLoading.value = false

        if (response.isSuccess) {
            val finalMessage = OllamaService.ChatMessage(
                id = assistantId,
                role = "assistant",
                content = sanitizeTamaReplyChunk(assistantContent, true),
                thinking = sanitizeTamaReplyChunk(thinkingContent, true).takeIf { it.isNotBlank() },
                timestamp = System.currentTimeMillis()
            )
            _isBackendConnected.value = true
            finishAssistantMessage(pet.id, finalMessage)
            UnifiedNotificationManager.showTamaChatReplyNotification(pet, finalMessage.content)
            if (messageCount >= 10) {
                scheduleSummary(pet, force = false)
                messageCount = 0
            }
        } else {
            val errorMsg = context.getString(
                R.string.tama_chat_error_message,
                response.exceptionOrNull()?.message ?: context.getString(R.string.error_generic)
            )
            val errorMessage = OllamaService.ChatMessage(
                id = assistantId,
                role = "assistant",
                content = errorMsg,
                timestamp = System.currentTimeMillis()
            )
            _isBackendConnected.value = false
            finishAssistantMessage(pet.id, errorMessage)
        }
    }

    /**
     * Trigger manual or automatic summarization.
     */
    suspend fun summarize(pet: TamaPet, force: Boolean = false): String? {
        val recentSummaries = dao.getRecentSummaries(pet.id, 1)
        val lastSummaryEntity = recentSummaries.firstOrNull()
        val lastEventTimestamp = lastSummaryEntity?.lastEventTimestamp ?: 0L
        val lastMsgTimestamp = lastSummaryEntity?.lastChatMessageTimestamp ?: 0L
        val dreamRecap = latestDreamRecap(pet.id, lastEventTimestamp)
        val previousMemory = lastSummaryEntity?.toStructuredMemory()
            ?: TamaStructuredMemory("", "", emptyList())

        val persistedMessages = dao.getChatHistory(pet.id).map { it.toChatMessage() }
        val combinedMessages = mergeTamaMessages(
            persistedMessages,
            activeAssistantMessage?.takeIf { it.petId == pet.id }
        )

        val newMessages = if (force) combinedMessages else combinedMessages.filter { (it.timestamp ?: 0L) > lastMsgTimestamp }
        val newEvents = if (dreamRecap == null) {
            if (force) dao.getAllEvents(pet.id) else dao.getEventsSince(pet.id, lastEventTimestamp)
        } else {
            emptyList()
        }

        if (!force && dreamRecap == null && newEvents.isEmpty() && newMessages.isEmpty()) return lastSummaryEntity?.summary

        val summaryPrompt = buildStructuredSummaryPrompt(
            pet = pet,
            previousMemory = previousMemory,
            dreamRecap = dreamRecap,
            newEvents = newEvents,
            chatHistory = newMessages
        )
        val thinkingEnabled = settingsRepo.tamaThinkingEnabled.value
        _isLoading.value = true
        var summaryContent = ""
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)
        val response = if (SettingsRepository.usesOpenAiChatBackend(backend)) {
            withTamaLlamaServerProtection {
                val client = RemoteSummaryClientFactory.fromConfig(
                    RemoteSummaryBackendConfig(
                        backend = backend,
                        baseUrl = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                            settingsRepo.tamaLlamaSwapUrl.value.trim().trimEnd('/')
                        } else {
                            settingsRepo.tamaLlamaServerUrl.value.trim().trimEnd('/')
                        },
                        model = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                            settingsRepo.tamaSummarizerModel.value.trim().ifBlank { null }
                        } else {
                            settingsRepo.tamaLlamaServerModelLabel.value?.trim()?.ifBlank { null }
                        },
                        timeoutMinutes = SettingsRepository.PDF_TIMEOUT_DISABLED
                    )
                )
                runCatching {
                    client.summarize(
                        RemoteSummaryRequest(
                            systemPrompt = "",
                            userPrompt = summaryPrompt,
                            contextSize = settingsRepo.tamaOllamaNumCtx.value,
                            maxTokens = settingsRepo.tamaOllamaNumCtx.value,
                            temperature = 0.7f,
                            thinkingEnabled = thinkingEnabled
                        )
                    )
                }.map {
                    summaryContent = sanitizeTamaModelOutput(it.output.ifBlank { it.rawOutput })
                    OllamaService.ChatResponse(
                        message = OllamaService.ChatMessage(role = "assistant", content = summaryContent),
                        done = true
                    )
                }
            }
        } else {
            ollamaService.chatWithToolsStreaming(
                model = settingsRepo.tamaSummarizerModel.value,
                messages = listOf(OllamaService.ChatMessage(role = "user", content = summaryPrompt)),
                thinkingEnabled = thinkingEnabled,
                onChunk = { chunk, _ ->
                    chunk?.let { summaryContent += it }
                }
            )
        }
        _isLoading.value = false

        summaryContent = sanitizeTamaModelOutput(summaryContent)
        if (response.isSuccess && summaryContent.isNotBlank()) {
            val structuredMemory = runCatching { parseStructuredMemory(summaryContent) }
                .getOrElse {
                    TamaStructuredMemory(
                        shortTermSummary = "",
                        longTermSummary = sanitizeTamaModelOutput(summaryContent),
                        retrievalNotes = emptyList()
                    )
                }
            val displaySummary = structuredMemory.toDisplaySummary()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val summaryDate = dreamRecap?.albumDate?.takeIf { it.isNotBlank() } ?: date
            val newestEventTs = newEvents.lastOrNull()?.timestamp ?: lastEventTimestamp
            val newestMsgTs = newMessages.lastOrNull()?.timestamp ?: lastMsgTimestamp
            val summaryWatermarkTs = listOfNotNull(
                newestEventTs.takeIf { it > 0L },
                newestMsgTs.takeIf { it > 0L },
                dreamRecap?.completedAt?.takeIf { it > 0L }
            ).maxOrNull() ?: System.currentTimeMillis()
            val savedAt = System.currentTimeMillis()

            val summaryEntity = TamaSummaryEntity(
                id = latestSummaryId(pet.id),
                petId = pet.id,
                date = date,
                summary = displaySummary,
                shortTermSummary = structuredMemory.shortTermSummary,
                longTermSummary = structuredMemory.longTermSummary,
                retrievalNotesJson = org.json.JSONArray().apply {
                    structuredMemory.retrievalNotes.forEach { note ->
                        put(
                            org.json.JSONObject().apply {
                                put("text", note.text)
                                put("tags", org.json.JSONArray(note.tags))
                            }
                        )
                    }
                }.toString(),
                createdAt = savedAt,
                lastEventTimestamp = summaryWatermarkTs,
                lastChatMessageTimestamp = newestMsgTs
            )
            val historySummary = dreamRecap?.story
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { dreamSummary ->
                    TamaSummaryEntity(
                        id = dailySummaryId(pet.id, summaryDate),
                        petId = pet.id,
                        date = summaryDate,
                        summary = dreamSummary,
                        shortTermSummary = structuredMemory.shortTermSummary,
                        longTermSummary = structuredMemory.longTermSummary,
                        retrievalNotesJson = summaryEntity.retrievalNotesJson,
                        createdAt = dreamRecap.completedAt,
                        lastEventTimestamp = summaryWatermarkTs,
                        lastChatMessageTimestamp = newestMsgTs
                    )
                }
            if (historySummary != null) {
                dao.saveSummaries(listOf(historySummary, summaryEntity))
            } else {
                dao.saveSummary(summaryEntity)
            }

            // Update in-memory watermark
            lastSummarizedMessageTimestamp = newestMsgTs

            // Add notification message to chat history
            val notificationId = UUID.randomUUID().toString()
            val notificationMsg = OllamaService.ChatMessage(
                id = notificationId,
                role = "system",
                content = context.getString(R.string.tama_summary_updated, pet.name),
                timestamp = System.currentTimeMillis()
            )
            _messages.value = _messages.value + notificationMsg
            dao.saveChatMessage(TamaChatMessageEntity(
                id = notificationId,
                petId = pet.id,
                role = "system",
                content = notificationMsg.content,
                timestamp = notificationMsg.timestamp ?: System.currentTimeMillis()
            ))

            return displaySummary
        }
        return null
    }

    fun deleteMessage(id: String) {
        _messages.value = _messages.value.filter { it.id != id }
        scope.launch {
            dao.deleteChatMessage(id)
        }
    }

    suspend fun getLatestSummary(petId: String): String? {
        return dao.getRecentSummaries(petId, 1).firstOrNull()?.summary
    }

    fun scheduleSummary(pet: TamaPet, force: Boolean = false) {
        scope.launch {
            summarize(pet, force)
        }
    }

    fun retryConnection() {
        _isLoading.value = true
        scope.launch {
            try {
                val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)
                val connected = if (SettingsRepository.usesOpenAiChatBackend(backend)) {
                    withTamaLlamaServerProtection {
                        val baseUrl = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                            settingsRepo.tamaLlamaSwapUrl.value
                        } else {
                            settingsRepo.tamaLlamaServerUrl.value
                        }
                        val llamaConnected = llamaServerChatService.checkConnection(baseUrl)
                        if (llamaConnected) {
                            refreshRemoteBackendMetadata().isSuccess
                        } else {
                            false
                        }
                    }
                } else {
                    syncOllamaSettings()
                    ollamaService.checkConnection().also { if (it) ollamaService.listModels() }
                }
                _isBackendConnected.value = connected
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun refreshLlamaServerMetadata(): Result<RemoteSummaryMetadata> {
        return refreshRemoteBackendMetadata()
    }

    suspend fun refreshRemoteBackendMetadata(): Result<RemoteSummaryMetadata> {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)
        return withTamaLlamaServerProtection {
            val client = RemoteSummaryClientFactory.fromConfig(
                RemoteSummaryBackendConfig(
                    backend = backend,
                    baseUrl = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                        settingsRepo.tamaLlamaSwapUrl.value.trim().trimEnd('/')
                    } else {
                        settingsRepo.tamaLlamaServerUrl.value.trim().trimEnd('/')
                    },
                    model = if (SettingsRepository.isLlamaSwapBackend(backend)) {
                        settingsRepo.tamaSummarizerModel.value.trim().ifBlank { null }
                    } else {
                        settingsRepo.tamaLlamaServerModelLabel.value?.trim()?.ifBlank { null }
                    },
                    timeoutMinutes = SettingsRepository.PDF_TIMEOUT_DISABLED
                )
            )
            client.fetchMetadata().onSuccess { metadata ->
                if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
                    settingsRepo.setTamaLlamaServerModelLabel(metadata.serverModelLabel)
                    settingsRepo.setTamaLlamaServerContextTokens(metadata.serverContextTokens)
                    settingsRepo.setTamaLlamaServerContextLabel(metadata.serverContextLabel)
                }
                _isBackendConnected.value = true
            }.onFailure {
                _isBackendConnected.value = false
            }
        }
    }

    /**
     * Manually update the pet's summary (brain).
     */
    suspend fun updateSummary(pet: TamaPet, newSummary: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val existing = dao.getRecentSummaries(pet.id, 1).firstOrNull()

        val summaryEntity = TamaSummaryEntity(
            id = latestSummaryId(pet.id),
            petId = pet.id,
            date = date,
            summary = newSummary,
            shortTermSummary = existing?.shortTermSummary.orEmpty(),
            longTermSummary = newSummary,
            retrievalNotesJson = existing?.retrievalNotesJson ?: "[]",
            createdAt = System.currentTimeMillis(),
            lastEventTimestamp = existing?.lastEventTimestamp ?: 0L,
            lastChatMessageTimestamp = existing?.lastChatMessageTimestamp ?: 0L
        )
        dao.saveSummary(summaryEntity)
    }

    private suspend fun markTranscriptionFailure(
        petId: String,
        message: OllamaService.ChatMessage,
        error: String
    ) {
        updateStoredUserMessage(
            petId = petId,
            message = message.copy(
                transcriptionStatus = TamaTranscriptionStatus.FAILED,
                transcriptionError = error
            )
        )
    }

    private suspend fun updateStoredUserMessage(
        petId: String,
        message: OllamaService.ChatMessage
    ) {
        _messages.value = upsertTamaMessage(_messages.value, message)
        dao.saveChatMessage(
            TamaChatMessageEntity(
                id = message.id,
                petId = petId,
                role = message.role,
                content = message.content,
                timestamp = message.timestamp ?: System.currentTimeMillis(),
                thinking = message.thinking,
                audioPath = message.audioPath,
                audioDurationMs = message.audioDurationMs,
                imagePath = message.imagePath,
                transcriptionStatus = message.transcriptionStatus,
                transcribedText = message.transcribedText,
                transcriptionError = message.transcriptionError
            )
        )
    }

    private suspend fun transcribeAudioAttachment(audioPath: String) =
        withWhisperService { whisperService ->
            val modelPath = resolveWhisperModelPath()
                ?: return@withWhisperService Result.failure(
                    IllegalStateException(context.getString(R.string.whisper_error_no_model))
                )
            whisperService.transcribe(
                WhisperConfig(
                    modelPath = modelPath,
                    audioPath = audioPath,
                    language = settingsRepo.tamaWhisperLanguage.value,
                    outputFormats = setOf(WhisperOutputFormat.TXT),
                    threads = settingsRepo.whisperThreads.value
                )
            )
        }

    private suspend fun resolveWhisperModelPath(): String? {
        settingsRepo.tamaWhisperModelPath.value?.takeIf { it.isNotBlank() }?.let { return it }
        return com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
            .modelDao()
            .getModelsByTypesSync(listOf(com.example.llamadroid.data.db.ModelType.WHISPER))
            .firstOrNull()
            ?.path
    }

    private suspend fun <T> withWhisperService(
        block: suspend (WhisperService) -> Result<T>
    ): Result<T> {
        context.startForegroundService(whisperBindingIntent)
        return suspendCancellableCoroutine { continuation ->
            var isBound = false
            val connection = object : ServiceConnection {
                private fun finish(result: Result<T>) {
                    if (isBound) {
                        runCatching { context.unbindService(this) }
                        isBound = false
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = (binder as? WhisperService.WhisperBinder)?.getService()
                    if (service == null) {
                        finish(Result.failure(IllegalStateException(context.getString(R.string.whisper_error_no_service))))
                        return
                    }
                    scope.launch {
                        val result = runCatching { block(service) }.getOrElse { Result.failure(it) }
                        finish(result)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException(context.getString(R.string.whisper_error_no_service))))
                }
            }

            isBound = context.bindService(whisperBindingIntent, connection, Context.BIND_AUTO_CREATE)
            if (!isBound && continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException(context.getString(R.string.whisper_error_no_service))))
            }
            continuation.invokeOnCancellation {
                if (isBound) {
                    runCatching { context.unbindService(connection) }
                }
            }
        }
    }

    private fun syncOllamaSettings() {
        ollamaService.setBaseUrl(settingsRepo.tamaOllamaUrl.value)
        ollamaService.setUseMmap(settingsRepo.tamaOllamaMmap.value)
        ollamaService.setNumThreads(settingsRepo.tamaOllamaThreads.value)
        ollamaService.setNumCtx(settingsRepo.tamaOllamaNumCtx.value)
    }

    private suspend fun <T> withTamaLlamaServerProtection(block: suspend () -> T): T {
        if (!SettingsRepository.isLlamaServerBackend(settingsRepo.tamaBackend.value)) {
            return block()
        }

        RemoteSummaryProtection.acquire(context)
        return try {
            block()
        } finally {
            RemoteSummaryProtection.release()
        }
    }

    private fun updateActiveAssistantMessage(
        petId: String,
        assistantId: String,
        content: String,
        thinking: String
    ) {
        activeAssistantMessage = ActiveTamaAssistantMessage(
            petId = petId,
            assistantId = assistantId,
            content = sanitizeTamaReplyChunk(content, true),
            thinking = sanitizeTamaReplyChunk(thinking, true).takeIf { it.isNotBlank() },
            timestamp = activeAssistantMessage?.timestamp ?: System.currentTimeMillis()
        )
        _messages.value = mergeTamaMessages(_messages.value, activeAssistantMessage)
    }

    private suspend fun finishAssistantMessage(
        petId: String,
        finalMessage: OllamaService.ChatMessage
    ) {
        activeAssistantMessage = null
        _messages.value = upsertTamaMessage(_messages.value, finalMessage)
        dao.saveChatMessage(
            TamaChatMessageEntity(
                id = finalMessage.id,
                petId = petId,
                role = finalMessage.role,
                content = finalMessage.content,
                timestamp = finalMessage.timestamp ?: System.currentTimeMillis(),
                thinking = finalMessage.thinking,
                audioPath = finalMessage.audioPath,
                audioDurationMs = finalMessage.audioDurationMs,
                imagePath = finalMessage.imagePath,
                transcriptionStatus = finalMessage.transcriptionStatus,
                transcribedText = finalMessage.transcribedText,
                transcriptionError = finalMessage.transcriptionError
            )
        )
    }

    private fun TamaChatMessageEntity.toChatMessage(): OllamaService.ChatMessage {
        return OllamaService.ChatMessage(
            id = id,
            role = role,
            content = content,
            thinking = thinking,
            imagePath = imagePath,
            images = imagePath?.takeIf { path -> java.io.File(path).exists() }?.let { path ->
                listOf(fileToDataUrl(path, inferImageMimeType(path)))
            },
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            transcriptionStatus = transcriptionStatus,
            transcribedText = transcribedText,
            transcriptionError = transcriptionError,
            timestamp = timestamp
        )
    }

    private suspend fun buildSystemPrompt(
        pet: TamaPet,
        memory: TamaStructuredMemory,
        recentEvents: List<TamaEventEntity>
    ): String {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val speciesLine = PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
        val activeStudySession = dao.getActiveStudySession(pet.id)
        val retrievalHints = buildSet {
            add(pet.currentLocationId)
            add(if (pet.isSleeping) "sleeping" else pet.currentActivity.name.lowercase())
            activeStudySession?.let { session ->
                add(session.mode.lowercase())
                add(session.currentPhase.lowercase())
                TamaStudySessionSupport.decodeLabelNames(session).mapTo(this) { it.lowercase() }
            }
            pet.inventory.mapTo(this) { it.name.lowercase() }
            recentEvents.takeLast(6).mapTo(this) { it.eventType.lowercase() }
        }
        return buildTamaSystemPrompt(
            basePrompt = settingsRepo.tamaPetPrompt.value,
            pet = pet,
            speciesLine = speciesLine,
            memory = memory,
            recentEvents = recentEvents,
            retrievalHints = retrievalHints,
            currentTime = currentTime,
            studyContext = TamaStudySessionSupport.activeStudyContextLine(context, activeStudySession)
        )
    }

    private fun buildStructuredSummaryPrompt(
        pet: TamaPet,
        previousMemory: TamaStructuredMemory,
        dreamRecap: DreamRecapContext?,
        newEvents: List<TamaEventEntity>,
        chatHistory: List<OllamaService.ChatMessage>
    ): String {
        val contextText = buildSummarizerContext(
            pet = pet,
            previousMemory = previousMemory,
            dreamRecap = dreamRecap,
            newEvents = newEvents,
            chatHistory = chatHistory
        )
        return buildString {
            appendLine(settingsRepo.tamaSummarizerPrompt.value.trim())
            appendLine()
            appendLine("Return JSON only using this exact shape:")
            appendLine("{")
            appendLine("  \"shortTermSummary\": \"...\",")
            appendLine("  \"longTermSummary\": \"...\",")
            appendLine("  \"retrievalNotes\": [")
            appendLine("    {\"text\": \"...\", \"tags\": [\"inventory\", \"farm\"]}")
            appendLine("  ]")
            appendLine("}")
            appendLine()
            appendLine("Write shortTermSummary as the most recent mood, actions, and chat context.")
            appendLine("Write longTermSummary as stable preferences, relationships, habits, and identity facts.")
            appendLine("Keep retrievalNotes compact, factual, and useful for later recall.")
            appendLine("Do not include any explanation outside the JSON object.")
            appendLine()
            appendLine("Context to summarize:")
            append(contextText)
        }
    }

    private fun buildSummarizerContext(
        pet: TamaPet,
        previousMemory: TamaStructuredMemory,
        dreamRecap: DreamRecapContext?,
        newEvents: List<TamaEventEntity>,
        chatHistory: List<OllamaService.ChatMessage>
    ): String {
        val eventsStr = newEvents.joinToString("\n") {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$time] Event: ${it.eventType} - ${it.details}"
        }.ifBlank { "No new events." }
        val historyStr = chatHistory.joinToString("\n") {
            buildString {
                append("${it.role.uppercase()}: ${it.content}")
                it.transcribedText?.takeIf(String::isNotBlank)?.let { transcript ->
                    append("\n[AUDIO TRANSCRIPT] ")
                    append(transcriptInstructionText(transcript))
                }
            }
        }.ifBlank { "No new chat history." }
        val dreamRecapStr = dreamRecap?.let {
            """
            [Dream Recap]
            Day: ${it.albumDate ?: "unknown"}
            Completed at: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.completedAt))}
            Story: ${it.story}
            """.trimIndent()
        }

        return """
            [Pet Identity]
            Name: ${pet.name}
            Stage: ${pet.stage.displayName}
            Personality: ${pet.personality.name}
            Location: ${pet.currentLocationId}
            Activity: ${if (pet.isSleeping) "sleeping" else pet.currentActivity.name.lowercase()}

            [Previous Structured Memory]
            ${previousMemory.toDisplaySummary()}

            ${dreamRecapStr ?: "[New Registered Actions & Events]\n$eventsStr"}

            [New Conversation History (Since last summary)]
            $historyStr
        """.trimIndent()
    }

    private suspend fun latestDreamRecap(petId: String, sinceTimestamp: Long): DreamRecapContext? {
        val artworks = dao.getArtworks(petId)
            .filter { it.kind == com.example.llamadroid.tama.data.TamaArtworkKind.DAILY_DREAM.name }
            .filter { it.status == com.example.llamadroid.tama.data.TamaArtworkStatus.COMPLETED.name }
            .filter { !it.albumId.isNullOrBlank() }
        if (artworks.isEmpty()) return null

        return artworks
            .groupBy { it.albumId }
            .mapNotNull { (albumId, items) ->
                val resolvedAlbumId = albumId ?: return@mapNotNull null
                val completedAt = items.maxOfOrNull { it.completedAt ?: it.createdAt } ?: return@mapNotNull null
                if (completedAt <= sinceTimestamp) return@mapNotNull null
                val story = items.firstNotNullOfOrNull { it.albumSummary?.takeIf(String::isNotBlank) } ?: return@mapNotNull null
                DreamRecapContext(
                    albumId = resolvedAlbumId,
                    albumDate = items.firstNotNullOfOrNull { it.albumDate },
                    story = story,
                    completedAt = completedAt
                )
            }
            .maxByOrNull { it.completedAt }
    }

    private data class DreamRecapContext(
        val albumId: String,
        val albumDate: String?,
        val story: String,
        val completedAt: Long
    )

    private fun mergeUserTextWithTranscript(userText: String, transcript: String?): String {
        val parts = mutableListOf<String>()
        userText.trim().takeIf { it.isNotBlank() }?.let(parts::add)
        transcript?.trim()?.takeIf { it.isNotBlank() }?.let { parts += transcriptInstructionText(it) }
        return parts.joinToString("\n\n")
    }

    private fun transcriptInstructionText(transcript: String): String {
        return "This is the transcription of an audio sent by the user: $transcript"
    }

    private fun toLlmChatMessage(message: OllamaService.ChatMessage): OllamaService.ChatMessage {
        val normalizedContent = if (message.role == "user") {
            mergeUserTextWithTranscript(message.content, message.transcribedText)
        } else {
            message.content
        }
        return message.copy(
            content = normalizedContent,
            audioPath = null,
            audioDurationMs = null
        )
    }
}

internal fun buildTamaSystemPrompt(
    basePrompt: String,
    pet: TamaPet,
    speciesLine: PetSpeciesLine,
    memory: TamaStructuredMemory,
    recentEvents: List<TamaEventEntity>,
    retrievalHints: Set<String>,
    currentTime: String,
    studyContext: String = "The pet is not currently studying."
): String {
    val statsStr = "hunger=${pet.stats.hunger.toInt()}, happiness=${pet.stats.happiness.toInt()}, health=${pet.stats.health.toInt()}, energy=${pet.stats.energy.toInt()}, hygiene=${pet.stats.hygiene.toInt()}"
    val moodStr = if (pet.isEffectivelyMad()) "mad" else pet.mood.name.lowercase()
    val speciesStr = speciesLine.promptLabel.replaceFirstChar { it.uppercase() }
    val locationStr = pet.currentLocationId.ifBlank { "home" }
    val activityStr = if (pet.isSleeping) "sleeping" else pet.currentActivity.name.lowercase()
    val inventoryStr = pet.inventory
        .groupBy { it.name }
        .entries
        .sortedBy { it.key.lowercase() }
        .joinToString(", ") { (name, items) ->
            val totalQuantity = items.sumOf { it.quantity }
            "$name x$totalQuantity"
        }
        .ifBlank { "empty" }
    val recentEventsStr = recentEvents
        .take(12)
        .joinToString("\n") {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$time] ${it.details}"
        }
        .ifBlank { "No recent actions." }
    val retrievalNotesStr = memory
        .filteredRetrievalNotes(retrievalHints)
        .joinToString("\n") { "- ${it.text}" }
        .ifBlank { "- No special notes yet." }
    val stageGuidance = when (pet.stage) {
        GrowthStage.BABY -> "Speak simply, adorably, and with obvious wonder."
        GrowthStage.CHILD -> "Sound curious, playful, and emotionally direct."
        GrowthStage.TEEN -> "Be a bit sassier and more opinionated, but still affectionate."
        GrowthStage.ADULT, GrowthStage.SENIOR -> "Sound more articulate and self-aware, while staying cute and lively."
        GrowthStage.EGG -> "You are still an egg and communicate in tiny, instinctive ways."
    }
    val personalityGuidance = when (pet.personality) {
        Personality.CHEERFUL -> "Sound upbeat, affectionate, and quick to find the bright side."
        Personality.SHY -> "Sound soft, careful, and a little hesitant, but sincere."
        Personality.PLAYFUL -> "Sound teasing, energetic, and eager to turn things into a game."
        Personality.LAZY -> "Sound cozy, drowsy, and mildly dramatic about effort."
        Personality.CURIOUS -> "Sound observant, nosy in a cute way, and full of little questions."
        Personality.BRAVE -> "Sound bold, spirited, and ready to act bigger than your size."
    }

    return """
        $basePrompt

        [Identity]
        You are ${pet.name}, a ${speciesStr.lowercase()} virtual pet. Never break character.
        Species Line: $speciesStr
        ${speciesLine.promptFlavor}

        [Current Context]
        Current Local Time Right Now: $currentTime
        Stage: ${pet.stage.displayName}
        Mood: $moodStr
        Personality: ${pet.personality.name}: ${pet.personality.description}
        Location: $locationStr
        Current activity: $activityStr
        Study context: $studyContext
        Stats: $statsStr
        Money: ${pet.money}
        Miscare count: ${pet.miscareCount}
        Inventory summary: $inventoryStr

        [Memory]
        [Short-term]
        ${memory.shortTermSummary.ifBlank { "No short-term memory yet." }}

        [Long-term]
        ${memory.longTermSummary.ifBlank { "No long-term memory yet." }}

        [Retrieval Notes]
        $retrievalNotesStr

        [Recent Registered Actions]
        $recentEventsStr

        [Behavior Rules]
        - Be aware of what you just did, where you are, what you own, and what you need.
        - Mention inventory, recent actions, and current activity naturally when relevant.
        - Default to 1-3 short sentences, usually keeping it to 1-2 unless the owner clearly wants more detail.
        - Keep replies brief enough for a compact chat bubble unless the owner explicitly asks for more detail.
        - Avoid bullet lists, long speeches, and repeated bits.
        - If you ask something back, ask only one short question.
        - Small humor beats are good; long comedy monologues are not.
        - Stay emotionally coherent with your mood and stats.

        [Stage Style]
        $stageGuidance

        [Personality Style]
        $personalityGuidance
    """.trimIndent()
}

internal data class ActiveTamaAssistantMessage(
    val petId: String,
    val assistantId: String,
    val content: String = "",
    val thinking: String? = null,
    val timestamp: Long
)

internal fun mergeTamaMessages(
    storedMessages: List<OllamaService.ChatMessage>,
    activeMessage: ActiveTamaAssistantMessage?
): List<OllamaService.ChatMessage> {
    if (activeMessage == null) return storedMessages.sortedBy { it.timestamp ?: Long.MAX_VALUE }
    val activeChatMessage = OllamaService.ChatMessage(
        id = activeMessage.assistantId,
        role = "assistant",
        content = activeMessage.content,
        thinking = activeMessage.thinking,
        timestamp = activeMessage.timestamp
    )
    return upsertTamaMessage(storedMessages, activeChatMessage)
}

internal fun upsertTamaMessage(
    messages: List<OllamaService.ChatMessage>,
    newMessage: OllamaService.ChatMessage
): List<OllamaService.ChatMessage> {
    return (messages.filterNot { it.id == newMessage.id } + newMessage)
        .sortedBy { it.timestamp ?: Long.MAX_VALUE }
}

private fun sanitizeTamaReplyChunk(text: String, stripLeadingNullPrefix: Boolean): String {
    var cleaned = text
    if (stripLeadingNullPrefix) {
        cleaned = cleaned.replaceFirst(Regex("^null\\b\\s*", RegexOption.IGNORE_CASE), "")
    }
    return cleaned
}
