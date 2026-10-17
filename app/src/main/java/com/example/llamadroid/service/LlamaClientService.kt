package com.example.llamadroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.os.IBinder
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.LlamaChatRequest
import com.example.llamadroid.service.compaction.NativeChatCompactionHooks
import com.example.llamadroid.service.compaction.NativeChatContextFallback
import com.example.llamadroid.service.compaction.NativeChatContextWindow
import com.example.llamadroid.service.compaction.NativeChatTurnDecorators
import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatResponse
import com.example.llamadroid.data.api.LlamaChatTimings
import com.example.llamadroid.data.api.LlamaStreamOptions
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.vision.ImageFallbackLogic
import com.example.llamadroid.service.vision.VisionDescriptionService
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.hasEmbeddedAudioTranscript
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.mergeUserTextWithAudioTranscript
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
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
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import com.example.llamadroid.service.chat.ChatAdmissionController
import com.example.llamadroid.service.chat.ChatAdmissionPlanner
import com.example.llamadroid.service.chat.ChatGenerationScheduler
import com.example.llamadroid.service.chat.ChatGenerationSession
import com.example.llamadroid.service.chat.ChatPowerLocks
import com.example.llamadroid.service.chat.DecodeTimings
import com.example.llamadroid.service.chat.NativeChatModelActivity
import com.example.llamadroid.service.chat.NativeChatStepHistory
import com.example.llamadroid.service.chat.NativeChatStepRecorder
import com.example.llamadroid.service.chat.NativeChatStepResume
import com.example.llamadroid.service.chat.NativeChatStepSession
import com.example.llamadroid.service.chat.NativeChatToolLoopState
import com.example.llamadroid.service.chat.NativeChatTurnNotes
import com.example.llamadroid.service.chat.NativeToolRetryPolicy
import com.example.llamadroid.service.chat.onOverflowWithTurn
import com.example.llamadroid.service.chat.saveSlotAfterStop
import com.example.llamadroid.service.chat.NativeToolCallPatcher
import com.example.llamadroid.service.chat.extractThinkingTags
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import com.example.llamadroid.onnx.stripTextForTts
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import kotlinx.coroutines.flow.first
import okhttp3.Call
import okhttp3.Callback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun isNativeChatLoopbackHost(host: String): Boolean {
    val normalized = normalizeNativeChatServerHost(host)
    return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized == "[::1]"
}

internal fun generationElapsedMs(
    requestStartedAtMs: Long,
    firstTokenReceivedAtMs: Long?,
    nowMs: Long
): Long {
    if (firstTokenReceivedAtMs == null) return 0L
    val effectiveStart = maxOf(requestStartedAtMs, firstTokenReceivedAtMs)
    return (nowMs - effectiveStart).coerceAtLeast(0L)
}

private const val LITERT_CHAT_FALLBACK_CONTEXT_TOKENS = 4000
private const val STREAMING_STATE_UPDATE_INTERVAL_MS = 100L
private const val STREAMING_PERSIST_INTERVAL_MS = 500L

private fun LlamaChatEntity.withDefaultLiteRtContext(model: LiteRtModelEntity): LlamaChatEntity {
    val selectedContext = contextSize.takeIf { it > 0 }
        ?: model.defaultLiteRtChatContextTokens()
        ?: LITERT_CHAT_FALLBACK_CONTEXT_TOKENS
    return if (selectedContext == contextSize) {
        this
    } else {
        copy(contextSize = selectedContext)
    }
}

internal fun nativeChatLocalHostForServer(host: String): String =
    if (normalizeNativeChatServerHost(host).contains(":")) "::1" else "127.0.0.1"

internal fun shouldAutoStartLocalLlamaServer(
    server: LlamaServerEntity,
    state: ServerState
): Boolean =
    server.isLlamaServerEngine() &&
        isNativeChatLoopbackHost(server.host) &&
        (state is ServerState.Stopped || state is ServerState.Error)

internal fun shouldAttachMmprojForLocalAutoStart(
    server: LlamaServerEntity,
    imagePath: String?,
    visionEnabled: Boolean,
    selectedMmprojPath: String?,
    videoPath: String? = null
): Boolean =
    (server.supportsVision || server.supportsVideo) &&
        (visionEnabled || server.supportsVideo) &&
        (!imagePath.isNullOrBlank() || !videoPath.isNullOrBlank()) &&
        !selectedMmprojPath.isNullOrBlank()

private fun LlamaServerEntity.supportsNativeVideoInput(): Boolean {
    if (!supportsVideo || (!isLlamaServerEngine() && !isLlamaSwapEngine())) return false
    // Managed saved models have a separate video switch. Remote rows without
    // launch-profile JSON continue to use their explicit supportsVideo flag.
    return LlamaServerLaunchProfile.decode(localLaunchProfileJson)?.videoEnabled ?: true
}

/**
 * Video audio is opt-in at the capability and policy boundaries.  The typed
 * launch-profile field is added by the settings work; until then an existing
 * video-enabled profile (or an explicit video-capable remote row) is the
 * compatibility default, while a JSON false value always disables it.
 */
internal fun LlamaServerEntity.allowsNativeChatVideoAudio(): Boolean =
    // Processing preferences live in VideoRecognitionSettingsRepository. A launch profile may
    // still contain the old audio flag for decoding, but it must not override the global policy.
    supportsNativeVideoInput() && supportsDirectAudioInput()

internal fun VideoProcessingPolicy.usesLegacyTranscriptRoute(): Boolean =
    mode == VideoProcessingMode.LEGACY_TRANSCRIPT

/** Inline video is safe only for an app-managed loopback profile with a verified bounded FPS. */
internal fun LlamaServerEntity.allowsSafeNativeChatVideoStream(): Boolean {
    if (!isLlamaServerEngine() || !isNativeChatLoopbackHost(host)) return false
    val profile = LlamaServerLaunchProfile.decode(localLaunchProfileJson) ?: return false
    return profile.videoEnabled &&
        profile.videoFps.isFinite() &&
        profile.videoFps > 0f &&
        profile.videoFps <= NativeLlamaVideoSupport.MAX_VIDEO_FPS
}

internal fun liteRtAutoGpuWorkerMaxAttempts(model: LiteRtModelEntity): Int =
    if (model.isKnownStableLiteRtGpuFamily()) 2 else 1

private fun LiteRtModelEntity.isKnownStableLiteRtGpuFamily(): Boolean {
    val lower = listOf(displayName, filename, repoId.orEmpty()).joinToString(" ").lowercase()
    return "gemma" in lower
}

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

class LlamaClientService : Service() {

    internal val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** One generation session per chat id; a chat already in the map is busy (running or queued). */
    private val sessions = ConcurrentHashMap<Long, ChatGenerationSession>()
    private val foregroundLock = Any()
    private var notificationTaskId: Int? = null
    private lateinit var powerLocks: ChatPowerLocks
    private lateinit var admissionPlanner: ChatAdmissionPlanner
    private lateinit var scheduler: ChatGenerationScheduler
    private lateinit var toolExtensions: List<NativeChatToolExtension>
    /** Used only by code paths that run outside any chat session. */
    private val fallbackChatService by lazy { LlamaServerChatService() }

    // Repository to save messages
    private lateinit var repository: LlamaRepository
    internal lateinit var settingsRepo: SettingsRepository
    /** Shared policy used by Video Summary and Native Chat video preparation. */
    internal lateinit var videoSettingsRepo: VideoRecognitionSettingsRepository
    private lateinit var ollamaService: OllamaService
    private lateinit var database: AppDatabase
    private lateinit var liteRtLmChatService: LiteRtLmChatService
    private lateinit var liteRtLmWorkerClient: LiteRtLmWorkerClient
    private lateinit var nativeChatToolRuntime: NativeChatToolRuntime
    private val whisperBindingIntent by lazy { Intent(applicationContext, WhisperService::class.java) }

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
        videoSettingsRepo = VideoRecognitionSettingsRepository(applicationContext)
        ollamaService = OllamaService(applicationContext)
        liteRtLmChatService = LiteRtLmChatService(applicationContext)
        liteRtLmWorkerClient = LiteRtLmWorkerClient(applicationContext)
        powerLocks = ChatPowerLocks(applicationContext, "LlamaClientService")
        admissionPlanner = ChatAdmissionPlanner(applicationContext, database)
        scheduler = ChatGenerationScheduler(
            scope = serviceScope,
            controller = ChatAdmissionController(ramFits = { target, reservedBytes ->
                val estimate = target.ramEstimate
                estimate == null || admissionPlanner.ramGate.decide(
                    estimate.copy(weightsBytes = estimate.weightsBytes + reservedBytes)
                ).ok
            }),
            publish = { state -> Companion.updateState(state) }
        )
        // Same extensions and order as PrepareCachesSheet, so prepared prefixes match.
        toolExtensions = listOf(com.example.llamadroid.service.delegation.NativeChatDelegationTools(applicationContext),
            NativeChatAttachmentTools.forChatService(applicationContext))
        nativeChatToolRuntime = NativeChatToolRuntime(
            context = applicationContext,
            noteDao = database.noteDao(),
            organizerDao = database.organizerDao(),
            alarmScheduler = { alarm -> OrganizerAlarmScheduler.scheduleAlarm(applicationContext, alarm) },
            alarmCanceler = { alarmId -> OrganizerAlarmScheduler.cancelAlarm(applicationContext, alarmId) },
            organizerChanged = { OrganizerCalendarWidgetProvider.refreshAll(applicationContext) },
            notesChanged = { NoteDisplayWidgetProvider.refreshAll(applicationContext) },
            knowledgeBaseRepository = com.example.llamadroid.data.repository.KnowledgeBaseRepository(applicationContext, database),
            imageGenerator = NativeChatUnifiedImageGenerator(applicationContext, database),
            backgroundRemover = NativeChatOnnxBackgroundRemover(applicationContext, database),
            pdfTextExtractor = { pdfBytes, maxChars ->
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                extractNativePdfTextFromBytes(pdfBytes, maxChars)
            },
            extensions = toolExtensions
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                synchronized(foregroundLock) { ensureForegroundStarted() }
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                val pretranscribedAudioText = intent.getStringExtra(EXTRA_PRETRANSCRIBED_AUDIO_TEXT)
                val forceAssistantTts = intent.getBooleanExtra(EXTRA_FORCE_ASSISTANT_TTS, false)
                val callMode = intent.getBooleanExtra(EXTRA_CALL_MODE, false)
                
                if (chatId != -1L) {
                    startGeneration(
                        chatId,
                        serverId,
                        userMessage,
                        imagePath,
                        audioPath,
                        videoPath,
                        pretranscribedAudioText,
                        forceAssistantTts,
                        callMode
                    )
                } else {
                    DebugLog.log("LlamaClientService: Missing params for generation")
                    Companion.updateState(GenerationState.Error("Missing parameters for generation", chatId))
                    releaseForegroundIfIdle()
                }
            }
            ACTION_STOP -> {
                synchronized(foregroundLock) { ensureForegroundStarted() }
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                if (chatId > 0L) {
                    sessions[chatId]?.let(::stopSession)
                } else {
                    stopAllSessions()
                }
                releaseForegroundIfIdle()
            }
            ACTION_CANCEL_QUEUED -> {
                synchronized(foregroundLock) { ensureForegroundStarted() }
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                if (chatId > 0L && scheduler.isQueued(chatId)) {
                    sessions[chatId]?.let(::stopSession)
                }
                releaseForegroundIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 foreground-service timeout: stop every session cleanly, never throw. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching {
            DebugLog.log("LlamaClientService: foreground timeout type=$fgsType; stopping all generations")
            stopAllSessions()
        }
        runCatching {
            synchronized(foregroundLock) {
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                notificationTaskId = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        runCatching { stopSelf() }
    }

    /** Caller holds [foregroundLock]. */
    private fun ensureForegroundStarted(): Int {
        notificationTaskId?.let { return it }
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_CLIENT,
            "Llama Chat"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return taskId
    }

    /** Removes the foreground notification once no chat is generating or queued. */
    private fun releaseForegroundIfIdle() {
        synchronized(foregroundLock) {
            if (sessions.isNotEmpty()) {
                refreshNotification()
                return
            }
            notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
            notificationTaskId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** The single notification: per-chat progress for one chat, "N chats generating" for several. */
    private fun notifyProgress(taskId: Int, progress: Float, text: String) {
        val count = sessions.size
        val shown = if (count > 1) {
            resources.getQuantityString(R.plurals.native_chat_notification_generating_count, count, count)
        } else {
            text
        }
        UnifiedNotificationManager.updateProgress(notificationTaskId ?: taskId, progress, shown)
    }

    private fun refreshNotification() {
        val taskId = notificationTaskId ?: return
        if (sessions.size > 1) notifyProgress(taskId, 0.05f, "")
    }

    private fun chatService(chatId: Long): LlamaServerChatService =
        sessions[chatId]?.llamaServerChatService ?: fallbackChatService

    private fun startGeneration(
        chatId: Long,
        serverId: Long,
        userMessage: String?,
        imagePath: String?,
        audioPath: String?,
        videoPath: String?,
        pretranscribedAudioText: String? = null,
        forceAssistantTts: Boolean = false,
        callMode: Boolean = false
    ) {
        val session = ChatGenerationSession(chatId)
        val taskId = synchronized(foregroundLock) {
            if (sessions.containsKey(chatId)) null else ensureForegroundStarted().also { sessions[chatId] = session }
        } ?: run {
            DebugLog.log("LlamaClientService: Generation already in progress for chat $chatId")
            // Surface the in-flight start instead of silently dropping the second send.
            if (NativeChatGenerationStates.stateFor(chatId).let {
                    it is GenerationState.Generating && it.content.isEmpty() &&
                        it.promptTotal == 0 && !it.statusText.isNullOrBlank()
                }
            ) {
                updateLocalLlamaServerStartupStatus(
                    chatId,
                    getString(R.string.native_chat_server_start_still_starting)
                )
            }
            return
        }

        powerLocks.acquire()
        warnIfBatteryOptimizationMayThrottle(chatId)

        Companion.updateState(GenerationState.Generating(chatId = chatId, content = ""))
        refreshNotification()

        val sessionJob = serviceScope.launch(session, start = CoroutineStart.LAZY) {
            var assistantMsgId: Long = -1L
            var managedUsage: LlamaServerUsageLease? = null
            val progress = StreamingProgress()
            // Forced direct-audio / native-video state for the one-shot Whisper / transcript fallback.
            val mediaTurn = MediaTurnState()
            var fallbackStatus: String? = null
            try {
                var server = resolveServerForGeneration(serverId)
                    ?: throw Exception("Server with ID $serverId not found")
                session.usesOllama = server.isOllamaEngine()
                // Plain text/image turns are saved before any wait so a queued message stays visible
                // in the chat; audio and video turns need the server to prepare them first.
                val userTurnSavedEarly = audioPath.isNullOrBlank() && videoPath.isNullOrBlank() &&
                    (!userMessage.isNullOrBlank() || !imagePath.isNullOrBlank())
                if (userTurnSavedEarly) {
                    val savedId = repository.addMessage(chatId = chatId, role = "user", content = userMessage.orEmpty(),
                        imagePath = imagePath?.takeIf { it.isNotBlank() }, audioPath = null, videoPath = null)
                    // Files picked in the composer belong to this message from the moment it is sent.
                    NativeChatAttachmentBinding.bindPending(applicationContext, chatId, savedId)
                }
                // Same-endpoint slots, RAM for a not-yet-running local model and the LiteRT engine
                // are shared resources: wait (Queued) until the scheduler admits this chat.
                scheduler.awaitAdmission(admissionPlanner.targetFor(chatId, server))
                Companion.updateState(GenerationState.Generating(chatId = chatId, content = ""))
                var managedContext: Int? = null
                server.managedServerCardId?.let { cardId ->
                    managedUsage = withContext(Dispatchers.IO) {
                        LlamaServerUsageLease(applicationContext,
                            com.example.llamadroid.data.model.LlamaServerCardEntity.sessionIdForCard(cardId), serviceScope)
                    }
                    val ready = ManagedLlamaServerCoordinator(applicationContext, database).prepare(cardId) {
                        updateLocalLlamaServerStartupStatus(chatId, it)
                    }
                    val profile = requireNotNull(ready.profile)
                    server = server.copy(host = managedLlamaConnectHost(profile.host), port = profile.serverPort,
                        modelName = profile.modelPath.substringAfterLast('/'),
                        localLaunchProfileJson = LlamaServerLaunchProfile.encodeForPersistence(profile),
                        supportsVision = profile.visionEnabled, supportsVideo = profile.videoEnabled)
                    managedContext = ready.contextTokens
                }
                if (server.isLiteRtEngine()) {
                    DebugLog.log("LlamaClientService: Using local LiteRT engine for Chat ID $chatId")
                } else {
                    DebugLog.log("LlamaClientService: Connecting to ${server.baseUrl()} for Chat ID $chatId")
                }
                if (callMode) {
                    DebugLog.log("LlamaClientService: handling call-mode turn for Chat ID $chatId")
                }

                var chat = repository.getChat(chatId) ?: throw Exception("Chat with ID $chatId not found")
                if (server.id > 0) repository.bindChatServerIfUnbound(chatId, server.id)
                // Per-chat audio routing (direct vs Whisper, model, language) overrides the server card.
                val mediaConfig = NativeChatToolConfig.fromApiParams(chat.apiParams)
                val audioRejected = NativeChatMediaFailures.audioRejected(chatId)
                server = server.withChatAudioOverrides(mediaConfig, audioRejected)
                session.videoMode = NativeChatMediaRouting.effectiveVideoMode(
                    mediaConfig.videoInputMode, NativeChatMediaFailures.videoRejected(chatId)
                )
                server = server.withChatVideoOverrides(session.videoMode)
                mediaTurn.audioDirectMode = NativeChatMediaRouting.forcesDirectAudio(mediaConfig.audioInputMode, audioRejected)
                mediaTurn.videoNativeMode = NativeChatMediaRouting.forcesNativeVideo(session.videoMode)
                managedContext?.let { limit ->
                    // The linked server owns the maximum; retain a user's smaller conversation budget.
                    if (chat.contextSize > limit) {
                        repository.updateChatContextSize(chatId, limit)
                        chat = chat.copy(contextSize = limit)
                    }
                }
                // A retry/continuation may not carry a new attachment intent, but replayed
                // history still needs MTMD enabled before the local server is started.
                val historyVideoPath = videoPath
                    ?: repository.getMessages(chatId).first()
                        .asReversed()
                        .firstOrNull { !it.videoPath.isNullOrBlank() }
                        ?.videoPath
                if (!historyVideoPath.isNullOrBlank() &&
                    nativeChatVideoRoute(chatId, server) == NativeChatMediaRouting.VideoRoute.UNSUPPORTED
                ) {
                    throw IllegalStateException(getString(R.string.llama_video_unsupported))
                }
                ensureLocalLlamaServerReadyIfNeeded(
                    chatId = chatId,
                    server = server,
                    imagePath = imagePath,
                    videoPath = historyVideoPath
                )
                // The server answers: its memory is real now, not just reserved by the RAM gate.
                scheduler.markServerUp(chatId)
                // contextSize 0 means "the server's window": resolve it (in memory only) so the
                // context ring and compaction have a real window. llama-server engines only.
                if (chat.contextSize <= 0) {
                    val window = NativeChatContextWindow.resolve(server, chat, managedContext)
                    if (window > 0) chat = chat.copy(contextSize = window)
                }

                val preparedUserTurn = prepareUserTurnForServer(
                    chatId = chatId,
                    server = server,
                    userMessage = userMessage,
                    imagePath = imagePath,
                    audioPath = audioPath,
                    videoPath = videoPath,
                    pretranscribedAudioText = pretranscribedAudioText
                )

                if (preparedUserTurn.shouldPersist() && !preparedUserTurn.alreadyPersisted && !userTurnSavedEarly) {
                    repository.addMessage(
                        chatId = chatId,
                        role = "user",
                        content = preparedUserTurn.content,
                        imagePath = preparedUserTurn.imagePath,
                        audioPath = preparedUserTurn.audioPath,
                        videoPath = preparedUserTurn.videoPath
                    )
                }

                val history = NativeChatTurnDecorators.apply(
                    applicationContext,
                    chatId,
                    applyImageFallbackForServer(
                        chatId = chatId,
                        server = server,
                        messages = prepareHistoryForServer(
                            chatId = chatId,
                            taskId = taskId,
                            server = server,
                            progress = progress
                        )
                    )
                )
                if (history.isEmpty()) throw Exception("Chat history is empty")
                mediaTurn.audioInTurn = server.supportsDirectAudioInput() &&
                    history.lastOrNull { it.role == "user" }?.audioPath?.isNotBlank() == true
                mediaTurn.videoInTurn = history.any { !it.videoPath.isNullOrBlank() }

                val isContinuation = !preparedUserTurn.shouldPersist() && history.last().role == "assistant"
                val assistantPreparation = prepareAssistantMessage(chatId, history, isContinuation)
                assistantMsgId = assistantPreparation.assistantMsgId
                progress.content = assistantPreparation.content
                progress.thinking = assistantPreparation.thinking

                val params = parseChatParams(chat.apiParams)
                val paramEnableThinking = (params["enable_thinking"] as? Boolean) ?: true
                val chatToolConfig = NativeChatToolConfig.fromParams(params)
                // Tool permissions are per chat now; the server card no longer caps them.
                val serverToolDefaults = NativeChatToolConfig.liteRtToolDefaults()
                val toolConfig = chatToolConfig.effectiveWithServerDefaults(serverToolDefaults)
                // Continue is one path: an interrupted tool loop, a reply stopped mid-sentence or a finished
                // reply resumes the tool loop on the same row (llama-server also extends finished replies).
                val resume = if (isContinuation && toolConfig.hasEnabledTools()) {
                    NativeChatStepResume.prepare(
                        assistantPreparation.history.last(),
                        allowComplete = !server.isOllamaEngine() && !server.isLiteRtEngine()
                    )
                } else {
                    null
                }
                val useNativeTools = (!isContinuation || resume != null) && toolConfig.hasEnabledTools()
                var turnHistory = assistantPreparation.history
                if (useNativeTools && resume != null) {
                    // The partial text stays in the live columns (prefilled); the reply's steps replace its history entry.
                    turnHistory = turnHistory.dropLast(1)
                    progress.thinking = resume.message.thinking.orEmpty()
                    progress.stepRecorder = NativeChatStepRecorder(resume.steps)
                    repository.updateMessageStepsAndText(assistantMsgId, progress.content, resume.message.thinking, resume.message.stepsJson)
                }
                // Tool messages only for tool turns on engines that take them; others get flat text.
                if (!useNativeTools || server.isLiteRtEngine()) turnHistory = NativeChatStepHistory.flattenEntities(turnHistory)

                // Per-chat disk KV cache: pins a slot (and restores a saved state) for eligible
                // llama-server chats; every other engine runs with a null slot and no overhead.
                val kvCache = NativeChatKvCacheCoordinator(applicationContext)
                // May restart the local server with slot saving; a managed card can move ports.
                if (kvCache.ensureSlotSaveSupport(server, chat) { updateLocalLlamaServerStartupStatus(chatId, it) }) {
                    server.managedServerCardId?.let { cardId ->
                        ManagedLlamaServerCoordinator(applicationContext, database).prepare(cardId).profile?.let { p ->
                            server = server.copy(host = managedLlamaConnectHost(p.host), port = p.serverPort)
                        }
                    }
                }
                val kvSlot = kvCache.withChatLease(
                    server = server,
                    chat = chat,
                    pinOnly = toolConfig.deepSeekDelegationEnabled,
                    onStatus = { updateLocalLlamaServerStartupStatus(chatId, it) }
                ) { lease ->
                session.kvLease = lease
                try {
                if (useNativeTools) {
                    streamNativeToolResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = turnHistory,
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
                        history = turnHistory,
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
                        history = turnHistory,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        params = params,
                        progress = progress
                    )
                } else {
                    NativeChatCompactionHooks.runPlainTurn(
                        applicationContext, chat, params, server, nativeChatMaxOutputTokens(params),
                        lease, turnHistory
                    ) { compactedHistory ->
                        streamLlamaServerResponse(
                            chatId = chatId,
                            taskId = taskId,
                            chat = chat,
                            server = server,
                            history = compactedHistory,
                            assistantMsgId = assistantMsgId,
                            isContinuation = isContinuation,
                            params = params,
                            progress = progress
                        )
                        progress.promptTokens to progress.completionTokens
                    }
                }
                } catch (e: Exception) {
                    // Stop: save the chat's slot state before the lease is released.
                    if (e is CancellationException || session.stopping.get()) saveSlotAfterStop(lease)
                    throw e
                } finally {
                    session.kvLease = null
                }
                lease.slotId
                }
                // Reached only after a complete reply; cancelled or failed turns throw before here.
                if (kvSlot != null && !session.stopping.get()) kvCache.scheduleSave(server, chat, kvSlot)

                val finalElapsedMs = progress.generationElapsedMs()
                val finalTps = progress.finalTokensPerSecond()
                if (progress.completionTokens == 0) {
                    progress.completionTokens = progress.tokenCount
                }
                if (progress.content.isBlank() && progress.toolEvents.isNotEmpty() && progress.stepRecorder?.hasSteps != true) {
                    DebugLog.log("LlamaClientService: tool loop finished without visible assistant content; persisting localized fallback")
                    progress.content = getString(R.string.llama_tool_empty_final_answer)
                }

                repository.updateMessageTruncatedStatus(assistantMsgId, progress.isTruncated)
                repository.updateMessageStreaming(
                    assistantMsgId,
                    progress.content,
                    progress.thinking.takeIf { it.isNotBlank() },
                    promptTokens = progress.displayPromptTokens(),
                    completionTokens = progress.completionTokens,
                    tps = finalTps,
                    generationTimeMs = finalElapsedMs,
                    stepsJson = progress.stepRecorder?.toJson(complete = true)
                )
                // Ollama/LiteRT turns bypass NativeChatCompactionHooks.Turn; keep the context ring measured.
                if (server.isOllamaEngine() || server.isLiteRtEngine()) {
                    NativeChatContextFallback.recordAfterTurn(
                        applicationContext, chatId, progress.promptTokens, progress.completionTokens,
                        managedContext ?: chat.contextSize
                    )
                }
                NativeChatTurnDecorators.onTurnCompleted(applicationContext, chatId, assistantMsgId)
                com.example.llamadroid.service.titlegen.TitleGenerationManager.onReplyCompleted(applicationContext, chatId)
                persistGeneratedImageMessages(chatId, progress)
                generateAssistantAudioIfEnabled(
                    messageId = assistantMsgId,
                    content = progress.content,
                    thinking = progress.thinking,
                    toolConfig = toolConfig,
                    force = forceAssistantTts
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
                if (e is CancellationException || session.stopping.get() || e.message == "Stopped by user") {
                     DebugLog.log("LlamaClientService: Generation cancelled")
                     if (assistantMsgId != -1L) {
                         val finalElapsedMs = progress.generationElapsedMs()
                         val finalTps = progress.finalTokensPerSecond()
                         progress.stepRecorder?.sealRunning()
                         repository.updateMessageStreaming(
                             assistantMsgId,
                             progress.content,
                             progress.thinking.takeIf { it.isNotBlank() },
                             promptTokens = progress.displayPromptTokens(),
                             completionTokens = if (progress.completionTokens == 0) progress.tokenCount else progress.completionTokens,
                             tps = finalTps,
                             generationTimeMs = finalElapsedMs,
                             stepsJson = progress.stepRecorder?.toJson(complete = false)
                         )
                         persistGeneratedImageMessages(chatId, progress)
                     }
                } else {
                    DebugLog.log("LlamaClientService: Error ${e.message}")
                    progress.stepRecorder?.sealRunning()
                    progress.stepRecorder?.toJsonIfDirty()?.let { repository.updateMessageSteps(assistantMsgId, it) }
                    fallbackStatus = prepareMediaFallback(chatId, serverId, assistantMsgId, mediaTurn, progress, e.message)
                    if (fallbackStatus == null) {
                        Companion.updateState(GenerationState.Error(e.message ?: "Unknown error", chatId))
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { managedUsage?.close() }
                endSession(session, keepForeground = fallbackStatus != null)
            }
            // The server rejected the forced media: rerun the turn once on the Whisper / transcript route.
            fallbackStatus?.let { status ->
                startGeneration(
                    chatId, serverId, null, null, null, null, null, forceAssistantTts, callMode
                )
                updateLocalLlamaServerStartupStatus(chatId, status)
            }
        }
        session.job = sessionJob
        sessionJob.start()
    }

    /** Frees everything a finished (or stopped) session held and starts whoever was waiting for it. */
    private fun endSession(session: ChatGenerationSession, keepForeground: Boolean) {
        stoppingChats.remove(session.chatId)
        sessions.remove(session.chatId, session)
        session.kvLease = null
        scheduler.finish(session.chatId)
        powerLocks.release()
        if (keepForeground) refreshNotification() else releaseForegroundIfIdle()
    }

    private fun stopSession(session: ChatGenerationSession) {
        if (!session.stopping.compareAndSet(false, true)) return
        val chatId = session.chatId
        (NativeChatGenerationStates.stateFor(chatId) as? GenerationState.Generating)?.let { generating ->
            Companion.updateState(generating.copy(statusText = getString(R.string.llama_generation_stopping)))
        }
        stoppingChats.add(chatId)
        session.cancelTransport()
        session.job?.cancel()
        // Ollama's stop switch is process-wide: only use it when no other chat talks to Ollama.
        if (session.usesOllama && sessions.values.none { it !== session && it.usesOllama }) OllamaService.stop()
        session.llamaServerChatService.stopGeneration()
        clearChatState(chatId)
    }

    private fun stopAllSessions() {
        val active = sessions.values.toList()
        active.forEach(::stopSession)
        if (active.isNotEmpty()) OllamaService.stop()
    }

    private class MediaTurnState {
        var audioDirectMode = false
        var audioInTurn = false
        var videoNativeMode = false
        var videoInTurn = false
    }

    private fun nativeChatVideoRoute(chatId: Long, server: LlamaServerEntity): NativeChatMediaRouting.VideoRoute =
        NativeChatMediaRouting.videoRoute(
            sessions[chatId]?.videoMode ?: NativeChatToolConfig.VIDEO_INPUT_SERVER,
            server.supportsNativeVideoInput(),
            videoSettingsRepo.snapshot().processingPolicy.usesLegacyTranscriptRoute()
        )

    /**
     * Remembers a media rejection for the chat and returns the status to show when the turn should be
     * retried on Whisper / the transcript route, or null to surface the original error.
     */
    private suspend fun prepareMediaFallback(
        chatId: Long,
        serverId: Long,
        assistantMsgId: Long,
        turn: MediaTurnState,
        progress: StreamingProgress,
        message: String?
    ): String? {
        val streamed = progress.content.isNotBlank() || progress.completionTokens > 0 || progress.toolEvents.isNotEmpty()
        val audio = NativeChatMediaRouting.shouldFallBack(
            turn.audioDirectMode, turn.audioInTurn, NativeChatMediaFailures.audioRejected(chatId), streamed, message
        )
        val video = !audio && NativeChatMediaRouting.shouldFallBack(
            turn.videoNativeMode, turn.videoInTurn, NativeChatMediaFailures.videoRejected(chatId), streamed, message
        )
        if (!audio && !video) return null
        val server = resolveServerForGeneration(serverId) ?: return null
        if (resolveWhisperModelPath(server) == null) return null
        if (audio) NativeChatMediaFailures.markAudioRejected(chatId) else NativeChatMediaFailures.markVideoRejected(chatId)
        if (assistantMsgId != -1L) {
            repository.getMessagesOnce(chatId).firstOrNull { it.id == assistantMsgId && it.content.isBlank() }
                ?.let { repository.deleteMessage(it) }
        }
        DebugLog.log("LlamaClientService: ${if (audio) "audio" else "video"} rejected by server; retrying via Whisper")
        return getString(if (audio) R.string.native_chat_audio_fallback_status else R.string.native_chat_video_fallback_status)
    }

    private suspend fun ensureLocalLlamaServerReadyIfNeeded(
        chatId: Long,
        server: LlamaServerEntity,
        imagePath: String?,
        videoPath: String?
    ) {
        // Linked cards have already been prepared through the shared keyed-session owner above.
        if (server.managedServerCardId != null) return
        NativeChatLocalServerStarter(applicationContext, settingsRepo, database, chatService(chatId)).ensureReady(
            server,
            NativeChatServerStartRequest(chatId = chatId, imagePath = imagePath, videoPath = videoPath),
            onStatus = { updateLocalLlamaServerStartupStatus(chatId, it) }
        )
    }

    private fun updateLocalLlamaServerStartupStatus(chatId: Long, status: String) {
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = "",
                statusText = status
            )
        )
        notificationTaskId?.let { taskId -> notifyProgress(taskId, 0.05f, status) }
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
        audioPath: String?,
        videoPath: String?,
        pretranscribedAudioText: String? = null
    ): PreparedUserTurn {
        val persistedImagePath = imagePath?.takeIf { it.isNotBlank() }
        val originalAudioPath = audioPath?.takeIf { it.isNotBlank() }
        val persistedVideoPath = videoPath
            ?.takeIf { it.isNotBlank() }
            ?.also(::requireVideoAttachment)
        if (persistedVideoPath != null &&
            nativeChatVideoRoute(chatId, server) == NativeChatMediaRouting.VideoRoute.UNSUPPORTED
        ) {
            throw IllegalStateException(getString(R.string.llama_video_unsupported))
        }
        if (originalAudioPath == null) {
            return PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = null,
                videoPath = persistedVideoPath
            )
        }

        if (server.supportsDirectAudioInput()) {
            if (!pretranscribedAudioText.isNullOrBlank()) {
                DebugLog.log(
                    "LlamaClientService: ignoring pre-transcribed audio text because " +
                        "server=${server.name} supports direct audio input / " +
                        "ignorando texto de audio pretranscrito porque " +
                        "el servidor=${server.name} admite entrada directa de audio"
                )
            }
            val preparedAudioPath = prepareAudioPathForServer(server, originalAudioPath).getOrThrow()
            return PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = preparedAudioPath,
                videoPath = persistedVideoPath
            )
        }

        pretranscribedAudioText?.trim()?.takeIf { it.isNotBlank() }?.let { transcript ->
            return PreparedUserTurn(
                content = mergeUserTextWithAudioTranscript(userMessage.orEmpty(), transcript),
                imagePath = persistedImagePath,
                audioPath = originalAudioPath,
                videoPath = persistedVideoPath
            )
        }

        return run {
            val pendingMessageId = repository.addMessage(
                chatId = chatId,
                role = "user",
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = originalAudioPath,
                videoPath = persistedVideoPath
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
                videoPath = persistedVideoPath,
                alreadyPersisted = true
            )
        }
    }

    /**
     * Servers without vision never receive image parts: the images stay in Room and the chat, and a
     * stored (or freshly generated) vision-helper description is attached as text instead.
     */
    private suspend fun applyImageFallbackForServer(
        chatId: Long,
        server: LlamaServerEntity,
        messages: List<LlamaMessageEntity>
    ): List<LlamaMessageEntity> {
        if (server.supportsVision || messages.none { ImageFallbackLogic.hasImage(it) }) return messages
        val descriptions = VisionDescriptionService.ensureDescriptions(
            applicationContext, chatId, server
        ) { updateLocalLlamaServerStartupStatus(chatId, it) }
        return ImageFallbackLogic.apply(messages, server.supportsVision, descriptions)
    }

    private suspend fun prepareHistoryForServer(
        chatId: Long,
        taskId: Int,
        server: LlamaServerEntity,
        progress: StreamingProgress
    ): List<LlamaMessageEntity> {
        val messages = repository.getMessages(chatId).first().filterNot { it.isError }
        if (nativeChatVideoRoute(chatId, server) == NativeChatMediaRouting.VideoRoute.TRANSCRIPT) {
            val videoMessages = messages.filter { !it.videoPath.isNullOrBlank() }
            if (videoMessages.isNotEmpty()) {
                val whisperModelPath = resolveWhisperModelPath(server)
                    ?: throw IllegalStateException(getString(R.string.llama_video_whisper_unavailable))
                val transcriber = VideoRecognitionWhisperTranscriber(
                    context = applicationContext,
                    runId = System.nanoTime()
                )
                return messages.map { message ->
                    val videoPath = message.videoPath?.takeIf { it.isNotBlank() }
                    if (videoPath == null) {
                        message
                    } else {
                        val transcript = transcriber.transcribe(
                            sourceFile = File(videoPath),
                            request = VideoRecognitionAudioFallbackRequest(
                                whisperModelPath = whisperModelPath,
                                language = server.whisperLanguage.ifBlank {
                                    LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE
                                },
                                threads = settingsRepo.whisperThreads.value,
                                vadConfig = settingsRepo.whisperVadConfigSnapshot(),
                                settingsOverride = null,
                                saveToNotes = false
                            )
                        ) { label, fraction ->
                            publishNativeChatVideoProgress(
                                chatId = chatId,
                                taskId = taskId,
                                progress = progress,
                                visualFraction = fraction,
                                audioFraction = fraction,
                                visualPhase = getString(R.string.video_recognition_mode_legacy),
                                audioPhase = label,
                                audioExpected = true
                            )
                        }.trim().take(NATIVE_CHAT_VIDEO_TRANSCRIPT_MAX_CHARS)
                        if (transcript.isBlank()) {
                            throw IllegalStateException(getString(R.string.llama_video_whisper_unavailable))
                        }
                        message.copy(
                            content = listOf(
                                message.content.trim(),
                                getString(R.string.llama_video_transcript_disclosure, transcript)
                            ).filter { it.isNotBlank() }.joinToString("\n\n"),
                            videoPath = null
                        )
                    }
                }.also {
                    publishNativeChatVideoProgress(
                        chatId = chatId,
                        taskId = taskId,
                        progress = progress,
                        visualFraction = 1f,
                        audioFraction = 1f,
                        visualPhase = getString(R.string.video_recognition_mode_legacy),
                        audioPhase = getString(R.string.video_recognition_audio_complete),
                        audioExpected = true
                    )
                }
            }
        }
        messages.forEach { message ->
            message.videoPath?.takeIf { it.isNotBlank() }?.let(::requireVideoAttachment)
            if (!message.videoPath.isNullOrBlank() &&
                nativeChatVideoRoute(chatId, server) == NativeChatMediaRouting.VideoRoute.UNSUPPORTED
            ) {
                throw IllegalStateException(getString(R.string.llama_video_unsupported))
            }
        }
        return messages.map { message ->
            when {
                server.supportsDirectAudioInput() -> normalizeAudioAttachmentForDirectInput(message, server)
                else -> ensureHistoryTranscript(message, server)
            }
        }
    }

    private suspend fun normalizeAudioAttachmentForDirectInput(
        message: LlamaMessageEntity,
        server: LlamaServerEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message

        val normalizedAudioPath = prepareAudioPathForServer(server, originalAudioPath).getOrThrow()
        if (normalizedAudioPath != originalAudioPath) {
            repository.updateMessageAudioPath(message.id, normalizedAudioPath)
            return message.copy(audioPath = normalizedAudioPath)
        }
        return message
    }

    private suspend fun prepareAudioPathForServer(
        server: LlamaServerEntity,
        audioPath: String
    ): Result<String> = prepareAudioPathForNativeLlama(
        context = applicationContext,
        audioPath = audioPath,
        forcePcmWav = server.isLiteRtEngine()
    )

    private fun requireVideoAttachment(videoPath: String) {
        val videoFile = File(videoPath)
        if (!videoFile.isFile || videoFile.length() <= 0L) {
            throw IllegalStateException(getString(R.string.llama_video_file_missing))
        }
    }

    private suspend fun ensureHistoryTranscript(
        message: LlamaMessageEntity,
        server: LlamaServerEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message
        if (message.role != "user") {
            return message
        }
        if (hasEmbeddedAudioTranscript(message.content)) {
            return message.copy(audioPath = null)
        }

        val transcript = transcribeAudioAttachment(server, originalAudioPath).getOrThrow().text
        val mergedContent = mergeUserTextWithAudioTranscript(message.content, transcript)
        repository.updateMessage(message.id, mergedContent)
        return message.copy(content = mergedContent, audioPath = null)
    }

    private suspend fun generateAssistantAudioIfEnabled(
        messageId: Long,
        content: String,
        thinking: String,
        toolConfig: NativeChatToolConfig,
        force: Boolean = false
    ) {
        if ((!toolConfig.assistantTtsEnabled && !force) || messageId <= 0L) return
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
                    totalSteps = toolConfig.assistantTtsTotalSteps,
                    speed = toolConfig.assistantTtsSpeed,
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

    internal fun nativeChatMaxOutputTokens(params: Map<String, Any>): Int? {
        val enabled = (params[NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED] as? Boolean) ?: false
        if (!enabled) return null
        return (params[NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS] as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
    }

    /** Execute a streaming request without leaving a blocking OkHttp call behind on cancel. */
    private suspend fun executeCancellableNativeChatRequest(
        client: OkHttpClient,
        request: Request
    ): okhttp3.Response {
        val session = currentCoroutineContext()[ChatGenerationSession]
        return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        session?.activeCall = call
        continuation.invokeOnCancellation {
            call.cancel()
            if (session != null && session.activeCall === call) session.activeCall = null
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (session != null && session.activeCall === call) session.activeCall = null
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
        }
    }

    /** Cancels the HTTP calls of the calling coroutine's chat session (other chats keep theirs). */
    private suspend fun cancelActiveNativeChatTransport() {
        currentCoroutineContext()[ChatGenerationSession]?.cancelTransport()
    }

    /** Retrofit's synchronous execute() has the same cancellation hazard as OkHttp execute(). */
    private suspend fun executeCancellableNativeChatRetrofitCall(
        call: retrofit2.Call<ResponseBody>
    ): retrofit2.Response<ResponseBody> {
        val session = currentCoroutineContext()[ChatGenerationSession]
        return suspendCancellableCoroutine { continuation ->
        session?.activeRetrofitCall = call
        continuation.invokeOnCancellation {
            call.cancel()
            if (session != null && session.activeRetrofitCall === call) session.activeRetrofitCall = null
        }
        call.enqueue(object : retrofit2.Callback<ResponseBody> {
            override fun onFailure(call: retrofit2.Call<ResponseBody>, error: Throwable) {
                if (session != null && session.activeRetrofitCall === call) session.activeRetrofitCall = null
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(
                call: retrofit2.Call<ResponseBody>,
                response: retrofit2.Response<ResponseBody>
            ) {
                if (!continuation.isActive) {
                    response.body()?.close()
                    response.errorBody()?.close()
                    return
                }
                continuation.resume(response) { _, value, _ ->
                    value.body()?.close()
                    value.errorBody()?.close()
                }
            }
        })
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
        progress: StreamingProgress,
        videoFallbackAttempted: Boolean = false
    ) {
        val baseUrl = server.baseUrl()
        val videoPreparation = prepareNativeChatVideoPayloads(
            history = history,
            chatId = chatId,
            server = server,
            contextSize = chat.contextSize,
            params = params,
            taskId = taskId,
            progress = progress,
            forceBoundedVideo = videoFallbackAttempted
        )
        try {
        val apiMessages = mutableListOf<LlamaChatMessage>()
        if (!chat.systemPrompt.isNullOrBlank()) {
            apiMessages += LlamaChatMessage("system", chat.systemPrompt)
        }
        var streamedMediaIndex = 0
        apiMessages += history.mapIndexed { index, message ->
            val videoPayload = videoPreparation.payloads.getOrElse(index) {
                NativeChatVideoPayload()
            }
            val videoData = videoPayload.localVideoData ?: if (videoPayload.streamVideo) {
                llamaVideoStreamMarker(streamedMediaIndex++)
            } else {
                null
            }
            val frameData = videoPayload.sampledFramePaths.map {
                llamaVideoStreamMarker(streamedMediaIndex++)
            }
            LlamaChatMessage(
                message.role,
                message.toNativeLlamaContent(
                    videoData = videoData,
                    videoFrameData = frameData,
                    videoSamplingDisclosure = videoPayload.samplingDisclosure,
                    videoObservation = videoPayload.videoObservation,
                    includeVideo = videoPayload.streamVideo || videoPayload.localVideoData != null,
                    additionalAudioPaths = videoPayload.videoAudioPath
                        ?.let(::listOf)
                        .orEmpty()
                )
            )
        }

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

        val baseRequest = LlamaChatRequest(
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
            max_tokens = nativeChatMaxOutputTokens(params),
            chat_template_kwargs = if (isContinuation || (params["enable_thinking"] as? Boolean) == false) {
                mapOf("enable_thinking" to false)
            } else {
                mapOf("enable_thinking" to true)
            },
            idSlot = null,
            cachePrompt = null
        )

        // The slot lock is held for exactly this request/stream (not re-entrant); a direct-video
        // rejection leaves the lock first, then retries below.
        // No base cache on this path: it only runs without tools, so there is no stable prefix.
        // Per-turn extension hints (e.g. delegation reminders) go after the conversation and are
        // never persisted.
        if (!isContinuation) {
            val hintConfig = NativeChatToolConfig.fromParams(params)
                .effectiveWithServerDefaults(NativeChatToolConfig.liteRtToolDefaults())
            history.lastOrNull { it.role == "user" }?.content?.let { latestUserText ->
                turnHintTexts(hintConfig, latestUserText).forEach { apiMessages += LlamaChatMessage("system", it) }
            }
        }
        val retryWithoutDirectVideo = NativeChatModelActivity.track(chatId) { (sessions[chatId]?.kvLease ?: UnpinnedKvSlotLease).request { slot ->
        val request = baseRequest.copy(idSlot = slot, cachePrompt = slot?.let { true })
        DebugLog.log("LlamaClientService: Sending request to $baseUrl")
        showDebugToast("Connecting to $baseUrl...")

        val call = try {
            api.chatCompletion(request)
        } catch (e: Exception) {
            videoPreparation.cleanup()
            showDebugToast("Connection Failed: ${e.message}")
            throw Exception("Failed to connect to $baseUrl: ${e.message}")
        }

        val responseBody: ResponseBody = if (videoPreparation.streamedMediaPaths.isNotEmpty()) {
            val requestBody = try {
                buildStreamingLlamaChatRequestBody(
                    request = request,
                    videoPaths = videoPreparation.streamedMediaPaths
                )
            } catch (error: Throwable) {
                videoPreparation.cleanup()
                throw error
            }
            val requestUrl = baseUrl.trimEnd('/') + "/v1/chat/completions"
            val rawRequest = Request.Builder()
                .url(requestUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(requestBody)
                .build()
            val rawResponse = try {
                executeCancellableNativeChatRequest(client, rawRequest)
            } catch (error: Throwable) {
                videoPreparation.cleanup()
                throw error
            }
            // Keep staged/frame files until the response stream finishes. llama-server may
            // read media lazily after it has returned response headers.
            if (!rawResponse.isSuccessful) {
                val errorBody = rawResponse.body?.string().orEmpty()
                rawResponse.close()
                cancelActiveNativeChatTransport()
                if (!videoFallbackAttempted && videoPreparation.hasDirectVideo) {
                    videoPreparation.cleanup()
                    return@request true
                }
                videoPreparation.cleanup()
                showDebugToast("Server Error ${rawResponse.code}")
                throw Exception("Server Error ${rawResponse.code}: $errorBody")
            }
            rawResponse.body ?: run {
                rawResponse.close()
                cancelActiveNativeChatTransport()
                videoPreparation.cleanup()
                throw Exception("Empty response body from server")
            }
        } else {
            val response = try {
                executeCancellableNativeChatRetrofitCall(call)
            } catch (error: Throwable) {
                videoPreparation.cleanup()
                throw error
            }
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string() ?: ""
                cancelActiveNativeChatTransport()
                if (!videoFallbackAttempted && videoPreparation.hasDirectVideo) {
                    response.errorBody()?.close()
                    videoPreparation.cleanup()
                    return@request true
                }
                showDebugToast("Server Error ${response.code()}")
                videoPreparation.cleanup()
                throw Exception("Server Error ${response.code()}: $errorBody")
            }
            response.body() ?: run {
                cancelActiveNativeChatTransport()
                videoPreparation.cleanup()
                throw Exception("Empty response body from server")
            }
        }

        showDebugToast("Connected! receiving stream...")

        try {
            val source = responseBody.source()
            val reader = BufferedReader(InputStreamReader(source.inputStream()))
            val textAccumulator = LlamaStreamingTextAccumulator(
            parseThinkingTags = !isContinuation,
            initialContent = progress.content,
            initialThinking = progress.thinking
            )
            var lastUpdate = System.currentTimeMillis()
            var firstDeltaReceived = false
            var plainTimings: LlamaChatTimings? = null
            val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
            progress.resetForNewStream()

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
                chunk.timings?.let { plainTimings = it }
                chunk.promptProgress?.takeIf { it.total > 0 }?.let { promptProgress ->
                    progress.promptProcessed = promptProgress.processed.coerceIn(0, promptProgress.total)
                    progress.promptTotal = promptProgress.total
                    progress.promptCached = promptProgress.cached.coerceAtLeast(0)
                    val promptFraction = (
                        progress.promptProcessed.toFloat() / progress.promptTotal.toFloat()
                        ).coerceIn(0f, 1f)
                    progress.promptProgress = promptFraction
                    progress.statusText = getString(
                        R.string.llama_prompt_processing_progress,
                        progress.promptProcessed,
                        progress.promptTotal,
                        (promptFraction * 100f).roundToInt(),
                        progress.promptCached
                    )
                    lastUpdate = updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    )
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

                progress.promptProgress = null
                progress.statusText = null
                if (delta.isNotEmpty() && !firstDeltaReceived && needsSpaceCheck && !delta.first().isWhitespace()) {
                    textAccumulator.appendContent(" ")
                }
                firstDeltaReceived = true
                progress.markFirstTokenReceived()
                textAccumulator.appendContent(delta)
                if (!isContinuation) textAccumulator.appendDedicatedThinking(dedicatedReasoning)

                progress.tokenCount++
                if (shouldPublishStreamingProgress(progress, lastUpdate)) {
                    val snapshot = textAccumulator.snapshot()
                    progress.content = snapshot.content
                    progress.thinking = snapshot.thinking
                    updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    ).also { lastUpdate = it }
                }
                } catch (_: Exception) {
                }
            }
            val completed = textAccumulator.finish()
            progress.content = completed.content
            progress.thinking = completed.thinking
            progress.speed.endRound(plainTimings?.let { DecodeTimings(it.predictedN, it.predictedMs, it.promptN) })
        } finally {
            responseBody.close()
            cancelActiveNativeChatTransport()
            videoPreparation.cleanup()
        }
        false
        } }
        if (retryWithoutDirectVideo) {
            return streamLlamaServerResponse(
                chatId = chatId,
                taskId = taskId,
                chat = chat,
                server = server,
                history = history,
                assistantMsgId = assistantMsgId,
                isContinuation = isContinuation,
                params = params,
                progress = progress,
                videoFallbackAttempted = true
            )
        }
        } finally {
            // Covers failures while building messages, Retrofit calls, or request bodies before
            // a response body exists. The inner finally handles the long-lived stream as well.
            videoPreparation.cleanup()
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

        val textAccumulator = LlamaStreamingTextAccumulator(
            parseThinkingTags = false,
            initialContent = progress.content,
            initialThinking = progress.thinking
        )
        var lastUpdate = System.currentTimeMillis()
        var firstDeltaReceived = false
        val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
        progress.resetForNewStream()

        val result = NativeChatModelActivity.track(chatId) { ollamaService.chatWithToolsStreaming(
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
                        textAccumulator.appendContent(" ")
                    }
                    firstDeltaReceived = true
                    textAccumulator.appendContent(contentDelta)
                }
                if (reasoningDelta.isNotEmpty()) {
                    textAccumulator.appendDedicatedThinking(reasoningDelta)
                }

                progress.markFirstTokenReceived()
                progress.tokenCount++
                progress.lastTokenAtMs = System.currentTimeMillis()
                if (shouldPublishStreamingProgress(progress, lastUpdate)) {
                    val snapshot = textAccumulator.snapshot()
                    progress.content = snapshot.content
                    progress.thinking = snapshot.thinking
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
            }
        ).getOrElse { throw it } }

        val completed = textAccumulator.finish()
        progress.content = completed.content
        progress.thinking = completed.thinking

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

        val baseContent = progress.content
        val baseThinking = progress.thinking
        val textAccumulator = LlamaStreamingTextAccumulator(
            parseThinkingTags = !isContinuation,
            initialContent = baseContent,
            initialThinking = baseThinking
        )
        var lastUpdate = System.currentTimeMillis()
        progress.resetForNewStream()

        val backendMode = normalizeLiteRtBackend(server.liteRtBackend)
        val effectiveChat = chat.withDefaultLiteRtContext(model)
        val request = LiteRtLmChatRequest(
            model = model,
            chat = effectiveChat,
            history = history,
            backendMode = backendMode,
            params = liteRtGalleryParams(params)
        )
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
            notifyProgress(taskId, 0.12f, status)
        }

        suspend fun handleChunk(delta: String) {
            currentCoroutineContext().ensureActive()
            if (delta.isEmpty()) return
            progress.markFirstTokenReceived()
            textAccumulator.appendContent(delta)
            progress.tokenCount++
            progress.lastTokenAtMs = System.currentTimeMillis()
            if (shouldPublishStreamingProgress(progress, lastUpdate)) {
                val snapshot = textAccumulator.snapshot()
                progress.content = repairLiteRtCompactTextForDisplay(snapshot.content)
                progress.thinking = repairLiteRtCompactTextForDisplay(snapshot.thinking)
                progress.tokenCount = estimateLiteRtCompletionTokens(
                    "${progress.content}\n${progress.thinking}"
                ).coerceAtLeast(progress.tokenCount)
                lastUpdate = updateStreamingProgress(
                    chatId = chatId,
                    taskId = taskId,
                    assistantMsgId = assistantMsgId,
                    progress = progress,
                    lastUpdateMs = lastUpdate
                )
            }
        }

        suspend fun handleThinkingChunk(delta: String) {
            currentCoroutineContext().ensureActive()
            if (delta.isEmpty() || isContinuation) return
            progress.markFirstTokenReceived()
            textAccumulator.appendDedicatedThinking(delta)
            progress.tokenCount++
            progress.lastTokenAtMs = System.currentTimeMillis()
            if (shouldPublishStreamingProgress(progress, lastUpdate)) {
                val snapshot = textAccumulator.snapshot()
                progress.content = repairLiteRtCompactTextForDisplay(snapshot.content)
                progress.thinking = repairLiteRtCompactTextForDisplay(snapshot.thinking)
                progress.tokenCount = estimateLiteRtCompletionTokens(
                    "${progress.content}\n${progress.thinking}"
                ).coerceAtLeast(progress.tokenCount)
                lastUpdate = updateStreamingProgress(
                    chatId = chatId,
                    taskId = taskId,
                    assistantMsgId = assistantMsgId,
                    progress = progress,
                    lastUpdateMs = lastUpdate
                )
            }
        }

        fun resetAfterFailedAcceleratorAttempt() {
            textAccumulator.reset(baseContent, baseThinking)
            progress.content = baseContent
            progress.thinking = baseThinking
            progress.resetForNewStream()
            progress.reportedTokensPerSecond = null
            lastUpdate = System.currentTimeMillis()
        }

        val stats = streamLiteRtLmRequestSafely(
            request = request,
            onStatus = ::publishStatus,
            onChunk = ::handleChunk,
            onThinkingChunk = ::handleThinkingChunk,
            onAcceleratorFailureReset = ::resetAfterFailedAcceleratorAttempt
        )

        val completed = textAccumulator.finish()
        progress.content = repairLiteRtCompactTextForDisplay(completed.content)
        progress.thinking = repairLiteRtCompactTextForDisplay(completed.thinking)

        progress.promptTokens = stats.promptTokens
        progress.completionTokens = stats.completionTokens
        progress.tokenCount = stats.completionTokens.coerceAtLeast(progress.tokenCount)
        progress.reportedTokensPerSecond = stats.tokensPerSecond
        if (progress.content.isBlank()) {
            val recovered = recoverLiteRtBlankResponseText(
                stats = stats,
                currentThinking = progress.thinking,
                thinkingEnabled = !isContinuation
            )
            if (recovered.thinking.isNotBlank()) {
                progress.thinking = mergeLiteRtRecoveredThinking(progress.thinking, recovered.thinking)
            }
            if (recovered.visible.isNotBlank()) {
                DebugLog.log(
                    "LlamaClientService: recovered blank LiteRT visible response " +
                        "visibleChars=${stats.visibleText.length} meteredChars=${stats.meteredText.length} " +
                        "thinkingChars=${progress.thinking.length}"
                )
                progress.content = recovered.visible
            }
        }
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
        val customTools = if (effectiveToolConfig.customToolsEnabled) {
            database.customToolDao().getEnabledToolsOnce()
        } else {
            emptyList()
        }
        val tools = nativeChatToolRuntime.availableTools(effectiveToolConfig, customTools)
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
        val knowledgeBaseRoutingGuide = nativeChatToolRuntime.buildKnowledgeBaseSelectionGuidance(effectiveToolConfig)
        // Stable prefix (tool awareness + extension rules, config-only) first, so a prepared base
        // cache matches; per-chat text (routing guide, retrieved context) goes after it.
        val stableBaseText = NativeChatBasePrefix.stableText(
            effectiveToolConfig,
            nativeChatToolRuntime.extensionAwareness(effectiveToolConfig)
        )
        val dynamicSystemBlocks = mutableListOf<String>()
        knowledgeBaseRoutingGuide?.takeIf { it.isNotBlank() }?.let { dynamicSystemBlocks += it }
        var latestTurnKnowledgeContext: String? = null
        history.lastOrNull { it.role == "user" }?.content?.let { latestUserText ->
            nativeChatToolRuntime.buildAutoKnowledgeContext(latestUserText, effectiveToolConfig)?.let { kbContext ->
                sourceCitations += extractNativeChatSourceCitations(kbContext)
                if (chat.persistKvCache) {
                    // Per-turn retrieval would change the system prompt every turn and defeat the
                    // saved KV prefix, so a cached chat carries it on the newest user turn instead.
                    latestTurnKnowledgeContext = kbContext
                } else {
                    dynamicSystemBlocks += kbContext
                }
            }
        }
        // Native tool streaming uses the text-only ChatMessage contract. Resolve video turns
        // through the same selected server first, then inject the bounded observation text so
        // tool-enabled turns retain video context without leaking an Android path or silently
        // dropping the attachment.
        val videoPreparation = if (history.any { !it.videoPath.isNullOrBlank() }) {
            prepareNativeChatVideoPayloads(
                history = history,
                chatId = chatId,
                server = server,
                contextSize = chat.contextSize,
                params = params,
                taskId = taskId,
                progress = progress,
                forceBoundedVideo = true
            )
        } else {
            null
        }
        val toolHistory = history.mapIndexed { index, message ->
            val observation = videoPreparation?.payloads?.getOrNull(index)?.videoObservation
            if (observation.isNullOrBlank()) {
                message
            } else {
                message.copy(
                    content = listOf(message.content.trim(), observation.trim())
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    videoPath = null
                )
            }
        }
        val messages = try {
            buildOllamaMessages(chat, toolHistory, server)
                .let { NativeChatBasePrefix.orderForBase(it, stableBaseText, dynamicSystemBlocks) }
                .withContextOnLatestUserTurn(latestTurnKnowledgeContext)
                .toMutableList()
        } finally {
            videoPreparation?.cleanup()
        }
        val samplingParams = LlamaServerSamplingParams.fromParams(params)
        val maxOutputTokens = nativeChatMaxOutputTokens(params)
        // Context compaction + gauge for llama-server engines (never Ollama/LiteRT).
        val compaction = if (server.isOllamaEngine() || server.isLiteRtEngine()) null else {
            NativeChatCompactionHooks.Turn(
                applicationContext, chat, params, server.baseUrl(), modelName, chat.contextSize,
                maxOutputTokens, tools, sessions[chatId]?.kvLease ?: UnpinnedKvSlotLease
            )
        }
        val historyIds = NativeChatStepHistory.entityIds(toolHistory)
        compaction?.prepare(messages, List<Long?>(messages.size - historyIds.size) { null } + historyIds)
        val resuming = progress.stepRecorder != null
        val steps = NativeChatStepSession(
            progress.stepRecorder ?: NativeChatStepRecorder().also { progress.stepRecorder = it },
            repository,
            assistantMsgId
        )
        // The model-facing messages of the turn are rebuilt from the recorded steps every round, with the
        // same expansion later turns replay, so live and replay are byte-identical. A Continue sends its
        // partial text as the trailing assistant message (prefill) for the first call only.
        val imageEncoder: ((String) -> String?)? =
            if (server.isOllamaEngine()) ({ path: String -> runCatching { fileToBase64(path) }.getOrNull() }) else null
        var prefill: OllamaService.ChatMessage? = if (resuming && progress.content.isNotBlank()) {
            OllamaService.ChatMessage(
                role = "assistant", content = progress.content, thinking = progress.thinking.takeIf { it.isNotBlank() }
            )
        } else {
            null
        }
        fun turnMessages() = NativeChatStepHistory.turnMessages(steps.recorder.snapshot(false).steps, prefill, imageEncoder)

        val textAccumulator = LlamaStreamingTextAccumulator(
            parseThinkingTags = false,
            initialContent = progress.content,
            initialThinking = progress.thinking
        )
        var lastUpdate = System.currentTimeMillis()
        progress.resetForNewStream()
        progress.statusText = null
        val loopState = NativeChatToolLoopState.rebuild(steps.recorder.snapshot(false).steps) { name ->
            maxMutatingToolExecutionsForTurn(name, effectiveToolConfig) != null
        }
        val executedToolSignatures = loopState.executedSignatures
        val completedMutatingToolCounts = loopState.completedMutatingCounts

        fun syncTextAccumulator(force: Boolean = false) {
            if (!force && !shouldPublishStreamingProgress(progress, lastUpdate)) return
            val snapshot = textAccumulator.snapshot()
            progress.content = snapshot.content
            progress.thinking = snapshot.thinking
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

        // One-turn hints from the enabled extensions are sent ONCE, right after the user message, and
        // recorded as a note so every later round and replay keeps them in place.
        if (!resuming) {
            history.lastOrNull { it.role == "user" }?.content?.let { steps.turnHints(turnHintTexts(effectiveToolConfig, it)) }
        }

        suspend fun runModelCall(availableTools: List<AgentTool>): OllamaService.ChatResponse {
            progress.statusText = null
            progress.speed.beginRound()
            // llama.cpp refuses a prefilled (trailing assistant) request while thinking is on.
            val callThinking = thinkingEnabled && prefill == null
            val requestMessages = messages + turnMessages()
            val chunkHandler: (String?, String?) -> Unit = chunkHandler@{ delta, thinkingDelta ->
                val contentDelta = delta.orEmpty()
                val reasoningDelta = thinkingDelta.orEmpty()
                if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
                    return@chunkHandler
                }

                progress.statusText = null
                if (contentDelta.isNotEmpty()) {
                    textAccumulator.appendContent(contentDelta)
                }
                if (reasoningDelta.isNotEmpty()) {
                    textAccumulator.appendDedicatedThinking(reasoningDelta)
                }

                progress.markFirstTokenReceived()
                progress.tokenCount++
                syncTextAccumulator()
            }

            return NativeChatModelActivity.track(chatId) { if (server.isOllamaEngine()) {
                ollamaService.chatWithToolsStreaming(
                    model = modelName ?: "",
                    messages = requestMessages,
                    tools = availableTools,
                    thinkingEnabled = callThinking,
                    numCtxOverride = chat.contextSize.takeIf { it > 0 },
                    onChunk = chunkHandler
                ).getOrElse { throw it }
            } else if (server.isLiteRtEngine()) {
                chatWithLiteRtToolsStreaming(
                    server = server,
                    chat = chat,
                    messages = requestMessages,
                    tools = availableTools,
                    thinkingEnabled = callThinking,
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
                (sessions[chatId]?.kvLease ?: UnpinnedKvSlotLease).request(
                    // The base is built prefix-only by the coordinator; a chat's own state is never saved as one.
                    base = if (server.isLlamaServerEngine() && availableTools.isNotEmpty()) {
                        NativeChatBasePrefix.hint(server.baseUrl(), modelName, stableBaseText, availableTools)
                    } else {
                        null
                    }
                ) { slot ->
                chatService(chatId).chatWithToolsStreaming(
                    baseUrl = server.baseUrl(),
                    messages = requestMessages,
                    tools = availableTools,
                    modelLabel = modelName,
                    thinkingEnabled = callThinking,
                    maxTokens = maxOutputTokens,
                    samplingParams = samplingParams,
                    requestOptions = LlamaServerRequestOptions(slotId = slot, sendReasoningContent = true),
                    onPromptProgress = { promptProgress ->
                        progress.promptProcessed = promptProgress.processed
                        progress.promptTotal = promptProgress.total
                        progress.promptCached = promptProgress.cached
                        progress.promptProgress = promptProgress.fraction
                        progress.statusText = getString(
                            R.string.llama_prompt_processing_progress,
                            promptProgress.processed,
                            promptProgress.total,
                            (promptProgress.fraction * 100f).roundToInt(),
                            promptProgress.cached
                        )
                        runBlocking {
                            lastUpdate = updateStreamingProgress(
                                chatId = chatId,
                                taskId = taskId,
                                assistantMsgId = assistantMsgId,
                                progress = progress,
                                lastUpdateMs = lastUpdate
                            )
                        }
                    },
                    onChunk = chunkHandler
                ).getOrElse { throw it }
                }
            } }
        }

        // One forced compaction + retry when the server rejects the prompt as too long.
        suspend fun runGuardedModelCall(availableTools: List<AgentTool>): OllamaService.ChatResponse =
            try {
                runModelCall(availableTools)
            } catch (error: Exception) {
                if (error is CancellationException || compaction?.onOverflowWithTurn(messages, turnMessages(), error) != true) throw error
                runModelCall(availableTools)
            }

        suspend fun mergeUsage(response: OllamaService.ChatResponse) {
            response.usage?.let { compaction?.onCallUsage(it.promptTokens, it.completionTokens) }
            progress.absorbCallUsage(response.usage)
            prefill = null
        }

        fun appendFallbackContent(response: OllamaService.ChatResponse) {
            val content = response.message.content
            if (content.isNotBlank() && !textAccumulator.rawContent().endsWith(content)) {
                textAccumulator.appendContent(content)
            }
            if (textAccumulator.snapshot().thinking.isBlank() && !response.message.thinking.isNullOrBlank()) {
                textAccumulator.appendDedicatedThinking(response.message.thinking.orEmpty())
            }
            syncTextAccumulator(force = true)
        }

        suspend fun executeToolCallWithRetry(
            toolCall: OllamaService.ToolCall,
            toolActivityBaseId: String
        ): NativeChatToolResult {
            var lastResult: NativeChatToolResult? = null
            repeat(NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS) { attemptIndex ->
                currentCoroutineContext().ensureActive()
                if (attemptIndex > 0) {
                    val retryStatus = getString(
                        R.string.llama_tool_activity_retrying,
                        toolCall.name,
                        attemptIndex + 1,
                        NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS
                    )
                    publishToolActivity(
                        chatId = chatId,
                        taskId = taskId,
                        progress = progress,
                        event = ToolActivityEvent(
                            id = "${toolActivityBaseId}_retry_$attemptIndex",
                            toolName = toolCall.name,
                            status = retryStatus,
                            title = toolCall.arguments["query"] ?: toolCall.arguments["url"] ?: toolCall.arguments["prompt"],
                            isComplete = false
                        )
                    )
                    DebugLog.log(
                        "LlamaClientService: retrying native tool ${toolCall.name} " +
                            "attempt=${attemptIndex + 1}/$NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS"
                    )
                }
                val result = try {
                    nativeChatToolRuntime.executeToolCall(
                        toolCall = toolCall,
                        config = effectiveToolConfig,
                        chatId = chatId,
                        customTools = customTools,
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
                    ).getOrElse { error ->
                        NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                }
                lastResult = result
                // Only transient failures (timeouts, network, busy server) are worth a second run.
                if (!NativeToolRetryPolicy.shouldRetry(result.content)) {
                    return result
                }
                if (attemptIndex + 1 < NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS) {
                    delay(250L)
                }
            }
            val failed = lastResult ?: NativeChatToolResult("tool_error: Tool execution failed.")
            DebugLog.log(
                "LlamaClientService: native tool ${toolCall.name} failed after " +
                    "$NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS attempts: ${failed.content.take(240)}"
            )
            return failed
        }

        repeat(effectiveToolConfig.maxToolRounds) { round ->
            currentCoroutineContext().ensureActive()
            // Out of room for more recorded tool steps: skip to the wrap-up so the transcript stays complete.
            if (steps.recorder.atToolCapacity) return@repeat
            val response = runGuardedModelCall(tools)
            mergeUsage(response)

            val toolCalls = normalizeToolCalls(response.toolCalls.orEmpty(), round)
            if (toolCalls.isEmpty()) {
                appendFallbackContent(response)
                if (progress.content.isBlank() && steps.recorder.hasToolSteps) {
                    DebugLog.log("LlamaClientService: model returned blank answer after tool results; retrying final response with tools disabled engine=${server.normalizedEngine()}")
                    steps.note(round, NativeChatTurnNotes.Note(NativeChatTurnNotes.BLANK_ANSWER_RETRY))
                    val retryResponse = runGuardedModelCall(emptyList())
                    mergeUsage(retryResponse)
                    appendFallbackContent(retryResponse)
                }
                applySourceCitationFallback(progress, sourceCitations)
                progress.statusText = null
                return
            }

            // The round's text and thinking become steps (written together with the cleared live columns).
            val roundSnapshot = textAccumulator.snapshot()
            textAccumulator.reset()
            progress.content = ""
            progress.thinking = ""
            steps.roundEnded(round, roundSnapshot.content, roundSnapshot.thinking)
            lastUpdate = updateStreamingProgress(
                chatId = chatId,
                taskId = taskId,
                assistantMsgId = assistantMsgId,
                progress = progress,
                lastUpdateMs = lastUpdate
            )

            val roundNotes = mutableListOf<NativeChatTurnNotes.Note>()
            for ((toolIndex, rawToolCall) in toolCalls.withIndex()) {
                currentCoroutineContext().ensureActive()
                val toolCall = NativeToolCallPatcher.prepare(
                    toolCall = rawToolCall,
                    messages = messages + turnMessages(),
                    progress = progress
                )
                val toolSignature = NativeChatToolLoopState.signature(toolCall.name, toolCall.arguments)
                if (!executedToolSignatures.add(toolSignature)) {
                    DebugLog.log("LlamaClientService: repeated native tool call after result; steering to final answer tool=${toolCall.name} engine=${server.normalizedEngine()}")
                    steps.toolSkipped(round, toolCall, NativeChatStepSession.SKIPPED_DUPLICATE)
                    roundNotes += NativeChatTurnNotes.duplicateCall(toolCall.name)
                    continue
                }
                val completedMutatingCount = completedMutatingToolCounts[toolCall.name] ?: 0
                val mutatingLimit = maxMutatingToolExecutionsForTurn(toolCall.name, effectiveToolConfig)
                if (mutatingLimit != null && completedMutatingCount >= mutatingLimit) {
                    DebugLog.log(
                        "LlamaClientService: suppressing repeated mutating native tool " +
                            "tool=${toolCall.name} completed=$completedMutatingCount limit=$mutatingLimit engine=${server.normalizedEngine()}"
                    )
                    steps.toolSkipped(round, toolCall, NativeChatStepSession.skippedLimit(toolCall.name))
                    roundNotes += NativeChatTurnNotes.mutatingLimit(toolCall.name)
                    continue
                }
                val toolActivityBaseId = buildToolActivityBaseId(round, toolIndex, toolCall)
                val toolStartedAtMs = System.currentTimeMillis()
                val stepIndex = steps.toolStarted(round, toolCall)
                if (stepIndex < 0) continue // transcript is full: never run a call the model would not see
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
                val toolResult = executeToolCallWithRetry(toolCall, toolActivityBaseId)
                val toolSucceeded = !toolResult.content.startsWith("tool_error:", ignoreCase = true)
                steps.toolFinished(stepIndex, toolSucceeded, toolResult.content, toolStartedAtMs)
                if (toolSucceeded && mutatingLimit != null) {
                    completedMutatingToolCounts[toolCall.name] = completedMutatingCount + 1
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
                if (toolCall.name == NativeChatToolRuntime.TOOL_DEEP_RESEARCH) {
                    roundNotes += NativeChatTurnNotes.Note(NativeChatTurnNotes.DEEP_RESEARCH_DONE)
                }
                toolResult.generatedImagePath
                    ?.takeIf { effectiveToolConfig.imageIterationEnabled && it.isNotBlank() }
                    ?.let { imagePath ->
                        buildGeneratedImageReviewMessage(imagePath, server)?.let { review ->
                            roundNotes += NativeChatTurnNotes.Note(review.content, review.role, review.imagePath)
                        }
                    }
            }
            // App messages of the round follow all its tool messages; the citation reminder lives in the stable system text.
            if (server.isLiteRtEngine()) roundNotes += NativeChatTurnNotes.Note(NativeChatTurnNotes.LITERT_FINALIZE)
            steps.notes(round, roundNotes)
        }

        steps.note(effectiveToolConfig.maxToolRounds, NativeChatTurnNotes.Note(NativeChatTurnNotes.ROUND_LIMIT))
        publishToolStatus(
            chatId = chatId,
            taskId = taskId,
            progress = progress,
            statusText = getString(R.string.llama_tool_status_finalizing)
        )
        val finalResponse = runGuardedModelCall(emptyList())
        mergeUsage(finalResponse)
        appendFallbackContent(finalResponse)
        applySourceCitationFallback(progress, sourceCitations)
        // The progress note becomes the last step; the reply stays resumable so Continue keeps working.
        steps.limitReached(effectiveToolConfig.maxToolRounds, progress.content, getString(R.string.native_chat_step_limit_notice))
        textAccumulator.reset()
        progress.content = ""
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
        val liteRtTools = selectLiteRtToolsForPrompt(messages, tools)
        if (liteRtTools.size < tools.size) {
            DebugLog.log(
                "LlamaClientService: LiteRT tool shortlist exposed=${liteRtTools.size} available=${tools.size}"
            )
        }
        val engineMaxTokens = effectiveLiteRtEngineMaxTokens(
            model = model,
            requestedMaxTokens = chat.contextSize.takeIf { it > 0 }
        ) ?: LITERT_TOOL_PROMPT_FALLBACK_MAX_TOKENS
        val conversation = buildLiteRtToolConversation(
            messages = messages,
            tools = liteRtTools,
            thinkingEnabled = thinkingEnabled,
            maxPromptChars = liteRtToolPromptCharBudget(engineMaxTokens)
        )
        val rendered = StringBuilder()
        val streamedVisible = StringBuilder()
        val stats = streamLiteRtLmConversation(
            server = server,
            chat = chat,
            model = model,
            params = params,
            conversation = conversation,
            onStatus = onStatus,
            onChunk = { chunk ->
                rendered.append(chunk)
                streamedVisible.append(chunk)
                onChunk(chunk, null)
            },
            onThinkingChunk = { chunk ->
                onChunk(null, chunk)
            }
        )
        var recoveredThinking = ""
        if (rendered.isBlank() && stats.toolCalls.isEmpty()) {
            val recovered = recoverLiteRtBlankResponseText(
                stats = stats,
                thinkingEnabled = thinkingEnabled
            )
            recoveredThinking = recovered.thinking
            if (recovered.thinking.isNotBlank()) {
                onChunk(null, recovered.thinking)
            }
            if (recovered.visible.isNotBlank()) {
                DebugLog.log(
                    "LlamaClientService: recovered blank LiteRT tool response " +
                        "visibleChars=${stats.visibleText.length} meteredChars=${stats.meteredText.length} " +
                        "recoveredVisibleChars=${recovered.visible.length} recoveredThinkingChars=${recovered.thinking.length}"
                )
                rendered.append(recovered.visible)
                streamedVisible.append(recovered.visible)
                onChunk(recovered.visible, null)
            }
        }
        val extracted = extractThinkingTags(
            rendered.toString(),
            currentCoroutineContext()[ChatGenerationSession]?.cumulativeDedicatedReasoning.orEmpty()
        )
        val responseThinking = mergeLiteRtRecoveredThinking(recoveredThinking, extracted.second)
        val enabledToolNames = tools.map { it.name }.toSet()
        val exposedToolNames = liteRtTools.map { it.name }.toSet()
        val unsupportedStructuredToolCalls = stats.toolCalls.filter { call -> call.name !in enabledToolNames }
        if (unsupportedStructuredToolCalls.isNotEmpty()) {
            DebugLog.log(
                "LlamaClientService: LiteRT returned unsupported structured tool calls " +
                    "names=${unsupportedStructuredToolCalls.joinToString(",") { it.name }}"
            )
        }
        val structuredToolCalls = stats.toolCalls
            .filter { call -> call.name in enabledToolNames }
        val unshortlistedStructuredToolCalls = structuredToolCalls.filter { call -> call.name !in exposedToolNames }
        if (unshortlistedStructuredToolCalls.isNotEmpty()) {
            DebugLog.log(
                "LlamaClientService: LiteRT accepted enabled tool calls outside shortlist " +
                    "names=${unshortlistedStructuredToolCalls.joinToString(",") { it.name }}"
            )
        }
        val parsed = if (structuredToolCalls.isNotEmpty()) {
            DebugLog.log(
                "LlamaClientService: LiteRT structured tool calls detected count=${structuredToolCalls.size}"
            )
            extracted.first
                .trim()
                .takeIf { it.isNotBlank() && thinkingEnabled }
                ?.let { leakedPlanning -> onChunk(null, leakedPlanning) }
            LiteRtToolParseResult(
                visibleContent = "",
                toolCalls = structuredToolCalls
            )
        } else {
            val fallback = parseLiteRtToolCallsFromText(extracted.first, liteRtTools)
            if (fallback.toolCalls.isNotEmpty()) {
                DebugLog.log(
                    "LlamaClientService: LiteRT degraded text tool-call fallback used count=${fallback.toolCalls.size}"
                )
                fallback.visibleContent
                    .trim()
                    .takeIf { it.isNotBlank() && thinkingEnabled }
                    ?.let { leakedPlanning -> onChunk(null, leakedPlanning) }
                LiteRtToolParseResult(
                    visibleContent = "",
                    toolCalls = fallback.toolCalls
                )
            } else {
                fallback
            }
        }
        return OllamaService.ChatResponse(
            message = OllamaService.ChatMessage(
                role = "assistant",
                content = parsed.visibleContent,
                toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
                thinking = responseThinking.takeIf { thinkingEnabled && it.isNotBlank() }
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

    private suspend fun streamLiteRtLmConversation(
        server: LlamaServerEntity,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        model: LiteRtModelEntity,
        params: Map<String, Any>,
        conversation: LiteRtConversationOverride,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtLmChatStats {
        val backendMode = normalizeLiteRtBackend(server.liteRtBackend)
        val effectiveChat = chat.withDefaultLiteRtContext(model)
        val request = LiteRtLmChatRequest(
            model = model,
            chat = effectiveChat,
            history = emptyList(),
            backendMode = backendMode,
            params = liteRtGalleryParams(params),
            conversationOverride = conversation
        )
        return streamLiteRtLmRequestSafely(
            request = request,
            onStatus = onStatus,
            onChunk = onChunk,
            onThinkingChunk = onThinkingChunk
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
        val effectiveChat = chat.withDefaultLiteRtContext(model)
        val request = LiteRtLmChatRequest(
            model = model,
            chat = effectiveChat,
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

    private fun liteRtGalleryParams(params: Map<String, Any>): Map<String, Any> =
        linkedMapOf<String, Any>(
            "top_k" to ((params["top_k"] as? Number)?.toInt() ?: 40).coerceIn(5, 64),
            "top_p" to ((params["top_p"] as? Number)?.toDouble() ?: 0.95).coerceIn(0.0, 0.95),
            "temperature" to ((params["temperature"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0),
            "enable_thinking" to ((params["enable_thinking"] as? Boolean) ?: false),
            LITERT_PARAM_MTP_ENABLED to ((params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false)
        ).apply {
            nativeChatMaxOutputTokens(params)?.let { outputTokens ->
                this[LITERT_PARAM_MAX_OUTPUT_TOKENS] = outputTokens.coerceAtLeast(1)
            }
        }

    private data class LiteRtRecoveredResponseText(
        val visible: String = "",
        val thinking: String = ""
    )

    private fun recoverLiteRtBlankResponseText(
        stats: LiteRtLmChatStats,
        currentThinking: String = "",
        thinkingEnabled: Boolean = true
    ): LiteRtRecoveredResponseText {
        if (stats.completionTokens <= 0) return LiteRtRecoveredResponseText()
        val candidates = listOf(stats.visibleText, stats.meteredText, currentThinking)
        for (candidate in candidates) {
            val repaired = repairLiteRtCompactTextForDisplay(candidate).trim()
            if (repaired.isBlank()) continue
            if (!thinkingEnabled) return LiteRtRecoveredResponseText(visible = repaired)
            val extracted = splitLiteRtRecoveredOutputText(repaired)
            val visible = repairLiteRtCompactTextForDisplay(extracted.first).trim()
            val thinking = repairLiteRtCompactTextForDisplay(extracted.second).trim()
            if (visible.isNotBlank() || thinking.isNotBlank()) {
                return LiteRtRecoveredResponseText(
                    visible = visible,
                    thinking = thinking
                )
            }
        }
        return LiteRtRecoveredResponseText()
    }

    private fun mergeLiteRtRecoveredThinking(existing: String, recovered: String): String {
        val cleanExisting = existing.trim()
        val cleanRecovered = recovered.trim()
        if (cleanRecovered.isBlank()) return cleanExisting
        if (cleanExisting.isBlank()) return cleanRecovered
        if (cleanExisting.contains(cleanRecovered)) return cleanExisting
        if (cleanRecovered.contains(cleanExisting)) return cleanRecovered
        return "$cleanExisting\n\n$cleanRecovered".trim()
    }

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

        val gpuMtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
        val gpuContextTokens = effectiveLiteRtEngineMaxTokensForBackend(
            model = model,
            requestedMaxTokens = request.chat.contextSize.takeIf { it > 0 },
            backendLabel = "GPU"
        )

        fun purgeGpuCacheForRetry(attempt: Int, crash: LiteRtLmWorkerCrashedException) {
            if (!crash.diedBeforeEngineInitialized) return
            val purged = purgeLiteRtLmEngineCacheDir(
                cacheRoot = applicationContext.cacheDir,
                modelId = model.id,
                backendLabel = "GPU",
                mtpEnabled = gpuMtpEnabled,
                contextTokens = gpuContextTokens
            )
            DebugLog.log(
                "LlamaClientService: purged LiteRT GPU cache after worker startup crash " +
                    "attempt=$attempt success=$purged model=${model.displayName} " +
                    "contextTokens=${gpuContextTokens ?: "default"} mtp=$gpuMtpEnabled " +
                    "lastPhase=${crash.lastPhase}"
            )
        }

        suspend fun runGpuWorker(attempt: Int, maxAttempts: Int): LiteRtLmChatStats {
            DebugLog.log(
                "LlamaClientService: Starting LiteRT GPU worker for ${model.displayName} " +
                    "attempt=$attempt/$maxAttempts contextTokens=${gpuContextTokens ?: "default"} " +
                    "mtp=$gpuMtpEnabled"
            )
            val stats = liteRtLmWorkerClient.streamGpuChat(
                request = request.copy(backendMode = LITERT_BACKEND_GPU),
                onStatus = onStatus,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )
            LiteRtLmAcceleratorHealth.clearGpuCrash(applicationContext, model)
            return stats
        }

        suspend fun runGpuWorkerWithRetry(maxAttempts: Int): LiteRtLmChatStats {
            var attempt = 1
            var lastCrash: LiteRtLmWorkerCrashedException? = null
            while (attempt <= maxAttempts) {
                try {
                    return runGpuWorker(attempt, maxAttempts)
                } catch (e: LiteRtLmWorkerCrashedException) {
                    lastCrash = e
                    DebugLog.log(
                        "LlamaClientService: LiteRT GPU worker crashed " +
                            "attempt=$attempt/$maxAttempts detail=${e.diagnosticDetail()}"
                    )
                    if (attempt >= maxAttempts) break
                    purgeGpuCacheForRetry(attempt, e)
                    attempt += 1
                }
            }
            throw lastCrash ?: IllegalStateException(getString(R.string.litert_error_gpu_worker_crashed))
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
                runGpuWorkerWithRetry(maxAttempts = 3)
            } catch (e: LiteRtLmWorkerCrashedException) {
                val detail = e.diagnosticDetail()
                LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                throw IllegalStateException(
                    getString(R.string.litert_error_explicit_backend_failed, "GPU", detail),
                    e
                )
            } catch (e: Throwable) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                if (detail.contains("corrupt text stream", ignoreCase = true)) {
                    LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                }
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
                    return runGpuWorkerWithRetry(maxAttempts = liteRtAutoGpuWorkerMaxAttempts(model))
                } catch (e: LiteRtLmWorkerCrashedException) {
                    val detail = e.diagnosticDetail()
                    LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                    resetAndReport("GPU", detail)
                } catch (e: Throwable) {
                    val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
                    if (detail.contains("corrupt text stream", ignoreCase = true)) {
                        LiteRtLmAcceleratorHealth.recordGpuCrash(applicationContext, model, detail)
                    }
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

    private fun buildLiteRtToolConversation(
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean,
        maxPromptChars: Int
    ): LiteRtConversationOverride {
        val baseSystemInstruction = buildLiteRtToolSystemInstruction(
            tools = tools,
            thinkingEnabled = thinkingEnabled
        )
        val (selectedMessages, omitted) = liteRtToolConversationMessages(
            messages = messages,
            maxChars = (maxPromptChars - baseSystemInstruction.length)
                .coerceAtLeast(LITERT_TOOL_PROMPT_MIN_MESSAGE_CHARS)
        )
        val extraSystemMessages = selectedMessages
            .filter { it.role == "system" }
            .map { it.content }
        val chatMessages = selectedMessages.filterNot { it.role == "system" }
        val latestUserIndex = chatMessages.indexOfLast { it.role == "user" }
        val initialMessages = if (latestUserIndex >= 0) {
            chatMessages.take(latestUserIndex)
        } else {
            chatMessages
        }.map { it.withLiteRtToolHistoryMediaStripped() }
        val userMessage = if (latestUserIndex >= 0) {
            chatMessages[latestUserIndex].content
        } else {
            "Continue."
        }
        val userImagePath = if (latestUserIndex >= 0) {
            chatMessages[latestUserIndex].imagePath
        } else {
            null
        }
        val userAudioPath = if (latestUserIndex >= 0) {
            chatMessages[latestUserIndex].audioPath
        } else {
            null
        }
        val systemInstruction = buildString {
            append(baseSystemInstruction)
            if (omitted) {
                appendLine()
                appendLine()
                append("[Earlier conversation omitted to fit this LiteRT model context.]")
            }
            extraSystemMessages.forEach { message ->
                appendLine()
                appendLine()
                append(message)
            }
        }
        return LiteRtConversationOverride(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            userMessage = userMessage,
            userImagePath = userImagePath,
            userAudioPath = userAudioPath,
            tools = tools.map { it.toLiteRtToolDefinition() }
        )
    }

    private fun LiteRtConversationMessage.withLiteRtToolHistoryMediaStripped(): LiteRtConversationMessage {
        if (imagePath.isNullOrBlank() && audioPath.isNullOrBlank()) return this
        val mediaNotes = buildList {
            if (!imagePath.isNullOrBlank()) add("[Earlier image attachment omitted from this LiteRT tool follow-up.]")
            if (!audioPath.isNullOrBlank()) add("[Earlier audio attachment omitted from this LiteRT tool follow-up.]")
        }
        val updatedContent = buildString {
            append(content.trim())
            mediaNotes.forEach { note ->
                if (isNotEmpty()) append('\n')
                append(note)
            }
        }.trim()
        return copy(
            content = updatedContent,
            imagePath = null,
            audioPath = null
        )
    }

    private fun selectLiteRtToolsForPrompt(
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>
    ): List<AgentTool> {
        if (tools.size <= LITERT_MAX_STRUCTURED_TOOLS) return tools
        val latestUser = messages
            .asReversed()
            .firstOrNull { it.role == "user" }
            ?.content
            .orEmpty()
            .lowercase()
        val latestUserMessage = messages
            .asReversed()
            .firstOrNull { it.role == "user" }
        val latestUserHasImage = latestUserMessage?.let { message ->
            !message.imagePath.isNullOrBlank() || !message.images.isNullOrEmpty()
        } == true
        val latestUserHasAudio = latestUserMessage?.audioPath.isNullOrBlank().not()
        val wanted = linkedSetOf<String>()

        fun include(vararg names: String) {
            names.forEach { wanted += it }
        }

        when {
            latestUser.containsAny(
                "deep research",
                "research",
                "investigate",
                "investiga",
                "investigar",
                "investigación",
                "investigacion",
                "recherche"
            ) -> include(
                NativeChatToolRuntime.TOOL_DEEP_RESEARCH,
                NativeChatToolRuntime.TOOL_WEB_SEARCH,
                NativeChatToolRuntime.TOOL_FETCH_URL,
                NativeChatToolRuntime.TOOL_SEARCH_PAGE,
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH,
                NativeChatToolRuntime.TOOL_KB_SEARCH,
                NativeChatToolRuntime.TOOL_KB_READ_SOURCE
            )
            latestUser.containsAny(
                "web",
                "search",
                "latest",
                "news",
                "busca",
                "buscar",
                "búsqueda",
                "busqueda",
                "noticia",
                "noticias",
                "último",
                "ultimo",
                "reciente"
            ) -> include(
                NativeChatToolRuntime.TOOL_WEB_SEARCH,
                NativeChatToolRuntime.TOOL_FETCH_URL,
                NativeChatToolRuntime.TOOL_SEARCH_PAGE
            )
        }
        if (latestUser.containsAny("kb", "knowledge", "document", "documento", "pdf", "archivo", "fichero")) {
            include(
                NativeChatToolRuntime.TOOL_KB_SEARCH,
                NativeChatToolRuntime.TOOL_KB_READ_CHUNK,
                NativeChatToolRuntime.TOOL_KB_READ_SOURCE,
                NativeChatToolRuntime.TOOL_KB_LIST_SOURCES
            )
        }
        if (latestUser.containsAny(
                "note",
                "notes",
                "nota",
                "notas",
                "todo",
                "task",
                "tarea",
                "tareas",
                "lista",
                "compra",
                "compras",
                "shopping",
                "añade",
                "anade",
                "agrega",
                "guarda",
                "guardar",
                "save this",
                "add this"
            )
        ) {
            include(
                NativeChatToolRuntime.TOOL_LIST_NOTES,
                NativeChatToolRuntime.TOOL_READ_NOTE,
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                NativeChatToolRuntime.TOOL_UPDATE_NOTE,
                NativeChatToolRuntime.TOOL_CREATE_TODO_LIST,
                NativeChatToolRuntime.TOOL_ADD_TODO_ITEM
            )
        }
        if (latestUser.containsAny("calendar", "event", "schedule", "calendario", "evento", "agenda", "programa")) {
            include(
                NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS,
                NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT,
                NativeChatToolRuntime.TOOL_UPDATE_CALENDAR_EVENT
            )
        }
        if (latestUser.containsAny("alarm", "remind", "timer", "alarma", "recordatorio", "recuerda", "temporizador")) {
            include(
                NativeChatToolRuntime.TOOL_LIST_ALARMS,
                NativeChatToolRuntime.TOOL_CREATE_ALARM,
                NativeChatToolRuntime.TOOL_UPDATE_ALARM
            )
        }
        if (latestUser.containsAny(
                "image",
                "photo",
                "picture",
                "background",
                "imagen",
                "foto",
                "fotografía",
                "fotografia",
                "dibuja",
                "genera",
                "generar",
                "fondo"
            )
        ) {
            include(
                NativeChatToolRuntime.TOOL_GENERATE_IMAGE,
                NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND
            )
        }
        if (latestUserHasAudio) {
            include(
                NativeChatToolRuntime.TOOL_GENERATE_IMAGE,
                NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND,
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                NativeChatToolRuntime.TOOL_UPDATE_NOTE,
                NativeChatToolRuntime.TOOL_CREATE_TODO_LIST,
                NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT,
                NativeChatToolRuntime.TOOL_CREATE_ALARM
            )
        }
        if (latestUser.any(Char::isDigit) || latestUser.containsAny("calculate", "math", "calcula", "calcular", "matemática", "matematica")) {
            include(NativeChatToolRuntime.TOOL_CALCULATOR)
        }
        if (latestUser.containsAny("date", "time", "today", "now", "fecha", "hora", "hoy", "ahora")) {
            include(NativeChatToolRuntime.TOOL_GET_DATETIME)
        }
        if (latestUserHasImage && wanted.isNotEmpty()) {
            include(
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                NativeChatToolRuntime.TOOL_UPDATE_NOTE
            )
        }

        val byName = tools.associateBy { it.name }
        val selected = tools.filter { tool ->
            tool.name in setOf(
                NativeChatToolRuntime.TOOL_LIST_WORKSPACE_FILES,
                NativeChatToolRuntime.TOOL_READ_WORKSPACE_FILE,
                NativeChatToolRuntime.TOOL_WRITE_WORKSPACE_FILE,
                NativeChatToolRuntime.TOOL_APPEND_WORKSPACE_FILE,
                NativeChatToolRuntime.TOOL_EDIT_WORKSPACE_LINES,
                NativeChatToolRuntime.TOOL_APPLY_WORKSPACE_PATCH
            ) || tool.description.contains("Local curl-style API tool enabled by the user.")
        }.toMutableList()
        wanted.mapNotNullTo(selected) { name ->
            byName[name]?.takeIf { tool -> selected.none { it.name == tool.name } }
        }
        val fallbackOrder = listOf(
            NativeChatToolRuntime.TOOL_WEB_SEARCH,
            NativeChatToolRuntime.TOOL_FETCH_URL,
            NativeChatToolRuntime.TOOL_KB_SEARCH,
            NativeChatToolRuntime.TOOL_KB_READ_SOURCE,
            NativeChatToolRuntime.TOOL_DEEP_RESEARCH,
            NativeChatToolRuntime.TOOL_GET_DATETIME,
            NativeChatToolRuntime.TOOL_CALCULATOR,
            NativeChatToolRuntime.TOOL_GENERATE_IMAGE,
            NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND
        )
        fallbackOrder.mapNotNullTo(selected) { name ->
            byName[name]?.takeIf { tool -> selected.none { it.name == tool.name } }
        }
        if (selected.isEmpty()) {
            selected += tools.take(LITERT_MAX_STRUCTURED_TOOLS)
        }
        // The delegation tools are only present when enabled for the chat; never shortlist them out.
        return (tools.filter { it.name.startsWith("deepseek_") } + selected)
            .distinctBy { it.name }
            .take(LITERT_MAX_STRUCTURED_TOOLS)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { needle -> needle in this }

    private fun buildLiteRtToolSystemInstruction(
        tools: List<AgentTool>,
        thinkingEnabled: Boolean
    ): String = buildString {
        appendLine("You are running in the app's local LiteRT chat engine.")
        appendLine("You are enclosed in the app runtime. Use only the native tools explicitly listed for this turn; do not assume shell access, arbitrary files, network, Android APIs, or external services unless a listed tool provides them.")
        appendLine("Answer in the user's language. Use readable Markdown with blank lines before numbered lists.")
        appendLine("Write normal text with normal spaces between words. Never concatenate words in the final answer.")
        if (thinkingEnabled) {
            appendLine("Do not write analysis, reasoning, or a Thinking Process section in the final answer.")
        } else {
            appendLine("Do not output a thinking block.")
        }
        if (tools.isEmpty()) {
            appendLine("No app tools are available in this turn. Answer now without calling tools.")
        } else {
            appendLine("App tools are available through the native LiteRT tool-calling channel. Call a tool only when it is needed, then answer normally after the app returns the tool result.")
            appendLine("Available native tool names: ${tools.joinToString(", ") { it.name }}")
            appendLine("If the latest user turn includes attached audio, listen to that audio as the user's request; do not say no audio was attached.")
            if (tools.any { it.name == NativeChatToolRuntime.TOOL_GENERATE_IMAGE }) {
                appendLine("If the user asks to generate, create, draw, or make an image, call generate_image. Do not claim you cannot generate images; the app performs image generation through that tool.")
            }
            if (tools.any { it.name == NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND }) {
                appendLine("When calling remove_image_background, pass an existing real image path. Reuse the exact path from the latest image attachment or prior tool result image_path. Never invent placeholders such as image_from_user_input or image_path_to_the_provided_image.")
            }
            appendLine("If the native tool channel is unavailable, use exactly one compact fallback block and no other text in that assistant turn:")
            appendLine("<tool_call>{\"name\":\"tool_name\",\"arguments\":{\"param\":\"value\"}}</tool_call>")
        }
    }.trim()

    private fun AgentTool.toLiteRtToolDefinition(): LiteRtToolDefinition =
        LiteRtToolDefinition(
            name = name,
            description = description.trim().ellipsizeLiteRtPromptContent(300),
            parameters = parameters.mapValues { (_, description) ->
                description.trim().ellipsizeLiteRtPromptContent(180)
            },
            requiredParams = requiredParams
        )

    private fun liteRtToolConversationMessages(
        messages: List<OllamaService.ChatMessage>,
        maxChars: Int
    ): Pair<List<LiteRtConversationMessage>, Boolean> {
        if (messages.isEmpty()) return emptyList<LiteRtConversationMessage>() to false
        val selected = mutableListOf<LiteRtConversationMessage>()
        var remaining = maxChars
        var omitted = false
        for (message in messages.asReversed()) {
            val conversationMessage = buildLiteRtToolConversationMessage(
                message = message,
                toolName = if (message.role == "tool") {
                    findLiteRtToolNameForResult(messages, message)
                } else {
                    null
                }
            ) ?: continue
            val size = conversationMessage.role.length + conversationMessage.content.length + 8
            val selectedMessage = if (size <= remaining) {
                conversationMessage
            } else if (selected.isEmpty() && remaining > LITERT_TOOL_PROMPT_MIN_MESSAGE_CHARS / 2) {
                conversationMessage.copy(
                    content = conversationMessage.content.ellipsizeLiteRtPromptContent(
                        (remaining - conversationMessage.role.length - 8).coerceAtLeast(256)
                    )
                )
            } else {
                omitted = true
                break
            }
            selected += selectedMessage
            remaining -= selectedMessage.role.length + selectedMessage.content.length + 8
            if (remaining <= 0) {
                omitted = true
                break
            }
        }
        if (selected.size < messages.count { it.hasLiteRtToolConversationPayload() }) {
            omitted = true
        }
        return selected.asReversed() to omitted
    }

    private fun buildLiteRtToolConversationMessage(
        message: OllamaService.ChatMessage,
        toolName: String? = null,
        contentMaxChars: Int = when (message.role) {
            "tool" -> LITERT_TOOL_PROMPT_TOOL_RESULT_CHARS
            "system" -> LITERT_TOOL_PROMPT_SYSTEM_CHARS
            else -> LITERT_TOOL_PROMPT_MESSAGE_CHARS
        }
    ): LiteRtConversationMessage? {
        val role = when (message.role) {
            "assistant" -> "assistant"
            "system" -> "system"
            "tool" -> "tool"
            else -> "user"
        }
        val content = when (message.role) {
            "assistant" -> {
                message.content.trim()
            }
            "tool" -> message.content.trim()
            else -> message.content.trim()
        }
            .ifBlank {
                liteRtMediaOnlyUserPrompt(
                    imagePath = message.imagePath,
                    audioPath = message.audioPath
                )
            }
            .ellipsizeLiteRtPromptContent(contentMaxChars)
        if (content.isBlank() && !message.hasLiteRtToolConversationPayload()) return null
        return LiteRtConversationMessage(
            role = role,
            content = content,
            imagePath = message.imagePath,
            audioPath = message.audioPath,
            toolCalls = message.toolCalls
                .orEmpty()
                .map { call ->
                    LiteRtToolCallSpec(
                        name = call.name,
                        arguments = call.arguments.mapValues { it.value }
                    )
                },
            toolName = toolName
        )
    }

    private fun OllamaService.ChatMessage.hasLiteRtToolConversationPayload(): Boolean =
        content.isNotBlank() ||
            !toolCalls.isNullOrEmpty() ||
            !imagePath.isNullOrBlank() ||
            !audioPath.isNullOrBlank()

    private fun liteRtMediaOnlyUserPrompt(
        imagePath: String?,
        audioPath: String?
    ): String = when {
        !imagePath.isNullOrBlank() && !audioPath.isNullOrBlank() ->
            "Use the attached image and audio to answer."
        !imagePath.isNullOrBlank() ->
            "Use the attached image to answer."
        !audioPath.isNullOrBlank() ->
            "Use the attached audio to answer."
        else -> ""
    }

    private fun findLiteRtToolNameForResult(
        messages: List<OllamaService.ChatMessage>,
        toolResult: OllamaService.ChatMessage
    ): String? {
        val resultIndex = messages.indexOf(toolResult).takeIf { it >= 0 } ?: messages.size
        val id = toolResult.toolCallId?.takeIf { it.isNotBlank() }
        return messages
            .take(resultIndex)
            .asReversed()
            .asSequence()
            .flatMap { it.toolCalls.orEmpty().asSequence() }
            .firstOrNull { call -> id == null || call.id == id }
            ?.name
    }

    private fun buildLiteRtToolPrompt(
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean,
        maxPromptChars: Int
    ): String {
        val header = buildString {
        appendLine("System:")
        appendLine("You are running in the app's local LiteRT chat engine.")
        appendLine("You are enclosed in the app runtime. Use only the native tools explicitly listed for this turn; do not assume shell access, arbitrary files, network, Android APIs, or external services unless a listed tool provides them.")
        appendLine("Answer in the user's language. Use readable Markdown with blank lines before numbered lists.")
        if (thinkingEnabled) {
            appendLine("Do not write analysis, reasoning, or a Thinking Process section in the final answer.")
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
        }
        val footer = "Assistant:\n"
        val messageBudget = (maxPromptChars - header.length - footer.length)
            .coerceAtLeast(LITERT_TOOL_PROMPT_MIN_MESSAGE_CHARS)
        val (messageBlocks, omitted) = liteRtToolPromptMessageBlocks(messages, messageBudget)
        return buildString {
            append(header)
            if (omitted) {
                appendLine("[Earlier conversation omitted to fit this LiteRT model context.]")
                appendLine()
            }
            messageBlocks.forEach { append(it) }
            append(footer)
        }.take(maxPromptChars)
    }

    private fun liteRtToolPromptMessageBlocks(
        messages: List<OllamaService.ChatMessage>,
        maxChars: Int
    ): Pair<List<String>, Boolean> {
        if (messages.isEmpty()) return emptyList<String>() to false
        val selected = mutableListOf<String>()
        var remaining = maxChars
        var omitted = false
        for (message in messages.asReversed()) {
            val block = buildLiteRtToolPromptMessageBlock(message)
            val selectedBlock = if (block.length <= remaining) {
                block
            } else if (selected.isEmpty() && remaining > LITERT_TOOL_PROMPT_MIN_MESSAGE_CHARS / 2) {
                buildLiteRtToolPromptMessageBlock(
                    message = message,
                    contentMaxChars = (remaining - 96).coerceAtLeast(256)
                ).take(remaining)
            } else {
                omitted = true
                break
            }
            selected += selectedBlock
            remaining -= selectedBlock.length
            if (remaining <= 0) {
                omitted = true
                break
            }
        }
        if (selected.size < messages.size) omitted = true
        return selected.asReversed() to omitted
    }

    private fun buildLiteRtToolPromptMessageBlock(
        message: OllamaService.ChatMessage,
        contentMaxChars: Int = when (message.role) {
            "tool" -> LITERT_TOOL_PROMPT_TOOL_RESULT_CHARS
            "system" -> LITERT_TOOL_PROMPT_SYSTEM_CHARS
            else -> LITERT_TOOL_PROMPT_MESSAGE_CHARS
        }
    ): String = buildString {
        when (message.role) {
            "system" -> {
                appendLine("System:")
                appendLine(message.content.trim().ellipsizeLiteRtPromptContent(contentMaxChars))
            }
            "assistant" -> {
                appendLine("Assistant:")
                if (!message.toolCalls.isNullOrEmpty()) {
                    message.toolCalls.forEach { toolCall ->
                        appendLine(buildLiteRtToolCallBlock(toolCall))
                    }
                } else {
                    appendLine(message.content.trim().ellipsizeLiteRtPromptContent(contentMaxChars))
                }
            }
            "tool" -> {
                appendLine("Tool result${message.toolCallId?.let { " ($it)" }.orEmpty()}:")
                appendLine(message.content.trim().ellipsizeLiteRtPromptContent(contentMaxChars))
            }
            else -> {
                appendLine("User:")
                appendLine(message.content.trim().ellipsizeLiteRtPromptContent(contentMaxChars))
            }
        }
        appendLine()
    }

    private fun liteRtToolPromptCharBudget(engineMaxTokens: Int): Int =
        (engineMaxTokens * LITERT_TOOL_PROMPT_CHARS_PER_TOKEN)
            .toInt()
            .coerceIn(LITERT_TOOL_PROMPT_MIN_CHARS, LITERT_TOOL_PROMPT_MAX_CHARS)

    private fun String.ellipsizeLiteRtPromptContent(maxChars: Int): String {
        if (length <= maxChars) return this
        val marker = "\n[...truncated for LiteRT context...]\n"
        val head = ((maxChars - marker.length) * 0.65f).toInt().coerceAtLeast(1)
        val tail = (maxChars - marker.length - head).coerceAtLeast(1)
        return take(head).trimEnd() + marker + takeLast(tail).trimStart()
    }

    private fun buildLiteRtToolSchemaJson(tools: List<AgentTool>): String =
        JSONArray().apply {
            tools.forEach { tool ->
                put(
                    JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description.trim().ellipsizeLiteRtPromptContent(220))
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
                                                    put("description", description.trim().ellipsizeLiteRtPromptContent(140))
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
        }.toString()

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
        val extracted = extractLiteRtToolCallPayloads(text)
        extracted.payloads.forEach { payload ->
            toolCalls += parseLiteRtToolCallPayload(payload, availableToolNames)
        }
        var visibleContent = extracted.visibleContent

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
            val chatService = currentCoroutineContext()[ChatGenerationSession]?.llamaServerChatService ?: fallbackChatService
            // A slot that never disturbs a chat's in-turn cache (spare slot, or swap-save on a 1-slot server).
            NativeChatKvCacheCoordinator(applicationContext).withUtilitySlot(server.baseUrl(), server) { slotId ->
                chatService.chatWithToolsStreaming(
                    baseUrl = server.baseUrl(), messages = messages, tools = emptyList(), modelLabel = modelName,
                    thinkingEnabled = false, maxTokens = NATIVE_SEARCH_SUMMARY_MAX_TOKENS,
                    samplingParams = LlamaServerSamplingParams(temperature = 0.2f, topP = 0.9f),
                    requestOptions = LlamaServerRequestOptions(slotId = slotId)
                ).getOrElse { throw it }
            }
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
            val steps = if (message.role == "assistant") NativeChatStepHistory.decode(message.stepsJson) else null
            if (steps != null) {
                // Reasoning is replayed for every reply; the chat template decides what to render, so
                // templates that keep it reuse the cached prefix across turns.
                val encodeImage: ((String) -> String?)? =
                    if (server.isOllamaEngine()) ({ path: String -> runCatching { fileToBase64(path) }.getOrNull() }) else null
                messages += NativeChatStepHistory.expand(message.content, steps, true, encodeImage, message.thinking)
                return@forEach
            }
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
                // Past reasoning is replayed; the template decides whether to render it.
                thinking = message.thinking?.takeIf { message.role == "assistant" && it.isNotBlank() }
            )
        }
        return messages
    }

    private fun List<OllamaService.ChatMessage>.withContextOnLatestUserTurn(
        context: String?
    ): List<OllamaService.ChatMessage> {
        if (context.isNullOrBlank()) return this
        val index = indexOfLast { it.role == "user" }
        if (index < 0) return this
        return toMutableList().also { messages ->
            val message = messages[index]
            messages[index] = message.copy(
                content = listOf(context.trim(), message.content.trim())
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )
        }
    }

    private suspend fun updateStreamingProgress(
        chatId: Long,
        taskId: Int,
        assistantMsgId: Long,
        progress: StreamingProgress,
        lastUpdateMs: Long
    ): Long {
        val nowMs = System.currentTimeMillis()
        val tps = progress.liveTokensPerSecond(nowMs)
        maybeRecordPowerDiagnostics(progress)

        if (nowMs - progress.lastStateUpdateMs >= STREAMING_STATE_UPDATE_INTERVAL_MS) {
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = progress.content,
                    thinking = progress.thinking.takeIf { it.isNotBlank() },
                    tokenCount = progress.tokenCount,
                    tokensPerSecond = tps,
                    statusText = progress.statusText,
                    promptProgress = progress.promptProgress,
                    promptProcessed = progress.promptProcessed,
                    promptTotal = progress.promptTotal,
                    promptCached = progress.promptCached,
                    videoProgress = progress.videoProgress,
                    videoVisualProgress = progress.videoVisualProgress,
                    videoAudioProgress = progress.videoAudioProgress,
                    videoVisualPhase = progress.videoVisualPhase,
                    videoAudioPhase = progress.videoAudioPhase,
                    toolEvents = progress.toolEvents.toList()
                )
            )
            progress.lastStateUpdateMs = nowMs
        }

        if (nowMs - lastUpdateMs <= STREAMING_PERSIST_INTERVAL_MS) {
            return lastUpdateMs
        }

        val currentElapsedMs = progress.generationElapsedMs()
        repository.updateMessageStreaming(
            assistantMsgId,
            progress.content,
            progress.thinking.takeIf { it.isNotBlank() },
            promptTokens = progress.displayPromptTokens(),
            completionTokens = progress.completionTokens,
            tps = tps,
            generationTimeMs = currentElapsedMs,
            stepsJson = progress.stepRecorder?.toJsonIfDirty()
        )
        val progressContext = if (progress.thinking.isNotBlank() && progress.content.isBlank()) {
            progress.thinking
        } else {
            progress.content
        }
        val words = progressContext.takeLast(512).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lastWords = words.takeLast(7).joinToString(" ")
        val progressText = if (lastWords.isBlank()) {
            "%d tok · %.1f t/s".format(progress.tokenCount, tps)
        } else {
            "%d tok · %.1f t/s | ...$lastWords".format(progress.tokenCount, tps)
        }
        notifyProgress(taskId, 0.5f, progressText)
        return System.currentTimeMillis()
    }

    /**
     * Publish bounded preparation state while Native Chat is still assembling its request.
     * The visual and audio branches report independently; the overall value is a weighted
     * fraction so a parallel Whisper process cannot make a long visual run appear complete.
     */
    internal fun publishNativeChatVideoProgress(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        visualFraction: Float,
        audioFraction: Float,
        visualPhase: String? = null,
        audioPhase: String? = null,
        audioExpected: Boolean
    ) {
        val visual = visualFraction.coerceIn(0f, 1f)
        val audio = audioFraction.coerceIn(0f, 1f)
        val overall = if (audioExpected) {
            (visual * 0.75f + audio * 0.25f).coerceIn(0f, 1f)
        } else {
            visual
        }
        progress.videoProgress = overall
        progress.videoVisualProgress = visual
        progress.videoAudioProgress = audio
        visualPhase?.trim()?.takeIf { it.isNotBlank() }?.let {
            progress.videoVisualPhase = it.take(NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS)
        }
        audioPhase?.trim()?.takeIf { it.isNotBlank() }?.let {
            progress.videoAudioPhase = it.take(NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS)
        }
        progress.statusText = progress.videoVisualPhase ?: progress.videoAudioPhase
        val current = NativeChatGenerationStates.stateFor(chatId) as? GenerationState.Generating
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = progress.reportedTokensPerSecond ?: 0.0,
                statusText = progress.statusText,
                promptProgress = progress.promptProgress,
                promptProcessed = progress.promptProcessed,
                promptTotal = progress.promptTotal,
                promptCached = progress.promptCached,
                videoProgress = progress.videoProgress,
                videoVisualProgress = progress.videoVisualProgress,
                videoAudioProgress = progress.videoAudioProgress,
                videoVisualPhase = progress.videoVisualPhase,
                videoAudioPhase = progress.videoAudioPhase,
                toolEvents = current?.toolEvents ?: progress.toolEvents.toList()
            )
        )
        notifyProgress(
            taskId,
            (0.05f + overall * 0.4f).coerceIn(0.05f, 0.45f),
            progress.statusText ?: getString(R.string.video_recognition_progress_preparing)
        )
    }

    private fun shouldPublishStreamingProgress(
        progress: StreamingProgress,
        lastPersistMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean =
        nowMs - progress.lastStateUpdateMs >= STREAMING_STATE_UPDATE_INTERVAL_MS ||
            nowMs - lastPersistMs > STREAMING_PERSIST_INTERVAL_MS

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
                videoProgress = progress.videoProgress,
                videoVisualProgress = progress.videoVisualProgress,
                videoAudioProgress = progress.videoAudioProgress,
                videoVisualPhase = progress.videoVisualPhase,
                videoAudioPhase = progress.videoAudioPhase,
                toolEvents = progress.toolEvents.toList()
            )
        )
        notifyProgress(taskId, 0.45f, statusText)
    }

    private fun publishToolActivity(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        event: ToolActivityEvent
    ) {
        val uniqueEvent = event.withUniqueToolActivityId(progress.toolEvents)
        progress.toolEvents += uniqueEvent
        if (progress.toolEvents.size > MAX_TOOL_ACTIVITY_EVENTS) {
            progress.toolEvents.removeAt(0)
        }
        progress.statusText = uniqueEvent.status
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = 0.0,
                statusText = uniqueEvent.status,
                toolEvents = progress.toolEvents.toList()
            )
        )
        notifyProgress(taskId, 0.45f, uniqueEvent.status)
    }

    private fun buildToolActivityBaseId(
        round: Int,
        toolIndex: Int,
        toolCall: OllamaService.ToolCall
    ): String {
        val toolCallId = toolCall.id?.takeIf { it.isNotBlank() } ?: System.nanoTime().toString()
        return "tool_${round}_${toolIndex}_$toolCallId"
    }

    private fun ToolActivityEvent.withUniqueToolActivityId(
        existingEvents: List<ToolActivityEvent>
    ): ToolActivityEvent {
        if (existingEvents.none { it.id == id }) {
            return this
        }
        val existingIds = existingEvents.mapTo(mutableSetOf()) { it.id }
        var suffix = 2
        var candidate = "${id}_$suffix"
        while (candidate in existingIds) {
            suffix += 1
            candidate = "${id}_$suffix"
        }
        return copy(id = candidate)
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
            NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND -> getString(R.string.llama_tool_status_bgr)
            else -> nativeChatToolRuntime.extensionFor(toolCall.name)?.statusText(toolCall.name)
                ?: getString(R.string.llama_tool_status_running)
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
                threads = settingsRepo.whisperThreads.value,
                purpose = WhisperInvocationPurpose.AUDIO_ATTACHMENT,
                vad = settingsRepo.whisperVadConfigSnapshot()
            )
        )
    }

    internal suspend fun resolveWhisperModelPath(server: LlamaServerEntity): String? {
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

    /** Non-empty one-turn hints of the enabled tool extensions for [latestUserText]. */
    private fun turnHintTexts(config: NativeChatToolConfig, latestUserText: String): List<String> =
        toolExtensions.filter { it.isEnabled(config) }.mapNotNull { extension ->
            runCatching { extension.turnHint(config, latestUserText) }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private fun showDebugToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
             android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessions.values.forEach { session ->
            session.cancelTransport()
            session.job?.cancel()
            session.llamaServerChatService.stopGeneration()
        }
        OllamaService.stop()
        serviceScope.cancel()
        sessions.clear()
        synchronized(foregroundLock) {
            notificationTaskId?.let { taskId -> UnifiedNotificationManager.dismissTask(taskId) }
            notificationTaskId = null
        }
        powerLocks.releaseAll()
    }

    private data class PreparedUserTurn(
        val content: String,
        val imagePath: String?,
        val audioPath: String?,
        val videoPath: String?,
        val alreadyPersisted: Boolean = false
    ) {
        fun shouldPersist(): Boolean =
            content.isNotBlank() ||
                !imagePath.isNullOrBlank() ||
                !audioPath.isNullOrBlank() ||
                !videoPath.isNullOrBlank()
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

    private fun maxMutatingToolExecutionsForTurn(
        toolName: String,
        toolConfig: NativeChatToolConfig
    ): Int? = when (toolName) {
        NativeChatToolRuntime.TOOL_GENERATE_IMAGE -> if (toolConfig.imageIterationEnabled) 2 else 1
        NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND,
        NativeChatToolRuntime.TOOL_CREATE_NOTE,
        NativeChatToolRuntime.TOOL_UPDATE_NOTE,
        NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
        NativeChatToolRuntime.TOOL_CREATE_TODO_LIST,
        NativeChatToolRuntime.TOOL_ADD_TODO_ITEM,
        NativeChatToolRuntime.TOOL_UPDATE_TODO_ITEM,
        NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT,
        NativeChatToolRuntime.TOOL_UPDATE_CALENDAR_EVENT,
        NativeChatToolRuntime.TOOL_CREATE_ALARM,
        NativeChatToolRuntime.TOOL_UPDATE_ALARM -> 1
        com.example.llamadroid.service.delegation.NativeChatDelegationTools.TOOL_SCHEDULE -> 2
        else -> if (nativeChatToolRuntime.extensionFor(toolName)?.isMutating(toolName) == true) 1 else null
    }

    companion object {
        const val ACTION_GENERATE = "GENERATE"
        const val ACTION_STOP = "STOP"

        /** Removes a queued (not yet running) chat; send with [EXTRA_CHAT_ID]. */
        const val ACTION_CANCEL_QUEUED = "CANCEL_QUEUED"
        const val EXTRA_CHAT_ID = "CHAT_ID"
        const val EXTRA_SERVER_ID = "SERVER_ID"
        const val EXTRA_USER_MESSAGE = "USER_MESSAGE"
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_AUDIO_PATH = "AUDIO_PATH"
        const val EXTRA_VIDEO_PATH = "VIDEO_PATH"
        const val EXTRA_PRETRANSCRIBED_AUDIO_TEXT = "PRETRANSCRIBED_AUDIO_TEXT"
        const val EXTRA_FORCE_ASSISTANT_TTS = "FORCE_ASSISTANT_TTS"
        const val EXTRA_CALL_MODE = "CALL_MODE"

        /**
         * Deprecated mirror of the latest change of any chat. Read the per-chat state from
         * [NativeChatGenerationStates] instead.
         */
        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState = _generationState.asStateFlow()

        /** Chats whose stop was requested: late progress updates must not revive their state. */
        private val stoppingChats: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()

        /** Publishes [state] for its chat (per-chat store) and mirrors it into [generationState]. */
        fun updateState(state: GenerationState) {
            val chatId = when (state) {
                is GenerationState.Generating -> state.chatId
                is GenerationState.Completed -> state.chatId
                is GenerationState.Error -> state.chatId
                is GenerationState.Queued -> state.chatId
                GenerationState.Idle -> -1L
            }
            if ((state is GenerationState.Generating || state is GenerationState.Queued) &&
                stoppingChats.contains(chatId)
            ) {
                return
            }
            if (chatId > 0L) NativeChatGenerationStates.update(chatId, state)
            _generationState.value = state
        }

        /** Marks [chatId] idle (stopped/cancelled) in the per-chat store and, if it is the latest, the mirror. */
        internal fun clearChatState(chatId: Long) {
            NativeChatGenerationStates.update(chatId, GenerationState.Idle)
            val latest = _generationState.value
            val latestChat = when (latest) {
                is GenerationState.Generating -> latest.chatId
                is GenerationState.Completed -> latest.chatId
                is GenerationState.Error -> latest.chatId
                is GenerationState.Queued -> latest.chatId
                GenerationState.Idle -> -1L
            }
            if (latestChat == chatId) _generationState.value = GenerationState.Idle
        }

        private const val POWER_DIAGNOSTIC_INTERVAL_MS = 30_000L
        
        /** Resets the deprecated mirror when nothing is running; per-chat entries are untouched. */
        fun resetStateIfIdle() {
            val latest = _generationState.value
            if (latest !is GenerationState.Generating && latest !is GenerationState.Queued) {
                _generationState.value = GenerationState.Idle
            }
        }

        /** Drops the finished (Completed/Error) entry of [chatId] so it reads as idle again. */
        fun resetStateIfIdle(chatId: Long) {
            NativeChatGenerationStates.clearIfIdle(chatId)
            resetStateIfIdle()
        }

        private const val MAX_TOOL_ACTIVITY_EVENTS = 40
        private const val NATIVE_TOOL_EXECUTION_MAX_ATTEMPTS = 2
        private const val NATIVE_SEARCH_SUMMARY_CONTEXT = 4096
        private const val NATIVE_SEARCH_SUMMARY_MAX_TOKENS = 512
        private const val NATIVE_SEARCH_SUMMARY_INPUT_CHARS = 6_000
        private const val NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS = 900
        private const val LITERT_TOOL_PROMPT_FALLBACK_MAX_TOKENS = 4000
        private const val LITERT_TOOL_PROMPT_CHARS_PER_TOKEN = 2.6
        private const val LITERT_TOOL_PROMPT_MIN_CHARS = 4_000
        private const val LITERT_TOOL_PROMPT_MAX_CHARS = 12_000
        private const val LITERT_TOOL_PROMPT_MIN_MESSAGE_CHARS = 1_200
        private const val LITERT_TOOL_PROMPT_MESSAGE_CHARS = 1_600
        private const val LITERT_TOOL_PROMPT_SYSTEM_CHARS = 1_200
        private const val LITERT_TOOL_PROMPT_TOOL_RESULT_CHARS = 2_400
        private const val LITERT_MAX_STRUCTURED_TOOLS = 10
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
            val promptProgress: Float? = null,
            val promptProcessed: Int = 0,
            val promptTotal: Int = 0,
            val promptCached: Int = 0,
            val videoProgress: Float? = null,
            val videoVisualProgress: Float = 0f,
            val videoAudioProgress: Float = 0f,
            val videoVisualPhase: String? = null,
            val videoAudioPhase: String? = null,
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
        /**
         * The chat's message is waiting: [reason] says for what (a free slot on the same server,
         * free RAM to start another local model, or the single LiteRT/Ollama engine).
         * [position] is 1-based within the queue of that resource; [resourceLabel] names it.
         */
        data class Queued(
            val chatId: Long,
            val reason: QueueReason,
            val position: Int = 1,
            val resourceLabel: String? = null
        ) : GenerationState()
    }

    enum class QueueReason { SLOT, RAM, ENGINE }
}
