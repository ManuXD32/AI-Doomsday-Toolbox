package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
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
    fun `component placement can split diffusion and place vae separately using layer split`() {
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
        assertTrue(args.contains("layer"))
        assertFalse(args.contains("row"))
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
    fun `distributed custom flags reject row split override`() {
        try {
            buildSdDistributedPreviewArgs(
                SdDistributedRuntimeConfig(
                    enabled = true,
                    placementMode = SdDistributedPlacementMode.MANUAL,
                    customFlags = "--split-mode row"
                )
            )
            fail("Expected disallowed flag exception")
        } catch (error: SdDisallowedDistributedFlagException) {
            assertEquals("--split-mode row", error.flag)
        }
    }

    @Test
    fun `tensor type rule presets map expected values`() {
        assertEquals(SdTensorTypeRulesPreset.AUTO, SdTensorTypeRules.presetFor(""))
        assertEquals("", SdTensorTypeRules.valueFor(SdTensorTypeRulesPreset.AUTO))
        assertEquals(SdTensorTypeRulesPreset.VAE_F16, SdTensorTypeRules.presetFor(SdTensorTypeRules.VAE_F16))
        assertEquals(
            "^vae\\.=f16,^first_stage_model\\.=f16",
            SdTensorTypeRules.valueFor(SdTensorTypeRulesPreset.VAE_F16)
        )
        assertEquals(SdTensorTypeRulesPreset.CUSTOM, SdTensorTypeRules.presetFor("model.diffusion*=q8_0"))
        assertEquals("model.diffusion*=q8_0", SdTensorTypeRules.valueFor(SdTensorTypeRulesPreset.CUSTOM, "model.diffusion*=q8_0"))
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
        assertEquals("RPC0=7.5,RPC1=2", plan.maxVramSpec)
        assertTrue(plan.backendSpec.contains("diffusion=RPC0&RPC1"))
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
    fun `auto ram settings emit auto fit and generated per rpc max vram budgets`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "a", port = 50062, displayName = "A", ramMB = 4096, threads = 4, rpcIndex = 0),
                SdDistributedPlanningWorker(host = "b", port = 50062, displayName = "B", ramMB = 8192, threads = 4, rpcIndex = 1)
            )
        )
        val config = SdDistributedMasterSettingsEntity(enabled = true).toRuntimeConfig(plan)
        val args = buildSdDistributedPreviewArgs(config)

        assertTrue(args.contains("--rpc-servers"))
        assertTrue(args.contains("--auto-fit"))
        assertTrue(args.contains("--max-vram"))
        assertTrue(args.contains("RPC0=7.5,RPC1=4"))
        assertFalse(args.contains("--backend"))
        assertFalse(args.contains("--params-backend"))
    }

    @Test
    fun `worker entities map rpc order from higher ram to lower ram`() {
        val workers = listOf(
            SdDistributedWorkerEntity(host = "10.0.0.3", port = 50062, deviceName = "Small", ramMB = 2048, threads = 4, sortOrder = 0),
            SdDistributedWorkerEntity(host = "10.0.0.2", port = 50062, deviceName = "Large", ramMB = 8192, threads = 8, sortOrder = 1)
        ).toSdPlanningWorkers()

        assertEquals("Large", workers[0].displayName)
        assertEquals("RPC0", workers[0].rpcName)
        assertEquals("Small", workers[1].displayName)
        assertEquals("RPC1", workers[1].rpcName)
    }

    @Test
    fun `component settings keep explicit backend and drop incompatible max vram`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "a", port = 50062, displayName = "A", ramMB = 4096, threads = 4, rpcIndex = 0),
                SdDistributedPlanningWorker(host = "b", port = 50062, displayName = "B", ramMB = 4096, threads = 4, rpcIndex = 1)
            )
        )
        val config = SdDistributedMasterSettingsEntity(
            enabled = true,
            placementMode = SdDistributedPlacementMode.COMPONENTS.name,
            backendSpec = "diffusion=RPC0&RPC1,vae=RPC0",
            paramsBackendSpec = "vae=cpu",
            maxVramEnabled = true,
            maxVramSpec = "RPC0=4,RPC1=4"
        ).toRuntimeConfig(plan)
        val args = buildSdDistributedPreviewArgs(config)

        assertTrue(args.contains("--backend"))
        assertTrue(args.contains("diffusion=RPC0&RPC1,vae=RPC0"))
        assertTrue(args.contains("--params-backend"))
        assertFalse(args.contains("--auto-fit"))
        assertFalse(args.contains("--max-vram"))
    }

    @Test
    fun `manual settings keep rpc list and raw flags only`() {
        val plan = buildRamWeightedSdPlacementPlan(
            listOf(
                SdDistributedPlanningWorker(host = "a", port = 50062, displayName = "A", ramMB = 4096, threads = 4, rpcIndex = 0)
            )
        )
        val config = SdDistributedMasterSettingsEntity(
            enabled = true,
            placementMode = SdDistributedPlacementMode.MANUAL.name,
            backendSpec = "diffusion=RPC0",
            paramsBackendSpec = "disk",
            maxVramEnabled = true,
            maxVramSpec = "RPC0=4",
            customFlags = "--backend diffusion=RPC0"
        ).toRuntimeConfig(plan)
        val args = buildSdDistributedPreviewArgs(config)

        assertEquals(
            listOf("--rpc-servers", "a:50062", "--split-mode", "layer", "--backend", "diffusion=RPC0"),
            args
        )
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
            splitMode = "row",
            tensorRules = "model.diffusion*=q8_0",
            steps = "32",
            sampler = "dpmpp2m",
            imagePrompt = "image prompt",
            imageNegativePrompt = "image negative",
            imageWidth = "640",
            imageHeight = "512",
            imageSteps = "28",
            imageCfg = "7.5",
            imageSeed = "123",
            imageSampler = "euler_a",
            imageScheduler = "karras",
            imageFlowShift = "3.0",
            imageVaePath = "/models/vae.gguf",
            imageClipLPath = "/models/clip_l.gguf",
            imageT5xxlPath = "/models/t5xxl.gguf",
            imageControlNetEnabled = true,
            imageControlNetPath = "/models/controlnet.gguf",
            imageLoraEnabled = true,
            imageLoraPath = "/models/lora.gguf",
            imageLoraApplyMode = "at_runtime",
            videoPrompt = "video prompt",
            videoNegativePrompt = "video negative",
            videoWidth = "480",
            videoHeight = "832",
            videoSteps = "18",
            videoCfg = "6.0",
            videoSeed = "456",
            videoSampler = "euler",
            videoScheduler = "exponential",
            videoFlowShift = "5.0",
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
        assertEquals("layer", restored.splitMode)
        assertEquals("model.diffusion*=q8_0", restored.tensorRules)
        assertEquals("32", restored.steps)
        assertEquals("dpmpp2m", restored.sampler)
        assertEquals("image prompt", restored.imagePrompt)
        assertEquals("image negative", restored.imageNegativePrompt)
        assertEquals("640", restored.imageWidth)
        assertEquals("512", restored.imageHeight)
        assertEquals("28", restored.imageSteps)
        assertEquals("7.5", restored.imageCfg)
        assertEquals("123", restored.imageSeed)
        assertEquals("euler_a", restored.imageSampler)
        assertEquals("karras", restored.imageScheduler)
        assertEquals("3.0", restored.imageFlowShift)
        assertEquals("/models/vae.gguf", restored.imageVaePath)
        assertEquals("/models/clip_l.gguf", restored.imageClipLPath)
        assertEquals("/models/t5xxl.gguf", restored.imageT5xxlPath)
        assertTrue(restored.imageControlNetEnabled)
        assertEquals("/models/controlnet.gguf", restored.imageControlNetPath)
        assertTrue(restored.imageLoraEnabled)
        assertEquals("/models/lora.gguf", restored.imageLoraPath)
        assertEquals("at_runtime", restored.imageLoraApplyMode)
        assertEquals("video prompt", restored.videoPrompt)
        assertEquals("video negative", restored.videoNegativePrompt)
        assertEquals("480", restored.videoWidth)
        assertEquals("832", restored.videoHeight)
        assertEquals("18", restored.videoSteps)
        assertEquals("6.0", restored.videoCfg)
        assertEquals("456", restored.videoSeed)
        assertEquals("euler", restored.videoSampler)
        assertEquals("exponential", restored.videoScheduler)
        assertEquals("5.0", restored.videoFlowShift)
        assertTrue(restored.videoUseVae)
        assertEquals("/models/video-vae.gguf", restored.videoVaePath)
        assertTrue(restored.videoUseT5xxl)
        assertEquals("/models/video-t5xxl.gguf", restored.videoT5xxlPath)
    }

    @Test
    fun `legacy settings template hydrates split image and video values`() {
        val restored = settingsFromJson(
            """
            {
              "prompt": "legacy prompt",
              "negativePrompt": "legacy negative",
              "dimensions": "704 x 384",
              "steps": "24",
              "cfg": "8.0",
              "seed": "999",
              "sampler": "heun",
              "scheduler": "karras",
              "flowShift": "4.5"
            }
            """.trimIndent()
        )

        assertEquals("legacy prompt", restored.imagePrompt)
        assertEquals("legacy prompt", restored.videoPrompt)
        assertEquals("legacy negative", restored.imageNegativePrompt)
        assertEquals("legacy negative", restored.videoNegativePrompt)
        assertEquals("704", restored.imageWidth)
        assertEquals("384", restored.imageHeight)
        assertEquals("704", restored.videoWidth)
        assertEquals("384", restored.videoHeight)
        assertEquals("24", restored.imageSteps)
        assertEquals("24", restored.videoSteps)
        assertEquals("8.0", restored.imageCfg)
        assertEquals("8.0", restored.videoCfg)
        assertEquals("999", restored.imageSeed)
        assertEquals("999", restored.videoSeed)
        assertEquals("heun", restored.imageSampler)
        assertEquals("heun", restored.videoSampler)
        assertEquals("karras", restored.imageScheduler)
        assertEquals("karras", restored.videoScheduler)
        assertEquals("4.5", restored.imageFlowShift)
        assertEquals("4.5", restored.videoFlowShift)
    }
}
