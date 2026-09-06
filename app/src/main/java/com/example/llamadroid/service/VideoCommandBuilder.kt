package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoHiresConfig
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoMainModelLayout
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.SdVideoPrerequisiteException
import com.example.llamadroid.sd.SdVideoPrerequisiteResult
import com.example.llamadroid.sd.SdVideoInputException
import com.example.llamadroid.sd.SdVideoWorkflowErrorCode
import com.example.llamadroid.sd.SdVideoWorkflowException
import com.example.llamadroid.sd.activeInOrder
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.sd.validate
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Build a stable-diffusion.cpp video command without Android/service state.
 * The service supplies the executable path and capability probe, while tests
 * and draft previews can use the same deterministic argument list.
 */
fun buildVideoCommandArgs(
    config: VideoGenerationConfig,
    executablePath: String = "sd-cli",
    binaryCapabilities: SdBinaryCapabilities? = null,
    loraStaging: VideoLoraStagingPlan? = null
): List<String> {
    validateVideoNumericInputs(config)
    if (config.videoFrames < 2) {
        throw SdVideoInputException(
            SdVideoInputException.Code.FRAMES_MUST_BE_VIDEO,
            "video generation requires at least two frames"
        )
    }
    val paths = config.resolvedVideoComponents()
    val inputs = config.resolvedVideoInputs()
    val workflow = config.resolvedVideoWorkflow()
    val highNoiseSelected = config.highNoiseSteps != null ||
        config.highNoiseCfgScale != null ||
        config.highNoiseSamplingMethod != null ||
        config.highNoiseImgCfgScale != null ||
        config.highNoiseGuidance != null ||
        config.highNoiseSlgScale != null ||
        config.highNoiseSkipLayerStart != null ||
        config.highNoiseSkipLayerEnd != null ||
        config.highNoiseSkipLayers.isNotBlank() ||
        config.highNoiseEta != null ||
        config.highNoiseLoras.any { it.enabled }
    val profile = config.videoFamily?.let { SdVideoFamilyProfiles.resolve(it, config.videoVariant) }
    val hasReferenceInputs = inputs.referenceImages.any(String::isNotBlank) ||
        inputs.referenceVideos.any(String::isNotBlank) ||
        inputs.referenceAudios.any(String::isNotBlank) ||
        inputs.referenceVideoAudios.any(String::isNotBlank)
    if (config.videoFamily == SdVideoFamily.MINIMAX_H3 &&
        hasReferenceInputs &&
        (!inputs.initImagePath.isNullOrBlank() || !inputs.endImagePath.isNullOrBlank())
    ) {
        throw SdVideoInputException(SdVideoInputException.Code.MINIMAX_REFERENCES_CONFLICT_WITH_KEYFRAMES)
    }
    if (config.videoFamily == SdVideoFamily.MINIMAX_H3 && !inputs.controlVideoPath.isNullOrBlank()) {
        throw SdVideoInputException(SdVideoInputException.Code.MINIMAX_CONTROL_VIDEO_UNSUPPORTED)
    }
    if (profile != null) {
        if (!profile.nativeWorkflowSupported) {
            throw SdVideoWorkflowException(
                family = profile.family,
                workflow = workflow,
                code = SdVideoWorkflowErrorCode.NATIVE_WORKFLOW_UNSUPPORTED
            )
        }
        if (!profile.supports(workflow)) {
            throw SdVideoWorkflowException(
                family = profile.family,
                workflow = workflow,
                code = SdVideoWorkflowErrorCode.UNSUPPORTED_WORKFLOW
            )
        }
        val prerequisites = profile.validate(paths, inputs, workflow)
        if (!prerequisites.isSatisfied) {
            throw SdVideoPrerequisiteException(profile, prerequisites)
        }
        if (highNoiseSelected && !profile.supportsHighNoisePass) {
            throw SdVideoWorkflowException(
                family = profile.family,
                workflow = workflow,
                code = SdVideoWorkflowErrorCode.HIGH_NOISE_UNSUPPORTED
            )
        }
        if (config.hires.enabled && !profile.supportsHires) {
            throw SdVideoWorkflowException(
                family = profile.family,
                workflow = workflow,
                code = SdVideoWorkflowErrorCode.HIRES_UNSUPPORTED
            )
        }
        if (highNoiseSelected && paths.highNoiseDiffusionModelPath.isNullOrBlank()) {
            throw SdVideoPrerequisiteException(
                profile,
                SdVideoPrerequisiteResult(
                    missingComponents = listOf(SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL)
                )
            )
        }
    }

    val requiredFlags = mutableSetOf<String>()
    val requiredModes = mutableSetOf<String>()
    val args = mutableListOf(executablePath)

    fun isCapabilityKnown(): Boolean = binaryCapabilities != null &&
        binaryCapabilities != SdBinaryCapabilities.ALLOW_ALL

    fun requireFlag(flag: String) {
        if (isCapabilityKnown() && !binaryCapabilities!!.supports(flag)) {
            requiredFlags += flag
        }
    }

    fun requireMode(mode: String) {
        if (isCapabilityKnown() && !binaryCapabilities!!.supportsMode(mode)) {
            requiredModes += mode
        }
    }

    fun addPath(flag: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let {
            requireFlag(flag)
            args.addAll(listOf(flag, it))
        }
    }

    fun addValue(flag: String, value: String) {
        requireFlag(flag)
        args.addAll(listOf(flag, value))
    }

    fun addBare(flag: String) {
        requireFlag(flag)
        args.add(flag)
    }

    requireFlag("-M")
    requireMode("vid_gen")
    addValue("-M", "vid_gen")

    val mainRole = profile?.mainModelLayout ?: SdVideoMainModelLayout.STANDALONE_DIFFUSION
    when (mainRole) {
        SdVideoMainModelLayout.STANDALONE_DIFFUSION -> {
            val modelPath = paths.diffusionModelPath ?: config.diffusionModelPath
            addPath("--diffusion-model", modelPath)
        }
        SdVideoMainModelLayout.FULL_MODEL -> {
            val modelPath = paths.fullModelPath ?: paths.diffusionModelPath ?: config.diffusionModelPath
            addPath("--model", modelPath)
        }
    }
    addPath("--high-noise-diffusion-model", paths.highNoiseDiffusionModelPath)
    addPath("--uncond-diffusion-model", paths.uncondDiffusionModelPath)

    // A profile accepts either a family VAE or a compatible TAE. If both are
    // saved, the explicit useTae choice wins and only one decoder is emitted.
    val decoderTae = paths.taePath?.takeIf { config.useTae || paths.vaePath.isNullOrBlank() }
    val decoderVae = paths.vaePath?.takeIf { decoderTae == null }
    addPath("--vae", decoderVae)
    addPath("--tae", decoderTae)

    addPath("--t5xxl", paths.t5xxlPath)
    addPath("--llm", paths.llmPath)
    addPath("--llm_vision", paths.llmVisionPath)
    addPath("--audio-vae", paths.audioVaePath)
    addPath("--embeddings-connectors", paths.embeddingsConnectorsPath)
    addPath("--motion-module", paths.motionModulePath)
    addPath("--clip_vision", paths.clipVisionPath)
    addPath("--ip-adapter", paths.ipAdapterPath)
    addPath("--ip-adapter-image", inputs.ipAdapterImagePath)
    config.ipAdapterStrength?.let { addValue("--ip-adapter-strength", it.toString()) }
    config.vaeFormat?.trim()?.takeIf { it.isNotBlank() }?.let { addValue("--vae-format", it) }

    val videoLoras = config.resolvedLoras().activeInOrder().also { validateSdLoras(it) }
    var effectiveLoraStaging = loraStaging
    if (videoLoras.isNotEmpty()) {
        val plan = loraStaging ?: VideoLoraStagingPlan.nativeAbsolute(videoLoras)
        require(plan.loras.map { it.path } == videoLoras.map { it.path }) {
            "Video LoRA staging plan does not match the active LoRA order"
        }
        effectiveLoraStaging = plan
        addPath("--lora-model-dir", plan.loraModelDirectory)
        config.loraApplyMode?.let {
            addValue("--lora-apply-mode", it.cliName)
        }
    }

    addValue("--prompt", buildVideoPrompt(config, effectiveLoraStaging))
    val negativePrompt = config.resolvedVideoNegativePrompt()
    if (negativePrompt.isNotBlank()) {
        addValue("-n", negativePrompt)
    }
    addValue("--sampling-method", config.samplingMethod.cliName)
    addValue("--video-frames", config.videoFrames.toString())
    addValue("--fps", config.fps.toString())
    addValue("--width", config.width.toString())
    addValue("--height", config.height.toString())
    addValue("--steps", config.steps.toString())
    addValue("--cfg-scale", config.cfgScale.toString())
    if (config.seed != -1L) {
        addValue("--seed", config.seed.toString())
    }
    config.scheduler?.let {
        addValue("--scheduler", it.cliName)
    }
    config.flowShift?.let {
        addValue("--flow-shift", it.toString())
    }
    config.highNoiseSteps?.let {
        addValue("--high-noise-steps", it.toString())
    }
    config.highNoiseCfgScale?.let {
        addValue("--high-noise-cfg-scale", it.toString())
    }
    config.highNoiseSamplingMethod?.let {
        addValue("--high-noise-sampling-method", it.cliName)
    }
    config.imgCfgScale?.let { addValue("--img-cfg-scale", it.toString()) }
    config.guidance?.let { addValue("--guidance", it.toString()) }
    config.slgScale?.let { addValue("--slg-scale", it.toString()) }
    config.skipLayerStart?.let { addValue("--skip-layer-start", it.toString()) }
    config.skipLayerEnd?.let { addValue("--skip-layer-end", it.toString()) }
    validateNativeSkipLayers(config.skipLayers, "skipLayers")?.let {
        addValue("--skip-layers", it)
    }
    config.eta?.let { addValue("--eta", it.toString()) }
    config.strength?.let { addValue("--strength", it.toString()) }
    config.highNoiseImgCfgScale?.let { addValue("--high-noise-img-cfg-scale", it.toString()) }
    config.highNoiseGuidance?.let { addValue("--high-noise-guidance", it.toString()) }
    config.highNoiseSlgScale?.let { addValue("--high-noise-slg-scale", it.toString()) }
    config.highNoiseSkipLayerStart?.let { addValue("--high-noise-skip-layer-start", it.toString()) }
    config.highNoiseSkipLayerEnd?.let { addValue("--high-noise-skip-layer-end", it.toString()) }
    validateNativeSkipLayers(config.highNoiseSkipLayers, "highNoiseSkipLayers")?.let {
        addValue("--high-noise-skip-layers", it)
    }
    config.highNoiseEta?.let { addValue("--high-noise-eta", it.toString()) }
    config.moeBoundary?.let { addValue("--moe-boundary", it.toString()) }
    config.vaceStrength?.let { addValue("--vace-strength", it.toString()) }
    if (config.sigmas.isNotBlank()) {
        addValue("--sigmas", validateNativeFloatList(config.sigmas, "sigmas"))
    }
    config.cacheMode?.let { addValue("--cache-mode", it.cliName) }
    if (config.cacheOption.isNotBlank()) addValue("--cache-option", config.cacheOption)
    if (config.scmMask.isNotBlank()) addValue("--scm-mask", config.scmMask)
    config.scmPolicy?.let { addValue("--scm-policy", it.cliName) }

    val initImagePath = inputs.initImagePath
    if (config.mode == VideoGenerationMode.IMG2VID && initImagePath.isNullOrBlank()) {
        throw SdVideoInputException(SdVideoInputException.Code.MODE_REQUIRES_INIT_IMAGE)
    }
    addPath("--init-img", initImagePath)
    addPath("--end-img", inputs.endImagePath)
    addPath("--control-image", inputs.controlImagePath)
    addPath("--control-video", inputs.controlVideoPath)
    addPath("--control-net", paths.controlNetPath)
    if (paths.controlNetPath != null &&
        (inputs.controlImagePath != null || inputs.controlVideoPath != null)
    ) {
        config.controlStrength?.let {
            addValue("--control-strength", it.toString())
        }
    }

    inputs.referenceImages.filter(String::isNotBlank).forEach { addPath("--ref-image", it) }
    inputs.referenceVideos.filter(String::isNotBlank).forEach { addPath("--ref-video", it) }
    inputs.referenceAudios.filter(String::isNotBlank).forEach { addPath("--ref-audio", it) }
    val referenceVideoAudios = inputs.referenceVideoAudios.filter(String::isNotBlank)
    // Native pairs soundtrack files by index and only rejects an audio list
    // that is longer than the reference-video list. A shorter list is valid:
    // remaining reference videos simply have no paired soundtrack.
    if (referenceVideoAudios.size > inputs.referenceVideos.count(String::isNotBlank)) {
        throw SdVideoInputException(SdVideoInputException.Code.REFERENCE_AUDIO_MISMATCH)
    }
    referenceVideoAudios.forEach { addPath("--ref-video-audio", it) }
    if (config.refImageArgs.isNotBlank()) {
        addValue("--ref-image-args", validateNativeKeyValueList(config.refImageArgs, "refImageArgs"))
    }
    if (config.increaseRefIndex) addBare("--increase-ref-index")
    if (config.disableAutoResizeRefImage) addBare("--disable-auto-resize-ref-image")

    if (config.vaeTiling) {
        addBare("--vae-tiling")
        addValue("--vae-tile-overlap", config.vaeTileOverlap.toString())
        if (config.vaeTileSize.isNotBlank()) addValue("--vae-tile-size", config.vaeTileSize)
        if (config.vaeRelativeTileSize.isNotBlank()) {
            addValue("--vae-relative-tile-size", config.vaeRelativeTileSize)
        }
    }
    if (config.temporalTiling) addBare("--temporal-tiling")
    if (config.circular) addBare("--circular")
    if (config.circularX) addBare("--circularx")
    if (config.circularY) addBare("--circulary")
    if (config.extraSampleArgs.isNotBlank()) {
        addValue("--extra-sample-args", validateNativeKeyValueList(config.extraSampleArgs, "extraSampleArgs"))
    }
    if (config.extraTilingArgs.isNotBlank()) {
        addValue("--extra-tiling-args", validateNativeKeyValueList(config.extraTilingArgs, "extraTilingArgs"))
    }

    appendHiresArgs(args, config.hires, paths.hiresUpscalersDir, paths.hiresUpscaler, ::requireFlag)

    if (config.diffusionFa) {
        addBare("--diffusion-fa")
    }
    if (config.diffusionConvDirect) {
        addBare("--diffusion-conv-direct")
    }
    if (config.vaeConvDirect) {
        addBare("--vae-conv-direct")
    }
    if (config.mmap) {
        addBare("--mmap")
    }
    if (config.threads > 0) addValue("-t", config.threads.toString())

    if (!config.distributedRuntime.enabled) {
        val binaryFile = File(executablePath)
        appendLocalSdBackendArgs(
            args = args,
            paramsBackendMode = config.sdParamsBackendMode,
            paramsBackendSpec = config.sdParamsBackendSpec,
            runtimeBackendMode = effectiveSdRuntimeBackendModeForBinary(binaryFile, config.sdRuntimeBackendMode),
            maxVramCpuGiB = effectiveSdMaxVramCpuGiBForBinary(binaryFile, config.maxVramCpuGiB),
            flagSupported = { flag ->
                binaryCapabilities == null ||
                    binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities.supports(flag)
            }
        )
    }
    appendSdDistributedArgs(args, config.distributedRuntime, binaryCapabilities)
    appendSdCustomFlags(args, config.customFlags)
    addValue("-o", config.resolvedNativeOutputPath())
    addBare("-v")

    // If -M itself is unavailable, the flag error is the actionable diagnosis.
    // Once mode selection is supported, report an unsupported mode before an
    // unrelated optional flag so callers can distinguish the typed failure.
    if (requiredModes.isNotEmpty() && "-M" in requiredFlags) {
        throw SdUnsupportedFlagsException(requiredFlags.toList().sorted())
    }
    if (requiredModes.isNotEmpty()) {
        throw SdUnsupportedModesException(requiredModes.toList().sorted())
    }
    if (requiredFlags.isNotEmpty()) {
        throw SdUnsupportedFlagsException(requiredFlags.toList().sorted())
    }
    return args
}

/** Existing tests and callers use this prompt helper directly. */
internal fun buildVideoPrompt(
    config: VideoGenerationConfig,
    loraStaging: VideoLoraStagingPlan? = null
): String {
    val activeLoras = config.resolvedLoras().activeInOrder()
    val loraTokens = activeLoras.mapIndexed { index, item ->
        videoLoraPromptToken(item, loraStaging?.promptPath(index, item))
    }
    val basePrompt = config.resolvedVideoPrompt()
    if (config.promptFormat == SdVideoPromptFormat.LINGBOT_CAPTION_JSON ||
        config.videoFamily == SdVideoFamily.LINGBOT_VIDEO
    ) {
        val parsed = runCatching { JSONObject(basePrompt) }.getOrNull()
        if (parsed != null) {
            val caption = parsed.optJSONObject("caption") ?: JSONObject().also {
                parsed.put("caption", it)
            }
            val description = caption.optString("comprehensive_description")
                .ifBlank { config.prompt }
            caption.put("comprehensive_description", (loraTokens + description).filter(String::isNotBlank).joinToString(" "))
            return parsed.toString()
        }
    }
    return (loraTokens + basePrompt).filter(String::isNotBlank).joinToString(" ")
}

internal fun videoLoraPromptToken(item: SdLoraSpec, promptPath: String? = null): String {
    val marker = if (item.highNoiseOnly) "|high_noise|" else ""
    val name = promptPath?.takeIf { it.isNotBlank() } ?: item.promptTokenName
    return "<lora:$marker$name:${formatVideoLoraStrength(item.strength)}>"
}

/** Compatibility name retained for older Wan-only callers and tests. */
internal fun wanLoraPromptToken(item: SdLoraSpec): String = videoLoraPromptToken(item)

private fun formatVideoLoraStrength(value: Float): String =
    String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')

private fun appendHiresArgs(
    args: MutableList<String>,
    hires: SdVideoHiresConfig,
    upscalersDir: String?,
    upscaler: String?,
    requireFlag: (String) -> Unit
) {
    if (!hires.enabled) return
    requireFlag("--hires")
    args.add("--hires")
    upscalersDir?.takeIf(String::isNotBlank)?.let {
        requireFlag("--hires-upscalers-dir")
        args.addAll(listOf("--hires-upscalers-dir", it))
    }
    upscaler?.takeIf(String::isNotBlank)?.let {
        requireFlag("--hires-upscaler")
        args.addAll(listOf("--hires-upscaler", it))
    }
    hires.width?.let {
        requireFlag("--hires-width")
        args.addAll(listOf("--hires-width", it.toString()))
    }
    hires.height?.let {
        requireFlag("--hires-height")
        args.addAll(listOf("--hires-height", it.toString()))
    }
    hires.steps?.let {
        requireFlag("--hires-steps")
        args.addAll(listOf("--hires-steps", it.toString()))
    }
    hires.scale?.let {
        requireFlag("--hires-scale")
        args.addAll(listOf("--hires-scale", it.toString()))
    }
    hires.denoisingStrength?.let {
        requireFlag("--hires-denoising-strength")
        args.addAll(listOf("--hires-denoising-strength", it.toString()))
    }
    hires.upscaleTileSize?.let {
        requireFlag("--hires-upscale-tile-size")
        args.addAll(listOf("--hires-upscale-tile-size", it.toString()))
    }
    if (hires.sigmas.isNotBlank()) {
        requireFlag("--hires-sigmas")
        args.addAll(listOf("--hires-sigmas", hires.sigmas))
    }
}

/** Native list parsers require bracketed integer layers; reject malformed draft values early. */
private fun validateNativeSkipLayers(raw: String, label: String): String? {
    val value = raw.trim()
    if (value.isBlank()) return null
    require(value.startsWith('[') && value.endsWith(']')) {
        "$label must use native list syntax such as [7,8,9]"
    }
    val body = value.substring(1, value.length - 1).trim()
    require(body.isNotBlank()) { "$label cannot be empty" }
    require(body.split(Regex("[, ]+"), limit = 0).all { it.toIntOrNull() != null }) {
        "$label must contain integer layer indices"
    }
    return value
}

private fun validateNativeFloatList(raw: String, label: String): String {
    val value = raw.trim()
    require(value.trim('[', ']').split(',').map(String::trim).all { it.toFloatOrNull() != null }) {
        "$label must be a comma-separated list of numbers"
    }
    return value
}

private fun validateNativeKeyValueList(raw: String, label: String): String {
    val value = raw.trim()
    require(value.split(',').all { item ->
        val parts = item.trim().split('=', limit = 2)
        parts.size == 2 && parts[0].trim().matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) &&
            parts[1].trim().isNotBlank()
    }) {
        "$label must be a comma-separated key=value list"
    }
    return value
}
