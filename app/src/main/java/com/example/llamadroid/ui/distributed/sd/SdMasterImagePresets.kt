package com.example.llamadroid.ui.distributed.sd

import androidx.annotation.StringRes
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.easy.EasySdSlot
import com.example.llamadroid.data.easy.easyLocalImageModelId
import com.example.llamadroid.data.easy.easySdSlotFor
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedBundleFile
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.sd.SdBundleGenerationDefaults
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdImageDefaults
import com.example.llamadroid.sd.SdImg2ImgBlock
import com.example.llamadroid.sd.sdComponentCandidates
import com.example.llamadroid.sd.sdRoleModelTypes
import com.example.llamadroid.sd.sdEffectiveMissingRoles
import com.example.llamadroid.sd.sdImg2ImgBlock
import com.example.llamadroid.sd.sdMainEmbeddedRoles
import com.example.llamadroid.sd.withImageDefaults
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.service.imageLoras
import com.example.llamadroid.service.withImageLoras
import com.example.llamadroid.ui.easy.resolveEasyImageSelection
import java.util.Locale

/** One tile in the image model picker: a catalog bundle or an imported image model. */
internal data class SdMasterImagePresetCard(
    val bundleId: String?,
    @StringRes val titleRes: Int?,
    val title: String,
    val mainPath: String?,
    val installed: Boolean,
    val missingFiles: Int
)

/** What picking one image main model implies: the model and the companion file per launch slot. */
internal data class SdMasterImageSelection(
    val mainPath: String,
    val bundleId: String?,
    val components: Map<EasySdSlot, String>,
    val missingFiles: Int
)

/** Test seam: decides which installed entity satisfies a bundle file. */
internal typealias SdBundleFileLookup = (SdCuratedBundle, SdCuratedBundleFile, List<ModelEntity>) -> ModelEntity?

private val installedLookup: SdBundleFileLookup = { bundle, file, installed ->
    file.installedSdCuratedModel(bundle, installed)
}

private fun SdCuratedBundleFile.isImageMain(): Boolean =
    (modelType == ModelType.SD_DIFFUSION || modelType == ModelType.SD_CHECKPOINT) &&
        sdCapabilities?.contains(SD_CAPABILITY_TXT2IMG) == true

private fun SdCuratedBundle.imageMainFile(): SdCuratedBundleFile? = files.firstOrNull { it.isImageMain() }

/** Bundles whose every file maps to an image launch slot (or is a LoRA); adapter-only starters are left out. */
private fun catalogImageBundles(): List<SdCuratedBundle> =
    SdCuratedBundleCatalog.bundles.filter { bundle ->
        bundle.imageMainFile() != null &&
            bundle.files.all { easySdSlotFor(it.modelType) != null || it.modelType == ModelType.SD_LORA }
    }

/** Catalog image bundles first, then every other installed image main model. */
internal fun sdMasterImagePresetCards(installed: List<ModelEntity>): List<SdMasterImagePresetCard> =
    sdMasterImagePresetCards(installed, installedLookup)

internal fun sdMasterImagePresetCards(
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup
): List<SdMasterImagePresetCard> {
    val bundleCards = catalogImageBundles().map { bundle ->
        val missing = bundle.files.count { lookup(bundle, it, installed) == null }
        SdMasterImagePresetCard(
            bundleId = bundle.id,
            titleRes = bundle.titleRes,
            title = bundle.id,
            mainPath = bundle.imageMainFile()?.let { lookup(bundle, it, installed) }?.path,
            installed = missing == 0,
            missingFiles = missing
        )
    }
    val bundleMains = bundleCards.mapNotNull { it.mainPath }.toSet()
    val others = installed
        .filter { it.isSdImageMainModel() && it.path !in bundleMains }
        .sortedBy { it.filename.lowercase(Locale.US) }
        .map { SdMasterImagePresetCard(null, null, it.filename, it.path, installed = true, missingFiles = 0) }
    return bundleCards + others
}

/**
 * Resolves the components for [mainPath]. Bundle members get the bundle's installed companions;
 * imported models reuse Easy mode's family-compatible pick. Null for a blank or unknown path.
 */
internal fun resolveSdMasterImageSelection(
    mainPath: String,
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup = installedLookup
): SdMasterImageSelection? {
    if (mainPath.isBlank()) return null
    for (bundle in catalogImageBundles()) {
        val main = bundle.imageMainFile() ?: continue
        if (lookup(bundle, main, installed)?.path != mainPath) continue
        val components = linkedMapOf<EasySdSlot, String>()
        var missing = 0
        for (file in bundle.files) {
            val found = lookup(bundle, file, installed)
            if (found == null) missing++
            val slot = easySdSlotFor(file.modelType)
            if (found != null && slot != null && slot != EasySdSlot.MAIN) components.putIfAbsent(slot, found.path)
        }
        return SdMasterImageSelection(mainPath, bundle.id, components, missing)
    }
    val model = installed.firstOrNull { it.path == mainPath } ?: return null
    val easy = resolveEasyImageSelection(easyLocalImageModelId(model.filename), installed)
    val components = easy?.components.orEmpty().mapValues { it.value.path }
    return SdMasterImageSelection(mainPath, null, components, 0)
}

/** Writes [selection] into the image slots; slots the selection does not fill are cleared. */
internal fun SdDistributedMasterSettingsEntity.withImageSelection(
    selection: SdMasterImageSelection
): SdDistributedMasterSettingsEntity {
    val next = copy(
        imageModelPath = selection.mainPath,
        imageVaePath = selection.components[EasySdSlot.VAE].orEmpty(),
        imageTaePath = selection.components[EasySdSlot.TAE].orEmpty(),
        imageClipLPath = selection.components[EasySdSlot.CLIP_L].orEmpty(),
        imageClipGPath = selection.components[EasySdSlot.CLIP_G].orEmpty(),
        imageT5xxlPath = selection.components[EasySdSlot.T5XXL].orEmpty(),
        imageLlmPath = selection.components[EasySdSlot.LLM].orEmpty(),
        imageLlmVisionPath = selection.components[EasySdSlot.LLM_VISION].orEmpty()
    )
    return if (next == this) this else next
}

/** Applies an installed [card]: main model plus every component slot. Returns `this` when nothing changes. */
internal fun applyImagePreset(
    settings: SdDistributedMasterSettingsEntity,
    card: SdMasterImagePresetCard,
    installed: List<ModelEntity>
): SdDistributedMasterSettingsEntity = applyImagePreset(settings, card, installed, installedLookup)

internal fun applyImagePreset(
    settings: SdDistributedMasterSettingsEntity,
    card: SdMasterImagePresetCard,
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup
): SdDistributedMasterSettingsEntity {
    val path = card.mainPath?.takeIf { card.installed } ?: return settings
    if (resolveSdMasterImageSelection(path, installed, lookup) == null) return settings
    return settings.withImageMainModel(path, card.bundleId, installed, lookup)
}

private fun curatedFileFor(fileId: String): Pair<SdCuratedBundle, SdCuratedBundleFile>? =
    SdCuratedBundleCatalog.bundles.firstNotNullOfOrNull { bundle ->
        bundle.files.firstOrNull { it.id == fileId }?.let { bundle to it }
    }

/** Drops the installed curated LCM LoRA from the stack (other LoRAs stay); disables LoRA when the stack empties. */
private fun SdDistributedMasterSettingsEntity.withoutCuratedLcmLora(
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup
): SdDistributedMasterSettingsEntity {
    val (bundle, file) = curatedFileFor(SdBundleGenerationDefaults.LCM_LORA_FILE_ID) ?: return this
    val lcmPath = lookup(bundle, file, installed)?.path ?: return this
    val stack = imageLoras()
    val kept = stack.filter { it.path != lcmPath }
    if (kept.size == stack.size) return this
    val next = withImageLoras(kept)
    return if (kept.isEmpty()) next.copy(imageLoraEnabled = false) else next
}

/** Per-model numbers plus, for LCM-style presets, the installed LoRA at its strength (untouched when absent). */
private fun SdDistributedMasterSettingsEntity.withModelDefaults(
    d: SdImageDefaults,
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup
): SdDistributedMasterSettingsEntity {
    val base = withImageDefaults(d)
    val loraId = d.loraFileId ?: return base.withoutCuratedLcmLora(installed, lookup)
    val (bundle, file) = curatedFileFor(loraId) ?: return base
    val lora = lookup(bundle, file, installed) ?: return base
    return base.withImageLoras(listOf(SdLoraSpec(path = lora.path, strength = d.loraStrength)))
}

/**
 * The one place a main-model change is applied (picker, preset card, Add main file): components,
 * per-model defaults, LCM LoRA and the img2img gate. Defaults are written only here, so later user
 * edits are never overwritten. [bundleId] (the tapped card) wins over the bundle that owns the file.
 */
internal fun SdDistributedMasterSettingsEntity.withImageMainModel(
    path: String,
    bundleId: String?,
    installed: List<ModelEntity>,
    lookup: SdBundleFileLookup = installedLookup
): SdDistributedMasterSettingsEntity {
    val selection = resolveSdMasterImageSelection(path, installed, lookup)
    var next = selection?.let { withImageSelection(it) } ?: copy(imageModelPath = path)
    val main = installed.firstOrNull { it.path == path }
    val defaults = SdBundleGenerationDefaults.forBundle(bundleId ?: selection?.bundleId)
        ?: SdBundleGenerationDefaults.forMain(main)
    next = if (defaults != null) next.withModelDefaults(defaults, installed, lookup)
    else next.withoutCuratedLcmLora(installed, lookup)
    if (next.imageWorkflowMode.equals(MODE_IMG2IMG, ignoreCase = true) &&
        sdImg2ImgBlock(main, "") == SdImg2ImgBlock.NOT_SUPPORTED
    ) {
        next = next.copy(imageWorkflowMode = MODE_TXT2IMG)
    }
    return if (next == this) this else next
}

private const val MODE_TXT2IMG = "TXT2IMG"
private const val MODE_IMG2IMG = "IMG2IMG"

/** Model type used when importing a file for [role] (curated bundles register SD_LLM and MMPROJ). */
internal fun sdImportTypeFor(role: SdComponentRole): ModelType = when (role) {
    SdComponentRole.LLM -> ModelType.SD_LLM
    SdComponentRole.LLM_VISION -> ModelType.MMPROJ
    else -> sdRoleModelTypes(role).first()
}

/** Stored path of one image component slot ("" when unset). */
internal fun SdDistributedMasterSettingsEntity.imageComponentPath(role: SdComponentRole): String = when (role) {
    SdComponentRole.VAE -> imageVaePath
    SdComponentRole.TAE -> imageTaePath
    SdComponentRole.CLIP_L -> imageClipLPath
    SdComponentRole.CLIP_G -> imageClipGPath
    SdComponentRole.T5XXL -> imageT5xxlPath
    SdComponentRole.LLM -> imageLlmPath
    SdComponentRole.LLM_VISION -> imageLlmVisionPath
    SdComponentRole.PHOTOMAKER -> imagePhotoMakerPath
    SdComponentRole.CONTROLNET -> imageControlNetPath
    else -> ""
}

/** Sets one image component slot; LoRA paths go through the stack helper. */
internal fun SdDistributedMasterSettingsEntity.withImageComponentPath(
    role: SdComponentRole,
    path: String
): SdDistributedMasterSettingsEntity = when (role) {
    SdComponentRole.VAE -> copy(imageVaePath = path)
    SdComponentRole.TAE -> copy(imageTaePath = path)
    SdComponentRole.CLIP_L -> copy(imageClipLPath = path)
    SdComponentRole.CLIP_G -> copy(imageClipGPath = path)
    SdComponentRole.T5XXL -> copy(imageT5xxlPath = path)
    SdComponentRole.LLM -> copy(imageLlmPath = path)
    SdComponentRole.LLM_VISION -> copy(imageLlmVisionPath = path)
    SdComponentRole.PHOTOMAKER -> copy(imagePhotoMakerPath = path)
    SdComponentRole.CONTROLNET -> copy(imageControlNetPath = path)
    SdComponentRole.LORA -> {
        val stack = imageLoras()
        withImageLoras(
            if (stack.isEmpty()) listOf(SdLoraSpec(path = path))
            else stack.mapIndexed { index, item -> if (index == 0) item.copy(path = path) else item }
        )
    }
    else -> this
}

private fun SdDistributedMasterSettingsEntity.selectedImageMain(installed: List<ModelEntity>): ModelEntity? =
    installed.firstOrNull { it.path == imageModelPath }

/** Required roles still without a file: required - embedded in the main file - filled (+ projector for gated img2img). */
internal fun sdMasterImageMissingRoles(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>
): List<SdComponentRole> = sdEffectiveMissingRoles(
    main = settings.selectedImageMain(installed),
    paths = SdComponentRole.entries.associateWith { settings.imageComponentPath(it) },
    img2img = settings.imageWorkflowMode.equals(MODE_IMG2IMG, ignoreCase = true)
)

/** Why img2img cannot run with the selected model (null = allowed). */
internal fun sdMasterImageImg2ImgBlock(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>
): SdImg2ImgBlock? = sdImg2ImgBlock(settings.selectedImageMain(installed), settings.imageLlmVisionPath)

private val imageComponentRoleOrder = listOf(
    SdComponentRole.VAE, SdComponentRole.TAE, SdComponentRole.CLIP_L, SdComponentRole.CLIP_G,
    SdComponentRole.T5XXL, SdComponentRole.LLM, SdComponentRole.LLM_VISION, SdComponentRole.PHOTOMAKER
)

/** Pickers data for the Advanced "model files" section: every installed file per role, MATCH first. */
internal fun sdMasterImageComponentChoices(
    settings: SdDistributedMasterSettingsEntity,
    installed: List<ModelEntity>
): SdDistributedImageComponentChoices {
    val main = installed.firstOrNull { it.path == settings.imageModelPath && it.isSdImageMainModel() }
    val family = main?.resolvedSdFamily()
    val spec = main?.resolveSdFamilySpec()
    val selected = (imageComponentRoleOrder + SdComponentRole.CONTROLNET).associateWith { settings.imageComponentPath(it) } +
        (SdComponentRole.LORA to settings.imageLoras().firstOrNull()?.path.orEmpty())
    // Unknown family: every image role is offered and optional. Known: spec roles plus anything already picked.
    val roles = if (spec == null) imageComponentRoleOrder else imageComponentRoleOrder.filter {
        it in spec.requiredRoles || it in spec.optionalRoles || selected[it].orEmpty().isNotBlank()
    }
    val candidateRoles = imageComponentRoleOrder + SdComponentRole.CONTROLNET + SdComponentRole.LORA
    return SdDistributedImageComponentChoices(
        familyLabel = family?.first?.storedValue.orEmpty(),
        spec = spec,
        roles = roles,
        requiredRoles = spec?.requiredRoles?.toSet().orEmpty(),
        embeddedRoles = sdMainEmbeddedRoles(main),
        candidates = candidateRoles.associateWith { sdComponentCandidates(main, installed, it) },
        selectedPaths = selected,
        controlNetEnabled = settings.imageControlNetEnabled,
        loraEnabled = settings.imageLoraEnabled,
        loraApplyMode = settings.imageLoraApplyMode,
        mainFamily = family?.first?.storedValue,
        mainVariant = family?.second
    )
}
