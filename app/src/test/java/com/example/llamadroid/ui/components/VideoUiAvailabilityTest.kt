package com.example.llamadroid.ui.components

import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoHiresConfig
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoPromptFormat
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.validateSdLoras
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.VideoRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUiAvailabilityTest {
    @Test
    fun `workflow readiness names all required Wan video to video inputs`() {
        val components = SdVideoComponentPaths(
            diffusionModelPath = "/models/wan.safetensors",
            t5xxlPath = "/models/t5xxl.gguf",
            vaePath = "/models/wan.vae.safetensors"
        )
        val withoutControlVideo = VideoRuntimeOptions(
            videoFamily = SdVideoFamily.WAN,
            workflow = SdVideoWorkflow.VIDEO_TO_VIDEO,
            videoComponents = components,
            videoInputs = SdVideoInputs(initImagePath = "/inputs/start.png")
        )

        val missing = videoGenerationReadiness(withoutControlVideo)

        assertFalse(missing.isSatisfied)
        assertEquals(listOf(com.example.llamadroid.sd.SdVideoInputRole.CONTROL_VIDEO), missing.missingInputs)
        assertTrue(
            videoGenerationReadiness(
                withoutControlVideo.copy(
                    videoInputs = withoutControlVideo.videoInputs.copy(controlVideoPath = "/inputs/control")
                )
            ).isSatisfied
        )
    }

    @Test
    fun `disabled malformed LoRA drafts do not enter launch validation`() {
        val disabledMalformed = SdLoraSpec(path = "", enabled = false)
        val enabled = SdLoraSpec(path = "/models/adapter.safetensors", enabled = true)

        val activeDrafts = videoLorasForValidation(listOf(disabledMalformed), listOf(enabled))
        assertEquals(listOf(enabled), activeDrafts)
        assertEquals(listOf(enabled), validateSdLoras(activeDrafts))
    }

    @Test
    fun `unavailable profile selections are cleared only by explicit reset`() {
        val original = VideoRuntimeOptions(
            videoFamily = SdVideoFamily.HUNYUAN_VIDEO,
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            useTae = true,
            highNoiseSteps = 8,
            highNoiseCfgScale = 2.5f,
            audioCodec = com.example.llamadroid.sd.SdVideoAudioCodec.AAC,
            promptFormat = SdVideoPromptFormat.LINGBOT_CAPTION_JSON,
            hires = SdVideoHiresConfig(enabled = true, scale = 2f)
        )
        val availability = videoUiAvailability(
            profile = SdVideoFamilyProfiles.HUNYUAN_VIDEO,
            binaryCapabilities = SdBinaryCapabilities.ALLOW_ALL
        )

        assertTrue(original.hasUnavailableAdvancedSelections(availability))
        val cleared = original.clearUnavailableAdvancedSelections(availability)

        assertNotEquals(original, cleared)
        assertTrue(original.hires.enabled)
        assertTrue(cleared.hires.enabled.not())
        assertEquals(null, cleared.highNoiseSteps)
        assertEquals(null, cleared.highNoiseCfgScale)
        assertEquals(null, cleared.audioCodec)
        // HunyuanVideo accepts a 32-channel TAE since 0.999998, so the TAE choice is kept.
        assertEquals(true, cleared.useTae)
        assertEquals(SdVideoPromptFormat.PLAIN, cleared.promptFormat)
    }

    @Test
    fun `family without high-noise pass hides the group and reports no engine blocks`() {
        val availability = videoUiAvailability(
            profile = SdVideoFamilyProfiles.LINGBOT_VIDEO,
            binaryCapabilities = SdBinaryCapabilities.ALLOW_ALL
        )
        val defaults = VideoRuntimeOptions()

        assertFalse(availability.highNoiseFamilySupported)
        assertTrue(availability.binaryBlockedControls.isEmpty())
        assertTrue(availability.familyBlockedControls.all { it.requiresHighNoiseProfile })
        assertEquals(8, availability.familyBlockedControls.size)
        assertTrue(availability.familyBlockedControls.none { defaults.hasSavedValue(it) })
    }

    @Test
    fun `saved high-noise value is reported as family blocked`() {
        val availability = videoUiAvailability(
            profile = SdVideoFamilyProfiles.LINGBOT_VIDEO,
            binaryCapabilities = SdBinaryCapabilities.ALLOW_ALL
        )
        val options = VideoRuntimeOptions(highNoiseGuidance = 3f)

        assertTrue(availability.familyBlockedControls.any { options.hasSavedValue(it) })
        assertTrue(availability.binaryBlockedControls.none { options.hasSavedValue(it) })
    }

    @Test
    fun `binary missing a flag is reported as binary blocked not family blocked`() {
        val availability = videoUiAvailability(
            profile = SdVideoFamilyProfiles.WAN,
            binaryCapabilities = SdBinaryCapabilities(
                supportedFlags = setOf("--guidance"),
                supportedModes = setOf("vid_gen")
            )
        )
        val options = VideoRuntimeOptions(eta = 0.5f)

        assertTrue(availability.highNoiseFamilySupported)
        assertTrue(availability.familyBlockedControls.isEmpty())
        assertTrue(VideoAdditionalControl.ETA in availability.binaryBlockedControls)
        assertTrue(availability.binaryBlockedControls.any { options.hasSavedValue(it) })
        assertTrue(VideoAdditionalControl.GUIDANCE !in availability.binaryBlockedControls)
    }
}
