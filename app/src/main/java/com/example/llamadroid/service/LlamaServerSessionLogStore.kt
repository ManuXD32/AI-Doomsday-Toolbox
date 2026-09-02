package com.example.llamadroid.service

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable per-session native output. It is intentionally separate from DebugLog/general logs.
 * Appends are batched by the runtime and do not read/rewrite the existing file for every line.
 * Compaction is performed only after the bounded tail has been exceeded. Deleting a card is the
 * only caller that should invoke [delete]. Stopping/restarting a session preserves its history.
 */
class LlamaServerSessionLogStore(context: Context) {
    private data class SessionState(
        val tail: ArrayDeque<String>,
        var fileLength: Long,
        var lastModified: Long,
        var lineCount: Int
    )

    private val directory = File(context.applicationContext.filesDir, "llama_server_session_logs")
    private val lock = Any()
    private val states = mutableMapOf<String, SessionState>()

    init {
        directory.mkdirs()
    }

    fun read(sessionId: String): List<String> = synchronized(lock) {
        val file = fileFor(sessionId)
        stateFor(file).tail.toList()
    }

    fun append(sessionId: String, line: String) {
        appendBatch(sessionId, listOf(line))
    }

    /** Append a bounded batch without the old read-all/rewrite-per-line behaviour. */
    fun appendBatch(sessionId: String, lines: List<String>) = synchronized(lock) {
        if (lines.isEmpty()) return@synchronized
        val file = fileFor(sessionId)
        file.parentFile?.mkdirs()
        val state = stateFor(file)
        val cleanLines = lines.mapNotNull(::sanitizeLine)
        if (cleanLines.isEmpty()) return@synchronized

        FileOutputStream(file, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            cleanLines.forEach { line ->
                writer.append(line).append('\n')
            }
        }

        cleanLines.forEach { state.tail.addLast(it) }
        state.lineCount = (state.lineCount + cleanLines.size).coerceAtMost(COMPACTION_LINES + 1)
        trimTailInPlace(state.tail)
        state.fileLength = file.length()
        state.lastModified = file.lastModified()

        // Keep the on-disk artifact bounded after crossing either limit. This runs at most once
        // per threshold crossing instead of once per native output line.
        if (state.fileLength > MAX_BYTES || state.lineCount > MAX_LINES) {
            compact(file, state)
        }
    }

    fun clear(sessionId: String) {
        synchronized(lock) {
            val file = fileFor(sessionId)
            file.delete()
            states.remove(file.path)
        }
    }

    fun delete(sessionId: String) {
        clear(sessionId)
    }

    fun lineCount(sessionId: String): Int = read(sessionId).size

    private fun stateFor(file: File): SessionState {
        val length = file.takeIf(File::isFile)?.length() ?: 0L
        val modified = file.takeIf(File::isFile)?.lastModified() ?: 0L
        val key = file.path
        val cached = states[key]
        if (cached != null && cached.fileLength == length && cached.lastModified == modified) {
            return cached
        }

        val loaded = loadState(file, length, modified)
        states[key] = loaded
        return loaded
    }

    private fun loadState(file: File, length: Long, modified: Long): SessionState {
        if (!file.isFile || length <= 0L) {
            return SessionState(ArrayDeque(), length, modified, 0)
        }

        // A stale file from an older build may be larger than the current cap. Read only a bounded
        // suffix; the first partial line is discarded when the suffix starts mid-file.
        val start = (length - READ_WINDOW_BYTES).coerceAtLeast(0L)
        val bytes = FileInputStream(file).use { input ->
            var remaining = start
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) break
                remaining -= skipped
            }
            val bounded = ByteArray(READ_WINDOW_BYTES.toInt())
            var count = 0
            while (count < bounded.size) {
                val read = input.read(bounded, count, bounded.size - count)
                if (read <= 0) break
                count += read
            }
            bounded.copyOf(count)
        }
        val decoded = bytes.toString(StandardCharsets.UTF_8)
        val rawLines = decoded.split('\n').toMutableList()
        if (start > 0L && rawLines.isNotEmpty()) rawLines.removeAt(0)
        if (rawLines.isNotEmpty() && rawLines.lastOrNull().isNullOrEmpty()) rawLines.removeAt(rawLines.lastIndex)
        val lines = rawLines.mapNotNull(::sanitizeLine)
        val tail = ArrayDeque(trimToMaxBytes(lines.takeLast(MAX_LINES)))
        val hasOlderLines = start > 0L || lines.size > MAX_LINES
        val lineCount = if (hasOlderLines) COMPACTION_LINES + 1 else lines.size
        return SessionState(tail, length, modified, lineCount)
    }

    private fun compact(file: File, state: SessionState) {
        val bounded = trimToMaxBytes(state.tail.toList())
        val temporary = File.createTempFile("${file.nameWithoutExtension}-", ".tmp", directory)
        try {
            FileOutputStream(temporary).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                bounded.forEach { line -> writer.append(line).append('\n') }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            state.tail.clear()
            state.tail.addAll(bounded)
            state.lineCount = state.tail.size
            state.fileLength = file.length()
            state.lastModified = file.lastModified()
        } finally {
            temporary.delete()
        }
    }

    private fun trimTailInPlace(tail: ArrayDeque<String>) {
        while (tail.size > MAX_LINES) tail.removeFirst()
        val bounded = trimToMaxBytes(tail.toList())
        tail.clear()
        tail.addAll(bounded)
    }

    private fun trimToMaxBytes(lines: List<String>): List<String> {
        var bytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            if (bytes >= MAX_BYTES) break
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
            val available = MAX_BYTES - bytes - 1
            if (lineBytes > MAX_BYTES || bytes + lineBytes > MAX_BYTES) {
                // Keep the newest line's UTF-8-safe tail when it is too large; older lines must
                // not displace newer output.
                if (kept.isEmpty() && available > 0) {
                    kept.addFirst(utf8Suffix(line, available))
                }
                break
            }
            kept.addFirst(line)
            bytes += lineBytes
        }
        return kept.toList()
    }

    private fun utf8Suffix(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var start = bytes.size - maxBytes
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return bytes.copyOfRange(start, bytes.size).toString(StandardCharsets.UTF_8)
    }

    private fun sanitizeLine(raw: String): String? {
        val clean = raw.replace("\r", "").replace("\u0000", "")
        return utf8Prefix(clean, MAX_LINE_BYTES).takeIf { it.isNotEmpty() }
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val next = if (
                value[index].isHighSurrogate() &&
                index + 1 < value.length &&
                value[index + 1].isLowSurrogate()
            ) {
                value.substring(index, index + 2)
            } else {
                value[index].toString()
            }
            val nextBytes = next.toByteArray(StandardCharsets.UTF_8).size
            if (used + nextBytes > maxBytes) break
            result.append(next)
            used += nextBytes
            index += next.length
        }
        return result.toString()
    }

    private fun fileFor(sessionId: String): File {
        val safe = sessionId
            .replace(UNSAFE_SESSION_CHARS, "_")
            .take(MAX_SESSION_ID_LENGTH)
            .ifBlank { "session" }
        return File(directory, "$safe.log")
    }

    private companion object {
        const val MAX_LINES = 2_000
        const val COMPACTION_LINES = MAX_LINES * 2
        const val MAX_BYTES = 1024 * 1024
        const val READ_WINDOW_BYTES = MAX_BYTES * 2L
        const val MAX_LINE_BYTES = 16 * 1024
        const val MAX_SESSION_ID_LENGTH = 160
        val UNSAFE_SESSION_CHARS = Regex("[^A-Za-z0-9._:-]")
    }
}
