package com.example.llamadroid.tama.shimeji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimejiGeometryTest {
    private val base = ShimejiDisplayGeometry(1080, 2400, 0, 80, 0, 100, 0)

    @Test
    fun identicalGeometryIsNotAChange() {
        assertFalse(ShimejiDisplayGeometry.hasChanged(base, base.copy()))
    }

    @Test
    fun firstReadingCountsAsChange() {
        assertTrue(ShimejiDisplayGeometry.hasChanged(null, base))
    }

    @Test
    fun sizeRotationAndInsetsCount() {
        assertTrue(ShimejiDisplayGeometry.hasChanged(base, base.copy(fullWidth = 2400, fullHeight = 1080)))
        assertTrue(ShimejiDisplayGeometry.hasChanged(base, base.copy(rotation = 2)))
        assertTrue(ShimejiDisplayGeometry.hasChanged(base, base.copy(insetBottom = 0)))
        assertTrue(ShimejiDisplayGeometry.hasChanged(base, base.copy(insetTop = 120)))
    }

    private fun place(top: Int, bottom: Int, cx: Int = 500) =
        ShimejiPanelPlacement.compute(cx, top, bottom, 1000, 2000, 16, 528, 280)

    @Test
    fun petInUpperHalfOpensBelow() {
        val p = place(200, 400)
        assertTrue(p.below)
        assertEquals(416, p.y)
    }

    @Test
    fun petInLowerHalfOpensAbove() {
        val p = place(1500, 1700)
        assertFalse(p.below)
        assertEquals(2000 - 1500 + 16, p.y)
    }

    @Test
    fun placementStaysInsideScreen() {
        val edge = place(-50, 100, cx = -100)
        assertEquals(0, edge.x)
        val right = place(1900, 2100, cx = 5000)
        assertEquals(1000 - right.widthPx, right.x)
        assertTrue(right.y in 0..(2000 - right.capPx))
        val narrow = ShimejiPanelPlacement.compute(100, 100, 200, 300, 600, 16, 528, 280)
        assertEquals(268, narrow.widthPx)
        assertTrue(narrow.x + narrow.widthPx <= 300)
    }
}
