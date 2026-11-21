package com.example.llamadroid.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdCuratedPresets
import com.example.llamadroid.sd.VideoModelSelection
import com.example.llamadroid.sd.VideoPresetCard
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.ui.components.VideoModelPicker
import com.example.llamadroid.ui.components.VideoModelPickerContent

/**
 * Scalar form defaults a preset card sets on the local video screen. These fields are not
 * part of [VideoRuntimeOptions], so the screen applies them through a callback.
 */
internal data class VideoScalarDefaults(
    val frames: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScaleText: String,
    val flowShiftText: String,
    val threads: Int,
    val mmap: Boolean,
    val diffusionFa: Boolean,
    val useVae: Boolean,
    val sampler: SamplingMethod,
    val scheduler: SdScheduler?
)

/** Scalar defaults for [card], or null when the card carries none (every card but LingBot phone). */
internal fun videoScalarDefaultsFor(card: VideoPresetCard): VideoScalarDefaults? {
    if (card.bundleId != SdCuratedPresets.LingBotPhone.BUNDLE_ID) return null
    val preset = SdCuratedPresets.LingBotPhone
    return VideoScalarDefaults(
        frames = preset.FRAMES,
        fps = preset.FPS,
        width = preset.WIDTH,
        height = preset.HEIGHT,
        steps = preset.STEPS,
        cfgScaleText = preset.CFG_SCALE_TEXT,
        flowShiftText = preset.FLOW_SHIFT_TEXT,
        threads = preset.THREADS,
        mmap = preset.MMAP,
        diffusionFa = preset.DIFFUSION_FA,
        useVae = preset.USE_VAE,
        sampler = preset.SAMPLER,
        scheduler = preset.SCHEDULER
    )
}

/**
 * Model selection block of the local video screen: hosts the shared [VideoModelPicker].
 * The screen's [onOptionsChange] keeps its stored main model path and draft in sync.
 */
@Composable
internal fun VideoGenModelSection(
    options: VideoRuntimeOptions,
    installedModels: List<ModelEntity>,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    onOpenModelManager: (bundleId: String?) -> Unit,
    onScalarDefaults: (VideoScalarDefaults) -> Unit,
    modifier: Modifier = Modifier
) {
    VideoModelPicker(
        options = options,
        installedModels = installedModels,
        onOptionsChange = onOptionsChange,
        onOpenModelManager = onOpenModelManager,
        modifier = modifier,
        onPresetApplied = { card -> videoScalarDefaultsFor(card)?.let(onScalarDefaults) }
    )
}

/** Test seam: same section with injected cards and resolver. */
@Composable
internal fun VideoGenModelSectionContent(
    options: VideoRuntimeOptions,
    cards: List<VideoPresetCard>,
    resolve: (String) -> VideoModelSelection?,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    onOpenModelManager: (bundleId: String?) -> Unit,
    onScalarDefaults: (VideoScalarDefaults) -> Unit,
    modifier: Modifier = Modifier
) {
    VideoModelPickerContent(
        options = options,
        cards = cards,
        resolve = resolve,
        onOptionsChange = onOptionsChange,
        onOpenModelManager = onOpenModelManager,
        modifier = modifier,
        onPresetApplied = { card -> videoScalarDefaultsFor(card)?.let(onScalarDefaults) }
    )
}
