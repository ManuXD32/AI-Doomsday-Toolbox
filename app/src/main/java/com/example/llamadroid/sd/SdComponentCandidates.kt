package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.parseSdCapabilities
import com.example.llamadroid.data.db.sdCapabilityTokens
import java.util.Locale

/** How well an installed file is known to fit the selected main model. */
enum class SdComponentFit { MATCH, UNVERIFIED }

data class SdComponentCandidate(val model: ModelEntity, val fit: SdComponentFit)

private val SD_MAIN_TYPES = setOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION)

/** One table: every ModelType that may fill an image role. LLM = LLM+SD_LLM, LLM_VISION = VISION_PROJECTOR+MMPROJ. */
fun sdRoleModelTypes(role: SdComponentRole): Set<ModelType> = when (role) {
    SdComponentRole.MAIN_MODEL -> SD_MAIN_TYPES
    SdComponentRole.VAE -> setOf(ModelType.SD_VAE)
    SdComponentRole.TAE -> setOf(ModelType.SD_TAE)
    SdComponentRole.CLIP_L -> setOf(ModelType.SD_CLIP_L)
    SdComponentRole.CLIP_G -> setOf(ModelType.SD_CLIP_G)
    SdComponentRole.T5XXL -> setOf(ModelType.SD_T5XXL)
    SdComponentRole.LLM -> setOf(ModelType.LLM, ModelType.SD_LLM)
    SdComponentRole.LLM_VISION -> setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
    SdComponentRole.CONTROLNET -> setOf(ModelType.SD_CONTROLNET)
    SdComponentRole.LORA -> setOf(ModelType.SD_LORA)
    SdComponentRole.PHOTOMAKER -> setOf(ModelType.SD_PHOTOMAKER)
    SdComponentRole.CLIP_VISION -> setOf(ModelType.SD_CLIP_VISION)
    SdComponentRole.IP_ADAPTER -> setOf(ModelType.SD_IP_ADAPTER)
    SdComponentRole.UPSCALER -> setOf(ModelType.SD_UPSCALER)
}

/** Same for video roles (DIFFUSION/FULL/HIGH_NOISE = SD_DIFFUSION+SD_CHECKPOINT filtered by isSdVideoMainModel()). */
fun sdVideoRoleModelTypes(role: SdVideoComponentRole): Set<ModelType> = when (role) {
    SdVideoComponentRole.DIFFUSION_MODEL,
    SdVideoComponentRole.FULL_MODEL,
    SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> SD_MAIN_TYPES
    SdVideoComponentRole.VAE -> setOf(ModelType.SD_VAE)
    SdVideoComponentRole.TAE -> setOf(ModelType.SD_TAE)
    SdVideoComponentRole.T5XXL -> setOf(ModelType.SD_T5XXL)
    SdVideoComponentRole.LLM -> setOf(ModelType.LLM, ModelType.SD_LLM)
    SdVideoComponentRole.LLM_VISION -> setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
    SdVideoComponentRole.AUDIO_VAE -> setOf(ModelType.SD_AUDIO_VAE)
    SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> setOf(ModelType.SD_EMBEDDINGS_CONNECTORS)
    SdVideoComponentRole.MOTION_MODULE -> setOf(ModelType.SD_MOTION_MODULE)
    SdVideoComponentRole.CLIP_VISION -> setOf(ModelType.SD_CLIP_VISION)
    SdVideoComponentRole.CONTROL_NET -> setOf(ModelType.SD_CONTROLNET)
    SdVideoComponentRole.LORA -> setOf(ModelType.SD_LORA)
    SdVideoComponentRole.HIRES_UPSCALER -> setOf(ModelType.SD_UPSCALER)
}

private fun isQwen21(family: SdModelFamily?, variant: String?): Boolean =
    family == SdModelFamily.QWEN_IMAGE &&
        variant?.trim()?.lowercase() in setOf("2.1", "qwen_image_2.1", "qwen-image-2.1")

private fun isSameFile(a: ModelEntity, b: ModelEntity?): Boolean =
    b != null && (a.filename == b.filename || (a.path.isNotBlank() && a.path == b.path))

/** MATCH rows (optionally pre-ordered) first, then UNVERIFIED rows by filename. */
private fun assembleCandidates(
    pool: List<ModelEntity>,
    matches: (ModelEntity) -> Boolean,
    orderMatches: (List<ModelEntity>) -> List<ModelEntity>
): List<SdComponentCandidate> {
    val (matched, other) = pool.partition(matches)
    return orderMatches(matched.sortedBy { it.filename.lowercase(Locale.US) })
        .map { SdComponentCandidate(it, SdComponentFit.MATCH) } +
        other.sortedBy { it.filename.lowercase(Locale.US) }
            .map { SdComponentCandidate(it, SdComponentFit.UNVERIFIED) }
}

/**
 * Every installed file of the role's types. MATCH = matchesSdFamily(family, variant); others UNVERIFIED.
 * MATCH first (Qwen 2.1 LLM/LLM_VISION keep orderQwenImage21* order), then UNVERIFIED by filename. Never hides a file;
 * main == null or unknown family -> all UNVERIFIED. Excludes the main file itself.
 */
fun sdComponentCandidates(
    main: ModelEntity?,
    installed: List<ModelEntity>,
    role: SdComponentRole
): List<SdComponentCandidate> {
    val types = sdRoleModelTypes(role)
    val pool = installed.filter { it.type in types && !isSameFile(it, main) }
    val (family, variant) = main?.resolvedSdFamily() ?: (null to null)
    val qwen21 = isQwen21(family, variant)
    return assembleCandidates(
        pool = pool,
        matches = { family != null && it.matchesSdFamily(family, variant) },
        orderMatches = {
            when {
                qwen21 && role == SdComponentRole.LLM -> orderQwenImage21TextEncoders(it)
                qwen21 && role == SdComponentRole.LLM_VISION -> orderQwenImage21VisionProjectors(it)
                else -> it
            }
        }
    )
}

/** Video: MATCH = matchesSdVideoFamily(family, variant) of main.resolvedSdVideoFamily(). Same ordering rules. */
fun sdVideoComponentCandidates(
    main: ModelEntity?,
    installed: List<ModelEntity>,
    role: SdVideoComponentRole
): List<SdComponentCandidate> {
    val types = sdVideoRoleModelTypes(role)
    val isMainRole = types == SD_MAIN_TYPES
    val pool = installed.filter {
        it.type in types && !isSameFile(it, main) && (!isMainRole || it.isSdVideoMainModel())
    }
    val (family, variant) = main?.resolvedSdVideoFamily() ?: (null to null)
    return assembleCandidates(
        pool = pool,
        matches = { family != null && it.matchesSdVideoFamily(family, variant) },
        orderMatches = { it }
    )
}

/**
 * Roles the main file already contains (inspection containsVae/ClipL/ClipG/T5xxl/Llm; an uninspected
 * SD_CHECKPOINT full model counts as VAE+CLIP_L(+CLIP_G for sdxl) like ImageGenScreen.kt:705).
 */
fun sdMainEmbeddedRoles(main: ModelEntity?): Set<SdComponentRole> {
    if (main == null) return emptySet()
    val inspection = main.sdArtifactInspection()
    if (inspection != null && inspection.isInspected) {
        return buildSet {
            if (inspection.containsVae) add(SdComponentRole.VAE)
            if (inspection.containsClipL) add(SdComponentRole.CLIP_L)
            if (inspection.containsClipG) add(SdComponentRole.CLIP_G)
            if (inspection.containsT5xxl) add(SdComponentRole.T5XXL)
            if (inspection.containsLlm) add(SdComponentRole.LLM)
        }
    }
    if (main.type != ModelType.SD_CHECKPOINT) return emptySet()
    val variant = main.resolvedSdFamily().second?.lowercase()
    return buildSet {
        add(SdComponentRole.VAE)
        add(SdComponentRole.CLIP_L)
        if (variant?.startsWith("sdxl") == true) add(SdComponentRole.CLIP_G)
    }
}

enum class SdImg2ImgBlock { NOT_SUPPORTED, NEEDS_VISION_PROJECTOR }

/**
 * null = img2img allowed. NOT_SUPPORTED when neither the model's capabilities nor spec.defaultCapabilities contain img2img;
 * NEEDS_VISION_PROJECTOR when spec.requiresVisionForImg2Img and llmVisionPath is blank.
 */
fun sdImg2ImgBlock(main: ModelEntity?, llmVisionPath: String): SdImg2ImgBlock? {
    if (main == null) return null
    val spec = main.resolveSdFamilySpec()
    val modelCaps = main.sdCapabilityTokens()
    val specCaps = spec?.defaultCapabilities.parseSdCapabilities()
    // No capability evidence at all (unknown family, never tagged): do not block.
    if (modelCaps.isEmpty() && specCaps.isEmpty()) return null
    if (SD_CAPABILITY_IMG2IMG !in modelCaps && SD_CAPABILITY_IMG2IMG !in specCaps) {
        return SdImg2ImgBlock.NOT_SUPPORTED
    }
    if (spec?.requiresVisionForImg2Img == true && llmVisionPath.isBlank()) {
        return SdImg2ImgBlock.NEEDS_VISION_PROJECTOR
    }
    return null
}

/**
 * required roles - embedded - filled; plus LLM_VISION when img2img && spec.requiresVisionForImg2Img.
 * [paths] = stored path per role ("" = unset).
 */
fun sdEffectiveMissingRoles(
    main: ModelEntity?,
    paths: Map<SdComponentRole, String>,
    img2img: Boolean
): List<SdComponentRole> {
    val spec = main?.resolveSdFamilySpec() ?: return emptyList()
    val embedded = sdMainEmbeddedRoles(main)
    fun filled(role: SdComponentRole) = !paths[role].isNullOrBlank()
    val missing = spec.requiredRoles
        .filter { it != SdComponentRole.MAIN_MODEL && it !in embedded && !filled(it) }
        .toMutableList()
    if (img2img && spec.requiresVisionForImg2Img &&
        SdComponentRole.LLM_VISION !in missing && !filled(SdComponentRole.LLM_VISION)
    ) {
        missing += SdComponentRole.LLM_VISION
    }
    return missing
}
