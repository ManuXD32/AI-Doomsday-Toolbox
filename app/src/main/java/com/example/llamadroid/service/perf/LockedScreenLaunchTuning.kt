package com.example.llamadroid.service.perf

import com.example.llamadroid.service.LlamaBinaryCapabilities

/** Why `--threads` was (not) changed. Stored by name in the report, so keep the names stable. */
enum class ThreadFitReason { DISABLED, UNKNOWN_CPUSET, NOT_IN_COMMAND, FITS, REDUCED }

data class ThreadFitDecision(
    val configured: Int,
    val effective: Int,
    /** Number of CPUs of the foreground cpuset, or null when it could not be read. */
    val grantedCpus: Int?,
    val reason: ThreadFitReason
)

/** Outcome of the thread-priority request. Stored by name in the report. */
enum class PriorityState { OFF, UNSUPPORTED, REQUESTED, APPLIED, REFUSED }

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
    /** llama.cpp priority level 2 = "high". */
    const val PRIORITY_LEVEL = "2"

    /**
     * llama.cpp (ggml-cpu `ggml_thread_apply_priority`) prints
     * `warn: failed to set thread priority <n> : <strerror> (<errno>)` when the kernel refuses.
     */
    fun isPriorityWarning(line: String): Boolean =
        line.contains("failed to set thread priority", ignoreCase = true)

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
     * of `/dev/cpuset/foreground/cpus` (null when unreadable).
     */
    fun tune(
        args: List<String>,
        options: LockedScreenOptions,
        capabilities: LlamaBinaryCapabilities?,
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
        val priority = applyPriority(out, options.threadPriority, capabilities)
        return LaunchTuningResult(out, fit, priority)
    }

    private fun applyPriority(
        out: MutableList<String>,
        enabled: Boolean,
        capabilities: LlamaBinaryCapabilities?
    ): PriorityState {
        if (!enabled) return PriorityState.OFF
        if (capabilities == null || capabilities.allowAll || !capabilities.supports("--prio")) {
            return PriorityState.UNSUPPORTED
        }
        // A user-supplied priority in custom flags wins.
        if (out.none { it == "--prio" }) {
            out.add("--prio")
            out.add(PRIORITY_LEVEL)
        }
        if (capabilities.supports("--prio-batch") && out.none { it == "--prio-batch" }) {
            out.add("--prio-batch")
            out.add(PRIORITY_LEVEL)
        }
        return PriorityState.REQUESTED
    }
}
