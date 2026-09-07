package com.example.llamadroid.ui.models

import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceRepository
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import org.junit.Assert.*
import org.junit.Test

class ModelPromotionChoicesTest {
    @Test fun everyVisibleChoiceRegistersTheExactSelectedRuntimeType() {
        ModelFamily.entries.forEach { family ->
            val choices = modelPromotionChoices(family)
            assertTrue(choices.isNotEmpty())
            assertEquals(choices.size, choices.map { it.id }.distinct().size)
            choices.forEach { choice ->
                assertEquals("$family ${choice.id}", choice.type,
                    ModelSourceRepository.runtimeModelTypeFor(family, choice.role))
            }
        }
    }

    @Test fun sdOffersVideoAndLlmCompanionsWithoutPretendingTheyAreCheckpoints() {
        val choices = modelPromotionChoices(ModelFamily.SD)
        assertEquals(ModelType.LLM, choices.single { it.role == "llm" }.type)
        assertEquals(ModelType.SD_DIFFUSION, choices.single { it.sdCapabilities == "vid_gen" }.type)
        assertTrue(choices.any { it.type == ModelType.SD_AUDIO_VAE })
        assertTrue(choices.any { it.type == ModelType.SD_EMBEDDINGS_CONNECTORS })
        assertTrue(choices.any { it.type == ModelType.SD_MOTION_MODULE })
    }

    @Test fun restoringVideoBundleKeepsVideoChoiceInsteadOfGenericDiffusion() {
        val artifact = PendingModelArtifactEntity(id = "fixture", filename = "video.safetensors",
            stagingPath = "/fixture", detectedType = ModelType.SD_DIFFUSION.name, requestedRole = "diffusion")
        val choice = initialModelPromotionChoice(ModelFamily.SD, artifact,
            """{"modelType":"SD_DIFFUSION","sdCapabilities":"vid_gen"}""")
        assertEquals("vid_gen", choice.sdCapabilities)
    }

    @Test fun explicitDraftRoleTakesPrecedenceOverGenericContainerDetection() {
        val artifact = PendingModelArtifactEntity(id = "fixture", filename = "draft.gguf",
            stagingPath = "/fixture", detectedType = ModelType.LLM.name, requestedRole = "draft")
        assertEquals(ModelType.LLM_DRAFT, initialModelPromotionChoice(ModelFamily.LLM, artifact, null).type)
    }
}
