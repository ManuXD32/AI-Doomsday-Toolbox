package com.example.llamadroid.branchy.cluster

import android.content.Context
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyEventParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

/** State of the `llama-branchy-helper` process. */
enum class BranchyInferenceStatus { OFF, RUNNING, EXITED }

/** A model slice file held by this device. [branches] is empty when the file name does not say. */
data class BranchySliceInfo(val name: String, val sizeBytes: Long, val branches: List<Int>, val path: String)

/** Pure helpers for slice file names (`<model>.b1-2.slice.gguf`) and the worker's `file_received` event. */
object BranchySliceNames {
    const val SUFFIX = ".slice.gguf"
    private val BRANCH_PART = Regex("""\.b(\d+(?:[-,]\d+)*)${Regex.escape(SUFFIX)}$""", RegexOption.IGNORE_CASE)

    fun isSlice(fileName: String): Boolean = fileName.endsWith(SUFFIX, ignoreCase = true)

    /** `m.b1-2.slice.gguf` -> [1, 2]; `m.b1,3.slice.gguf` -> [1, 3]; unknown pattern -> empty. */
    fun branches(fileName: String): List<Int> {
        val spec = BRANCH_PART.find(fileName)?.groupValues?.get(1) ?: return emptyList()
        val out = sortedSetOf<Int>()
        for (part in spec.split(',')) {
            val range = part.split('-')
            val from = range.first().toIntOrNull() ?: return emptyList()
            val to = range.getOrNull(1)?.let { it.toIntOrNull() ?: return emptyList() } ?: from
            if (to < from || to - from > 255) return emptyList()
            for (b in from..to) out.add(b)
        }
        return out.toList()
    }

    /** File name only (no directories), so a copied or received name cannot escape the worker's files folder. */
    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim()

    fun info(file: File): BranchySliceInfo =
        BranchySliceInfo(file.name, file.length(), branches(file.name), file.absolutePath)

    /** Path of the slice named by a `file_received` stdout line, or null for any other line or a non-slice file. */
    fun receivedSlicePath(line: String, filesDir: File): File? {
        val event = BranchyEventParser.parse(line) as? BranchyEvent.Unknown ?: return null
        if (event.type != "file_received") return null
        val name = (event.raw["name"] as? JsonPrimitive)?.contentOrNull?.let(::safeName)?.takeIf(::isSlice) ?: return null
        val declared = (event.raw["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::File)
        // Only trust the declared path when it sits inside the files folder.
        val file = declared?.takeIf { it.parentFile?.canonicalPath == filesDir.canonicalPath } ?: File(filesDir, name)
        return file.takeIf { it.isFile }
    }

    /** Folder where the worker stores received files. */
    fun filesDir(context: Context): File = File(context.filesDir, "branchy/worker/files")

    /** Newest slice already on this device, if any. */
    fun latest(context: Context): File? =
        filesDir(context).listFiles()?.filter { it.isFile && isSlice(it.name) }?.maxByOrNull { it.lastModified() }
}

/** Battery guard decision: pause below the threshold when unplugged, resume with a small margin or on charge. */
object BranchyBatteryGuard {
    const val RESUME_MARGIN = 3

    fun shouldPause(enabled: Boolean, percent: Int, pluggedIn: Boolean, minPercent: Int, currentlyPaused: Boolean): Boolean {
        if (!enabled || pluggedIn || percent < 0) return false
        return if (currentlyPaused) percent < minPercent + RESUME_MARGIN else percent < minPercent
    }
}

/**
 * Runs `llama-branchy-helper` for one slice at a time. A new [start] replaces the running process, and a late
 * exit of a replaced process never overwrites the state of the new one.
 */
class BranchyInferenceHelperRunner(
    private val scope: CoroutineScope,
    private val onExit: (Int?) -> Unit
) {
    private var generation = 0

    @Volatile private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(exe: File, slice: File, port: Int, tokenFile: File, threads: Int, ctx: Int) {
        stopLocked()
        val gen = ++generation
        val argv = listOf(
            exe.absolutePath,
            "--model", slice.absolutePath,
            "--port", port.toString(),
            "--token-file", tokenFile.absolutePath,
            "--threads", threads.toString(),
            "--ctx", ctx.toString()
        )
        val proc = try {
            ProcessBuilder(argv).redirectErrorStream(true).directory(slice.parentFile).start()
        } catch (e: IOException) {
            BranchyHelperState.appendInferenceLine(e.message ?: e.javaClass.simpleName)
            BranchyHelperState.setInferenceExited(null)
            return
        }
        process = proc
        BranchyHelperState.setInferenceRunning(port)
        scope.launch {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val text = line.trim()
                    if (text.isNotEmpty() && isCurrent(gen)) BranchyHelperState.appendInferenceLine(text.take(MAX_LINE_CHARS))
                }
            } catch (_: IOException) {
                // The process was killed while we were reading.
            }
            val code = runCatching { proc.waitFor() }.getOrNull()
            synchronized(this@BranchyInferenceHelperRunner) {
                if (gen == generation) {
                    process = null
                    BranchyHelperState.setInferenceExited(code)
                    onExit(code)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        BranchyHelperState.setInferenceOff()
    }

    /** Suspends (SIGSTOP) or continues (SIGCONT) the helper process. */
    fun signal(suspend: Boolean) {
        process?.let { BranchyProcessSignals.signal(it, suspend) }
    }

    @Synchronized
    private fun isCurrent(gen: Int) = gen == generation

    private fun stopLocked() {
        generation++
        process?.let {
            BranchyProcessSignals.signal(it, suspend = false) // a stopped process would keep a pending SIGTERM
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
    }

    private companion object {
        const val MAX_LINE_CHARS = 300
    }
}

/** SIGSTOP/SIGCONT for child processes (the pid is not public on API 26-32, so it is read reflectively). */
object BranchyProcessSignals {
    private const val SIGCONT = 18
    private const val SIGSTOP = 19

    fun signal(process: Process, suspend: Boolean) {
        val pid = pidOf(process) ?: return
        runCatching { android.os.Process.sendSignal(pid, if (suspend) SIGSTOP else SIGCONT) }
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        field.getInt(process)
    }.getOrNull()?.takeIf { it > 1 }
}
