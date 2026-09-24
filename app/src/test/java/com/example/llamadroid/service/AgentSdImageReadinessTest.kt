package com.example.llamadroid.service

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentSdImageReadinessTest {
    @Test
    fun qwenImageReadinessRequiresSelectedFamilyCompatibleExistingSupportFiles() {
        val directory = Files.createTempDirectory("agent-sd-readiness-").toFile()
        try {
            val main = createModelFile(directory, "qwen-image-2.1.gguf", ModelType.SD_DIFFUSION)
                .copy(sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG))
            val llm = createModelFile(directory, "qwen3-vl.gguf", ModelType.SD_LLM)
            val vae = createModelFile(directory, "qwen-image-vae.safetensors", ModelType.SD_VAE)

            val missing = resolveAgentSdImageReadiness(
                mainModels = listOf(main),
                supportModels = listOf(llm, vae),
                sdParams = NativeChatSdImageToolParams(model = main.filename),
            )
            assertTrue(missing.modelSelected)
            assertTrue(missing.familySupported)
            assertFalse(missing.ready)
            assertEquals(setOf(SdComponentRole.LLM, SdComponentRole.VAE), missing.missingRequiredRoles.toSet())

            val ready = resolveAgentSdImageReadiness(
                mainModels = listOf(main),
                supportModels = listOf(llm, vae),
                sdParams = NativeChatSdImageToolParams(
                    model = main.filename,
                    llmPath = llm.filename,
                    vaePath = vae.filename,
                ),
            )
            assertTrue(ready.ready)

            val missingFile = resolveAgentSdImageReadiness(
                mainModels = listOf(main),
                supportModels = listOf(llm.copy(path = File(directory, "missing.gguf").absolutePath), vae),
                sdParams = NativeChatSdImageToolParams(
                    model = main.filename,
                    llmPath = llm.filename,
                    vaePath = vae.filename,
                ),
            )
            assertFalse(missingFile.ready)
            assertTrue(SdComponentRole.LLM in missingFile.missingRequiredRoles)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createModelFile(directory: File, filename: String, type: ModelType): ModelEntity {
        val file = File(directory, filename).apply { writeText("test") }
        return ModelEntity(
            filename = filename,
            path = file.absolutePath,
            sizeBytes = file.length(),
            type = type,
            repoId = "local/test",
            sdFamily = SdModelFamily.QWEN_IMAGE.storedValue,
            sdVariant = "2.1",
            sdCompatProfiles = "qwen_image:2.1",
        )
    }
}
