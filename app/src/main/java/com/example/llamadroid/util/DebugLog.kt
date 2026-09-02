package com.example.llamadroid.util

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class LogEntry(val timestamp: Long, val message: String)

internal fun debugLogTrimPersistedLines(lines: List<String>, maxLines: Int): List<String> =
    lines.takeLast(maxLines.coerceAtLeast(0))

/**
 * Small synchronized ring buffer used by [DebugLog]. Keeping the deque separate from the
 * StateFlow means a native output burst does not allocate a new logical list for every line.
 */
internal class BoundedLogBuffer<T>(private val capacity: Int) {
    private val values = ArrayDeque<T>()

    @Synchronized
    fun add(value: T) {
        if (capacity <= 0) return
        while (values.size >= capacity) values.removeFirst()
        values.addLast(value)
    }

    @Synchronized
    fun addAll(items: Iterable<T>) {
        items.forEach(::add)
    }

    @Synchronized
    fun replace(items: Iterable<T>) {
        values.clear()
        items.forEach(::add)
    }

    @Synchronized
    fun snapshot(): List<T> = values.toList()

    @Synchronized
    fun clear() {
        values.clear()
    }
}

object DebugLog {
    private const val MAX_LOGS = 1000
    private const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_PERSISTED_LOG_LINES = 1000
    private const val PERSISTED_LOG_PRUNE_BATCH = 100
    private const val BATCH_WINDOW_MILLIS = 75L
    private const val LOG_DIR = "debug_log"
    private const val LOG_FILE = "app_logs.tsv"

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    /**
     * All mutations and file operations are serialized through this lock. Disk work only runs
     * once per short batch, so callers never copy/rewrite the full logical log for each line.
     */
    private val lock = Any()
    private val buffer = BoundedLogBuffer<LogEntry>(MAX_LOGS)
    private val pendingEntries = ArrayDeque<LogEntry>()
    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DebugLogFlush").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null
    private var persistedLineCount: Int = 0
    private var flushFuture: ScheduledFuture<*>? = null

    // Patterns to filter out (noisy server logs + sensitive build info)
    private val filterPatterns = listOf(
        // Noisy server health checks
        "GET /health",
        "GET /props",
        "log_server_r: request: GET /health",
        "log_server_r: request: GET /props",
        // Sensitive build configuration info (security)
        "configuration:",
        "--prefix=",
        "--cc=",
        "--cxx=",
        "--ar=",
        "--ranlib=",
        "--strip=",
        "--sysroot=",
        "--extra-cflags=",
        "--extra-ldflags=",
        "/home/",
        "/Users/",
        "/mnt/",
        "prebuilt/linux",
        "toolchains/llvm",
        "[VIDEO-GEN] Heartbeat:",
        "[StableDiffusionService] Heartbeat:",
        "Heartbeat: activeModes="
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshFromDisk()
    }

    fun log(message: String) {
        if (filterPatterns.any { message.contains(it) }) return

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            message = message.take(MAX_MESSAGE_CHARS)
        )
        synchronized(lock) {
            buffer.add(entry)
            if (pendingEntries.size >= MAX_LOGS) pendingEntries.removeFirst()
            pendingEntries.addLast(entry)
            scheduleFlushLocked()
        }
    }

    /**
     * Merge the persisted tail without loading an old unbounded file into memory. This is called
     * by the Logs screen and during application startup.
     */
    fun refreshFromDisk() {
        // Make the explicit refresh boundary include lines emitted in the current batch.
        flushNow()
        val persisted = readPersisted()
        if (persisted.isEmpty()) return
        synchronized(lock) {
            val merged = (persisted + buffer.snapshot())
                .distinctBy { it.timestamp to it.message }
                .sortedBy { it.timestamp }
                .takeLast(MAX_LOGS)
            buffer.replace(merged)
            _logs.value = buffer.snapshot()
        }
    }

    fun clear() {
        synchronized(lock) {
            flushFuture?.cancel(false)
            flushFuture = null
            pendingEntries.clear()
            buffer.clear()
            _logs.value = emptyList()
            persistedLineCount = 0
            logFile()?.delete()
        }
    }

    /** Test/support hook and startup drain; normal writes remain batched asynchronously. */
    internal fun flushNow() {
        synchronized(lock) {
            flushFuture?.cancel(false)
            flushFuture = null
            flushPendingLocked()
        }
    }

    private fun scheduleFlushLocked() {
        if (flushFuture?.isDone == false) return
        flushFuture = flushExecutor.schedule(
            {
                synchronized(lock) {
                    flushFuture = null
                    flushPendingLocked()
                }
            },
            BATCH_WINDOW_MILLIS,
            TimeUnit.MILLISECONDS
        )
    }

    /** Caller holds [lock]. */
    private fun flushPendingLocked() {
        if (pendingEntries.isEmpty()) return
        val entries = ArrayList<LogEntry>(pendingEntries.size)
        while (pendingEntries.isNotEmpty()) entries += pendingEntries.removeFirst()
        _logs.value = buffer.snapshot()
        appendPersistedLocked(entries)
    }

    /** Caller holds [lock]. */
    private fun appendPersistedLocked(entries: List<LogEntry>) {
        val file = logFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                entries.forEach { entry ->
                    writer.append(entry.timestamp.toString())
                    writer.append('\t')
                    writer.append(encode(entry.message))
                    writer.newLine()
                }
            }
            persistedLineCount += entries.size
            if (persistedLineCount > MAX_PERSISTED_LOG_LINES + PERSISTED_LOG_PRUNE_BATCH) {
                persistedLineCount = prunePersistedFile(file)
            }
        }
    }

    private fun readPersisted(): List<LogEntry> {
        val file = logFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            val tail = readPersistedTail(file, MAX_PERSISTED_LOG_LINES)
            if (tail.totalLines > MAX_PERSISTED_LOG_LINES) {
                // Repair files written by older versions without ever holding the complete
                // history in memory.
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    tail.lines.forEach { line ->
                        writer.append(line)
                        writer.newLine()
                    }
                }
            }
            val lines = tail.lines
            persistedLineCount = lines.size
            lines
                .mapNotNull { line ->
                    val timestamp = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                    val encoded = line.substringAfter('\t', missingDelimiterValue = "")
                    val message = decode(encoded) ?: return@mapNotNull null
                    if (filterPatterns.any { message.contains(it) }) return@mapNotNull null
                    LogEntry(timestamp, message.take(MAX_MESSAGE_CHARS))
                }
                .takeLast(MAX_LOGS)
        }.getOrDefault(emptyList())
    }

    private data class PersistedTail(
        val lines: List<String>,
        val totalLines: Int
    )

    private fun readPersistedTail(file: File, maxLines: Int): PersistedTail {
        val lines = ArrayDeque<String>()
        var totalLines = 0
        file.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence.forEach { line ->
                totalLines += 1
                if (lines.size >= maxLines) lines.removeFirst()
                lines.addLast(line)
            }
        }
        return PersistedTail(lines = lines.toList(), totalLines = totalLines)
    }

    private fun prunePersistedFile(file: File): Int {
        val keptLines = readPersistedTail(file, MAX_PERSISTED_LOG_LINES).lines
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            keptLines.forEach { line ->
                writer.append(line)
                writer.newLine()
            }
        }
        return keptLines.size
    }

    private fun logFile(): File? {
        val context = appContext ?: return null
        return File(File(context.filesDir, LOG_DIR), LOG_FILE)
    }

    private fun encode(message: String): String =
        Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(encoded: String): String? =
        runCatching { String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
}
