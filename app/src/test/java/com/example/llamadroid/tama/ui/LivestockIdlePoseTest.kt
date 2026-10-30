package com.example.llamadroid.tama.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivestockIdlePoseTest {

    @Test
    fun breathingStaysSubtle() {
        for (step in 0..48) {
            val pose = livestockIdlePose(t = step * 0.1f, seed = 0, hopStart = -1f, density = 1f)
            assertTrue(pose.scaleY in 0.999f..1.026f)
            assertTrue(pose.scaleX in 0.989f..1.001f)
            assertTrue(kotlin.math.abs(pose.translationY) <= 1.51f)
        }
    }

    @Test
    fun slotsAreDesynced() {
        val a = livestockIdlePose(t = 1f, seed = 0, hopStart = -1f, density = 1f)
        val b = livestockIdlePose(t = 1f, seed = 1, hopStart = -1f, density = 1f)
        assertTrue(a.scaleY != b.scaleY || a.translationY != b.translationY)
    }

    @Test
    fun hopRisesThenSettles() {
        val start = 2f
        val mid = livestockIdlePose(t = start + 0.14f, seed = 0, hopStart = start, density = 1f)
        val rest = livestockIdlePose(t = start + 0.14f, seed = 0, hopStart = -1f, density = 1f)
        assertTrue(mid.translationY < rest.translationY - 5f)
        val after = livestockIdlePose(t = start + 1f, seed = 0, hopStart = start, density = 1f)
        val restAfter = livestockIdlePose(t = start + 1f, seed = 0, hopStart = -1f, density = 1f)
        assertEquals(restAfter.translationY, after.translationY, 0.0001f)
    }
}
