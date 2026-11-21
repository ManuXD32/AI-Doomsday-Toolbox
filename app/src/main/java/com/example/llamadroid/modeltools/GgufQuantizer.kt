package com.example.llamadroid.modeltools

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.ui.models.buildLocalModelEntity
import com.example.llamadroid.util.DebugLog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose

/** Target types offered for GGUF quantization. [bitsPerWeight] is the average size used for estimates. */
enum class QuantTarget(val ggufName: String, val bitsPerWeight: Double) {
    Q8_0("Q8_0", 8.5),
    Q4_K_M("Q4_K_M", 4.85),
    Q4_0("Q4_0", 4.5)
}

/** One step of a running quantization. */
sealed class QuantEvent {
    data class Progress(val tensor: Int, val total: Int) : QuantEvent()
    data class Done(val output: File) : QuantEvent()
    data class Failed(val reason: FailReason, val detail: String = "") : QuantEvent()
}

enum class FailReason { NO_BINARY, NO_SPACE, EXIT_CODE, CANCELLED }

/** Pure helpers (argv, naming, estimates, progress parsing); kept free of Android types for unit tests. */
object GgufQuantizeSupport {
    /** Bytes the app may write under [dir], including cache the system can clear (falls back to StatFs). */
    fun allocatableBytes(context: android.content.Context, dir: java.io.File): Long = runCatching {
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(dir))
    }.getOrElse { runCatching { android.os.StatFs(dir.path).availableBytes }.getOrDefault(0L) }

    private val progressRegex = Regex("""^\s*\[\s*(\d+)\s*/\s*(\d+)\s*]""")
    private val srcBits = listOf(
        "BF16" to 16.0, "F16" to 16.0, "F32" to 32.0, "Q8_0" to 8.5, "Q6_K" to 6.56,
        "Q5_K" to 5.7, "Q5_1" to 6.0, "Q5_0" to 5.5, "Q4_K" to 4.85, "Q4_1" to 5.0, "Q4_0" to 4.5,
        "Q3_K" to 3.9, "Q2_K" to 2.96
    )
    private val quantTagRegex = Regex("""[-_.](BF16|F16|F32|Q\d_[0-9A-Z_]+|IQ\d_[0-9A-Z_]+)$""", RegexOption.IGNORE_CASE)

    fun buildArgv(exe: String, input: String, output: String, target: QuantTarget, threads: Int, allowRequantize: Boolean): List<String> =
        buildList {
            add(exe)
            if (allowRequantize) add("--allow-requantize")
            add(input); add(output); add(target.ggufName)
            if (threads > 0) add(threads.toString())
        }

    /** `<name>-<TYPE>.gguf` next to [source]; a trailing quant tag of the source name is replaced. */
    fun outputName(sourceFilename: String, typeName: String): String {
        val base = sourceFilename.removeSuffix(".gguf").removeSuffix(".GGUF")
        val stripped = quantTagRegex.replace(base, "").ifBlank { base }
        return "$stripped-$typeName.gguf"
    }

    /**
     * `<name>-<TYPE>.gguf` in [dir] that never equals [source] and does not exist yet
     * (compared case-insensitively, as shared storage may be case-insensitive); appends `-2`, `-3`, ...
     */
    fun uniqueOutputFile(dir: File, name: String, source: File?): File {
        val stem = name.removeSuffix(".gguf").removeSuffix(".GGUF")
        val taken = dir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet().orEmpty()
        val sourceName = source?.name?.lowercase(Locale.ROOT)
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$stem.gguf" else "$stem-$n.gguf"
            val lower = candidate.lowercase(Locale.ROOT)
            if (lower != sourceName && lower !in taken && !File(dir, candidate).exists()) return File(dir, candidate)
            n++
        }
    }

    /** Quantization tag of a GGUF filename (e.g. `Q4_K_M`), ignoring a `-2`-style uniqueness suffix; null when absent. */
    fun quantTag(filename: String): String? {
        val base = filename.removeSuffix(".gguf").removeSuffix(".GGUF").replace(uniqueSuffixRegex, "")
        return quantTagRegex.find(base)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    }

    private val uniqueSuffixRegex = Regex("""-\d+$""")

    /** True when the filename carries a quantization tag other than f16/bf16/f32. */
    fun looksQuantized(filename: String): Boolean {
        val tag = quantTagRegex.find(filename.removeSuffix(".gguf"))?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: return false
        return tag != "F16" && tag != "BF16" && tag != "F32"
    }

    private fun sourceBitsFor(filename: String): Double {
        val up = filename.uppercase(Locale.ROOT)
        return srcBits.firstOrNull { (tag, _) -> up.contains(tag) }?.second ?: 16.0
    }

    fun estimateOutputBytes(sourceBytes: Long, sourceFilename: String, target: QuantTarget): Long =
        if (sourceBytes <= 0L) 0L else (sourceBytes * (target.bitsPerWeight / sourceBitsFor(sourceFilename))).toLong()

    /** Estimate for an export from F16 weights of [paramBytesF16] bytes to the Branchy export type. */
    fun estimateExportBytes(paramBytesF16: Long, type: String): Long = when (type.lowercase(Locale.ROOT)) {
        "q8_0" -> (paramBytesF16 * 8.5 / 16.0).toLong()
        "q4_0" -> (paramBytesF16 * 4.5 / 16.0).toLong()
        else -> paramBytesF16
    }

    /** Parses `[ 12/ 291] blk.0...` lines of llama-quantize; null when [line] is not a progress line. */
    fun parseProgress(line: String): Pair<Int, Int>? {
        val m = progressRegex.find(line) ?: return null
        val cur = m.groupValues[1].toIntOrNull() ?: return null
        val total = m.groupValues[2].toIntOrNull() ?: return null
        return if (total > 0) cur to total else null
    }

    fun hasEnoughSpace(freeBytes: Long, neededBytes: Long): Boolean = freeBytes >= neededBytes + neededBytes / 20 + 16L * 1024 * 1024
}

/** Runs the bundled `llama-quantize` and registers the result as a local model. */
class GgufQuantizer(private val context: Context) {

    /** Cold flow: collecting starts the process, cancelling the collector kills it. */
    fun quantize(source: File, target: QuantTarget, allowRequantize: Boolean): Flow<QuantEvent> = callbackFlow {
        val exe = BinaryRepository(context).getCpuTieredBinary("llama-quantize")
        if (exe == null) {
            trySend(QuantEvent.Failed(FailReason.NO_BINARY)); close(); return@callbackFlow
        }
        val dir = source.parentFile ?: context.filesDir
        val out = GgufQuantizeSupport.uniqueOutputFile(dir, GgufQuantizeSupport.outputName(source.name, target.ggufName), source)
        val needed = GgufQuantizeSupport.estimateOutputBytes(source.length(), source.name, target)
        val free = GgufQuantizeSupport.allocatableBytes(context, source.parentFile ?: context.filesDir)
        if (!GgufQuantizeSupport.hasEnoughSpace(free, needed)) {
            trySend(QuantEvent.Failed(FailReason.NO_SPACE, needed.toString())); close(); return@callbackFlow
        }
        val tmp = File(out.parentFile, out.name + ".part")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val argv = GgufQuantizeSupport.buildArgv(exe.absolutePath, source.absolutePath, tmp.absolutePath, target, threads, allowRequantize)
        DebugLog.log("[Quantize] ${argv.joinToString(" ")}")
        val pb = ProcessBuilder(argv).redirectErrorStream(true).directory(context.filesDir)
        val libDir = File(context.filesDir, "lib").apply { mkdirs() }
        pb.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${exe.parentFile?.absolutePath.orEmpty()}"
        pb.environment()["HOME"] = context.filesDir.absolutePath
        val proc = pb.start()
        var lastLine = ""
        launch(Dispatchers.IO) {
          try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) lastLine = line
                GgufQuantizeSupport.parseProgress(line)?.let { (c, t) -> trySend(QuantEvent.Progress(c, t)) }
            }
            val exit = proc.waitFor()
            if (exit == 0 && tmp.isFile && tmp.length() > 0L) {
                if (!out.exists() && tmp.renameTo(out)) trySend(QuantEvent.Done(out)) else trySend(QuantEvent.Failed(FailReason.EXIT_CODE, "rename"))
            } else {
                tmp.delete()
                trySend(QuantEvent.Failed(FailReason.EXIT_CODE, lastLine.take(200)))
            }
        } catch (e: Exception) {
            tmp.delete()
            trySend(QuantEvent.Failed(FailReason.CANCELLED, e.message.orEmpty()))
          }
          close()
        }
        awaitClose {
            if (proc.isAlive) { proc.destroyForcibly(); tmp.delete() }
        }
    }.flowOn(Dispatchers.IO)
}

/** Registers [file] through the model manager's local-import entity builder and repository. */
suspend fun registerLocalGgufModel(
    context: Context,
    file: File,
    origin: String? = null,
    derivedFrom: String? = null
): Result<String> = runCatching {
    val repository = ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
    val entity = buildLocalModelEntity(path = file.absolutePath, filename = file.name, modelType = ModelType.LLM)
        .copy(origin = origin, derivedFrom = derivedFrom)
    repository.insertModel(entity)
    entity.filename
}
