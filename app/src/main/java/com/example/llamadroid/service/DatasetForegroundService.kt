package com.example.llamadroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DatasetDao
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class DatasetForegroundService : Service(), DatasetProcessor.RuntimeHooks {

    companion object {
        private const val TAG = "DatasetForegroundSvc"
        private const val CHANNEL_ID = "dataset_runtime"
        private const val NOTIFICATION_ID = 9101

        const val ACTION_ENQUEUE = "enqueue_dataset_job"
        const val ACTION_ENQUEUE_BATCH = "enqueue_dataset_jobs"
        const val ACTION_CANCEL_CURRENT = "cancel_dataset_current"
        const val ACTION_CANCEL_ALL = "cancel_dataset_all"
        const val ACTION_REMOVE_QUEUED_JOB = "remove_dataset_queued_job"
        const val ACTION_MOVE_JOB_UP = "move_dataset_job_up"
        const val ACTION_MOVE_JOB_DOWN = "move_dataset_job_down"
        const val ACTION_REQUEST_RESUME = "request_dataset_resume"

        private const val EXTRA_JOB_JSON = "job_json"
        private const val EXTRA_JOBS_JSON = "jobs_json"
        private const val EXTRA_INDEX = "index"

        @Volatile
        private var isRunning = false

        private val resumeRequested = AtomicBoolean(false)

        val progress = DatasetProcessor.progress
        val isProcessing = DatasetProcessor.isProcessing
        val jobQueue = DatasetProcessor.jobQueue
        val activeJob = DatasetProcessor.activeJob

        fun enqueue(context: Context, job: DatasetProcessor.Job) {
            val intent = Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_JOB_JSON, job.toPersistedDatasetJob().toJson().toString())
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun enqueueBatch(context: Context, jobs: List<DatasetProcessor.Job>) {
            if (jobs.isEmpty()) return
            val jobsJson = JSONArray().apply {
                jobs.forEach { put(it.toPersistedDatasetJob().toJson()) }
            }
            val intent = Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                action = ACTION_ENQUEUE_BATCH
                putExtra(EXTRA_JOBS_JSON, jobsJson.toString())
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun cancelCurrent(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_CANCEL_CURRENT
                }
            )
        }

        fun cancelAll(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_CANCEL_ALL
                }
            )
        }

        fun removeQueuedJob(context: Context, index: Int) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_REMOVE_QUEUED_JOB
                    putExtra(EXTRA_INDEX, index)
                }
            )
        }

        fun moveQueuedJobUp(context: Context, index: Int) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_MOVE_JOB_UP
                    putExtra(EXTRA_INDEX, index)
                }
            )
        }

        fun moveQueuedJobDown(context: Context, index: Int) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_MOVE_JOB_DOWN
                    putExtra(EXTRA_INDEX, index)
                }
            )
        }

        fun requestResume(context: Context) {
            val appContext = context.applicationContext
            if (isRunning) {
                recordBreadcrumb(
                    event = "resume_requested",
                    phase = ACTION_REQUEST_RESUME,
                    details = "running=true mode=already_running"
                )
                return
            }
            if (!resumeRequested.compareAndSet(false, true)) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val recoverableJob = AiRuntimeJobStore.getRecoverableJobs(appContext)
                    .firstOrNull { it.type == AiRuntimeJobStore.TYPE_DATASET_PIPELINE }
                val resumeAction = resolveDatasetResumeAction(recoverableJob)
                recordBreadcrumb(
                    event = "resume_requested",
                    phase = ACTION_REQUEST_RESUME,
                    details = "running=false recoverable=${resumeAction.shouldRecover}"
                )
                if (!resumeAction.shouldRecover) {
                    recordBreadcrumb(
                        event = "resume_skipped",
                        phase = ACTION_REQUEST_RESUME,
                        details = "reason=no_recoverable_runtime"
                    )
                    resumeRequested.set(false)
                    return@launch
                }
                val intent = Intent(appContext, DatasetForegroundService::class.java).apply {
                    action = ACTION_REQUEST_RESUME
                }
                ContextCompat.startForegroundService(appContext, intent)
                recordBreadcrumb(
                    event = "resume_dispatched",
                    phase = ACTION_REQUEST_RESUME,
                    details = "running=false mode=resume_cold"
                )
            }
        }

        internal fun deserializeRuntimeJob(rawJson: String?): DatasetProcessor.Job? {
            if (rawJson.isNullOrBlank()) return null
            return runCatching {
                PersistedDatasetJob.fromJson(JSONObject(rawJson)).toRuntimeJob()
            }.getOrNull()
        }

        internal fun deserializeRuntimeJobs(rawJson: String?): List<DatasetProcessor.Job> {
            if (rawJson.isNullOrBlank()) return emptyList()
            return runCatching {
                val jobs = JSONArray(rawJson)
                buildList {
                    for (index in 0 until jobs.length()) {
                        val item = jobs.optJSONObject(index) ?: continue
                        PersistedDatasetJob.fromJson(item).toRuntimeJob()?.let { add(it) }
                    }
                }
            }.getOrElse { emptyList() }
        }

        private fun recordBreadcrumb(
            event: String,
            phase: String? = null,
            details: String? = null
        ) {
            runCatching {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "dataset_foreground_service",
                    event = event,
                    phase = phase,
                    details = details
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: DatasetDao
    private lateinit var processor: DatasetProcessor

    private var progressObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false
    private var runtimeCreatedAt: Long = System.currentTimeMillis()
    private var activeProjectId: Long? = null

    override fun onCreate() {
        super.onCreate()
        dao = AppDatabase.getDatabase(applicationContext).datasetDao()
        processor = DatasetProcessor(applicationContext, dao)
        DatasetProcessor.setProcessor(processor)
        DatasetProcessor.setRuntimeHooks(this)
        createNotificationChannel()
        observeProgressUpdates()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> handleEnqueue(intent.getStringExtra(EXTRA_JOB_JSON))
            ACTION_ENQUEUE_BATCH -> handleEnqueueBatch(intent.getStringExtra(EXTRA_JOBS_JSON))
            ACTION_CANCEL_CURRENT -> handleCancelCurrent()
            ACTION_CANCEL_ALL -> handleCancelAll()
            ACTION_REMOVE_QUEUED_JOB -> handleRemoveQueuedJob(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_MOVE_JOB_UP -> handleMoveJobUp(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_MOVE_JOB_DOWN -> handleMoveJobDown(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_REQUEST_RESUME -> {
                recordBreadcrumb(
                    event = "resume_command_received",
                    phase = ACTION_REQUEST_RESUME,
                    details = "running=$isRunning"
                )
                handleRequestResume()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        DatasetProcessor.setRuntimeHooks(null)
        releaseWakeLock()
        progressObserverJob?.cancel()
        foregroundStarted = false
        isRunning = false
        resumeRequested.set(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onJobStarted(job: DatasetProcessor.Job) {
        activeProjectId = job.projectId
        ensureForegroundStarted(
            status = getString(R.string.dataset_runtime_notification_title),
            projectId = job.projectId
        )
        serviceScope.launch {
            persistRecoverableRuntime()
        }
    }

    override fun onJobFinished(
        job: DatasetProcessor.Job?,
        outcome: DatasetProcessor.JobOutcome,
        errorMessage: String?
    ) {
        serviceScope.launch {
            when (outcome) {
                DatasetProcessor.JobOutcome.SUCCESS -> {
                    if (DatasetProcessor.jobQueue.value.isEmpty()) {
                        markRuntimeTerminal(
                            status = AiRuntimeJobStore.STATUS_COMPLETED,
                            progressText = getString(R.string.dataset_stage_done)
                        )
                        stopRuntimeService()
                    }
                }

                DatasetProcessor.JobOutcome.FAILED -> {
                    markRuntimeTerminal(
                        status = AiRuntimeJobStore.STATUS_FAILED,
                        progressText = getString(R.string.dataset_stage_error),
                        errorMessage = errorMessage
                    )
                    stopRuntimeService()
                }

                DatasetProcessor.JobOutcome.CANCELLED -> {
                    markRuntimeTerminal(
                        status = AiRuntimeJobStore.STATUS_CANCELLED,
                        progressText = getString(R.string.action_cancelled),
                        errorMessage = errorMessage
                    )
                    stopRuntimeService()
                }
            }
        }
    }

    private fun handleEnqueue(rawJobJson: String?) {
        val job = deserializeRuntimeJob(rawJobJson) ?: run {
            stopSelfIfIdle()
            return
        }
        activeProjectId = job.projectId
        if (!ensureForegroundStarted(
                status = getString(R.string.dataset_runtime_notification_title),
                projectId = job.projectId,
                startSource = "enqueue",
                requestedAction = ACTION_ENQUEUE
            )
        ) return
        DatasetProcessor.queueJob(job)
        serviceScope.launch {
            persistRecoverableRuntime()
        }
    }

    private fun handleEnqueueBatch(rawJobsJson: String?) {
        val jobs = deserializeRuntimeJobs(rawJobsJson)
        if (jobs.isEmpty()) {
            stopSelfIfIdle()
            return
        }
        activeProjectId = jobs.first().projectId
        if (!ensureForegroundStarted(
                status = getString(R.string.dataset_runtime_notification_title),
                projectId = jobs.first().projectId,
                startSource = "enqueue_batch",
                requestedAction = ACTION_ENQUEUE_BATCH
            )
        ) return
        DatasetProcessor.queueJobs(jobs)
        serviceScope.launch {
            persistRecoverableRuntime()
        }
    }

    private fun handleCancelCurrent() {
        cancelRuntimeImmediately(ACTION_CANCEL_CURRENT)
    }

    private fun handleCancelAll() {
        cancelRuntimeImmediately(ACTION_CANCEL_ALL)
    }

    private fun cancelRuntimeImmediately(action: String) {
        recordBreadcrumb(
            event = "cancel_requested",
            phase = action,
            details = "queued=${DatasetProcessor.jobQueue.value.size}"
        )
        DatasetProcessor.cancelAllImmediately()
        serviceScope.launch {
            markRuntimeTerminal(
                status = AiRuntimeJobStore.STATUS_CANCELLED,
                progressText = getString(R.string.action_cancelled),
                errorMessage = getString(R.string.dataset_runtime_cancelled_message)
            )
            stopRuntimeService()
        }
    }

    private fun handleRemoveQueuedJob(index: Int) {
        DatasetProcessor.removeJob(index)
        serviceScope.launch {
            if (DatasetProcessor.isProcessing.value) {
                persistRecoverableRuntime()
            }
        }
        stopSelfIfIdle()
    }

    private fun handleMoveJobUp(index: Int) {
        DatasetProcessor.moveJobUp(index)
        serviceScope.launch {
            if (DatasetProcessor.isProcessing.value) {
                persistRecoverableRuntime()
            }
        }
        stopSelfIfIdle()
    }

    private fun handleMoveJobDown(index: Int) {
        DatasetProcessor.moveJobDown(index)
        serviceScope.launch {
            if (DatasetProcessor.isProcessing.value) {
                persistRecoverableRuntime()
            }
        }
        stopSelfIfIdle()
    }

    private fun handleRequestResume() {
        if (!ensureForegroundStarted(
                status = getString(R.string.dataset_runtime_recovering),
                projectId = activeProjectId,
                startSource = "resume_cold",
                requestedAction = ACTION_REQUEST_RESUME
            )
        ) return
        serviceScope.launch {
            recoverPersistedRuntime()
        }
    }

    private fun observeProgressUpdates() {
        progressObserverJob = serviceScope.launch {
            DatasetProcessor.progress.collectLatest { progress ->
                progress?.let { activeProjectId = it.projectId }
                if (foregroundStarted) {
                    updateForegroundNotification(progress)
                }
                if (DatasetProcessor.isProcessing.value) {
                    persistRecoverableRuntime()
                }
            }
        }
    }

    private suspend fun recoverPersistedRuntime() {
        try {
            val runtimeJob = AiRuntimeJobStore.getRecoverableJobs(applicationContext)
                .firstOrNull { it.type == AiRuntimeJobStore.TYPE_DATASET_PIPELINE }
            val resumeAction = resolveDatasetResumeAction(runtimeJob)
            recordBreadcrumb(
                event = "runtime_recovery_scan",
                phase = ACTION_REQUEST_RESUME,
                details = "recoverable=${if (resumeAction.shouldRecover) 1 else 0}"
            )
            if (!resumeAction.shouldRecover || resumeAction.snapshot == null) {
                recordBreadcrumb(
                    event = "runtime_recovery_empty_stop",
                    phase = ACTION_REQUEST_RESUME,
                    details = "recoverable=0"
                )
                stopRuntimeService()
                return
            }
            runtimeCreatedAt = runtimeJob?.createdAt ?: System.currentTimeMillis()
            runtimeJob?.let {
                AiRuntimeJobStore.markState(
                    context = applicationContext,
                    jobId = it.jobId,
                    status = AiRuntimeJobStore.STATUS_RECOVERING,
                    checkpointJson = it.checkpointJson,
                    progressText = getString(R.string.dataset_runtime_recovering),
                    errorMessage = null
                )
            }
            activeProjectId = resumeAction.snapshot.firstProjectId()
            val restoredJobs = resumeAction.snapshot.toRuntimeQueue()
            if (restoredJobs.isEmpty()) {
                recordBreadcrumb(
                    event = "runtime_recovery_empty_stop",
                    phase = ACTION_REQUEST_RESUME,
                    details = "recoverable=0 restored=0"
                )
                stopRuntimeService()
                return
            }
            DatasetProcessor.restoreQueuedJobs(restoredJobs)
        } finally {
            resumeRequested.set(false)
        }
    }

    private suspend fun persistRecoverableRuntime() {
        val active = DatasetProcessor.activeJob.value?.toPersistedDatasetJob()
        val queued = DatasetProcessor.jobQueue.value.map { it.toPersistedDatasetJob() }
        if (active == null && queued.isEmpty()) return

        val snapshot = PersistedDatasetRuntimeSnapshot(
            activeJob = active,
            queuedJobs = queued
        )
        val progress = DatasetProcessor.progress.value
        val now = System.currentTimeMillis()
        if (runtimeCreatedAt == 0L) {
            runtimeCreatedAt = now
        }
        val progressText = when {
            progress != null -> "${progress.stage}: ${progress.current}/${progress.total}"
            active != null -> active.name
            else -> getString(R.string.dataset_runtime_notification_title)
        }
        AiRuntimeJobStore.upsert(
            applicationContext,
            AiRuntimeJobEntity(
                jobId = DATASET_RUNTIME_JOB_ID,
                jobKey = DATASET_RUNTIME_JOB_KEY,
                type = AiRuntimeJobStore.TYPE_DATASET_PIPELINE,
                status = AiRuntimeJobStore.STATUS_RUNNING,
                payloadJson = snapshot.toJson().toString(),
                checkpointJson = buildCheckpointJson(active, progress).toString(),
                progressText = progressText,
                errorMessage = null,
                resumable = true,
                createdAt = runtimeCreatedAt,
                updatedAt = now
            )
        )
    }

    private suspend fun markRuntimeTerminal(
        status: String,
        progressText: String,
        errorMessage: String? = null
    ) {
        val existing = AiRuntimeJobStore.getByJobKey(applicationContext, DATASET_RUNTIME_JOB_KEY) ?: return
        AiRuntimeJobStore.markState(
            context = applicationContext,
            jobId = existing.jobId,
            status = status,
            checkpointJson = existing.checkpointJson,
            progressText = progressText,
            errorMessage = errorMessage
        )
    }

    private fun ensureForegroundStarted(status: String, projectId: Long?): Boolean {
        return ensureForegroundStarted(
            status = status,
            projectId = projectId,
            startSource = "direct",
            requestedAction = null
        )
    }

    private fun ensureForegroundStarted(
        status: String,
        projectId: Long?,
        startSource: String,
        requestedAction: String?
    ): Boolean {
        activeProjectId = projectId ?: activeProjectId
        if (!foregroundStarted) {
            acquireWakeLock()
            val startResult = ForegroundStart.start(
                this,
                NOTIFICATION_ID,
                buildNotification(progress = DatasetProcessor.progress.value, status = status),
                ForegroundServiceTypes.DATA_SYNC
            )
            if (startResult is ForegroundStart.Result.Failed) {
                releaseWakeLock()
                val title = getString(R.string.dataset_runtime_notification_title)
                val message = ForegroundStart.failureMessage(this, startResult.reason, title)
                val failedTaskId = UnifiedNotificationManager.startTask(
                    UnifiedNotificationManager.TaskType.KNOWLEDGE_BASE,
                    title
                )
                UnifiedNotificationManager.failTask(failedTaskId, message)
                recordBreadcrumb(
                    event = "foreground_start_failed",
                    phase = requestedAction,
                    details = "source=$startSource reason=${startResult.reason}"
                )
                stopSelfIfIdle()
                return false
            }
            foregroundStarted = true
            isRunning = true
            recordBreadcrumb(
                event = "foreground_started",
                phase = requestedAction,
                details = "source=$startSource projectId=${activeProjectId ?: -1}"
            )
            return true
        }
        updateForegroundNotification(DatasetProcessor.progress.value, overrideStatus = status)
        return true
    }

    private fun updateForegroundNotification(
        progress: DatasetProcessor.Progress?,
        overrideStatus: String? = null
    ) {
        val notification = buildNotification(progress = progress, status = overrideStatus)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            DebugLog.log("[$TAG] Notification permission missing for dataset runtime update")
        }
    }

    private fun buildNotification(
        progress: DatasetProcessor.Progress?,
        status: String? = null
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
        .setContentTitle(getString(R.string.dataset_runtime_notification_title))
        .setContentText(
            status ?: when {
                progress == null -> getString(R.string.dataset_processing_notif)
                progress.total > 0 -> "${progress.stage}: ${progress.current}/${progress.total}"
                else -> progress.stage
            }
        )
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                progress?.currentItem?.takeIf { it.isNotBlank() }
                    ?: status
                    ?: getString(R.string.dataset_processing_notif)
            )
        )
        .setSubText(getString(R.string.dataset_runtime_queue_count, DatasetProcessor.jobQueue.value.size))
        .setContentIntent(buildOpenProjectPendingIntent())
        .addAction(
            android.R.drawable.ic_media_pause,
            getString(R.string.action_stop),
            buildStopPendingIntent()
        )
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setSilent(true)
        .setProgress(
            progress?.total?.takeIf { it > 0 } ?: 0,
            progress?.current ?: 0,
            progress?.total?.let { it <= 0 } ?: true
        )
        .build()

    private fun buildOpenProjectPendingIntent(): PendingIntent {
        val route = activeProjectId?.let(Screen.DatasetProject::createRoute) ?: Screen.Dataset.route
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, DatasetForegroundService::class.java).apply {
            action = ACTION_CANCEL_CURRENT
        }
        return PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dataset_runtime_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.dataset_runtime_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AI-Doomsday:DatasetForegroundService"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "AI-Doomsday:DatasetForegroundServiceWifi"
                )
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (error: Exception) {
            DebugLog.log("[$TAG] Failed to acquire runtime locks: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopSelfIfIdle() {
        if (DatasetProcessor.isProcessing.value) return
        if (foregroundStarted) {
            stopRuntimeService()
            return
        }
        stopSelf()
    }

    private fun stopRuntimeService() {
        val wasForeground = foregroundStarted
        foregroundStarted = false
        isRunning = false
        if (wasForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        runCatching {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        }
        releaseWakeLock()
        stopSelf()
    }
}

private fun buildCheckpointJson(
    activeJob: PersistedDatasetJob?,
    progress: DatasetProcessor.Progress?
): JSONObject = JSONObject().apply {
    put("activeJob", activeJob?.toJson())
    if (progress != null) {
        put("stage", progress.stage)
        put("current", progress.current)
        put("total", progress.total)
        put("currentItem", progress.currentItem)
        put("projectId", progress.projectId)
    }
}
