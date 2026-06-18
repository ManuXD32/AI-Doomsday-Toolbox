package com.example.llamadroid.util

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class LogEntry(val timestamp: Long, val message: String)

object DebugLog {
    private const val MAX_LOGS = 500
    private const val LOG_DIR = "debug_log"
    private const val LOG_FILE = "app_logs.tsv"

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    
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
        // Skip noisy logs that spam the output
        if (filterPatterns.any { message.contains(it) }) {
            return
        }
        
        val entry = LogEntry(System.currentTimeMillis(), message)
        synchronized(lock) {
            val updated = (_logs.value + entry).takeLast(MAX_LOGS)
            _logs.value = updated
            appendPersisted(entry)
        }
    }

    fun refreshFromDisk() {
        val persisted = readPersisted()
        if (persisted.isEmpty()) return
        synchronized(lock) {
            val merged = (_logs.value + persisted)
                .distinctBy { it.timestamp to it.message }
                .sortedBy { it.timestamp }
                .takeLast(MAX_LOGS)
            if (merged != _logs.value) {
                _logs.value = merged
            }
        }
    }
    
    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
            logFile()?.delete()
        }
    }

    private fun appendPersisted(entry: LogEntry) {
        val file = logFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("${entry.timestamp}\t${encode(entry.message)}\n")
        }
    }

    private fun readPersisted(): List<LogEntry> {
        val file = logFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val timestamp = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                    val encoded = line.substringAfter('\t', missingDelimiterValue = "")
                    val message = decode(encoded) ?: return@mapNotNull null
                    if (filterPatterns.any { message.contains(it) }) return@mapNotNull null
                    LogEntry(timestamp, message)
                }
                .takeLast(MAX_LOGS)
        }.getOrDefault(emptyList())
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
