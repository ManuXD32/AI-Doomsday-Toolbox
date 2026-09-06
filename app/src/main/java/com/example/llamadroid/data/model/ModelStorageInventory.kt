package com.example.llamadroid.data.model

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

data class StorageArtifact(val path: String, val groups: Set<String>, val countAsModel: Boolean = true)
data class StorageUsage(val count: Int = 0, val bytes: Long = 0L)
data class ModelStorageInventory(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val modelsBytes: Long = 0L,
    val groups: Map<String, StorageUsage> = emptyMap(),
    val pendingBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val loaded: Boolean = false
) {
    fun usage(group: String) = groups[group] ?: StorageUsage()
}

private fun ModelEntity.storageGroups(): Set<String> = buildSet {
    add(storageFamilyKey())
    add("type:${type.name}")
    add(when (type.name) {
        "LLM", "VISION" -> "llm:base"
        "LLM_DRAFT" -> "llm:draft"
        "LORA" -> "llm:lora"
        "EMBEDDING" -> "llm:embedding"
        "VISION_PROJECTOR", "MMPROJ" -> "llm:projector"
        else -> "type:${type.name}"
    })
    // A shared encoder can be managed from both families; total bytes are still deduplicated.
    if (type.name in setOf("LLM", "VISION", "VISION_PROJECTOR", "MMPROJ")) add("llm")
}

fun ModelEntity.storageFamilyKey(): String = when {
    type.name.startsWith("SD_") || !sdCompatProfiles.isNullOrBlank() -> "sd"
    type.name.startsWith("ONNX_") -> "onnx"
    type.name == "WHISPER" -> "whisper"
    type.name == "QUADTRIX" -> "quadtrix"
    else -> "llm"
}

/** Disk enumeration is independent of metadata sizes and never follows a directory twice. */
internal fun physicalFiles(path: String): Map<String, Long> {
    val files = linkedMapOf<String, Long>()
    val visited = hashSetOf<String>()
    val queue = java.util.ArrayDeque<File>()
    queue.add(File(path))
    while (queue.isNotEmpty()) {
        val candidate = queue.removeFirst()
        val canonical = runCatching { candidate.canonicalPath }.getOrNull() ?: continue
        if (!visited.add(canonical)) continue
        if (candidate.isDirectory) candidate.listFiles()?.forEach(queue::addLast)
        else if (candidate.isFile && !candidate.name.endsWith(".part")) files[canonical] = candidate.length().coerceAtLeast(0L)
    }
    return files
}

internal fun measureModelStorage(
    artifacts: List<StorageArtifact>,
    pending: List<String> = emptyList(),
    downloads: List<String> = emptyList(),
    totalBytes: Long = 0L,
    freeBytes: Long = 0L
): ModelStorageInventory {
    val allFiles = linkedMapOf<String, Long>()
    val groupFiles = linkedMapOf<String, MutableMap<String, Long>>()
    val groupRoots = linkedMapOf<String, MutableSet<String>>()
    val cache = hashMapOf<String, Map<String, Long>>()
    artifacts.forEach { artifact ->
        val root = runCatching { File(artifact.path).canonicalPath }.getOrNull() ?: return@forEach
        val files = cache.getOrPut(root) { physicalFiles(root) }
        if (files.isEmpty()) return@forEach
        allFiles.putAll(files)
        artifact.groups.forEach { group ->
            if (artifact.countAsModel) groupRoots.getOrPut(group) { linkedSetOf() }.add(root)
            groupFiles.getOrPut(group) { linkedMapOf() }.putAll(files)
        }
    }
    val pendingFiles = linkedMapOf<String, Long>()
    pending.forEach { path -> pendingFiles.putAll(physicalFiles(path)) }
    allFiles.keys.forEach(pendingFiles::remove)
    val partialFiles = linkedMapOf<String, Long>()
    downloads.forEach { path ->
        val file = File(path)
        if (file.isFile) runCatching { file.canonicalPath }.getOrNull()?.let { partialFiles[it] = file.length() }
    }
    allFiles.keys.forEach(partialFiles::remove)
    pendingFiles.keys.forEach(partialFiles::remove)
    return ModelStorageInventory(
        totalBytes.coerceAtLeast(0L), freeBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L)),
        allFiles.values.sum(), groupFiles.mapValues { (key, files) -> StorageUsage(groupRoots[key].orEmpty().size, files.values.sum()) } +
            ("unknown" to StorageUsage(pendingFiles.size, pendingFiles.values.sum())),
        pendingFiles.values.sum(), partialFiles.values.sum(), loaded = true
    )
}

/** One event-driven scan shared by all visible model surfaces; no composition-time I/O. */
class ModelStorageRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val db = AppDatabase.getDatabase(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private data class Inputs(val models: List<ModelEntity>, val liteRt: List<LiteRtModelEntity>,
        val downloads: List<Triple<String, String, String>>, val pending: List<PendingModelArtifactEntity>)

    private val inputs = combine(
        db.modelDao().getAllModels(), db.liteRtModelDao().observeAll(),
        db.downloadTaskDao().observeAll().map { tasks -> tasks.map { Triple(it.destPath, it.status, it.modelType) } }.distinctUntilChanged(),
        db.modelLibraryDao().observePendingArtifacts()
    ) { models, liteRt, downloads, pending -> Inputs(models, liteRt, downloads, pending) }

    val inventory: StateFlow<ModelStorageInventory> = combine(inputs, refreshes.onStart { emit(Unit) }) { input, _ ->
        val (models, liteRt, downloads, pending) = input
        val artifacts = models.map { StorageArtifact(it.path, it.storageGroups()) } +
            liteRt.map { model ->
                val file = File(model.path)
                val root = File(app.noBackupFilesDir, "litert_models")
                val parent = file.parentFile
                val path = if (parent != null && parent != root && parent.toPath().startsWith(root.toPath())) parent.path else file.path
                StorageArtifact(path, setOf("litert"))
            } + pending.filter { it.status == "PROMOTED" }.map { artifact ->
                StorageArtifact(artifact.destinationPath ?: artifact.stagingPath, buildSet {
                    (artifact.detectedFamily ?: artifact.requestedFamily)?.lowercase()?.let(::add)
                    artifact.detectedType?.let { add("type:$it") }
                }, countAsModel = false)
            } + listOfNotNull(app.getExternalFilesDir(null)?.let { File(it, "models/whisper/vad") }, File(app.filesDir, "whisper_vad_models"))
                .flatMap { it.listFiles().orEmpty().toList() }.filter { it.isFile }.map { StorageArtifact(it.path, setOf("whisper", "type:WHISPER_VAD")) }
        val stats = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        measureModelStorage(artifacts, pending = pending.filter { it.status != "PROMOTED" }
                .flatMap { listOfNotNull(it.stagingPath, it.destinationPath) },
            downloads = downloads.map { (path, _, _) -> "$path.part" },
            totalBytes = stats?.totalBytes ?: 0L, freeBytes = stats?.availableBytes ?: 0L)
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), ModelStorageInventory())

    fun refresh() { refreshes.tryEmit(Unit) }

    companion object {
        // The repository canonicalizes its input to applicationContext; no Activity is retained.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ModelStorageRepository? = null
        fun get(context: Context): ModelStorageRepository = instance ?: synchronized(this) {
            instance ?: ModelStorageRepository(context).also { instance = it }
        }
    }
}
