package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchyGuardsTest {
    private val guards = TrainingGuards(pauseBelowBatteryPct = 20, pauseWhenUnplugged = false, maxBatteryTempC = 42)
    private fun snap(level: Int = 80, plugged: Boolean = false, temp: Double = 30.0) = BatterySnapshot(level, plugged, temp)

    private fun decide(s: BatterySnapshot, running: Boolean, paused: Boolean = false, auto: Boolean = true, g: TrainingGuards = guards) =
        BranchyGuards.decide(g, s, running, paused, auto)

    @Test
    fun healthyRunKeepsGoing() {
        assertEquals(GuardAction.None, decide(snap(), running = true))
    }

    @Test
    fun lowBatteryPausesOnlyWhenUnplugged() {
        assertEquals(GuardAction.Pause(BranchyPauseReason.BATTERY_LOW), decide(snap(level = 19), running = true))
        assertEquals(GuardAction.None, decide(snap(level = 19, plugged = true), running = true))
        assertEquals(GuardAction.None, decide(snap(level = 20), running = true))
    }

    @Test
    fun heatPausesEvenWhenCharging() {
        assertEquals(GuardAction.Pause(BranchyPauseReason.BATTERY_HOT), decide(snap(plugged = true, temp = 42.5), running = true))
        assertEquals(GuardAction.None, decide(snap(temp = 42.0), running = true))
    }

    @Test
    fun unpluggedGuardIsOptIn() {
        assertEquals(GuardAction.None, decide(snap(), running = true))
        val strict = guards.copy(pauseWhenUnplugged = true)
        assertEquals(GuardAction.Pause(BranchyPauseReason.UNPLUGGED), decide(snap(), running = true, g = strict))
        assertEquals(GuardAction.None, decide(snap(plugged = true), running = true, g = strict))
    }

    @Test
    fun heatWinsOverOtherReasons() {
        val strict = guards.copy(pauseWhenUnplugged = true)
        assertEquals(GuardAction.Pause(BranchyPauseReason.BATTERY_HOT), decide(snap(level = 5, temp = 50.0), running = true, g = strict))
    }

    @Test
    fun resumeNeedsAutoResumeAndHysteresis() {
        // Paused by a guard: wait until the condition clears with margin (5 % battery, 3 C).
        assertEquals(GuardAction.None, decide(snap(level = 22), running = false, paused = true))
        assertEquals(GuardAction.Resume, decide(snap(level = 25), running = false, paused = true))
        assertEquals(GuardAction.Resume, decide(snap(level = 10, plugged = true), running = false, paused = true))
        assertEquals(GuardAction.None, decide(snap(temp = 40.0), running = false, paused = true))
        assertEquals(GuardAction.Resume, decide(snap(temp = 39.0), running = false, paused = true))
        assertEquals(GuardAction.None, decide(snap(level = 90), running = false, paused = true, auto = false))
    }

    @Test
    fun userPausesNeverAutoResume() {
        assertEquals(GuardAction.None, decide(snap(), running = false, paused = false))
    }

    @Test
    fun zeroDisablesAGuard() {
        val off = TrainingGuards(pauseBelowBatteryPct = 0, pauseWhenUnplugged = false, maxBatteryTempC = 0)
        assertEquals(GuardAction.None, decide(snap(level = 1, temp = 80.0), running = true, g = off))
        assertNull(BranchyGuards.violation(off, snap(level = 1, temp = 80.0), recovering = false))
    }
}
