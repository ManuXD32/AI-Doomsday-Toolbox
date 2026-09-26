package com.example.llamadroid.util

/** Monotonic cadence for nonterminal transfer and persistence updates. */
internal class DownloadProgressPolicy(
    private val intervalMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var lastUpdateMs: Long? = null

    fun shouldUpdate(force: Boolean = false): Boolean {
        val now = nowMs()
        val previous = lastUpdateMs
        if (force || previous == null || now - previous >= intervalMs || now < previous) {
            lastUpdateMs = now
            return true
        }
        return false
    }
}
