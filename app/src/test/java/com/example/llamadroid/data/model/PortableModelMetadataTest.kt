package com.example.llamadroid.data.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableModelMetadataTest {
    @Test fun `LiteRT portability keeps explicit capabilities without paths or runtime claims`() {
        val model = LiteRtModelEntity(displayName = "Fixture", filename = "model.task", path = "/private/model.task",
            supportsVision = true, supportsAudio = true, supportsGpu = false, maxContextTokens = 4096,
            kbEmbeddingRunnable = true, kbEmbeddingStatus = "ready on this device")
        val metadata = JSONObject(PortableModelMetadata.fromLiteRt(model))
        assertTrue(metadata.getBoolean("supportsVision")); assertTrue(metadata.getBoolean("supportsAudio"))
        assertFalse(metadata.getBoolean("supportsGpu")); assertEquals(4096, metadata.getInt("maxContextTokens"))
        assertFalse(metadata.has("path")); assertFalse(metadata.has("kbEmbeddingRunnable"))
        assertFalse(metadata.has("kbEmbeddingStatus"))
        assertEquals("{}", PortableModelMetadata.sanitize("""{"maxContextTokens":4294967297}"""))
    }
    @Test fun `portable definitions keep runtime compatibility and drop installation data`() {
        val raw = JSONObject().put("modelType", "SD_DIFFUSION")
            .put("sdFamily", "lingbot_video").put("sdVariant", "dense_1.3b")
            .put("sdCompatProfiles", "lingbot_video:dense_1.3b")
            .put("path", "/data/user/0/private/model.gguf")
            .put("huggingFaceToken", "test-credential")
            .put("onnxReferenceUri", "content://private/image")
            .put("isVision", true).toString()
        val portable = JSONObject(PortableModelMetadata.sanitize(raw))
        assertEquals("lingbot_video", portable.getString("sdFamily"))
        assertEquals("dense_1.3b", portable.getString("sdVariant"))
        assertTrue(portable.getBoolean("isVision"))
        assertFalse(portable.has("path"))
        assertFalse(portable.has("huggingFaceToken"))
        assertFalse(portable.has("onnxReferenceUri"))
    }

    @Test fun `malformed oversized and path-valued compatibility metadata are discarded`() {
        assertEquals("{}", PortableModelMetadata.sanitize("not JSON"))
        assertEquals("{}", PortableModelMetadata.sanitize("x".repeat(32769)))
        val raw = JSONObject().put("sdFamily", "/private/path").put("liteRtProfile", "https://private/token")
        assertEquals("{}", PortableModelMetadata.sanitize(raw.toString()))
    }
}
