package com.example.llamadroid.service

import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdParamsBackendMode
import com.example.llamadroid.sd.SdRuntimeBackendMode
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.SdImageInputMode
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.util.DeviceAcceleration
import java.io.File

data class SdBinaryCapabilities(
    val supportedFlags: Set<String>
) {
    fun supports(flag: String): Boolean = supportedFlags.contains(flag)

    companion object {
        val ALLOW_ALL = SdBinaryCapabilities(emptySet())
    }
}

class SdMissingComponentsException(
    val roles: List<SdComponentRole>
) : IllegalStateException("Missing required components: ${roles.joinToString(", ") { it.name }}")

class SdUnsupportedFlagsException(
    val flags: List<String>
) : IllegalStateException("Unsupported stable-diffusion.cpp flags: ${flags.joinToString(", ")}")

class SdDisallowedDistributedFlagException(
    val flag: String,
    override val message: String = "Disallowed distributed stable-diffusion.cpp flag: $flag"
) : IllegalStateException(message)

fun parseSdBinaryCapabilities(helpText: String): SdBinaryCapabilities {
    val flagRegex = Regex("""(?<![A-Za-z0-9_-])(--[A-Za-z0-9][A-Za-z0-9_-]*|-[A-Za-z])(?![A-Za-z0-9_-])""")
    return SdBinaryCapabilities(
        supportedFlags = flagRegex.findAll(helpText).map { it.value }.toSet()
    )
}

fun inferSdFamilyForConfig(config: SDConfig): Pair<SdModelFamily, String?> {
    val explicitFamily = SdModelFamily.fromStoredValue(config.modelFamily)
    if (explicitFamily != null) {
        return explicitFamily to config.modelVariant
    }
    if (config.isFluxModel) {
        return SdModelFamily.FLUX_1 to config.modelVariant
    }

    val inferredType = when {
        config.llmPath != null || config.clipLPath != null || config.clipGPath != null || config.t5xxlPath != null ->
            ModelType.SD_DIFFUSION
        else -> ModelType.SD_CHECKPOINT
    }
    val inferred = inferSdFamily(inferredType, config.modelPath, File(config.modelPath).name)
    return (inferred.first ?: SdModelFamily.CHECKPOINT) to inferred.second
}

fun buildSdCommandArgs(
    config: SDConfig,
    binaryCapabilities: SdBinaryCapabilities? = null
): List<String> {
    val args = mutableListOf<String>()
    val requiredFlags = mutableSetOf<String>()

    fun requireFlag(flag: String) {
        if (binaryCapabilities != null &&
            binaryCapabilities != SdBinaryCapabilities.ALLOW_ALL &&
            !binaryCapabilities.supports(flag)
        ) {
            requiredFlags += flag
        }
    }

    fun addFlagIfSupported(flag: String) {
        if (binaryCapabilities == null ||
            binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
            binaryCapabilities.supports(flag)
        ) {
            args.add(flag)
        }
    }

    when (config.mode) {
        SDMode.TXT2IMG, SDMode.IMG2IMG -> args.addAll(listOf("-M", "img_gen"))
        SDMode.UPSCALE -> args.addAll(listOf("-M", "upscale"))
    }

    if (config.mode == SDMode.UPSCALE) {
        args.addAll(listOf("-o", config.outputPath))
        config.initImage?.let { args.addAll(listOf("-i", it)) }
        config.upscaleModel?.let { args.addAll(listOf("--upscale-model", it)) }
        args.addAll(listOf("--upscale-repeats", config.upscaleRepeats.toString()))
        if (config.threads > 0) {
            args.addAll(listOf("-t", config.threads.toString()))
        }
        if (!config.distributedRuntime.enabled) {
            appendLocalSdBackendArgs(
                args = args,
                paramsBackendMode = config.sdParamsBackendMode,
                runtimeBackendMode = config.sdRuntimeBackendMode,
                maxVramCpuGiB = config.maxVramCpuGiB,
                flagSupported = { flag ->
                    binaryCapabilities == null ||
                        binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                        binaryCapabilities.supports(flag)
                }
            )
        }
        appendSdDistributedArgs(args, config.distributedRuntime, binaryCapabilities)
        appendSdCustomFlags(args, config.customFlags)
        args.add("-v")
        if (requiredFlags.isNotEmpty()) {
            throw SdUnsupportedFlagsException(requiredFlags.toList().sorted())
        }
        return args
    }

    val (family, variant) = inferSdFamilyForConfig(config)
    val spec = resolveSdFamilySpec(family, variant)
    val missingComponents = spec.requiredRoles.filter { role ->
        when (role) {
            SdComponentRole.VAE -> config.vaePath.isNullOrBlank()
            SdComponentRole.TAE -> config.taePath.isNullOrBlank()
            SdComponentRole.CLIP_L -> config.clipLPath.isNullOrBlank()
            SdComponentRole.CLIP_G -> config.clipGPath.isNullOrBlank()
            SdComponentRole.T5XXL -> config.t5xxlPath.isNullOrBlank()
            SdComponentRole.LLM -> config.llmPath.isNullOrBlank()
            SdComponentRole.LLM_VISION -> config.llmVisionPath.isNullOrBlank()
            SdComponentRole.PHOTOMAKER -> config.photoMakerPath.isNullOrBlank()
            else -> false
        }
    }
    if (missingComponents.isNotEmpty()) {
        throw SdMissingComponentsException(missingComponents)
    }

    if (spec.usesDiffusionModelFlag) {
        requireFlag("--diffusion-model")
        args.addAll(listOf("--diffusion-model", config.modelPath))
    } else {
        args.addAll(listOf("-m", config.modelPath))
    }

    config.vaePath?.let { args.addAll(listOf("--vae", it)) }
    config.taePath?.let {
        // TAESD is a decode-only VAE; TAE/TAEHV remain the family component path.
        val decoderFlag = if (File(it).name.contains("taesd", ignoreCase = true)) "--taesd" else "--tae"
        requireFlag(decoderFlag)
        args.addAll(listOf(decoderFlag, it))
    }
    config.clipLPath?.let { args.addAll(listOf("--clip_l", it)) }
    config.clipGPath?.let {
        requireFlag("--clip_g")
        args.addAll(listOf("--clip_g", it))
    }
    config.t5xxlPath?.let { args.addAll(listOf("--t5xxl", it)) }
    config.llmPath?.let {
        requireFlag("--llm")
        args.addAll(listOf("--llm", it))
    }
    config.llmVisionPath?.let {
        requireFlag("--llm_vision")
        args.addAll(listOf("--llm_vision", it))
    }
    config.photoMakerPath?.let {
        requireFlag("--photo-maker")
        args.addAll(listOf("--photo-maker", it))
    }
    config.ipAdapter?.let { rawAdapter ->
        val adapter = validateSdIpAdapterConfig(
            config = rawAdapter,
            supportsIpAdapter = spec.supportsIpAdapter
        ) ?: error("IP-Adapter validation returned no configuration")
        requireFlag("--clip_vision")
        requireFlag("--ip-adapter")
        requireFlag("--ip-adapter-image")
        requireFlag("--ip-adapter-strength")
        args.addAll(listOf("--clip_vision", adapter.clipVisionPath))
        args.addAll(listOf("--ip-adapter", adapter.adapterPath))
        args.addAll(listOf("--ip-adapter-image", adapter.imagePath))
        args.addAll(
            listOf(
                "--ip-adapter-strength",
                formatSdIpAdapterStrength(adapter.strength)
            )
        )
    }

    val promptAdapters = buildList {
        config.loraPath?.let { path ->
            val name = File(path).nameWithoutExtension
            if (name.isNotBlank()) add("<lora:$name:${config.loraStrength}>")
        }
        config.textualInversionPath?.let { path ->
            val token = File(path).nameWithoutExtension
            if (token.isNotBlank()) add(token)
        }
    }
    val effectivePrompt = (promptAdapters + config.prompt)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    args.addAll(listOf("-p", effectivePrompt))
    if (config.negativePrompt.isNotBlank()) {
        args.addAll(listOf("-n", config.negativePrompt))
    }
    args.addAll(listOf("-W", config.width.toString()))
    args.addAll(listOf("-H", config.height.toString()))
    args.addAll(listOf("--steps", config.steps.toString()))
    args.addAll(listOf("--cfg-scale", config.cfgScale.toString()))
    args.addAll(listOf("--sampling-method", config.samplingMethod.cliName))
    config.scheduler?.let {
        requireFlag("--scheduler")
        args.addAll(listOf("--scheduler", it.cliName))
    }
    args.addAll(listOf("-s", config.seed.toString()))

    config.cacheMode?.let { args.addAll(listOf("--cache-mode", it.cliName)) }
    if (config.cacheOption.isNotBlank()) {
        args.addAll(listOf("--cache-option", config.cacheOption))
    }
    if (config.scmMask.isNotBlank()) {
        args.addAll(listOf("--scm-mask", config.scmMask))
    }
    config.scmPolicy?.let { args.addAll(listOf("--scm-policy", it.cliName)) }

    if (config.controlNetPath != null && config.controlImagePath != null) {
        args.addAll(listOf("--control-net", config.controlNetPath))
        args.addAll(listOf("--control-image", config.controlImagePath))
        args.addAll(listOf("--control-strength", config.controlStrength.toString()))
    }

    if (config.loraPath != null) {
        requireFlag("--lora-model-dir")
        args.addAll(
            listOf(
                "--lora-model-dir",
                File(config.loraPath).parent ?: "."
            )
        )
        config.loraApplyMode?.let {
            requireFlag("--lora-apply-mode")
            args.addAll(listOf("--lora-apply-mode", it.cliName))
        }
    }
    if (config.textualInversionPath != null) {
        requireFlag("--embd-dir")
        args.addAll(
            listOf(
                "--embd-dir",
                File(config.textualInversionPath).parent ?: "."
            )
        )
    }

    if (config.quantizationType.isNotBlank()) {
        args.addAll(listOf("--type", config.quantizationType))
    }

    if (config.flowShift != null && spec.supportsFlowShift) {
        requireFlag("--flow-shift")
        args.addAll(listOf("--flow-shift", config.flowShift.toString()))
    }
    if (config.diffusionFa && spec.supportsDiffusionFa) {
        requireFlag("--diffusion-fa")
        args.add("--diffusion-fa")
    }
    if (config.diffusionConvDirect) {
        requireFlag("--diffusion-conv-direct")
        args.add("--diffusion-conv-direct")
    }
    if (config.mmap && spec.supportsMmap) {
        requireFlag("--mmap")
        args.add("--mmap")
    }
    if (config.vaeConvDirect && spec.supportsVaeConvDirect) {
        requireFlag("--vae-conv-direct")
        args.add("--vae-conv-direct")
    }
    if (config.qwenImageZeroCondT && spec.supportsQwenImageZeroCondT) {
        requireFlag("--qwen-image-zero-cond-t")
        args.add("--qwen-image-zero-cond-t")
    }
    if (config.chromaDisableDitMask && spec.supportsChromaDisableDitMask) {
        requireFlag("--chroma-disable-dit-mask")
        args.add("--chroma-disable-dit-mask")
    }

    args.addAll(listOf("-o", config.outputPath))

    if (config.mode == SDMode.IMG2IMG) {
        val input = config.initImage
            ?: throw IllegalStateException("Missing input image")
        when (spec.img2imgInputMode) {
            SdImageInputMode.INIT_IMAGE -> {
                args.addAll(listOf("-i", input))
                args.addAll(listOf("--strength", config.strength.toString()))
            }
            SdImageInputMode.REFERENCE_IMAGE -> {
                requireFlag("-r")
                args.addAll(listOf("-r", input))
            }
        }
    }

    if (config.threads > 0) {
        args.addAll(listOf("-t", config.threads.toString()))
    }

    if (config.vaeTiling) {
        args.add("--vae-tiling")
        args.addAll(listOf("--vae-tile-overlap", config.vaeTileOverlap.toString()))
        if (config.vaeTileSize.isNotBlank()) {
            args.addAll(listOf("--vae-tile-size", config.vaeTileSize))
        }
        if (config.vaeRelativeTileSize.isNotBlank()) {
            args.addAll(listOf("--vae-relative-tile-size", config.vaeRelativeTileSize))
        }
    }

    if (config.tensorTypeRules.isNotBlank()) {
        args.addAll(listOf("--tensor-type-rules", config.tensorTypeRules))
    }

    if (!config.distributedRuntime.enabled) {
        appendLocalSdBackendArgs(
            args = args,
            paramsBackendMode = config.sdParamsBackendMode,
            runtimeBackendMode = config.sdRuntimeBackendMode,
            maxVramCpuGiB = config.maxVramCpuGiB,
            flagSupported = { flag ->
                binaryCapabilities == null ||
                    binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities.supports(flag)
            }
        )
    }
    appendSdDistributedArgs(args, config.distributedRuntime, binaryCapabilities)
    appendSdCustomFlags(args, config.customFlags)
    args.add("-v")

    if (requiredFlags.isNotEmpty()) {
        throw SdUnsupportedFlagsException(requiredFlags.toList().sorted())
    }

    return args
}

fun buildSdUpscaleCommandArgs(
    config: SDUpscaleConfig,
    binaryCapabilities: SdBinaryCapabilities? = null
): List<String> {
    val args = mutableListOf<String>()
    val requiredFlags = mutableSetOf<String>()

    fun requireFlag(flag: String) {
        if (binaryCapabilities != null &&
            binaryCapabilities != SdBinaryCapabilities.ALLOW_ALL &&
            !binaryCapabilities.supports(flag)
        ) {
            requiredFlags += flag
        }
    }

    requireFlag("-M")
    requireFlag("-o")
    requireFlag("-i")
    requireFlag("--upscale-model")
    requireFlag("--upscale-repeats")

    args.addAll(listOf("-M", "upscale"))
    args.addAll(listOf("-o", config.outputPath))
    args.addAll(listOf("-i", config.inputImagePath))
    args.addAll(listOf("--upscale-model", config.modelPath))
    args.addAll(listOf("--upscale-repeats", config.upscaleRepeats.toString()))
    if (config.threads > 0) {
        args.addAll(listOf("-t", config.threads.toString()))
    }
    if (!config.distributedRuntime.enabled) {
        appendLocalSdBackendArgs(
            args = args,
            paramsBackendMode = config.sdParamsBackendMode,
            runtimeBackendMode = config.sdRuntimeBackendMode,
            maxVramCpuGiB = config.maxVramCpuGiB,
            flagSupported = { flag ->
                binaryCapabilities == null ||
                    binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities.supports(flag)
            }
        )
    }
    appendSdDistributedArgs(args, config.distributedRuntime, binaryCapabilities)
    appendSdCustomFlags(args, config.customFlags)
    args.add("-v")

    if (requiredFlags.isNotEmpty()) {
        throw SdUnsupportedFlagsException(requiredFlags.toList().sorted())
    }

    return args
}

fun appendSdCustomFlags(args: MutableList<String>, customFlags: String) {
    if (customFlags.isNotBlank()) {
        args.addAll(splitShellLikeArgs(customFlags))
    }
}

fun appendLocalSdBackendArgs(
    args: MutableList<String>,
    paramsBackendMode: String,
    runtimeBackendMode: String,
    maxVramCpuGiB: String,
    flagSupported: (String) -> Boolean = { true }
) {
    val explicitBackend = runtimeBackendMode.takeIf { it.contains('=') }
    (explicitBackend ?: SdRuntimeBackendMode.fromStoredValue(runtimeBackendMode).cliValue)?.let { backend ->
        if (flagSupported("--backend")) {
            args.addAll(listOf("--backend", backend))
        }
    }
    SdParamsBackendMode.fromStoredValue(paramsBackendMode).cliValue?.let { paramsBackend ->
        if (flagSupported("--params-backend")) {
            args.addAll(listOf("--params-backend", paramsBackend))
        }
    }
    normalizeSdMaxVramCpuGiB(maxVramCpuGiB)?.let { budget ->
        if (flagSupported("--max-vram")) {
            args.addAll(listOf("--max-vram", "cpu=$budget"))
        }
    }
}

fun effectiveSdMaxVramCpuGiBForBinary(sdBinary: File, maxVramCpuGiB: String): String =
    if (DeviceAcceleration.isAcceleratorBinary(sdBinary)) "" else maxVramCpuGiB

/** The upstream device names are `vulkan0` and `opencl0`; CPU keeps text and VAE memory off GPU by default. */
fun effectiveSdRuntimeBackendModeForBinary(sdBinary: File, requested: String): String {
    if (requested.contains('=')) return requested
    return when (sdBinary.name) {
        "libsd_snapdragon_vulkan.so" -> "te=cpu,diffusion=vulkan0,vae=cpu"
        "libsd_snapdragon_opencl.so" -> "te=cpu,diffusion=opencl0,vae=cpu"
        else -> requested
    }
}

fun normalizeSdMaxVramCpuGiB(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val amount = trimmed.toFloatOrNull() ?: return null
    if (amount <= 0f) return null
    return trimmed
}
