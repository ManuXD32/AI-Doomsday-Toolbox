package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintBackgroundRemovalMaskSupportTest {
    @Test
    fun `opaque grayscale background removal exports keep their foreground plane`() {
        val alpha = foregroundAlphaFromArgb(
            intArrayOf(0xff000000.toInt(), 0xff808080.toInt(), 0xffffffff.toInt())
        )

        assertEquals(0, alpha[0].toInt() and 0xff)
        assertEquals(128, alpha[1].toInt() and 0xff)
        assertEquals(255, alpha[2].toInt() and 0xff)
    }

    @Test
    fun `transparent foreground exports use alpha instead of source color`() {
        val alpha = foregroundAlphaFromArgb(intArrayOf(0x1244aa11, 0xee001122.toInt()))

        assertEquals(0x12, alpha[0].toInt() and 0xff)
        assertEquals(0xee, alpha[1].toInt() and 0xff)
    }

    @Test
    fun `subject and background polarity select opposite editable regions`() {
        val plane = ForegroundMaskAlphaPlane(2, 1, byteArrayOf(0, 255.toByte()))

        val subject = foregroundMaskToInpaintRaster(
            plane, 2, 1, InpaintAutoMaskPolarity.AUTO_SUBJECT
        )
        val background = foregroundMaskToInpaintRaster(
            plane, 2, 1, InpaintAutoMaskPolarity.AUTO_BACKGROUND
        )

        assertEquals(0, subject.valueAt(0, 0))
        assertEquals(255, subject.valueAt(1, 0))
        assertEquals(255, background.valueAt(0, 0))
        assertEquals(0, background.valueAt(1, 0))
    }

    @Test
    fun `proportional foreground masks resize with their polarity intact`() {
        val plane = ForegroundMaskAlphaPlane(2, 1, byteArrayOf(0, 255.toByte()))
        val resized = foregroundMaskToInpaintRaster(
            plane, 4, 2, InpaintAutoMaskPolarity.AUTO_BACKGROUND
        )

        assertEquals(255, resized.valueAt(0, 0))
        assertEquals(255, resized.valueAt(1, 1))
        assertEquals(0, resized.valueAt(2, 0))
        assertEquals(0, resized.valueAt(3, 1))
    }

    @Test
    fun `non proportional foreground masks are rejected before resize`() {
        val plane = ForegroundMaskAlphaPlane(2, 1, byteArrayOf(0, 255.toByte()))

        val result = runCatching {
            foregroundMaskToInpaintRaster(
                plane, 2, 2, InpaintAutoMaskPolarity.AUTO_SUBJECT
            )
        }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }
}
