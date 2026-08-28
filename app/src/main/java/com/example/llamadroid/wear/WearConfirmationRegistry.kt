package com.example.llamadroid.wear

import java.util.Collections

/**
 * Tracks the server-start confirmations this process has actually put in front of
 * the user, so an untrusted launcher cannot talk the app into starting a
 * network-facing server.
 *
 * [WearServerStartConfirmationActivity] must stay exported and BROWSABLE for the
 * watch to reach it through `RemoteActivityHelper`, which also lets any app or web
 * page launch it with an arbitrary `requestId`. A confirmation is therefore only
 * honoured when it is:
 *
 * - an id this process minted for a genuinely blocked start ([mark]),
 * - answered at most once, and
 * - answered within [ttlMs].
 *
 * State is intentionally in-memory only. If the process died, the blocked start it
 * belonged to is gone too, and the correct recovery is a fresh request from the
 * watch rather than honouring an id nobody is waiting on.
 */
class WearConfirmationRegistry(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val pending = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > maxEntries
        }
    )

    /** Record that [requestId] has been offered to the user for confirmation. */
    fun mark(requestId: String) {
        if (requestId.isBlank()) return
        pending[requestId] = clock()
    }

    /**
     * Returns true exactly once for a live, unexpired [requestId]. Any unknown,
     * blank, replayed, or expired id returns false and starts nothing.
     */
    fun consume(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        val markedAt = pending.remove(requestId) ?: return false
        return clock() - markedAt <= ttlMs
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
