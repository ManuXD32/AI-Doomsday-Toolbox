package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.toSdLoraSpecs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReusePayloadTest {
    @Test
    fun `distributed transfer explains unavailable direct convolution controls`() {
        val restored = VideoReuseDraftAdapter.toDistributedDraft(payload().copy(diffusionConvDirect = true))
        assertTrue(restored.warnings.any { it.code == VideoReuseWarningCode.DESTINATION_OPTION_UNAVAILABLE })
    }

    @Test
    fun `warning language selects the requested translation`() {
        val warning = VideoReuseWarning(VideoReuseWarningCode.MISSING_REFERENCE, english = "Missing", spanish = "Falta")
        org.junit.Assert.assertEquals("Missing", warning.message(false))
        org.junit.Assert.assertEquals("Falta", warning.message(true))
    }

    @Test
    fun `numeric only distributed edits require replacement confirmation`() {
        val defaults = com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity()
        org.junit.Assert.assertFalse(VideoReuseDraftAdapter.hasMeaningfulDistributedDraft(defaults))
        org.junit.Assert.assertTrue(VideoReuseDraftAdapter.hasMeaningfulDistributedDraft(defaults.copy(videoSteps = "123")))
    }

    @Test
    fun `local adapter restores scalar typed advanced and ordered lora state`() {
        val regular = listOf(
            SdLoraSpec("/missing/first.safetensors", strength = 0.35f, highNoiseOnly = false),
            SdLoraSpec("/missing/second.safetensors", strength = 0.85f, highNoiseOnly = true)
        )
        val highNoise = listOf(
            SdLoraSpec("/missing/high-a.safetensors", strength = 1.2f, highNoiseOnly = true),
            SdLoraSpec("/missing/high-b.safetensors", strength = 0.6f, enabled = false, highNoiseOnly = true)
        )
        val payload = payload(
            loras = regular,
            highNoiseLoras = highNoise,
            runtimeOptions = VideoRuntimeOptions(
                videoFamily = SdVideoFamily.WAN,
                workflow = SdVideoWorkflow.IMAGE_TO_VIDEO,
                videoComponents = SdVideoComponentPaths(
                    diffusionModelPath = "/missing/model.safetensors",
                    highNoiseDiffusionModelPath = "/missing/high-noise.safetensors",
                    clipVisionPath = "/missing/clip-vision.safetensors"
                ),
                videoInputs = SdVideoInputs(
                    initImagePath = "/missing/init.png",
                    endImagePath = "/missing/end.png",
                    referenceImages = listOf("/missing/reference.png")
                ),
                seed = 1234L,
                highNoiseSteps = 7,
                highNoiseCfgScale = 2.25f,
                highNoiseSamplingMethod = SamplingMethod.HEUN,
                controlStrength = 0.72f,
                guidance = 3.5f,
                extraSampleArgs = "--custom-sampler-value"
            )
        )

        val draft = VideoReuseDraftAdapter.toLocalDraftJson(payload)

        assertEquals(1, draft.optInt("mode"))
        assertEquals("a prompt", draft.optString("prompt"))
        assertEquals("9", draft.optString("frames"))
        assertEquals(1234L, parseVideoRuntimeOptions(draft.optString("videoAdvancedJson"))?.seed)
        assertEquals(regular, draft.optJSONArray("loras")!!.toSdLoraSpecs())
        assertEquals(highNoise, draft.optJSONArray("highNoiseLoras")!!.toSdLoraSpecs())
        assertFalse(draft.optBoolean("distributedEnabled", true))
        assertTrue(draft.optString("videoAdvancedJson").isNotBlank())
    }

    @Test
    fun `verified source replaces only missing exact role and keeps missing path visible`() {
        val installed = tempFile("installed-model")
        val missing = "/old/shared/model.safetensors"
        val payload = payload(
            modelPath = missing,
            runtimeOptions = VideoRuntimeOptions(
                videoFamily = SdVideoFamily.WAN,
                videoComponents = SdVideoComponentPaths(diffusionModelPath = missing),
                videoInputs = SdVideoInputs(initImagePath = "/old/shared/input.png")
            )
        )

        val resolved = payload.withResolvedReferences(
            mapOf("model" to installed.path, "input" to "/does/not/exist.png")
        )
        val draft = VideoReuseDraftAdapter.toLocalDraft(payload, verifiedSources = mapOf("model" to installed.path))

        assertEquals(installed.path, resolved.mainModelPath)
        assertEquals(installed.path, draft.json.optString("model"))
        assertTrue(draft.warnings.any { it.code == VideoReuseWarningCode.MISSING_REFERENCE })
        assertTrue(draft.warnings.single { it.code == VideoReuseWarningCode.MISSING_REFERENCE }.path!!.endsWith("input.png"))
        installed.delete()
    }

    @Test
    fun `readable hires upscaler directory is retained as an available reference`() {
        val directory = createTempDir(prefix = "video-reuse-upscalers-")
        val payload = payload(
            runtimeOptions = VideoRuntimeOptions(
                videoComponents = SdVideoComponentPaths(hiresUpscalersDir = directory.path),
                videoInputs = SdVideoInputs(controlVideoPath = directory.path, referenceVideos = listOf(directory.path))
            )
        )

        val evidence = payload.referenceEvidence()
            .single { it.field == "videoComponents.hiresUpscalersDir" }

        assertEquals(VideoReuseReferenceSource.RECORDED_PATH, evidence.source)
        assertTrue(
            payload.warnings().none {
                it.code == VideoReuseWarningCode.MISSING_REFERENCE &&
                    it.path == directory.path
            }
        )
        directory.deleteRecursively()
    }

    @Test
    fun `old metadata gets safe defaults and explicit advanced warning`() {
        val metadata = payload(runtimeOptions = VideoRuntimeOptions()).toMetadataWithoutTypedRuntime()

        val restored = VideoReusePayload.fromMetadata(metadata)

        assertFalse(restored.runtimeOptionsAvailable)
        assertTrue(restored.warnings().any { it.code == VideoReuseWarningCode.ADVANCED_OPTIONS_UNAVAILABLE })
        assertEquals(VideoGenerationMode.IMG2VID, metadata.modeEnum)
        assertEquals(SdVideoWorkflow.IMAGE_TO_VIDEO, restored.resolvedRuntimeOptions.workflow)
    }

    @Test
    fun `distributed adapter preserves placement only when current workers match`() {
        val distributedPayload = payload(
            distributedRuntime = SdDistributedRuntimeConfig(
                enabled = true,
                rpcServers = "10.0.0.2:50062",
                placementMode = SdDistributedPlacementMode.COMPONENTS,
                backendSpec = "CPU:RPC0",
                paramsBackendSpec = "auto:RPC0",
                maxVramSpec = "RPC0=4",
                splitMode = SdDistributedSplitMode.ROW,
                customFlags = "--workers RPC0"
            )
        )
        val worker = VideoReuseWorker("10.0.0.2", 50062)
        val restored = VideoReuseDraftAdapter.toDistributedSettings(
            distributedPayload,
            currentWorkers = listOf(worker)
        )
        val mismatch = VideoReuseDraftAdapter.toDistributedSettings(
            distributedPayload,
            currentWorkers = listOf(VideoReuseWorker("10.0.0.3", 50062))
        )
        val localSource = VideoReuseDraftAdapter.toDistributedSettings(
            payload(),
            currentWorkers = listOf(worker)
        )

        assertTrue(restored.settings.enabled)
        assertEquals("COMPONENTS", restored.settings.placementMode)
        assertEquals("row", restored.settings.splitMode)
        assertEquals("CPU:RPC0", restored.settings.backendSpec)
        assertFalse(mismatch.settings.enabled)
        assertTrue(mismatch.warnings.any { it.code == VideoReuseWarningCode.DISTRIBUTED_WORKER_MISMATCH })
        assertEquals("", mismatch.settings.backendSpec)
        assertTrue(localSource.settings.enabled)
    }

    @Test
    fun `handoff persists a bounded metadata reference and clears only matching target`() {
        val directory = createTempDir(prefix = "video-reuse-handoff-")
        val file = File(directory, VideoReuseHandoffStore.FILE_NAME)
        val published = VideoReuseHandoffStore.publish(
            file,
            "/data/video metadata.json",
            VideoReuseTarget.DISTRIBUTED
        )

        assertEquals(published, VideoReuseHandoffStore.peek(file))
        assertTrue(VideoReuseHandoffStore.complete(file, published.copy(target = VideoReuseTarget.LOCAL)).not())
        assertTrue(VideoReuseHandoffStore.complete(file, published.copy(createdAt = published.createdAt - 1L)).not())
        assertNotNull(VideoReuseHandoffStore.peek(file))
        assertTrue(VideoReuseHandoffStore.complete(file, published))
        assertEquals(null, VideoReuseHandoffStore.peek(file))
        directory.deleteRecursively()
    }

    @Test
    fun `fresh local draft defaults do not request replacement while edits do`() {
        val defaults = VideoReuseDraftAdapter.toLocalDraftJson(
            payload(
                runtimeOptions = VideoRuntimeOptions(workflow = SdVideoWorkflow.TEXT_TO_VIDEO)
            )
        )
        val fresh = org.json.JSONObject()
            .put("mode", 0)
            .put("frames", "8")
            .put("fps", "5")
            .put("width", "480")
            .put("height", "832")
            .put("steps", "18")
            .put("cfg", "6.0")
            .put("threads", "-1")
            .put("sampler", SamplingMethod.EULER.name)
            .put("vaeTiling", true)
            .put("diffusionFa", true)
            .put("mmap", true)
            .put("videoAdvancedJson", VideoRuntimeOptions(workflow = SdVideoWorkflow.TEXT_TO_VIDEO).toJsonString())

        assertFalse(VideoReuseDraftAdapter.hasMeaningfulLocalDraft(fresh))
        assertTrue(VideoReuseDraftAdapter.hasMeaningfulLocalDraft(org.json.JSONObject(fresh.toString()).put("steps", "19")))
        assertTrue(defaults.optString("videoAdvancedJson").isNotBlank())
    }

    private fun payload(
        modelPath: String = "/missing/model.safetensors",
        loras: List<SdLoraSpec> = emptyList(),
        highNoiseLoras: List<SdLoraSpec> = emptyList(),
        runtimeOptions: VideoRuntimeOptions = VideoRuntimeOptions(workflow = SdVideoWorkflow.TEXT_TO_VIDEO),
        distributedRuntime: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig()
    ): VideoReusePayload = VideoReusePayload(
        mode = VideoGenerationMode.IMG2VID,
        prompt = "a prompt",
        negativePrompt = "bad",
        modelPath = modelPath,
        videoFrames = 9,
        fps = 17,
        width = 640,
        height = 360,
        steps = 23,
        cfgScale = 4.5f,
        flowShift = 2.0f,
        samplingMethod = SamplingMethod.HEUN,
        scheduler = SdScheduler.KARRAS,
        cacheMode = SdCacheMode.EASYCACHE,
        cacheOption = "6",
        scmMask = "mask",
        scmPolicy = SdCacheScmPolicy.STATIC,
        threads = 3,
        vaeTiling = true,
        vaeTileSize = "32x32",
        diffusionFa = false,
        diffusionConvDirect = true,
        vaeConvDirect = true,
        mmap = false,
        sdRuntimeBackendMode = "te=cpu,diffusion=vulkan0,vae=cpu",
        customFlags = "--video-flag",
        distributedRuntime = distributedRuntime,
        loras = loras,
        highNoiseLoras = highNoiseLoras,
        loraApplyMode = "all",
        videoFamily = runtimeOptions.videoFamily?.storedValue,
        workflow = runtimeOptions.workflow?.storedValue,
        videoComponents = runtimeOptions.videoComponents,
        videoInputs = runtimeOptions.videoInputs,
        runtimeOptions = runtimeOptions
    )

    private fun VideoReusePayload.toMetadataWithoutTypedRuntime(): GeneratedVideoMetadata =
        GeneratedVideoMetadata(
            mode = mode.folderName,
            prompt = prompt,
            negativePrompt = negativePrompt,
            diffusionModelPath = modelPath,
            diffusionModelName = "model.safetensors",
            vaeEnabled = vaeEnabled,
            vaePath = vaePath,
            vaeName = null,
            t5xxlEnabled = t5xxlEnabled,
            t5xxlPath = t5xxlPath,
            t5xxlName = null,
            initImagePath = initImagePath,
            videoFrames = videoFrames,
            fps = fps,
            width = width,
            height = height,
            steps = steps,
            cfgScale = cfgScale,
            flowShift = flowShift,
            samplingMethod = samplingMethod,
            scheduler = scheduler,
            cacheMode = cacheMode,
            cacheOption = cacheOption,
            scmMask = scmMask,
            scmPolicy = scmPolicy,
            threads = threads,
            vaeTiling = vaeTiling,
            vaeTileSize = vaeTileSize,
            diffusionFa = diffusionFa,
            diffusionConvDirect = diffusionConvDirect,
            vaeConvDirect = vaeConvDirect,
            mmap = mmap,
            sdParamsBackendMode = sdParamsBackendMode,
            sdParamsBackendSpec = sdParamsBackendSpec,
            sdRuntimeBackendMode = sdRuntimeBackendMode,
            maxVramCpuGiB = maxVramCpuGiB,
            customFlags = customFlags,
            distributedRuntime = distributedRuntime,
            loras = loras,
            highNoiseLoras = highNoiseLoras,
            loraApplyMode = loraApplyMode,
            createdAt = 1L,
            aviPath = "",
            mp4Path = "",
            metadataPath = "/tmp/video.json",
            videoFamily = videoFamily,
            videoVariant = videoVariant,
            workflow = workflow,
            videoComponents = videoComponents,
            videoInputs = videoInputs,
            videoRuntimeOptions = null,
            useTae = useTae,
            seed = runtimeOptions.seed,
            highNoiseSteps = runtimeOptions.highNoiseSteps,
            highNoiseCfgScale = runtimeOptions.highNoiseCfgScale,
            highNoiseSamplingMethod = runtimeOptions.highNoiseSamplingMethod,
            controlStrength = runtimeOptions.controlStrength,
            vaeTileOverlap = runtimeOptions.vaeTileOverlap,
            vaeRelativeTileSize = runtimeOptions.vaeRelativeTileSize
        )

    private fun tempFile(prefix: String): File = File.createTempFile(prefix, ".bin").apply {
        writeText("test")
    }
}
