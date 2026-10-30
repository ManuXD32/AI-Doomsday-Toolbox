package com.example.llamadroid.service.delegation

import org.json.JSONArray

/** Pure helpers that assemble the per-thread live list shown in the viewer's Harness tab. */
object DelegationThreadLives {
    /** (sessionId, title) pairs from `harnessThreadsJson` plus [currentSession], distinct, in order. */
    fun parseThreads(threadsJson: String?, currentSession: String?, fallbackTitle: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        if (!threadsJson.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(threadsJson)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    result.putIfAbsent(id, o.optString("title").ifBlank { fallbackTitle })
                }
            }
        }
        currentSession?.takeIf { it.isNotBlank() }?.let { result.putIfAbsent(it, fallbackTitle) }
        return result.map { it.key to it.value }
    }

    /** Builds the live list: [digests] gives running/context, [progress] maps session to (processed, total). */
    fun assemble(
        threads: List<Pair<String, String>>,
        digests: Map<String, HarnessTaskDigest>,
        progress: Map<String, Pair<Int, Int>> = emptyMap()
    ): List<DelegationThreadLive> = threads.map { (id, title) ->
        val digest = digests[id]
        val (processed, total) = progress[id] ?: (0 to 0)
        DelegationThreadLive(
            sessionId = id,
            title = title,
            running = digest?.running == true,
            promptProcessed = processed,
            promptTotal = total,
            contextUsed = digest?.ctxUsedTokens?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            contextWindow = digest?.ctxWindowTokens?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            lastSeq = digest?.lastSeq ?: -1L
        )
    }

    /** Thread list for the viewer: persisted threads, overlaid with [live] data when present. */
    fun forViewer(
        threadsJson: String?,
        currentSession: String?,
        fallbackTitle: String,
        live: List<DelegationThreadLive>,
        knownSeqs: Map<String, Long> = emptyMap()
    ): List<DelegationThreadLive> {
        val liveById = live.associateBy { it.sessionId }
        val persisted = parseThreads(threadsJson, currentSession, fallbackTitle)
        val merged = persisted.map { (id, title) ->
            val remembered = knownSeqs[id] ?: -1L
            liveById[id]?.let { row ->
                row.copy(
                    title = row.title.ifBlank { title },
                    lastSeq = maxOf(row.lastSeq, remembered)
                )
            } ?: DelegationThreadLive(id, title, running = false, lastSeq = remembered)
        }
        val known = persisted.map { it.first }.toSet()
        return merged + live.filter { it.sessionId !in known }
    }

    /** Used/window percent (0..100), or null when either value is unknown. */
    fun contextPercent(used: Int, window: Int): Int? =
        if (window <= 0 || used < 0) null else ((used.toLong() * 100L) / window).toInt().coerceIn(0, 100)
}
