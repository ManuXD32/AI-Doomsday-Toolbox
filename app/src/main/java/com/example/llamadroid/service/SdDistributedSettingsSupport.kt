package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun List<SdDistributedWorkerEntity>.toSdPlanningWorkers(): List<SdDistributedPlanningWorker> =
    sortedWith(compareBy<SdDistributedWorkerEntity> { it.sortOrder }.thenBy { it.deviceName })
        .mapIndexed { index, worker ->
            SdDistributedPlanningWorker(
                id = worker.id,
                host = worker.host,
                port = worker.port,
                displayName = worker.deviceName,
                ramMB = worker.ramMB,
                threads = worker.threads,
                backendDevice = worker.backendDevice,
                rpcIndex = index
            )
        }

fun SdDistributedMasterSettingsEntity.toMasterPlanningWorker(): SdDistributedPlanningWorker? {
    if (!masterContributes) return null
    return SdDistributedPlanningWorker(
        id = -1L,
        host = "127.0.0.1",
        port = 0,
        displayName = masterDisplayName.ifBlank { "This device" },
        ramMB = masterRamMB.coerceAtLeast(512),
        threads = masterThreads.coerceAtLeast(1),
        backendDevice = masterBackendDevice.ifBlank { "cpu" },
        rpcIndex = -1,
        isLocalMaster = true,
        allowedModules = SdDistributedModules.normalizeCsv(masterAllowedModules),
        manualDiffusionSharePercent = masterDiffusionSharePercent.toIntOrNull()?.coerceIn(1, 95)
    )
}

fun SdDistributedMasterSettingsEntity.toPlanningWorkers(workers: List<SdDistributedWorkerEntity>): List<SdDistributedPlanningWorker> =
    listOfNotNull(toMasterPlanningWorker()) + workers.toSdPlanningWorkers()

fun SdDistributedMasterSettingsEntity.toRamPlannerOptions(): SdRamPlannerOptions =
    SdRamPlannerOptions(
        autoRamScope = SdDistributedAutoRamScope.fromStoredValue(autoRamScope)
    )

fun SdDistributedMasterSettingsEntity.toRuntimeConfig(plan: SdDistributedPlacementPlan): SdDistributedRuntimeConfig {
    val mode = runCatching {
        SdDistributedPlacementMode.valueOf(placementMode)
    }.getOrDefault(SdDistributedPlacementMode.AUTO_RAM)
    val split = SdDistributedSplitMode.fromCliName(splitMode)
    val useAutoFit = mode == SdDistributedPlacementMode.AUTO_FIT || autoFit
    val planBackends = mode == SdDistributedPlacementMode.AUTO_RAM
    val rpcServers = plan.rpcServers
    return SdDistributedRuntimeConfig(
        enabled = enabled && (rpcServers.isNotBlank() || plan.backendSpec.isNotBlank()),
        rpcServers = rpcServers,
        placementMode = mode,
        backendSpec = if (planBackends) plan.backendSpec else backendSpec.trim(),
        paramsBackendSpec = if (planBackends && paramsBackendSpec.isBlank()) {
            plan.paramsBackendSpec
        } else {
            paramsBackendSpec.trim()
        },
        autoFit = useAutoFit,
        maxVramSpec = if (maxVramEnabled) maxVramSpec.trim() else "",
        splitMode = split,
        customFlags = customFlags
    )
}

suspend fun restorePersistedSdDistributedRuntime(context: Context) {
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).sdDistributedDao()
        val settings = dao.getMasterSettings() ?: SdDistributedMasterSettingsEntity()
        val workers = dao.getEnabledWorkersOnce()
        val planningWorkers = settings.toPlanningWorkers(workers)
        val plan = buildRamWeightedSdPlacementPlan(planningWorkers, settings.toRamPlannerOptions())
        val runtimeConfig = settings.toRuntimeConfig(plan)
        val assignmentByWorker = assignmentsByRpc(plan)
        SdDistributedService.setRuntimeConfig(runtimeConfig)
        SdDistributedService.setActiveWorkers(
            planningWorkers.map { worker ->
                SdDistributedWorkerRuntime(
                    host = worker.host,
                    port = worker.port,
                    deviceName = worker.displayName,
                    ramMB = worker.ramMB,
                    threads = worker.threads,
                    backendDevice = worker.backendDevice,
                    rpcName = worker.rpcName,
                    isLocalMaster = worker.isLocalMaster,
                    assignedModules = assignmentByWorker[worker.rpcName].orEmpty().map { it.displayLabel },
                    plannedAssignments = assignmentByWorker[worker.rpcName].orEmpty()
                )
            }
        )
    }
}

fun settingsToJson(settings: SdDistributedMasterSettingsEntity): String =
    JSONObject()
        .put("enabled", settings.enabled)
        .put("placementMode", settings.placementMode)
        .put("backendSpec", settings.backendSpec)
        .put("paramsBackendSpec", settings.paramsBackendSpec)
        .put("autoFit", settings.autoFit)
        .put("autoRamScope", settings.autoRamScope)
        .put("maxVramEnabled", settings.maxVramEnabled)
        .put("maxVramSpec", settings.maxVramSpec)
        .put("splitMode", settings.splitMode)
        .put("customFlags", settings.customFlags)
        .put("prompt", settings.prompt)
        .put("negativePrompt", settings.negativePrompt)
        .put("dimensions", settings.dimensions)
        .put("steps", settings.steps)
        .put("cfg", settings.cfg)
        .put("seed", settings.seed)
        .put("sampler", settings.sampler)
        .put("scheduler", settings.scheduler)
        .put("batchCount", settings.batchCount)
        .put("clipSkip", settings.clipSkip)
        .put("strength", settings.strength)
        .put("frames", settings.frames)
        .put("fps", settings.fps)
        .put("runtimeThreads", settings.runtimeThreads)
        .put("mmap", settings.mmap)
        .put("diffusionFa", settings.diffusionFa)
        .put("vaeTiling", settings.vaeTiling)
        .put("vaeTileSize", settings.vaeTileSize)
        .put("vaeTileOverlap", settings.vaeTileOverlap)
        .put("flowShift", settings.flowShift)
        .put("quantization", settings.quantization)
        .put("tensorRules", settings.tensorRules)
        .put("loraStrength", settings.loraStrength)
        .put("controlStrength", settings.controlStrength)
        .put("cacheMode", settings.cacheMode)
        .put("cacheOption", settings.cacheOption)
        .put("scmMask", settings.scmMask)
        .put("scmPolicy", settings.scmPolicy)
        .put("masterContributes", settings.masterContributes)
        .put("masterDisplayName", settings.masterDisplayName)
        .put("masterRamMB", settings.masterRamMB)
        .put("masterThreads", settings.masterThreads)
        .put("masterBackendDevice", settings.masterBackendDevice)
        .put("masterAllowedModules", settings.masterAllowedModules)
        .put("masterDiffusionSharePercent", settings.masterDiffusionSharePercent)
        .put("imageWorkflowMode", settings.imageWorkflowMode)
        .put("imageModelPath", settings.imageModelPath)
        .put("imageUpscalerModelPath", settings.imageUpscalerModelPath)
        .put("imageInputPath", settings.imageInputPath)
        .put("imageVaePath", settings.imageVaePath)
        .put("imageTaePath", settings.imageTaePath)
        .put("imageClipLPath", settings.imageClipLPath)
        .put("imageClipGPath", settings.imageClipGPath)
        .put("imageT5xxlPath", settings.imageT5xxlPath)
        .put("imageLlmPath", settings.imageLlmPath)
        .put("imageLlmVisionPath", settings.imageLlmVisionPath)
        .put("imagePhotoMakerPath", settings.imagePhotoMakerPath)
        .put("imageControlNetEnabled", settings.imageControlNetEnabled)
        .put("imageControlNetPath", settings.imageControlNetPath)
        .put("imageLoraEnabled", settings.imageLoraEnabled)
        .put("imageLoraPath", settings.imageLoraPath)
        .put("imageLoraApplyMode", settings.imageLoraApplyMode)
        .put("imageCustomFlags", settings.imageCustomFlags)
        .put("videoWorkflowMode", settings.videoWorkflowMode)
        .put("videoModelPath", settings.videoModelPath)
        .put("videoInputPath", settings.videoInputPath)
        .put("videoUseVae", settings.videoUseVae)
        .put("videoVaePath", settings.videoVaePath)
        .put("videoUseT5xxl", settings.videoUseT5xxl)
        .put("videoT5xxlPath", settings.videoT5xxlPath)
        .put("videoCustomFlags", settings.videoCustomFlags)
        .toString()

fun settingsFromJson(json: String, base: SdDistributedMasterSettingsEntity = SdDistributedMasterSettingsEntity()): SdDistributedMasterSettingsEntity {
    val obj = runCatching { JSONObject(json) }.getOrElse { return base }
    return base.copy(
        updatedAt = System.currentTimeMillis(),
        enabled = obj.optBoolean("enabled", base.enabled),
        placementMode = obj.optString("placementMode", base.placementMode),
        backendSpec = obj.optString("backendSpec", base.backendSpec),
        paramsBackendSpec = obj.optString("paramsBackendSpec", base.paramsBackendSpec),
        autoFit = obj.optBoolean("autoFit", base.autoFit),
        autoRamScope = obj.optString("autoRamScope", base.autoRamScope),
        maxVramEnabled = obj.optBoolean("maxVramEnabled", base.maxVramEnabled),
        maxVramSpec = obj.optString("maxVramSpec", base.maxVramSpec),
        splitMode = obj.optString("splitMode", base.splitMode),
        customFlags = obj.optString("customFlags", base.customFlags),
        prompt = obj.optString("prompt", base.prompt),
        negativePrompt = obj.optString("negativePrompt", base.negativePrompt),
        dimensions = obj.optString("dimensions", base.dimensions),
        steps = obj.optString("steps", base.steps),
        cfg = obj.optString("cfg", base.cfg),
        seed = obj.optString("seed", base.seed),
        sampler = obj.optString("sampler", base.sampler),
        scheduler = obj.optString("scheduler", base.scheduler),
        batchCount = obj.optString("batchCount", base.batchCount),
        clipSkip = obj.optString("clipSkip", base.clipSkip),
        strength = obj.optString("strength", base.strength),
        frames = obj.optString("frames", base.frames),
        fps = obj.optString("fps", base.fps),
        runtimeThreads = obj.optString("runtimeThreads", base.runtimeThreads),
        mmap = obj.optBoolean("mmap", base.mmap),
        diffusionFa = obj.optBoolean("diffusionFa", base.diffusionFa),
        vaeTiling = obj.optBoolean("vaeTiling", base.vaeTiling),
        vaeTileSize = obj.optString("vaeTileSize", base.vaeTileSize),
        vaeTileOverlap = obj.optString("vaeTileOverlap", base.vaeTileOverlap),
        flowShift = obj.optString("flowShift", base.flowShift),
        quantization = obj.optString("quantization", base.quantization),
        tensorRules = obj.optString("tensorRules", base.tensorRules),
        loraStrength = obj.optString("loraStrength", base.loraStrength),
        controlStrength = obj.optString("controlStrength", base.controlStrength),
        cacheMode = obj.optString("cacheMode", base.cacheMode),
        cacheOption = obj.optString("cacheOption", base.cacheOption),
        scmMask = obj.optString("scmMask", base.scmMask),
        scmPolicy = obj.optString("scmPolicy", base.scmPolicy),
        masterContributes = obj.optBoolean("masterContributes", base.masterContributes),
        masterDisplayName = obj.optString("masterDisplayName", base.masterDisplayName),
        masterRamMB = obj.optInt("masterRamMB", base.masterRamMB),
        masterThreads = obj.optInt("masterThreads", base.masterThreads),
        masterBackendDevice = obj.optString("masterBackendDevice", base.masterBackendDevice),
        masterAllowedModules = obj.optString("masterAllowedModules", base.masterAllowedModules),
        masterDiffusionSharePercent = obj.optString("masterDiffusionSharePercent", base.masterDiffusionSharePercent),
        imageWorkflowMode = obj.optString("imageWorkflowMode", base.imageWorkflowMode),
        imageModelPath = obj.optString("imageModelPath", base.imageModelPath),
        imageUpscalerModelPath = obj.optString("imageUpscalerModelPath", base.imageUpscalerModelPath),
        imageInputPath = obj.optString("imageInputPath", base.imageInputPath),
        imageVaePath = obj.optString("imageVaePath", base.imageVaePath),
        imageTaePath = obj.optString("imageTaePath", base.imageTaePath),
        imageClipLPath = obj.optString("imageClipLPath", base.imageClipLPath),
        imageClipGPath = obj.optString("imageClipGPath", base.imageClipGPath),
        imageT5xxlPath = obj.optString("imageT5xxlPath", base.imageT5xxlPath),
        imageLlmPath = obj.optString("imageLlmPath", base.imageLlmPath),
        imageLlmVisionPath = obj.optString("imageLlmVisionPath", base.imageLlmVisionPath),
        imagePhotoMakerPath = obj.optString("imagePhotoMakerPath", base.imagePhotoMakerPath),
        imageControlNetEnabled = obj.optBoolean("imageControlNetEnabled", base.imageControlNetEnabled),
        imageControlNetPath = obj.optString("imageControlNetPath", base.imageControlNetPath),
        imageLoraEnabled = obj.optBoolean("imageLoraEnabled", base.imageLoraEnabled),
        imageLoraPath = obj.optString("imageLoraPath", base.imageLoraPath),
        imageLoraApplyMode = obj.optString("imageLoraApplyMode", base.imageLoraApplyMode),
        imageCustomFlags = obj.optString("imageCustomFlags", base.imageCustomFlags),
        videoWorkflowMode = obj.optString("videoWorkflowMode", base.videoWorkflowMode),
        videoModelPath = obj.optString("videoModelPath", base.videoModelPath),
        videoInputPath = obj.optString("videoInputPath", base.videoInputPath),
        videoUseVae = obj.optBoolean("videoUseVae", base.videoUseVae),
        videoVaePath = obj.optString("videoVaePath", base.videoVaePath),
        videoUseT5xxl = obj.optBoolean("videoUseT5xxl", base.videoUseT5xxl),
        videoT5xxlPath = obj.optString("videoT5xxlPath", base.videoT5xxlPath),
        videoCustomFlags = obj.optString("videoCustomFlags", base.videoCustomFlags)
    )
}

fun assignmentsByRpc(plan: SdDistributedPlacementPlan): Map<String, List<SdDistributedWorkerAssignment>> {
    val result = mutableMapOf<String, MutableList<SdDistributedWorkerAssignment>>()
    plan.assignments.forEach { assignment ->
        assignment.devices.forEach { rpc ->
            result.getOrPut(rpc) { mutableListOf() } += SdDistributedWorkerAssignment(
                module = assignment.module,
                displayLabel = moduleDisplayLabel(assignment.module),
                isSplit = assignment.isSplit,
                estimatedLayerShare = assignment.estimatedLayerShares[rpc] ?: if (assignment.isSplit) 0 else 100
            )
        }
    }
    return result
}

private fun moduleDisplayLabel(module: String): String = when (module) {
    "diffusion" -> "Diffusion"
    "te" -> "Text Encoder"
    "vae" -> "VAE / TAE"
    "upscaler" -> "Upscaler"
    else -> module
}
