package com.example.llamadroid.tama.shimeji

/** Persisted per-day presence accounting. [dayKey] is any stable per-day value (e.g. epoch day). */
data class ShimejiPresenceState(
    val dayKey: Long,
    val minutesCredited: Int,
    val carryMs: Long
)

data class ShimejiPresenceResult(
    val happinessDelta: Int,
    val newState: ShimejiPresenceState
)

/** Pure accounting: +1 happiness per 10 visible minutes, max +12 per day, nothing while screen off. */
object ShimejiPresence {
    const val MINUTES_PER_POINT = 10
    const val MAX_POINTS_PER_DAY = 12
    private const val MS_PER_POINT = MINUTES_PER_POINT * 60_000L
    private const val MAX_MINUTES_PER_DAY = MINUTES_PER_POINT * MAX_POINTS_PER_DAY

    fun accrue(
        visibleMs: Long,
        screenOn: Boolean,
        dayKey: Long,
        stored: ShimejiPresenceState?
    ): ShimejiPresenceResult {
        val base = if (stored == null || stored.dayKey != dayKey) {
            ShimejiPresenceState(dayKey, 0, 0L)
        } else {
            stored
        }
        if (!screenOn || visibleMs <= 0L) return ShimejiPresenceResult(0, base)
        if (base.minutesCredited >= MAX_MINUTES_PER_DAY) {
            return ShimejiPresenceResult(0, base.copy(carryMs = 0L))
        }
        val total = base.carryMs.coerceAtLeast(0L) + visibleMs
        val earned = (total / MS_PER_POINT).toInt()
        val room = (MAX_MINUTES_PER_DAY - base.minutesCredited) / MINUTES_PER_POINT
        val delta = minOf(earned, room)
        val credited = base.minutesCredited + delta * MINUTES_PER_POINT
        val carry = if (credited >= MAX_MINUTES_PER_DAY) 0L else total % MS_PER_POINT
        return ShimejiPresenceResult(delta, ShimejiPresenceState(dayKey, credited, carry))
    }
}
