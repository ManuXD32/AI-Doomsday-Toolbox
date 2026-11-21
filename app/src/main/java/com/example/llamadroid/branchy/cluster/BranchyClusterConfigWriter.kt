package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.branchy.BranchyPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parallelism mode of a cluster run (cluster.json `mode`). */
enum class BranchyClusterMode(val value: String) {
    BRANCH_PARALLEL("branch_parallel"), DATA_PARALLEL("data_parallel")
}

/** One entry of cluster.json `devices`. */
data class ClusterDeviceSpec(
    val id: String,
    val host: String,
    val port: Int,
    val branches: List<Int>,
    val gflops: Double? = null,
    /** Helper thread count the master applies after connecting (`set_threads`); null/<= 0 omits the key. */
    val threads: Int? = null,
    /** Other address to try after [host] (VPN vs LAN); written as `hosts:[host, altHost]`. */
    val altHost: String? = null,
    /** Device RAM in MB; written as `ram_mb` when > 0 so the native rebalance may grow this device. */
    val ramMb: Long = 0L
)

/** cluster.json `dp` block (data-parallel local SGD). */
data class ClusterDpSettings(
    val syncEvery: Int = 64,
    val syncSeconds: Int = 0,
    val bits: Int = 8,
    val outerMomentum: Double = 0.0
)

/** Where a device from the plan is reached. */
data class ClusterEndpoint(
    val host: String,
    val port: Int,
    val gflops: Double? = null,
    val threads: Int? = null,
    val altHost: String? = null,
    val ramMb: Long = 0L
)

/** Pure builder of `cluster.json` (see `BC/src/cluster_config.cpp`). */
object BranchyClusterConfigWriter {
    private val pretty = Json { prettyPrint = true }

    /**
     * Device specs from a plan: every placement that is not the coordinator and has branches, looked up in
     * [endpoints] by device id. When the coordinator itself holds branches and [includeCoordinator] is set, a
     * `coordinator` entry on `127.0.0.1:[coordinatorPort]` is added. Devices without an endpoint are returned in
     * the second element so the caller can report them instead of writing a broken file.
     */
    fun devicesFromPlacement(
        placement: List<BranchyPlacement>,
        endpoints: Map<String, ClusterEndpoint>,
        includeCoordinator: Boolean = false,
        coordinatorPort: Int = BranchyClusterIds.DEFAULT_WORKER_PORT
    ): Pair<List<ClusterDeviceSpec>, List<String>> {
        val devices = mutableListOf<ClusterDeviceSpec>()
        val missing = mutableListOf<String>()
        for (p in placement) {
            if (p.branches.isEmpty()) continue
            if (p.role == "coordinator") {
                if (includeCoordinator) {
                    devices += ClusterDeviceSpec(
                        BranchyClusterIds.COORDINATOR_ID, BranchyClusterIds.COORDINATOR_HOST,
                        coordinatorPort, p.branches.sorted()
                    )
                }
                continue
            }
            val ep = endpoints[p.device]
            if (ep == null) missing += p.device
            else devices += ClusterDeviceSpec(p.device, ep.host, ep.port, p.branches.sorted(), ep.gflops, ep.threads, ep.altHost, ep.ramMb)
        }
        return devices to missing
    }

    /** The cluster.json text for [devices]; `dp` is written only in [BranchyClusterMode.DATA_PARALLEL]. */
    fun write(
        clusterId: String,
        mode: BranchyClusterMode,
        devices: List<ClusterDeviceSpec>,
        wire: String = "f32",
        pipelineDepth: Int = 2,
        absorbMicrobatches: Int = 3,
        dp: ClusterDpSettings = ClusterDpSettings(),
        coordinatorRamMb: Long = 0L
    ): String {
        require(devices.isNotEmpty()) { "cluster needs at least one device" }
        val obj: JsonObject = buildJsonObject {
            put("cluster_id", clusterId)
            put("mode", mode.value)
            // Native cluster_config accepts a port-less `coordinator` entry (RAM only, no branches) for rebalance growth.
            val coordRam = coordinatorRamMb.takeIf { it > 0L && devices.none { d -> d.id == BranchyClusterIds.COORDINATOR_ID } }
            put("devices", JsonArray(devices.map { d ->
                buildJsonObject {
                    put("id", d.id)
                    put("host", d.host)
                    d.altHost?.takeIf { it.isNotBlank() && it != d.host }?.let { alt ->
                        put("hosts", buildJsonArray { add(JsonPrimitive(d.host)); add(JsonPrimitive(alt)) })
                    }
                    put("port", d.port)
                    put("branches", buildJsonArray { d.branches.sorted().forEach { add(JsonPrimitive(it)) } })
                    d.gflops?.takeIf { it > 0.0 && it.isFinite() }?.let { put("gflops", it) }
                    d.threads?.takeIf { it > 0 }?.let { put("threads", it) }
                    (d.ramMb.takeIf { it > 0L } ?: coordinatorRamMb.takeIf { d.id == BranchyClusterIds.COORDINATOR_ID && it > 0L })
                        ?.let { put("ram_mb", it) }
                }
            } + listOfNotNull(coordRam?.let { ram ->
                buildJsonObject { put("id", BranchyClusterIds.COORDINATOR_ID); put("ram_mb", ram) }
            })))
            put("wire", wire)
            put("pipeline_depth", pipelineDepth)
            put("absorb_microbatches", absorbMicrobatches)
            if (mode == BranchyClusterMode.DATA_PARALLEL) {
                put("dp", buildJsonObject {
                    put("sync_every", dp.syncEvery)
                    put("sync_seconds", dp.syncSeconds)
                    put("bits", dp.bits)
                    put("outer_momentum", dp.outerMomentum)
                    put("step_mode", "auto")
                })
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }

    /** A cluster id valid for the native `[a-z0-9_.-]+` rule, e.g. from a run name. */
    fun clusterId(seed: String): String {
        val s = seed.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-').take(40)
        return s.ifEmpty { "cluster" }
    }
}
