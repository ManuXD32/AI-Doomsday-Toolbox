package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.branchy.BranchyJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File

enum class BranchyWorkerStorageKind { SHARDS, DP_RUN, DP_DATA, SLICE }

/** One deletable item under the worker root. [name] is the run name when known, else null (show [id]). */
data class BranchyWorkerStorageEntry(
    val kind: BranchyWorkerStorageKind,
    val id: String,
    val name: String?,
    val path: File,
    val bytes: Long,
    /** Epoch milliseconds, 0 when unknown. */
    val lastUsedMs: Long
)

/** Scans and cleans the worker root (`filesDir/branchy/worker`). Pure file logic, no Android dependencies. */
object BranchyWorkerStorage {
    private const val DP_DIR = "dp"
    private const val FILES_DIR = "files"
    private const val META = "meta.json"
    const val SLICE_SUFFIX = ".slice.gguf"

    fun scan(root: File): List<BranchyWorkerStorageEntry> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<BranchyWorkerStorageEntry>()
        root.listFiles()?.filter { it.isDirectory && it.name != DP_DIR && it.name != FILES_DIR }?.forEach { dir ->
            val meta = readObject(File(dir, META))
            out += BranchyWorkerStorageEntry(
                BranchyWorkerStorageKind.SHARDS, dir.name, text(meta, "run"), dir,
                sizeOf(dir), lastUsed(meta, dir)
            )
        }
        scanDp(File(root, DP_DIR), out)
        File(root, FILES_DIR).listFiles()?.filter { it.isFile && it.name.endsWith(SLICE_SUFFIX) }?.forEach { f ->
            out += BranchyWorkerStorageEntry(BranchyWorkerStorageKind.SLICE, f.name, null, f, f.length(), f.lastModified())
        }
        return out.sortedByDescending { it.lastUsedMs }
    }

    /** DP shards are stored flat as `dp_<cluster_id>_r<rank>_s<source>.btok` (+ `.part`); group them by cluster id. */
    private fun scanDp(dp: File, out: MutableList<BranchyWorkerStorageEntry>) {
        if (!dp.isDirectory) return
        val runs = (readObject(File(dp, META))?.get("runs") as? JsonObject).orEmpty()
        val groups = HashMap<String?, MutableList<File>>()
        dp.listFiles().orEmpty().filter { it.name != META }.forEach { f ->
            groups.getOrPut(dpOwner(f.name, runs.keys)) { ArrayList() } += f
        }
        groups.forEach { (id, files) ->
            val bytes = files.sumOf { sizeOf(it) }
            val newest = files.maxOf { it.lastModified() }
            if (id == null) {
                if (bytes > 0L) out += BranchyWorkerStorageEntry(BranchyWorkerStorageKind.DP_DATA, DP_DIR, null, dp, bytes, newest)
            } else {
                val meta = runs[id] as? JsonObject
                out += BranchyWorkerStorageEntry(
                    BranchyWorkerStorageKind.DP_RUN, id, text(meta, "run"), dp, bytes, lastUsedOr(meta, newest)
                )
            }
        }
    }

    /** Cluster id owning a flat dp file: the longest meta id whose `dp_<id>_` prefix matches, else null. */
    private fun dpOwner(fileName: String, ids: Set<String>): String? =
        ids.filter { isSafeName(it) && fileName.startsWith("dp_${it}_") }.maxByOrNull { it.length }

    /** Deletes [entry] if it is inside [root]; returns true when it is gone. DP run entries are dropped from dp/meta.json. */
    fun delete(root: File, entry: BranchyWorkerStorageEntry): Boolean {
        val base = root.canonicalFile
        val target = entry.path.canonicalFile
        if (target == base || !target.path.startsWith(base.path + File.separator)) return false
        return when (entry.kind) {
            BranchyWorkerStorageKind.DP_RUN, BranchyWorkerStorageKind.DP_DATA -> {
                val runIds = (readObject(File(target, META))?.get("runs") as? JsonObject)?.keys.orEmpty()
                val wanted = if (entry.kind == BranchyWorkerStorageKind.DP_RUN) entry.id else null
                val ok = target.listFiles().orEmpty()
                    .filter { it.name != META && dpOwner(it.name, runIds) == wanted }
                    .map { it.deleteRecursively() }.all { it }
                if (ok && wanted != null) dropRun(target, wanted)
                ok
            }
            else -> target.deleteRecursively()
        }
    }

    fun deleteAll(root: File, entries: List<BranchyWorkerStorageEntry>): Boolean =
        entries.map { delete(root, it) }.all { it }

    private fun dropRun(dp: File, id: String) {
        val file = File(dp, META)
        val obj = readObject(file) ?: return
        val runs = (obj["runs"] as? JsonObject) ?: return
        val updated = JsonObject(obj + ("runs" to JsonObject(runs - id)))
        runCatching { file.writeText(BranchyJson.json.encodeToString(JsonObject.serializer(), updated)) }
    }

    private fun isSafeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun readObject(file: File): JsonObject? = runCatching {
        if (file.isFile) BranchyJson.json.parseToJsonElement(file.readText()) as? JsonObject else null
    }.getOrNull()

    private fun text(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun lastUsed(meta: JsonObject?, dir: File): Long = lastUsedOr(meta, dir.lastModified())

    private fun lastUsedOr(meta: JsonObject?, fallbackMs: Long): Long {
        val sec = (meta?.get("last_used") as? JsonPrimitive)?.doubleOrNull
        return if (sec != null && sec > 0) (sec * 1000).toLong() else fallbackMs
    }

    fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
