package com.example.llamadroid.audio.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.llamadroid.audio.AudioGenerationJobEntity
import com.example.llamadroid.audio.AudioHistoryItem
import com.example.llamadroid.audio.AudioJobStatuses
import com.example.llamadroid.audio.AudioWorkspaceStorage
import com.example.llamadroid.audio.AudioFileInspector
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.library.modelDeletionOperationMutex
import com.example.llamadroid.onnx.OnnxTtsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class AudioLibraryQuery(
    val query: String = "",
    val folderId: String? = null,
    val kind: String? = null,
    val sort: AudioLibrarySort = AudioLibrarySort.NEWEST,
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    val normalizedPage: Int get() = page.coerceAtLeast(0)
    val normalizedPageSize: Int get() = pageSize.coerceIn(1, MAX_PAGE_SIZE)

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * Database-backed library for generated audio. It deliberately owns logical organization and
 * local output deletion while leaving shared/exported copies untouched.
 */
class AudioLibraryRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val appContext = context.applicationContext
    private val dao = database.audioLibraryDao()
    private val audioDao = database.audioDao()

    fun observePage(request: AudioLibraryQuery): Flow<AudioLibraryPage> = flow {
        ensureLegacyMigrated()
        val query = request.query.trim()
        val folderId = request.folderId
        val kind = request.kind?.trim()?.takeIf { it.isNotEmpty() }
        val sort = request.sort.name
        val page = request.normalizedPage
        val pageSize = request.normalizedPageSize
        emitAll(
            combine(
                dao.observeItemsPage(query, folderId, kind, sort, pageSize, page * pageSize),
                dao.observeItemCount(query, folderId, kind)
            ) { items, count ->
                AudioLibraryPage(
                    items = items.map(AudioLibraryItemEntity::toModel),
                    page = page,
                    pageSize = pageSize,
                    totalCount = count
                )
            }
        )
    }.flowOn(Dispatchers.IO)

    fun observePreferences(): Flow<AudioLibraryPreferences> = flow {
        ensureLegacyMigrated()
        emitAll(dao.observePreferences().map { it?.toModel() ?: AudioLibraryPreferences() })
    }.flowOn(Dispatchers.IO)

    fun observeFolders(): Flow<List<AudioLibraryFolder>> = flow {
        ensureLegacyMigrated()
        emitAll(dao.observeFolders().map { folders -> folders.map(AudioLibraryFolderEntity::toModel) })
    }.flowOn(Dispatchers.IO)

    suspend fun getPreferences(): AudioLibraryPreferences = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.getPreferences()?.toModel() ?: AudioLibraryPreferences()
    }

    suspend fun savePreferences(preferences: AudioLibraryPreferences) = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.upsertPreferences(
            AudioLibraryPreferencesEntity(
                query = preferences.query.trim(),
                sort = preferences.sort.name,
                folderId = preferences.folderId,
                kind = preferences.kind?.trim()?.takeIf { it.isNotEmpty() },
                legacyMigrationVersion = CURRENT_MIGRATION_VERSION,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setSearchAndSort(query: String, sort: AudioLibrarySort) {
        val current = getPreferences()
        setSearchAndSort(query, sort, current.folderId, current.kind)
    }

    suspend fun setSearchAndSort(
        query: String,
        sort: AudioLibrarySort,
        folderId: String?,
        kind: String?
    ) {
        savePreferences(AudioLibraryPreferences(query.trim(), sort, folderId, kind))
    }

    suspend fun listAllItems(): List<AudioLibraryItem> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.observeAllItems().first().map(AudioLibraryItemEntity::toModel)
    }

    suspend fun listMatchingIds(query: AudioLibraryQuery): List<String> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.matchingItemIds(query.query.trim(), query.folderId, query.kind?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Reconciles a completed job without changing a user-renamed title or selected folder. */
    suspend fun upsertJob(job: AudioGenerationJobEntity): AudioLibraryItem? = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val path = job.outputPath ?: job.wavPath ?: return@withContext null
        val file = File(path)
        // A completion callback can race the final file move. Do not index a transient or
        // missing path; a later successful reconciliation can add the row once it is real.
        if (!file.isFile || file.length() <= 0L) return@withContext null
        val originKey = "job:${job.id}"
        val existing = dao.getItemsByOriginKeys(listOf(originKey)).firstOrNull()
        val voiceName = job.voiceProfileId?.let { audioDao.getVoiceProfile(it)?.name }
        val kind = kindFor(job)
        val titlePreferences = appContext.getSharedPreferences("audio_history_titles", Context.MODE_PRIVATE)
        val candidate = AudioLibraryItemEntity(
            id = existing?.id ?: originKey,
            originKey = originKey,
            title = existing?.title
                ?: titlePreferences.getString(job.id, null)
                ?: job.sourceName?.takeIf { it.isNotBlank() }
                ?: job.modelDisplayName,
            audioPath = file.absolutePath,
            metadataPath = job.metadataPath,
            mimeType = mimeTypeFor(file),
            durationMs = job.durationMs,
            sizeBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            createdAt = existing?.createdAt ?: (job.completedAt ?: job.createdAt),
            updatedAt = System.currentTimeMillis(),
            folderId = existing?.folderId,
            source = "audio_workspace",
            status = job.status,
            modelName = job.modelDisplayName,
            voiceName = voiceName ?: existing?.voiceName,
            language = job.language ?: job.modelLanguage ?: "en",
            kind = kind
        )
        if (existing == null) dao.insertItem(candidate) else dao.updateItem(candidate)
        candidate.toModel()
    }

    /** Index chat/document speech immediately, without rescanning the whole library. */
    suspend fun indexLegacyOutput(file: File) = withContext(Dispatchers.IO) {
        if (file.canonicalFile.parentFile != OnnxTtsStorage.outputDir(appContext).canonicalFile ||
            !file.isFile || file.length() == 0L) return@withContext
        ensureLegacyMigrated()
        val metadata = OnnxTtsStorage.readMetadata(file)
        val origin = "legacy:${file.absolutePath}"
        val duration = metadata?.let { (it.durationSeconds * 1000f).toLong() }
            ?: runCatching { AudioFileInspector.inspect(file).durationMs }.getOrDefault(0L)
        database.withTransaction {
            val existing = dao.getItemsByOriginKeys(listOf(origin)).firstOrNull()
            val row = AudioLibraryItemEntity(
                id = existing?.id ?: origin, originKey = origin,
                title = existing?.title ?: metadata?.sourceName ?: file.nameWithoutExtension,
                audioPath = file.absolutePath,
                metadataPath = OnnxTtsStorage.metadataFileFor(file).absolutePath,
                mimeType = mimeTypeFor(file), durationMs = duration, sizeBytes = file.length(),
                createdAt = existing?.createdAt ?: file.lastModified(), updatedAt = System.currentTimeMillis(),
                folderId = existing?.folderId, source = "legacy_onnx_tts", status = AudioJobStatuses.COMPLETE,
                modelName = metadata?.modelName ?: "Supertonic", voiceName = metadata?.voiceName,
                language = metadata?.language ?: "en", kind = "speech"
            )
            if (existing == null) dao.insertItem(row) else dao.updateItem(row)
        }
    }

    /** Reconcile all terminal jobs and legacy Supertonic files. Safe to call repeatedly. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        ensureLegacyMigrated(force = true)
        audioDao.observeHistory().first().forEach { upsertJob(it) }
    }

    fun observeHistory(): Flow<List<AudioHistoryItem>> = flow {
        ensureLegacyMigrated()
        emitAll(dao.observeAllItems().map { items -> items.map { item -> item.toHistoryItem() } })
    }.flowOn(Dispatchers.IO)

    suspend fun listHistory(): List<AudioHistoryItem> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.observeAllItems().first().map { item -> item.toHistoryItem() }
            .sortedByDescending { it.createdAt }
    }

    suspend fun renameItem(id: String, title: String): AudioLibraryBatchResult = withContext(Dispatchers.IO) {
        renameItems(listOf(id), title)
    }

    suspend fun previewRename(ids: Collection<String>, title: String): List<AudioLibraryRenamePreview> =
        withContext(Dispatchers.IO) {
            val clean = title.trim()
            if (clean.isBlank()) return@withContext emptyList()
            val uniqueIds = ids.distinct()
            val rows = getItemsChunked(uniqueIds).associateBy { it.id }
            uniqueIds.mapIndexedNotNull { index, id ->
                rows[id]?.let { row ->
                    AudioLibraryRenamePreview(
                        itemId = id,
                        currentTitle = row.title,
                        nextTitle = if (uniqueIds.size == 1) clean else "$clean ${index + 1}"
                    )
                }
            }
        }

    /** Applies a deterministic numbered suffix for a batch rename, preserving stable IDs. */
    suspend fun renameItems(ids: Collection<String>, title: String): AudioLibraryBatchResult = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val clean = title.trim()
        if (clean.isBlank()) {
            return@withContext AudioLibraryBatchResult(
                failures = ids.distinct().map { AudioLibraryFailure(it, AudioLibraryFailureCode.INVALID_NAME) }
            )
        }
        val uniqueIds = ids.distinct()
        val rows = try {
            getItemsChunked(uniqueIds).associateBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext AudioLibraryBatchResult(
                failures = uniqueIds.map { AudioLibraryFailure(it, AudioLibraryFailureCode.IO, error.javaClass.simpleName) }
            )
        }
        val successes = mutableListOf<String>()
        val failures = mutableListOf<AudioLibraryFailure>()
        uniqueIds.forEachIndexed { index, id ->
            if (id !in rows) {
                failures += AudioLibraryFailure(id, AudioLibraryFailureCode.NOT_FOUND)
            } else {
                val nextTitle = if (uniqueIds.size == 1) clean else "$clean ${index + 1}"
                try {
                    if (dao.renameItem(id, nextTitle) > 0) successes += id
                    else failures += AudioLibraryFailure(id, AudioLibraryFailureCode.IO)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += AudioLibraryFailure(id, AudioLibraryFailureCode.IO, error.javaClass.simpleName)
                }
            }
        }
        AudioLibraryBatchResult(successes, failures)
    }

    suspend fun moveItems(ids: Collection<String>, folderId: String?): AudioLibraryBatchResult = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val uniqueIds = ids.distinct()
        try {
            database.withTransaction {
                if (folderId != null && dao.getFolder(folderId) == null) {
                    return@withTransaction AudioLibraryBatchResult(
                        failures = uniqueIds.map { AudioLibraryFailure(it, AudioLibraryFailureCode.INVALID_FOLDER) }
                    )
                }
                val rows = getItemsChunked(uniqueIds).map { it.id }.toSet()
                val successes = uniqueIds.filter { it in rows }
                val failures = uniqueIds.filterNot { it in rows }
                    .map { AudioLibraryFailure(it, AudioLibraryFailureCode.NOT_FOUND) }
                    .toMutableList()
                val moved = mutableListOf<String>()
                successes.chunked(SQL_BATCH_SIZE).forEach { chunk ->
                    val changed = dao.moveItems(chunk, folderId)
                    check(changed == chunk.size) { "Item move did not update every row" }
                    moved += chunk
                }
                AudioLibraryBatchResult(moved, failures)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AudioLibraryBatchResult(
                failures = uniqueIds.map { AudioLibraryFailure(it, AudioLibraryFailureCode.IO, error.javaClass.simpleName) }
            )
        }
    }

    suspend fun deleteItem(id: String): AudioLibraryBatchResult = deleteItems(listOf(id))

    /** Deletes only app-owned local output and then its row; exported copies are never touched. */
    suspend fun deleteItems(ids: Collection<String>): AudioLibraryBatchResult = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val uniqueIds = ids.distinct()
        try {
            modelDeletionOperationMutex.withLock {
                database.withTransaction { deleteItemsInTransaction(uniqueIds) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AudioLibraryBatchResult(
                failures = uniqueIds.map { AudioLibraryFailure(it, AudioLibraryFailureCode.IO, error.javaClass.simpleName) }
            )
        }
    }

    /**
     * Deletes rows while the shared model/input deletion lock and one Room transaction are held.
     * File removal cannot roll back with SQLite, so each filesystem failure is returned as a
     * typed per-item result and its row remains available for retry.
     */
    private suspend fun deleteItemsInTransaction(ids: Collection<String>): AudioLibraryBatchResult {
        val uniqueIds = ids.distinct()
        val rows = getItemsChunked(uniqueIds).associateBy { it.id }
        val successes = mutableListOf<String>()
        val failures = mutableListOf<AudioLibraryFailure>()
        uniqueIds.forEach { id ->
            val row = rows[id]
            if (row == null) {
                failures += AudioLibraryFailure(id, AudioLibraryFailureCode.NOT_FOUND)
                return@forEach
            }
            if (isReferencedByActiveJob(row)) {
                failures += AudioLibraryFailure(id, AudioLibraryFailureCode.ACTIVE_JOB)
                return@forEach
            }
            val failure = deleteLocalOutput(row)
            if (failure != null) {
                failures += failure
            } else if (dao.deleteItem(id) > 0) {
                successes += id
            } else {
                failures += AudioLibraryFailure(id, AudioLibraryFailureCode.IO)
            }
        }
        return AudioLibraryBatchResult(successes, failures)
    }

    suspend fun exportItem(id: String, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureLegacyMigrated()
            val row = dao.getItem(id) ?: throw AudioLibraryOperationException(AudioLibraryFailureCode.NOT_FOUND, "Audio item not found")
            val source = File(row.audioPath)
            require(source.isFile) { "Audio output is missing" }
            appContext.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalStateException("Could not open export destination")
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun createFolder(name: String, parentId: String? = null): AudioLibraryFolder = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val clean = validateFolderName(name)
        database.withTransaction {
            if (parentId != null && dao.getFolder(parentId) == null) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.INVALID_FOLDER, "Parent folder does not exist")
            }
            if (dao.findFolderByName(parentId, clean) != null) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.CONFLICT, "A folder with this name already exists")
            }
            val now = System.currentTimeMillis()
            val folder = AudioLibraryFolderEntity(UUID.randomUUID().toString(), clean, parentId, now, now)
            dao.insertFolder(folder)
            folder.toModel()
        }
    }

    suspend fun renameFolder(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        val clean = validateFolderName(name)
        database.withTransaction {
            val existing = dao.getFolder(id) ?: return@withTransaction false
            if (dao.findFolderByName(existing.parentId, clean)?.id?.let { it != id } == true) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.CONFLICT, "A folder with this name already exists")
            }
            dao.updateFolder(existing.copy(name = clean, updatedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun moveFolder(id: String, parentId: String?): Boolean = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        database.withTransaction {
            val existing = dao.getFolder(id) ?: return@withTransaction false
            validateFolderParent(id, parentId)
            if (dao.findFolderByName(parentId, existing.name)?.id?.let { it != id } == true) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.CONFLICT, "A folder with this name already exists")
            }
            dao.updateFolder(existing.copy(parentId = parentId, updatedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun deleteFolder(id: String): Boolean = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        database.withTransaction {
            if (dao.getFolder(id) == null) return@withTransaction false
            if (dao.countChildFolders(id) > 0 || dao.countItemsInFolder(id) > 0) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.FOLDER_NOT_EMPTY, "Folder is not empty")
            }
            dao.deleteFolder(id) > 0
        }
    }

    /**
     * Removes a folder using an explicit policy for its contents. Moving keeps nested folders
     * intact under the deleted folder's parent. Deleting removes only app-owned local outputs;
     * exported copies are never touched.
     */
    suspend fun deleteFolderContents(
        id: String,
        mode: AudioLibraryFolderDeleteMode
    ): AudioLibraryBatchResult = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        when (mode) {
            AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT -> {
                try {
                    database.withTransaction {
                        val folder = dao.getFolder(id)
                            ?: return@withTransaction AudioLibraryBatchResult(
                                failures = listOf(AudioLibraryFailure(id, AudioLibraryFailureCode.NOT_FOUND))
                            )
                        val folders = dao.getFolders()
                        val children = folders.filter { it.parentId == id }
                        val siblingNames = folders
                            .filter { it.parentId == folder.parentId && it.id != id }
                            .map { it.name.lowercase(Locale.ROOT) }
                            .toSet()
                        if (children.any { it.name.lowercase(Locale.ROOT) in siblingNames }) {
                            return@withTransaction AudioLibraryBatchResult(
                                failures = listOf(
                                    AudioLibraryFailure(
                                        id,
                                        AudioLibraryFailureCode.CONFLICT,
                                        "Nested folder name conflicts with its destination"
                                    )
                                )
                            )
                        }
                        val directItems = getItemsInFoldersChunked(setOf(id))
                        val moved = directItems.map { it.id }.distinct().toMutableList()
                        moved.chunked(SQL_BATCH_SIZE).forEach { chunk ->
                            check(dao.moveItems(chunk, folder.parentId) == chunk.size) {
                                "Folder item move did not update every row"
                            }
                        }
                        children.forEach { child ->
                            dao.updateFolder(child.copy(parentId = folder.parentId, updatedAt = System.currentTimeMillis()))
                        }
                        check(dao.deleteFolder(id) > 0) { "Folder could not be removed" }
                        AudioLibraryBatchResult(succeededIds = moved)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: AudioLibraryOperationException) {
                    AudioLibraryBatchResult(
                        failures = listOf(AudioLibraryFailure(id, error.code, error.message))
                    )
                } catch (error: Throwable) {
                    AudioLibraryBatchResult(
                        failures = listOf(AudioLibraryFailure(id, AudioLibraryFailureCode.IO, error.javaClass.simpleName))
                    )
                }
            }

            AudioLibraryFolderDeleteMode.DELETE_CONTAINED_AUDIO -> {
                try {
                    modelDeletionOperationMutex.withLock {
                        database.withTransaction {
                            dao.getFolder(id)
                                ?: return@withTransaction AudioLibraryBatchResult(
                                    failures = listOf(AudioLibraryFailure(id, AudioLibraryFailureCode.NOT_FOUND))
                                )
                            val folders = dao.getFolders()
                            val subtreeIds = folderSubtreeIds(id, folders)
                            val containedItems = getItemsInFoldersChunked(subtreeIds)
                            val deletedItems = deleteItemsInTransaction(containedItems.map { it.id })
                            if (deletedItems.failures.isNotEmpty()) return@withTransaction deletedItems
                            folders.filter { it.id in subtreeIds }
                                .sortedByDescending { folderDepth(it.id, folders) }
                                .forEach { descendant ->
                                    check(dao.deleteFolder(descendant.id) > 0) {
                                        "Folder could not be removed"
                                    }
                                }
                            deletedItems
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: AudioLibraryOperationException) {
                    AudioLibraryBatchResult(
                        failures = listOf(AudioLibraryFailure(id, error.code, error.message))
                    )
                } catch (error: Throwable) {
                    AudioLibraryBatchResult(
                        failures = listOf(AudioLibraryFailure(id, AudioLibraryFailureCode.IO, error.javaClass.simpleName))
                    )
                }
            }
        }
    }

    private suspend fun ensureLegacyMigrated(force: Boolean = false) {
        migrationMutex.withLock {
            val existingPreferences = dao.getPreferences()
            if (!force && existingPreferences?.legacyMigrationVersion ?: 0 >= CURRENT_MIGRATION_VERSION) return
            val rows = mutableListOf<AudioLibraryItemEntity>()
            val titlePreferences = appContext.getSharedPreferences("audio_history_titles", Context.MODE_PRIVATE)
            val jobs = audioDao.observeHistory().first()
            val knownPaths = jobs.mapNotNull { it.outputPath ?: it.wavPath }.toSet()
            jobs.forEach { job ->
                val path = job.outputPath ?: job.wavPath ?: return@forEach
                val file = File(path)
                val origin = "job:${job.id}"
                val existing = dao.getItemsByOriginKeys(listOf(origin)).firstOrNull()
                // Do not recreate a row for a job whose local output was intentionally removed.
                // An existing row remains as a missing-output history marker, while no stale row
                // is inserted during a forced reconciliation.
                if (!file.isFile || file.length() <= 0L) return@forEach
                val kind = kindFor(job)
                rows += AudioLibraryItemEntity(
                    id = existing?.id ?: origin,
                    originKey = origin,
                    title = existing?.title ?: titlePreferences.getString(origin, null)
                        ?: titlePreferences.getString(job.id, null)
                        ?: job.sourceName?.takeIf { it.isNotBlank() } ?: job.modelDisplayName,
                    audioPath = file.absolutePath,
                    metadataPath = job.metadataPath,
                    mimeType = mimeTypeFor(file),
                    durationMs = job.durationMs,
                    sizeBytes = file.takeIf(File::isFile)?.length() ?: 0L,
                    createdAt = existing?.createdAt ?: (job.completedAt ?: job.createdAt),
                    updatedAt = System.currentTimeMillis(),
                    folderId = existing?.folderId,
                    source = "audio_workspace",
                    status = job.status,
                    modelName = job.modelDisplayName,
                    voiceName = job.voiceProfileId?.let { audioDao.getVoiceProfile(it)?.name } ?: existing?.voiceName,
                    language = job.language ?: job.modelLanguage ?: "en",
                    kind = kind
                )
            }
            OnnxTtsStorage.listGeneratedAudio(appContext).forEach { file ->
                if (file.absolutePath in knownPaths) return@forEach
                val origin = "legacy:${file.absolutePath}"
                val existing = dao.getItemsByOriginKeys(listOf(origin)).firstOrNull()
                val metadata = OnnxTtsStorage.readMetadata(file)
                val duration = metadata?.let { (it.durationSeconds * 1000f).toLong() }
                    ?: runCatching { AudioFileInspector.inspect(file).durationMs }.getOrDefault(0L)
                rows += AudioLibraryItemEntity(
                    id = existing?.id ?: origin,
                    originKey = origin,
                    title = existing?.title ?: titlePreferences.getString(origin, null)
                        ?: titlePreferences.getString(file.absolutePath, null)
                        ?: metadata?.sourceName ?: file.nameWithoutExtension,
                    audioPath = file.absolutePath,
                    metadataPath = OnnxTtsStorage.metadataFileFor(file).absolutePath,
                    mimeType = mimeTypeFor(file),
                    durationMs = duration,
                    sizeBytes = file.length(),
                    createdAt = existing?.createdAt ?: file.lastModified(),
                    updatedAt = System.currentTimeMillis(),
                    folderId = existing?.folderId,
                    source = "legacy_onnx_tts",
                    status = AudioJobStatuses.COMPLETE,
                    modelName = metadata?.modelName ?: "Supertonic",
                    voiceName = metadata?.voiceName,
                    language = metadata?.language ?: "en",
                    kind = "speech"
                )
            }
            rows.forEach { row ->
                val prior = dao.getItemsByOriginKeys(listOf(row.originKey)).firstOrNull()
                if (prior == null) {
                    dao.insertItem(row)
                } else {
                    // Refresh paths/status/metadata while retaining user-owned title and folder.
                    dao.updateItem(row.copy(title = prior.title, folderId = prior.folderId))
                }
            }
            dao.upsertPreferences(
                (existingPreferences ?: AudioLibraryPreferencesEntity()).copy(
                    legacyMigrationVersion = CURRENT_MIGRATION_VERSION,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun validateFolderParent(id: String, parentId: String?) {
        if (parentId == null) return
        if (id == parentId) {
            throw AudioLibraryOperationException(AudioLibraryFailureCode.CYCLE, "Folder cannot contain itself")
        }
        if (dao.getFolder(parentId) == null) {
            throw AudioLibraryOperationException(AudioLibraryFailureCode.INVALID_FOLDER, "Parent folder does not exist")
        }
        val visited = mutableSetOf<String>()
        var current: String? = parentId
        while (current != null) {
            if (!visited.add(current)) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.CYCLE, "Folder hierarchy contains a cycle")
            }
            if (current == id) {
                throw AudioLibraryOperationException(AudioLibraryFailureCode.CYCLE, "Folder cannot be moved into its descendant")
            }
            current = dao.getFolder(current)?.parentId
        }
    }

    private suspend fun getItemsChunked(ids: Collection<String>): List<AudioLibraryItemEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(SQL_BATCH_SIZE).flatMap { dao.getItems(it) }
    }

    private suspend fun getItemsInFoldersChunked(folderIds: Collection<String>): List<AudioLibraryItemEntity> {
        if (folderIds.isEmpty()) return emptyList()
        return folderIds.distinct().chunked(SQL_BATCH_SIZE).flatMap { dao.getItemsInFolders(it) }
    }

    private fun folderSubtreeIds(rootId: String, folders: List<AudioLibraryFolderEntity>): Set<String> {
        val childrenByParent = folders.groupBy { it.parentId }
        val result = linkedSetOf<String>()
        val pending = java.util.ArrayDeque<String>()
        pending.addLast(rootId)
        while (!pending.isEmpty()) {
            val current = pending.removeFirst()
            if (!result.add(current)) continue
            childrenByParent[current].orEmpty().forEach { pending.addLast(it.id) }
        }
        return result
    }

    private fun folderDepth(id: String, folders: List<AudioLibraryFolderEntity>): Int {
        val byId = folders.associateBy { it.id }
        val visited = mutableSetOf<String>()
        var depth = 0
        var current: String? = id
        while (current != null && visited.add(current)) {
            current = byId[current]?.parentId
            if (current != null) depth++
        }
        return depth
    }

    private fun validateFolderName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 80 || clean == "." || clean == "..") {
            throw AudioLibraryOperationException(AudioLibraryFailureCode.INVALID_NAME, "Folder name is invalid")
        }
        return clean
    }

    private fun deleteLocalOutput(row: AudioLibraryItemEntity): AudioLibraryFailure? {
        val file = File(row.audioPath)
        if (!file.exists()) return AudioLibraryFailure(row.id, AudioLibraryFailureCode.FILE_MISSING)
        return runCatching {
            val outputRoot = AudioWorkspaceStorage.outputRoot(appContext).canonicalFile
            val legacyRoot = OnnxTtsStorage.outputDir(appContext).canonicalFile
            val target = file.canonicalFile
            when {
                target.path == legacyRoot.path || target.parentFile?.path == legacyRoot.path -> {
                    val result = OnnxTtsStorage.deleteGeneratedAudioSet(legacyRoot, target)
                    if (result.failedFiles > 0 || result.skippedUnsafe) {
                        AudioLibraryFailure(row.id, AudioLibraryFailureCode.IO)
                    } else null
                }
                target.path.startsWith(outputRoot.path + File.separator) -> {
                    if (row.originKey.startsWith("job:")) {
                        AudioWorkspaceStorage.deleteJobOutput(appContext, row.originKey.removePrefix("job:"))
                    } else {
                        target.delete()
                        row.metadataPath?.let { path ->
                            val metadata = File(path).canonicalFile
                            if (metadata.path.startsWith(outputRoot.path + File.separator)) metadata.delete()
                        }
                    }
                    if (target.exists()) AudioLibraryFailure(row.id, AudioLibraryFailureCode.IO) else null
                }
                else -> AudioLibraryFailure(row.id, AudioLibraryFailureCode.UNSAFE_PATH)
            }
        }.getOrElse { AudioLibraryFailure(row.id, AudioLibraryFailureCode.IO, it.javaClass.simpleName) }
    }

    /**
     * A music Remix/Inpaint/Extend job may still be reading a library output while it is queued
     * or running. Keep that input row and its bytes until the job reaches a terminal state.
     * Reference voice paths are checked as well so a cloned voice cannot disappear mid-run.
     */
    private suspend fun isReferencedByActiveJob(row: AudioLibraryItemEntity): Boolean {
        val scope = deletionScope(row) ?: return false
        val activeStatuses = setOf(
            AudioJobStatuses.QUEUED,
            AudioJobStatuses.PREPARING,
            AudioJobStatuses.RUNNING,
            AudioJobStatuses.CANCELLING
        )
        return audioDao.getClaimedJobs().any { job ->
            if (job.status !in activeStatuses) return@any false
            val references = buildList {
                job.wavPath?.let(::add)
                job.outputPath?.let(::add)
                job.metadataPath?.let(::add)
                job.referenceAudioPath?.let(::add)
                runCatching {
                    JSONObject(job.metadataJson)
                        .optJSONObject("stableAudio")
                        ?.optString("initAudioPath")
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()?.let(::add)
            }
            references.any { candidate -> scope.contains(candidate) }
        }
    }

    /**
     * Describes every local path that a library delete may remove. Workspace job rows remove the
     * complete job directory (WAV, encoded output, and metadata); legacy rows remove the WAV/MP3
     * pair and both sidecars. Checking only the selected row's path would allow an active remix or
     * conditioning job to lose a sibling input while the row itself appeared unrelated.
     */
    private fun deletionScope(row: AudioLibraryItemEntity): DeletionScope? {
        val target = runCatching { File(row.audioPath).canonicalFile }.getOrNull() ?: return null
        val outputRoot = runCatching { AudioWorkspaceStorage.outputRoot(appContext).canonicalFile }.getOrNull()
        if (outputRoot != null && row.originKey.startsWith("job:") &&
            target.path.startsWith(outputRoot.path + File.separator) &&
            target.parentFile?.parentFile?.path == outputRoot.path
        ) {
            return DeletionScope.Directory(target.parentFile!!.path)
        }
        val legacyRoot = runCatching { OnnxTtsStorage.outputDir(appContext).canonicalFile }.getOrNull()
        if (legacyRoot != null && target.parentFile?.path == legacyRoot.path &&
            target.extension.lowercase(Locale.ROOT) in setOf("wav", "mp3")
        ) {
            val stem = target.nameWithoutExtension
            return DeletionScope.Exact(
                buildSet {
                    add(File(legacyRoot, "$stem.wav").canonicalPath)
                    add(File(legacyRoot, "$stem.mp3").canonicalPath)
                    add(File(legacyRoot, "$stem.wav.json").canonicalPath)
                    add(File(legacyRoot, "$stem.mp3.json").canonicalPath)
                }
            )
        }
        return DeletionScope.Exact(
            buildSet {
                add(target.path)
                row.metadataPath?.let { metadata ->
                    runCatching { add(File(metadata).canonicalPath) }
                }
            }
        )
    }

    private sealed interface DeletionScope {
        fun contains(candidate: String): Boolean

        data class Exact(private val paths: Set<String>) : DeletionScope {
            override fun contains(candidate: String): Boolean =
                runCatching { File(candidate).canonicalPath in paths }.getOrDefault(false)
        }

        data class Directory(private val path: String) : DeletionScope {
            override fun contains(candidate: String): Boolean = runCatching {
                val canonical = File(candidate).canonicalPath
                canonical == path || canonical.startsWith(path + File.separator)
            }.getOrDefault(false)
        }
    }

    private fun kindFor(job: AudioGenerationJobEntity): String = when (job.family) {
        "stable_audio_music" -> "music"
        "stable_audio_sfx" -> "sfx"
        else -> "speech"
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        else -> "audio/*"
    }

    private fun AudioLibraryPreferencesEntity.toModel() = AudioLibraryPreferences(
        query = query,
        sort = AudioLibrarySort.fromStored(sort),
        folderId = folderId,
        kind = kind
    )

    private fun AudioLibraryItemEntity.toHistoryItem() = AudioHistoryItem(
        id = id,
        title = title,
        modelName = modelName,
        voiceName = voiceName,
        language = language,
        durationMs = durationMs,
        createdAt = createdAt,
        audioPath = audioPath,
        metadataPath = metadataPath,
        status = status,
        source = source
    )

    companion object {
        private val migrationMutex = Mutex()
        const val CURRENT_MIGRATION_VERSION = 1
        private const val SQL_BATCH_SIZE = 400
    }
}
