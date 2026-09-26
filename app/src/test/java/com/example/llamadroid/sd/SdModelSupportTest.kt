package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
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
    fun `infers flux 2 spellings and klein variants before generic flux`() {
        val cases = listOf(
            "flux.2-dev-q4.gguf" to "dev",
            "flux-2_dev-q4.gguf" to "dev",
            "flux_2-klein-4b.gguf" to "klein_4b",
            "chroma2-kaleidoscope.gguf" to null,
            "kaleidoscope.gguf" to null
        )

        cases.forEach { (filename, expectedVariant) ->
            val inferred = inferSdFamily(
                type = ModelType.SD_DIFFUSION,
                repoId = "local/flux-model",
                filename = filename
            )
            assertEquals(filename, SdModelFamily.FLUX_2, inferred.first)
            assertEquals(filename, expectedVariant, inferred.second)
        }
    }

    @Test
    fun `infers chroma radiance before generic chroma`() {
        val radiance = inferSdFamily(
            type = ModelType.SD_DIFFUSION,
            repoId = "local/chroma",
            filename = "chroma1-radiance-q4.gguf"
        )
        val chroma = inferSdFamily(
            type = ModelType.SD_DIFFUSION,
            repoId = "local/chroma",
            filename = "chroma-q4.gguf"
        )

        assertEquals(SdModelFamily.CHROMA_RADIANCE, radiance.first)
        assertEquals(SdModelFamily.CHROMA, chroma.first)
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
    fun `qwen image 21 exposes image editing capability and vision requirement`() {
        val expectedCapabilities = buildSdCapabilities(
            SD_CAPABILITY_TXT2IMG,
            SD_CAPABILITY_IMG2IMG
        )
        val spec = resolveSdFamilySpec(SdModelFamily.QWEN_IMAGE, "2.1")

        assertEquals(expectedCapabilities, defaultCapabilitiesForFamily(
            SdModelFamily.QWEN_IMAGE,
            ModelType.SD_DIFFUSION,
            "2.1"
        ))
        assertEquals(expectedCapabilities, spec.defaultCapabilities)
        assertTrue(SdComponentRole.LLM in spec.requiredRoles)
        assertTrue(SdComponentRole.VAE in spec.requiredRoles)
        assertTrue(SdComponentRole.LLM_VISION in spec.optionalRoles)
        assertTrue(spec.requiresVisionForImg2Img)
    }

    @Test
    fun `qwen image support rows match normalized and legacy 21 profiles`() {
        val normalized = ModelEntity(
            filename = "Qwen3VL-8B-Instruct-Q4_K_M.gguf",
            path = "/models/Qwen3VL-8B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1024L,
            type = ModelType.SD_LLM,
            repoId = "Qwen/Qwen3-VL-8B-Instruct-GGUF",
            sdFamily = SdModelFamily.QWEN_IMAGE.storedValue,
            sdVariant = "2.1",
            sdCompatProfiles = "qwen_image:2.1"
        )
        val legacy = normalized.copy(sdCompatProfiles = "qwen_image:qwen_image_2.1")
        val legacyMain = normalized.copy(
            type = ModelType.SD_DIFFUSION,
            sdVariant = "qwen_image_2.1"
        )
        val unrelated = ModelEntity(
            filename = "llama-3.1.gguf",
            path = "/models/llama-3.1.gguf",
            sizeBytes = 1024L,
            type = ModelType.LLM,
            repoId = "local/llama"
        )
        val explicitlyScoped = unrelated.copy(
            sdFamily = SdModelFamily.QWEN_IMAGE.storedValue,
            sdVariant = "2.1"
        )

        assertTrue(normalized.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertTrue(legacy.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertEquals("2.1", legacyMain.resolvedSdFamily().second)
        assertTrue(resolveSdFamilySpec(SdModelFamily.QWEN_IMAGE, legacyMain.sdVariant).requiresVisionForImg2Img)
        assertFalse(unrelated.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertTrue(explicitlyScoped.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
    }

    @Test
    fun `qwen image 21 excludes older generic VAE and vision components`() {
        val olderVae = ModelEntity(
            filename = "qwen_image_vae.safetensors",
            path = "/models/qwen_image_vae.safetensors",
            sizeBytes = 1024L,
            type = ModelType.SD_VAE,
            repoId = "legacy/qwen-image",
            sdFamily = SdModelFamily.QWEN_IMAGE.storedValue,
            sdCompatProfiles = SdModelFamily.QWEN_IMAGE.storedValue
        )
        val versionedVae = olderVae.copy(
            filename = "qwen_image_2.1_vae_bf16.safetensors",
            sdVariant = "2.1",
            sdCompatProfiles = "qwen_image:2.1"
        )
        val genericProjector = olderVae.copy(
            filename = "mmproj-qwen2.5-vl.gguf",
            type = ModelType.MMPROJ
        )

        assertFalse(olderVae.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertFalse(genericProjector.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertTrue(versionedVae.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
        assertTrue(olderVae.matchesSdFamily(SdModelFamily.QWEN_IMAGE, null))
        val manuallyDownloadedVae = olderVae.copy(
            filename = "qwen_image_2.1_vae_bf16.safetensors",
            repoId = "Comfy-Org/Qwen-Image-2.1",
            sdFamily = null,
            sdCompatProfiles = null
        )
        assertTrue(manuallyDownloadedVae.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
    }

    @Test
    fun `legacy downloaded Qwen 21 roles recover from curated filenames without redownload`() {
        val bundle = requireNotNull(SdCuratedBundleCatalog.byId("qwen-image-21-q4-vision"))
        val installedRows = bundle.files.map { file ->
            val filename = file.localFilename(bundle.installPrefix)
            ModelEntity(
                filename = filename,
                path = "/models/$filename",
                sizeBytes = file.sizeBytes,
                type = file.modelType,
                repoId = file.repoId
            )
        }

        assertTrue(installedRows.any { it.type == ModelType.SD_LLM && it.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1") })
        assertTrue(installedRows.any { it.type == ModelType.SD_VAE && it.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1") })
        assertTrue(installedRows.any { it.type == ModelType.MMPROJ && it.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1") })

        val f16 = installedRows.single { it.type == ModelType.MMPROJ }
        val q8 = f16.copy(filename = "Qwen-Image-2.1-Q4-mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf")
        assertEquals(
            listOf(f16.filename, q8.filename),
            orderQwenImage21VisionProjectors(listOf(q8, f16)).map { it.filename }
        )
        assertTrue(q8.matchesSdFamily(SdModelFamily.QWEN_IMAGE, "2.1"))
    }

    @Test
    fun `qwen image variant is inferred for support artifacts`() {
        val inferred = inferSdFamily(
            type = ModelType.MMPROJ,
            repoId = "Qwen/Qwen3-VL-8B-Instruct-GGUF",
            filename = "mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf"
        )

        // The projector's repository name alone is not an image-generation
        // family marker. A curated row supplies explicit Qwen metadata.
        assertEquals(null, inferred.first)
        assertEquals(null, inferred.second)
        val qwen = inferSdFamily(
            type = ModelType.MMPROJ,
            repoId = "leejet/Qwen-Image-2.1-GGUF",
            filename = "mmproj-qwen_image_2.1.gguf"
        )
        assertEquals(SdModelFamily.QWEN_IMAGE, qwen.first)
        assertEquals("2.1", qwen.second)
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
