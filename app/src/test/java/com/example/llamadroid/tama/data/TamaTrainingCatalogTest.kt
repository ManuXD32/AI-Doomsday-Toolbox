package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TamaTrainingCatalogTest {
    @Test
    fun `training tiers mirror work unlocks pay and study gain speed`() {
        assertEquals(TamaWorkCatalog.jobs.size, TamaTrainingCatalog.tiers.size)
        TamaTrainingCatalog.tiers.zip(TamaWorkCatalog.jobs).forEach { (tier, job) ->
            assertEquals(job.requiredEducation, tier.requiredExercise)
            assertEquals(job.hourlyPay, tier.hourlyPay)
        }
        assertEquals(TAMA_STUDY_EDUCATION_PER_HOUR, TAMA_TRAINING_EXERCISE_PER_HOUR)
        assertEquals(10f, TAMA_TRAINING_HAPPINESS_PER_HOUR)
    }
}
