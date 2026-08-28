package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdPhaseCCliSupportTest {
    @Test fun `sd1 inpaint emits source and mask without unsupported image cfg`() {
        val model = File.createTempFile("sd-v1-5", ".gguf")
        val source = File.createTempFile("source", ".png")
        val mask = File.createTempFile("mask", ".png")
        try {
            val args = buildSdCommandArgs(
                SDConfig(
                    modelPath = model.absolutePath,
                    prompt = "repair",
                    outputPath = File(model.parentFile, "out.png").absolutePath,
                    mode = SDMode.IMG2IMG,
                    initImage = source.absolutePath,
                    maskImage = mask.absolutePath,
                    strength = 0.75f,
                    imgCfgScale = 1.5f
                ),
                SdBinaryCapabilities.ALLOW_ALL
            )
            assertEquals(mask.absolutePath, args[args.indexOf("--mask") + 1])
            assertFalse(args.contains("--img-cfg-scale"))
        } finally {
            model.delete(); source.delete(); mask.delete()
        }
    }

    @Test fun `explicit editing model capability emits image cfg`() {
        val model = File.createTempFile("instruct-pix2pix", ".gguf")
        val source = File.createTempFile("source", ".png")
        try {
            val args = buildSdCommandArgs(
                SDConfig(
                    modelPath = model.absolutePath,
                    modelFamily = "checkpoint",
                    modelVariant = "instruct_pix2pix",
                    prompt = "repair",
                    outputPath = File(model.parentFile, "out.png").absolutePath,
                    mode = SDMode.IMG2IMG,
                    initImage = source.absolutePath,
                    imgCfgScale = 1.5f
                ),
                SdBinaryCapabilities.ALLOW_ALL
            )
            assertEquals("1.5", args[args.indexOf("--img-cfg-scale") + 1])
        } finally {
            model.delete(); source.delete()
        }
    }

    @Test fun `adetailer flags are absent when disabled and deterministic when enabled`() {
        val model = File.createTempFile("sd-v1-5", ".gguf")
        val detector = compatibleDetectorFile()
        try {
            val base = SDConfig(
                modelPath = model.absolutePath,
                prompt = "portrait",
                outputPath = File(model.parentFile, "out.png").absolutePath
            )
            val disabled = buildSdCommandArgs(base, SdBinaryCapabilities.ALLOW_ALL)
            assertFalse(disabled.contains("--ad-model"))

            val enabled = buildSdCommandArgs(
                base.copy(
                    adetailer = SdADetailerConfig(
                        modelPath = detector.absolutePath,
                        prompt = "detailed face",
                        confidence = 0.35f
                    )
                ),
                SdBinaryCapabilities.ALLOW_ALL
            )
            assertEquals("img_gen", enabled[enabled.indexOf("-M") + 1])
            assertEquals(detector.absolutePath, enabled[enabled.indexOf("--ad-model") + 1])
            assertTrue(enabled[enabled.indexOf("--extra-ad-args") + 1].contains("confidence=0.35"))
            assertTrue(enabled[enabled.indexOf("--extra-ad-args") + 1].contains("inpaint_padding=32"))
        } finally {
            model.delete(); detector.delete()
        }
    }

    @Test fun `existing image ADetailer emits both detector and init image flags`() {
        val model = File.createTempFile("sd-v1-5", ".gguf")
        val detector = compatibleDetectorFile()
        val source = File.createTempFile("source", ".png")
        try {
            val args = buildSdCommandArgs(
                SDConfig(
                    mode = SDMode.ADETAILER,
                    modelPath = model.absolutePath,
                    prompt = "portrait",
                    outputPath = File(model.parentFile, "out.png").absolutePath,
                    initImage = source.absolutePath,
                    width = 512,
                    height = 512,
                    strength = 0.75f,
                    adetailer = SdADetailerConfig(
                        modelPath = detector.absolutePath,
                        denoisingStrength = 0.35f
                    )
                ),
                SdBinaryCapabilities.ALLOW_ALL
            )
            assertEquals("adetailer", args[args.indexOf("-M") + 1])
            assertEquals(detector.absolutePath, args[args.indexOf("--ad-model") + 1])
            assertEquals(source.absolutePath, args[args.indexOf("-i") + 1])
            assertEquals("0.35", args[args.indexOf("--strength") + 1])
            assertFalse(args.contains("-W"))
            assertFalse(args.contains("-H"))
            val extraArgs = args[args.indexOf("--extra-ad-args") + 1]
            assertTrue(extraArgs.contains("inpaint_width=512"))
            assertTrue(extraArgs.contains("inpaint_height=512"))
            assertFalse(extraArgs.contains("denoising_strength"))
        } finally {
            model.delete(); detector.delete(); source.delete()
        }
    }

    @Test fun `dedicated ADetailer emits global size only for explicit source resize`() {
        val model = File.createTempFile("sd-v1-5", ".gguf")
        val detector = compatibleDetectorFile()
        val source = File.createTempFile("source", ".png")
        try {
            val args = buildSdCommandArgs(
                SDConfig(
                    mode = SDMode.ADETAILER,
                    modelPath = model.absolutePath,
                    prompt = "portrait",
                    outputPath = File(model.parentFile, "out.png").absolutePath,
                    initImage = source.absolutePath,
                    width = 768,
                    height = 512,
                    adetailerResizeInput = true,
                    adetailer = SdADetailerConfig(modelPath = detector.absolutePath)
                ),
                SdBinaryCapabilities.ALLOW_ALL
            )
            assertEquals("768", args[args.indexOf("-W") + 1])
            assertEquals("512", args[args.indexOf("-H") + 1])
        } finally {
            model.delete(); detector.delete(); source.delete()
        }
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
