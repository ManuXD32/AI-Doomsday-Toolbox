package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.canStudy
import com.example.llamadroid.tama.data.canWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaGrowthTimingTest {
    @Test
    fun `growth stages use explicit per stage durations`() {
        assertEquals(60_000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.EGG))
        assertEquals(60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.BABY))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.CHILD))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.TEEN))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.ADULT))
        assertNull(GrowthStage.durationUntilNextStageMillis(GrowthStage.SENIOR))
    }

    @Test
    fun `pet mapper preserves lifetime timer stage timer and growth lock pause state`() {
        val pet = TamaPet(
            id = "pet-1",
            name = "Peque",
            birthTimestamp = 1_000L,
            stageProgressStartTime = 9_000L,
            growthLocked = true,
            growthLockStartedAt = 12_000L,
            introspectionLevel = 12.5f,
            stage = GrowthStage.TEEN
        )

        val roundTrip = PetMapper.toDomain(PetMapper.toEntity(pet))

        assertEquals(1_000L, roundTrip.birthTimestamp)
        assertEquals(9_000L, roundTrip.stageProgressStartTime)
        assertEquals(12_000L, roundTrip.growthLockStartedAt)
        assertEquals(12.5f, roundTrip.introspectionLevel, 0.001f)
        assertEquals(GrowthStage.TEEN, roundTrip.stage)
    }

    @Test
    fun `only baby pets are blocked from work`() {
        assertTrue(GrowthStage.EGG.canWork())
        assertFalse(GrowthStage.BABY.canWork())
        assertTrue(GrowthStage.CHILD.canWork())
        assertTrue(GrowthStage.TEEN.canWork())
        assertTrue(GrowthStage.ADULT.canWork())
        assertTrue(GrowthStage.SENIOR.canWork())
    }

    @Test
    fun `only baby pets are blocked from study`() {
        assertTrue(GrowthStage.EGG.canStudy())
        assertFalse(GrowthStage.BABY.canStudy())
        assertTrue(GrowthStage.CHILD.canStudy())
        assertTrue(GrowthStage.TEEN.canStudy())
        assertTrue(GrowthStage.ADULT.canStudy())
        assertTrue(GrowthStage.SENIOR.canStudy())
    }
}
