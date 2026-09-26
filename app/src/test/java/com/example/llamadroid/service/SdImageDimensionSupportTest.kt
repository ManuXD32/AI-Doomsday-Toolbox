package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdImageDimensionSupportTest {
    @Test
    fun `Qwen Image 21 dimensions use 32 pixel alignment`() {
        assertEquals(32, requiredSdImageDimensionMultiple("qwen_image", "2.1"))
        assertEquals(32, requiredSdImageDimensionMultiple("QWEN_IMAGE", "qwen-image-2.1"))
        assertEquals(8, requiredSdImageDimensionMultiple("qwen_image_edit", "2511"))
        assertEquals(8, requiredSdImageDimensionMultiple("flux_1", "schnell"))

        assertTrue(isValidSdImageDimensions(512, 768, 32))
        assertFalse(isValidSdImageDimensions(520, 768, 32))
        assertFalse(isValidSdImageDimensions(512, 770, 32))
    }
}
