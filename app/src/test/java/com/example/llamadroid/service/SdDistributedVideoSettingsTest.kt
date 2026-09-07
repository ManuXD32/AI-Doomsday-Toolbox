package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdDistributedVideoSettingsTest {

    @Test
    fun `advanced video contract survives distributed template round trip`() {
        val advanced = VideoRuntimeOptions(
            videoFamily = SdVideoFamily.LINGBOT_VIDEO,
            videoVariant = "dense_1.3b",
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/models/lingbot.safetensors",
                llmPath = "/models/qwen3-vl.gguf",
                taePath = "/models/taew2_1.safetensors"
            ),
            videoInputs = SdVideoInputs(referenceImages = listOf("/inputs/ref.png")),
            useTae = true,
            seed = 42L
        )
        val saved = SdDistributedMasterSettingsEntity(videoAdvancedJson = advanced.toJsonString())

        val restored = settingsFromJson(settingsToJson(saved))
        val parsed = restored.videoRuntimeOptionsOrNull()

        assertEquals(SdVideoFamily.LINGBOT_VIDEO, parsed?.videoFamily)
        assertEquals("dense_1.3b", parsed?.videoVariant)
        assertEquals("/models/taew2_1.safetensors", parsed?.videoComponents?.taePath)
        assertEquals(listOf("/inputs/ref.png"), parsed?.videoInputs?.referenceImages)
        assertEquals(42L, parsed?.seed)
    }

    @Test
    fun `legacy template leaves advanced contract absent`() {
        val restored = settingsFromJson(
            """{"videoModelPath":"/models/legacy.gguf","videoWorkflowMode":"TXT2VID"}"""
        )

        assertNull(restored.videoRuntimeOptionsOrNull())
        assertEquals("/models/legacy.gguf", restored.videoModelPath)
    }

    @Test
    fun `legacy template clears an unrelated current advanced draft`() {
        val current = SdDistributedMasterSettingsEntity(
            videoAdvancedJson = VideoRuntimeOptions(
                videoFamily = SdVideoFamily.LINGBOT_VIDEO,
                videoVariant = "dense_1.3b"
            ).toJsonString()
        )

        val restored = settingsFromJson(
            """{"videoModelPath":"/models/legacy.gguf","videoWorkflowMode":"TXT2VID"}""",
            base = current
        )

        assertNull(restored.videoRuntimeOptionsOrNull())
        assertEquals("{}", restored.videoAdvancedJson)
    }
}
