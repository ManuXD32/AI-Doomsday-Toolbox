package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.model.LiteRtModelEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledModelAssetTest {
    @Test
    fun `LiteRT asset keeps dedicated identity and portable capabilities`() {
        val model = LiteRtModelEntity(
            id = 27L,
            displayName = "Edge engine",
            filename = "engine.tflite",
            path = "/data/no_backup/litert_models/edge/engine.tflite",
            supportsNpu = true,
            supportsVision = true,
            maxContextTokens = 4096
        )

        val asset = InstalledModelAsset.fromLiteRt(model)
        val metadata = JSONObject(asset.metadataJson)

        assertEquals("litert:27", asset.stableId)
        assertEquals(ModelFamily.LITERT, asset.family)
        assertEquals("engine.tflite", asset.filename)
        assertTrue(metadata.getBoolean("supportsNpu"))
        assertTrue(metadata.getBoolean("supportsVision"))
        assertEquals(4096, metadata.getInt("maxContextTokens"))
    }
}

