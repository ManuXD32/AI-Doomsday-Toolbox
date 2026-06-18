package com.example.llamadroid.service

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraApplyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdCliSupportTest {

    @Test
    fun `checkpoint txt2img builds without required components`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = "/models/sd15.safetensors",
                modelFamily = "checkpoint",
                modelVariant = "sd1",
                prompt = "a tiny cabin",
                outputPath = "/tmp/out.png"
            )
        )

        assertTrue(args.contains("-m"))
        assertTrue(args.contains("/models/sd15.safetensors"))
        assertFalse(args.contains("--diffusion-model"))
    }

    @Test
    fun `diffusion txt2img builds with required family components`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = "/models/flux1.gguf",
                modelFamily = "flux_1",
                prompt = "a storm over a field",
                outputPath = "/tmp/out.png",
                vaePath = "/models/ae.safetensors",
                clipLPath = "/models/clip_l.safetensors",
                t5xxlPath = "/models/t5xxl.gguf"
            )
        )

        assertTrue(args.contains("--diffusion-model"))
        assertTrue(args.contains("--vae"))
        assertTrue(args.contains("--clip_l"))
        assertTrue(args.contains("--t5xxl"))
    }

    @Test
    fun `family toggles only emit when supported by resolved family`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = "/models/sd15.safetensors",
                modelFamily = "checkpoint",
                modelVariant = "sd1",
                prompt = "a clear lake",
                outputPath = "/tmp/out.png",
                flowShift = 3.0f,
                diffusionFa = true,
                mmap = true,
                vaeConvDirect = true,
                qwenImageZeroCondT = true,
                chromaDisableDitMask = true
            )
        )

        assertTrue(args.contains("--diffusion-fa"))
        assertTrue(args.contains("--mmap"))
        assertTrue(args.contains("--vae-conv-direct"))
        assertFalse(args.contains("--flow-shift"))
        assertFalse(args.contains("--qwen-image-zero-cond-t"))
        assertFalse(args.contains("--chroma-disable-dit-mask"))
    }

    @Test
    fun `sd tool components resolve by family and selected component id`() {
        val model = sdModel(
            filename = "flux1.gguf",
            path = "/models/flux1.gguf",
            type = ModelType.SD_DIFFUSION,
            family = "flux_1"
        )
        val components = resolveSdToolComponents(
            supportModels = listOf(
                sdModel(
                    filename = "ae.safetensors",
                    path = "/models/ae.safetensors",
                    type = ModelType.SD_VAE,
                    compatProfiles = "flux_1"
                ),
                sdModel(
                    filename = "wrong-vae.safetensors",
                    path = "/models/wrong-vae.safetensors",
                    type = ModelType.SD_VAE,
                    compatProfiles = "checkpoint"
                ),
                sdModel(
                    filename = "clip_l.safetensors",
                    path = "/models/clip_l.safetensors",
                    type = ModelType.SD_CLIP_L,
                    compatProfiles = "flux_1"
                ),
                sdModel(
                    filename = "t5xxl.gguf",
                    path = "/models/t5xxl.gguf",
                    type = ModelType.SD_T5XXL,
                    compatProfiles = "flux_1"
                )
            ),
            sdParams = NativeChatSdImageToolParams(
                vaePath = "ae.safetensors",
                clipLPath = "clip_l.safetensors",
                t5xxlPath = "t5xxl.gguf"
            ),
            model = model
        )

        assertEquals("/models/ae.safetensors", components.pathForRole(SdComponentRole.VAE))
        assertEquals("/models/clip_l.safetensors", components.pathForRole(SdComponentRole.CLIP_L))
        assertEquals("/models/t5xxl.gguf", components.pathForRole(SdComponentRole.T5XXL))
        assertEquals(null, components.taePath)
    }

    @Test
    fun `flux2 img2img uses llm and reference image path`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.IMG2IMG,
                modelPath = "/models/flux2.gguf",
                modelFamily = "flux_2",
                modelVariant = "dev",
                prompt = "a lighthouse",
                outputPath = "/tmp/out.png",
                initImage = "/tmp/input.png",
                vaePath = "/models/ae.safetensors",
                llmPath = "/models/flux2-llm.gguf"
            )
        )

        assertTrue(args.contains("--diffusion-model"))
        assertTrue(args.contains("--llm"))
        assertTrue(args.contains("-r"))
        assertFalse(args.contains("--strength"))
    }

    @Test
    fun `qwen image edit 2511 adds zero cond t flag`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.IMG2IMG,
                modelPath = "/models/qwen-image-edit.gguf",
                modelFamily = "qwen_image_edit",
                modelVariant = "2511",
                prompt = "change the sky to sunset",
                outputPath = "/tmp/out.png",
                initImage = "/tmp/ref.png",
                llmPath = "/models/qwen2_5_vl.gguf",
                qwenImageZeroCondT = true
            )
        )

        assertTrue(args.contains("--qwen-image-zero-cond-t"))
        assertTrue(args.contains("-r"))
    }

    @Test
    fun `sdxl photomaker uses checkpoint flag and adapter`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = "/models/sdxl.safetensors",
                modelFamily = "checkpoint",
                modelVariant = "sdxl",
                prompt = "portrait photo",
                outputPath = "/tmp/out.png",
                photoMakerPath = "/models/photomaker.bin"
            )
        )

        assertTrue(args.contains("-m"))
        assertTrue(args.contains("--photo-maker"))
        assertFalse(args.contains("--diffusion-model"))
    }

    @Test
    fun `missing required components throws`() {
        try {
            buildSdCommandArgs(
                SDConfig(
                    mode = SDMode.TXT2IMG,
                    modelPath = "/models/sd3.gguf",
                    modelFamily = "sd3",
                    prompt = "a city",
                    outputPath = "/tmp/out.png"
                )
            )
            fail("Expected missing component exception")
        } catch (expected: SdMissingComponentsException) {
            assertTrue(expected.roles.isNotEmpty())
        }
    }

    @Test
    fun `capability parsing supports short and long flags`() {
        val caps = parseSdBinaryCapabilities(
            """
            Usage: sd -M img_gen [options]
              --diffusion-model FILE
              --llm FILE
              --llm_vision FILE
              --clip_g FILE
              --photo-maker FILE
              --qwen-image-zero-cond-t
              -r FILE
            """.trimIndent()
        )

        assertTrue(caps.supports("--diffusion-model"))
        assertTrue(caps.supports("--llm"))
        assertTrue(caps.supports("--clip_g"))
        assertTrue(caps.supports("--photo-maker"))
        assertTrue(caps.supports("-r"))
    }

    @Test
    fun `lora apply mode is included when selected`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = "/models/base.safetensors",
                modelFamily = "checkpoint",
                prompt = "stylized portrait",
                outputPath = "/tmp/out.png",
                loraPath = "/models/style.safetensors",
                loraStrength = 0.8f,
                loraApplyMode = SdLoraApplyMode.AT_RUNTIME
            )
        )

        assertTrue(args.contains("--lora-apply-mode"))
        assertTrue(args.contains(SdLoraApplyMode.AT_RUNTIME.cliName))
    }

    @Test
    fun `upscale mode uses dedicated upscale flags and input image`() {
        val args = buildSdCommandArgs(
            SDConfig(
                mode = SDMode.UPSCALE,
                modelPath = "/models/realesrgan-x4.bin",
                prompt = "",
                outputPath = "/tmp/out.png",
                initImage = "/tmp/input.png",
                upscaleModel = "/models/realesrgan-x4.bin",
                upscaleRepeats = 3,
                threads = 6
            )
        )

        assertTrue(args.contains("-M"))
        assertTrue(args.contains("upscale"))
        assertTrue(args.contains("--upscale-model"))
        assertTrue(args.contains("/models/realesrgan-x4.bin"))
        assertTrue(args.contains("--upscale-repeats"))
        assertTrue(args.contains("3"))
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("/tmp/input.png"))
    }

    @Test
    fun `dedicated upscale builder emits the expected standalone command`() {
        val args = buildSdUpscaleCommandArgs(
            SDUpscaleConfig(
                modelPath = "/models/realesrgan-x4.bin",
                inputImagePath = "/tmp/input.png",
                outputPath = "/tmp/out.png",
                upscaleRepeats = 2,
                threads = 4
            )
        )

        assertTrue(args.contains("-M"))
        assertTrue(args.contains("upscale"))
        assertTrue(args.contains("--upscale-model"))
        assertTrue(args.contains("/models/realesrgan-x4.bin"))
        assertTrue(args.contains("--upscale-repeats"))
        assertTrue(args.contains("2"))
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("/tmp/input.png"))
        assertTrue(args.contains("-t"))
        assertTrue(args.contains("4"))
        assertFalse(args.contains("-p"))
    }

    private fun assertOption(args: List<String>, flag: String, value: String) {
        val index = args.indexOf(flag)
        assertTrue("$flag should be present", index >= 0)
        assertEquals(value, args.getOrNull(index + 1))
    }

    private fun sdModel(
        filename: String,
        path: String,
        type: ModelType,
        family: String? = null,
        compatProfiles: String? = null
    ): ModelEntity = ModelEntity(
        filename = filename,
        path = path,
        sizeBytes = 1024L,
        type = type,
        repoId = "local/test",
        sdFamily = family,
        sdCompatProfiles = compatProfiles
    )
}
