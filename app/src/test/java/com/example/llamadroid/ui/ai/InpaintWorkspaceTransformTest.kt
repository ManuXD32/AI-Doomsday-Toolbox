package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class InpaintWorkspaceTransformTest {
    @Test
    fun `stored transform restores center crop and defaults safely to fit`() {
        assertEquals(
            InpaintCanvasTransform.CENTER_CROP,
            InpaintCanvasTransform.fromStoredValue("CENTER_CROP")
        )
        assertEquals(InpaintCanvasTransform.FIT, InpaintCanvasTransform.fromStoredValue(null))
        assertEquals(InpaintCanvasTransform.FIT, InpaintCanvasTransform.fromStoredValue("unknown"))
    }
}
