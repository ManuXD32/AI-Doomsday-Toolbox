package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedBundleFile
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdIpAdapterConfig
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.buildSdCommandArgs
import com.example.llamadroid.service.buildVideoCommandArgs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Builds the argv the app really produces for every curated generation bundle with the real
 * command builders and writes them to `build/sd-preset-commands.json` (module-relative) for the
 * host smoke harness. Paths are replaced by `{file:<id>}`, `{output}` and `{input_image}`.
 *
 * Defaults used (no invented values):
 * - LingBot Phone: [SdCuratedPresets.LingBotPhone] (the one-tap preset both screens apply).
 * - Wan 2.2 local-video: `SdVideoFamilyProfiles.resolve(WAN, "wan2_2_ti2v").defaultValues`
 *   (the screens do not apply profile defaults themselves; this is the family contract).
 * - Image bundles: the image screen has no per-family presets, so the [SDConfig] defaults it
 *   starts from apply (512x512, 20 steps, cfg 7, Euler a, random seed).
 */
class CuratedPresetCommandsTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Preset(
        val id: String,
        val bundle: SdCuratedBundle,
        val kind: String,
        val args: List<String>,
        val outputExtension: String,
        val smoke: Map<String, String>
    )

    private val placeholders = mutableMapOf<String, String>()
    private lateinit var root: File

    /** Bundles that bundle only detector/upscaler pipelines, not a plain generation preset. */
    private fun isSkipped(bundle: SdCuratedBundle): Boolean =
        bundle.id.startsWith("workflow-") ||
            bundle.files.none { it.modelType == ModelType.SD_CHECKPOINT || it.modelType == ModelType.SD_DIFFUSION }

    private fun fixtureName(file: SdCuratedBundleFile): String =
        file.localFilenameOverride ?: file.remotePath.substringAfterLast('/')

    private fun pathFor(file: SdCuratedBundleFile, real: Boolean = false): String {
        val target = File(root, fixtureName(file))
        if (real) target.writeText("fixture")
        return target.path.also { placeholders[it] = "{file:${file.id}}" }
    }

    private fun inputImage(): String =
        File(root, "input.png").also { it.writeText("fixture") }.path.also { placeholders[it] = "{input_image}" }

    private fun output(extension: String): String =
        File(root, "output.$extension").path.also { placeholders[it] = "{output}" }

    private fun normalize(args: List<String>): List<String> = args.map { placeholders[it] ?: it }

    private fun videoConfig(
        components: SdVideoComponentPaths,
        inputs: SdVideoInputs,
        mode: VideoGenerationMode,
        family: SdVideoFamily,
        variant: String?,
        workflow: SdVideoWorkflow,
        defaults: SdVideoDefaults,
        useVae: Boolean,
        useTae: Boolean,
        output: String
    ) = VideoGenerationConfig(
        mode = mode,
        prompt = "a paper boat on a quiet lake",
        diffusionModelPath = components.diffusionModelPath!!,
        outputAviPath = output,
        outputMp4Path = File(root, "output.mp4").path,
        metadataPath = File(root, "output.json").path,
        useVae = useVae,
        useT5xxl = components.t5xxlPath != null,
        videoFrames = defaults.frames,
        fps = defaults.fps,
        width = defaults.width,
        height = defaults.height,
        steps = defaults.steps,
        cfgScale = defaults.cfgScale,
        flowShift = defaults.flowShift,
        seed = defaults.seed,
        samplingMethod = SamplingMethod.EULER,
        videoFamily = family,
        videoVariant = variant,
        workflow = workflow,
        videoComponents = components,
        videoInputs = inputs,
        useTae = useTae
    )

    private fun buildVideoPreset(
        id: String,
        bundle: SdCuratedBundle,
        config: VideoGenerationConfig,
        smoke: Map<String, String>
    ): Preset {
        val profile = SdVideoFamilyProfiles.resolve(config.videoFamily!!, config.videoVariant)
        val workflow = config.workflow!!
        assertTrue(
            "$id prerequisites",
            profile.prerequisites(config.videoComponents, config.videoInputs, workflow).isSatisfied
        )
        // The first argument is the executable placeholder; the contract starts after it.
        val args = buildVideoCommandArgs(config, executablePath = "sd-cli").drop(1)
        return Preset(id, bundle, "video", normalize(args), "avi", smoke)
    }

    private fun lingBotPresets(): List<Preset> {
        val bundle = SdCuratedBundleCatalog.byId(SdCuratedPresets.LingBotPhone.BUNDLE_ID)!!
        val preset = SdCuratedPresets.LingBotPhone
        val installed = bundle.files.associate { it.id to pathFor(it) }
        val components = SdCuratedPresets.resolveLingBotComponents(installed, bundle.files.size)!!
        val options = SdCuratedPresets.lingBotRuntimeOptions(components)
        val defaults = SdVideoDefaults(
            preset.FRAMES, preset.FPS, preset.WIDTH, preset.HEIGHT, preset.STEPS,
            preset.CFG_SCALE, preset.FLOW_SHIFT, preset.SEED
        )
        val smoke = mapOf("--steps" to "2", "--video-frames" to "9")
        fun config(mode: VideoGenerationMode, workflow: SdVideoWorkflow, inputs: SdVideoInputs) =
            videoConfig(
                components, inputs, mode, options.videoFamily!!, options.videoVariant, workflow,
                defaults, useVae = preset.USE_VAE, useTae = options.useTae, output = output("avi")
            ).copy(
                samplingMethod = preset.SAMPLER,
                scheduler = preset.SCHEDULER,
                diffusionFa = preset.DIFFUSION_FA,
                mmap = preset.MMAP,
                threads = preset.THREADS,
                promptFormat = options.promptFormat
            )
        return listOf(
            buildVideoPreset(
                "${bundle.id}/t2v", bundle,
                config(VideoGenerationMode.TXT2VID, SdVideoWorkflow.TEXT_TO_VIDEO, SdVideoInputs()),
                smoke
            ),
            buildVideoPreset(
                "${bundle.id}/i2v", bundle,
                config(
                    VideoGenerationMode.IMG2VID, SdVideoWorkflow.IMAGE_TO_VIDEO,
                    SdVideoInputs(initImagePath = inputImage())
                ),
                smoke
            )
        )
    }

    private fun wanPresets(bundle: SdCuratedBundle): List<Preset> {
        val diffusion = bundle.files.first { it.modelType == ModelType.SD_DIFFUSION }
        val t5 = bundle.files.first { it.modelType == ModelType.SD_T5XXL }
        val vae = bundle.files.first { it.modelType == ModelType.SD_VAE }
        val components = SdVideoComponentPaths(
            diffusionModelPath = pathFor(diffusion),
            t5xxlPath = pathFor(t5),
            vaePath = pathFor(vae)
        )
        val variant = "wan2_2_ti2v"
        val defaults = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, variant).defaultValues
        val smoke = mapOf("--steps" to "2", "--video-frames" to "5", "--width" to "256", "--height" to "256")
        fun config(mode: VideoGenerationMode, workflow: SdVideoWorkflow, inputs: SdVideoInputs) =
            videoConfig(
                components, inputs, mode, SdVideoFamily.WAN, variant, workflow,
                defaults, useVae = true, useTae = false, output = output("avi")
            )
        return listOf(
            buildVideoPreset(
                "${bundle.id}/t2v", bundle,
                config(VideoGenerationMode.TXT2VID, SdVideoWorkflow.TEXT_TO_VIDEO, SdVideoInputs()),
                smoke
            ),
            buildVideoPreset(
                "${bundle.id}/i2v", bundle,
                config(
                    VideoGenerationMode.IMG2VID, SdVideoWorkflow.IMAGE_TO_VIDEO,
                    SdVideoInputs(initImagePath = inputImage())
                ),
                smoke
            )
        )
    }

    private fun imagePresets(bundle: SdCuratedBundle): List<Preset> {
        val main = bundle.files.first {
            it.modelType == ModelType.SD_CHECKPOINT || it.modelType == ModelType.SD_DIFFUSION
        }
        val hasIpAdapter = bundle.files.any { it.modelType == ModelType.SD_IP_ADAPTER }
        fun pathOf(type: ModelType, real: Boolean = false) =
            bundle.files.firstOrNull { it.modelType == type }?.let { pathFor(it, real) }
        val modelPath = pathFor(main)
        val vae = pathOf(ModelType.SD_VAE)
        val clipL = pathOf(ModelType.SD_CLIP_L)
        val t5 = pathOf(ModelType.SD_T5XXL)
        val llm = pathOf(ModelType.SD_LLM)
        val llmVision = pathOf(ModelType.MMPROJ)
        val smoke = mapOf("--steps" to "1", "-W" to "256", "-H" to "256")
        val supportsImg2Img = main.sdCapabilities?.split(',')?.contains(SD_CAPABILITY_IMG2IMG) == true
        val modes = listOf(SDMode.TXT2IMG) + if (supportsImg2Img) listOf(SDMode.IMG2IMG) else emptyList()
        return modes.map { mode ->
            val suffix = if (mode == SDMode.TXT2IMG) "txt2img" else "img2img"
            val ipAdapter = if (hasIpAdapter) SdIpAdapterConfig(
                adapterPath = pathOf(ModelType.SD_IP_ADAPTER, real = true)!!,
                clipVisionPath = pathOf(ModelType.SD_CLIP_VISION, real = true)!!,
                imagePath = inputImage()
            ) else null
            val config = SDConfig(
                modelPath = modelPath,
                prompt = "a tiny cabin in a snowy forest",
                outputPath = output("png"),
                mode = mode,
                initImage = if (mode == SDMode.IMG2IMG) inputImage() else null,
                modelFamily = main.sdFamily,
                modelVariant = main.sdVariant,
                vaePath = vae,
                clipLPath = clipL,
                t5xxlPath = t5,
                llmPath = llm,
                llmVisionPath = llmVision,
                ipAdapter = ipAdapter
            )
            Preset("${bundle.id}/$suffix", bundle, "image", normalize(buildSdCommandArgs(config)), "png", smoke)
        }
    }

    private fun applySmoke(preset: Preset): List<String> {
        val args = preset.args.toMutableList()
        preset.smoke.forEach { (flag, value) ->
            val index = args.indexOf(flag)
            assertTrue("${preset.id}: smoke flag $flag missing from args", index >= 0)
            args[index + 1] = value
        }
        return args
    }

    private fun assertSmokeValid(preset: Preset) {
        val args = applySmoke(preset)
        fun int(flag: String): Int? = args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1].toInt() }
        assertTrue("${preset.id}: steps", (int("--steps") ?: 1) in 1..2)
        listOfNotNull(int("--width"), int("--height"), int("-W"), int("-H")).forEach {
            assertEquals("${preset.id}: size multiple of 16 ($it)", 0, it % 16)
        }
        int("--video-frames")?.let {
            assertTrue("${preset.id}: frames", it in 5..9 && (it - 1) % 4 == 0)
        }
    }

    @Test
    fun `every curated generation bundle has a golden command and the JSON is written`() {
        root = temporaryFolder.newFolder("fixtures")
        val presets = mutableListOf<Preset>()
        SdCuratedBundleCatalog.bundles.filterNot(::isSkipped).forEach { bundle ->
            val isVideo = bundle.files.any { it.sdCapabilities?.split(',')?.contains(SD_CAPABILITY_VID_GEN) == true }
            presets += when {
                bundle.id == SdCuratedPresets.LingBotPhone.BUNDLE_ID -> lingBotPresets()
                isVideo -> wanPresets(bundle)
                else -> imagePresets(bundle)
            }
        }
        assertTrue(presets.any { it.id == "lingbot-phone/t2v" } && presets.any { it.id == "lingbot-phone/i2v" })
        presets.forEach { preset ->
            assertTrue("${preset.id}: has output", preset.args.contains("{output}"))
            assertTrue("${preset.id}: has file placeholder", preset.args.any { it.startsWith("{file:") })
            assertFalse("${preset.id}: raw fixture path leaked", preset.args.any { it.contains(root.path) })
            assertSmokeValid(preset)
        }
        val lingBot = presets.first { it.id == "lingbot-phone/t2v" }.args
        assertTrue(lingBot.contains("--tae"))
        assertFalse(lingBot.contains("--vae"))

        val json = JSONObject()
            .put("schema", 1)
            .put("generatedBy", "CuratedPresetCommandsTest")
            .put("presets", JSONArray(presets.map { preset ->
                JSONObject()
                    .put("id", preset.id)
                    .put("bundleId", preset.bundle.id)
                    .put("kind", preset.kind)
                    .put("args", JSONArray(preset.args))
                    .put("outputExtension", preset.outputExtension)
                    .put("smoke", JSONObject(preset.smoke))
                    .put("files", JSONArray(preset.bundle.files.map { file ->
                        JSONObject()
                            .put("id", file.id)
                            .put("fileName", fixtureName(file))
                            .put("url", file.transferUrl())
                            .put("sha256", file.sha256)
                            // Approximate catalog sizes are not enforced by the app either; the SHA is.
                            .put("sizeBytes", if (file.sizeIsApproximate) JSONObject.NULL else file.sizeBytes)
                    }))
            }))
        val out = File("build/sd-preset-commands.json")
        out.parentFile?.mkdirs()
        out.writeText(json.toString(2))
    }
}
