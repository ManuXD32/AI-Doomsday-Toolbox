package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdLoraTest {
    @Test
    fun `legacy single lora maps to one ordered item`() {
        val mapped = SdLoraSpec.fromLegacy("/models/style.safetensors", 0.65f)
        assertEquals(listOf(SdLoraSpec("/models/style.safetensors", 0.65f)), mapped)
    }

    @Test
    fun `json preserves order strength enabled and high noise marker`() {
        val source = listOf(
            SdLoraSpec("/models/first.safetensors", 0.4f),
            SdLoraSpec("/models/wan-high.safetensors", 1.2f, highNoiseOnly = true)
        )
        val restored = JSONArray(source.toJsonArray().toString()).toSdLoraSpecs()
        assertEquals(source, restored)
        assertEquals("first", restored[0].promptTokenName)
        assertTrue(restored[1].highNoiseOnly)
    }

    @Test(expected = SdLoraConfigurationException::class)
    fun `duplicate paths are rejected before launch`() {
        validateSdLoras(
            listOf(
                SdLoraSpec("/models/style.safetensors"),
                SdLoraSpec("/models/style.safetensors", 0.5f)
            )
        )
    }

    @Test
    fun `lora compatibility follows selected base family`() {
        val base = model(ModelType.SD_CHECKPOINT, "checkpoint", "sd1")
        val compatible = model(ModelType.SD_LORA, null, null, "checkpoint:sd1")
        val incompatible = model(ModelType.SD_LORA, null, null, "checkpoint:sdxl")
        assertTrue(validateSdLoraModelCompatibility(base, listOf(compatible)).isEmpty())
        assertTrue(validateSdLoraModelCompatibility(base, listOf(incompatible)).isNotEmpty())
    }

    private fun model(type: ModelType, family: String?, variant: String?, profiles: String? = null): ModelEntity =
        ModelEntity(
            filename = "model.safetensors",
            path = "/models/model.safetensors",
            sizeBytes = 0L,
            type = type,
            repoId = "",
            sdFamily = family,
            sdVariant = variant,
            sdCompatProfiles = profiles
        )
}
