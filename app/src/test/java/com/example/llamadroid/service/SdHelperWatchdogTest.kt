package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdHelperWatchdogTest {
    private val helperA = SdHelper("Phone A", "10.0.0.2", 50052)

    @Test
    fun decisionFunctions() {
        assertFalse(SdHelperWatchdog.helperLost(1))
        assertTrue(SdHelperWatchdog.helperLost(2))
        assertEquals(0, SdHelperWatchdog.nextFailures(1, reachable = true))
        assertEquals(2, SdHelperWatchdog.nextFailures(1, reachable = false))
    }

    @Test
    fun twoFailuresReportOnce() {
        val lost = mutableListOf<SdHelper>()
        val dog = SdHelperWatchdog(listOf(helperA), probe = { false }, onLost = { lost += it })
        repeat(5) { dog.tick() }
        assertEquals(listOf(helperA), lost)
        assertTrue(dog.lost)
    }

    @Test
    fun failureThenSuccessIsNotLost() {
        val results = ArrayDeque(listOf(false, true, false, true))
        val lost = mutableListOf<SdHelper>()
        val dog = SdHelperWatchdog(listOf(helperA), probe = { results.removeFirst() }, onLost = { lost += it })
        repeat(4) { dog.tick() }
        assertTrue(lost.isEmpty())
        assertFalse(dog.lost)
    }

    @Test
    fun stopPreventsFurtherProbes() {
        var probes = 0
        val dog = SdHelperWatchdog(listOf(helperA), probe = { probes++; false }, onLost = {})
        dog.stop()
        dog.tick()
        assertEquals(0, probes)
    }

    @Test
    fun parseHelpersNamesKnownWorkers() {
        val known = listOf(SdDistributedWorkerRuntime("10.0.0.2", 50052, "Pixel", 8000, 4))
        val helpers = SdHelperWatchdog.parseHelpers("10.0.0.2:50052, 10.0.0.3:50053,bad", known)
        assertEquals(listOf("Pixel", "10.0.0.3"), helpers.map { it.name })
        assertEquals(50053, helpers[1].port)
    }
}
