package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SdIpAdapterCliTest {

    @Test
    fun `plain generation command remains unchanged when adapter is absent`() {
        val config = baseConfig()
        val args = buildSdCommandArgs(config, SdBinaryCapabilities.ALLOW_ALL)

        assertFalse(args.contains("--ip-adapter"))
        assertFalse(args.contains("--clip_vision"))
        assertFalse(args.contains("--ip-adapter-image"))
    }

    @Test
    fun `sd15 and sdxl use the same structured flags and preserve paths with spaces`() {
        val root = createTempDirectory("adt-ip-adapter-cli").toFile()
        val adapter = File(root, "ip adapter plus.safetensors").apply { writeText("adapter") }
        val clip = File(root, "clip vision.safetensors").apply { writeText("clip") }
        val image = File(root, "reference image.png").apply { writeText("image") }
        val capabilities = SdBinaryCapabilities(
            setOf(
                "--clip_vision",
                "--ip-adapter",
                "--ip-adapter-image",
                "--ip-adapter-strength"
            )
        )

        listOf("sd1", "sdxl").forEach { variant ->
            val args = buildSdCommandArgs(
                baseConfig().copy(
                    modelVariant = variant,
                    ipAdapter = SdIpAdapterConfig(
                        adapterPath = adapter.absolutePath,
                        clipVisionPath = clip.absolutePath,
                        imagePath = image.absolutePath,
                        strength = 0.75f
                    )
                ),
                capabilities
            )
            assertFlagValue(args, "--ip-adapter", adapter.absolutePath)
            assertFlagValue(args, "--clip_vision", clip.absolutePath)
            assertFlagValue(args, "--ip-adapter-image", image.absolutePath)
            assertFlagValue(args, "--ip-adapter-strength", "0.75")
        }
    }

    @Test
    fun `controlnet and ip adapter compose`() {
        val root = createTempDirectory("adt-ip-adapter-controlnet").toFile()
        val adapter = File(root, "adapter.safetensors").apply { writeText("adapter") }
        val clip = File(root, "clip.safetensors").apply { writeText("clip") }
        val image = File(root, "reference.png").apply { writeText("image") }
        val args = buildSdCommandArgs(
            baseConfig().copy(
                controlNetPath = "/models/controlnet.safetensors",
                controlImagePath = "/files/control.png",
                ipAdapter = SdIpAdapterConfig(
                    adapter.absolutePath,
                    clip.absolutePath,
                    image.absolutePath,
                    1f
                )
            ),
            SdBinaryCapabilities.ALLOW_ALL
        )

        assertTrue(args.contains("--control-net"))
        assertTrue(args.contains("--control-image"))
        assertTrue(args.contains("--ip-adapter"))
        assertTrue(args.contains("--ip-adapter-image"))
    }

    @Test
    fun `missing binary flag is reported before launch`() {
        val root = createTempDirectory("adt-ip-adapter-flags").toFile()
        val adapter = File(root, "adapter.safetensors").apply { writeText("adapter") }
        val clip = File(root, "clip.safetensors").apply { writeText("clip") }
        val image = File(root, "reference.png").apply { writeText("image") }
        val error = assertThrows(SdUnsupportedFlagsException::class.java) {
            buildSdCommandArgs(
                baseConfig().copy(
                    ipAdapter = SdIpAdapterConfig(
                        adapter.absolutePath,
                        clip.absolutePath,
                        image.absolutePath,
                        1f
                    )
                ),
                SdBinaryCapabilities(
                    setOf("--clip_vision", "--ip-adapter", "--ip-adapter-image")
                )
            )
        }

        assertEquals(listOf("--ip-adapter-strength"), error.flags)
    }

    @Test
    fun `distributed command retains ip adapter flags without local backend arguments`() {
        val root = createTempDirectory("adt-ip-adapter-distributed").toFile()
        val adapter = File(root, "adapter.safetensors").apply { writeText("adapter") }
        val clip = File(root, "clip.safetensors").apply { writeText("clip") }
        val image = File(root, "reference.png").apply { writeText("image") }
        val args = buildSdCommandArgs(
            baseConfig().copy(
                ipAdapter = SdIpAdapterConfig(
                    adapter.absolutePath,
                    clip.absolutePath,
                    image.absolutePath,
                    0.8f
                ),
                distributedRuntime = SdDistributedRuntimeConfig(
                    enabled = true,
                    rpcServers = "127.0.0.1:50052"
                ),
                sdRuntimeBackendMode = "vulkan0",
                sdParamsBackendMode = "gpu"
            ),
            SdBinaryCapabilities.ALLOW_ALL
        )

        assertTrue(args.contains("--ip-adapter"))
        assertTrue(args.contains("--clip_vision"))
        assertTrue(args.contains("--rpc-servers"))
        assertFalse(args.contains("--params-backend"))
    }

    private fun baseConfig(): SDConfig = SDConfig(
        mode = SDMode.TXT2IMG,
        modelPath = "/models/sd15.gguf",
        modelFamily = "checkpoint",
        modelVariant = "sd1",
        prompt = "portrait",
        outputPath = "/output/result.png"
    )

    private fun assertFlagValue(args: List<String>, flag: String, expected: String) {
        val index = args.indexOf(flag)
        assertTrue("missing $flag in $args", index >= 0)
        assertEquals(expected, args[index + 1])
    }
}
