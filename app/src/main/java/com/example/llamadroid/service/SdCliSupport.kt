package com.example.llamadroid.service

import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdParamsBackendMode
import com.example.llamadroid.sd.resolveSdParamsBackendProfile
import com.example.llamadroid.sd.SdRuntimeBackendMode
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.SdImageInputMode
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdResolvedPipeline
import com.example.llamadroid.sd.resolveValidatedSdPipeline
import com.example.llamadroid.sd.SdPipelineValidationException
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.sd.activeInOrder
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.util.DeviceAcceleration
import java.io.File
import java.util.Locale

data class SdBinaryCapabilities(
    val supportedFlags: Set<String>,
    val supportedModes: Set<String> = emptySet(),
    val allowAll: Boolean = false,
    /** New stable-diffusion.cpp parses `--auto-fit` as an `on|off` value. */
    val autoFitRequiresValue: Boolean = false
) {
    fun supports(flag: String): Boolean = allowAll || supportedFlags.contains(flag)

    /** `-M` values explicitly advertised by the installed stable-diffusion.cpp binary. */
    fun supportsMode(mode: String): Boolean =
        allowAll || mode.lowercase(Locale.US) in supportedModes

    companion object {
        val ALLOW_ALL = SdBinaryCapabilities(
            emptySet(),
            allowAll = true,
            autoFitRequiresValue = true
        )
    }
}

class SdMissingComponentsException(
    val roles: List<SdComponentRole>
) : IllegalStateException("Missing required components: ${roles.joinToString(", ") { it.name }}")

class SdUnsupportedFlagsException(
    val flags: List<String>
) : IllegalStateException("Unsupported stable-diffusion.cpp flags: ${flags.joinToString(", ")}")

class SdUnsupportedModesException(
    val modes: List<String>
) : IllegalStateException("Unsupported stable-diffusion.cpp modes: ${modes.joinToString(", ")}")

class SdDisallowedDistributedFlagException(
    val flag: String,
    override val message: String = "Disallowed distributed stable-diffusion.cpp flag: $flag"
) : IllegalStateException(message)

enum class SdReferenceImageIssue { LIMIT_EXCEEDED, BLANK_PATH }

class SdReferenceImageException(val issue: SdReferenceImageIssue) : IllegalArgumentException(issue.name)

fun parseSdBinaryCapabilities(helpText: String): SdBinaryCapabilities {
    val flagRegex = Regex("""(?<![A-Za-z0-9_-])(--[A-Za-z0-9][A-Za-z0-9_-]*|-[A-Za-z])(?![A-Za-z0-9_-])""")
    val nativeModeRegex = Regex("""(?i)\b(?:txt2img|img2img|img_gen|upscale|adetailer|txt2vid|img2vid|vid_gen)\b""")
    val autoFitOption = Regex("(?m)^\\s*--auto-fit\\b").find(helpText)
    val autoFitHelp = if (autoFitOption != null) {
        val optionEnd = helpText.indexOf('\n', autoFitOption.range.last)
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: helpText.length
        val nextOption = Regex("(?m)^\\s*(?:-[^\\s,]+\\s*,\\s*)?--[A-Za-z0-9][A-Za-z0-9_-]*\\b")
            .find(helpText, optionEnd)
        helpText.substring(autoFitOption.range.first, nextOption?.range?.first ?: helpText.length)
    } else {
        ""
    }
    return SdBinaryCapabilities(
        supportedFlags = flagRegex.findAll(helpText).map { it.value }.toSet(),
        supportedModes = nativeModeRegex.findAll(helpText)
            .map { it.value.lowercase(Locale.US) }
            .toSet(),
        autoFitRequiresValue = autoFitHelp.contains("on|off", ignoreCase = true) ||
            autoFitHelp.contains("on or off", ignoreCase = true)
    )
}

fun inferSdFamilyForConfig(config: SDConfig): Pair<SdModelFamily?, String?> {
    val explicitFamily = SdModelFamily.fromStoredValue(config.modelFamily)
    if (explicitFamily != null) {
        return explicitFamily to config.modelVariant
    }

    val inferredType = when {
        config.llmPath != null || config.clipLPath != null || config.clipGPath != null || config.t5xxlPath != null ->
            ModelType.SD_DIFFUSION
        else -> ModelType.SD_CHECKPOINT
    }
    val inferred = inferSdFamily(inferredType, config.modelPath, File(config.modelPath).name)
    return inferred
}

/**
 * Compatibility entry point: resolve and validate before constructing native
 * arguments.  New process runners should resolve the pipeline once and pass it
 * to the overload below so diagnostics and command construction share exactly
 * the same structural decision.
 */
fun buildSdCommandArgs(
    config: SDConfig,
    binaryCapabilities: SdBinaryCapabilities? = null
): List<String> = buildSdCommandArgs(
    config = config,
    pipeline = if (config.mode == SDMode.UPSCALE) {
        SdResolvedPipeline(
            family = null,
            variant = null,
            mainModelPath = config.modelPath,
            mainLayout = SdMainLayout.UNKNOWN,
            requiredExternalRoles = emptySet(),
            optionalExternalRoles = emptySet(),
            resolvedComponents = emptyMap()
        )
    } else {
        resolveValidatedSdPipeline(config)
    },
    binaryCapabilities = binaryCapabilities
)

/** Build arguments from an already validated pipeline; never infer packaging here. */
fun buildSdCommandArgs(
    config: SDConfig,
    pipeline: SdResolvedPipeline,
    binaryCapabilities: SdBinaryCapabilities? = null
): List<String> {
    val args = mutableListOf<String>()
    val requiredFlags = mutableSetOf<String>()
    val requiredModes = mutableSetOf<String>()

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

    fun requireMode(mode: String) {
        if (binaryCapabilities != null &&
            binaryCapabilities != SdBinaryCapabilities.ALLOW_ALL &&
            binaryCapabilities.supportedModes.isNotEmpty() &&
            !binaryCapabilities.supportsMode(mode)
        ) {
            requiredModes += mode
        }
    }

    when (config.mode) {
        SDMode.TXT2IMG, SDMode.IMG2IMG -> {
            requireFlag("-M")
            requireMode("img_gen")
            args.addAll(listOf("-M", "img_gen"))
        }
        SDMode.ADETAILER -> {
            requireFlag("-M")
            requireMode("adetailer")
            args.addAll(listOf("-M", "adetailer"))
        }
        SDMode.UPSCALE -> {
            requireFlag("-M")
            requireMode("upscale")
            args.addAll(listOf("-M", "upscale"))
        }
    }

    if (config.mode == SDMode.UPSCALE) {
        requireFlag("-o")
        args.addAll(listOf("-o", config.outputPath))
        config.initImage?.let {
            requireFlag("-i")
            args.addAll(listOf("-i", it))
        }
        config.upscaleModel?.let {
            requireFlag("--upscale-model")
            args.addAll(listOf("--upscale-model", it))
        }
        requireFlag("--upscale-repeats")
        args.addAll(listOf("--upscale-repeats", config.upscaleRepeats.toString()))
        if (config.threads > 0) {
            requireFlag("-t")
            args.addAll(listOf("-t", config.threads.toString()))
        }
        if (!config.distributedRuntime.enabled) {
            appendSdAutoFitArg(
                args = args,
                enabled = false,
                binaryCapabilities = binaryCapabilities,
                flagSupported = { flag ->
                    binaryCapabilities == null ||
                        binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                        binaryCapabilities.supports(flag)
                },
                onUnsupported = { requiredFlags += "--auto-fit" }
            )
            appendLocalSdBackendArgs(
                args = args,
                paramsBackendSpec = config.sdParamsBackendSpec,
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
        if (requiredModes.isNotEmpty()) {
            throw SdUnsupportedModesException(requiredModes.toList().sorted())
        }
        return args
    }

    pipeline.requireValid()

    val family = pipeline.family
        ?: throw SdPipelineValidationException(pipeline)
    val variant = pipeline.variant
    val dimensionMultiple = requiredSdImageDimensionMultiple(family.storedValue, variant)
    if (dimensionMultiple > 8 &&
        !isValidSdImageDimensions(config.width, config.height, dimensionMultiple)
    ) {
        throw SdImageDimensionException(config.width, config.height, dimensionMultiple)
    }
    val spec = pipeline.spec ?: resolveSdFamilySpec(family, variant)
    val adetailerConfig = config.adetailer?.let { raw ->
        val configured = if (config.mode == SDMode.ADETAILER) {
            raw.copy(
                inpaintWidth = raw.inpaintWidth ?: config.width,
                inpaintHeight = raw.inpaintHeight ?: config.height
            )
        } else {
            raw
        }
        validateSdADetailerConfig(configured)
    }
    val baseLoras = config.resolvedLoras().also { validateSdLoras(it) }
    val detailLoras = adetailerConfig?.loras.orEmpty().also { validateSdLoras(it) }
    val missingComponents = pipeline.requiredExternalRoles.filter { role ->
        pipeline.pathForRole(role).isNullOrBlank()
    }
    if (missingComponents.isNotEmpty()) {
        throw SdMissingComponentsException(missingComponents)
    }

    when (pipeline.mainLayout) {
        SdMainLayout.FULL_MODEL -> {
            requireFlag("-m")
            args.addAll(listOf("-m", pipeline.mainModelPath))
        }
        SdMainLayout.STANDALONE_DIFFUSION -> {
            requireFlag("--diffusion-model")
            args.addAll(listOf("--diffusion-model", pipeline.mainModelPath))
        }
        else -> throw SdPipelineValidationException(pipeline)
    }

    pipeline.pathForRole(SdComponentRole.VAE)?.let {
        requireFlag("--vae")
        args.addAll(listOf("--vae", it))
    }
    pipeline.pathForRole(SdComponentRole.TAE)?.let {
        // TAESD is a decode-only VAE; TAE/TAEHV remain the family component path.
        val decoderFlag = if (File(it).name.contains("taesd", ignoreCase = true)) "--taesd" else "--tae"
        requireFlag(decoderFlag)
        args.addAll(listOf(decoderFlag, it))
    }
    pipeline.pathForRole(SdComponentRole.CLIP_L)?.let {
        requireFlag("--clip_l")
        args.addAll(listOf("--clip_l", it))
    }
    pipeline.pathForRole(SdComponentRole.CLIP_G)?.let {
        requireFlag("--clip_g")
        args.addAll(listOf("--clip_g", it))
    }
    pipeline.pathForRole(SdComponentRole.T5XXL)?.let {
        requireFlag("--t5xxl")
        args.addAll(listOf("--t5xxl", it))
    }
    pipeline.pathForRole(SdComponentRole.LLM)?.let {
        requireFlag("--llm")
        args.addAll(listOf("--llm", it))
    }
    pipeline.pathForRole(SdComponentRole.LLM_VISION)?.let {
        requireFlag("--llm_vision")
        args.addAll(listOf("--llm_vision", it))
    }
    pipeline.pathForRole(SdComponentRole.PHOTOMAKER)?.let {
        requireFlag("--photo-maker")
        args.addAll(listOf("--photo-maker", it))
    }
    pipeline.vaeFormatOverride?.let {
        requireFlag("--vae-format")
        args.addAll(listOf("--vae-format", it))
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

    fun loraPromptTokens(items: List<SdLoraSpec>): List<String> = items.activeInOrder().mapNotNull { item ->
        item.promptTokenName.takeIf { it.isNotBlank() }?.let { name ->
            "<lora:$name:${formatSdLoraStrength(item.strength)}>"
        }
    }

    val promptAdapters = buildList {
        addAll(loraPromptTokens(baseLoras))
        config.textualInversionPath?.let { path ->
            val token = File(path).nameWithoutExtension
            if (token.isNotBlank()) add(token)
        }
    }
    val effectivePrompt = (promptAdapters + config.prompt)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    requireFlag("-p")
    args.addAll(listOf("-p", effectivePrompt))
    if (config.negativePrompt.isNotBlank()) {
        requireFlag("-n")
        args.addAll(listOf("-n", config.negativePrompt))
    }
    config.maskImage?.let {
        requireFlag("--mask")
        args.addAll(listOf("--mask", it))
    }
    config.imgCfgScale?.takeIf { spec.supportsImgCfgScale }?.let {
        requireFlag("--img-cfg-scale")
        args.addAll(listOf("--img-cfg-scale", it.toString()))
    }
    adetailerConfig?.let { ad ->
        requireFlag("--ad-model")
        requireFlag("--ad-prompt")
        requireFlag("--ad-negative-prompt")
        requireFlag("--extra-ad-args")
        args.addAll(listOf("--ad-model", ad.modelPath))
        val detailPrompt = (loraPromptTokens(detailLoras) + ad.prompt)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (detailPrompt.isNotBlank()) args.addAll(listOf("--ad-prompt", detailPrompt))
        if (ad.negativePrompt.isNotBlank()) args.addAll(listOf("--ad-negative-prompt", ad.negativePrompt))
        args.addAll(
            listOf(
                "--extra-ad-args",
                serializeSdADetailerExtraArgs(
                    config = ad,
                    includeDenoisingStrength = config.mode != SDMode.ADETAILER
                )
            )
        )
    }
    if (config.mode != SDMode.ADETAILER || config.adetailerResizeInput) {
        requireFlag("-W")
        requireFlag("-H")
        args.addAll(listOf("-W", config.width.toString()))
        args.addAll(listOf("-H", config.height.toString()))
    }
    requireFlag("--steps")
    requireFlag("--cfg-scale")
    requireFlag("--sampling-method")
    args.addAll(listOf("--steps", config.steps.toString()))
    args.addAll(listOf("--cfg-scale", config.cfgScale.toString()))
    args.addAll(listOf("--sampling-method", config.samplingMethod.cliName))
    config.scheduler?.let {
        requireFlag("--scheduler")
        args.addAll(listOf("--scheduler", it.cliName))
    }
    requireFlag("-s")
    args.addAll(listOf("-s", config.seed.toString()))

    config.cacheMode?.let {
        requireFlag("--cache-mode")
        args.addAll(listOf("--cache-mode", it.cliName))
    }
    if (config.cacheOption.isNotBlank()) {
        requireFlag("--cache-option")
        args.addAll(listOf("--cache-option", config.cacheOption))
    }
    if (config.scmMask.isNotBlank()) {
        requireFlag("--scm-mask")
        args.addAll(listOf("--scm-mask", config.scmMask))
    }
    config.scmPolicy?.let {
        requireFlag("--scm-policy")
        args.addAll(listOf("--scm-policy", it.cliName))
    }

    if (config.controlNetPath != null && config.controlImagePath != null) {
        requireFlag("--control-net")
        requireFlag("--control-image")
        requireFlag("--control-strength")
        args.addAll(listOf("--control-net", config.controlNetPath))
        args.addAll(listOf("--control-image", config.controlImagePath))
        args.addAll(listOf("--control-strength", config.controlStrength.toString()))
    }

    val allLoras = baseLoras + detailLoras
    val loraDirectories = allLoras
        .activeInOrder()
        .mapNotNull { item -> File(item.path).parent?.takeIf { it.isNotBlank() } }
        .distinct()
    if (loraDirectories.isNotEmpty()) {
        requireFlag("--lora-model-dir")
        loraDirectories.forEach { directory ->
            args.addAll(listOf("--lora-model-dir", directory))
        }
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
        requireFlag("--type")
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
    // stable-diffusion.cpp 6b3edaa removed the old family-specific switches
    // from the CLI. These model toggles now share the typed key=value
    // `--model-args` surface advertised by the packaged binary. An explicit
    // help capability set may still describe an older custom binary, so keep
    // its direct switches as a compatibility fallback only when that help
    // proves they are supported.
    val modelArgs = buildList<Pair<String, String>> {
        if (config.qwenImageZeroCondT && spec.supportsQwenImageZeroCondT) {
            add("qwen_image_zero_cond_t=true" to "--qwen-image-zero-cond-t")
        }
        if (config.chromaDisableDitMask && spec.supportsChromaDisableDitMask) {
            add("chroma_use_dit_mask=false" to "--chroma-disable-dit-mask")
        }
    }
    if (modelArgs.isNotEmpty()) {
        val useModelArgs = binaryCapabilities == null ||
            binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
            binaryCapabilities.supports("--model-args")
        if (useModelArgs) {
            requireFlag("--model-args")
            args.addAll(listOf("--model-args", modelArgs.joinToString(",") { it.first }))
        } else {
            modelArgs.forEach { (_, legacyFlag) ->
                requireFlag(legacyFlag)
                args.add(legacyFlag)
            }
        }
    }

    requireFlag("-o")
    args.addAll(listOf("-o", config.outputPath))

    if (config.mode == SDMode.IMG2IMG ||
        (config.mode == SDMode.ADETAILER && config.initImage != null)
    ) {
        val input = config.initImage
            ?: throw IllegalStateException("Missing input image")
        when (spec.img2imgInputMode) {
            SdImageInputMode.INIT_IMAGE -> {
                requireFlag("-i")
                requireFlag("--strength")
                args.addAll(listOf("-i", input))
                val effectiveStrength = if (config.mode == SDMode.ADETAILER) {
                    adetailerConfig?.denoisingStrength ?: config.strength
                } else {
                    config.strength
                }
                args.addAll(listOf("--strength", effectiveStrength.toString()))
            }
            SdImageInputMode.REFERENCE_IMAGE -> {
                val references = config.referenceImages
                    .ifEmpty { listOf(input) }
                if (references.size > 10) {
                    throw SdReferenceImageException(SdReferenceImageIssue.LIMIT_EXCEEDED)
                }
                if (references.any(String::isBlank)) {
                    throw SdReferenceImageException(SdReferenceImageIssue.BLANK_PATH)
                }
                references.forEach { reference ->
                    requireFlag("-r")
                    args.addAll(listOf("-r", reference))
                }
            }
        }
    }

    if (config.threads > 0) {
        requireFlag("-t")
        args.addAll(listOf("-t", config.threads.toString()))
    }

    if (config.vaeTiling) {
        requireFlag("--vae-tiling")
        requireFlag("--vae-tile-overlap")
        args.add("--vae-tiling")
        args.addAll(listOf("--vae-tile-overlap", config.vaeTileOverlap.toString()))
        if (config.vaeTileSize.isNotBlank()) {
            requireFlag("--vae-tile-size")
            args.addAll(listOf("--vae-tile-size", config.vaeTileSize))
        }
        if (config.vaeRelativeTileSize.isNotBlank()) {
            requireFlag("--vae-relative-tile-size")
            args.addAll(listOf("--vae-relative-tile-size", config.vaeRelativeTileSize))
        }
    }

    if (config.tensorTypeRules.isNotBlank()) {
        requireFlag("--tensor-type-rules")
        args.addAll(listOf("--tensor-type-rules", config.tensorTypeRules))
    }

    if (!config.distributedRuntime.enabled) {
        appendSdAutoFitArg(
            args = args,
            enabled = false,
            binaryCapabilities = binaryCapabilities,
            flagSupported = { flag ->
                binaryCapabilities == null ||
                    binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities.supports(flag)
            },
            onUnsupported = { requiredFlags += "--auto-fit" }
        )
        appendLocalSdBackendArgs(
            args = args,
            paramsBackendSpec = config.sdParamsBackendSpec,
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
    if (requiredModes.isNotEmpty()) {
        throw SdUnsupportedModesException(requiredModes.toList().sorted())
    }

    return args
}

private fun formatSdLoraStrength(value: Float): String =
    String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')

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
        appendSdAutoFitArg(
            args = args,
            enabled = false,
            binaryCapabilities = binaryCapabilities,
            flagSupported = { flag ->
                binaryCapabilities == null ||
                    binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL ||
                    binaryCapabilities.supports(flag)
            },
            onUnsupported = { requiredFlags += "--auto-fit" }
        )
        appendLocalSdBackendArgs(
            args = args,
            paramsBackendSpec = config.sdParamsBackendSpec,
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

/**
 * Keep the app's pre-refresh default (manual backend placement) across the
 * stable-diffusion.cpp auto-fit default change. Older binaries exposed this
 * as a bare boolean flag, while refreshed binaries require `on` or `off`.
 */
internal fun appendSdAutoFitArg(
    args: MutableList<String>,
    enabled: Boolean,
    binaryCapabilities: SdBinaryCapabilities?,
    flagSupported: (String) -> Boolean = { true },
    onUnsupported: () -> Unit = {}
) {
    val requiresValue = sdAutoFitUsesValueSyntax(binaryCapabilities)
    if (!requiresValue && !enabled) return
    if (!flagSupported("--auto-fit")) {
        onUnsupported()
        return
    }
    if (requiresValue) {
        args.addAll(listOf("--auto-fit", if (enabled) "on" else "off"))
    } else if (enabled) {
        args.add("--auto-fit")
    }
}

internal fun sdAutoFitUsesValueSyntax(binaryCapabilities: SdBinaryCapabilities?): Boolean =
    binaryCapabilities == null ||
        binaryCapabilities.allowAll ||
        binaryCapabilities.autoFitRequiresValue

fun appendLocalSdBackendArgs(
    args: MutableList<String>,
    paramsBackendMode: String,
    runtimeBackendMode: String,
    maxVramCpuGiB: String,
    paramsBackendSpec: String = "auto",
    flagSupported: (String) -> Boolean = { true }
) {
    val explicitBackend = runtimeBackendMode.takeIf { it.contains('=') }
    (explicitBackend ?: SdRuntimeBackendMode.fromStoredValue(runtimeBackendMode).cliValue)?.let { backend ->
        if (flagSupported("--backend")) {
            args.addAll(listOf("--backend", backend))
        }
    }
    resolveSdParamsBackendProfile(
        spec = paramsBackendSpec,
        legacyMode = paramsBackendMode
    ).cliValue?.let { paramsBackend ->
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
