package com.example.llamadroid.service.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File

/** Pure helpers for the hint loop, unit-tested without Android. */
object HintSessionMath {
    /**
     * utime + stime (clock ticks) of a `/proc/<pid>/task/<tid>/stat` line. The command name may
     * contain spaces and parentheses, so fields are counted after the last `)`.
     */
    fun cpuTicks(statLine: String): Long? {
        val tail = statLine.substringAfterLast(')', "").trim().split(' ')
        // tail[0] is the state (field 3); utime is field 14 and stime field 15.
        val utime = tail.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = tail.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** Busy time in nanoseconds of the busiest thread between two samples, or 0 when idle. */
    fun busiestThreadNanos(previous: Map<Int, Long>, current: Map<Int, Long>, tickNanos: Long): Long {
        var best = 0L
        current.forEach { (tid, ticks) ->
            val delta = ticks - (previous[tid] ?: ticks)
            if (delta > best) best = delta
        }
        return best * tickNanos
    }
}

/**
 * Registers the llama-server threads with Android's PerformanceHintManager (API 31+).
 *
 * Every [PERIOD_MS] the loop lists the thread ids of the server, reports how long its busiest
 * thread ran during the last window (capped at the window) against a [TARGET_NANOS] target, and
 * recreates the hint session when the thread set changed (threads appear after the model loads).
 * The platform may refuse a session for threads of another process; that is recorded, never fatal.
 */
class LockedScreenHintSession(
    context: Context,
    private val pid: Int,
    private val onState: (HintState, String?) -> Unit,
    private val procRoot: File = File("/proc")
) {
    private val appContext = context.applicationContext

    @Volatile
    private var closed = false
    private var thread: Thread? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onState(HintState.UNSUPPORTED, "api<31")
            return
        }
        val worker = Thread({ loop() }, "adt-perf-hint").apply { isDaemon = true }
        thread = worker
        onState(HintState.PENDING, null)
        worker.start()
    }

    fun close() {
        closed = true
        thread?.interrupt()
        thread = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun loop() {
        var session: PerformanceHintManager.Session? = null
        var sessionTids: Set<Int> = emptySet()
        var lastTicks: Map<Int, Long> = emptyMap()
        var lastNanos = System.nanoTime()
        val tickNanos = runCatching { 1_000_000_000L / Os.sysconf(OsConstants._SC_CLK_TCK) }
            .getOrDefault(10_000_000L)
        try {
            val manager = appContext.getSystemService(PerformanceHintManager::class.java)
            if (manager == null) {
                onState(HintState.REFUSED, "no PerformanceHintManager")
                return
            }
            while (!closed) {
                Thread.sleep(PERIOD_MS)
                val ticks = readTicks()
                if (ticks.isEmpty()) continue
                val tids = ticks.keys
                if (tids != sessionTids) {
                    runCatching { session?.close() }
                    session = null
                    sessionTids = tids
                    try {
                        session = manager.createHintSession(tids.toIntArray(), TARGET_NANOS)
                        if (session == null) {
                            onState(HintState.REFUSED, "createHintSession returned null")
                        } else {
                            onState(HintState.ACCEPTED, "${tids.size} threads")
                        }
                    } catch (t: Throwable) {
                        // Retried only when the thread set changes again.
                        onState(HintState.REFUSED, t.javaClass.simpleName)
                    }
                }
                val now = System.nanoTime()
                val window = now - lastNanos
                lastNanos = now
                val busy = HintSessionMath.busiestThreadNanos(lastTicks, ticks, tickNanos)
                lastTicks = ticks
                val actual = busy.coerceAtMost(window)
                if (actual > 0L) {
                    runCatching { session?.reportActualWorkDuration(actual) }
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (t: Throwable) {
            onState(HintState.REFUSED, t.javaClass.simpleName)
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun readTicks(): Map<Int, Long> {
        val tasks = File(procRoot, "$pid/task").listFiles().orEmpty()
        val out = LinkedHashMap<Int, Long>()
        tasks.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            val ticks = runCatching { HintSessionMath.cpuTicks(File(dir, "stat").readText()) }.getOrNull()
            if (ticks != null) out[tid] = ticks
        }
        return out
    }

    companion object {
        const val PERIOD_MS = 100L
        /** Target per-period work: 50 ms of a 100 ms window marks the work as demanding. */
        const val TARGET_NANOS = 50_000_000L
    }
}
