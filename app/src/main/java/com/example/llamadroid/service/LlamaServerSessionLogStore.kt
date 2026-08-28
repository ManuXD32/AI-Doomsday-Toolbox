package com.example.llamadroid.service

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Durable per-session native output. It is intentionally separate from DebugLog/general logs.
 * Every write is bounded to 2,000 lines and 1 MiB, and deleting a card is the only caller that
 * should invoke [delete]. Stopping/restarting a session preserves its history.
 */
class LlamaServerSessionLogStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "llama_server_session_logs")
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    fun read(sessionId: String): List<String> = synchronized(lock) {
        fileFor(sessionId).takeIf(File::isFile)?.readLines(Charsets.UTF_8)
            ?.takeLast(MAX_LINES)
            ?.let(::trimToMaxBytes)
            .orEmpty()
    }

    fun append(sessionId: String, line: String) = synchronized(lock) {
        val file = fileFor(sessionId)
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()
        val clean = line.replace("\r", "").replace("\u0000", "")
        val next = (existing + clean).takeLast(MAX_LINES)
        val bounded = trimToMaxBytes(next)
        file.writeText(bounded.joinToString(separator = "\n", postfix = if (bounded.isNotEmpty()) "\n" else ""), Charsets.UTF_8)
    }

    fun clear(sessionId: String) = synchronized(lock) {
        fileFor(sessionId).delete()
    }

    fun delete(sessionId: String) = clear(sessionId)

    fun lineCount(sessionId: String): Int = read(sessionId).size

    private fun trimToMaxBytes(lines: List<String>): List<String> {
        var bytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            if (bytes >= MAX_BYTES) break
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
            val available = MAX_BYTES - bytes - 1
            if (lineBytes > MAX_BYTES || bytes + lineBytes > MAX_BYTES) {
                // Keep the newest line's tail when it is too large; once newer lines have
                // already been retained, older lines must not displace them.
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
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var start = bytes.size - maxBytes
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return bytes.copyOfRange(start, bytes.size).toString(StandardCharsets.UTF_8)
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
        const val MAX_BYTES = 1024 * 1024
        const val MAX_SESSION_ID_LENGTH = 160
        val UNSAFE_SESSION_CHARS = Regex("[^A-Za-z0-9._:-]")
    }
}
