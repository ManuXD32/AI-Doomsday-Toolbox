package com.example.llamadroid.audio

import android.app.Service
import androidx.room.withTransaction
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.onnx.extractReadableTextFromUri
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Serialized, foreground, recoverable queue for all local Audio workspace jobs. */
class AudioGenerationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    @Volatile private var queueJob: Job? = null
    @Volatile private var notificationTaskId: Int? = null
    @Volatile private var activeJobId: String? = null
    @Volatile private var activeAdapter: AudioTtsAdapter? = null
    @Volatile private var latestStartId: Int = 0
    @Volatile private var cancelRequested = false

    private val repository by lazy { AudioWorkspaceRepository(this, AppDatabase.getDatabase(this)) }
    private val registry by lazy { AudioAdapterRegistry.forContext(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeAdapter?.cancel()
        queueJob?.cancel()
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) { recordInterruptedQueue() }
        }
        clearForeground()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        try {
            when (intent?.action) {
                ACTION_CANCEL -> {
                    val requestedId = intent.getStringExtra(EXTRA_JOB_ID)
                    if (requestedId == null && activeJobId == null && queueJob?.isActive != true) {
                        if (stopSelfResult(startId)) clearForeground()
                    } else if (requestedId == null || requestedId == activeJobId) {
                        ensureForeground()
                        val cancelledJobId = requestedId ?: activeJobId
                        cancelRequested = true
                        activeAdapter?.cancel()
                        serviceScope.launch {
                            try {
                                cancelledJobId?.let {
                                    AppDatabase.getDatabase(applicationContext).audioDao()
                                        .markCancelling(it, getString(R.string.audio_runtime_status_cancelling))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                logLifecycleFailure("cancel active job", error)
                            }
                        }
                    } else {
                        ensureForeground()
                        serviceScope.launch {
                            try {
                                AppDatabase.getDatabase(applicationContext).audioDao()
                                    .cancelQueuedJob(
                                        requestedId,
                                        getString(R.string.audio_runtime_status_cancelled)
                                    )
                                withContext(Dispatchers.Main.immediate) {
                                    if (queueJob?.isActive != true && stopSelfResult(startId)) {
                                        clearForeground()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                logLifecycleFailure("cancel queued job", error)
                            }
                        }
                    }
                }
                ACTION_START, null -> {
                    ensureForeground()
                    startQueue()
                }
            }
        } catch (error: Throwable) {
            logLifecycleFailure("start command", error)
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) {
                    recordInterruptedQueue()
                }
            }
            clearForeground()
            stopSelfResult(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Service shutdown is recoverable. Manual cancellation is signalled by
        // ACTION_CANCEL; a lifecycle shutdown leaves the row interrupted so
        // the next explicit retry can resume it from its persisted request.
        cancelRequested = false
        runCatching { activeAdapter?.cancel() }
        queueJob?.cancel()
        serviceScope.cancel()
        clearForeground()
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ONNX_TTS,
            getString(R.string.audio_runtime_title)
        )
        notificationTaskId = taskId
        try {
            startForeground(taskId, notification)
        } catch (error: Throwable) {
            notificationTaskId = null
            runCatching { UnifiedNotificationManager.dismissTask(taskId) }
            throw error
        }
    }

    private fun startQueue() {
        if (queueJob?.isActive == true) return
        val drainStartId = latestStartId
        queueJob = serviceScope.launch {
            try {
                queueMutex.withLock {
                    val dao = AppDatabase.getDatabase(applicationContext).audioDao()
                    // The coordinator is process-scoped. It repairs rows left by
                    // a dead process once, before this service ever dequeues a
                    // row; subsequent starts in the same process leave newly
                    // enqueued work untouched.
                    AudioRuntimeCoordinator.initialize(applicationContext, dao)
                    while (true) {
                        ensureActive()
                        val snapshotStartId = latestStartId
                        val next = dao.getNextQueuedJob()
                        if (next == null) {
                            // Serialize the final stop/notification transition
                            // with onStartCommand. A newer start must be able
                            // to launch its own drain after this one releases
                            // the queue mutex, rather than observing an old
                            // still-active Job and losing its wake-up.
                            val stopped = withContext(Dispatchers.Main.immediate) {
                                if (latestStartId == snapshotStartId && stopSelfResult(snapshotStartId)) {
                                    queueJob = null
                                    clearForeground()
                                    true
                                } else false
                            }
                            if (stopped) break
                            continue
                        }
                        runJob(next)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleQueueFailure(error, drainStartId)
            }
        }
    }

    private suspend fun handleQueueFailure(error: Throwable, drainStartId: Int) {
        val failedAt = System.currentTimeMillis()
        val failedJobId = activeJobId
        logLifecycleFailure("queue", error)
        withContext(NonCancellable) {
            if (failedJobId != null) {
                try {
                    val dao = AppDatabase.getDatabase(applicationContext).audioDao()
                    val current = try {
                        dao.getJob(failedJobId)
                    } catch (_: Throwable) {
                        null
                    }
                    if (current != null && current.status !in AudioJobStatuses.terminal) {
                        try {
                            dao.updateJob(
                                current.copy(
                                    status = AudioJobStatuses.ERROR,
                                    stageMessage = getString(R.string.audio_runtime_status_error),
                                    errorMessage = getString(R.string.audio_runtime_error_generic),
                                    updatedAt = failedAt,
                                    completedAt = failedAt
                                )
                            )
                        } catch (_: Throwable) {
                            // The database may itself be unavailable. The
                            // lifecycle error remains recoverable on the next
                            // service start.
                        }
                    }
                } catch (_: Throwable) {
                    // Keep a coordinator failure from escaping the service
                    // coroutine when database construction itself fails.
                }
            }
            recordInterruptedQueue()
        }
        notificationTaskId?.let { taskId ->
            runCatching {
                UnifiedNotificationManager.failTask(
                    taskId,
                    getString(R.string.audio_runtime_error_generic)
                )
            }
        }
        activeJobId?.let { runCatching { WakeLockManager.release("AudioGenerationService:$it") } }
        runCatching { activeAdapter?.cancel() }
        activeAdapter = null
        activeJobId = null
        cancelRequested = false
        withContext(Dispatchers.Main.immediate) {
            queueJob = null
            if (stopSelfResult(drainStartId)) {
                clearForeground()
            } else {
                startQueue()
            }
        }
    }

    private suspend fun recordInterruptedQueue() {
        try {
            AppDatabase.getDatabase(applicationContext).audioDao().markRecoverableJobsInterrupted(
                getString(R.string.audio_runtime_status_interrupted)
            )
        } catch (error: Throwable) {
            logLifecycleFailure("persist interruption", error)
        }
    }

    private fun clearForeground() {
        notificationTaskId?.let { taskId ->
            runCatching { UnifiedNotificationManager.dismissTask(taskId) }
        }
        notificationTaskId = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun logLifecycleFailure(operation: String, error: Throwable) {
        DebugLog.log("[AUDIO] Lifecycle failure operation=$operation error=${error.javaClass.simpleName}")
    }

    private suspend fun runJob(initial: AudioGenerationJobEntity) {
        val dao = AppDatabase.getDatabase(applicationContext).audioDao()
        val startedAt = System.currentTimeMillis()
        cancelRequested = false
        activeJobId = initial.id
        var job = initial.copy(
            status = AudioJobStatuses.PREPARING,
            progress = 0f,
            stageMessage = getString(R.string.audio_runtime_status_preparing),
            startedAt = startedAt,
            updatedAt = startedAt,
            errorMessage = null
        )
        // A cancellation may win after the queue lookup. Only a conditional
        // claim can move this row into preparation without resurrecting it.
        if (dao.claimQueuedJob(job.id, job.stageMessage, startedAt) == 0) {
            activeJobId = null
            cancelRequested = false
            return
        }
        WakeLockManager.acquire(applicationContext, "AudioGenerationService:${initial.id}")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        val (newTaskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ONNX_TTS,
            initial.modelDisplayName.ifBlank { getString(R.string.audio_runtime_title) }
        )
        notificationTaskId = newTaskId
        startForeground(newTaskId, notification)
        try {
            val text = resolveText(job)
            ensureNotCancelled()
            val voice = job.voiceProfileId?.let { repository.getVoiceProfile(it) }
            require(job.voiceProfileId == null || voice != null) { "Voice reference profile is missing" }
            val adapter = registry.resolve(job.toRequest().model)
                ?: error(getString(R.string.audio_runtime_error_no_adapter))
            val referencePath = resolveReferencePath(job, voice)
            val preparedReference = if (adapter.info.supportsReferenceAudio && !referencePath.isNullOrBlank()) {
                AudioVoiceAssetStore.prepareReference(
                    context = applicationContext,
                    input = File(referencePath),
                    output = File(AudioWorkspaceStorage.stagingRoot(applicationContext), "${job.id}_reference.wav"),
                    trimStartMs = job.trimStartMs,
                    trimEndMs = job.trimEndMs,
                    normalize = job.normalizeReference,
                    denoise = job.denoiseReference,
                    isCancelled = { cancelRequested }
                ).absolutePath
            } else {
                referencePath
            }
            val request = job.toRequest().copy(
                text = text,
                referenceAudioPath = preparedReference
            ).validate()
            activeAdapter = adapter
            job = job.copy(
                status = AudioJobStatuses.RUNNING,
                progress = 0f,
                completedChunks = 0,
                totalChunks = 0,
                stageMessage = getString(R.string.audio_runtime_status_generating),
                updatedAt = System.currentTimeMillis()
            )
            dao.updateJob(job)
            notificationTaskId?.let {
                UnifiedNotificationManager.updateProgress(
                    it,
                    job.progress,
                    getString(R.string.audio_runtime_status_generating)
                )
            }
            val result = adapter.generate(
                request = request,
                text = text,
                outputDir = AudioWorkspaceStorage.jobRoot(applicationContext, job.id),
                onProgress = { progress ->
                    ensureNotCancelled()
                    val current = dao.getJob(job.id) ?: job
                    val reportedTotal = progress.totalChunks.coerceAtLeast(0)
                    val totalChunks = if (progress.unit == AudioProgressUnit.STAGE) 0 else if (reportedTotal > 0) reportedTotal else current.totalChunks
                    val completedChunks = if (reportedTotal > 0) {
                        progress.completedChunks.coerceIn(0, reportedTotal)
                    } else {
                        current.completedChunks.coerceAtMost(totalChunks)
                    }
                    val actualProgress = if (progress.unit == AudioProgressUnit.STAGE) 0f else if (totalChunks > 0) {
                        completedChunks.toFloat() / totalChunks.toFloat()
                    } else {
                        current.progress.coerceIn(0f, 1f)
                    }
                    job = current.copy(
                        status = AudioJobStatuses.RUNNING,
                        progress = actualProgress,
                        stageMessage = localizeStage(progress.stage),
                        completedChunks = completedChunks,
                        totalChunks = totalChunks,
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.updateJob(job)
                    notificationTaskId?.let {
                        UnifiedNotificationManager.updateProgress(it, job.progress, localizeStage(progress.stage))
                    }
                },
                isCancelled = { cancelRequested }
            )
            ensureNotCancelled()
            job = (dao.getJob(job.id) ?: job).copy(
                stageMessage = getString(R.string.audio_runtime_status_saving),
                updatedAt = System.currentTimeMillis()
            )
            dao.updateJob(job)
            val persisted = AudioOutputStorage.persist(
                applicationContext,
                job,
                result,
                isCancelled = { cancelRequested }
            )
            val finishedAt = System.currentTimeMillis()
            job = (dao.getJob(job.id) ?: job).copy(
                status = AudioJobStatuses.COMPLETE,
                progress = 1f,
                stageMessage = getString(R.string.audio_runtime_status_complete),
                outputPath = persisted.outputPath,
                wavPath = persisted.wavPath,
                metadataPath = persisted.metadataPath,
                durationMs = persisted.durationMs,
                sampleRate = persisted.sampleRate,
                updatedAt = finishedAt,
                completedAt = finishedAt
            )
            val database = AppDatabase.getDatabase(applicationContext)
            val library = com.example.llamadroid.audio.library.AudioLibraryRepository(applicationContext, database)
            // Finish the one-time legacy scan before opening the short completion transaction.
            library.getPreferences()
            database.withTransaction {
                dao.updateJob(job)
                checkNotNull(library.upsertJob(job)) { "Audio output is missing" }
            }
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.audio_runtime_complete))
            }
        } catch (cancelled: CancellationException) {
            val cancelledAt = System.currentTimeMillis()
            withContext(NonCancellable) {
                val current = dao.getJob(initial.id) ?: initial
                dao.updateJob(current.copy(
                    status = if (cancelRequested) AudioJobStatuses.CANCELLED else AudioJobStatuses.INTERRUPTED,
                    stageMessage = if (cancelRequested) {
                        getString(R.string.audio_runtime_status_cancelled)
                    } else {
                        getString(R.string.audio_runtime_status_interrupted)
                    },
                    updatedAt = cancelledAt,
                    completedAt = cancelledAt
                ))
            }
            AudioWorkspaceStorage.deleteJobOutput(applicationContext, initial.id)
            notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        } catch (error: Throwable) {
            val failedAt = System.currentTimeMillis()
            val message = localizeError(error)
            DebugLog.log("[AUDIO] Generation failed adapter=${initial.adapterId} job=${initial.id}: ${error.javaClass.simpleName}")
            withContext(NonCancellable) {
                try {
                    val current = dao.getJob(initial.id) ?: initial
                    dao.updateJob(current.copy(
                        status = AudioJobStatuses.ERROR,
                        stageMessage = getString(R.string.audio_runtime_status_error),
                        errorMessage = message,
                        updatedAt = failedAt,
                        completedAt = failedAt
                    ))
                } catch (_: Throwable) {
                    // Leave the persisted request for startup recovery if the
                    // database is unavailable while recording the failure.
                }
            }
            notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
        } finally {
            WakeLockManager.release("AudioGenerationService:${initial.id}")
            File(AudioWorkspaceStorage.stagingRoot(applicationContext), "${initial.id}_reference.wav").delete()
            activeAdapter = null
            activeJobId = null
            cancelRequested = false
        }
    }

    private suspend fun resolveText(job: AudioGenerationJobEntity): String {
        job.sourceUri?.takeIf { it.isNotBlank() }?.let { raw ->
            val name = job.sourceName ?: "document"
            return extractReadableTextFromUri(applicationContext, Uri.parse(raw), name).trim()
        }
        return job.text?.trim().orEmpty().also {
            require(AudioModelFamilies.isMusicOrSfx(job.family) || it.isNotBlank()) { getString(R.string.audio_runtime_error_empty_text) }
        }
    }

    private fun resolveReferencePath(job: AudioGenerationJobEntity, profile: AudioVoiceProfile?): String? {
        profile ?: return job.referenceAudioPath
        val preferred = when {
            job.denoiseReference && !profile.denoisedPath.isNullOrBlank() -> profile.denoisedPath
            job.normalizeReference && !profile.normalizedPath.isNullOrBlank() -> profile.normalizedPath
            else -> profile.originalPath
        }
        return preferred?.takeIf { File(it).isFile } ?: profile.originalPath
    }

    private fun ensureNotCancelled() {
        if (cancelRequested) throw CancellationException(getString(R.string.audio_runtime_error_cancelled))
    }

    private fun localizeStage(stage: String): String {
        val lower = stage.lowercase()
        return when {
            "tokeniz" in lower -> getString(R.string.audio_music_stage_tokenize)
            "condition" in lower || "text encod" in lower -> getString(R.string.audio_music_stage_condition)
            "audio encod" in lower -> getString(R.string.audio_music_stage_encode)
            "sampl" in lower -> getString(R.string.audio_music_stage_sample)
            "decod" in lower -> getString(R.string.audio_music_stage_decode)
            "lora" in lower -> getString(R.string.audio_music_stage_lora)
            "load" in lower || "validat" in lower || "prepar" in lower ->
                getString(R.string.audio_runtime_status_preparing)
            "save" in lower || "convert" in lower || "complete" in lower ->
                getString(R.string.audio_runtime_status_saving)
            else -> getString(R.string.audio_runtime_status_generating)
        }
    }

    private fun localizeError(error: Throwable): String {
        val raw = error.message.orEmpty().lowercase()
        return when {
            "worker_process_died" in raw || "timeout" in raw -> getString(R.string.audio_music_error_worker)
            "not enough memory" in raw -> getString(R.string.audio_music_error_memory)
            "lora" in raw || "adapter" in raw || "fp16" in raw -> getString(R.string.audio_music_error_lora)
            "output_invalid" in raw || "invalid audio output" in raw -> getString(R.string.audio_music_error_output)
            "input audio" in raw || "extension duration" in raw -> getString(R.string.audio_music_error_input)
            "component" in raw || "digest" in raw || "bad_request" in raw || "native_pipeline_unavailable" in raw ->
                getString(R.string.audio_music_error_components)
            "llama-tts binary" in raw || "binary is not installed" in raw ->
                getString(R.string.audio_runtime_error_native_binary_missing)
            "companion" in raw || "projector" in raw ->
                getString(R.string.audio_runtime_error_companion_missing)
            "model file" in raw || "model path" in raw || "model is missing" in raw ->
                getString(R.string.audio_runtime_error_model_missing)
            "speaker reference" in raw ->
                getString(R.string.audio_runtime_error_reference_required)
            "ffmpeg" in raw -> getString(R.string.audio_runtime_error_ffmpeg_missing)
            "text" in raw || "source document" in raw ->
                getString(R.string.audio_runtime_error_empty_text)
            "voice reference" in raw && ("read" in raw || "silent" in raw || "format" in raw || "duration" in raw || "empty" in raw) ->
                getString(R.string.audio_runtime_error_voice_invalid)
            "shared output export" in raw -> getString(R.string.audio_runtime_error_generic)
            else -> getString(R.string.audio_runtime_error_generic)
        }
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.audio.action.START"
        const val ACTION_CANCEL = "com.example.llamadroid.audio.action.CANCEL"
        private const val EXTRA_JOB_ID = "job_id"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, AudioGenerationService::class.java).apply {
                action = ACTION_START
            }
            context.applicationContext.startForegroundService(intent)
        }

        fun cancel(context: Context, jobId: String? = null) {
            val intent = Intent(context.applicationContext, AudioGenerationService::class.java).apply {
                action = ACTION_CANCEL
                jobId?.let { putExtra(EXTRA_JOB_ID, it) }
            }
            context.applicationContext.startForegroundService(intent)
        }
    }
}
