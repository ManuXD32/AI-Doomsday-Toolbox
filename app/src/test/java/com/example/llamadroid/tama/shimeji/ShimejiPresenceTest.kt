package com.example.llamadroid.tama.shimeji

import org.junit.Assert.assertEquals
import org.junit.Test

class ShimejiPresenceTest {
    private val tenMin = 10 * 60_000L

    @Test
    fun awardsOnePointPerTenMinutesWithCarry() {
        val r1 = ShimejiPresence.accrue(tenMin - 1L, true, 100L, null)
        assertEquals(0, r1.happinessDelta)
        val r2 = ShimejiPresence.accrue(1L + tenMin, true, 100L, r1.newState)
        assertEquals(2, r2.happinessDelta)
        assertEquals(20, r2.newState.minutesCredited)
        assertEquals(0L, r2.newState.carryMs)
    }

    @Test
    fun noCreditWhileScreenOff() {
        val r = ShimejiPresence.accrue(5 * tenMin, false, 100L, null)
        assertEquals(0, r.happinessDelta)
        assertEquals(0, r.newState.minutesCredited)
    }

    @Test
    fun capsAtTwelvePerDay() {
        val r1 = ShimejiPresence.accrue(20 * tenMin, true, 100L, null)
        assertEquals(12, r1.happinessDelta)
        assertEquals(0L, r1.newState.carryMs)
        val r2 = ShimejiPresence.accrue(5 * tenMin, true, 100L, r1.newState)
        assertEquals(0, r2.happinessDelta)
        assertEquals(120, r2.newState.minutesCredited)
    }

    @Test
    fun newDayResetsCredit() {
        val r1 = ShimejiPresence.accrue(20 * tenMin, true, 100L, null)
        val r2 = ShimejiPresence.accrue(3 * tenMin, true, 101L, r1.newState)
        assertEquals(3, r2.happinessDelta)
        assertEquals(101L, r2.newState.dayKey)
        assertEquals(30, r2.newState.minutesCredited)
    }

    @Test
    fun partialCapKeepsRemainder() {
        val stored = ShimejiPresenceState(100L, 100, 0L)
        val r = ShimejiPresence.accrue(5 * tenMin, true, 100L, stored)
        assertEquals(2, r.happinessDelta)
        assertEquals(120, r.newState.minutesCredited)
    }
}
