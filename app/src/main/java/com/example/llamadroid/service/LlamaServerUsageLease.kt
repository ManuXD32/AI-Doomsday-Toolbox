package com.example.llamadroid.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Cross-process usage receipts; one atomic file per caller means writers never share a file. */
class LlamaServerUsageStore(context: Context, sessionId: String) {
    private val directory = File(context.applicationContext.filesDir,
        "llama_server_usage/${sessionId.replace(Regex("[^A-Za-z0-9._:-]"), "_")}")

    fun write(token: String, active: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) = guarded {
        require(token.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        check(directory.isDirectory || directory.mkdirs())
        writeLlamaServerMetadata(File(directory, "$token.json"),
            JSONObject().put("active", active).put("elapsedMs", nowMs).toString())
        pruneReceipts(nowMs, token)
    }

    /** Called under the writer gate so pruning cannot erase a concurrent renewed receipt. */
    private fun pruneReceipts(nowMs: Long, currentToken: String) {
        directory.listFiles()?.filter { it.extension == "json" && it.name != "$currentToken.json" }?.forEach { file ->
            runCatching {
                val at = JSONObject(file.readText()).getLong("elapsedMs")
                if (at > nowMs || nowMs - at > RECEIPT_RETENTION_MS) file.delete()
            }
        }
    }

    private fun <T> guarded(block: () -> T): T {
        check(directory.isDirectory || directory.mkdirs())
        val semaphore = gates.getOrPut(directory.absolutePath) { Semaphore(1) }
        semaphore.acquire()
        try {
            return RandomAccessFile(File(directory, ".gate"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        } finally { semaphore.release() }
    }

    /** Acquisition cannot race an idle stop: a new caller waits, then prepares the stopped server. */
    suspend fun <T> guardIdleStop(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val semaphore = gates.getOrPut(directory.absolutePath) { Semaphore(1) }
        semaphore.acquire()
        try {
            RandomAccessFile(File(directory, ".gate"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        } finally { semaphore.release() }
    }

    fun read(nowMs: Long = SystemClock.elapsedRealtime()): LlamaServerUsageSnapshot {
        if (!directory.exists()) return LlamaServerUsageSnapshot()
        val files = directory.listFiles() ?: return LlamaServerUsageSnapshot(readable = false)
        var active = false
        var lastActivity = 0L
        var readable = true
        for (file in files.filter { it.extension == "json" }) {
            try {
                val record = JSONObject(file.readText())
                val at = record.getLong("elapsedMs")
                val age = nowMs - at
                // Old-boot receipts and hour-old completed/expired leases cannot affect this run.
                // Writers prune these metadata receipts while holding the cross-process gate.
                if (age < 0 || age > RECEIPT_RETENTION_MS) {
                    continue
                }
                val live = record.optBoolean("active") && age <= LEASE_TTL_MS
                active = active || live
                val endedAt = if (record.optBoolean("active") && !live) at + LEASE_TTL_MS else at
                lastActivity = maxOf(lastActivity, endedAt)
            } catch (_: Exception) {
                readable = false
            }
        }
        return LlamaServerUsageSnapshot(active, lastActivity, readable)
    }

    companion object {
        const val LEASE_TTL_MS = 60_000L
        private const val RECEIPT_RETENTION_MS = 3_600_000L
        private val gates = ConcurrentHashMap<String, Semaphore>()
    }
}

/** Holds a server through a complete native chat/agent turn, including long tool execution. */
class LlamaServerUsageLease(
    context: Context,
    sessionId: String,
    scope: CoroutineScope
) : Closeable {
    private val store = LlamaServerUsageStore(context, sessionId)
    private val token = UUID.randomUUID().toString()
    private var closed = false
    private val heartbeat: Job

    init {
        store.write(token, true)
        heartbeat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                runCatching { renew() }
            }
        }
    }

    @Synchronized private fun renew() {
        if (!closed) store.write(token, true)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        heartbeat.cancel()
        // A failed final write expires by TTL; do not mask an inference error during cleanup.
        runCatching { store.write(token, false) }
    }
}
