package com.example.llamadroid.ui.components

import com.example.llamadroid.sd.SdVideoFamilyProfile
import com.example.llamadroid.sd.SdVideoDecoderKind
import com.example.llamadroid.sd.SdVideoInputRole
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.sd.SdLoraSpec

/**
 * Optional video controls that can be disabled without discarding their saved values.
 * The command builder remains the final authority; this model only keeps the editor in
 * sync with a probed binary and the selected family profile.
 */
enum class VideoAdditionalControl(
    val cliFlag: String,
    val requiresHighNoiseProfile: Boolean = false
) {
    IMG_CFG_SCALE("--img-cfg-scale"),
    GUIDANCE("--guidance"),
    SLG_SCALE("--slg-scale"),
    SLG_START("--skip-layer-start"),
    SLG_END("--skip-layer-end"),
    SKIP_LAYERS("--skip-layers"),
    ETA("--eta"),
    STRENGTH("--strength"),
    HIGH_NOISE_IMG_CFG_SCALE("--high-noise-img-cfg-scale", requiresHighNoiseProfile = true),
    HIGH_NOISE_GUIDANCE("--high-noise-guidance", requiresHighNoiseProfile = true),
    HIGH_NOISE_SAMPLING_METHOD("--high-noise-sampling-method", requiresHighNoiseProfile = true),
    HIGH_NOISE_SLG_SCALE("--high-noise-slg-scale", requiresHighNoiseProfile = true),
    HIGH_NOISE_SLG_START("--high-noise-skip-layer-start", requiresHighNoiseProfile = true),
    HIGH_NOISE_SLG_END("--high-noise-skip-layer-end", requiresHighNoiseProfile = true),
    HIGH_NOISE_SKIP_LAYERS("--high-noise-skip-layers", requiresHighNoiseProfile = true),
    HIGH_NOISE_ETA("--high-noise-eta", requiresHighNoiseProfile = true),
    MOE_BOUNDARY("--moe-boundary"),
    VACE_STRENGTH("--vace-strength"),
    IP_ADAPTER_STRENGTH("--ip-adapter-strength"),
    VAE_FORMAT("--vae-format"),
    SIGMAS("--sigmas"),
    REF_IMAGE_ARGS("--ref-image-args"),
    EXTRA_SAMPLE_ARGS("--extra-sample-args"),
    EXTRA_TILING_ARGS("--extra-tiling-args"),
    INCREASE_REF_INDEX("--increase-ref-index"),
    DISABLE_AUTO_RESIZE_REF_IMAGE("--disable-auto-resize-ref-image"),
    CIRCULAR("--circular"),
    CIRCULAR_X("--circularx"),
    CIRCULAR_Y("--circulary"),
    TEMPORAL_TILING("--temporal-tiling")
}

/**
 * UI-only availability derived from profile and binary facts. While a capability probe is
 * pending, controls stay editable. A completed probe with no binary is a separate unavailable
 * state so the editor can offer recovery instead of displaying an endless loading message.
 * Saved values are never cleared when a control becomes unavailable.
 */
data class VideoUiAvailability(
    val unavailableControls: Set<VideoAdditionalControl> = emptySet(),
    val unavailableFlags: Set<String> = emptySet(),
    val workflowEnabled: Boolean = true,
    val highNoiseEnabled: Boolean = true,
    val hiresEnabled: Boolean = true,
    val vaeTilingEnabled: Boolean = true,
    val taeEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val lingBotPromptEnabled: Boolean = true,
    val binaryProbePending: Boolean = false,
    val binaryProbeUnavailable: Boolean = false
) {
    fun isEnabled(control: VideoAdditionalControl): Boolean = control !in unavailableControls

    fun isFlagEnabled(flag: String): Boolean = flag !in unavailableFlags
}

/** The same typed prerequisite result drives the editor, footer, and launch preflight. */
internal data class VideoGenerationReadiness(
    val unsupportedWorkflow: Boolean = false,
    val missingComponents: List<SdVideoComponentRole> = emptyList(),
    val missingInputs: List<SdVideoInputRole> = emptyList()
) {
    val isSatisfied: Boolean
        get() = !unsupportedWorkflow && missingComponents.isEmpty() && missingInputs.isEmpty()
}

internal fun videoGenerationReadiness(options: VideoRuntimeOptions): VideoGenerationReadiness {
    val profile = options.videoFamily?.let { SdVideoFamilyProfiles.resolve(it, options.videoVariant) }
    val workflow = options.workflow
        ?: profile?.supportedWorkflows?.firstOrNull()
        ?: SdVideoWorkflow.TEXT_TO_VIDEO
    if (profile == null) return VideoGenerationReadiness()
    if (!profile.supports(workflow)) {
        return VideoGenerationReadiness(unsupportedWorkflow = true)
    }
    val result = profile.prerequisites(options.videoComponents, options.videoInputs, workflow)
    return VideoGenerationReadiness(
        missingComponents = result.missingComponents,
        missingInputs = result.missingInputs
    )
}

/** Only enabled adapters are sent to the launch validator; disabled rows remain editable drafts. */
internal fun videoLorasForValidation(
    loras: List<SdLoraSpec>,
    highNoiseLoras: List<SdLoraSpec>
): List<SdLoraSpec> = (loras + highNoiseLoras).filter { it.enabled }

/**
 * Explicitly clear values that the selected profile or binary cannot consume. This is opt-in so
 * switching profiles never silently destroys a reusable draft.
 */
internal fun VideoRuntimeOptions.clearUnavailableAdvancedSelections(
    availability: VideoUiAvailability
): VideoRuntimeOptions {
    var next = this

    fun clear(control: VideoAdditionalControl, update: (VideoRuntimeOptions) -> VideoRuntimeOptions) {
        if (!availability.isEnabled(control)) next = update(next)
    }

    clear(VideoAdditionalControl.IMG_CFG_SCALE) { it.copy(imgCfgScale = null) }
    clear(VideoAdditionalControl.GUIDANCE) { it.copy(guidance = null) }
    clear(VideoAdditionalControl.SLG_SCALE) { it.copy(slgScale = null) }
    clear(VideoAdditionalControl.SLG_START) { it.copy(skipLayerStart = null) }
    clear(VideoAdditionalControl.SLG_END) { it.copy(skipLayerEnd = null) }
    clear(VideoAdditionalControl.SKIP_LAYERS) { it.copy(skipLayers = "") }
    clear(VideoAdditionalControl.ETA) { it.copy(eta = null) }
    clear(VideoAdditionalControl.STRENGTH) { it.copy(strength = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_IMG_CFG_SCALE) { it.copy(highNoiseImgCfgScale = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_GUIDANCE) { it.copy(highNoiseGuidance = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_SAMPLING_METHOD) { it.copy(highNoiseSamplingMethod = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_SLG_SCALE) { it.copy(highNoiseSlgScale = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_SLG_START) { it.copy(highNoiseSkipLayerStart = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_SLG_END) { it.copy(highNoiseSkipLayerEnd = null) }
    clear(VideoAdditionalControl.HIGH_NOISE_SKIP_LAYERS) { it.copy(highNoiseSkipLayers = "") }
    clear(VideoAdditionalControl.HIGH_NOISE_ETA) { it.copy(highNoiseEta = null) }
    clear(VideoAdditionalControl.MOE_BOUNDARY) { it.copy(moeBoundary = null) }
    clear(VideoAdditionalControl.VACE_STRENGTH) { it.copy(vaceStrength = null) }
    clear(VideoAdditionalControl.IP_ADAPTER_STRENGTH) { it.copy(ipAdapterStrength = null) }
    clear(VideoAdditionalControl.VAE_FORMAT) { it.copy(vaeFormat = null) }
    clear(VideoAdditionalControl.SIGMAS) { it.copy(sigmas = "") }
    clear(VideoAdditionalControl.REF_IMAGE_ARGS) { it.copy(refImageArgs = "") }
    clear(VideoAdditionalControl.EXTRA_SAMPLE_ARGS) { it.copy(extraSampleArgs = "") }
    clear(VideoAdditionalControl.EXTRA_TILING_ARGS) { it.copy(extraTilingArgs = "") }
    clear(VideoAdditionalControl.INCREASE_REF_INDEX) { it.copy(increaseRefIndex = false) }
    clear(VideoAdditionalControl.DISABLE_AUTO_RESIZE_REF_IMAGE) { it.copy(disableAutoResizeRefImage = false) }
    clear(VideoAdditionalControl.CIRCULAR) { it.copy(circular = false) }
    clear(VideoAdditionalControl.CIRCULAR_X) { it.copy(circularX = false) }
    clear(VideoAdditionalControl.CIRCULAR_Y) { it.copy(circularY = false) }
    clear(VideoAdditionalControl.TEMPORAL_TILING) { it.copy(temporalTiling = false) }

    if (!availability.highNoiseEnabled) {
        next = next.copy(
            highNoiseSteps = null,
            highNoiseCfgScale = null,
            highNoiseSamplingMethod = null,
            highNoiseImgCfgScale = null,
            highNoiseGuidance = null,
            highNoiseSlgScale = null,
            highNoiseSkipLayerStart = null,
            highNoiseSkipLayerEnd = null,
            highNoiseSkipLayers = "",
            highNoiseEta = null
        )
    }
    if (!availability.isFlagEnabled("--control-strength")) next = next.copy(controlStrength = null)
    if (!availability.isFlagEnabled("--seed")) next = next.copy(seed = -1L)
    if (!availability.vaeTilingEnabled || !availability.isFlagEnabled("--vae-tile-overlap")) {
        next = next.copy(vaeTileOverlap = 0.5f)
    }
    if (!availability.vaeTilingEnabled || !availability.isFlagEnabled("--vae-relative-tile-size")) {
        next = next.copy(vaeRelativeTileSize = "")
    }
    if (!availability.hiresEnabled || !availability.isFlagEnabled("--hires")) {
        next = next.copy(hires = next.hires.copy(enabled = false))
    }
    if (!availability.isFlagEnabled("--hires-width")) next = next.copy(hires = next.hires.copy(width = null))
    if (!availability.isFlagEnabled("--hires-height")) next = next.copy(hires = next.hires.copy(height = null))
    if (!availability.isFlagEnabled("--hires-steps")) next = next.copy(hires = next.hires.copy(steps = null))
    if (!availability.isFlagEnabled("--hires-scale")) next = next.copy(hires = next.hires.copy(scale = null))
    if (!availability.isFlagEnabled("--hires-denoising-strength")) {
        next = next.copy(hires = next.hires.copy(denoisingStrength = null))
    }
    if (!availability.isFlagEnabled("--hires-upscale-tile-size")) {
        next = next.copy(hires = next.hires.copy(upscaleTileSize = null))
    }
    if (!availability.isFlagEnabled("--hires-sigmas")) next = next.copy(hires = next.hires.copy(sigmas = ""))
    if (!availability.taeEnabled || !availability.isFlagEnabled("--tae")) next = next.copy(useTae = false)
    if (!availability.audioEnabled) next = next.copy(audioCodec = null)
    if (!availability.lingBotPromptEnabled && next.promptFormat == com.example.llamadroid.sd.SdVideoPromptFormat.LINGBOT_CAPTION_JSON) {
        next = next.copy(promptFormat = com.example.llamadroid.sd.SdVideoPromptFormat.PLAIN)
    }
    return next
}

internal fun VideoRuntimeOptions.hasUnavailableAdvancedSelections(
    availability: VideoUiAvailability
): Boolean = this != clearUnavailableAdvancedSelections(availability)

fun videoUiAvailability(
    profile: SdVideoFamilyProfile? = null,
    binaryCapabilities: SdBinaryCapabilities? = null,
    binaryProbePending: Boolean = binaryCapabilities == null,
    binaryProbeUnavailable: Boolean = false
): VideoUiAvailability {
    fun supportsFlag(flag: String): Boolean = binaryCapabilities == null ||
        binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
        binaryCapabilities.supports(flag)

    val trackedFlags = buildSet {
        addAll(VideoAdditionalControl.entries.map { it.cliFlag })
        addAll(
            listOf(
                "--diffusion-model",
                "--model",
                "--high-noise-diffusion-model",
                "--uncond-diffusion-model",
                "--vae",
                "--tae",
                "--t5xxl",
                "--llm",
                "--llm_vision",
                "--audio-vae",
                "--embeddings-connectors",
                "--motion-module",
                "--clip_vision",
                "--control-net",
                "--hires-upscalers-dir",
                "--hires-upscaler",
                "--lora-model-dir",
                "--lora-apply-mode",
                "--init-img",
                "--end-img",
                "--control-image",
                "--control-video",
                "--control-strength",
                "--ref-image",
                "--ref-video",
                "--ref-video-audio",
                "--ref-audio",
                "--ip-adapter",
                "--ip-adapter-image",
                "--seed",
                "--high-noise-steps",
                "--high-noise-cfg-scale",
                "--vae-tiling",
                "--vae-tile-overlap",
                "--vae-relative-tile-size",
                "--hires",
                "--hires-width",
                "--hires-height",
                "--hires-steps",
                "--hires-scale",
                "--hires-denoising-strength",
                "--hires-upscale-tile-size",
                "--hires-sigmas"
            )
        )
    }
    val unavailable = buildSet {
        VideoAdditionalControl.entries.forEach { control ->
            val profileUnavailable = control.requiresHighNoiseProfile &&
                profile?.supportsHighNoisePass == false
            val binaryUnavailable = !supportsFlag(control.cliFlag)
            if (profileUnavailable || binaryUnavailable) add(control)
        }
    }
    val unavailableFlags = trackedFlags.filterNot(::supportsFlag).toSet()
    return VideoUiAvailability(
        unavailableControls = unavailable,
        unavailableFlags = unavailableFlags,
        workflowEnabled = binaryProbePending ||
            (!binaryProbeUnavailable && (
                binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities?.supportsMode("vid_gen") == true
                )),
        highNoiseEnabled = profile?.supportsHighNoisePass != false &&
            supportsFlag("--high-noise-steps"),
        hiresEnabled = profile?.supportsHires != false && supportsFlag("--hires"),
        vaeTilingEnabled = profile?.supportsVaeTiling != false && supportsFlag("--vae-tiling"),
        taeEnabled = profile?.let {
            it.decoderKind == SdVideoDecoderKind.TAE ||
                it.decoderKind == SdVideoDecoderKind.VAE_OR_TAE
        } ?: true,
        audioEnabled = profile?.supportsAudioOutput != false,
        lingBotPromptEnabled = profile == null ||
            profile.promptFormat == com.example.llamadroid.sd.SdVideoPromptFormat.LINGBOT_CAPTION_JSON,
        binaryProbePending = binaryProbePending,
        binaryProbeUnavailable = binaryProbeUnavailable
    )
}
