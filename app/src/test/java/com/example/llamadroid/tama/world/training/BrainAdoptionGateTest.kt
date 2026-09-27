package com.example.llamadroid.tama.world.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainAdoptionGateTest {
    @Test
    fun `level one requires the rounded up success threshold`() {
        val current = metrics("untrained", successes = 0)
        val below = metrics("candidate", successes = 19)
        val passing = metrics("candidate", successes = 20)

        assertEquals(
            "guided_gate_success_threshold",
            guidedAdoptionGateFailure(PolicyComparison(current, below), actualAdoptedReference = false)
        )
        assertNull(guidedAdoptionGateFailure(PolicyComparison(current, passing), actualAdoptedReference = false))
    }

    @Test
    fun `safety regression is rejected and adopted references require improvement`() {
        val current = metrics("current", successes = 20, invalidActionRate = 0.1)
        val unsafe = metrics("candidate", successes = 22, invalidActionRate = 0.2)
        assertEquals(
            "guided_gate_invalid_actions",
            guidedAdoptionGateFailure(PolicyComparison(current, unsafe), actualAdoptedReference = true)
        )

        val equal = metrics("candidate", successes = 20, invalidActionRate = 0.1)
        assertEquals(
            "guided_gate_no_improvement",
            guidedAdoptionGateFailure(PolicyComparison(current, equal), actualAdoptedReference = true)
        )
        assertNull(guidedAdoptionGateFailure(PolicyComparison(current, equal), actualAdoptedReference = false))
    }

    private fun metrics(
        version: String,
        successes: Int,
        invalidActionRate: Double = 0.0
    ) = EvaluationMetrics(
        policyVersion = version,
        curriculumId = CurriculumLevel.VISIBLE_TARGET.id,
        seedCount = 24,
        successfulEpisodes = successes,
        needFailures = 0,
        stuckEpisodes = 0,
        meanReturn = successes.toDouble(),
        meanSteps = 10.0,
        meanObjectiveReward = 1.0,
        meanNeedReward = 0.0,
        meanExplorationReward = 0.0,
        meanSocialReward = 0.0,
        meanEfficiencyReward = 0.0,
        meanInvalidPenalty = 0.0,
        meanDangerPenalty = 0.0,
        meanRepetitionPenalty = 0.0,
        meanStuckPenalty = 0.0,
        invalidActions = (invalidActionRate * 24).toInt(),
        invalidActionRate = invalidActionRate
    )
}
