package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdArtifactFormat
import com.example.llamadroid.sd.SdInspectionCache
import com.example.llamadroid.sd.SdInspectionConfidence
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdPipelineIssue
import com.example.llamadroid.sd.SdPipelineIssueCode
import com.example.llamadroid.sd.SdResolvedPipeline
import com.example.llamadroid.sd.needsSdArtifactInspection
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolveSdPipeline
import com.example.llamadroid.sd.sdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import java.io.File

/**
 * The final, shared preflight used by every local SD generation entry point.
 * It reads bounded headers only, lazily persists main-model evidence when the
 * artifact belongs to the library, and validates every configured component
 * before stable-diffusion.cpp is allowed to start.
 */
internal suspend fun resolveSdPipelineForLaunch(
    context: Context,
    config: SDConfig
): SdResolvedPipeline {
    require(config.mode != SDMode.UPSCALE) { "Upscaling does not use an SD model pipeline." }

    val dao = AppDatabase.getDatabase(context.applicationContext).modelDao()
    val storedMain = dao.getModelByPath(config.modelPath)
    val configuredMainType = storedMain?.type ?: when (config.modelLayout) {
        SdMainLayout.FULL_MODEL -> ModelType.SD_CHECKPOINT
        SdMainLayout.STANDALONE_DIFFUSION -> ModelType.SD_DIFFUSION
        else -> null
    }
    val mainFile = File(config.modelPath)
    val mainInspection = when {
        configuredMainType == null -> SdInspectionCache.inspect(mainFile)
        storedMain != null && !storedMain.needsSdArtifactInspection(mainFile) ->
            storedMain.sdArtifactInspection() ?: SdInspectionCache.inspect(mainFile)
        else -> ModelRepository.inspectSdArtifact(mainFile, configuredMainType)
    }

    if (storedMain != null && storedMain.needsSdArtifactInspection(mainFile)) {
        dao.insertModel(storedMain.withSdArtifactInspection(mainInspection))
    }

    val effectiveConfig = config.copy(
        modelFamily = config.modelFamily ?: storedMain?.sdFamily ?: storedMain?.sdDetectedFamily,
        modelVariant = config.modelVariant ?: storedMain?.sdVariant,
        modelLayout = config.modelLayout
            ?: storedMain?.sdArtifactLayout?.let(SdMainLayout::fromStoredValue)
    )
    var pipeline = resolveSdPipeline(effectiveConfig, mainInspection)
    val componentIssues = mutableListOf<SdPipelineIssue>()
    val componentWarnings = mutableListOf<SdPipelineIssue>()

    val mainValidationError = if (configuredMainType != null) {
        ModelRepository.validateSdArtifactInspection(
            configuredType = configuredMainType,
            inspection = mainInspection,
            configuredFamily = effectiveConfig.modelFamily
        ).exceptionOrNull()
    } else if (mainInspection.format == SdArtifactFormat.UNKNOWN) {
        IllegalArgumentException("The selected Stable Diffusion artifact format is unsupported or unreadable.")
    } else {
        null
    }
    mainValidationError?.let { error ->
        componentIssues += SdPipelineIssue(
            code = SdPipelineIssueCode.INVALID_ARTIFACT,
            message = error.message ?: "The selected Stable Diffusion artifact is invalid.",
            evidence = "model=${mainFile.name}"
        )
    }

    configuredComponents(effectiveConfig).forEach { (type, path) ->
        val file = File(path)
        val inspection = ModelRepository.inspectSdArtifact(file, type)
        val validation = ModelRepository.validateSdArtifactInspection(
            configuredType = type,
            inspection = inspection,
            configuredFamily = pipeline.family?.storedValue
        )
        validation.exceptionOrNull()?.let { error ->
            componentIssues += SdPipelineIssue(
                code = SdPipelineIssueCode.COMPONENT_INCOMPATIBLE,
                message = error.message ?: "A configured Stable Diffusion component is incompatible.",
                evidence = "component=${file.name} type=${type.name}"
            )
        }
        if (validation.isSuccess && inspection.confidence != SdInspectionConfidence.HIGH &&
            inspection.warnings.isNotEmpty()
        ) {
            componentWarnings += SdPipelineIssue(
                code = SdPipelineIssueCode.COMPONENT_INCOMPATIBLE,
                message = "${file.name}: ${inspection.warnings.first()}",
                blocking = false,
                evidence = "confidence=${inspection.confidence.storedValue}"
            )
        }
        persistComponentInspection(dao, path, inspection)
    }

    val resolvedFamily = pipeline.family
    if (resolvedFamily != null) {
        configuredCompatibilityComponents(effectiveConfig).forEach { (type, path) ->
            val stored = dao.getModelByPath(path)
            if (stored != null && !stored.matchesSdFamily(resolvedFamily, pipeline.variant)) {
                componentIssues += SdPipelineIssue(
                    code = SdPipelineIssueCode.COMPONENT_INCOMPATIBLE,
                    message = "The selected ${type.name} artifact is incompatible with " +
                        resolvedFamily.storedValue + ".",
                    evidence = "component=${File(path).name} type=${type.name} " +
                        "family=${resolvedFamily.storedValue} variant=${pipeline.variant.orEmpty()}"
                )
            }
        }
    }

    pipeline = pipeline.copy(
        warnings = pipeline.warnings + componentWarnings,
        blockingIssues = pipeline.blockingIssues + componentIssues
    )
    return pipeline.requireValid()
}

private suspend fun persistComponentInspection(
    dao: com.example.llamadroid.data.db.ModelDao,
    path: String,
    inspection: SdArtifactInspection
) {
    val stored = dao.getModelByPath(path) ?: return
    val file = File(path)
    if (stored.needsSdArtifactInspection(file)) {
        dao.insertModel(stored.withSdArtifactInspection(inspection))
    }
}

private fun configuredComponents(config: SDConfig): List<Pair<ModelType, String>> = buildList {
    config.vaePath?.let { add(ModelType.SD_VAE to it) }
    config.taePath?.let { add(ModelType.SD_TAE to it) }
    config.clipLPath?.let { add(ModelType.SD_CLIP_L to it) }
    config.clipGPath?.let { add(ModelType.SD_CLIP_G to it) }
    config.t5xxlPath?.let { add(ModelType.SD_T5XXL to it) }
    config.controlNetPath?.let { add(ModelType.SD_CONTROLNET to it) }
    config.resolvedLoras().forEach { add(ModelType.SD_LORA to it.path) }
    config.ipAdapter?.let { adapter ->
        add(ModelType.SD_IP_ADAPTER to adapter.adapterPath)
        add(ModelType.SD_CLIP_VISION to adapter.clipVisionPath)
    }
}

private fun configuredCompatibilityComponents(
    config: SDConfig
): List<Pair<ModelType, String>> = buildList {
    config.ipAdapter?.let { adapter ->
        add(ModelType.SD_IP_ADAPTER to adapter.adapterPath)
        add(ModelType.SD_CLIP_VISION to adapter.clipVisionPath)
    }
}

internal fun sdPipelineIssueMessage(
    context: Context,
    issue: SdPipelineIssue?
): String = context.getString(
    when (issue?.code) {
        SdPipelineIssueCode.MISSING_VAE -> R.string.imagegen_error_sd_missing_vae
        SdPipelineIssueCode.DETECTED_FAMILY_CONFLICT,
        SdPipelineIssueCode.DETECTED_LAYOUT_CONFLICT,
        SdPipelineIssueCode.COMPONENT_INCOMPATIBLE -> R.string.imagegen_error_sd_metadata_mismatch
        SdPipelineIssueCode.INVALID_ARTIFACT -> R.string.imagegen_error_sd_corrupt_model
        else -> R.string.imagegen_sd_pipeline_unresolved
    }
)
