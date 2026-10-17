package com.example.llamadroid.service.perf

import android.os.Process
import java.io.File

/** Pure helpers for the app-side thread priority, unit-tested without Android. */
object ThreadPriorityMath {
    /** `Process.THREAD_PRIORITY_URGENT_DISPLAY`; also the nice value the readback expects. */
    const val TARGET_NICE = -8

    /**
     * Nice value (field 19) of a `/proc/<pid>/task/<tid>/stat` line. The command name may contain
     * spaces and parentheses, so fields are counted after the last `)`.
     */
    fun niceOf(statLine: String): Int? {
        val tail = statLine.substringAfterLast(')', "").trim().split(' ')
        // tail[0] is the state (field 3), so field 19 is tail[16].
        return tail.getOrNull(16)?.toIntOrNull()
    }

    /**
     * APPLIED when at least 80% of the readable threads are at [target] or better, PARTIAL when only
     * some are, REFUSED when none are. With no readable thread the state stays REQUESTED.
     */
    fun classify(niceValues: Collection<Int>, target: Int = TARGET_NICE): PriorityOutcome {
        val total = niceValues.size
        if (total == 0) return PriorityOutcome(PriorityState.REQUESTED, 0, 0)
        val raised = niceValues.count { it <= target }
        val state = when {
            raised * 5 >= total * 4 -> PriorityState.APPLIED
            raised > 0 -> PriorityState.PARTIAL
            else -> PriorityState.REFUSED
        }
        return PriorityOutcome(state, raised, total)
    }
}

data class PriorityOutcome(val state: PriorityState, val raised: Int, val total: Int)

/**
 * Raises the priority of every thread of the llama-server child from the app side, because the
 * `--prio` flag maps to SCHED_FIFO inside llama.cpp, which Android refuses (it printed a warning per
 * thread). Threads are listed from `/proc/<pid>/task`, raised once each with
 * `Process.setThreadPriority(tid, URGENT_DISPLAY)` and the result is read back from the stat files.
 *
 * Light schedule: a scan at start, one when the server reports ready ([poke]), then every
 * [EARLY_PERIOD_MS] for the first minute and every [LATE_PERIOD_MS] after. Never throws.
 */
class LockedScreenThreadPriority(
    private val pid: Int,
    private val onOutcome: (PriorityOutcome) -> Unit,
    private val procRoot: File = File("/proc"),
    private val setPriority: (Int) -> Boolean = ::setUrgentDisplay
) {
    private val attempted = HashSet<Int>()
    private val wake = Object()
    @Volatile private var closed = false
    @Volatile private var poked = false
    private var thread: Thread? = null

    fun start() {
        val worker = Thread({ loop() }, "adt-perf-prio").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    /** Requests an immediate rescan (called when the server becomes ready). */
    fun poke() {
        synchronized(wake) { poked = true; wake.notifyAll() }
    }

    fun close() {
        closed = true
        poke()
        thread = null
    }

    /** One pass: raise new threads, then read every thread's nice value back. Visible for tests. */
    fun scanOnce(): PriorityOutcome {
        val tasks = File(procRoot, "$pid/task").listFiles().orEmpty()
        val tids = tasks.mapNotNull { it.name.toIntOrNull() }
        tids.filter { attempted.add(it) }.forEach { tid -> runCatching { setPriority(tid) } }
        val nice = tasks.mapNotNull { dir ->
            runCatching { ThreadPriorityMath.niceOf(File(dir, "stat").readText()) }.getOrNull()
        }
        return ThreadPriorityMath.classify(nice)
    }

    private fun loop() {
        var elapsed = 0L
        var last: PriorityOutcome? = null
        try {
            while (!closed) {
                val outcome = scanOnce()
                if (outcome != last) {
                    last = outcome
                    onOutcome(outcome)
                }
                val period = if (elapsed < EARLY_WINDOW_MS) EARLY_PERIOD_MS else LATE_PERIOD_MS
                val startedWait = System.nanoTime()
                synchronized(wake) {
                    if (!poked && !closed) wake.wait(period)
                    poked = false
                }
                elapsed += (System.nanoTime() - startedWait) / 1_000_000L
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (_: Throwable) {
            // Priority is best effort; the readback keeps its last state.
        }
    }

    companion object {
        const val EARLY_PERIOD_MS = 5_000L
        const val EARLY_WINDOW_MS = 60_000L
        const val LATE_PERIOD_MS = 60_000L

        private fun setUrgentDisplay(tid: Int): Boolean = try {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
