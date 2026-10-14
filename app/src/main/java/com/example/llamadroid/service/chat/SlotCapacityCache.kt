package com.example.llamadroid.service.chat

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-endpoint slot capacity (llama-server `/props` -> `total_slots`) cached for about ten
 * minutes; a failed probe is cached briefly so an unreachable server is not hammered.
 */
class SlotCapacityCache(
    private val fetch: suspend (baseUrl: String) -> Int?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val failureTtlMs: Long = FAILURE_TTL_MS
) {
    private data class Entry(val slots: Int?, val expiresAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Slot count of [endpointKey] or null when unknown. */
    suspend fun capacity(endpointKey: String, baseUrl: String): Int? {
        val now = nowMs()
        entries[endpointKey]?.takeIf { it.expiresAtMs > now }?.let { return it.slots }
        val slots = runCatching { fetch(baseUrl) }.getOrNull()?.takeIf { it > 0 }
        entries[endpointKey] = Entry(slots, now + if (slots != null) ttlMs else failureTtlMs)
        return slots
    }

    fun invalidate(endpointKey: String) {
        entries.remove(endpointKey)
    }

    companion object {
        const val TTL_MS = 10L * 60L * 1000L
        const val FAILURE_TTL_MS = 30L * 1000L

        /** `/props` body -> `total_slots`, or null when absent/invalid. */
        fun parseTotalSlots(json: String): Int? = runCatching {
            JSONObject(json).optInt("total_slots", 0).takeIf { it > 0 }
        }.getOrNull()
    }
}
