package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.R
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdDistributedLaunchValidationTest {
    private fun image(
        mode: SDMode = SDMode.TXT2IMG,
        modelPath: String = "/m.gguf",
        prompt: String = "cat",
        width: Int? = 512,
        height: Int? = 512,
        missing: List<SdComponentRole> = emptyList(),
        input: String = "",
        upscaler: String = "",
        controlNetEnabled: Boolean = false,
        controlNetPath: String = "",
        loraEnabled: Boolean = false,
        loraPath: String = ""
    ) = sdDistributedImageLaunchIssue(
        mode, modelPath, prompt, width, height, missing, input, upscaler,
        controlNetEnabled, controlNetPath, loraEnabled, loraPath
    )

    @Test
    fun imageBranchesReturnTheirIssue() {
        assertNull(image())
        assertEquals(SdDistributedLaunchIssue.SelectImageModel, image(modelPath = ""))
        assertEquals(SdDistributedLaunchIssue.PromptRequired, image(prompt = " "))
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.imagegen_width_label),
            image(width = null)
        )
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.imagegen_height_label),
            image(height = null)
        )
        assertEquals(
            SdDistributedLaunchIssue.MissingImageComponents(listOf(SdComponentRole.VAE)),
            image(missing = listOf(SdComponentRole.VAE))
        )
        assertEquals(SdDistributedLaunchIssue.InputImageRequired, image(mode = SDMode.IMG2IMG))
        assertEquals(
            SdDistributedLaunchIssue.SelectUpscalerModel,
            image(mode = SDMode.UPSCALE, input = "/i.png")
        )
        assertEquals(
            SdDistributedLaunchIssue.InputImageRequired,
            image(mode = SDMode.UPSCALE, upscaler = "/u.bin")
        )
        assertEquals(
            SdDistributedLaunchIssue.MissingImageComponents(listOf(SdComponentRole.CONTROLNET)),
            image(mode = SDMode.IMG2IMG, input = "/i.png", controlNetEnabled = true)
        )
        assertEquals(
            SdDistributedLaunchIssue.MissingImageComponents(listOf(SdComponentRole.LORA)),
            image(loraEnabled = true)
        )
    }

    @Test
    fun upscaleIgnoresGenerationOnlyFields() {
        assertNull(image(mode = SDMode.UPSCALE, modelPath = "", prompt = "", width = null, input = "/i", upscaler = "/u"))
    }

    private val wanComponents = SdVideoComponentPaths(
        diffusionModelPath = "/wan.gguf",
        t5xxlPath = "/t5.gguf",
        vaePath = "/vae.safetensors"
    )

    private fun options(
        components: SdVideoComponentPaths = wanComponents,
        family: SdVideoFamily? = SdVideoFamily.WAN,
        workflow: SdVideoWorkflow = SdVideoWorkflow.TEXT_TO_VIDEO,
        init: String? = null
    ) = VideoRuntimeOptions(
        videoFamily = family,
        workflow = workflow,
        videoComponents = components,
        videoInputs = SdVideoInputs(initImagePath = init)
    )

    private fun video(
        opts: VideoRuntimeOptions = options(),
        mode: VideoGenerationMode = VideoGenerationMode.TXT2VID,
        prompt: String = "waves",
        frames: String = "8",
        width: Int? = 320,
        height: Int? = 320,
        loras: List<SdLoraSpec> = emptyList(),
        compatible: Set<String> = emptySet()
    ) = sdDistributedVideoLaunchIssue(mode, opts, prompt, frames, width, height, loras, compatible)

    @Test
    fun componentsChosenOnlyInTheEditorPass() {
        assertNull(video())
    }

    @Test
    fun videoBranchesReturnTheirIssue() {
        assertEquals(
            SdDistributedLaunchIssue.SelectVideoModel,
            video(opts = options(components = SdVideoComponentPaths(), family = null))
        )
        assertEquals(SdDistributedLaunchIssue.PromptRequired, video(prompt = ""))
        val missing = video(opts = options(components = wanComponents.copy(t5xxlPath = null)))
        assertEquals(
            SdDistributedLaunchIssue.MissingVideoComponents(listOf(SdVideoComponentRole.T5XXL)),
            missing
        )
        assertEquals(
            SdDistributedLaunchIssue.VideoUnsupportedWorkflow,
            video(opts = options(workflow = SdVideoWorkflow.TEXT_TO_AUDIO_VIDEO))
        )
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.sd_dist_frames),
            video(frames = "1")
        )
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.sd_dist_frames),
            video(frames = "")
        )
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.video_gen_width_label),
            video(width = null)
        )
        assertEquals(
            SdDistributedLaunchIssue.InvalidNumber(R.string.video_gen_height_label),
            video(height = null)
        )
    }

    @Test
    fun imageToVideoNeedsInitImageInEffectiveInputs() {
        val noProfile = options(family = null, workflow = SdVideoWorkflow.IMAGE_TO_VIDEO)
        assertEquals(
            SdDistributedLaunchIssue.InputImageRequired,
            video(opts = noProfile, mode = VideoGenerationMode.IMG2VID)
        )
        assertNull(
            video(opts = noProfile.copy(videoInputs = SdVideoInputs(initImagePath = "/i.png")), mode = VideoGenerationMode.IMG2VID)
        )
    }

    @Test
    fun incompatibleEnabledLorasAreReported() {
        val loras = listOf(SdLoraSpec(path = "/l/a.safetensors"), SdLoraSpec(path = "/l/off.safetensors", enabled = false))
        assertEquals(
            SdDistributedLaunchIssue.IncompatibleVideoLoras(listOf("/l/a.safetensors")),
            video(loras = loras)
        )
        assertNull(video(loras = loras, compatible = setOf("/l/a.safetensors")))
    }

    @Test
    fun legacyVaeAndT5FlagsNoLongerBlock() {
        // The legacy flags are not inputs of the validator at all: a draft with them on but no
        // legacy paths validates purely on the editor components.
        val issue = video(opts = options(components = wanComponents.copy(vaePath = null, taePath = "/tae.safetensors")))
        assertTrue(issue == null)
    }
}
