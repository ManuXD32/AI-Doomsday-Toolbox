package com.example.llamadroid.service

import android.os.Parcelable
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoAudioCodec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoHiresConfig
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoNativeOutputFormat
import com.example.llamadroid.sd.SdVideoOutputFormat
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.activeInOrder
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.sd.toSdLoraSpecs
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Configuration for stable-diffusion.cpp video generation.
 */
@Parcelize
data class VideoGenerationConfig(
    val mode: VideoGenerationMode,
    val prompt: String,
    val negativePrompt: String = "",
    val diffusionModelPath: String,
    val outputAviPath: String,
    val outputMp4Path: String,
    val metadataPath: String,
    val initImagePath: String? = null,
    val useVae: Boolean = false,
    val vaePath: String? = null,
    val useT5xxl: Boolean = false,
    val t5xxlPath: String? = null,
    val videoFrames: Int = 8,
    val fps: Int = 5,
    val width: Int = 480,
    val height: Int = 832,
    val steps: Int = 18,
    val cfgScale: Float = 6.0f,
    val flowShift: Float? = null,
    val samplingMethod: SamplingMethod = SamplingMethod.EULER,
    val scheduler: SdScheduler? = null,
    val cacheMode: SdCacheMode? = null,
    val cacheOption: String = "",
    val scmMask: String = "",
    val scmPolicy: SdCacheScmPolicy? = null,
    val vaeTiling: Boolean = false,
    val vaeTileSize: String = "24x24",
    val diffusionFa: Boolean = true,
    val diffusionConvDirect: Boolean = false,
    val vaeConvDirect: Boolean = false,
    val mmap: Boolean = true,
    val threads: Int = -1,
    val sdParamsBackendMode: String = "auto",
    /** Normalized local module residency; distributed placement remains separate. */
    val sdParamsBackendSpec: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    /** Ordered Wan/video adapters. [highNoiseLoras] is kept separate per Wan 2.2 item. */
    val loras: List<SdLoraSpec> = emptyList(),
    val highNoiseLoras: List<SdLoraSpec> = emptyList(),
    val loraApplyMode: SdLoraApplyMode? = null,
    val customFlags: String = "",
    // Typed video contracts. The legacy fields above remain the compatibility
    // source for old screens, server requests, and saved intents.
    val videoFamily: SdVideoFamily? = null,
    val videoVariant: String? = null,
    val workflow: SdVideoWorkflow? = null,
    val videoComponents: SdVideoComponentPaths = SdVideoComponentPaths(),
    val videoInputs: SdVideoInputs = SdVideoInputs(),
    val useTae: Boolean = false,
    val seed: Long = -1L,
    val highNoiseSteps: Int? = null,
    val highNoiseCfgScale: Float? = null,
    val highNoiseSamplingMethod: SamplingMethod? = null,
    val controlStrength: Float? = null,
    val vaeTileOverlap: Float = 0.5f,
    val vaeRelativeTileSize: String = "",
    val hires: SdVideoHiresConfig = SdVideoHiresConfig(),
    val outputFormat: SdVideoOutputFormat = SdVideoOutputFormat.MP4,
    val nativeOutputFormat: SdVideoNativeOutputFormat = SdVideoNativeOutputFormat.AVI,
    val nativeOutputPath: String? = null,
    /** Null chooses the output format's audio codec; NONE explicitly removes audio. */
    val audioCodec: SdVideoAudioCodec? = SdVideoAudioCodec.AAC,
    val conversionRecoveryEnabled: Boolean = true,
    /** Native sampler/guidance controls. Null/blank means use the native default. */
    val imgCfgScale: Float? = null,
    val guidance: Float? = null,
    val slgScale: Float? = null,
    val skipLayerStart: Float? = null,
    val skipLayerEnd: Float? = null,
    val skipLayers: String = "",
    val eta: Float? = null,
    val strength: Float? = null,
    val highNoiseImgCfgScale: Float? = null,
    val highNoiseGuidance: Float? = null,
    val highNoiseSlgScale: Float? = null,
    val highNoiseSkipLayerStart: Float? = null,
    val highNoiseSkipLayerEnd: Float? = null,
    val highNoiseSkipLayers: String = "",
    val highNoiseEta: Float? = null,
    val moeBoundary: Float? = null,
    val vaceStrength: Float? = null,
    val ipAdapterStrength: Float? = null,
    val vaeFormat: String? = null,
    val sigmas: String = "",
    val refImageArgs: String = "",
    val extraSampleArgs: String = "",
    val extraTilingArgs: String = "",
    val increaseRefIndex: Boolean = false,
    val disableAutoResizeRefImage: Boolean = false,
    val circular: Boolean = false,
    val circularX: Boolean = false,
    val circularY: Boolean = false,
    val temporalTiling: Boolean = false,
    val promptFormat: SdVideoPromptFormat? = null,
    /** Optional pre-shaped LingBot caption JSON. */
    val lingBotPromptJson: String? = null
) : Parcelable

fun VideoGenerationConfig.resolvedLoras(): List<SdLoraSpec> {
    val configured = loras.map { it.copy(highNoiseOnly = false) } +
        highNoiseLoras.map { it.copy(highNoiseOnly = true) }
    // Disabled rows are retained for draft/metadata round trips, but their
    // missing files or stale values must not prevent a launch. Only selected
    // adapters are validated before command construction.
    validateSdLoras(configured.filter { it.enabled })
    return configured.map(SdLoraSpec::normalized)
}

fun VideoGenerationConfig.resolvedVideoWorkflow(): SdVideoWorkflow =
    workflow ?: when (mode) {
        VideoGenerationMode.TXT2VID -> SdVideoWorkflow.TEXT_TO_VIDEO
        VideoGenerationMode.IMG2VID -> SdVideoWorkflow.IMAGE_TO_VIDEO
    }

/** Merge the typed component contract with fields written by older callers. */
fun VideoGenerationConfig.resolvedVideoComponents(): SdVideoComponentPaths =
    videoComponents.copy(
        diffusionModelPath = videoComponents.diffusionModelPath ?: diffusionModelPath,
        fullModelPath = videoComponents.fullModelPath ?: diffusionModelPath,
        vaePath = videoComponents.vaePath ?: vaePath.takeIf { useVae },
        taePath = videoComponents.taePath ?: vaePath.takeIf { useTae },
        t5xxlPath = videoComponents.t5xxlPath ?: t5xxlPath.takeIf { useT5xxl }
    )

fun VideoGenerationConfig.resolvedVideoInputs(): SdVideoInputs =
    videoInputs.copy(
        initImagePath = videoInputs.initImagePath ?: initImagePath
    )

fun VideoGenerationConfig.resolvedNativeOutputPath(): String =
    nativeOutputPath?.takeIf { it.isNotBlank() } ?: outputAviPath

/** Keep the portable artifact extension aligned with the selected output format. */
fun VideoGenerationConfig.resolvedPortableOutputPath(): String {
    val requested = File(outputMp4Path)
    val extension = outputFormat.extension
    if (requested.extension.equals(extension, ignoreCase = true)) return requested.path
    val filename = "${requested.nameWithoutExtension}.$extension"
    return requested.parentFile?.resolve(filename)?.path ?: filename
}

fun buildLingBotPromptJson(prompt: String): String = JSONObject().apply {
    put(
        "caption",
        JSONObject()
            .put("comprehensive_description", prompt)
            .put("camera_info", JSONObject())
            .put("world_knowledge", JSONArray())
            .put("prominent_elements", JSONArray())
    )
}.toString()

fun buildLingBotNegativePromptJson(negativePrompt: String): String = JSONObject().apply {
    put(
        "universal_negative",
        JSONObject().put("composition_and_content", JSONArray().put(negativePrompt))
    )
}.toString()

fun VideoGenerationConfig.resolvedVideoPrompt(): String {
    val format = promptFormat ?: if (videoFamily == SdVideoFamily.LINGBOT_VIDEO) {
        SdVideoPromptFormat.LINGBOT_CAPTION_JSON
    } else {
        SdVideoPromptFormat.PLAIN
    }
    return when (format) {
        SdVideoPromptFormat.PLAIN -> prompt
        SdVideoPromptFormat.LINGBOT_CAPTION_JSON ->
            lingBotPromptJson?.takeIf { it.isNotBlank() } ?: buildLingBotPromptJson(prompt)
    }
}

fun VideoGenerationConfig.resolvedVideoNegativePrompt(): String {
    if (negativePrompt.isBlank()) return ""
    val format = promptFormat ?: if (videoFamily == SdVideoFamily.LINGBOT_VIDEO) {
        SdVideoPromptFormat.LINGBOT_CAPTION_JSON
    } else {
        SdVideoPromptFormat.PLAIN
    }
    if (format == SdVideoPromptFormat.PLAIN) return negativePrompt
    return runCatching { JSONObject(negativePrompt).toString() }.getOrElse {
        buildLingBotNegativePromptJson(negativePrompt)
    }
}

enum class VideoGenerationMode(val folderName: String) {
    TXT2VID("txt2vid"),
    IMG2VID("img2vid")
}

sealed class VideoGenerationState {
    object Idle : VideoGenerationState()
    data class Generating(
        val progress: Float,
        val status: String,
        val currentStep: Int = 0,
        val totalSteps: Int = 0,
        val etaSeconds: Double? = null
    ) : VideoGenerationState()
    data class Converting(val progress: Float, val status: String) : VideoGenerationState()
    data class Copying(val progress: Float, val status: String) : VideoGenerationState()
    data class Complete(
        val metadata: GeneratedVideoMetadata,
        val warningMessage: String? = null
    ) : VideoGenerationState()
    data class Error(val message: String) : VideoGenerationState()
}

data class GeneratedVideoMetadata(
    val mode: String,
    val prompt: String,
    val negativePrompt: String = "",
    val diffusionModelPath: String,
    val diffusionModelName: String,
    val vaeEnabled: Boolean,
    val vaePath: String?,
    val vaeName: String?,
    val t5xxlEnabled: Boolean,
    val t5xxlPath: String?,
    val t5xxlName: String?,
    val initImagePath: String?,
    val videoFrames: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val flowShift: Float?,
    val samplingMethod: SamplingMethod,
    val scheduler: SdScheduler?,
    val cacheMode: SdCacheMode?,
    val cacheOption: String,
    val scmMask: String,
    val scmPolicy: SdCacheScmPolicy?,
    val threads: Int,
    val vaeTiling: Boolean,
    val vaeTileSize: String?,
    val diffusionFa: Boolean,
    val diffusionConvDirect: Boolean = false,
    val vaeConvDirect: Boolean = false,
    val mmap: Boolean,
    val sdParamsBackendMode: String = "auto",
    val sdParamsBackendSpec: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig,
    val loras: List<SdLoraSpec> = emptyList(),
    val highNoiseLoras: List<SdLoraSpec> = emptyList(),
    val loraApplyMode: String? = null,
    val createdAt: Long,
    val aviPath: String,
    val mp4Path: String,
    val metadataPath: String,
    val generationDurationMs: Long? = null,
    val conditioningDurationMs: Long? = null,
    val samplingDurationMs: Long? = null,
    val decodingDurationMs: Long? = null,
    val exportedAviUri: String? = null,
    val exportedMp4Uri: String? = null,
    val exportedMetadataUri: String? = null,
    val exportedNativeUri: String? = null,
    val videoFamily: String? = null,
    val videoVariant: String? = null,
    val workflow: String? = null,
    val videoComponents: SdVideoComponentPaths = SdVideoComponentPaths(),
    val videoInputs: SdVideoInputs = SdVideoInputs(),
    val useTae: Boolean = false,
    val taePath: String? = null,
    val taeName: String? = null,
    val seed: Long = -1L,
    val highNoiseSteps: Int? = null,
    val highNoiseCfgScale: Float? = null,
    val highNoiseSamplingMethod: SamplingMethod? = null,
    val controlStrength: Float? = null,
    val vaeTileOverlap: Float = 0.5f,
    val vaeRelativeTileSize: String? = null,
    val hires: SdVideoHiresConfig = SdVideoHiresConfig(),
    val outputFormat: String = SdVideoOutputFormat.MP4.name,
    val nativeOutputFormat: String = SdVideoNativeOutputFormat.AVI.name,
    val nativeOutputPath: String? = null,
    val audioCodec: String? = SdVideoAudioCodec.AAC.name,
    val conversionAttempted: Boolean = false,
    val conversionRecoveredNative: Boolean = false,
    val conversionWarning: String? = null,
    val audioSidecarPath: String? = null,
    val exportedAudioUri: String? = null
) {
    val modeEnum: VideoGenerationMode
        get() = VideoGenerationMode.entries.firstOrNull { it.folderName == mode } ?: VideoGenerationMode.TXT2VID

    /** The artifact that remains playable after a conversion fallback. */
    val preferredArtifactPath: String
        get() = if (conversionRecoveredNative) {
            nativeOutputPath ?: aviPath
        } else {
            when {
                File(mp4Path).exists() -> mp4Path
                File(aviPath).exists() -> aviPath
                else -> nativeOutputPath ?: mp4Path
            }
        }

    fun promptSnippet(maxLength: Int = 80): String =
        if (prompt.length <= maxLength) prompt else prompt.take(maxLength - 1).trimEnd() + "…"

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("diffusionModelPath", diffusionModelPath)
        put("diffusionModelName", diffusionModelName)
        put("vaeEnabled", vaeEnabled)
        put("vaePath", vaePath)
        put("vaeName", vaeName)
        put("t5xxlEnabled", t5xxlEnabled)
        put("t5xxlPath", t5xxlPath)
        put("t5xxlName", t5xxlName)
        put("initImagePath", initImagePath)
        put("videoFrames", videoFrames)
        put("fps", fps)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfgScale", cfgScale.toDouble())
        put("flowShift", flowShift?.toDouble())
        put("samplingMethod", samplingMethod.name)
        put("scheduler", scheduler?.name)
        put("cacheMode", cacheMode?.name)
        put("cacheOption", cacheOption)
        put("scmMask", scmMask)
        put("scmPolicy", scmPolicy?.name)
        put("threads", threads)
        put("vaeTiling", vaeTiling)
        put("vaeTileSize", vaeTileSize)
        put("diffusionFa", diffusionFa)
        put("diffusionConvDirect", diffusionConvDirect)
        put("vaeConvDirect", vaeConvDirect)
        put("mmap", mmap)
        put("sdParamsBackendMode", sdParamsBackendMode)
        put("sdParamsBackendSpec", sdParamsBackendSpec)
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
        put("loras", loras.toJsonArray())
        put("highNoiseLoras", highNoiseLoras.toJsonArray())
        put("loraApplyMode", loraApplyMode)
        put("createdAt", createdAt)
        put("aviPath", aviPath)
        put("mp4Path", mp4Path)
        put("metadataPath", metadataPath)
        put("generationDurationMs", generationDurationMs)
        put("conditioningDurationMs", conditioningDurationMs)
        put("samplingDurationMs", samplingDurationMs)
        put("decodingDurationMs", decodingDurationMs)
        put("exportedAviUri", exportedAviUri)
        put("exportedMp4Uri", exportedMp4Uri)
        put("exportedMetadataUri", exportedMetadataUri)
        put("exportedNativeUri", exportedNativeUri)
        put("videoFamily", videoFamily)
        put("videoVariant", videoVariant)
        put("workflow", workflow)
        put("videoComponents", videoComponents.toJsonObject())
        put("videoInputs", videoInputs.toJsonObject())
        put("useTae", useTae)
        put("taePath", taePath)
        put("taeName", taeName)
        put("seed", seed)
        put("highNoiseSteps", highNoiseSteps)
        put("highNoiseCfgScale", highNoiseCfgScale?.toDouble())
        put("highNoiseSamplingMethod", highNoiseSamplingMethod?.name)
        put("controlStrength", controlStrength?.toDouble())
        put("vaeTileOverlap", vaeTileOverlap.toDouble())
        put("vaeRelativeTileSize", vaeRelativeTileSize)
        put("hires", hires.toJsonObject())
        put("outputFormat", outputFormat)
        put("nativeOutputFormat", nativeOutputFormat)
        put("nativeOutputPath", nativeOutputPath)
        // Keep an explicit null distinct from a missing legacy field; otherwise
        // JSONObject drops the key and fromJson() restores the AAC default.
        put("audioCodec", audioCodec ?: JSONObject.NULL)
        put("conversionAttempted", conversionAttempted)
        put("conversionRecoveredNative", conversionRecoveredNative)
        put("conversionWarning", conversionWarning)
        put("audioSidecarPath", audioSidecarPath)
        put("exportedAudioUri", exportedAudioUri)
    }

    fun writeToFile(target: File = File(metadataPath)) {
        target.parentFile?.mkdirs()
        target.writeText(toJson().toString(2))
    }

    companion object {
        fun fromFile(file: File): GeneratedVideoMetadata? {
            if (!file.exists()) return null
            return try {
                fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                DebugLog.log("[VIDEO-GEN] Failed to read metadata ${file.absolutePath}: ${e.message}")
                null
            }
        }

        fun fromJson(json: JSONObject): GeneratedVideoMetadata =
            GeneratedVideoMetadata(
                mode = json.optString("mode", VideoGenerationMode.TXT2VID.folderName),
                prompt = json.optString("prompt"),
                negativePrompt = json.optString("negativePrompt"),
                diffusionModelPath = json.optString("diffusionModelPath"),
                diffusionModelName = json.optString("diffusionModelName"),
                vaeEnabled = json.optBoolean("vaeEnabled", false),
                vaePath = json.optString("vaePath").ifBlank { null },
                vaeName = json.optString("vaeName").ifBlank { null },
                t5xxlEnabled = json.optBoolean("t5xxlEnabled", false),
                t5xxlPath = json.optString("t5xxlPath").ifBlank { null },
                t5xxlName = json.optString("t5xxlName").ifBlank { null },
                initImagePath = json.optString("initImagePath").ifBlank { null },
                videoFrames = json.optInt("videoFrames", 8),
                fps = json.optInt("fps", 5),
                width = json.optInt("width", 480),
                height = json.optInt("height", 832),
                steps = json.optInt("steps", 18),
                cfgScale = json.optDouble("cfgScale", 6.0).toFloat(),
                flowShift = parseOptionalFloat(json, "flowShift"),
                samplingMethod = parseSamplingMethod(json.optString("samplingMethod")),
                scheduler = SdScheduler.fromCliName(json.optString("scheduler")),
                cacheMode = SdCacheMode.fromStoredValue(json.optString("cacheMode").ifBlank { null }),
                cacheOption = json.optString("cacheOption"),
                scmMask = json.optString("scmMask"),
                scmPolicy = SdCacheScmPolicy.fromStoredValue(json.optString("scmPolicy").ifBlank { null }),
                threads = json.optInt("threads", -1),
                vaeTiling = json.optBoolean("vaeTiling", false),
                vaeTileSize = json.optString("vaeTileSize").ifBlank { null },
                diffusionFa = json.optBoolean("diffusionFa", true),
                diffusionConvDirect = json.optBoolean("diffusionConvDirect", false),
                vaeConvDirect = json.optBoolean("vaeConvDirect", false),
                mmap = json.optBoolean("mmap", true),
                sdParamsBackendMode = json.optString("sdParamsBackendMode", "auto"),
                sdParamsBackendSpec = json.optString("sdParamsBackendSpec", "auto"),
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
                loras = json.optJSONArray("loras")?.toSdLoraSpecs().orEmpty(),
                highNoiseLoras = json.optJSONArray("highNoiseLoras")?.toSdLoraSpecs().orEmpty(),
                loraApplyMode = json.optString("loraApplyMode").ifBlank { null },
                createdAt = json.optLong("createdAt", 0L),
                aviPath = json.optString("aviPath"),
                mp4Path = json.optString("mp4Path"),
                metadataPath = json.optString("metadataPath", filePathFallback(json)),
                generationDurationMs = json.optLong("generationDurationMs", -1L).takeIf { it >= 0L },
                conditioningDurationMs = json.optLong("conditioningDurationMs", -1L).takeIf { it >= 0L },
                samplingDurationMs = json.optLong("samplingDurationMs", -1L).takeIf { it >= 0L },
                decodingDurationMs = json.optLong("decodingDurationMs", -1L).takeIf { it >= 0L },
                exportedAviUri = json.optString("exportedAviUri").ifBlank { null },
                exportedMp4Uri = json.optString("exportedMp4Uri").ifBlank { null },
                exportedMetadataUri = json.optString("exportedMetadataUri").ifBlank { null },
                exportedNativeUri = json.optString("exportedNativeUri").ifBlank { null },
                videoFamily = json.optString("videoFamily").ifBlank { null },
                videoVariant = json.optString("videoVariant").ifBlank { null },
                workflow = json.optString("workflow").ifBlank { null },
                videoComponents = json.optJSONObject("videoComponents")?.toSdVideoComponentPaths()
                    ?: SdVideoComponentPaths(),
                videoInputs = json.optJSONObject("videoInputs")?.toSdVideoInputs()
                    ?: SdVideoInputs(),
                useTae = json.optBoolean("useTae", false),
                taePath = json.optString("taePath").ifBlank { null },
                taeName = json.optString("taeName").ifBlank { null },
                seed = json.optLong("seed", -1L),
                highNoiseSteps = json.optInt("highNoiseSteps", -1).takeIf { it >= 0 },
                highNoiseCfgScale = parseOptionalFloat(json, "highNoiseCfgScale"),
                highNoiseSamplingMethod = parseSamplingMethodOrNull(json.optString("highNoiseSamplingMethod")),
                controlStrength = parseOptionalFloat(json, "controlStrength"),
                vaeTileOverlap = json.optDouble("vaeTileOverlap", 0.5).toFloat(),
                vaeRelativeTileSize = json.optString("vaeRelativeTileSize").ifBlank { null },
                hires = json.optJSONObject("hires")?.toSdVideoHiresConfig() ?: SdVideoHiresConfig(),
                outputFormat = json.optString("outputFormat", SdVideoOutputFormat.MP4.name),
                nativeOutputFormat = json.optString("nativeOutputFormat", SdVideoNativeOutputFormat.AVI.name),
                nativeOutputPath = json.optString("nativeOutputPath").ifBlank { null },
                // Keep explicit automatic codec selection distinct from the absent legacy field.
                audioCodec = when {
                    !json.has("audioCodec") -> SdVideoAudioCodec.AAC.name
                    json.isNull("audioCodec") -> null
                    else -> json.optString("audioCodec").ifBlank { null }
                },
                conversionAttempted = json.optBoolean("conversionAttempted", false),
                conversionRecoveredNative = json.optBoolean("conversionRecoveredNative", false),
                conversionWarning = json.optString("conversionWarning").ifBlank { null },
                audioSidecarPath = json.optString("audioSidecarPath").ifBlank { null },
                exportedAudioUri = json.optString("exportedAudioUri").ifBlank { null }
            )

        private fun filePathFallback(json: JSONObject): String = json.optString("mp4Path") + ".json"

        private fun parseSamplingMethod(value: String): SamplingMethod {
            return SamplingMethod.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.cliName.equals(value, ignoreCase = true)
            } ?: SamplingMethod.EULER
        }

        private fun parseSamplingMethodOrNull(value: String): SamplingMethod? =
            value.takeIf { it.isNotBlank() }?.let { raw ->
                SamplingMethod.entries.firstOrNull {
                    it.name.equals(raw, ignoreCase = true) || it.cliName.equals(raw, ignoreCase = true)
                }
            }

        private fun parseOptionalFloat(json: JSONObject, key: String): Float? {
            return if (json.has(key) && !json.isNull(key)) {
                json.optDouble(key).toFloat()
            } else {
                null
            }
        }
    }
}

/** Public JSON bridge for draft/distributed settings and generated sidecars. */
fun SdVideoComponentPaths.toJsonObject(): JSONObject = JSONObject().apply {
    put("diffusionModelPath", diffusionModelPath)
    put("fullModelPath", fullModelPath)
    put("highNoiseDiffusionModelPath", highNoiseDiffusionModelPath)
    put("uncondDiffusionModelPath", uncondDiffusionModelPath)
    put("ipAdapterPath", ipAdapterPath)
    put("vaePath", vaePath)
    put("taePath", taePath)
    put("t5xxlPath", t5xxlPath)
    put("llmPath", llmPath)
    put("llmVisionPath", llmVisionPath)
    put("audioVaePath", audioVaePath)
    put("embeddingsConnectorsPath", embeddingsConnectorsPath)
    put("motionModulePath", motionModulePath)
    put("clipVisionPath", clipVisionPath)
    put("controlNetPath", controlNetPath)
    put("hiresUpscalersDir", hiresUpscalersDir)
    put("hiresUpscaler", hiresUpscaler)
}

fun JSONObject.toSdVideoComponentPaths(): SdVideoComponentPaths = SdVideoComponentPaths(
    diffusionModelPath = optString("diffusionModelPath").ifBlank { null },
    fullModelPath = optString("fullModelPath").ifBlank { null },
    highNoiseDiffusionModelPath = optString("highNoiseDiffusionModelPath").ifBlank { null },
    uncondDiffusionModelPath = optString("uncondDiffusionModelPath").ifBlank { null },
    ipAdapterPath = optString("ipAdapterPath").ifBlank { null },
    vaePath = optString("vaePath").ifBlank { null },
    taePath = optString("taePath").ifBlank { null },
    t5xxlPath = optString("t5xxlPath").ifBlank { null },
    llmPath = optString("llmPath").ifBlank { null },
    llmVisionPath = optString("llmVisionPath").ifBlank { null },
    audioVaePath = optString("audioVaePath").ifBlank { null },
    embeddingsConnectorsPath = optString("embeddingsConnectorsPath").ifBlank { null },
    motionModulePath = optString("motionModulePath").ifBlank { null },
    clipVisionPath = optString("clipVisionPath").ifBlank { null },
    controlNetPath = optString("controlNetPath").ifBlank { null },
    hiresUpscalersDir = optString("hiresUpscalersDir").ifBlank { null },
    hiresUpscaler = optString("hiresUpscaler").ifBlank { null }
)

fun SdVideoInputs.toJsonObject(): JSONObject = JSONObject().apply {
    put("initImagePath", initImagePath)
    put("endImagePath", endImagePath)
    put("controlImagePath", controlImagePath)
    put("controlVideoPath", controlVideoPath)
    put("referenceImages", referenceImages.toJsonStringArray())
    put("referenceVideos", referenceVideos.toJsonStringArray())
    put("referenceVideoAudios", referenceVideoAudios.toJsonStringArray())
    put("referenceAudios", referenceAudios.toJsonStringArray())
    put("ipAdapterImagePath", ipAdapterImagePath)
}

fun JSONObject.toSdVideoInputs(): SdVideoInputs = SdVideoInputs(
    initImagePath = optString("initImagePath").ifBlank { null },
    endImagePath = optString("endImagePath").ifBlank { null },
    controlImagePath = optString("controlImagePath").ifBlank { null },
    controlVideoPath = optString("controlVideoPath").ifBlank { null },
    referenceImages = optStringList("referenceImages"),
    referenceVideos = optStringList("referenceVideos"),
    referenceVideoAudios = optStringList("referenceVideoAudios"),
    referenceAudios = optStringList("referenceAudios"),
    ipAdapterImagePath = optString("ipAdapterImagePath").ifBlank { null }
)

fun SdVideoHiresConfig.toJsonObject(): JSONObject = JSONObject().apply {
    put("enabled", enabled)
    put("width", width)
    put("height", height)
    put("steps", steps)
    put("scale", scale?.toDouble())
    put("denoisingStrength", denoisingStrength?.toDouble())
    put("upscaleTileSize", upscaleTileSize)
    put("sigmas", sigmas)
}

fun JSONObject.toSdVideoHiresConfig(): SdVideoHiresConfig = SdVideoHiresConfig(
    enabled = optBoolean("enabled", false),
    width = optInt("width", -1).takeIf { it > 0 },
    height = optInt("height", -1).takeIf { it > 0 },
    steps = optInt("steps", -1).takeIf { it > 0 },
    scale = optDouble("scale", Double.NaN).toFloat().takeUnless { it.isNaN() },
    denoisingStrength = optDouble("denoisingStrength", Double.NaN).toFloat().takeUnless { it.isNaN() },
    upscaleTileSize = optInt("upscaleTileSize", -1).takeIf { it > 0 },
    sigmas = optString("sigmas")
)

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun List<String>.toJsonStringArray(): JSONArray = JSONArray().also { array ->
    forEach { value -> array.put(value) }
}

/**
 * New video settings that do not fit the legacy scalar draft fields. Paths
 * remain explicit so the distributed worker can report missing components
 * instead of silently selecting a different local file.
 */
data class VideoRuntimeOptions(
    val videoFamily: SdVideoFamily? = null,
    val videoVariant: String? = null,
    val workflow: SdVideoWorkflow? = null,
    val videoComponents: SdVideoComponentPaths = SdVideoComponentPaths(),
    val videoInputs: SdVideoInputs = SdVideoInputs(),
    val useTae: Boolean = false,
    val seed: Long = -1L,
    val highNoiseSteps: Int? = null,
    val highNoiseCfgScale: Float? = null,
    val highNoiseSamplingMethod: SamplingMethod? = null,
    val controlStrength: Float? = null,
    val vaeTileOverlap: Float = 0.5f,
    val vaeRelativeTileSize: String = "",
    val hires: SdVideoHiresConfig = SdVideoHiresConfig(),
    val outputFormat: SdVideoOutputFormat = SdVideoOutputFormat.MP4,
    val nativeOutputFormat: SdVideoNativeOutputFormat = SdVideoNativeOutputFormat.AVI,
    val audioCodec: SdVideoAudioCodec? = SdVideoAudioCodec.AAC,
    val conversionRecoveryEnabled: Boolean = true,
    val imgCfgScale: Float? = null,
    val guidance: Float? = null,
    val slgScale: Float? = null,
    val skipLayerStart: Float? = null,
    val skipLayerEnd: Float? = null,
    val skipLayers: String = "",
    val eta: Float? = null,
    val strength: Float? = null,
    val highNoiseImgCfgScale: Float? = null,
    val highNoiseGuidance: Float? = null,
    val highNoiseSlgScale: Float? = null,
    val highNoiseSkipLayerStart: Float? = null,
    val highNoiseSkipLayerEnd: Float? = null,
    val highNoiseSkipLayers: String = "",
    val highNoiseEta: Float? = null,
    val moeBoundary: Float? = null,
    val vaceStrength: Float? = null,
    val ipAdapterStrength: Float? = null,
    val vaeFormat: String? = null,
    val sigmas: String = "",
    val refImageArgs: String = "",
    val extraSampleArgs: String = "",
    val extraTilingArgs: String = "",
    val increaseRefIndex: Boolean = false,
    val disableAutoResizeRefImage: Boolean = false,
    val circular: Boolean = false,
    val circularX: Boolean = false,
    val circularY: Boolean = false,
    val temporalTiling: Boolean = false,
    val promptFormat: SdVideoPromptFormat? = null,
    val lingBotPromptJson: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("videoFamily", videoFamily?.storedValue)
        put("videoVariant", videoVariant)
        put("workflow", workflow?.storedValue)
        put("videoComponents", videoComponents.toJsonObject())
        put("videoInputs", videoInputs.toJsonObject())
        put("useTae", useTae)
        put("seed", seed)
        put("highNoiseSteps", highNoiseSteps)
        put("highNoiseCfgScale", highNoiseCfgScale?.toDouble())
        put("highNoiseSamplingMethod", highNoiseSamplingMethod?.name)
        put("controlStrength", controlStrength?.toDouble())
        put("vaeTileOverlap", vaeTileOverlap.toDouble())
        put("vaeRelativeTileSize", vaeRelativeTileSize)
        put("hires", hires.toJsonObject())
        put("outputFormat", outputFormat.name)
        put("nativeOutputFormat", nativeOutputFormat.name)
        // JSONObject.put(key, null) removes the key. Keep an explicit JSON null so
        // a deliberate automatic codec policy survives a draft round trip; an
        // absent key remains the legacy AAC default in fromJsonObject().
        put("audioCodec", audioCodec?.name ?: JSONObject.NULL)
        put("conversionRecoveryEnabled", conversionRecoveryEnabled)
        put("imgCfgScale", imgCfgScale?.toDouble())
        put("guidance", guidance?.toDouble())
        put("slgScale", slgScale?.toDouble())
        put("skipLayerStart", skipLayerStart?.toDouble())
        put("skipLayerEnd", skipLayerEnd?.toDouble())
        put("skipLayers", skipLayers)
        put("eta", eta?.toDouble())
        put("strength", strength?.toDouble())
        put("highNoiseImgCfgScale", highNoiseImgCfgScale?.toDouble())
        put("highNoiseGuidance", highNoiseGuidance?.toDouble())
        put("highNoiseSlgScale", highNoiseSlgScale?.toDouble())
        put("highNoiseSkipLayerStart", highNoiseSkipLayerStart?.toDouble())
        put("highNoiseSkipLayerEnd", highNoiseSkipLayerEnd?.toDouble())
        put("highNoiseSkipLayers", highNoiseSkipLayers)
        put("highNoiseEta", highNoiseEta?.toDouble())
        put("moeBoundary", moeBoundary?.toDouble())
        put("vaceStrength", vaceStrength?.toDouble())
        put("ipAdapterStrength", ipAdapterStrength?.toDouble())
        put("vaeFormat", vaeFormat)
        put("sigmas", sigmas)
        put("refImageArgs", refImageArgs)
        put("extraSampleArgs", extraSampleArgs)
        put("extraTilingArgs", extraTilingArgs)
        put("increaseRefIndex", increaseRefIndex)
        put("disableAutoResizeRefImage", disableAutoResizeRefImage)
        put("circular", circular)
        put("circularX", circularX)
        put("circularY", circularY)
        put("temporalTiling", temporalTiling)
        put("promptFormat", promptFormat?.name)
        put("lingBotPromptJson", lingBotPromptJson)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): VideoRuntimeOptions = VideoRuntimeOptions(
            videoFamily = SdVideoFamily.fromStoredValue(json.optString("videoFamily")),
            videoVariant = json.optString("videoVariant").ifBlank { null },
            workflow = SdVideoWorkflow.fromStoredValue(json.optString("workflow")),
            videoComponents = json.optJSONObject("videoComponents")?.toSdVideoComponentPaths()
                ?: SdVideoComponentPaths(),
            videoInputs = json.optJSONObject("videoInputs")?.toSdVideoInputs() ?: SdVideoInputs(),
            useTae = json.optBoolean("useTae", false),
            seed = json.optLong("seed", -1L),
            highNoiseSteps = json.optInt("highNoiseSteps", -1).takeIf { it >= 0 },
            highNoiseCfgScale = parseOptionalFloat(json, "highNoiseCfgScale"),
            highNoiseSamplingMethod = parseSamplingMethod(json.optString("highNoiseSamplingMethod")),
            controlStrength = parseOptionalFloat(json, "controlStrength"),
            vaeTileOverlap = json.optDouble("vaeTileOverlap", 0.5).toFloat(),
            vaeRelativeTileSize = json.optString("vaeRelativeTileSize"),
            hires = json.optJSONObject("hires")?.toSdVideoHiresConfig() ?: SdVideoHiresConfig(),
            outputFormat = parseEnum(json.optString("outputFormat"), SdVideoOutputFormat.MP4),
            nativeOutputFormat = parseEnum(json.optString("nativeOutputFormat"), SdVideoNativeOutputFormat.AVI),
            audioCodec = when {
                !json.has("audioCodec") -> SdVideoAudioCodec.AAC
                json.isNull("audioCodec") -> null
                else -> parseNullableEnum<SdVideoAudioCodec>(json.optString("audioCodec"), SdVideoAudioCodec.AAC)
            },
            imgCfgScale = parseOptionalFloat(json, "imgCfgScale"),
            guidance = parseOptionalFloat(json, "guidance"),
            slgScale = parseOptionalFloat(json, "slgScale"),
            skipLayerStart = parseOptionalFloat(json, "skipLayerStart"),
            skipLayerEnd = parseOptionalFloat(json, "skipLayerEnd"),
            skipLayers = json.optString("skipLayers"),
            eta = parseOptionalFloat(json, "eta"),
            strength = parseOptionalFloat(json, "strength"),
            highNoiseImgCfgScale = parseOptionalFloat(json, "highNoiseImgCfgScale"),
            highNoiseGuidance = parseOptionalFloat(json, "highNoiseGuidance"),
            highNoiseSlgScale = parseOptionalFloat(json, "highNoiseSlgScale"),
            highNoiseSkipLayerStart = parseOptionalFloat(json, "highNoiseSkipLayerStart"),
            highNoiseSkipLayerEnd = parseOptionalFloat(json, "highNoiseSkipLayerEnd"),
            highNoiseSkipLayers = json.optString("highNoiseSkipLayers"),
            highNoiseEta = parseOptionalFloat(json, "highNoiseEta"),
            moeBoundary = parseOptionalFloat(json, "moeBoundary"),
            vaceStrength = parseOptionalFloat(json, "vaceStrength"),
            ipAdapterStrength = parseOptionalFloat(json, "ipAdapterStrength"),
            vaeFormat = json.optString("vaeFormat").ifBlank { null },
            sigmas = json.optString("sigmas"),
            refImageArgs = json.optString("refImageArgs"),
            extraSampleArgs = json.optString("extraSampleArgs"),
            extraTilingArgs = json.optString("extraTilingArgs"),
            increaseRefIndex = json.optBoolean("increaseRefIndex", false),
            disableAutoResizeRefImage = json.optBoolean("disableAutoResizeRefImage", false),
            circular = json.optBoolean("circular", false),
            circularX = json.optBoolean("circularX", false),
            circularY = json.optBoolean("circularY", false),
            temporalTiling = json.optBoolean("temporalTiling", false),
            promptFormat = when {
                !json.has("promptFormat") || json.isNull("promptFormat") -> null
                else -> SdVideoPromptFormat.entries.firstOrNull {
                    it.name.equals(json.optString("promptFormat"), ignoreCase = true)
                }
            },
            conversionRecoveryEnabled = json.optBoolean("conversionRecoveryEnabled", true),
            lingBotPromptJson = json.optString("lingBotPromptJson").ifBlank { null }
        )

        private fun parseSamplingMethod(value: String): SamplingMethod? =
            value.takeIf { it.isNotBlank() }?.let { raw ->
                SamplingMethod.entries.firstOrNull {
                    it.name.equals(raw, ignoreCase = true) || it.cliName.equals(raw, ignoreCase = true)
                }
            }

        private inline fun <reified T : Enum<T>> parseEnum(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback

        private inline fun <reified T : Enum<T>> parseNullableEnum(value: String, fallback: T?): T? =
            if (value.isBlank()) fallback else enumValues<T>().firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: fallback

        private fun parseOptionalFloat(json: JSONObject, key: String): Float? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key).toFloat() else null
    }
}

/** Compact string form used by distributed settings and persisted drafts. */
fun VideoRuntimeOptions.toJsonString(indent: Int = 0): String = toJsonObject().toString(indent)

/** Parse a distributed/draft JSON value without allowing malformed optional state to escape. */
fun parseVideoRuntimeOptions(json: String): VideoRuntimeOptions? = runCatching {
    VideoRuntimeOptions.fromJsonObject(JSONObject(json))
}.getOrNull()

fun VideoGenerationConfig.toVideoRuntimeOptions(): VideoRuntimeOptions = VideoRuntimeOptions(
    videoFamily = videoFamily,
    videoVariant = videoVariant,
    workflow = workflow,
    videoComponents = videoComponents,
    videoInputs = videoInputs,
    useTae = useTae,
    seed = seed,
    highNoiseSteps = highNoiseSteps,
    highNoiseCfgScale = highNoiseCfgScale,
    highNoiseSamplingMethod = highNoiseSamplingMethod,
    controlStrength = controlStrength,
    vaeTileOverlap = vaeTileOverlap,
    vaeRelativeTileSize = vaeRelativeTileSize,
    hires = hires,
    outputFormat = outputFormat,
    nativeOutputFormat = nativeOutputFormat,
    audioCodec = audioCodec,
    conversionRecoveryEnabled = conversionRecoveryEnabled,
    imgCfgScale = imgCfgScale,
    guidance = guidance,
    slgScale = slgScale,
    skipLayerStart = skipLayerStart,
    skipLayerEnd = skipLayerEnd,
    skipLayers = skipLayers,
    eta = eta,
    strength = strength,
    highNoiseImgCfgScale = highNoiseImgCfgScale,
    highNoiseGuidance = highNoiseGuidance,
    highNoiseSlgScale = highNoiseSlgScale,
    highNoiseSkipLayerStart = highNoiseSkipLayerStart,
    highNoiseSkipLayerEnd = highNoiseSkipLayerEnd,
    highNoiseSkipLayers = highNoiseSkipLayers,
    highNoiseEta = highNoiseEta,
    moeBoundary = moeBoundary,
    vaceStrength = vaceStrength,
    ipAdapterStrength = ipAdapterStrength,
    vaeFormat = vaeFormat,
    sigmas = sigmas,
    refImageArgs = refImageArgs,
    extraSampleArgs = extraSampleArgs,
    extraTilingArgs = extraTilingArgs,
    increaseRefIndex = increaseRefIndex,
    disableAutoResizeRefImage = disableAutoResizeRefImage,
    circular = circular,
    circularX = circularX,
    circularY = circularY,
    temporalTiling = temporalTiling,
    promptFormat = promptFormat,
    lingBotPromptJson = lingBotPromptJson
)

fun VideoRuntimeOptions.applyTo(config: VideoGenerationConfig): VideoGenerationConfig = config.copy(
    videoFamily = videoFamily,
    videoVariant = videoVariant,
    workflow = workflow,
    videoComponents = videoComponents,
    videoInputs = videoInputs,
    useTae = useTae,
    seed = seed,
    highNoiseSteps = highNoiseSteps,
    highNoiseCfgScale = highNoiseCfgScale,
    highNoiseSamplingMethod = highNoiseSamplingMethod,
    controlStrength = controlStrength,
    vaeTileOverlap = vaeTileOverlap,
    vaeRelativeTileSize = vaeRelativeTileSize,
    hires = hires,
    outputFormat = outputFormat,
    nativeOutputFormat = nativeOutputFormat,
    audioCodec = audioCodec,
    conversionRecoveryEnabled = conversionRecoveryEnabled,
    imgCfgScale = imgCfgScale,
    guidance = guidance,
    slgScale = slgScale,
    skipLayerStart = skipLayerStart,
    skipLayerEnd = skipLayerEnd,
    skipLayers = skipLayers,
    eta = eta,
    strength = strength,
    highNoiseImgCfgScale = highNoiseImgCfgScale,
    highNoiseGuidance = highNoiseGuidance,
    highNoiseSlgScale = highNoiseSlgScale,
    highNoiseSkipLayerStart = highNoiseSkipLayerStart,
    highNoiseSkipLayerEnd = highNoiseSkipLayerEnd,
    highNoiseSkipLayers = highNoiseSkipLayers,
    highNoiseEta = highNoiseEta,
    moeBoundary = moeBoundary,
    vaceStrength = vaceStrength,
    ipAdapterStrength = ipAdapterStrength,
    vaeFormat = vaeFormat,
    sigmas = sigmas,
    refImageArgs = refImageArgs,
    extraSampleArgs = extraSampleArgs,
    extraTilingArgs = extraTilingArgs,
    increaseRefIndex = increaseRefIndex,
    disableAutoResizeRefImage = disableAutoResizeRefImage,
    circular = circular,
    circularX = circularX,
    circularY = circularY,
    temporalTiling = temporalTiling,
    promptFormat = promptFormat,
    lingBotPromptJson = lingBotPromptJson
)

class VideoGenerationStateHolder(val mode: VideoGenerationMode) {
    private val _state = MutableStateFlow<VideoGenerationState>(VideoGenerationState.Idle)
    val state: StateFlow<VideoGenerationState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep

    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps

    private val _etaSeconds = MutableStateFlow<Double?>(null)
    val etaSeconds: StateFlow<Double?> = _etaSeconds

    private val _currentPrompt = MutableStateFlow("")
    val currentPrompt: StateFlow<String> = _currentPrompt

    private val _generatedVideos = MutableStateFlow<List<GeneratedVideoMetadata>>(emptyList())
    val generatedVideos: StateFlow<List<GeneratedVideoMetadata>> = _generatedVideos

    fun updateState(newState: VideoGenerationState) {
        _state.value = newState
        when (newState) {
            is VideoGenerationState.Generating -> {
                _progress.value = newState.progress
                _status.value = newState.status
                _currentStep.value = newState.currentStep
                _totalSteps.value = newState.totalSteps
                _etaSeconds.value = newState.etaSeconds
            }
            is VideoGenerationState.Converting -> {
                _progress.value = newState.progress
                _status.value = newState.status
                _etaSeconds.value = null
            }
            is VideoGenerationState.Copying -> {
                _progress.value = newState.progress
                _status.value = newState.status
                _etaSeconds.value = null
            }
            is VideoGenerationState.Complete -> {
                _progress.value = 1f
                _status.value = ""
                _currentStep.value = _totalSteps.value
                _etaSeconds.value = null
                addVideo(newState.metadata)
            }
            is VideoGenerationState.Error -> {
                _progress.value = 0f
                _status.value = newState.message
                _currentStep.value = 0
                _etaSeconds.value = null
            }
            is VideoGenerationState.Idle -> {
                _progress.value = 0f
                _status.value = ""
                _currentStep.value = 0
                _totalSteps.value = 0
                _etaSeconds.value = null
            }
        }
    }

    fun updatePrompt(prompt: String) {
        _currentPrompt.value = prompt
    }

    fun setVideos(videos: List<GeneratedVideoMetadata>) {
        _generatedVideos.value = videos
    }

    fun addVideo(metadata: GeneratedVideoMetadata) {
        _generatedVideos.value = (_generatedVideos.value + metadata)
            .distinctBy { it.mp4Path }
            .sortedByDescending { it.createdAt }
    }

    fun removeVideo(metadata: GeneratedVideoMetadata) {
        _generatedVideos.value = _generatedVideos.value.filter { it.mp4Path != metadata.mp4Path }
    }

    fun reset() {
        _state.value = VideoGenerationState.Idle
        _progress.value = 0f
        _status.value = ""
        _currentStep.value = 0
        _etaSeconds.value = null
    }

    companion object {
        val txt2vid = VideoGenerationStateHolder(VideoGenerationMode.TXT2VID)
        val img2vid = VideoGenerationStateHolder(VideoGenerationMode.IMG2VID)

        val distributedTxt2vid = VideoGenerationStateHolder(VideoGenerationMode.TXT2VID)
        val distributedImg2vid = VideoGenerationStateHolder(VideoGenerationMode.IMG2VID)

        fun getForMode(mode: VideoGenerationMode): VideoGenerationStateHolder = when (mode) {
            VideoGenerationMode.TXT2VID -> txt2vid
            VideoGenerationMode.IMG2VID -> img2vid
        }

        fun getForMode(
            mode: VideoGenerationMode,
            useDistributedStateHolder: Boolean
        ): VideoGenerationStateHolder =
            if (useDistributedStateHolder) {
                when (mode) {
                    VideoGenerationMode.TXT2VID -> distributedTxt2vid
                    VideoGenerationMode.IMG2VID -> distributedImg2vid
                }
            } else {
                getForMode(mode)
            }

        fun getForModeIndex(index: Int): VideoGenerationStateHolder = when (index) {
            1 -> img2vid
            else -> txt2vid
        }
    }
}

fun loadGeneratedVideoMetadata(rootDir: File): List<GeneratedVideoMetadata> {
    val results = mutableListOf<GeneratedVideoMetadata>()
    VideoGenerationMode.entries.forEach { mode ->
        val dir = File(rootDir, mode.folderName)
        dir.listFiles()
            ?.filter { it.extension.equals("json", ignoreCase = true) }
            ?.forEach { metadataFile ->
                val metadata = GeneratedVideoMetadata.fromFile(metadataFile)
                if (metadata != null && File(metadata.preferredArtifactPath).exists()) {
                    results += metadata
                }
            }
    }
    return results.sortedByDescending { it.createdAt }
}
