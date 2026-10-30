package com.example.llamadroid.service

/** Metadata only: receipt timestamps and bounded counters, never prompts or tool arguments. */
data class LlamaServerUsageSnapshot(
    val active: Boolean = false,
    val lastActivityMs: Long = 0,
    val readable: Boolean = true
)

data class LlamaServerIdleObservation(val busy: Boolean, val fingerprint: String)
data class LlamaServerIdleCheckpoint(val activityAtMs: Long, val fingerprint: String?)

/** Recorded native children survive only within the same boot; reject a future/legacy clock value. */
internal fun restoredLlamaIdleActivityAt(savedMs: Long?, nowMs: Long): Long =
    savedMs?.takeIf { it in 0..nowMs } ?: nowMs

/** Monotonic-clock policy kept independent of Android for boundary and race regression tests. */
class LlamaServerIdlePolicy(
    private val timeoutMs: Long,
    readyAtMs: Long,
    previousFingerprint: String? = null
) {
    private var lastActivityMs = readyAtMs
    private var fingerprint: String? = previousFingerprint

    @Synchronized fun checkpoint(): LlamaServerIdleCheckpoint = LlamaServerIdleCheckpoint(lastActivityMs, fingerprint)

    @Synchronized fun shouldStop(
        nowMs: Long,
        usage: LlamaServerUsageSnapshot,
        observation: LlamaServerIdleObservation?
    ): Boolean {
        lastActivityMs = maxOf(lastActivityMs, usage.lastActivityMs.coerceAtMost(nowMs))
        if (usage.active || observation?.busy == true) lastActivityMs = nowMs
        if (observation != null) {
            if (fingerprint != null && fingerprint != observation.fingerprint) lastActivityMs = nowMs
            fingerprint = observation.fingerprint
        }
        // An unreadable lease or unsupported/broken activity probe is not proof of inactivity.
        return timeoutMs > 0 && usage.readable && !usage.active && observation != null &&
            !observation.busy && nowMs - lastActivityMs > timeoutMs
    }
}
