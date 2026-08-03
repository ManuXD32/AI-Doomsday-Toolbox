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
    ANIMA("anima");

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
    val usesDiffusionModelFlag: Boolean,
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
    val supportsChromaDisableDitMask: Boolean = false
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
    if (explicitFamily != null) {
        return explicitFamily to sdVariantToken()
    }
    return inferSdFamily(type, repoId, filename)
}

fun ModelEntity.resolveSdFamilySpec(): SdModelFamilySpec? {
    val (family, variant) = resolvedSdFamily()
    return family?.let { resolveSdFamilySpec(it, variant) }
}

fun ModelEntity.sdCompatProfileTokens(): Set<String> = sdCompatProfiles.parseSdCompatProfiles()

fun ModelEntity.sdVariantToken(): String? = sdVariant?.trim()?.ifBlank { null }?.lowercase()

fun ModelEntity.effectiveSdCompatProfiles(): Set<String> {
    val explicit = sdCompatProfileTokens()
    if (explicit.isNotEmpty()) return explicit
    return defaultCompatProfilesFor(type)
}

fun ModelEntity.isSdImageSupportModel(): Boolean =
    type == ModelType.LLM || type == ModelType.VISION_PROJECTOR

fun ModelEntity.isSdImageMainModel(): Boolean {
    if (type != ModelType.SD_CHECKPOINT && type != ModelType.SD_DIFFUSION) {
        return false
    }
    if (hasSdCapability(SD_CAPABILITY_VID_GEN) && !hasSdCapability(SD_CAPABILITY_TXT2IMG) && !hasSdCapability(SD_CAPABILITY_IMG2IMG)) {
        return false
    }
    return true
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
        SdModelFamily.Z_IMAGE.storedValue,
        SdModelFamily.OVIS_IMAGE.storedValue,
        SdModelFamily.ANIMA.storedValue
    )
    ModelType.SD_TAE -> setOf(
        SdModelFamily.QWEN_IMAGE.storedValue,
        SdModelFamily.QWEN_IMAGE_EDIT.storedValue
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
        SdModelFamily.CHROMA_RADIANCE.storedValue
    )
    ModelType.SD_CONTROLNET -> setOf(SdModelFamily.CHECKPOINT.storedValue)
    ModelType.SD_LORA -> setOf(
        SdModelFamily.CHECKPOINT.storedValue,
        SdModelFamily.FLUX_1.storedValue,
        SdModelFamily.FLUX_KONTEXT.storedValue,
        SdModelFamily.FLUX_2.storedValue,
        SdModelFamily.CHROMA.storedValue,
        SdModelFamily.CHROMA_RADIANCE.storedValue,
        SdModelFamily.SD3.storedValue
    )
    ModelType.SD_TEXTUAL_INVERSION -> setOf(
        SdModelFamily.CHECKPOINT.storedValue
    )
    ModelType.SD_PHOTOMAKER -> setOf("${SdModelFamily.CHECKPOINT.storedValue}:sdxl")
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
            else -> SdModelFamily.CHECKPOINT to "sd1"
        }
        ModelType.SD_DIFFUSION -> when {
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
            else -> SdModelFamily.FLUX_1 to inferSdVariant(type, haystack)
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
        else -> "sd1"
    }
    ModelType.SD_DIFFUSION -> when {
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
    if (type == ModelType.SD_UPSCALER) return null
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
        usesDiffusionModelFlag = false,
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
        supportsLoraApplyMode = true
    )
    SdModelFamily.SD3 -> SdModelFamilySpec(
        family = family,
        variant = variant,
        usesDiffusionModelFlag = false,
        cacheArchitecture = SdCacheArchitecture.DIT,
        img2imgInputMode = SdImageInputMode.INIT_IMAGE,
        defaultCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
        requiredRoles = setOf(
            SdComponentRole.CLIP_L,
            SdComponentRole.CLIP_G,
            SdComponentRole.T5XXL
        ),
        optionalRoles = setOf(SdComponentRole.LORA),
        supportsMmap = true,
        supportsDiffusionFa = true,
        supportsVaeConvDirect = true,
        supportsLoraApplyMode = true
    )
    SdModelFamily.FLUX_1 -> SdModelFamilySpec(
        family = family,
        variant = variant,
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
        usesDiffusionModelFlag = true,
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
}
