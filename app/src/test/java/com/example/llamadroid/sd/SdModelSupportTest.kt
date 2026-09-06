package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdModelSupportTest {

    @Test
    fun `infers sdxl checkpoint family`() {
        val inferred = inferSdFamily(
            type = ModelType.SD_CHECKPOINT,
            repoId = "city96/sdxl-gguf",
            filename = "sdxl-q4.gguf"
        )

        assertEquals(SdModelFamily.CHECKPOINT, inferred.first)
        assertEquals("sdxl", inferred.second)
    }

    @Test
    fun `infers flux kontext diffusion family`() {
        val inferred = inferSdFamily(
            type = ModelType.SD_DIFFUSION,
            repoId = "city96/FLUX.1-Kontext-dev-gguf",
            filename = "flux1-kontext-dev-q4.gguf"
        )

        assertEquals(SdModelFamily.FLUX_KONTEXT, inferred.first)
        assertEquals("dev", inferred.second)
    }

    @Test
    fun `infers qwen image edit 2511 family`() {
        val inferred = inferSdFamily(
            type = ModelType.SD_DIFFUSION,
            repoId = "Qwen/Qwen-Image-Edit",
            filename = "qwen-image-edit-2511-q4.gguf"
        )

        assertEquals(SdModelFamily.QWEN_IMAGE_EDIT, inferred.first)
        assertEquals("2511", inferred.second)
    }

    @Test
    fun `default capabilities for kontext support txt2img and img2img`() {
        assertEquals(
            buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
            defaultCapabilitiesForFamily(SdModelFamily.FLUX_KONTEXT, ModelType.SD_DIFFUSION)
        )
    }

    @Test
    fun `compat matching honors explicit compat profiles`() {
        val model = ModelEntity(
            filename = "qwen-llm.gguf",
            path = "/models/qwen-llm.gguf",
            sizeBytes = 1024L,
            type = ModelType.LLM,
            repoId = "local",
            sdCompatProfiles = "qwen_image_edit:2511"
        )

        assertTrue(model.matchesSdFamily(SdModelFamily.QWEN_IMAGE_EDIT, "2511"))
    }

    @Test
    fun `resolved family spec falls back to inference`() {
        val model = ModelEntity(
            filename = "sd3.5-large-q4.gguf",
            path = "/models/sd3.5-large-q4.gguf",
            sizeBytes = 2048L,
            type = ModelType.SD_CHECKPOINT,
            repoId = "stabilityai/stable-diffusion-3.5-large-gguf"
        )

        val spec = model.resolveSdFamilySpec()

        assertEquals(SdModelFamily.SD3, spec?.family)
        assertTrue(spec?.requiredRoles?.contains(SdComponentRole.CLIP_G) == true)
    }

    @Test
    fun `video family bridge keeps stable stored values`() {
        assertEquals(SdModelFamily.LINGBOT_VIDEO, SdVideoFamily.LINGBOT_VIDEO.toSdModelFamily())
        assertEquals(SdVideoFamily.SVD, SdModelFamily.SVD.toVideoFamily())
        assertEquals("lingbot_video", SdVideoFamily.LINGBOT_VIDEO.toSdModelFamily().storedValue)
    }

    @Test
    fun `video main selection requires explicit or detected family`() {
        val generic = ModelEntity(
            filename = "model.safetensors",
            path = "/models/model.safetensors",
            sizeBytes = 1024L,
            type = ModelType.SD_DIFFUSION,
            repoId = "local",
            sdCapabilities = SD_CAPABILITY_VID_GEN
        )
        val lingbot = generic.copy(sdFamily = SdModelFamily.LINGBOT_VIDEO.storedValue)

        assertTrue(generic.isSdVideoMainModel())
        assertFalse(generic.matchesSdVideoFamily(SdVideoFamily.LINGBOT_VIDEO))
        assertTrue(lingbot.matchesSdVideoFamily(SdVideoFamily.LINGBOT_VIDEO, "dense_1.3b"))
        assertFalse(lingbot.matchesSdVideoFamily(SdVideoFamily.WAN))
    }

    @Test
    fun `video companion defaults include the role-specific family set`() {
        assertTrue(SdModelFamily.LTX_VIDEO.storedValue in defaultCompatProfilesFor(ModelType.SD_AUDIO_VAE))
        assertTrue(SdModelFamily.LTX_VIDEO.storedValue in defaultCompatProfilesFor(ModelType.SD_EMBEDDINGS_CONNECTORS))
        assertTrue(SdModelFamily.ANIMATEDIFF.storedValue in defaultCompatProfilesFor(ModelType.SD_MOTION_MODULE))
        assertTrue(SdModelFamily.WAN.storedValue in defaultCompatProfilesFor(ModelType.SD_LORA))
        assertTrue(SdModelFamily.WAN.storedValue in defaultCompatProfilesFor(ModelType.SD_CLIP_VISION))
        assertTrue(SdModelFamily.SVD.storedValue in defaultCompatProfilesFor(ModelType.SD_CLIP_VISION))
    }
}
