package com.example.llamadroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.LlamaChatRequest
import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatResponse
import com.example.llamadroid.data.api.LlamaStreamOptions
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.hasEmbeddedAudioTranscript
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.mergeUserTextWithAudioTranscript
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import com.example.llamadroid.onnx.stripTextForTts
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

internal fun isNativeChatLoopbackHost(host: String): Boolean {
    val normalized = normalizeNativeChatServerHost(host)
    return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized == "[::1]"
}

internal fun nativeChatLocalHostForServer(host: String): String =
    if (normalizeNativeChatServerHost(host).contains(":")) "::1" else "127.0.0.1"

internal fun normalizeNativeChatServerHost(host: String): String {
    val trimmed = host.trim()
    return runCatching {
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            URI(trimmed).host.orEmpty()
        } else {
            trimmed.trim('[', ']')
        }
    }.getOrDefault(trimmed).lowercase()
}

private val NativeChatCitationLinkPattern = Regex(
    """\[((?:\\.|[^\]\n])+)]\(\s*<?((?:https?://[^\s)>]+)|(?:kb://chunk/\d+))>?(?:\s+"[^"]*")?\s*\)""",
    RegexOption.IGNORE_CASE
)

internal data class NativeChatSourceCitation(
    val label: String,
    val url: String,
    val markdown: String
)

internal fun extractNativeChatSourceCitations(toolOutput: String): List<NativeChatSourceCitation> {
    return toolOutput
        .lineSequence()
        .filter { line ->
            line.contains("citation", ignoreCase = true) ||
                line.trimStart().matches(Regex("""^\d+\.\s+\[.*"""))
        }
        .flatMap { line ->
            NativeChatCitationLinkPattern.findAll(line).map { match ->
                val label = unescapeNativeChatMarkdownLabel(match.groupValues[1])
                val url = match.groupValues[2]
                NativeChatSourceCitation(
                    label = label,
                    url = url,
                    markdown = "[${escapeNativeChatMarkdownLabel(label)}]($url)"
                )
            }
        }
        .distinctBy { it.url }
        .toList()
}

internal fun applyNativeChatSourceCitationFallback(
    content: String,
    citations: List<NativeChatSourceCitation>
): String {
    val uniqueCitations = citations.distinctBy { it.url }
    if (uniqueCitations.isEmpty() || content.isBlank()) return content

    var updated = Regex("""\[(\d+)](?!\()""").replace(content) { match ->
        val sourceIndex = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@replace match.value
        val citation = uniqueCitations.getOrNull(sourceIndex) ?: return@replace match.value
        "[${match.groupValues[1]}](${citation.url})"
    }

    uniqueCitations.forEach { citation ->
        val label = citation.label.trim()
        if (label.isBlank()) return@forEach
        updated = Regex("""\[${Regex.escape(label)}](?!\()""").replace(updated) { citation.markdown }
    }

    val hasSourceLink = uniqueCitations.any { citation -> updated.contains(citation.url) }
    if (!hasSourceLink) {
        updated = buildString {
            append(updated.trimEnd())
            appendLine()
            appendLine()
            appendLine("Sources:")
            uniqueCitations.take(8).forEachIndexed { index, citation ->
                appendLine("${index + 1}. ${citation.markdown}")
            }
        }.trimEnd()
    }

    return updated
}

private fun unescapeNativeChatMarkdownLabel(label: String): String {
    val result = StringBuilder()
    var escaped = false
    for (char in label) {
        if (escaped) {
            result.append(char)
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else {
            result.append(char)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}

private fun escapeNativeChatMarkdownLabel(label: String): String =
    label
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")

internal fun nativeChatToolAwarenessMessages(
    toolConfig: NativeChatToolConfig
): List<OllamaService.ChatMessage> = buildList {
    if (!toolConfig.toolsEnabled) return@buildList
    val currentYear = java.time.LocalDate.now().year

    if (toolConfig.noteToolsEnabled || toolConfig.todoToolsEnabled) {
        val noteGuidance = if (toolConfig.noteToolsEnabled) {
            "When the user asks you to write, save, create, update, or improve a note, call the appropriate note tool in this turn instead of only saying that you will do it. Use create_note to save durable findings and long research notes with source citations. Use list_notes to discover whitelisted note IDs, then read_note to inspect exact note content before editing. Do not ask the user to provide note IDs when list_notes/read_note can find them. Use update_note or replace_note_text to revise notes later, and read_note to recover previous research instead of forgetting it."
        } else {
            "Use list_notes to discover whitelisted todo-list note IDs, then read_note to inspect exact todo item indexes before editing. Do not ask the user to provide note IDs or todo indexes when list_notes/read_note can find them."
        }
        val todoGuidance = if (toolConfig.todoToolsEnabled) {
            " For todo lists, use the todo-list tools for creating lists, checking, unchecking, adding, editing, or removing items."
        } else {
            ""
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Native chat note tools are available. $noteGuidance$todoGuidance Existing notes outside the whitelist are intentionally invisible."
            )
        )
    }

    if (toolConfig.imageGenerationEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "When using generate_image, first improve the user's image idea into a stronger prompt with clear subject, composition, style, lighting, color, medium, and constraints. If generated-image review is enabled and an attached result image appears, inspect it before the final answer; regenerate with a better optimized prompt when it misses the request, otherwise answer using the available result."
            )
        )
    }

    if (toolConfig.webSearchEnabled || toolConfig.fetchUrlEnabled) {
        val webGuidance = buildList {
            if (toolConfig.webSearchEnabled) {
                add("Use web_search for broad discovery")
            }
            add("use search_page to inspect links and snippets inside a specific page")
            if (toolConfig.fetchUrlEnabled) {
                add("use fetch_url to read the full content of a chosen URL")
            }
        }.joinToString(", ").replaceFirstChar { it.uppercase() }
        val workflowGuidance = when {
            toolConfig.webSearchEnabled && toolConfig.fetchUrlEnabled ->
                " For tasks like latest commits, releases, issues, changelogs, or docs on a project site, first find the official page, then use search_page with a navigation query such as commits or releases, then fetch_url the returned URL before summarizing."
            toolConfig.webSearchEnabled ->
                " For tasks like latest commits, releases, issues, changelogs, or docs on a project site, first find the official page, then use search_page with a navigation query such as commits or releases before summarizing."
            else ->
                " Use the URL the user provides as the starting point; search_page can inspect same-page matches and links, and fetch_url can read the chosen URL."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Web navigation tools are available. $webGuidance.$workflowGuidance"
            )
        )
    }

    if (toolConfig.deepResearchEnabled) {
        val selectedKbImportGuidance = if (toolConfig.deepResearchImportIntoSelectedKbEnabled && toolConfig.selectedKnowledgeBaseIds.any { it > 0L }) {
            " If the user explicitly asks to find/import new resources for the currently selected knowledge base, call deep_research with target_knowledge_base_id set to one of the selected KB ids; this permission is enabled for this chat."
        } else {
            " Do not import Deep Research sources into an already selected knowledge base; create a new visible KB unless the selected-KB import permission is enabled."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Deep Research is available. Use deep_research when the user asks for broad, multi-source research that should create a normal visible knowledge base. Refine the query before calling it, include a short title/focus when useful, and set source_limit only when the user asks for a custom maximum import count. Treat source_limit as an upper bound, not as a required number of sources. The tool downloads readable webpages and PDFs, extracts their text, imports those sources into the KB, vectorizes them, and selects the KB for this chat; it is not a note-writing tool.$selectedKbImportGuidance The tool may run for a long time and returns only after the knowledge base has been created or updated, imported, vectorized, and selected for this chat. Do not send a final answer saying research will start; wait for the tool result, then answer using kb_search/kb_read_source with exact KB citation links when needed."
            )
        )
    }

    if (toolConfig.calendarToolsEnabled || toolConfig.alarmToolsEnabled) {
        val organizerGuidance = buildList {
            if (toolConfig.calendarToolsEnabled) {
                add("Use list_calendar_events/read_calendar_event to discover event IDs before editing or deleting")
            }
            if (toolConfig.alarmToolsEnabled) {
                add("use list_alarms/read_alarm to discover alarm IDs before editing or deleting")
            }
        }.joinToString("; ").replaceFirstChar { it.uppercase() }
        val idGuidance = when {
            toolConfig.calendarToolsEnabled && toolConfig.alarmToolsEnabled ->
                "Do not ask the user for event or alarm IDs when the tools can find them. Calendar events can exist without alarms. Create or update phone alarms only when the user asks for an alert/reminder or when it is clearly useful for scheduling."
            toolConfig.calendarToolsEnabled ->
                "Do not ask the user for event IDs when the tools can find them. Calendar events can exist without alarms."
            else ->
                "Do not ask the user for alarm IDs when the tools can find them. Create or update phone alarms only when the user asks for an alert/reminder or when it is clearly useful for scheduling."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Organizer tools are available. $organizerGuidance; $idGuidance For enabled organizer tool times, use the device-local timezone; if the user gives a month/day without a year, assume the current year ($currentYear) unless they explicitly say another year."
            )
        )
    }

    if (toolConfig.knowledgeBaseEnabled) {
        val guidance = if (toolConfig.chatDocumentKnowledgeBaseId?.let { it > 0L } == true) {
            "This chat has uploaded document vectors available through the knowledge-base tools. When the user mentions the document, file, attachment, PDF, or says to use what they sent, use the retrieved chat-document context or call kb_search before answering. Do not say no document was attached when the chat document tools are enabled; use the returned chunks and cite them with the exact Markdown citation links, for example [AL.pdf chunk 9](kb://chunk/123), not bare labels like [AL.pdf chunk 9]."
        } else {
            "Knowledge-base tools are available. Use kb_search before answering questions that ask for local knowledge-base content, and cite KB-derived claims with the returned exact Markdown citation links, not bare citation labels."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = guidance
            )
        )
    }
}

class LlamaClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Active generation state
    private var job: Job? = null
    private var notificationTaskId: Int? = null
    
    // Repository to save messages
    private lateinit var repository: LlamaRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var ollamaService: OllamaService
    private lateinit var database: AppDatabase
    private val llamaServerChatService = LlamaServerChatService()
    private lateinit var liteRtLmChatService: LiteRtLmChatService
    private lateinit var liteRtLmWorkerClient: LiteRtLmWorkerClient
    private lateinit var nativeChatToolRuntime: NativeChatToolRuntime
    private val whisperBindingIntent by lazy { Intent(applicationContext, WhisperService::class.java) }
    private val powerLockGuard = Any()
    private var powerLocksHeld = false
    
    override fun onCreate() {
        super.onCreate()
        // Initialize repository (using manual instantiation for now, assuming DI isn't fully set up)
        database = AppDatabase.getDatabase(applicationContext)
        repository = LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
        settingsRepo = SettingsRepository(applicationContext)
        ollamaService = OllamaService(applicationContext)
        liteRtLmChatService = LiteRtLmChatService(applicationContext)
        liteRtLmWorkerClient = LiteRtLmWorkerClient(applicationContext)
        nativeChatToolRuntime = NativeChatToolRuntime(
            context = applicationContext,
            noteDao = database.noteDao(),
            organizerDao = database.organizerDao(),
            alarmScheduler = { alarm -> OrganizerAlarmScheduler.scheduleAlarm(applicationContext, alarm) },
            alarmCanceler = { alarmId -> OrganizerAlarmScheduler.cancelAlarm(applicationContext, alarmId) },
            organizerChanged = { OrganizerCalendarWidgetProvider.refreshAll(applicationContext) },
            notesChanged = { NoteDisplayWidgetProvider.refreshAll(applicationContext) },
            knowledgeBaseRepository = com.example.llamadroid.data.repository.KnowledgeBaseRepository(applicationContext, database),
            imageGenerator = NativeChatOnnxImageGenerator(applicationContext, database),
            pdfTextExtractor = { pdfBytes, maxChars ->
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                extractNativePdfTextFromBytes(pdfBytes, maxChars)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                
                if (chatId != -1L) {
                    startGeneration(chatId, serverId, userMessage, imagePath, audioPath)
                } else {
                    DebugLog.log("LlamaClientService: Missing params for generation")
                    Companion.updateState(GenerationState.Error("Missing parameters for generation", chatId))
                }
            }
            ACTION_STOP -> {
                stopGeneration()
            }
        }
        return START_NOT_STICKY
    }

    private fun startGeneration(
        chatId: Long,
        serverId: Long,
        userMessage: String?,
        imagePath: String?,
        audioPath: String?
    ) {
        if (job?.isActive == true) {
            DebugLog.log("LlamaClientService: Generation already in progress")
            return
        }

        // Start Foreground
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_CLIENT,
            "Llama Chat"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        
        acquirePowerLocks()
        warnIfBatteryOptimizationMayThrottle(chatId)

        resetThinking()
        Companion.updateState(GenerationState.Generating(chatId = chatId, content = ""))

        job = serviceScope.launch {
            var assistantMsgId: Long = -1L
            val progress = StreamingProgress()
            try {
                val server = resolveServerForGeneration(serverId)
                    ?: throw Exception("Server with ID $serverId not found")
                if (server.isLiteRtEngine()) {
                    DebugLog.log("LlamaClientService: Using local LiteRT engine for Chat ID $chatId")
                } else {
                    DebugLog.log("LlamaClientService: Connecting to ${server.baseUrl()} for Chat ID $chatId")
                }

                val chat = repository.getChat(chatId) ?: throw Exception("Chat with ID $chatId not found")
                ensureLocalLlamaServerReadyIfNeeded(chatId = chatId, server = server)

                val preparedUserTurn = prepareUserTurnForServer(
                    chatId = chatId,
                    server = server,
                    userMessage = userMessage,
                    imagePath = imagePath,
                    audioPath = audioPath
                )

                if (preparedUserTurn.shouldPersist() && !preparedUserTurn.alreadyPersisted) {
                    repository.addMessage(
                        chatId = chatId,
                        role = "user",
                        content = preparedUserTurn.content,
                        imagePath = preparedUserTurn.imagePath,
                        audioPath = preparedUserTurn.audioPath
                    )
                }

                val history = prepareHistoryForServer(chatId, server)
                if (history.isEmpty()) throw Exception("Chat history is empty")

                val isContinuation = !preparedUserTurn.shouldPersist() && history.last().role == "assistant"
                val assistantPreparation = prepareAssistantMessage(chatId, history, isContinuation)
                assistantMsgId = assistantPreparation.assistantMsgId
                progress.content = assistantPreparation.content
                progress.thinking = assistantPreparation.thinking

                val params = parseChatParams(chat.apiParams)
                val paramEnableThinking = (params["enable_thinking"] as? Boolean) ?: true
                val chatToolConfig = NativeChatToolConfig.fromParams(params)
                val serverToolDefaults = NativeChatToolConfig.fromApiParams(server.defaultApiParams)
                val toolConfig = chatToolConfig.effectiveWithServerDefaults(serverToolDefaults)
                val useNativeTools = !isContinuation && toolConfig.hasEnabledTools()

                if (useNativeTools) {
                    streamNativeToolResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        thinkingEnabled = paramEnableThinking,
                        params = params,
                        toolConfig = toolConfig,
                        progress = progress
                    )
                } else if (server.isOllamaEngine()) {
                    streamOllamaResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        thinkingEnabled = !isContinuation && paramEnableThinking,
                        progress = progress
                    )
                } else if (server.isLiteRtEngine()) {
                    streamLiteRtLmResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        params = params,
                        progress = progress
                    )
                } else {
                    streamLlamaServerResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        params = params,
                        progress = progress
                    )
                }

                val finalElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
                val finalElapsed = finalElapsedMs / 1000.0
                val finalTps = progress.reportedTokensPerSecond
                    ?: if (finalElapsed > 0.0) progress.tokenCount / finalElapsed else 0.0
                if (progress.completionTokens == 0) {
                    progress.completionTokens = progress.tokenCount
                }

                repository.updateMessageTruncatedStatus(assistantMsgId, progress.isTruncated)
                repository.updateMessageThinkingAndContent(
                    assistantMsgId,
                    progress.content,
                    progress.thinking.takeIf { it.isNotBlank() },
                    promptTokens = progress.promptTokens,
                    completionTokens = progress.completionTokens,
                    tps = finalTps,
                    generationTimeMs = finalElapsedMs
                )
                persistGeneratedImageMessages(chatId, progress)
                generateAssistantAudioIfEnabled(
                    messageId = assistantMsgId,
                    content = progress.content,
                    thinking = progress.thinking,
                    toolConfig = toolConfig
                )

                Companion.updateState(GenerationState.Completed(
                    chatId = chatId,
                    content = progress.content,
                    thinking = progress.thinking.takeIf { it.isNotBlank() },
                    completionTokens = progress.completionTokens,
                    promptTokens = progress.promptTokens,
                    tokensPerSecond = finalTps
                ))
            } catch (e: Exception) {
                if (e is CancellationException) {
                     DebugLog.log("LlamaClientService: Generation cancelled")
                     if (assistantMsgId != -1L) {
                         val finalElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
                         val finalElapsed = finalElapsedMs / 1000.0
                         val finalTps = progress.reportedTokensPerSecond
                             ?: if (finalElapsed > 0.0) progress.tokenCount / finalElapsed else 0.0
                         repository.updateMessageThinkingAndContent(
                             assistantMsgId,
                             progress.content,
                             progress.thinking.takeIf { it.isNotBlank() },
                             promptTokens = progress.promptTokens,
                             completionTokens = if (progress.completionTokens == 0) progress.tokenCount else progress.completionTokens,
                             tps = finalTps,
                             generationTimeMs = finalElapsedMs
                         )
                         persistGeneratedImageMessages(chatId, progress)
                     }
                } else {
                    DebugLog.log("LlamaClientService: Error ${e.message}")
                    Companion.updateState(GenerationState.Error(e.message ?: "Unknown error", chatId))
                }
            } finally {
                releasePowerLocks()
                notificationTaskId?.let { taskId ->
                    UnifiedNotificationManager.dismissTask(taskId)
                }
                notificationTaskId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                job = null
            }
        }
    }

    private suspend fun ensureLocalLlamaServerReadyIfNeeded(
        chatId: Long,
        server: LlamaServerEntity
    ) {
        if (!server.isLlamaServerEngine() || !isNativeChatLoopbackHost(server.host)) return
        val baseUrl = server.baseUrl()
        if (llamaServerChatService.checkConnection(baseUrl)) return

        if (LlamaService.state.value is ServerState.Starting || LlamaService.state.value is ServerState.Loading) {
            updateLocalLlamaServerStartupStatus(
                chatId,
                getString(R.string.llama_client_status_waiting_local_server)
            )
            if (waitForLocalLlamaServerReady(baseUrl = baseUrl)) {
                return
            }
        }

        val modelPath = settingsRepo.selectedModelPath.value
            ?: throw IllegalStateException(getString(R.string.llama_client_error_no_selected_model))
        val startIntent = Intent(applicationContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, nativeChatLocalHostForServer(server.host))
            putExtra(LlamaService.EXTRA_PORT, server.port)
            if (settingsRepo.speculativeEnabled.value) {
                putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
            }
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        }

        updateLocalLlamaServerStartupStatus(
            chatId,
            getString(R.string.llama_client_status_starting_local_server)
        )
        DebugLog.log("LlamaClientService: Auto-starting local llama-server at $baseUrl")
        applicationContext.startForegroundService(startIntent)

        if (!waitForLocalLlamaServerReady(baseUrl = baseUrl)) {
            throw IllegalStateException(getString(R.string.llama_client_error_local_server_timeout))
        }
    }

    private suspend fun waitForLocalLlamaServerReady(baseUrl: String): Boolean {
        repeat(LOCAL_SERVER_READY_ATTEMPTS) { attempt ->
            delay(LOCAL_SERVER_READY_DELAY_MS)
            currentCoroutineContext().ensureActive()
            if (llamaServerChatService.checkConnection(baseUrl)) {
                DebugLog.log("LlamaClientService: local llama-server ready after attempt=${attempt + 1}")
                return true
            }
        }
        return false
    }

    private fun updateLocalLlamaServerStartupStatus(chatId: Long, status: String) {
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = "",
                statusText = status
            )
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, 0.05f, status)
        }
    }

    private suspend fun resolveServerForGeneration(serverId: Long): LlamaServerEntity? {
        return if (serverId == -1L) {
            database.llamaServerDao().getLastUsedServer()
        } else {
            repository.getServer(serverId)
        }
    }

    private suspend fun prepareUserTurnForServer(
        chatId: Long,
        server: LlamaServerEntity,
        userMessage: String?,
        imagePath: String?,
        audioPath: String?
    ): PreparedUserTurn {
        val persistedImagePath = imagePath?.takeIf { it.isNotBlank() }
        val originalAudioPath = audioPath?.takeIf { it.isNotBlank() }
        if (originalAudioPath == null) {
            return PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = null
            )
        }

        return if (server.supportsDirectAudioInput()) {
            val preparedAudioPath = prepareAudioPathForNativeLlama(applicationContext, originalAudioPath).getOrThrow()
            PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = preparedAudioPath
            )
        } else {
            val pendingMessageId = repository.addMessage(
                chatId = chatId,
                role = "user",
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = originalAudioPath
            )
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    isTranscribingAudio = true,
                    transcribingMessageId = pendingMessageId
                )
            )
            val transcript = try {
                transcribeAudioAttachment(server, originalAudioPath).getOrThrow().text
            } catch (e: Exception) {
                repository.updateMessageErrorStatus(pendingMessageId, true)
                throw e
            }
            val mergedContent = mergeUserTextWithAudioTranscript(userMessage.orEmpty(), transcript)
            repository.updateMessageContentAndError(
                id = pendingMessageId,
                content = mergedContent,
                isError = false
            )
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    isTranscribingAudio = false
                )
            )
            PreparedUserTurn(
                content = mergedContent,
                imagePath = persistedImagePath,
                audioPath = originalAudioPath,
                alreadyPersisted = true
            )
        }
    }

    private suspend fun prepareHistoryForServer(
        chatId: Long,
        server: LlamaServerEntity
    ): List<LlamaMessageEntity> {
        val messages = repository.getMessages(chatId).first().filterNot { it.isError }
        return messages.map { message ->
            when {
                server.supportsDirectAudioInput() -> normalizeAudioAttachmentForDirectInput(message)
                else -> ensureHistoryTranscript(message, server)
            }
        }
    }

    private suspend fun normalizeAudioAttachmentForDirectInput(
        message: LlamaMessageEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message
        if (hasEmbeddedAudioTranscript(message.content)) return message

        val normalizedAudioPath = prepareAudioPathForNativeLlama(applicationContext, originalAudioPath).getOrThrow()
        if (normalizedAudioPath != originalAudioPath) {
            repository.updateMessageAudioPath(message.id, normalizedAudioPath)
            return message.copy(audioPath = normalizedAudioPath)
        }
        return message
    }

    private suspend fun ensureHistoryTranscript(
        message: LlamaMessageEntity,
        server: LlamaServerEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message
        if (message.role != "user" || hasEmbeddedAudioTranscript(message.content)) {
            return message
        }

        val transcript = transcribeAudioAttachment(server, originalAudioPath).getOrThrow().text
        val mergedContent = mergeUserTextWithAudioTranscript(message.content, transcript)
        repository.updateMessage(message.id, mergedContent)
        return message.copy(content = mergedContent)
    }

    private suspend fun generateAssistantAudioIfEnabled(
        messageId: Long,
        content: String,
        thinking: String,
        toolConfig: NativeChatToolConfig
    ) {
        if (!toolConfig.assistantTtsEnabled || messageId <= 0L) return
        val ttsText = stripTextForTts(content, thinking).takeIf { it.isNotBlank() } ?: return
        try {
            val model = database.modelDao()
                .getModelsByTypesSync(listOf(ModelType.ONNX_TTS))
                .firstOrNull()
                ?: run {
                    DebugLog.log("LlamaClientService: assistant TTS skipped, no ONNX_TTS model installed")
                    return
                }
            val result = SupertonicTtsPipeline(applicationContext).generate(
                OnnxTtsRequest(
                    modelPath = model.path,
                    modelName = model.filename,
                    text = ttsText,
                    language = toolConfig.normalizedAssistantTtsLanguage().ifBlank { SUPERTONIC_DEFAULT_LANGUAGE },
                    voiceName = toolConfig.assistantTtsVoiceName,
                    sourceName = "llama_message_$messageId"
                )
            ) { _, status ->
                DebugLog.log("LlamaClientService: assistant TTS $status")
            }
            repository.updateMessageAudioPath(messageId, result.playableFile.absolutePath)
        } catch (error: Exception) {
            DebugLog.log("LlamaClientService: assistant TTS failed: ${error.message}")
        }
    }

    private suspend fun prepareAssistantMessage(
        chatId: Long,
        history: List<LlamaMessageEntity>,
        isContinuation: Boolean
    ): AssistantMessagePreparation {
        if (isContinuation && history.isNotEmpty() && history.last().role == "assistant") {
            val lastAssistantMsg = history.last()
            val existingThinking = lastAssistantMsg.thinking ?: ""
            val rawContent = lastAssistantMsg.content
            val lastWsIndex = rawContent.indexOfLast { it == ' ' || it == '\n' || it == '\t' }
            val existingContent = if (lastWsIndex > 0 && rawContent.isNotEmpty() && !rawContent.last().isWhitespace()) {
                rawContent.substring(0, lastWsIndex + 1)
            } else {
                rawContent
            }

            if (existingContent != rawContent) {
                repository.updateMessage(lastAssistantMsg.id, existingContent)
            }

            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = existingContent,
                    thinking = existingThinking.takeIf { it.isNotBlank() }
                )
            )

            DebugLog.log("LlamaClientService: Continuing from existing assistant message (${existingContent.length} chars)")
            return AssistantMessagePreparation(
                history = history.dropLast(1) + lastAssistantMsg.copy(content = existingContent),
                assistantMsgId = lastAssistantMsg.id,
                content = existingContent,
                thinking = existingThinking
            )
        }

        return AssistantMessagePreparation(
            history = history,
            assistantMsgId = repository.addMessage(chatId, "assistant", ""),
            content = "",
            thinking = ""
        )
    }

    private fun parseChatParams(apiParams: String?): Map<String, Any> {
        if (apiParams.isNullOrBlank()) return emptyMap()
        return try {
            Gson().fromJson(apiParams, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun streamLlamaServerResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        isContinuation: Boolean,
        params: Map<String, Any>,
        progress: StreamingProgress
    ) {
        val baseUrl = server.baseUrl()
        val apiMessages = mutableListOf<LlamaChatMessage>()
        if (!chat.systemPrompt.isNullOrBlank()) {
            apiMessages += LlamaChatMessage("system", chat.systemPrompt)
        }
        apiMessages += history.map { LlamaChatMessage(it.role, it.toNativeLlamaContent()) }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .build()
                chain.proceed(request)
            }
            .build()

        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlamaApi::class.java)

        val requestModel = if (server.isLlamaSwapEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(getString(R.string.llama_swap_model_required))
        } else {
            server.modelName?.takeIf { it.isNotBlank() } ?: "default"
        }

        val request = LlamaChatRequest(
            model = requestModel,
            messages = apiMessages,
            stream = true,
            streamOptions = LlamaStreamOptions(includeUsage = true),
            temperature = (params["temperature"] as? Number)?.toFloat(),
            top_p = (params["top_p"] as? Number)?.toFloat(),
            top_k = (params["top_k"] as? Number)?.toInt(),
            min_p = (params["min_p"] as? Number)?.toFloat(),
            seed = (params["seed"] as? Number)?.toInt(),
            repeat_penalty = (params["repeat_penalty"] as? Number)?.toFloat(),
            frequency_penalty = (params["frequency_penalty"] as? Number)?.toFloat(),
            presence_penalty = (params["presence_penalty"] as? Number)?.toFloat(),
            chat_template_kwargs = if (isContinuation || (params["enable_thinking"] as? Boolean) == false) {
                mapOf("enable_thinking" to false)
            } else {
                mapOf("enable_thinking" to true)
            }
        )

        DebugLog.log("LlamaClientService: Sending request to $baseUrl")
        showDebugToast("Connecting to $baseUrl...")

        val call = try {
            api.chatCompletion(request)
        } catch (e: Exception) {
            showDebugToast("Connection Failed: ${e.message}")
            throw Exception("Failed to connect to $baseUrl: ${e.message}")
        }

        val response = call.execute()
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: ""
            showDebugToast("Server Error ${response.code()}")
            throw Exception("Server Error ${response.code()}: $errorBody")
        }

        showDebugToast("Connected! receiving stream...")

        val source = response.body()?.source() ?: throw Exception("Empty response body from server")
        val reader = BufferedReader(InputStreamReader(source.inputStream()))
        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        var firstDeltaReceived = false
        val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (line == "data: [DONE]") break

            if (!line.startsWith("data: ")) continue
            val json = line.substring(6)
            try {
                val chunk = Gson().fromJson(json, LlamaChatResponse::class.java)
                chunk.usage?.let { usage ->
                    progress.promptTokens = usage.promptTokens
                    progress.completionTokens = usage.completionTokens
                }
                chunk.choices.firstOrNull()?.finish_reason?.let { finishReason ->
                    val normalized = finishReason.lowercase()
                    if (normalized == "length" || normalized == "max_tokens") {
                        progress.isTruncated = true
                        DebugLog.log("LlamaClientService: Truncation detected via finish_reason='$normalized'")
                    }
                }

                val deltaObj = chunk.choices.firstOrNull()?.delta
                val delta = deltaObj?.content ?: ""
                val dedicatedReasoning = deltaObj?.reasoningContent ?: deltaObj?.thinking ?: ""
                if (delta.isEmpty() && dedicatedReasoning.isEmpty()) continue

                if (delta.isNotEmpty() && !firstDeltaReceived && needsSpaceCheck && !delta.first().isWhitespace()) {
                    rawSequence += " "
                }
                firstDeltaReceived = true
                rawSequence += delta

                if (!isContinuation) {
                    val extracted = extractThinking(rawSequence, dedicatedReasoning)
                    progress.content = extracted.first
                    progress.thinking = extracted.second
                } else {
                    progress.content = rawSequence
                }

                progress.tokenCount++
                updateStreamingProgress(
                    chatId = chatId,
                    taskId = taskId,
                    assistantMsgId = assistantMsgId,
                    progress = progress,
                    lastUpdateMs = lastUpdate
                ).also { lastUpdate = it }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun streamOllamaResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        isContinuation: Boolean,
        thinkingEnabled: Boolean,
        progress: StreamingProgress
    ) {
        val modelName = server.modelName?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(getString(R.string.llama_ollama_model_required))

        syncOllamaService(server)

        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        var firstDeltaReceived = false
        val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false

        val result = ollamaService.chatWithToolsStreaming(
            model = modelName,
            messages = buildOllamaMessages(chat, history, server),
            thinkingEnabled = thinkingEnabled,
            numCtxOverride = chat.contextSize.takeIf { it > 0 },
            onChunk = { delta, thinkingDelta ->
                val contentDelta = delta.orEmpty()
                val reasoningDelta = thinkingDelta.orEmpty()
                if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
                    return@chatWithToolsStreaming
                }

                if (contentDelta.isNotEmpty()) {
                    if (!firstDeltaReceived && needsSpaceCheck && !contentDelta.first().isWhitespace()) {
                        rawSequence += " "
                    }
                    firstDeltaReceived = true
                    rawSequence += contentDelta
                    progress.content = rawSequence
                }
                if (reasoningDelta.isNotEmpty()) {
                    progress.thinking += reasoningDelta
                }

                progress.tokenCount++
                progress.lastTokenAtMs = System.currentTimeMillis()
                runBlocking {
                    lastUpdate = updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    )
                }
            }
        ).getOrElse { throw it }

        progress.promptTokens = result.usage?.promptTokens ?: progress.promptTokens
        result.usage?.completionTokens?.let { completionTokens ->
            progress.completionTokens = completionTokens
            progress.tokenCount = completionTokens
        }
        val usageTps = result.usage?.completionTokens
            ?.takeIf { it > 0 }
            ?.let { completionTokens ->
                result.usage?.evalDurationNs
                    ?.takeIf { it > 0L }
                    ?.let { durationNs -> completionTokens / (durationNs / 1_000_000_000.0) }
            }
        progress.reportedTokensPerSecond = usageTps ?: progress.reportedTokensPerSecond

        if (progress.content.isBlank() && result.message.content.isNotBlank()) {
            progress.content = result.message.content
        }
        if (progress.thinking.isBlank() && !result.message.thinking.isNullOrBlank()) {
            progress.thinking = result.message.thinking.orEmpty()
        }
    }

    private suspend fun streamLiteRtLmResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        isContinuation: Boolean,
        params: Map<String, Any>,
        progress: StreamingProgress
    ) {
        val modelId = server.liteRtModelId
            ?: throw IllegalStateException(getString(R.string.litert_error_model_missing))
        val model = database.liteRtModelDao().getById(modelId)
            ?: throw IllegalStateException(getString(R.string.litert_error_model_missing))

        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false

        val backendMode = normalizeLiteRtBackend(server.liteRtBackend)
        val request = LiteRtLmChatRequest(
            model = model,
            chat = chat,
            history = history,
            backendMode = backendMode,
            params = liteRtGalleryParams(params)
        )
        val baseRawSequence = rawSequence
        val baseContent = progress.content
        val baseThinking = progress.thinking

        suspend fun publishStatus(status: String) {
            progress.statusText = status
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = progress.content,
                    thinking = progress.thinking.takeIf { it.isNotBlank() },
                    tokenCount = progress.tokenCount,
                    tokensPerSecond = progress.reportedTokensPerSecond ?: 0.0,
                    statusText = status,
                    toolEvents = progress.toolEvents.toList()
                )
            )
            UnifiedNotificationManager.updateProgress(taskId, 0.12f, status)
        }

        suspend fun handleChunk(delta: String) {
            currentCoroutineContext().ensureActive()
            if (delta.isEmpty()) return
            rawSequence += delta
            if (!isContinuation) {
                val extracted = extractThinking(rawSequence)
                progress.content = repairLiteRtCompactTextForDisplay(extracted.first)
                progress.thinking = repairLiteRtCompactTextForDisplay(extracted.second)
            } else {
                progress.content = repairLiteRtCompactTextForDisplay(rawSequence)
            }
            progress.tokenCount = estimateLiteRtCompletionTokens(
                "${progress.content}\n${progress.thinking}"
            ).coerceAtLeast(progress.tokenCount + 1)
            progress.lastTokenAtMs = System.currentTimeMillis()
            lastUpdate = updateStreamingProgress(
                chatId = chatId,
                taskId = taskId,
                assistantMsgId = assistantMsgId,
                progress = progress,
                lastUpdateMs = lastUpdate
            )
        }

        suspend fun handleThinkingChunk(delta: String) {
            currentCoroutineContext().ensureActive()
            if (delta.isEmpty() || isContinuation) return
            val extracted = extractThinking(rawSequence, delta)
            progress.content = repairLiteRtCompactTextForDisplay(extracted.first)
            progress.thinking = repairLiteRtCompactTextForDisplay(extracted.second)
            progress.tokenCount = estimateLiteRtCompletionTokens(
                "${progress.content}\n${progress.thinking}"
            ).coerceAtLeast(progress.tokenCount + 1)
            progress.lastTokenAtMs = System.currentTimeMillis()
            lastUpdate = updateStreamingProgress(
                chatId = chatId,
                taskId = taskId,
                assistantMsgId = assistantMsgId,
                progress = progress,
                lastUpdateMs = lastUpdate
            )
        }

        fun resetAfterFailedAcceleratorAttempt() {
            rawSequence = baseRawSequence
            progress.content = baseContent
            progress.thinking = baseThinking
            progress.tokenCount = 0
            progress.promptTokens = 0
            progress.completionTokens = 0
            progress.reportedTokensPerSecond = null
            progress.lastTokenAtMs = 0L
            lastUpdate = System.currentTimeMillis()
        }

        val stats = streamLiteRtLmRequestSafely(
            request = request,
            onStatus = ::publishStatus,
            onChunk = ::handleChunk,
            onThinkingChunk = ::handleThinkingChunk,
            onAcceleratorFailureReset = ::resetAfterFailedAcceleratorAttempt
        )

        progress.promptTokens = stats.promptTokens
        progress.completionTokens = stats.completionTokens
        progress.tokenCount = stats.completionTokens.coerceAtLeast(progress.tokenCount)
        progress.reportedTokensPerSecond = stats.tokensPerSecond
    }

    private suspend fun streamNativeToolResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        thinkingEnabled: Boolean,
        params: Map<String, Any>,
        toolConfig: NativeChatToolConfig,
        progress: StreamingProgress
    ) {
        val effectiveToolConfig = nativeChatToolRuntime.configWithOrganizerPermissions(toolConfig)
        val tools = nativeChatToolRuntime.availableTools(effectiveToolConfig)
        if (tools.isEmpty()) {
            if (server.isOllamaEngine()) {
                streamOllamaResponse(
                    chatId = chatId,
                    taskId = taskId,
                    chat = chat,
                    server = server,
                    history = history,
                    assistantMsgId = assistantMsgId,
                    isContinuation = false,
                    thinkingEnabled = thinkingEnabled,
                    progress = progress
                )
            } else if (server.isLiteRtEngine()) {
                streamLiteRtLmResponse(
                    chatId = chatId,
                    taskId = taskId,
                    chat = chat,
                    server = server,
                    history = history,
                    assistantMsgId = assistantMsgId,
                    isContinuation = false,
                    params = params,
                    progress = progress
                )
            } else {
                streamLlamaServerResponse(
                    chatId = chatId,
                    taskId = taskId,
                    chat = chat,
                    server = server,
                    history = history,
                    assistantMsgId = assistantMsgId,
                    isContinuation = false,
                    params = params,
                    progress = progress
                )
            }
            return
        }

        val modelName = if (server.isOllamaEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(getString(R.string.llama_ollama_model_required))
        } else if (server.isLlamaSwapEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(getString(R.string.llama_swap_model_required))
        } else {
            server.modelName?.takeIf { it.isNotBlank() }
        }
        if (server.isOllamaEngine()) {
            syncOllamaService(server)
        }

        val sourceCitations = mutableListOf<NativeChatSourceCitation>()
        val transientSystemMessages = nativeChatToolAwarenessMessages(effectiveToolConfig).toMutableList()
        history.lastOrNull { it.role == "user" }?.content?.let { latestUserText ->
            nativeChatToolRuntime.buildAutoKnowledgeContext(latestUserText, effectiveToolConfig)?.let { kbContext ->
                sourceCitations += extractNativeChatSourceCitations(kbContext)
                transientSystemMessages += OllamaService.ChatMessage(
                    role = "system",
                    content = kbContext
                )
            }
        }
        val messages = buildOllamaMessages(chat, history, server)
            .withMergedTransientSystemMessages(transientSystemMessages)
            .toMutableList()
        val samplingParams = LlamaServerSamplingParams.fromParams(params)
        val numCtx = chat.contextSize.takeIf { it > 0 } ?: 16384

        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false
        progress.statusText = null

        suspend fun runModelCall(availableTools: List<AgentTool>): OllamaService.ChatResponse {
            progress.statusText = null
            val chunkHandler: (String?, String?) -> Unit = chunkHandler@{ delta, thinkingDelta ->
                val contentDelta = delta.orEmpty()
                val reasoningDelta = thinkingDelta.orEmpty()
                if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
                    return@chunkHandler
                }

                progress.statusText = null
                if (contentDelta.isNotEmpty()) {
                    rawSequence += contentDelta
                    if (server.isLiteRtEngine()) {
                        val extracted = extractThinking(rawSequence)
                        progress.content = extracted.first
                        progress.thinking = if (thinkingEnabled) extracted.second else ""
                    } else {
                        progress.content = rawSequence
                    }
                }
                if (reasoningDelta.isNotEmpty()) {
                    progress.thinking += reasoningDelta
                }

                progress.tokenCount++
                runBlocking {
                    lastUpdate = updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    )
                }
            }

            return if (server.isOllamaEngine()) {
                ollamaService.chatWithToolsStreaming(
                    model = modelName ?: "",
                    messages = messages,
                    tools = availableTools,
                    thinkingEnabled = thinkingEnabled,
                    numCtxOverride = chat.contextSize.takeIf { it > 0 },
                    onChunk = chunkHandler
                ).getOrElse { throw it }
            } else if (server.isLiteRtEngine()) {
                chatWithLiteRtToolsStreaming(
                    server = server,
                    chat = chat,
                    messages = messages,
                    tools = availableTools,
                    thinkingEnabled = thinkingEnabled,
                    params = params,
                    onStatus = { status ->
                        publishToolStatus(
                            chatId = chatId,
                            taskId = taskId,
                            progress = progress,
                            statusText = status
                        )
                    },
                    onChunk = chunkHandler
                )
            } else {
                llamaServerChatService.chatWithToolsStreaming(
                    baseUrl = server.baseUrl(),
                    messages = messages,
                    tools = availableTools,
                    modelLabel = modelName,
                    thinkingEnabled = thinkingEnabled,
                    numCtx = numCtx,
                    samplingParams = samplingParams,
                    onChunk = chunkHandler
                ).getOrElse { throw it }
            }
        }

        fun mergeUsage(response: OllamaService.ChatResponse) {
            val usage = response.usage ?: return
            usage.promptTokens?.let { progress.promptTokens += it }
            usage.completionTokens?.let { completionTokens ->
                progress.completionTokens += completionTokens
                progress.tokenCount = progress.completionTokens
            }
            usage.evalDurationNs
                ?.takeIf { it > 0L }
                ?.let { durationNs ->
                    usage.completionTokens
                        ?.takeIf { it > 0 }
                        ?.let { completionTokens ->
                            progress.reportedTokensPerSecond = completionTokens / (durationNs / 1_000_000_000.0)
                        }
                }
        }

        fun appendFallbackContent(response: OllamaService.ChatResponse) {
            val content = response.message.content
            if (content.isNotBlank() && !rawSequence.endsWith(content)) {
                rawSequence += content
                progress.content = rawSequence
            }
            if (progress.thinking.isBlank() && !response.message.thinking.isNullOrBlank()) {
                progress.thinking = response.message.thinking.orEmpty()
            }
        }

        repeat(effectiveToolConfig.maxToolRounds) { round ->
            currentCoroutineContext().ensureActive()
            val visibleContentBeforeModelCall = rawSequence
            val response = runModelCall(tools)
            mergeUsage(response)

            val toolCalls = normalizeToolCalls(response.toolCalls.orEmpty(), round)
            if (toolCalls.isEmpty()) {
                appendFallbackContent(response)
                applySourceCitationFallback(progress, sourceCitations)
                progress.statusText = null
                return
            }

            rawSequence = visibleContentBeforeModelCall
            progress.content = rawSequence
            lastUpdate = updateStreamingProgress(
                chatId = chatId,
                taskId = taskId,
                assistantMsgId = assistantMsgId,
                progress = progress,
                lastUpdateMs = lastUpdate
            )

            messages += response.message.copy(content = "", toolCalls = toolCalls)
            val roundReviewMessages = mutableListOf<OllamaService.ChatMessage>()
            for (toolCall in toolCalls) {
                currentCoroutineContext().ensureActive()
                val toolActivityBaseId = toolCall.id ?: "tool_${round}_${System.nanoTime()}"
                publishToolStatus(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    statusText = statusTextForToolCall(toolCall)
                )
                publishToolActivity(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    event = ToolActivityEvent(
                        id = "${toolActivityBaseId}_start",
                        toolName = toolCall.name,
                        status = statusTextForToolCall(toolCall),
                        title = toolCall.arguments["query"] ?: toolCall.arguments["url"] ?: toolCall.arguments["prompt"]
                    )
                )
                val toolResult = try {
                    nativeChatToolRuntime.executeToolCall(
                        toolCall = toolCall,
                        config = effectiveToolConfig,
                        chatId = chatId,
                        onProgress = { toolProgress ->
                            publishToolActivity(
                                chatId = chatId,
                                taskId = taskId,
                                progress = progress,
                                event = ToolActivityEvent(
                                    id = "${toolActivityBaseId}_${System.nanoTime()}",
                                    toolName = toolCall.name,
                                    status = localizedToolProgressStatus(toolCall, toolProgress),
                                    title = toolProgress.title,
                                    url = toolProgress.url,
                                    outputPreview = toolProgress.outputPreview,
                                    isComplete = toolProgress.isComplete
                                )
                            )
                        },
                        searchSummarizer = { request ->
                            summarizeNativeSearchPageWithBackend(
                                server = server,
                                modelName = modelName,
                                request = request
                            )
                        }
                    )
                        .getOrElse { error ->
                            NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                }
                publishToolActivity(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    event = ToolActivityEvent(
                        id = "${toolActivityBaseId}_done",
                        toolName = toolCall.name,
                        status = getString(R.string.llama_tool_activity_done),
                        title = toolCall.name,
                        outputPreview = toolResult.content.take(1_000),
                        isComplete = true
                    )
                )
                toolResult.generatedImagePath?.takeIf { it.isNotBlank() }?.let { imagePath ->
                    progress.generatedImagePaths += imagePath
                }
                sourceCitations += extractNativeChatSourceCitations(toolResult.content)
                messages += OllamaService.ChatMessage(
                    role = "tool",
                    content = toolResult.content,
                    toolCallId = toolCall.id
                )
                if (toolCall.name == NativeChatToolRuntime.TOOL_DEEP_RESEARCH) {
                    messages += OllamaService.ChatMessage(
                        role = "system",
                        content = "Deep Research has finished. Do not call deep_research again for the same request. Send a visible chat response now. Briefly say the research KB is ready and mention the imported source count from the tool result. If the user asked for factual conclusions, use kb_search or kb_read_source on the selected KB first and cite exact KB links."
                    )
                }
                toolResult.generatedImagePath
                    ?.takeIf { effectiveToolConfig.imageIterationEnabled && it.isNotBlank() }
                    ?.let { imagePath ->
                        buildGeneratedImageReviewMessage(imagePath, server)?.let { reviewMessage ->
                            roundReviewMessages += reviewMessage
                        }
                    }
            }
            messages += roundReviewMessages
            if (sourceCitations.isNotEmpty()) {
                messages += OllamaService.ChatMessage(
                    role = "system",
                    content = "Citation requirement: The recent tool results include source_citations or KB citation Markdown links. Cite web, Kiwix, search_page, fetch_url, and KB-derived claims using the exact Markdown links. If you use numeric citations like [1], make them Markdown links to the matching source URL. If you use KB labels like [AL.pdf chunk 9], keep the full link form [AL.pdf chunk 9](kb://chunk/123)."
                )
            }
        }

        messages += OllamaService.ChatMessage(
            role = "system",
            content = "The native chat tool round limit has been reached. Answer now using only the tool results already provided. Do not call more tools. If the tool results include source_citations or KB citation links, cite sourced claims with those exact Markdown links."
        )
        publishToolStatus(
            chatId = chatId,
            taskId = taskId,
            progress = progress,
            statusText = getString(R.string.llama_tool_status_finalizing)
        )
        val finalResponse = runModelCall(emptyList())
        mergeUsage(finalResponse)
        appendFallbackContent(finalResponse)
        applySourceCitationFallback(progress, sourceCitations)
        progress.statusText = null
    }

    private suspend fun chatWithLiteRtToolsStreaming(
        server: LlamaServerEntity,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean,
        params: Map<String, Any>,
        onStatus: suspend (String) -> Unit,
        onChunk: (String?, String?) -> Unit
    ): OllamaService.ChatResponse {
        val modelId = server.liteRtModelId
            ?: throw IllegalStateException(getString(R.string.litert_error_model_missing))
        val model = database.liteRtModelDao().getById(modelId)
            ?: throw IllegalStateException(getString(R.string.litert_error_model_missing))
        val prompt = buildLiteRtToolPrompt(
            messages = messages,
            tools = tools,
            thinkingEnabled = thinkingEnabled
        )
        val rendered = StringBuilder()
        val stats = streamLiteRtLmPrompt(
            server = server,
            chat = chat,
            model = model,
            params = params,
            prompt = prompt,
            onStatus = onStatus,
            onChunk = { chunk ->
                rendered.append(chunk)
                onChunk(chunk, null)
            },
            onThinkingChunk = { chunk ->
                onChunk(null, chunk)
            }
        )
        val extracted = extractThinking(rendered.toString())
        val parsed = parseLiteRtToolCallsFromText(extracted.first, tools)
        return OllamaService.ChatResponse(
            message = OllamaService.ChatMessage(
                role = "assistant",
                content = parsed.visibleContent,
                toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
                thinking = extracted.second.takeIf { thinkingEnabled && it.isNotBlank() }
            ),
            done = true,
            toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
            usage = OllamaService.ChatUsage(
                promptTokens = stats.promptTokens,
                completionTokens = stats.completionTokens,
                totalTokens = stats.promptTokens + stats.completionTokens,
                backend = normalizeLiteRtBackend(server.liteRtBackend)
            )
        )
    }

    private suspend fun streamLiteRtLmPrompt(
        server: LlamaServerEntity,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        model: LiteRtModelEntity,
        params: Map<String, Any>,
        prompt: String,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtLmChatStats {
        val backendMode = normalizeLiteRtBackend(server.liteRtBackend)
        val request = LiteRtLmChatRequest(
            model = model,
            chat = chat,
            history = emptyList(),
            backendMode = backendMode,
            params = liteRtGalleryParams(params),
            promptOverride = prompt
        )
        return streamLiteRtLmRequestSafely(
            request = request,
            onStatus = onStatus,
            onChunk = onChunk,
            onThinkingChunk = onThinkingChunk
        )
    }

    private fun liteRtGalleryParams(params: Map<String, Any>): Map<String, Any> = linkedMapOf(
        "top_k" to ((params["top_k"] as? Number)?.toInt() ?: 40).coerceIn(5, 64),
        "top_p" to ((params["top_p"] as? Number)?.toDouble() ?: 0.95).coerceIn(0.0, 0.95),
        "temperature" to ((params["temperature"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0)
    )

    private suspend fun streamLiteRtLmRequestSafely(
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {},
        onAcceleratorFailureReset: (() -> Unit)? = null
    ): LiteRtLmChatStats {
        val backendMode = normalizeLiteRtBackend(request.backendMode)
        val model = request.model

        suspend fun resetAndReport(label: String, detail: String) {
            onAcceleratorFailureReset?.invoke()
            onStatus(getString(R.string.litert_status_backend_failed, label))
            DebugLog.log("LlamaClientService: LiteRT $label worker failed: $detail")
        }

        suspend fun runGpuWorker(): LiteRtLmChatStats {
            DebugLog.log("LlamaClientService: Starting LiteRT GPU worker for ${model.displayName}")
            return liteRtLmWorkerClient.streamGpuChat(
                request = request.copy(backendMode = LITERT_BACKEND_GPU),
                onStatus = onStatus,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )
        }

        if (backendMode == LITERT_BACKEND_GPU) {
            if (LiteRtLmAcceleratorHealth.isGpuQuarantined(applicationContext, model)) {
                val detail = LiteRtLmAcceleratorHealth.gpuCrashDetail(applicationContext, model).orEmpty()
                DebugLog.log(
                    "LlamaClientService: explicit LiteRT GPU retry after recent worker crash " +
                        "for ${model.displayName}: $detail"
                )
                onStatus(getString(R.string.litert_status_gpu_forced_retry))
            }
            return try {
                runGpuWorker()
            } catch (e: Throwable) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                throw IllegalStateException(
                    getString(R.string.litert_error_explicit_backend_failed, "GPU", detail),
                    e
                )
            }
        }

        if (backendMode == LITERT_BACKEND_AUTO && model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
            if (LiteRtLmAcceleratorHealth.isGpuQuarantined(applicationContext, model)) {
                val detail = LiteRtLmAcceleratorHealth.gpuCrashDetail(applicationContext, model).orEmpty()
                DebugLog.log(
                    "LlamaClientService: skipping LiteRT GPU in Auto after recent worker crash " +
                        "for ${model.displayName}: $detail"
                )
                onStatus(getString(R.string.litert_status_gpu_disabled_auto))
            } else {
                try {
                    return runGpuWorker()
                } catch (e: Throwable) {
                    val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                    LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                    resetAndReport("GPU", detail)
                }
            }
        }

        return liteRtLmChatService.streamChat(
            request = request.copy(backendMode = LITERT_BACKEND_CPU),
            onStatus = onStatus,
            onChunk = onChunk,
            onThinkingChunk = onThinkingChunk
        )
    }

    private fun buildLiteRtToolPrompt(
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean
    ): String = buildString {
        appendLine("System:")
        appendLine("You are running in the app's local LiteRT chat engine.")
        appendLine("Answer in the user's language. Use readable Markdown with blank lines before numbered lists.")
        if (thinkingEnabled) {
            appendLine("If this model supports visible thinking, place it inside <think>...</think> before the final answer.")
        } else {
            appendLine("Do not output a thinking block.")
        }
        if (tools.isEmpty()) {
            appendLine("No app tools are available in this turn. Answer now without calling tools.")
        } else {
            appendLine("App tools are available. When you need a tool, output exactly one or more blocks in this form and no other text in that assistant turn:")
            appendLine("<tool_call>{\"name\":\"tool_name\",\"arguments\":{\"param\":\"value\"}}</tool_call>")
            appendLine("Use only tool names listed below. After tool results are provided, answer normally using those results.")
            appendLine("Available tools:")
            appendLine(buildLiteRtToolSchemaJson(tools))
        }
        appendLine()
        appendLine("Conversation:")
        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    appendLine("System:")
                    appendLine(message.content.trim())
                }
                "assistant" -> {
                    appendLine("Assistant:")
                    if (!message.toolCalls.isNullOrEmpty()) {
                        message.toolCalls.forEach { toolCall ->
                            appendLine(buildLiteRtToolCallBlock(toolCall))
                        }
                    } else {
                        appendLine(message.content.trim())
                    }
                }
                "tool" -> {
                    appendLine("Tool result${message.toolCallId?.let { " ($it)" }.orEmpty()}:")
                    appendLine(message.content.trim())
                }
                else -> {
                    appendLine("User:")
                    appendLine(message.content.trim())
                }
            }
            appendLine()
        }
        appendLine("Assistant:")
    }

    private fun buildLiteRtToolSchemaJson(tools: List<AgentTool>): String =
        JSONArray().apply {
            tools.forEach { tool ->
                put(
                    JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        tool.parameters.forEach { (name, description) ->
                                            put(
                                                name,
                                                JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", description)
                                                }
                                            )
                                        }
                                    }
                                )
                                put(
                                    "required",
                                    JSONArray().apply { tool.requiredParams.forEach { put(it) } }
                                )
                            }
                        )
                    }
                )
            }
        }.toString(2)

    private fun buildLiteRtToolCallBlock(toolCall: OllamaService.ToolCall): String =
        "<tool_call>${JSONObject().apply {
            put("name", toolCall.name)
            put("arguments", JSONObject(toolCall.arguments as Map<*, *>))
        }}</tool_call>"

    private data class LiteRtToolParseResult(
        val visibleContent: String,
        val toolCalls: List<OllamaService.ToolCall>
    )

    private fun parseLiteRtToolCallsFromText(
        text: String,
        tools: List<AgentTool>
    ): LiteRtToolParseResult {
        val availableToolNames = tools.map { it.name }.toSet()
        val toolCalls = mutableListOf<OllamaService.ToolCall>()
        val tagRegex = Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""", RegexOption.IGNORE_CASE)
        tagRegex.findAll(text).forEach { match ->
            toolCalls += parseLiteRtToolCallPayload(match.groupValues.getOrElse(1) { "" }, availableToolNames)
        }
        var visibleContent = text.replace(tagRegex, "").trim()

        if (toolCalls.isEmpty()) {
            val candidate = unwrapLiteRtToolJsonCandidate(visibleContent)
            if (candidate != null) {
                val parsed = parseLiteRtToolCallPayload(candidate, availableToolNames)
                if (parsed.isNotEmpty()) {
                    toolCalls += parsed
                    visibleContent = ""
                }
            }
        }

        return LiteRtToolParseResult(
            visibleContent = visibleContent,
            toolCalls = toolCalls
        )
    }

    private fun unwrapLiteRtToolJsonCandidate(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fence = Regex("""^```(?:json|tool_call)?\s*([\s\S]*?)\s*```$""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        return fence?.takeIf { it.startsWith("{") || it.startsWith("[") }
    }

    private fun parseLiteRtToolCallPayload(
        rawPayload: String,
        availableToolNames: Set<String>
    ): List<OllamaService.ToolCall> = runCatching {
        val trimmed = rawPayload.trim()
        when {
            trimmed.startsWith("[") -> {
                val array = JSONArray(trimmed)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)
                            ?.let { parseLiteRtToolCallObject(it, availableToolNames) }
                            ?.let { add(it) }
                    }
                }
            }
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("tool_calls")?.let { array ->
                    return@runCatching buildList {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)
                                ?.let { parseLiteRtToolCallObject(it, availableToolNames) }
                                ?.let { add(it) }
                        }
                    }
                }
                listOfNotNull(parseLiteRtToolCallObject(obj, availableToolNames))
            }
            else -> emptyList()
        }
    }.getOrElse { error ->
        DebugLog.log("LlamaClientService: LiteRT tool-call parse failed: ${error.message}")
        emptyList()
    }

    private fun parseLiteRtToolCallObject(
        json: JSONObject,
        availableToolNames: Set<String>
    ): OllamaService.ToolCall? {
        val wrapper = json.optJSONObject("tool_call")
            ?: json.optJSONObject("function_call")
            ?: json
        val function = wrapper.optJSONObject("function")
        val source = function ?: wrapper
        val name = source.optString("name").trim()
        if (name.isBlank() || name !in availableToolNames) return null
        val argsSource = source.opt("arguments")
            ?: source.opt("args")
            ?: source.opt("parameters")
        val arguments = AgentRuntimeSupport.normalizeToolArguments(argsSource)
        val id = wrapper.optString("id").takeIf { it.isNotBlank() }
            ?: json.optString("id").takeIf { it.isNotBlank() }
        return OllamaService.ToolCall(
            name = name,
            arguments = arguments,
            id = id
        )
    }

    private fun buildGeneratedImageReviewMessage(
        imagePath: String,
        server: LlamaServerEntity
    ): OllamaService.ChatMessage? {
        if (!server.supportsVision) return null
        val imageFile = File(imagePath)
        if (!imageFile.exists() || !imageFile.isFile) return null
        val encodedImage = runCatching { fileToBase64(imagePath) }.getOrNull()
            ?: return null
        return OllamaService.ChatMessage(
            role = "user",
            content = "Generated image from generate_image is attached for visual review. Compare it with the user's request. If it needs improvement and tool rounds remain, call generate_image again with a better optimized prompt. If it is good enough, give the final answer. Do not insert it into a note unless the user asked for that or you decide a note tool call is appropriate.",
            images = if (server.isOllamaEngine()) listOf(encodedImage) else null,
            imagePath = imagePath
        )
    }

    private suspend fun summarizeNativeSearchPageWithBackend(
        server: LlamaServerEntity,
        modelName: String?,
        request: NativeChatSearchSummaryRequest
    ): String {
        val pageText = request.content
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .take(NATIVE_SEARCH_SUMMARY_INPUT_CHARS)
        require(pageText.isNotBlank()) { "No readable text found." }

        val messages = listOf(
            OllamaService.ChatMessage(
                role = "system",
                content = "You are a compact search-result summarizer. Return only a factual 2-3 sentence summary of the page content. Do not quote long passages, do not include tool instructions, and do not say you searched the web."
            ),
            OllamaService.ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("Source type: ${request.source}")
                    appendLine("Title: ${request.title}")
                    appendLine("URL: ${request.url}")
                    appendLine()
                    appendLine("Readable page text:")
                    append(pageText)
                }
            )
        )

        val response = if (server.isOllamaEngine()) {
            ollamaService.chatWithToolsStreaming(
                model = modelName.orEmpty(),
                messages = messages,
                tools = emptyList(),
                thinkingEnabled = false,
                numCtxOverride = NATIVE_SEARCH_SUMMARY_CONTEXT
            ).getOrElse { throw it }
        } else {
            llamaServerChatService.chatWithToolsStreaming(
                baseUrl = server.baseUrl(),
                messages = messages,
                tools = emptyList(),
                modelLabel = modelName,
                thinkingEnabled = false,
                numCtx = NATIVE_SEARCH_SUMMARY_MAX_TOKENS,
                samplingParams = LlamaServerSamplingParams(
                    temperature = 0.2f,
                    topP = 0.9f
                )
            ).getOrElse { throw it }
        }

        return response.message.content
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(request.maxChars.coerceIn(200, NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS))
            ?: throw IllegalStateException("Search summarizer returned an empty summary.")
    }

    private fun syncOllamaService(server: LlamaServerEntity) {
        ollamaService.setBaseUrl(server.baseUrl().trimEnd('/'))
        ollamaService.setUseMmap(settingsRepo.ollamaMmap.value)
        ollamaService.setNumThreads(settingsRepo.ollamaThreads.value)
        ollamaService.setNumCtx(settingsRepo.ollamaNumCtx.value)
    }

    private suspend fun persistGeneratedImageMessages(chatId: Long, progress: StreamingProgress) {
        val imagePaths = progress.generatedImagePaths.distinct()
        if (imagePaths.isEmpty()) return
        imagePaths.forEach { imagePath ->
            repository.addMessage(
                chatId = chatId,
                role = "assistant",
                content = getString(R.string.llama_generated_image_message),
                imagePath = imagePath
            )
        }
        progress.generatedImagePaths.clear()
    }

    private fun warnIfBatteryOptimizationMayThrottle(chatId: Long) {
        val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return
        val exempt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        if (!exempt) {
            val warning = getString(R.string.llama_battery_optimization_warning)
            DebugLog.log("[LlamaClientService] $warning")
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    statusText = warning
                )
            )
        }
    }

    private fun maybeRecordPowerDiagnostics(progress: StreamingProgress) {
        val now = System.currentTimeMillis()
        if (now - progress.lastPowerDiagnosticMs < POWER_DIAGNOSTIC_INTERVAL_MS) return
        progress.lastPowerDiagnosticMs = now
        val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        val batteryExempt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        val stalledMs = (now - progress.lastTokenAtMs).coerceAtLeast(0L)
        DebugLog.log(
            "[LlamaClientService] power wakeLock=${WakeLockManager.isHeld()} " +
                "wifiLock=${WakeLockManager.isWifiHeld()} interactive=${powerManager?.isInteractive} " +
                "powerSave=${powerManager?.isPowerSaveMode} batteryExempt=$batteryExempt " +
                "tokenGapMs=$stalledMs tokens=${progress.tokenCount}"
        )
    }

    private fun buildOllamaMessages(
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        history: List<LlamaMessageEntity>,
        server: LlamaServerEntity
    ): List<OllamaService.ChatMessage> {
        val messages = mutableListOf<OllamaService.ChatMessage>()
        if (!chat.systemPrompt.isNullOrBlank()) {
            messages += OllamaService.ChatMessage(
                role = "system",
                content = chat.systemPrompt
            )
        }

        history.forEach { message ->
            messages += OllamaService.ChatMessage(
                role = message.role,
                content = message.content,
                images = if (message.role == "user" && server.supportsVision && !message.imagePath.isNullOrBlank()) {
                    listOf(fileToBase64(message.imagePath))
                } else {
                    null
                },
                imagePath = message.imagePath,
                audioPath = message.audioPath,
                thinking = message.thinking
            )
        }
        return messages
    }

    private fun List<OllamaService.ChatMessage>.withMergedTransientSystemMessages(
        transientSystemMessages: List<OllamaService.ChatMessage>
    ): List<OllamaService.ChatMessage> {
        val transientContent = transientSystemMessages
            .filter { it.role == "system" && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content.trim() }
        if (transientContent.isBlank()) return this
        val messages = toMutableList()
        val firstSystemIndex = messages.indexOfFirst { it.role == "system" }
        if (firstSystemIndex >= 0) {
            val existing = messages[firstSystemIndex]
            messages[firstSystemIndex] = existing.copy(
                content = listOf(existing.content.trim(), transientContent)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )
        } else {
            messages.add(
                0,
                OllamaService.ChatMessage(
                    role = "system",
                    content = transientContent
                )
            )
        }
        return messages
    }

    private suspend fun updateStreamingProgress(
        chatId: Long,
        taskId: Int,
        assistantMsgId: Long,
        progress: StreamingProgress,
        lastUpdateMs: Long
    ): Long {
        val elapsed = (System.currentTimeMillis() - progress.streamStartTimeMs) / 1000.0
        val tps = if (elapsed > 0.0) progress.tokenCount / elapsed else 0.0
        maybeRecordPowerDiagnostics(progress)

        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = tps,
                statusText = progress.statusText,
                toolEvents = progress.toolEvents.toList()
            )
        )

        if (System.currentTimeMillis() - lastUpdateMs <= 500) {
            return lastUpdateMs
        }

        val currentElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
        repository.updateMessageThinkingAndContent(
            assistantMsgId,
            progress.content,
            progress.thinking.takeIf { it.isNotBlank() },
            promptTokens = progress.promptTokens,
            completionTokens = progress.completionTokens,
            tps = tps,
            generationTimeMs = currentElapsedMs
        )
        val progressContext = if (progress.thinking.isNotBlank() && progress.content.isBlank()) {
            progress.thinking
        } else {
            progress.content
        }
        val words = progressContext.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lastWords = words.takeLast(7).joinToString(" ")
        val progressText = if (lastWords.isBlank()) {
            "%d tok · %.1f t/s".format(progress.tokenCount, tps)
        } else {
            "%d tok · %.1f t/s | ...$lastWords".format(progress.tokenCount, tps)
        }
        UnifiedNotificationManager.updateProgress(taskId, 0.5f, progressText)
        return System.currentTimeMillis()
    }

    private fun publishToolStatus(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        statusText: String
    ) {
        progress.statusText = statusText
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = 0.0,
                statusText = statusText,
                toolEvents = progress.toolEvents.toList()
            )
        )
        UnifiedNotificationManager.updateProgress(taskId, 0.45f, statusText)
    }

    private fun publishToolActivity(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        event: ToolActivityEvent
    ) {
        progress.toolEvents += event
        if (progress.toolEvents.size > MAX_TOOL_ACTIVITY_EVENTS) {
            progress.toolEvents.removeAt(0)
        }
        progress.statusText = event.status
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = 0.0,
                statusText = event.status,
                toolEvents = progress.toolEvents.toList()
            )
        )
        UnifiedNotificationManager.updateProgress(taskId, 0.45f, event.status)
    }

    private fun statusTextForToolCall(toolCall: OllamaService.ToolCall): String =
        when (toolCall.name) {
            NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                R.string.llama_tool_status_web_search,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_SEARCH_PAGE -> getString(
                R.string.llama_tool_status_search_page,
                (toolCall.arguments["query"] ?: toolCall.arguments["url"]).orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                R.string.llama_tool_status_kiwix_search,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_FETCH_URL -> getString(
                R.string.llama_tool_status_fetch_url,
                toolCall.arguments["url"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_DEEP_RESEARCH -> getString(
                R.string.llama_tool_status_deep_research,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_GET_DATETIME -> getString(R.string.llama_tool_status_datetime)
            NativeChatToolRuntime.TOOL_CALCULATOR -> getString(R.string.llama_tool_status_calculator)
            NativeChatToolRuntime.TOOL_LIST_NOTES -> getString(R.string.llama_tool_status_list_notes)
            NativeChatToolRuntime.TOOL_READ_NOTE -> getString(R.string.llama_tool_status_read_note)
            NativeChatToolRuntime.TOOL_CREATE_NOTE -> getString(R.string.llama_tool_status_create_note)
            NativeChatToolRuntime.TOOL_UPDATE_NOTE -> getString(R.string.llama_tool_status_update_note)
            NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT -> getString(R.string.llama_tool_status_update_note)
            NativeChatToolRuntime.TOOL_CREATE_TODO_LIST -> getString(R.string.llama_tool_status_create_todo)
            NativeChatToolRuntime.TOOL_ADD_TODO_ITEM,
            NativeChatToolRuntime.TOOL_UPDATE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_REMOVE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED -> getString(R.string.llama_tool_status_update_todo)
            NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS,
            NativeChatToolRuntime.TOOL_READ_CALENDAR_EVENT -> getString(R.string.llama_tool_status_calendar)
            NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT,
            NativeChatToolRuntime.TOOL_UPDATE_CALENDAR_EVENT,
            NativeChatToolRuntime.TOOL_DELETE_CALENDAR_EVENT -> getString(R.string.llama_tool_status_update_calendar)
            NativeChatToolRuntime.TOOL_LIST_ALARMS,
            NativeChatToolRuntime.TOOL_READ_ALARM -> getString(R.string.llama_tool_status_alarms)
            NativeChatToolRuntime.TOOL_CREATE_ALARM,
            NativeChatToolRuntime.TOOL_UPDATE_ALARM,
            NativeChatToolRuntime.TOOL_DELETE_ALARM -> getString(R.string.llama_tool_status_update_alarm)
            NativeChatToolRuntime.TOOL_KB_SEARCH -> getString(
                R.string.llama_tool_status_kb_search,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_KB_READ_CHUNK -> getString(R.string.llama_tool_status_kb_read)
            NativeChatToolRuntime.TOOL_KB_LIST_SOURCES -> getString(R.string.llama_tool_status_kb_sources)
            NativeChatToolRuntime.TOOL_GENERATE_IMAGE -> getString(R.string.llama_tool_status_generate_image)
            else -> getString(R.string.llama_tool_status_running)
        }

    private fun localizedToolProgressStatus(
        toolCall: OllamaService.ToolCall,
        progress: NativeChatToolProgress
    ): String {
        val title = progress.title?.take(80).orEmpty()
        return when (progress.phase) {
            NativeChatToolProgressPhase.SEARCHING -> statusTextForToolCall(toolCall)
            NativeChatToolProgressPhase.RESEARCHING -> statusTextForToolCall(toolCall)
            NativeChatToolProgressPhase.FOUND -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_found_web,
                    progress.count ?: 0
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_found_kiwix,
                    progress.count ?: 0
                )
                NativeChatToolRuntime.TOOL_SEARCH_PAGE -> getString(
                    R.string.llama_tool_activity_found_page_links,
                    progress.count ?: 0
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.READING -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_reading_web,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_reading_kiwix,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.SUMMARIZED -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_summarized_web,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_summarized_kiwix,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.FETCHING -> statusTextForToolCall(toolCall)
            NativeChatToolProgressPhase.GENERATING -> statusTextForToolCall(toolCall)
            else -> progress.status
        }
    }

    private fun normalizeToolCalls(
        toolCalls: List<OllamaService.ToolCall>,
        round: Int
    ): List<OllamaService.ToolCall> = toolCalls.mapIndexed { index, toolCall ->
        if (!toolCall.id.isNullOrBlank()) {
            toolCall
        } else {
            toolCall.copy(id = "call_${round}_${index}_${System.nanoTime()}")
        }
    }

    private suspend fun transcribeAudioAttachment(
        server: LlamaServerEntity,
        audioPath: String
    ): Result<WhisperResult> = withWhisperService { whisperService ->
        val whisperModelPath = resolveWhisperModelPath(server)
            ?: return@withWhisperService Result.failure(
                IllegalStateException(getString(R.string.whisper_error_no_model))
            )

        whisperService.transcribe(
            WhisperConfig(
                modelPath = whisperModelPath,
                audioPath = audioPath,
                language = server.whisperLanguage.ifBlank { LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE },
                outputFormats = setOf(WhisperOutputFormat.TXT),
                threads = settingsRepo.whisperThreads.value
            )
        )
    }

    private suspend fun resolveWhisperModelPath(server: LlamaServerEntity): String? {
        server.whisperModelPath
            ?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
            ?.let { return it }
        return database.modelDao()
            .getModelsByTypesSync(listOf(ModelType.WHISPER))
            .firstOrNull()
            ?.path
    }

    private suspend fun <T> withWhisperService(
        block: suspend (WhisperService) -> Result<T>
    ): Result<T> {
        applicationContext.startForegroundService(whisperBindingIntent)
        return suspendCancellableCoroutine { continuation ->
            var isBound = false
            val connection = object : ServiceConnection {
                private fun finish(result: Result<T>) {
                    if (isBound) {
                        runCatching { applicationContext.unbindService(this) }
                        isBound = false
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = (binder as? WhisperService.WhisperBinder)?.getService()
                    if (service == null) {
                        finish(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
                        return
                    }
                    serviceScope.launch {
                        val result = runCatching { block(service) }.getOrElse { Result.failure(it) }
                        finish(result)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
                }
            }

            isBound = applicationContext.bindService(
                whisperBindingIntent,
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!isBound && continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
            }
            continuation.invokeOnCancellation {
                if (isBound) {
                    runCatching { applicationContext.unbindService(connection) }
                }
            }
        }
    }

    private var cumulativeDedicatedReasoning = ""

    private fun extractThinking(text: String, newDedicated: String = ""): Pair<String, String> {
        if (newDedicated.isNotEmpty()) {
            cumulativeDedicatedReasoning += newDedicated
        }
        val thinkTags = listOf("<think>", "<|think|>", "<thought>", "<Thought>", "<Think>")
        val endThinkTags = listOf("</think>", "</|think|>", "</thought>", "</Thought>", "</Think>")
        
        var startTag: String? = null
        var startIndex = -1
        
        for (tag in thinkTags) {
            val idx = text.indexOf(tag, ignoreCase = true)
            if (idx != -1 && (startIndex == -1 || idx < startIndex)) {
                startIndex = idx
                startTag = tag
            }
        }
        if (startIndex == -1) {
            return Pair(text, cumulativeDedicatedReasoning.trim())
        }
        var endTag: String? = null
        var endIndex = -1
        for (tag in endThinkTags) {
            val idx = text.indexOf(tag, startIndex + (startTag?.length ?: 0), ignoreCase = true)
            if (idx != -1 && (endIndex == -1 || idx < endIndex)) {
                endIndex = idx
                endTag = tag
            }
        }
        return if (endIndex != -1) {
            val thinking = text.substring(startIndex + (startTag?.length ?: 0), endIndex)
            val content = text.substring(0, startIndex) + text.substring(endIndex + (endTag?.length ?: 0))
            Pair(content.trim(), (cumulativeDedicatedReasoning + "\n" + thinking).trim())
        } else {
            val thinking = text.substring(startIndex + (startTag?.length ?: 0))
            val content = text.substring(0, startIndex)
            Pair(content.trim(), (cumulativeDedicatedReasoning + "\n" + thinking).trim())
        }
    }

    private fun resetThinking() {
        cumulativeDedicatedReasoning = ""
    }

    private fun stopGeneration() {
        job?.cancel()
        OllamaService.stop()
        llamaServerChatService.stopGeneration()
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePowerLocks()
        Companion.updateState(GenerationState.Idle)
    }

    private fun showDebugToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
             android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        OllamaService.stop()
        llamaServerChatService.stopGeneration()
        serviceScope.cancel()
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        releasePowerLocks()
    }

    private fun acquirePowerLocks() {
        synchronized(powerLockGuard) {
            if (powerLocksHeld) return
            WakeLockManager.acquire(applicationContext, "LlamaClientService")
            WakeLockManager.acquireWifiLock(applicationContext, "LlamaClientService")
            powerLocksHeld = true
        }
    }

    private fun releasePowerLocks() {
        synchronized(powerLockGuard) {
            if (!powerLocksHeld) return
            WakeLockManager.release("LlamaClientService")
            WakeLockManager.releaseWifiLock("LlamaClientService")
            powerLocksHeld = false
        }
    }

    private data class PreparedUserTurn(
        val content: String,
        val imagePath: String?,
        val audioPath: String?,
        val alreadyPersisted: Boolean = false
    ) {
        fun shouldPersist(): Boolean =
            content.isNotBlank() || !imagePath.isNullOrBlank() || !audioPath.isNullOrBlank()
    }

    private data class AssistantMessagePreparation(
        val history: List<LlamaMessageEntity>,
        val assistantMsgId: Long,
        val content: String,
        val thinking: String
    )

    private fun applySourceCitationFallback(progress: StreamingProgress, citations: List<NativeChatSourceCitation>) {
        progress.content = applyNativeChatSourceCitationFallback(progress.content, citations)
        progress.thinking = applyNativeChatSourceCitationFallback(progress.thinking, citations)
    }

    private data class StreamingProgress(
        var content: String = "",
        var thinking: String = "",
        var promptTokens: Int = 0,
        var completionTokens: Int = 0,
        var tokenCount: Int = 0,
        var streamStartTimeMs: Long = System.currentTimeMillis(),
        var isTruncated: Boolean = false,
        var reportedTokensPerSecond: Double? = null,
        var statusText: String? = null,
        val toolEvents: MutableList<ToolActivityEvent> = mutableListOf(),
        val generatedImagePaths: MutableList<String> = mutableListOf(),
        var lastPowerDiagnosticMs: Long = 0L,
        var lastTokenAtMs: Long = System.currentTimeMillis()
    )

    companion object {
        const val ACTION_GENERATE = "GENERATE"
        const val ACTION_STOP = "STOP"
        const val EXTRA_CHAT_ID = "CHAT_ID"
        const val EXTRA_SERVER_ID = "SERVER_ID"
        const val EXTRA_USER_MESSAGE = "USER_MESSAGE"
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_AUDIO_PATH = "AUDIO_PATH"

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState = _generationState.asStateFlow()
        
        fun updateState(state: GenerationState) {
            _generationState.value = state
        }

        private const val POWER_DIAGNOSTIC_INTERVAL_MS = 30_000L
        private const val LOCAL_SERVER_READY_ATTEMPTS = 90
        private const val LOCAL_SERVER_READY_DELAY_MS = 2_000L
        
        fun resetStateIfIdle() {
            if (_generationState.value !is GenerationState.Generating) {
                _generationState.value = GenerationState.Idle
            }
        }

        private const val MAX_TOOL_ACTIVITY_EVENTS = 40
        private const val NATIVE_SEARCH_SUMMARY_CONTEXT = 4096
        private const val NATIVE_SEARCH_SUMMARY_MAX_TOKENS = 512
        private const val NATIVE_SEARCH_SUMMARY_INPUT_CHARS = 6_000
        private const val NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS = 900
    }

    data class ToolActivityEvent(
        val id: String,
        val toolName: String,
        val status: String,
        val title: String? = null,
        val url: String? = null,
        val outputPreview: String? = null,
        val isComplete: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis()
    )
    
    sealed class GenerationState {
        object Idle : GenerationState()
        data class Generating(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val tokenCount: Int = 0,
            val tokensPerSecond: Double = 0.0,
            val isTranscribingAudio: Boolean = false,
            val transcribingMessageId: Long? = null,
            val statusText: String? = null,
            val toolEvents: List<ToolActivityEvent> = emptyList()
        ) : GenerationState()
        data class Completed(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val completionTokens: Int = 0,
            val promptTokens: Int = 0,
            val tokensPerSecond: Double = 0.0
        ) : GenerationState()
        data class Error(val message: String, val chatId: Long = -1L) : GenerationState()
    }
}
