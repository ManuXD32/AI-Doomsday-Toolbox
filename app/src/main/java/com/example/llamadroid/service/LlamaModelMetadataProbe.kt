package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.ProcessUtils
import com.example.llamadroid.util.NativeProcessCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class LlamaModelProbeResult(
    val transformerBlocks: Int,
    val offloadableLayers: Int,
    val architecture: String?,
    val binaryPath: String,
    val modelFingerprint: String
)

class LlamaModelProbeTimeoutException : IllegalStateException()
class LlamaModelProbeNoLayersException : IllegalStateException()

private sealed interface ProbeOutputEvent {
    data class Line(val value: String) : ProbeOutputEvent
    data class Failure(val error: Throwable) : ProbeOutputEvent
    data object End : ProbeOutputEvent
}

/**
 * Reads model structure through the exact llama-server binary selected for launch.
 * Probe output is intentionally never forwarded to application or distributed logs.
 */
object LlamaModelMetadataProbe {
    private val cache = ConcurrentHashMap<String, LlamaModelProbeResult>()
    private val layerRegex = Regex(
        """\b(?:n_layers?|num_hidden_layers|[A-Za-z0-9_.-]+\.block_count|block_count)(?:\s+[A-Za-z0-9_]+)?\s*(?:=|:)\s*(\d+)\b""",
        RegexOption.IGNORE_CASE
    )
    private val architectureRegex = Regex(
        """(?:general\.architecture|model\s+arch(?:itecture)?)(?:\s+[A-Za-z0-9_]+)?\s*(?:=|:)\s*['\"]?([A-Za-z0-9_.-]+)""",
        RegexOption.IGNORE_CASE
    )

    suspend fun probe(
        context: Context,
        modelPath: String,
        binary: File,
        timeoutMs: Long = 15_000L
    ): LlamaModelProbeResult = withContext(Dispatchers.IO) {
        val model = File(modelPath)
        require(model.isFile) { "Model file is unavailable" }
        val fingerprint = fingerprint(model, binary)
        val probeRoot = File(context.cacheDir, "llama_metadata_probe").apply { mkdirs() }
        cleanupOwnedProbeProcesses(probeRoot, "before metadata check")
        cache[fingerprint]?.let { return@withContext it }

        val probeDir = File(probeRoot, fingerprint.hashCode().toUInt().toString())
            .apply { mkdirs() }
        val probePort = ServerSocket(0).use { it.localPort }
        var process: Process? = null
        var readerThread: Thread? = null
        val observedLines = AtomicInteger(0)
        try {
            require(timeoutMs > 0L) { "Probe timeout must be positive" }
            val command = buildProbeCommand(binary, model, probePort)
            val builder = ProcessBuilder(command)
                .directory(probeDir)
                .redirectErrorStream(true)
            val libraryPath = BinaryRepository(context.applicationContext).getLibraryDir()
            builder.environment()["LD_LIBRARY_PATH"] = libraryPath
            builder.environment()["HOME"] = probeDir.absolutePath
            builder.environment()["PWD"] = probeDir.absolutePath
            builder.environment()["TMPDIR"] = probeDir.absolutePath
            process = builder.start()
            DebugLog.log("[LlamaModelMetadataProbe] Started bounded metadata check (${timeoutMs}ms)")

            // Only retain lines that can contain the two values we need. Verbose
            // llama.cpp output can contain very large tokenizer arrays, so it must
            // never be accumulated in app memory merely to find block_count.
            val events = ArrayBlockingQueue<ProbeOutputEvent>(8)
            readerThread = Thread({
                try {
                    process!!.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            observedLines.incrementAndGet()
                            if (parseTransformerBlocks(line) != null || parseArchitecture(line) != null) {
                                events.put(ProbeOutputEvent.Line(line))
                            }
                        }
                    }
                    events.put(ProbeOutputEvent.End)
                } catch (error: Throwable) {
                    events.offer(ProbeOutputEvent.Failure(error))
                }
            }, "llama-model-metadata-reader").apply {
                isDaemon = true
                start()
            }

            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var architecture: String? = null
            while (true) {
                coroutineContext.ensureActive()
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) throw LlamaModelProbeTimeoutException()
                val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                    .coerceIn(1L, 100L)
                when (val event = events.poll(waitMs, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is ProbeOutputEvent.Line -> {
                        architecture = architecture ?: parseArchitecture(event.value)
                        val blocks = parseTransformerBlocks(event.value) ?: continue
                        val result = LlamaModelProbeResult(
                            transformerBlocks = blocks,
                            offloadableLayers = blocks + 1,
                            architecture = architecture,
                            binaryPath = binary.absolutePath,
                            modelFingerprint = fingerprint
                        )
                        cache[fingerprint] = result
                        DebugLog.log(
                            "[LlamaModelMetadataProbe] Metadata received: $blocks blocks; stopping probe"
                        )
                        return@withContext result
                    }
                    is ProbeOutputEvent.Failure -> {
                        if (process?.isAlive == true) throw event.error
                        throw LlamaModelProbeNoLayersException()
                    }
                    ProbeOutputEvent.End -> throw LlamaModelProbeNoLayersException()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw LlamaModelProbeNoLayersException()
        } catch (error: Throwable) {
            val status = when (error) {
                is LlamaModelProbeTimeoutException -> "timed out"
                else -> "failed (${error.javaClass.simpleName})"
            }
            DebugLog.log(
                "[LlamaModelMetadataProbe] Metadata check $status after ${observedLines.get()} output lines; cleaning up"
            )
            throw error
        } finally {
            val rootPid = runCatching {
                process?.let { child ->
                    (Process::class.java.getMethod("pid").invoke(child) as? Number)?.toInt()
                }
            }.getOrNull() ?: -1
            if (rootPid > 0) {
                NativeProcessCleanup.cleanupProcessTreeSync(
                    reason = "llama metadata probe",
                    rootPid = rootPid,
                    includeRoot = true,
                    graceMs = 100L,
                    forceMs = 250L
                )
            }
            ProcessUtils.stopProcessSync(process, gracePeriodMs = 100L, forcePeriodMs = 250L)
            NativeProcessCleanup.cleanupSameUidLlamaServersOwnedByDirectorySync(
                reason = "after llama metadata probe",
                ownerDirectory = probeDir
            )
            readerThread?.interrupt()
        }
    }

    internal fun parseTransformerBlocks(line: String): Int? = layerRegex.find(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    internal fun parseArchitecture(line: String): String? = architectureRegex.find(line)
        ?.groupValues
        ?.getOrNull(1)

    fun manualResult(transformerBlocks: Int, modelPath: String): LlamaModelProbeResult {
        require(transformerBlocks in 1..2048) { "Transformer blocks must be between 1 and 2048" }
        return LlamaModelProbeResult(
            transformerBlocks = transformerBlocks,
            offloadableLayers = transformerBlocks + 1,
            architecture = null,
            binaryPath = "manual",
            modelFingerprint = "manual|$modelPath|$transformerBlocks"
        )
    }

    internal fun buildProbeCommand(binary: File, model: File, port: Int): List<String> = listOf(
        binary.absolutePath,
        "--model", model.absolutePath,
        "--ctx-size", "8",
        "--threads", "1",
        "--batch-size", "8",
        "--no-warmup",
        "--device", "none",
        "--n-gpu-layers", "0",
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "--verbose"
    )

    internal fun fingerprint(model: File, binary: File): String = listOf(
        model.absolutePath,
        model.length(),
        model.lastModified(),
        binary.absolutePath,
        binary.length(),
        binary.lastModified()
    ).joinToString("|")

    private fun cleanupOwnedProbeProcesses(probeRoot: File, reason: String) {
        probeRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.forEach { directory ->
                NativeProcessCleanup.cleanupSameUidLlamaServersOwnedByDirectorySync(
                    reason = reason,
                    ownerDirectory = directory
                )
            }
    }

    internal fun clearCache() = cache.clear()
}
