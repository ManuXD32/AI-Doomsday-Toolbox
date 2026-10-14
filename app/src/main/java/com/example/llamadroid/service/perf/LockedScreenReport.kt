package com.example.llamadroid.service.perf

import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File

/** What the `:llama_runtime` process did for one local llama-server, shared with the main process. */
@Keep
data class LockedScreenReport(
    val port: Int = 0,
    val pid: Int = 0,
    val updatedAtMillis: Long = 0L,
    val threadFitEnabled: Boolean = false,
    val configuredThreads: Int = 0,
    val effectiveThreads: Int = 0,
    val threadFitReason: String = ThreadFitReason.DISABLED.name,
    val foregroundCpus: String? = null,
    val topAppCpus: String? = null,
    val priorityState: String = PriorityState.OFF.name,
    /** One of OFF, UNSUPPORTED, ACCEPTED, REFUSED. */
    val hintState: String = HintState.OFF.name,
    val hintDetail: String? = null,
    val running: Boolean = true
)

enum class HintState { OFF, UNSUPPORTED, PENDING, ACCEPTED, REFUSED }

/** Small JSON files under `files/locked_screen_perf/`, one per server port. */
class LockedScreenReportStore(filesDir: File) {
    private val dir = File(filesDir, "locked_screen_perf")
    private val gson = Gson()

    private fun fileFor(port: Int) = File(dir, "port-$port.json")

    fun write(report: LockedScreenReport) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(report.port)
            val temp = File(dir, "port-${report.port}.json.tmp")
            temp.writeText(gson.toJson(report))
            if (!temp.renameTo(target)) {
                target.writeText(gson.toJson(report))
                temp.delete()
            }
        }
    }

    fun read(port: Int): LockedScreenReport? =
        runCatching { gson.fromJson(fileFor(port).readText(), LockedScreenReport::class.java) }.getOrNull()

    fun readAll(): List<LockedScreenReport> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), LockedScreenReport::class.java) }.getOrNull() }
            .filter { it.port > 0 }
            .sortedBy { it.port }
}
