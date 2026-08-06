package com.example.llamadroid.ui.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdOptionalFeatureDraftSupportTest {

    @Test
    fun `old draft without ip adapter remains compatible`() {
        val draft = JSONObject().put("prompt", "old prompt")

        assertEquals(SdIpAdapterDraftState(), draft.readSdIpAdapterDraft())
        assertEquals("old prompt", draft.getString("prompt"))
    }

    @Test
    fun `enabled ip adapter draft round trips`() {
        val draft = JSONObject().put("prompt", "portrait")
        val state = SdIpAdapterDraftState(
            enabled = true,
            adapterPath = "/models/ip adapter.safetensors",
            clipVisionPath = "/models/clip vision.safetensors",
            imagePath = "/files/reference image.png",
            strength = 0.75f
        )

        draft.putSdIpAdapterDraft(state)
        val restored = draft.readSdIpAdapterDraft()

        assertTrue(restored.enabled)
        assertEquals(state.adapterPath, restored.adapterPath)
        assertEquals(state.clipVisionPath, restored.clipVisionPath)
        assertEquals(state.imagePath, restored.imagePath)
        assertEquals(0.75f, restored.strength)
        assertEquals("portrait", draft.getString("prompt"))
    }

    @Test
    fun `disabled selections remain available as last used defaults`() {
        val draft = JSONObject()
        val state = SdIpAdapterDraftState(
            enabled = false,
            adapterPath = "/models/adapter.safetensors",
            clipVisionPath = "/models/clip.safetensors",
            imagePath = "/files/reference.png",
            strength = 0.8f
        )

        draft.putSdIpAdapterDraft(state)
        val restored = draft.readSdIpAdapterDraft()

        assertFalse(restored.enabled)
        assertEquals(state.adapterPath, restored.adapterPath)
        assertEquals(state.clipVisionPath, restored.clipVisionPath)
        assertEquals(state.imagePath, restored.imagePath)
        assertEquals(0.8f, restored.strength)
    }

    @Test
    fun `malformed ip adapter object does not destroy surrounding draft`() {
        val draft = JSONObject()
            .put("prompt", "kept")
            .put(
                "ipAdapter",
                JSONObject()
                    .put("enabled", "not-a-boolean")
                    .put("adapter", 42)
                    .put("strength", "bad")
            )

        val restored = draft.readSdIpAdapterDraft()

        assertFalse(restored.enabled)
        assertNull(restored.adapterPath)
        assertEquals(1.0f, restored.strength)
        assertEquals("kept", draft.getString("prompt"))
    }

    @Test
    fun `fully default disabled state is omitted`() {
        val draft = JSONObject().put("prompt", "kept")

        draft.putSdIpAdapterDraft(SdIpAdapterDraftState())

        assertFalse(draft.has("ipAdapter"))
        assertEquals("kept", draft.getString("prompt"))
    }
}
