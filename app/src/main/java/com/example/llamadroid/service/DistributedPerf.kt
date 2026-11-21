package com.example.llamadroid.service

import android.content.Context
import androidx.core.content.edit

/**
 * Pure helpers and settings for distributed-inference performance (see docs/distributed-performance.md).
 * Everything that is not Context-bound is side-effect free so it can be unit tested on the host.
 */
object DistributedPerf {
    /** Worker `-c` tensor cache is on by default: weights are not re-uploaded on every load. */
    const val DEFAULT_WORKER_CACHE = true
    /** `GGML_RPC_DEBUG` logs a line per RPC call per token; only enabled by an explicit debug toggle. */
    const val DEFAULT_RPC_DEBUG = false
    /** Physical batch (`--ubatch-size`) sent explicitly instead of relying on the server default. */
    const val DEFAULT_UBATCH = 512
    const val MIN_UBATCH = 32
    const val MAX_UBATCH = 4096

    /** A device holding less than this fraction of the layers triggers TINY_SHARE. */
    const val TINY_SHARE_FRACTION = 0.10
    /** Warn when estimated network time per token exceeds this fraction of estimated compute time. */
    const val NETWORK_COMPUTE_WARN_RATIO = 0.30
    /** Effective single-stream memory bandwidth assumed for phone CPUs when estimating decode time. */
    const val ASSUMED_MEMORY_BANDWIDTH_MB_PER_S = 20_000.0
    /** Model bytes plus KV cache / compute-buffer headroom. */
    const val MODEL_OVERHEAD_FACTOR = 1.10
    const val RTT_SAMPLE_WINDOW = 5

    const val ENV_RPC_DEBUG = "GGML_RPC_DEBUG"

    /** Value for the `GGML_RPC_DEBUG` environment variable, or null when it must not be set at all. */
    fun rpcDebugEnvValue(debugEnabled: Boolean): String? = if (debugEnabled) "1" else null

    fun coerceUbatch(value: Int): Int = value.coerceIn(MIN_UBATCH, MAX_UBATCH)

    /** Median of the latest [RTT_SAMPLE_WINDOW] timed connects, in milliseconds; null without samples. */
    fun medianRttMs(samples: List<Long>): Long? {
        val window = samples.takeLast(RTT_SAMPLE_WINDOW).filter { it >= 0 }.sorted()
        if (window.isEmpty()) return null
        val mid = window.size / 2
        return if (window.size % 2 == 1) window[mid] else (window[mid - 1] + window[mid]) / 2
    }

    /** Appends [sample] and keeps only the last [RTT_SAMPLE_WINDOW] entries. */
    fun appendRttSample(samples: List<Long>, sample: Long): List<Long> =
        (samples + sample.coerceAtLeast(0)).takeLast(RTT_SAMPLE_WINDOW)

    /**
     * Decode is memory-bandwidth bound: every token streams the whole model once across the
     * pipeline, so compute_ms = modelBytes / bandwidth.
     */
    fun estimateComputeMsPerToken(modelSizeMiB: Int): Double =
        if (modelSizeMiB <= 0) 0.0 else modelSizeMiB * 1.048576 / ASSUMED_MEMORY_BANDWIDTH_MB_PER_S * 1000.0

    /** One round trip per remote device per token; unknown RTTs contribute nothing. */
    fun estimateNetworkMsPerToken(rttMsOfRemoteDevices: List<Long?>): Double =
        rttMsOfRemoteDevices.filterNotNull().sumOf { it.toDouble() }

    fun isNetworkBound(networkMs: Double, computeMs: Double): Boolean =
        computeMs > 0.0 && networkMs > computeMs * NETWORK_COMPUTE_WARN_RATIO
}

enum class DistributedPlanWarningType {
    /** A device would hold less than 10% of the layers; it adds a hop for little capacity. */
    TINY_SHARE,
    /** The model fits in the master's RAM budget; workers only add latency. */
    MODEL_FITS_ON_MASTER,
    /** Estimated network time per token exceeds ~30% of estimated compute. */
    NETWORK_BOUND,
    /** Selected devices cannot hold the model; layers were spread proportionally to RAM. */
    INSUFFICIENT_RAM
}

data class DistributedPlanWarning(
    val type: DistributedPlanWarningType,
    /** Stable key of the affected device (TINY_SHARE), otherwise null. */
    val deviceKey: String? = null,
    val sharePercent: Int? = null,
    val networkMsPerToken: Int? = null,
    val computeMsPerToken: Int? = null
)

data class DistributedPlanDevice(val ref: DistributedDeviceRef, val capacityMiB: Int)

data class DistributedLayerPlan(
    val allocations: List<DistributedLayerAllocation>,
    val warnings: List<DistributedPlanWarning>,
    val estimatedNetworkMsPerToken: Int? = null,
    val estimatedComputeMsPerToken: Int? = null
)

/** Fewest-hops layer planner: master first up to its budget, then the fewest and largest workers. */
object DistributedLayerPlanner {
    fun plan(
        layers: Int,
        modelSizeMiB: Int,
        devices: List<DistributedPlanDevice>,
        /** Median RTT per worker address (`ip:port`), used only for the network-bound estimate. */
        workerRttMs: Map<String, Long> = emptyMap()
    ): DistributedLayerPlan {
        require(layers > 0)
        require(devices.isNotEmpty())
        fun key(d: DistributedPlanDevice) = d.ref.stableKey()
        val proportional = {
            DistributedLlamaLaunchResolver.allocateLayersByRam(layers, devices.map { it.ref }) { ref ->
                devices.first { it.ref == ref }.capacityMiB
            }
        }
        if (modelSizeMiB <= 0) return DistributedLayerPlan(proportional(), emptyList())

        val perLayerMiB = modelSizeMiB * DistributedPerf.MODEL_OVERHEAD_FACTOR / layers
        fun capLayers(d: DistributedPlanDevice) = (d.capacityMiB / perLayerMiB).toInt().coerceAtLeast(0)

        val assigned = LinkedHashMap<String, Int>()
        var remaining = layers
        val masters = devices.filter { it.ref.kind != DistributedDeviceKind.WORKER }
        masters.forEach { d ->
            val take = minOf(remaining, capLayers(d))
            if (take > 0) { assigned[key(d)] = take; remaining -= take }
        }
        val pool = devices.filter { it.ref.kind == DistributedDeviceKind.WORKER && capLayers(it) > 0 }.toMutableList()
        while (remaining > 0 && pool.isNotEmpty()) {
            val fitting = pool.filter { capLayers(it) >= remaining }
            val pick = if (fitting.isNotEmpty()) fitting.minBy { capLayers(it) } else pool.maxBy { capLayers(it) }
            pool.remove(pick)
            val take = minOf(remaining, capLayers(pick))
            assigned[key(pick)] = take
            remaining -= take
        }

        val warnings = mutableListOf<DistributedPlanWarning>()
        val allocations: List<DistributedLayerAllocation>
        if (remaining > 0) {
            // Not enough RAM anywhere: fall back to a RAM-proportional spread over devices with RAM.
            warnings += DistributedPlanWarning(DistributedPlanWarningType.INSUFFICIENT_RAM)
            val usable = devices.filter { it.capacityMiB > 0 }
            allocations = if (usable.isEmpty()) proportional() else
                DistributedLlamaLaunchResolver.allocateLayersByRam(layers, usable.map { it.ref }) { ref ->
                    usable.first { it.ref == ref }.capacityMiB
                }
        } else {
            allocations = devices.filter { assigned.containsKey(key(it)) }.map { d ->
                val n = assigned.getValue(key(d))
                DistributedLayerAllocation(d.ref, d.capacityMiB, n.toFloat() / layers, n)
            }
        }

        val workerAllocations = allocations.filter { it.device.kind == DistributedDeviceKind.WORKER && it.assignedLayers > 0 }
        val masterFits = masters.sumOf { capLayers(it) } >= layers
        if (masterFits && devices.any { it.ref.kind == DistributedDeviceKind.WORKER }) {
            warnings += DistributedPlanWarning(DistributedPlanWarningType.MODEL_FITS_ON_MASTER)
        }
        if (allocations.count { it.assignedLayers > 0 } > 1) {
            allocations.filter { it.assignedLayers > 0 && it.assignedLayers.toDouble() / layers < DistributedPerf.TINY_SHARE_FRACTION }
                .forEach {
                    warnings += DistributedPlanWarning(
                        DistributedPlanWarningType.TINY_SHARE,
                        deviceKey = it.device.stableKey(),
                        sharePercent = (it.assignedLayers * 100.0 / layers).toInt()
                    )
                }
        }
        val computeMs = DistributedPerf.estimateComputeMsPerToken(modelSizeMiB)
        val networkMs = DistributedPerf.estimateNetworkMsPerToken(
            workerAllocations.map { alloc -> alloc.device.workerAddress?.let { workerRttMs[it] } }
        )
        if (workerAllocations.isNotEmpty() && DistributedPerf.isNetworkBound(networkMs, computeMs)) {
            warnings += DistributedPlanWarning(
                DistributedPlanWarningType.NETWORK_BOUND,
                networkMsPerToken = networkMs.toInt(),
                computeMsPerToken = computeMs.toInt()
            )
        }
        return DistributedLayerPlan(
            allocations = allocations,
            warnings = warnings,
            estimatedNetworkMsPerToken = networkMs.toInt().takeIf { workerAllocations.isNotEmpty() },
            estimatedComputeMsPerToken = computeMs.toInt()
        )
    }
}

/** Exact per-device split for [DistributedLayerPlanning.PERCENT] and [DistributedLayerPlanning.LAYERS]. */
object DistributedManualSplit {
    enum class Reason {
        /** A value is missing or below 1. */
        INVALID_VALUE,
        /** Percentages do not add up to 100, or layers do not add up to the model's layer count. */
        TOTAL_MISMATCH,
        /** A percent is so small that the device would get no layer (index in [Result.Invalid.deviceIndex]). */
        ZERO_LAYERS
    }

    sealed interface Result {
        /** [layers] has one entry per device, in input order, and sums to the model layer count. */
        data class Valid(val layers: List<Int>) : Result
        /** [total] is the sum of the entered values (percent or layers). */
        data class Invalid(val reason: Reason, val total: Int, val deviceIndex: Int? = null) : Result
    }

    fun evaluate(mode: DistributedLayerPlanning, values: List<Int?>, layers: Int): Result {
        require(mode.isManual) { "Not a manual split mode" }
        val total = values.sumOf { it ?: 0 }
        if (values.isEmpty() || values.any { it == null || it < 1 }) {
            return Result.Invalid(Reason.INVALID_VALUE, total, values.indexOfFirst { it == null || it < 1 }.takeIf { it >= 0 })
        }
        val entered = values.map { it!! }
        val expected = if (mode == DistributedLayerPlanning.PERCENT) 100 else layers
        if (total != expected) return Result.Invalid(Reason.TOTAL_MISMATCH, total)
        if (mode == DistributedLayerPlanning.LAYERS) return Result.Valid(entered)
        val assigned = percentToLayers(entered, layers)
        val zero = assigned.indexOfFirst { it < 1 }
        return if (zero >= 0) Result.Invalid(Reason.ZERO_LAYERS, total, zero) else Result.Valid(assigned)
    }

    /** Largest-remainder rounding: the result always sums to [layers]; ties go to the earlier device. */
    fun percentToLayers(percents: List<Int>, layers: Int): List<Int> {
        val sum = percents.sum().coerceAtLeast(1)
        val exact = percents.map { layers.toDouble() * it / sum }
        val assigned = exact.map { it.toInt() }.toMutableList()
        var remaining = layers - assigned.sum()
        exact.indices.sortedWith(compareByDescending<Int> { exact[it] - assigned[it] }.thenBy { it }).forEach { index ->
            if (remaining > 0) {
                assigned[index]++
                remaining--
            }
        }
        return assigned
    }

    /** The only warning a manual split may produce: a device assigned more than its RAM holds. */
    fun ramWarnings(
        allocations: List<DistributedLayerAllocation>,
        modelSizeMiB: Int,
        layers: Int
    ): List<DistributedPlanWarning> {
        if (modelSizeMiB <= 0 || layers <= 0) return emptyList()
        val perLayerMiB = modelSizeMiB * DistributedPerf.MODEL_OVERHEAD_FACTOR / layers
        return if (allocations.any { it.assignedLayers * perLayerMiB > it.contributedRamMiB }) {
            listOf(DistributedPlanWarning(DistributedPlanWarningType.INSUFFICIENT_RAM))
        } else emptyList()
    }
}

/** Worker capabilities advertised in the NSD TXT record (`ram_free_mb`, `cores`, `app_ver`). */
data class WorkerAdvert(val ramFreeMb: Int?, val cores: Int?, val appVersion: String?) {
    /** The advertised RAM, or [fallback] (the legacy 4096 MiB default) when the worker did not send it. */
    fun ramOrDefault(fallback: Int = 4096): Int = ramFreeMb ?: fallback

    fun toAttributes(): Map<String, String> = buildMap {
        ramFreeMb?.let { put(KEY_RAM, it.toString()) }
        cores?.let { put(KEY_CORES, it.toString()) }
        appVersion?.let { put(KEY_APP_VER, it) }
    }

    companion object {
        const val KEY_RAM = "ram_free_mb"
        const val KEY_CORES = "cores"
        const val KEY_APP_VER = "app_ver"
        val EMPTY = WorkerAdvert(null, null, null)

        fun parse(attributes: Map<String, ByteArray?>?): WorkerAdvert {
            if (attributes.isNullOrEmpty()) return EMPTY
            fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)
            return WorkerAdvert(
                ramFreeMb = text(KEY_RAM)?.toIntOrNull()?.takeIf { it in 1..4_194_304 },
                cores = text(KEY_CORES)?.toIntOrNull()?.takeIf { it in 1..1024 },
                appVersion = text(KEY_APP_VER)?.take(32)
            )
        }
    }
}

/** Persisted performance toggles (worker cache, RPC debug logging, ubatch size). */
object DistributedPerfSettings {
    private const val PREFS = "distributed_perf"
    private const val KEY_WORKER_CACHE = "worker_cache"
    private const val KEY_RPC_DEBUG = "rpc_debug"
    private const val KEY_UBATCH = "ubatch_size"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun workerCacheEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORKER_CACHE, DistributedPerf.DEFAULT_WORKER_CACHE)

    fun setWorkerCacheEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_WORKER_CACHE, enabled) }

    fun rpcDebugEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RPC_DEBUG, DistributedPerf.DEFAULT_RPC_DEBUG)

    fun setRpcDebugEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_RPC_DEBUG, enabled) }

    fun ubatchSize(context: Context): Int =
        DistributedPerf.coerceUbatch(prefs(context).getInt(KEY_UBATCH, DistributedPerf.DEFAULT_UBATCH))

    fun setUbatchSize(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_UBATCH, DistributedPerf.coerceUbatch(value)) }
}

/** Localized text for the performance data, so the UI only renders strings. */
object DistributedPerfText {
    /** Round-trip label for a worker row, e.g. "RTT 8 ms" or "RTT unknown". */
    fun rttLabel(context: Context, rttMs: Long?): String =
        if (rttMs == null) context.getString(com.example.llamadroid.R.string.dist_perf_rtt_unknown)
        else context.getString(com.example.llamadroid.R.string.dist_perf_rtt_ms, rttMs.toString())

    /** Advertised worker capabilities, e.g. "6144 MiB free, 8 cores"; null when nothing was advertised. */
    fun advertLabel(context: Context, ramMb: Int?, cores: Int?): String? = when {
        ramMb != null && cores != null -> context.getString(
            com.example.llamadroid.R.string.dist_perf_advert_ram_cores, ramMb.toString(), cores.toString()
        )
        ramMb != null -> context.getString(com.example.llamadroid.R.string.dist_perf_advert_ram, ramMb.toString())
        else -> null
    }

    /** [deviceName] is substituted for TINY_SHARE warnings (resolve it from [DistributedPlanWarning.deviceKey]). */
    fun warningMessage(context: Context, warning: DistributedPlanWarning, deviceName: String? = null): String {
        return when (warning.type) {
            DistributedPlanWarningType.TINY_SHARE -> context.getString(
                com.example.llamadroid.R.string.dist_perf_warn_tiny_share,
                deviceName ?: warning.deviceKey.orEmpty(),
                "${warning.sharePercent ?: 0}%"
            )
            DistributedPlanWarningType.MODEL_FITS_ON_MASTER -> context.getString(com.example.llamadroid.R.string.dist_perf_warn_fits_master)
            DistributedPlanWarningType.NETWORK_BOUND -> context.getString(
                com.example.llamadroid.R.string.dist_perf_warn_network_bound,
                (warning.networkMsPerToken ?: 0).toString(),
                (warning.computeMsPerToken ?: 0).toString()
            )
            DistributedPlanWarningType.INSUFFICIENT_RAM -> context.getString(com.example.llamadroid.R.string.dist_perf_warn_insufficient_ram)
        }
    }
}
