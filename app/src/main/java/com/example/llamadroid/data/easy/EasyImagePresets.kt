package com.example.llamadroid.data.easy

import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdScheduler

/** Model families Easy mode knows fixed image settings for. */
enum class EasyImageKind { SD15_SDCPP, SDXL_TURBO, FLUX_SCHNELL, ONNX_SD15 }

/**
 * Fixed standard settings for one image model family. [sizes] lists the allowed canvases;
 * the first entry is always the 512x512 default. [sampler] and [scheduler] are null for ONNX,
 * whose pipeline has no sampler selection.
 */
data class EasyImagePreset(
    val kind: EasyImageKind,
    val steps: Int,
    val cfgScale: Float,
    val sampler: SamplingMethod?,
    val scheduler: SdScheduler?,
    val sizes: List<Pair<Int, Int>>,
    val negativePrompt: String? = null
) {
    val defaultSize: Pair<Int, Int> get() = sizes.first()
}

object EasyImagePresets {
    private const val SD_NEGATIVE =
        "blurry, low quality, deformed, extra fingers, bad anatomy, watermark, text"

    /**
     * ONNX txt2img only requires width and height divisible by 8
     * (OnnxTxt2ImgPipeline.kt:46; grid in OnnxRuntimeSupport.kt:104-114,405). The SD1.5 sizes
     * below are all multiples of 64, so they are valid and stay in the LOW/MEDIUM RAM profile.
     */
    private val sd15Sizes = listOf(512 to 512, 512 to 768, 768 to 512)

    val all: List<EasyImagePreset> = listOf(
        EasyImagePreset(
            EasyImageKind.SD15_SDCPP, steps = 20, cfgScale = 7f,
            sampler = SamplingMethod.EULER_A, scheduler = SdScheduler.DISCRETE,
            sizes = sd15Sizes, negativePrompt = SD_NEGATIVE
        ),
        EasyImagePreset(
            EasyImageKind.SDXL_TURBO, steps = 4, cfgScale = 1f,
            sampler = SamplingMethod.EULER_A, scheduler = SdScheduler.DISCRETE,
            sizes = listOf(512 to 512, 768 to 768)
        ),
        EasyImagePreset(
            EasyImageKind.FLUX_SCHNELL, steps = 4, cfgScale = 1f,
            sampler = SamplingMethod.EULER, scheduler = SdScheduler.DISCRETE,
            sizes = listOf(512 to 512, 768 to 768, 1024 to 1024)
        ),
        EasyImagePreset(
            EasyImageKind.ONNX_SD15, steps = 20, cfgScale = 7.5f,
            sampler = null, scheduler = null,
            sizes = sd15Sizes, negativePrompt = SD_NEGATIVE
        )
    )

    fun forKind(kind: EasyImageKind): EasyImagePreset = all.first { it.kind == kind }
}
