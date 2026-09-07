package com.example.llamadroid.ui.models

import com.example.llamadroid.data.db.ModelType
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerViewModelTest {
    @Test
    fun `local model import entity is marked downloaded after copy`() {
        val file = Files.createTempFile("local-model-", ".gguf").toFile().apply {
            writeText("gguf")
            deleteOnExit()
        }

        val model = buildLocalModelEntity(
            path = file.absolutePath,
            filename = "unlimited-ocr.gguf",
            modelType = ModelType.LLM,
            hasVision = true
        )

        assertTrue(model.isDownloaded)
        assertTrue(model.isVision)
        assertEquals(ModelType.LLM, model.type)
        assertEquals(file.length(), model.sizeBytes)
    }

    @Test
    fun `embedding badge still changes local import type without changing downloaded contract`() {
        val model = buildLocalModelEntity(
            path = "/models/embedding.gguf",
            filename = "embedding.gguf",
            modelType = ModelType.LLM,
            hasVision = true,
            hasEmbedding = true
        )

        assertEquals(ModelType.EMBEDDING, model.type)
        assertTrue(model.isDownloaded)
        assertFalse(model.isVision)
    }
}
