package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoAudioCodec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoMainModelLayout
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.toJsonArray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Destination selected by the gallery reuse action. */
enum class VideoReuseTarget {
    LOCAL,
    DISTRIBUTED
}

enum class VideoReuseWarningCode {
    METADATA_UNAVAILABLE,
    MALFORMED_METADATA,
    ADVANCED_OPTIONS_UNAVAILABLE,
    MISSING_REFERENCE,
    DISTRIBUTED_WORKER_MISMATCH,
    DESTINATION_OPTION_UNAVAILABLE
}

/** A warning is deliberately bilingual so it can be shown before Android resources are ready. */
data class VideoReuseWarning(
    val code: VideoReuseWarningCode,
    val field: String? = null,
    val path: String? = null,
    val english: String,
    val spanish: String
) {
    fun message(spanish: Boolean): String = if (spanish) this.spanish else english
}

data class VideoReusePayloadReadResult(
    val payload: VideoReusePayload?,
    val warnings: List<VideoReuseWarning> = emptyList()
)

enum class VideoReuseReferenceSource {
    RECORDED_PATH,
    VERIFIED_SOURCE,
    MISSING
}

/**
 * Evidence for one recorded path. Resolution is exact-path based: a basename is
 * never enough to replace a missing model or input.
 */
data class VideoReuseReferenceEvidence(
    val field: String,
    val recordedPath: String,
    val selectedPath: String,
    val source: VideoReuseReferenceSource
)

/** A small, typed worker snapshot used by the distributed draft adapter. */
data class VideoReuseWorker(
    val host: String,
    val port: Int,
    val enabled: Boolean = true
) {
    val endpoint: String
        get() = "${host.trim()}:$port".lowercase(Locale.US)
}

fun SdDistributedWorkerEntity.toVideoReuseWorker(): VideoReuseWorker =
    VideoReuseWorker(host = host, port = port, enabled = isEnabled)

fun Iterable<SdDistributedWorkerEntity>.toVideoReuseWorkers(): List<VideoReuseWorker> =
    map { it.toVideoReuseWorker() }

data class VideoReuseDistributedValidation(
    val compatible: Boolean,
    val missingEndpoints: List<String> = emptyList(),
    val missingRpcNames: List<String> = emptyList()
)

/**
 * Metadata required to refill either the local video form or the distributed
 * master form. This is persisted as JSON only through metadata/handoff files;
 * it is intentionally not a Parcelable/Intent extra.
 */
data class VideoReusePayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mode: VideoGenerationMode,
    val prompt: String,
    val negativePrompt: String = "",
    val modelPath: String,
    val modelName: String = "",
    val vaeEnabled: Boolean = false,
    val vaePath: String? = null,
    val t5xxlEnabled: Boolean = false,
    val t5xxlPath: String? = null,
    val initImagePath: String? = null,
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
    val threads: Int = -1,
    val vaeTiling: Boolean = false,
    val vaeTileSize: String = "24x24",
    val diffusionFa: Boolean = true,
    val diffusionConvDirect: Boolean = false,
    val vaeConvDirect: Boolean = false,
    val mmap: Boolean = true,
    val sdParamsBackendMode: String = "auto",
    val sdParamsBackendSpec: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    val customFlags: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    val loras: List<SdLoraSpec> = emptyList(),
    val highNoiseLoras: List<SdLoraSpec> = emptyList(),
    val loraApplyMode: String? = null,
    val videoFamily: String? = null,
    val videoVariant: String? = null,
    val workflow: String? = null,
    val videoComponents: SdVideoComponentPaths = SdVideoComponentPaths(),
    val videoInputs: SdVideoInputs = SdVideoInputs(),
    val useTae: Boolean = false,
    val runtimeOptions: VideoRuntimeOptions = VideoRuntimeOptions(),
    /** False means this sidecar predates the full typed runtime object. */
    val runtimeOptionsAvailable: Boolean = true,
    val sourceMetadataPath: String? = null
) {
    val mainModelPath: String
        get() = modelPath.ifBlank {
            videoComponents.diffusionModelPath
                ?: videoComponents.fullModelPath
                ?: ""
        }

    /** Runtime options with metadata's legacy paths filled in, in exact recorded order. */
    val resolvedRuntimeOptions: VideoRuntimeOptions
        get() = runtimeOptions.copy(
            videoFamily = runtimeOptions.videoFamily ?: com.example.llamadroid.sd.SdVideoFamily.fromStoredValue(videoFamily),
            videoVariant = runtimeOptions.videoVariant ?: videoVariant,
            workflow = runtimeOptions.workflow ?: SdVideoWorkflow.fromStoredValue(workflow)
                ?: if (mode == VideoGenerationMode.IMG2VID) SdVideoWorkflow.IMAGE_TO_VIDEO else SdVideoWorkflow.TEXT_TO_VIDEO,
            videoComponents = normalizeMainModelComponents(
                mergeComponents(
                    runtimeOptions.videoComponents,
                    videoComponents,
                    modelPath = mainModelPath,
                    vaePath = vaePath,
                    taePath = runtimeOptions.videoComponents.taePath,
                    t5xxlPath = t5xxlPath
                ),
                runtimeOptions.videoFamily
                    ?: com.example.llamadroid.sd.SdVideoFamily.fromStoredValue(videoFamily),
                runtimeOptions.videoVariant ?: videoVariant
            ),
            videoInputs = mergeInputs(runtimeOptions.videoInputs, videoInputs, initImagePath),
            useTae = runtimeOptions.useTae || useTae
        )

    /**
     * Replace a missing recorded path only when the caller supplies an exact,
     * readable source for that field. A source map is intentionally keyed by
     * role and never inferred from a filename, so a duplicate basename cannot
     * silently select the wrong model or input.
     */
    fun withResolvedReferences(verifiedSources: Map<String, String>): VideoReusePayload {
        if (verifiedSources.isEmpty()) return this

        val currentRuntime = resolvedRuntimeOptions
        val currentComponents = currentRuntime.videoComponents
        val currentInputs = currentRuntime.videoInputs
        val resolvedModel = resolveReference("model", mainModelPath, verifiedSources).orEmpty()
        fun resolveComponent(field: String, path: String?): String? {
            val resolved = resolveReference(field, path, verifiedSources)
            return if (
                resolved == path &&
                !path.isNullOrBlank() &&
                sameRecordedPath(path, mainModelPath) &&
                isReadablePath(resolvedModel)
            ) {
                resolvedModel
            } else {
                resolved
            }
        }
        val resolvedComponents = currentComponents.copy(
            diffusionModelPath = resolveComponent(
                "videoComponents.diffusionModelPath",
                currentComponents.diffusionModelPath,
            ),
            fullModelPath = resolveComponent(
                "videoComponents.fullModelPath",
                currentComponents.fullModelPath,
            ),
            highNoiseDiffusionModelPath = resolveReference(
                "videoComponents.highNoiseDiffusionModelPath",
                currentComponents.highNoiseDiffusionModelPath,
                verifiedSources
            ),
            uncondDiffusionModelPath = resolveReference(
                "videoComponents.uncondDiffusionModelPath",
                currentComponents.uncondDiffusionModelPath,
                verifiedSources
            ),
            ipAdapterPath = resolveReference(
                "videoComponents.ipAdapterPath",
                currentComponents.ipAdapterPath,
                verifiedSources
            ),
            vaePath = resolveReference("videoComponents.vaePath", currentComponents.vaePath, verifiedSources),
            taePath = resolveReference("videoComponents.taePath", currentComponents.taePath, verifiedSources),
            t5xxlPath = resolveReference("videoComponents.t5xxlPath", currentComponents.t5xxlPath, verifiedSources),
            llmPath = resolveReference("videoComponents.llmPath", currentComponents.llmPath, verifiedSources),
            llmVisionPath = resolveReference(
                "videoComponents.llmVisionPath",
                currentComponents.llmVisionPath,
                verifiedSources
            ),
            audioVaePath = resolveReference("videoComponents.audioVaePath", currentComponents.audioVaePath, verifiedSources),
            embeddingsConnectorsPath = resolveReference(
                "videoComponents.embeddingsConnectorsPath",
                currentComponents.embeddingsConnectorsPath,
                verifiedSources
            ),
            motionModulePath = resolveReference(
                "videoComponents.motionModulePath",
                currentComponents.motionModulePath,
                verifiedSources
            ),
            clipVisionPath = resolveReference(
                "videoComponents.clipVisionPath",
                currentComponents.clipVisionPath,
                verifiedSources
            ),
            controlNetPath = resolveReference(
                "videoComponents.controlNetPath",
                currentComponents.controlNetPath,
                verifiedSources
            ),
            hiresUpscalersDir = resolveReference(
                "videoComponents.hiresUpscalersDir",
                currentComponents.hiresUpscalersDir,
                verifiedSources
            ),
            hiresUpscaler = resolveReference(
                "videoComponents.hiresUpscaler",
                currentComponents.hiresUpscaler,
                verifiedSources
            )
        )
        val resolvedInputs = currentInputs.copy(
            initImagePath = resolveReference("input", currentInputs.initImagePath, verifiedSources),
            endImagePath = resolveReference("endImage", currentInputs.endImagePath, verifiedSources),
            controlImagePath = resolveReference("controlImage", currentInputs.controlImagePath, verifiedSources),
            controlVideoPath = resolveReference("controlVideo", currentInputs.controlVideoPath, verifiedSources),
            referenceImages = currentInputs.referenceImages.mapIndexed { index, path ->
                resolveReference("referenceImages[$index]", path, verifiedSources) ?: ""
            },
            referenceVideos = currentInputs.referenceVideos.mapIndexed { index, path ->
                resolveReference("referenceVideos[$index]", path, verifiedSources) ?: ""
            },
            referenceVideoAudios = currentInputs.referenceVideoAudios.mapIndexed { index, path ->
                resolveReference("referenceVideoAudios[$index]", path, verifiedSources) ?: ""
            },
            referenceAudios = currentInputs.referenceAudios.mapIndexed { index, path ->
                resolveReference("referenceAudios[$index]", path, verifiedSources) ?: ""
            },
            ipAdapterImagePath = resolveReference("ipAdapterImage", currentInputs.ipAdapterImagePath, verifiedSources)
        )
        val selectedModel = when {
            isReadablePath(resolvedModel) -> resolvedModel
            isReadablePath(resolvedComponents.diffusionModelPath.orEmpty()) ->
                resolvedComponents.diffusionModelPath.orEmpty()
            isReadablePath(resolvedComponents.fullModelPath.orEmpty()) ->
                resolvedComponents.fullModelPath.orEmpty()
            else -> resolvedModel
        }
        return copy(
            modelPath = selectedModel,
            vaePath = resolveReference("vae", vaePath, verifiedSources),
            t5xxlPath = resolveReference("t5xxl", t5xxlPath, verifiedSources),
            initImagePath = resolveReference("input", initImagePath, verifiedSources),
            videoComponents = resolvedComponents,
            videoInputs = resolvedInputs,
            loras = loras.mapIndexed { index, lora ->
                lora.copy(path = resolveReference("loras[$index]", lora.path, verifiedSources).orEmpty())
            },
            highNoiseLoras = highNoiseLoras.mapIndexed { index, lora ->
                lora.copy(path = resolveReference("highNoiseLoras[$index]", lora.path, verifiedSources).orEmpty())
            },
            runtimeOptions = currentRuntime.copy(
                videoComponents = resolvedComponents,
                videoInputs = resolvedInputs
            )
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("mode", mode.folderName)
        put("modelPath", modelPath)
        put("model", modelPath)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("diffusionModelPath", mainModelPath)
        put("diffusionModelName", modelName.ifBlank { File(mainModelPath).name })
        put("vaeEnabled", vaeEnabled)
        putNullable("vaePath", vaePath)
        put("t5xxlEnabled", t5xxlEnabled)
        putNullable("t5xxlPath", t5xxlPath)
        putNullable("initImagePath", initImagePath)
        put("videoFrames", videoFrames)
        put("fps", fps)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfgScale", cfgScale.toDouble())
        putNullable("flowShift", flowShift?.toDouble())
        put("samplingMethod", samplingMethod.name)
        putNullable("scheduler", scheduler?.name)
        putNullable("cacheMode", cacheMode?.name)
        put("cacheOption", cacheOption)
        put("scmMask", scmMask)
        putNullable("scmPolicy", scmPolicy?.name)
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
        put("customFlags", customFlags)
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
        putNullable("loraApplyMode", loraApplyMode)
        putNullable("videoFamily", videoFamily)
        putNullable("videoVariant", videoVariant)
        putNullable("workflow", workflow)
        put("videoComponents", resolvedRuntimeOptions.videoComponents.toJsonObject())
        put("videoInputs", resolvedRuntimeOptions.videoInputs.toJsonObject())
        put("useTae", resolvedRuntimeOptions.useTae)
        put("videoRuntimeOptions", resolvedRuntimeOptions.toJsonObject())
        put("runtimeOptionsAvailable", runtimeOptionsAvailable)
        putNullable("sourceMetadataPath", sourceMetadataPath)
    }

    /** Exact path evidence used to build visible warnings without basename fallback. */
    fun referenceEvidence(verifiedSources: Map<String, String> = emptyMap()): List<VideoReuseReferenceEvidence> {
        val result = mutableListOf<VideoReuseReferenceEvidence>()

        fun add(field: String, recorded: String?) {
            val path = recorded?.trim().orEmpty()
            if (path.isBlank()) return
            val verified = verifiedSources[field]?.trim()?.takeIf { it.isNotBlank() }
            val source = when {
                isReadableReference(field, path) -> VideoReuseReferenceSource.RECORDED_PATH
                verified != null && isReadableReference(field, verified) -> VideoReuseReferenceSource.VERIFIED_SOURCE
                else -> VideoReuseReferenceSource.MISSING
            }
            val selected = when (source) {
                VideoReuseReferenceSource.RECORDED_PATH -> path
                VideoReuseReferenceSource.VERIFIED_SOURCE -> verified.orEmpty()
                VideoReuseReferenceSource.MISSING -> path
            }
            result += VideoReuseReferenceEvidence(field, path, selected, source)
        }

        add("model", mainModelPath)
        add("vae", vaePath)
        add("t5xxl", t5xxlPath)
        add("input", resolvedRuntimeOptions.videoInputs.initImagePath ?: initImagePath)
        val components = resolvedRuntimeOptions.videoComponents
        add("videoComponents.diffusionModelPath", components.diffusionModelPath)
        add("videoComponents.fullModelPath", components.fullModelPath)
        add("videoComponents.highNoiseDiffusionModelPath", components.highNoiseDiffusionModelPath)
        add("videoComponents.uncondDiffusionModelPath", components.uncondDiffusionModelPath)
        add("videoComponents.ipAdapterPath", components.ipAdapterPath)
        add("videoComponents.vaePath", components.vaePath)
        add("videoComponents.taePath", components.taePath)
        add("videoComponents.t5xxlPath", components.t5xxlPath)
        add("videoComponents.llmPath", components.llmPath)
        add("videoComponents.llmVisionPath", components.llmVisionPath)
        add("videoComponents.audioVaePath", components.audioVaePath)
        add("videoComponents.embeddingsConnectorsPath", components.embeddingsConnectorsPath)
        add("videoComponents.motionModulePath", components.motionModulePath)
        add("videoComponents.clipVisionPath", components.clipVisionPath)
        add("videoComponents.controlNetPath", components.controlNetPath)
        add("videoComponents.hiresUpscalersDir", components.hiresUpscalersDir)
        add("videoComponents.hiresUpscaler", components.hiresUpscaler)
        val inputs = resolvedRuntimeOptions.videoInputs
        add("endImage", inputs.endImagePath)
        add("controlImage", inputs.controlImagePath)
        add("controlVideo", inputs.controlVideoPath)
        add("ipAdapterImage", inputs.ipAdapterImagePath)
        inputs.referenceImages.forEachIndexed { index, path -> add("referenceImages[$index]", path) }
        inputs.referenceVideos.forEachIndexed { index, path -> add("referenceVideos[$index]", path) }
        inputs.referenceVideoAudios.forEachIndexed { index, path -> add("referenceVideoAudios[$index]", path) }
        inputs.referenceAudios.forEachIndexed { index, path -> add("referenceAudios[$index]", path) }
        loras.forEachIndexed { index, lora -> add("loras[$index]", lora.path) }
        highNoiseLoras.forEachIndexed { index, lora -> add("highNoiseLoras[$index]", lora.path) }
        return result
    }

    fun warnings(verifiedSources: Map<String, String> = emptyMap()): List<VideoReuseWarning> = buildList {
        if (!runtimeOptionsAvailable) {
            add(
                VideoReuseWarning(
                    code = VideoReuseWarningCode.ADVANCED_OPTIONS_UNAVAILABLE,
                    english = "This older video metadata does not contain advanced runtime options; safe defaults were restored.",
                    spanish = "Estos metadatos de vídeo antiguos no contienen opciones avanzadas; se restauraron valores seguros."
                )
            )
        }
        withResolvedReferences(verifiedSources).referenceEvidence()
            .filter { it.source == VideoReuseReferenceSource.MISSING }
            .forEach { evidence ->
                add(
                    VideoReuseWarning(
                        code = VideoReuseWarningCode.MISSING_REFERENCE,
                        field = evidence.field,
                        path = evidence.recordedPath,
                        english = "Saved file '${File(evidence.recordedPath).name}' is unavailable. Choose a replacement in the form.",
                        spanish = "El archivo guardado '${File(evidence.recordedPath).name}' no está disponible. Selecciona un sustituto en el formulario."
                    )
                )
            }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun fromMetadata(
            metadata: GeneratedVideoMetadata,
            sourceMetadataPath: String? = metadata.metadataPath
        ): VideoReusePayload {
            val nested = metadata.videoRuntimeOptions
            val fallback = VideoRuntimeOptions(
                videoFamily = com.example.llamadroid.sd.SdVideoFamily.fromStoredValue(metadata.videoFamily),
                videoVariant = metadata.videoVariant,
                workflow = SdVideoWorkflow.fromStoredValue(metadata.workflow)
                    ?: if (metadata.modeEnum == VideoGenerationMode.IMG2VID) {
                        SdVideoWorkflow.IMAGE_TO_VIDEO
                    } else {
                        SdVideoWorkflow.TEXT_TO_VIDEO
                    },
                videoComponents = mergeComponents(
                    SdVideoComponentPaths(),
                    metadata.videoComponents,
                    metadata.diffusionModelPath,
                    metadata.vaePath,
                    metadata.taePath,
                    metadata.t5xxlPath
                ),
                videoInputs = mergeInputs(metadata.videoInputs, SdVideoInputs(), metadata.initImagePath),
                useTae = metadata.useTae,
                seed = metadata.seed,
                highNoiseSteps = metadata.highNoiseSteps,
                highNoiseCfgScale = metadata.highNoiseCfgScale,
                highNoiseSamplingMethod = metadata.highNoiseSamplingMethod,
                controlStrength = metadata.controlStrength,
                vaeTileOverlap = metadata.vaeTileOverlap,
                vaeRelativeTileSize = metadata.vaeRelativeTileSize.orEmpty(),
                hires = metadata.hires,
                outputFormat = parseOutputFormat(metadata.outputFormat),
                nativeOutputFormat = parseNativeOutputFormat(metadata.nativeOutputFormat),
                audioCodec = parseAudioCodec(metadata.audioCodec),
                promptFormat = com.example.llamadroid.sd.SdVideoPromptFormat.entries.firstOrNull {
                    it.name.equals(metadata.videoRuntimeOptions?.promptFormat?.name, ignoreCase = true)
                }
            )
            val runtime = (nested ?: fallback).copy(
                videoComponents = normalizeMainModelComponents(
                    mergeComponents(
                        nested?.videoComponents ?: SdVideoComponentPaths(),
                        metadata.videoComponents,
                        metadata.diffusionModelPath,
                        metadata.vaePath,
                        metadata.taePath,
                        metadata.t5xxlPath
                    ),
                    nested?.videoFamily ?: fallback.videoFamily,
                    nested?.videoVariant ?: fallback.videoVariant
                ),
                videoInputs = mergeInputs(nested?.videoInputs ?: SdVideoInputs(), metadata.videoInputs, metadata.initImagePath),
                workflow = nested?.workflow ?: fallback.workflow,
                videoFamily = nested?.videoFamily ?: fallback.videoFamily,
                videoVariant = nested?.videoVariant ?: fallback.videoVariant,
                useTae = nested?.useTae ?: metadata.useTae
            )
            return VideoReusePayload(
                mode = metadata.modeEnum,
                prompt = metadata.prompt,
                negativePrompt = metadata.negativePrompt,
                modelPath = metadata.diffusionModelPath.ifBlank {
                    metadata.videoComponents.diffusionModelPath
                        ?: metadata.videoComponents.fullModelPath
                        ?: runtime.videoComponents.diffusionModelPath
                        ?: runtime.videoComponents.fullModelPath
                        ?: ""
                },
                modelName = metadata.diffusionModelName,
                vaeEnabled = metadata.vaeEnabled || runtime.videoComponents.vaePath != null && !runtime.useTae,
                vaePath = metadata.vaePath ?: runtime.videoComponents.vaePath,
                t5xxlEnabled = metadata.t5xxlEnabled || runtime.videoComponents.t5xxlPath != null,
                t5xxlPath = metadata.t5xxlPath ?: runtime.videoComponents.t5xxlPath,
                initImagePath = metadata.initImagePath ?: runtime.videoInputs.initImagePath,
                videoFrames = metadata.videoFrames,
                fps = metadata.fps,
                width = metadata.width,
                height = metadata.height,
                steps = metadata.steps,
                cfgScale = metadata.cfgScale,
                flowShift = metadata.flowShift,
                samplingMethod = metadata.samplingMethod,
                scheduler = metadata.scheduler,
                cacheMode = metadata.cacheMode,
                cacheOption = metadata.cacheOption,
                scmMask = metadata.scmMask,
                scmPolicy = metadata.scmPolicy,
                threads = metadata.threads,
                vaeTiling = metadata.vaeTiling,
                vaeTileSize = metadata.vaeTileSize ?: "24x24",
                diffusionFa = metadata.diffusionFa,
                diffusionConvDirect = metadata.diffusionConvDirect,
                vaeConvDirect = metadata.vaeConvDirect,
                mmap = metadata.mmap,
                sdParamsBackendMode = metadata.sdParamsBackendMode,
                sdParamsBackendSpec = metadata.sdParamsBackendSpec,
                sdRuntimeBackendMode = metadata.sdRuntimeBackendMode,
                maxVramCpuGiB = metadata.maxVramCpuGiB,
                customFlags = metadata.customFlags,
                distributedRuntime = metadata.distributedRuntime,
                // Keep the recorded array order and stage bit. The high-noise
                // array is independently ordered and its role implies the bit.
                loras = metadata.loras,
                highNoiseLoras = metadata.highNoiseLoras.map { it.copy(highNoiseOnly = true) },
                loraApplyMode = metadata.loraApplyMode,
                videoFamily = runtime.videoFamily?.storedValue,
                videoVariant = runtime.videoVariant,
                workflow = runtime.workflow?.storedValue,
                videoComponents = runtime.videoComponents,
                videoInputs = runtime.videoInputs,
                useTae = runtime.useTae,
                runtimeOptions = runtime,
                runtimeOptionsAvailable = nested != null,
                sourceMetadataPath = sourceMetadataPath
            )
        }

        fun fromMetadataFile(file: File): VideoReusePayloadReadResult {
            if (!file.isFile || !file.canRead()) {
                return VideoReusePayloadReadResult(
                    payload = null,
                    warnings = listOf(
                        VideoReuseWarning(
                            code = VideoReuseWarningCode.METADATA_UNAVAILABLE,
                            path = file.path,
                            english = "Video metadata is unavailable at '${file.path}'.",
                            spanish = "Los metadatos de vídeo no están disponibles en '${file.path}'."
                        )
                    )
                )
            }
            return try {
                val metadata = GeneratedVideoMetadata.fromFile(file)
                    ?: throw IllegalArgumentException("metadata parser returned null")
                val payload = fromMetadata(metadata, file.path)
                VideoReusePayloadReadResult(payload, payload.warnings())
            } catch (_: Throwable) {
                VideoReusePayloadReadResult(
                    payload = null,
                    warnings = listOf(
                        VideoReuseWarning(
                            code = VideoReuseWarningCode.MALFORMED_METADATA,
                            path = file.path,
                            english = "Video metadata at '${file.path}' could not be read.",
                            spanish = "No se pudieron leer los metadatos de vídeo en '${file.path}'."
                        )
                    )
                )
            }
        }

        fun fromJson(json: JSONObject): VideoReusePayload {
            val metadata = GeneratedVideoMetadata.fromJson(json)
            return fromMetadata(
                metadata,
                json.optString("sourceMetadataPath")
                    .ifBlank { metadata.metadataPath }
                    .ifBlank { null }
            ).copy(
                schemaVersion = json.optInt("schemaVersion", CURRENT_SCHEMA_VERSION),
                runtimeOptionsAvailable = json.optBoolean(
                    "runtimeOptionsAvailable",
                    json.optJSONObject("videoRuntimeOptions") != null
                )
            )
        }

        fun fromJsonOrNull(json: String): VideoReusePayload? = runCatching {
            fromJson(JSONObject(json))
        }.getOrNull()

        private fun mergeComponents(
            preferred: SdVideoComponentPaths,
            secondary: SdVideoComponentPaths,
            modelPath: String?,
            vaePath: String?,
            taePath: String?,
            t5xxlPath: String?
        ): SdVideoComponentPaths = preferred.copy(
            diffusionModelPath = preferred.diffusionModelPath ?: secondary.diffusionModelPath ?: modelPath,
            fullModelPath = preferred.fullModelPath ?: secondary.fullModelPath,
            highNoiseDiffusionModelPath = preferred.highNoiseDiffusionModelPath ?: secondary.highNoiseDiffusionModelPath,
            uncondDiffusionModelPath = preferred.uncondDiffusionModelPath ?: secondary.uncondDiffusionModelPath,
            ipAdapterPath = preferred.ipAdapterPath ?: secondary.ipAdapterPath,
            vaePath = preferred.vaePath ?: secondary.vaePath ?: vaePath,
            taePath = preferred.taePath ?: secondary.taePath ?: taePath,
            t5xxlPath = preferred.t5xxlPath ?: secondary.t5xxlPath ?: t5xxlPath,
            llmPath = preferred.llmPath ?: secondary.llmPath,
            llmVisionPath = preferred.llmVisionPath ?: secondary.llmVisionPath,
            audioVaePath = preferred.audioVaePath ?: secondary.audioVaePath,
            embeddingsConnectorsPath = preferred.embeddingsConnectorsPath ?: secondary.embeddingsConnectorsPath,
            motionModulePath = preferred.motionModulePath ?: secondary.motionModulePath,
            clipVisionPath = preferred.clipVisionPath ?: secondary.clipVisionPath,
            controlNetPath = preferred.controlNetPath ?: secondary.controlNetPath,
            hiresUpscalersDir = preferred.hiresUpscalersDir ?: secondary.hiresUpscalersDir,
            hiresUpscaler = preferred.hiresUpscaler ?: secondary.hiresUpscaler
        )

        private fun mergeInputs(
            preferred: SdVideoInputs,
            secondary: SdVideoInputs,
            initImagePath: String?
        ): SdVideoInputs = preferred.copy(
            initImagePath = preferred.initImagePath ?: secondary.initImagePath ?: initImagePath,
            endImagePath = preferred.endImagePath ?: secondary.endImagePath,
            controlImagePath = preferred.controlImagePath ?: secondary.controlImagePath,
            controlVideoPath = preferred.controlVideoPath ?: secondary.controlVideoPath,
            referenceImages = preferred.referenceImages.ifEmpty { secondary.referenceImages },
            referenceVideos = preferred.referenceVideos.ifEmpty { secondary.referenceVideos },
            referenceVideoAudios = preferred.referenceVideoAudios.ifEmpty { secondary.referenceVideoAudios },
            referenceAudios = preferred.referenceAudios.ifEmpty { secondary.referenceAudios },
            ipAdapterImagePath = preferred.ipAdapterImagePath ?: secondary.ipAdapterImagePath
        )

        private fun parseOutputFormat(value: String): com.example.llamadroid.sd.SdVideoOutputFormat =
            enumValues<com.example.llamadroid.sd.SdVideoOutputFormat>().firstOrNull { it.name.equals(value, true) }
                ?: com.example.llamadroid.sd.SdVideoOutputFormat.MP4

        private fun parseNativeOutputFormat(value: String): com.example.llamadroid.sd.SdVideoNativeOutputFormat =
            enumValues<com.example.llamadroid.sd.SdVideoNativeOutputFormat>().firstOrNull { it.name.equals(value, true) }
                ?: com.example.llamadroid.sd.SdVideoNativeOutputFormat.AVI

        private fun parseAudioCodec(value: String?): SdVideoAudioCodec? = when {
            value == null -> SdVideoAudioCodec.AAC
            value.isBlank() -> null
            else -> enumValues<SdVideoAudioCodec>().firstOrNull { it.name.equals(value, true) }
        }
    }
}

private fun isReadablePath(path: String): Boolean = File(path).isFile && File(path).canRead()

private fun isReadableReference(field: String, path: String): Boolean =
    if (field == "videoComponents.hiresUpscalersDir" || field == "controlVideo" || field.startsWith("referenceVideos[")) {
        File(path).isDirectory && File(path).canRead()
    } else {
        isReadablePath(path)
    }

private fun resolveReference(
    field: String,
    recorded: String?,
    verifiedSources: Map<String, String>
): String? {
    val original = recorded?.trim().orEmpty()
    if (original.isBlank()) return recorded
    if (isReadableReference(field, original)) return original
    val verified = verifiedSources[field]?.trim().orEmpty()
    return verified.takeIf { isReadableReference(field, it) } ?: recorded
}

/**
 * Older metadata and the legacy config bridge could write one main model into
 * both roles. When the family declares a layout, retain the role that the
 * family actually consumes; with no family evidence, preserve both paths.
 */
private fun normalizeMainModelComponents(
    components: SdVideoComponentPaths,
    family: com.example.llamadroid.sd.SdVideoFamily?,
    variant: String?
): SdVideoComponentPaths {
    val diffusion = components.diffusionModelPath
    val full = components.fullModelPath
    if (diffusion.isNullOrBlank() || full.isNullOrBlank() || !sameRecordedPath(diffusion, full)) {
        return components
    }
    val layout = family
        ?.let { com.example.llamadroid.sd.SdVideoFamilyProfiles.resolve(it, variant).mainModelLayout }
        ?: return components
    return when (layout) {
        SdVideoMainModelLayout.FULL_MODEL -> components.copy(diffusionModelPath = null)
        SdVideoMainModelLayout.STANDALONE_DIFFUSION -> components.copy(fullModelPath = null)
    }
}

private fun sameRecordedPath(left: String, right: String): Boolean {
    if (left.trim() == right.trim()) return true
    return runCatching { File(left).canonicalFile == File(right).canonicalFile }.getOrDefault(false)
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun warningsToJson(warnings: List<VideoReuseWarning>): JSONArray = JSONArray().also { array ->
    warnings.forEach { warning ->
        array.put(
            JSONObject()
                .put("code", warning.code.name)
                .putNullable("field", warning.field)
                .putNullable("path", warning.path)
                .put("english", warning.english)
                .put("spanish", warning.spanish)
        )
    }
}

data class VideoReuseDraftResult(
    val json: JSONObject,
    val warnings: List<VideoReuseWarning> = emptyList(),
    val target: VideoReuseTarget
)

data class VideoReuseDistributedDraft(
    val settings: SdDistributedMasterSettingsEntity,
    val json: JSONObject,
    val warnings: List<VideoReuseWarning> = emptyList()
)

object VideoReuseDraftAdapter {
    fun toLocalDraft(
        payload: VideoReusePayload,
        existingDraft: JSONObject? = null,
        verifiedSources: Map<String, String> = emptyMap()
    ): VideoReuseDraftResult {
        val resolvedPayload = payload.withResolvedReferences(verifiedSources)
        val runtime = resolvedPayload.resolvedRuntimeOptions
        val draft = existingDraft?.let { JSONObject(it.toString()) } ?: JSONObject()
        val warnings = payload.warnings(verifiedSources)
        val modelPath = resolvedPayload.mainModelPath
        val placement = parsePlacement(resolvedPayload.sdRuntimeBackendMode)
        draft.put("mode", if (resolvedPayload.mode == VideoGenerationMode.IMG2VID) 1 else 0)
        draft.put("model", modelPath)
        draft.put("prompt", resolvedPayload.prompt)
        draft.put("negativePrompt", resolvedPayload.negativePrompt)
        draft.put("useVae", resolvedPayload.vaeEnabled && !runtime.useTae)
        draft.putNullable("vae", resolvedPayload.vaePath)
        draft.put("useT5", resolvedPayload.t5xxlEnabled)
        draft.putNullable("t5", resolvedPayload.t5xxlPath)
        draft.putNullable("input", resolvedPayload.initImagePath ?: runtime.videoInputs.initImagePath)
        draft.put("frames", resolvedPayload.videoFrames.toString())
        draft.put("fps", resolvedPayload.fps.toString())
        draft.put("width", resolvedPayload.width.toString())
        draft.put("height", resolvedPayload.height.toString())
        draft.put("steps", resolvedPayload.steps.toString())
        draft.put("cfg", resolvedPayload.cfgScale.toString())
        draft.put("threads", resolvedPayload.threads.toString())
        draft.put("sampler", resolvedPayload.samplingMethod.name)
        draft.putNullable("scheduler", resolvedPayload.scheduler?.cliName)
        draft.put("flowShiftEnabled", resolvedPayload.flowShift != null)
        draft.put("flowShift", resolvedPayload.flowShift?.toString().orEmpty())
        draft.put("vaeTileSize", resolvedPayload.vaeTileSize)
        draft.put("vaeTiling", resolvedPayload.vaeTiling)
        draft.put("diffusionFa", resolvedPayload.diffusionFa)
        draft.put("mmap", resolvedPayload.mmap)
        draft.putNullable("cacheMode", resolvedPayload.cacheMode?.cliName)
        draft.put("cacheOption", resolvedPayload.cacheOption)
        draft.put("scmMask", resolvedPayload.scmMask)
        draft.putNullable("scmPolicy", resolvedPayload.scmPolicy?.cliName)
        draft.put("diffConv", resolvedPayload.diffusionConvDirect)
        draft.put("vaeConv", resolvedPayload.vaeConvDirect)
        draft.put("flags", resolvedPayload.customFlags)
        draft.put("customFlags", resolvedPayload.customFlags)
        draft.put("loras", resolvedPayload.loras.toJsonArray())
        draft.put("highNoiseLoras", resolvedPayload.highNoiseLoras.toJsonArray())
        draft.putNullable("loraApplyMode", resolvedPayload.loraApplyMode)
        draft.put("tePlacement", placement["te"] ?: "cpu")
        draft.put("diffusionPlacement", placement["diffusion"] ?: "cpu")
        draft.put("vaePlacement", placement["vae"] ?: "cpu")
        draft.put("sdParamsBackendMode", resolvedPayload.sdParamsBackendMode)
        draft.put("sdParamsBackendSpec", resolvedPayload.sdParamsBackendSpec)
        draft.put("sdRuntimeBackendMode", resolvedPayload.sdRuntimeBackendMode)
        draft.put("maxVramCpuGiB", resolvedPayload.maxVramCpuGiB)
        draft.put("reuseSdParamsBackendMode", resolvedPayload.sdParamsBackendMode)
        draft.put("reuseSdParamsBackendSpec", resolvedPayload.sdParamsBackendSpec)
        draft.put("reuseSdRuntimeBackendMode", resolvedPayload.sdRuntimeBackendMode)
        draft.put("reuseMaxVramCpuGiB", resolvedPayload.maxVramCpuGiB)
        draft.put("videoAdvancedJson", runtime.toJsonString())
        draft.put("videoReuseSchemaVersion", resolvedPayload.schemaVersion)
        draft.putNullable("videoReuseSourceMetadataPath", resolvedPayload.sourceMetadataPath)
        draft.put("videoReuseWarnings", warningsToJson(warnings))
        // A local run must not inherit a distributed launch from its source.
        draft.put("distributedEnabled", false)
        draft.put("distributedRpcServers", "")
        draft.put("distributedBackendSpec", "")
        draft.put("distributedParamsBackendSpec", "")
        draft.put("distributedMaxVramSpec", "")
        draft.put("distributedCustomFlags", "")
        draft.put("distributedPlacementMode", "")
        draft.put("distributedAutoFit", false)
        draft.put("distributedSplitMode", "")
        return VideoReuseDraftResult(
            json = draft,
            warnings = warnings,
            target = VideoReuseTarget.LOCAL
        )
    }

    fun toLocalDraftJson(
        payload: VideoReusePayload,
        existingDraft: JSONObject? = null,
        verifiedSources: Map<String, String> = emptyMap()
    ): JSONObject = toLocalDraft(payload, existingDraft, verifiedSources).json

    fun toDistributedDraft(
        payload: VideoReusePayload,
        baseSettings: SdDistributedMasterSettingsEntity = SdDistributedMasterSettingsEntity(),
        currentWorkers: Collection<VideoReuseWorker> = emptyList(),
        verifiedSources: Map<String, String> = emptyMap()
    ): VideoReuseDraftResult {
        val resolvedPayload = payload.withResolvedReferences(verifiedSources)
        val compatibility = validateDistributedRuntime(resolvedPayload.distributedRuntime, currentWorkers)
        val warnings = payload.warnings(verifiedSources).toMutableList()
        if (resolvedPayload.diffusionConvDirect || resolvedPayload.vaeConvDirect) {
            warnings += VideoReuseWarning(
                code = VideoReuseWarningCode.DESTINATION_OPTION_UNAVAILABLE,
                field = "directConvolution",
                english = "This result used direct convolution options that the distributed form does not expose. They were not restored; review the distributed backend settings before generating.",
                spanish = "Este resultado usó opciones de convolución directa que el formulario distribuido no ofrece. No se restauraron; revisa los ajustes del backend distribuido antes de generar."
            )
        }
        val draft = JSONObject(settingsToJson(baseSettings))
        val runtime = resolvedPayload.resolvedRuntimeOptions
        val modelPath = resolvedPayload.mainModelPath
        draft.put("videoPrompt", resolvedPayload.prompt)
        draft.put("videoNegativePrompt", resolvedPayload.negativePrompt)
        draft.put("videoWidth", resolvedPayload.width.toString())
        draft.put("videoHeight", resolvedPayload.height.toString())
        draft.put("videoSteps", resolvedPayload.steps.toString())
        draft.put("videoCfg", resolvedPayload.cfgScale.toString())
        draft.put("videoSeed", runtime.seed.takeIf { it != -1L }?.toString() ?: payloadSeed(resolvedPayload))
        draft.put("videoSampler", resolvedPayload.samplingMethod.cliName)
        draft.putNullable("videoScheduler", resolvedPayload.scheduler?.cliName)
        draft.put("videoFlowShift", resolvedPayload.flowShift?.toString().orEmpty())
        draft.put("frames", resolvedPayload.videoFrames.toString())
        draft.put("fps", resolvedPayload.fps.toString())
        draft.put("runtimeThreads", resolvedPayload.threads.toString())
        draft.put("mmap", resolvedPayload.mmap)
        draft.put("diffusionFa", resolvedPayload.diffusionFa)
        draft.put("vaeTiling", resolvedPayload.vaeTiling)
        draft.put("vaeTileSize", resolvedPayload.vaeTileSize)
        draft.put("vaeTileOverlap", runtime.vaeTileOverlap.toString())
        draft.put("cacheMode", resolvedPayload.cacheMode?.cliName.orEmpty())
        draft.put("cacheOption", resolvedPayload.cacheOption)
        draft.put("scmMask", resolvedPayload.scmMask)
        draft.put("scmPolicy", resolvedPayload.scmPolicy?.cliName.orEmpty())
        draft.put("loraStrength", resolvedPayload.loras.firstOrNull()?.strength?.toString() ?: baseSettings.loraStrength)
        draft.put("controlStrength", runtime.controlStrength?.toString() ?: baseSettings.controlStrength)
        draft.put("videoWorkflowMode", if (resolvedPayload.mode == VideoGenerationMode.IMG2VID) "IMG2VID" else "TXT2VID")
        draft.put("videoModelPath", modelPath)
        draft.put("videoInputPath", resolvedPayload.initImagePath ?: runtime.videoInputs.initImagePath.orEmpty())
        draft.put("videoUseVae", resolvedPayload.vaeEnabled && !runtime.useTae)
        draft.put("videoVaePath", resolvedPayload.vaePath.orEmpty())
        draft.put("videoUseT5xxl", resolvedPayload.t5xxlEnabled)
        draft.put("videoT5xxlPath", resolvedPayload.t5xxlPath.orEmpty())
        draft.put("videoLorasJson", resolvedPayload.loras.toJsonArray().toString())
        draft.put("videoHighNoiseLorasJson", resolvedPayload.highNoiseLoras.toJsonArray().toString())
        draft.put("videoLoraApplyMode", resolvedPayload.loraApplyMode.orEmpty())
        draft.put("videoCustomFlags", resolvedPayload.customFlags)
        draft.put("videoAdvancedJson", runtime.toJsonString())
        draft.put("videoReuseSchemaVersion", resolvedPayload.schemaVersion)
        draft.putNullable("videoReuseSourceMetadataPath", resolvedPayload.sourceMetadataPath)

        if (payload.distributedRuntime.enabled && compatibility.compatible) {
            val sourceRuntime = payload.distributedRuntime
            draft.put("enabled", true)
            draft.put("placementMode", sourceRuntime.placementMode.name)
            draft.put("backendSpec", sourceRuntime.backendSpec)
            draft.put("paramsBackendSpec", sourceRuntime.paramsBackendSpec)
            draft.put("autoFit", sourceRuntime.autoFit)
            draft.put("maxVramEnabled", sourceRuntime.maxVramSpec.isNotBlank())
            draft.put("maxVramSpec", sourceRuntime.maxVramSpec)
            draft.put("splitMode", sourceRuntime.splitMode.cliName)
            draft.put("customFlags", sourceRuntime.customFlags)
            draft.put("distributedEnabled", true)
            draft.put("distributedRpcServers", sourceRuntime.rpcServers)
            draft.put("distributedPlacementMode", sourceRuntime.placementMode.name)
            draft.put("distributedBackendSpec", sourceRuntime.backendSpec)
            draft.put("distributedParamsBackendSpec", sourceRuntime.paramsBackendSpec)
            draft.put("distributedAutoFit", sourceRuntime.autoFit)
            draft.put("distributedMaxVramSpec", sourceRuntime.maxVramSpec)
            draft.put("distributedSplitMode", sourceRuntime.splitMode.name)
            draft.put("distributedCustomFlags", sourceRuntime.customFlags)
        } else if (payload.distributedRuntime.enabled && !compatibility.compatible) {
            val detail = (compatibility.missingEndpoints + compatibility.missingRpcNames).joinToString(", ")
            warnings += VideoReuseWarning(
                code = VideoReuseWarningCode.DISTRIBUTED_WORKER_MISMATCH,
                field = "distributedRuntime",
                english = "Saved distributed placement could not be restored because these current workers are unavailable: $detail. Current distributed settings were kept.",
                spanish = "No se pudo restaurar la distribución guardada porque estos trabajadores actuales no están disponibles: $detail. Se mantuvieron los ajustes distribuidos actuales."
            )
        } else {
            // Selecting the distributed destination must leave an enabled,
            // editable distributed draft when the source was generated
            // locally and therefore has no saved distributed runtime.
            draft.put("enabled", true)
        }
        draft.put("videoReuseWarnings", warningsToJson(warnings))
        return VideoReuseDraftResult(
            json = draft,
            warnings = warnings,
            target = VideoReuseTarget.DISTRIBUTED
        )
    }

    fun toDistributedSettingsJson(
        payload: VideoReusePayload,
        baseSettings: SdDistributedMasterSettingsEntity = SdDistributedMasterSettingsEntity(),
        currentWorkers: Collection<VideoReuseWorker> = emptyList(),
        verifiedSources: Map<String, String> = emptyMap()
    ): JSONObject = toDistributedDraft(payload, baseSettings, currentWorkers, verifiedSources).json

    fun toDistributedSettings(
        payload: VideoReusePayload,
        baseSettings: SdDistributedMasterSettingsEntity = SdDistributedMasterSettingsEntity(),
        currentWorkers: Collection<VideoReuseWorker> = emptyList(),
        verifiedSources: Map<String, String> = emptyMap()
    ): VideoReuseDistributedDraft {
        val result = toDistributedDraft(payload, baseSettings, currentWorkers, verifiedSources)
        val parsed = settingsFromJson(result.json.toString(), baseSettings)
        return VideoReuseDistributedDraft(
            // settingsFromJson retains the historical LAYER default for
            // compatibility. Reuse must keep an explicitly saved ROW mode.
            settings = parsed.copy(
                splitMode = result.json.optString("splitMode", parsed.splitMode),
                // Make the restored video controls visible on the first frame;
                // these values are persisted before the handoff is completed so
                // the settings observer cannot collapse the restored section.
                generationExpanded = true,
                videoExpanded = true,
                runtimeExpanded = true,
                adaptersExpanded = true
            ),
            json = result.json,
            warnings = result.warnings
        )
    }

    fun toDistributedDraftFromDatabaseWorkers(
        payload: VideoReusePayload,
        baseSettings: SdDistributedMasterSettingsEntity = SdDistributedMasterSettingsEntity(),
        currentWorkers: Iterable<SdDistributedWorkerEntity>,
        verifiedSources: Map<String, String> = emptyMap()
    ): VideoReuseDraftResult = toDistributedDraft(
        payload,
        baseSettings,
        currentWorkers.toVideoReuseWorkers(),
        verifiedSources
    )

    fun hasMeaningfulLocalDraft(draft: JSONObject?): Boolean {
        if (draft == null) return false
        fun textIsSet(key: String): Boolean = draft.optString(key).trim().let {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
        fun differs(key: String, default: String): Boolean =
            textIsSet(key) && draft.optString(key).trim() != default
        fun booleanIsSet(key: String, default: Boolean): Boolean =
            draft.has(key) && draft.optBoolean(key, default) != default
        fun placementIsSet(key: String): Boolean = draft.optString(key)
            .trim()
            .lowercase(Locale.US)
            .let { it.isNotBlank() && it !in DEFAULT_LOCAL_PLACEMENTS }

        if (draft.optInt("mode", 0) != 0 ||
            textIsSet("prompt") ||
            textIsSet("negativePrompt") ||
            textIsSet("model") ||
            textIsSet("input") ||
            textIsSet("vae") ||
            textIsSet("t5") ||
            textIsSet("flags") ||
            textIsSet("customFlags") ||
            textIsSet("cacheOption") ||
            textIsSet("scmMask") ||
            textIsSet("loraApplyMode") ||
            differs("frames", "8") ||
            differs("fps", "5") ||
            differs("width", "480") ||
            differs("height", "832") ||
            differs("steps", "18") ||
            differs("cfg", "6.0") ||
            differs("threads", "-1") ||
            differs("vaeTileSize", "24x24") ||
            differs("sampler", SamplingMethod.EULER.name) ||
            textIsSet("scheduler") ||
            textIsSet("flowShift") ||
            textIsSet("cacheMode") ||
            textIsSet("scmPolicy") ||
            booleanIsSet("useVae", false) ||
            booleanIsSet("useT5", false) ||
            booleanIsSet("flowShiftEnabled", false) ||
            booleanIsSet("vaeTiling", true) ||
            booleanIsSet("diffusionFa", true) ||
            booleanIsSet("diffConv", false) ||
            booleanIsSet("vaeConv", false) ||
            booleanIsSet("mmap", true) ||
            placementIsSet("tePlacement") ||
            placementIsSet("diffusionPlacement") ||
            placementIsSet("vaePlacement") ||
            draft.optJSONArray("loras")?.length()?.let { it > 0 } == true ||
            draft.optJSONArray("highNoiseLoras")?.length()?.let { it > 0 } == true
        ) {
            return true
        }

        val rawAdvanced = draft.optString("videoAdvancedJson").trim()
        if (rawAdvanced.isBlank() || rawAdvanced == "{}" || rawAdvanced.equals("null", ignoreCase = true)) return false
        val runtime = parseVideoRuntimeOptions(rawAdvanced) ?: return true
        val expectedWorkflow = if (draft.optInt("mode", 0) == 1) {
            SdVideoWorkflow.IMAGE_TO_VIDEO
        } else {
            SdVideoWorkflow.TEXT_TO_VIDEO
        }
        // The screen writes the default workflow into every capture. Treat a
        // legacy omitted workflow as equivalent so it does not trigger a false
        // replacement prompt after an upgrade.
        return runtime.copy(workflow = runtime.workflow ?: expectedWorkflow) !=
            VideoRuntimeOptions(workflow = expectedWorkflow)
    }

    fun hasMeaningfulDistributedDraft(settings: SdDistributedMasterSettingsEntity): Boolean {
        val defaults = SdDistributedMasterSettingsEntity()
        // Runtime controls are shared with image drafts; protect those edits too.
        return settings.copy(id = defaults.id, updatedAt = defaults.updatedAt) != defaults
    }

    fun requiresReplacement(
        target: VideoReuseTarget,
        localDraft: JSONObject? = null,
        distributedSettings: SdDistributedMasterSettingsEntity? = null
    ): Boolean = when (target) {
        VideoReuseTarget.LOCAL -> hasMeaningfulLocalDraft(localDraft)
        VideoReuseTarget.DISTRIBUTED -> distributedSettings?.let(::hasMeaningfulDistributedDraft) == true
    }

    private fun parsePlacement(value: String): Map<String, String> =
        value.split(',')
            .mapNotNull { part ->
                val pieces = part.trim().split('=', limit = 2)
                if (pieces.size == 2 && pieces[0].isNotBlank() && pieces[1].isNotBlank()) {
                    pieces[0].trim().lowercase(Locale.US) to pieces[1].trim()
                } else {
                    null
                }
            }
            .toMap()

    private fun payloadSeed(payload: VideoReusePayload): String =
        payload.runtimeOptions.seed.takeIf { it != -1L }?.toString() ?: "-1"

    private val DEFAULT_LOCAL_PLACEMENTS = setOf("cpu", "auto", "vulkan0", "opencl0")
}

fun validateDistributedRuntime(
    runtime: SdDistributedRuntimeConfig,
    currentWorkers: Collection<VideoReuseWorker>
): VideoReuseDistributedValidation {
    if (!runtime.enabled) {
        return VideoReuseDistributedValidation(compatible = true)
    }
    val enabledWorkers = currentWorkers.filter { it.enabled && it.host.isNotBlank() && it.port > 0 }
    val availableEndpoints = enabledWorkers.map { it.endpoint }.toSet()
    val recordedEndpoints = runtime.rpcServers
        .split(',')
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() }
    val missingEndpoints = recordedEndpoints.distinct().filterNot { it in availableEndpoints }
    val rpcRefs = Regex("\\bRPC(\\d+)\\b", RegexOption.IGNORE_CASE)
        .findAll(
            listOf(
                runtime.backendSpec,
                runtime.paramsBackendSpec,
                runtime.maxVramSpec,
                runtime.customFlags
            ).joinToString(",")
        )
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toSet()
    val missingRpcNames = rpcRefs
        .filter { it !in recordedEndpoints.indices }
        .sorted()
        .map { "RPC$it" }
    return VideoReuseDistributedValidation(
        compatible = missingEndpoints.isEmpty() && missingRpcNames.isEmpty(),
        missingEndpoints = missingEndpoints,
        missingRpcNames = missingRpcNames
    )
}

data class VideoReuseHandoff(
    val metadataPath: String,
    val target: VideoReuseTarget,
    val createdAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = VideoReusePayload.CURRENT_SCHEMA_VERSION
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("metadataPath", metadataPath)
        .put("target", target.name)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): VideoReuseHandoff? {
            val path = json.optString("metadataPath").trim()
            if (path.isBlank() || path.length > VideoReuseHandoffStore.MAX_METADATA_PATH_LENGTH) return null
            val target = VideoReuseTarget.entries.firstOrNull {
                it.name.equals(json.optString("target"), ignoreCase = true)
            } ?: return null
            return VideoReuseHandoff(
                metadataPath = path,
                target = target,
                createdAt = json.optLong("createdAt", 0L),
                schemaVersion = json.optInt("schemaVersion", VideoReusePayload.CURRENT_SCHEMA_VERSION)
            )
        }
    }
}

/**
 * Durable handoff for gallery -> form navigation. Only a bounded metadata path
 * is persisted, so process death cannot lose the selection or overflow an Intent.
 */
object VideoReuseHandoffStore {
    const val FILE_NAME = "video_reuse_handoff.json"
    const val MAX_METADATA_PATH_LENGTH = 4096
    const val MAX_HANDOFF_BYTES = 16 * 1024L

    fun handoffFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun publish(
        context: Context,
        metadataPath: String,
        target: VideoReuseTarget
    ): VideoReuseHandoff = publish(handoffFile(context), metadataPath, target)

    fun publish(
        handoffFile: File,
        metadataPath: String,
        target: VideoReuseTarget
    ): VideoReuseHandoff {
        val path = metadataPath.trim()
        require(path.isNotBlank()) { "metadataPath must not be blank" }
        require(path.length <= MAX_METADATA_PATH_LENGTH) { "metadataPath is too long" }
        val handoff = VideoReuseHandoff(path, target)
        val json = handoff.toJson().toString()
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_HANDOFF_BYTES) {
            "video reuse handoff is too large"
        }
        handoffFile.parentFile?.mkdirs()
        val temp = File(handoffFile.parentFile ?: File("."), "${handoffFile.name}.tmp")
        temp.writeText(json, StandardCharsets.UTF_8)
        check(temp.renameTo(handoffFile)) { "unable to persist video reuse handoff" }
        return handoff
    }

    fun peek(context: Context): VideoReuseHandoff? = peek(handoffFile(context))

    fun peek(handoffFile: File): VideoReuseHandoff? {
        if (!handoffFile.isFile || handoffFile.length() > MAX_HANDOFF_BYTES) return null
        return runCatching { VideoReuseHandoff.fromJson(JSONObject(handoffFile.readText(StandardCharsets.UTF_8))) }
            .getOrNull()
    }

    fun readPayload(
        context: Context,
        handoff: VideoReuseHandoff? = peek(context)
    ): VideoReusePayloadReadResult? = handoff?.let { VideoReusePayload.fromMetadataFile(File(it.metadataPath)) }

    fun clear(context: Context): Boolean = clear(handoffFile(context))

    fun clear(handoffFile: File): Boolean = !handoffFile.exists() || handoffFile.delete()

    /** Clear only the handoff that the form actually applied. */
    fun complete(context: Context, handoff: VideoReuseHandoff): Boolean {
        return complete(handoffFile(context), handoff)
    }

    fun complete(handoffFile: File, handoff: VideoReuseHandoff): Boolean {
        val current = peek(handoffFile) ?: return false
        if (
            current.metadataPath != handoff.metadataPath ||
            current.target != handoff.target ||
            current.createdAt != handoff.createdAt
        ) return false
        return clear(handoffFile)
    }
}
