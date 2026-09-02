package com.example.llamadroid.service

import android.os.Debug
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.min

/**
 * A deliberately small, content-free health sample taken around an SD run.
 *
 * This data is intended for diagnostics breadcrumbs only. It contains process/resource counters,
 * never prompts, model names, paths, native output, or command arguments.
 */
internal data class SdPostRunHealthSnapshot(
    val javaHeapUsedBytes: Long?,
    val javaHeapCommittedBytes: Long?,
    val nativeHeapAllocatedBytes: Long?,
    val nativeHeapSizeBytes: Long?,
    val processPssKb: Long?,
    val processRssKb: Long?,
    val threadCount: Int?,
    val fileDescriptorCount: Int?,
    val activeWorkCount: Int,
    val activeProcessCount: Int,
    val generationLockHeld: Boolean,
    val wakeLockHeld: Boolean
) {
    fun toDetails(sample: String): String = SdPostRunHealthFormatter.format(this, sample)

    companion object {
        fun capture(
            activeWorkCount: Int,
            activeProcessCount: Int,
            generationLockHeld: Boolean,
            wakeLockHeld: Boolean
        ): SdPostRunHealthSnapshot {
            val runtime = Runtime.getRuntime()
            val committed = runtime.totalMemory().takeIf { it >= 0L }
            val used = committed?.let { (it - runtime.freeMemory()).coerceAtLeast(0L) }
            val nativeAllocated = runCatching { Debug.getNativeHeapAllocatedSize() }
                .getOrNull()
                ?.takeIf { it >= 0L }
            val nativeSize = runCatching { Debug.getNativeHeapSize() }
                .getOrNull()
                ?.takeIf { it >= 0L }
            val memory = readProcessMemory()
            val threadCount = memory.threadCount
                ?: runCatching { Thread.activeCount() }
                    .getOrNull()
                    ?.takeIf { it > 0 }
            return SdPostRunHealthSnapshot(
                javaHeapUsedBytes = used,
                javaHeapCommittedBytes = committed,
                nativeHeapAllocatedBytes = nativeAllocated,
                nativeHeapSizeBytes = nativeSize,
                processPssKb = readProcessPssKb(),
                processRssKb = memory.rssKb,
                threadCount = threadCount,
                fileDescriptorCount = countFileDescriptors(),
                activeWorkCount = activeWorkCount.coerceAtLeast(0),
                activeProcessCount = activeProcessCount.coerceAtLeast(0),
                generationLockHeld = generationLockHeld,
                wakeLockHeld = wakeLockHeld
            )
        }
    }
}

/** Single-line bounded formatter for [GenerationDiagnosticsStore] breadcrumb details. */
internal object SdPostRunHealthFormatter {
    const val MAX_DETAILS_CHARS = 1_024
    private const val MAX_COUNTER_VALUE = 999_999_999_999_999L
    private const val MAX_COUNT = 100_000

    fun format(snapshot: SdPostRunHealthSnapshot, sample: String): String = buildString {
        append("sample=")
        append(sample.take(64).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24).ifBlank { "unknown" })
        appendMetric("javaHeapUsedBytes", snapshot.javaHeapUsedBytes)
        appendMetric("javaHeapCommittedBytes", snapshot.javaHeapCommittedBytes)
        appendMetric("nativeHeapAllocatedBytes", snapshot.nativeHeapAllocatedBytes)
        appendMetric("nativeHeapSizeBytes", snapshot.nativeHeapSizeBytes)
        appendMetric("processPssKb", snapshot.processPssKb)
        appendMetric("processRssKb", snapshot.processRssKb)
        snapshot.threadCount?.let { append(" threadCount=").append(it.coerceIn(0, MAX_COUNT)) }
        snapshot.fileDescriptorCount?.let {
            append(" fileDescriptorCount=").append(it.coerceIn(0, MAX_COUNT))
        }
        append(" activeWorkCount=").append(snapshot.activeWorkCount.coerceIn(0, MAX_COUNT))
        append(" activeProcessCount=").append(snapshot.activeProcessCount.coerceIn(0, MAX_COUNT))
        append(" generationLockHeld=").append(snapshot.generationLockHeld)
        append(" wakeLockHeld=").append(snapshot.wakeLockHeld)
    }.take(MAX_DETAILS_CHARS)

    private fun StringBuilder.appendMetric(name: String, value: Long?) {
        value?.let {
            append(' ')
            append(name)
            append('=')
            append(it.coerceIn(0L, MAX_COUNTER_VALUE))
        }
    }
}

private data class ProcStatusMetrics(
    val rssKb: Long?,
    val threadCount: Int?
)

private const val MAX_PROC_STATUS_BYTES = 32 * 1024
private const val MAX_FD_COUNT = 100_000

private fun readProcessMemory(): ProcStatusMetrics {
    val text = runCatching {
        val file = File("/proc/self/status")
        if (!file.isFile) return@runCatching null
        val bytes = ByteArrayOutputStream(MAX_PROC_STATUS_BYTES)
        file.inputStream().use { input ->
            val buffer = ByteArray(min(4 * 1024, MAX_PROC_STATUS_BYTES))
            var remaining = MAX_PROC_STATUS_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, min(buffer.size, remaining))
                if (read <= 0) break
                bytes.write(buffer, 0, read)
                remaining -= read
            }
        }
        String(bytes.toByteArray(), Charsets.US_ASCII)
    }.getOrNull() ?: return ProcStatusMetrics(null, null)

    val rssKb = procStatusValue(text, "VmRSS")
    val threadCount = procStatusValue(text, "Threads")
        ?.toInt()
        ?.takeIf { it > 0 }
    return ProcStatusMetrics(rssKb, threadCount)
}

private fun procStatusValue(text: String, key: String): Long? = text.lineSequence()
    .firstOrNull { it.startsWith("$key:") }
    ?.substringAfter(':')
    ?.trim()
    ?.takeWhile { !it.isWhitespace() }
    ?.toLongOrNull()
    ?.takeIf { it >= 0L }

private fun readProcessPssKb(): Long? = runCatching {
    val memoryInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memoryInfo)
    memoryInfo.totalPss.toLong()
}.getOrNull()?.takeIf { it >= 0L }

private fun countFileDescriptors(): Int? = runCatching {
    val path = File("/proc/self/fd").toPath()
    if (!Files.isDirectory(path)) return@runCatching null
    var count = 0
    Files.newDirectoryStream(path).use { entries ->
        for (ignored in entries) {
            count++
            if (count >= MAX_FD_COUNT) break
        }
    }
    count
}.getOrNull()
