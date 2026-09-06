package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryRoleMappingTest {
    @Test
    fun `explicit stable diffusion roles keep their runtime types`() {
        assertEquals(ModelType.SD_VAE, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "vae"))
        assertEquals(ModelType.SD_CLIP_VISION, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "clip_vision"))
        assertEquals(ModelType.SD_IP_ADAPTER, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "ip-adapter"))
        assertEquals(ModelType.SD_CONTROLNET, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "controlnet"))
        assertEquals(ModelType.SD_ADETAILER, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "adetailer"))
        assertEquals(ModelType.SD_AUDIO_VAE, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "audioVAE"))
        assertEquals(ModelType.SD_EMBEDDINGS_CONNECTORS, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "connectors"))
        assertEquals(ModelType.SD_MOTION_MODULE, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "motionmodule"))
        assertEquals(ModelType.SD_DIFFUSION, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, "highnoise"))
        assertEquals(ModelType.ONNX_BACKGROUND_REMOVAL, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.ONNX, "background_removal"))
        assertEquals(ModelType.ONNX_IMAGE_UPSCALER, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.ONNX, "upscaler"))
    }

    @Test
    fun `lingbot component roles remain llm family metadata`() {
        assertEquals(ModelType.LORA, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "lora"))
        assertEquals(ModelType.EMBEDDING, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "embedding"))
        assertEquals(ModelType.LLM_DRAFT, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "draft"))
        assertEquals(ModelType.LLM, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "audioVAE"))
        assertEquals(ModelType.LLM, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "motionmodule"))
        assertEquals(ModelType.VISION_PROJECTOR, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "vision_projector"))
        assertEquals(ModelType.MMPROJ, ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, "CLIPvision"))
    }

    @Test
    fun `cross family source requires an explicit compatible companion role`() {
        assertTrue(isCompatibleSourceFamily(ModelFamily.LLM, ModelFamily.SD, "llm"))
        assertTrue(isCompatibleSourceFamily(ModelFamily.LLM, ModelFamily.SD, "LLMvision"))
        assertFalse(isCompatibleSourceFamily(ModelFamily.LLM, ModelFamily.SD, null))
        assertTrue(requiresManualRoleSelection(ModelFamily.SD, "motionmodule"))
    }
}
