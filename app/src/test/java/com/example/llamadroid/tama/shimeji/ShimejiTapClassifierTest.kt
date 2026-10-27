package com.example.llamadroid.tama.shimeji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimejiTapClassifierTest {
    private fun tap(c: ShimejiTapClassifier, t: Long): ShimejiTapEvent? {
        c.onDown(10f, 10f, t)
        return c.onUp(10f, 10f, t + 50L)
    }

    @Test
    fun singleTapEmitsAfterWindow() {
        val c = ShimejiTapClassifier(touchSlopPx = 8f)
        assertNull(tap(c, 0L))
        assertEquals(400L, c.pending())
        assertNull(c.flush(399L))
        assertEquals(ShimejiTapEvent.SINGLE, c.flush(400L))
        assertNull(c.pending())
    }

    @Test
    fun doubleTapEmitsAfterWindow() {
        val c = ShimejiTapClassifier(8f)
        assertNull(tap(c, 0L))
        assertNull(tap(c, 200L))
        assertEquals(600L, c.pending())
        assertEquals(ShimejiTapEvent.DOUBLE, c.flush(600L))
    }

    @Test
    fun tripleTapEmitsImmediately() {
        val c = ShimejiTapClassifier(8f)
        assertNull(tap(c, 0L))
        assertNull(tap(c, 150L))
        assertEquals(ShimejiTapEvent.TRIPLE, tap(c, 300L))
        assertNull(c.pending())
        assertNull(c.flush(10_000L))
    }

    @Test
    fun slowTapsAreSeparateGroups() {
        val c = ShimejiTapClassifier(8f)
        assertNull(tap(c, 0L))
        // Next touch arrives after the window: the first group is returned by onDown.
        assertEquals(ShimejiTapEvent.SINGLE, c.onDown(10f, 10f, 1000L))
        assertNull(c.onUp(10f, 10f, 1050L))
        assertEquals(ShimejiTapEvent.SINGLE, c.flush(1400L))
    }

    @Test
    fun movementBeyondSlopIsDragNotTap() {
        val c = ShimejiTapClassifier(8f)
        c.onDown(10f, 10f, 0L)
        assertFalse(c.onMove(14f, 12f, 10L))
        assertTrue(c.onMove(30f, 12f, 20L))
        assertNull(c.onUp(30f, 12f, 30L))
        assertNull(c.pending())
        assertNull(c.flush(10_000L))
    }

    @Test
    fun dragDiscardsPendingTaps() {
        val c = ShimejiTapClassifier(8f)
        assertNull(tap(c, 0L))
        c.onDown(10f, 10f, 100L)
        assertTrue(c.onMove(100f, 10f, 120L))
        assertNull(c.onUp(100f, 10f, 140L))
        assertNull(c.flush(10_000L))
    }

    @Test
    fun noPendingWhileFingerDown() {
        val c = ShimejiTapClassifier(8f)
        assertNull(tap(c, 0L))
        c.onDown(10f, 10f, 300L)
        assertNull(c.pending())
        assertNull(c.flush(900L))
    }
}
