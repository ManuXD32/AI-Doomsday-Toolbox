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
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_ONE
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_TWO
import com.example.llamadroid.data.db.LiveTranslatorSessionEntity
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import com.example.llamadroid.data.db.LiveTranslatorTurnEntity
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.SupertonicTtsPipeline
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

enum class LiveTranslatorPhase {
    IDLE,
    LISTENING,
    SPEECH_DETECTED,
    TRANSCRIBING,
    TRANSLATING,
    SPEAKING,
    WAITING,
    STOPPING,
    ERROR
}

data class LiveTranslatorUiState(
    val isActive: Boolean = false,
    val sessionId: Long = -1L,
    val templateId: Long = -1L,
    val currentSpeaker: Int = LIVE_TRANSLATOR_SPEAKER_ONE,
    val phase: LiveTranslatorPhase = LiveTranslatorPhase.IDLE,
    val status: String = "",
    val elapsedSeconds: Int = 0,
    val inputLevel: Float = 0f,
    val error: String? = null
)

enum class LiveTranslatorSamplePhase {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    DONE,
    ERROR
}

data class LiveTranslatorSampleState(
    val isActive: Boolean = false,
    val phase: LiveTranslatorSamplePhase = LiveTranslatorSamplePhase.IDLE,
    val status: String = "",
    val inputLevel: Float = 0f,
    val transcript: String = "",
    val detectedLanguage: String? = null,
    val normalizedLanguage: String? = null,
    val error: String? = null
)

class LiveTranslatorService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: AppDatabase
    private lateinit var runner: LiveTranslatorNativeRunner
    private val whisperBindingIntent by lazy { Intent(applicationContext, WhisperService::class.java) }
    private var activeJob: Job? = null
    private var notificationTaskId: Int? = null
    private var currentAudioRecord: AudioRecord? = null
    private var currentPlayer: MediaPlayer? = null
    @Volatile private var stopRequested = false
    @Volatile private var nextSpeakerOverride: Int? = null
    private var sampleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        runner = LiveTranslatorNativeRunner(applicationContext, database)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, -1L)
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (templateId <= 0L) {
                    publishError(getString(R.string.live_translator_error_missing_template))
                    stopSelf(startId)
                } else if (activeJob?.isActive == true) {
                    publishError(getString(R.string.live_translator_error_already_running))
                } else {
                    startForegroundSession()
                    startLoop(templateId, sessionId.takeIf { it > 0L }, startId)
                }
            }
            ACTION_STOP -> stopTranslator()
            ACTION_SET_NEXT_SPEAKER -> {
                val speaker = intent.getIntExtra(EXTRA_SPEAKER, LIVE_TRANSLATOR_SPEAKER_ONE)
                nextSpeakerOverride = speaker.coerceIn(LIVE_TRANSLATOR_SPEAKER_ONE, LIVE_TRANSLATOR_SPEAKER_TWO)
                _state.value = _state.value.copy(currentSpeaker = nextSpeakerOverride ?: _state.value.currentSpeaker)
            }
            ACTION_SAMPLE_LANGUAGE -> {
                val whisperModelPath = intent.getStringExtra(EXTRA_WHISPER_MODEL_PATH)
                val whisperThreads = intent.getIntExtra(EXTRA_WHISPER_THREADS, 4)
                if (activeJob?.isActive == true) {
                    publishSampleError(getString(R.string.live_translator_sampler_error_live_running))
                } else if (sampleJob?.isActive == true) {
                    publishSampleError(getString(R.string.live_translator_sampler_error_already_running))
                } else {
                    startForegroundSession()
                    startLanguageSample(whisperModelPath, whisperThreads, startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTranslator()
        serviceScope.cancel()
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        notificationTaskId = null
        releaseLocks()
        _state.value = LiveTranslatorUiState()
        super.onDestroy()
    }

    private fun startForegroundSession() {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LIVE_TRANSLATOR,
            getString(R.string.live_translator_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        acquireLocks()
    }

    private fun startLoop(templateId: Long, existingSessionId: Long?, startId: Int) {
        stopRequested = false
        nextSpeakerOverride = null
        activeJob = serviceScope.launch {
            var sessionId = existingSessionId ?: -1L
            var currentSpeaker = LIVE_TRANSLATOR_SPEAKER_ONE
            try {
                val template = database.liveTranslatorTemplateDao().getTemplateById(templateId)
                    ?: throw IllegalStateException(getString(R.string.live_translator_error_missing_template))
                sessionId = existingSessionId ?: createSession(template)
                publishState(
                    sessionId = sessionId,
                    templateId = templateId,
                    speaker = currentSpeaker,
                    phase = LiveTranslatorPhase.LISTENING,
                    status = getString(R.string.live_translator_state_listening)
                )
                while (isActive && !stopRequested) {
                    nextSpeakerOverride?.let {
                        currentSpeaker = it
                        nextSpeakerOverride = null
                    }
                    val utterance = recordUtterance(sessionId, template, currentSpeaker)
                    if (utterance == null) {
                        publishState(
                            sessionId,
                            templateId,
                            currentSpeaker,
                            LiveTranslatorPhase.WAITING,
                            getString(R.string.live_translator_state_waiting)
                        )
                        delay(250)
                        continue
                    }
                    ensureActiveSession()
                    publishState(sessionId, templateId, currentSpeaker, LiveTranslatorPhase.TRANSCRIBING, getString(R.string.live_translator_state_transcribing))
                    val transcriptResult = transcribeUtterance(template, utterance).getOrThrow()
                    val transcript = transcriptResult.text.trim()
                    if (transcript.isBlank()) {
                        throw IllegalStateException(getString(R.string.live_translator_error_empty_transcript))
                    }
                    val routing = LiveTranslatorLogic.resolveTurnRouting(
                        template = template,
                        expectedSpeaker = currentSpeaker,
                        detectedLanguage = transcriptResult.detectedLanguage
                    )
                    currentSpeaker = routing.sourceSpeaker
                    if (routing.usedDetectedLanguage) {
                        DebugLog.log(
                            "[LIVE-TRANSLATOR] Whisper detected ${routing.detectedLanguage}; " +
                                "routing turn as speaker=${routing.sourceSpeaker} " +
                                "${routing.sourceLanguage}->${routing.targetLanguage}"
                        )
                    }
                    val turnId = database.liveTranslatorTurnDao().insert(
                        LiveTranslatorTurnEntity(
                            sessionId = sessionId,
                            speaker = routing.sourceSpeaker,
                            originalText = transcript,
                            detectedLanguage = transcriptResult.detectedLanguage,
                            sourceLanguage = routing.sourceLanguage,
                            targetLanguage = routing.targetLanguage,
                            audioPath = utterance.absolutePath
                        )
                    )
                    database.liveTranslatorSessionDao().touch(sessionId)
                    ensureActiveSession()
                    publishState(sessionId, templateId, currentSpeaker, LiveTranslatorPhase.TRANSLATING, getString(R.string.live_translator_state_translating))
                    val translated = runner.translate(template, routing.sourceLanguage, routing.targetLanguage, transcript)
                    val translatedTurn = database.liveTranslatorTurnDao().getTurnsOnce(sessionId)
                        .firstOrNull { it.id == turnId }
                        ?.copy(translatedText = translated)
                    if (translatedTurn != null) {
                        database.liveTranslatorTurnDao().update(translatedTurn)
                    }
                    ensureActiveSession()
                    publishState(sessionId, templateId, currentSpeaker, LiveTranslatorPhase.SPEAKING, getString(R.string.live_translator_state_speaking))
                    val spokenTtsLanguage = LiveTranslatorLogic.ttsLanguageForTranslatedText(routing.targetTtsLanguage, translated)
                    if (spokenTtsLanguage != routing.targetTtsLanguage) {
                        DebugLog.log(
                            "[LIVE-TRANSLATOR] Translation text looked like $spokenTtsLanguage; " +
                                "using that TTS language instead of ${routing.targetTtsLanguage}"
                        )
                    }
                    val ttsFile = synthesizeTranslation(template, spokenTtsLanguage, translated)
                    translatedTurn?.copy(ttsAudioPath = ttsFile.absolutePath)?.let {
                        database.liveTranslatorTurnDao().update(it)
                    }
                    playAudio(ttsFile)
                    currentSpeaker = routing.targetSpeaker
                    nextSpeakerOverride?.let {
                        currentSpeaker = it
                        nextSpeakerOverride = null
                    }
                    publishState(sessionId, templateId, currentSpeaker, LiveTranslatorPhase.WAITING, getString(R.string.live_translator_state_waiting))
                    delay(250)
                }
                publishState(sessionId, templateId, currentSpeaker, LiveTranslatorPhase.STOPPING, getString(R.string.live_translator_state_stopped))
            } catch (cancelled: CancellationException) {
                DebugLog.log("[LIVE-TRANSLATOR] Cancelled")
            } catch (error: Throwable) {
                val message = error.message ?: getString(R.string.error_generic)
                DebugLog.log("[LIVE-TRANSLATOR] Failed: $message\n${error.stackTraceToString()}")
                if (sessionId > 0L) {
                    database.liveTranslatorTurnDao().insert(
                        LiveTranslatorTurnEntity(
                            sessionId = sessionId,
                            speaker = currentSpeaker,
                            originalText = "",
                            sourceLanguage = "",
                            targetLanguage = "",
                            isError = true,
                            errorMessage = message
                        )
                    )
                    database.liveTranslatorSessionDao().touch(sessionId)
                }
                publishError(message, sessionId)
            } finally {
                cleanupActiveAudio()
                releaseLocks()
                notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
                notificationTaskId = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                activeJob = null
                stopRequested = false
                nextSpeakerOverride = null
                if (_state.value.phase != LiveTranslatorPhase.ERROR) {
                    _state.value = LiveTranslatorUiState()
                }
                stopSelf(startId)
            }
        }
    }

    private fun startLanguageSample(whisperModelPath: String?, whisperThreads: Int, startId: Int) {
        sampleJob = serviceScope.launch {
            try {
                publishSampleState(
                    LiveTranslatorSamplePhase.RECORDING,
                    getString(R.string.live_translator_sampler_state_recording)
                )
                val utterance = recordFixedSample()
                ensureSampleActive()
                publishSampleState(
                    LiveTranslatorSamplePhase.TRANSCRIBING,
                    getString(R.string.live_translator_sampler_state_transcribing)
                )
                val result = transcribeLanguageSample(
                    whisperModelPath = whisperModelPath,
                    whisperThreads = whisperThreads,
                    utterance = utterance
                ).getOrThrow()
                val detected = result.detectedLanguage?.trim()?.takeIf { it.isNotBlank() }
                val normalized = LiveTranslatorLogic.normalizeLanguageTag(detected)
                _sampleState.value = LiveTranslatorSampleState(
                    isActive = false,
                    phase = LiveTranslatorSamplePhase.DONE,
                    status = if (detected.isNullOrBlank()) {
                        getString(R.string.live_translator_sampler_result_unknown)
                    } else {
                        getString(R.string.live_translator_sampler_result_language, detected)
                    },
                    transcript = result.text.trim(),
                    detectedLanguage = detected,
                    normalizedLanguage = normalized
                )
                runCatching { utterance.delete() }
            } catch (cancelled: CancellationException) {
                DebugLog.log("[LIVE-TRANSLATOR] Language sampler cancelled")
            } catch (error: Throwable) {
                val message = error.message ?: getString(R.string.error_generic)
                DebugLog.log("[LIVE-TRANSLATOR] Language sampler failed: $message\n${error.stackTraceToString()}")
                publishSampleError(message)
            } finally {
                cleanupActiveAudio()
                releaseLocks()
                notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
                notificationTaskId = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                sampleJob = null
                stopSelf(startId)
            }
        }
    }

    private suspend fun createSession(template: LiveTranslatorTemplateEntity): Long {
        val now = System.currentTimeMillis()
        val title = getString(
            R.string.live_translator_session_title,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        )
        return database.liveTranslatorSessionDao().insert(
            LiveTranslatorSessionEntity(
                title = title,
                templateId = template.id,
                templateSnapshotJson = LiveTranslatorLogic.templateSnapshotJson(template),
                speaker1Language = template.speaker1Language,
                speaker2Language = template.speaker2Language,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    @Suppress("MissingPermission")
    private suspend fun recordUtterance(
        sessionId: Long,
        template: LiveTranslatorTemplateEntity,
        speaker: Int
    ): File? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(getString(R.string.llama_record_permission_denied))
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException(getString(R.string.llama_call_error_audio_record))
        }
        val bufferSize = max(minBuffer, SAMPLE_RATE / 2)
        val buffer = ShortArray(bufferSize / 2)
        val detector = LlamaCallVoiceActivityDetector(
            sampleRate = SAMPLE_RATE,
            silenceAfterSpeechMs = template.finishedTalkingTimeoutSeconds.coerceIn(1, 30) * 1_000L,
            noSpeechTimeoutMs = template.startSpeakingTimeoutSeconds.coerceIn(1, 120) * 1_000L
        )
        val outputFile = createUtteranceFile()
        var writer: LlamaCallWavFileWriter? = null
        var speechStarted = false
        val startedAt = System.currentTimeMillis()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        currentAudioRecord = recorder
        try {
            recorder.startRecording()
            publishState(sessionId, template.id, speaker, LiveTranslatorPhase.LISTENING, getString(R.string.live_translator_state_listening))
            while (isActive && !stopRequested) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val level = LlamaCallAudioLevel.rms(buffer, count)
                val decision = detector.accept(buffer, count)
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                if (decision.hasSpeechStarted && !speechStarted) {
                    speechStarted = true
                    writer = LlamaCallWavFileWriter(outputFile, SAMPLE_RATE)
                }
                if (speechStarted) {
                    writer?.write(buffer, count)
                    publishState(sessionId, template.id, speaker, LiveTranslatorPhase.SPEECH_DETECTED, getString(R.string.live_translator_state_speech_detected), elapsedSeconds, level)
                } else {
                    publishState(sessionId, template.id, speaker, LiveTranslatorPhase.LISTENING, getString(R.string.live_translator_state_listening), elapsedSeconds, level)
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
            throw CancellationException(getString(R.string.live_translator_state_stopped))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            currentAudioRecord = null
            writer?.close()
        }
    }

    private fun createUtteranceFile(): File {
        val dir = File(filesDir, "live_translator_audio").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "turn_$timestamp.wav")
    }

    @Suppress("MissingPermission")
    private suspend fun recordFixedSample(): File = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(getString(R.string.llama_record_permission_denied))
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException(getString(R.string.llama_call_error_audio_record))
        }
        val bufferSize = max(minBuffer, SAMPLE_RATE / 2)
        val buffer = ShortArray(bufferSize / 2)
        val outputFile = createSampleFile()
        val writer = LlamaCallWavFileWriter(outputFile, SAMPLE_RATE)
        val startedAt = System.currentTimeMillis()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        currentAudioRecord = recorder
        try {
            recorder.startRecording()
            while (isActive && sampleJob?.isActive == true && System.currentTimeMillis() - startedAt < SAMPLE_DURATION_MS) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                writer.write(buffer, count)
                val level = LlamaCallAudioLevel.rms(buffer, count)
                _sampleState.value = _sampleState.value.copy(inputLevel = level)
            }
            writer.finish().takeIf { it.length() > WAV_HEADER_BYTES }
                ?: throw IllegalStateException(getString(R.string.live_translator_error_empty_transcript))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            currentAudioRecord = null
            writer.close()
        }
    }

    private fun createSampleFile(): File {
        val dir = File(cacheDir, "live_translator_samples").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "sample_$timestamp.wav")
    }

    private suspend fun transcribeUtterance(
        template: LiveTranslatorTemplateEntity,
        utterance: File
    ): Result<WhisperResult> {
        val modelPath = template.whisperModelPath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: database.modelDao().getModelsByTypesSync(listOf(com.example.llamadroid.data.db.ModelType.WHISPER))
                .firstOrNull()
                ?.path
            ?: throw IllegalStateException(getString(R.string.whisper_error_no_model))
        return withWhisperService { whisper ->
            whisper.transcribe(
                WhisperConfig(
                    modelPath = modelPath,
                    audioPath = utterance.absolutePath,
                    language = LiveTranslatorLogic.WHISPER_LANGUAGE_AUTO,
                    outputFormats = setOf(WhisperOutputFormat.TXT),
                    threads = template.whisperThreads.coerceIn(1, 16)
                )
            )
        }
    }

    private suspend fun transcribeLanguageSample(
        whisperModelPath: String?,
        whisperThreads: Int,
        utterance: File
    ): Result<WhisperResult> {
        val modelPath = whisperModelPath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: database.modelDao().getModelsByTypesSync(listOf(com.example.llamadroid.data.db.ModelType.WHISPER))
                .firstOrNull()
                ?.path
            ?: throw IllegalStateException(getString(R.string.whisper_error_no_model))
        return withWhisperService { whisper ->
            whisper.transcribe(
                WhisperConfig(
                    modelPath = modelPath,
                    audioPath = utterance.absolutePath,
                    language = LiveTranslatorLogic.WHISPER_LANGUAGE_AUTO,
                    outputFormats = setOf(WhisperOutputFormat.TXT),
                    threads = whisperThreads.coerceIn(1, 16)
                )
            )
        }
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
                    if (continuation.isActive) continuation.resume(result)
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
                if (isBound) runCatching { applicationContext.unbindService(connection) }
            }
        }
    }

    private fun synthesizeTranslation(
        template: LiveTranslatorTemplateEntity,
        ttsLanguage: String,
        translated: String
    ): File {
        val modelPath = template.ttsModelPath?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(getString(R.string.onnx_tts_no_model))
        val result = SupertonicTtsPipeline(applicationContext).generate(
            OnnxTtsRequest(
                modelPath = modelPath,
                modelName = template.ttsModelName ?: File(modelPath).name,
                text = translated,
                language = ttsLanguage.ifBlank { "en" },
                voiceName = template.ttsVoiceName,
                totalSteps = template.ttsSteps.coerceIn(1, 32),
                speed = template.ttsSpeed.coerceIn(0.5f, 2.0f),
                sourceName = "live_translator"
            )
        )
        return result.playableFile
    }

    private suspend fun playAudio(file: File) {
        val completed = CompletableDeferred<Unit>()
        val player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                if (!completed.isCompleted) completed.complete(Unit)
                runCatching { it.release() }
            }
            setOnErrorListener { mp, _, _ ->
                if (!completed.isCompleted) completed.completeExceptionally(IllegalStateException(getString(R.string.llama_call_error_playback)))
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
        sessionId: Long,
        templateId: Long,
        speaker: Int,
        phase: LiveTranslatorPhase,
        status: String,
        elapsedSeconds: Int = _state.value.elapsedSeconds,
        inputLevel: Float = _state.value.inputLevel
    ) {
        _state.value = LiveTranslatorUiState(
            isActive = true,
            sessionId = sessionId,
            templateId = templateId,
            currentSpeaker = speaker,
            phase = phase,
            status = status,
            elapsedSeconds = elapsedSeconds,
            inputLevel = inputLevel
        )
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, -1f, status) }
    }

    private fun publishError(message: String, sessionId: Long = _state.value.sessionId) {
        _state.value = LiveTranslatorUiState(
            isActive = sessionId > 0L,
            sessionId = sessionId,
            phase = LiveTranslatorPhase.ERROR,
            status = message,
            error = message
        )
        notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
    }

    private fun publishSampleState(phase: LiveTranslatorSamplePhase, status: String) {
        _sampleState.value = LiveTranslatorSampleState(
            isActive = phase == LiveTranslatorSamplePhase.RECORDING || phase == LiveTranslatorSamplePhase.TRANSCRIBING,
            phase = phase,
            status = status,
            inputLevel = _sampleState.value.inputLevel,
            transcript = _sampleState.value.transcript,
            detectedLanguage = _sampleState.value.detectedLanguage,
            normalizedLanguage = _sampleState.value.normalizedLanguage
        )
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, -1f, status) }
    }

    private fun publishSampleError(message: String) {
        _sampleState.value = LiveTranslatorSampleState(
            isActive = false,
            phase = LiveTranslatorSamplePhase.ERROR,
            status = message,
            error = message
        )
        notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
    }

    private fun ensureActiveSession() {
        if (stopRequested || activeJob?.isActive == false) {
            throw CancellationException(getString(R.string.live_translator_state_stopped))
        }
    }

    private fun ensureSampleActive() {
        if (sampleJob?.isActive == false) {
            throw CancellationException(getString(R.string.live_translator_state_stopped))
        }
    }

    private fun stopTranslator() {
        stopRequested = true
        cleanupActiveAudio()
        activeJob?.cancel(CancellationException(getString(R.string.live_translator_state_stopped)))
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
        WakeLockManager.acquire(applicationContext, "LiveTranslatorService")
        WakeLockManager.acquireWifiLock(applicationContext, "LiveTranslatorService")
    }

    private fun releaseLocks() {
        WakeLockManager.release("LiveTranslatorService")
        WakeLockManager.releaseWifiLock("LiveTranslatorService")
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.action.START_LIVE_TRANSLATOR"
        const val ACTION_STOP = "com.example.llamadroid.action.STOP_LIVE_TRANSLATOR"
        const val ACTION_SET_NEXT_SPEAKER = "com.example.llamadroid.action.SET_LIVE_TRANSLATOR_NEXT_SPEAKER"
        const val EXTRA_TEMPLATE_ID = "TEMPLATE_ID"
        const val EXTRA_SESSION_ID = "SESSION_ID"
        const val EXTRA_SPEAKER = "SPEAKER"
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLE_DURATION_MS = 5_000L
        private const val WAV_HEADER_BYTES = 44L

        private val _state = MutableStateFlow(LiveTranslatorUiState())
        val state: StateFlow<LiveTranslatorUiState> = _state

        private val _sampleState = MutableStateFlow(LiveTranslatorSampleState())
        val sampleState: StateFlow<LiveTranslatorSampleState> = _sampleState

        fun startIntent(context: Context, templateId: Long, sessionId: Long? = null): Intent =
            Intent(context, LiveTranslatorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TEMPLATE_ID, templateId)
                if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveTranslatorService::class.java).apply { action = ACTION_STOP }

        fun setNextSpeakerIntent(context: Context, speaker: Int): Intent =
            Intent(context, LiveTranslatorService::class.java).apply {
                action = ACTION_SET_NEXT_SPEAKER
                putExtra(EXTRA_SPEAKER, speaker)
            }

        fun sampleIntent(context: Context, whisperModelPath: String?, whisperThreads: Int): Intent =
            Intent(context, LiveTranslatorService::class.java).apply {
                action = ACTION_SAMPLE_LANGUAGE
                putExtra(EXTRA_WHISPER_MODEL_PATH, whisperModelPath)
                putExtra(EXTRA_WHISPER_THREADS, whisperThreads)
            }

        const val ACTION_SAMPLE_LANGUAGE = "com.example.llamadroid.action.SAMPLE_LIVE_TRANSLATOR_LANGUAGE"
        const val EXTRA_WHISPER_MODEL_PATH = "WHISPER_MODEL_PATH"
        const val EXTRA_WHISPER_THREADS = "WHISPER_THREADS"
    }
}
