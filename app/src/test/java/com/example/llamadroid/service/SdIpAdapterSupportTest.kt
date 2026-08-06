package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SdIpAdapterSupportTest {

    @Test
    fun `null configuration is a no-op`() {
        assertNull(
            validateSdIpAdapterConfig(
                config = null,
                supportsIpAdapter = true,
                requireReadableFiles = false
            )
        )
    }

    @Test
    fun `valid configuration is normalized`() {
        val result = validateSdIpAdapterConfig(
            config = SdIpAdapterConfig(
                adapterPath = " /models/adapter.safetensors ",
                clipVisionPath = " /models/clip.safetensors ",
                imagePath = " /files/reference.png ",
                strength = 0.75f
            ),
            supportsIpAdapter = true,
            requireReadableFiles = false
        )

        assertEquals("/models/adapter.safetensors", result?.adapterPath)
        assertEquals("/models/clip.safetensors", result?.clipVisionPath)
        assertEquals("/files/reference.png", result?.imagePath)
    }

    @Test
    fun `unsupported family is blocked`() {
        val error = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config(),
                supportsIpAdapter = false,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.UNSUPPORTED_FAMILY, error.issue)
        assertTrue(error is SdConfigurationException)
    }

    @Test
    fun `incompatible adapter and clip vision are distinguishable`() {
        val adapterError = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config(),
                supportsIpAdapter = true,
                adapterCompatible = false,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.INCOMPATIBLE_ADAPTER, adapterError.issue)

        val clipError = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config(),
                supportsIpAdapter = true,
                clipVisionCompatible = false,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.INCOMPATIBLE_CLIP_VISION, clipError.issue)
    }


    @Test
    fun `missing members are reported independently`() {
        val missingAdapter = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config().copy(adapterPath = ""),
                supportsIpAdapter = true,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.MISSING_ADAPTER, missingAdapter.issue)

        val missingClip = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config().copy(clipVisionPath = ""),
                supportsIpAdapter = true,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.MISSING_CLIP_VISION, missingClip.issue)

        val missingImage = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config().copy(imagePath = ""),
                supportsIpAdapter = true,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.MISSING_REFERENCE_IMAGE, missingImage.issue)
    }

    @Test
    fun `unreadable files are blocked before native launch`() {
        val error = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config(),
                supportsIpAdapter = true
            )
        }
        assertEquals(SdIpAdapterIssue.UNREADABLE_ADAPTER, error.issue)
    }

    @Test
    fun `invalid strength is blocked and formatting is locale independent`() {
        val error = assertThrows(SdIpAdapterConfigurationException::class.java) {
            validateSdIpAdapterConfig(
                config = config().copy(strength = Float.NaN),
                supportsIpAdapter = true,
                requireReadableFiles = false
            )
        }
        assertEquals(SdIpAdapterIssue.INVALID_STRENGTH, error.issue)
        assertEquals("0.75", formatSdIpAdapterStrength(0.75f))
        assertEquals("1", formatSdIpAdapterStrength(1f))
    }

    private fun config(): SdIpAdapterConfig = SdIpAdapterConfig(
        adapterPath = "/models/adapter.safetensors",
        clipVisionPath = "/models/clip.safetensors",
        imagePath = "/files/reference.png",
        strength = 0.75f
    )
}
