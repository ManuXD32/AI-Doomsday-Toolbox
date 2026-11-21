package com.example.llamadroid.branchy

import com.example.llamadroid.modeltools.GgufQuantizeSupport
import java.io.File
import java.util.Locale

/** The parts a run directory is split into for the storage dialogs. */
enum class BranchyStoragePart { TOKENIZED, TOKENIZER, CHECKPOINTS, MIRROR, DP, EXPORTS, OTHER }

/** Byte sizes per [BranchyStoragePart] of one run (or the sum of several). */
data class BranchyRunStorage(val bytes: Map<BranchyStoragePart, Long>) {
    fun of(part: BranchyStoragePart): Long = bytes[part] ?: 0L
    val total: Long get() = bytes.values.sum()

    /** What "Free space" removes: tokenized data, branch-parallel mirror and DP shard copies. */
    val freeable: Long
        get() = freeable(keepMirror = false)

    /** Paused runs keep their mirror (see [BranchyRunStorageScan.freeableFiles]). */
    fun freeable(keepMirror: Boolean): Long =
        of(BranchyStoragePart.TOKENIZED) + of(BranchyStoragePart.DP) + (if (keepMirror) 0L else of(BranchyStoragePart.MIRROR))

    operator fun plus(other: BranchyRunStorage) = BranchyRunStorage(
        BranchyStoragePart.entries.associateWith { of(it) + other.of(it) }
    )

    companion object {
        val EMPTY = BranchyRunStorage(emptyMap())
    }
}

/** One planned export move: [from] inside the run dir to [to] in the models folder. */
data class BranchyExportMove(val from: File, val to: File)

/** Pure scan / plan logic for run storage. File-system work: call off the main thread. */
object BranchyRunStorageScan {
    private val stageSegment = Regex("stage\\d+")

    /** Classifies a path relative to the run dir (`/` separated). Later stages (`stageN/`) are folded onto stage 0. */
    fun classify(relativePath: String): BranchyStoragePart {
        var segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.size > 1 && stageSegment.matches(segments[0])) segments = segments.drop(1)
        val name = segments.lastOrNull().orEmpty().lowercase(Locale.ROOT)
        return when {
            segments.isEmpty() -> BranchyStoragePart.OTHER
            segments[0] == "checkpoints" -> BranchyStoragePart.CHECKPOINTS
            segments[0] == "shards" && segments.getOrNull(1) == "mirror" -> BranchyStoragePart.MIRROR
            segments[0] == "dp" -> BranchyStoragePart.DP
            segments[0] == "export" && name.endsWith(".gguf") -> BranchyStoragePart.EXPORTS
            segments.size <= 2 && name.startsWith("tokenizer") && name.endsWith(".json") -> BranchyStoragePart.TOKENIZER
            segments[0] == "data" && segments.size == 2 &&
                (name.endsWith(".btok") || name.endsWith(".done")) -> BranchyStoragePart.TOKENIZED
            else -> BranchyStoragePart.OTHER
        }
    }

    private fun relative(runDir: File, file: File): String = file.relativeTo(runDir).invariantSeparatorsPath

    fun scan(runDir: File): BranchyRunStorage {
        if (!runDir.isDirectory) return BranchyRunStorage.EMPTY
        val sums = mutableMapOf<BranchyStoragePart, Long>()
        runDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val part = classify(relative(runDir, f))
            sums[part] = (sums[part] ?: 0L) + f.length()
        }
        return BranchyRunStorage(sums)
    }

    fun summarize(runDirs: List<File>): BranchyRunStorage =
        runDirs.fold(BranchyRunStorage.EMPTY) { acc, dir -> acc + scan(dir) }

    /** Files "Free space" deletes. Checkpoints, exports and the tokenizer (needed to export) stay. */
    fun freeableFiles(runDir: File, keepMirror: Boolean = false): List<File> {
        if (!runDir.isDirectory) return emptyList()
        // A paused branch-parallel run keeps its mirror: it is what lets the run change devices while a helper is offline.
        val parts = if (keepMirror) setOf(BranchyStoragePart.TOKENIZED, BranchyStoragePart.DP)
        else setOf(BranchyStoragePart.TOKENIZED, BranchyStoragePart.MIRROR, BranchyStoragePart.DP)
        return runDir.walkTopDown().filter { it.isFile && classify(relative(runDir, it)) in parts }.toList()
    }

    /** Deletes [freeableFiles] plus the directories they leave empty; returns the bytes freed. */
    fun freeSpace(runDir: File, keepMirror: Boolean = false): Long {
        var freed = 0L
        val files = freeableFiles(runDir, keepMirror)
        files.forEach { f ->
            val size = f.length()
            if (f.delete()) freed += size
        }
        files.mapNotNull { it.parentFile }.distinct().sortedByDescending { it.absolutePath.length }.forEach { dir ->
            var d: File? = dir
            while (d != null && d != runDir && d.isDirectory && d.list().isNullOrEmpty()) {
                val parent = d.parentFile
                d.delete()
                d = parent
            }
        }
        return freed
    }

    fun exportFiles(runDir: File): List<File> {
        if (!runDir.isDirectory) return emptyList()
        return runDir.walkTopDown()
            .filter { it.isFile && classify(relative(runDir, it)) == BranchyStoragePart.EXPORTS }
            .sortedBy { it.absolutePath }.toList()
    }

    /**
     * Where each export goes. Names never clash with files already in [modelsDir], with [reservedNames]
     * (lower-case library filenames) or with each other.
     */
    fun planExportMoves(runDir: File, modelsDir: File, reservedNames: Set<String> = emptySet()): List<BranchyExportMove> {
        val used = reservedNames.map { it.lowercase(Locale.ROOT) }.toMutableSet()
        return exportFiles(runDir).map { src ->
            val stem = src.name.removeSuffix(".gguf").removeSuffix(".GGUF")
            var target = GgufQuantizeSupport.uniqueOutputFile(modelsDir, src.name, null)
            var n = 2
            while (target.name.lowercase(Locale.ROOT) in used) {
                target = GgufQuantizeSupport.uniqueOutputFile(modelsDir, "$stem-${n++}", null)
            }
            used += target.name.lowercase(Locale.ROOT)
            BranchyExportMove(src, target)
        }
    }
}
