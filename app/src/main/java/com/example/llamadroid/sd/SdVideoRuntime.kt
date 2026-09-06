package com.example.llamadroid.sd

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The video architectures understood by the pinned stable-diffusion.cpp
 * binary.  These values are app metadata; native still receives `-M vid_gen`
 * for every family.
 */
enum class SdVideoFamily(val storedValue: String) {
    WAN("wan"),
    HUNYUAN_VIDEO("hunyuan_video"),
    LINGBOT_VIDEO("lingbot_video"),
    LTX_VIDEO("ltx_video"),
    MINIMAX_H3("minimax_h3"),
    SVD("svd"),
    ANIMATEDIFF("animatediff");

    companion object {
        fun fromStoredValue(value: String?): SdVideoFamily? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            }
        }
    }
}

/** User-visible workflows that map to the native `vid_gen` mode. */
enum class SdVideoWorkflow(val storedValue: String) {
    TEXT_TO_VIDEO("text_to_video"),
    IMAGE_TO_VIDEO("image_to_video"),
    FIRST_LAST_FRAME("first_last_frame"),
    VIDEO_TO_VIDEO("video_to_video"),
    REFERENCE_TO_VIDEO("reference_to_video"),
    TEXT_TO_AUDIO_VIDEO("text_to_audio_video"),
    IMAGE_TO_AUDIO_VIDEO("image_to_audio_video"),
    FIRST_LAST_TO_AUDIO_VIDEO("first_last_to_audio_video"),
    REFERENCE_TO_AUDIO_VIDEO("reference_to_audio_video");

    companion object {
        fun fromStoredValue(value: String?): SdVideoWorkflow? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            }
        }
    }
}

/** Text encoders a family expects to be present for conditioning. */
enum class SdVideoTextConditioning {
    NONE,
    T5,
    LLM,
    BOTH
}

/** External model/component roles emitted by the video command builder. */
enum class SdVideoComponentRole(val cliFlag: String?) {
    DIFFUSION_MODEL("--diffusion-model"),
    FULL_MODEL("--model"),
    HIGH_NOISE_DIFFUSION_MODEL("--high-noise-diffusion-model"),
    VAE("--vae"),
    TAE("--tae"),
    T5XXL("--t5xxl"),
    LLM("--llm"),
    LLM_VISION("--llm_vision"),
    AUDIO_VAE("--audio-vae"),
    EMBEDDINGS_CONNECTORS("--embeddings-connectors"),
    MOTION_MODULE("--motion-module"),
    CLIP_VISION("--clip_vision"),
    CONTROL_NET("--control-net"),
    LORA("--lora-model-dir"),
    HIRES_UPSCALER("--hires-upscalers-dir")
}

/** Inputs accepted by the pinned native video path. */
enum class SdVideoInputRole {
    INIT_IMAGE,
    END_IMAGE,
    CONTROL_IMAGE,
    CONTROL_VIDEO,
    REFERENCE_IMAGE,
    REFERENCE_VIDEO,
    REFERENCE_VIDEO_AUDIO,
    REFERENCE_AUDIO
}

enum class SdVideoDecoderKind {
    VAE,
    TAE,
    VAE_OR_TAE
}

/** Decoder compatibility is explicit because TAE weights are family-specific. */
enum class SdVideoDecoderCompatibility {
    NATIVE_VAE_ONLY,
    TAEHV_WAN21,
    TAEHV_WAN22,
    LTX_CONV_VIDEO_VAE
}

enum class SdVideoMainModelLayout {
    STANDALONE_DIFFUSION,
    FULL_MODEL
}

enum class SdVideoOutputFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    WEBM("webm", "video/webm"),
    AVI("avi", "video/x-msvideo")
}

enum class SdVideoNativeOutputFormat(val extension: String, val mimeType: String) {
    AVI("avi", "video/x-msvideo"),
    WEBM("webm", "video/webm"),
    WEBP("webp", "image/webp")
}

enum class SdVideoAudioCodec(val cliName: String) {
    AAC("aac"),
    OPUS("opus"),
    COPY("copy"),
    NONE("none")
}

enum class SdVideoPromptFormat {
    PLAIN,
    LINGBOT_CAPTION_JSON
}

/** All model paths required by a video profile. Null means not selected. */
@Parcelize
data class SdVideoComponentPaths(
    val diffusionModelPath: String? = null,
    val fullModelPath: String? = null,
    val highNoiseDiffusionModelPath: String? = null,
    val uncondDiffusionModelPath: String? = null,
    val ipAdapterPath: String? = null,
    val vaePath: String? = null,
    val taePath: String? = null,
    val t5xxlPath: String? = null,
    val llmPath: String? = null,
    val llmVisionPath: String? = null,
    val audioVaePath: String? = null,
    val embeddingsConnectorsPath: String? = null,
    val motionModulePath: String? = null,
    val clipVisionPath: String? = null,
    val controlNetPath: String? = null,
    val hiresUpscalersDir: String? = null,
    val hiresUpscaler: String? = null
) : Parcelable

/** Input paths are separate from model components so saved workflows remain portable. */
@Parcelize
data class SdVideoInputs(
    val initImagePath: String? = null,
    val endImagePath: String? = null,
    val controlImagePath: String? = null,
    val controlVideoPath: String? = null,
    val referenceImages: List<String> = emptyList(),
    val referenceVideos: List<String> = emptyList(),
    val referenceVideoAudios: List<String> = emptyList(),
    val referenceAudios: List<String> = emptyList(),
    val ipAdapterImagePath: String? = null
) : Parcelable

@Parcelize
data class SdVideoHiresConfig(
    val enabled: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val scale: Float? = null,
    val denoisingStrength: Float? = null,
    val upscaleTileSize: Int? = null,
    val sigmas: String = ""
) : Parcelable

data class SdVideoDefaults(
    val frames: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val flowShift: Float? = null,
    val seed: Long = -1L
)

data class SdVideoPrerequisiteResult(
    val missingComponents: List<SdVideoComponentRole> = emptyList(),
    val missingInputs: List<SdVideoInputRole> = emptyList()
) {
    val isSatisfied: Boolean
        get() = missingComponents.isEmpty() && missingInputs.isEmpty()
}

class SdVideoPrerequisiteException(
    val profile: SdVideoFamilyProfile,
    val result: SdVideoPrerequisiteResult
) : IllegalArgumentException(
    "${profile.family.storedValue} video prerequisites missing: " +
        (result.missingComponents.map { it.name } + result.missingInputs.map { it.name })
            .joinToString(", ")
)

enum class SdVideoWorkflowErrorCode {
    UNSUPPORTED_WORKFLOW,
    NATIVE_WORKFLOW_UNSUPPORTED,
    HIGH_NOISE_UNSUPPORTED,
    HIRES_UNSUPPORTED
}

class SdVideoWorkflowException(
    val family: SdVideoFamily,
    val workflow: SdVideoWorkflow,
    val code: SdVideoWorkflowErrorCode
) : IllegalArgumentException(
    "${family.storedValue} video option ${code.name.lowercase()} for ${workflow.storedValue}"
)

class SdVideoInputException(
    val code: Code,
    val detail: String? = null
) : IllegalArgumentException(
    "Invalid video input: ${code.name.lowercase()}${detail?.let { " ($it)" } ?: ""}"
) {
    enum class Code {
        MODE_REQUIRES_INIT_IMAGE,
        REFERENCE_AUDIO_MISMATCH,
        MINIMAX_REFERENCES_CONFLICT_WITH_KEYFRAMES,
        MINIMAX_CONTROL_VIDEO_UNSUPPORTED,
        FRAMES_MUST_BE_VIDEO,
        INVALID_NUMERIC_VALUE
    }
}

/**
 * A family/profile contract used by the UI and command builder.  A component
 * group represents alternatives, for example a Wan-compatible VAE or TAE.
 */
data class SdVideoFamilyProfile(
    val family: SdVideoFamily,
    val variant: String? = null,
    val displayName: String,
    val mainModelLayout: SdVideoMainModelLayout = SdVideoMainModelLayout.STANDALONE_DIFFUSION,
    val textConditioning: SdVideoTextConditioning = SdVideoTextConditioning.NONE,
    val decoderKind: SdVideoDecoderKind = SdVideoDecoderKind.VAE,
    val decoderCompatibility: SdVideoDecoderCompatibility = SdVideoDecoderCompatibility.NATIVE_VAE_ONLY,
    val supportedWorkflows: Set<SdVideoWorkflow>,
    val requiredComponentGroups: List<Set<SdVideoComponentRole>>,
    val optionalComponents: Set<SdVideoComponentRole> = emptySet(),
    val defaultValues: SdVideoDefaults,
    val supportsHighNoisePass: Boolean = false,
    val supportsAudioOutput: Boolean = false,
    val supportsHires: Boolean = false,
    val supportsVaeTiling: Boolean = true,
    /** False when the pinned native binary recognizes the family but lacks its conditioning path. */
    val nativeWorkflowSupported: Boolean = true,
    val promptFormat: SdVideoPromptFormat = SdVideoPromptFormat.PLAIN,
    /** Exact inputs required by a workflow; each role is an independent requirement. */
    val requiredInputRoles: Map<SdVideoWorkflow, Set<SdVideoInputRole>> = emptyMap(),
    /** Alternatives such as MiniMax Ref2VA accepting an image, video, or audio reference. */
    val requiredInputGroups: Map<SdVideoWorkflow, List<Set<SdVideoInputRole>>> = emptyMap(),
    /** Extra components required only by particular workflows, such as Wan I2V vision weights. */
    val workflowRequiredComponentGroups: Map<SdVideoWorkflow, List<Set<SdVideoComponentRole>>> = emptyMap()
) {
    fun prerequisites(
        components: SdVideoComponentPaths,
        inputs: SdVideoInputs,
        workflow: SdVideoWorkflow
    ): SdVideoPrerequisiteResult {
        val requiredComponents = requiredComponentGroups + workflowRequiredComponentGroups[workflow].orEmpty()
        val missingComponents = requiredComponents
            .filterNot { group -> group.any { components.pathFor(it).isUsable() } }
            .flatMap { it.toList() }
        val availableInputs = inputs.availableRoles()
        val exactInputGroups = requiredInputRoles[workflow].orEmpty().map { setOf(it) }
        val alternativeInputGroups = requiredInputGroups[workflow].orEmpty()
        val missingInputs = (exactInputGroups + alternativeInputGroups)
            .filterNot { group -> group.any(availableInputs::contains) }
            .flatMap { it.toList() }
        return SdVideoPrerequisiteResult(missingComponents, missingInputs)
    }

    fun supports(workflow: SdVideoWorkflow): Boolean = workflow in supportedWorkflows
}

fun SdVideoComponentPaths.pathFor(role: SdVideoComponentRole): String? = when (role) {
    SdVideoComponentRole.DIFFUSION_MODEL -> diffusionModelPath
    SdVideoComponentRole.FULL_MODEL -> fullModelPath
    SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> highNoiseDiffusionModelPath
    SdVideoComponentRole.VAE -> vaePath
    SdVideoComponentRole.TAE -> taePath
    SdVideoComponentRole.T5XXL -> t5xxlPath
    SdVideoComponentRole.LLM -> llmPath
    SdVideoComponentRole.LLM_VISION -> llmVisionPath
    SdVideoComponentRole.AUDIO_VAE -> audioVaePath
    SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> embeddingsConnectorsPath
    SdVideoComponentRole.MOTION_MODULE -> motionModulePath
    SdVideoComponentRole.CLIP_VISION -> clipVisionPath
    SdVideoComponentRole.CONTROL_NET -> controlNetPath
    SdVideoComponentRole.LORA -> null
    SdVideoComponentRole.HIRES_UPSCALER -> hiresUpscaler ?: hiresUpscalersDir
}

private fun String?.isUsable(): Boolean = !isNullOrBlank()

fun SdVideoInputs.availableRoles(): Set<SdVideoInputRole> = buildSet {
    if (initImagePath.isUsable()) add(SdVideoInputRole.INIT_IMAGE)
    if (endImagePath.isUsable()) add(SdVideoInputRole.END_IMAGE)
    if (controlImagePath.isUsable()) add(SdVideoInputRole.CONTROL_IMAGE)
    if (controlVideoPath.isUsable()) add(SdVideoInputRole.CONTROL_VIDEO)
    if (referenceImages.any { it.isNotBlank() }) add(SdVideoInputRole.REFERENCE_IMAGE)
    if (referenceVideos.any { it.isNotBlank() }) add(SdVideoInputRole.REFERENCE_VIDEO)
    if (referenceVideoAudios.any { it.isNotBlank() }) add(SdVideoInputRole.REFERENCE_VIDEO_AUDIO)
    if (referenceAudios.any { it.isNotBlank() }) add(SdVideoInputRole.REFERENCE_AUDIO)
}

fun SdVideoFamilyProfile.requiredRolesFor(workflow: SdVideoWorkflow): Set<SdVideoComponentRole> =
    (requiredComponentGroups + workflowRequiredComponentGroups[workflow].orEmpty())
        .flatten()
        .toSet()

/** Stage labels retain the existing SdLoraSpec high-noise semantics. */
enum class SdVideoLoraStage {
    LOW_NOISE,
    HIGH_NOISE,
    ALL_PASSES
}

fun SdLoraSpec.videoStage(): SdVideoLoraStage =
    if (highNoiseOnly) SdVideoLoraStage.HIGH_NOISE else SdVideoLoraStage.ALL_PASSES

object SdVideoFamilyProfiles {
    val WAN = SdVideoFamilyProfile(
        family = SdVideoFamily.WAN,
        displayName = "Wan",
        textConditioning = SdVideoTextConditioning.T5,
        decoderKind = SdVideoDecoderKind.VAE_OR_TAE,
        decoderCompatibility = SdVideoDecoderCompatibility.TAEHV_WAN21,
        supportedWorkflows = setOf(
            SdVideoWorkflow.TEXT_TO_VIDEO,
            SdVideoWorkflow.IMAGE_TO_VIDEO,
            SdVideoWorkflow.FIRST_LAST_FRAME,
            SdVideoWorkflow.VIDEO_TO_VIDEO
        ),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.DIFFUSION_MODEL),
            setOf(SdVideoComponentRole.T5XXL),
            setOf(SdVideoComponentRole.VAE, SdVideoComponentRole.TAE)
        ),
        optionalComponents = setOf(
            SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL,
            SdVideoComponentRole.CLIP_VISION,
            SdVideoComponentRole.CONTROL_NET
        ),
        defaultValues = SdVideoDefaults(33, 24, 832, 480, 18, 6.0f, 3.0f),
        supportsHighNoisePass = true,
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE),
            SdVideoWorkflow.FIRST_LAST_FRAME to setOf(SdVideoInputRole.INIT_IMAGE, SdVideoInputRole.END_IMAGE),
            SdVideoWorkflow.VIDEO_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE, SdVideoInputRole.CONTROL_VIDEO)
        ),
        workflowRequiredComponentGroups = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to listOf(setOf(SdVideoComponentRole.CLIP_VISION)),
            SdVideoWorkflow.FIRST_LAST_FRAME to listOf(setOf(SdVideoComponentRole.CLIP_VISION))
        )
    )

    val HUNYUAN_VIDEO = SdVideoFamilyProfile(
        family = SdVideoFamily.HUNYUAN_VIDEO,
        displayName = "HunyuanVideo",
        textConditioning = SdVideoTextConditioning.BOTH,
        decoderKind = SdVideoDecoderKind.VAE,
        supportedWorkflows = setOf(SdVideoWorkflow.TEXT_TO_VIDEO),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.DIFFUSION_MODEL),
            setOf(SdVideoComponentRole.LLM),
            setOf(SdVideoComponentRole.T5XXL),
            setOf(SdVideoComponentRole.VAE)
        ),
        defaultValues = SdVideoDefaults(33, 24, 1280, 720, 20, 6.0f),
        supportsHires = false,
        supportsVaeTiling = true
    )

    val LINGBOT_VIDEO = SdVideoFamilyProfile(
        family = SdVideoFamily.LINGBOT_VIDEO,
        variant = "dense_1.3b",
        displayName = "LingBot Video",
        textConditioning = SdVideoTextConditioning.LLM,
        decoderKind = SdVideoDecoderKind.VAE_OR_TAE,
        decoderCompatibility = SdVideoDecoderCompatibility.TAEHV_WAN21,
        supportedWorkflows = setOf(SdVideoWorkflow.TEXT_TO_VIDEO, SdVideoWorkflow.IMAGE_TO_VIDEO),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.DIFFUSION_MODEL),
            setOf(SdVideoComponentRole.LLM),
            setOf(SdVideoComponentRole.VAE, SdVideoComponentRole.TAE)
        ),
        defaultValues = SdVideoDefaults(9, 4, 256, 144, 12, 3.0f, 3.0f, 42L),
        promptFormat = SdVideoPromptFormat.LINGBOT_CAPTION_JSON,
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE)
        )
    )

    val LTX_VIDEO = SdVideoFamilyProfile(
        family = SdVideoFamily.LTX_VIDEO,
        displayName = "LTX Video",
        textConditioning = SdVideoTextConditioning.LLM,
        decoderKind = SdVideoDecoderKind.VAE,
        decoderCompatibility = SdVideoDecoderCompatibility.LTX_CONV_VIDEO_VAE,
        supportedWorkflows = setOf(
            SdVideoWorkflow.TEXT_TO_VIDEO,
            SdVideoWorkflow.IMAGE_TO_VIDEO,
            SdVideoWorkflow.FIRST_LAST_FRAME
        ),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.DIFFUSION_MODEL),
            setOf(SdVideoComponentRole.LLM),
            setOf(SdVideoComponentRole.VAE),
            setOf(SdVideoComponentRole.AUDIO_VAE),
            setOf(SdVideoComponentRole.EMBEDDINGS_CONNECTORS)
        ),
        optionalComponents = setOf(SdVideoComponentRole.HIRES_UPSCALER),
        defaultValues = SdVideoDefaults(33, 24, 1280, 720, 20, 6.0f),
        supportsAudioOutput = true,
        supportsHires = true,
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE),
            SdVideoWorkflow.FIRST_LAST_FRAME to setOf(SdVideoInputRole.INIT_IMAGE, SdVideoInputRole.END_IMAGE)
        )
    )

    val MINIMAX_H3 = SdVideoFamilyProfile(
        family = SdVideoFamily.MINIMAX_H3,
        displayName = "MiniMax-H3",
        textConditioning = SdVideoTextConditioning.LLM,
        decoderKind = SdVideoDecoderKind.VAE,
        supportedWorkflows = setOf(
            SdVideoWorkflow.TEXT_TO_AUDIO_VIDEO,
            SdVideoWorkflow.IMAGE_TO_AUDIO_VIDEO,
            SdVideoWorkflow.FIRST_LAST_TO_AUDIO_VIDEO,
            SdVideoWorkflow.REFERENCE_TO_AUDIO_VIDEO
        ),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.DIFFUSION_MODEL),
            setOf(SdVideoComponentRole.LLM),
            setOf(SdVideoComponentRole.VAE)
        ),
        optionalComponents = setOf(SdVideoComponentRole.AUDIO_VAE, SdVideoComponentRole.LLM_VISION),
        defaultValues = SdVideoDefaults(56, 24, 864, 480, 20, 1.0f),
        supportsAudioOutput = true,
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_AUDIO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE),
            SdVideoWorkflow.FIRST_LAST_TO_AUDIO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE, SdVideoInputRole.END_IMAGE),
        ),
        requiredInputGroups = mapOf(
            SdVideoWorkflow.REFERENCE_TO_AUDIO_VIDEO to listOf(
                setOf(
                    SdVideoInputRole.REFERENCE_IMAGE,
                    SdVideoInputRole.REFERENCE_VIDEO,
                    SdVideoInputRole.REFERENCE_AUDIO
                )
            )
        )
    )

    val SVD = SdVideoFamilyProfile(
        family = SdVideoFamily.SVD,
        displayName = "Stable Video Diffusion",
        mainModelLayout = SdVideoMainModelLayout.FULL_MODEL,
        textConditioning = SdVideoTextConditioning.NONE,
        decoderKind = SdVideoDecoderKind.VAE,
        supportedWorkflows = setOf(SdVideoWorkflow.IMAGE_TO_VIDEO),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.FULL_MODEL),
            setOf(SdVideoComponentRole.VAE)
        ),
        defaultValues = SdVideoDefaults(14, 6, 576, 1024, 25, 2.5f),
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE)
        ),
        // The pinned snapshot recognizes SVD and routes it through vid_gen,
        // but has no SVD-specific image-conditioning branch in
        // prepare_video_generation_latents. Keep inspection/catalog support
        // while preventing a misleading generation launch.
        nativeWorkflowSupported = false
    )

    val ANIMATEDIFF = SdVideoFamilyProfile(
        family = SdVideoFamily.ANIMATEDIFF,
        displayName = "AnimateDiff",
        mainModelLayout = SdVideoMainModelLayout.FULL_MODEL,
        textConditioning = SdVideoTextConditioning.NONE,
        decoderKind = SdVideoDecoderKind.VAE,
        supportedWorkflows = setOf(SdVideoWorkflow.TEXT_TO_VIDEO, SdVideoWorkflow.IMAGE_TO_VIDEO),
        requiredComponentGroups = listOf(
            setOf(SdVideoComponentRole.FULL_MODEL),
            setOf(SdVideoComponentRole.MOTION_MODULE)
        ),
        optionalComponents = setOf(SdVideoComponentRole.VAE),
        defaultValues = SdVideoDefaults(16, 8, 512, 512, 20, 8.0f),
        requiredInputRoles = mapOf(
            SdVideoWorkflow.IMAGE_TO_VIDEO to setOf(SdVideoInputRole.INIT_IMAGE)
        )
    )

    val all: List<SdVideoFamilyProfile> = listOf(
        WAN,
        HUNYUAN_VIDEO,
        LINGBOT_VIDEO,
        LTX_VIDEO,
        MINIMAX_H3,
        SVD,
        ANIMATEDIFF
    )

    fun resolve(family: SdVideoFamily, variant: String? = null): SdVideoFamilyProfile {
        val base = all.first { it.family == family }
        return when {
            variant.isNullOrBlank() || base.variant.equals(variant, ignoreCase = true) -> base
            family == SdVideoFamily.LTX_VIDEO && variant.contains("2.5", ignoreCase = true) -> {
                base.copy(
                    variant = variant,
                    requiredComponentGroups = base.requiredComponentGroups.filterNot { group ->
                        group.contains(SdVideoComponentRole.EMBEDDINGS_CONNECTORS)
                    },
                    defaultValues = SdVideoDefaults(121, 24, 1280, 720, 20, 3.0f)
                )
            }
            family == SdVideoFamily.WAN -> resolveWanVariant(base, variant)
            family == SdVideoFamily.HUNYUAN_VIDEO -> {
                // The pinned native docs expose HunyuanVideo 1.5: Qwen2.5-VL
                // plus ByT5, a diffusion transformer, and a causal video VAE.
                // Keep the explicit variant for catalog matching while using
                // the same validated component contract.
                base.copy(variant = variant)
            }
            else -> base.copy(variant = variant)
        }
    }

    private fun resolveWanVariant(
        base: SdVideoFamilyProfile,
        variant: String
    ): SdVideoFamilyProfile {
        val key = variant.lowercase().replace('-', '_').replace(' ', '_')
        val isWan22 = "2.2" in key || "wan22" in key || "wan_2_2" in key
        if (!isWan22) return base.copy(variant = variant)

        val isTi2v = "ti2v" in key || "t2i" in key
        if (isTi2v) {
            return base.copy(
                variant = variant,
                decoderCompatibility = SdVideoDecoderCompatibility.TAEHV_WAN22,
                supportedWorkflows = setOf(
                    SdVideoWorkflow.TEXT_TO_VIDEO,
                    SdVideoWorkflow.IMAGE_TO_VIDEO
                ),
                workflowRequiredComponentGroups = emptyMap(),
                defaultValues = SdVideoDefaults(33, 24, 1280, 720, 20, 5.0f)
            )
        }

        // Wan2.2 A14B T2V/I2V uses the low-noise model plus a required
        // high-noise model. Native's I2V/FLF path does not request CLIP
        // vision for this variant; only the Wan2.1 descriptors do.
        return base.copy(
            variant = variant,
            decoderCompatibility = SdVideoDecoderCompatibility.TAEHV_WAN21,
            requiredComponentGroups = base.requiredComponentGroups +
                listOf(setOf(SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL)),
            workflowRequiredComponentGroups = emptyMap(),
            defaultValues = SdVideoDefaults(33, 24, 832, 480, 10, 3.5f, 3.0f)
        )
    }

    /** Curated component names used by the LingBot dense 1.3B example. */
    val LINGBOT_DENSE_1_3B_COMPONENTS = SdVideoComponentPaths(
        diffusionModelPath = "lingbot-video-dense-1.3b.safetensors",
        llmPath = "Qwen3-VL-4B-Instruct-Q4_K_M.gguf",
        taePath = "taew2_1.safetensors"
    )
}

fun SdVideoFamilyProfile.validate(
    components: SdVideoComponentPaths,
    inputs: SdVideoInputs,
    workflow: SdVideoWorkflow
): SdVideoPrerequisiteResult = prerequisites(components, inputs, workflow)
