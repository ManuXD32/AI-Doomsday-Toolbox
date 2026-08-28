package com.example.llamadroid.ui.ai

import com.example.llamadroid.data.db.ModelEntity

internal const val IMAGE_GEN_MODE_TXT2IMG = 0
internal const val IMAGE_GEN_MODE_IMG2IMG = 1
internal const val IMAGE_GEN_MODE_UPSCALE = 2
internal const val IMAGE_GEN_MODE_INPAINT = 3
internal const val IMAGE_GEN_MODE_ADETAILER = 4

/** The two deliberate ADetailer workflows supported by the native CLI. */
internal enum class ADetailerInputMode {
    EXISTING_IMAGE,
    GENERATED_IMAGE;

    companion object {
        fun fromStoredValue(value: String?): ADetailerInputMode =
            entries.firstOrNull { it.name == value } ?: EXISTING_IMAGE
    }
}

/**
 * A user-facing operation is more precise than the underlying sd.cpp mode. Inpaint uses
 * img_gen, existing-image ADetailer uses the dedicated mode, and generated ADetailer uses
 * img_gen plus detector flags.
 */
internal enum class ImageGenOperation {
    CREATE,
    TRANSFORM,
    UPSCALE,
    INPAINT,
    ADETAILER_EXISTING,
    ADETAILER_GENERATED
}

internal fun resolveImageGenOperation(
    selectedMode: Int,
    adetailerInputMode: ADetailerInputMode = ADetailerInputMode.EXISTING_IMAGE
): ImageGenOperation = when (selectedMode) {
    IMAGE_GEN_MODE_IMG2IMG -> ImageGenOperation.TRANSFORM
    IMAGE_GEN_MODE_UPSCALE -> ImageGenOperation.UPSCALE
    IMAGE_GEN_MODE_INPAINT -> ImageGenOperation.INPAINT
    IMAGE_GEN_MODE_ADETAILER -> when (adetailerInputMode) {
        ADetailerInputMode.EXISTING_IMAGE -> ImageGenOperation.ADETAILER_EXISTING
        ADetailerInputMode.GENERATED_IMAGE -> ImageGenOperation.ADETAILER_GENERATED
    }
    else -> ImageGenOperation.CREATE
}

internal enum class ImageGenReadinessIssue {
    MODEL,
    PROMPT,
    SOURCE_IMAGE,
    MASK,
    DETECTOR,
    FAMILY_SUPPORT,
    REQUIRED_COMPONENTS
}

internal data class ImageGenReadiness(
    val issues: Set<ImageGenReadinessIssue>
) {
    val isReady: Boolean get() = issues.isEmpty()
}

/** Pure input contract shared by the CTA and the launch dispatcher. */
internal data class ImageGenReadinessInput(
    val operation: ImageGenOperation,
    val hasReadableModel: Boolean,
    val hasPrompt: Boolean,
    val hasReadableSourceImage: Boolean,
    val hasReadableMask: Boolean,
    val hasReadableDetector: Boolean,
    val supportsTxt2Img: Boolean,
    val supportsImg2Img: Boolean,
    val hasRequiredComponents: Boolean
)

internal fun resolveImageGenReadiness(input: ImageGenReadinessInput): ImageGenReadiness {
    val issues = linkedSetOf<ImageGenReadinessIssue>()
    if (!input.hasReadableModel) issues += ImageGenReadinessIssue.MODEL
    if (!input.hasRequiredComponents) issues += ImageGenReadinessIssue.REQUIRED_COMPONENTS

    when (input.operation) {
        ImageGenOperation.CREATE -> {
            if (!input.hasPrompt) issues += ImageGenReadinessIssue.PROMPT
            if (!input.supportsTxt2Img) issues += ImageGenReadinessIssue.FAMILY_SUPPORT
        }
        ImageGenOperation.TRANSFORM -> {
            if (!input.hasPrompt) issues += ImageGenReadinessIssue.PROMPT
            if (!input.hasReadableSourceImage) issues += ImageGenReadinessIssue.SOURCE_IMAGE
            if (!input.supportsImg2Img) issues += ImageGenReadinessIssue.FAMILY_SUPPORT
        }
        ImageGenOperation.UPSCALE -> {
            if (!input.hasReadableSourceImage) issues += ImageGenReadinessIssue.SOURCE_IMAGE
        }
        ImageGenOperation.INPAINT -> {
            if (!input.hasPrompt) issues += ImageGenReadinessIssue.PROMPT
            if (!input.hasReadableSourceImage) issues += ImageGenReadinessIssue.SOURCE_IMAGE
            if (!input.hasReadableMask) issues += ImageGenReadinessIssue.MASK
            if (!input.supportsImg2Img) issues += ImageGenReadinessIssue.FAMILY_SUPPORT
        }
        ImageGenOperation.ADETAILER_EXISTING -> {
            if (!input.hasPrompt) issues += ImageGenReadinessIssue.PROMPT
            if (!input.hasReadableSourceImage) issues += ImageGenReadinessIssue.SOURCE_IMAGE
            if (!input.hasReadableDetector) issues += ImageGenReadinessIssue.DETECTOR
            if (!input.supportsImg2Img) issues += ImageGenReadinessIssue.FAMILY_SUPPORT
        }
        ImageGenOperation.ADETAILER_GENERATED -> {
            if (!input.hasPrompt) issues += ImageGenReadinessIssue.PROMPT
            if (!input.hasReadableDetector) issues += ImageGenReadinessIssue.DETECTOR
            if (!input.supportsTxt2Img) issues += ImageGenReadinessIssue.FAMILY_SUPPORT
        }
    }
    return ImageGenReadiness(issues)
}

internal fun resolveInitialImageGenMode(targetScreen: String?): Int {
    return when (targetScreen) {
        "imagegen_upscale" -> 2
        "imagegen_img2img" -> 1
        else -> 0
    }
}

internal fun resolveImageGenActiveModels(
    selectedMode: Int,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): List<ModelEntity> {
    return if (selectedMode == 2) upscalerModels else generationModels
}

internal fun resolveImageGenSelectedMainModel(
    selectedMode: Int,
    selectedModelPath: String?,
    generationModels: List<ModelEntity>
): ModelEntity? {
    if (selectedMode == 2) return null
    return generationModels.firstOrNull { it.path == selectedModelPath }
}

internal fun hasValidImageGenSelection(
    selectedMode: Int,
    selectedModelPath: String?,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): Boolean {
    if (selectedModelPath == null) return false
    return when (selectedMode) {
        2 -> upscalerModels.any { it.path == selectedModelPath }
        else -> generationModels.any { it.path == selectedModelPath }
    }
}

internal fun normalizeImageGenSelectionForMode(
    targetMode: Int,
    currentSelectedModelPath: String?,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): String? {
    val targetModels = resolveImageGenActiveModels(
        selectedMode = targetMode,
        generationModels = generationModels,
        upscalerModels = upscalerModels
    )
    return currentSelectedModelPath?.takeIf { path ->
        targetModels.any { it.path == path }
    }
}
