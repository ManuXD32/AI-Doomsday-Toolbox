package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyClusterConfigWriter
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterMode
import com.example.llamadroid.branchy.cluster.ClusterDeviceSpec
import com.example.llamadroid.branchy.cluster.ClusterDpSettings
import com.example.llamadroid.branchy.cluster.ClusterEndpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Helper devices (ids of `branchy_cluster_device`) a run was planned with. */
@Serializable
data class ClusterSelection(
    val deviceIds: List<String> = emptyList(),
    /** `auto`, `branch_parallel` or `data_parallel`, as given to the planner. */
    val mode: String = "auto"
) {
    val isEmpty: Boolean get() = deviceIds.isEmpty()
}

/** Result of [BranchyClusterRun.clusterJson]. */
sealed interface ClusterJsonResult {
    /** The plan keeps every branch on this phone: train without `--cluster`. */
    data object NotNeeded : ClusterJsonResult
    data class Ready(val json: String, val devices: List<ClusterDeviceSpec>, val mode: BranchyClusterMode) : ClusterJsonResult

    /** Planned devices that are no longer known (removed, or never had an address). */
    data class MissingEndpoints(val ids: List<String>) : ClusterJsonResult
}

/** Pure helpers that join the planner, the helper list and `cluster.json`. */
object BranchyClusterRun {
    /** Device id of this phone in the planner input. */
    const val LOCAL_ID = "local"

    /** Used when a helper advertised no speed; deliberately low so it never looks faster than it is. */
    const val FALLBACK_GFLOPS = 10.0
    const val DEFAULT_HELPER_THREADS = 4

    /** Planner input devices: this phone first, then each selected helper (unknown speed gets [FALLBACK_GFLOPS]). */
    fun planDevices(
        local: BranchyDevice,
        helpers: List<BranchyClusterDeviceEntity>,
        ramMb: Map<String, Long> = emptyMap(),
        gflops: Map<String, Double> = emptyMap(),
        linkMbps: Map<String, Double> = emptyMap()
    ): List<BranchyDevice> = listOf(local) + helpers.map { h ->
        BranchyDevice(
            id = h.id,
            ramMb = ramMb[h.id] ?: h.ramMb?.toLong() ?: 0L,
            storageMb = 0L,
            gflops = (gflops[h.id] ?: h.gflops)?.takeIf { it > 0.0 && it.isFinite() } ?: FALLBACK_GFLOPS,
            threads = DEFAULT_HELPER_THREADS,
            linkMbps = linkMbps[h.id]?.takeIf { it > 0.0 && it.isFinite() } ?: BranchyDevice.DEFAULT_LINK_MBPS
        )
    }

    /** Helpers that have no RAM figure (advert or typed): the planner cannot place anything on them. */
    fun helpersWithoutRam(helpers: List<BranchyClusterDeviceEntity>, ramMb: Map<String, Long>): List<BranchyClusterDeviceEntity> =
        helpers.filter { (ramMb[it.id] ?: it.ramMb?.toLong() ?: 0L) <= 0L }

    /** Non-coordinator ids of [plan] that hold branches but have no entry in [known]. */
    fun missingEndpoints(plan: BranchyPlan, known: Collection<String>): List<String> =
        plan.placement.filter { it.role != "coordinator" && it.branches.isNotEmpty() && it.device !in known }
            .map { it.device }

    fun mode(plan: BranchyPlan): BranchyClusterMode =
        if (plan.mode == "data_parallel") BranchyClusterMode.DATA_PARALLEL else BranchyClusterMode.BRANCH_PARALLEL

    /** Seconds of local steps per DP round; the native side adapts `sync_every` to it. */
    const val DP_SYNC_SECONDS = 60

    /** `dp` block from the plan (fallback 64 steps / 8 bits); no round timeout, so the native side picks it. */
    fun dpSettings(plan: BranchyPlan): ClusterDpSettings = ClusterDpSettings(
        syncEvery = plan.syncEvery?.takeIf { it > 0 } ?: 64,
        syncSeconds = DP_SYNC_SECONDS,
        bits = plan.deltaBits?.takeIf { it > 0 } ?: 8
    )

    /** Initial connect timeout per helper attempt (native default stays 3000 for other callers). */
    const val CONNECT_TIMEOUT_MS = 15_000
    const val MESSAGE_TIMEOUT_MS = 60_000
    const val JOIN_WINDOW_MS = 90_000

    /** DP helper rejoin cadence; branch parallel keeps the native default (2 s). */
    const val DP_REJOIN_INTERVAL_MS = 10_000

    /**
     * Adds the network keys the app always sets (`connect_timeout_ms`, `message_timeout_ms`, `join_window_ms`, plus `rejoin_interval_ms` for DP) and,
     * when [controlFile] is given, `control_file`, to a cluster.json text.
     */
    fun withNetworkKeys(json: String, controlFile: String?, mode: BranchyClusterMode? = null): String {
        val obj = BranchyJson.json.parseToJsonElement(json) as? JsonObject ?: return json
        val extra = buildMap<String, JsonElement> {
            put("connect_timeout_ms", JsonPrimitive(CONNECT_TIMEOUT_MS))
            put("message_timeout_ms", JsonPrimitive(MESSAGE_TIMEOUT_MS))
            put("join_window_ms", JsonPrimitive(JOIN_WINDOW_MS))
            if (mode == BranchyClusterMode.DATA_PARALLEL) put("rejoin_interval_ms", JsonPrimitive(DP_REJOIN_INTERVAL_MS))
            if (controlFile != null) put("control_file", JsonPrimitive(controlFile))
        }
        return BranchyJson.json.encodeToString(JsonObject.serializer(), JsonObject(obj + extra))
    }

    /** cluster.json for [plan], looking helper endpoints up in [helpers]; [helperThreads] (id to threads) is written as `threads` per helper. */
    fun clusterJson(
        clusterId: String,
        plan: BranchyPlan,
        helpers: List<BranchyClusterDeviceEntity>,
        controlFile: String? = null,
        helperThreads: Map<String, Int> = emptyMap(),
        planDevices: List<BranchyDevice> = emptyList()
    ): ClusterJsonResult {
        if (!plan.isCluster) return ClusterJsonResult.NotNeeded
        val ramById = planDevices.associate { it.id to it.ramMb }
        val endpoints = helpers.associate {
            it.id to ClusterEndpoint(it.host, it.port, it.gflops, helperThreads[it.id]?.takeIf { n -> n > 0 }, it.altHost, ramById[it.id] ?: 0L)
        }
        val (devices, missing) = BranchyClusterConfigWriter.devicesFromPlacement(plan.placement, endpoints)
        if (missing.isNotEmpty()) return ClusterJsonResult.MissingEndpoints(missing)
        if (devices.isEmpty()) return ClusterJsonResult.NotNeeded
        val mode = mode(plan)
        val json = BranchyClusterConfigWriter.write(
            clusterId = BranchyClusterConfigWriter.clusterId(clusterId), mode = mode, devices = devices,
            pipelineDepth = plan.pipelineDepth.coerceAtLeast(1), dp = dpSettings(plan),
            coordinatorRamMb = ramById[LOCAL_ID] ?: 0L
        )
        return ClusterJsonResult.Ready(withNetworkKeys(json, controlFile, mode), devices, mode)
    }

    /**
     * A minimal cluster.json for `cluster-bench`: one distinct branch per helper (the benchmark only needs the
     * endpoints). Null when [helpers] is empty.
     */
    fun benchClusterJson(clusterId: String, helpers: List<BranchyClusterDeviceEntity>): String? {
        if (helpers.isEmpty()) return null
        val devices = helpers.mapIndexed { i, h -> ClusterDeviceSpec(h.id, h.host, h.port, listOf(i), h.gflops, altHost = h.altHost) }
        return BranchyClusterConfigWriter.write(
            clusterId = BranchyClusterConfigWriter.clusterId(clusterId), mode = BranchyClusterMode.BRANCH_PARALLEL,
            devices = devices
        )
    }
}
