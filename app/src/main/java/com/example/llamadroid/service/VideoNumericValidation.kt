package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoInputException

/** Shared local/distributed boundary: never pass non-finite numbers to the native runtime. */
internal fun validateVideoNumericInputs(config: VideoGenerationConfig) {
    fun invalid(flag: String): Nothing = throw SdVideoInputException(
        SdVideoInputException.Code.INVALID_NUMERIC_VALUE, flag
    )
    fun finite(flag: String, value: Float?) {
        if (value != null && !value.isFinite()) invalid(flag)
    }
    fun positive(flag: String, value: Int?) {
        if (value != null && value <= 0) invalid(flag)
    }
    positive("--width", config.width)
    positive("--height", config.height)
    positive("--fps", config.fps)
    positive("--steps", config.steps)
    // Negative thread counts retain the existing automatic-thread compatibility behavior.
    listOf(
        "--cfg-scale" to config.cfgScale, "--flow-shift" to config.flowShift,
        "--high-noise-cfg-scale" to config.highNoiseCfgScale,
        "--control-strength" to config.controlStrength,
        "--img-cfg-scale" to config.imgCfgScale, "--guidance" to config.guidance,
        "--slg-scale" to config.slgScale, "--skip-layer-start" to config.skipLayerStart,
        "--skip-layer-end" to config.skipLayerEnd, "--eta" to config.eta,
        "--strength" to config.strength, "--high-noise-img-cfg-scale" to config.highNoiseImgCfgScale,
        "--high-noise-guidance" to config.highNoiseGuidance,
        "--high-noise-slg-scale" to config.highNoiseSlgScale,
        "--high-noise-skip-layer-start" to config.highNoiseSkipLayerStart,
        "--high-noise-skip-layer-end" to config.highNoiseSkipLayerEnd,
        "--high-noise-eta" to config.highNoiseEta, "--moe-boundary" to config.moeBoundary,
        "--vace-strength" to config.vaceStrength, "--ip-adapter-strength" to config.ipAdapterStrength
    ).forEach { (flag, value) -> finite(flag, value) }
    if (config.vaeTiling) finite("--vae-tile-overlap", config.vaeTileOverlap)
    if (config.hires.enabled) {
        // The pinned CLI accepts zero for automatic hires dimensions/steps.
        listOf("--hires-width" to config.hires.width, "--hires-height" to config.hires.height,
            "--hires-steps" to config.hires.steps).forEach { (flag, value) ->
            if (value != null && value < 0) invalid(flag)
        }
        finite("--hires-scale", config.hires.scale)
        finite("--hires-denoising-strength", config.hires.denoisingStrength)
    }
}
