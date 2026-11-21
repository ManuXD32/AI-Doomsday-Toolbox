package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdImg2ImgBlock
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import com.example.llamadroid.service.SdDistributedModules
import com.example.llamadroid.service.SdDistributedPlacementPlan
import com.example.llamadroid.service.SdDistributedPlanningWorker
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdDistributedWorkerRuntime
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.assignmentsByRpc
import com.example.llamadroid.service.buildRamWeightedSdPlacementPlan
import com.example.llamadroid.service.imageLoras
import com.example.llamadroid.service.toPlanningWorkers
import com.example.llamadroid.service.toRamPlannerOptions
import com.example.llamadroid.service.selectedDiffusionModelBytes
import com.example.llamadroid.service.toRuntimeConfig
import com.example.llamadroid.service.videoHighNoiseLoras
import com.example.llamadroid.service.videoLoras
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure builders for the main-device runtime and for the image / video launch configs, extracted
 * from the former `SdDistributedMasterContent` so they can be unit tested without Compose.
 */

/** Planner output for the current settings plus the Helpers handed to the service. */
internal data class SdMasterRuntime(
    val planningWorkers: List<SdDistributedPlanningWorker>,
    val plan: SdDistributedPlacementPlan,
    val runtimeConfig: SdDistributedRuntimeConfig,
    val workerRuntimes: List<SdDistributedWorkerRuntime>
)

internal fun buildSdMasterRuntime(
    settings: SdDistributedMasterSettingsEntity,
    workers: List<SdDistributedWorkerEntity>,
    /** true = video run, false = image run, null = unknown (larger of the two models). */
    video: Boolean? = null
): SdMasterRuntime {
    val planningWorkers = settings.toPlanningWorkers(workers.filter { it.isEnabled })
    val plan = buildRamWeightedSdPlacementPlan(
        planningWorkers,
        settings.toRamPlannerOptions(settings.selectedDiffusionModelBytes(video))
    )
    val assignmentByRpc = assignmentsByRpc(plan)
    return SdMasterRuntime(
        planningWorkers = planningWorkers,
        plan = plan,
        runtimeConfig = settings.toRuntimeConfig(plan),
        workerRuntimes = planningWorkers.map {
            SdDistributedWorkerRuntime(
                host = it.host,
                port = it.port,
                deviceName = it.displayName,
                ramMB = it.ramMB,
                threads = it.threads,
                backendDevice = it.backendDevice,
                rpcName = it.rpcName,
                isLocalMaster = it.isLocalMaster,
                assignedModules = assignmentByRpc[it.rpcName].orEmpty().map { assignment -> assignment.module },
                plannedAssignments = assignmentByRpc[it.rpcName].orEmpty()
            )
        }
    )
}

/** `yyyyMMdd_HHmmss` stamp used in generated file names. */
internal fun sdMasterStamp(date: Date): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)

/** Output PNG for an image run (creates the mode sub-folder). */
internal fun sdMasterImageOutputFile(filesDir: File, mode: SDMode, date: Date = Date()): File {
    val subfolder = when (mode) {
        SDMode.TXT2IMG -> "txt2img"
        SDMode.IMG2IMG -> "img2img"
        SDMode.ADETAILER -> "adetailer"
        SDMode.UPSCALE -> "upscaled"
    }
    val dir = File(File(filesDir, "sd_output").apply { mkdirs() }, subfolder).apply { mkdirs() }
    return File(dir, "sd_${sdMasterStamp(date)}.png")
}

/** Folder for a video run's outputs (creates it). */
internal fun sdMasterVideoOutputDir(filesDir: File, mode: VideoGenerationMode): File =
    File(File(filesDir, "video_gen_output").apply { mkdirs() }, mode.folderName).apply { mkdirs() }

internal fun sdMasterVideoBaseName(date: Date = Date()): String = "video_${sdMasterStamp(date)}"

/** Image SDConfig for TXT2IMG / IMG2IMG / ADETAILER. Callers validate dimensions first. */
internal fun buildSdImageConfig(
    settings: SdDistributedMasterSettingsEntity,
    runtimeConfig: SdDistributedRuntimeConfig,
    outputPath: String,
    modelFamily: String?,
    modelVariant: String?,
    sigmas: String = ""
): SDConfig {
    val mode = settings.imageWorkflowMode.toSdMasterImageMode()
    val sampler = SamplingMethod.entries.firstOrNull {
        it.cliName == settings.imageSampler || it.name == settings.imageSampler
    } ?: SamplingMethod.EULER_A
    val cacheMode = SdCacheMode.fromStoredValue(settings.cacheMode)
    val scmPolicy = SdCacheScmPolicy.fromStoredValue(settings.scmPolicy)
    return SDConfig(
        mode = mode,
        modelPath = settings.imageModelPath,
        prompt = settings.imagePrompt,
        negativePrompt = settings.imageNegativePrompt,
        width = requireNotNull(parseSdDistributedDimension(settings.imageWidth)),
        height = requireNotNull(parseSdDistributedDimension(settings.imageHeight)),
        steps = settings.imageSteps.toIntOrNull() ?: 20,
        cfgScale = settings.imageCfg.toFloatOrNull() ?: 7.0f,
        seed = settings.imageSeed.toLongOrNull() ?: -1L,
        samplingMethod = sampler,
        scheduler = SdScheduler.fromCliName(settings.imageScheduler),
        outputPath = outputPath,
        initImage = settings.imageInputPath.takeIf { mode == SDMode.IMG2IMG },
        strength = settings.strength.toFloatOrNull() ?: 0.75f,
        threads = settings.runtimeThreads.toIntOrNull() ?: -1,
        vaeTiling = settings.vaeTiling,
        vaeTileOverlap = settings.vaeTileOverlap.toFloatOrNull() ?: 0f,
        vaeTileSize = settings.vaeTileSize.ifBlank { "32x32" },
        tensorTypeRules = settings.tensorRules,
        modelFamily = modelFamily,
        modelVariant = modelVariant,
        vaePath = settings.imageVaePath.ifBlank { null },
        taePath = settings.imageTaePath.ifBlank { null },
        clipLPath = settings.imageClipLPath.ifBlank { null },
        clipGPath = settings.imageClipGPath.ifBlank { null },
        t5xxlPath = settings.imageT5xxlPath.ifBlank { null },
        llmPath = settings.imageLlmPath.ifBlank { null },
        llmVisionPath = settings.imageLlmVisionPath.ifBlank { null },
        photoMakerPath = settings.imagePhotoMakerPath.ifBlank { null },
        controlNetPath = if (settings.imageControlNetEnabled) settings.imageControlNetPath.ifBlank { null } else null,
        controlImagePath = if (settings.imageControlNetEnabled && mode == SDMode.IMG2IMG) {
            settings.imageInputPath.ifBlank { null }
        } else {
            null
        },
        controlStrength = settings.controlStrength.toFloatOrNull() ?: 0.9f,
        loraPath = if (settings.imageLoraEnabled) settings.imageLoraPath.ifBlank { null } else null,
        loraStrength = settings.loraStrength.toFloatOrNull() ?: 1.0f,
        loraApplyMode = if (settings.imageLoraEnabled) SdLoraApplyMode.fromStoredValue(settings.imageLoraApplyMode) else null,
        loras = if (settings.imageLoraEnabled) settings.imageLoras() else emptyList(),
        cacheMode = cacheMode,
        cacheOption = if (cacheMode != null) settings.cacheOption else "",
        scmMask = if (cacheMode == SdCacheMode.CACHE_DIT) settings.scmMask else "",
        scmPolicy = if (cacheMode == SdCacheMode.CACHE_DIT) scmPolicy else null,
        flowShift = settings.imageFlowShift.toFloatOrNull(),
        sigmas = sigmas,
        diffusionFa = settings.diffusionFa,
        mmap = settings.mmap,
        // Keep local parameter residency out of distributed commands.
        sdParamsBackendSpec = "auto",
        sdParamsBackendMode = "auto",
        sdRuntimeBackendMode = "auto",
        quantizationType = settings.quantization,
        distributedRuntime = runtimeConfig,
        customFlags = settings.imageCustomFlags
    )
}

/** Upscale-only config (mode UPSCALE). */
internal fun buildSdUpscaleConfig(
    settings: SdDistributedMasterSettingsEntity,
    runtimeConfig: SdDistributedRuntimeConfig,
    outputPath: String
): SDUpscaleConfig = SDUpscaleConfig(
    modelPath = settings.imageUpscalerModelPath,
    inputImagePath = settings.imageInputPath,
    outputPath = outputPath,
    threads = settings.runtimeThreads.toIntOrNull() ?: -1,
    // Distributed placement is selected by the remote runtime; never inherit the local model's residency.
    sdParamsBackendSpec = "auto",
    sdParamsBackendMode = "auto",
    sdRuntimeBackendMode = "auto",
    maxVramCpuGiB = "",
    distributedRuntime = runtimeConfig,
    customFlags = settings.imageCustomFlags
)

/** Module -> model paths for the run monitor's transfer estimate (image). */
internal fun sdMasterImageModulePaths(settings: SdDistributedMasterSettingsEntity): Map<String, List<String?>> {
    val mode = settings.imageWorkflowMode.toSdMasterImageMode()
    return mapOf(
        SdDistributedModules.DIFFUSION to listOf(settings.imageModelPath.takeIf { mode != SDMode.UPSCALE }),
        SdDistributedModules.TEXT_ENCODER to listOf(
            settings.imageClipLPath,
            settings.imageClipGPath,
            settings.imageT5xxlPath,
            settings.imageLlmPath,
            settings.imageLlmVisionPath
        ),
        SdDistributedModules.VAE to listOf(settings.imageVaePath, settings.imageTaePath),
        SdDistributedModules.CONTROLNET to listOf(settings.imageControlNetPath.takeIf { settings.imageControlNetEnabled }),
        SdDistributedModules.UPSCALER to listOf(settings.imageUpscalerModelPath.takeIf { mode == SDMode.UPSCALE })
    )
}

/** First blocking issue for an image run, or null. [missingRoles] come from the selected family spec. */
internal fun sdMasterImageLaunchIssue(
    settings: SdDistributedMasterSettingsEntity,
    missingRoles: List<SdComponentRole>,
    img2imgBlock: SdImg2ImgBlock? = null
): SdDistributedLaunchIssue? = sdDistributedImageLaunchIssue(
    mode = settings.imageWorkflowMode.toSdMasterImageMode(),
    modelPath = settings.imageModelPath,
    prompt = settings.imagePrompt,
    width = parseSdDistributedDimension(settings.imageWidth),
    height = parseSdDistributedDimension(settings.imageHeight),
    missingRequiredRoles = missingRoles,
    inputPath = settings.imageInputPath,
    upscalerModelPath = settings.imageUpscalerModelPath,
    controlNetEnabled = settings.imageControlNetEnabled,
    controlNetPath = settings.imageControlNetPath,
    loraEnabled = settings.imageLoraEnabled,
    loraPath = settings.imageLoraPath,
    img2imgBlock = img2imgBlock
)

/** The video options merged with the legacy scalar columns (card state wins only where the editor is empty). */
internal fun effectiveSdVideoOptions(
    settings: SdDistributedMasterSettingsEntity,
    options: VideoRuntimeOptions
): VideoRuntimeOptions {
    val mode = settings.videoWorkflowMode.toSdMasterVideoMode()
    return options.copy(
        workflow = options.workflow ?: if (mode == VideoGenerationMode.IMG2VID) {
            SdVideoWorkflow.IMAGE_TO_VIDEO
        } else {
            SdVideoWorkflow.TEXT_TO_VIDEO
        },
        videoComponents = options.videoComponents.copy(
            diffusionModelPath = options.videoComponents.diffusionModelPath ?: settings.videoModelPath.ifBlank { null },
            fullModelPath = options.videoComponents.fullModelPath ?: settings.videoModelPath.ifBlank { null },
            vaePath = options.videoComponents.vaePath ?: settings.videoVaePath.takeIf { settings.videoUseVae },
            t5xxlPath = options.videoComponents.t5xxlPath ?: settings.videoT5xxlPath.takeIf { settings.videoUseT5xxl }
        ),
        videoInputs = options.videoInputs.copy(
            initImagePath = options.videoInputs.initImagePath ?: settings.videoInputPath.ifBlank { null }
        ),
        seed = options.seed.takeIf { it != -1L } ?: settings.videoSeed.toLongOrNull() ?: -1L
    )
}

/** First blocking issue for a video run, or null. */
internal fun sdMasterVideoLaunchIssue(
    settings: SdDistributedMasterSettingsEntity,
    options: VideoRuntimeOptions,
    compatibleLoraPaths: Set<String>
): SdDistributedLaunchIssue? = sdDistributedVideoLaunchIssue(
    mode = settings.videoWorkflowMode.toSdMasterVideoMode(),
    effectiveOptions = effectiveSdVideoOptions(settings, options),
    prompt = settings.videoPrompt,
    framesText = settings.frames,
    width = parseSdDistributedDimension(settings.videoWidth),
    height = parseSdDistributedDimension(settings.videoHeight),
    loras = settings.videoLoras() + settings.videoHighNoiseLoras(),
    compatibleLoraPaths = compatibleLoraPaths
)

/** Module -> model paths for the run monitor's transfer estimate (video). */
internal fun sdMasterVideoModulePaths(effective: VideoRuntimeOptions): Map<String, List<String?>> {
    val c = effective.videoComponents
    return mapOf(
        SdDistributedModules.DIFFUSION to listOf(c.diffusionModelPath ?: c.fullModelPath, c.highNoiseDiffusionModelPath),
        SdDistributedModules.TEXT_ENCODER to listOf(c.t5xxlPath, c.llmPath, c.llmVisionPath),
        SdDistributedModules.VAE to listOf(c.vaePath, c.taePath),
        SdDistributedModules.CONTROLNET to listOf(c.controlNetPath)
    )
}

/** Video VideoGenerationConfig. Callers validate dimensions first. */
internal fun buildSdVideoConfig(
    settings: SdDistributedMasterSettingsEntity,
    videoOptions: VideoRuntimeOptions,
    runtimeConfig: SdDistributedRuntimeConfig,
    outputDir: File,
    baseName: String
): VideoGenerationConfig {
    val mode = settings.videoWorkflowMode.toSdMasterVideoMode()
    val sampler = SamplingMethod.entries.firstOrNull {
        it.cliName == settings.videoSampler || it.name == settings.videoSampler
    } ?: SamplingMethod.EULER
    val cacheMode = SdCacheMode.fromStoredValue(settings.cacheMode)
    val scmPolicy = SdCacheScmPolicy.fromStoredValue(settings.scmPolicy)
    val effective = effectiveSdVideoOptions(settings, videoOptions)
    val selectedModelPath = effective.videoComponents.diffusionModelPath ?: effective.videoComponents.fullModelPath
    // VAE is replaced by the TAE decoder when the editor enables it.
    val vaePath = effective.videoComponents.vaePath?.takeIf { it.isNotBlank() && !effective.useTae }
    return VideoGenerationConfig(
        mode = mode,
        prompt = settings.videoPrompt,
        negativePrompt = settings.videoNegativePrompt,
        diffusionModelPath = selectedModelPath.orEmpty(),
        outputAviPath = File(outputDir, "$baseName.avi").absolutePath,
        outputMp4Path = File(outputDir, "$baseName.mp4").absolutePath,
        metadataPath = File(outputDir, "$baseName.json").absolutePath,
        initImagePath = effective.videoInputs.initImagePath?.takeIf { mode == VideoGenerationMode.IMG2VID },
        useVae = vaePath != null,
        vaePath = vaePath,
        useT5xxl = effective.videoComponents.t5xxlPath != null,
        t5xxlPath = effective.videoComponents.t5xxlPath,
        videoFrames = settings.frames.toIntOrNull() ?: 8,
        fps = settings.fps.toIntOrNull() ?: 5,
        width = requireNotNull(parseSdDistributedDimension(settings.videoWidth)),
        height = requireNotNull(parseSdDistributedDimension(settings.videoHeight)),
        steps = settings.videoSteps.toIntOrNull() ?: 18,
        cfgScale = settings.videoCfg.toFloatOrNull() ?: 6.0f,
        flowShift = settings.videoFlowShift.toFloatOrNull(),
        samplingMethod = sampler,
        scheduler = SdScheduler.fromCliName(settings.videoScheduler),
        loras = settings.videoLoras(),
        highNoiseLoras = settings.videoHighNoiseLoras(),
        loraApplyMode = SdLoraApplyMode.fromStoredValue(settings.videoLoraApplyMode),
        cacheMode = cacheMode,
        cacheOption = if (cacheMode != null) settings.cacheOption else "",
        scmMask = if (cacheMode == SdCacheMode.CACHE_DIT) settings.scmMask else "",
        scmPolicy = if (cacheMode == SdCacheMode.CACHE_DIT) scmPolicy else null,
        vaeTiling = settings.vaeTiling,
        vaeTileSize = settings.vaeTileSize.ifBlank { "24x24" },
        diffusionFa = settings.diffusionFa,
        mmap = settings.mmap,
        threads = settings.runtimeThreads.toIntOrNull() ?: -1,
        // Distributed placement is negotiated independently of local model-row preferences.
        sdParamsBackendSpec = "auto",
        sdParamsBackendMode = "auto",
        sdRuntimeBackendMode = "auto",
        maxVramCpuGiB = "",
        distributedRuntime = runtimeConfig,
        customFlags = settings.videoCustomFlags,
        videoFamily = effective.videoFamily,
        videoVariant = effective.videoVariant,
        workflow = effective.workflow,
        videoComponents = effective.videoComponents,
        videoInputs = effective.videoInputs,
        useTae = effective.useTae,
        seed = effective.seed,
        highNoiseSteps = effective.highNoiseSteps,
        highNoiseCfgScale = effective.highNoiseCfgScale,
        highNoiseSamplingMethod = effective.highNoiseSamplingMethod,
        controlStrength = effective.controlStrength,
        vaeTileOverlap = effective.vaeTileOverlap,
        vaeRelativeTileSize = effective.vaeRelativeTileSize,
        hires = effective.hires,
        outputFormat = effective.outputFormat,
        nativeOutputFormat = effective.nativeOutputFormat,
        nativeOutputPath = File(outputDir, "$baseName.${effective.nativeOutputFormat.extension}").absolutePath,
        audioCodec = effective.audioCodec,
        conversionRecoveryEnabled = effective.conversionRecoveryEnabled,
        imgCfgScale = effective.imgCfgScale,
        guidance = effective.guidance,
        slgScale = effective.slgScale,
        skipLayerStart = effective.skipLayerStart,
        skipLayerEnd = effective.skipLayerEnd,
        skipLayers = effective.skipLayers,
        eta = effective.eta,
        strength = effective.strength,
        highNoiseImgCfgScale = effective.highNoiseImgCfgScale,
        highNoiseGuidance = effective.highNoiseGuidance,
        highNoiseSlgScale = effective.highNoiseSlgScale,
        highNoiseSkipLayerStart = effective.highNoiseSkipLayerStart,
        highNoiseSkipLayerEnd = effective.highNoiseSkipLayerEnd,
        highNoiseSkipLayers = effective.highNoiseSkipLayers,
        highNoiseEta = effective.highNoiseEta,
        moeBoundary = effective.moeBoundary,
        vaceStrength = effective.vaceStrength,
        ipAdapterStrength = effective.ipAdapterStrength,
        vaeFormat = effective.vaeFormat,
        sigmas = effective.sigmas,
        refImageArgs = effective.refImageArgs,
        extraSampleArgs = effective.extraSampleArgs,
        extraTilingArgs = effective.extraTilingArgs,
        increaseRefIndex = effective.increaseRefIndex,
        disableAutoResizeRefImage = effective.disableAutoResizeRefImage,
        circular = effective.circular,
        circularX = effective.circularX,
        circularY = effective.circularY,
        temporalTiling = effective.temporalTiling,
        promptFormat = effective.promptFormat,
        lingBotPromptJson = effective.lingBotPromptJson
    )
}
