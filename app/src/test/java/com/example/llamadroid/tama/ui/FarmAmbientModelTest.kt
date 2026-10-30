package com.example.llamadroid.tama.ui

import com.example.llamadroid.tama.data.FarmDaylightPhase
import com.example.llamadroid.tama.data.FarmWeatherKind
import com.example.llamadroid.tama.data.PetSpriteState
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmAmbientModelTest {
    @Test
    fun nightLabelReplacesSunnyAndCloudyOnly() {
        val n = FarmDaylightPhase.NIGHT
        assertEquals(FarmWeatherLabel.NIGHT, farmWeatherLabel(FarmWeatherKind.SUNNY, n))
        assertEquals(FarmWeatherLabel.NIGHT, farmWeatherLabel(FarmWeatherKind.CLOUDY, n))
        assertEquals(FarmWeatherLabel.RAIN, farmWeatherLabel(FarmWeatherKind.RAIN, n))
        assertEquals(FarmWeatherLabel.WINDY, farmWeatherLabel(FarmWeatherKind.WINDY, n))
        assertEquals(FarmWeatherLabel.SUNNY, farmWeatherLabel(FarmWeatherKind.SUNNY, FarmDaylightPhase.DAY))
        assertEquals(FarmWeatherLabel.CLOUDY, farmWeatherLabel(FarmWeatherKind.CLOUDY, FarmDaylightPhase.DAWN))
    }

    @Test
    fun tintAlphaIsCapped() {
        assertEquals(FARM_TINT_MAX_ALPHA, farmCapTintAlpha(0xFF101840.toInt()) ushr 24)
        assertEquals(0x20, farmCapTintAlpha(0x20FFFFFF) ushr 24)
        for (minute in 0 until 1440 step 30) {
            val ms = minute * 60_000L
            assertTrue((farmAmbientSnapshot("p", ms, ZoneOffset.UTC).tintArgb ushr 24) <= FARM_TINT_MAX_ALPHA)
        }
    }

    @Test
    fun snapshotIsDeterministic() {
        assertEquals(farmAmbientSnapshot("p", 1_700_000_000_000L, ZoneOffset.UTC), farmAmbientSnapshot("p", 1_700_000_000_000L, ZoneOffset.UTC))
    }

    @Test
    fun petWalksToPlotWorksThenIdles() {
        val brain = FarmPetBrain(1L)
        brain.requestPlot(farmPlotLaneFraction(2)) // column 2 -> 5/6
        var sawWork = false
        repeat(2_000) {
            brain.step(16, napWanted = false, stationary = false)
            if (brain.mode == FarmPetMode.WORK) sawWork = true
        }
        assertTrue(sawWork)
        assertTrue(brain.x in 0f..1f)
    }

    @Test
    fun petNapsWhenTiredAndWakesAndReactsToTap() {
        val brain = FarmPetBrain(2L)
        brain.step(16, napWanted = true, stationary = false)
        assertEquals(FarmPetMode.NAP, brain.mode)
        brain.step(16, napWanted = false, stationary = false)
        assertEquals(FarmPetMode.IDLE, brain.mode)
        brain.tap()
        brain.step(16, napWanted = false, stationary = false)
        assertEquals(FarmPetMode.HAPPY, brain.mode)
        assertEquals(PetSpriteState.HAPPY, farmPetSpriteState(FarmPetMode.HAPPY))
        assertEquals(PetSpriteState.SLEEP, farmPetSpriteState(FarmPetMode.NAP))
    }

    @Test
    fun eggNeverMoves() {
        val brain = FarmPetBrain(3L)
        brain.requestPlot(0.1f)
        repeat(500) { brain.step(16, napWanted = false, stationary = true) }
        assertEquals(0.5f, brain.x, 0f)
        assertEquals(FarmPetMode.IDLE, brain.mode)
    }

    @Test
    fun crittersStayInsideAndAreDeterministic() {
        for (kind in FarmCritterKind.entries) for (i in 0 until 5) for (t in 0..600 step 7) {
            val pose = farmCritterPose(kind, i, 42L, t.toFloat())
            assertEquals(pose, farmCritterPose(kind, i, 42L, t.toFloat()))
            if (kind != FarmCritterKind.BIRD) assertTrue(pose.x in 0f..1f && pose.y in 0f..1f)
            assertTrue(farmCritterFrame(t.toFloat(), 100, pose.phase) in 0..3)
        }
    }

    @Test
    fun laneGeometryAndCounts() {
        // Room below the column: pet stands below it; tight layouts clamp to the layer.
        assertEquals(900f + 64f, farmPetFeetY(1200f, 100f, 900f, 64f), 0.01f)
        assertEquals(1198f, farmPetFeetY(1200f, 5f, 1190f, 64f), 0.01f)
        assertEquals(0, farmCloudShadowCount(0f))
        assertEquals(5, farmCloudShadowCount(1f))
        assertEquals(1f / 6f, farmPlotLaneFraction(9), 0.0001f)
    }
}
