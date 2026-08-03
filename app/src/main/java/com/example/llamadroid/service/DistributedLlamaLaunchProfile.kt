package com.example.llamadroid.service

import com.google.gson.Gson
import java.util.Locale

/**
 * Immutable, cross-process snapshot for one distributed llama.cpp launch.
 *
 * [config] is already fully resolved. The runtime process must not fill missing
 * master values from general LLM settings or from process-local StateFlows.
 */
data class DistributedLlamaLaunchProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val config: LlamaConfig,
    val workers: List<DistributedWorkerLaunchSpec>,
    val masterTargetRamMiB: Int,
    val transformerBlocks: Int = config.nGpuLayers.coerceAtLeast(1),
    val offloadableLayers: Int = transformerBlocks + 1,
    val mainLayerAllocations: List<DistributedLayerAllocation> = emptyList(),
    val kvPlacement: DistributedKvPlacement = DistributedKvPlacement(),
    val speculativePlacement: DistributedSpeculativePlacement,
    val speculativeWorkerIndex: Int? = null,
    val commandTemplate: String? = null,
    val customCommand: String? = null
) {
    init {
        require(config.modelPath.isNotBlank()) { "A model is required" }
        require(config.rpcWorkers == workers.map { it.address }) {
            "RPC worker order must match the launch profile"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3
        private val gson = Gson()

        fun encode(profile: DistributedLlamaLaunchProfile): String = gson.toJson(profile)

        fun decode(value: String?): DistributedLlamaLaunchProfile? = value
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching { gson.fromJson(encoded, DistributedLlamaLaunchProfile::class.java) }
                    .getOrNull()
                    ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            }
    }
}

data class DistributedWorkerLaunchSpec(
    val address: String,
    val ramMiB: Int,
    val assignedProportion: Float? = null,
    /** Stable database id. Older profiles use the address as their stable key. */
    val workerId: Long? = null
)

enum class DistributedDeviceKind { MASTER_CPU, MASTER_ACCELERATOR, WORKER }

data class DistributedDeviceRef(
    val kind: DistributedDeviceKind,
    val workerId: Long? = null,
    val workerAddress: String? = null
)

fun DistributedDeviceRef.stableKey(): String = when (kind) {
    DistributedDeviceKind.MASTER_CPU -> "master_cpu"
    DistributedDeviceKind.MASTER_ACCELERATOR -> "master_accelerator"
    DistributedDeviceKind.WORKER -> workerId?.let { "worker:$it" }
        ?: "worker:${workerAddress.orEmpty()}"
}

enum class MainModelPlacementMode { RESIDENT, DISTRIBUTED }

data class MainModelPlacement(
    val mode: MainModelPlacementMode = MainModelPlacementMode.DISTRIBUTED,
    val devices: List<DistributedDeviceRef> = emptyList(),
    /** Stable-key to relative share. Only used for DISTRIBUTED target placement. */
    val shares: Map<String, Float> = emptyMap(),
    /** Explicit RAM contribution for each stable device key. */
    val ramContributionsMiB: Map<String, Int> = emptyMap()
)

data class DistributedLayerAllocation(
    val device: DistributedDeviceRef,
    val contributedRamMiB: Int,
    val normalizedShare: Float,
    val assignedLayers: Int
)

enum class DistributedKvPlacementMode { DISTRIBUTED_WITH_LAYERS, MASTER_CPU, ACCELERATOR_DEVICE }

data class DistributedKvPlacement(
    val mode: DistributedKvPlacementMode = DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS,
    val device: DistributedDeviceRef? = null
)

enum class DraftModelPlacementMode { RESIDENT, DISTRIBUTED }

data class DraftModelPlacement(
    val mode: DraftModelPlacementMode = DraftModelPlacementMode.RESIDENT,
    val devices: List<DistributedDeviceRef> = emptyList()
)

enum class MmprojPlacement { MASTER_CPU, MASTER_ACCELERATOR }

/** The single launch artifact consumed by preview and the isolated runtime. */
data class ResolvedDistributedLaunch(
    val schemaVersion: Int = SCHEMA_VERSION,
    val profile: DistributedLlamaLaunchProfile,
    val binaryPath: String,
    val argv: List<String>,
    val endpointHost: String,
    val endpointPort: Int,
    val workerDeviceOrder: Map<String, String>
) {
    init {
        require(binaryPath.isNotBlank())
        require(argv.isNotEmpty() && argv.first() == binaryPath)
        require(endpointPort in 1..65535)
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private val gson = Gson()
        fun encode(value: ResolvedDistributedLaunch): String = gson.toJson(value)
        fun decode(value: String?): ResolvedDistributedLaunch? = value
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { gson.fromJson(it, ResolvedDistributedLaunch::class.java) }.getOrNull() }
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
    }
}

data class DistributedLlamaResolveRequest(
    val modelPath: String,
    val modelSizeMiB: Int,
    val modelLayers: Int,
    val transformerBlocks: Int = (modelLayers - 1).coerceAtLeast(1),
    val workers: List<DistributedWorkerLaunchSpec>,
    val masterTargetRamMiB: Int,
    val host: String,
    val port: Int = 8080,
    val threads: Int,
    val batchSize: Int,
    val contextSize: Int,
    val temperature: Float,
    val kvCacheEnabled: Boolean,
    val kvCacheTypeK: String,
    val kvCacheTypeV: String,
    val kvCacheReuse: Int,
    val fitEnabled: Boolean,
    val fitTargetMiB: String?,
    val nGpuLayersArgument: String?,
    val speculativeMode: LlamaSpeculativeMode?,
    val draftModelPath: String?,
    val draftModelSizeMiB: Int = 0,
    val draftMax: Int,
    val draftMin: Int,
    val draftPMin: Float,
    val draftThreads: Int,
    val draftThreadsBatch: Int,
    val draftDeviceMode: String,
    val draftGpuLayers: String?,
    val mtpDraftMax: Int,
    val mtpDraftMin: Int,
    val mtpDraftPMin: Float,
    val ngramModNMatch: Int,
    val ngramModNMin: Int,
    val ngramModNMax: Int,
    val ngramSimpleSizeN: Int,
    val ngramSimpleSizeM: Int,
    val ngramSimpleMinHits: Int,
    val ngramMapKSizeN: Int,
    val ngramMapKSizeM: Int,
    val ngramMapKMinHits: Int,
    val ngramMapK4VSizeN: Int,
    val ngramMapK4VSizeM: Int,
    val ngramMapK4VMinHits: Int,
    val speculativePlacement: DistributedSpeculativePlacement,
    val speculativeWorkerIndex: Int?,
    val parallel: Int?,
    val cacheRam: Int?,
    val customFlags: String?,
    val commandTemplate: String?,
    val customCommand: String?,
    val flashAttention: Boolean,
    val mainPlacement: MainModelPlacement? = null,
    val draftPlacement: DraftModelPlacement? = null,
    val mmprojPath: String? = null,
    val mmprojPlacement: MmprojPlacement = MmprojPlacement.MASTER_ACCELERATOR,
    val kvPlacement: DistributedKvPlacement = DistributedKvPlacement()
)

object DistributedLlamaLaunchResolver {
    fun resolve(request: DistributedLlamaResolveRequest): DistributedLlamaLaunchProfile {
        require(request.workers.isNotEmpty()) { "At least one distributed worker is required" }
        require(request.modelLayers > 0) { "Model layer count must be positive" }
        val workerIndex = request.speculativeWorkerIndex
            ?.takeIf { it in request.workers.indices }
        val remoteDraft = request.speculativePlacement == DistributedSpeculativePlacement.WORKER_SHARED ||
            request.speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED
        require(!remoteDraft || workerIndex != null) { "A speculative worker must be selected" }
        require(request.speculativePlacement != DistributedSpeculativePlacement.MASTER_DEDICATED ||
            request.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
            "Dedicated master placement is only supported for MTP"
        }

        fun workerIndex(ref: DistributedDeviceRef): Int = request.workers.indexOfFirst { worker ->
            (ref.workerId != null && worker.workerId == ref.workerId) ||
                (!ref.workerAddress.isNullOrBlank() && worker.address == ref.workerAddress)
        }
        val placement = request.mainPlacement ?: MainModelPlacement(
            mode = MainModelPlacementMode.DISTRIBUTED,
            devices = buildList {
                if (request.masterTargetRamMiB > 0) add(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU))
                request.workers.forEach { worker ->
                    add(DistributedDeviceRef(DistributedDeviceKind.WORKER, worker.workerId, worker.address))
                }
            }
        )
        require(placement.devices.isNotEmpty()) { "At least one main-model device is required" }
        val selectedTargetWorkerIndexes = placement.devices
            .filter { it.kind == DistributedDeviceKind.WORKER }
            .map(::workerIndex)
            .onEach { require(it >= 0) { "A selected main-model worker is unavailable" } }
            .toSet()
        val workerRams = request.workers.mapIndexed { index, worker ->
            val reserved = if (remoteDraft && index == workerIndex) request.draftModelSizeMiB else 0
            val remaining = (worker.ramMiB - reserved).coerceAtLeast(0)
            if (index !in selectedTargetWorkerIndexes) {
                0
            } else if (request.speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED && index == workerIndex) {
                0
            } else {
                remaining
            }
        }
        fun contribution(ref: DistributedDeviceRef): Int {
            placement.ramContributionsMiB[ref.stableKey()]?.let { return it.coerceAtLeast(0) }
            return when (ref.kind) {
                DistributedDeviceKind.MASTER_CPU, DistributedDeviceKind.MASTER_ACCELERATOR -> request.masterTargetRamMiB
                DistributedDeviceKind.WORKER -> workerRams.getOrElse(workerIndex(ref)) { 0 }
            }
        }
        val layerAllocations = allocateLayersByRam(
            layers = request.modelLayers,
            devices = if (placement.mode == MainModelPlacementMode.RESIDENT) placement.devices.take(1) else placement.devices,
            ramMiB = ::contribution
        )
        val selectedMainRefs = layerAllocations.map { it.device }
        val draftWorkerRefs = request.draftPlacement?.devices.orEmpty()
            .filter { it.kind == DistributedDeviceKind.WORKER }
        val usedWorkerIndexes = (selectedMainRefs + draftWorkerRefs)
            .filter { it.kind == DistributedDeviceKind.WORKER }
            .map(::workerIndex)
            .filter { it >= 0 }
            .toSet()
        val usedWorkers = request.workers.filterIndexed { index, _ -> index in usedWorkerIndexes }
        val effectiveSpeculativeWorkerIndex = request.workers.getOrNull(workerIndex ?: -1)?.address?.let { address ->
            usedWorkers.indexOfFirst { it.address == address }.takeIf { it >= 0 }
        }
        fun rpcName(ref: DistributedDeviceRef): String? {
            if (ref.kind != DistributedDeviceKind.WORKER) return null
            val address = request.workers.getOrNull(workerIndex(ref))?.address ?: return null
            return usedWorkers.indexOfFirst { it.address == address }.takeIf { it >= 0 }?.let { "RPC$it" }
        }
        val targetDevices = selectedMainRefs.mapNotNull { ref ->
            when (ref.kind) {
                DistributedDeviceKind.MASTER_CPU -> null
                DistributedDeviceKind.MASTER_ACCELERATOR -> "GPUOpenCL"
                DistributedDeviceKind.WORKER -> rpcName(ref)
            }
        }.distinct()
        val kvHost = request.kvPlacement.device
        val effectiveSplitMode = when {
            request.kvPlacement.mode == DistributedKvPlacementMode.ACCELERATOR_DEVICE -> "row"
            placement.mode == MainModelPlacementMode.RESIDENT -> "none"
            targetDevices.size > 1 -> "layer"
            else -> null
        }
        val mainGpu = if (request.kvPlacement.mode == DistributedKvPlacementMode.ACCELERATOR_DEVICE) {
            requireNotNull(kvHost) { "A KV host device is required" }
            val name = when (kvHost.kind) {
                DistributedDeviceKind.MASTER_CPU -> error("Master CPU uses CPU KV placement")
                DistributedDeviceKind.MASTER_ACCELERATOR -> "GPUOpenCL"
                DistributedDeviceKind.WORKER -> rpcName(kvHost)
            }
            targetDevices.indexOf(name).takeIf { it >= 0 }
                ?: error("The selected KV host must participate in main-model placement")
        } else null
        val acceleratorAllocations = layerAllocations.filter { it.device.kind != DistributedDeviceKind.MASTER_CPU }
        val rpcLayers = acceleratorAllocations.sumOf { it.assignedLayers }
        val acceleratorShareTotal = acceleratorAllocations.sumOf { it.normalizedShare.toDouble() }
        val tensorSplit = acceleratorAllocations.takeIf { it.size > 1 }?.joinToString(",") {
            String.format(Locale.US, "%.4f", it.normalizedShare / acceleratorShareTotal)
        }
        val fitTarget = if (request.fitEnabled) {
            DistributedLlamaArguments.normalizeFitTarget(request.fitTargetMiB, usedWorkers.size)
        } else null
        val draftDeviceId = request.draftPlacement?.devices
            ?.mapNotNull { ref ->
                when (ref.kind) {
                    DistributedDeviceKind.MASTER_CPU -> "none"
                    DistributedDeviceKind.MASTER_ACCELERATOR -> "GPUOpenCL"
                    DistributedDeviceKind.WORKER -> rpcName(ref)
                }
            }
            ?.distinct()
            ?.joinToString(",")
            ?.takeIf(String::isNotBlank)
            ?: if (remoteDraft) request.workers.getOrNull(workerIndex ?: -1)?.address?.let { address ->
                usedWorkers.indexOfFirst { it.address == address }.takeIf { it >= 0 }?.let { "RPC$it" }
            } else null
        val effectiveDraftPath = request.draftModelPath?.takeIf {
            request.speculativeMode != LlamaSpeculativeMode.DRAFT_MTP || it.isNotBlank()
        }

        val config = LlamaConfig(
            modelPath = request.modelPath,
            contextSize = request.contextSize,
            threads = request.threads,
            batchSize = request.batchSize,
            port = request.port,
            temperature = request.temperature,
            host = request.host,
            mmprojPath = request.mmprojPath,
            mmprojOffload = request.mmprojPath?.let { request.mmprojPlacement == MmprojPlacement.MASTER_ACCELERATOR },
            kvCacheEnabled = request.kvCacheEnabled,
            kvCacheTypeK = request.kvCacheTypeK,
            kvCacheTypeV = request.kvCacheTypeV,
            kvCacheReuse = request.kvCacheReuse,
            kvOffloadMode = when (request.kvPlacement.mode) {
                DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS,
                DistributedKvPlacementMode.ACCELERATOR_DEVICE -> LlamaKvOffloadMode.ACCELERATOR.value
                DistributedKvPlacementMode.MASTER_CPU -> LlamaKvOffloadMode.CPU.value
            },
            rpcWorkers = usedWorkers.map { it.address },
            targetDevices = targetDevices,
            splitMode = effectiveSplitMode,
            mainGpu = mainGpu,
            nGpuLayers = rpcLayers,
            nGpuLayersArgument = when {
                selectedMainRefs.singleOrNull()?.kind == DistributedDeviceKind.MASTER_CPU -> "0"
                rpcLayers > 0 -> rpcLayers.toString()
                else -> null
            },
            tensorSplit = tensorSplit,
            fitEnabled = request.fitEnabled,
            fitTargetMiB = fitTarget,
            speculativeMode = request.speculativeMode,
            draftModelPath = effectiveDraftPath,
            draftMax = request.draftMax,
            draftMin = request.draftMin,
            draftPMin = request.draftPMin,
            draftThreads = request.draftThreads,
            draftThreadsBatch = request.draftThreadsBatch,
            draftDeviceMode = request.draftDeviceMode,
            draftDeviceId = draftDeviceId,
            draftGpuLayers = request.draftGpuLayers.takeIf { remoteDraft },
            mtpDraftMax = request.mtpDraftMax,
            mtpDraftMin = request.mtpDraftMin,
            mtpDraftPMin = request.mtpDraftPMin,
            ngramModNMatch = request.ngramModNMatch,
            ngramModNMin = request.ngramModNMin,
            ngramModNMax = request.ngramModNMax,
            ngramSimpleSizeN = request.ngramSimpleSizeN,
            ngramSimpleSizeM = request.ngramSimpleSizeM,
            ngramSimpleMinHits = request.ngramSimpleMinHits,
            ngramMapKSizeN = request.ngramMapKSizeN,
            ngramMapKSizeM = request.ngramMapKSizeM,
            ngramMapKMinHits = request.ngramMapKMinHits,
            ngramMapK4VSizeN = request.ngramMapK4VSizeN,
            ngramMapK4VSizeM = request.ngramMapK4VSizeM,
            ngramMapK4VMinHits = request.ngramMapK4VMinHits,
            parallel = request.parallel,
            cacheRam = request.cacheRam,
            emitDefaultCachePolicyArgs = false,
            customFlags = request.customFlags,
            flashAttention = request.flashAttention
        )
        DistributedLlamaArguments.validate(
            deviceCount = targetDevices.size.coerceAtLeast(usedWorkers.size),
            fitEnabled = config.fitEnabled,
            fitTargetMiB = config.fitTargetMiB,
            tensorSplit = config.tensorSplit
        )
        return DistributedLlamaLaunchProfile(
            config = config,
            workers = usedWorkers,
            masterTargetRamMiB = request.masterTargetRamMiB,
            transformerBlocks = request.transformerBlocks,
            offloadableLayers = request.modelLayers,
            mainLayerAllocations = layerAllocations,
            kvPlacement = request.kvPlacement,
            speculativePlacement = request.speculativePlacement,
            speculativeWorkerIndex = effectiveSpeculativeWorkerIndex,
            commandTemplate = request.commandTemplate?.takeIf(String::isNotBlank),
            customCommand = request.customCommand?.takeIf(String::isNotBlank)
        )
    }

    internal fun allocateLayersByRam(
        layers: Int,
        devices: List<DistributedDeviceRef>,
        ramMiB: (DistributedDeviceRef) -> Int
    ): List<DistributedLayerAllocation> {
        require(layers > 0)
        require(devices.isNotEmpty())
        val contributions = devices.map { ramMiB(it).coerceAtLeast(0) }
        require(contributions.sum() > 0) { "Selected main-model devices contribute no RAM" }
        val total = contributions.sum().toDouble()
        val exact = contributions.map { layers * it / total }
        val assigned = exact.map(Double::toInt).toMutableList()
        var remaining = layers - assigned.sum()
        exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - assigned[it] }.thenBy { it }
        ).forEach { index ->
            if (remaining > 0) {
                assigned[index]++
                remaining--
            }
        }
        return devices.mapIndexed { index, device ->
            DistributedLayerAllocation(
                device = device,
                contributedRamMiB = contributions[index],
                normalizedShare = (contributions[index] / total).toFloat(),
                assignedLayers = assigned[index]
            )
        }
    }
}
