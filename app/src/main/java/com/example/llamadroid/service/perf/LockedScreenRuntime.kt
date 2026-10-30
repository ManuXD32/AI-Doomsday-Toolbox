package com.example.llamadroid.service.perf

import com.example.llamadroid.service.LlamaConfig
import java.io.File

/**
 * Per-launch helper used by `ProcessController.start` inside the `:llama_runtime` process.
 * It does not change the launch arguments or thread priorities; it only records the server pid in a
 * [LockedScreenReport] file so the main process can read the CPUs the server is allowed to run on
 * for the screen-on/off speed diagnostics. Nothing here may throw into the caller.
 */
class LockedScreenRuntime private constructor(
    private val filesDir: File,
    private val port: Int
) {
    private val store = LockedScreenReportStore(filesDir)
    @Volatile
    private var report = LockedScreenReport(port = port, updatedAtMillis = System.currentTimeMillis())

    fun onProcessStarted(pid: Int) {
        if (pid <= 0) return
        runCatching { update { it.copy(pid = pid, running = true) } }
    }

    fun close() {
        if (report.running) runCatching { update { it.copy(running = false) } }
    }

    @Synchronized
    private fun update(change: (LockedScreenReport) -> LockedScreenReport) {
        report = change(report).copy(updatedAtMillis = System.currentTimeMillis())
        store.write(report)
    }

    companion object {
        fun create(config: LlamaConfig, filesDir: File): LockedScreenRuntime =
            LockedScreenRuntime(filesDir = filesDir, port = config.port)
    }
}
