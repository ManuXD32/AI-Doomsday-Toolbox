package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadTaskArtifacts
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.library.SOURCE_IDENTITY_INVALIDATED_MARKER
import com.example.llamadroid.data.model.library.resolvedDownloadUrl
import com.example.llamadroid.data.model.library.sourceIsVerifiedForRecovery
import com.example.llamadroid.data.model.library.PendingArtifactStatus
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.library.ModelArtifactFinalizer
import com.example.llamadroid.data.model.library.ModelArtifactDiscardPolicy
import com.example.llamadroid.data.model.library.PendingArtifactRuntimeMetadata
import com.example.llamadroid.data.model.library.ensurePendingArtifactActive
import com.example.llamadroid.data.model.downloadPartFile
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.data.model.toDownloadTaskEntity
import com.example.llamadroid.data.model.toPendingDownload
import com.example.llamadroid.data.model.isStableDiffusionArtifact
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_HF_TREE_BUNDLE
import com.example.llamadroid.onnx.OnnxCatalog
import com.example.llamadroid.onnx.OnnxBundleValidator
import com.example.llamadroid.onnx.OnnxImportSupport
import com.example.llamadroid.onnx.OnnxTtsBundleValidator
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val cancellablePendingStatuses = setOf(
    PendingArtifactStatus.STAGED.storedValue,
    PendingArtifactStatus.INSPECTING.storedValue,
    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
    PendingArtifactStatus.VALIDATED.storedValue,
    PendingArtifactStatus.FAILED.storedValue
)

/** State shared by a task's cancellation/discard operation and retry waiters. */
private class DownloadCleanupState(
    /** Monotonic process-local order at which this cleanup was claimed. */
    val startedGeneration: Long
) {
    val completed = CompletableDeferred<Unit>()

    /** Latest cancel/discard intent folded into this cleanup owner. */
    @Volatile var latestCancellationGeneration: Long = startedGeneration

    /** Set synchronously when Discard arrives while Cancel is still unwinding. */
    @Volatile var discardRequested: Boolean = false

    /** A cancel-all/discarded generation must not be revived by an old retry tap. */
    @Volatile var suppressQueuedRetries: Boolean = false

    /** Prevents a late Discard from being folded into already-settled cleanup. */
    @Volatile var finishing: Boolean = false
}

private data class DownloadCleanupClaim(
    val state: DownloadCleanupState,
    val owner: Boolean,
    val startReservation: CompletableDeferred<Job>? = null
)

private data class DownloadStartClaim(
    val owner: Boolean,
    val activeJob: Job? = null,
    val startReservation: CompletableDeferred<Job>? = null,
    val cleanupState: DownloadCleanupState? = null,
    val cancelAllState: DownloadCleanupState? = null
)

private data class CancelAllClaim(
    val state: DownloadCleanupState,
    val owner: Boolean,
    val startReservations: List<CompletableDeferred<Job>>
)

/**
 * Small pure policy boundary for the service's retry races. Keeping the
 * ordering decision separate makes the newer-cancel and cancel-all contracts
 * testable without starting a foreground service or touching user files.
 */
internal object DownloadRetryGate {
    fun cleanupStartedBeforeRetry(cleanupGeneration: Long?, retryGeneration: Long): Boolean =
        cleanupGeneration != null && cleanupGeneration < retryGeneration

    fun newerCleanupWins(cleanupGeneration: Long?, retryGeneration: Long): Boolean =
        cleanupGeneration != null && cleanupGeneration > retryGeneration

    fun requestInvalidated(
        requestEpoch: Long,
        currentEpoch: Long,
        cancelAllActive: Boolean
    ): Boolean = requestEpoch != currentEpoch || cancelAllActive
}

/** Cancel-all selection policy that leaves completed Unknown/manual rows visible. */
internal object DownloadCancelAllPolicy {
    fun isActiveTaskStatus(status: String): Boolean = status == DOWNLOAD_TASK_STATUS_ACTIVE ||
        status == DOWNLOAD_TASK_STATUS_RESUMABLE

    fun isQueuedArtifactStatus(status: String): Boolean =
        status == PendingArtifactStatus.STAGED.storedValue ||
            status == PendingArtifactStatus.INSPECTING.storedValue
}

/**
 * Foreground service for downloading models.
 * Keeps downloads running when app is backgrounded.
 */
class DownloadService : Service() {
    
    companion object {
        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_CANCEL_ALL = "cancel_all"
        const val ACTION_DISCARD_DOWNLOAD = "discard_download"
        const val ACTION_DISCARD_PENDING_ARTIFACT = "discard_pending_artifact"
        
        const val EXTRA_URL = "url"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_EXPLICIT_RETRY = "explicit_retry"
        const val EXTRA_RETRY_REQUESTED_AT = "retry_requested_at"
        const val EXTRA_PRESERVE_RETRY_TIMESTAMP = "preserve_retry_timestamp"
        const val EXTRA_PENDING_ARTIFACT_ID = "pending_artifact_id"
        
        private val activeDownloads = mutableMapOf<String, Job>()
        /** Serializes cancellation/retry for one durable task. */
        private val taskLocks = mutableMapOf<String, Mutex>()
        /** Coalesces retry intents received while the cancelled worker unwinds. */
        private val queuedRetryIds = mutableSetOf<String>()
        /** Reserves a task ID before constructing its lazy Job. */
        private val startReservations = mutableMapOf<String, CompletableDeferred<Job>>()
        /** Keeps retries behind the complete cancel/discard cleanup transaction. */
        private val cleanupStates = mutableMapOf<String, DownloadCleanupState>()
        /**
         * Retains the latest cancellation order after a completed cleanup
         * state is removed. Entries live only while a retry/cleanup can still
         * observe them, preventing a repeated Cancel from reviving an old
         * queued retry.
         */
        private val lastCancelGenerations = mutableMapOf<String, Long>()
        /** Blocks new starts while cancel-all persists and drains every task. */
        private var cancelAllState: DownloadCleanupState? = null
        /** Invalidates retry requests that were queued before cancel-all. */
        private var retryEpoch: Long = 0L
        /** Orders service-side retry and cleanup intents without wall-clock drift. */
        private var operationGeneration: Long = 0L
        
        fun startDownload(
            context: Context,
            url: String,
            destPath: String,
            filename: String,
            downloadId: String = filename,
            explicitRetry: Boolean = false,
            retryRequestedAt: Long = System.currentTimeMillis(),
            /** Bundle/repository retries keep the durable timestamp guard. */
            preserveRetryTimestamp: Boolean = explicitRetry
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DEST_PATH, destPath)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_EXPLICIT_RETRY, explicitRetry)
                putExtra(EXTRA_RETRY_REQUESTED_AT, retryRequestedAt)
                putExtra(EXTRA_PRESERVE_RETRY_TIMESTAMP, preserveRetryTimestamp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun cancelDownload(context: Context, filename: String, downloadId: String = filename) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun resumeDownload(
            context: Context,
            downloadId: String,
            explicitRetry: Boolean = false,
            retryRequestedAt: Long = System.currentTimeMillis(),
            /** Direct UI retry can use the service generation when queued behind Cancel. */
            preserveRetryTimestamp: Boolean = false
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_EXPLICIT_RETRY, explicitRetry)
                putExtra(EXTRA_RETRY_REQUESTED_AT, retryRequestedAt)
                putExtra(EXTRA_PRESERVE_RETRY_TIMESTAMP, preserveRetryTimestamp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun discardDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DISCARD_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        /** Discards one Unknown artifact, including rows whose task is complete. */
        fun discardPendingArtifact(context: Context, pendingArtifactId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DISCARD_PENDING_ARTIFACT
                putExtra(EXTRA_PENDING_ARTIFACT_ID, pendingArtifactId)
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationTaskId: Int? = null

    private fun taskMutexFor(taskId: String): Mutex = synchronized(taskLocks) {
        taskLocks.getOrPut(taskId) { Mutex() }
    }
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH)
                val filename = intent.getStringExtra(EXTRA_FILENAME)
                val explicitRetry = intent.getBooleanExtra(EXTRA_EXPLICIT_RETRY, false)
                val retryRequestedAt = intent.getLongExtra(EXTRA_RETRY_REQUESTED_AT, 0L)
                    .takeIf { it > 0L }
                val preserveRetryTimestamp = intent.getBooleanExtra(
                    EXTRA_PRESERVE_RETRY_TIMESTAMP,
                    explicitRetry
                )
                
                val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.DOWNLOAD,
                    filename ?: downloadId ?: getString(R.string.models_tab_downloading)
                )
                notificationTaskId = taskId
                startForeground(taskId, notification)
                startDownloadInternal(
                    url = url,
                    destPath = destPath,
                    filename = filename,
                    downloadId = downloadId,
                    explicitRetry = explicitRetry,
                    retryRequestedAt = retryRequestedAt,
                    preserveRetryTimestamp = preserveRetryTimestamp
                )
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: filename
                cancelDownloadInternal(filename, downloadId)
            }
            ACTION_CANCEL_ALL -> {
                cancelAllDownloadsInternal()
            }
            ACTION_DISCARD_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                discardDownloadInternal(downloadId)
            }
            ACTION_DISCARD_PENDING_ARTIFACT -> {
                val pendingArtifactId = intent.getStringExtra(EXTRA_PENDING_ARTIFACT_ID)
                    ?: return START_NOT_STICKY
                discardPendingArtifactInternal(pendingArtifactId)
            }
        }
        return START_NOT_STICKY
    }

    /** Claims a task ID before constructing its lazy worker. */
    private fun claimDownloadStart(taskId: String): DownloadStartClaim = synchronized(activeDownloads) {
        cancelAllState?.takeIf { !it.completed.isCompleted }?.let { state ->
            return@synchronized DownloadStartClaim(owner = false, cancelAllState = state)
        }
        cleanupStates[taskId]?.takeIf { !it.completed.isCompleted }?.let { state ->
            return@synchronized DownloadStartClaim(owner = false, cleanupState = state)
        }
        if (cleanupStates[taskId]?.completed?.isCompleted == true) {
            cleanupStates.remove(taskId)
        }
        activeDownloads[taskId]?.takeIf { !it.isCompleted }?.let { job ->
            return@synchronized DownloadStartClaim(owner = false, activeJob = job)
        }
        startReservations[taskId]?.let { reservation ->
            return@synchronized DownloadStartClaim(owner = false, startReservation = reservation)
        }
        val reservation = CompletableDeferred<Job>()
        startReservations[taskId] = reservation
        DownloadStartClaim(owner = true, startReservation = reservation)
    }

    /** Increments the service-local ordering clock while holding activeDownloads. */
    private fun nextOperationGenerationLocked(): Long {
        operationGeneration = if (operationGeneration == Long.MAX_VALUE) 1L else operationGeneration + 1L
        return operationGeneration
    }

    /**
     * Queues one explicit retry behind the prior worker and all cleanup gates.
     * The request epoch prevents cancel-all from reviving a retry that was
     * already waiting when the user cancelled every download.
     */
    private fun queueExplicitRetry(
        taskId: String,
        url: String?,
        destPath: String?,
        filename: String?,
        downloadId: String?,
        retryRequestedAt: Long?,
        preserveRetryTimestamp: Boolean,
        retryGeneration: Long,
        previousJob: Job? = null,
        startReservation: CompletableDeferred<Job>? = null,
        cleanupState: DownloadCleanupState? = null
    ) {
        val requestEpoch = synchronized(activeDownloads) {
            if (cancelAllState?.completed?.isCompleted == false) return@synchronized null
            if (!queuedRetryIds.add(taskId)) return@synchronized null
            retryEpoch
        } ?: return
        serviceScope.launch {
            try {
                val prior = previousJob ?: startReservation?.await()
                prior?.join()
                val state = cleanupState ?: synchronized(activeDownloads) { cleanupStates[taskId] }
                state?.completed?.await()
                val cleanupStartedBeforeRetry = DownloadRetryGate.cleanupStartedBeforeRetry(
                    state?.startedGeneration,
                    retryGeneration
                )
                val latestCancellation = synchronized(activeDownloads) {
                    latestCancellationGenerationLocked(taskId, state)
                }
                val newerCleanupWon = DownloadRetryGate.newerCleanupWins(
                    latestCancellation,
                    retryGeneration
                )
                val invalidated = synchronized(activeDownloads) {
                    DownloadRetryGate.requestInvalidated(
                        requestEpoch = requestEpoch,
                        currentEpoch = retryEpoch,
                        cancelAllActive = cancelAllState?.completed?.isCompleted == false
                    )
                }
                if (invalidated || newerCleanupWon || state?.suppressQueuedRetries == true) return@launch
                startDownloadInternal(
                    url = url,
                    destPath = destPath,
                    filename = filename,
                    downloadId = downloadId,
                    explicitRetry = true,
                    retryRequestedAt = retryRequestedAt.takeUnless {
                        cleanupStartedBeforeRetry && !preserveRetryTimestamp
                    },
                    preserveRetryTimestamp = preserveRetryTimestamp,
                    retryGeneration = retryGeneration
                )
            } finally {
                synchronized(activeDownloads) {
                    queuedRetryIds.remove(taskId)
                    val state = cleanupStates[taskId]
                    if (state?.completed?.isCompleted == true && taskId !in queuedRetryIds) {
                        cleanupStates.remove(taskId)
                    }
                    val latestCancellation = lastCancelGenerations[taskId]
                    if (taskId !in queuedRetryIds &&
                        cleanupStates[taskId] == null &&
                        (latestCancellation == null || latestCancellation <= retryGeneration)
                    ) {
                        lastCancelGenerations.remove(taskId)
                    }
                }
                stopWhenNoActiveDownloads()
            }
        }
    }

    /** Starts one cancellation/discard owner and synchronously blocks new starts. */
    private fun beginTaskCleanup(taskId: String, discard: Boolean): DownloadCleanupClaim =
        synchronized(activeDownloads) {
            cleanupStates[taskId]?.takeIf { !it.completed.isCompleted && !it.finishing }?.let { state ->
                val generation = nextOperationGenerationLocked()
                state.latestCancellationGeneration = maxOf(
                    state.latestCancellationGeneration,
                    generation
                )
                lastCancelGenerations[taskId] = generation
                if (discard) state.discardRequested = true
                return@synchronized DownloadCleanupClaim(state = state, owner = false)
            }
            cleanupStates[taskId]?.takeIf { !it.completed.isCompleted && it.finishing && !discard }?.let { state ->
                val generation = nextOperationGenerationLocked()
                state.latestCancellationGeneration = maxOf(
                    state.latestCancellationGeneration,
                    generation
                )
                lastCancelGenerations[taskId] = generation
                return@synchronized DownloadCleanupClaim(state = state, owner = false)
            }
            val generation = nextOperationGenerationLocked()
            val state = DownloadCleanupState(
                startedGeneration = generation
            ).apply {
                discardRequested = discard
            }
            cleanupStates[taskId] = state
            lastCancelGenerations[taskId] = generation
            DownloadCleanupClaim(
                state = state,
                owner = true,
                startReservation = startReservations[taskId]
            )
        }

    /** Binds filename-based cancellation to the durable task ID once resolved. */
    private fun bindCleanupAlias(taskId: String, state: DownloadCleanupState) {
        synchronized(activeDownloads) {
            cleanupStates.putIfAbsent(taskId, state)
            val latest = lastCancelGenerations[taskId]
            if (latest == null || latest < state.latestCancellationGeneration) {
                lastCancelGenerations[taskId] = state.latestCancellationGeneration
            }
        }
    }

    /** Reads both the original key and a resolved task alias under the map lock. */
    private fun latestCancellationGenerationLocked(
        taskId: String,
        state: DownloadCleanupState? = cleanupStates[taskId]
    ): Long? {
        val values = buildList {
            lastCancelGenerations[taskId]?.let(::add)
            state?.latestCancellationGeneration?.let(::add)
            cleanupStates[taskId]?.latestCancellationGeneration?.let(::add)
        }
        return values.maxOrNull()
    }

    /** Completes the gate only after all state, progress, and files are settled. */
    private fun completeTaskCleanup(state: DownloadCleanupState, suppressRetries: Boolean = false) {
        synchronized(activeDownloads) {
            state.suppressQueuedRetries = state.suppressQueuedRetries || suppressRetries || state.discardRequested
            state.finishing = true
            state.completed.complete(Unit)
            cleanupStates.entries
                // A retry waiter owns the task ID until it has observed the
                // completed cleanup gate. Keep that alias visible so a late
                // start cannot race the cancellation sentinel out of the
                // state map and create a second worker.
                .filter { (key, value) ->
                    value === state && key !in queuedRetryIds
                }
                .map { it.key }
                .forEach { key ->
                    cleanupStates.remove(key)
                    val latest = lastCancelGenerations[key]
                    if (key !in queuedRetryIds &&
                        (latest == null || latest <= state.latestCancellationGeneration)
                    ) {
                        lastCancelGenerations.remove(key)
                    }
                }
        }
        stopWhenNoActiveDownloads()
    }

    /** Installs a process-wide cancellation gate before cancel-all does async DB work. */
    private fun beginCancelAll(): CancelAllClaim = synchronized(activeDownloads) {
        cancelAllState?.takeIf { !it.completed.isCompleted }?.let { state ->
            return@synchronized CancelAllClaim(state = state, owner = false, startReservations = emptyList())
        }
        val state = DownloadCleanupState(
            startedGeneration = nextOperationGenerationLocked()
        ).apply { suppressQueuedRetries = true }
        cancelAllState = state
        retryEpoch += 1L
        CancelAllClaim(
            state = state,
            owner = true,
            startReservations = startReservations.values.toList()
        )
    }

    private fun completeCancelAll(state: DownloadCleanupState) {
        synchronized(activeDownloads) {
            state.suppressQueuedRetries = true
            state.completed.complete(Unit)
            if (cancelAllState === state) cancelAllState = null
        }
        stopWhenNoActiveDownloads()
    }
    
    private fun startDownloadInternal(
        url: String?,
        destPath: String?,
        filename: String?,
        downloadId: String?,
        explicitRetry: Boolean = false,
        retryRequestedAt: Long? = null,
        preserveRetryTimestamp: Boolean = explicitRetry,
        retryGeneration: Long? = null
    ) {
        val resolvedTaskId = downloadId ?: filename ?: destPath ?: return
        val requestGeneration = if (explicitRetry) {
            retryGeneration ?: synchronized(activeDownloads) { nextOperationGenerationLocked() }
        } else {
            null
        }
        val claim = claimDownloadStart(resolvedTaskId)
        if (!claim.owner) {
            if (explicitRetry && claim.cancelAllState == null) {
                queueExplicitRetry(
                    taskId = resolvedTaskId,
                    url = url,
                    destPath = destPath,
                    filename = filename,
                    downloadId = downloadId,
                    retryRequestedAt = retryRequestedAt,
                    preserveRetryTimestamp = preserveRetryTimestamp,
                    retryGeneration = requireNotNull(requestGeneration),
                    previousJob = claim.activeJob,
                    startReservation = claim.startReservation,
                    cleanupState = claim.cleanupState
                )
            }
            return
        }
        val startReservation = requireNotNull(claim.startReservation)
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val db = AppDatabase.getDatabase(this@DownloadService)
            val taskDao = db.downloadTaskDao()
            val taskMutex = taskMutexFor(resolvedTaskId)
            taskMutex.withLock {
            val storedTask = taskDao.getById(resolvedTaskId)
                ?: filename?.let { taskDao.getByFilename(it) }
            val memoryPending = PendingDownloadHolder.getPending(resolvedTaskId)
                ?: filename?.let { PendingDownloadHolder.getPending(it) }
            val pending = memoryPending ?: storedTask?.toPendingDownload()
            val finalUrl = url ?: storedTask?.url ?: return@launch
            val finalDestPath = destPath ?: storedTask?.destPath ?: pending?.destPath ?: return@launch
            val finalFilename = filename ?: storedTask?.filename ?: pending?.filename ?: File(finalDestPath).name
            val destFile = File(finalDestPath)
            val progressKey = pending?.progressKey ?: storedTask?.progressKey ?: resolvedTaskId
            val candidateTask = pending?.toDownloadTaskEntity(resolvedTaskId, finalUrl)
                ?: storedTask?.copy(status = DOWNLOAD_TASK_STATUS_ACTIVE, updatedAt = System.currentTimeMillis())
                ?: DownloadTaskEntity(
                    id = resolvedTaskId,
                    url = finalUrl,
                    destPath = finalDestPath,
                    filename = finalFilename,
                    repoId = finalUrl,
                    progressKey = progressKey,
                    modelType = ModelType.LLM.name
                )
            val libraryDao = db.modelLibraryDao()
            val armedTask: DownloadTaskEntity? = db.withTransaction {
                val artifactId = pending?.pendingArtifactId ?: storedTask?.pendingArtifactId
                val artifact = artifactId?.let { libraryDao.getPendingArtifactById(it) }
                val newerCleanupWon = if (explicitRetry) {
                    val generation = requestGeneration
                    generation != null && synchronized(activeDownloads) {
                        DownloadRetryGate.newerCleanupWins(
                            latestCancellationGenerationLocked(resolvedTaskId),
                            generation
                        )
                    }
                } else {
                    false
                }
                if (newerCleanupWon) {
                    // A cancellation claimed after this retry intent is a
                    // newer operation even when the DB clock has not advanced
                    // monotonically across threads. Leave its sentinel intact.
                    return@withTransaction null
                }
                if (storedTask?.status == DOWNLOAD_TASK_STATUS_CANCELLED && !explicitRetry) {
                    // A task cancellation is also a durable no-resume
                    // sentinel. Keep the linked artifact terminal so startup
                    // recovery cannot silently revive it.
                    if (artifact != null && artifact.status in cancellablePendingStatuses) {
                        libraryDao.upsert(
                            artifact.copy(
                                status = PendingArtifactStatus.CANCELLED.storedValue,
                                validationMessage = null,
                                updatedAt = nextStateTimestamp(artifact.updatedAt)
                            )
                        )
                    }
                    taskDao.upsert(candidateTask.copy(status = DOWNLOAD_TASK_STATUS_CANCELLED))
                    return@withTransaction null
                }
                if (artifact != null) {
                    // Recheck inside the arming transaction: a source edit may
                    // have won after repository validation or startup recovery.
                    artifact.sourceId?.let { sourceId ->
                        val source = libraryDao.getSourceById(sourceId)
                        if (!sourceIsVerifiedForRecovery(source) || source?.resolvedDownloadUrl() != finalUrl) {
                            if (artifact.status in cancellablePendingStatuses) {
                                val error = getString(R.string.model_library_error_not_verified)
                                libraryDao.upsert(artifact.copy(
                                    status = PendingArtifactStatus.FAILED.storedValue,
                                    validationJson = if (source?.resolvedDownloadUrl() != finalUrl)
                                        SOURCE_IDENTITY_INVALIDATED_MARKER else artifact.validationJson,
                                    validationMessage = error,
                                    updatedAt = nextStateTimestamp(artifact.updatedAt)
                                ))
                                taskDao.upsert(candidateTask.copy(status = DOWNLOAD_TASK_STATUS_FAILED,
                                    lastError = error, updatedAt = System.currentTimeMillis()))
                            }
                            return@withTransaction null
                        }
                    }
                    when (artifact.status) {
                        PendingArtifactStatus.CANCELLED.storedValue,
                        PendingArtifactStatus.FAILED.storedValue -> {
                            if (!explicitRetry) {
                                // A cancelled/failed stage-only row is a
                                // durable sentinel. Startup recovery and a
                                // normal resume must not reopen it implicitly.
                                taskDao.upsert(
                                    candidateTask.copy(
                                        // Preserve FAILED as a failure sentinel;
                                        // only an actual cancellation should
                                        // change the task to CANCELLED.
                                        status = if (artifact.status == PendingArtifactStatus.FAILED.storedValue) {
                                            DOWNLOAD_TASK_STATUS_FAILED
                                        } else {
                                            DOWNLOAD_TASK_STATUS_CANCELLED
                                        },
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                                return@withTransaction null
                            }
                            if (preserveRetryTimestamp && retryRequestedAt != null &&
                                artifact.updatedAt > retryRequestedAt
                            ) {
                                // A cancellation written after the retry
                                // intent wins over that stale intent.
                                throw CancellationException("Staged artifact was cancelled after retry was requested")
                            }
                            if (artifact.validationJson == SOURCE_IDENTITY_INVALIDATED_MARKER) {
                                // The prior worker and cancellation cleanup have
                                // drained before this task lock is acquired. Only
                                // exact managed, unregistered old files are removed;
                                // keep the marker until cleanup and arming succeed.
                                deleteManagedPendingArtifactFiles(db, artifact, storedTask)
                            }
                            libraryDao.upsert(
                                artifact.copy(
                                    status = PendingArtifactStatus.STAGED.storedValue,
                                    detectedFamily = null,
                                    detectedRole = null,
                                    detectedType = null,
                                    validationJson = null,
                                    validationMessage = null,
                                    promotedModelKey = null,
                                    promotedAt = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        PendingArtifactStatus.PROMOTED.storedValue ->
                            throw CancellationException("Staged artifact has already been promoted")
                    }
                }
                taskDao.upsert(candidateTask.copy(status = DOWNLOAD_TASK_STATUS_ACTIVE, updatedAt = System.currentTimeMillis()))
                candidateTask.copy(status = DOWNLOAD_TASK_STATUS_ACTIVE)
            }
            val persistedTask = armedTask
                ?: throw CancellationException("Download was cancelled; explicit retry is required")
            PendingDownloadHolder.addPendingFrom(persistedTask)
            DownloadProgressHolder.updateProgress(progressKey, finalFilename, initialProgressFor(destFile))

            destFile.parentFile?.mkdirs()
            var lastProgress = 0
            var downloadSuccess = false
            var completionError: String? = null

            if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_HF_TREE_BUNDLE) {
                try {
                    val db = AppDatabase.getDatabase(this@DownloadService)
                    val entity = downloadPendingHfTreeBundle(
                        pending = pending,
                        onProgress = { progress, label ->
                            val progressPercent = (progress * 100).toInt()
                            DownloadProgressHolder.updateProgress(progressKey, progress)
                            DownloadProgressHolder.updateStatus(progressKey, label)
                            serviceScope.launch {
                                runCatching {
                                    db.withTransaction {
                                        pending.pendingArtifactId?.let { artifactId ->
                                            ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                                        }
                                        taskDao.updateState(
                                            id = resolvedTaskId,
                                            status = DOWNLOAD_TASK_STATUS_ACTIVE,
                                            bytesDownloaded = persistedTask.partFile().length(),
                                            totalBytes = null,
                                            lastError = null
                                        )
                                    }
                                }
                            }
                            if (progressPercent >= lastProgress + 5 || progress >= 1f) {
                                lastProgress = progressPercent
                                updateNotification(label, progressPercent)
                            }
                        }
                    )
                    db.withTransaction {
                        pending.pendingArtifactId?.let { artifactId ->
                            ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                        }
                        db.modelDao().insertModel(entity)
                    }
                    DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                    DownloadProgressHolder.removeProgress(progressKey)
                    db.withTransaction {
                        pending.pendingArtifactId?.let { artifactId ->
                            ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                        }
                        taskDao.updateState(resolvedTaskId, DOWNLOAD_TASK_STATUS_COMPLETED, 0L, null, null)
                        taskDao.deleteById(resolvedTaskId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log("DownloadService: Failed to download ONNX tree bundle - ${e.message}")
                    completionError = e.message ?: "Failed to finalize download"
                    DownloadProgressHolder.updateProgress(progressKey, -1f)
                    DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_failed))
                    taskDao.updateState(
                        resolvedTaskId,
                        DOWNLOAD_TASK_STATUS_FAILED,
                        persistedTask.partFile().length(),
                        null,
                        completionError
                    )
                    DownloadProgressHolder.removeProgress(progressKey)
                } finally {
                    PendingDownloadHolder.removePending(resolvedTaskId)
                    removeActiveDownload(resolvedTaskId, currentCoroutineContext()[Job])
                    if (synchronized(activeDownloads) { activeDownloads.isEmpty() }) {
                        completionError?.let { error ->
                            notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                        } ?: updateNotification(getString(R.string.downloads_complete), 100)
                        stopWhenNoActiveDownloadsAfterDelay()
                    }
                }
                return@launch
            }
            
            Downloader.download(
                url = finalUrl,
                destFile = destFile,
                context = this@DownloadService,
                bearerToken = pending?.huggingFaceToken,
                downloadId = resolvedTaskId,
                preservePartialOnCancel = pending?.stageOnly == true
            )
                .catch { e ->
                    DebugLog.log("DownloadService: Download failed - ${e.message}")
                    if (e is CancellationException) {
                        // Cancellation is a control path. Let the service job
                        // unwind without turning a user-cancelled artifact into
                        // FAILED or RESUMABLE.
                        throw e
                    }
                    val db = AppDatabase.getDatabase(this@DownloadService)
                    val wasCancelled = recordDownloadFailure(
                        db = db,
                        taskId = resolvedTaskId,
                        destPath = finalDestPath,
                        pending = pending,
                        error = e.message
                    )
                    if (wasCancelled) {
                        PendingDownloadHolder.removePending(resolvedTaskId)
                    } else {
                        val failureStatus = if (pending?.liteRtDisplayName != null && e.isHuggingFaceAccessFailure()) {
                            if (pending.huggingFaceToken.isNullOrBlank()) {
                                getString(R.string.litert_hf_token_required_error, e.huggingFaceStatusCode())
                            } else {
                                getString(R.string.litert_hf_access_denied_error, e.huggingFaceStatusCode())
                            }
                        } else {
                            getString(R.string.onnx_models_download_failed)
                        }
                        DownloadProgressHolder.updateProgress(progressKey, -1f)
                        DownloadProgressHolder.updateStatus(progressKey, failureStatus)
                        PendingDownloadHolder.removePending(resolvedTaskId)
                    }
                }
                .collect { progress ->
                    if (pending?.stageOnly == true && pending.pendingArtifactId != null &&
                        AppDatabase.getDatabase(this@DownloadService).modelLibraryDao()
                            .getPendingArtifactById(pending.pendingArtifactId)?.status == PendingArtifactStatus.CANCELLED.storedValue
                    ) {
                        throw CancellationException("Staged download was cancelled")
                    }
                    val mappedProgress = if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        if (progress >= 0f) progress * 0.9f else progress
                    } else {
                        progress
                    }
                    DownloadProgressHolder.updateProgress(progressKey, mappedProgress)
                    if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_downloading))
                    }
                    db.withTransaction {
                        pending?.pendingArtifactId?.let { artifactId ->
                            ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                        }
                        taskDao.updateState(
                            id = resolvedTaskId,
                            status = DOWNLOAD_TASK_STATUS_ACTIVE,
                            bytesDownloaded = downloadPartFile(finalDestPath).length(),
                            totalBytes = null,
                            lastError = null
                        )
                    }
                    val progressPercent = if (mappedProgress >= 0f) (mappedProgress * 100).toInt() else lastProgress
                    if (mappedProgress >= 0f && (progressPercent >= lastProgress + 5 || progress == 1f)) {
                        lastProgress = progressPercent
                        updateNotification(finalFilename, progressPercent)
                    }
                    if (progress >= 1f) {
                        downloadSuccess = true
                    }
                }
            
            // Download complete - save to DB if pending
            if (downloadSuccess) {
                if (pending?.stageOnly == true) {
                    try {
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        val pendingArtifactId = pending.pendingArtifactId
                            ?: error("Staged download is missing a pending artifact ID")
                        val artifact = ensurePendingArtifactActive(db.modelLibraryDao(), pendingArtifactId)
                        val stagedArtifact = artifact.copy(
                            stagingPath = destFile.absolutePath,
                            status = PendingArtifactStatus.STAGED.storedValue,
                            validationMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.modelLibraryDao().upsertActiveArtifact(stagedArtifact)
                        ModelArtifactFinalizer.finalizeIfKnown(
                            database = db,
                            artifact = stagedArtifact,
                            downloadedFile = destFile,
                            metadata = PendingArtifactRuntimeMetadata.fromPending(pending)
                        ).getOrThrow()
                        DownloadProgressHolder.updateProgress(progressKey, 1f)
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_completed))
                        db.withTransaction {
                            // The artifact guard and task completion share one
                            // transaction, so cancellation cannot be silently
                            // overwritten by a late downloader callback.
                            ensurePendingArtifactActive(db.modelLibraryDao(), pendingArtifactId)
                            taskDao.updateState(
                                id = resolvedTaskId,
                                status = DOWNLOAD_TASK_STATUS_COMPLETED,
                                bytesDownloaded = destFile.length(),
                                totalBytes = destFile.length(),
                                lastError = null
                            )
                            taskDao.deleteById(resolvedTaskId)
                        }
                        delay(1200)
                        DownloadProgressHolder.removeProgress(progressKey)
                    } catch (e: CancellationException) {
                        val pendingArtifactId = pending.pendingArtifactId
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        val wasCancelled = persistCancellationIfMarked(
                            db = db,
                            taskId = resolvedTaskId,
                            artifactId = pendingArtifactId,
                            bytes = destFile.length()
                        )
                        if (wasCancelled) {
                            // The cancellation sentinel is authoritative even
                            // though this coroutine is already cancelled.
                        } else {
                            throw e
                        }
                    } catch (e: Exception) {
                        completionError = e.message ?: "Failed to stage download"
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        val wasCancelled = recordDownloadFailure(
                            db = db,
                            taskId = resolvedTaskId,
                            destPath = finalDestPath,
                            pending = pending,
                            error = completionError,
                            bytesOverride = destFile.length()
                        )
                        if (!wasCancelled) {
                            DownloadProgressHolder.updateProgress(progressKey, -1f)
                            DownloadProgressHolder.updateStatus(progressKey, completionError ?: "Failed to stage download")
                        }
                    }
                    PendingDownloadHolder.removePending(resolvedTaskId)
                } else if (pending != null) {
                    try {
                        val genericCurated =
                            com.example.llamadroid.data.model.CuratedModelBundleRegistry
                                .fileForInstalledFilename(finalFilename)
                        val sdCurated =
                            com.example.llamadroid.data.model.SdCuratedBundleCatalog
                                .fileForLocalFilename(finalFilename)
                        if (genericCurated != null || sdCurated != null) {
                            val verifyingLabel = getString(R.string.sd_bundle_verifying)
                            DownloadProgressHolder.updateProgress(progressKey, 0.999f)
                            DownloadProgressHolder.updateStatus(progressKey, verifyingLabel)
                            updateNotification(verifyingLabel, 99)
                            if (genericCurated != null) {
                                com.example.llamadroid.data.model.verifyCuratedModelDownload(
                                    localFilename = finalFilename,
                                    downloadedFile = destFile
                                )
                            } else {
                                com.example.llamadroid.data.model.verifySdCuratedDownload(
                                    localFilename = finalFilename,
                                    downloadedFile = destFile
                                )
                            }
                        }
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        pending.pendingArtifactId?.let { artifactId ->
                            ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                        }
                        var lastFinalizePercent = -1
                        var lastFinalizeLabel: String? = null
                        val progressReporter: (Float, String) -> Unit = { progress, label ->
                            val progressPercent = (progress * 100).toInt()
                            val shouldReport =
                                label != lastFinalizeLabel ||
                                    progressPercent >= lastFinalizePercent + 2 ||
                                    progress >= 1f
                            if (shouldReport) {
                                lastFinalizePercent = progressPercent
                                lastFinalizeLabel = label
                                DownloadProgressHolder.updateProgress(progressKey, progress)
                                DownloadProgressHolder.updateStatus(progressKey, label)
                                updateNotification(label, progressPercent)
                            }
                        }
                        if (pending.liteRtDisplayName != null) {
                            LiteRtModelRepository(this@DownloadService, db.liteRtModelDao()).finalizeServiceDownload(
                                pending = pending,
                                downloadedFile = destFile,
                                onProgress = progressReporter
                            )
                            DebugLog.log("DownloadService: Saved $filename to LiteRT model DB")
                        } else {
                            val entity = finalizePendingDownload(
                                pending = pending,
                                downloadedFile = destFile,
                                onProgress = progressReporter
                            )
                            db.withTransaction {
                                pending.pendingArtifactId?.let { artifactId ->
                                    ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                                }
                                db.modelDao().insertModel(entity)
                            }
                            DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                        }
                        DownloadProgressHolder.updateProgress(progressKey, 1f)
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_completed))
                        db.withTransaction {
                            pending.pendingArtifactId?.let { artifactId ->
                                ensurePendingArtifactActive(db.modelLibraryDao(), artifactId)
                            }
                            taskDao.updateState(
                                id = resolvedTaskId,
                                status = DOWNLOAD_TASK_STATUS_COMPLETED,
                                bytesDownloaded = destFile.length(),
                                totalBytes = destFile.length(),
                                lastError = null
                            )
                            taskDao.deleteById(resolvedTaskId)
                        }
                        delay(1200)
                        DownloadProgressHolder.removeProgress(progressKey)
                    } catch (e: CancellationException) {
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        persistCancellationIfMarked(
                            db = db,
                            taskId = resolvedTaskId,
                            artifactId = pending.pendingArtifactId,
                            bytes = destFile.length()
                        )
                        throw e
                    } catch (e: Exception) {
                        DebugLog.log("DownloadService: Failed to save to DB - ${e.message}")
                        completionError = e.message ?: "Failed to finalize download"
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        val wasCancelled = recordDownloadFailure(
                            db = db,
                            taskId = resolvedTaskId,
                            destPath = finalDestPath,
                            pending = pending,
                            error = completionError,
                            bytesOverride = destFile.length()
                        )
                        if (!wasCancelled) DownloadProgressHolder.updateProgress(progressKey, -1f)
                    }
                    PendingDownloadHolder.removePending(resolvedTaskId)
                } else {
                    db.withTransaction {
                        // Ordinary downloads can still receive Cancel after
                        // the network flow emits 1. Do not overwrite a
                        // cancellation sentinel in this final write.
                        val current = taskDao.getById(resolvedTaskId)
                        if (current?.status == DOWNLOAD_TASK_STATUS_CANCELLED) {
                            throw CancellationException("Download was cancelled before completion was recorded")
                        }
                        taskDao.updateState(
                            id = resolvedTaskId,
                            status = DOWNLOAD_TASK_STATUS_COMPLETED,
                            bytesDownloaded = destFile.length(),
                            totalBytes = destFile.length(),
                            lastError = null
                        )
                        taskDao.deleteById(resolvedTaskId)
                    }
                }
            }
            
            removeActiveDownload(resolvedTaskId, currentCoroutineContext()[Job])
            if (synchronized(activeDownloads) { activeDownloads.isEmpty() }) {
                completionError?.let { error ->
                    notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                } ?: updateNotification(getString(R.string.downloads_complete), 100)
                stopWhenNoActiveDownloadsAfterDelay()
            }
            }
        }
        
        synchronized(activeDownloads) {
            // The reservation makes read/construct/register one logical
            // operation: a concurrent start cannot create a second worker
            // before this job is visible in activeDownloads.
            startReservations.remove(resolvedTaskId)?.complete(job)
            activeDownloads[resolvedTaskId] = job
        }
        job.invokeOnCompletion { removeActiveDownload(resolvedTaskId, job) }
        job.start()
    }

    private fun cancelDownloadInternal(filename: String, downloadId: String) {
        val cleanup = beginTaskCleanup(downloadId, discard = false)
        if (!cleanup.owner) return
        val memoryPending = PendingDownloadHolder.getPending(downloadId)
            ?: PendingDownloadHolder.getPending(filename)
        serviceScope.launch(NonCancellable) {
            try {
                // If a start was already constructing its lazy worker, wait
                // until registration publishes that worker before marking the
                // durable sentinel and looking it up for cancellation.
                cleanup.startReservation?.let { reservation ->
                    try {
                        reservation.await()
                    } catch (_: Throwable) {
                        // The start failed before registration; the DB
                        // sentinel below still makes recovery safe.
                    }
                }
                val db = AppDatabase.getDatabase(this@DownloadService)
                val cancellation = markDownloadCancelled(db, filename, downloadId, memoryPending)
                bindCleanupAlias(cancellation.taskId, cleanup.state)
                // The public API historically accepted either a filename or a
                // durable task ID. Resolve the job again after the transaction
                // so filename cancellation never targets a replacement job.
                val job = synchronized(activeDownloads) {
                    activeDownloads[cancellation.taskId] ?: activeDownloads[downloadId]
                }
                val progressKey = memoryPending?.progressKey
                    ?: cancellation.task?.progressKey
                    ?: downloadId
                DownloadProgressHolder.updateProgress(progressKey, -1f)
                DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_cancelled))

                // Persist the sentinel before interrupting OkHttp. This closes
                // the race in which a late callback observes ACTIVE/FAILED.
                job?.cancel()
                Downloader.cancelDownload(cancellation.taskId)
                runCatching { job?.join() }
                reassertDownloadCancelled(db, cancellation)

                if (!cancellation.stageOnly) {
                    cancellation.task?.let { DownloadTaskArtifacts.deleteIncompleteArtifacts(it) }
                    cancellation.pending?.stagingPath?.let(::deleteIncompleteFiles)
                }
                PendingDownloadHolder.removePending(cancellation.taskId)
                PendingDownloadHolder.removePending(downloadId)
                DownloadProgressHolder.removeProgress(progressKey)
                if (!cancellation.stageOnly) {
                    db.downloadTaskDao().deleteById(cancellation.taskId)
                }
                removeActiveDownload(cancellation.taskId, job)

                // Discard may arrive while this Cancel owner is unwinding. Do
                // the destructive part before releasing the shared gate, and
                // re-check the flag under the same lock used by beginTaskCleanup.
                var discarded = false
                while (true) {
                    val discardRequested = synchronized(activeDownloads) {
                        cleanup.state.discardRequested
                    }
                    if (discardRequested && !discarded) {
                        if (cancellation.stageOnly) {
                            deleteManagedPendingArtifactFiles(
                                db = db,
                                pending = cancellation.pending,
                                task = cancellation.task
                            )
                        } else {
                            cancellation.task?.let { DownloadTaskArtifacts.deleteIncompleteArtifacts(it) }
                            cancellation.pending?.stagingPath?.let(::deleteIncompleteFiles)
                        }
                        db.downloadTaskDao().deleteById(cancellation.taskId)
                        discarded = true
                    }
                    val settled = synchronized(activeDownloads) {
                        if (cleanup.state.discardRequested && !discarded) {
                            false
                        } else {
                            // A Discard arriving after this point starts a
                            // new owner rather than mutating this closing
                            // generation, so it cannot be missed here.
                            cleanup.state.finishing = true
                            true
                        }
                    }
                    if (settled) break
                }
                cleanup.state.suppressQueuedRetries = discarded
            } catch (failure: Throwable) {
                cleanup.state.suppressQueuedRetries = true
                DebugLog.log("DownloadService: cancellation cleanup failed - ${failure.message}")
            } finally {
                completeTaskCleanup(cleanup.state, cleanup.state.suppressQueuedRetries)
            }
        }
    }

    private fun discardDownloadInternal(
        downloadId: String,
        pendingArtifactId: String? = null
    ) {
        val cleanup = beginTaskCleanup(downloadId, discard = true)
        if (!cleanup.owner) return
        serviceScope.launch(NonCancellable) {
            try {
                cleanup.startReservation?.let { reservation ->
                    try {
                        reservation.await()
                    } catch (_: Throwable) {
                        // Continue to persist the cancellation sentinel even
                        // if the competing start failed before registration.
                    }
                }
                val db = AppDatabase.getDatabase(this@DownloadService)
                val cancellation = markDownloadCancelled(
                    db = db,
                    filename = null,
                    requestedTaskId = downloadId,
                    memoryPending = PendingDownloadHolder.getPending(downloadId),
                    requestedPendingArtifactId = pendingArtifactId
                )
                bindCleanupAlias(cancellation.taskId, cleanup.state)
                val job = synchronized(activeDownloads) {
                    activeDownloads[cancellation.taskId] ?: activeDownloads[downloadId]
                }
                job?.cancel()
                Downloader.cancelDownload(cancellation.taskId)
                runCatching { job?.join() }
                reassertDownloadCancelled(db, cancellation)
                if (cancellation.stageOnly) {
                    deleteManagedPendingArtifactFiles(
                        db = db,
                        pending = cancellation.pending,
                        task = cancellation.task
                    )
                } else {
                    cancellation.task?.let { DownloadTaskArtifacts.deleteIncompleteArtifacts(it) }
                    cancellation.pending?.stagingPath?.let(::deleteIncompleteFiles)
                }
                PendingDownloadHolder.removePending(cancellation.taskId)
                DownloadProgressHolder.removeProgress(
                    cancellation.task?.progressKey ?: cancellation.taskId
                )
                // The pending row is retained as a durable CANCELLED sentinel
                // so the library can offer an explicit retry, while the
                // discarded task and all partial bytes are removed.
                db.downloadTaskDao().deleteById(cancellation.taskId)
                removeActiveDownload(cancellation.taskId, job)
            } catch (failure: Throwable) {
                cleanup.state.suppressQueuedRetries = true
                DebugLog.log("DownloadService: discard cleanup failed - ${failure.message}")
            } finally {
                completeTaskCleanup(cleanup.state, suppressRetries = true)
            }
        }
    }

    /** Resolves a pending row before entering the task cleanup gate. */
    private fun discardPendingArtifactInternal(pendingArtifactId: String) {
        serviceScope.launch(NonCancellable) {
            val db = AppDatabase.getDatabase(this@DownloadService)
            val pending = db.modelLibraryDao().getPendingArtifactById(pendingArtifactId)
            val linkedTask = pending?.downloadTaskId?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { db.downloadTaskDao().getById(it) }
                ?.takeIf { it.pendingArtifactId == pendingArtifactId }
            val taskId = linkedTask?.id ?: "pending-artifact:$pendingArtifactId"
            discardDownloadInternal(taskId, pendingArtifactId = pendingArtifactId)
        }
    }

    /**
     * Deletes only a pending artifact's exact managed files. A task without a
     * pending row gets a synthetic pending target so stage-only cleanup still
     * follows the same root and runtime/provenance checks.
     */
    private suspend fun deleteManagedPendingArtifactFiles(
        db: AppDatabase,
        pending: PendingModelArtifactEntity?,
        task: DownloadTaskEntity?
    ) {
        db.withTransaction {
            val currentPending = pending?.id?.let { db.modelLibraryDao().getPendingArtifactById(it) }
            val effectivePending = currentPending ?: pending
            val target = effectivePending ?: task?.let {
                PendingModelArtifactEntity(
                    id = "discard-task:${it.id}",
                    downloadTaskId = it.id,
                    sourceId = it.sourceId,
                    filename = it.filename,
                    stagingPath = it.destPath,
                    destinationPath = it.destPath,
                    requestedFamily = it.artifactFamily,
                    requestedRole = it.artifactRole,
                    status = PendingArtifactStatus.CANCELLED.storedValue
                )
            } ?: return@withTransaction
            val candidates = ModelArtifactDiscardPolicy.deletionCandidates(
                this@DownloadService,
                target
            )
            ModelArtifactDiscardPolicy.requireNotRegistered(
                database = db,
                candidates = candidates,
                allowedPendingArtifactId = effectivePending?.id,
                allowedTaskId = task?.id
            )
            ModelArtifactDiscardPolicy.deleteFiles(candidates)
        }
    }

    private data class CancellationRecord(
        val taskId: String,
        val task: DownloadTaskEntity?,
        val pending: PendingModelArtifactEntity?,
        val stageOnly: Boolean
    )

    /**
     * Writes the cancellation sentinel before interrupting the active job.
     * This method deliberately runs inside a Room transaction so a task and
     * its pending artifact cannot disagree about whether a retry is allowed.
     */
    private suspend fun markDownloadCancelled(
        db: AppDatabase,
        filename: String?,
        requestedTaskId: String,
        memoryPending: PendingDownload?,
        requestedPendingArtifactId: String? = null
    ): CancellationRecord = db.withTransaction {
        val taskDao = db.downloadTaskDao()
        val libraryDao = db.modelLibraryDao()
        val task = taskDao.getById(requestedTaskId)
            ?: filename?.let { taskDao.getByFilename(it) }
        val taskId = task?.id ?: requestedTaskId
        val pending = requestedPendingArtifactId?.let { libraryDao.getPendingArtifactById(it) }
            ?: task?.pendingArtifactId?.let { libraryDao.getPendingArtifactById(it) }
            ?: libraryDao.getByDownloadTaskId(taskId)
            ?: memoryPending?.pendingArtifactId?.let { libraryDao.getPendingArtifactById(it) }
        val stageOnly = task?.stageOnly == true || pending != null || memoryPending?.stageOnly == true
        if (pending?.status == PendingArtifactStatus.PROMOTED.storedValue ||
            pending?.promotedModelKey != null
        ) {
            throw com.example.llamadroid.data.model.library.ModelLibraryException(
                com.example.llamadroid.data.model.library.ModelLibraryErrorCode.ARTIFACT_DISCARD_PROMOTED,
                "Promoted model files cannot be discarded from the Unknown library"
            )
        }
        val now = System.currentTimeMillis()
        if (pending != null && pending.status in cancellablePendingStatuses) {
            libraryDao.upsert(
                pending.copy(
                    status = PendingArtifactStatus.CANCELLED.storedValue,
                    validationMessage = null,
                    updatedAt = nextStateTimestamp(pending.updatedAt, now)
                )
            )
        }
        task?.let {
            if (it.status != DOWNLOAD_TASK_STATUS_COMPLETED) {
                taskDao.updateState(
                    id = taskId,
                    status = DOWNLOAD_TASK_STATUS_CANCELLED,
                    bytesDownloaded = it.partFile().length().coerceAtLeast(0L),
                    totalBytes = null,
                    lastError = null,
                    updatedAt = now
                )
            }
        }
        CancellationRecord(taskId, task, pending, stageOnly)
    }

    /** Re-checks terminal state after a cancelled worker has unwound. */
    private suspend fun reassertDownloadCancelled(
        db: AppDatabase,
        record: CancellationRecord
    ) = db.withTransaction {
        val taskDao = db.downloadTaskDao()
        val libraryDao = db.modelLibraryDao()
        libraryDao.getPendingArtifactById(record.pending?.id ?: "")?.let { current ->
            if (current.status in cancellablePendingStatuses) {
                libraryDao.upsert(
                    current.copy(
                        status = PendingArtifactStatus.CANCELLED.storedValue,
                        validationMessage = null,
                        updatedAt = nextStateTimestamp(current.updatedAt)
                    )
                )
            }
        }
        taskDao.getById(record.taskId)?.let { current ->
            if (current.status != DOWNLOAD_TASK_STATUS_COMPLETED) {
                taskDao.updateState(
                    id = current.id,
                    status = DOWNLOAD_TASK_STATUS_CANCELLED,
                    bytesDownloaded = current.partFile().length().coerceAtLeast(0L),
                    totalBytes = null,
                    lastError = null
                )
            }
        }
    }

    /**
     * Records a network/finalization failure only while the artifact remains
     * active. A cancellation sentinel always wins, even if OkHttp reports the
     * error after the user has pressed Cancel.
     */
    private suspend fun recordDownloadFailure(
        db: AppDatabase,
        taskId: String,
        destPath: String,
        pending: PendingDownload?,
        error: String?,
        bytesOverride: Long? = null
    ): Boolean = withContext(NonCancellable) {
        db.withTransaction {
            val taskDao = db.downloadTaskDao()
            val libraryDao = db.modelLibraryDao()
            val artifact = pending?.pendingArtifactId?.let { libraryDao.getPendingArtifactById(it) }
            val bytes = (bytesOverride ?: downloadPartFile(destPath).length()).coerceAtLeast(0L)
            if (artifact?.status == PendingArtifactStatus.CANCELLED.storedValue) {
                taskDao.updateState(
                    id = taskId,
                    status = DOWNLOAD_TASK_STATUS_CANCELLED,
                    bytesDownloaded = bytes,
                    totalBytes = null,
                    lastError = null
                )
                true
            } else {
                taskDao.updateState(
                    id = taskId,
                    status = if (bytes > 0L) DOWNLOAD_TASK_STATUS_RESUMABLE else DOWNLOAD_TASK_STATUS_FAILED,
                    bytesDownloaded = bytes,
                    totalBytes = null,
                    lastError = error
                )
                if (pending?.stageOnly == true && artifact != null &&
                    artifact.status in cancellablePendingStatuses
                ) {
                    libraryDao.upsert(
                        artifact.copy(
                            status = PendingArtifactStatus.FAILED.storedValue,
                            validationMessage = error?.take(512),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                false
            }
        }
    }

    private suspend fun ensurePendingArtifactActiveForCancellation(
        db: AppDatabase,
        artifactId: String?
    ) {
        if (artifactId == null) return
        val current = db.modelLibraryDao().getPendingArtifactById(artifactId)
        if (current?.status != PendingArtifactStatus.CANCELLED.storedValue) {
            throw CancellationException("Artifact cancellation sentinel was lost")
        }
    }

    private suspend fun persistCancellationIfMarked(
        db: AppDatabase,
        taskId: String,
        artifactId: String?,
        bytes: Long
    ): Boolean = withContext(NonCancellable) {
        db.withTransaction {
            val current = artifactId?.let { db.modelLibraryDao().getPendingArtifactById(it) }
            if (current?.status != PendingArtifactStatus.CANCELLED.storedValue) {
                false
            } else {
                ensurePendingArtifactActiveForCancellation(db, artifactId)
                db.downloadTaskDao().updateState(
                    id = taskId,
                    status = DOWNLOAD_TASK_STATUS_CANCELLED,
                    bytesDownloaded = bytes.coerceAtLeast(0L),
                    totalBytes = null,
                    lastError = null
                )
                true
            }
        }
    }

    /** Persists cancellation for every task, including tasks recovered after process death. */
    private fun cancelAllDownloadsInternal() {
        val claim = beginCancelAll()
        if (!claim.owner) return
        serviceScope.launch(NonCancellable) {
            try {
                // A start intent can have reserved a task ID while this
                // cancel-all request is being delivered. Let that reservation
                // publish (or fail) before collecting workers to drain.
                claim.startReservations.forEach { reservation ->
                    runCatching { reservation.await() }
                }
                val jobs = synchronized(activeDownloads) { activeDownloads.toMap() }
                val db = AppDatabase.getDatabase(this@DownloadService)
                val taskDao = db.downloadTaskDao()
                val libraryDao = db.modelLibraryDao()
                val tasks = taskDao.observeAll().first()
                val pending = libraryDao.observePendingArtifacts().first()
                val now = System.currentTimeMillis()
                val cancellableTasks = tasks.filter {
                    DownloadCancelAllPolicy.isActiveTaskStatus(it.status)
                }
                // A preallocated bundle row may not have a task row yet. Its
                // STAGED/INSPECTING state still represents queued transfer
                // work; completed Unknown/manual rows must remain untouched.
                val cancellablePending = pending.filter {
                    DownloadCancelAllPolicy.isQueuedArtifactStatus(it.status)
                }
                val cancellablePendingIds = cancellablePending.map { it.id }.toSet()
                db.withTransaction {
                    cancellablePending.forEach { artifact ->
                        libraryDao.upsert(
                            artifact.copy(
                                status = PendingArtifactStatus.CANCELLED.storedValue,
                                validationMessage = null,
                                updatedAt = nextStateTimestamp(artifact.updatedAt, now)
                            )
                        )
                    }
                    cancellableTasks.forEach { task ->
                        taskDao.updateState(
                            id = task.id,
                            status = DOWNLOAD_TASK_STATUS_CANCELLED,
                            bytesDownloaded = task.partFile().length().coerceAtLeast(0L),
                            totalBytes = null,
                            lastError = null,
                            updatedAt = now
                        )
                    }
                }
                jobs.values.forEach { it.cancel() }
                Downloader.cancelAllDownloads()
                jobs.values.forEach { job -> runCatching { job.join() } }

                // Per-task cancellation/discard handlers may still be
                // persisting their final sentinel or cleaning progress. The
                // process-wide gate stays closed until those gates complete.
                val taskCleanupStates = synchronized(activeDownloads) {
                    cleanupStates.values.distinct()
                }
                taskCleanupStates.forEach { state ->
                    runCatching { state.completed.await() }
                }

                // A progress callback may have been queued before the
                // sentinel transaction. Reassert cancellation after all
                // workers and per-task cleanup gates unwind.
                db.withTransaction {
                    libraryDao.observePendingArtifacts().first()
                        .filter {
                            it.id in cancellablePendingIds &&
                                DownloadCancelAllPolicy.isQueuedArtifactStatus(it.status)
                        }
                        .forEach { artifact ->
                            libraryDao.upsert(
                                artifact.copy(
                                    status = PendingArtifactStatus.CANCELLED.storedValue,
                                    validationMessage = null,
                                    updatedAt = nextStateTimestamp(artifact.updatedAt)
                                )
                            )
                        }
                    cancellableTasks.forEach { task ->
                        taskDao.getById(task.id)?.let { current ->
                            if (current.status != DOWNLOAD_TASK_STATUS_COMPLETED) {
                                taskDao.updateState(
                                    id = current.id,
                                    status = DOWNLOAD_TASK_STATUS_CANCELLED,
                                    bytesDownloaded = current.partFile().length().coerceAtLeast(0L),
                                    totalBytes = null,
                                    lastError = null
                                )
                            }
                        }
                    }
                }
                cancellableTasks.forEach { task ->
                    PendingDownloadHolder.removePending(task.id)
                    DownloadProgressHolder.removeProgress(task.progressKey)
                }
                jobs.forEach { (id, job) -> removeActiveDownload(id, job) }
            } catch (failure: Throwable) {
                DebugLog.log("DownloadService: cancel-all cleanup failed - ${failure.message}")
            } finally {
                completeCancelAll(claim.state)
            }
        }
    }

    private fun deleteIncompleteFiles(path: String) {
        val destination = File(path)
        runCatching { destination.delete() }
        runCatching { File(destination.parentFile ?: File("."), "${destination.name}.part").delete() }
    }

    private fun nextStateTimestamp(previous: Long, now: Long = System.currentTimeMillis()): Long =
        maxOf(now, previous + 1L)

    private fun removeActiveDownload(taskId: String, job: Job?) {
        synchronized(activeDownloads) {
            if (job == null || activeDownloads[taskId] === job) {
                activeDownloads.remove(taskId)
            }
        }
    }

    private fun stopWhenNoActiveDownloads() {
        synchronized(activeDownloads) {
            if (activeDownloads.isNotEmpty() ||
                startReservations.isNotEmpty() ||
                queuedRetryIds.isNotEmpty() ||
                cleanupStates.values.any { !it.completed.isCompleted } ||
                cancelAllState?.completed?.isCompleted == false
            ) return
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Lets the completion notification remain visible briefly, then checks
     * again before stopping. A sequential bundle can enqueue its next file
     * during that window; stopping without this second check would kill it.
     */
    private suspend fun stopWhenNoActiveDownloadsAfterDelay() {
        delay(2_000L)
        stopWhenNoActiveDownloads()
    }

    private fun initialProgressFor(destFile: File): Float {
        val part = File(destFile.parentFile ?: File("."), "${destFile.name}.part")
        return if (part.length() > 0L) DownloadProgressHolder.INDETERMINATE else 0f
    }
    
    private fun updateNotification(text: String, progress: Int) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, progress / 100f, text)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }

    private suspend fun finalizePendingDownload(
        pending: PendingDownload,
        downloadedFile: File,
        onProgress: (Float, String) -> Unit
    ): ModelEntity {
        return if (
            (pending.type == ModelType.ONNX_IMAGE_GEN || pending.type == ModelType.ONNX_TTS) &&
            pending.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
        ) {
            val installDirPath = pending.onnxInstallDirPath
                ?: error("Missing ONNX install directory for ${pending.filename}")
            val installDir = File(installDirPath)
            try {
                val coroutineContext = currentCoroutineContext()
                onProgress(0.92f, getString(R.string.onnx_models_phase_extracting))
                val extractedSizeBytes = OnnxImportSupport.extractBundleArchive(
                    archiveFile = downloadedFile,
                    installDir = installDir,
                    onPhase = { phase ->
                        val label = when (phase) {
                            "extracting" -> getString(R.string.onnx_models_phase_extracting)
                            "validating" -> getString(R.string.onnx_models_phase_validating)
                            "completed" -> getString(R.string.onnx_models_phase_completed)
                            else -> pending.filename
                        }
                        onProgress(0.92f, label)
                    },
                    ensureActive = { coroutineContext.ensureActive() },
                    onProgress = { extractProgress ->
                        coroutineContext.ensureActive()
                        onProgress(0.92f + (extractProgress * 0.07f), getString(R.string.onnx_models_phase_extracting))
                    }
                )
                onProgress(1f, getString(R.string.onnx_models_phase_completed))
                val validation = if (pending.type == ModelType.ONNX_TTS) {
                    OnnxTtsBundleValidator.validateDirectory(installDir)
                } else {
                    OnnxBundleValidator.validateDirectory(installDir)
                }
                val resolvedOnnxCapabilities = ModelRepository.resolveOnnxCapabilities(
                    explicitCapabilities = pending.onnxCapabilities,
                    detectedCapabilities = validation.supportedCapabilities
                )
                ModelEntity(
                    filename = pending.filename,
                    path = installDir.absolutePath,
                    sizeBytes = extractedSizeBytes,
                    type = pending.type,
                    repoId = pending.repoId,
                    isDownloaded = true,
                    isVision = pending.isVision,
                    sdCapabilities = pending.sdCapabilities,
                    sdFamily = pending.sdFamily,
                    sdVariant = pending.sdVariant,
                    sdCompatProfiles = pending.sdCompatProfiles,
                    onnxCapabilities = resolvedOnnxCapabilities,
                    onnxAssetKind = pending.onnxAssetKind,
                    onnxPipelineFamily = pending.onnxPipelineFamily,
                    onnxReferenceUri = pending.onnxReferenceUri,
                    onnxReferencePath = pending.onnxReferencePath
                )
            } catch (e: Exception) {
                OnnxImportSupport.deleteRecursively(installDir)
                throw e
            } finally {
                downloadedFile.delete()
            }
        } else {
            val resolvedOnnxCapabilities = if (pending.type == ModelType.ONNX_IMAGE_GEN) {
                ModelRepository.resolveOnnxCapabilities(
                    explicitCapabilities = pending.onnxCapabilities,
                    detectedCapabilities = emptySet()
                )
            } else {
                pending.onnxCapabilities
            }
            // Inspect the completed payload before copying it to the canonical
            // library or inserting a trusted model row.  A failed preflight
            // leaves the downloaded file in place for recovery/retry.
            val sdInspection = if (pending.type.isStableDiffusionArtifact()) {
                onProgress(0.91f, getString(R.string.sd_models_inspecting_artifact))
                val inspected = ModelRepository.inspectSdArtifact(downloadedFile, pending.type)
                ModelRepository.validateSdArtifactInspection(
                    configuredType = pending.type,
                    inspection = inspected,
                    configuredFamily = pending.sdFamily
                ).getOrThrow()
                inspected
            } else {
                null
            }
            if (ModelLibraryManager.usesManagedExternalCanonicalStorage(pending.type)) {
                // Single-copy path: the managed external destination is already the canonical runtime file.
            } else if (pending.type == ModelType.ONNX_IMAGE_GEN ||
                pending.type == ModelType.ONNX_BACKGROUND_REMOVAL ||
                pending.type == ModelType.ONNX_IMAGE_UPSCALER ||
                pending.type == ModelType.ONNX_TTS
            ) {
                // ONNX payloads now stay in internal app-managed storage only.
            } else {
                ModelLibraryManager.copyFileToLibrary(
                    context = this@DownloadService,
                    relativeDir = ModelLibraryManager.relativeDirFor(pending.type),
                    filename = pending.filename,
                    sourceFile = downloadedFile
                ).getOrThrow()
            }
            ModelEntity(
                filename = pending.filename,
                path = downloadedFile.absolutePath,
                sizeBytes = downloadedFile.length(),
                type = pending.type,
                repoId = pending.repoId,
                isDownloaded = true,
                isVision = pending.isVision,
                sdCapabilities = pending.sdCapabilities,
                sdFamily = pending.sdFamily,
                sdVariant = pending.sdVariant,
                sdCompatProfiles = pending.sdCompatProfiles,
                onnxCapabilities = resolvedOnnxCapabilities,
                onnxAssetKind = pending.onnxAssetKind,
                onnxPipelineFamily = pending.onnxPipelineFamily,
                onnxReferenceUri = pending.onnxReferenceUri,
                onnxReferencePath = pending.onnxReferencePath
            ).let { candidate ->
                sdInspection?.let(candidate::withSdArtifactInspection) ?: candidate
            }
        }
    }

    private suspend fun downloadPendingHfTreeBundle(
        pending: PendingDownload,
        onProgress: (Float, String) -> Unit
    ): ModelEntity {
        require(pending.type == ModelType.ONNX_TTS) {
            "Hugging Face tree bundles are only supported for ONNX TTS."
        }
        val installDirPath = pending.onnxInstallDirPath
            ?: error("Missing ONNX install directory for ${pending.filename}")
        val installDir = File(installDirPath)
        OnnxImportSupport.deleteRecursively(installDir)
        installDir.mkdirs()
        val files = OnnxCatalog.supertonicRequiredFiles
        val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var completedBytes = 0L
        try {
            files.forEach { fileEntry ->
                currentCoroutineContext().ensureActive()
                val output = File(installDir, fileEntry.relativePath)
                output.parentFile?.mkdirs()
                val url = OnnxCatalog.supertonicResolveUrl(fileEntry.relativePath)
                Downloader.download(url, output, this@DownloadService)
                    .collect { fileProgress ->
                        currentCoroutineContext().ensureActive()
                        val weighted = (completedBytes + (fileEntry.sizeBytes * fileProgress).toLong())
                            .toFloat() / totalBytes.toFloat()
                        onProgress(weighted.coerceIn(0f, 0.96f), getString(R.string.onnx_models_phase_downloading))
                    }
                completedBytes += fileEntry.sizeBytes
            }
            onProgress(0.98f, getString(R.string.onnx_models_phase_validating))
            val validation = OnnxTtsBundleValidator.validateDirectory(installDir)
            require(validation.isValid) {
                "Missing Supertonic bundle files: ${validation.missingPaths.joinToString(", ")}"
            }
            val resolvedOnnxCapabilities = ModelRepository.resolveOnnxCapabilities(
                explicitCapabilities = pending.onnxCapabilities,
                detectedCapabilities = validation.supportedCapabilities
            )
            val sizeBytes = installDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            onProgress(1f, getString(R.string.onnx_models_phase_completed))
            return ModelEntity(
                filename = pending.filename,
                path = installDir.absolutePath,
                sizeBytes = sizeBytes,
                type = pending.type,
                repoId = pending.repoId,
                isDownloaded = true,
                isVision = pending.isVision,
                sdCapabilities = pending.sdCapabilities,
                sdFamily = pending.sdFamily,
                sdVariant = pending.sdVariant,
                sdCompatProfiles = pending.sdCompatProfiles,
                onnxCapabilities = resolvedOnnxCapabilities,
                onnxAssetKind = pending.onnxAssetKind,
                onnxPipelineFamily = pending.onnxPipelineFamily,
                onnxReferenceUri = pending.onnxReferenceUri,
                onnxReferencePath = pending.onnxReferencePath
            )
        } catch (e: Exception) {
            OnnxImportSupport.deleteRecursively(installDir)
            throw e
        } finally {
            runCatching { File(pending.destPath).delete() }
        }
    }

    private fun Throwable.isHuggingFaceAccessFailure(): Boolean {
        val message = message.orEmpty()
        return "huggingface.co" in message && ("(401)" in message || "(403)" in message)
    }

    private fun Throwable.huggingFaceStatusCode(): Int =
        when {
            "(401)" in message.orEmpty() -> 401
            else -> 403
        }
}
