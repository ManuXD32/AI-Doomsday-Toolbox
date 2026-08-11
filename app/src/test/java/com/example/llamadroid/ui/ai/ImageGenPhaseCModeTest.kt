package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenPhaseCModeTest {
    @Test fun `phase c routes preserve existing mode ids`() {
        assertEquals(0, IMAGE_GEN_MODE_TXT2IMG)
        assertEquals(1, IMAGE_GEN_MODE_IMG2IMG)
        assertEquals(2, IMAGE_GEN_MODE_UPSCALE)
        assertEquals(3, IMAGE_GEN_MODE_INPAINT)
        assertEquals(4, IMAGE_GEN_MODE_ADETAILER)
    }

    @Test fun `inpaint and both ADetailer workflows become ready from their actual requirements`() {
        val base = ImageGenReadinessInput(
            operation = ImageGenOperation.INPAINT,
            hasReadableModel = true,
            hasPrompt = true,
            hasReadableSourceImage = true,
            hasReadableMask = true,
            hasReadableDetector = false,
            supportsTxt2Img = true,
            supportsImg2Img = true,
            hasRequiredComponents = true
        )
        assertTrue(resolveImageGenReadiness(base).isReady)
        assertFalse(resolveImageGenReadiness(base.copy(hasReadableMask = false)).isReady)

        val existing = base.copy(
            operation = ImageGenOperation.ADETAILER_EXISTING,
            hasReadableMask = false,
            hasReadableDetector = true
        )
        assertTrue(resolveImageGenReadiness(existing).isReady)
        assertFalse(resolveImageGenReadiness(existing.copy(hasReadableSourceImage = false)).isReady)

        val generated = existing.copy(
            operation = ImageGenOperation.ADETAILER_GENERATED,
            hasReadableSourceImage = false,
            supportsImg2Img = false,
            supportsTxt2Img = true
        )
        assertTrue(resolveImageGenReadiness(generated).isReady)
    }

    @Test fun `ADetailer operation preserves selected input intent`() {
        assertEquals(
            ImageGenOperation.ADETAILER_EXISTING,
            resolveImageGenOperation(IMAGE_GEN_MODE_ADETAILER, ADetailerInputMode.EXISTING_IMAGE)
        )
        assertEquals(
            ImageGenOperation.ADETAILER_GENERATED,
            resolveImageGenOperation(IMAGE_GEN_MODE_ADETAILER, ADetailerInputMode.GENERATED_IMAGE)
        )
    }
}
