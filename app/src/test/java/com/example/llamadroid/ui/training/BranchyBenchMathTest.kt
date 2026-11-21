package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyEvent.BenchPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchyBenchMathTest {
    @Test
    fun bestIsSmallestCountWithinFivePercentOfMax() {
        val pts = listOf(BenchPoint(2, 20.0), BenchPoint(4, 38.0), BenchPoint(6, 39.0), BenchPoint(8, 40.0))
        assertEquals(4, BranchyBenchMath.best(pts))
    }

    @Test
    fun emptyIsZero() {
        assertEquals(0, BranchyBenchMath.best(emptyList()))
    }

    @Test
    fun resolveUsesSummaryWhenPresent() {
        val pts = listOf(BenchPoint(2, 20.0), BenchPoint(4, 38.0))
        assertEquals(4 to 39.0, BranchyBenchMath.resolve(4, 39.0, pts))
    }

    @Test
    fun resolveFallsBackToPoints() {
        val pts = listOf(BenchPoint(2, 20.0), BenchPoint(4, 38.0), BenchPoint(6, 39.0))
        assertEquals(4 to 38.0, BranchyBenchMath.resolve(0, 0.0, pts))
        assertEquals(0 to 0.0, BranchyBenchMath.resolve(0, 0.0, emptyList()))
    }
}
