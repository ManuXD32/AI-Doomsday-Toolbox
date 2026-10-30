package com.example.llamadroid.service.compaction

import androidx.core.content.edit
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Per-chat context gauge state. Metadata only: never any message text. */
data class NativeChatContextUsage(
    val chatId: Long,
    val usedTokens: Int,
    val windowTokens: Int,
    /** True when [usedTokens] came from an exact server count, false when estimated. */
    val measured: Boolean,
    val lastSavedTokens: Int? = null,
    val lastSavedMeasured: Boolean = false,
    val updatedAt: Long
) {
    val percent: Int
        get() = if (windowTokens <= 0) 0
        else ((usedTokens.toLong().coerceAtLeast(0) * 100) / windowTokens).toInt().coerceIn(0, 100)
}

/**
 * Process singleton feeding the chat top-bar context ring. Call [load] once (any entry point
 * with a Context) before observing so persisted values are restored. Each chat has its own
 * StateFlow so collectors only wake for their chat.
 */
object NativeChatContextUsageStore {
    private const val PREFS = "native_chat_context_usage"
    private const val KEY_PREFIX = "c"
    private const val MAX_CHATS = 200

    private val flows = ConcurrentHashMap<Long, MutableStateFlow<NativeChatContextUsage?>>()
    @Volatile private var loaded = false

    private fun flowFor(chatId: Long) = flows.getOrPut(chatId) { MutableStateFlow(null) }

    fun observe(chatId: Long): StateFlow<NativeChatContextUsage?> = flowFor(chatId)

    fun current(chatId: Long): NativeChatContextUsage? = flows[chatId]?.value

    fun record(context: Context, chatId: Long, usedTokens: Int, windowTokens: Int, measured: Boolean) {
        load(context)
        val next = applyRecord(current(chatId), chatId, usedTokens, windowTokens, measured, System.currentTimeMillis())
        publish(context, next)
    }

    fun recordCompaction(context: Context, chatId: Long, savedTokens: Int, measured: Boolean) {
        load(context)
        val next = applyCompaction(current(chatId), chatId, savedTokens, measured, System.currentTimeMillis())
        publish(context, next)
    }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) continue
            val usage = runCatching { fromJson(JSONObject(value)) }.getOrNull() ?: continue
            val flow = flowFor(usage.chatId)
            if (flow.value == null) flow.value = usage
        }
    }

    fun clear(context: Context, chatId: Long) {
        flows[chatId]?.value = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { remove(KEY_PREFIX + chatId) }
    }

    private fun publish(context: Context, usage: NativeChatContextUsage) {
        flowFor(usage.chatId).value = usage
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // LRU cap: drop the least recently updated entries beyond MAX_CHATS.
        val all = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it != KEY_PREFIX + usage.chatId }
        val evicted = if (all.size + 1 > MAX_CHATS) {
            all.map { k ->
                k to (runCatching { JSONObject(prefs.getString(k, "") ?: "").optLong("updatedAt", 0L) }.getOrDefault(0L))
            }.sortedBy { it.second }.take(all.size + 1 - MAX_CHATS).map { it.first }
        } else {
            emptyList()
        }
        prefs.edit {
            putString(KEY_PREFIX + usage.chatId, toJson(usage).toString())
            evicted.forEach { remove(it) }
        }
        evicted.forEach { k -> k.removePrefix(KEY_PREFIX).toLongOrNull()?.let { flows[it]?.value = null } }
    }

    internal fun applyRecord(
        previous: NativeChatContextUsage?, chatId: Long, usedTokens: Int, windowTokens: Int,
        measured: Boolean, now: Long
    ) = NativeChatContextUsage(
        chatId = chatId,
        usedTokens = usedTokens.coerceAtLeast(0),
        windowTokens = windowTokens.coerceAtLeast(0),
        measured = measured,
        lastSavedTokens = previous?.lastSavedTokens,
        lastSavedMeasured = previous?.lastSavedMeasured ?: false,
        updatedAt = now
    )

    internal fun applyCompaction(
        previous: NativeChatContextUsage?, chatId: Long, savedTokens: Int, measured: Boolean, now: Long
    ) = (previous ?: NativeChatContextUsage(chatId, 0, 0, false, updatedAt = now)).copy(
        lastSavedTokens = savedTokens.coerceAtLeast(0),
        lastSavedMeasured = measured,
        updatedAt = now
    )

    private fun toJson(u: NativeChatContextUsage) = JSONObject().apply {
        put("chatId", u.chatId); put("used", u.usedTokens); put("window", u.windowTokens)
        put("measured", u.measured); put("updatedAt", u.updatedAt)
        u.lastSavedTokens?.let { put("saved", it) }
        put("savedMeasured", u.lastSavedMeasured)
    }

    private fun fromJson(o: JSONObject) = NativeChatContextUsage(
        chatId = o.getLong("chatId"),
        usedTokens = o.optInt("used", 0),
        windowTokens = o.optInt("window", 0),
        measured = o.optBoolean("measured", false),
        lastSavedTokens = if (o.has("saved")) o.optInt("saved") else null,
        lastSavedMeasured = o.optBoolean("savedMeasured", false),
        updatedAt = o.optLong("updatedAt", 0L)
    )
}
