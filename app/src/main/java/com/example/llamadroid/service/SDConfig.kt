package com.example.llamadroid.service

import android.os.Parcelable
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdLoraApplyMode
import kotlinx.parcelize.Parcelize

@Parcelize
data class SdIpAdapterConfig(
    val adapterPath: String,
    val clipVisionPath: String,
    val imagePath: String,
    val strength: Float = 1.0f
) : Parcelable

/**
 * Configuration for stable-diffusion.cpp image generation
 */
@Parcelize
data class SDConfig(
    val modelPath: String,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1, // -1 for random
    val samplingMethod: SamplingMethod = SamplingMethod.EULER_A,
    val scheduler: SdScheduler? = null,
    val outputPath: String,
    // img2img specific
    val initImage: String? = null,
    val maskImage: String? = null,
    val strength: Float = 0.75f,
    val imgCfgScale: Float? = null,
    // Upscale specific
    val upscaleModel: String? = null,
    val upscaleRepeats: Int = 1,
    // Mode
    val mode: SDMode = SDMode.TXT2IMG,
    // Performance
    val threads: Int = -1, // -1 for auto
    val vaeTiling: Boolean = false, // For low memory
    val vaeTileOverlap: Float = 0.5f,
    val vaeTileSize: String = "32x32",
    val vaeRelativeTileSize: String = "",
    val tensorTypeRules: String = "",
    val cacheMode: SdCacheMode? = null,
    val cacheOption: String = "",
    val scmMask: String = "",
    val scmPolicy: SdCacheScmPolicy? = null,
    // Backward-compatible hint kept while older UI paths are migrated.
    val isFluxModel: Boolean = false,
    // Family metadata and components
    val modelFamily: String? = null,
    val modelVariant: String? = null,
    val vaePath: String? = null,
    val taePath: String? = null,
    val clipLPath: String? = null,
    val clipGPath: String? = null,
    val t5xxlPath: String? = null,
    val llmPath: String? = null,
    val llmVisionPath: String? = null,
    // ControlNet (optional)
    val controlNetPath: String? = null,
    val controlImagePath: String? = null,
    val controlStrength: Float = 0.9f,
    // LoRA (optional)
    val loraPath: String? = null,
    val loraStrength: Float = 1.0f,
    val loraApplyMode: SdLoraApplyMode? = null,
    /** Ordered multi-LoRA list. Empty means use the legacy single-LoRA fields. */
    val loras: List<SdLoraSpec> = emptyList(),
    // Textual inversion embedding (optional). stable-diffusion.cpp loads all
    // embeddings from its parent directory and resolves this file's stem as a
    // prompt token.
    val textualInversionPath: String? = null,
    // PhotoMaker (optional)
    val photoMakerPath: String? = null,
    // IP-Adapter (classic and Plus share the same upstream CLI flags)
    val ipAdapter: SdIpAdapterConfig? = null,
    val adetailer: SdADetailerConfig? = null,
    // Dedicated ADetailer preserves the source resolution unless the user
    // explicitly opts into resizing the entire source before detection.
    val adetailerResizeInput: Boolean = false,
    // Family-specific runtime flags
    val flowShift: Float? = null,
    val diffusionFa: Boolean = false,
    val diffusionConvDirect: Boolean = false,
    val mmap: Boolean = false,
    val vaeConvDirect: Boolean = false,
    val qwenImageZeroCondT: Boolean = false,
    val chromaDisableDitMask: Boolean = false,
    val sdParamsBackendMode: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    // Quantization type for stable-diffusion.cpp (--type)
    val quantizationType: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    val customFlags: String = "",
    // User-facing operation metadata remains separate from the native CLI mode.
    val operation: String? = null,
    val sourceTransform: String? = null,
    val maskProvenance: String? = null,
    val maskPolarity: String? = null,
    // Curated workflow provenance is copied into the sidecar metadata. It is
    // nullable so older saved commands and non-curated generations remain valid.
    val workflowPresetId: String? = null,
    val workflowBundleId: String? = null,
    val workflowRevision: String? = null
) : Parcelable

/** Resolve old saved commands/drafts into the ordered representation. */
fun SDConfig.resolvedLoras(): List<SdLoraSpec> =
    if (loras.isNotEmpty()) loras else SdLoraSpec.fromLegacy(loraPath, loraStrength)

@Parcelize
data class SDWorkflowConfig(
    val txt2imgConfig: SDConfig,
    val upscaleConfig: SDConfig
) : Parcelable

@Parcelize
data class SDUpscaleConfig(
    val modelPath: String,
    val inputImagePath: String,
    val outputPath: String,
    val upscaleRepeats: Int = 1,
    val threads: Int = -1,
    val sdParamsBackendMode: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    val maxVramCpuGiB: String = "",
    val distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    val customFlags: String = ""
) : Parcelable

enum class SamplingMethod(val cliName: String) {
    EULER("euler"),
    EULER_A("euler_a"),
    HEUN("heun"),
    DPM2("dpm2"),
    DPM_PP_2S_A("dpm++2s_a"),
    DPM_PP_2M("dpm++2m"),
    DPM_PP_2M_V2("dpm++2mv2"),
    LCM("lcm"),
    DDIM_TRAILING("ddim_trailing")
}

enum class SdScheduler(val cliName: String) {
    DISCRETE("discrete"),
    KARRAS("karras"),
    EXPONENTIAL("exponential"),
    AYS("ays"),
    GITS("gits"),
    SMOOTHSTEP("smoothstep"),
    SGM_UNIFORM("sgm_uniform"),
    SIMPLE("simple"),
    KL_OPTIMAL("kl_optimal"),
    LCM("lcm"),
    BONG_TANGENT("bong_tangent");

    companion object {
        fun fromCliName(value: String?): SdScheduler? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull {
                it.cliName.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}
