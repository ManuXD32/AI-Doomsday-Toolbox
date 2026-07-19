package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdDistributedRuntimeTest {

    @Test
    fun `disabled config emits no distributed args`() {
        val args = buildSdDistributedPreviewArgs(SdDistributedRuntimeConfig(enabled = false))

        assertTrue(args.isEmpty())
    }

    @Test
    fun `auto fit config emits rpc params backend max vram and layer split`() {
        val args = buildSdDistributedPreviewArgs(
            SdDistributedRuntimeConfig(
                enabled = true,
                placementMode = SdDistributedPlacementMode.AUTO_FIT,
                autoFit = true,
                rpcServers = "10.0.0.2:50062,10.0.0.3:50062",
                paramsBackendSpec = "disk",
                maxVramSpec = "4096",
                splitMode = SdDistributedSplitMode.LAYER
            )
        )

        assertEquals(
            listOf(
                "--rpc-servers",
                "10.0.0.2:50062,10.0.0.3:50062",
                "--auto-fit",
                "--params-backend",
                "disk",
                "--max-vram",
                "4096",
                "--split-mode",
                "layer"
            ),
            args
        )
    }

    @Test
    fun `component placement can split diffusion and place vae separately`() {
        val args = buildSdDistributedPreviewArgs(
            SdDistributedRuntimeConfig(
                enabled = true,
                rpcServers = "host-a:50062,host-b:50062,host-c:50062",
                placementMode = SdDistributedPlacementMode.COMPONENTS,
                backendSpec = "diffusion=RPC0&RPC1,te=cpu,vae=RPC2",
                autoFit = false,
                splitMode = SdDistributedSplitMode.ROW
            )
        )

        assertTrue(args.contains("--backend"))
        assertTrue(args.contains("diffusion=RPC0&RPC1,te=cpu,vae=RPC2"))
        assertTrue(args.contains("--rpc-servers"))
        assertTrue(args.contains("host-a:50062,host-b:50062,host-c:50062"))
        assertTrue(args.contains("--split-mode"))
        assertTrue(args.contains("row"))
        assertFalse(args.contains("--auto-fit"))
    }

    @Test
    fun `manual custom flags are split shell-like after structured args`() {
        val args = buildSdDistributedPreviewArgs(
            SdDistributedRuntimeConfig(
                enabled = true,
                placementMode = SdDistributedPlacementMode.MANUAL,
                autoFit = false,
                customFlags = "--foo \"bar baz\" --flag"
            )
        )

        assertEquals(listOf("--split-mode", "layer", "--foo", "bar baz", "--flag"), args)
    }

    @Test
    fun `capability probe blocks unsupported distributed flags`() {
        try {
            appendSdDistributedArgs(
                mutableListOf(),
                SdDistributedRuntimeConfig(
                    enabled = true,
                    rpcServers = "10.0.0.2:50062",
                    paramsBackendSpec = "disk"
                ),
                SdBinaryCapabilities(supportedFlags = setOf("--rpc-servers"))
            )
            fail("Expected unsupported flags exception")
        } catch (error: SdUnsupportedFlagsException) {
            assertEquals(listOf("--params-backend", "--split-mode"), error.flags)
        }
    }

    @Test
    fun `missing distributed flags reports the same flags the command builder requires`() {
        val missing = missingSdDistributedFlags(
            SdDistributedRuntimeConfig(
                enabled = true,
                rpcServers = "10.0.0.2:50062",
                backendSpec = "diffusion=RPC0",
                paramsBackendSpec = "disk",
                maxVramSpec = "4096"
            ),
            SdBinaryCapabilities(supportedFlags = setOf("--rpc-servers", "--backend"))
        )

        assertEquals(listOf("--max-vram", "--params-backend", "--split-mode"), missing)
    }

    @Test
    fun `ram planner default only splits diffusion across ordered workers`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "10.0.0.3", port = 50062, displayName = "Small", ramMB = 2048, threads = 4, rpcIndex = 1),
                SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Large", ramMB = 8192, threads = 8, rpcIndex = 0)
            )
        )

        assertEquals("10.0.0.2:50062,10.0.0.3:50062", plan.rpcServers)
        assertTrue(plan.backendSpec.contains("diffusion=RPC0&RPC1") || plan.backendSpec.contains("diffusion=RPC1&RPC0"))
        assertFalse(plan.backendSpec.contains("vae="))
        assertFalse(plan.backendSpec.contains("te="))
        assertFalse(plan.backendSpec.contains("upscaler="))
    }

    @Test
    fun `ram planner full pipeline can offload text encoder vae and upscaler`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "10.0.0.3", port = 50062, displayName = "Small", ramMB = 2048, threads = 4, rpcIndex = 1),
                SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Large", ramMB = 8192, threads = 8, rpcIndex = 0)
            ),
            SdRamPlannerOptions(autoRamScope = SdDistributedAutoRamScope.FULL_PIPELINE)
        )

        assertTrue(plan.backendSpec.contains("diffusion="))
        assertTrue(plan.backendSpec.contains("vae="))
        assertTrue(plan.backendSpec.contains("te="))
        assertTrue(plan.backendSpec.contains("upscaler="))
    }

    @Test
    fun `auto ram settings emit backend spec and omit max vram by default`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "a", port = 50062, displayName = "A", ramMB = 4096, threads = 4, rpcIndex = 0),
                SdDistributedPlanningWorker(host = "b", port = 50062, displayName = "B", ramMB = 4096, threads = 4, rpcIndex = 1)
            )
        )
        val config = SdDistributedMasterSettingsEntity(enabled = true).toRuntimeConfig(plan)
        val args = buildSdDistributedPreviewArgs(config)

        assertTrue(args.contains("--rpc-servers"))
        assertTrue(args.contains("--backend"))
        assertFalse(args.contains("--auto-fit"))
        assertFalse(args.contains("--max-vram"))
    }

    @Test
    fun `single worker default plan does not offload the whole pipeline`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Remote", ramMB = 24000, threads = 6, rpcIndex = 0)
            )
        )

        assertEquals("diffusion=RPC0", plan.backendSpec)
    }

    @Test
    fun `ram planner includes local master as cpu and omits it from rpc servers`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(
                    host = "127.0.0.1",
                    port = 0,
                    displayName = "This device",
                    ramMB = 4096,
                    threads = 4,
                    rpcIndex = -1,
                    isLocalMaster = true
                ),
                SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Remote", ramMB = 4096, threads = 4, rpcIndex = 0)
            )
        )

        assertEquals("10.0.0.2:50062", plan.rpcServers)
        assertTrue(plan.backendSpec.contains("diffusion=cpu&RPC0") || plan.backendSpec.contains("diffusion=RPC0&cpu"))
    }

    @Test
    fun `ram planner honors master allowed modules and manual diffusion share`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(
                    host = "127.0.0.1",
                    port = 0,
                    displayName = "This device",
                    ramMB = 8192,
                    threads = 4,
                    rpcIndex = -1,
                    isLocalMaster = true,
                    allowedModules = setOf(SdDistributedModules.DIFFUSION),
                    manualDiffusionSharePercent = 25
                ),
                SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Remote", ramMB = 8192, threads = 4, rpcIndex = 0)
            )
        )

        val diffusion = plan.assignments.first { it.module == SdDistributedModules.DIFFUSION }
        assertEquals(25, diffusion.estimatedLayerShares["cpu"])
        assertFalse(plan.backendSpec.contains("te=cpu"))
        assertFalse(plan.backendSpec.contains("vae=cpu"))
    }

    @Test
    fun `settings template json restores saved values`() {
        val settings = SdDistributedMasterSettingsEntity(
            enabled = true,
            placementMode = "MANUAL",
            autoRamScope = "FULL_PIPELINE",
            backendSpec = "diffusion=RPC0,vae=RPC1",
            steps = "32",
            sampler = "dpmpp2m",
            imageVaePath = "/models/vae.gguf",
            imageClipLPath = "/models/clip_l.gguf",
            imageT5xxlPath = "/models/t5xxl.gguf",
            imageControlNetEnabled = true,
            imageControlNetPath = "/models/controlnet.gguf",
            imageLoraEnabled = true,
            imageLoraPath = "/models/lora.gguf",
            imageLoraApplyMode = "at_runtime",
            videoUseVae = true,
            videoVaePath = "/models/video-vae.gguf",
            videoUseT5xxl = true,
            videoT5xxlPath = "/models/video-t5xxl.gguf"
        )

        val restored = settingsFromJson(settingsToJson(settings))

        assertTrue(restored.enabled)
        assertEquals("MANUAL", restored.placementMode)
        assertEquals("FULL_PIPELINE", restored.autoRamScope)
        assertEquals("diffusion=RPC0,vae=RPC1", restored.backendSpec)
        assertEquals("32", restored.steps)
        assertEquals("dpmpp2m", restored.sampler)
        assertEquals("/models/vae.gguf", restored.imageVaePath)
        assertEquals("/models/clip_l.gguf", restored.imageClipLPath)
        assertEquals("/models/t5xxl.gguf", restored.imageT5xxlPath)
        assertTrue(restored.imageControlNetEnabled)
        assertEquals("/models/controlnet.gguf", restored.imageControlNetPath)
        assertTrue(restored.imageLoraEnabled)
        assertEquals("/models/lora.gguf", restored.imageLoraPath)
        assertEquals("at_runtime", restored.imageLoraApplyMode)
        assertTrue(restored.videoUseVae)
        assertEquals("/models/video-vae.gguf", restored.videoVaePath)
        assertTrue(restored.videoUseT5xxl)
        assertEquals("/models/video-t5xxl.gguf", restored.videoT5xxlPath)
    }
}
