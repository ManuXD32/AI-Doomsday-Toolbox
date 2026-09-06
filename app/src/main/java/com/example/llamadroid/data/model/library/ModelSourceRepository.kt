package com.example.llamadroid.data.model.library

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelBundleWithItems
import com.example.llamadroid.data.db.ModelLibraryDao
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PortableModelMetadata
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.toDownloadTaskEntity
import com.example.llamadroid.data.model.toPendingDownload
import com.example.llamadroid.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.io.File
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Serializes durable state transitions which compete with queue cancellation. */
private val modelLibraryBundleStateMutex = Mutex()

/** Serializes direct recovery callers without nesting the application queue key. */
private val pendingBundleRecoveryMutex = Mutex()

/** Prevents two taps/process callers from creating the same custom artifact. */
private val customDownloadMutex = Mutex()

private val activeCustomArtifactStatuses = setOf(
    PendingArtifactStatus.STAGED.storedValue,
    PendingArtifactStatus.INSPECTING.storedValue,
    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
    PendingArtifactStatus.VALIDATED.storedValue,
    PendingArtifactStatus.PROMOTED.storedValue
)

private val cancellableBundleArtifactStatuses = setOf(
    PendingArtifactStatus.STAGED.storedValue,
    PendingArtifactStatus.INSPECTING.storedValue,
    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
    PendingArtifactStatus.VALIDATED.storedValue
)

/**
 * This marker is kept in the pending row until the next explicit retry. It
 * lets a retry distinguish an old partial from a normal user cancellation even
 * when the old DownloadTaskEntity was already removed during process recovery.
 * It is intentionally JSON so diagnostics can safely render it as data.
 */
internal const val SOURCE_IDENTITY_INVALIDATED_MARKER = "{\"sourceIdentityInvalidated\":true}"

internal fun sameModelSourceIdentity(expected: ModelSourceEntity, current: ModelSourceEntity): Boolean =
    expected.id == current.id &&
        expected.normalizedKey == current.normalizedKey &&
        expected.url == current.url

internal fun sourceIsVerifiedForRecovery(source: ModelSourceEntity?): Boolean =
    source?.verified == true && source.validationStatus == "verified"

internal fun pendingSourceIdentityChanged(
    artifact: PendingModelArtifactEntity,
    existingTaskUrl: String?,
    currentResolvedUrl: String
): Boolean = artifact.validationJson == SOURCE_IDENTITY_INVALIDATED_MARKER ||
    existingTaskUrl?.trim()?.takeIf { it.isNotBlank() }?.let { it != currentResolvedUrl } == true

/**
 * Returns a bearer token only for Hugging Face sources. Saved direct HTTPS
 * links can be edited after an HF download, so reusing the old task must not
 * accidentally put that credential on an arbitrary host.
 */
internal fun bearerTokenForSource(
    kind: ModelSourceKind?,
    requestedToken: String?,
    persistedToken: String? = null
): String? {
    if (kind != ModelSourceKind.HUGGING_FACE_FILE &&
        kind != ModelSourceKind.HUGGING_FACE_REPOSITORY
    ) return null
    return requestedToken?.trim()?.takeIf { it.isNotEmpty() }
        ?: persistedToken?.trim()?.takeIf { it.isNotEmpty() }
}

/** Stable runtime reference used by model selectors and provenance screens. */
data class ModelArtifactReference(
    val family: ModelFamily,
    val localPath: String,
    val displayName: String,
    val modelKey: String? = null
) {
    init {
        require(localPath.trim().isNotEmpty()) { "Artifact local path cannot be blank" }
        require(displayName.trim().isNotEmpty()) { "Artifact display name cannot be blank" }
    }
}

data class ModelBundleInspection(
    val bundle: ModelBundleEntity,
    val items: List<ModelBundleItemEntity>,
    val missingSourceItemIds: List<String>,
    val unverifiedSourceItemIds: List<String>,
    val invalidItemIds: List<String>,
    val readyItemIds: List<String>,
    val verifiedExistingItemIds: List<String>
) {
    val isRunnable: Boolean
        get() = items.isNotEmpty() && items.filter { it.required }.all {
            it.id !in missingSourceItemIds && it.id !in unverifiedSourceItemIds && it.id !in invalidItemIds &&
                (it.id in readyItemIds || it.id in verifiedExistingItemIds)
        }
}

data class BundleDownloadRequest(
    val item: ModelBundleItemEntity,
    val source: ModelSourceEntity,
    val url: String,
    val destination: File
)

data class BundleDownloadPlan(
    val bundle: ModelBundleEntity,
    val requests: List<BundleDownloadRequest>,
    val missingSourceItemIds: List<String>,
    val unverifiedSourceItemIds: List<String>,
    val verifiedExistingItemIds: List<String>,
    val invalidItemIds: List<String>
)

data class BundleDownloadRunResult(
    val bundleId: String,
    val startedItemIds: List<String>,
    val completedItemIds: List<String>,
    val pendingArtifactIds: List<String>,
    val failedItemIds: List<String>,
    val skippedExistingItemIds: List<String>,
    val missingSourceItemIds: List<String>
)

/**
 * Persistence and coordination boundary for saved model sources, bundles and
 * staged artifacts. It leaves normal foreground downloads on DownloadService,
 * while the bundle coordinator persists the whole plan and the service-side
 * finalizer registers confidently recognized runtime rows.
 */
class ModelSourceRepository(
    private val libraryDao: ModelLibraryDao,
    private val folderBrowser: HuggingFaceFolderBrowser,
    private val verificationClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS).build()
) {

    val sources: Flow<List<ModelSourceEntity>> = libraryDao.observeSources()
    val provenance: Flow<List<ModelProvenanceEntity>> = libraryDao.observeProvenance()
    val bundles: Flow<List<ModelBundleEntity>> = libraryDao.observeBundles()
    val pendingArtifacts: Flow<List<PendingModelArtifactEntity>> = libraryDao.observePendingArtifacts()

    fun observeSources(): Flow<List<ModelSourceEntity>> = sources

    fun observeSources(family: ModelFamily): Flow<List<ModelSourceEntity>> =
        libraryDao.observeSourcesByFamily(family.storedValue)

    fun observeBundles(): Flow<List<ModelBundleEntity>> = bundles

    fun observeBundles(family: ModelFamily): Flow<List<ModelBundleEntity>> =
        libraryDao.observeBundlesByFamily(family.storedValue)

    fun observePendingArtifacts(): Flow<List<PendingModelArtifactEntity>> = pendingArtifacts

    fun observePendingArtifacts(statuses: Set<PendingArtifactStatus>): Flow<List<PendingModelArtifactEntity>> =
        libraryDao.observeByStatuses(statuses.map { it.storedValue })

    fun observeBundleItems(bundleId: String): Flow<List<ModelBundleItemEntity>> =
        libraryDao.observeForBundle(bundleId)

    suspend fun saveSource(draft: ModelSourceDraft): Result<ModelSourceEntity> = withContext(Dispatchers.IO) {
        // Source edits and delayed verification commits share one process-wide
        // gate. The verification commit also re-reads the row, so an edit that
        // wins while the network request is in flight cannot be overwritten by
        // the stale response.
        modelLibraryBundleStateMutex.withLock {
            ModelSourceUrlValidator.toEntity(draft).mapCatching { candidate ->
                val existing = libraryDao.getByNormalizedKey(candidate.normalizedKey)
                val existingById = draft.id?.let { libraryDao.getSourceById(it) }
                if (existing != null && draft.id != null && existing.id != draft.id) {
                    throw ModelLibraryException(ModelLibraryErrorCode.SOURCE_ALREADY_SAVED,
                        "This URL is already saved; select its existing saved link")
                }
                if (existingById != null && existingById.normalizedKey != candidate.normalizedKey) {
                    val activePending = libraryDao.observePendingArtifacts().first().any { artifact ->
                        artifact.sourceId == existingById.id && artifact.status in setOf(
                            PendingArtifactStatus.STAGED.storedValue,
                            PendingArtifactStatus.INSPECTING.storedValue,
                            PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                            PendingArtifactStatus.VALIDATED.storedValue
                        )
                    }
                    if (activePending) {
                        throw ModelLibraryException(
                            ModelLibraryErrorCode.SOURCE_HAS_PENDING_DOWNLOAD,
                            "Finish, cancel, or remove the pending download before replacing this source link"
                        )
                    }
                }
                val persisted = if (existing != null && draft.id == null) {
                    // Keep the original provenance classification when a user
                    // re-enters the same URL for another compatible bundle role.
                    // Runtime compatibility is decided on the bundle item role.
                    candidate.copy(
                        id = existing.id,
                        family = existing.family,
                        createdAt = existing.createdAt,
                        revision = existing.revision,
                        expectedSha256 = candidate.expectedSha256 ?: existing.expectedSha256,
                        expectedSizeBytes = candidate.expectedSizeBytes ?: existing.expectedSizeBytes,
                        mediaType = candidate.mediaType ?: existing.mediaType,
                        verified = existing.verified,
                        authRequired = existing.authRequired,
                        validationStatus = existing.validationStatus,
                        checkedAt = existing.checkedAt,
                        lastErrorCode = existing.lastErrorCode
                    )
                } else if (existingById != null) {
                    // Editing a label or optional metadata must not make a
                    // previously verified URL appear unverified. A changed URL
                    // deliberately resets verification and is checked again by
                    // the user before it can be queued.
                    candidate.copy(
                        id = existingById.id,
                        createdAt = existingById.createdAt,
                        revision = if (existingById.normalizedKey == candidate.normalizedKey)
                            existingById.revision else candidate.revision,
                        expectedSha256 = candidate.expectedSha256 ?: existingById.expectedSha256
                            .takeIf { existingById.normalizedKey == candidate.normalizedKey },
                        expectedSizeBytes = candidate.expectedSizeBytes ?: existingById.expectedSizeBytes
                            .takeIf { existingById.normalizedKey == candidate.normalizedKey },
                        mediaType = candidate.mediaType ?: existingById.mediaType
                            .takeIf { existingById.normalizedKey == candidate.normalizedKey },
                        verified = existingById.normalizedKey == candidate.normalizedKey && existingById.verified,
                        authRequired = if (existingById.normalizedKey == candidate.normalizedKey) {
                            existingById.authRequired
                        } else {
                            candidate.authRequired
                        },
                        validationStatus = if (existingById.normalizedKey == candidate.normalizedKey) {
                            existingById.validationStatus
                        } else {
                            candidate.validationStatus
                        },
                        checkedAt = if (existingById.normalizedKey == candidate.normalizedKey) {
                            existingById.checkedAt
                        } else {
                            candidate.checkedAt
                        },
                        lastErrorCode = if (existingById.normalizedKey == candidate.normalizedKey) {
                            existingById.lastErrorCode
                        } else {
                            candidate.lastErrorCode
                        }
                    )
                } else {
                    candidate
                }
                if (existingById != null && existingById.normalizedKey != persisted.normalizedKey) {
                    libraryDao.replaceSourceIdentity(persisted)
                } else {
                    libraryDao.upsert(persisted)
                }
                persisted
            }
        }
    }

    suspend fun getSource(sourceId: String): ModelSourceEntity? = withContext(Dispatchers.IO) {
        libraryDao.getSourceById(sourceId)
    }

    suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
        // Provenance deliberately remains inspectable if a user removes a
        // saved link; source rows have no model-row ownership semantics.
        libraryDao.deleteSourceById(sourceId)
    }

    /**
     * Cancels a bundle queue, then detaches its pending rows before deleting
     * the definition. Staged/downloaded files and source/provenance rows stay
     * on disk and remain inspectable as standalone Unknown artifacts.
     */
    suspend fun deleteBundle(context: Context, bundleId: String) = withContext(Dispatchers.IO) {
        // Keep direct repository callers safe as well as the screen action.
        ModelLibraryQueueScope.cancel("bundle:$bundleId")
        val hadDefinition = libraryDao.getBundleById(bundleId) != null
        if (hadDefinition) {
            // This writes cancellation sentinels for items which have not
            // started yet and sends cancellation to every known service task.
            cancelBundleDownload(context, bundleId).getOrThrow()
        } else {
            // Clean up rows left by an older process that removed the
            // definition first. Mark active rows cancelled before detaching so
            // a late service completion cannot promote them.
            val orphaned = libraryDao.getPendingArtifactsForBundle(bundleId)
            val now = System.currentTimeMillis()
            val cancelled = orphaned.map { artifact ->
                if (artifact.status in cancellableBundleArtifactStatuses) {
                    artifact.copy(
                        status = PendingArtifactStatus.CANCELLED.storedValue,
                        validationMessage = null,
                        updatedAt = nextStateTimestamp(artifact.updatedAt, now)
                    )
                } else {
                    artifact
                }
            }
            if (cancelled != orphaned) {
                libraryDao.upsertPendingArtifactsAtomically(cancelled)
            }
            orphaned.filter { it.status in cancellableBundleArtifactStatuses }.forEach { artifact ->
                artifact.downloadTaskId?.trim()?.takeIf { it.isNotBlank() }?.let { taskId ->
                    DownloadService.cancelDownload(context.applicationContext, artifact.filename, taskId)
                }
            }
        }

        val pending = libraryDao.getPendingArtifactsForBundle(bundleId)
        val detached = pending.map { it.copy(bundleId = null, bundleItemId = null) }
        // The Room transaction updates references before deleting definitions,
        // so an interrupted delete cannot strand an artifact behind an absent
        // bundle item.
        libraryDao.detachPendingArtifactsAndDeleteBundle(bundleId, detached)
    }

    /**
     * Queues a pasted direct file as a durable pending artifact. Custom files
     * have no synthetic bundle: structural recognition decides their runtime
     * family, while the requested family/role remains manual-promotion context.
     */
    suspend fun startCustomDownload(
        context: Context,
        sourceId: String,
        family: ModelFamily,
        role: String?,
        bearerToken: String? = null,
        /** Shared selection namespace for HF files that must retain nested paths. */
        storageGroup: String? = null
    ): Result<PendingModelArtifactEntity> = withContext(Dispatchers.IO) {
        customDownloadMutex.withLock {
            runCatching {
                val source = requireNotNull(libraryDao.getSourceById(sourceId)) {
                    "Saved source was not found"
                }
                // A durable active row is the source of truth for duplicate taps,
                // including after the original process has been recreated. A
                // failed/cancelled row is intentionally eligible for an explicit retry.
                libraryDao.observePendingArtifacts().first().firstOrNull { artifact ->
                    artifact.bundleId == null &&
                        artifact.sourceId == source.id &&
                        artifact.status in activeCustomArtifactStatuses &&
                        (artifact.status != PendingArtifactStatus.PROMOTED.storedValue ||
                            File(artifact.destinationPath ?: artifact.stagingPath).exists())
                }?.let { return@runCatching it }
                // A cancelled/failed custom row is the durable retry handle.
                // Reusing it preserves its task ID and `.part` file, so a
                // second tap cannot silently start a parallel download or
                // discard resumable bytes.
                libraryDao.observePendingArtifacts().first().firstOrNull { artifact ->
                    artifact.bundleId == null &&
                        artifact.sourceId == source.id &&
                        artifact.status in setOf(
                            PendingArtifactStatus.CANCELLED.storedValue,
                            PendingArtifactStatus.FAILED.storedValue
                        )
                }?.let { cancelled ->
                    return@runCatching retryPendingArtifactLocked(
                        context = context,
                        id = cancelled.id,
                        bearerToken = bearerToken
                    ).getOrThrow()
                }
                val url = source.resolvedDownloadUrl()
                    ?: throw ModelLibraryException(
                        ModelLibraryErrorCode.INVALID_HF_FILE_PATH,
                        "Custom downloads require a direct model file link"
                    )
                val verified = verifySource(sourceId, bearerToken).getOrThrow()
                val verifiedUrl = verified.resolvedDownloadUrl() ?: url
                val sourceRelativePath = safeRelativeDownloadPath(
                    verified.filePath ?: verified.label,
                    fallback = "downloaded_model"
                )
                val sourceName = sourceRelativePath.substringAfterLast('/')
                val pendingId = "custom:${UUID.randomUUID()}"
                val taskId = "custom-download:${UUID.randomUUID()}"
                val storageNamespace = storageGroup
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::safeStorageGroup)
                val staging = File(
                    context.applicationContext.filesDir,
                    if (storageNamespace == null) {
                        "model-library/${family.storageValue}/custom/${pendingId.replace(Regex("[^A-Za-z0-9._-]"), "_")}/$sourceRelativePath"
                    } else {
                        "model-library/${family.storageValue}/custom-repository/$storageNamespace/$sourceRelativePath"
                    }
                ).canonicalFile
                staging.parentFile?.mkdirs()
                val row = PendingModelArtifactEntity(
                    id = pendingId,
                    downloadTaskId = taskId,
                    sourceId = verified.id,
                    bundleId = null,
                    bundleItemId = null,
                    filename = sourceName,
                    stagingPath = staging.absolutePath,
                    destinationPath = staging.absolutePath,
                    requestedFamily = family.storedValue,
                    requestedRole = role?.trim()?.takeIf { it.isNotBlank() },
                    status = PendingArtifactStatus.STAGED.storedValue,
                    requiresManualPromotion = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                libraryDao.upsert(row)
                try {
                    PendingDownloadHolder.addPending(
                        downloadId = taskId,
                        filename = staging.name,
                        repoId = verified.repositoryId ?: verified.url,
                        progressKey = taskId,
                        type = runtimeTypeFor(family, role),
                        destPath = staging.absolutePath,
                        huggingFaceToken = bearerTokenForSource(
                            kind = ModelSourceKind.fromStoredValue(verified.kind),
                            requestedToken = bearerToken
                        ),
                        sourceId = verified.id,
                        artifactFamily = family.storedValue,
                        artifactRole = role?.trim()?.takeIf { it.isNotBlank() },
                        pendingArtifactId = pendingId,
                        stageOnly = true
                    )
                    DownloadService.startDownload(
                        context = context.applicationContext,
                        url = verifiedUrl,
                        destPath = staging.absolutePath,
                        filename = staging.name,
                        downloadId = taskId
                    )
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    libraryDao.upsert(
                        row.copy(
                            status = PendingArtifactStatus.FAILED.storedValue,
                            validationMessage = failure.message?.take(512),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    throw failure
                }
                row
            }
        }
    }

    /**
     * Cancels one staged artifact durably. The row is retained so the Unknown
     * surface can offer Retry; its payload and partial file remain available.
     */
    suspend fun cancelPendingArtifact(
        context: Context,
        id: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val cancelled = modelLibraryBundleStateMutex.withLock {
                db.withTransaction {
                    val current = requireNotNull(libraryDao.getPendingArtifactById(id)) {
                        "Pending artifact was not found"
                    }
                    if (current.status != PendingArtifactStatus.PROMOTED.storedValue &&
                        current.status != PendingArtifactStatus.CANCELLED.storedValue
                    ) {
                        libraryDao.upsert(
                            current.copy(
                                status = PendingArtifactStatus.CANCELLED.storedValue,
                                validationMessage = null,
                                updatedAt = nextStateTimestamp(current.updatedAt)
                            )
                        )
                    }
                    current.downloadTaskId?.let { taskId ->
                        db.downloadTaskDao().getById(taskId)?.let { task ->
                            if (task.status != com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED) {
                                db.downloadTaskDao().updateStatus(
                                    task.id,
                                    com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED,
                                    null,
                                    nextStateTimestamp(task.updatedAt)
                                )
                            }
                        }
                    }
                    current
                }
            }
            cancelled.downloadTaskId?.trim()?.takeIf { it.isNotBlank() }?.let { taskId ->
                DownloadService.cancelDownload(
                    context.applicationContext,
                    cancelled.filename,
                    taskId
                )
            }
            Unit
        }
    }

    /**
     * Discards a completed or cancelled Unknown artifact without touching its
     * saved source, bundle definition, or provenance edges. The service owns
     * the final cancellation/file cleanup so an active worker cannot race a
     * direct file delete; this method waits until that cleanup has settled.
     */
    suspend fun discardPendingArtifact(
        context: Context,
        id: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        customDownloadMutex.withLock {
            runCatching {
                val appContext = context.applicationContext
                val db = AppDatabase.getDatabase(appContext)
                val snapshot = modelLibraryBundleStateMutex.withLock {
                    db.withTransaction {
                        val current = requireNotNull(libraryDao.getPendingArtifactById(id)) {
                            "Pending artifact was not found"
                        }
                        val linkedTask = current.downloadTaskId
                            ?.let { db.downloadTaskDao().getById(it) }
                            ?.takeIf { it.pendingArtifactId == current.id }
                        val candidates = ModelArtifactDiscardPolicy.deletionCandidates(
                            appContext,
                            current
                        )
                        ModelArtifactDiscardPolicy.requireNotRegistered(
                            database = db,
                            candidates = candidates,
                            allowedPendingArtifactId = current.id,
                            allowedTaskId = linkedTask?.id
                        )
                        if (current.status != PendingArtifactStatus.CANCELLED.storedValue) {
                            libraryDao.upsert(
                                current.copy(
                                    status = PendingArtifactStatus.CANCELLED.storedValue,
                                    validationMessage = null,
                                    updatedAt = nextStateTimestamp(current.updatedAt)
                                )
                            )
                        }
                        linkedTask?.let { task ->
                            if (task.status != com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED) {
                                db.downloadTaskDao().updateStatus(
                                    task.id,
                                    com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED,
                                    null,
                                    nextStateTimestamp(task.updatedAt)
                                )
                            }
                        }
                        DiscardSnapshot(current, candidates)
                    }
                }
                DownloadService.discardPendingArtifact(appContext, id)
                awaitPendingArtifactDiscard(
                    database = db,
                    snapshot = snapshot,
                    context = appContext
                )
                Unit
            }
        }
    }

    private data class DiscardSnapshot(
        val artifact: PendingModelArtifactEntity,
        val candidates: List<File>
    )

    private suspend fun awaitPendingArtifactDiscard(
        database: AppDatabase,
        snapshot: DiscardSnapshot,
        context: Context
    ) {
        try {
            withTimeout(PENDING_ARTIFACT_DISCARD_TIMEOUT_MILLIS) {
                while (true) {
                    val current = database.modelLibraryDao().getPendingArtifactById(snapshot.artifact.id)
                    if (current?.status == PendingArtifactStatus.PROMOTED.storedValue ||
                        current?.promotedModelKey != null
                    ) {
                        throw ModelLibraryException(
                            ModelLibraryErrorCode.ARTIFACT_DISCARD_PROMOTED,
                            "The pending artifact became a promoted model before discard completed"
                        )
                    }
                    val candidates = current?.let {
                        ModelArtifactDiscardPolicy.deletionCandidates(context, it)
                    } ?: snapshot.candidates
                    val taskId = current?.downloadTaskId?.trim()?.takeIf { it.isNotBlank() }
                    val task = taskId?.let { database.downloadTaskDao().getById(it) }
                    val taskBelongsToArtifact = task?.pendingArtifactId == current?.id
                    val taskSettled = task == null || !taskBelongsToArtifact
                    val filesGone = candidates.none { it.exists() }
                    if ((current == null || current.status == PendingArtifactStatus.CANCELLED.storedValue) &&
                        taskSettled && filesGone
                    ) {
                        return@withTimeout
                    }
                    delay(PENDING_ARTIFACT_DISCARD_POLL_MILLIS)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_FAILED,
                "The pending artifact cleanup did not settle",
                cause = timeout
            )
        }
    }

    /**
     * Explicitly re-arms a failed/cancelled staged artifact while preserving
     * its destination and `.part` file. The service performs the final
     * transaction which changes the sentinel back to STAGED after its
     * per-task cancellation lock has drained any late worker callback.
     */
    suspend fun retryPendingArtifact(
        context: Context,
        id: String,
        bearerToken: String? = null
    ): Result<PendingModelArtifactEntity> = withContext(Dispatchers.IO) {
        customDownloadMutex.withLock {
            retryPendingArtifactLocked(context, id, bearerToken)
        }
    }

    /**
     * Caller must hold [customDownloadMutex]. Keeping the implementation
     * lock-free here lets startCustomDownload turn a cancelled URL tap into
     * the same durable retry operation without recursively acquiring Mutex.
     */
    private suspend fun retryPendingArtifactLocked(
        context: Context,
        id: String,
        bearerToken: String?
    ): Result<PendingModelArtifactEntity> = runCatching {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val retryRequestedAt = System.currentTimeMillis()
        val sourceAndRow = modelLibraryBundleStateMutex.withLock {
            val row = requireNotNull(libraryDao.getPendingArtifactById(id)) {
                "Pending artifact was not found"
            }
            require(row.status == PendingArtifactStatus.CANCELLED.storedValue ||
                row.status == PendingArtifactStatus.FAILED.storedValue
            ) { "Pending artifact is not retryable in its current state" }
            val source = row.sourceId?.let { libraryDao.getSourceById(it) }
            row to source
        }
        val row = sourceAndRow.first
        val source = requireNotNull(sourceAndRow.second) { "Pending source was not found" }
        val verified = verifySource(source.id, bearerToken).getOrThrow()
        val url = verified.resolvedDownloadUrl()
            ?: throw ModelLibraryException(
                ModelLibraryErrorCode.INVALID_HF_FILE_PATH,
                "Retry requires a direct model file link"
            )
        val taskDao = db.downloadTaskDao()
        val existingTask = row.downloadTaskId?.let { taskDao.getById(it) }
        val sourceIdentityChanged = pendingSourceIdentityChanged(row, existingTask?.url, url)
        val sourceKind = ModelSourceKind.fromStoredValue(verified.kind)
        // Direct HTTPS retries must clear any stale token from a task that
        // previously pointed at Hugging Face. Only HF sources may authorize.
        val retryToken = bearerTokenForSource(
            kind = sourceKind,
            requestedToken = bearerToken,
            persistedToken = existingTask?.huggingFaceToken
        )
        val family = ModelFamily.fromStoredValue(row.requestedFamily) ?: ModelFamily.LLM
        val taskId = existingTask?.id ?: row.downloadTaskId?.takeIf { it.isNotBlank() }
            ?: "pending-download:${row.id}"
        val task = existingTask?.let { existing ->
            existing.copy(
                url = url,
                huggingFaceToken = retryToken,
                sourceId = row.sourceId ?: existing.sourceId,
                artifactFamily = family.storedValue,
                artifactRole = row.requestedRole ?: existing.artifactRole,
                pendingArtifactId = row.id,
                stageOnly = true,
                status = DOWNLOAD_TASK_STATUS_CANCELLED,
                updatedAt = retryRequestedAt
            )
        } ?: PendingDownload(
            filename = row.filename,
            repoId = verified.repositoryId ?: verified.url,
            progressKey = taskId,
            type = runtimeTypeFor(family, row.requestedRole),
            destPath = row.stagingPath,
            huggingFaceToken = retryToken,
            sourceId = row.sourceId,
            artifactFamily = family.storedValue,
            artifactRole = row.requestedRole,
            pendingArtifactId = row.id,
            stageOnly = true
        ).toDownloadTaskEntity(taskId, url, status = DOWNLOAD_TASK_STATUS_CANCELLED)
        val persistedRow = modelLibraryBundleStateMutex.withLock {
            db.withTransaction {
                val current = requireNotNull(libraryDao.getPendingArtifactById(id)) {
                    "Pending artifact was removed before retry"
                }
                require(current.status == PendingArtifactStatus.CANCELLED.storedValue ||
                    current.status == PendingArtifactStatus.FAILED.storedValue
                ) { "Pending artifact changed while retry was being prepared" }
                if (current.updatedAt > retryRequestedAt) {
                    throw CancellationException("Pending artifact was cancelled after retry was requested")
                }
                val currentSource = current.sourceId?.let { libraryDao.getSourceById(it) }
                if (currentSource == null || !sameModelSourceIdentity(verified, currentSource) ||
                    !sourceIsVerifiedForRecovery(currentSource)
                ) throw sourceChangedDuringVerification()
                libraryDao.upsert(
                    current.copy(
                        downloadTaskId = taskId,
                        validationJson = if (sourceIdentityChanged) SOURCE_IDENTITY_INVALIDATED_MARKER else current.validationJson,
                        updatedAt = retryRequestedAt
                    )
                )
                taskDao.upsert(task)
                current.copy(
                    downloadTaskId = taskId,
                    validationJson = if (sourceIdentityChanged) SOURCE_IDENTITY_INVALIDATED_MARKER else current.validationJson
                )
            }
        }
        PendingDownloadHolder.addPendingFrom(task)
        DownloadService.resumeDownload(
            context.applicationContext,
            taskId,
            explicitRetry = true,
            retryRequestedAt = retryRequestedAt,
            // Source verification can span the cleanup request. Preserve the
            // durable timestamp guard for this cross-operation retry; the
            // service generation override is reserved for direct UI retries
            // already queued behind an in-process cleanup gate.
            preserveRetryTimestamp = true
        )
        persistedRow
    }

    /** Marks every unfinished bundle item cancelled and stops its active task. */
    suspend fun cancelBundleDownload(context: Context, bundleId: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // Serialize cancellation with plan persistence and the short
            // "persist row -> start service" transition. Missing sentinel rows
            // are intentional: startup recovery only considers active statuses,
            // so a queue cancelled before its first task is never resurrected.
            val rows = modelLibraryBundleStateMutex.withLock {
                currentCoroutineContext().ensureActive()
                val relation = requireNotNull(libraryDao.getWithItems(bundleId)) {
                    "Bundle was not found"
                }
                val family = ModelFamily.fromStoredValue(relation.bundle.family)
                    ?: throw ModelLibraryException(ModelLibraryErrorCode.BUNDLE_INVALID, "Bundle family is invalid")
                val root = File(
                    context.applicationContext.filesDir,
                    "model-library/${family.storageValue}/bundles/$bundleId"
                ).canonicalFile
                val current = libraryDao.getPendingArtifactsForBundle(bundleId)
                val byItem = current.filter { it.bundleItemId != null }.associateBy { it.bundleItemId }
                val now = System.currentTimeMillis()
                val cancellationRows = relation.items.map { item ->
                    val existing = byItem[item.id]
                    if (existing != null) {
                        if (existing.status == PendingArtifactStatus.PROMOTED.storedValue) existing
                        else existing.copy(
                            status = PendingArtifactStatus.CANCELLED.storedValue,
                            validationMessage = null,
                            updatedAt = nextStateTimestamp(existing.updatedAt, now)
                        )
                    } else {
                        val source = item.sourceId?.let { libraryDao.getSourceById(it) }
                        val filename = safeRelativeDownloadPath(
                            item.localFilename ?: item.relativePath ?: source?.filePath ?: source?.label ?: item.itemKey,
                            fallback = "artifact"
                        ).substringAfterLast('/')
                        val destination = resolveLocalFile(root, item, filename)
                        val staging = stagingPathFor(root, bundleId, item, filename)
                        PendingModelArtifactEntity(
                            id = "pending:bundle:$bundleId:${item.id}",
                            downloadTaskId = "bundle:$bundleId:${item.id}",
                            sourceId = item.sourceId,
                            bundleId = bundleId,
                            bundleItemId = item.id,
                            filename = filename,
                            stagingPath = staging.absolutePath,
                            destinationPath = destination.absolutePath,
                            requestedFamily = item.family,
                            requestedRole = item.role,
                            status = PendingArtifactStatus.CANCELLED.storedValue,
                            requiresManualPromotion = true,
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                }
                // Also stop stale rows whose item was removed from a draft while
                // an older task was still present.
                val knownItemIds = relation.items.mapTo(mutableSetOf()) { it.id }
                val staleRows = current.filter { it.bundleItemId !in knownItemIds &&
                    it.status != PendingArtifactStatus.PROMOTED.storedValue
                }.map { it.copy(
                    status = PendingArtifactStatus.CANCELLED.storedValue,
                    validationMessage = null,
                    updatedAt = nextStateTimestamp(it.updatedAt, now)
                ) }
                val allRows = cancellationRows + staleRows
                libraryDao.upsertPendingArtifactsAtomically(allRows)
                allRows.filter { it.status != PendingArtifactStatus.PROMOTED.storedValue }
            }
            rows.mapNotNull { row ->
                val taskId = row.downloadTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DownloadService.cancelDownload(context.applicationContext, row.filename, taskId)
                taskId
            }.size
        }
    }

    suspend fun browseSource(
        sourceId: String,
        bearerToken: String? = null,
        pageSize: Int = HuggingFaceFolderBrowser.DEFAULT_PAGE_SIZE,
        maxPages: Int = HuggingFaceFolderBrowser.DEFAULT_MAX_PAGES,
        cursor: String? = null
    ): Result<HfFolderListing> = withContext(Dispatchers.IO) {
        runCatching {
            val source = requireNotNull(libraryDao.getSourceById(sourceId)) { "Saved source was not found" }
            require(source.kind == ModelSourceKind.HUGGING_FACE_REPOSITORY.storedValue) {
                "Only a Hugging Face repository source can be browsed"
            }
            val repo = requireNotNull(source.repositoryId) { "Saved source has no repository ID" }
            folderBrowser.listFolder(
                repositoryId = repo,
                revision = source.revision,
                folderPath = source.filePath,
                bearerToken = bearerToken,
                pageSize = pageSize,
                maxPages = maxPages,
                cursor = cursor
            )
        }
    }

    /**
     * Lists another folder in a saved Hugging Face repository without
     * persisting a temporary source row for every breadcrumb. Authentication
     * remains request-scoped and the browser keeps its page/cursor bounds.
     */
    suspend fun browseSourceFolder(
        sourceId: String,
        folderPath: String?,
        bearerToken: String? = null,
        pageSize: Int = HuggingFaceFolderBrowser.DEFAULT_PAGE_SIZE,
        maxPages: Int = HuggingFaceFolderBrowser.DEFAULT_MAX_PAGES,
        cursor: String? = null
    ): Result<HfFolderListing> = withContext(Dispatchers.IO) {
        runCatching {
            val source = requireNotNull(libraryDao.getSourceById(sourceId)) { "Saved source was not found" }
            require(source.kind == ModelSourceKind.HUGGING_FACE_REPOSITORY.storedValue) {
                "Only a Hugging Face repository source can be browsed"
            }
            val repo = requireNotNull(source.repositoryId) { "Saved source has no repository ID" }
            folderBrowser.listFolder(
                repositoryId = repo,
                revision = source.revision,
                folderPath = folderPath,
                bearerToken = bearerToken,
                pageSize = pageSize,
                maxPages = maxPages,
                cursor = cursor
            )
        }
    }

    /** Rechecks remote availability immediately before a bundle download. */
    suspend fun verifySource(sourceId: String, bearerToken: String? = null): Result<ModelSourceEntity> = withContext(Dispatchers.IO) {
        val source = libraryDao.getSourceById(sourceId)
            ?: return@withContext Result.failure(ModelLibraryException(ModelLibraryErrorCode.SOURCE_NOT_FOUND, "Saved source was not found"))
        try {
            val token = bearerToken?.trim()?.takeIf { it.isNotBlank() }
            val evidence = when (ModelSourceKind.fromStoredValue(source.kind)) {
                ModelSourceKind.HUGGING_FACE_REPOSITORY -> {
                    folderBrowser.listFolder(requireNotNull(source.repositoryId), source.revision,
                        source.filePath, token, pageSize = 1, maxPages = 1)
                    source
                }
                ModelSourceKind.HUGGING_FACE_FILE -> verifyHttpSource(source, token)
                ModelSourceKind.HTTPS -> verifyHttpSource(source, null)
                null -> throw ModelLibraryException(ModelLibraryErrorCode.INVALID_URL, "Saved source kind is invalid")
            }
            val verified = commitSourceVerificationIfIdentityMatches(
                captured = source,
                evidence = evidence,
                verified = true,
                authRequired = false,
                validationStatus = "verified",
                lastErrorCode = null
            ) ?: return@withContext Result.failure(sourceChangedDuringVerification())
            Result.success(verified)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = when (failure) {
                is ModelLibraryException -> failure
                is HuggingFaceHttpException -> ModelLibraryException(failure.errorCode, "Hugging Face request failed", cause = failure)
                is SocketTimeoutException -> ModelLibraryException(ModelLibraryErrorCode.REQUEST_TIMEOUT, "Source request timed out", cause = failure)
                else -> ModelLibraryException(ModelLibraryErrorCode.NETWORK_FAILURE, "Source request failed", cause = failure)
            }
            val authentication = error.code in setOf(ModelLibraryErrorCode.AUTHENTICATION_REQUIRED, ModelLibraryErrorCode.AUTHENTICATION_REJECTED)
            val checkLater = error.code in setOf(ModelLibraryErrorCode.NETWORK_FAILURE, ModelLibraryErrorCode.REQUEST_TIMEOUT)
            val committed = commitSourceVerificationIfIdentityMatches(
                captured = source,
                evidence = source,
                verified = false,
                authRequired = authentication,
                validationStatus = if (authentication) "authentication" else if (checkLater) "needs_check" else "unavailable",
                lastErrorCode = error.code.name
            )
            if (committed == null) Result.failure(sourceChangedDuringVerification()) else Result.failure(error)
        }
    }

    /**
     * Commits only verification evidence for the identity that was fetched.
     * A source label or URL can be edited while the network call is in flight;
     * re-reading under the same state gate used by [saveSource] prevents a late
     * response from restoring the old URL, label, or verification state.
     */
    private suspend fun commitSourceVerificationIfIdentityMatches(
        captured: ModelSourceEntity,
        evidence: ModelSourceEntity,
        verified: Boolean,
        authRequired: Boolean,
        validationStatus: String,
        lastErrorCode: String?
    ): ModelSourceEntity? = modelLibraryBundleStateMutex.withLock {
        val current = libraryDao.getSourceById(captured.id) ?: return@withLock null
        if (!sameModelSourceIdentity(captured, current)) return@withLock null
        val now = System.currentTimeMillis()
        val committed = current.copy(
            verified = verified,
            authRequired = authRequired,
            expectedSha256 = evidence.expectedSha256 ?: current.expectedSha256,
            expectedSizeBytes = evidence.expectedSizeBytes ?: current.expectedSizeBytes,
            mediaType = evidence.mediaType ?: current.mediaType,
            revision = evidence.revision.takeIf { it.isNotBlank() } ?: current.revision,
            validationStatus = validationStatus,
            checkedAt = now,
            lastErrorCode = lastErrorCode,
            updatedAt = now
        )
        libraryDao.upsert(committed)
        committed
    }

    private fun sourceChangedDuringVerification(): ModelLibraryException =
        ModelLibraryException(
            ModelLibraryErrorCode.SOURCE_NOT_VERIFIED,
            "The saved source changed while it was being checked; verify the new link before downloading"
        )

    suspend fun recordProvenance(
        sourceId: String,
        reference: ModelArtifactReference,
        role: String? = null,
        artifactSha256: String? = null,
        sizeBytes: Long? = null
    ): Result<ModelProvenanceEntity> = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(libraryDao.getSourceById(sourceId)) { "Cannot record provenance for a missing source" }
            val provenance = ModelProvenanceEntity(
                sourceId = sourceId,
                modelKey = reference.modelKey ?: reference.displayName,
                family = reference.family.storedValue,
                role = role,
                localPath = File(reference.localPath).canonicalPath,
                artifactSha256 = artifactSha256?.trim()?.lowercase(Locale.US),
                sizeBytes = sizeBytes,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            libraryDao.replaceProvenanceForArtifact(provenance)
        }
    }

    suspend fun saveBundle(
        bundle: ModelBundleEntity,
        items: List<ModelBundleItemEntity>
    ): Result<ModelBundleWithItems> = withContext(Dispatchers.IO) {
        runCatching {
            require(bundle.name.trim().isNotEmpty()) { "Bundle name cannot be blank" }
            val family = ModelFamily.fromStoredValue(bundle.family)
                ?: error("Bundle family is invalid")
            require(items.map { it.itemKey }.distinct().size == items.size) {
                "Bundle item keys must be unique"
            }
            validateBundleLayout(items)
            items.forEach { item ->
                require(item.bundleId == bundle.id) { "Bundle item belongs to another bundle" }
                require(ModelFamily.fromStoredValue(item.family) == family) {
                    "Bundle item family must match the bundle family"
                }
                validateMultipart(item)
                item.sourceId?.let { sourceId ->
                    requireNotNull(libraryDao.getSourceById(sourceId)) {
                        "Bundle item source was not found"
                    }
                    // A source's family is where it was saved, not structural evidence.
                    // The same URL can supply an encoder to an SD or an LLM bundle.
                }
            }
            libraryDao.replaceBundle(
                bundle = bundle.copy(updatedAt = System.currentTimeMillis()),
                // Keep only the portable runtime whitelist. Paths, URLs with
                // credentials, and device-specific references never enter a
                // bundle definition or portable backup.
                items = items.map { it.copy(modelMetadataJson = PortableModelMetadata.sanitize(it.modelMetadataJson)) }
            )
        }
    }

    suspend fun inspectBundle(
        bundleId: String,
        localRoot: File? = null
    ): Result<ModelBundleInspection> = withContext(Dispatchers.IO) {
        runCatching {
            val relation = requireNotNull(libraryDao.getWithItems(bundleId)) { "Bundle was not found" }
            val missingSource = mutableListOf<String>()
            val unverifiedSource = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            val ready = mutableListOf<String>()
            val existing = mutableListOf<String>()
            relation.items.forEach { item ->
                val source = item.sourceId?.let { libraryDao.getSourceById(it) }
                if (item.sourceId == null || source == null) {
                    missingSource += item.id
                    return@forEach
                }
                if (!source.verified) {
                    unverifiedSource += item.id
                    return@forEach
                }
                if (item.family != relation.bundle.family || item.id in incompleteBundleGroupIds(relation.items)) {
                    invalid += item.id
                    return@forEach
                }
                val localFile = localRoot?.let { resolveLocalFile(it, item) }
                if (localFile != null && isVerifiedExisting(item, localFile, item.family)) {
                    existing += item.id
                } else {
                    ready += item.id
                }
            }
            ModelBundleInspection(relation.bundle, relation.items, missingSource, unverifiedSource, invalid, ready, existing)
        }
    }

    /**
     * Builds a deterministic missing-file queue. Requests are returned in
     * multipart order and can be consumed by [downloadSequentially]. A local
     * file is skipped only after digest/size or structural verification.
     */
    suspend fun planMissingDownloads(
        bundleId: String,
        localRoot: File
    ): Result<BundleDownloadPlan> = withContext(Dispatchers.IO) {
        runCatching {
            val relation = requireNotNull(libraryDao.getWithItems(bundleId)) { "Bundle was not found" }
            val requests = mutableListOf<BundleDownloadRequest>()
            val missingSource = mutableListOf<String>()
            val unverifiedSource = mutableListOf<String>()
            val existing = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            relation.items.sortedWith(compareBy<ModelBundleItemEntity> { it.partGroup.orEmpty() }.thenBy { it.partIndex ?: Int.MAX_VALUE }.thenBy { it.itemKey })
                .forEach { item ->
                    if (item.family != relation.bundle.family || item.id in incompleteBundleGroupIds(relation.items)) {
                        invalid += item.id
                        return@forEach
                    }
                    val source = item.sourceId?.let { libraryDao.getSourceById(it) }
                    if (source == null) {
                        missingSource += item.id
                        return@forEach
                    }
                    if (!source.verified) {
                        unverifiedSource += item.id
                        return@forEach
                    }
                    val filename = item.localFilename
                        ?: item.relativePath?.substringAfterLast('/')
                        ?: source.filePath?.substringAfterLast('/')
                        ?: source.label
                    val destination = resolveLocalFile(localRoot, item, filename)
                    val evidence = item.copy(expectedSha256 = source.expectedSha256 ?: item.expectedSha256,
                        expectedSizeBytes = source.expectedSizeBytes ?: item.expectedSizeBytes)
                    val provenancePath = libraryDao.getProvenanceBySource(source.id).firstNotNullOfOrNull { edge ->
                        val candidate = edge.localPath?.takeIf { it.isNotBlank() }?.let(::File)
                        candidate?.takeIf { it.exists() &&
                            (isVerifiedExisting(evidence, it, item.family) ||
                                evidence.expectedSha256 == null && edge.artifactSha256 != null &&
                                (evidence.expectedSizeBytes == null || it.length() == evidence.expectedSizeBytes) &&
                                artifactFileSha256(it) == edge.artifactSha256)
                        }
                    }
                    if (provenancePath != null ||
                        destination.exists() && isVerifiedExisting(evidence, destination, item.family)
                    ) {
                        existing += item.id
                        return@forEach
                    }
                    val url = source.resolvedDownloadUrl()
                        ?: run {
                            missingSource += item.id
                            return@forEach
                        }
                    requests += BundleDownloadRequest(item, source, url, destination)
                }
            BundleDownloadPlan(relation.bundle, requests, missingSource, unverifiedSource, existing, invalid)
        }
    }

    /** Executes each request only after the previous request completes. */
    suspend fun downloadSequentially(
        plan: BundleDownloadPlan,
        download: suspend (BundleDownloadRequest) -> Unit
    ) {
        plan.requests.forEach { request -> download(request) }
    }

    /**
     * Starts or resumes every missing item in one bundle, one foreground task
     * at a time. Each task is represented by a durable pending artifact before
     * the service starts, so a process death leaves enough information for a
     * later call to resume the same item rather than silently restarting it.
     * The service stages files only; this coordinator inspects them afterward
     * and lets the service-side finalizer register known runtime rows.
     */
    suspend fun startBundleDownloadQueue(
        context: Context,
        bundleId: String,
        localRoot: File,
        bearerToken: String? = null,
        timeoutMillis: Long = DEFAULT_QUEUE_TIMEOUT_MILLIS,
        /** Only a user action may reopen FAILED/CANCELLED pending rows. */
        explicitRetry: Boolean = false
    ): Result<BundleDownloadRunResult> = withContext(Dispatchers.IO) {
        runCatching {
            val relation = requireNotNull(libraryDao.getWithItems(bundleId)) { "Bundle was not found" }
            relation.items.mapNotNull { it.sourceId }.distinct().forEach { sourceId ->
                currentCoroutineContext().ensureActive()
                verifySource(sourceId, bearerToken).getOrThrow()
            }
            currentCoroutineContext().ensureActive()
            // FAILED/CANCELLED rows remain durable sentinels until the service
            // receives an explicit retry intent. The service re-arms them only
            // after its per-task cancellation lock has drained old callbacks.
            val retryRequestedAt = System.currentTimeMillis()
            val plan = planMissingDownloads(bundleId, localRoot).getOrThrow()
            val blocked = (plan.missingSourceItemIds + plan.unverifiedSourceItemIds + plan.invalidItemIds).toSet()
            if (relation.items.isEmpty() || relation.items.any { it.required && it.id in blocked }) {
                throw ModelLibraryException(ModelLibraryErrorCode.BUNDLE_INVALID,
                    "Complete the required bundle files and verify their sources before downloading")
            }
            val started = mutableListOf<String>()
            val completed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val db = AppDatabase.getDatabase(context)
            val taskDao = db.downloadTaskDao()
            val token = bearerToken?.trim()?.takeIf { it.isNotBlank() }
            if (plan.requests.any { it.source.authRequired } && token == null) {
                throw ModelLibraryException(
                    ModelLibraryErrorCode.AUTHENTICATION_REQUIRED,
                    "One or more bundle sources require an authentication token"
                )
            }
            // A process can die after the staged payload is moved to its
            // destination but before the pending row is marked PROMOTED. The
            // plan therefore may classify the destination as an existing
            // verified file and omit it from requests. Re-run the same
            // finalizer for those durable rows so known files are registered
            // even when no screen or task remains alive.
            libraryDao.getPendingArtifactsForBundle(bundleId)
                .asSequence()
                .filter {
                    it.status != PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue &&
                        (explicitRetry || it.status !in setOf(
                            PendingArtifactStatus.CANCELLED.storedValue,
                            PendingArtifactStatus.FAILED.storedValue
                        ))
                }
                .forEach { artifact ->
                    val destination = artifact.destinationPath
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::File)
                    val item = artifact.bundleItemId?.let { libraryDao.getBundleItemById(it) }
                    val source = artifact.sourceId?.let { libraryDao.getSourceById(it) }
                    if (destination != null && item != null && source != null &&
                        isVerifiedExisting(item, destination, source.family)
                    ) {
                        val finalized = ModelArtifactFinalizer.finalizeIfKnown(
                            database = db,
                            artifact = artifact,
                            downloadedFile = destination,
                            metadata = PendingArtifactRuntimeMetadata(
                                repoId = source.repositoryId ?: source.url
                            )
                        ).getOrThrow()
                        if (finalized.promoted) {
                            completed += item.id
                        } else {
                            failed += item.id
                        }
                    }
                }
            // Persist the complete missing-file plan before starting the first
            // foreground task. A process death can therefore resume every
            // remaining item from pending_model_artifacts.
            val pendingRows = modelLibraryBundleStateMutex.withLock {
                currentCoroutineContext().ensureActive()
                val requestedRows = plan.requests.map { request ->
                    val taskId = "bundle:$bundleId:${request.item.id}"
                    val pendingId = "pending:$taskId"
                    val stagingPath = stagingPathFor(localRoot, bundleId, request.item, request.destination.name)
                    stagingPath.parentFile?.mkdirs()
                    val existing = libraryDao.getPendingArtifactById(pendingId)
                    // Cancellation wins over a stale plan snapshot. The user
                    // must explicitly retry to reopen this row.
                    existing ?: PendingModelArtifactEntity(
                        id = pendingId,
                        filename = request.destination.name,
                        stagingPath = stagingPath.absolutePath,
                        downloadTaskId = taskId,
                        sourceId = request.source.id,
                        bundleId = bundleId,
                        bundleItemId = request.item.id,
                        requestedFamily = ModelFamily.fromStoredValue(plan.bundle.family)?.storedValue,
                        requestedRole = request.item.role,
                        destinationPath = request.destination.absolutePath,
                        status = PendingArtifactStatus.STAGED.storedValue,
                        requiresManualPromotion = true,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
                // A verified existing member still needs a durable artifact row
                // when it belongs to a directory/split group. Otherwise the
                // finalizer cannot prove that every group member is materialized
                // after a process restart.
                val existingRows = plan.verifiedExistingItemIds.mapNotNull { itemId ->
                    val item = relation.items.firstOrNull { it.id == itemId } ?: return@mapNotNull null
                    val source = item.sourceId?.let { libraryDao.getSourceById(it) }
                        ?: return@mapNotNull null
                    val existing = findVerifiedExistingPath(item, source, localRoot)
                        ?: return@mapNotNull null
                    val filename = item.localFilename
                        ?: item.relativePath?.substringAfterLast('/')
                        ?: source.filePath?.substringAfterLast('/')
                        ?: source.label
                    val bundlePath = resolveExistingBundlePath(existing, localRoot, item, filename)
                    val stagingPath = stagingPathFor(localRoot, bundleId, item, filename)
                    stagingPath.parentFile?.mkdirs()
                    val taskId = "bundle:$bundleId:${item.id}"
                    val pendingId = "pending:$taskId"
                    val current = libraryDao.getPendingArtifactById(pendingId)
                    current ?: PendingModelArtifactEntity(
                        id = pendingId,
                        filename = filename,
                        stagingPath = stagingPath.absolutePath,
                        downloadTaskId = taskId,
                        sourceId = source.id,
                        bundleId = bundleId,
                        bundleItemId = item.id,
                        requestedFamily = ModelFamily.fromStoredValue(plan.bundle.family)?.storedValue,
                        requestedRole = item.role,
                        destinationPath = bundlePath.absolutePath,
                        status = PendingArtifactStatus.INSPECTING.storedValue,
                        requiresManualPromotion = true,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
                val all = (requestedRows + existingRows).distinctBy { it.id }
                // Persist the complete queue and the existing group members as
                // one transaction before any service start.
                libraryDao.upsertPendingArtifactsAtomically(all)
                all
            }
            // Room transaction makes the complete queue durable as one unit;
            // process death cannot leave only the first few rows resumable.
            pendingRows.filter {
                it.bundleItemId in plan.verifiedExistingItemIds &&
                    (explicitRetry || it.status !in setOf(
                        PendingArtifactStatus.CANCELLED.storedValue,
                        PendingArtifactStatus.FAILED.storedValue
                    ))
            }.forEach { artifact ->
                currentCoroutineContext().ensureActive()
                val destination = artifact.destinationPath?.trim()?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(artifact.stagingPath)
                val item = artifact.bundleItemId?.let { libraryDao.getBundleItemById(it) }
                val source = artifact.sourceId?.let { libraryDao.getSourceById(it) }
                if (item != null && source != null && destination.exists()) {
                    val finalized = ModelArtifactFinalizer.finalizeIfKnown(
                        database = db,
                        artifact = artifact,
                        downloadedFile = destination,
                        metadata = PendingArtifactRuntimeMetadata(
                            repoId = source.repositoryId ?: source.url
                        )
                    ).getOrThrow()
                    if (item.id !in completed) {
                        if (finalized.promoted) completed += item.id else failed += item.id
                    }
                }
            }
            val pendingByItemId = plan.requests.associate { request ->
                request.item.id to requireNotNull(
                    pendingRows.firstOrNull { it.bundleItemId == request.item.id }
                        ?: libraryDao.getPendingArtifactById("pending:bundle:$bundleId:${request.item.id}")
                )
            }
            val pendingIds = pendingRows.map { it.id }.toMutableList()
            for (request in plan.requests) {
                currentCoroutineContext().ensureActive()
                val taskId = "bundle:$bundleId:${request.item.id}"
                val pendingId = "pending:$taskId"
                val stagingPath = stagingPathFor(localRoot, bundleId, request.item, request.destination.name)
                stagingPath.parentFile?.mkdirs()
                val persisted = requireNotNull(pendingByItemId[request.item.id])
                val existingStaged = File(persisted.stagingPath)
                val pendingStatus = PendingArtifactStatus.fromStoredValue(persisted.status)
                if (pendingStatus == PendingArtifactStatus.CANCELLED && !explicitRetry) {
                    throw CancellationException("Bundle queue was cancelled")
                }
                if (pendingStatus == PendingArtifactStatus.PROMOTED && existingStaged.exists()) {
                    val finalized = ModelArtifactFinalizer.finalizeIfKnown(
                        database = db,
                        artifact = persisted,
                        downloadedFile = existingStaged,
                        metadata = PendingArtifactRuntimeMetadata(
                            repoId = request.source.repositoryId ?: request.source.url
                        )
                    ).getOrThrow()
                    if (finalized.promoted) {
                        completed += request.item.id
                    } else {
                        failed += request.item.id
                    }
                    continue
                }
                if (pendingStatus == PendingArtifactStatus.VALIDATED && existingStaged.exists() &&
                    taskDao.getById(taskId) == null
                ) {
                    val finalized = ModelArtifactFinalizer.finalizeIfKnown(
                        database = db,
                        artifact = persisted,
                        downloadedFile = existingStaged,
                        metadata = PendingArtifactRuntimeMetadata(
                            repoId = request.source.repositoryId ?: request.source.url
                        )
                    ).getOrThrow()
                    if (finalized.promoted) {
                        completed += request.item.id
                    } else {
                        failed += request.item.id
                    }
                    continue
                }
                if (pendingStatus == PendingArtifactStatus.NEEDS_MANUAL_PROMOTION) {
                    failed += request.item.id
                    continue
                }
                if (existingStaged.exists() && (existingStaged.isDirectory || existingStaged.length() > 0L) &&
                    taskDao.getById(taskId) == null && pendingStatus == PendingArtifactStatus.STAGED
                ) {
                    val finalized = ModelArtifactFinalizer.finalizeIfKnown(
                        database = db,
                        artifact = persisted,
                        downloadedFile = existingStaged,
                        metadata = PendingArtifactRuntimeMetadata(
                            repoId = request.source.repositoryId ?: request.source.url
                        )
                    ).getOrThrow()
                    if (finalized.promoted) {
                        completed += request.item.id
                        continue
                    }
                    if (libraryDao.getPendingArtifactById(pendingId)?.status == PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue) {
                        failed += request.item.id
                        continue
                    }
                }
                val family = ModelFamily.fromStoredValue(plan.bundle.family)
                    ?: error("Bundle family is invalid")
                // Re-read the row while holding the same mutex as cancellation.
                // A stale plan object must never start a task after the user has
                // cancelled the queue.
                modelLibraryBundleStateMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val activeArtifact = libraryDao.getPendingArtifactById(pendingId)
                    if (activeArtifact == null ||
                        (activeArtifact.status == PendingArtifactStatus.CANCELLED.storedValue && !explicitRetry)
                    ) {
                        throw CancellationException("Bundle queue was cancelled")
                    }
                    val existingTask = taskDao.getById(taskId)
                    val runtimeType = runtimeTypeFor(family, request.item.role)
                    PendingDownloadHolder.addPending(
                        downloadId = taskId,
                        filename = stagingPath.name,
                        repoId = request.source.repositoryId ?: request.source.url,
                        progressKey = taskId,
                        type = runtimeType,
                        destPath = stagingPath.absolutePath,
                        huggingFaceToken = bearerTokenForSource(
                            kind = ModelSourceKind.fromStoredValue(request.source.kind),
                            requestedToken = token
                        ),
                        sourceId = request.source.id,
                        artifactFamily = family.storedValue,
                        artifactRole = request.item.role,
                        pendingArtifactId = pendingId,
                        stageOnly = true
                    )
                    if (existingTask != null) {
                        DownloadService.resumeDownload(
                            context,
                            taskId,
                            explicitRetry = explicitRetry,
                            retryRequestedAt = retryRequestedAt,
                            preserveRetryTimestamp = explicitRetry
                        )
                    } else {
                        DownloadService.startDownload(
                            context = context,
                            url = request.url,
                            destPath = stagingPath.absolutePath,
                            filename = stagingPath.name,
                            downloadId = taskId,
                            explicitRetry = explicitRetry,
                            retryRequestedAt = retryRequestedAt
                        )
                    }
                }
                started += request.item.id
                awaitDownloadCompletion(
                    context = context,
                    taskId = taskId,
                    pendingArtifactId = pendingId,
                    stagingPath = stagingPath,
                    timeoutMillis = timeoutMillis
                )
                currentCoroutineContext().ensureActive()
                val inspected = inspectPendingArtifact(pendingId).getOrThrow()
                if (inspected.status == PendingArtifactStatus.VALIDATED.storedValue ||
                    inspected.status == PendingArtifactStatus.PROMOTED.storedValue
                ) {
                    completed += request.item.id
                } else {
                    failed += request.item.id
                }
            }
            BundleDownloadRunResult(
                bundleId = bundleId,
                startedItemIds = started,
                completedItemIds = completed,
                pendingArtifactIds = pendingIds,
                failedItemIds = failed,
                skippedExistingItemIds = plan.verifiedExistingItemIds,
                missingSourceItemIds = plan.missingSourceItemIds + plan.unverifiedSourceItemIds + plan.invalidItemIds
            )
        }
    }

    /**
     * Rehydrates bundle queues left by a process death. The pending rows carry
     * the bundle/item/task relationship and the staging layout carries the
     * selected model root, so callers do not need an in-memory queue object.
     */
    suspend fun resumePendingBundleQueue(
        context: Context,
        bundleId: String,
        bearerToken: String? = null
    ): Result<BundleDownloadRunResult>? = withContext(Dispatchers.IO) {
        val rows = libraryDao.observeByStatuses(
            listOf(
                PendingArtifactStatus.STAGED.storedValue,
                PendingArtifactStatus.INSPECTING.storedValue,
                PendingArtifactStatus.VALIDATED.storedValue
            )
        ).first().filter { it.bundleId == bundleId && !it.downloadTaskId.isNullOrBlank() }
        val root = rows.asSequence()
            .mapNotNull { queueRootFromStaging(it.stagingPath) }
            .firstOrNull()
            ?: return@withContext null
        // LlamaApplication owns the process-wide bundle key around this call.
        // Direct callers still need serialization, but must not nest the same
        // key or they would suppress their own recovery as a duplicate.
        pendingBundleRecoveryMutex.withLock {
            runCatching {
                startBundleDownloadQueue(
                    context = context,
                    bundleId = bundleId,
                    localRoot = root,
                    bearerToken = bearerToken
                ).getOrThrow()
            }
        }
    }

    suspend fun resumePendingBundleQueues(
        context: Context,
        bearerToken: String? = null
    ): List<Result<BundleDownloadRunResult>> = withContext(Dispatchers.IO) {
        val rows = libraryDao.observeByStatuses(
            listOf(
                PendingArtifactStatus.STAGED.storedValue,
                PendingArtifactStatus.INSPECTING.storedValue,
                PendingArtifactStatus.VALIDATED.storedValue
            )
        ).first()
        rows.asSequence()
            .filter { !it.bundleId.isNullOrBlank() && !it.downloadTaskId.isNullOrBlank() }
            .groupBy { it.bundleId!! }
            .mapNotNull { (bundleId, _) ->
                resumePendingBundleQueue(context, bundleId, bearerToken)
            }
            .toList()
    }

    /** Rehydrates custom stage-only tasks after the process owning DownloadService dies. */
    suspend fun resumePendingCustomDownloads(
        context: Context,
        bearerToken: String? = null
    ): Int = withContext(Dispatchers.IO) {
        val rows = libraryDao.observeByStatuses(
            listOf(
                PendingArtifactStatus.STAGED.storedValue,
                PendingArtifactStatus.INSPECTING.storedValue,
                PendingArtifactStatus.VALIDATED.storedValue
            )
        ).first().filter { it.bundleId == null && !it.downloadTaskId.isNullOrBlank() }
        val taskDao = AppDatabase.getDatabase(context).downloadTaskDao()
        rows.count { row ->
            val taskId = requireNotNull(row.downloadTaskId)
            val completion = CompletableDeferred<Boolean>()
            val job = ModelLibraryQueueScope.launch("custom-recovery:$taskId") {
                try {
                    completion.complete(runCatching {
                        val task = taskDao.getById(taskId)
                        val staged = File(row.stagingPath)
                        val source = row.sourceId?.let { libraryDao.getSourceById(it) }
                        if (task != null) {
                            if (task.status == com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED) {
                                // A stale process may have persisted the task
                                // sentinel before the artifact row. Startup
                                // must settle on cancellation rather than
                                // turning it into an automatic retry.
                                modelLibraryBundleStateMutex.withLock {
                                    libraryDao.getPendingArtifactById(row.id)?.let { current ->
                                        if (current.status in setOf(
                                                PendingArtifactStatus.STAGED.storedValue,
                                                PendingArtifactStatus.INSPECTING.storedValue,
                                                PendingArtifactStatus.VALIDATED.storedValue
                                            )
                                        ) {
                                            libraryDao.upsert(
                                                current.copy(
                                                    status = PendingArtifactStatus.CANCELLED.storedValue,
                                                    validationMessage = null,
                                                    updatedAt = nextStateTimestamp(current.updatedAt)
                                                )
                                            )
                                        }
                                    }
                                }
                                false
                            } else if (task.status == com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED) {
                                false
                            } else if (!sourceIsVerifiedForRecovery(source)) {
                                // Startup must never turn an unverified saved
                                // link into a network request. The user can
                                // explicitly recheck the source and retry it.
                                false
                            } else {
                                PendingDownloadHolder.addPendingFrom(task)
                                DownloadService.resumeDownload(context.applicationContext, taskId)
                                true
                            }
                        } else if (staged.isFile &&
                            (row.sourceId == null || sourceIsVerifiedForRecovery(source))
                        ) {
                            ModelArtifactFinalizer.finalizeIfKnown(
                                database = AppDatabase.getDatabase(context),
                                artifact = row,
                                downloadedFile = staged,
                                metadata = PendingArtifactRuntimeMetadata(repoId = source?.repositoryId ?: source?.url ?: "model-library")
                            ).getOrThrow()
                            true
                        } else {
                            false
                        }
                    }.getOrDefault(false))
                } catch (cancelled: CancellationException) {
                    completion.cancel(cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    completion.complete(false)
                    throw failure
                }
            }
            if (job == null) false else completion.await()
        }
    }

    suspend fun stagePendingArtifact(
        filename: String,
        stagingPath: String,
        downloadTaskId: String? = null,
        sourceId: String? = null,
        bundleId: String? = null,
        bundleItemId: String? = null,
        requestedFamily: ModelFamily? = null,
        requestedRole: String? = null,
        destinationPath: String? = null,
        id: String? = null
    ): Result<PendingModelArtifactEntity> = withContext(Dispatchers.IO) {
        runCatching {
            require(filename.trim().isNotEmpty()) { "Pending artifact filename cannot be blank" }
            require(stagingPath.trim().isNotEmpty()) { "Pending artifact staging path cannot be blank" }
            sourceId?.let { requireNotNull(libraryDao.getSourceById(it)) { "Pending source was not found" } }
            val row = PendingModelArtifactEntity(
                id = id ?: java.util.UUID.randomUUID().toString(),
                downloadTaskId = downloadTaskId,
                sourceId = sourceId,
                bundleId = bundleId,
                bundleItemId = bundleItemId,
                filename = filename,
                stagingPath = stagingPath,
                destinationPath = destinationPath,
                requestedFamily = requestedFamily?.storedValue,
                requestedRole = requestedRole,
                status = PendingArtifactStatus.STAGED.storedValue,
                requiresManualPromotion = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            libraryDao.upsert(row)
            row
        }
    }

    suspend fun inspectPendingArtifact(id: String): Result<PendingModelArtifactEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val row = requireNotNull(libraryDao.getPendingArtifactById(id)) { "Pending artifact was not found" }
            if (row.status == PendingArtifactStatus.PROMOTED.storedValue) return@runCatching row
            val target = File(row.stagingPath)
            val group = resolvePendingArtifactGroup(libraryDao, row, target)
            if (!group.complete) return@runCatching row
            val result = ModelArtifactRecognizer.inspect(group.entry)
            val requestedFamily = ModelFamily.fromStoredValue(row.requestedFamily)
            val roleRequiresManual = requestedFamily != null &&
                requiresManualRoleSelection(requestedFamily, row.requestedRole) &&
                (result.requiresManualPromotion || result.detectedType != runtimeTypeFor(requestedFamily, row.requestedRole).name)
            val updated = row.copy(
                detectedFamily = requestedFamily?.storedValue ?: result.family?.storedValue,
                detectedRole = row.requestedRole ?: result.role,
                detectedType = if (roleRequiresManual) {
                    runtimeTypeFor(requestedFamily!!, row.requestedRole).name
                } else {
                    result.detectedType
                },
                status = if (!roleRequiresManual && result.isStructurallyValid && !result.requiresManualPromotion) {
                    PendingArtifactStatus.VALIDATED.storedValue
                } else {
                    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue
                },
                validationJson = result.validationJson,
                validationMessage = if (roleRequiresManual) {
                    "Artifact is structurally valid; confirm the requested companion role"
                } else {
                    result.validationMessage
                },
                requiresManualPromotion = roleRequiresManual || result.requiresManualPromotion,
                updatedAt = System.currentTimeMillis()
            )
            // Inspection is asynchronous with cancellation. The guarded DAO
            // write prevents a late recognizer result from reviving a
            // CANCELLED sentinel.
            libraryDao.upsertActiveArtifact(updated)
            updated
        }
    }

    /**
     * Manual promotion only updates the pending state. The caller remains
     * responsible for inserting the existing runtime ModelEntity/LiteRT row,
     * which keeps this layer from inventing an UNKNOWN ModelType.
     */
    suspend fun promotePendingArtifact(
        id: String,
        family: ModelFamily,
        displayName: String,
        role: String? = null,
        modelKey: String? = null
    ): Result<ModelArtifactReference> = withContext(Dispatchers.IO) {
        runCatching {
            val row = requireNotNull(libraryDao.getPendingArtifactById(id)) { "Pending artifact was not found" }
            val group = resolvePendingArtifactGroup(libraryDao, row, File(row.stagingPath))
            if (!group.complete) throw ModelLibraryException(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED,
                "Required multipart files are missing")
            val result = ModelArtifactRecognizer.validateForPromotion(group.entry, family, role ?: row.requestedRole)
            if (!result.isStructurallyValid) {
                throw ModelLibraryException(
                    result.errorCode ?: ModelLibraryErrorCode.RECOGNITION_FAILED,
                    result.validationMessage ?: "Artifact failed structural validation"
                )
            }
            val reference = ModelArtifactReference(
                family = family,
                localPath = row.stagingPath,
                displayName = displayName,
                modelKey = modelKey ?: row.promotedModelKey ?: displayName
            )
            libraryDao.upsertActiveArtifact(
                row.copy(
                    requestedFamily = family.storedValue,
                    requestedRole = role ?: row.requestedRole,
                    detectedFamily = family.storedValue,
                    detectedRole = result.role ?: role,
                    detectedType = result.detectedType
                        ?: runtimeTypeFor(family, role ?: row.requestedRole).name,
                    status = PendingArtifactStatus.PROMOTED.storedValue,
                    validationJson = result.validationJson,
                    validationMessage = result.validationMessage,
                    requiresManualPromotion = false,
                    promotedModelKey = reference.modelKey,
                    promotedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            reference
        }
    }

    /**
     * Promotes a validated pending artifact and registers it in the existing
     * runtime table. LiteRT keeps its dedicated table; all other families use
     * the existing ModelEntity/ModelType contract.
     */
    suspend fun promotePendingArtifact(
        context: Context,
        id: String,
        family: ModelFamily,
        displayName: String,
        role: String? = null,
        modelKey: String? = null,
        metadataJson: String? = null
    ): Result<ModelArtifactReference> = withContext(Dispatchers.IO) {
        runCatching {
            val row = requireNotNull(libraryDao.getPendingArtifactById(id)) { "Pending artifact was not found" }
            val group = resolvePendingArtifactGroup(libraryDao, row, File(row.stagingPath))
            if (!group.complete) throw ModelLibraryException(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED,
                "Required multipart files are missing")
            val staged = group.entry.canonicalFile
            val effectiveRole = role ?: row.requestedRole ?: row.detectedRole
            val result = ModelArtifactRecognizer.validateForPromotion(staged, family, effectiveRole)
            if (!result.isStructurallyValid) {
                throw ModelLibraryException(
                    result.errorCode ?: ModelLibraryErrorCode.RECOGNITION_FAILED,
                    result.validationMessage ?: "Artifact failed structural validation"
                )
            }
            val reference = ModelArtifactReference(
                family = family,
                localPath = staged.absolutePath,
                displayName = displayName,
                modelKey = modelKey ?: row.promotedModelKey ?: displayName
            )
            val destination = staged.takeIf { group.members.size > 1 || it.isDirectory } ?: row.destinationPath?.trim()?.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
                ?: staged
            if (staged.absolutePath != destination.absolutePath) {
                copyArtifactWithoutOverwrite(staged, destination, acceptIdentical = true)
            }
            val source = row.sourceId?.let { libraryDao.getSourceById(it) }
            val repoId = source?.repositoryId ?: source?.url ?: "model-library"
            val portableMetadata = JSONObject(PortableModelMetadata.sanitize(metadataJson ?: row.bundleItemId
                ?.let { libraryDao.getBundleItemById(it)?.modelMetadataJson }))
            val database = libraryDaoDatabase(context)
            val installedSize = com.example.llamadroid.data.model.physicalFiles(destination.absolutePath).values.sum()
            val installedHash = artifactFileSha256(destination)
            val registeredKey = database.withTransaction {
            group.members.forEach { ensurePendingArtifactActive(libraryDao, it.id) }
            val registeredKey = if (family == ModelFamily.LITERT) {
                val previous = database.liteRtModelDao().getByPath(destination.absolutePath)
                libraryDaoDatabase(context).liteRtModelDao().insert(
                    com.example.llamadroid.data.model.LiteRtModelEntity(
                        id = previous?.id ?: 0L,
                        displayName = displayName,
                        path = destination.absolutePath,
                        sourceUri = source?.url,
                        repoId = repoId,
                        filename = destination.name,
                        sizeBytes = installedSize,
                        backendPreference = portableMetadata?.optString("liteRtBackend", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: com.example.llamadroid.data.model.LITERT_BACKEND_AUTO,
                        supportsCpu = portableMetadata.optBoolean("supportsCpu", true),
                        supportsGpu = portableMetadata.optBoolean("supportsGpu", true),
                        supportsNpu = portableMetadata.optBoolean("supportsNpu", false),
                        supportsVision = portableMetadata.optBoolean("supportsVision", false),
                        supportsAudio = portableMetadata.optBoolean("supportsAudio", false),
                        supportsEmbedding = portableMetadata.optBoolean("supportsEmbedding", false),
                        maxContextTokens = portableMetadata.optInt("maxContextTokens", 0).takeIf { it > 0 }
                    )
                )
                .let { "litert:$it" }
            } else {
                val key = availableModelRecordKey(database, destination, id)
                val runtimeType = runtimeModelTypeFor(family, effectiveRole)
                libraryDaoDatabase(context).modelDao().insertModel(
                    ModelEntity(
                        filename = key,
                        path = destination.absolutePath,
                        sizeBytes = installedSize,
                        type = runtimeType,
                        repoId = repoId,
                        isDownloaded = true,
                        isVision = portableMetadata?.optBoolean(
                            "isVision",
                            effectiveRole?.contains("vision", ignoreCase = true) == true
                        ) ?: (effectiveRole?.contains("vision", ignoreCase = true) == true),
                        sdCapabilities = portableMetadata?.optString("sdCapabilities")?.takeIf { it.isNotBlank() },
                        sdFamily = portableMetadata?.optString("sdFamily")?.takeIf { it.isNotBlank() },
                        sdVariant = portableMetadata?.optString("sdVariant")?.takeIf { it.isNotBlank() },
                        sdCompatProfiles = portableMetadata?.optString("sdCompatProfiles")?.takeIf { it.isNotBlank() },
                        onnxCapabilities = portableMetadata?.optString("onnxCapabilities")?.takeIf { it.isNotBlank() },
                        onnxAssetKind = portableMetadata?.optString("onnxAssetKind")?.takeIf { it.isNotBlank() },
                        onnxPipelineFamily = portableMetadata?.optString("onnxPipelineFamily")?.takeIf { it.isNotBlank() }
                    )
                )
                key
            }
            libraryDao.upsert(
                row.copy(
                    stagingPath = destination.absolutePath,
                    requestedFamily = family.storedValue,
                    requestedRole = effectiveRole,
                    detectedFamily = family.storedValue,
                    detectedRole = effectiveRole,
                    detectedType = runtimeModelTypeFor(family, effectiveRole).name,
                    status = PendingArtifactStatus.PROMOTED.storedValue,
                    validationJson = result.validationJson,
                    validationMessage = result.validationMessage,
                    requiresManualPromotion = false,
                    promotedModelKey = registeredKey,
                    promotedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            row.sourceId?.let { sourceId ->
                libraryDao.upsert(
                    ModelProvenanceEntity(
                        id = "pending:$id",
                        sourceId = sourceId,
                        modelKey = registeredKey,
                        family = family.storedValue,
                        role = effectiveRole,
                        localPath = destination.absolutePath,
                        artifactSha256 = installedHash,
                        sizeBytes = installedSize,
                        importedAt = row.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            registeredKey
            }
            if (staged.absolutePath != destination.absolutePath) {
                if (staged.isDirectory) staged.deleteRecursively() else staged.delete()
            }
            reference.copy(localPath = destination.absolutePath, modelKey = registeredKey).also {
                if (group.members.size > 1) markGroupInstalled(libraryDao, group, it)
            }
        }
    }

    private fun libraryDaoDatabase(context: Context): AppDatabase = AppDatabase.getDatabase(context)

    private fun validateMultipart(item: ModelBundleItemEntity) {
        if (item.partGroup == null) {
            require(item.partIndex == null && item.partCount == null) {
                "Multipart index/count require a part group"
            }
            return
        }
        require(item.partCount != null && item.partCount > 0) { "Multipart count must be positive" }
        require(item.partIndex != null && item.partIndex in 0 until item.partCount) {
            "Multipart index must be within the declared part count"
        }
    }

    private fun resolveLocalFile(root: File, item: ModelBundleItemEntity, fallbackName: String? = null): File {
        val relative = item.relativePath?.trim()?.takeIf { it.isNotBlank() }
            ?: item.localFilename?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackName?.trim()?.takeIf { it.isNotBlank() }
            ?: item.itemKey
        val candidate = File(root, relative)
        val rootCanonical = root.canonicalFile
        val targetCanonical = candidate.canonicalFile
        require(
            targetCanonical.absolutePath == rootCanonical.absolutePath ||
                targetCanonical.absolutePath.startsWith(rootCanonical.absolutePath + File.separator)
        ) { "Bundle item path escapes the selected model root" }
        return targetCanonical
    }

    private fun isVerifiedExisting(item: ModelBundleItemEntity, file: File, family: String): Boolean {
        if (!file.exists()) return false
        item.expectedSizeBytes?.let { expected ->
            if (file.length() != expected) return false
        }
        item.expectedSha256?.trim()?.lowercase(Locale.US)?.let { expected ->
            if (!expected.matches(Regex("[a-f0-9]{64}"))) return false
            if (sha256(file) != expected) return false
        }
        if (item.expectedSha256 != null) return true
        // A valid container establishes compatibility, never the identity of a source.
        // Without a saved digest, the planner may use a matching provenance digest instead.
        return false
    }

    /** Finds a digest-confirmed installed file, including one outside the bundle root. */
    private suspend fun findVerifiedExistingPath(
        item: ModelBundleItemEntity,
        source: ModelSourceEntity,
        localRoot: File
    ): File? {
        val evidence = item.copy(
            expectedSha256 = source.expectedSha256 ?: item.expectedSha256,
            expectedSizeBytes = source.expectedSizeBytes ?: item.expectedSizeBytes
        )
        val provenancePath = libraryDao.getProvenanceBySource(source.id).firstNotNullOfOrNull { edge ->
            val candidate = edge.localPath?.takeIf { it.isNotBlank() }?.let(::File)
            candidate?.takeIf { it.exists() &&
                (isVerifiedExisting(evidence, it, item.family) ||
                    evidence.expectedSha256 == null && edge.artifactSha256 != null &&
                    (evidence.expectedSizeBytes == null || it.length() == evidence.expectedSizeBytes) &&
                    artifactFileSha256(it) == edge.artifactSha256)
            }
        }
        if (provenancePath != null) return provenancePath.canonicalFile
        val destination = runCatching { resolveLocalFile(localRoot, item) }.getOrNull()
        return destination?.takeIf { isVerifiedExisting(evidence, it, item.family) }
    }

    /** Reuses a verified old file while materializing it at the bundle's exact relative path. */
    private fun resolveExistingBundlePath(
        existing: File,
        localRoot: File,
        item: ModelBundleItemEntity,
        filename: String
    ): File {
        val destination = resolveLocalFile(localRoot, item, filename)
        if (existing.canonicalPath != destination.canonicalPath) {
            copyArtifactWithoutOverwrite(existing, destination, acceptIdentical = true)
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun verifyHttpSource(source: ModelSourceEntity, token: String?): ModelSourceEntity {
        val url = source.resolvedDownloadUrl()
            ?: throw ModelLibraryException(ModelLibraryErrorCode.INVALID_URL, "Saved source has no downloadable file URL")
        val request = Request.Builder().url(url).header("Range", "bytes=0-1023")
            .apply { token?.let { header("Authorization", "Bearer $it") } }.get().build()
        return verificationClient.newCall(request).execute().use { response ->
            when {
                response.request.url.scheme != "https" -> throw ModelLibraryException(ModelLibraryErrorCode.HTTPS_REQUIRED, "Source redirected away from HTTPS")
                response.code == 401 -> throw ModelLibraryException(ModelLibraryErrorCode.AUTHENTICATION_REQUIRED, "Source requires authentication")
                response.code == 403 -> throw ModelLibraryException(ModelLibraryErrorCode.AUTHENTICATION_REJECTED, "Source rejected authentication")
                response.code == 404 -> throw ModelLibraryException(ModelLibraryErrorCode.SOURCE_NOT_FOUND, "Source file was not found")
                !response.isSuccessful -> throw ModelLibraryException(ModelLibraryErrorCode.HTTP_FAILURE,
                    "Source request failed", arguments = listOf(response.code.toString()))
            }
            val mediaType = response.body?.contentType()?.toString()
            val prefix = response.peekBody(1024).string().trimStart().lowercase(Locale.US)
            if (mediaType?.contains("text/html", true) == true || mediaType?.contains("application/xhtml", true) == true ||
                prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) {
                throw ModelLibraryException(ModelLibraryErrorCode.WEBPAGE_LINK, "This link opens a webpage instead of a file")
            }
            val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
            val checksum = (response.header("X-Linked-Etag") ?: response.header("ETag"))?.trim('"')
                ?.lowercase(Locale.US)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val revision = response.header("X-Repo-Commit")?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
            source.copy(expectedSizeBytes = source.expectedSizeBytes ?: size,
                expectedSha256 = source.expectedSha256 ?: checksum, mediaType = mediaType,
                revision = revision ?: source.revision)
        }
    }

    private suspend fun awaitDownloadCompletion(
        context: Context,
        taskId: String,
        pendingArtifactId: String,
        stagingPath: File,
        timeoutMillis: Long
    ) {
        val taskDao = AppDatabase.getDatabase(context).downloadTaskDao()
        val startedAt = System.currentTimeMillis()
        val boundedTimeout = timeoutMillis.coerceIn(MIN_QUEUE_TIMEOUT_MILLIS, MAX_QUEUE_TIMEOUT_MILLIS)
        while (System.currentTimeMillis() - startedAt < boundedTimeout) {
            currentCoroutineContext().ensureActive()
            val progress = DownloadProgressHolder.progress.value[taskId]
            if (progress != null && progress >= 1f && stagingPath.isFile) return
            if (progress != null && progress < 0f && progress != DownloadProgressHolder.INDETERMINATE) {
                throw ModelLibraryException(ModelLibraryErrorCode.DOWNLOAD_FAILED, "Bundle item download failed")
            }
            val task = taskDao.getById(taskId)
            if (task != null && task.status in setOf("FAILED", "RESUMABLE", "CANCELLED", "DISCARDED")) {
                throw ModelLibraryException(
                    ModelLibraryErrorCode.DOWNLOAD_FAILED,
                    task.lastError?.take(256) ?: "Bundle item download failed"
                )
            }
            val pending = libraryDao.getPendingArtifactById(pendingArtifactId)
            if (pending?.status == PendingArtifactStatus.CANCELLED.storedValue) {
                throw CancellationException("Bundle item download was cancelled")
            }
            if (pending?.status == PendingArtifactStatus.FAILED.storedValue) {
                throw ModelLibraryException(
                    ModelLibraryErrorCode.DOWNLOAD_FAILED,
                    pending.validationMessage?.take(256) ?: "Bundle item download failed"
                )
            }
            if (task == null && pending?.status == PendingArtifactStatus.STAGED.storedValue && stagingPath.isFile) {
                return
            }
            delay(250)
        }
        throw ModelLibraryException(
            ModelLibraryErrorCode.DOWNLOAD_TIMEOUT,
            "Timed out waiting for bundle item download"
        )
    }

    private fun stagingPathFor(
        localRoot: File,
        bundleId: String,
        item: ModelBundleItemEntity,
        filename: String
    ): File {
        val safeBundle = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeItem = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeFilename = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "artifact" }
        return File(localRoot, ".model-library-staging/$safeBundle/$safeItem/$safeFilename").canonicalFile
    }

    private fun queueRootFromStaging(path: String): File? {
        var current: File? = runCatching { File(path).canonicalFile }.getOrNull()?.parentFile
        while (current != null) {
            if (current.name == ".model-library-staging") return current.parentFile
            current = current.parentFile
        }
        return null
    }

    private fun runtimeTypeFor(family: ModelFamily, role: String?): ModelType =
        Companion.runtimeModelTypeFor(family, role)

    companion object {
        const val DEFAULT_QUEUE_TIMEOUT_MILLIS = 30L * 60L * 1000L
        private const val PENDING_ARTIFACT_DISCARD_TIMEOUT_MILLIS = 20_000L
        private const val PENDING_ARTIFACT_DISCARD_POLL_MILLIS = 50L
        private const val MIN_QUEUE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_QUEUE_TIMEOUT_MILLIS = 24L * 60L * 60L * 1000L

        /** Maps explicit bundle roles to existing runtime types without inventing an UNKNOWN enum. */
        fun runtimeModelTypeFor(family: ModelFamily, role: String?): ModelType = when (family) {
            ModelFamily.LLM -> when (normalizedModelLibraryRole(role)) {
                "lora", "adapter" -> ModelType.LORA
                "embedding", "embeddings" -> ModelType.EMBEDDING
                "draft", "llm_draft" -> ModelType.LLM_DRAFT
                "llm_vision", "llmvision", "vision_projector" -> ModelType.VISION_PROJECTOR
                "clip_vision", "clipvision", "mmproj" -> ModelType.MMPROJ
                else -> ModelType.LLM
            }
            ModelFamily.SD -> when (normalizedModelLibraryRole(role)) {
                // Explicit LLM companions retain the LLM runtime row even
                // when their source is attached to an SD bundle.
                "llm", "text_encoder", "textencoder", "tokenizer",
                "shared_tokenizer", "shared_text_encoder" -> ModelType.LLM
                "vae" -> ModelType.SD_VAE
                "tae" -> ModelType.SD_TAE
                "clip_l" -> ModelType.SD_CLIP_L
                "clip_g" -> ModelType.SD_CLIP_G
                "t5xxl" -> ModelType.SD_T5XXL
                "lora" -> ModelType.SD_LORA
                "controlnet" -> ModelType.SD_CONTROLNET
                "clip_vision", "clipvision", "vision_encoder" -> ModelType.SD_CLIP_VISION
                "ip_adapter", "ipadapter" -> ModelType.SD_IP_ADAPTER
                "photomaker" -> ModelType.SD_PHOTOMAKER
                "textual_inversion", "textualinversion", "embedding" -> ModelType.SD_TEXTUAL_INVERSION
                "upscaler", "esrgan" -> ModelType.SD_UPSCALER
                "adetailer" -> ModelType.SD_ADETAILER
                "standalone_diffusion", "diffusion" -> ModelType.SD_DIFFUSION
                "audio_vae", "audiovae" -> ModelType.SD_AUDIO_VAE
                "embeddings_connectors", "embeddingsconnector", "connectors" -> ModelType.SD_EMBEDDINGS_CONNECTORS
                "motion_module", "motionmodule" -> ModelType.SD_MOTION_MODULE
                // There is no separate persisted high-noise enum; retain the
                // explicit role in provenance while using the diffusion row.
                "high_noise", "highnoise", "high_noise_diffusion", "high_noise_diffusion_model" -> ModelType.SD_DIFFUSION
                "llm_vision", "llmvision", "vision_projector" -> ModelType.VISION_PROJECTOR
                "mmproj" -> ModelType.MMPROJ
                else -> ModelType.SD_CHECKPOINT
            }
            ModelFamily.ONNX -> when (normalizedModelLibraryRole(role)) {
                "tts", "onnx_tts", "speech" -> ModelType.ONNX_TTS
                "background_removal", "backgroundremoval", "rembg", "matting" -> ModelType.ONNX_BACKGROUND_REMOVAL
                "upscaler", "image_upscaler", "imageupscaler" -> ModelType.ONNX_IMAGE_UPSCALER
                else -> ModelType.ONNX_IMAGE_GEN
            }
            ModelFamily.LITERT -> ModelType.LLM
            ModelFamily.WHISPER -> ModelType.WHISPER
        }
    }
}

private fun nextStateTimestamp(previous: Long, now: Long = System.currentTimeMillis()): Long =
    maxOf(now, previous + 1L)

/** Keeps authenticated HF folder selections nested while rejecting traversal. */
private fun safeRelativeDownloadPath(raw: String?, fallback: String): String = raw
    .orEmpty()
    .trim()
    .replace('\\', '/')
    .split('/')
    .asSequence()
    .filter { it.isNotBlank() && it != "." && it != ".." }
    .map { segment ->
        segment
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
            .trim()
            .ifBlank { "_" }
    }
    .joinToString("/")
    .ifBlank { fallback }

/** Keep a multi-file HF selection in one collision-resistant directory. */
private fun safeStorageGroup(raw: String): String = raw
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .trim('_')
    .take(128)
    .ifBlank { "selection-${UUID.randomUUID()}" }

fun ModelSourceEntity.resolvedDownloadUrl(): String? {
    return when (ModelSourceKind.fromStoredValue(kind)) {
        ModelSourceKind.HUGGING_FACE_FILE -> {
            val repo = repositoryId ?: return null
            val path = filePath?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val encodedPath = path.split('/').joinToString("/") {
                java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
            }
            val encodedRevision = java.net.URLEncoder.encode(revision.ifBlank { "main" }, Charsets.UTF_8.name())
                .replace("+", "%20")
            "https://huggingface.co/$repo/resolve/$encodedRevision/$encodedPath"
        }
        ModelSourceKind.HTTPS -> url
        ModelSourceKind.HUGGING_FACE_REPOSITORY, null -> null
    }
}
