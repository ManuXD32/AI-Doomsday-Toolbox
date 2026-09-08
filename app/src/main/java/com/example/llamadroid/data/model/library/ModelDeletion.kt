package com.example.llamadroid.data.model.library

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.audio.AudioJobStatuses
import com.example.llamadroid.audio.AudioModelFamilies
import com.example.llamadroid.audio.localAssetPaths
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.partFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** Shared process lock for model rows, bundles, and their pending artifacts. */
internal val modelDeletionOperationMutex = Mutex()

/**
 * Returns every local asset claimed by an active audio request. This helper
 * is intentionally shared by model and bundle preflight so both removal
 * surfaces apply the same protection rules while holding the process lease.
 */
internal suspend fun activeAudioJobDependencies(
    context: Context,
    database: AppDatabase
): List<ModelDeletionDependency> {
    val activeStatuses = setOf(
        AudioJobStatuses.QUEUED,
        AudioJobStatuses.PREPARING,
        AudioJobStatuses.RUNNING,
        AudioJobStatuses.CANCELLING
    )
    return database.audioDao().getClaimedJobs()
        .asSequence()
        .filter { it.status in activeStatuses }
        .flatMap { job ->
            val paths = runCatching { job.toRequest().localAssetPaths() }
                .getOrElse { error ->
                    if (AudioModelFamilies.isMusicOrSfx(job.family)) {
                        throw IllegalStateException(context.getString(R.string.error_generic), error)
                    }
                    // Legacy speech rows have no nested component graph. Keep
                    // direct assets protected even if an optional field is
                    // malformed so deletion remains fail-closed.
                    buildSet {
                        add(job.modelPath)
                        job.companionPath?.let(::add)
                        job.referenceAudioPath?.let(::add)
                    }.filter(String::isNotBlank).map { File(it).canonicalPath }.toSet()
                }
            paths.map { path ->
                ModelDeletionDependency(
                    targetKey = "audio-job:${job.id}",
                    relation = "audio-job",
                    path = path
                )
            }
        }
        .toList()
}

/** Download rows and process-local registrations that currently claim bytes. */
internal suspend fun activeDownloadDependencies(
    database: AppDatabase
): List<ModelDeletionDependency> {
    val dependencies = mutableListOf<ModelDeletionDependency>()
    database.downloadTaskDao().observeAll().first()
        .filter { it.status == DOWNLOAD_TASK_STATUS_ACTIVE || it.status == DOWNLOAD_TASK_STATUS_RESUMABLE }
        .forEach { task ->
            dependencies += ModelDeletionDependency("download:${task.id}", "download", task.destPath)
            dependencies += ModelDeletionDependency("download:${task.id}", "download-part", task.partFile().absolutePath)
        }
    val activeArtifactStatuses = setOf(
        PendingArtifactStatus.STAGED.storedValue,
        PendingArtifactStatus.INSPECTING.storedValue,
        PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
        PendingArtifactStatus.VALIDATED.storedValue
    )
    database.modelLibraryDao().observePendingArtifacts().first()
        .filter { it.status in activeArtifactStatuses }
        .forEach { artifact ->
            dependencies += ModelDeletionDependency("pending:${artifact.id}", "pending-download", artifact.stagingPath)
            artifact.destinationPath?.let {
                dependencies += ModelDeletionDependency("pending:${artifact.id}", "pending-destination", it)
            }
        }
    PendingDownloadHolder.allPending().forEach { pending ->
        dependencies += ModelDeletionDependency(
            "download:${pending.progressKey}",
            "download-memory",
            pending.destPath
        )
    }
    val trackedModels = database.modelDao().getAllModels().first()
    DownloadProgressHolder.progress.value
        .filterValues { it == DownloadProgressHolder.INDETERMINATE || it in 0f..0.999999f }
        .keys.map(DownloadProgressHolder::getFilename).distinct().forEach { filename ->
        trackedModels.firstOrNull { it.filename == filename }?.let { model ->
            dependencies += ModelDeletionDependency("download:$filename", "download-progress", model.path)
        }
    }
    return dependencies.distinctBy { it.targetKey to it.relation to it.path }
}

/** A file considered by a model or bundle deletion operation. */
data class ModelDeletionFile(
    val path: String,
    val sizeBytes: Long,
    val kind: String,
    val protectedBy: List<String> = emptyList()
)

/** A runtime or provenance edge which prevents a payload from being removed. */
data class ModelDeletionDependency(
    val targetKey: String,
    val relation: String,
    val path: String
)

data class ModelDeletionPathFailure(
    val path: String,
    val code: ModelLibraryErrorCode,
    val message: String? = null
)

/**
 * Side-effect-free deletion preflight. The UI can show this before it asks
 * the repository to cancel queues, detach provenance, or remove files.
 */
data class ModelDeletionPreview(
    val operationId: String = UUID.randomUUID().toString(),
    val targetKey: String,
    val targetLabel: String,
    val targetKind: String = "model",
    val files: List<ModelDeletionFile>,
    val dependencies: List<ModelDeletionDependency> = emptyList(),
    val protectedPaths: List<String> = emptyList(),
    val canDelete: Boolean = dependencies.isEmpty(),
    val blockingCode: ModelLibraryErrorCode? = null
)

enum class ModelDeletionStatus {
    COMPLETED,
    BLOCKED,
    FAILED,
    RECOVERABLE
}

/** Typed result returned after a deletion attempt. */
data class ModelDeletionResult(
    val operationId: String,
    val targetKey: String,
    val targetKind: String = "model",
    val status: ModelDeletionStatus,
    val deletedPaths: List<String> = emptyList(),
    val preservedPaths: List<String> = emptyList(),
    val reclaimedBytes: Long = 0L,
    val failedPaths: List<ModelDeletionPathFailure> = emptyList(),
    val errorCode: ModelLibraryErrorCode? = null,
    val errorMessage: String? = null
)

/**
 * Durable deletion journal boundary. The Room DB 115 owner supplies the
 * implementation and records each path as it is attempted, so an interrupted
 * operation can be resumed without guessing from filenames.
 */
interface ModelDeletionJournal {
    suspend fun begin(preview: ModelDeletionPreview): String
    suspend fun recordPath(
        operationId: String,
        path: String,
        deleted: Boolean,
        failure: ModelDeletionPathFailure? = null
    )
    suspend fun complete(result: ModelDeletionResult)
    suspend fun fail(result: ModelDeletionResult)

    /** Returns operations that were interrupted or failed and may be retried. */
    suspend fun recoverableOperations(limit: Int = 50): List<ModelDeletionJournalSnapshot>

    /** Loads one operation with its durable per-path progress. */
    suspend fun operation(operationId: String): ModelDeletionJournalSnapshot?
}

enum class ModelDeletionJournalStatus {
    IN_PROGRESS,
    COMPLETED,
    RECOVERABLE,
    FAILED
}

enum class ModelDeletionJournalPathStatus {
    PENDING,
    DELETED,
    PRESERVED,
    FAILED
}

data class ModelDeletionJournalPath(
    val path: String,
    val status: ModelDeletionJournalPathStatus,
    val failure: ModelDeletionPathFailure? = null,
    val updatedAt: Long
)

data class ModelDeletionJournalSnapshot(
    val operationId: String,
    val status: ModelDeletionJournalStatus,
    val preview: ModelDeletionPreview,
    val result: ModelDeletionResult?,
    val paths: List<ModelDeletionJournalPath>,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Runtime queue boundary for deletion. Audio enqueue/claim code should hold
 * this lease while it snapshots component paths from its job metadata and
 * claims a job; model deletion uses the same lease before mutating files.
 */
interface ModelDeletionCoordinator {
    suspend fun activeDependencies(paths: Set<String>): List<ModelDeletionDependency>

    suspend fun <T> withDeletionLease(paths: Set<String>, block: suspend () -> T): T
}
