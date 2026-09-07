package com.example.llamadroid.util

import android.app.ActivityManager
import android.content.Context
import android.os.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * The small memory sample needed by worker controls and the foreground notification.
 *
 * Keeping this shape separate from [SystemMonitor] avoids opening /proc, sysfs, thermal,
 * battery, and network nodes for a card which only needs ActivityManager memory counters.
 */
data class MemoryTelemetrySnapshot(
    val sampledAtEpochMs: Long,
    val totalBytes: Long,
    val availableBytes: Long
) {
    val totalMiB: Long
        get() = totalBytes.coerceAtLeast(0L) / MIB_BYTES

    val availableMiB: Long
        get() = availableBytes.coerceAtLeast(0L) / MIB_BYTES

    val totalRamGb: Float
        get() = totalBytes.coerceAtLeast(0L) / GB_BYTES.toFloat()

    val freeRamGb: Float
        get() = availableBytes.coerceAtLeast(0L) / GB_BYTES.toFloat()

    companion object {
        const val MIB_BYTES: Long = 1024L * 1024L
        const val GB_BYTES: Long = 1024L * 1024L * 1024L

        val Empty = MemoryTelemetrySnapshot(
            sampledAtEpochMs = 0L,
            totalBytes = 0L,
            availableBytes = 0L
        )
    }
}

/** A tiny injectable seam for deterministic worker telemetry tests. */
fun interface MemoryTelemetrySampler {
    fun sample(nowEpochMs: Long): MemoryTelemetrySnapshot
}

/** Reads only ActivityManager memory counters. It performs no disk or shell access. */
class ActivityManagerMemoryTelemetrySampler(context: Context) : MemoryTelemetrySampler {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun sample(nowEpochMs: Long): MemoryTelemetrySnapshot {
        Trace.beginSection("WorkerMemory.sample")
        return try {
            val info = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(info)
            MemoryTelemetrySnapshot(nowEpochMs, info.totalMem, info.availMem)
        } finally {
            Trace.endSection()
        }
    }
}

/**
 * Shared worker memory stream. The upstream sampler is started only while somebody observes it,
 * runs on IO, emits immediately, and refreshes at the worker's two-second freshness boundary.
 * The singleton is intentionally process-local; no telemetry is persisted.
 */
object MemoryTelemetry {
    const val SAMPLE_INTERVAL_MS: Long = 2_000L
    const val STOP_TIMEOUT_MS: Long = 5_000L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shared: StateFlow<MemoryTelemetrySnapshot>? = null

    fun observe(context: Context): StateFlow<MemoryTelemetrySnapshot> = synchronized(lock) {
        shared ?: createFlow(
            sampler = ActivityManagerMemoryTelemetrySampler(context.applicationContext),
            scope = scope
        ).also { shared = it }
    }

    /**
     * Test seam for the exact WhileSubscribed/IO contract. Production callers should use
     * [observe], which reuses one stream for the worker screen and notification.
     */
    internal fun createFlow(
        sampler: MemoryTelemetrySampler,
        scope: CoroutineScope,
        intervalMs: Long = SAMPLE_INTERVAL_MS,
        started: SharingStarted = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)
    ): StateFlow<MemoryTelemetrySnapshot> {
        val source: Flow<MemoryTelemetrySnapshot> = flow {
            while (true) {
                val sample = try {
                    sampler.sample(System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A temporary ActivityManager/Binder failure must not terminate the shared stream.
                    MemoryTelemetrySnapshot.Empty
                }
                emit(sample)
                delay(intervalMs.coerceAtLeast(1L))
            }
        }.flowOn(Dispatchers.IO)
        return source.stateIn(scope, started, MemoryTelemetrySnapshot.Empty)
    }
}
