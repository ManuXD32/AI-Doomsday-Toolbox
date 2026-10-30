package com.example.llamadroid.tama.shimeji

import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.TamaStudyPhase
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimejiTimerFormatTest {
    private val labels = ShimejiTimerLabels("Focus", "Short rest", "Long rest", "Study", "Work", "Relax", "Train")
    private val now = 1_000_000L

    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("00:00", ShimejiTimerFormat.clock(-5L))
        assertEquals("00:59", ShimejiTimerFormat.clock(59_999L))
        assertEquals("12:34", ShimejiTimerFormat.clock(754_000L))
        assertEquals("1:02:03", ShimejiTimerFormat.clock(3_723_000L))
    }

    @Test
    fun sleepWinsAndShowsElapsedAndEnergy() {
        val state = ShimejiTimerFormat.timerState(true, now - 90_000L, ActivityType.STUDYING, now, null, 63.9f, now)!!
        assertEquals(ShimejiTimerKind.SLEEP, state.kind)
        assertEquals(63, state.energyPercent)
        assertEquals("Zz 01:30 63%", ShimejiTimerFormat.badgeText(state, now, labels))
    }

    @Test
    fun nothingRunningHasNoTimer() {
        assertNull(ShimejiTimerFormat.timerState(false, null, ActivityType.NONE, null, null, 100f, now))
    }

    @Test
    fun pomodoroCountsDownAndNamesThePhase() {
        val study = ShimejiStudyInfo(true, TamaStudyPhase.FOCUS, now - 60_000L, now + 754_000L)
        val state = ShimejiTimerFormat.timerState(false, null, ActivityType.STUDYING, now, study, 80f, now)!!
        assertEquals(ShimejiTimerKind.POMODORO, state.kind)
        assertEquals("Focus 12:34", ShimejiTimerFormat.badgeText(state, now, labels))
        val rest = state.copy(phase = TamaStudyPhase.LONG_REST)
        assertEquals("Long rest 12:34", ShimejiTimerFormat.badgeText(rest, now, labels))
        // Past the phase end (refresh not yet run) clamps at zero.
        assertEquals("Focus 00:00", ShimejiTimerFormat.badgeText(state, now + 900_000L, labels))
    }

    @Test
    fun normalStudyWithoutSessionFallsBackToActivityStart() {
        val state = ShimejiTimerFormat.timerState(false, null, ActivityType.STUDYING, now - 5_000L, null, 80f, now)!!
        assertEquals(ShimejiTimerKind.STUDY, state.kind)
        assertEquals("Study 00:05", ShimejiTimerFormat.badgeText(state, now, labels))
    }

    @Test
    fun workAndRelaxCountUp() {
        val work = ShimejiTimerFormat.timerState(false, null, ActivityType.WORKING, now - 61_000L, null, 50f, now)!!
        assertEquals("Work 01:01", ShimejiTimerFormat.badgeText(work, now, labels))
        val relax = ShimejiTimerFormat.timerState(false, null, ActivityType.RELAXING, now - 3_600_000L, null, 50f, now)!!
        assertEquals("Relax 1:00:00", ShimejiTimerFormat.badgeText(relax, now, labels))
    }

    @Test
    fun occupiedOnlyWhenAwakeAndBusy() {
        assertTrue(ShimejiTimerFormat.isOccupied(false, ActivityType.WORKING))
        assertFalse(ShimejiTimerFormat.isOccupied(true, ActivityType.WORKING))
        assertFalse(ShimejiTimerFormat.isOccupied(false, ActivityType.NONE))
    }

    @Test
    fun occupiedPetStaysSeatedButCanBeDragged() {
        val bounds = ShimejiBounds(width = 400f, height = 800f, floorInset = 100f, spriteSize = 100f)
        val m = ShimejiMotion(bounds, ShimejiProfile(false, false, ShimejiActivity.PLAYFUL, isOccupied = true), 150f, 600f)
        val rng = Random(3)
        assertEquals(ShimejiMode.SIT, m.mode)
        repeat(4000) {
            m.step(16L, rng)
            assertEquals(ShimejiMode.SIT, m.mode)
            assertEquals(150f, m.x, 0.01f)
        }
        m.startDrag(200f, 300f)
        assertEquals(ShimejiMode.DRAGGED, m.mode)
        m.release(0f, 0f)
        repeat(600) { m.step(16L, rng) }
        assertEquals(ShimejiMode.SIT, m.mode)
    }

    @Test
    fun becomingOccupiedSitsAndFinishingResumesWandering() {
        val bounds = ShimejiBounds(width = 400f, height = 800f, floorInset = 100f, spriteSize = 100f)
        val m = ShimejiMotion(bounds, ShimejiProfile(false, false), 150f, 600f)
        m.forceWalk()
        m.updateProfile(ShimejiProfile(false, false, ShimejiActivity.NORMAL, isOccupied = true))
        assertEquals(ShimejiMode.SIT, m.mode)
        m.updateProfile(ShimejiProfile(false, false))
        assertEquals(ShimejiMode.IDLE, m.mode)
    }
}
