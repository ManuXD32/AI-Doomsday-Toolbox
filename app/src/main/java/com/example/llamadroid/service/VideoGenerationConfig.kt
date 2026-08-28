package com.example.llamadroid.service

import android.os.Parcelable
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.activeInOrder
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.sd.toSdLoraSpecs
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
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
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    /** Ordered Wan/video adapters. [highNoiseLoras] is kept separate per Wan 2.2 item. */
    val loras: List<SdLoraSpec> = emptyList(),
    val highNoiseLoras: List<SdLoraSpec> = emptyList(),
    val loraApplyMode: SdLoraApplyMode? = null,
    val customFlags: String = ""
) : Parcelable

fun VideoGenerationConfig.resolvedLoras(): List<SdLoraSpec> =
    validateSdLoras(
        loras.map { it.copy(highNoiseOnly = false) } +
            highNoiseLoras.map { it.copy(highNoiseOnly = true) }
    )

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
    val exportedMetadataUri: String? = null
) {
    val modeEnum: VideoGenerationMode
        get() = VideoGenerationMode.entries.firstOrNull { it.folderName == mode } ?: VideoGenerationMode.TXT2VID

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
                exportedMetadataUri = json.optString("exportedMetadataUri").ifBlank { null }
            )

        private fun filePathFallback(json: JSONObject): String = json.optString("mp4Path") + ".json"

        private fun parseSamplingMethod(value: String): SamplingMethod {
            return SamplingMethod.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.cliName.equals(value, ignoreCase = true)
            } ?: SamplingMethod.EULER
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
                if (metadata != null && File(metadata.mp4Path).exists()) {
                    results += metadata
                }
            }
    }
    return results.sortedByDescending { it.createdAt }
}
