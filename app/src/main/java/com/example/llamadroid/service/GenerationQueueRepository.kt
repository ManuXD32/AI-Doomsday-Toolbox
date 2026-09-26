package com.example.llamadroid.service

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.GenerationQueueControlEntity
import com.example.llamadroid.data.db.GenerationQueueItemEntity
import com.example.llamadroid.data.db.GenerationQueueRunSummary
import com.example.llamadroid.data.db.GenerationQueueListItem
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object GenerationQueueRuntime {
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()
    val isActive: Boolean get() = _active.value
    internal fun setActive(value: Boolean) { _active.value = value }
}

data class GenerationQueueProgress(
    val total: Int,
    val finished: Int,
    val succeeded: Int,
    val failed: Int,
    val waiting: Int
)

data class QueuedGenerationOutcome(
    val status: String,
    val resultPath: String? = null,
    val metadataPath: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun succeeded(resultPath: String, metadataPath: String? = null) =
            QueuedGenerationOutcome("SUCCEEDED", resultPath, metadataPath)
        fun failed(message: String) = QueuedGenerationOutcome("FAILED", errorMessage = message)
        fun stopped() = QueuedGenerationOutcome("STOPPED")
        fun interrupted(message: String) = QueuedGenerationOutcome("INTERRUPTED", errorMessage = message)
    }
}

/** All queue mutations share one lock and one Room transaction boundary. */
class GenerationQueueRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val dao = database.generationQueueDao()

    val queue = dao.observeQueue()
    val pendingCountFlow = dao.observePendingCount()
    val control = dao.observeControl()

    fun historyPagingSource(): PagingSource<Int, GenerationQueueListItem> = dao.historyPagingSource()

    fun runSummaryFlow(runId: String?): Flow<GenerationQueueRunSummary> =
        runId?.let(dao::observeRunSummary) ?: flowOf(GenerationQueueRunSummary.EMPTY)

    suspend fun add(prepared: PreparedGenerationQueueItem) = withContext(Dispatchers.IO) {
        try { mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                val assignedRun = control.runId.takeIf {
                    control.state in setOf("RUNNING", "PAUSING", "PAUSED")
                }
                dao.putItem(GenerationQueueItemEntity(
                    id = prepared.id,
                    kind = prepared.kind,
                    mode = prepared.mode,
                    promptPreview = prepared.promptPreview,
                    configJson = prepared.configJson,
                    sortOrder = dao.maxSortOrder() + 1,
                    runId = assignedRun,
                    createdAtMillis = System.currentTimeMillis()
                ))
            }
        } } catch (error: Exception) {
            discardStagedInputs(prepared.id)
            throw error
        }
        refreshNotification()
    }

    suspend fun controlNow(): GenerationQueueControlEntity = withContext(Dispatchers.IO) {
        mutationMutex.withLock { database.withTransaction { controlOrDefault() } }
    }

    suspend fun item(id: String): GenerationQueueItemEntity? = withContext(Dispatchers.IO) { dao.getItem(id) }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) { dao.pendingCount() }

    suspend fun beginRun(scheduled: Boolean): String? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                if (control.state in setOf("RUNNING", "PAUSING")) return@withTransaction null
                if (scheduled && (control.state != "SCHEDULED" ||
                        (control.scheduledAtMillis ?: Long.MAX_VALUE) > System.currentTimeMillis() + 1_000L)) {
                    return@withTransaction null
                }
                if (dao.pendingCount() == 0) return@withTransaction null
                val runId = control.runId.takeIf { control.state == "PAUSED" }
                    ?: UUID.randomUUID().toString()
                dao.assignRunToPending(runId)
                dao.putControl(control.copy(state = "RUNNING", scheduledAtMillis = null,
                    runId = runId, activeItemId = null, updatedAtMillis = System.currentTimeMillis()))
                runId
            }
        }
    }

    suspend fun claimNext(): GenerationQueueItemEntity? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                if (control.state == "PAUSING") {
                    dao.putControl(control.copy(state = "PAUSED", activeItemId = null,
                        updatedAtMillis = System.currentTimeMillis()))
                    return@withTransaction null
                }
                if (control.state != "RUNNING") return@withTransaction null
                val next = dao.nextPending()
                if (next == null) {
                    dao.putControl(control.copy(state = "IDLE", runId = null, activeItemId = null,
                        updatedAtMillis = System.currentTimeMillis()))
                    return@withTransaction null
                }
                val active = next.copy(status = "RUNNING", runId = control.runId,
                    startedAtMillis = System.currentTimeMillis())
                dao.updateItem(active)
                dao.putControl(control.copy(activeItemId = active.id,
                    updatedAtMillis = System.currentTimeMillis()))
                active
            }
        }
    }

    suspend fun finish(itemId: String, outcome: QueuedGenerationOutcome) = withContext(Dispatchers.IO) {
        var discardInputs = false
        mutationMutex.withLock {
            database.withTransaction {
                val item = dao.getItem(itemId) ?: return@withTransaction
                if (item.status != "RUNNING") return@withTransaction
                dao.updateItem(item.copy(status = outcome.status,
                    finishedAtMillis = System.currentTimeMillis(), resultPath = outcome.resultPath,
                    metadataPath = outcome.metadataPath, errorMessage = outcome.errorMessage))
                val control = controlOrDefault()
                dao.putControl(control.copy(
                    state = if (control.state == "PAUSING") "PAUSED" else control.state,
                    activeItemId = null, updatedAtMillis = System.currentTimeMillis()
                ))
                discardInputs = outcome.status == "SUCCEEDED" || outcome.status == "STOPPED"
            }
        }
        if (discardInputs) discardStagedInputs(itemId)
    }

    /** A retry is a new attempt; the terminal row keeps its original outcome and duration. */
    suspend fun retry(itemId: String): String = withContext(Dispatchers.IO) {
        val source = mutationMutex.withLock {
            if (!retryClaims.add(itemId)) throw GenerationQueueRetryException(GenerationQueueRetryIssue.UNAVAILABLE)
            try {
                dao.getItem(itemId)?.takeIf { it.status == "FAILED" || it.status == "INTERRUPTED" }
                    ?: throw GenerationQueueRetryException(GenerationQueueRetryIssue.UNAVAILABLE)
            } catch (error: Exception) {
                retryClaims.remove(itemId)
                throw error
            }
        }
        try {
            val prepared = GenerationQueueSnapshot.retry(appContext, source)
            try {
                mutationMutex.withLock {
                    database.withTransaction {
                        val current = dao.getItem(itemId)
                        if (current == null || current.status != source.status ||
                            current.configJson != source.configJson) {
                            throw GenerationQueueRetryException(GenerationQueueRetryIssue.UNAVAILABLE)
                        }
                        val control = controlOrDefault()
                        val assignedRun = control.runId.takeIf {
                            control.state in setOf("RUNNING", "PAUSING", "PAUSED")
                        }
                        dao.putItem(GenerationQueueItemEntity(
                            id = prepared.id,
                            kind = prepared.kind,
                            mode = prepared.mode,
                            promptPreview = prepared.promptPreview,
                            configJson = prepared.configJson,
                            sortOrder = dao.maxSortOrder() + 1,
                            runId = assignedRun,
                            createdAtMillis = System.currentTimeMillis()
                        ))
                    }
                }
            } catch (error: Exception) {
                discardStagedInputs(prepared.id)
                throw error
            }
            discardStagedInputs(itemId)
            refreshNotification()
            prepared.id
        } finally {
            mutationMutex.withLock { retryClaims.remove(itemId) }
        }
    }

    fun hasRetryFiles(itemId: String): Boolean = stagedInputs(itemId)?.isDirectory == true

    suspend fun discardRetryFiles(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val allowed = mutationMutex.withLock {
            val item = dao.getItem(itemId)
            if (item?.status !in setOf("FAILED", "INTERRUPTED") || !retryClaims.add(itemId)) false
            else true
        }
        if (!allowed) return@withContext false
        try { stagedInputs(itemId)?.deleteRecursively() == true }
        finally { mutationMutex.withLock { retryClaims.remove(itemId) } }
    }

    suspend fun requestPause() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                if (control.state == "RUNNING") {
                    dao.putControl(control.copy(
                        state = if (control.activeItemId == null) "PAUSED" else "PAUSING",
                        updatedAtMillis = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    suspend fun setSchedule(atMillis: Long) = withContext(Dispatchers.IO) {
        require(atMillis > System.currentTimeMillis()) { "Choose a future time" }
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                require(control.state !in setOf("RUNNING", "PAUSING")) { "Queue is running" }
                require(dao.pendingCount() > 0) { "Queue is empty" }
                dao.putControl(control.copy(state = "SCHEDULED", scheduledAtMillis = atMillis,
                    runId = null, activeItemId = null, updatedAtMillis = System.currentTimeMillis()))
            }
        }
    }

    suspend fun cancelSchedule() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                if (control.state in setOf("SCHEDULED", "MISSED")) {
                    dao.putControl(control.copy(state = "IDLE", scheduledAtMillis = null,
                        updatedAtMillis = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun markMissedSchedule() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val control = controlOrDefault()
                if (control.state == "SCHEDULED") {
                    dao.putControl(control.copy(state = "MISSED", scheduledAtMillis = null,
                        updatedAtMillis = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun removePending(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val removed = mutationMutex.withLock { database.withTransaction { dao.deletePending(itemId) > 0 } }
        if (removed) {
            discardStagedInputs(itemId)
            if (dao.pendingCount() == 0) {
                val control = controlNow()
                if (control.state == "SCHEDULED") {
                    cancelSchedule()
                    GenerationQueueScheduler.cancel(appContext)
                }
            }
            refreshNotification()
        }
        removed
    }

    suspend fun movePending(itemId: String, delta: Int): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            database.withTransaction {
                val rows = dao.pendingOrder()
                val index = rows.indexOfFirst { it.id == itemId }
                val otherIndex = index + delta
                if (index < 0 || otherIndex !in rows.indices) return@withTransaction false
                val first = rows[index]
                val second = rows[otherIndex]
                dao.updatePendingOrder(first.id, second.sortOrder)
                dao.updatePendingOrder(second.id, first.sortOrder)
                true
            }
        }
    }

    suspend fun progress(runId: String): GenerationQueueProgress = withContext(Dispatchers.IO) {
        val summary = dao.runSummary(runId)
        GenerationQueueProgress(
            total = summary.total,
            finished = summary.finished,
            succeeded = summary.succeeded,
            failed = summary.failed,
            waiting = summary.waiting
        )
    }

    suspend fun recoverOrphanedRun() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (GenerationQueueRuntime.isActive) return@withLock
            database.withTransaction {
                val active = dao.runningItems()
                val now = System.currentTimeMillis()
                active.forEach { item ->
                    dao.updateItem(item.copy(status = "INTERRUPTED", finishedAtMillis = now,
                        errorMessage = "Generation process was interrupted"))
                }
                val control = controlOrDefault()
                if (control.state in setOf("RUNNING", "PAUSING")) {
                    dao.putControl(control.copy(state = "PAUSED", activeItemId = null,
                        updatedAtMillis = now))
                }
            }
        }
    }

    private suspend fun controlOrDefault(): GenerationQueueControlEntity =
        dao.getControl() ?: GenerationQueueControlEntity().also { dao.putControl(it) }

    private fun discardStagedInputs(id: String) {
        stagedInputs(id)?.deleteRecursively()
    }

    private fun stagedInputs(id: String): File? =
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() == id) {
            File(appContext.filesDir, "generation_queue_inputs/$id")
        } else null

    private suspend fun refreshNotification() {
        val state = dao.getControl() ?: return
        if (state.state == "SCHEDULED") {
            state.scheduledAtMillis?.let { at ->
                GenerationQueueNotifications.showScheduled(appContext, dao.pendingCount(), at)
            }
        } else if (state.state in setOf("RUNNING", "PAUSING") && GenerationQueueRuntime.isActive) {
            runCatching { appContext.startService(GenerationQueueService.refreshIntent(appContext)) }
        } else if (state.state == "PAUSED" && state.runId != null) {
            GenerationQueueNotifications.showStopped(appContext, progress(state.runId), paused = true)
        } else if (state.state == "IDLE") {
            val count = dao.pendingCount()
            if (count > 0) GenerationQueueNotifications.showPending(appContext, count)
            else GenerationQueueNotifications.dismiss(appContext)
        }
    }

    companion object {
        private val mutationMutex = Mutex()
        private val retryClaims = mutableSetOf<String>()
    }
}
