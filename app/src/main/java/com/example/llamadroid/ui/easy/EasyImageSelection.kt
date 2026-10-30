package com.example.llamadroid.ui.easy

import android.content.Context
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.easy.EasyImageEngine
import com.example.llamadroid.data.easy.EasyImageKind
import com.example.llamadroid.data.easy.EasyImagePreset
import com.example.llamadroid.data.easy.EasyImagePresets
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.EasyModelEntry
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.data.easy.EasySdSlot
import com.example.llamadroid.data.easy.easyLocalImageModelId
import com.example.llamadroid.data.easy.easySdSlotFor
import com.example.llamadroid.data.easy.inferEasyImageKind
import com.example.llamadroid.data.easy.isEasySdMainType
import com.example.llamadroid.data.easy.parseEasyLocalImageModelId
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolveSdPipeline
import com.example.llamadroid.sd.sdArtifactInspection
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Easy mode needs to launch one image generation with fixed settings. */
data class EasyImageSelection(
    val id: String,
    /** Curated entry, or null for an imported ("On this phone") model. */
    val entry: EasyModelEntry?,
    val main: ModelEntity,
    val kind: EasyImageKind,
    val engine: EasyImageEngine,
    /** Installed companion files (Flux clip-l / t5 / vae ...) keyed by launch slot. */
    val components: Map<EasySdSlot, ModelEntity>
) {
    val preset: EasyImagePreset get() = EasyImagePresets.forKind(kind)
}

private fun slotOfRole(role: SdComponentRole): EasySdSlot? = when (role) {
    SdComponentRole.VAE -> EasySdSlot.VAE
    SdComponentRole.TAE -> EasySdSlot.TAE
    SdComponentRole.CLIP_L -> EasySdSlot.CLIP_L
    SdComponentRole.CLIP_G -> EasySdSlot.CLIP_G
    SdComponentRole.T5XXL -> EasySdSlot.T5XXL
    SdComponentRole.LLM -> EasySdSlot.LLM
    SdComponentRole.LLM_VISION -> EasySdSlot.LLM_VISION
    else -> null
}

/** Imported checkpoints / diffusion models and ONNX bundles that are not part of the curated list. */
fun easyLocalImageModels(
    installed: List<ModelEntity>,
    curatedMainPaths: Set<String>,
    pathExists: (String) -> Boolean = { File(it).exists() }
): List<ModelEntity> =
    installed.filter { model ->
        model.isEasyUsable() && model.path !in curatedMainPaths &&
            (isEasySdMainType(model.type) || model.type == ModelType.ONNX_IMAGE_GEN) &&
            pathExists(model.path)
    }

/** Main-model paths of installed curated image entries, so "On this phone" can exclude them. */
fun easyCuratedImageMainPaths(installed: List<ModelEntity>): Set<String> =
    EasyModelCatalog.entriesFor(EasyModelKind.IMAGE)
        .mapNotNull { EasyModelCatalog.installedModel(it, installed)?.path }
        .toSet()

/** Resolves a stored [id] (curated entry id or "local:<filename>") against the installed models. */
fun resolveEasyImageSelection(id: String, installed: List<ModelEntity>): EasyImageSelection? {
    val entry = EasyModelCatalog.byId(id)
    if (entry != null && entry.kind == EasyModelKind.IMAGE) {
        val main = EasyModelCatalog.installedModel(entry, installed) ?: return null
        val kind = entry.imageKind ?: inferEasyImageKind(main.type, main.filename, main.sdFamily, main.sdVariant)
        val engine = entry.engine ?: EasyImageEngine.SDCPP
        val components = mutableMapOf<EasySdSlot, ModelEntity>()
        EasyModelCatalog.sdBundle(entry)?.let { bundle ->
            bundle.files.forEach { file ->
                val slot = easySdSlotFor(file.modelType)
                if (slot != null && slot != EasySdSlot.MAIN) {
                    file.installedSdCuratedModel(bundle, installed)?.let { components[slot] = it }
                }
            }
        }
        return EasyImageSelection(id, entry, main, kind, engine, components)
    }
    val filename = parseEasyLocalImageModelId(id) ?: return null
    val main = installed.firstOrNull { it.filename == filename && it.isEasyUsable() } ?: return null
    if (!isEasySdMainType(main.type) && main.type != ModelType.ONNX_IMAGE_GEN) return null
    val kind = inferEasyImageKind(main.type, main.filename, main.sdFamily, main.sdVariant)
    val engine = if (main.type == ModelType.ONNX_IMAGE_GEN) EasyImageEngine.ONNX else EasyImageEngine.SDCPP
    val components = if (engine == EasyImageEngine.SDCPP) autoComponents(main, installed) else emptyMap()
    return EasyImageSelection(id, null, main, kind, engine, components)
}

/** Picks the first installed, family-compatible file for each external role the pipeline needs. */
private fun autoComponents(main: ModelEntity, installed: List<ModelEntity>): Map<EasySdSlot, ModelEntity> {
    val pipeline = runCatching {
        resolveSdPipeline(
            SDConfig(
                modelPath = main.path, prompt = "", outputPath = "",
                modelFamily = main.sdFamily, modelVariant = main.sdVariant,
                modelLayout = main.sdArtifactLayout?.let(SdMainLayout::fromStoredValue)
            ),
            main.sdArtifactInspection()
        )
    }.getOrNull() ?: return emptyMap()
    val family = pipeline.family ?: return emptyMap()
    val result = mutableMapOf<EasySdSlot, ModelEntity>()
    pipeline.requiredExternalRoles.forEach { role ->
        val slot = slotOfRole(role) ?: return@forEach
        installed.firstOrNull {
            it.isEasyUsable() && easySdSlotFor(it.type) == slot && it.matchesSdFamily(family, pipeline.variant)
        }?.let { result[slot] = it }
    }
    return result
}

/**
 * The model the Images tab should use: the saved choice when it is still installed, otherwise the
 * best installed curated entry for this phone, otherwise any installed imported model.
 */
fun effectiveEasyImageSelection(
    savedId: String,
    installed: List<ModelEntity>,
    totalRamBytes: Long
): EasyImageSelection? {
    resolveEasyImageSelection(savedId, installed)?.let { return it }
    val curated = EasyModelCatalog.entriesFor(EasyModelKind.IMAGE)
    val preferred = EasyModelCatalog.recommendFor(EasyModelKind.IMAGE, totalRamBytes)
    val ordered = listOfNotNull(preferred) + curated.filter { it != preferred }
    ordered.forEach { entry -> resolveEasyImageSelection(entry.id, installed)?.let { return it } }
    val local = easyLocalImageModels(installed, easyCuratedImageMainPaths(installed)).firstOrNull() ?: return null
    return resolveEasyImageSelection(easyLocalImageModelId(local.filename), installed)
}

/** Builds the sd.cpp launch config with the Easy preset; mirrors ImageGenScreen's TXT2IMG path. */
fun buildEasySdConfig(
    context: Context,
    selection: EasyImageSelection,
    prompt: String,
    size: Pair<Int, Int>
): SDConfig {
    val preset = selection.preset
    val main = selection.main
    val layout = main.sdArtifactLayout?.let(SdMainLayout::fromStoredValue)
    val pipelineLayout = runCatching {
        resolveSdPipeline(
            SDConfig(
                modelPath = main.path, prompt = "", outputPath = "",
                modelFamily = main.sdFamily, modelVariant = main.sdVariant, modelLayout = layout,
                vaePath = selection.components[EasySdSlot.VAE]?.path,
                taePath = selection.components[EasySdSlot.TAE]?.path,
                clipLPath = selection.components[EasySdSlot.CLIP_L]?.path,
                clipGPath = selection.components[EasySdSlot.CLIP_G]?.path,
                t5xxlPath = selection.components[EasySdSlot.T5XXL]?.path,
                llmPath = selection.components[EasySdSlot.LLM]?.path,
                llmVisionPath = selection.components[EasySdSlot.LLM_VISION]?.path
            ),
            main.sdArtifactInspection()
        ).mainLayout
    }.getOrNull() ?: layout
    val outputDir = File(easyOutputRoot(context), "sd").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return SDConfig(
        mode = SDMode.TXT2IMG,
        modelPath = main.path,
        prompt = prompt,
        negativePrompt = preset.negativePrompt.orEmpty(),
        width = size.first,
        height = size.second,
        steps = preset.steps,
        cfgScale = preset.cfgScale,
        seed = -1L,
        samplingMethod = preset.sampler ?: com.example.llamadroid.service.SamplingMethod.EULER_A,
        scheduler = preset.scheduler,
        outputPath = File(outputDir, "sd_$timestamp.png").absolutePath,
        threads = easyAutoThreads(),
        easyMode = true,
        modelLayout = pipelineLayout,
        modelFamily = main.sdFamily,
        modelVariant = main.sdVariant,
        vaePath = selection.components[EasySdSlot.VAE]?.path,
        taePath = selection.components[EasySdSlot.TAE]?.path,
        clipLPath = selection.components[EasySdSlot.CLIP_L]?.path,
        clipGPath = selection.components[EasySdSlot.CLIP_G]?.path,
        t5xxlPath = selection.components[EasySdSlot.T5XXL]?.path,
        llmPath = selection.components[EasySdSlot.LLM]?.path,
        llmVisionPath = selection.components[EasySdSlot.LLM_VISION]?.path,
        // Fixed Easy defaults: tile the VAE for large canvases, no RAM cap.
        vaeTiling = maxOf(size.first, size.second) >= 768
    )
}

/** Builds the ONNX launch config with the Easy preset; mirrors OnnxImageGenScreen's TXT2IMG path. */
fun buildEasyOnnxConfig(
    context: Context,
    selection: EasyImageSelection,
    prompt: String,
    size: Pair<Int, Int>
): OnnxImageGenConfig {
    val preset = selection.preset
    val catalogEntry = com.example.llamadroid.onnx.resolveOnnxCatalogEntry(selection.main)
    val canvas = com.example.llamadroid.onnx.normalizeOnnxCanvasSize(size.first, size.second)
    val outputDir = File(easyOutputRoot(context), "onnx").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outputFile = File(outputDir, "${OnnxImageGenMode.TXT2IMG.storageToken}_$timestamp.png")
    return OnnxImageGenConfig(
        modelPath = selection.main.path,
        modelName = catalogEntry?.title ?: selection.main.filename,
        mode = OnnxImageGenMode.TXT2IMG,
        prompt = prompt,
        negativePrompt = preset.negativePrompt.orEmpty(),
        width = canvas.normalizedWidth,
        height = canvas.normalizedHeight,
        steps = preset.steps,
        cfgScale = preset.cfgScale,
        seed = -1L,
        requestedWidth = size.first,
        requestedHeight = size.second,
        outputPath = outputFile.absolutePath,
        easyMode = true
    )
}

/** Root of every Easy-mode image output; the Easy gallery shows only this folder. */
fun easyOutputRoot(context: Context): File = File(context.filesDir, "easy_output")

/** Automatic thread count for Easy runs, independent of the advanced SD settings. */
internal fun easyAutoThreads(cpuCount: Int = Runtime.getRuntime().availableProcessors()): Int =
    (cpuCount / 2).coerceIn(2, 6)
