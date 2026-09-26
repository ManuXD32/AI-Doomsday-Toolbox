package com.example.llamadroid.data.model

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelDao
import com.example.llamadroid.data.db.ModelBackupPolicy
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.model.library.ModelClassificationSource
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val recoveryMutex = Mutex()
private const val GGUF_HEADER_BYTES = 24
private const val MAX_GGUF_TENSORS = 2_000_000L
private const val MAX_GGUF_METADATA = 1_000_000L

/** Restores completed downloads without moving bytes or loading model tensors. */
internal suspend fun recoverManagedGgufModels(context: Context, modelDao: ModelDao, directory: File) =
    withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
            val root = directory.canonicalFile
            if (!root.isDirectory) return@withLock

            val database = AppDatabase.getDatabase(context)
            val rows = modelDao.getAllModels().first()
            val knownPaths = rows.mapNotNull { canonicalPathOrNull(it.path) }.toSet()
            val knownFilenames = rows.map { it.filename }.toSet()
            val tasks = database.downloadTaskDao().observeAll().first()
            val activePaths = tasks
                .filter { it.status == DOWNLOAD_TASK_STATUS_ACTIVE || it.status == DOWNLOAD_TASK_STATUS_RESUMABLE }
                .flatMap { task ->
                    listOf(task.destPath, downloadPartFile(task.destPath).absolutePath)
                }
                .mapNotNull(::canonicalPathOrNull)
                .toSet()
            val pendingPaths = PendingDownloadHolder.allPending()
                .flatMap { pending -> listOf(pending.destPath, downloadPartFile(pending.destPath).absolutePath) }
                .mapNotNull(::canonicalPathOrNull)
                .toSet()

            root.listFiles().orEmpty()
                .asSequence()
                .filter { it.name.endsWith(".gguf", ignoreCase = true) }
                .filter { file ->
                    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@filter false
                    canonical.parentFile == root &&
                        isRecoverableGgufFile(canonical)
                }
                .filter { file ->
                    val canonicalPath = canonicalPathOrNull(file.path) ?: return@filter false
                    canonicalPath !in knownPaths &&
                        file.name !in knownFilenames &&
                        canonicalPath !in activePaths &&
                        canonicalPath !in pendingPaths
                }
                .forEach { file ->
                    val canonicalFile = file.canonicalFile
                    val task = tasks
                        .firstOrNull { task -> canonicalPathOrNull(task.destPath) == canonicalFile.path }
                    val taskType = task?.modelType
                        ?.let { value -> runCatching { ModelType.valueOf(value) }.getOrNull() }
                        ?.takeIf { type ->
                            ModelLibraryManager.relativeDirFor(type) == ModelLibraryManager.relativeDirFor(ModelType.LLM)
                        }
                    val type = taskType ?: ModelType.LLM
                    if (modelDao.getModelByPath(canonicalFile.path) != null ||
                        modelDao.getModelByFilename(canonicalFile.name) != null
                    ) return@forEach

                    val recovered = ModelEntity(
                        filename = canonicalFile.name,
                        path = canonicalFile.path,
                        sizeBytes = canonicalFile.length(),
                        type = type,
                        repoId = task?.repoId?.trim().orEmpty().ifBlank {
                            ModelBackupPolicy.LOCAL_IMPORT_REPO_ID
                        },
                        isDownloaded = true,
                        isVision = task?.isVision == true,
                        sdCapabilities = task?.sdCapabilities,
                        sdFamily = task?.sdFamily,
                        sdVariant = task?.sdVariant,
                        sdCompatProfiles = task?.sdCompatProfiles,
                        onnxCapabilities = task?.onnxCapabilities,
                        onnxAssetKind = task?.onnxAssetKind,
                        onnxPipelineFamily = task?.onnxPipelineFamily,
                        onnxReferenceUri = task?.onnxReferenceUri,
                        onnxReferencePath = task?.onnxReferencePath,
                        classificationSource = task?.classificationSource?.takeIf { it.isNotBlank() }
                            ?: ModelClassificationSource.AUTO.storedValue,
                        detectedClassificationJson = task?.detectedClassificationJson
                    )
                    modelDao.insertModel(recovered)
                    DebugLog.log(
                        "ModelRepository: Recovered completed GGUF ${canonicalFile.name} into the model catalog"
                    )
                }
        }
    }

private fun canonicalPathOrNull(path: String): String? =
    runCatching { File(path).canonicalPath }.getOrNull()

internal fun hasValidGgufHeader(file: File): Boolean = runCatching {
    val header = ByteArray(GGUF_HEADER_BYTES)
    val complete = file.inputStream().use { input ->
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read <= 0) return@use false
            offset += read
        }
        true
    }
    complete &&
        header[0] == 'G'.code.toByte() &&
        header[1] == 'G'.code.toByte() &&
        header[2] == 'U'.code.toByte() &&
        header[3] == 'F'.code.toByte() &&
        readLittleEndianUInt32(header, 4) in 2L..3L &&
        readLittleEndianUInt64(header, 8) in 1L..MAX_GGUF_TENSORS &&
        readLittleEndianUInt64(header, 16) in 0L..MAX_GGUF_METADATA
}.getOrDefault(false)

private fun readLittleEndianUInt32(bytes: ByteArray, offset: Int): Long =
    (0 until 4).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
    }

private fun readLittleEndianUInt64(bytes: ByteArray, offset: Int): Long =
    (0 until 8).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
    }

internal fun isRecoverableGgufFile(file: File): Boolean =
    file.name.endsWith(".gguf", ignoreCase = true) &&
        file.isFile && file.canRead() && file.length() > GGUF_HEADER_BYTES &&
        !downloadPartFile(file.path).exists() && hasValidGgufHeader(file)

/** Exact source identity; an unknown local file never shadows a remote model. */
internal fun matchesCatalogModel(model: ModelEntity, repoId: String, filename: String, type: ModelType): Boolean =
    model.type == type && model.isDownloaded && File(model.path).isFile &&
        (model.repoId == "https://huggingface.co/$repoId/resolve/main/$filename" ||
            (model.repoId == repoId && model.filename == ModelLibraryManager.canonicalFilename(filename)))

/** Accept a request once, then finish only its durable service handoff despite picker disposal. */
internal suspend fun <T> handoffModelDownload(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) { block() }
}
