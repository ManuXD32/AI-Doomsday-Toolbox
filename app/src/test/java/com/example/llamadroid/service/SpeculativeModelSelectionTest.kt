package com.example.llamadroid.service

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeModelSelectionTest {
    private val models = listOf(
        model("target.gguf", ModelType.LLM),
        model("vision-target.gguf", ModelType.VISION),
        model("mtp-draft.gguf", ModelType.LLM_DRAFT),
        model("embeddings.gguf", ModelType.EMBEDDING),
        model("projector.gguf", ModelType.VISION_PROJECTOR),
        model("adapter.gguf", ModelType.LORA)
    )

    @Test
    fun `standard draft modes expose normal LLM-compatible models only`() {
        listOf(
            LlamaSpeculativeMode.DRAFT_SIMPLE,
            LlamaSpeculativeMode.DRAFT_DFLASH,
            LlamaSpeculativeMode.DRAFT_DSPARK
        ).forEach { mode ->
            val selected = speculativeDraftModelsFor(models, mode)
            assertEquals(
                listOf("target.gguf", "vision-target.gguf"),
                selected.map { it.filename }
            )
        }
    }

    @Test
    fun `MTP mode exposes only dedicated draft models`() {
        val selected = speculativeDraftModelsFor(models, LlamaSpeculativeMode.DRAFT_MTP)

        assertEquals(listOf("mtp-draft.gguf"), selected.map { it.filename })
        assertTrue(selected.all { it.type == ModelType.LLM_DRAFT })
    }

    @Test
    fun `effective draft path rejects a path from another mode family`() {
        val standard = speculativeDraftModelsFor(models, LlamaSpeculativeMode.DRAFT_SIMPLE)
        val mtp = speculativeDraftModelsFor(models, LlamaSpeculativeMode.DRAFT_MTP)

        assertEquals(null, effectiveSpeculativeDraftPath("/models/mtp-draft.gguf", standard))
        assertEquals(null, effectiveSpeculativeDraftPath("/models/target.gguf", mtp))
        assertEquals(
            "/models/mtp-draft.gguf",
            effectiveSpeculativeDraftPath("/models/mtp-draft.gguf", mtp)
        )
    }

    private fun model(filename: String, type: ModelType) = ModelEntity(
        filename = filename,
        path = "/models/$filename",
        sizeBytes = 1024L,
        type = type,
        repoId = "test/$filename",
        isDownloaded = true
    )
}
