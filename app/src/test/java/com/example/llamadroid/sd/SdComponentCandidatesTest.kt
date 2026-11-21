package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdComponentCandidatesTest {

    private fun row(
        filename: String,
        type: ModelType,
        family: String? = null,
        variant: String? = null,
        profiles: String? = null
    ) = ModelEntity(
        filename = filename,
        path = "/models/$filename",
        sizeBytes = 1024L,
        type = type,
        repoId = "local/$filename",
        sdFamily = family,
        sdVariant = variant,
        sdCompatProfiles = profiles
    )

    private val qwenMain = row(
        "qwen-image-2.1-Q4_K_M.gguf", ModelType.SD_DIFFUSION,
        family = "qwen_image", variant = "2.1", profiles = "qwen_image:2.1"
    )
    private val qwenLlm = row(
        "Qwen3VL-8B-Instruct-Q4_K_M.gguf", ModelType.SD_LLM,
        family = "qwen_image", variant = "2.1", profiles = "qwen_image:2.1"
    )
    private val qwenProjectorF16 = row(
        "mmproj-F16.gguf", ModelType.MMPROJ,
        family = "qwen_image", variant = "2.1", profiles = "qwen_image:2.1"
    )
    private val qwenProjectorQ8 = row(
        "mmproj-Q8_0.gguf", ModelType.MMPROJ,
        family = "qwen_image", variant = "2.1", profiles = "qwen_image:2.1"
    )
    private val qwenVae = row(
        "qwen_image_2.1_vae.safetensors", ModelType.SD_VAE,
        family = "qwen_image", variant = "2.1", profiles = "qwen_image:2.1"
    )
    private val customLlm = row("my-llama.gguf", ModelType.LLM)
    private val customLora = row("style.safetensors", ModelType.SD_LORA)
    private val customTae = row("custom-tae.safetensors", ModelType.SD_TAE)

    private fun fits(list: List<SdComponentCandidate>) = list.associate { it.model.filename to it.fit }

    @Test
    fun `qwen 21 bundle rows are MATCH for the qwen main and ordered`() {
        val installed = listOf(qwenMain, qwenLlm, qwenProjectorQ8, qwenProjectorF16, qwenVae, customLlm)

        val llm = sdComponentCandidates(qwenMain, installed, SdComponentRole.LLM)
        assertEquals(SdComponentFit.MATCH, fits(llm)[qwenLlm.filename])
        assertEquals(SdComponentFit.UNVERIFIED, fits(llm)[customLlm.filename])
        assertEquals(qwenLlm.filename, llm.first().model.filename)

        val vision = sdComponentCandidates(qwenMain, installed, SdComponentRole.LLM_VISION)
        assertEquals(listOf(qwenProjectorF16.filename, qwenProjectorQ8.filename), vision.map { it.model.filename })
        assertTrue(vision.all { it.fit == SdComponentFit.MATCH })

        val vae = sdComponentCandidates(qwenMain, installed, SdComponentRole.VAE)
        assertEquals(listOf(SdComponentCandidate(qwenVae, SdComponentFit.MATCH)), vae)
    }

    @Test
    fun `custom files without profiles are present but UNVERIFIED`() {
        val installed = listOf(qwenMain, customLlm, customLora)
        assertEquals(
            SdComponentFit.UNVERIFIED,
            sdComponentCandidates(qwenMain, installed, SdComponentRole.LLM).single().fit
        )
        assertEquals(
            SdComponentFit.UNVERIFIED,
            sdComponentCandidates(qwenMain, installed, SdComponentRole.LORA).single().fit
        )
        val fluxMain = row("flux1-schnell.gguf", ModelType.SD_DIFFUSION, family = "flux_1", variant = "schnell")
        val tae = sdComponentCandidates(fluxMain, listOf(fluxMain, customTae), SdComponentRole.TAE)
        assertEquals(listOf(SdComponentCandidate(customTae, SdComponentFit.UNVERIFIED)), tae)
    }

    @Test
    fun `null main lists every file as UNVERIFIED and never lists the main itself`() {
        val installed = listOf(qwenMain, qwenLlm, customLlm)
        val all = sdComponentCandidates(null, installed, SdComponentRole.LLM)
        assertEquals(2, all.size)
        assertTrue(all.all { it.fit == SdComponentFit.UNVERIFIED })
        assertTrue(sdComponentCandidates(qwenMain, installed, SdComponentRole.MAIN_MODEL).isEmpty())
    }

    @Test
    fun `role table maps llm and projector to both types`() {
        assertEquals(setOf(ModelType.LLM, ModelType.SD_LLM), sdRoleModelTypes(SdComponentRole.LLM))
        assertEquals(
            setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ),
            sdRoleModelTypes(SdComponentRole.LLM_VISION)
        )
        assertEquals(setOf(ModelType.SD_LLM, ModelType.LLM), sdVideoRoleModelTypes(SdVideoComponentRole.LLM))
    }

    @Test
    fun `embedded vae from inspection removes vae from missing roles`() {
        val inspection = SdArtifactInspection(
            format = SdArtifactFormat.SAFETENSORS,
            containsVae = true,
            tensorCount = 10L
        )
        val withVae = qwenMain.withSdArtifactInspection(inspection)
        assertTrue(SdComponentRole.VAE in sdMainEmbeddedRoles(withVae))

        val missingBare = sdEffectiveMissingRoles(qwenMain, emptyMap(), img2img = false)
        assertTrue(SdComponentRole.VAE in missingBare)
        assertTrue(SdComponentRole.LLM in missingBare)

        val missingEmbedded = sdEffectiveMissingRoles(withVae, emptyMap(), img2img = false)
        assertFalse(SdComponentRole.VAE in missingEmbedded)
        assertTrue(SdComponentRole.LLM in missingEmbedded)

        val filled = sdEffectiveMissingRoles(
            qwenMain,
            mapOf(SdComponentRole.LLM to "/models/a", SdComponentRole.VAE to "/models/b"),
            img2img = false
        )
        assertTrue(filled.isEmpty())
    }

    @Test
    fun `uninspected checkpoint counts as full model with vae and clip`() {
        val sdxl = row("sdxl.safetensors", ModelType.SD_CHECKPOINT, family = "checkpoint", variant = "sdxl")
        assertEquals(
            setOf(SdComponentRole.VAE, SdComponentRole.CLIP_L, SdComponentRole.CLIP_G),
            sdMainEmbeddedRoles(sdxl)
        )
        assertTrue(sdMainEmbeddedRoles(qwenMain).isEmpty())
        assertTrue(sdMainEmbeddedRoles(null).isEmpty())
    }

    @Test
    fun `qwen 21 img2img needs the vision projector`() {
        assertEquals(SdImg2ImgBlock.NEEDS_VISION_PROJECTOR, sdImg2ImgBlock(qwenMain, ""))
        assertNull(sdImg2ImgBlock(qwenMain, "/models/mmproj-F16.gguf"))

        val paths = mapOf(SdComponentRole.LLM to "/m/llm", SdComponentRole.VAE to "/m/vae")
        assertTrue(sdEffectiveMissingRoles(qwenMain, paths, img2img = false).isEmpty())
        assertEquals(
            listOf(SdComponentRole.LLM_VISION),
            sdEffectiveMissingRoles(qwenMain, paths, img2img = true)
        )
        assertTrue(
            sdEffectiveMissingRoles(
                qwenMain, paths + (SdComponentRole.LLM_VISION to "/m/mm"), img2img = true
            ).isEmpty()
        )
    }

    @Test
    fun `txt2img only family is not supported for img2img`() {
        val flux = row("flux1-schnell.gguf", ModelType.SD_DIFFUSION, family = "flux_1", variant = "schnell")
        assertEquals(SdImg2ImgBlock.NOT_SUPPORTED, sdImg2ImgBlock(flux, ""))
        assertNull(sdImg2ImgBlock(null, ""))
        val sd15 = row("sd15.safetensors", ModelType.SD_CHECKPOINT, family = "checkpoint", variant = "sd1")
        assertNull(sdImg2ImgBlock(sd15, ""))
    }
}
