package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One remote SD RPC helper the running sd-cli depends on. */
data class SdHelper(val name: String, val host: String, val port: Int) {
    val endpoint: String get() = "$host:$port"
}

/**
 * Ends a distributed SD run whose remote helper stopped answering. The native client has no socket timeouts, so a
 * helper that vanishes without a FIN/RST (or a stuck child holding the stdout pipe) otherwise looks hung forever.
 *
 * Helpers are the endpoints of the run's own `--rpc-servers` list (what sd-cli really connects to), named from
 * [SdDistributedService.activeWorkers] when a matching host:port exists.
 */
class SdHelperWatchdog(
    private val helpers: List<SdHelper>,
    private val probe: (SdHelper) -> SdHelperLiveness = { SdHelperProbe.probe(it.host, it.port) },
    private val lastOutputAtMs: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val onUnverifiable: (SdHelper) -> Unit = {},
    private val onLost: (SdHelper) -> Unit
) {
    private class State {
        var probed = false
        var unverifiable = false
        var seenHeartbeat = false
        var unreachable = 0
        var hostAliveAfterHeartbeat = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val states = helpers.associate { it.endpoint to State() }

    @Volatile private var stopped = false
    @Volatile var lostHelper: SdHelper? = null
        private set

    val lost: Boolean get() = lostHelper != null

    fun start(intervalMs: Long = INTERVAL_MS) {
        if (helpers.isEmpty() || job != null) return
        activeRuns.incrementAndGet()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    /** One probe round. Reports the first helper meeting a kill rule, once. */
    fun tick() {
        if (stopped || lost) return
        for (helper in helpers) {
            if (stopped) return
            val state = states.getValue(helper.endpoint)
            val liveness = probe(helper)
            if (!state.probed) {
                state.probed = true
                if (liveness == SdHelperLiveness.UNREACHABLE) {
                    state.unverifiable = true
                    onUnverifiable(helper)
                }
            }
            when (liveness) {
                SdHelperLiveness.HEARTBEAT -> {
                    state.seenHeartbeat = true
                    state.unreachable = 0
                    state.hostAliveAfterHeartbeat = 0
                }
                SdHelperLiveness.HOST_ALIVE -> {
                    state.unreachable = 0
                    state.hostAliveAfterHeartbeat = if (state.seenHeartbeat) state.hostAliveAfterHeartbeat + 1 else 0
                }
                SdHelperLiveness.UNREACHABLE -> {
                    state.unreachable++
                    state.hostAliveAfterHeartbeat = 0
                }
            }
            val processGone = state.hostAliveAfterHeartbeat >= PROCESS_GONE_THRESHOLD
            val silentAndUnreachable = !state.unverifiable &&
                state.unreachable >= UNREACHABLE_THRESHOLD &&
                now() - lastOutputAtMs() >= SILENCE_MS
            if (processGone || silentAndUnreachable) {
                lostHelper = helper
                onLost(helper)
                return
            }
        }
    }

    fun stop() {
        if (!stopped && job != null) activeRuns.decrementAndGet()
        stopped = true
        scope.cancel()
    }

    /** Localized terminal message when a helper was lost, else null. */
    fun lostMessage(context: Context): String? = lostHelper?.let {
        context.getString(R.string.sd_helper_lost_error, it.name, it.endpoint)
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val UNREACHABLE_THRESHOLD = 6
        const val PROCESS_GONE_THRESHOLD = 2
        const val SILENCE_MS = 60_000L

        private val activeRuns = java.util.concurrent.atomic.AtomicInteger(0)

        /** True while a distributed run is being watched; the helpers' RPC ports must not be probed then. */
        val runActive: Boolean get() = activeRuns.get() > 0

        /** Parses `host:port,host:port` into helpers, naming them from known workers. */
        fun parseHelpers(rpcServers: String, known: List<SdDistributedWorkerRuntime>): List<SdHelper> =
            rpcServers.split(',').mapNotNull { raw ->
                val entry = raw.trim()
                val idx = entry.lastIndexOf(':')
                val port = entry.substring(idx + 1).toIntOrNull()
                if (idx <= 0 || port == null) return@mapNotNull null
                val host = entry.substring(0, idx)
                val name = known.firstOrNull { it.host == host && it.port == port && !it.isLocalMaster }
                    ?.deviceName?.takeIf { it.isNotBlank() } ?: host
                SdHelper(name, host, port)
            }

        /**
         * Starts a watchdog for a distributed run with remote helpers, or returns null. On loss it logs the reason,
         * kills [process] and closes its stdout so the blocked reader returns.
         */
        fun startFor(
            context: Context,
            runtime: SdDistributedRuntimeConfig,
            distributedHolder: Boolean,
            process: Process,
            lastOutputAtMs: () -> Long
        ): SdHelperWatchdog? {
            if (!distributedHolder || !runtime.enabled || !runtime.hasRpcServers) return null
            val helpers = parseHelpers(runtime.rpcServers, SdDistributedService.activeWorkers.value)
            if (helpers.isEmpty()) return null
            return SdHelperWatchdog(
                helpers = helpers,
                lastOutputAtMs = lastOutputAtMs,
                onUnverifiable = { helper ->
                    SdDistributedService.appendLog(
                        context.getString(R.string.sd_r8_w01_unverifiable_log, helper.name, helper.endpoint)
                    )
                },
                onLost = { helper ->
                    SdDistributedService.appendLog(context.getString(R.string.sd_helper_lost_error, helper.name, helper.endpoint))
                    process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            ).also { it.start() }
        }

        /** Runs a stream consumer; the "stream closed" error caused by a watchdog kill is not a failure of its own. */
        inline fun consumeUnlessHelperLost(watchdog: SdHelperWatchdog?, consume: () -> Unit) {
            try {
                consume()
            } catch (e: java.io.IOException) {
                if (watchdog?.lost != true) throw e
            }
        }
    }
}
