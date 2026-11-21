package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyEvent.BenchDevice
import com.example.llamadroid.branchy.BranchyEvent.BenchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyHelperBenchTest {
    private val points = listOf(BenchPoint(2, 10.0), BenchPoint(4, 19.0), BenchPoint(6, 20.0))

    @Test
    fun curveUsesSummaryAndNameOverride() {
        val c = BranchyHelperBench.curve(BenchDevice("h1", "raw", 4, 19.5, points, null), "Pixel")
        assertEquals("Pixel", c.name)
        assertEquals(4, c.best)
        assertEquals(19.5, c.gflops, 0.0)
        assertTrue(c.applied)
    }

    @Test
    fun curveFallsBackToPointsAndId() {
        val c = BranchyHelperBench.curve(BenchDevice("h1", null, 0, 20.0, points, null), null)
        assertEquals("h1", c.name)
        assertEquals(4, c.best)
    }

    @Test
    fun failedSweepIsNotAppliedAndKeepsError() {
        val c = BranchyHelperBench.curve(BenchDevice("h1", null, 0, 0.0, emptyList(), "unauthorized"), null)
        assertFalse(c.applied)
        assertEquals("unauthorized", c.error)
        assertEquals(emptyMap<String, Int>(), BranchyBenchMath.withHelperThreads(emptyMap(), c))
    }

    @Test
    fun curveForReturnsLatestPerHelper() {
        val a = BenchCurve("a", "A", points, 4, 19.0)
        val a2 = BenchCurve("a", "A", points, 6, 20.0)
        val st = BranchyBenchState(devices = listOf(a, BenchCurve("b", "B", points, 2, 10.0), a2))
        assertEquals(6, st.curveFor("a")?.best)
        assertNull(st.curveFor("zzz"))
    }
}
