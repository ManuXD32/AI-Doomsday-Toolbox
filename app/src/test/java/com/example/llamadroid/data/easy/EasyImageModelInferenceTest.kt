package com.example.llamadroid.data.easy

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyImageModelInferenceTest {
    @Test
    fun `onnx models always use the onnx preset`() {
        assertEquals(
            EasyImageKind.ONNX_SD15,
            inferEasyImageKind(ModelType.ONNX_IMAGE_GEN, "flux-onnx-bundle")
        )
    }

    @Test
    fun `flux and schnell names map to the flux preset`() {
        assertEquals(EasyImageKind.FLUX_SCHNELL, inferEasyImageKind(ModelType.SD_DIFFUSION, "flux1-schnell-q4_0.gguf"))
        assertEquals(EasyImageKind.FLUX_SCHNELL, inferEasyImageKind(ModelType.SD_DIFFUSION, "model.gguf", sdFamily = "flux_1"))
        assertEquals(EasyImageKind.FLUX_SCHNELL, inferEasyImageKind(ModelType.SD_CHECKPOINT, "My-Schnell.safetensors"))
    }

    @Test
    fun `sdxl needs turbo to use the turbo preset`() {
        assertEquals(EasyImageKind.SDXL_TURBO, inferEasyImageKind(ModelType.SD_CHECKPOINT, "sdxl-turbo-q8.gguf"))
        assertEquals(EasyImageKind.SDXL_TURBO, inferEasyImageKind(ModelType.SD_CHECKPOINT, "sd_xl_turbo_1.0.safetensors"))
        assertEquals(EasyImageKind.SDXL_TURBO, inferEasyImageKind(ModelType.SD_CHECKPOINT, "Juggernaut-XL-turbo.gguf"))
        assertEquals(EasyImageKind.SD15_SDCPP, inferEasyImageKind(ModelType.SD_CHECKPOINT, "sdxl-base-1.0.safetensors"))
        assertEquals(EasyImageKind.SD15_SDCPP, inferEasyImageKind(ModelType.SD_CHECKPOINT, "turbo-v1.gguf"))
    }

    @Test
    fun `plain checkpoints fall back to sd15 and xl needs a whole token`() {
        assertEquals(EasyImageKind.SD15_SDCPP, inferEasyImageKind(ModelType.SD_CHECKPOINT, "dreamshaper_8.safetensors"))
        assertEquals(EasyImageKind.SD15_SDCPP, inferEasyImageKind(ModelType.SD_CHECKPOINT, "pixl-turbo.gguf"))
    }

    @Test
    fun `local ids round trip`() {
        val id = easyLocalImageModelId("my model.gguf")
        assertEquals("my model.gguf", parseEasyLocalImageModelId(id))
        assertNull(parseEasyLocalImageModelId("image-sd15-q4"))
        assertNull(parseEasyLocalImageModelId("local:"))
    }

    @Test
    fun `sd slots cover flux bundle roles and ignore unrelated types`() {
        assertEquals(EasySdSlot.MAIN, easySdSlotFor(ModelType.SD_DIFFUSION))
        assertEquals(EasySdSlot.MAIN, easySdSlotFor(ModelType.SD_CHECKPOINT))
        assertEquals(EasySdSlot.CLIP_L, easySdSlotFor(ModelType.SD_CLIP_L))
        assertEquals(EasySdSlot.T5XXL, easySdSlotFor(ModelType.SD_T5XXL))
        assertEquals(EasySdSlot.VAE, easySdSlotFor(ModelType.SD_VAE))
        assertNull(easySdSlotFor(ModelType.LLM))
        assertNull(easySdSlotFor(ModelType.SD_LORA))
        assertTrue(isEasySdMainType(ModelType.SD_CHECKPOINT))
        assertFalse(isEasySdMainType(ModelType.SD_VAE))
    }

    @Test
    fun `size coercion keeps allowed sizes and defaults otherwise`() {
        val flux = EasyImagePresets.forKind(EasyImageKind.FLUX_SCHNELL)
        assertEquals(1024 to 1024, coerceEasyImageSize(flux, 1024 to 1024))
        assertEquals(512 to 512, coerceEasyImageSize(flux, 512 to 768))
        assertEquals(512 to 512, coerceEasyImageSize(flux, null))
        assertEquals("512×768", easySizeLabel(512 to 768))
    }
}
