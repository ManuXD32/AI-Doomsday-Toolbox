package com.example.llamadroid.training

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.db.DownloadTaskDao
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** One file to download into a dataset directory. */
data class DatasetDownloadFile(val path: String, val sizeBytes: Long)

/**
 * Owns the dataset rows and files under `getExternalFilesDir(null)/training/datasets/<id>/`.
 * Downloads go through [DownloadService] (resume, notifications, durable task rows); [startDownload]
 * is injectable for tests. For `dataset:` task ids the service forwards the app's Hugging Face token,
 * so gated datasets download once the user stored a token (checked in [addRemote]).
 */
class DatasetRepository(
    private val context: Context,
    private val dao: TrainingDatasetDao,
    private val taskDao: DownloadTaskDao,
    private val tokenProvider: () -> String = { HfToken.read(context) },
    private val startDownload: (url: String, destPath: String, filename: String, downloadId: String) -> Unit =
        { url, dest, name, id -> DownloadService.startDownload(context, url, dest, name, id) },
    private val cancelDownload: (filename: String, downloadId: String) -> Unit =
        { name, id -> DownloadService.cancelDownload(context, name, id) }
) {
    constructor(context: Context, db: AppDatabase = AppDatabase.getDatabase(context)) :
        this(context, db.trainingDatasetDao(), db.downloadTaskDao())

    fun observeAll(): Flow<List<TrainingDatasetEntity>> = dao.observeAll()

    suspend fun list(): List<TrainingDatasetEntity> = dao.getAll()

    suspend fun get(id: Long): TrainingDatasetEntity? = dao.getById(id)

    fun datasetRoot(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "training/datasets")

    fun datasetDir(id: Long): File = File(datasetRoot(), id.toString())

    /** Task id used with DownloadService/DownloadProgressHolder for file [index] of dataset [id]. */
    fun taskId(id: Long, index: Int): String = "dataset:$id:$index"

    /** Creates the row and starts downloading the curated files. */
    suspend fun addCurated(entry: CuratedDataset, capMb: Int = entry.defaultCapMb): Result<Long> =
        addRemote(
            name = entry.title, source = DatasetSource.CURATED, repo = entry.repo, config = entry.config,
            split = entry.split, revision = entry.revision, licence = entry.licence, format = entry.format,
            columnMap = entry.columnMap, files = entry.files.map { DatasetDownloadFile(it.path, it.sizeBytes) },
            gated = entry.gated, capMb = capMb
        )

    /** Creates the row for an arbitrary Hub dataset and starts downloading [files]. */
    suspend fun addHf(
        name: String, repo: String, revision: String, licence: String, gated: Boolean,
        format: DatasetFormat, columnMap: Map<String, String>, files: List<DatasetDownloadFile>,
        config: String? = null, split: String? = null
    ): Result<Long> = addRemote(
        name, DatasetSource.HF, repo, config, split, revision, licence, format, columnMap, files, gated, 0
    )

    private suspend fun addRemote(
        name: String, source: DatasetSource, repo: String, config: String?, split: String?, revision: String,
        licence: String, format: DatasetFormat, columnMap: Map<String, String>,
        files: List<DatasetDownloadFile>, gated: Boolean, capMb: Int
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(files.isNotEmpty()) { "No files selected" }
            if (gated && tokenProvider().isBlank()) throw IOException("Hugging Face token required for gated dataset")
            val total = files.sumOf { it.sizeBytes }
            val id = dao.insert(
                TrainingDatasetEntity(
                    name = name, source = source.value, hfRepo = repo, hfConfig = config, hfSplit = split,
                    revision = revision, licence = licence, format = format.value,
                    columnMapJson = DatasetHelpers.columnMapJson(columnMap), localPath = "",
                    sizeBytes = total, rowCount = DatasetHelpers.capToRows(
                        capMb, DatasetHelpers.fileKind(files.first().path), format
                    ) ?: DatasetHelpers.estimateRows(total, DatasetHelpers.fileKind(files.first().path), format),
                    status = DatasetStatus.DOWNLOADING.value, error = null, createdAt = System.currentTimeMillis()
                )
            )
            try {
                val dir = datasetDir(id).apply { mkdirs() }
                dao.updateLocalPath(id, dir.absolutePath)
                val localNames = files.map { DatasetHelpers.safeFileName(it.path.substringAfterLast('/')) }
                File(dir, MANIFEST).writeText(
                    buildJsonObject {
                        put("files", buildJsonArray {
                            files.forEachIndexed { i, f ->
                                add(buildJsonObject { put("name", localNames[i]); put("size", f.sizeBytes) })
                            }
                        })
                    }.toString()
                )
                files.forEachIndexed { i, f ->
                    val tid = taskId(id, i)
                    DownloadProgressHolder.updateProgress(tid, localNames[i], 0f)
                    DownloadProgressHolder.updateStatus(tid, name)
                    startDownload(
                        HfDatasetClient.resolveUrl(repo, revision, f.path),
                        File(dir, localNames[i]).absolutePath, localNames[i], tid
                    )
                }
            } catch (t: Throwable) {
                dao.updateStatus(id, DatasetStatus.FAILED.value, t.message)
                throw t
            }
            id
        }
    }

    /**
     * Recomputes a dataset's status from its files and download tasks (call from the UI while
     * downloading, and on screen open). Returns the updated row.
     */
    suspend fun refreshStatus(id: Long): TrainingDatasetEntity? = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext null
        if (row.source == DatasetSource.FILE.value || row.status == DatasetStatus.READY.value) return@withContext row
        val dir = datasetDir(id)
        val manifest = readManifest(File(dir, MANIFEST))
        if (manifest.isEmpty()) return@withContext row
        var allDone = true
        var error: String? = null
        var anyActive = false
        manifest.forEachIndexed { i, (name, size) ->
            val file = File(dir, name)
            if (file.isFile && file.length() > 0L && (size <= 0L || file.length() >= size)) return@forEachIndexed
            allDone = false
            val task = taskDao.getById(taskId(id, i))
            when {
                task == null -> Unit
                task.status == DOWNLOAD_TASK_STATUS_ACTIVE || task.status == DOWNLOAD_TASK_STATUS_RESUMABLE ->
                    anyActive = true
                else -> error = task.lastError ?: task.status
            }
        }
        val (status, err) = when {
            allDone -> DatasetStatus.READY to null
            error != null -> DatasetStatus.FAILED to error
            anyActive -> DatasetStatus.DOWNLOADING to null
            else -> return@withContext row
        }
        if (status.value != row.status || err != row.error) dao.updateStatus(id, status.value, err)
        if (status == DatasetStatus.READY) {
            val bytes = manifest.sumOf { (name, _) -> File(dir, name).length() }
            dao.updateSize(id, bytes, row.rowCount)
        }
        dao.getById(id)
    }

    /** Copies a SAF document into a new dataset directory; [onProgress] gets (copiedBytes, totalBytes or -1). */
    suspend fun importFile(uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                var display = uri.lastPathSegment.orEmpty()
                var total = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                                ?.let { c.getString(it) }?.takeIf { it.isNotBlank() }?.let { display = it }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { total = c.getLong(it) }
                        }
                    }
                val safeName = DatasetHelpers.safeFileName(display)
                if (!DatasetHelpers.isSupported(safeName)) throw IOException("Unsupported dataset file type: $safeName")
                val format = DatasetHelpers.detectFormat(safeName)
                val id = dao.insert(
                    TrainingDatasetEntity(
                        name = safeName, source = DatasetSource.FILE.value, hfRepo = null, hfConfig = null,
                        hfSplit = null, revision = null, licence = "user-provided", format = format.value,
                        columnMapJson = "{}", localPath = "", sizeBytes = maxOf(total, 0L), rowCount = null,
                        status = DatasetStatus.DOWNLOADING.value, error = null, createdAt = System.currentTimeMillis()
                    )
                )
                val dir = datasetDir(id).apply { mkdirs() }
                try {
                    dao.updateLocalPath(id, dir.absolutePath)
                    val target = File(dir, safeName)
                    var copied = 0L
                    val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $safeName")
                    input.use { src ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                onProgress(copied, total)
                            }
                        }
                    }
                    val kind = DatasetHelpers.fileKind(safeName)
                    dao.updateSize(id, copied, DatasetHelpers.estimateRows(copied, kind, format))
                    dao.updateStatus(id, DatasetStatus.READY.value, null)
                } catch (t: Throwable) {
                    dir.deleteRecursively()
                    dao.deleteById(id)
                    throw t
                }
                id
            }
        }

    /** Cancels downloads, removes the files and the row. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val row = dao.getById(id)
        val dir = datasetDir(id)
        if (row != null && row.status == DatasetStatus.DOWNLOADING.value) {
            readManifest(File(dir, MANIFEST)).forEachIndexed { i, (name, _) ->
                runCatching { cancelDownload(name, taskId(id, i)) }
                DownloadProgressHolder.removeProgress(taskId(id, i))
            }
        }
        dir.deleteRecursively()
        dao.deleteById(id)
    }

    /** The data files of a ready dataset (excludes the manifest and partial downloads). */
    fun dataFiles(id: Long): List<File> =
        datasetDir(id).listFiles { f -> f.isFile && f.name != MANIFEST && !f.name.endsWith(".part") }
            ?.sortedBy { it.name }.orEmpty()

    private fun readManifest(file: File): List<Pair<String, Long>> {
        if (!file.isFile) return emptyList()
        val obj = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject
            ?: return emptyList()
        return (obj["files"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            name to ((o["size"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
    }
}
