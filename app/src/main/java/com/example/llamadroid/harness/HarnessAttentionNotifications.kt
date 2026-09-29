package com.example.llamadroid.harness

import android.content.Context
import android.util.AtomicFile
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.ui.agent.harness.HarnessAttentionNotice
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable dedupe stores hashes/times only; request content remains in the live Harness store. */
internal class HarnessAttentionNotifications(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val file = AtomicFile(com.example.llamadroid.harness.runtime.HarnessRuntimeScope.dataFile(context, "agent_harness/attention-notifications.json"))
    private val lock = Mutex()
    private var delivered: LinkedHashMap<String, Long>? = null

    suspend fun publish(notice: HarnessAttentionNotice) = withContext(Dispatchers.IO) {
        val mapping = database.harnessDao(com.example.llamadroid.harness.runtime.HarnessRuntimeScope.id(context)).session(notice.sessionId)
        val destination = when (notice.kind) {
            "plan" -> "plan"
            "question", "approval" -> "requests"
            else -> "conversation"
        }
        val route = mapping?.let { Screen.Agent.createRoute(it.conversationId, destination) } ?: Screen.Agent.route
        val key = attentionNotificationKey(notice.sessionId, notice.key, notice.kind, com.example.llamadroid.harness.runtime.HarnessRuntimeScope.id(context))
        lock.withLock {
            val known = delivered ?: read().also { delivered = it }
            if (key in known) return@withLock
            if (!UnifiedNotificationManager.showHarnessAttention(key, notice.kind, route)) return@withLock
            known[key] = System.currentTimeMillis()
            while (known.size > 4096) known.remove(known.keys.first())
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                val json = JSONObject()
                known.forEach { (id, time) -> json.put(id, time) }
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    private fun read(): LinkedHashMap<String, Long> = runCatching {
        val bytes = file.openRead().use { it.readBounded(512 * 1024) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        json.keys().asSequence().filter { it.matches(Regex("[a-f0-9]{64}")) }
            .map { it to json.optLong(it) }.sortedBy { it.second }.toList().takeLast(4096)
            .toMap(LinkedHashMap())
    }.getOrDefault(LinkedHashMap())
}

internal fun attentionNotificationKey(session: String, event: String, kind: String, runtimeId: String = com.example.llamadroid.harness.runtime.HarnessRuntimeScope.LEGACY_RUNTIME_ID): String =
    MessageDigest.getInstance("SHA-256").digest("$runtimeId\u0000$session\u0000$event\u0000$kind".toByteArray())
        .joinToString("") { "%02x".format(it) }
