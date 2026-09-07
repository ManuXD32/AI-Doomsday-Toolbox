package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoOutputFormat
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.requiredRolesFor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCommandBuilderTest {

    @Test
    fun `invalid numbers fail before constructing a local or distributed command`() {
        val invalid = listOf(
            baseConfig().copy(width = 0), baseConfig().copy(fps = -1),
            baseConfig().copy(cfgScale = Float.NaN),
            baseConfig().copy(guidance = Float.POSITIVE_INFINITY),
            baseConfig().copy(highNoiseEta = Float.NEGATIVE_INFINITY),
            baseConfig().copy(hires = com.example.llamadroid.sd.SdVideoHiresConfig(
                enabled = true, denoisingStrength = Float.NaN
            ))
        )
        invalid.forEach { config ->
            listOf(false, true).forEach { distributed ->
                val error = runCatching {
                    buildVideoCommandArgs(config.copy(
                        distributedRuntime = config.distributedRuntime.copy(enabled = distributed)
                    ))
                }.exceptionOrNull()
                assertTrue(error is com.example.llamadroid.sd.SdVideoInputException)
                assertEquals(com.example.llamadroid.sd.SdVideoInputException.Code.INVALID_NUMERIC_VALUE,
                    (error as com.example.llamadroid.sd.SdVideoInputException).code)
            }
        }
    }

    @Test
    fun `inactive hires draft does not block the normal command`() {
        val args = buildVideoCommandArgs(baseConfig().copy(
            hires = com.example.llamadroid.sd.SdVideoHiresConfig(enabled = false, scale = Float.NaN)
        ))
        assertFalse(args.contains("--hires-scale"))
    }

    @Test
    fun `zero hires dimensions and steps preserve native automatic values`() {
        val args = buildVideoCommandArgs(baseConfig().copy(
            hires = com.example.llamadroid.sd.SdVideoHiresConfig(
                enabled = true, width = 0, height = 0, steps = 0, scale = 2f
            )
        ))
        assertEquals("0", args[args.indexOf("--hires-steps") + 1])
    }

    @Test
    fun `builder rejects still image frame count`() {
        val error = runCatching { buildVideoCommandArgs(baseConfig().copy(videoFrames = 1)) }
            .exceptionOrNull()

        assertTrue(error is com.example.llamadroid.sd.SdVideoInputException)
        assertEquals(
            com.example.llamadroid.sd.SdVideoInputException.Code.FRAMES_MUST_BE_VIDEO,
            (error as com.example.llamadroid.sd.SdVideoInputException).code
        )
    }

    @Test
    fun `native video options are emitted only when selected`() {
        val config = baseConfig().copy(
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/video.gguf",
                uncondDiffusionModelPath = "/models/uncond.gguf",
                ipAdapterPath = "/models/ip-adapter.safetensors"
            ),
            videoInputs = SdVideoInputs(ipAdapterImagePath = "/inputs/reference.png"),
            imgCfgScale = 1.5f,
            guidance = 3.25f,
            slgScale = 2.5f,
            skipLayerStart = 0.01f,
            skipLayerEnd = 0.2f,
            skipLayers = "[7,8,9]",
            eta = 0.1f,
            strength = 0.75f,
            highNoiseImgCfgScale = 1.2f,
            highNoiseGuidance = 2.5f,
            highNoiseSlgScale = 1.5f,
            highNoiseSkipLayerStart = 0.02f,
            highNoiseSkipLayerEnd = 0.3f,
            highNoiseSkipLayers = "[5,6]",
            highNoiseEta = 0.2f,
            moeBoundary = 0.875f,
            vaceStrength = 0.8f,
            ipAdapterStrength = 0.9f,
            vaeFormat = "wan",
            sigmas = "14.6,7.8,0",
            refImageArgs = "preset=qwen_layered",
            extraSampleArgs = "base_shift=0.5",
            extraTilingArgs = "temporal_tile_frames=4",
            increaseRefIndex = true,
            disableAutoResizeRefImage = true,
            circular = true,
            circularX = true,
            circularY = true,
            temporalTiling = true
        )

        val args = buildVideoCommandArgs(config)

        assertEquals("/models/uncond.gguf", args[args.indexOf("--uncond-diffusion-model") + 1])
        assertEquals("/models/ip-adapter.safetensors", args[args.indexOf("--ip-adapter") + 1])
        assertEquals("/inputs/reference.png", args[args.indexOf("--ip-adapter-image") + 1])
        assertEquals("[7,8,9]", args[args.indexOf("--skip-layers") + 1])
        assertEquals("[5,6]", args[args.indexOf("--high-noise-skip-layers") + 1])
        assertTrue(args.containsAll(listOf(
            "--img-cfg-scale", "--guidance", "--slg-scale", "--eta", "--strength",
            "--high-noise-img-cfg-scale", "--high-noise-guidance", "--high-noise-slg-scale",
            "--high-noise-eta", "--moe-boundary", "--vace-strength", "--vae-format",
            "--sigmas", "--ref-image-args", "--extra-sample-args", "--extra-tiling-args",
            "--increase-ref-index", "--disable-auto-resize-ref-image", "--temporal-tiling",
            "--circular", "--circularx", "--circulary"
        )))
    }

    @Test
    fun `native list and key value options reject malformed values`() {
        val listError = runCatching {
            buildVideoCommandArgs(baseConfig().copy(skipLayers = "7,8,9"))
        }.exceptionOrNull()
        val keyValueError = runCatching {
            buildVideoCommandArgs(baseConfig().copy(extraSampleArgs = "not-a-pair"))
        }.exceptionOrNull()

        assertTrue(listError is IllegalArgumentException)
        assertTrue(keyValueError is IllegalArgumentException)
    }

    @Test
    fun `LingBot emits TAE seed and caption JSON`() {
        val config = baseConfig().copy(
            prompt = "a paper boat on a quiet lake",
            videoFamily = SdVideoFamily.LINGBOT_VIDEO,
            videoVariant = "dense_1.3b",
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/lingbot.safetensors",
                llmPath = "/models/qwen3-vl.gguf",
                taePath = "/models/taew2_1.safetensors"
            ),
            useTae = true,
            videoFrames = 9,
            fps = 4,
            width = 256,
            height = 144,
            steps = 12,
            cfgScale = 3.0f,
            flowShift = 3.0f,
            seed = 42L,
            threads = 4,
            diffusionFa = false,
            mmap = false
        )

        val args = buildVideoCommandArgs(config)
        val prompt = args[args.indexOf("--prompt") + 1]

        assertEquals("vid_gen", args[args.indexOf("-M") + 1])
        assertEquals("/models/taew2_1.safetensors", args[args.indexOf("--tae") + 1])
        assertFalse(args.contains("--vae"))
        assertEquals("42", args[args.indexOf("--seed") + 1])
        assertTrue(prompt.contains("caption"))
        assertEquals(
            "a paper boat on a quiet lake",
            JSONObject(prompt).getJSONObject("caption").getString("comprehensive_description")
        )
    }

    @Test
    fun `Wan first-last workflow requires clip vision and high-noise controls`() {
        val config = baseConfig().copy(
            videoFamily = SdVideoFamily.WAN,
            workflow = SdVideoWorkflow.FIRST_LAST_FRAME,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/wan-low.gguf",
                highNoiseDiffusionModelPath = "/models/wan-high.gguf",
                t5xxlPath = "/models/umt5.gguf",
                vaePath = "/models/wan-vae.safetensors",
                clipVisionPath = "/models/clip-vision.safetensors"
            ),
            videoInputs = SdVideoInputs(
                initImagePath = "/inputs/start.png",
                endImagePath = "/inputs/end.png"
            ),
            highNoiseSteps = 8,
            highNoiseCfgScale = 3.5f,
            highNoiseSamplingMethod = SamplingMethod.EULER,
            flowShift = 3.0f,
            diffusionFa = false,
            mmap = false
        )

        val args = buildVideoCommandArgs(config)

        assertEquals("/models/wan-high.gguf", args[args.indexOf("--high-noise-diffusion-model") + 1])
        assertEquals("8", args[args.indexOf("--high-noise-steps") + 1])
        assertEquals("/inputs/start.png", args[args.indexOf("--init-img") + 1])
        assertEquals("/inputs/end.png", args[args.indexOf("--end-img") + 1])
        assertTrue(args.contains("--clip_vision"))
    }

    @Test
    fun `binary capability gating reports typed decoder and mode requirements`() {
        val config = baseConfig().copy(
            videoFamily = SdVideoFamily.LINGBOT_VIDEO,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/lingbot.safetensors",
                llmPath = "/models/qwen3-vl.gguf",
                taePath = "/models/tae.safetensors"
            ),
            useTae = true,
            diffusionFa = false,
            mmap = false
        )

        val flags = runCatching {
            buildVideoCommandArgs(
                config,
                binaryCapabilities = SdBinaryCapabilities(
                    supportedFlags = emptySet(),
                    supportedModes = emptySet()
                )
            )
        }.exceptionOrNull()

        assertTrue(flags is SdUnsupportedFlagsException)
        assertTrue((flags as SdUnsupportedFlagsException).flags.contains("--tae"))

        val mode = runCatching {
            buildVideoCommandArgs(
                config,
                binaryCapabilities = SdBinaryCapabilities(
                    supportedFlags = setOf("-M", "--diffusion-model", "--llm", "--tae"),
                    supportedModes = emptySet()
                )
            )
        }.exceptionOrNull()
        assertTrue(mode is SdUnsupportedModesException)
        assertTrue((mode as SdUnsupportedModesException).modes.contains("vid_gen"))
    }

    @Test
    fun `active same basename loras use absolute tokens and one native directory`() {
        val config = baseConfig().copy(
            loras = listOf(
                SdLoraSpec("/models/low/shared.safetensors", strength = 0.5f)
            ),
            highNoiseLoras = listOf(
                SdLoraSpec("/models/high/shared.safetensors", strength = 0.75f)
            )
        )

        val args = buildVideoCommandArgs(config)
        val prompt = args[args.indexOf("--prompt") + 1]

        assertEquals(1, args.count { it == "--lora-model-dir" })
        assertTrue(prompt.indexOf("/models/low/shared.safetensors") >= 0)
        assertTrue(prompt.indexOf("|high_noise|/models/high/shared.safetensors") >= 0)
        assertTrue(
            prompt.indexOf("/models/low/shared.safetensors") <
                prompt.indexOf("|high_noise|/models/high/shared.safetensors")
        )
    }

    @Test
    fun `reference video audio may omit a trailing soundtrack`() {
        val config = baseConfig().copy(
            videoInputs = SdVideoInputs(
                referenceVideos = listOf("/inputs/frames-0", "/inputs/frames-1"),
                referenceVideoAudios = listOf("/inputs/track-0.wav")
            )
        )

        val args = buildVideoCommandArgs(config)

        assertEquals(2, args.count { it == "--ref-video" })
        assertEquals(1, args.count { it == "--ref-video-audio" })
    }

    @Test
    fun `MiniMax references reject first or last keyframes`() {
        val error = runCatching {
            buildVideoCommandArgs(
                baseConfig().copy(
                    videoFamily = SdVideoFamily.MINIMAX_H3,
                    videoComponents = SdVideoComponentPaths(
                        diffusionModelPath = "/models/minimax.gguf",
                        llmPath = "/models/qwen.gguf",
                        vaePath = "/models/video-vae.safetensors"
                    ),
                    videoInputs = SdVideoInputs(
                        initImagePath = "/inputs/start.png",
                        referenceVideos = listOf("/inputs/reference-frames")
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.example.llamadroid.sd.SdVideoInputException)
        assertEquals(
            com.example.llamadroid.sd.SdVideoInputException.Code.MINIMAX_REFERENCES_CONFLICT_WITH_KEYFRAMES,
            (error as com.example.llamadroid.sd.SdVideoInputException).code
        )
    }

    @Test
    fun `MiniMax rejects control video frames`() {
        val error = runCatching {
            buildVideoCommandArgs(
                baseConfig().copy(
                    videoFamily = SdVideoFamily.MINIMAX_H3,
                    videoComponents = SdVideoComponentPaths(
                        diffusionModelPath = "/models/minimax.gguf",
                        llmPath = "/models/qwen.gguf",
                        vaePath = "/models/video-vae.safetensors"
                    ),
                    videoInputs = SdVideoInputs(controlVideoPath = "/inputs/control-frames")
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.example.llamadroid.sd.SdVideoInputException)
        assertEquals(
            com.example.llamadroid.sd.SdVideoInputException.Code.MINIMAX_CONTROL_VIDEO_UNSUPPORTED,
            (error as com.example.llamadroid.sd.SdVideoInputException).code
        )
    }

    @Test
    fun `disabled missing lora is retained in config but absent from command`() {
        val config = baseConfig().copy(
            loras = listOf(SdLoraSpec("/missing/disabled.safetensors", enabled = false))
        )

        val args = buildVideoCommandArgs(config)
        val prompt = args[args.indexOf("--prompt") + 1]

        assertFalse(args.contains("--lora-model-dir"))
        assertFalse(prompt.contains("disabled.safetensors"))
    }

    @Test
    fun `SVD checkpoint role is retained but generation is blocked by pinned backend`() {
        val profile = SdVideoFamilyProfiles.resolve(SdVideoFamily.SVD)

        assertEquals(com.example.llamadroid.sd.SdVideoMainModelLayout.FULL_MODEL, profile.mainModelLayout)
        assertFalse(profile.nativeWorkflowSupported)

        val error = runCatching {
            buildVideoCommandArgs(
                baseConfig().copy(
                    mode = VideoGenerationMode.IMG2VID,
                    videoFamily = SdVideoFamily.SVD,
                    workflow = SdVideoWorkflow.IMAGE_TO_VIDEO,
                    videoComponents = SdVideoComponentPaths(
                        fullModelPath = "/models/svd.safetensors",
                        vaePath = "/models/svd-vae.safetensors"
                    ),
                    videoInputs = SdVideoInputs(initImagePath = "/inputs/start.png")
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.example.llamadroid.sd.SdVideoWorkflowException)
        assertEquals(
            com.example.llamadroid.sd.SdVideoWorkflowErrorCode.NATIVE_WORKFLOW_UNSUPPORTED,
            (error as com.example.llamadroid.sd.SdVideoWorkflowException).code
        )
    }

    @Test
    fun `Wan 2 point 2 I2V does not require Wan 2 point 1 clip vision`() {
        val profile = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "2.2_i2v_a14b")
        val result = profile.prerequisites(
            components = SdVideoComponentPaths(
                diffusionModelPath = "/models/wan-low.gguf",
                highNoiseDiffusionModelPath = "/models/wan-high.gguf",
                t5xxlPath = "/models/umt5.gguf",
                vaePath = "/models/wan-vae.safetensors"
            ),
            inputs = SdVideoInputs(initImagePath = "/inputs/start.png"),
            workflow = SdVideoWorkflow.IMAGE_TO_VIDEO
        )

        assertTrue(result.isSatisfied)
    }

    @Test
    fun `Wan 2 point 2 TI2V does not require high noise model`() {
        val profile = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "2.2_ti2v_5b")

        assertFalse(profile.requiredRolesFor(SdVideoWorkflow.TEXT_TO_VIDEO).contains(
            com.example.llamadroid.sd.SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL
        ))
    }

    @Test
    fun `runtime options JSON round trip preserves typed state including null audio`() {
        val original = VideoRuntimeOptions(
            videoFamily = SdVideoFamily.LTX_VIDEO,
            videoVariant = "2.5",
            workflow = SdVideoWorkflow.FIRST_LAST_FRAME,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/ltx.gguf",
                llmPath = "/models/gemma.gguf",
                vaePath = "/models/ltx-vae.safetensors",
                audioVaePath = "/models/ltx-audio-vae.safetensors"
            ),
            videoInputs = SdVideoInputs(
                initImagePath = "/inputs/start.png",
                endImagePath = "/inputs/end.png",
                referenceAudios = listOf("/inputs/voice.wav")
            ),
            useTae = false,
            seed = 99L,
            highNoiseSteps = 4,
            highNoiseCfgScale = 3.0f,
            highNoiseSamplingMethod = SamplingMethod.DPM2,
            controlStrength = 0.6f,
            vaeTileOverlap = 0.25f,
            vaeRelativeTileSize = "0.5",
            hires = com.example.llamadroid.sd.SdVideoHiresConfig(enabled = true, steps = 4, scale = 2f),
            outputFormat = SdVideoOutputFormat.WEBM,
            audioCodec = null,
            imgCfgScale = 1.1f,
            guidance = 2.5f,
            slgScale = 0.7f,
            skipLayerStart = 0.01f,
            skipLayerEnd = 0.2f,
            skipLayers = "[7,8,9]",
            eta = 0.15f,
            strength = 0.8f,
            highNoiseImgCfgScale = 1.2f,
            highNoiseGuidance = 2.0f,
            highNoiseSlgScale = 0.5f,
            highNoiseSkipLayerStart = 0.02f,
            highNoiseSkipLayerEnd = 0.3f,
            highNoiseSkipLayers = "[5,6]",
            highNoiseEta = 0.2f,
            moeBoundary = 0.875f,
            vaceStrength = 0.9f,
            ipAdapterStrength = 0.8f,
            vaeFormat = "wan",
            sigmas = "14.6,7.8,0",
            refImageArgs = "preset=krea2_edit",
            extraSampleArgs = "base_shift=0.5",
            extraTilingArgs = "temporal_tile_frames=4",
            increaseRefIndex = true,
            disableAutoResizeRefImage = true,
            circular = true,
            circularX = true,
            circularY = true,
            temporalTiling = true,
            promptFormat = SdVideoPromptFormat.PLAIN,
            lingBotPromptJson = null
        )

        val restored = parseVideoRuntimeOptions(original.toJsonString())

        assertEquals(original, restored)
    }

    @Test
    fun `input prerequisite alternatives accept one MiniMax reference kind`() {
        val profile = SdVideoFamilyProfiles.MINIMAX_H3
        val result = profile.prerequisites(
            components = SdVideoComponentPaths(
                diffusionModelPath = "/models/minimax.gguf",
                llmPath = "/models/qwen.gguf",
                vaePath = "/models/video-vae.safetensors"
            ),
            inputs = SdVideoInputs(referenceVideos = listOf("/inputs/reference.mp4")),
            workflow = SdVideoWorkflow.REFERENCE_TO_AUDIO_VIDEO
        )

        assertTrue(result.isSatisfied)
    }

    @Test
    fun `LTX 2 point 5 profile does not require embedding connectors`() {
        val profile = SdVideoFamilyProfiles.resolve(SdVideoFamily.LTX_VIDEO, "2.5")

        assertFalse(profile.requiredRolesFor(SdVideoWorkflow.TEXT_TO_VIDEO).contains(
            com.example.llamadroid.sd.SdVideoComponentRole.EMBEDDINGS_CONNECTORS
        ))
    }

    private fun baseConfig(): VideoGenerationConfig = VideoGenerationConfig(
        mode = VideoGenerationMode.TXT2VID,
        prompt = "test prompt",
        diffusionModelPath = "/models/video.gguf",
        outputAviPath = "/tmp/video.avi",
        outputMp4Path = "/tmp/video.mp4",
        metadataPath = "/tmp/video.json",
        samplingMethod = SamplingMethod.EULER,
        cacheMode = null,
        diffusionFa = true,
        mmap = true
    )
}
