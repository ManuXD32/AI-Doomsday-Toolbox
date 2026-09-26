package com.example.llamadroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.R
import com.example.llamadroid.data.db.GenerationQueueItemEntity
import com.example.llamadroid.data.db.RestoreCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single foreground owner serializes local image and video jobs across screen changes. */
class GenerationQueueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: GenerationQueueRepository
    private var drainJob: Job? = null
    private var activeItemId: String? = null
    private var activeRunId: String? = null
    private var currentCancel: (() -> Unit)? = null
    private var stopRequestedItemId: String? = null
    @Volatile private var wakeLockProbe: (() -> Boolean)? = null
    private var timedOut = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (RestoreCoordinator.isMaintenance(newBase)) newBase
            else LlamaApplication.updateLocale(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        if (RestoreCoordinator.isMaintenance(this)) {
            stopSelf()
            return
        }
        repository = GenerationQueueRepository(this)
        GenerationDiagnosticsStore.init(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (RestoreCoordinator.isMaintenance(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RUN, ACTION_RUN_SCHEDULED -> {
                if (drainJob?.isActive == true) return START_NOT_STICKY
                timedOut = false
                try {
                    startForeground(GenerationQueueNotifications.ID,
                        GenerationQueueNotifications.running(this,
                            GenerationQueueProgress(0, 0, 0, 0, 0), null))
                } catch (error: RuntimeException) {
                    stopSelfResult(startId)
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        if (intent.action == ACTION_RUN_SCHEDULED) {
                            runCatching { repository.markMissedSchedule() }
                        }
                        GenerationQueueNotifications.showStartBlocked(applicationContext)
                        runCatching { GenerationDiagnosticsStore.recordBreadcrumb(
                            source = "GenerationQueueService", mode = "QUEUE",
                            event = "foreground_start_rejected",
                            details = "errorClass=${error.javaClass.simpleName}",
                            notificationActive = false) }
                    }
                    return START_NOT_STICKY
                }
                GenerationQueueRuntime.setActive(true)
                drainJob = scope.launch {
                    try {
                        drain(intent.action == ACTION_RUN_SCHEDULED)
                    } finally {
                        currentCancel = null
                        activeItemId = null
                        activeRunId = null
                        GenerationQueueRuntime.setActive(false)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
            }
            ACTION_PAUSE -> scope.launch { repository.requestPause() }
            ACTION_STOP_ITEM -> {
                val requestedId = intent.getStringExtra(EXTRA_ITEM_ID)
                if (requestedId != null && requestedId == activeItemId) {
                    stopRequestedItemId = requestedId
                    currentCancel?.invoke()
                }
            }
            ACTION_REFRESH -> scope.launch {
                activeRunId?.let { runId ->
                    updateNotification(runId, activeItemId?.let { repository.item(it)?.kind })
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain(scheduled: Boolean) {
        val runId = repository.beginRun(scheduled) ?: run {
            GenerationQueueNotifications.dismiss(this)
            return
        }
        activeRunId = runId
        GenerationQueueScheduler.cancelAlarmOnly(this)
        updateNotification(runId, null)
        while (scope.isActive && !timedOut) {
            if (!waitForManualJobs(runId)) break
            if (timedOut) break
            val item = repository.claimNext() ?: break
            activeItemId = item.id
            updateNotification(runId, item.kind)
            val diagnostics = GenerationQueuePowerDiagnostics(this, item)
            val progressReader = progressReader(item)
            val diagnosticJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val progress = progressReader()
                    diagnostics.sample(progress?.first, progress?.second,
                        wakeLockProbe?.invoke(), GenerationQueueRuntime.isActive)
                    delay(DIAGNOSTIC_SAMPLE_MS)
                }
            }
            val attempted = try {
                execute(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                QueuedGenerationOutcome.failed(error.message ?: getString(R.string.error_generic))
            } finally {
                withContext(NonCancellable) { diagnosticJob.cancelAndJoin() }
            }
            val outcome = if (timedOut) QueuedGenerationOutcome.interrupted(MEDIA_PROCESSING_TIMEOUT_REASON)
                else attempted
            val lastProgress = progressReader()
            diagnostics.sample(lastProgress?.first, lastProgress?.second,
                wakeLockProbe?.invoke(), GenerationQueueRuntime.isActive,
                event = "item_finished:${outcome.status}")
            currentCancel = null
            activeItemId = null
            stopRequestedItemId = null
            repository.finish(item.id, outcome)
            updateNotification(runId, null)
            if (outcome.status == "INTERRUPTED") {
                repository.requestPause()
                break
            }
        }
        val control = repository.controlNow()
        GenerationQueueNotifications.showStopped(this, repository.progress(runId),
            paused = control.state == "PAUSED", mediaLimitReached = timedOut)
        activeRunId = null
    }

    private suspend fun waitForManualJobs(runId: String): Boolean {
        while (localGeneratorBusy()) {
            if (timedOut || repository.controlNow().state != "RUNNING") return false
            updateNotification(runId, null)
            delay(300)
        }
        return true
    }

    private fun localGeneratorBusy(): Boolean {
        val sd = listOf(SDModeStateHolder.txt2img, SDModeStateHolder.img2img,
            SDModeStateHolder.adetailer, SDModeStateHolder.upscale,
            SDModeStateHolder.workflowTxt2img, SDModeStateHolder.workflowUpscale)
        if (sd.any { it.state.value is SDGenerationState.Generating }) return true
        return listOf(VideoGenerationStateHolder.txt2vid, VideoGenerationStateHolder.img2vid)
            .any { holder -> holder.state.value is VideoGenerationState.Generating ||
                holder.state.value is VideoGenerationState.Converting ||
                holder.state.value is VideoGenerationState.Copying }
    }

    private fun progressReader(item: GenerationQueueItemEntity): () -> Pair<Int, Int>? =
        runCatching {
            when (item.kind) {
                GenerationQueueSnapshot.IMAGE -> {
                    val mode = GenerationQueueSnapshot.decodeImage(item.configJson).mode
                    val holder = SDModeStateHolder.getForMode(mode)
                    val reader: () -> Pair<Int, Int>? = { (holder.state.value as? SDGenerationState.Generating)?.snapshot?.let {
                        it.currentStep to it.totalSteps
                    } }
                    reader
                }
                GenerationQueueSnapshot.UPSCALE -> {
                    val holder = SDModeStateHolder.upscale
                    val reader: () -> Pair<Int, Int>? = { (holder.state.value as? SDGenerationState.Generating)?.snapshot?.let {
                        it.currentStep to it.totalSteps
                    } }
                    reader
                }
                GenerationQueueSnapshot.VIDEO -> {
                    val mode = GenerationQueueSnapshot.decodeVideo(item.configJson).mode
                    val holder = VideoGenerationStateHolder.getForMode(mode)
                    val reader: () -> Pair<Int, Int>? = { (holder.state.value as? VideoGenerationState.Generating)?.let {
                        it.currentStep to it.totalSteps
                    } }
                    reader
                }
                else -> {
                    val reader: () -> Pair<Int, Int>? = { null }
                    reader
                }
            }
        }.getOrElse { { null } }

    private suspend fun execute(item: GenerationQueueItemEntity): QueuedGenerationOutcome =
        when (item.kind) {
            GenerationQueueSnapshot.IMAGE -> runImage(GenerationQueueSnapshot.decodeImage(item.configJson))
            GenerationQueueSnapshot.UPSCALE -> runUpscale(GenerationQueueSnapshot.decodeUpscale(item.configJson))
            GenerationQueueSnapshot.VIDEO -> runVideo(GenerationQueueSnapshot.decodeVideo(item.configJson))
            else -> QueuedGenerationOutcome.failed(getString(R.string.generation_queue_unsupported_item))
        }

    private suspend fun runImage(config: SDConfig): QueuedGenerationOutcome {
        val bound = bindImage()
        return try {
            val result = CompletableDeferred<QueuedGenerationOutcome>()
            wakeLockProbe = { bound.service.isGenerationWakeLockHeld() }
            currentCancel = { bound.service.cancelMode(config.mode) }
            bound.service.startQueued(config) { result.complete(it) }
            if (stopRequestedItemId == activeItemId) currentCancel?.invoke()
            result.await()
        } finally {
            wakeLockProbe = null
            unbindService(bound.connection)
        }
    }

    private suspend fun runUpscale(config: SDUpscaleConfig): QueuedGenerationOutcome {
        val bound = bindImage()
        return try {
            val result = CompletableDeferred<QueuedGenerationOutcome>()
            wakeLockProbe = { bound.service.isGenerationWakeLockHeld() }
            currentCancel = { bound.service.cancelMode(SDMode.UPSCALE) }
            bound.service.startQueued(config) { result.complete(it) }
            if (stopRequestedItemId == activeItemId) currentCancel?.invoke()
            result.await()
        } finally {
            wakeLockProbe = null
            unbindService(bound.connection)
        }
    }

    private suspend fun runVideo(config: VideoGenerationConfig): QueuedGenerationOutcome {
        val bound = bindVideo()
        return try {
            val result = CompletableDeferred<QueuedGenerationOutcome>()
            wakeLockProbe = { bound.service.isGenerationWakeLockHeld() }
            currentCancel = { bound.service.cancelMode(config.mode) }
            bound.service.startQueued(config) { result.complete(it) }
            if (stopRequestedItemId == activeItemId) currentCancel?.invoke()
            result.await()
        } finally {
            wakeLockProbe = null
            unbindService(bound.connection)
        }
    }

    private suspend fun bindImage(): BoundService<StableDiffusionService> =
        suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!continuation.isActive) {
                        runCatching { unbindService(this) }
                        return
                    }
                    val service = (binder as? StableDiffusionService.LocalBinder)?.getService()
                    if (service == null) continuation.resumeWithException(IllegalStateException("Image service unavailable"))
                    else continuation.resume(BoundService(service, this))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Image service disconnected"))
                }
            }
            if (!bindService(Intent(this, StableDiffusionService::class.java), connection, BIND_AUTO_CREATE)) {
                continuation.resumeWithException(IllegalStateException("Image service unavailable"))
            }
        }

    private suspend fun bindVideo(): BoundService<VideoGenerationService> =
        suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!continuation.isActive) {
                        runCatching { unbindService(this) }
                        return
                    }
                    val service = (binder as? VideoGenerationService.LocalBinder)?.getService()
                    if (service == null) continuation.resumeWithException(IllegalStateException("Video service unavailable"))
                    else continuation.resume(BoundService(service, this))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Video service disconnected"))
                }
            }
            if (!bindService(Intent(this, VideoGenerationService::class.java), connection, BIND_AUTO_CREATE)) {
                continuation.resumeWithException(IllegalStateException("Video service unavailable"))
            }
        }

    private suspend fun updateNotification(runId: String, currentKind: String?) {
        val notification = GenerationQueueNotifications.running(this, repository.progress(runId), currentKind)
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(GenerationQueueNotifications.ID, notification)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0) {
            if (timedOut) return
            timedOut = true
            val interruptedId = activeItemId
            val runId = activeRunId
            runCatching { currentCancel?.invoke() }
            // Android gives this callback only a few seconds; persistence runs separately.
            stopSelf()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val saved = runCatching {
                    repository.requestPause()
                    interruptedId?.let { repository.finish(it,
                        QueuedGenerationOutcome.interrupted(MEDIA_PROCESSING_TIMEOUT_REASON)) }
                }
                val progress = runId?.let { runCatching { repository.progress(it) }.getOrNull() }
                if (progress != null) GenerationQueueNotifications.showStopped(applicationContext,
                    progress, paused = true, mediaLimitReached = true)
                else GenerationQueueNotifications.showStartBlocked(applicationContext)
                runCatching { GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "GenerationQueueService", mode = "QUEUE",
                    event = "media_processing_timeout", details = buildString {
                        append("itemId=").append(interruptedId ?: "none")
                        append(" saved=").append(saved.isSuccess)
                    }, notificationActive = false) }
            }
        } else {
            super.onTimeout(startId, fgsType)
        }
    }

    override fun onDestroy() {
        runCatching { currentCancel?.invoke() }
        drainJob?.cancel()
        scope.cancel()
        GenerationQueueRuntime.setActive(false)
        super.onDestroy()
    }

    private data class BoundService<T : Service>(val service: T, val connection: ServiceConnection)

    companion object {
        const val MEDIA_PROCESSING_TIMEOUT_REASON = "media_processing_time_limit"
        private const val DIAGNOSTIC_SAMPLE_MS = 15_000L
        private const val ACTION_RUN = "com.example.llamadroid.action.RUN_GENERATION_QUEUE"
        private const val ACTION_RUN_SCHEDULED = "com.example.llamadroid.action.RUN_SCHEDULED_GENERATION_QUEUE"
        private const val ACTION_PAUSE = "com.example.llamadroid.action.PAUSE_GENERATION_QUEUE"
        private const val ACTION_STOP_ITEM = "com.example.llamadroid.action.STOP_QUEUED_GENERATION"
        private const val ACTION_REFRESH = "com.example.llamadroid.action.REFRESH_GENERATION_QUEUE"
        private const val EXTRA_ITEM_ID = "queue_item_id"

        fun runIntent(context: Context, scheduled: Boolean = false): Intent =
            Intent(context, GenerationQueueService::class.java).apply {
                action = if (scheduled) ACTION_RUN_SCHEDULED else ACTION_RUN
            }

        fun pauseIntent(context: Context): Intent = Intent(context, GenerationQueueService::class.java)
            .apply { action = ACTION_PAUSE }

        fun stopItemIntent(context: Context, itemId: String): Intent =
            Intent(context, GenerationQueueService::class.java).apply {
                action = ACTION_STOP_ITEM
                putExtra(EXTRA_ITEM_ID, itemId)
            }

        fun refreshIntent(context: Context): Intent = Intent(context, GenerationQueueService::class.java)
            .apply { action = ACTION_REFRESH }

        fun startNow(context: Context) {
            ContextCompat.startForegroundService(context, runIntent(context))
        }
    }
}
