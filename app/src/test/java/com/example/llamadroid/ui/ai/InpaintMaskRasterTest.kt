package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintMaskRasterTest {
    @Test
    fun `painting and erasing update the grayscale mask`() {
        val mask = InpaintMaskRaster.empty(32, 32)
        mask.paintCircle(16f, 16f, 5f, softness = 0f, erase = false)

        assertTrue(mask.valueAt(16, 16) > 250)
        assertFalse(mask.isEmpty())

        mask.paintCircle(16f, 16f, 3f, softness = 0f, erase = true)
        assertEquals(0, mask.valueAt(16, 16))
    }

    @Test
    fun `soft brush produces a feathered edge`() {
        val mask = InpaintMaskRaster.empty(32, 32)
        mask.paintCircle(16f, 16f, 8f, softness = 1f, erase = false)

        assertTrue(mask.valueAt(16, 16) > mask.valueAt(21, 16))
        assertTrue(mask.valueAt(21, 16) in 1..254)
    }

    @Test
    fun `line interpolation leaves no empty gap`() {
        val mask = InpaintMaskRaster.empty(64, 16)
        mask.paintLine(3f, 8f, 60f, 8f, radius = 2f, softness = 0f, erase = false)

        for (x in 3..60) assertTrue("gap at x=$x", mask.valueAt(x, 8) > 0)
    }

    @Test
    fun `invert swaps empty and full`() {
        val mask = InpaintMaskRaster.empty(8, 8)
        assertTrue(mask.isEmpty())
        mask.invert()
        assertTrue(mask.isFull())
        mask.invert()
        assertTrue(mask.isEmpty())
    }

    @Test
    fun `background selection inverts foreground alpha`() {
        val alpha = byteArrayOf(0, 64, 127, 255.toByte())
        val subject = InpaintMaskRaster.fromForegroundAlpha(2, 2, alpha, selectBackground = false)
        val background = InpaintMaskRaster.fromForegroundAlpha(2, 2, alpha, selectBackground = true)

        assertEquals(0, subject.valueAt(0, 0))
        assertEquals(255, subject.valueAt(1, 1))
        assertEquals(255, background.valueAt(0, 0))
        assertEquals(0, background.valueAt(1, 1))
    }

    @Test
    fun `nearest resize keeps mask polarity`() {
        val resized = InpaintMaskRaster.resizeNearest(
            sourceWidth = 2,
            sourceHeight = 1,
            source = byteArrayOf(0, 255.toByte()),
            targetWidth = 4,
            targetHeight = 2
        )

        assertEquals(0, resized.valueAt(0, 0))
        assertEquals(0, resized.valueAt(1, 1))
        assertEquals(255, resized.valueAt(2, 0))
        assertEquals(255, resized.valueAt(3, 1))
    }

    @Test
    fun `aspect compatibility accepts proportional masks only`() {
        assertTrue(InpaintMaskRaster.compatibleAspectRatio(512, 256, 1024, 512))
        assertFalse(InpaintMaskRaster.compatibleAspectRatio(512, 512, 1024, 512))
    }
}
