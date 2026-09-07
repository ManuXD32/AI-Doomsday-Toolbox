package com.example.llamadroid.ui.ai

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdModelsUiSupportTest {
    @Test
    fun `shared LLM and projector rows are shown without compatibility metadata`() {
        assertTrue(isAdditionalSdModel(model(ModelType.LLM, "Qwen3VL-4B-Instruct-Q4_K_M.gguf")))
        assertTrue(isAdditionalSdModel(model(ModelType.VISION_PROJECTOR)))
        assertTrue(isAdditionalSdModel(model(ModelType.MMPROJ)))
    }

    @Test
    fun `ordinary non-SD rows remain outside the additional SD section`() {
        assertFalse(isAdditionalSdModel(model(ModelType.WHISPER)))
    }

    private fun model(type: ModelType, filename: String = "model.bin") = ModelEntity(
        filename = filename,
        path = "/models/$filename",
        sizeBytes = 1L,
        type = type,
        repoId = "test",
        sdCompatProfiles = null
    )
}
