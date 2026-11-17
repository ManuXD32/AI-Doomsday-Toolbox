package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdScheduler
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoRuntimeOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdMasterLaunchTest {
    private val runtime = SdDistributedRuntimeConfig(enabled = true, rpcServers = "10.0.0.2:50062")

    @Test
    fun imageConfigMatchesHandWrittenExpectation() {
        val settings = SdDistributedMasterSettingsEntity(
            imageWorkflowMode = "TXT2IMG",
            imageModelPath = "/m/flux.gguf",
            imagePrompt = "a cat",
            imageNegativePrompt = "blurry",
            imageWidth = "768",
            imageHeight = "640",
            imageSteps = "12",
            imageCfg = "3.5",
            imageSeed = "42",
            imageSampler = "euler",
            imageScheduler = "",
            imageFlowShift = "2.5",
            imageVaePath = "/m/ae.safetensors",
            imageT5xxlPath = "/m/t5.gguf",
            imageInputPath = "/ignored.png",
            runtimeThreads = "6",
            vaeTiling = true,
            vaeTileSize = "",
            vaeTileOverlap = "0.25",
            diffusionFa = true,
            mmap = true,
            quantization = "q8_0",
            tensorRules = "rule",
            controlStrength = "0.5",
            loraStrength = "0.8",
            cacheMode = "",
            imageCustomFlags = "--foo"
        )
        val actual = buildSdImageConfig(settings, runtime, "/out/a.png", "flux", "dev")
        val expected = SDConfig(
            mode = SDMode.TXT2IMG,
            modelPath = "/m/flux.gguf",
            prompt = "a cat",
            negativePrompt = "blurry",
            width = 768,
            height = 640,
            steps = 12,
            cfgScale = 3.5f,
            seed = 42L,
            samplingMethod = SamplingMethod.entries.first { it.cliName == "euler" || it.name == "euler" },
            scheduler = SdScheduler.fromCliName(""),
            outputPath = "/out/a.png",
            initImage = null,
            strength = 0.75f,
            threads = 6,
            vaeTiling = true,
            vaeTileOverlap = 0.25f,
            vaeTileSize = "32x32",
            tensorTypeRules = "rule",
            modelFamily = "flux",
            modelVariant = "dev",
            vaePath = "/m/ae.safetensors",
            taePath = null,
            clipLPath = null,
            clipGPath = null,
            t5xxlPath = "/m/t5.gguf",
            llmPath = null,
            llmVisionPath = null,
            photoMakerPath = null,
            controlNetPath = null,
            controlImagePath = null,
            controlStrength = 0.5f,
            loraPath = null,
            loraStrength = 0.8f,
            loraApplyMode = null,
            loras = emptyList(),
            cacheMode = null,
            cacheOption = "",
            scmMask = "",
            scmPolicy = null,
            flowShift = 2.5f,
            diffusionFa = true,
            mmap = true,
            sdParamsBackendSpec = "auto",
            sdParamsBackendMode = "auto",
            sdRuntimeBackendMode = "auto",
            quantizationType = "q8_0",
            distributedRuntime = runtime,
            customFlags = "--foo"
        )
        assertEquals(expected, actual)
    }

    @Test
    fun img2imgCarriesInitImage() {
        val settings = SdDistributedMasterSettingsEntity(
            imageWorkflowMode = "IMG2IMG",
            imageModelPath = "/m/x.gguf",
            imagePrompt = "p",
            imageInputPath = "/in.png"
        )
        assertEquals("/in.png", buildSdImageConfig(settings, runtime, "/o.png", null, null).initImage)
    }

    @Test
    fun videoConfigMatchesHandWrittenExpectation() {
        val options = VideoRuntimeOptions(
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            videoComponents = SdVideoComponentPaths(diffusionModelPath = "/m/wan.gguf"),
            seed = -1L
        )
        val settings = SdDistributedMasterSettingsEntity(
            videoWorkflowMode = "TXT2VID",
            videoModelPath = "/m/wan.gguf",
            videoPrompt = "waves",
            videoNegativePrompt = "bad",
            videoUseVae = true,
            videoVaePath = "/m/vae.safetensors",
            videoUseT5xxl = true,
            videoT5xxlPath = "/m/umt5.gguf",
            videoSeed = "99",
            frames = "17",
            fps = "16",
            videoWidth = "480",
            videoHeight = "832",
            videoSteps = "10",
            videoCfg = "5.5",
            videoFlowShift = "3.0",
            videoSampler = "euler",
            videoScheduler = "",
            videoLoraApplyMode = "",
            cacheMode = "",
            vaeTileSize = "",
            diffusionFa = true,
            mmap = true,
            runtimeThreads = "4",
            videoCustomFlags = "--bar"
        )
        val dir = File("/out/txt2vid")
        val actual = buildSdVideoConfig(settings, options, runtime, dir, "video_1")

        val components = SdVideoComponentPaths(
            diffusionModelPath = "/m/wan.gguf",
            fullModelPath = "/m/wan.gguf",
            vaePath = "/m/vae.safetensors",
            t5xxlPath = "/m/umt5.gguf"
        )
        val expected = VideoGenerationConfig(
            mode = VideoGenerationMode.TXT2VID,
            prompt = "waves",
            negativePrompt = "bad",
            diffusionModelPath = "/m/wan.gguf",
            outputAviPath = File(dir, "video_1.avi").absolutePath,
            outputMp4Path = File(dir, "video_1.mp4").absolutePath,
            metadataPath = File(dir, "video_1.json").absolutePath,
            initImagePath = null,
            useVae = true,
            vaePath = "/m/vae.safetensors",
            useT5xxl = true,
            t5xxlPath = "/m/umt5.gguf",
            videoFrames = 17,
            fps = 16,
            width = 480,
            height = 832,
            steps = 10,
            cfgScale = 5.5f,
            flowShift = 3.0f,
            samplingMethod = SamplingMethod.EULER,
            scheduler = SdScheduler.fromCliName(""),
            loras = emptyList(),
            highNoiseLoras = emptyList(),
            loraApplyMode = SdLoraApplyMode.fromStoredValue(""),
            cacheMode = null,
            cacheOption = "",
            scmMask = "",
            scmPolicy = null,
            vaeTiling = false,
            vaeTileSize = "24x24",
            diffusionFa = true,
            mmap = true,
            threads = 4,
            sdParamsBackendSpec = "auto",
            sdParamsBackendMode = "auto",
            sdRuntimeBackendMode = "auto",
            maxVramCpuGiB = "",
            distributedRuntime = runtime,
            customFlags = "--bar",
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            videoComponents = components,
            videoInputs = SdVideoInputs(),
            seed = 99L,
            nativeOutputPath = File(dir, "video_1.avi").absolutePath
        )
        assertEquals(expected, actual)
    }

    @Test
    fun effectiveVideoOptionsKeepEditorValuesOverLegacyColumns() {
        val settings = SdDistributedMasterSettingsEntity(
            videoModelPath = "/legacy.gguf",
            videoUseVae = false,
            videoVaePath = "/legacy-vae",
            videoSeed = "5"
        )
        val options = VideoRuntimeOptions(
            videoComponents = SdVideoComponentPaths(diffusionModelPath = "/editor.gguf"),
            seed = 8L
        )
        val effective = effectiveSdVideoOptions(settings, options)
        assertEquals("/editor.gguf", effective.videoComponents.diffusionModelPath)
        assertNull(effective.videoComponents.vaePath)
        assertEquals(8L, effective.seed)
    }

    @Test
    fun runtimeBuilderSkipsDisabledHelpers() {
        val settings = SdDistributedMasterSettingsEntity(masterContributes = false)
        val workers = listOf(
            SdDistributedWorkerEntity(id = 1, host = "10.0.0.2", isEnabled = true),
            SdDistributedWorkerEntity(id = 2, host = "10.0.0.3", isEnabled = false)
        )
        val built = buildSdMasterRuntime(settings, workers)
        assertEquals(listOf("10.0.0.2"), built.workerRuntimes.map { it.host })
    }
}
