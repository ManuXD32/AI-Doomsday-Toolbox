package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryManagerTest {

    @Test
    fun relativeDirFor_maps_model_families_to_play_safe_library_folders() {
        assertEquals("llm", ModelLibraryManager.relativeDirFor(ModelType.LLM))
        assertEquals("llm", ModelLibraryManager.relativeDirFor(ModelType.LORA))
        assertEquals("llm", ModelLibraryManager.relativeDirFor(ModelType.EMBEDDING))
        assertEquals("mmproj", ModelLibraryManager.relativeDirFor(ModelType.VISION_PROJECTOR))
        assertEquals("mmproj", ModelLibraryManager.relativeDirFor(ModelType.MMPROJ))
        assertEquals("sd/checkpoints", ModelLibraryManager.relativeDirFor(ModelType.SD_CHECKPOINT))
        assertEquals("sd/flux", ModelLibraryManager.relativeDirFor(ModelType.SD_DIFFUSION))
        assertEquals("sd/clip_l", ModelLibraryManager.relativeDirFor(ModelType.SD_CLIP_L))
        assertEquals("sd/clip_g", ModelLibraryManager.relativeDirFor(ModelType.SD_CLIP_G))
        assertEquals("sd/t5xxl", ModelLibraryManager.relativeDirFor(ModelType.SD_T5XXL))
        assertEquals("sd/tae", ModelLibraryManager.relativeDirFor(ModelType.SD_TAE))
        assertEquals("sd/vae", ModelLibraryManager.relativeDirFor(ModelType.SD_VAE))
        assertEquals("sd/lora", ModelLibraryManager.relativeDirFor(ModelType.SD_LORA))
        assertEquals("sd/controlnet", ModelLibraryManager.relativeDirFor(ModelType.SD_CONTROLNET))
        assertEquals("sd/photomaker", ModelLibraryManager.relativeDirFor(ModelType.SD_PHOTOMAKER))
        assertEquals("onnx", ModelLibraryManager.relativeDirFor(ModelType.ONNX_IMAGE_GEN))
        assertEquals("onnx", ModelLibraryManager.relativeDirFor(ModelType.ONNX_TTS))
        assertEquals("whisper", ModelLibraryManager.relativeDirFor(ModelType.WHISPER))
        assertEquals("quadtrix", ModelLibraryManager.relativeDirFor(ModelType.QUADTRIX))
    }

    @Test
    fun relativePathFor_maps_lora_to_managed_llm_library_path() {
        assertEquals("llm/adapter.gguf", ModelLibraryManager.relativePathFor(ModelType.LORA, "adapter.gguf"))
    }

    @Test
    fun relativePathForLiteRt_uses_dedicated_folder() {
        assertEquals("litert/demo.litertlm", ModelLibraryManager.relativePathForLiteRt("demo.litertlm"))
    }
}
