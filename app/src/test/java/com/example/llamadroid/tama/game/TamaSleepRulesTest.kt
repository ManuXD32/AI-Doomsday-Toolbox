package com.example.llamadroid.tama.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaSleepRulesTest {
    private val min = 60_000L

    @Test
    fun restoresHalfEnergyPointPerMinute() {
        val s = TamaSleepRules.step(0L, 0L, 10 * min, energy = 20f, isBaby = false)
        assertEquals(5f, s.energyGain, 0.001f)
        assertFalse(s.autoWake)
        assertEquals(10 * min, s.endMs)
    }

    @Test
    fun babyRestoresFaster() {
        val s = TamaSleepRules.step(0L, 0L, 10 * min, energy = 20f, isBaby = true)
        assertEquals(10f, s.energyGain, 0.001f)
    }

    @Test
    fun catchUpIsClampedToSleepStart() {
        val s = TamaSleepRules.step(100 * min, 0L, 110 * min, energy = 0f, isBaby = false)
        assertEquals(5f, s.energyGain, 0.001f)
        assertEquals(10f, s.minutes, 0.001f)
    }

    @Test
    fun autoWakesWhenFullAfterMinimumSleep() {
        val s = TamaSleepRules.step(0L, 0L, 400 * min, energy = 0f, isBaby = false)
        assertTrue(s.autoWake)
        assertEquals(200 * min, s.endMs)
        assertEquals(100f, s.energyGain, 0.001f)
    }

    @Test
    fun doesNotWakeBeforeTwentyMinutesEvenIfFull() {
        val early = TamaSleepRules.step(0L, 0L, 10 * min, energy = 100f, isBaby = false)
        assertFalse(early.autoWake)
        assertEquals(0f, early.energyGain, 0.001f)
        val later = TamaSleepRules.step(0L, 10 * min, 30 * min, energy = 100f, isBaby = false)
        assertTrue(later.autoWake)
        assertEquals(20 * min, later.endMs)
    }

    @Test
    fun maxSleepForcesWake() {
        val s = TamaSleepRules.step(0L, 0L, 20 * 60 * min, energy = 0f, isBaby = false)
        assertTrue(s.autoWake)
        assertTrue(s.endMs <= 12 * 60 * min)
        val low = TamaSleepRules.step(0L, 0L, 13 * 60 * min, energy = -1000f, isBaby = false)
        assertTrue(low.autoWake)
        assertEquals(12 * 60 * min, low.endMs)
    }

    @Test
    fun driftIsQuarterOfAwakeDecay() {
        assertEquals(1.25f, TamaSleepRules.drift(60f, 5f), 0.001f)
        assertEquals(0f, TamaSleepRules.drift(0f, 5f), 0.001f)
    }
}
