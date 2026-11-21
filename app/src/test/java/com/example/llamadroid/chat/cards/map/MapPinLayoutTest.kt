package com.example.llamadroid.chat.cards.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MapPinLayoutTest {
    private val spain = listOf(GeoPoint(40.4168, -3.7038), GeoPoint(39.8628, -4.0273), GeoPoint(40.9429, -4.1088))

    // Mirrors ChatMapCard.previewViewport: 768x480 preview, insets side 44 / top 44 / bottom 100, max zoom 16.
    private fun previewZoom(points: List<GeoPoint>, side: Int, top: Int, bottom: Int): Int {
        val availH = 480 - top - bottom
        return MapTileMath.fitZoom(GeoBounds.of(points)!!, 768, availH + 2 * side, side, 16)
    }

    @Test
    fun madridToledoSegoviaFitsOneZoomStepHigher() {
        assertEquals(7, previewZoom(spain, 62, 96, 140))
        assertEquals(8, previewZoom(spain, 44, 44, 100))
    }

    private fun pinsAt(vararg xy: Pair<Float, Float>) = xy.map { PinInput(it.first, it.second, 80f, 32f) }

    @Test
    fun spacedPinsKeepPositionsAndLabels() {
        val vp = MapTileMath.viewportFor(GeoBounds.of(spain)!!, 768, 480, 44, 8)
        val pins = spain.map { val s = MapTileMath.project(it, vp); PinInput(s.x.toFloat(), s.y.toFloat(), 80f, 32f) }
        val out = MapPinLayout.layout(pins, 28f, 768f, 480f, 6f, 4f)
        out.forEachIndexed { i, p ->
            assertEquals(pins[i].x, p.x, 0.001f)
            assertEquals(pins[i].y, p.y, 0.001f)
            assertTrue(p.labelVisible)
        }
    }

    @Test
    fun identicalCoordinatesAreOffsetAndOnlyFirstLabelShows() {
        val out = MapPinLayout.layout(pinsAt(300f to 200f, 300f to 200f), 28f, 768f, 480f, 6f, 4f)
        assertTrue(hypot(out[0].x - out[1].x, out[0].y - out[1].y) >= 28f * 1.7f - 0.01f)
        assertTrue(out[0].labelVisible)
        assertTrue(!out[1].labelVisible || out[1].labelY != out[0].labelY || out[1].labelX != out[0].labelX)
    }

    @Test
    fun labelOverlappingAnotherPinIsHidden() {
        // Pin 1 sits directly under pin 0's label.
        val out = MapPinLayout.layout(pinsAt(300f to 100f, 300f to 160f), 28f, 768f, 480f, 6f, 4f)
        assertTrue(!out[0].labelVisible)
        assertTrue(out[1].labelVisible)
    }

    @Test
    fun eightSpreadPlacesNeverOverlapShownLabels() {
        val pts = (0 until 8).map { (60f + it * 85f) to (80f + (it % 3) * 110f) }
        val out = MapPinLayout.layout(pinsAt(*pts.toTypedArray()), 28f, 768f, 480f, 6f, 4f)
        val shown = out.filter { it.labelVisible }
        assertTrue(shown.isNotEmpty())
        for (a in shown.indices) for (b in a + 1 until shown.size) {
            val p = shown[a]; val q = shown[b]
            val disjoint = p.labelX + 80f <= q.labelX || q.labelX + 80f <= p.labelX ||
                p.labelY + 32f <= q.labelY || q.labelY + 32f <= p.labelY
            assertTrue(disjoint)
        }
        // pins themselves stay separated
        for (a in out.indices) for (b in a + 1 until out.size) {
            assertTrue(hypot(out[a].x - out[b].x, out[a].y - out[b].y) >= 28f * 1.7f - 0.01f)
        }
    }
}
