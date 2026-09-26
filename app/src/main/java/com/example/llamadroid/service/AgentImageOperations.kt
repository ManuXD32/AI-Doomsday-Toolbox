package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.*
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.resolvedSdFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shared image tools with explicit file access; neither implementation invokes an agent loop. */
class AgentImageOperations(
    private val context: Context,
    private val inputFileForPath: suspend (String) -> File,
    private val sanitizePath: (String) -> String,
    private val persistBytes: suspend (String, ByteArray) -> Unit,
    private val toProjectRelativePath: (String) -> String,
    private val onStatus: (String) -> Unit = {}
) {
    private suspend fun writeFileBytes(path: String, bytes: ByteArray): Result<Unit> = runCatching {
        persistBytes(path, bytes)
    }.onFailure { if (it is CancellationException) throw it }

    private fun isSupportedImagePath(path: String): Boolean =
        File(path).extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    private fun parseAgentImageGenerationResolution(value: String): Pair<Int, Int>? {
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return (width to height).takeIf { width in 64..2048 && height in 64..2048 }
    }

    suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        settingsRepo: com.example.llamadroid.data.SettingsRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!settingsRepo.agentImageGenerationToolEnabled.value) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_generate_image_tool_disabled)))
            }
            if (settingsRepo.agentImageGenerationEngine.value.equals("SD", ignoreCase = true)) {
                return@withContext generateSdAgentImage(prompt, negativePrompt, outputPath, settingsRepo)
            }
            val db = AppDatabase.getDatabase(context.applicationContext)
            val selectedModelId = settingsRepo.agentImageGenerationModel.value?.trim().orEmpty()
            if (selectedModelId.isBlank()) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_generate_image_model_missing)))
            }
            val model = db.modelDao()
                .getModelsByTypesSync(listOf(ModelType.ONNX_IMAGE_GEN))
                .filter { it.isOnnxTxt2ImgBundle() }
                .find { it.filename == selectedModelId || it.path == selectedModelId }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.agent_generate_image_model_missing)))
            val (width, height) = parseAgentImageGenerationResolution(settingsRepo.agentImageGenerationResolution.value)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.agent_generate_image_resolution_invalid)))
            val normalizedOutputPath = if (File(outputPath).extension.isBlank()) "$outputPath.png" else outputPath
            val safeOutputPath = sanitizePath(normalizedOutputPath)
            val localTempDir = File(context.cacheDir, "agent_image_generation").apply { mkdirs() }
            val localTempFile = File.createTempFile("generated_", ".png", localTempDir)

            val result = OnnxTxt2ImgPipeline().generate(
                config = OnnxImageGenConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    mode = OnnxImageGenMode.TXT2IMG,
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    width = width,
                    height = height,
                    steps = settingsRepo.agentImageGenerationSteps.value.coerceAtLeast(1),
                    cfgScale = settingsRepo.agentImageGenerationCfg.value,
                    seed = -1L,
                    requestedWidth = width,
                    requestedHeight = height,
                    backend = OnnxRuntimeBackend.CPU,
                    runtimeOptions = OnnxRuntimeOptions(),
                    outputPath = localTempFile.absolutePath
                ),
                onProgress = { _, status ->
                    onStatus(context.getString(R.string.agent_generating_image_status, status))
                }
            )

            writeFileBytes(safeOutputPath, result.outputFile.readBytes()).getOrThrow()
            runCatching { result.outputFile.delete() }

            Result.success(
                buildString {
                    appendLine(context.getString(R.string.agent_generate_image_result_saved, toProjectRelativePath(safeOutputPath)))
                    appendLine(context.getString(R.string.model_filename_label, model.filename))
                    appendLine(context.getString(R.string.agent_generate_image_result_resolution, "${width}x${height}"))
                    appendLine(context.getString(R.string.agent_generate_image_result_steps, settingsRepo.agentImageGenerationSteps.value))
                    append(context.getString(R.string.agent_generate_image_result_cfg, String.format(java.util.Locale.US, "%.1f", settingsRepo.agentImageGenerationCfg.value)))
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun generateSdAgentImage(
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        settingsRepo: SettingsRepository
    ): Result<String> {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val selectedModelId = settingsRepo.agentSdImageGenerationModel.value?.trim().orEmpty()
        if (selectedModelId.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.agent_generate_image_sd_model_missing)))
        }
        val mainModels = db.modelDao()
            .getModelsByTypesSync(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
            .filter { it.isSdImageMainModel() && it.supportsSdTxt2Img() && File(it.path).isFile }
        val model = mainModels.find { it.filename == selectedModelId || it.path == selectedModelId }
            ?: return Result.failure(Exception(context.getString(R.string.agent_generate_image_sd_model_missing)))
        val (family, variant) = model.resolvedSdFamily()
        val spec = family?.let { com.example.llamadroid.sd.resolveSdFamilySpec(it, variant) }
            ?: return Result.failure(Exception(context.getString(R.string.agent_generate_image_sd_model_missing)))
        val supportModels = db.modelDao().getModelsByTypesSync(AGENT_SD_IMAGE_SUPPORT_TYPES)
            .filter { File(it.path).isFile }
        val sdParams = settingsRepo.agentSdImageToolParams(model.filename)
        val components = resolveSdToolComponents(supportModels, sdParams, model)
        val missingRequired = missingRequiredSdToolComponents(model, supportModels, sdParams)
            ?: return Result.failure(Exception(context.getString(R.string.agent_generate_image_sd_model_missing)))
        if (missingRequired.isNotEmpty()) {
            return Result.failure(
                Exception(
                    context.getString(
                        R.string.agent_generate_image_sd_components_missing,
                        missingRequired.joinToString(", ") { it.name }
                    )
                )
            )
        }
        val normalizedOutputPath = if (File(outputPath).extension.isBlank()) "$outputPath.png" else outputPath
        val safeOutputPath = sanitizePath(normalizedOutputPath)
        val localTempDir = File(context.cacheDir, "agent_image_generation").apply { mkdirs() }
        val localTempFile = File.createTempFile("generated_sd_", ".png", localTempDir)
        val resolvedNegativePrompt = negativePrompt.takeIf { it.isNotBlank() } ?: sdParams.negativePrompt
        val seed = sdParams.seed.trim().toLongOrNull() ?: -1L

        val resultFile = SdToolGenerationRunner(context).generateTxt2Img(
            config = SDConfig(
                modelPath = model.path,
                prompt = prompt,
                negativePrompt = resolvedNegativePrompt,
                width = sdParams.width,
                height = sdParams.height,
                steps = sdParams.steps,
                cfgScale = sdParams.cfgScale,
                seed = seed,
                samplingMethod = sdParams.sampler,
                outputPath = localTempFile.absolutePath,
                mode = SDMode.TXT2IMG,
                threads = sdParams.threads,
                modelLayout = model.sdArtifactLayout
                    ?.let(SdMainLayout::fromStoredValue)
                    ?.takeUnless { it == SdMainLayout.UNKNOWN }
                    ?: if (model.type == ModelType.SD_CHECKPOINT) {
                        SdMainLayout.FULL_MODEL
                    } else {
                        SdMainLayout.STANDALONE_DIFFUSION
                    },
                modelFamily = family.storedValue,
                modelVariant = variant,
                vaePath = components.vaePath,
                taePath = components.taePath,
                clipLPath = components.clipLPath,
                clipGPath = components.clipGPath,
                t5xxlPath = components.t5xxlPath,
                llmPath = components.llmPath,
                llmVisionPath = components.llmVisionPath,
                photoMakerPath = components.photoMakerPath,
                loras = sdParams.loras,
                loraApplyMode = sdParams.loraApplyMode,
                flowShift = sdParams.flowShift.toFloatOrNull(),
                diffusionFa = sdParams.diffusionFa && spec.supportsDiffusionFa,
                mmap = sdParams.mmap && spec.supportsMmap,
                vaeConvDirect = sdParams.vaeConvDirect && spec.supportsVaeConvDirect,
                qwenImageZeroCondT = sdParams.qwenImageZeroCondT && spec.supportsQwenImageZeroCondT,
                chromaDisableDitMask = sdParams.chromaDisableDitMask && spec.supportsChromaDisableDitMask,
                sdParamsBackendSpec = model.sdParamsBackendSpec,
                sdParamsBackendMode = model.sdParamsBackendMode,
                sdRuntimeBackendMode = model.sdRuntimeBackendMode,
                maxVramCpuGiB = if (settingsRepo.sdMaxCpuRamEnabled.value) settingsRepo.sdMaxCpuRamGiB.value else ""
            ),
            onProgress = { snapshot ->
                onStatus(context.getString(R.string.agent_generating_image_status, "${snapshot.currentStep}/${snapshot.totalSteps}"))
            },
            onStatus = { status ->
                if (status.isNotBlank()) {
                    onStatus(context.getString(R.string.agent_generating_image_status, status.take(80)))
                }
            }
        )

        writeFileBytes(safeOutputPath, resultFile.readBytes()).getOrThrow()
        runCatching { resultFile.delete() }

        return Result.success(
            buildString {
                appendLine(context.getString(R.string.agent_generate_image_result_saved, toProjectRelativePath(safeOutputPath)))
                appendLine(context.getString(R.string.agent_generate_image_result_engine, "SD"))
                appendLine(context.getString(R.string.model_filename_label, model.filename))
                appendLine(context.getString(R.string.agent_generate_image_result_family, family.storedValue))
                appendLine(context.getString(R.string.agent_generate_image_result_resolution, "${sdParams.width}x${sdParams.height}"))
                appendLine(context.getString(R.string.agent_generate_image_result_steps, sdParams.steps))
                appendLine(context.getString(R.string.agent_generate_image_result_sampler, sdParams.sampler.cliName))
                append(context.getString(R.string.agent_generate_image_result_cfg, String.format(java.util.Locale.US, "%.1f", sdParams.cfgScale)))
            }
        )
    }

    suspend fun removeImageBackground(
        imagePath: String,
        outputPath: String?,
        settingsRepo: com.example.llamadroid.data.SettingsRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!settingsRepo.agentBackgroundRemovalToolEnabled.value) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_bgr_tool_disabled)))
            }
            val safeInputPath = sanitizePath(imagePath)
            val inputFile = inputFileForPath(imagePath)
            if (!inputFile.isFile) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_bgr_input_missing)))
            }
            if (!isSupportedImagePath(safeInputPath)) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_bgr_input_unsupported)))
            }
            val db = AppDatabase.getDatabase(context.applicationContext)
            val selectedModelId = settingsRepo.agentBackgroundRemovalModel.value?.trim().orEmpty()
            if (selectedModelId.isBlank()) {
                return@withContext Result.failure(Exception(context.getString(R.string.agent_bgr_model_missing)))
            }
            val model = db.modelDao()
                .getModelsByTypesSync(listOf(ModelType.ONNX_BACKGROUND_REMOVAL))
                .filter { it.isOnnxBackgroundRemovalModel() }
                .find { it.filename == selectedModelId || it.path == selectedModelId }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.agent_bgr_model_missing)))
            val backend = runCatching {
                OnnxRuntimeBackend.valueOf(settingsRepo.agentBackgroundRemovalBackend.value)
            }.getOrDefault(OnnxRuntimeBackend.CPU)
            val graphOptimization = runCatching {
                OnnxGraphOptimizationLevel.valueOf(settingsRepo.agentBackgroundRemovalGraphOptimization.value)
            }.getOrDefault(OnnxGraphOptimizationLevel.ALL)
            val resolvedOutputPath = outputPath?.takeIf { it.isNotBlank() }
                ?: defaultBackgroundRemovalOutputPath(inputFile)
            val normalizedOutputPath = if (File(resolvedOutputPath).extension.isBlank()) {
                "$resolvedOutputPath.png"
            } else {
                resolvedOutputPath
            }
            val safeOutputPath = sanitizePath(normalizedOutputPath)
            onStatus(context.getString(R.string.agent_bgr_status_starting))
            val result = OnnxBackgroundRemovalPipeline().removeBackground(
                context = context,
                config = OnnxBackgroundRemovalConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    inputPaths = listOf(inputFile.absolutePath),
                    inputNames = listOf(inputFile.name),
                    backend = backend,
                    runtimeOptions = OnnxRuntimeOptions(
                        runtimeThreadCount = settingsRepo.agentBackgroundRemovalRuntimeThreads.value.takeIf { it > 0 },
                        graphOptimizationLevel = graphOptimization
                    ),
                    alphaThreshold = settingsRepo.agentBackgroundRemovalAlphaThreshold.value,
                    featherRadius = settingsRepo.agentBackgroundRemovalFeatherRadius.value,
                    maskSoftness = settingsRepo.agentBackgroundRemovalMaskSoftness.value,
                    maskContrast = settingsRepo.agentBackgroundRemovalMaskContrast.value,
                    exportMask = settingsRepo.agentBackgroundRemovalExportMask.value,
                    resizeBeforeProcessing = settingsRepo.agentBackgroundRemovalResizeBeforeProcessing.value,
                    resizeMaxEdge = settingsRepo.agentBackgroundRemovalResizeMaxEdge.value,
                    preserveSourceNames = true
                ),
                inputFile = inputFile,
                sourceName = inputFile.name,
                onDiagnostic = {},
                onProgress = { stage, _ ->
                    onStatus(context.getString(R.string.agent_bgr_status_phase, stage.name.lowercase()))
                }
            )

            writeFileBytes(safeOutputPath, result.outputFile.readBytes()).getOrThrow()
            val maskWorkspacePath = if (settingsRepo.agentBackgroundRemovalExportMask.value) {
                result.maskFile?.let { maskFile ->
                    val maskPath = safeOutputPath.substringBeforeLast(".") + "_mask.png"
                    writeFileBytes(maskPath, maskFile.readBytes()).getOrThrow()
                    toProjectRelativePath(maskPath)
                }
            } else {
                null
            }
            runCatching { result.outputFile.delete() }
            runCatching { result.maskFile?.delete() }

            Result.success(
                buildString {
                    appendLine(context.getString(R.string.agent_bgr_result_removed, toProjectRelativePath(safeOutputPath)))
                    appendLine(context.getString(R.string.agent_bgr_result_source, toProjectRelativePath(safeInputPath)))
                    appendLine(context.getString(R.string.model_filename_label, model.filename))
                    appendLine(context.getString(R.string.agent_bgr_result_backend, backend.name))
                    appendLine(
                        context.getString(
                            R.string.agent_bgr_result_resize_before,
                            settingsRepo.agentBackgroundRemovalResizeBeforeProcessing.value.toString()
                        )
                    )
                    appendLine(context.getString(R.string.agent_bgr_result_resize_max_edge, settingsRepo.agentBackgroundRemovalResizeMaxEdge.value))
                    maskWorkspacePath?.let { appendLine(context.getString(R.string.agent_bgr_result_mask, it)) }
                }.trimEnd()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun defaultBackgroundRemovalOutputPath(inputFile: File): String {
        val baseName = inputFile.nameWithoutExtension
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .ifBlank { "image" }
        return "generated/background-removal/${baseName}_bgr.png"
    }

}
