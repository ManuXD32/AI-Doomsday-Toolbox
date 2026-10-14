package com.example.llamadroid.service.perf

import android.content.Context
import android.os.PowerManager
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.LlamaClientService.GenerationState
import com.example.llamadroid.service.NativeChatGenerationStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Everything the server detail sheet shows about locked-screen speed, read from disk and memory. */
data class LockedScreenView(
    val stats: ServerSpeedStats?,
    val report: LockedScreenReport?,
    val coreMixOff: CoreMix?,
    val coreMixOn: CoreMix?
)

/**
 * Main-process recorder: measures tokens/s of local llama-server generations with the screen on
 * versus off, together with the CPUs the llama-server process was allowed to use at that moment
 * (`/proc/<pid>/status`, the pid comes from the report the `:llama_runtime` process writes).
 * Only counts and speeds are kept; no message content is read or stored.
 */
object LockedScreenSpeedRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aggregator = LockedScreenSpeedAggregator()
    private val lock = Any()
    private val lastSampleAt = HashMap<Long, Long>()
    private val portByChat = HashMap<Long, Pair<Long, Int?>>()
    private var started = false

    @Volatile
    private var filesDir: File? = null

    /** Idempotent; call from `Application.onCreate` of the main process. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
            filesDir = app.filesDir
        }
        scope.launch {
            NativeChatGenerationStates.states.collect { states ->
                runCatching { observe(app, states) }
            }
        }
    }

    private suspend fun observe(app: Context, states: Map<Long, GenerationState>) {
        val now = System.currentTimeMillis()
        val power = app.getSystemService(PowerManager::class.java)
        states.forEach { (chatId, state) ->
            val tps = when (state) {
                is GenerationState.Generating -> state.tokensPerSecond
                is GenerationState.Completed -> state.tokensPerSecond
                else -> return@forEach
            }
            if (tps <= 0.0) return@forEach
            val isFinal = state is GenerationState.Completed
            val last = synchronized(lock) { lastSampleAt[chatId] }
            if (!isFinal && last != null && now - last < SAMPLE_GAP_MS) return@forEach
            if (isFinal && last != null && now - last < FINAL_DEDUPE_MS) return@forEach
            val screenOn = power?.isInteractive ?: true
            val port = portForChat(app, chatId, now) ?: return@forEach
            synchronized(lock) { lastSampleAt[chatId] = now }
            val cpus = allowedCpusOfServer(port)
            synchronized(lock) { aggregator.record(port, screenOn, tps, cpus) }
        }
    }

    private suspend fun portForChat(app: Context, chatId: Long, now: Long): Int? {
        synchronized(lock) {
            portByChat[chatId]?.let { (at, port) -> if (now - at < PORT_CACHE_MS) return port }
        }
        val port = runCatching {
            val db = AppDatabase.getDatabase(app)
            val serverId = db.llamaChatDao().getChatById(chatId)?.serverId
            val server = serverId?.let { db.llamaServerDao().getServerById(it) }
            server?.takeIf { it.host == "127.0.0.1" || it.host == "localhost" }?.port
        }.getOrNull()
        synchronized(lock) { portByChat[chatId] = now to port }
        return port
    }

    private fun allowedCpusOfServer(port: Int): String? {
        val dir = filesDir ?: return null
        val pid = LockedScreenReportStore(dir).read(port)?.pid?.takeIf { it > 0 } ?: return null
        return runCatching {
            CpuList.parseAllowedListFromStatus(File("/proc/$pid/status").readText())
        }.getOrNull()
    }

    /** Disk and memory read for the detail sheet; call off the main thread. */
    fun viewFor(context: Context, port: Int): LockedScreenView {
        val stats = synchronized(lock) { aggregator.stats(port) }
        val report = LockedScreenReportStore(context.applicationContext.filesDir).read(port)
        val classes = CpuTopology.classify(CpuTopology.readMaxFrequencies())
        fun mix(cpus: String?) = cpus?.let { CpuTopology.mix(CpuList.parse(it), classes) }?.takeIf { !it.isEmpty }
        return LockedScreenView(stats, report, mix(stats?.screenOffCpus), mix(stats?.screenOnCpus))
    }

    /** Plain-English diagnostics block for "Copy diagnostics"; null when nothing was recorded. */
    fun summary(context: Context): String? {
        val store = LockedScreenReportStore(context.applicationContext.filesDir)
        val reports = store.readAll()
        val ports = (reports.map { it.port } + synchronized(lock) { aggregator.keys() }).distinct().sorted()
        if (ports.isEmpty()) return null
        return buildString {
            append("Locked-screen performance\n")
            ports.forEach { port ->
                val stats = synchronized(lock) { aggregator.stats(port) }
                val report = reports.firstOrNull { it.port == port }
                append("- port ").append(port).append(": ")
                append(
                    summaryLine(stats, report)
                )
                append('\n')
            }
        }.trimEnd()
    }

    internal fun summaryLine(stats: ServerSpeedStats?, report: LockedScreenReport?): String {
        fun speed(tps: Double?, cpus: String?, samples: Int): String =
            if (tps == null) "no samples" else
                String.format(Locale.US, "%.1f t/s (%d samples%s)", tps, samples,
                    cpus?.let { ", CPUs $it" }.orEmpty())
        val parts = mutableListOf<String>()
        parts += "screen off " + speed(stats?.screenOffTps, stats?.screenOffCpus, stats?.screenOffSamples ?: 0)
        parts += "screen on " + speed(stats?.screenOnTps, stats?.screenOnCpus, stats?.screenOnSamples ?: 0)
        if (report != null) {
            parts += "threads ${report.configuredThreads}->${report.effectiveThreads} (${report.threadFitReason}, " +
                "foreground CPUs ${report.foregroundCpus ?: "?"}, top-app ${report.topAppCpus ?: "?"})"
            parts += "priority ${report.priorityState}"
            parts += "hints ${report.hintState}" + (report.hintDetail?.let { " ($it)" } ?: "")
        }
        return parts.joinToString("; ")
    }

    private const val SAMPLE_GAP_MS = 5_000L
    private const val FINAL_DEDUPE_MS = 1_000L
    private const val PORT_CACHE_MS = 60_000L
}
