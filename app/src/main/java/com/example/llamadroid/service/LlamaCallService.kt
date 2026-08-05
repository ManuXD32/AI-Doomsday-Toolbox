package com.example.llamadroid.service

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

enum class LlamaCallPhase {
    LISTENING,
    SPEECH_DETECTED,
    SENDING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    WAITING,
    ENDING,
    ERROR
}

data class LlamaCallUiState(
    val isActive: Boolean = false,
    val chatId: Long = -1L,
    val phase: LlamaCallPhase = LlamaCallPhase.ENDING,
    val status: String = "",
    val elapsedSeconds: Int = 0,
    val inputLevel: Float = 0f,
    val error: String? = null
)

private data class LlamaCallTimingConfig(
    val silenceAfterSpeechMs: Long = NativeChatToolConfig.DEFAULT_CALL_SILENCE_AFTER_SPEECH_SECONDS * 1_000L,
    val noSpeechTimeoutMs: Long = NativeChatToolConfig.DEFAULT_CALL_NO_SPEECH_TIMEOUT_SECONDS * 1_000L
) {
    companion object {
        fun fromToolConfig(config: NativeChatToolConfig): LlamaCallTimingConfig = LlamaCallTimingConfig(
            silenceAfterSpeechMs = config.callSilenceAfterSpeechSeconds
                .coerceIn(
                    NativeChatToolConfig.MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS,
                    NativeChatToolConfig.MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS
                ) * 1_000L,
            noSpeechTimeoutMs = config.callNoSpeechTimeoutSeconds
                .coerceIn(
                    NativeChatToolConfig.MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS,
                    NativeChatToolConfig.MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS
                ) * 1_000L
        )
    }
}

class LlamaCallService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: AppDatabase
    private lateinit var repository: LlamaRepository
    private lateinit var settingsRepo: SettingsRepository
    private val whisperBindingIntent by lazy { Intent(applicationContext, WhisperService::class.java) }
    private var callJob: Job? = null
    private var notificationTaskId: Int? = null
    private var currentAudioRecord: AudioRecord? = null
    private var currentPlayer: MediaPlayer? = null
    @Volatile private var hangUpRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        repository = LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
        settingsRepo = SettingsRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                if (chatId <= 0L || serverId <= 0L) {
                    publishError(getString(R.string.llama_call_error_missing_params))
                    stopSelf(startId)
                } else if (callJob?.isActive == true) {
                    publishError(getString(R.string.llama_call_error_already_active))
                } else {
                    startForegroundSession(chatId)
                    startCallLoop(chatId, serverId, startId)
                }
            }
            ACTION_HANG_UP -> hangUp()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hangUp()
        serviceScope.cancel()
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        notificationTaskId = null
        releaseLocks()
        _state.value = LlamaCallUiState()
        super.onDestroy()
    }

    private fun startForegroundSession(chatId: Long) {
        val title = getString(R.string.llama_call_notification_title)
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_CALL,
            title
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        acquireLocks()
        publishState(chatId, LlamaCallPhase.LISTENING, getString(R.string.llama_call_state_listening))
    }

    private fun startCallLoop(chatId: Long, serverId: Long, startId: Int) {
        hangUpRequested = false
        callJob = serviceScope.launch {
            try {
                val server = repository.getServer(serverId)
                    ?: throw IllegalStateException(getString(R.string.llama_call_error_no_server))
                val callTiming = repository.getChat(chatId)
                    ?.apiParams
                    ?.let(NativeChatToolConfig::fromApiParams)
                    ?.let(LlamaCallTimingConfig::fromToolConfig)
                    ?: LlamaCallTimingConfig()
                DebugLog.log(
                    "[LLAMA-CALL] Using call timers " +
                        "silenceAfterSpeechMs=${callTiming.silenceAfterSpeechMs} " +
                        "noSpeechTimeoutMs=${callTiming.noSpeechTimeoutMs}"
                )
                while (isActive && !hangUpRequested) {
                    val utterance = recordUtterance(chatId, callTiming) ?: break
                    ensureActiveCall()
                    val transcript = if (server.requiresAudioTranscriptionFallback()) {
                        publishState(chatId, LlamaCallPhase.TRANSCRIBING, getString(R.string.llama_call_state_transcribing))
                        transcribeUtterance(server, utterance).trim().also { text ->
                            if (text.isBlank()) {
                                throw IllegalStateException(getString(R.string.llama_call_error_empty_transcript))
                            }
                        }
                    } else {
                        DebugLog.log("[LLAMA-CALL] Server '${server.name}' supports direct audio input; skipping Whisper pre-transcription")
                        null
                    }
                    ensureActiveCall()
                    publishState(chatId, LlamaCallPhase.SENDING, getString(R.string.llama_call_state_sending))
                    val turnStartMs = System.currentTimeMillis()
                    startLlamaTurn(chatId, serverId, utterance, transcript)
                    publishState(chatId, LlamaCallPhase.THINKING, getString(R.string.llama_call_state_thinking))
                    waitForLlamaCompletion(chatId)
                    ensureActiveCall()
                    val audioFile = findAssistantAudio(chatId, turnStartMs)
                        ?: throw IllegalStateException(getString(R.string.llama_call_error_no_tts_audio))
                    publishState(chatId, LlamaCallPhase.SPEAKING, getString(R.string.llama_call_state_speaking))
                    playAssistantAudio(audioFile)
                    ensureActiveCall()
                    publishState(chatId, LlamaCallPhase.WAITING, getString(R.string.llama_call_state_waiting))
                    delay(250)
                }
                publishState(chatId, LlamaCallPhase.ENDING, getString(R.string.llama_call_state_ending))
            } catch (cancelled: CancellationException) {
                DebugLog.log("[LLAMA-CALL] Call cancelled")
            } catch (error: Throwable) {
                val message = error.message ?: getString(R.string.error_generic)
                DebugLog.log("[LLAMA-CALL] Call failed: $message\n${error.stackTraceToString()}")
                publishError(message, chatId)
            } finally {
                cleanupActiveAudio()
                releaseLocks()
                notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
                notificationTaskId = null
                _state.value = LlamaCallUiState()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                callJob = null
                hangUpRequested = false
                stopSelf(startId)
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun recordUtterance(chatId: Long, callTiming: LlamaCallTimingConfig): File? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(getString(R.string.llama_record_permission_denied))
        }
        val sampleRate = CALL_SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException(getString(R.string.llama_call_error_audio_record))
        }
        val bufferSize = max(minBuffer, sampleRate / 2)
        val buffer = ShortArray(bufferSize / 2)
        val detector = LlamaCallVoiceActivityDetector(
            sampleRate = sampleRate,
            silenceAfterSpeechMs = callTiming.silenceAfterSpeechMs,
            noSpeechTimeoutMs = callTiming.noSpeechTimeoutMs
        )
        val outputFile = createUtteranceFile()
        var writer: LlamaCallWavFileWriter? = null
        var speechStarted = false
        val startedAt = System.currentTimeMillis()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        currentAudioRecord = recorder
        try {
            recorder.startRecording()
            publishState(chatId, LlamaCallPhase.LISTENING, getString(R.string.llama_call_state_listening))
            while (isActive && !hangUpRequested) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val level = LlamaCallAudioLevel.rms(buffer, count)
                val decision = detector.accept(buffer, count)
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                if (decision.hasSpeechStarted && !speechStarted) {
                    speechStarted = true
                    writer = LlamaCallWavFileWriter(outputFile, sampleRate)
                }
                if (speechStarted) {
                    writer?.write(buffer, count)
                    publishState(
                        chatId,
                        LlamaCallPhase.SPEECH_DETECTED,
                        getString(R.string.llama_call_state_speech_detected),
                        elapsedSeconds,
                        level
                    )
                } else {
                    publishState(
                        chatId,
                        LlamaCallPhase.LISTENING,
                        getString(R.string.llama_call_state_listening),
                        elapsedSeconds,
                        level
                    )
                }
                if (decision.shouldSubmit) {
                    return@withContext writer?.finish()?.takeIf { it.length() > WAV_HEADER_BYTES }
                }
                if (decision.shouldTimeout) {
                    writer?.close()
                    outputFile.delete()
                    return@withContext null
                }
            }
            throw CancellationException(getString(R.string.llama_call_state_ending))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            currentAudioRecord = null
            writer?.close()
        }
    }

    private fun createUtteranceFile(): File {
        val dir = File(filesDir, "llama_call_audio").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "call_$timestamp.wav")
    }

    private suspend fun transcribeUtterance(server: LlamaServerEntity, utterance: File): String {
        val modelPath = server.whisperModelPath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: database.modelDao()
                .getModelsByTypesSync(listOf(ModelType.WHISPER))
                .firstOrNull()
                ?.path
            ?: throw IllegalStateException(getString(R.string.whisper_error_no_model))
        return withWhisperService { whisper ->
            whisper.transcribe(
                WhisperConfig(
                    modelPath = modelPath,
                    audioPath = utterance.absolutePath,
                    language = server.whisperLanguage.ifBlank { LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE },
                    outputFormats = setOf(WhisperOutputFormat.TXT),
                    threads = settingsRepo.whisperThreads.value,
                    purpose = WhisperInvocationPurpose.CALL_TRANSCRIPTION
                )
            )
        }.getOrThrow().text
    }

    private suspend fun <T> withWhisperService(block: suspend (WhisperService) -> Result<T>): Result<T> {
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
            isBound = applicationContext.bindService(whisperBindingIntent, connection, Context.BIND_AUTO_CREATE)
            if (!isBound) {
                continuation.resume(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
            }
            continuation.invokeOnCancellation {
                if (isBound) {
                    runCatching { applicationContext.unbindService(connection) }
                }
            }
        }
    }

    private fun startLlamaTurn(chatId: Long, serverId: Long, utterance: File, transcript: String?) {
        if (LlamaClientService.generationState.value is LlamaClientService.GenerationState.Generating) {
            throw IllegalStateException(getString(R.string.llama_call_error_generation_active))
        }
        val intent = Intent(applicationContext, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_GENERATE
            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            putExtra(LlamaClientService.EXTRA_AUDIO_PATH, utterance.absolutePath)
            if (!transcript.isNullOrBlank()) {
                putExtra(LlamaClientService.EXTRA_PRETRANSCRIBED_AUDIO_TEXT, transcript)
            }
            putExtra(LlamaClientService.EXTRA_FORCE_ASSISTANT_TTS, true)
            putExtra(LlamaClientService.EXTRA_CALL_MODE, true)
        }
        applicationContext.startForegroundService(intent)
    }

    private suspend fun waitForLlamaCompletion(chatId: Long) {
        var sawGenerating = false
        val terminal = LlamaClientService.generationState
            .filter { state ->
                when (state) {
                    is LlamaClientService.GenerationState.Generating -> {
                        if (state.chatId == chatId) sawGenerating = true
                        false
                    }
                    is LlamaClientService.GenerationState.Completed -> sawGenerating && state.chatId == chatId
                    is LlamaClientService.GenerationState.Error -> sawGenerating && (state.chatId == chatId || state.chatId == -1L)
                    LlamaClientService.GenerationState.Idle -> false
                }
            }
            .first()
        if (terminal is LlamaClientService.GenerationState.Error) {
            throw IllegalStateException(terminal.message)
        }
    }

    private suspend fun findAssistantAudio(chatId: Long, sinceMs: Long): File? {
        repeat(20) {
            val file = repository.getMessagesOnce(chatId)
                .asReversed()
                .firstOrNull { message ->
                    message.role == "assistant" &&
                        message.timestamp >= sinceMs &&
                        !message.audioPath.isNullOrBlank()
                }
                ?.audioPath
                ?.let(::File)
                ?.takeIf { it.exists() }
            if (file != null) return file
            delay(500)
        }
        return null
    }

    private suspend fun playAssistantAudio(file: File) {
        val completed = CompletableDeferred<Unit>()
        val player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                if (!completed.isCompleted) completed.complete(Unit)
                runCatching { it.release() }
            }
            setOnErrorListener { mp, _, _ ->
                if (!completed.isCompleted) {
                    completed.completeExceptionally(IllegalStateException(getString(R.string.llama_call_error_playback)))
                }
                runCatching { mp.release() }
                true
            }
            prepare()
        }
        currentPlayer = player
        try {
            player.start()
            completed.await()
        } finally {
            currentPlayer = null
            runCatching { player.release() }
        }
    }

    private fun publishState(
        chatId: Long,
        phase: LlamaCallPhase,
        status: String,
        elapsedSeconds: Int = _state.value.elapsedSeconds,
        inputLevel: Float = _state.value.inputLevel
    ) {
        _state.value = LlamaCallUiState(
            isActive = true,
            chatId = chatId,
            phase = phase,
            status = status,
            elapsedSeconds = elapsedSeconds,
            inputLevel = inputLevel
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, -1f, status)
        }
    }

    private fun publishError(message: String, chatId: Long = _state.value.chatId) {
        _state.value = LlamaCallUiState(
            isActive = chatId > 0L,
            chatId = chatId,
            phase = LlamaCallPhase.ERROR,
            status = message,
            error = message
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.failTask(taskId, message)
        }
    }

    private fun ensureActiveCall() {
        if (hangUpRequested || callJob?.isActive == false) {
            throw CancellationException(getString(R.string.llama_call_state_ending))
        }
    }

    private fun hangUp() {
        hangUpRequested = true
        cleanupActiveAudio()
        callJob?.cancel(CancellationException(getString(R.string.llama_call_state_ending)))
    }

    private fun cleanupActiveAudio() {
        runCatching { currentAudioRecord?.stop() }
        runCatching { currentAudioRecord?.release() }
        currentAudioRecord = null
        runCatching { currentPlayer?.stop() }
        runCatching { currentPlayer?.release() }
        currentPlayer = null
    }

    private fun acquireLocks() {
        WakeLockManager.acquire(applicationContext, "LlamaCallService")
        WakeLockManager.acquireWifiLock(applicationContext, "LlamaCallService")
    }

    private fun releaseLocks() {
        WakeLockManager.release("LlamaCallService")
        WakeLockManager.releaseWifiLock("LlamaCallService")
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.action.START_LLAMA_CALL"
        const val ACTION_HANG_UP = "com.example.llamadroid.action.HANG_UP_LLAMA_CALL"
        const val EXTRA_CHAT_ID = "CHAT_ID"
        const val EXTRA_SERVER_ID = "SERVER_ID"
        private const val CALL_SAMPLE_RATE = 16_000
        private const val WAV_HEADER_BYTES = 44L

        private val _state = MutableStateFlow(LlamaCallUiState())
        val state: StateFlow<LlamaCallUiState> = _state

        fun startIntent(context: Context, chatId: Long, serverId: Long): Intent =
            Intent(context, LlamaCallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_SERVER_ID, serverId)
            }

        fun hangUpIntent(context: Context): Intent =
            Intent(context, LlamaCallService::class.java).apply {
                action = ACTION_HANG_UP
            }
    }
}
