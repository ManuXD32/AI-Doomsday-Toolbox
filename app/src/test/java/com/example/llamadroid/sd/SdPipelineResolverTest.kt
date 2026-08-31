package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.buildSdCommandArgs
import com.example.llamadroid.service.inferSdFamilyForConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdPipelineResolverTest {

    @Test
    fun `standalone diffusion SD3 is detected without a FLUX fallback`() {
        assertEquals(
            SdModelFamily.SD3,
            inferSdFamily(
                ModelType.SD_DIFFUSION,
                repoId = "stabilityai/stable-diffusion-3.5",
                filename = "sd3.5-large-diffusion.safetensors"
            ).first
        )
        assertEquals(
            null,
            inferSdFamily(
                ModelType.SD_DIFFUSION,
                repoId = "local/unknown",
                filename = "transformer-q8.gguf"
            ).first
        )
    }

    @Test
    fun `standalone SD3 resolves required components and SD3 VAE format`() {
        val config = SDConfig(
            modelPath = "/models/sd3-transformer.gguf",
            modelFamily = SdModelFamily.SD3.storedValue,
            modelLayout = SdMainLayout.STANDALONE_DIFFUSION,
            prompt = "a lighthouse",
            outputPath = "/tmp/out.png",
            vaePath = "/models/sd3-vae.safetensors",
            clipLPath = "/models/clip-l.safetensors",
            clipGPath = "/models/clip-g.safetensors",
            t5xxlPath = "/models/t5xxl.gguf"
        )

        val pipeline = resolveValidatedSdPipeline(config)
        assertEquals(SdMainLayout.STANDALONE_DIFFUSION, pipeline.mainLayout)
        assertEquals("sd3", pipeline.vaeFormatOverride)

        val args = buildSdCommandArgs(config, pipeline, SdBinaryCapabilities.ALLOW_ALL)
        assertTrue(args.contains("--diffusion-model"))
        assertFalse(args.contains("-m"))
        assertTrue(args.windowed(2).any { it == listOf("--vae-format", "sd3") })
    }

    @Test
    fun `standalone SD3 blocks until VAE and encoders are selected`() {
        val pipeline = resolveSdPipeline(
            SDConfig(
                modelPath = "/models/sd3-transformer.gguf",
                modelFamily = SdModelFamily.SD3.storedValue,
                modelLayout = SdMainLayout.STANDALONE_DIFFUSION,
                prompt = "a lighthouse",
                outputPath = "/tmp/out.png"
            )
        )

        assertFalse(pipeline.isValid)
        assertEquals(
            setOf(SdComponentRole.VAE, SdComponentRole.CLIP_L, SdComponentRole.CLIP_G, SdComponentRole.T5XXL),
            pipeline.blockingIssues.mapNotNull { it.role }.toSet()
        )
    }

    @Test
    fun `full SD3 only requires encoders absent from the inspected artifact`() {
        val config = SDConfig(
            modelPath = "/models/sd3-full.safetensors",
            modelFamily = SdModelFamily.SD3.storedValue,
            modelLayout = SdMainLayout.FULL_MODEL,
            prompt = "a lighthouse",
            outputPath = "/tmp/out.png"
        )
        val inspection = SdArtifactInspection(
            format = SdArtifactFormat.SAFETENSORS,
            detectedFamily = SdModelFamily.SD3,
            detectedRole = SdArtifactRole.FULL_MODEL,
            mainLayout = SdMainLayout.FULL_MODEL,
            containsDiffusion = true,
            containsVae = true,
            containsClipL = true,
            containsClipG = true,
            containsT5xxl = true,
            tensorCount = 100,
            confidence = SdInspectionConfidence.HIGH
        )

        val pipeline = resolveValidatedSdPipeline(config, inspection)
        val args = buildSdCommandArgs(config, pipeline, SdBinaryCapabilities.ALLOW_ALL)
        assertTrue(args.contains("-m"))
        assertFalse(args.contains("--diffusion-model"))
        assertTrue(pipeline.requiredExternalRoles.isEmpty())
    }

    @Test
    fun `legacy flux hint cannot classify unknown diffusion`() {
        val config = SDConfig(
            modelPath = "/models/transformer.gguf",
            prompt = "a lighthouse",
            outputPath = "/tmp/out.png",
            isFluxModel = true
        )
        assertEquals(null, inferSdFamilyForConfig(config).first)
    }

}
