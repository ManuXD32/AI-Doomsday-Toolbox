package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.parseSdCapabilities
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class NativeChatUnifiedImageGenerator(
    private val context: Context,
    private val database: AppDatabase
) : NativeChatImageGenerator {
    private val onnxGenerator by lazy { NativeChatOnnxImageGenerator(context, database) }
    private val sdGenerator by lazy { NativeChatSdImageGenerator(context, database) }

    override suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        config: NativeChatToolConfig
    ): Result<NativeChatGeneratedImage> {
        return when (config.imageParams.engine) {
            NativeChatImageGenerationEngine.ONNX -> onnxGenerator.generateImage(prompt, negativePrompt, config)
            NativeChatImageGenerationEngine.SD -> sdGenerator.generateImage(prompt, negativePrompt, config)
        }
    }
}

class NativeChatSdImageGenerator(
    private val context: Context,
    private val database: AppDatabase
) : NativeChatImageGenerator {

    override suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        config: NativeChatToolConfig
    ): Result<NativeChatGeneratedImage> = withContext(Dispatchers.IO) {
        try {
            val imageParams = config.imageParams.sdParams
            val modelDao = database.modelDao()
            val mainModels = modelDao
                .getModelsByTypesSync(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
                .filter { it.isSdImageMainModel() && it.supportsSdTxt2Img() }
            val selectedModelId = imageParams.model?.trim().orEmpty()
            val model = mainModels.firstOrNull { it.matchesToolModelId(selectedModelId) }
                ?: mainModels.firstOrNull()
                ?: return@withContext Result.failure(Exception(context.getString(R.string.native_chat_generate_image_sd_model_missing)))

            val (family, variant) = model.resolvedSdFamily()
            val spec = family?.let { resolveSdFamilySpec(it, variant) }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.native_chat_generate_image_sd_model_missing)))
            val supportModels = modelDao.getModelsByTypesSync(
                listOf(
                    ModelType.SD_VAE,
                    ModelType.SD_TAE,
                    ModelType.SD_CLIP_L,
                    ModelType.SD_CLIP_G,
                    ModelType.SD_T5XXL,
                    ModelType.LLM,
                    ModelType.VISION_PROJECTOR,
                    ModelType.SD_PHOTOMAKER
                )
            )
            val components = resolveSdToolComponents(
                supportModels = supportModels,
                sdParams = imageParams,
                model = model
            )
            val missingRequired = spec.requiredRoles.filter { components.pathForRole(it).isNullOrBlank() }
            if (missingRequired.isNotEmpty()) {
                return@withContext Result.failure(
                    Exception(
                        context.getString(
                            R.string.native_chat_generate_image_sd_components_missing,
                            missingRequired.joinToString(", ") { it.name }
                        )
                    )
                )
            }

            val outputFile = buildNativeChatSdOutputFile(context)
            val resolvedNegativePrompt = negativePrompt.takeIf { it.isNotBlank() }
                ?: imageParams.negativePrompt
            val seed = imageParams.seed.trim().toLongOrNull() ?: -1L
            val settingsRepo = SettingsRepository(context)
            val maxVramCpuGiB = if (settingsRepo.sdMaxCpuRamEnabled.value) {
                settingsRepo.sdMaxCpuRamGiB.value
            } else {
                ""
            }
            val startedAt = System.currentTimeMillis()
            val resultFile = SdToolGenerationRunner(context).generateTxt2Img(
                config = SDConfig(
                    modelPath = model.path,
                    prompt = prompt,
                    negativePrompt = resolvedNegativePrompt,
                    width = imageParams.width,
                    height = imageParams.height,
                    steps = imageParams.steps,
                    cfgScale = imageParams.cfgScale,
                    seed = seed,
                    samplingMethod = imageParams.sampler,
                    outputPath = outputFile.absolutePath,
                    mode = SDMode.TXT2IMG,
                    threads = imageParams.threads,
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
                    loras = imageParams.loras,
                    loraApplyMode = imageParams.loraApplyMode,
                    flowShift = imageParams.flowShift.toFloatOrNull(),
                    diffusionFa = imageParams.diffusionFa && spec.supportsDiffusionFa,
                    mmap = imageParams.mmap && spec.supportsMmap,
                    vaeConvDirect = imageParams.vaeConvDirect && spec.supportsVaeConvDirect,
                    qwenImageZeroCondT = imageParams.qwenImageZeroCondT && spec.supportsQwenImageZeroCondT,
                    chromaDisableDitMask = imageParams.chromaDisableDitMask && spec.supportsChromaDisableDitMask,
                    sdParamsBackendSpec = model.sdParamsBackendSpec,
                    sdParamsBackendMode = model.sdParamsBackendMode,
                    sdRuntimeBackendMode = model.sdRuntimeBackendMode,
                    maxVramCpuGiB = maxVramCpuGiB
                ),
                onProgress = { snapshot ->
                    DebugLog.log("[NativeChatImage][SD] step ${snapshot.currentStep}/${snapshot.totalSteps}")
                },
                onStatus = { status ->
                    status.takeIf { it.isNotBlank() }?.let {
                        DebugLog.log("[NativeChatImage][SD] $it")
                    }
                }
            )
            val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val content = buildString {
                appendLine("tool: generate_image")
                appendLine("engine: SD")
                appendLine("status: created")
                appendLine("image_path: ${resultFile.absolutePath}")
                appendLine("note_markdown: ![${prompt.toMarkdownAltText()}](${resultFile.absolutePath})")
                appendLine("model: ${model.filename}")
                appendLine("family: ${family.storedValue}")
                appendLine("resolution: ${imageParams.width}x${imageParams.height}")
                appendLine("steps: ${imageParams.steps}")
                appendLine("cfg: ${String.format(Locale.US, "%.1f", imageParams.cfgScale)}")
                appendLine("sampler: ${imageParams.sampler.cliName}")
                appendLine("seed: ${if (seed >= 0L) seed else "random"}")
                append("total_time_ms: $durationMs")
            }
            Result.success(
                NativeChatGeneratedImage(
                    content = content,
                    imagePath = resultFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun String.toMarkdownAltText(): String {
        return trim()
            .replace(Regex("""[\[\]\n\r]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .take(80)
            .ifBlank { "Generated image" }
    }
}

internal data class NativeChatSdResolvedComponents(
    val vaePath: String? = null,
    val taePath: String? = null,
    val clipLPath: String? = null,
    val clipGPath: String? = null,
    val t5xxlPath: String? = null,
    val llmPath: String? = null,
    val llmVisionPath: String? = null,
    val photoMakerPath: String? = null
) {
    fun pathForRole(role: SdComponentRole): String? = when (role) {
        SdComponentRole.VAE -> vaePath
        SdComponentRole.TAE -> taePath
        SdComponentRole.CLIP_L -> clipLPath
        SdComponentRole.CLIP_G -> clipGPath
        SdComponentRole.T5XXL -> t5xxlPath
        SdComponentRole.LLM -> llmPath
        SdComponentRole.LLM_VISION -> llmVisionPath
        SdComponentRole.PHOTOMAKER -> photoMakerPath
        else -> null
    }
}

internal fun resolveSdToolComponents(
    supportModels: List<ModelEntity>,
    sdParams: NativeChatSdImageToolParams,
    model: ModelEntity
): NativeChatSdResolvedComponents {
    val (family, variant) = model.resolvedSdFamily()
    fun resolve(type: ModelType, id: String?): String? {
        if (family == null) return null
        val cleanId = id?.trim().orEmpty()
        if (cleanId.isBlank()) return null
        return supportModels
            .filter { it.type == type && it.matchesSdFamily(family, variant) }
            .firstOrNull { it.matchesToolModelId(cleanId) }
            ?.path
    }
    return NativeChatSdResolvedComponents(
        vaePath = resolve(ModelType.SD_VAE, sdParams.vaePath),
        taePath = resolve(ModelType.SD_TAE, sdParams.taePath),
        clipLPath = resolve(ModelType.SD_CLIP_L, sdParams.clipLPath),
        clipGPath = resolve(ModelType.SD_CLIP_G, sdParams.clipGPath),
        t5xxlPath = resolve(ModelType.SD_T5XXL, sdParams.t5xxlPath),
        llmPath = resolve(ModelType.LLM, sdParams.llmPath),
        llmVisionPath = resolve(ModelType.VISION_PROJECTOR, sdParams.llmVisionPath),
        photoMakerPath = resolve(ModelType.SD_PHOTOMAKER, sdParams.photoMakerPath)
    )
}

internal fun ModelEntity.supportsSdTxt2Img(): Boolean {
    val explicit = sdCapabilities.parseSdCapabilities()
    if (explicit.isNotEmpty()) return SD_CAPABILITY_TXT2IMG in explicit
    val (family, variant) = resolvedSdFamily()
    return family?.let { SD_CAPABILITY_TXT2IMG in resolveSdFamilySpec(it, variant).defaultCapabilities.parseSdCapabilities() } == true
}

internal fun ModelEntity.matchesToolModelId(id: String): Boolean =
    id.isBlank() || filename == id || path == id

private fun buildNativeChatSdOutputFile(context: Context): File {
    val dir = File(context.filesDir, "sd_output/txt2img").apply { mkdirs() }
    return File(dir, "native_chat_${System.currentTimeMillis()}.png")
}
