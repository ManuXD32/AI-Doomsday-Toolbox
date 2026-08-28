package com.example.llamadroid.service

import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.sd.toSdLoraSpecs
import org.json.JSONObject
import java.io.File

data class SdGeneratedImageMetadata(
    val mode: String,
    val prompt: String = "",
    val negativePrompt: String = "",
    val modelPath: String,
    val modelName: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val samplingMethod: SamplingMethod,
    val scheduler: SdScheduler?,
    val initImagePath: String?,
    val strength: Float,
    val upscaleModelPath: String?,
    val upscaleModelName: String?,
    val upscaleRepeats: Int,
    val threads: Int,
    val vaePath: String?,
    val vaeName: String?,
    val taePath: String?,
    val taeName: String?,
    val clipLPath: String?,
    val clipLName: String?,
    val clipGPath: String?,
    val clipGName: String?,
    val t5xxlPath: String?,
    val t5xxlName: String?,
    val llmPath: String?,
    val llmName: String?,
    val llmVisionPath: String?,
    val llmVisionName: String?,
    val controlNetPath: String?,
    val controlNetName: String?,
    val controlStrength: Float,
    val loraPath: String?,
    val loraName: String?,
    val loraStrength: Float,
    val loraApplyMode: String?,
    /** Ordered multi-LoRA snapshot; legacy fields above remain for old readers. */
    val loras: List<SdLoraSpec> = emptyList(),
    val adetailerLoras: List<SdLoraSpec> = emptyList(),
    val textualInversionPath: String?,
    val textualInversionName: String?,
    val photoMakerPath: String?,
    val photoMakerName: String?,
    val flowShift: Float?,
    val diffusionFa: Boolean,
    val diffusionConvDirect: Boolean = false,
    val mmap: Boolean,
    val vaeConvDirect: Boolean,
    val qwenImageZeroCondT: Boolean,
    val chromaDisableDitMask: Boolean,
    val vaeTiling: Boolean,
    val vaeTileOverlap: Float,
    val vaeTileSize: String,
    val vaeRelativeTileSize: String,
    val tensorTypeRules: String,
    val quantizationType: String,
    val cacheMode: SdCacheMode?,
    val cacheOption: String,
    val scmMask: String,
    val scmPolicy: SdCacheScmPolicy?,
    val sdParamsBackendMode: String,
    val sdRuntimeBackendMode: String,
    val maxVramCpuGiB: String,
    val distributedRuntime: SdDistributedRuntimeConfig,
    val customFlags: String,
    val createdAt: Long,
    val outputPath: String,
    val metadataPath: String,
    val generationDurationMs: Long?,
    val conditioningDurationMs: Long? = null,
    val samplingDurationMs: Long? = null,
    val decodingDurationMs: Long? = null,
    val operation: String? = null,
    val sourceTransform: String? = null,
    val maskProvenance: String? = null,
    val maskPolarity: String? = null,
    val imageCfgScale: Float? = null,
    val adetailerModelName: String? = null,
    val adetailerConfidence: Float? = null,
    val adetailerDenoisingStrength: Float? = null,
    val adetailerOutcome: String? = null,
    val workflowPresetId: String? = null,
    val workflowBundleId: String? = null,
    val workflowRevision: String? = null
) {
    val modeEnum: SDMode
        get() = runCatching { SDMode.valueOf(mode) }.getOrDefault(SDMode.TXT2IMG)

    fun promptSnippet(maxLength: Int = 80): String =
        if (prompt.length <= maxLength) prompt else prompt.take(maxLength - 1).trimEnd() + "..."

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("modelPath", modelPath)
        put("modelName", modelName)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfgScale", cfgScale.toDouble())
        put("seed", seed)
        put("samplingMethod", samplingMethod.name)
        put("scheduler", scheduler?.name)
        put("initImagePath", initImagePath)
        put("strength", strength.toDouble())
        put("upscaleModelPath", upscaleModelPath)
        put("upscaleModelName", upscaleModelName)
        put("upscaleRepeats", upscaleRepeats)
        put("threads", threads)
        put("vaePath", vaePath)
        put("vaeName", vaeName)
        put("taePath", taePath)
        put("taeName", taeName)
        put("clipLPath", clipLPath)
        put("clipLName", clipLName)
        put("clipGPath", clipGPath)
        put("clipGName", clipGName)
        put("t5xxlPath", t5xxlPath)
        put("t5xxlName", t5xxlName)
        put("llmPath", llmPath)
        put("llmName", llmName)
        put("llmVisionPath", llmVisionPath)
        put("llmVisionName", llmVisionName)
        put("controlNetPath", controlNetPath)
        put("controlNetName", controlNetName)
        put("controlStrength", controlStrength.toDouble())
        put("loraPath", loraPath)
        put("loraName", loraName)
        put("loraStrength", loraStrength.toDouble())
        put("loraApplyMode", loraApplyMode)
        put("loras", loras.toJsonArray())
        put("adetailerLoras", adetailerLoras.toJsonArray())
        put("textualInversionPath", textualInversionPath)
        put("textualInversionName", textualInversionName)
        put("photoMakerPath", photoMakerPath)
        put("photoMakerName", photoMakerName)
        put("flowShift", flowShift?.toDouble())
        put("diffusionFa", diffusionFa)
        put("diffusionConvDirect", diffusionConvDirect)
        put("mmap", mmap)
        put("vaeConvDirect", vaeConvDirect)
        put("qwenImageZeroCondT", qwenImageZeroCondT)
        put("chromaDisableDitMask", chromaDisableDitMask)
        put("vaeTiling", vaeTiling)
        put("vaeTileOverlap", vaeTileOverlap.toDouble())
        put("vaeTileSize", vaeTileSize)
        put("vaeRelativeTileSize", vaeRelativeTileSize)
        put("tensorTypeRules", tensorTypeRules)
        put("quantizationType", quantizationType)
        put("cacheMode", cacheMode?.name)
        put("cacheOption", cacheOption)
        put("scmMask", scmMask)
        put("scmPolicy", scmPolicy?.name)
        put("sdParamsBackendMode", sdParamsBackendMode)
        put("sdRuntimeBackendMode", sdRuntimeBackendMode)
        put("maxVramCpuGiB", maxVramCpuGiB)
        put("distributedEnabled", distributedRuntime.enabled)
        put("distributedRpcServers", distributedRuntime.rpcServers)
        put("distributedPlacementMode", distributedRuntime.placementMode.name)
        put("distributedBackendSpec", distributedRuntime.backendSpec)
        put("distributedParamsBackendSpec", distributedRuntime.paramsBackendSpec)
        put("distributedAutoFit", distributedRuntime.autoFit)
        put("distributedMaxVramSpec", distributedRuntime.maxVramSpec)
        put("distributedSplitMode", distributedRuntime.splitMode.name)
        put("distributedCustomFlags", distributedRuntime.customFlags)
        put("customFlags", customFlags)
        put("createdAt", createdAt)
        put("outputPath", outputPath)
        put("metadataPath", metadataPath)
        put("generationDurationMs", generationDurationMs)
        put("conditioningDurationMs", conditioningDurationMs)
        put("samplingDurationMs", samplingDurationMs)
        put("decodingDurationMs", decodingDurationMs)
        put("operation", operation)
        put("sourceTransform", sourceTransform)
        put("maskProvenance", maskProvenance)
        put("maskPolarity", maskPolarity)
        put("imageCfgScale", imageCfgScale?.toDouble())
        put("adetailerModelName", adetailerModelName)
        put("adetailerConfidence", adetailerConfidence?.toDouble())
        put("adetailerDenoisingStrength", adetailerDenoisingStrength?.toDouble())
        put("adetailerOutcome", adetailerOutcome)
        put("workflowPresetId", workflowPresetId)
        put("workflowBundleId", workflowBundleId)
        put("workflowRevision", workflowRevision)
    }

    fun writeToFile(target: File = File(metadataPath)) {
        target.parentFile?.mkdirs()
        target.writeText(toJson().toString(2))
    }

    companion object {
        fun metadataFileForImage(imageFile: File): File =
            File(
                imageFile.parentFile ?: imageFile.absoluteFile.parentFile ?: File("."),
                "${imageFile.nameWithoutExtension}.json"
            )

        fun fromConfig(
            config: SDConfig,
            outputFile: File,
            generationDurationMs: Long,
            stageTimings: SdStageTimings = SdStageTimings()
        ): SdGeneratedImageMetadata =
            SdGeneratedImageMetadata(
                mode = config.mode.name,
                prompt = config.prompt,
                negativePrompt = config.negativePrompt,
                modelPath = config.modelPath,
                modelName = File(config.modelPath).name,
                width = config.width,
                height = config.height,
                steps = config.steps,
                cfgScale = config.cfgScale,
                seed = config.seed,
                samplingMethod = config.samplingMethod,
                scheduler = config.scheduler,
                initImagePath = config.initImage,
                strength = config.strength,
                upscaleModelPath = config.upscaleModel,
                upscaleModelName = config.upscaleModel?.let { File(it).name },
                upscaleRepeats = config.upscaleRepeats,
                threads = config.threads,
                vaePath = config.vaePath,
                vaeName = config.vaePath?.let { File(it).name },
                taePath = config.taePath,
                taeName = config.taePath?.let { File(it).name },
                clipLPath = config.clipLPath,
                clipLName = config.clipLPath?.let { File(it).name },
                clipGPath = config.clipGPath,
                clipGName = config.clipGPath?.let { File(it).name },
                t5xxlPath = config.t5xxlPath,
                t5xxlName = config.t5xxlPath?.let { File(it).name },
                llmPath = config.llmPath,
                llmName = config.llmPath?.let { File(it).name },
                llmVisionPath = config.llmVisionPath,
                llmVisionName = config.llmVisionPath?.let { File(it).name },
                controlNetPath = config.controlNetPath,
                controlNetName = config.controlNetPath?.let { File(it).name },
                controlStrength = config.controlStrength,
                loraPath = config.loraPath ?: config.resolvedLoras().firstOrNull()?.path,
                loraName = (config.loraPath ?: config.resolvedLoras().firstOrNull()?.path)?.let { File(it).name },
                loraStrength = config.loraPath?.let { config.loraStrength }
                    ?: config.resolvedLoras().firstOrNull()?.strength
                    ?: config.loraStrength,
                loraApplyMode = config.loraApplyMode?.cliName,
                loras = config.resolvedLoras(),
                adetailerLoras = config.adetailer?.loras.orEmpty(),
                textualInversionPath = config.textualInversionPath,
                textualInversionName = config.textualInversionPath?.let { File(it).name },
                photoMakerPath = config.photoMakerPath,
                photoMakerName = config.photoMakerPath?.let { File(it).name },
                flowShift = config.flowShift,
                diffusionFa = config.diffusionFa,
                diffusionConvDirect = config.diffusionConvDirect,
                mmap = config.mmap,
                vaeConvDirect = config.vaeConvDirect,
                qwenImageZeroCondT = config.qwenImageZeroCondT,
                chromaDisableDitMask = config.chromaDisableDitMask,
                vaeTiling = config.vaeTiling,
                vaeTileOverlap = config.vaeTileOverlap,
                vaeTileSize = config.vaeTileSize,
                vaeRelativeTileSize = config.vaeRelativeTileSize,
                tensorTypeRules = config.tensorTypeRules,
                quantizationType = config.quantizationType,
                cacheMode = config.cacheMode,
                cacheOption = config.cacheOption,
                scmMask = config.scmMask,
                scmPolicy = config.scmPolicy,
                sdParamsBackendMode = config.sdParamsBackendMode,
                sdRuntimeBackendMode = config.sdRuntimeBackendMode,
                maxVramCpuGiB = config.maxVramCpuGiB,
                distributedRuntime = config.distributedRuntime,
                customFlags = config.customFlags,
                createdAt = System.currentTimeMillis(),
                outputPath = outputFile.absolutePath,
                metadataPath = metadataFileForImage(outputFile).absolutePath,
                generationDurationMs = generationDurationMs,
                conditioningDurationMs = stageTimings.conditioningMs,
                samplingDurationMs = stageTimings.samplingMs,
                decodingDurationMs = stageTimings.decodingMs,
                operation = config.operation,
                sourceTransform = config.sourceTransform,
                maskProvenance = config.maskProvenance,
                maskPolarity = config.maskPolarity,
                imageCfgScale = config.imgCfgScale,
                adetailerModelName = config.adetailer?.modelPath?.let { File(it).name },
                adetailerConfidence = config.adetailer?.confidence,
                adetailerDenoisingStrength = config.adetailer?.denoisingStrength,
                workflowPresetId = config.workflowPresetId,
                workflowBundleId = config.workflowBundleId,
                workflowRevision = config.workflowRevision
            )

        fun fromUpscaleConfig(
            config: SDUpscaleConfig,
            outputFile: File,
            generationDurationMs: Long
        ): SdGeneratedImageMetadata =
            SdGeneratedImageMetadata(
                mode = SDMode.UPSCALE.name,
                prompt = "",
                negativePrompt = "",
                modelPath = config.modelPath,
                modelName = File(config.modelPath).name,
                width = 0,
                height = 0,
                steps = config.upscaleRepeats,
                cfgScale = 0f,
                seed = -1L,
                samplingMethod = SamplingMethod.EULER_A,
                scheduler = null,
                initImagePath = config.inputImagePath,
                strength = 1f,
                upscaleModelPath = config.modelPath,
                upscaleModelName = File(config.modelPath).name,
                upscaleRepeats = config.upscaleRepeats,
                threads = config.threads,
                vaePath = null,
                vaeName = null,
                taePath = null,
                taeName = null,
                clipLPath = null,
                clipLName = null,
                clipGPath = null,
                clipGName = null,
                t5xxlPath = null,
                t5xxlName = null,
                llmPath = null,
                llmName = null,
                llmVisionPath = null,
                llmVisionName = null,
                controlNetPath = null,
                controlNetName = null,
                controlStrength = 0f,
                loraPath = null,
                loraName = null,
                loraStrength = 0f,
                loraApplyMode = null,
                loras = emptyList(),
                adetailerLoras = emptyList(),
                textualInversionPath = null,
                textualInversionName = null,
                photoMakerPath = null,
                photoMakerName = null,
                flowShift = null,
                diffusionFa = false,
                mmap = false,
                vaeConvDirect = false,
                qwenImageZeroCondT = false,
                chromaDisableDitMask = false,
                vaeTiling = false,
                vaeTileOverlap = 0f,
                vaeTileSize = "",
                vaeRelativeTileSize = "",
                tensorTypeRules = "",
                quantizationType = "",
                cacheMode = null,
                cacheOption = "",
                scmMask = "",
                scmPolicy = null,
                sdParamsBackendMode = config.sdParamsBackendMode,
                sdRuntimeBackendMode = config.sdRuntimeBackendMode,
                maxVramCpuGiB = config.maxVramCpuGiB,
                distributedRuntime = config.distributedRuntime,
                customFlags = config.customFlags,
                createdAt = System.currentTimeMillis(),
                outputPath = outputFile.absolutePath,
                metadataPath = metadataFileForImage(outputFile).absolutePath,
                generationDurationMs = generationDurationMs
            )

        fun fromFile(file: File): SdGeneratedImageMetadata? {
            if (!file.isFile) return null
            return runCatching {
                fromJson(JSONObject(file.readText()))
            }.onFailure { error ->
                DebugLog.log("[StableDiffusionService] Failed to read image metadata ${file.absolutePath}: ${error.message}")
            }.getOrNull()
        }

        fun fromJson(json: JSONObject): SdGeneratedImageMetadata =
            SdGeneratedImageMetadata(
                mode = json.optString("mode", SDMode.TXT2IMG.name),
                prompt = json.optString("prompt"),
                negativePrompt = json.optString("negativePrompt"),
                modelPath = json.optString("modelPath"),
                modelName = json.optString("modelName"),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                steps = json.optInt("steps", 0),
                cfgScale = json.optDouble("cfgScale", 0.0).toFloat(),
                seed = json.optLong("seed", -1L),
                samplingMethod = parseSamplingMethod(json.optString("samplingMethod")),
                scheduler = SdScheduler.fromCliName(json.optString("scheduler")),
                initImagePath = json.optString("initImagePath").ifBlank { null },
                strength = json.optDouble("strength", 0.0).toFloat(),
                upscaleModelPath = json.optString("upscaleModelPath").ifBlank { null },
                upscaleModelName = json.optString("upscaleModelName").ifBlank { null },
                upscaleRepeats = json.optInt("upscaleRepeats", 1),
                threads = json.optInt("threads", -1),
                vaePath = json.optString("vaePath").ifBlank { null },
                vaeName = json.optString("vaeName").ifBlank { null },
                taePath = json.optString("taePath").ifBlank { null },
                taeName = json.optString("taeName").ifBlank { null },
                clipLPath = json.optString("clipLPath").ifBlank { null },
                clipLName = json.optString("clipLName").ifBlank { null },
                clipGPath = json.optString("clipGPath").ifBlank { null },
                clipGName = json.optString("clipGName").ifBlank { null },
                t5xxlPath = json.optString("t5xxlPath").ifBlank { null },
                t5xxlName = json.optString("t5xxlName").ifBlank { null },
                llmPath = json.optString("llmPath").ifBlank { null },
                llmName = json.optString("llmName").ifBlank { null },
                llmVisionPath = json.optString("llmVisionPath").ifBlank { null },
                llmVisionName = json.optString("llmVisionName").ifBlank { null },
                controlNetPath = json.optString("controlNetPath").ifBlank { null },
                controlNetName = json.optString("controlNetName").ifBlank { null },
                controlStrength = json.optDouble("controlStrength", 0.0).toFloat(),
                loraPath = json.optString("loraPath").ifBlank { null },
                loraName = json.optString("loraName").ifBlank { null },
                loraStrength = json.optDouble("loraStrength", 0.0).toFloat(),
                loraApplyMode = json.optString("loraApplyMode").ifBlank { null },
                loras = json.optJSONArray("loras")?.toSdLoraSpecs()
                    ?.takeIf { it.isNotEmpty() }
                    ?: SdLoraSpec.fromLegacy(
                        json.optString("loraPath").ifBlank { null },
                        json.optDouble("loraStrength", 1.0).toFloat()
                    ),
                adetailerLoras = json.optJSONArray("adetailerLoras")?.toSdLoraSpecs().orEmpty(),
                textualInversionPath = json.optString("textualInversionPath").ifBlank { null },
                textualInversionName = json.optString("textualInversionName").ifBlank { null },
                photoMakerPath = json.optString("photoMakerPath").ifBlank { null },
                photoMakerName = json.optString("photoMakerName").ifBlank { null },
                flowShift = parseOptionalFloat(json, "flowShift"),
                diffusionFa = json.optBoolean("diffusionFa", false),
                diffusionConvDirect = json.optBoolean("diffusionConvDirect", false),
                mmap = json.optBoolean("mmap", false),
                vaeConvDirect = json.optBoolean("vaeConvDirect", false),
                qwenImageZeroCondT = json.optBoolean("qwenImageZeroCondT", false),
                chromaDisableDitMask = json.optBoolean("chromaDisableDitMask", false),
                vaeTiling = json.optBoolean("vaeTiling", false),
                vaeTileOverlap = json.optDouble("vaeTileOverlap", 0.0).toFloat(),
                vaeTileSize = json.optString("vaeTileSize"),
                vaeRelativeTileSize = json.optString("vaeRelativeTileSize"),
                tensorTypeRules = json.optString("tensorTypeRules"),
                quantizationType = json.optString("quantizationType"),
                cacheMode = SdCacheMode.fromStoredValue(json.optString("cacheMode").ifBlank { null }),
                cacheOption = json.optString("cacheOption"),
                scmMask = json.optString("scmMask"),
                scmPolicy = SdCacheScmPolicy.fromStoredValue(json.optString("scmPolicy").ifBlank { null }),
                sdParamsBackendMode = json.optString("sdParamsBackendMode", "auto"),
                sdRuntimeBackendMode = json.optString("sdRuntimeBackendMode", "auto"),
                maxVramCpuGiB = json.optString("maxVramCpuGiB"),
                distributedRuntime = SdDistributedRuntimeConfig(
                    enabled = json.optBoolean("distributedEnabled", false),
                    rpcServers = json.optString("distributedRpcServers"),
                    placementMode = runCatching {
                        SdDistributedPlacementMode.valueOf(json.optString("distributedPlacementMode"))
                    }.getOrDefault(SdDistributedPlacementMode.AUTO_RAM),
                    backendSpec = json.optString("distributedBackendSpec"),
                    paramsBackendSpec = json.optString("distributedParamsBackendSpec"),
                    autoFit = json.optBoolean("distributedAutoFit", true),
                    maxVramSpec = json.optString("distributedMaxVramSpec"),
                    splitMode = runCatching {
                        SdDistributedSplitMode.valueOf(json.optString("distributedSplitMode"))
                    }.getOrDefault(SdDistributedSplitMode.LAYER),
                    customFlags = json.optString("distributedCustomFlags")
                ),
                customFlags = json.optString("customFlags"),
                createdAt = json.optLong("createdAt", 0L),
                outputPath = json.optString("outputPath"),
                metadataPath = json.optString("metadataPath"),
                generationDurationMs = json.optLong("generationDurationMs", -1L).takeIf { it >= 0L },
                conditioningDurationMs = json.optLong("conditioningDurationMs", -1L).takeIf { it >= 0L },
                samplingDurationMs = json.optLong("samplingDurationMs", -1L).takeIf { it >= 0L },
                decodingDurationMs = json.optLong("decodingDurationMs", -1L).takeIf { it >= 0L },
                operation = json.optString("operation").ifBlank { null },
                sourceTransform = json.optString("sourceTransform").ifBlank { null },
                maskProvenance = json.optString("maskProvenance").ifBlank { null },
                maskPolarity = json.optString("maskPolarity").ifBlank { null },
                imageCfgScale = parseOptionalFloat(json, "imageCfgScale"),
                adetailerModelName = json.optString("adetailerModelName").ifBlank { null },
                adetailerConfidence = parseOptionalFloat(json, "adetailerConfidence"),
                adetailerDenoisingStrength = parseOptionalFloat(json, "adetailerDenoisingStrength"),
                adetailerOutcome = json.optString("adetailerOutcome").ifBlank { null },
                workflowPresetId = json.optString("workflowPresetId").ifBlank { null },
                workflowBundleId = json.optString("workflowBundleId").ifBlank { null },
                workflowRevision = json.optString("workflowRevision").ifBlank { null }
            )

        private fun parseSamplingMethod(value: String): SamplingMethod =
            SamplingMethod.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.cliName.equals(value, ignoreCase = true)
            } ?: SamplingMethod.EULER_A

        private fun parseOptionalFloat(json: JSONObject, key: String): Float? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key).toFloat() else null
    }
}
