package com.example.llamadroid.util

/**
 * Pure conversions used by the rootless /proc and /sys probes.
 *
 * Keeping these conversions independent from Android makes malformed, missing,
 * vendor-specific, and permission-denied nodes testable without requiring a
 * device or root access.
 */
internal object RootlessStatsParsing {
    private val whitespace = Regex("\\s+")

    data class ZramValues(
        val originalBytes: Long?,
        val compressedBytes: Long?,
        val memoryBytes: Long?,
        val memoryLimitBytes: Long?,
        val memoryUsedMaxBytes: Long?
    )

    fun firstLong(text: String?): Long? = text
        ?.trim()
        ?.split(whitespace)
        ?.firstOrNull()
        ?.toLongOrNull()

    fun cpuUsagePercent(
        previousTotal: Long,
        previousIdle: Long,
        currentTotal: Long,
        currentIdle: Long
    ): Float? {
        val totalDelta = currentTotal - previousTotal
        val idleDelta = currentIdle - previousIdle
        if (totalDelta <= 0L || idleDelta < 0L) return null
        return (((totalDelta - idleDelta).toDouble() * 100.0) / totalDelta.toDouble())
            .toFloat()
            .coerceIn(0f, 100f)
    }

    /** Converts cumulative scheduler runtime (nanoseconds) to a wall-clock percentage. */
    fun runtimeUsagePercent(
        previousRuntimeNs: Long,
        currentRuntimeNs: Long,
        elapsedNs: Long
    ): Float? {
        val runtimeDelta = currentRuntimeNs - previousRuntimeNs
        if (elapsedNs <= 0L || runtimeDelta < 0L) return null
        // A small accounting overshoot is possible around wakeups. Larger jumps
        // indicate a reset or overlapping vendor counter and must be rejected.
        if (runtimeDelta > elapsedNs * 1.35) return null
        return (runtimeDelta.toDouble() * 100.0 / elapsedNs.toDouble())
            .toFloat()
            .coerceIn(0f, 100f)
    }

    /** Converts cumulative CPU idle residency (microseconds) to activity percentage. */
    fun idleUsagePercent(
        previousIdleUs: Long,
        currentIdleUs: Long,
        elapsedUs: Long
    ): Float? {
        val idleDelta = currentIdleUs - previousIdleUs
        if (elapsedUs <= 0L || idleDelta < 0L || idleDelta > elapsedUs * 1.35) return null
        return (100.0 - idleDelta.toDouble() * 100.0 / elapsedUs.toDouble())
            .toFloat()
            .coerceIn(0f, 100f)
    }

    fun frequencyMHz(raw: Long): Long? {
        if (raw <= 0L) return null
        return if (raw > 100_000L) raw / 1_000L else raw
    }

    fun temperatureCelsius(raw: Long): Float? = when {
        raw in -200_000L..200_000L -> raw / 1000f
        raw in -200_000_000L..200_000_000L -> raw / 1_000_000f
        else -> null
    }

    fun currentMilliAmpsFromMicroAmps(raw: Long): Float? =
        raw.takeUnless { it == Long.MIN_VALUE }?.div(1000f)

    fun ioRatePerSecond(previous: Long, current: Long, elapsedMs: Long): Long? {
        if (elapsedMs <= 0L || current < previous) return null
        return ((current - previous).toDouble() * 1000.0 / elapsedMs.toDouble()).toLong()
    }

    fun swapBytesFromKilobytes(value: Long?): Long? = value?.let {
        it.takeIf { candidate -> candidate >= 0L }
            ?.let { candidate -> runCatching { Math.multiplyExact(candidate, 1024L) }.getOrNull() }
    }

    fun parseZramMmStat(text: String?): ZramValues? {
        val fields = text?.trim()?.split(whitespace)?.map { it.toLongOrNull() }.orEmpty()
        if (fields.none { it != null }) return null
        return ZramValues(
            originalBytes = fields.getOrNull(0)?.takeIf { it >= 0L },
            compressedBytes = fields.getOrNull(1)?.takeIf { it >= 0L },
            memoryBytes = fields.getOrNull(2)?.takeIf { it >= 0L },
            memoryLimitBytes = fields.getOrNull(3)?.takeIf { it >= 0L },
            memoryUsedMaxBytes = fields.getOrNull(4)?.takeIf { it >= 0L }
        )
    }

    fun kgslMemoryBytes(pageAllocated: Long?, coherent: Long?, fallback: List<Long?>): Long? {
        if (pageAllocated != null || coherent != null) {
            return runCatching { Math.addExact(pageAllocated ?: 0L, coherent ?: 0L) }.getOrNull()
        }
        return fallback.firstOrNull { it != null && it >= 0L }
    }
}
