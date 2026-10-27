package com.example.llamadroid.tama.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmAnimalWanderTest {
    @Test
    fun positionStaysInRangeAndTurnsAround() {
        val wander = FarmAnimalWander(3)
        var sawRight = false
        var sawLeft = false
        var sawPause = false
        var t = 0f
        while (t < wander.periodSeconds * 2) {
            val s = wander.sampleAt(t)
            assertTrue(s.x in 0f..1f)
            if (s.walking && s.facingRight) sawRight = true
            if (s.walking && !s.facingRight) sawLeft = true
            if (!s.walking) sawPause = true
            t += 0.05f
        }
        assertTrue(sawRight && sawLeft && sawPause)
    }

    @Test
    fun animalsHaveDifferentTiming() {
        assertNotEquals(FarmAnimalWander(1).periodSeconds, FarmAnimalWander(2).periodSeconds)
    }

    @Test
    fun framesCycleThroughFour() {
        val wander = FarmAnimalWander(5)
        assertEquals(0, wander.frameAt(0f, true))
        assertEquals(1, wander.frameAt(0.13f, true))
        assertEquals(0, wander.frameAt(0.5f, true))
        assertFalse((0..100).any { wander.frameAt(it * 0.1f, false) !in 0..3 })
    }
}
