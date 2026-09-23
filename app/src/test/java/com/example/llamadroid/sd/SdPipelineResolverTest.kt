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
    fun `qwen image 21 text generation requires its VAE and text encoder`() {
        val pipeline = resolveValidatedSdPipeline(
            SDConfig(
                modelPath = "/models/qwen_image_2.1-Q4_K.gguf",
                modelFamily = SdModelFamily.QWEN_IMAGE.storedValue,
                modelVariant = "2.1",
                modelLayout = SdMainLayout.STANDALONE_DIFFUSION,
                prompt = "a lighthouse",
                outputPath = "/tmp/out.png",
                vaePath = "/models/qwen-image-vae.safetensors",
                llmPath = "/models/Qwen3VL-8B-Instruct-Q4_K_M.gguf"
            )
        )

        assertEquals(
            setOf(SdComponentRole.LLM, SdComponentRole.VAE),
            pipeline.requiredExternalRoles
        )
        val args = buildSdCommandArgs(
            SDConfig(
                modelPath = "/models/qwen_image_2.1-Q4_K.gguf",
                modelFamily = SdModelFamily.QWEN_IMAGE.storedValue,
                modelVariant = "2.1",
                modelLayout = SdMainLayout.STANDALONE_DIFFUSION,
                prompt = "a lighthouse",
                outputPath = "/tmp/out.png",
                vaePath = "/models/qwen-image-vae.safetensors",
                llmPath = "/models/Qwen3VL-8B-Instruct-Q4_K_M.gguf"
            ),
            pipeline,
            SdBinaryCapabilities.ALLOW_ALL
        )
        assertTrue(args.containsAll(listOf("--diffusion-model", "--llm", "--vae")))
        assertFalse(args.contains("--llm_vision"))
    }

    @Test
    fun `qwen image 21 image editing requires and emits the matching mmproj`() {
        val base = SDConfig(
            mode = com.example.llamadroid.service.SDMode.IMG2IMG,
            modelPath = "/models/qwen_image_2.1-Q4_K.gguf",
            modelFamily = SdModelFamily.QWEN_IMAGE.storedValue,
            modelVariant = "2.1",
            modelLayout = SdMainLayout.STANDALONE_DIFFUSION,
            prompt = "edit this image",
            outputPath = "/tmp/out.png",
            initImage = "/tmp/input.png",
            vaePath = "/models/qwen-image-vae.safetensors",
            llmPath = "/models/Qwen3VL-8B-Instruct-Q4_K_M.gguf"
        )
        val missingVision = resolveSdPipeline(base)
        assertFalse(missingVision.isValid)
        assertTrue(
            missingVision.blockingIssues.any {
                it.role == SdComponentRole.LLM_VISION
            }
        )

        val withVision = base.copy(
            llmVisionPath = "/models/mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf"
        )
        val pipeline = resolveValidatedSdPipeline(withVision)
        assertTrue(SdComponentRole.LLM_VISION in pipeline.requiredExternalRoles)
        val args = buildSdCommandArgs(withVision, pipeline, SdBinaryCapabilities.ALLOW_ALL)
        assertTrue(args.containsAll(listOf("--llm_vision", "-r", "/tmp/input.png")))
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
    fun `configured family remains authoritative when inspection disagrees`() {
        val inspection = SdArtifactInspection(
            format = SdArtifactFormat.SAFETENSORS,
            detectedFamily = SdModelFamily.FLUX_2,
            detectedRole = SdArtifactRole.FULL_MODEL,
            mainLayout = SdMainLayout.FULL_MODEL,
            containsDiffusion = true,
            tensorCount = 1L,
            confidence = SdInspectionConfidence.HIGH
        )

        val pipeline = resolveSdPipeline(
            SDConfig(
                modelPath = "/models/manual-chroma.safetensors",
                modelFamily = SdModelFamily.CHROMA.storedValue,
                modelLayout = SdMainLayout.FULL_MODEL,
                prompt = "a lighthouse",
                outputPath = "/tmp/out.png"
            ),
            inspection
        )

        assertEquals(SdModelFamily.CHROMA, pipeline.family)
        assertTrue(pipeline.blockingIssues.isEmpty())
        assertTrue(pipeline.warnings.any { it.code == SdPipelineIssueCode.DETECTED_FAMILY_CONFLICT })
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
