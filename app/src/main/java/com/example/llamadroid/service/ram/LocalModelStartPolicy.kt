package com.example.llamadroid.service.ram

/** A running local model session that could be stopped to free RAM. */
data class LocalModelCandidate(
    val sessionId: String,
    val residentBytes: Long,
    val idle: Boolean,
    /** SystemClock.elapsedRealtime() of the last activity; smaller = less recently active. */
    val lastActivityElapsedMs: Long
)

sealed interface LocalModelStartPlan {
    /** The model fits as-is. */
    data object Fits : LocalModelStartPlan

    /** Stop these idle sessions (least recently active first) and then start. */
    data class StopThenStart(val stop: List<LocalModelCandidate>) : LocalModelStartPlan

    /** Even after stopping every idle session the model does not fit. */
    data class Refuse(val neededBytes: Long, val availableAfterStopsBytes: Long) : LocalModelStartPlan
}

/** Pure decision: which idle local models to evict so a new one can start. */
object LocalModelStartPolicy {
    fun plan(
        neededBytes: Long,
        availBytes: Long,
        lowMemory: Boolean,
        candidates: List<LocalModelCandidate>
    ): LocalModelStartPlan {
        if (!lowMemory && neededBytes <= availBytes) return LocalModelStartPlan.Fits
        val idle = candidates.filter { it.idle }.sortedBy { it.lastActivityElapsedMs }
        val stops = mutableListOf<LocalModelCandidate>()
        var avail = availBytes
        for (candidate in idle) {
            if (avail >= neededBytes && stops.isNotEmpty()) break
            stops += candidate
            avail += candidate.residentBytes.coerceAtLeast(0L)
            if (avail >= neededBytes) break
        }
        return if (avail >= neededBytes && stops.isNotEmpty()) {
            LocalModelStartPlan.StopThenStart(stops)
        } else {
            LocalModelStartPlan.Refuse(neededBytes, avail)
        }
    }
}

/**
 * Best-effort "last activity" on the SystemClock.elapsedRealtime() axis. Takes the newest of the
 * runtime's idle checkpoint, the usage receipts and the snapshot's wall-clock `updatedAt` converted
 * to elapsed time, so a missing checkpoint never makes an active session look ancient.
 */
fun estimateLastActivityElapsedMs(
    idleActivityAtElapsedMs: Long?,
    usageLastActivityMs: Long,
    updatedAtWallMs: Long,
    nowWallMs: Long,
    nowElapsedMs: Long
): Long {
    val fromUpdatedAt = nowElapsedMs - (nowWallMs - updatedAtWallMs).coerceAtLeast(0L)
    return maxOf(idleActivityAtElapsedMs ?: Long.MIN_VALUE, usageLastActivityMs, fromUpdatedAt)
}
