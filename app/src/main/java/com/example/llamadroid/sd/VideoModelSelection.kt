package com.example.llamadroid.sd

import androidx.annotation.StringRes
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedBundleFile
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.service.VideoRuntimeOptions
import java.util.Locale

/**
 * Result of picking one video main model: which family it is and which companion files
 * (VAE, text encoder, ...) go with it. Pure data so every screen resolves it the same way.
 */
data class VideoModelSelection(
    val mainPath: String,
    val family: SdVideoFamily?,
    val variant: String?,
    val components: SdVideoComponentPaths,
    val missingRoles: List<SdVideoComponentRole>,
    val bundleId: String?,
    /**
     * Roles filled from the first of several installed candidates. [withSelection] keeps the
     * user's current value for these roles when one is already set.
     */
    val ambiguousRoles: Set<SdVideoComponentRole> = emptySet()
)

/** One tile in the video model picker: a catalog bundle or an imported video model. */
data class VideoPresetCard(
    val bundleId: String?,
    @StringRes val titleRes: Int?,
    val title: String,
    val mainPath: String?,
    val installed: Boolean,
    val missingFiles: Int
)

private fun SdCuratedBundleFile.isMainFile(): Boolean =
    modelType == ModelType.SD_DIFFUSION || modelType == ModelType.SD_CHECKPOINT

private fun SdCuratedBundleFile.isVideoMain(): Boolean =
    isMainFile() && sdCapabilities?.contains(SD_CAPABILITY_VID_GEN) == true &&
        sdCapabilities.contains(SD_CAPABILITY_TXT2IMG) != true

private fun SdCuratedBundle.videoMainFile(): SdCuratedBundleFile? = files.firstOrNull { it.isVideoMain() }

private fun catalogVideoBundles(): List<SdCuratedBundle> =
    SdCuratedBundleCatalog.bundles.filter { it.videoMainFile() != null }

private fun SdCuratedBundleFile.videoRole(): SdVideoComponentRole? = when (componentRole?.lowercase(Locale.US)) {
    "diffusion" -> SdVideoComponentRole.DIFFUSION_MODEL
    "llm" -> SdVideoComponentRole.LLM
    "llm_vision" -> SdVideoComponentRole.LLM_VISION
    "vae" -> SdVideoComponentRole.VAE
    "tae" -> SdVideoComponentRole.TAE
    "t5xxl" -> SdVideoComponentRole.T5XXL
    else -> when (modelType) {
        ModelType.SD_DIFFUSION, ModelType.SD_CHECKPOINT -> SdVideoComponentRole.DIFFUSION_MODEL
        ModelType.SD_VAE -> SdVideoComponentRole.VAE
        ModelType.SD_TAE -> SdVideoComponentRole.TAE
        ModelType.SD_T5XXL -> SdVideoComponentRole.T5XXL
        ModelType.LLM, ModelType.SD_LLM -> SdVideoComponentRole.LLM
        ModelType.VISION_PROJECTOR, ModelType.MMPROJ -> SdVideoComponentRole.LLM_VISION
        else -> null
    }
}

private fun roleTypes(role: SdVideoComponentRole): Set<ModelType> = when (role) {
    SdVideoComponentRole.VAE -> setOf(ModelType.SD_VAE)
    SdVideoComponentRole.TAE -> setOf(ModelType.SD_TAE)
    SdVideoComponentRole.T5XXL -> setOf(ModelType.SD_T5XXL)
    SdVideoComponentRole.LLM -> setOf(ModelType.LLM, ModelType.SD_LLM)
    SdVideoComponentRole.LLM_VISION -> setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
    else -> emptySet()
}

private fun SdVideoComponentPaths.withRole(role: SdVideoComponentRole, path: String?): SdVideoComponentPaths =
    when (role) {
        SdVideoComponentRole.DIFFUSION_MODEL -> copy(diffusionModelPath = path)
        SdVideoComponentRole.FULL_MODEL -> copy(fullModelPath = path)
        SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> copy(highNoiseDiffusionModelPath = path)
        SdVideoComponentRole.VAE -> copy(vaePath = path)
        SdVideoComponentRole.TAE -> copy(taePath = path)
        SdVideoComponentRole.T5XXL -> copy(t5xxlPath = path)
        SdVideoComponentRole.LLM -> copy(llmPath = path)
        SdVideoComponentRole.LLM_VISION -> copy(llmVisionPath = path)
        SdVideoComponentRole.AUDIO_VAE -> copy(audioVaePath = path)
        SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> copy(embeddingsConnectorsPath = path)
        SdVideoComponentRole.MOTION_MODULE -> copy(motionModulePath = path)
        SdVideoComponentRole.CLIP_VISION -> copy(clipVisionPath = path)
        SdVideoComponentRole.CONTROL_NET -> copy(controlNetPath = path)
        SdVideoComponentRole.LORA, SdVideoComponentRole.HIRES_UPSCALER -> this
    }

/**
 * Resolves what a picked video main model implies. Curated bundle members get the bundle's
 * companion files; imported models get one installed, family-compatible candidate per required
 * role. Returns null only for a blank [mainPath].
 */
fun resolveVideoModelSelection(mainPath: String, installed: List<ModelEntity>): VideoModelSelection? =
    resolveVideoModelSelection(mainPath, installed, ::installedSdCuratedModelLookup)

private fun installedSdCuratedModelLookup(
    bundle: SdCuratedBundle,
    file: SdCuratedBundleFile,
    installed: List<ModelEntity>
): ModelEntity? = file.installedSdCuratedModel(bundle, installed)

/** Family implied by a catalog video bundle when the installed entity carries no family. */
private fun SdCuratedBundle.impliedVideoFamily(): SdVideoFamily? =
    SdVideoFamily.fromStoredValue(videoMainFile()?.sdFamily) ?: when (id) {
        "local-video" -> SdVideoFamily.WAN
        "lingbot-phone" -> SdVideoFamily.LINGBOT_VIDEO
        else -> null
    }

/** Test seam: [installedLookup] decides which installed entity satisfies a bundle file. */
internal fun resolveVideoModelSelection(
    mainPath: String,
    installed: List<ModelEntity>,
    installedLookup: (SdCuratedBundle, SdCuratedBundleFile, List<ModelEntity>) -> ModelEntity?
): VideoModelSelection? {
    if (mainPath.isBlank()) return null
    val mainModel = installed.firstOrNull { it.path == mainPath }
    val (family, variant) = mainModel?.resolvedSdVideoFamily() ?: (null to null)

    for (bundle in catalogVideoBundles()) {
        val main = bundle.videoMainFile() ?: continue
        if (installedLookup(bundle, main, installed)?.path != mainPath) continue
        val bundleFamily = family ?: bundle.impliedVideoFamily()
        val bundleVariant = if (family == null) main.sdVariant else variant
        var components = SdVideoComponentPaths()
        val missing = mutableListOf<SdVideoComponentRole>()
        for (file in bundle.files) {
            val role = file.videoRole() ?: continue
            val model = installedLookup(bundle, file, installed)
            if (model == null) missing += role else components = components.withRole(role, model.path)
        }
        if (bundle.id == SdCuratedPresets.LingBotPhone.BUNDLE_ID) components = components.copy(vaePath = null)
        return VideoModelSelection(mainPath, bundleFamily, bundleVariant, components, missing.distinct(), bundle.id)
    }

    if (family == null) {
        return VideoModelSelection(mainPath, null, null, SdVideoComponentPaths(), emptyList(), null)
    }
    val profile = SdVideoFamilyProfiles.resolve(family, variant)
    var components = if (profile.mainModelLayout == SdVideoMainModelLayout.FULL_MODEL) {
        SdVideoComponentPaths(fullModelPath = mainPath)
    } else {
        SdVideoComponentPaths(diffusionModelPath = mainPath)
    }
    val ambiguous = mutableSetOf<SdVideoComponentRole>()
    val missing = mutableListOf<SdVideoComponentRole>()
    for (group in profile.requiredComponentGroups) {
        if (SdVideoComponentRole.DIFFUSION_MODEL in group || SdVideoComponentRole.FULL_MODEL in group) continue
        // Alternatives (VAE or TAE): fill the first role that has a candidate, never both.
        val candidatesByRole = group.sortedBy { it.ordinal }.associateWith { role ->
            val types = roleTypes(role)
            installed.filter { it.path != mainPath && it.type in types && it.matchesSdVideoFamily(family, variant) }
                .sortedBy { it.filename.lowercase(Locale.US) }
        }
        val chosen = candidatesByRole.entries.firstOrNull { it.value.isNotEmpty() }
        if (chosen == null) {
            missing += group.minBy { it.ordinal }
            continue
        }
        components = components.withRole(chosen.key, chosen.value.first().path)
        if (candidatesByRole.values.sumOf { it.size } > 1) ambiguous += chosen.key
    }
    return VideoModelSelection(mainPath, family, variant, components, missing, null, ambiguous)
}

private val selectionRoles = listOf(
    SdVideoComponentRole.DIFFUSION_MODEL, SdVideoComponentRole.FULL_MODEL,
    SdVideoComponentRole.VAE, SdVideoComponentRole.TAE, SdVideoComponentRole.T5XXL,
    SdVideoComponentRole.LLM, SdVideoComponentRole.LLM_VISION
)

/**
 * Applies [selection] to the runtime options. Returns `this` when nothing changes. The LingBot
 * Phone bundle also applies the full preset ([SdCuratedPresets.lingBotRuntimeOptions]): workflow,
 * inputs, TAE use, seed and prompt format. Scalar defaults of that preset (frames, fps, size,
 * steps, cfg, flow shift, threads, mmap, flash attention, sampler, prompts) are not part of
 * [VideoRuntimeOptions] and stay the caller's job.
 */
fun VideoRuntimeOptions.withSelection(selection: VideoModelSelection): VideoRuntimeOptions {
    val next = when {
        selection.family == null && selection.bundleId == null -> copy(videoFamily = null, videoVariant = null)
        selection.bundleId == SdCuratedPresets.LingBotPhone.BUNDLE_ID &&
            selection.family == SdVideoFamily.LINGBOT_VIDEO ->
            SdCuratedPresets.lingBotRuntimeOptions(
                videoComponents.mergedWith(selection, replaceAll = true),
                this
            )
        else -> copy(
            videoFamily = selection.family,
            videoVariant = selection.variant,
            videoComponents = videoComponents.mergedWith(selection, replaceAll = selection.bundleId != null)
        )
    }
    return if (next == this) this else next
}

private fun SdVideoComponentPaths.mergedWith(
    selection: VideoModelSelection,
    replaceAll: Boolean
): SdVideoComponentPaths {
    var result = this
    for (role in selectionRoles) {
        val picked = selection.components.pathFor(role)
        val current = pathFor(role)
        val value = when {
            role in selection.ambiguousRoles && !current.isNullOrBlank() -> current
            picked != null -> picked
            replaceAll -> null
            else -> current
        }
        result = result.withRole(role, value)
    }
    return result
}

/** Catalog video bundles first, then every other installed video main model. */
fun videoPresetCards(installed: List<ModelEntity>): List<VideoPresetCard> =
    videoPresetCards(installed, ::installedSdCuratedModelLookup)

/** Test seam, see [resolveVideoModelSelection]. */
internal fun videoPresetCards(
    installed: List<ModelEntity>,
    installedLookup: (SdCuratedBundle, SdCuratedBundleFile, List<ModelEntity>) -> ModelEntity?
): List<VideoPresetCard> {
    val bundleCards = catalogVideoBundles().map { bundle ->
        val missing = bundle.files.count { installedLookup(bundle, it, installed) == null }
        VideoPresetCard(
            bundleId = bundle.id,
            titleRes = bundle.titleRes,
            title = bundle.id,
            mainPath = bundle.videoMainFile()?.let { installedLookup(bundle, it, installed) }?.path,
            installed = missing == 0,
            missingFiles = missing
        )
    }
    val bundleMains = bundleCards.mapNotNull { it.mainPath }.toSet()
    val others = installed
        .filter { it.isSdVideoMainModel() && it.path !in bundleMains }
        .sortedBy { it.filename.lowercase(Locale.US) }
        .map { model ->
            VideoPresetCard(null, null, model.filename, model.path, installed = true, missingFiles = 0)
        }
    return bundleCards + others
}
