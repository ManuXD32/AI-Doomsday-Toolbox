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
import java.net.InetSocketAddress
import java.net.Socket

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
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val probe: (SdHelper) -> Boolean = ::probeTcp,
    private val onLost: (SdHelper) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var failures: Map<String, Int> = emptyMap()

    @Volatile private var stopped = false
    @Volatile var lostHelper: SdHelper? = null
        private set

    val lost: Boolean get() = lostHelper != null

    fun start(intervalMs: Long = INTERVAL_MS) {
        if (helpers.isEmpty() || job != null) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    /** One probe round. Reports the first helper reaching [threshold] consecutive failures, once. */
    fun tick() {
        if (stopped || lost) return
        for (helper in helpers) {
            if (stopped) return
            val ok = probe(helper)
            val next = nextFailures(failures[helper.endpoint] ?: 0, ok)
            failures = failures + (helper.endpoint to next)
            if (helperLost(next, threshold)) {
                lostHelper = helper
                onLost(helper)
                return
            }
        }
    }

    fun stop() {
        stopped = true
        scope.cancel()
    }

    /** Localized terminal message when a helper was lost, else null. */
    fun lostMessage(context: Context): String? = lostHelper?.let {
        context.getString(R.string.sd_helper_lost_error, it.name, it.endpoint)
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val DEFAULT_THRESHOLD = 2
        const val PROBE_TIMEOUT_MS = 1500

        fun helperLost(consecutiveFailures: Int, threshold: Int = DEFAULT_THRESHOLD): Boolean =
            consecutiveFailures >= threshold

        fun nextFailures(previous: Int, reachable: Boolean): Int = if (reachable) 0 else previous + 1

        fun probeTcp(helper: SdHelper): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(helper.host, helper.port), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

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
            process: Process
        ): SdHelperWatchdog? {
            if (!distributedHolder || !runtime.enabled || !runtime.hasRpcServers) return null
            val helpers = parseHelpers(runtime.rpcServers, SdDistributedService.activeWorkers.value)
            if (helpers.isEmpty()) return null
            return SdHelperWatchdog(helpers) { helper ->
                SdDistributedService.appendLog(context.getString(R.string.sd_helper_lost_error, helper.name, helper.endpoint))
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }.also { it.start() }
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
