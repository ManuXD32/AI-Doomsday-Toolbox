package com.example.llamadroid.service.perf

/** Why `--threads` was (not) changed. Stored by name in the report, so keep the names stable. */
enum class ThreadFitReason { DISABLED, UNKNOWN_CPUSET, NOT_IN_COMMAND, FITS, REDUCED }

data class ThreadFitDecision(
    val configured: Int,
    val effective: Int,
    /** Number of CPUs of the foreground cpuset, or null when it could not be read. */
    val grantedCpus: Int?,
    val reason: ThreadFitReason
)

/**
 * Outcome of the app-side thread-priority request. Stored by name in the report; UNSUPPORTED is only
 * produced by older reports (it meant the binary lacked `--prio`).
 */
enum class PriorityState { OFF, UNSUPPORTED, REQUESTED, APPLIED, PARTIAL, REFUSED }

data class LockedScreenOptions(
    val threadFit: Boolean,
    val threadPriority: Boolean,
    val performanceHints: Boolean
)

data class LaunchTuningResult(
    val args: List<String>,
    val threadFit: ThreadFitDecision,
    val priorityState: PriorityState
)

/** Pure launch-argument tuning for the locked-screen performance options. */
object LockedScreenLaunchTuning {
    fun decideThreadFit(configured: Int, grantedCpus: Int?, enabled: Boolean): ThreadFitDecision = when {
        !enabled -> ThreadFitDecision(configured, configured, grantedCpus, ThreadFitReason.DISABLED)
        grantedCpus == null || grantedCpus <= 0 ->
            ThreadFitDecision(configured, configured, null, ThreadFitReason.UNKNOWN_CPUSET)
        configured <= grantedCpus ->
            ThreadFitDecision(configured, configured, grantedCpus, ThreadFitReason.FITS)
        else -> ThreadFitDecision(configured, grantedCpus, grantedCpus, ThreadFitReason.REDUCED)
    }

    /**
     * Applies thread fit and priority to a finished argument list. [foregroundCpus] is the content
     * of `/dev/cpuset/foreground/cpus` (null when unreadable). Thread priority never touches the
     * arguments: llama.cpp's `--prio` uses SCHED_FIFO, which Android refuses with a warning per thread,
     * so the app raises the threads itself (see [LockedScreenThreadPriority]). A `--prio` the user
     * typed in custom arguments passes through untouched.
     */
    fun tune(
        args: List<String>,
        options: LockedScreenOptions,
        foregroundCpus: String?
    ): LaunchTuningResult {
        val out = args.toMutableList()
        val threadIndex = out.indexOfFirst { it == "--threads" || it == "-t" }
        val configured = out.getOrNull(threadIndex + 1)?.toIntOrNull()?.takeIf { threadIndex >= 0 && it > 0 }
        val granted = CpuList.parse(foregroundCpus).size.takeIf { it > 0 }
        val fit = if (configured == null) {
            ThreadFitDecision(0, 0, granted, ThreadFitReason.NOT_IN_COMMAND)
        } else {
            decideThreadFit(configured, granted, options.threadFit)
        }
        if (configured != null && fit.reason == ThreadFitReason.REDUCED) {
            out[threadIndex + 1] = fit.effective.toString()
            // Without an explicit value llama.cpp reuses --threads for prompt processing; keep
            // the configured count there because prompts normally run while the screen is on.
            if (out.none { it == "--threads-batch" || it == "-tb" }) {
                out.add("--threads-batch")
                out.add(configured.toString())
            }
        }
        val priority = if (options.threadPriority) PriorityState.REQUESTED else PriorityState.OFF
        return LaunchTuningResult(out, fit, priority)
    }
}
