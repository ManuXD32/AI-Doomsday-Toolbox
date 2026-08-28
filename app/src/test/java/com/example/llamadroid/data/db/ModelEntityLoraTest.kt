package com.example.llamadroid.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEntityLoraTest {

    @Test
    fun `legacy quadtrix lora repository rows are recognized`() {
        val model = ModelEntity(
            filename = "adapter.gguf",
            path = "/models/adapter.gguf",
            sizeBytes = 1L,
            type = ModelType.QUADTRIX,
            repoId = "quadtrix/lora/qwen-profile"
        )

        assertTrue(model.isLegacyQuadtrixLoraAdapter())
    }

    @Test
    fun `ordinary quadtrix rows are not recognized as legacy lora`() {
        val model = ModelEntity(
            filename = "checkpoint.bin",
            path = "/models/checkpoint.bin",
            sizeBytes = 1L,
            type = ModelType.QUADTRIX,
            repoId = "quadtrix/qwen-profile"
        )

        assertFalse(model.isLegacyQuadtrixLoraAdapter())
    }
}
