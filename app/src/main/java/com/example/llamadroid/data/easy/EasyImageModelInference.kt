package com.example.llamadroid.data.easy

import com.example.llamadroid.data.db.ModelType

/**
 * Pure helpers that map an imported (non-curated) image model onto the fixed Easy presets.
 * Nothing here touches Android, so it is covered by plain JVM tests.
 */

private const val LOCAL_IMAGE_ID_PREFIX = "local:"
private val XL_TOKEN = Regex("(^|[^a-z0-9])xl([^a-z0-9]|$)")

/**
 * Picks the preset family for an imported model: ONNX bundles use the ONNX SD1.5 preset,
 * flux/schnell weights use the Flux preset, SDXL + turbo uses the SDXL-Turbo preset and
 * everything else falls back to SD1.5.
 */
fun inferEasyImageKind(
    type: ModelType,
    filename: String,
    sdFamily: String? = null,
    sdVariant: String? = null
): EasyImageKind {
    if (type == ModelType.ONNX_IMAGE_GEN) return EasyImageKind.ONNX_SD15
    val name = filename.lowercase()
    val family = sdFamily.orEmpty().lowercase()
    val variant = sdVariant.orEmpty().lowercase()
    val haystack = "$name $family $variant"
    if ("flux" in haystack || "schnell" in haystack) return EasyImageKind.FLUX_SCHNELL
    val isXl = "sdxl" in haystack || "sd_xl" in haystack || "sd-xl" in haystack ||
        XL_TOKEN.containsMatchIn(haystack)
    if (isXl && "turbo" in haystack) return EasyImageKind.SDXL_TURBO
    return EasyImageKind.SD15_SDCPP
}

/** Stable id stored in [EasyModeSettings.imageModelId] for an imported model (its filename key). */
fun easyLocalImageModelId(filename: String): String = LOCAL_IMAGE_ID_PREFIX + filename

/** Returns the filename of an imported model id, or null when [id] is not an imported id. */
fun parseEasyLocalImageModelId(id: String): String? =
    id.takeIf { it.startsWith(LOCAL_IMAGE_ID_PREFIX) }?.removePrefix(LOCAL_IMAGE_ID_PREFIX)?.takeIf { it.isNotBlank() }

/** Where an installed stable-diffusion.cpp file goes in `SDConfig`. */
enum class EasySdSlot { MAIN, VAE, TAE, CLIP_L, CLIP_G, T5XXL, LLM, LLM_VISION }

/** Maps an installed file's type to its launch slot; null when Easy mode does not use it. */
fun easySdSlotFor(type: ModelType): EasySdSlot? = when (type) {
    ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION -> EasySdSlot.MAIN
    ModelType.SD_VAE -> EasySdSlot.VAE
    ModelType.SD_TAE -> EasySdSlot.TAE
    ModelType.SD_CLIP_L -> EasySdSlot.CLIP_L
    ModelType.SD_CLIP_G -> EasySdSlot.CLIP_G
    ModelType.SD_T5XXL -> EasySdSlot.T5XXL
    ModelType.SD_LLM -> EasySdSlot.LLM
    ModelType.VISION_PROJECTOR, ModelType.MMPROJ -> EasySdSlot.LLM_VISION
    else -> null
}

/** True for model types that can be the main model of an Easy sd.cpp generation. */
fun isEasySdMainType(type: ModelType): Boolean = easySdSlotFor(type) == EasySdSlot.MAIN

/** Snaps [size] to a size the preset allows; unknown sizes fall back to the 512 default. */
fun coerceEasyImageSize(preset: EasyImagePreset, size: Pair<Int, Int>?): Pair<Int, Int> =
    size?.takeIf { it in preset.sizes } ?: preset.defaultSize

/** "512x768" label used on size chips. */
fun easySizeLabel(size: Pair<Int, Int>): String = "${size.first}×${size.second}"
