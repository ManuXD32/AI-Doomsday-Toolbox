package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoRuntimeOptions

/**
 * Single source of truth for curated one-tap video presets. Both the local video screen and the
 * distributed screen apply these values; each screen only differs in which base state it starts
 * from (a fresh state locally, the current state in the distributed editor).
 */
object SdCuratedPresets {
    /** LingBot Phone: dense 1.3B diffusion model, Qwen3-VL 4B Q4 `--llm`, taew2_1 TAE, no VAE. */
    object LingBotPhone {
        const val BUNDLE_ID = "lingbot-phone"
        const val DIFFUSION_FILE_ID = "lingbot-dense-13b"
        const val LLM_FILE_ID = "lingbot-qwen3-vl-4b-q4"
        const val TAE_FILE_ID = "lingbot-taew21"
        const val VARIANT = "dense_1.3b"
        const val FRAMES = 9
        const val FPS = 4
        const val WIDTH = 256
        const val HEIGHT = 144
        const val STEPS = 12
        const val CFG_SCALE = 3f
        const val FLOW_SHIFT = 3f
        const val SEED = 42L
        const val THREADS = 4
        const val MMAP = true
        const val DIFFUSION_FA = true
        /** TAE only: the LingBot decoder is never paired with a VAE in this preset. */
        const val USE_VAE = false
        const val USE_TAE = true
        val SAMPLER = SamplingMethod.EULER
        val SCHEDULER: SdScheduler? = null

        /** Whole-number floats render without a trailing ".0" in the text fields. */
        val CFG_SCALE_TEXT = presetNumberText(CFG_SCALE)
        val FLOW_SHIFT_TEXT = presetNumberText(FLOW_SHIFT)
    }

    /** Installed file paths keyed by curated file id (bundle files that are installed and intact). */
    fun installedPathsById(bundle: SdCuratedBundle, installed: List<ModelEntity>): Map<String, String> =
        bundle.files.mapNotNull { file ->
            file.installedSdCuratedModel(bundle, installed)?.let { file.id to it.path }
        }.toMap()

    /** All-or-nothing: returns null unless every bundle file is installed. */
    fun resolveLingBotComponents(
        bundle: SdCuratedBundle,
        installed: List<ModelEntity>,
        base: SdVideoComponentPaths = SdVideoComponentPaths()
    ): SdVideoComponentPaths? =
        resolveLingBotComponents(installedPathsById(bundle, installed), bundle.files.size, base)

    fun resolveLingBotComponents(
        installedById: Map<String, String>,
        expectedFileCount: Int,
        base: SdVideoComponentPaths = SdVideoComponentPaths()
    ): SdVideoComponentPaths? {
        if (installedById.size != expectedFileCount) return null
        return base.copy(
            diffusionModelPath = installedById[LingBotPhone.DIFFUSION_FILE_ID],
            llmPath = installedById[LingBotPhone.LLM_FILE_ID],
            taePath = installedById[LingBotPhone.TAE_FILE_ID],
            vaePath = null
        )
    }

    fun lingBotRuntimeOptions(
        components: SdVideoComponentPaths,
        base: VideoRuntimeOptions = VideoRuntimeOptions()
    ): VideoRuntimeOptions = base.copy(
        videoFamily = SdVideoFamily.LINGBOT_VIDEO,
        videoVariant = LingBotPhone.VARIANT,
        workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
        videoComponents = components,
        videoInputs = SdVideoInputs(),
        useTae = LingBotPhone.USE_TAE,
        seed = LingBotPhone.SEED,
        promptFormat = SdVideoPromptFormat.LINGBOT_CAPTION_JSON
    )
}

internal fun presetNumberText(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
