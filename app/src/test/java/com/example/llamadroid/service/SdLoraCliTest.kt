package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdLoraCliTest {
    @Test
    fun `image command emits ordered prompt tokens and one directory per parent`() {
        val args = buildSdCommandArgs(
            SDConfig(
                modelPath = "/models/sd15.safetensors",
                modelFamily = "checkpoint",
                modelVariant = "sd1",
                prompt = "portrait",
                outputPath = "/tmp/out.png",
                loras = listOf(
                    SdLoraSpec("/loras/style-a.safetensors", 0.4f),
                    SdLoraSpec("/loras/style-b.safetensors", 1.25f)
                ),
                loraApplyMode = SdLoraApplyMode.AT_RUNTIME
            )
        )
        val prompt = args[args.indexOf("-p") + 1]
        assertTrue(prompt.indexOf("<lora:style-a:0.4>") < prompt.indexOf("<lora:style-b:1.25>"))
        assertEquals(1, args.count { it == "--lora-model-dir" })
        assertTrue(args.contains("--lora-apply-mode"))
        assertTrue(args.contains("at_runtime"))
    }

    @Test
    fun `detail LoRAs are applied after base LoRAs and upscale remains pure`() {
        val detector = compatibleDetectorFile()
        try {
            val detailArgs = buildSdCommandArgs(
                SDConfig(
                    modelPath = "/models/sd15.safetensors",
                    modelFamily = "checkpoint",
                    modelVariant = "sd1",
                    prompt = "portrait",
                    outputPath = "/tmp/out.png",
                    adetailer = SdADetailerConfig(
                        modelPath = detector.absolutePath,
                        prompt = "sharp eyes",
                        loras = listOf(SdLoraSpec("/loras/detail.safetensors", 0.7f))
                    ),
                    loras = listOf(SdLoraSpec("/loras/base.safetensors", 0.5f))
                )
            )
            val detailPrompt = detailArgs[detailArgs.indexOf("--ad-prompt") + 1]
            assertTrue(detailPrompt.contains("<lora:detail:0.7>"))
            val pureUpscale = buildSdCommandArgs(
                SDConfig(
                    mode = SDMode.UPSCALE,
                    modelPath = "/models/upscaler.pth",
                    prompt = "",
                    outputPath = "/tmp/upscaled.png",
                    initImage = "/tmp/input.png",
                    upscaleModel = "/models/upscaler.pth",
                    loras = listOf(SdLoraSpec("/loras/not-used.safetensors"))
                )
            )
            assertFalse(pureUpscale.contains("--lora-model-dir"))
            assertFalse(pureUpscale.any { it.contains("<lora:") })
        } finally {
            detector.delete()
        }
    }

    @Test
    fun `Wan high noise LoRA uses high noise prompt marker`() {
        val prompt = buildVideoPrompt(
            VideoGenerationConfig(
                mode = VideoGenerationMode.TXT2VID,
                prompt = "a dancer",
                diffusionModelPath = "/models/wan.gguf",
                outputAviPath = "/tmp/out.avi",
                outputMp4Path = "/tmp/out.mp4",
                metadataPath = "/tmp/out.json",
                loras = listOf(SdLoraSpec("/loras/regular.safetensors", 0.5f)),
                highNoiseLoras = listOf(SdLoraSpec("/loras/high.safetensors", 0.8f, highNoiseOnly = false))
            )
        )
        assertTrue(prompt.contains("<lora:regular:0.5>"))
        assertTrue(prompt.contains("<lora:|high_noise|high:0.8>"))
    }

    private fun compatibleDetectorFile(): File {
        val detector = File.createTempFile("detector", ".safetensors")
        val header = """{"__metadata__":{"yolov8.variant":"detect"},"model.0.conv.weight":{},"model.22.cv2.0.2.weight":{},"model.22.cv3.0.2.weight":{}}"""
        detector.outputStream().use { output ->
            output.write(
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(header.toByteArray().size.toLong())
                    .array()
            )
            output.write(header.toByteArray())
            output.write(byteArrayOf(0, 0))
        }
        return detector
    }
}
