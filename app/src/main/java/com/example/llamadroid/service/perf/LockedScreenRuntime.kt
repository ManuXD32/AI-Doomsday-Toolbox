package com.example.llamadroid.service.perf

import android.content.Context
import com.example.llamadroid.service.LlamaBinaryCapabilities
import com.example.llamadroid.service.LlamaConfig
import java.io.File

/**
 * Per-launch helper used by `ProcessController.start` inside the `:llama_runtime` process.
 * It tunes the launch arguments, watches the server log for a refused thread priority, runs the
 * PerformanceHintManager session, and mirrors every outcome to a [LockedScreenReport] file that
 * the main process shows in the server detail sheet. Nothing here may throw into the caller.
 */
class LockedScreenRuntime private constructor(
    private val context: Context?,
    private val filesDir: File,
    private val port: Int,
    private val options: LockedScreenOptions,
    private val cpusetRoot: File
) {
    private val store = LockedScreenReportStore(filesDir)
    @Volatile
    private var report = LockedScreenReport(port = port, updatedAtMillis = System.currentTimeMillis())
    private var hint: LockedScreenHintSession? = null

    /** Rewrites [args] according to the options and records the decision. Returns [args] on error. */
    fun tune(args: List<String>, capabilities: LlamaBinaryCapabilities?): List<String> = runCatching {
        // Falls back to this service process's own allowed CPUs (it is a foreground-service
        // process too) when SELinux hides the cpuset files from apps.
        val foreground = readCpuset("foreground") ?: readSelfAllowedCpus()
        val topApp = readCpuset("top-app")
        val result = LockedScreenLaunchTuning.tune(args, options, capabilities, foreground)
        update {
            it.copy(
                threadFitEnabled = options.threadFit,
                configuredThreads = result.threadFit.configured,
                effectiveThreads = result.threadFit.effective,
                threadFitReason = result.threadFit.reason.name,
                foregroundCpus = foreground?.trim(),
                topAppCpus = topApp?.trim(),
                priorityState = result.priorityState.name,
                hintState = if (options.performanceHints) HintState.PENDING.name else HintState.OFF.name,
                running = true
            )
        }
        result.args
    }.getOrDefault(args)

    fun onProcessStarted(pid: Int) {
        if (pid <= 0) return
        runCatching {
            update { it.copy(pid = pid) }
            val ctx = context
            if (options.performanceHints && ctx != null) {
                hint = LockedScreenHintSession(ctx, pid, ::onHintState).also { it.start() }
            }
        }
    }

    fun onLogLine(line: String) {
        if (report.priorityState != PriorityState.REQUESTED.name &&
            report.priorityState != PriorityState.APPLIED.name
        ) return
        if (LockedScreenLaunchTuning.isPriorityWarning(line)) {
            runCatching { update { it.copy(priorityState = PriorityState.REFUSED.name) } }
        }
    }

    /** Called when the server reports ready: a requested priority without a warning counts as applied. */
    fun onReady() {
        if (report.priorityState == PriorityState.REQUESTED.name) {
            runCatching { update { it.copy(priorityState = PriorityState.APPLIED.name) } }
        }
    }

    fun close() {
        runCatching { hint?.close() }
        hint = null
        if (report.running) runCatching { update { it.copy(running = false) } }
    }

    private fun onHintState(state: HintState, detail: String?) {
        runCatching { update { it.copy(hintState = state.name, hintDetail = detail) } }
    }

    @Synchronized
    private fun update(change: (LockedScreenReport) -> LockedScreenReport) {
        report = change(report).copy(updatedAtMillis = System.currentTimeMillis())
        store.write(report)
    }

    private fun readCpuset(name: String): String? =
        runCatching { File(cpusetRoot, "$name/cpus").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun readSelfAllowedCpus(): String? =
        runCatching { CpuList.parseAllowedListFromStatus(File("/proc/self/status").readText()) }.getOrNull()

    companion object {
        /** Always created (the report also feeds diagnostics); the options only gate behavior. */
        fun create(config: LlamaConfig, filesDir: File, context: Context?): LockedScreenRuntime =
            LockedScreenRuntime(
                context = context,
                filesDir = filesDir,
                port = config.port,
                options = LockedScreenOptions(
                    threadFit = config.lockedScreenThreadFit,
                    threadPriority = config.lockedScreenThreadPriority,
                    performanceHints = config.lockedScreenPerformanceHints
                ),
                cpusetRoot = File("/dev/cpuset")
            )
    }
}
