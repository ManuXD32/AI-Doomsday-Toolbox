package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.training.BrainMetricSample
import com.example.llamadroid.tama.world.training.BrainRuntimeState
import com.example.llamadroid.tama.world.training.ComponentTotals
import com.example.llamadroid.tama.world.training.CurriculumLevel
import com.example.llamadroid.tama.world.training.EvaluationMetrics
import com.example.llamadroid.tama.world.training.LivingPolicySummary
import com.example.llamadroid.tama.world.training.BrainRuntimeCheckpoint
import com.example.llamadroid.tama.world.training.PolicyComparison
import com.example.llamadroid.tama.world.training.TrainerSnapshot
import com.example.llamadroid.tama.world.training.TrainingMetrics
import com.example.llamadroid.tama.world.ui.WorldAutonomyLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainUiProjectionTest {
    @Test fun rapidPermissionChangesMergeAgainstTheirActualRenderedBase() {
        val rendered = AutonomyPolicy()
        val firstClick = rendered.copy(allowPurchases = true)
        val secondClick = rendered.copy(allowSellingItems = true)
        val afterFirst = rendered.mergeAutonomyChanges(rendered, firstClick)
        val afterSecond = afterFirst.mergeAutonomyChanges(rendered, secondClick)
        assertTrue(afterSecond.allowPurchases)
        assertTrue(afterSecond.allowSellingItems)
        assertFalse(afterSecond.allowDungeonEntry)
    }

    @Test fun objectiveAndMapMeasurementsDoNotUseRewardOrInvalidActionProxies() {
        val metrics = TrainingMetrics(
            episodes = 4, successfulEpisodes = 2, environmentSteps = 100,
            invalidActions = 10, totalEpisodeSteps = 100, objectiveEpisodes = 2,
            totalObjectiveSteps = 12, pathEfficiencyTotal = 0.9, pathEfficiencySamples = 2,
            explorationTotal = 1.2, explorationSamples = 4,
            components = ComponentTotals(exploration = 500.0)
        )
        val snapshot = TrainerSnapshot(true, CurriculumLevel.LOCOMOTION_VALIDATION,
            metrics, "candidate", "current", emptyList())
        val projected = projectBrainRuntimeState(BrainRuntimeState(snapshot = snapshot)).metrics
        assertEquals(6f, projected.objectiveSteps, 0.001f)
        assertEquals(45f, projected.pathEfficiencyPercent, 0.001f)
        assertEquals(30f, projected.explorationScorePercent, 0.001f)
    }

    @Test fun chartHistoryIsBoundedAndHoldoutSamplesStayDistinct() {
        val runtime = BrainRuntimeState(
            history = (0L..249L).map { BrainMetricSample(it, reward = it.toFloat(), success = 0.5f, loss = 0.2f) },
            evaluationHistory = listOf(BrainMetricSample(240L, reward = 9f, success = 0.8f, loss = 0f)),
            chargingOnly = false, minimumBatteryPercent = 35, maxThreads = 3, environmentCount = 6
        )
        val projected = projectBrainRuntimeState(runtime)
        assertEquals(8, projected.charts.size)
        assertTrue(projected.charts.all { it.points.size == 120 && it.points.first().step == 130L })
        assertEquals(9f, projected.charts.first { it.id == "reward" }.evaluationPoints.single().value, 0.001f)
        assertTrue(projected.charts.first { it.id == "loss" }.evaluationPoints.isEmpty())
        assertEquals(35, projected.resourceSettings.minimumBatteryPercent)
        assertEquals(3, projected.resourceSettings.maxThreads)
        assertEquals(6, projected.resourceSettings.environmentCount)
        assertEquals(false, projected.resourceSettings.chargingOnly)
    }

    @Test fun policyVersionAndCheckpointIdentityAreProjectedSeparately() {
        val checkpoint = BrainRuntimeCheckpoint(
            id = "cp-1",
            createdAt = 10L,
            episodes = 12L,
            curriculumId = CurriculumLevel.LOCOMOTION_VALIDATION.id,
            modelHash = "checkpoint-sha",
            policyVersion = "candidate-v7",
            rewardConfiguration = "decomposed-v1;catalog-v1;curriculum=0;guard.socialCooldownSteps=12",
            objectiveSteps = 4.5f,
            pathEfficiency = 0.5f,
            explorationScore = 0.25f
        )
        val snapshot = TrainerSnapshot(
            paused = true,
            curriculum = CurriculumLevel.LOCOMOTION_VALIDATION,
            metrics = TrainingMetrics(),
            candidatePolicyVersion = "candidate-v7",
            activePolicyVersion = "active-v2",
            previews = emptyList()
        )
        val runtime = BrainRuntimeState(
            snapshot = snapshot,
            checkpoints = listOf(checkpoint),
            activePolicyId = "policy-v2",
            activePolicyVersion = 2,
            adoptedTrainingCheckpointId = checkpoint.id,
            adoptedPolicies = listOf(
                LivingPolicySummary(
                    id = "policy-v2",
                    version = 2,
                    parentId = null,
                    modelHash = "living-sha",
                    createdAt = 11L,
                    active = true
                )
            ),
            hasAdoptedComparisonPolicy = true
        )

        val projected = projectBrainRuntimeState(runtime)

        assertEquals(2, projected.currentBrainVersion)
        assertTrue(projected.adopted)
        assertEquals(2, projected.adoptedPolicies.single().version)
        assertTrue(projected.checkpoints.single().isCurrent)
        assertTrue(projected.checkpoints.single().isCandidate)
        assertEquals(
            "decomposed-v1;catalog-v1;curriculum=0;guard.socialCooldownSteps=12",
            projected.checkpoints.single().rewardConfiguration
        )
        assertTrue(projected.checkpoints.single().metricSummary.contains("objective=4.5"))
    }

    @Test fun legacyCheckpointWithoutRewardMetadataRemainsExplicitlyUnavailable() {
        val checkpoint = BrainRuntimeCheckpoint(
            id = "legacy",
            createdAt = 10L,
            episodes = 2L,
            curriculumId = CurriculumLevel.LOCOMOTION_VALIDATION.id,
            modelHash = "legacy-sha"
        )

        val projected = projectBrainRuntimeState(
            BrainRuntimeState(checkpoints = listOf(checkpoint)),
            labels = BrainUiLabels(unavailable = "Unavailable")
        )

        assertEquals("Unavailable", projected.checkpoints.single().rewardConfiguration)
    }

    @Test fun checkpointRewardDescriptorUsesThePresentationLabelHook() {
        val checkpoint = BrainRuntimeCheckpoint(
            id = "descriptor-checkpoint",
            createdAt = 10L,
            episodes = 2L,
            curriculumId = CurriculumLevel.LOCOMOTION_VALIDATION.id,
            modelHash = "sha",
            rewardConfiguration = "internal-reward-schema"
        )

        val projected = projectBrainRuntimeState(
            BrainRuntimeState(checkpoints = listOf(checkpoint)),
            labels = BrainUiLabels(checkpointRewardConfiguration = { "localized:$it" })
        )

        assertEquals("localized:internal-reward-schema", projected.checkpoints.single().rewardConfiguration)
    }

    @Test fun evaluationUsesObjectiveStepsAndRealHoldoutMeasurements() {
        val current = evaluationMetrics("untrained-model", meanSteps = 99.0, objectiveSteps = 3.0,
            pathEfficiency = 0.50, explorationScore = 0.25, invalidActionRate = 0.25)
        val candidate = evaluationMetrics("candidate-v7", meanSteps = 88.0, objectiveSteps = 4.0,
            pathEfficiency = 0.75, explorationScore = 0.50, invalidActionRate = 0.0)
        val projected = projectBrainRuntimeState(
            BrainRuntimeState(
                comparison = PolicyComparison(current, candidate),
                evaluatedCheckpointId = "checkpoint-42"
            )
        ).evaluation ?: error("evaluation_missing")

        assertEquals("checkpoint-42", projected.candidateCheckpointId)
        assertEquals(3f, projected.currentObjectiveSteps, 0.001f)
        assertEquals(4f, projected.candidateObjectiveSteps, 0.001f)
        assertEquals(50f, projected.currentPathEfficiencyPercent, 0.001f)
        assertEquals(75f, projected.candidatePathEfficiencyPercent, 0.001f)
        assertEquals(25f, projected.currentExplorationPercent, 0.001f)
        assertEquals(50f, projected.candidateExplorationPercent, 0.001f)
        assertEquals(25f, projected.currentInvalidActionPercent, 0.001f)
        assertEquals(0f, projected.candidateInvalidActionPercent, 0.001f)
    }

    @Test fun canonicalAutonomyWinsOverStaleBrainRuntimeDefaults() {
        val canonical = AutonomyPolicy(
            level = AutonomyLevel.FULL,
            allowPurchases = true,
            maximumAutonomousPurchase = 17,
            allowWork = true
        )
        val projected = projectBrainRuntimeState(
            BrainRuntimeState(autonomy = AutonomyPolicy()),
            autonomyPolicy = canonical
        )

        assertEquals(WorldAutonomyLevel.FULL, projected.safeAutonomy.level)
        assertTrue(projected.safeAutonomy.allowPurchases)
        assertEquals(17, projected.safeAutonomy.maximumAutonomousPurchase)
        assertTrue(projected.safeAutonomy.allowWork)
        assertFalse(projected.adopted)
        assertTrue(projected.activePolicyIsBaseline)
    }

    private fun evaluationMetrics(
        policyVersion: String,
        meanSteps: Double,
        objectiveSteps: Double,
        pathEfficiency: Double,
        explorationScore: Double,
        invalidActionRate: Double
    ) = EvaluationMetrics(
        policyVersion = policyVersion,
        curriculumId = CurriculumLevel.LOCOMOTION_VALIDATION.id,
        seedCount = 4,
        successfulEpisodes = 2,
        needFailures = 0,
        stuckEpisodes = 0,
        meanReturn = 1.0,
        meanSteps = meanSteps,
        meanObjectiveReward = 0.0,
        meanNeedReward = 0.0,
        meanExplorationReward = 0.0,
        meanSocialReward = 0.0,
        meanEfficiencyReward = 0.0,
        meanInvalidPenalty = 0.0,
        meanDangerPenalty = 0.0,
        meanRepetitionPenalty = 0.0,
        meanStuckPenalty = 0.0,
        meanObjectiveSteps = objectiveSteps,
        pathEfficiency = pathEfficiency,
        explorationScore = explorationScore,
        invalidActions = (invalidActionRate * 4).toInt(),
        invalidActionRate = invalidActionRate
    )
}
