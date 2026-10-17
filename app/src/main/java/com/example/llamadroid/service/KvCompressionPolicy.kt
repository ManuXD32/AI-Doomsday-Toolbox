package com.example.llamadroid.service

import android.content.Context
import androidx.core.content.edit

/**
 * Pure rules for when an idle KV cache file is gzipped. A cache idle for at least [FAST_IDLE_MS]
 * is compressed when unpacking it later is estimated to take at most [MAX_UNPACK_MS]; every other
 * cache waits for [SLOW_IDLE_MS]. The estimate is `size / measured unpack throughput`.
 */
internal object KvCompressionPolicy {
    const val FAST_IDLE_MS = 60L * 60 * 1000
    const val SLOW_IDLE_MS = 24L * 60 * 60 * 1000
    const val MAX_UNPACK_MS = 3_000L

    /** Assumed unpack throughput (150 MB/s) until the first real unpack is measured. */
    const val DEFAULT_BYTES_PER_MS = 150_000.0

    /** Unpacks smaller than this are too noisy to update the throughput average. */
    const val MIN_SAMPLE_BYTES = 4L * 1024 * 1024

    const val EMA_ALPHA = 0.3

    fun estimateUnpackMs(bytes: Long, bytesPerMs: Double): Long {
        val speed = bytesPerMs.takeIf { it > 0.0 } ?: DEFAULT_BYTES_PER_MS
        return (bytes.coerceAtLeast(0L) / speed).toLong()
    }

    /** Idle time a cache of [bytes] must reach before it is compressed. */
    fun requiredIdleMs(bytes: Long, bytesPerMs: Double): Long =
        if (estimateUnpackMs(bytes, bytesPerMs) <= MAX_UNPACK_MS) FAST_IDLE_MS else SLOW_IDLE_MS

    /** True when a cache idle for [idleMs] should be compressed now; [ignoreIdle] is "Compress now". */
    fun isDue(idleMs: Long, bytes: Long, bytesPerMs: Double, ignoreIdle: Boolean = false): Boolean =
        ignoreIdle || idleMs >= requiredIdleMs(bytes, bytesPerMs)

    /**
     * Exponential moving average of the unpack throughput in bytes per millisecond. Returns
     * [previous] unchanged when the sample is too small or has no elapsed time.
     */
    fun updatedSpeed(previous: Double?, bytes: Long, elapsedMs: Long): Double? {
        if (bytes < MIN_SAMPLE_BYTES || elapsedMs <= 0L) return previous
        val sample = bytes.toDouble() / elapsedMs.toDouble()
        return if (previous == null || previous <= 0.0) sample else previous + EMA_ALPHA * (sample - previous)
    }
}

/** Persists the measured unpack throughput (bytes per millisecond) as an exponential average. */
class KvUnpackSpeedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Measured throughput in bytes per millisecond, or null before the first measurement. */
    fun measuredBytesPerMs(): Double? = prefs.getString(KEY, null)?.toDoubleOrNull()?.takeIf { it > 0.0 }

    /** Throughput used for estimates: measured, else the 150 MB/s assumption. */
    fun effectiveBytesPerMs(): Double = measuredBytesPerMs() ?: KvCompressionPolicy.DEFAULT_BYTES_PER_MS

    fun record(bytes: Long, elapsedMs: Long) {
        synchronized(LOCK) {
            val updated = KvCompressionPolicy.updatedSpeed(measuredBytesPerMs(), bytes, elapsedMs) ?: return
            prefs.edit { putString(KEY, updated.toString()) }
        }
    }

    private companion object {
        const val PREFS = "kv_cache_unpack_speed"
        const val KEY = "bytes_per_ms"
        val LOCK = Any()
    }
}

/** Progress of a "Compress now" run. */
data class KvCompressProgress(val done: Int, val total: Int)

/** Result of a "Compress now" run. */
data class KvCompressResult(val compressed: Int, val savedBytes: Long)
