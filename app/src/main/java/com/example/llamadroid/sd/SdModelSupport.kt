package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.db.hasSdCapability

enum class SdModelFamily(val storedValue: String) {
    CHECKPOINT("checkpoint"),
    SD3("sd3"),
    FLUX_1("flux_1"),
    FLUX_KONTEXT("flux_kontext"),
    FLUX_2("flux_2"),
    CHROMA("chroma"),
    CHROMA_RADIANCE("chroma_radiance"),
    QWEN_IMAGE("qwen_image"),
    QWEN_IMAGE_EDIT("qwen_image_edit"),
    Z_IMAGE("z_image"),
    OVIS_IMAGE("ovis_image"),
    ANIMA("anima"),
    // Video families are appended so existing stored family ordinals and
    // storedValue strings remain stable for image pipelines.
    WAN("wan"),
    HUNYUAN_VIDEO("hunyuan_video"),
    LINGBOT_VIDEO("lingbot_video"),
    LTX_VIDEO("ltx_video"),
    MINIMAX_H3("minimax_h3"),
    SVD("svd"),
    ANIMATEDIFF("animatediff");

    companion object {
        fun fromStoredValue(value: String?): SdModelFamily? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            }
        }
    }
}

fun SdModelFamily.isVideoFamily(): Boolean = when (this) {
    SdModelFamily.WAN,
    SdModelFamily.HUNYUAN_VIDEO,
    SdModelFamily.LINGBOT_VIDEO,
    SdModelFamily.LTX_VIDEO,
    SdModelFamily.MINIMAX_H3,
    SdModelFamily.SVD,
    SdModelFamily.ANIMATEDIFF -> true
    else -> false
}

fun SdVideoFamily.toSdModelFamily(): SdModelFamily = when (this) {
    SdVideoFamily.WAN -> SdModelFamily.WAN
    SdVideoFamily.HUNYUAN_VIDEO -> SdModelFamily.HUNYUAN_VIDEO
    SdVideoFamily.LINGBOT_VIDEO -> SdModelFamily.LINGBOT_VIDEO
    SdVideoFamily.LTX_VIDEO -> SdModelFamily.LTX_VIDEO
    SdVideoFamily.MINIMAX_H3 -> SdModelFamily.MINIMAX_H3
    SdVideoFamily.SVD -> SdModelFamily.SVD
    SdVideoFamily.ANIMATEDIFF -> SdModelFamily.ANIMATEDIFF
}

fun SdModelFamily.toVideoFamily(): SdVideoFamily? = when (this) {
    SdModelFamily.WAN -> SdVideoFamily.WAN
    SdModelFamily.HUNYUAN_VIDEO -> SdVideoFamily.HUNYUAN_VIDEO
    SdModelFamily.LINGBOT_VIDEO -> SdVideoFamily.LINGBOT_VIDEO
    SdModelFamily.LTX_VIDEO -> SdVideoFamily.LTX_VIDEO
    SdModelFamily.MINIMAX_H3 -> SdVideoFamily.MINIMAX_H3
    SdModelFamily.SVD -> SdVideoFamily.SVD
    SdModelFamily.ANIMATEDIFF -> SdVideoFamily.ANIMATEDIFF
    else -> null
}

enum class SdCacheArchitecture {
    UNET,
    DIT
}

enum class SdImageInputMode {
    INIT_IMAGE,
    REFERENCE_IMAGE
}

enum class SdComponentRole(val compatToken: String) {
    MAIN_MODEL("main"),
    VAE("vae"),
    TAE("tae"),
    CLIP_L("clip_l"),
    CLIP_G("clip_g"),
    T5XXL("t5xxl"),
    LLM("llm"),
    LLM_VISION("llm_vision"),
    CONTROLNET("controlnet"),
    LORA("lora"),
    PHOTOMAKER("photomaker"),
    CLIP_VISION("clip_vision"),
    IP_ADAPTER("ip_adapter"),
    UPSCALER("upscaler");

    companion object {
        fun fromModelType(type: ModelType): SdComponentRole? = when (type) {
            ModelType.SD_VAE -> VAE
            ModelType.SD_TAE -> TAE
            ModelType.SD_CLIP_L -> CLIP_L
            ModelType.SD_CLIP_G -> CLIP_G
            ModelType.SD_T5XXL -> T5XXL
            ModelType.SD_CONTROLNET -> CONTROLNET
            ModelType.SD_LORA -> LORA
            ModelType.SD_PHOTOMAKER -> PHOTOMAKER
            ModelType.SD_CLIP_VISION -> CLIP_VISION
            ModelType.SD_IP_ADAPTER -> IP_ADAPTER
            ModelType.SD_UPSCALER -> UPSCALER
            ModelType.LLM -> LLM
            ModelType.VISION_PROJECTOR -> LLM_VISION
            else -> null
        }
    }
}

enum class SdLoraApplyMode(val cliName: String) {
    IMMEDIATELY("immediately"),
    AT_RUNTIME("at_runtime");

    companion object {
        fun fromStoredValue(value: String?): SdLoraApplyMode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.cliName.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            }
        }
    }
}

data class SdModelFamilySpec(
    val family: SdModelFamily,
    val variant: String? = null,
    val cacheArchitecture: SdCacheArchitecture,
    val img2imgInputMode: SdImageInputMode = SdImageInputMode.INIT_IMAGE,
    val defaultCapabilities: String? = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
    val requiredRoles: Set<SdComponentRole> = emptySet(),
    val optionalRoles: Set<SdComponentRole> = emptySet(),
    val supportsFlowShift: Boolean = false,
    val supportsDiffusionFa: Boolean = false,
    val supportsMmap: Boolean = false,
    val supportsVaeConvDirect: Boolean = false,
    val supportsLoraApplyMode: Boolean = false,
    val supportsQwenImageZeroCondT: Boolean = false,
    val supportsChromaDisableDitMask: Boolean = false,
    val supportsIpAdapter: Boolean = false,
    /** Three-conditioning image CFG is an editing-model capability, not generic img2img. */
    val supportsImgCfgScale: Boolean = false
)

fun String?.parseSdCompatProfiles(): Set<String> =
    this
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

fun buildSdCompatProfiles(vararg tokens: String?): String? {
    val normalized = tokens
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    return normalized.joinToString(",").ifBlank { null }
}

/**
 * Return an exact checkpoint-generation profile for components whose weights
 * are tied to a base checkpoint.  An IP-Adapter or CLIP-Vision filename with a
 * clear SD1/SDXL marker must not remain compatible with both generations.
 */
fun exactSdCheckpointComponentProfile(
    type: ModelType,
    family: SdModelFamily?,
    variant: String?
): String? {
    if (type != ModelType.SD_IP_ADAPTER && type != ModelType.SD_CLIP_VISION) return null
    if (family != SdModelFamily.CHECKPOINT) return null
    val normalizedVariant = variant
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val canonicalVariant = when {
        normalizedVariant == "sdxl" || normalizedVariant.startsWith("sdxl_") -> "sdxl"
        normalizedVariant == "sd1" || normalizedVariant.startsWith("sd1_") ||
            normalizedVariant.startsWith("sd15") || normalizedVariant.startsWith("sd_1") -> "sd1"
        else -> return null
    }
    return buildSdCompatProfiles("${SdModelFamily.CHECKPOINT.storedValue}:$canonicalVariant")
}

/**
 * Resolve default compatibility metadata while preserving explicit caller
 * choices. The exact old broad default is narrowed only when filename
 * inference supplied an unambiguous checkpoint generation.
 */
fun resolveSdCompatProfiles(
    type: ModelType,
    explicitProfiles: String?,
    family: SdModelFamily?,
    variant: String?
): String? {
    val exactProfile = exactSdCheckpointComponentProfile(type, family, variant)
    val oldBroadDefault = setOf(
        "${SdModelFamily.CHECKPOINT.storedValue}:sd1",
        "${SdModelFamily.CHECKPOINT.storedValue}:sdxl"
    )
    if (explicitProfiles != null) {
        // Treat all ordering/whitespace variants of the exact legacy value as
        // generated defaults, while leaving any other explicit profile set
        // untouched.
        if (explicitProfiles.parseSdCompatProfiles() == oldBroadDefault && exactProfile != null) {
            return exactProfile
        }
        return explicitProfiles
    }
    return exactProfile ?: buildSdCompatProfiles(*defaultCompatProfilesFor(type).toTypedArray())
}

fun SdModelFamily.compatTokens(variant: String? = null): Set<String> = buildSet {
    add(storedValue)
    variant
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.let { add("$storedValue:$it") }
}

fun ModelEntity.sdFamilyEnum(): SdModelFamily? = SdModelFamily.fromStoredValue(sdFamily)

fun ModelEntity.resolvedSdFamily(): Pair<SdModelFamily?, String?> {
    val explicitFamily = sdFamilyEnum()
    val inspection = sdArtifactInspection()
    val detectedFamily = inspection?.detectedFamily ?: SdModelFamily.fromStoredValue(sdDetectedFamily)
    val detectedVariant = inspection?.detectedVariant ?: sdVariantToken()
    if (explicitFamily != null) {
        val explicitVariant = sdVariantToken()
        if (explicitVariant != null) return explicitFamily to explicitVariant
        return explicitFamily to (detectedVariant ?: inferSdFamily(type, repoId, filename).second)
    }
    if (detectedFamily != null) return detectedFamily to (detectedVariant ?: inferSdFamily(type, repoId, filename).second)
    return inferSdFamily(type, repoId, filename)
}

fun ModelEntity.resolveSdFamilySpec(): SdModelFamilySpec? {
    val (family, variant) = resolvedSdFamily()
    return family?.let { resolveSdFamilySpec(it, variant) }
}

fun ModelEntity.sdCompatProfileTokens(): Set<String> = sdCompatProfiles.parseSdCompatProfiles()

fun ModelEntity.sdVariantToken(): String? = sdVariant?.trim()?.ifBlank { null }?.lowercase()

fun ModelEntity.effectiveSdCompatProfiles(): Set<String> {
    val inferred = inferSdFamily(type, repoId, filename)
    val inspection = sdArtifactInspection()
    val family = sdFamilyEnum() ?: inspection?.detectedFamily ?:
        SdModelFamily.fromStoredValue(sdDetectedFamily) ?: inferred.first
    val variant = sdVariantToken() ?: inspection?.detectedVariant ?: inferred.second
    val resolved = resolveSdCompatProfiles(
        type = type,
        explicitProfiles = sdCompatProfiles,
        family = family,
        variant = variant
    )
    return resolved.parseSdCompatProfiles()
}

fun ModelEntity.isSdImageSupportModel(): Boolean =
    type == ModelType.LLM || type == ModelType.VISION_PROJECTOR

fun ModelEntity.isSdImageMainModel(): Boolean {
    if (type != ModelType.SD_CHECKPOINT && type != ModelType.SD_DIFFUSION) {
        return false
    }
    if (resolvedSdVideoFamily().first != null) return false
    if (hasSdCapability(SD_CAPABILITY_VID_GEN) && !hasSdCapability(SD_CAPABILITY_TXT2IMG) && !hasSdCapability(SD_CAPABILITY_IMG2IMG)) {
        return false
    }
    return true
}

/**
 * Resolve only explicit or structurally detected video metadata.  Video
 * families are deliberately absent from filename inference: an imported
 * `wan.safetensors` with unrelated tensors remains an Unknown/pending row.
 */
fun ModelEntity.resolvedSdVideoFamily(): Pair<SdVideoFamily?, String?> {
    val (family, variant) = resolvedSdFamily()
    return family?.toVideoFamily() to variant
}

fun ModelEntity.isSdVideoMainModel(): Boolean {
    if (type != ModelType.SD_CHECKPOINT && type != ModelType.SD_DIFFUSION) return false
    return hasSdCapability(SD_CAPABILITY_VID_GEN) || resolvedSdVideoFamily().first != null
}

/** True when a row is a family-compatible video component or main model. */
fun ModelEntity.matchesSdVideoFamily(
    family: SdVideoFamily,
    variant: String? = null
): Boolean {
    val resolved = resolvedSdVideoFamily()
    val isMain = type == ModelType.SD_CHECKPOINT || type == ModelType.SD_DIFFUSION
    if (isMain) {
        // A main model must have an explicit/structurally detected family. A
        // generic vid_gen capability alone must not make it match every family.
        if (!isSdVideoMainModel() || resolved.first != family) return false
        val requested = variant?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return requested == null || resolved.second.isNullOrBlank() || resolved.second.equals(requested, ignoreCase = true)
    }
    val requestedModelFamily = family.toSdModelFamily()
    val familyMatches = matchesSdFamily(requestedModelFamily, variant)
    if (!familyMatches) return false
    val explicitComponentFamily = resolved.first
    return explicitComponentFamily == null || explicitComponentFamily == family
}

fun ModelEntity.matchesSdFamily(family: SdModelFamily, variant: String? = null): Boolean {
    val familyTokens = family.compatTokens(variant)
    return effectiveSdCompatProfiles().any { it in familyTokens }
}

fun defaultCompatProfilesFor(type: ModelType): Set<String> = when (type) {
    ModelType.SD_VAE -> setOf(
        SdModelFamily.CHECKPOINT.storedValue,
        SdModelFamily.FLUX_1.storedValue,
        SdModelFamily.FLUX_KONTEXT.storedValue,
        SdModelFamily.FLUX_2.storedValue,
        SdModelFamily.CHROMA.storedValue,
        SdModelFamily.QWEN_IMAGE.storedValue,
        SdModelFamily.QWEN_IMAGE_EDIT.storedValue,
        SdModelFamily.SD3.storedValue,
        SdModelFamily.Z_IMAGE.storedValue,
        SdModelFamily.OVIS_IMAGE.storedValue,
        SdModelFamily.ANIMA.storedValue
    ) + setOf(
        SdModelFamily.WAN.storedValue,
        SdModelFamily.HUNYUAN_VIDEO.storedValue,
        SdModelFamily.LINGBOT_VIDEO.storedValue,
        SdModelFamily.LTX_VIDEO.storedValue,
        SdModelFamily.MINIMAX_H3.storedValue,
        SdModelFamily.SVD.storedValue,
        SdModelFamily.ANIMATEDIFF.storedValue
    )
    ModelType.SD_TAE -> setOf(
        SdModelFamily.QWEN_IMAGE.storedValue,
        SdModelFamily.QWEN_IMAGE_EDIT.storedValue,
        SdModelFamily.WAN.storedValue,
        SdModelFamily.LINGBOT_VIDEO.storedValue
    )
    ModelType.SD_CLIP_L -> setOf(
        SdModelFamily.FLUX_1.storedValue,
        SdModelFamily.FLUX_KONTEXT.storedValue,
        SdModelFamily.SD3.storedValue
    )
    ModelType.SD_CLIP_G -> setOf(SdModelFamily.SD3.storedValue)
    ModelType.SD_T5XXL -> setOf(
        SdModelFamily.FLUX_1.storedValue,
        SdModelFamily.FLUX_KONTEXT.storedValue,
        SdModelFamily.SD3.storedValue,
        SdModelFamily.CHROMA.storedValue,
        SdModelFamily.CHROMA_RADIANCE.storedValue,
        SdModelFamily.WAN.storedValue,
        SdModelFamily.HUNYUAN_VIDEO.storedValue
    )
    ModelType.LLM,
    ModelType.VISION_PROJECTOR,
    ModelType.MMPROJ -> setOf(
        SdModelFamily.LTX_VIDEO.storedValue,
        SdModelFamily.MINIMAX_H3.storedValue,
        SdModelFamily.LINGBOT_VIDEO.storedValue,
        SdModelFamily.HUNYUAN_VIDEO.storedValue
    )
    ModelType.SD_CONTROLNET -> setOf(
        SdModelFamily.CHECKPOINT.storedValue,
        SdModelFamily.WAN.storedValue
    )
    ModelType.SD_LORA -> setOf(
        SdModelFamily.CHECKPOINT.storedValue,
        SdModelFamily.FLUX_1.storedValue,
        SdModelFamily.FLUX_KONTEXT.storedValue,
        SdModelFamily.FLUX_2.storedValue,
        SdModelFamily.CHROMA.storedValue,
        SdModelFamily.CHROMA_RADIANCE.storedValue,
        SdModelFamily.SD3.storedValue,
        SdModelFamily.WAN.storedValue,
        SdModelFamily.HUNYUAN_VIDEO.storedValue,
        SdModelFamily.LINGBOT_VIDEO.storedValue,
        SdModelFamily.LTX_VIDEO.storedValue,
        SdModelFamily.MINIMAX_H3.storedValue,
        SdModelFamily.SVD.storedValue,
        SdModelFamily.ANIMATEDIFF.storedValue
    )
    ModelType.SD_TEXTUAL_INVERSION -> setOf(
        SdModelFamily.CHECKPOINT.storedValue
    )
    ModelType.SD_PHOTOMAKER -> setOf("${SdModelFamily.CHECKPOINT.storedValue}:sdxl")
    ModelType.SD_CLIP_VISION -> setOf(
        "${SdModelFamily.CHECKPOINT.storedValue}:sd1",
        "${SdModelFamily.CHECKPOINT.storedValue}:sdxl",
        SdModelFamily.WAN.storedValue,
        SdModelFamily.SVD.storedValue
    )
    ModelType.SD_IP_ADAPTER -> setOf(
        "${SdModelFamily.CHECKPOINT.storedValue}:sd1",
        "${SdModelFamily.CHECKPOINT.storedValue}:sdxl"
    )
    ModelType.SD_AUDIO_VAE -> setOf(
        SdModelFamily.LTX_VIDEO.storedValue,
        SdModelFamily.MINIMAX_H3.storedValue
    )
    ModelType.SD_EMBEDDINGS_CONNECTORS -> setOf(SdModelFamily.LTX_VIDEO.storedValue)
    ModelType.SD_MOTION_MODULE -> setOf(SdModelFamily.ANIMATEDIFF.storedValue)
    ModelType.SD_DIFFUSION -> setOf(
        SdModelFamily.WAN.storedValue,
        SdModelFamily.HUNYUAN_VIDEO.storedValue,
        SdModelFamily.LINGBOT_VIDEO.storedValue,
        SdModelFamily.LTX_VIDEO.storedValue,
        SdModelFamily.MINIMAX_H3.storedValue,
        SdModelFamily.SVD.storedValue,
        SdModelFamily.ANIMATEDIFF.storedValue
    )
    ModelType.SD_UPSCALER -> setOf(SdComponentRole.UPSCALER.compatToken)
    else -> emptySet()
}

fun inferSdFamily(
    type: ModelType,
    repoId: String,
    filename: String
): Pair<SdModelFamily?, String?> {
    val haystack = listOf(repoId, filename).joinToString(" ").lowercase()
    return when (type) {
        ModelType.SD_CHECKPOINT -> when {
            haystack.contains("sd3.5") || haystack.contains("sd3_medium") || haystack.contains("stable-diffusion-3") || haystack.contains("stable diffusion 3") ->
                SdModelFamily.SD3 to inferSdVariant(type, haystack)
            haystack.contains("sdxl") ->
                SdModelFamily.CHECKPOINT to "sdxl"
            haystack.contains("sd2") || haystack.contains("2.1") ->
                SdModelFamily.CHECKPOINT to "sd2"
            haystack.contains("sd1") || haystack.contains("sd-1") ||
                haystack.contains("sd15") || haystack.contains("sd-1-5") ||
                haystack.contains("sd-v1") || haystack.contains("sd_v1") ->
                SdModelFamily.CHECKPOINT to "sd1"
            else -> null to null
        }
        ModelType.SD_CLIP_VISION,
        ModelType.SD_IP_ADAPTER -> when {
            haystack.contains("sdxl") -> SdModelFamily.CHECKPOINT to "sdxl"
            haystack.contains("sd1") || haystack.contains("sd-1") ||
                haystack.contains("sd15") || haystack.contains("sd-1-5") ||
                haystack.contains("sd_1_5") || haystack.contains("sd1_5") ||
                haystack.contains("sd-v1") || haystack.contains("sd_v1") ->
                SdModelFamily.CHECKPOINT to "sd1"
            else -> null to null
        }
        ModelType.SD_DIFFUSION -> when {
            haystack.contains("sd3.5") || haystack.contains("sd3_5") ||
                haystack.contains("sd3-medium") || haystack.contains("sd3_medium") ||
                haystack.contains("stable-diffusion-3") || haystack.contains("stable diffusion 3") ||
                Regex("(?:^|[^a-z0-9])sd3(?:[^a-z0-9]|$)").containsMatchIn(haystack) ->
                SdModelFamily.SD3 to inferSdVariant(type, haystack)
            haystack.contains("kontext") -> SdModelFamily.FLUX_KONTEXT to inferSdVariant(type, haystack)
            haystack.contains("flux.2") || haystack.contains("flux-2") || haystack.contains("klein") ->
                SdModelFamily.FLUX_2 to inferSdVariant(type, haystack)
            haystack.contains("chroma1-radiance") || haystack.contains("radiance") ->
                SdModelFamily.CHROMA_RADIANCE to inferSdVariant(type, haystack)
            haystack.contains("chroma") ->
                SdModelFamily.CHROMA to inferSdVariant(type, haystack)
            haystack.contains("qwen image edit") || haystack.contains("qwen-image-edit") ->
                SdModelFamily.QWEN_IMAGE_EDIT to inferSdVariant(type, haystack)
            haystack.contains("qwen image") || haystack.contains("qwen-image") ->
                SdModelFamily.QWEN_IMAGE to inferSdVariant(type, haystack)
            haystack.contains("z-image") || haystack.contains("z_image") ->
                SdModelFamily.Z_IMAGE to inferSdVariant(type, haystack)
            haystack.contains("ovis") ->
                SdModelFamily.OVIS_IMAGE to inferSdVariant(type, haystack)
            haystack.contains("anima") ->
                SdModelFamily.ANIMA to inferSdVariant(type, haystack)
            else -> null to null
        }
        else -> null to null
    }
}

private fun inferSdVariant(type: ModelType, haystack: String): String? = when (type) {
    ModelType.SD_CHECKPOINT -> when {
        haystack.contains("sdxl") -> "sdxl"
        haystack.contains("sd2") || haystack.contains("2.1") -> "sd2"
        haystack.contains("sd3.5-large") || haystack.contains("sd3.5_large") -> "sd3_5_large"
        haystack.contains("sd3") -> "sd3"
        else -> null
    }
    ModelType.SD_DIFFUSION -> when {
        haystack.contains("sd3.5-large") || haystack.contains("sd3.5_large") -> "sd3_5_large"
        haystack.contains("sd3.5") || haystack.contains("sd3_5") -> "sd3_5"
        haystack.contains("sd3-medium") || haystack.contains("sd3_medium") -> "sd3_medium"
        haystack.contains("sd3") -> "sd3"
        haystack.contains("schnell") -> "schnell"
        haystack.contains("dev") -> "dev"
        haystack.contains("2509") -> "2509"
        haystack.contains("2511") -> "2511"
        haystack.contains("turbo") -> "turbo"
        haystack.contains("base") -> "base"
        haystack.contains("klein-4b") || haystack.contains("klein 4b") -> "klein_4b"
        haystack.contains("klein-base-4b") || haystack.contains("klein base 4b") -> "klein_base_4b"
        haystack.contains("klein-9b") || haystack.contains("klein 9b") -> "klein_9b"
        haystack.contains("klein-base-9b") || haystack.contains("klein base 9b") -> "klein_base_9b"
        else -> null
    }
    else -> null
}

fun defaultCapabilitiesForFamily(
    family: SdModelFamily?,
    type: ModelType
): String? {
    if (type == ModelType.SD_UPSCALER ||
        type == ModelType.SD_CLIP_VISION ||
        type == ModelType.SD_IP_ADAPTER
    ) return null
    return when (family) {
        SdModelFamily.CHECKPOINT,
        SdModelFamily.SD3,
        SdModelFamily.FLUX_1,
        SdModelFamily.CHROMA,
        SdModelFamily.CHROMA_RADIANCE,
        SdModelFamily.QWEN_IMAGE,
        SdModelFamily.Z_IMAGE,
        SdModelFamily.OVIS_IMAGE,
        SdModelFamily.ANIMA -> buildSdCapabilities(SD_CAPABILITY_TXT2IMG)
        SdModelFamily.FLUX_KONTEXT,
        SdModelFamily.FLUX_2,
        SdModelFamily.QWEN_IMAGE_EDIT -> buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG)
        SdModelFamily.WAN,
        SdModelFamily.HUNYUAN_VIDEO,
        SdModelFamily.LINGBOT_VIDEO,
        SdModelFamily.LTX_VIDEO,
        SdModelFamily.MINIMAX_H3,
        SdModelFamily.SVD,
        SdModelFamily.ANIMATEDIFF -> buildSdCapabilities(SD_CAPABILITY_VID_GEN)
        null -> null
    }
}

fun resolveSdFamilySpec(
    family: SdModelFamily,
    variant: String? = null
): SdModelFamilySpec = when (family) {
    SdModelFamily.CHECKPOINT -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.UNET,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
        optionalRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.CONTROLNET,
            SdComponentRole.LORA
        ) + if (variant == "sdxl") setOf(SdComponentRole.PHOTOMAKER) else emptySet(),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true,
        supportsIpAdapter = variant == "sd1" || variant == "sdxl",
        supportsImgCfgScale = variant in setOf("instruct_pix2pix", "instruct-pix2pix", "pix2pix")
    )
    SdModelFamily.SD3 -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.CLIP_L,
            SdComponentRole.CLIP_G,
            SdComponentRole.T5XXL
        ),
        // Packaging is resolved separately.  VAE is an optional external
        // override for a full SD3 artifact and a required role for a
        // standalone SD3 artifact (see SdPipelineResolver).
        optionalRoles = setOf(SdComponentRole.VAE, SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.FLUX_1 -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.CLIP_L,
            SdComponentRole.T5XXL
        ),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.FLUX_KONTEXT -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.REFERENCE_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.CLIP_L,
            SdComponentRole.T5XXL
        ),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.FLUX_2 -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.REFERENCE_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.LLM
        ),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.CHROMA -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.T5XXL
        ),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsChromaDisableDitMask = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.CHROMA_RADIANCE -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(SdComponentRole.T5XXL),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsChromaDisableDitMask = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.QWEN_IMAGE -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.REFERENCE_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(SdComponentRole.LLM),
        optionalRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.TAE
        ),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsFlowShift = true
    )
    SdModelFamily.QWEN_IMAGE_EDIT -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.REFERENCE_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
        requiredRoles = setOf(SdComponentRole.LLM) + if (variant == "2509") setOf(SdComponentRole.LLM_VISION) else emptySet(),
        optionalRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.TAE
        ),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsFlowShift = true,
        supportsQwenImageZeroCondT = variant == "2511"
    )
    SdModelFamily.Z_IMAGE -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.LLM
        ),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true
    )
    SdModelFamily.OVIS_IMAGE -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.LLM
        ),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true
    )
    SdModelFamily.ANIMA -> SdModelFamilySpec(
        family = family,
        variant = variant,
        cacheArchitecture = SdCacheArchitecture.DIT,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.LLM
        ),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true
    )
    SdModelFamily.WAN -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.T5XXL),
        optionalRoles = setOf(
            SdComponentRole.VAE,
            SdComponentRole.TAE,
            SdComponentRole.CLIP_VISION,
            SdComponentRole.CONTROLNET,
            SdComponentRole.LORA
        ),
        supportsFlowShift = true
    )
    SdModelFamily.HUNYUAN_VIDEO -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.LLM, SdComponentRole.T5XXL),
        optionalRoles = setOf(SdComponentRole.VAE, SdComponentRole.LORA)
    )
    SdModelFamily.LINGBOT_VIDEO -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.LLM),
        optionalRoles = setOf(SdComponentRole.VAE, SdComponentRole.TAE, SdComponentRole.LORA)
    )
    SdModelFamily.LTX_VIDEO -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.LLM, SdComponentRole.VAE),
        optionalRoles = setOf(SdComponentRole.LORA)
    )
    SdModelFamily.MINIMAX_H3 -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.LLM, SdComponentRole.VAE),
        optionalRoles = setOf(SdComponentRole.LLM_VISION, SdComponentRole.LORA)
    )
    SdModelFamily.SVD -> videoFamilySpec(
        family = family,
        variant = variant,
        requiredRoles = setOf(SdComponentRole.VAE),
        optionalRoles = setOf(SdComponentRole.LORA)
    )
    SdModelFamily.ANIMATEDIFF -> videoFamilySpec(
        family = family,
        variant = variant,
        optionalRoles = setOf(SdComponentRole.VAE, SdComponentRole.LORA)
    )
}

private fun videoFamilySpec(
    family: SdModelFamily,
    variant: String?,
    requiredRoles: Set<SdComponentRole> = emptySet(),
    optionalRoles: Set<SdComponentRole> = emptySet(),
    supportsFlowShift: Boolean = false
): SdModelFamilySpec = SdModelFamilySpec(
    family = family,
    variant = variant,
    cacheArchitecture = SdCacheArchitecture.DIT,
    defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_VID_GEN),
    requiredRoles = requiredRoles,
    optionalRoles = optionalRoles,
    supportsFlowShift = supportsFlowShift,
    supportsDiffusionFa = true,
    supportsMmap = true,
    supportsVaeConvDirect = true,
    supportsLoraApplyMode = true
)
