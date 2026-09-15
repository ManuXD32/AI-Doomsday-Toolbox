package com.example.llamadroid.tama.world.training

import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.policy.Observation
import com.example.llamadroid.tama.world.policy.PolicySpec
import com.example.llamadroid.tama.world.policy.RecurrentPolicyInferenceArtifact
import com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy
import java.io.File
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingPipelineTest {
    @Test
    fun curriculumZeroValidatesEpisodesWithoutPpoOrModelUpdates() {
        val trainer = PpoTrainer(
            GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false),
            TrainerConfig(
                seed = 7001L,
                curriculum = CurriculumLevel.LOCOMOTION_VALIDATION,
                environmentCount = 1,
                rolloutStepsPerEnvironment = 1,
                ppoEpochs = 1,
                maxThreads = 1
            )
        )
        try {
            val policyBefore = trainer.candidatePolicyCheckpoint()
            val artifactBefore = trainer.candidateInferenceArtifact()

            val snapshot = trainer.advance(64)

            assertTrue("validation should execute environment steps", snapshot.metrics.environmentSteps >= 64L)
            assertTrue("validation should complete its bounded episode", snapshot.metrics.episodes >= 1L)
            assertEquals(0L, snapshot.metrics.updateCount)
            assertEquals("candidate-0", snapshot.candidatePolicyVersion)
            assertNull(snapshot.lastUpdateMillis)
            assertArrayEquals(policyBefore, trainer.candidatePolicyCheckpoint())
            assertArrayEquals(artifactBefore, trainer.candidateInferenceArtifact())
        } finally {
            trainer.close()
        }
    }

    @Test
    fun resourceProfilesExposeTheirActualSamplerDefaults() {
        assertEquals(TrainerResourceDefaults(1, 2), TrainerProfile.ECO.resourceDefaults())
        assertEquals(TrainerResourceDefaults(2, 4), TrainerProfile.BALANCED.resourceDefaults())
        assertEquals(TrainerResourceDefaults(4, 8), TrainerProfile.FAST.resourceDefaults())
    }

    @Test
    fun holdoutSeedDomainCannotOverlapTrainingDomain() {
        val plan = HoldoutSeedPlan(1234L)
        val training = plan.trainingSeeds(512).toSet()
        val evaluation = plan.evaluationSeeds(512).toSet()
        assertTrue(training.all(plan::isTrainingSeed))
        assertTrue(evaluation.all(plan::isEvaluationSeed))
        assertTrue(training.intersect(evaluation).isEmpty())
    }

    @Test
    fun holdoutEvaluationCanBeStoppedWithoutUpdatingTheCandidate() {
        val trainer = PpoTrainer(
            GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false),
            TrainerConfig(environmentCount = 1, rolloutStepsPerEnvironment = 2)
        )
        try {
            val before = trainer.candidatePolicyCheckpoint()
            var checks = 0
            assertThrows(EvaluationAbortedException::class.java) {
                trainer.evaluateCurrentVsCandidate(HoldoutSeedPlan(0L).evaluationSeeds(4)) {
                    checks++ < 2
                }
            }
            assertTrue("evaluation should poll its cancellation callback", checks >= 2)
            assertArrayEquals(before, trainer.candidatePolicyCheckpoint())
        } finally {
            trainer.close()
        }
    }

    @Test
    fun trainerExportsWeightOnlyCandidateAndActiveArtifacts() {
        val trainer = PpoTrainer(
            GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false),
            TrainerConfig(environmentCount = 1, rolloutStepsPerEnvironment = 2)
        )
        try {
            val candidate = RecurrentPolicyInferenceArtifact.fromByteArray(trainer.candidateInferenceArtifact())
            val active = RecurrentPolicyInferenceArtifact.fromByteArray(trainer.activeInferenceArtifact())
            assertTrue(candidate.serializedSizeBytes < 1_000_000)
            assertTrue(active.serializedSizeBytes < 1_000_000)
            assertEquals(trainer.candidatePolicyVersionName, candidate.policyVersion)
            assertEquals(trainer.activePolicyVersionName, active.policyVersion)
        } finally {
            trainer.close()
        }
    }

    @Test
    fun comparisonReferenceRefreshPreservesCandidateAndTrainingAge() {
        val config = TrainerConfig(
            seed = 301L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 1,
            rolloutStepsPerEnvironment = 2,
            entropyCoefficient = 0f
        )
        val trainer = PpoTrainer(
            GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false),
            config,
            candidatePolicy = RecurrentPpoPolicy(seed = 302L, policyVersion = "candidate"),
            initialCurrentPolicy = RecurrentPpoPolicy(seed = 303L, policyVersion = "old-current")
        )
        try {
            trainer.advance(2)
            val candidateBefore = trainer.candidatePolicyCheckpoint()
            val stepsBefore = trainer.snapshot().metrics.environmentSteps
            trainer.replaceCurrentPolicy(RecurrentPpoPolicy(seed = 304L, policyVersion = "adopted-v2"))

            assertArrayEquals(candidateBefore, trainer.candidatePolicyCheckpoint())
            assertEquals(stepsBefore, trainer.snapshot().metrics.environmentSteps)
            assertEquals("adopted-v2", trainer.activePolicyVersionName)
            assertEquals("adopted-v2", trainer.evaluateCurrent(longArrayOf(-1L)).policyVersion)
            assertEquals(
                "adopted-v2",
                RecurrentPolicyInferenceArtifact.fromByteArray(trainer.activeInferenceArtifact()).policyVersion
            )
        } finally {
            trainer.close()
        }
    }

    @Test
    fun environmentSafetyMasksWallsAndReportsInvalidActions() {
        val environment = GridNavigationEnvironment(0)
        try {
            val observation = environment.reset(55L, CurriculumLevel.VISIBLE_TARGET)
            assertFalse(observation.actionMask[MovementIntent.MOVE_N.wireId])
            val step = environment.step(MovementIntent.MOVE_N)
            assertFalse(step.actionWasValid)
            assertTrue(step.reward.invalidPenalty > 0f)
        } finally {
            environment.close()
        }
    }

    @Test
    fun everyCurriculumLevelProducesTheFullSemanticPreviewContract() {
        CurriculumLevel.entries.forEach { curriculum ->
            val environment = GridNavigationEnvironment(
                environmentId = curriculum.id,
                fixedTargetForVisibleCurriculum = false
            )
            try {
                val observation = environment.reset(900L + curriculum.id, curriculum)
                assertEquals(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS,
                    observation.tileFeatures.size)
                assertEquals(PolicySpec.SCALAR_FEATURES, observation.scalarFeatures.size)
                assertEquals(PolicySpec.ACTION_COUNT, observation.actionMask.size)
                val preview = requireNotNull(environment.preview())
                assertEquals(observation.tileFeatures.toList(), preview.observation?.tileFeatures?.toList())
                assertEquals(observation.scalarFeatures.toList(), preview.observation?.scalarFeatures?.toList())
                assertEquals(curriculum, preview.curriculum)
            } finally {
                environment.close()
            }
        }
    }

    @Test
    fun hiddenCurriculumDoesNotExposeUnknownTilesOrExactTargetDistance() {
        val environment = GridNavigationEnvironment(0, fixedTargetForVisibleCurriculum = false)
        try {
            var selected: Observation? = null
            for (seed in 0L..256L) {
                val observation = environment.reset(seed, CurriculumLevel.HIDDEN_TARGET_SEARCH)
                if (observation.scalarFeatures[10] == 0f) {
                    selected = observation
                    break
                }
            }
            val observation = requireNotNull(selected) { "expected a hidden target outside the sensed window" }
            var unknownTileCount = 0
            for (offset in observation.tileFeatures.indices step PolicySpec.TILE_CHANNELS) {
                if (observation.tileFeatures[offset + 11] == 0f) {
                    unknownTileCount += 1
                    for (channel in 0 until PolicySpec.TILE_CHANNELS) {
                        assertEquals(0f, observation.tileFeatures[offset + channel])
                    }
                }
            }
            assertTrue("hidden observation should contain unknown tiles", unknownTileCount > 0)
        } finally {
            environment.close()
        }
    }

    @Test
    fun actualGridNavigationImprovesAcrossHeldOutTargetsAndObstacles() {
        val config = TrainerConfig(
            seed = 919L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 8,
            rolloutStepsPerEnvironment = 32,
            ppoEpochs = 1,
            gamma = 0.9f,
            gaeLambda = 0.8f,
            entropyCoefficient = 0.03f,
            learningRate = 0.0005f
        )
        val trainer = PpoTrainer(GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false), config)
        try {
            // Keep this as a natural seeded initialization. A hand-authored WAIT bias can make
            // the metric look better while hiding whether PPO learned navigation from scratch.
            val holdoutSeeds = HoldoutSeedPlan(config.seed).evaluationSeeds(64)
            val previews = ArrayList<EnvironmentPreview>(holdoutSeeds.size)
            var blockedMovementSeen = false
            holdoutSeeds.forEachIndexed { index, seed ->
                val environment = GridNavigationEnvironment(index, fixedTargetForVisibleCurriculum = false)
                try {
                    val observation = environment.reset(seed, CurriculumLevel.VISIBLE_TARGET)
                    val preview = requireNotNull(environment.preview())
                    previews += preview
                    val legalMoves = observation.actionMask.take(8).count { it }
                    assertTrue("a legal mask must leave an alternative to the target direction", legalMoves >= 2)
                    blockedMovementSeen = blockedMovementSeen || legalMoves < 8
                } finally {
                    environment.close()
                }
            }
            val targetDirections = previews.map { preview ->
                Pair((preview.goalX - preview.x).coerceIn(-1, 1), (preview.goalY - preview.y).coerceIn(-1, 1))
            }.toSet()
            assertTrue("holdout targets must cover the compass", targetDirections.size >= 4)
            assertTrue("holdout must include obstacle-bearing observations", blockedMovementSeen)

            val before = trainer.evaluateCandidate(holdoutSeeds)
            repeat(115) { trainer.advance(256) }
            val after = trainer.evaluateCandidate(holdoutSeeds)
            println(
                "actualGridNavigation seed=${config.seed} updates=115 holdout=${holdoutSeeds.size} " +
                    "before=${before.successfulEpisodes}/${before.seedCount} " +
                    "after=${after.successfulEpisodes}/${after.seedCount} " +
                    "return=${before.meanReturn}->${after.meanReturn} " +
                    "objective=${before.meanObjectiveReward}->${after.meanObjectiveReward} " +
                    "steps=${before.meanSteps}->${after.meanSteps}"
            )
            assertTrue("actual Grid PPO should add held-out objective successes", after.successfulEpisodes > before.successfulEpisodes)
            assertTrue("actual Grid PPO should clear at least half of held-out targets", after.successfulEpisodes >= holdoutSeeds.size / 2)
            assertTrue("actual Grid PPO should improve held-out objective reward", after.meanObjectiveReward > before.meanObjectiveReward)
            assertTrue("actual Grid PPO should improve held-out return", after.meanReturn > before.meanReturn)
        } finally {
            trainer.close()
        }
    }

    @Test
    fun parallelSamplerUsesMultipleThreadsWithoutChangingSerialPpoOrder() {
        val threadIds = Collections.synchronizedSet(mutableSetOf<Long>())
        val parallel = PpoTrainer(
            ThreadRecordingFactory(threadIds, CyclicBarrier(4)),
            TrainerConfig(
                seed = 1401L,
                environmentCount = 4,
                rolloutStepsPerEnvironment = 4,
                ppoEpochs = 1,
                entropyCoefficient = 0f,
                maxThreads = 4
            )
        )
        val serial = PpoTrainer(
            ThreadRecordingFactory(Collections.synchronizedSet(mutableSetOf<Long>())),
            parallel.config.copy(maxThreads = 1)
        )
        try {
            parallel.advance(4)
            serial.advance(4)
            assertEquals(4, parallel.samplerThreadCount)
            assertTrue("parallel sampler should execute isolated environments concurrently", threadIds.size >= 2)
            assertArrayEquals(serial.candidatePolicyCheckpoint(), parallel.candidatePolicyCheckpoint())
            assertEquals(serial.snapshot().metrics, parallel.snapshot().metrics)
            assertEquals(serial.snapshot().previews, parallel.snapshot().previews)
        } finally {
            parallel.close()
            serial.close()
        }
    }

    @Test
    fun outcomeTelemetryCountsInvalidActionsAndUsesMeasuredRatios() {
        val trainer = PpoTrainer(
            TelemetryFactory(),
            TrainerConfig(environmentCount = 1, rolloutStepsPerEnvironment = 1, entropyCoefficient = 0f)
        )
        try {
            trainer.advance(1)
            val metrics = trainer.snapshot().metrics
            assertEquals(1L, metrics.invalidActions)
            assertEquals(1f, metrics.objectiveSteps, 0f)
            assertEquals(50f, metrics.pathEfficiency * 100f, 0.001f)
            assertEquals(0.5f, metrics.explorationScore, 0.001f)
            assertEquals(1f, metrics.invalidActionRate, 0f)
        } finally {
            trainer.close()
        }
    }

    @Test
    fun checkpointRestoresOptimizerRngAndRecurrentEnvironmentState() {
        val config = TrainerConfig(
            seed = 81L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 2,
            rolloutStepsPerEnvironment = 4,
            ppoEpochs = 1,
            entropyCoefficient = 0f
        )
        val first = PpoTrainer(GridNavigationEnvironmentFactory(), config)
        val second = PpoTrainer(GridNavigationEnvironmentFactory(), config)
        val checkpoint = File.createTempFile("adt-trainer", ".json")
        try {
            first.advance(8)
            first.save(checkpoint)
            second.load(checkpoint)
            assertArrayEquals(first.candidatePolicyCheckpoint(), second.candidatePolicyCheckpoint())
            assertEquals(first.snapshot().metrics.environmentSteps, second.snapshot().metrics.environmentSteps)
            assertEquals(first.snapshot().previews, second.snapshot().previews)

            first.advance(8)
            second.advance(8)
            assertArrayEquals(first.candidatePolicyCheckpoint(), second.candidatePolicyCheckpoint())
            assertEquals(first.snapshot().metrics, second.snapshot().metrics)
        } finally {
            first.close()
            second.close()
            checkpoint.delete()
        }
    }

    @Test
    fun reconfiguredCheckpointKeepsTrainingAgeAndPolicyState() {
        val oldConfig = TrainerConfig(
            seed = 211L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 1,
            rolloutStepsPerEnvironment = 4,
            ppoEpochs = 1,
            entropyCoefficient = 0f
        )
        val first = PpoTrainer(GridNavigationEnvironmentFactory(), oldConfig)
        val checkpoint = File.createTempFile("adt-reconfigure", ".json")
        try {
            first.advance(4)
            first.save(checkpoint)
            val age = first.snapshot().metrics
            val replacement = PpoTrainer(
                GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false),
                oldConfig.copy(
                    curriculum = CurriculumLevel.RESOURCE_GATHERING,
                    environmentCount = 3,
                    profile = TrainerProfile.FAST
                )
            )
            try {
                replacement.loadReconfigured(checkpoint)
                assertEquals(age, replacement.snapshot().metrics)
                assertArrayEquals(first.candidatePolicyCheckpoint(), replacement.candidatePolicyCheckpoint())
                assertEquals(CurriculumLevel.RESOURCE_GATHERING, replacement.snapshot().curriculum)
                replacement.advance(3)
                assertTrue(replacement.snapshot().metrics.environmentSteps > age.environmentSteps)
            } finally {
                replacement.close()
            }
        } finally {
            first.close()
            checkpoint.delete()
        }
    }

    @Test
    fun ppoImprovesARealHeldOutOneStepNavigationTask() {
        val config = TrainerConfig(
            seed = 19L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 2,
            rolloutStepsPerEnvironment = 8,
            ppoEpochs = 2,
            entropyCoefficient = 0f,
            learningRate = 0.05f
        )
        val initialCandidate = com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy(seed = config.seed)
        val initialParameters = initialCandidate.snapshot()
        initialParameters.actorBias.fill(0f)
        initialParameters.actorBias[MovementIntent.WAIT.wireId] = 2f
        initialParameters.actorBias[MovementIntent.MOVE_E.wireId] = 0f
        initialCandidate.restore(initialParameters)
        val trainer = PpoTrainer(OneStepGoalFactory(), config, candidatePolicy = initialCandidate)
        try {
            val seeds = HoldoutSeedPlan(config.seed).evaluationSeeds(32)
            val before = trainer.evaluateCandidate(seeds)
            repeat(8) { trainer.advance(32) }
            val after = trainer.evaluateCandidate(seeds)
            assertTrue("real PPO candidate should improve success on held-out directions", after.successRate > before.successRate)
            assertTrue(after.meanReturn > before.meanReturn)
        } finally {
            trainer.close()
        }
    }

    @Test
    fun ppoImprovesMultiStepNavigationAcrossHeldOutDirections() {
        val config = TrainerConfig(
            seed = 73L,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            environmentCount = 4,
            rolloutStepsPerEnvironment = 12,
            ppoEpochs = 2,
            entropyCoefficient = 0f,
            learningRate = 0.03f
        )
        val initialCandidate = com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy(seed = config.seed)
        val initialParameters = initialCandidate.snapshot()
        initialParameters.actorBias.fill(0f)
        initialParameters.actorBias[MovementIntent.WAIT.wireId] = 2f
        initialCandidate.restore(initialParameters)
        val trainer = PpoTrainer(MultiStepGoalFactory(), config, candidatePolicy = initialCandidate)
        try {
            val seeds = HoldoutSeedPlan(config.seed).evaluationSeeds(32)
            val before = trainer.evaluateCandidate(seeds)
            repeat(10) { trainer.advance(48) }
            val after = trainer.evaluateCandidate(seeds)
            assertTrue("multi-step PPO should improve held-out navigation success", after.successRate > before.successRate)
            assertTrue("multi-step PPO should improve held-out return", after.meanReturn > before.meanReturn)
        } finally {
            trainer.close()
        }
    }

    private class OneStepGoalFactory : TrainingWorldEnvironmentFactory {
        override fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment =
            OneStepGoalEnvironment(environmentId).also { it.reset(seed, curriculum) }
    }

    private class MultiStepGoalFactory : TrainingWorldEnvironmentFactory {
        override fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment =
            MultiStepGoalEnvironment(environmentId).also { it.reset(seed, curriculum) }
    }

    private class ThreadRecordingFactory(
        private val threadIds: MutableSet<Long>,
        private val barrier: CyclicBarrier? = null
    ) : TrainingWorldEnvironmentFactory {
        override fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment =
            ThreadRecordingEnvironment(environmentId, threadIds, barrier)
    }

    private class ThreadRecordingEnvironment(
        override val environmentId: Int,
        private val threadIds: MutableSet<Long>,
        private val barrier: CyclicBarrier?
    ) : TrainingWorldEnvironment {
        private var seed = 0L
        private var step = 0

        override fun reset(seed: Long, curriculum: CurriculumLevel): Observation {
            this.seed = seed
            step = 0
            return observation()
        }

        override fun step(action: MovementIntent): EnvironmentStep {
            barrier?.await(5, TimeUnit.SECONDS)
            threadIds += Thread.currentThread().id
            step += 1
            return EnvironmentStep(
                observation = observation(),
                reward = RewardBreakdown(objectiveReward = if (action == MovementIntent.WAIT) 0.1f else -0.01f),
                truncated = step >= 100,
                actionWasValid = true,
                preview = preview()
            )
        }

        override fun preview(): EnvironmentPreview = EnvironmentPreview(
            environmentId, seed, CurriculumLevel.VISIBLE_TARGET, step, 0, 1, 1, step,
            MovementIntent.WAIT, false, false
        )

        private fun observation(): Observation = Observation(
            FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS),
            FloatArray(PolicySpec.SCALAR_FEATURES),
            BooleanArray(PolicySpec.ACTION_COUNT) { true }
        )
    }

    private class TelemetryFactory : TrainingWorldEnvironmentFactory {
        override fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment =
            TelemetryEnvironment(environmentId)
    }

    private class TelemetryEnvironment(override val environmentId: Int) : TrainingWorldEnvironment {
        private var seed = 0L
        private var done = false

        override fun reset(seed: Long, curriculum: CurriculumLevel): Observation {
            this.seed = seed
            done = false
            return observation()
        }

        override fun step(action: MovementIntent): EnvironmentStep {
            done = true
            return EnvironmentStep(
                observation = observation(),
                reward = RewardBreakdown(objectiveReward = 1f),
                terminated = true,
                actionWasValid = false,
                success = true,
                outcome = EnvironmentOutcome(
                    objectiveSteps = 1,
                    actualMovementSteps = 2,
                    shortestFeasibleSteps = 1,
                    discoveredTraversableTiles = 2,
                    totalTraversableTiles = 4
                ),
                preview = preview()
            )
        }

        override fun preview(): EnvironmentPreview = EnvironmentPreview(
            environmentId, seed, CurriculumLevel.VISIBLE_TARGET, 0, 0, 1, 0, if (done) 1 else 0,
            MovementIntent.WAIT, done, done
        )

        private fun observation(): Observation = Observation(
            FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS),
            FloatArray(PolicySpec.SCALAR_FEATURES),
            BooleanArray(PolicySpec.ACTION_COUNT) { true }
        )
    }

    private class OneStepGoalEnvironment(override val environmentId: Int) : TrainingWorldEnvironment {
        private var seed = 0L
        private var step = 0
        private var done = false
        private var won = false

        override fun reset(seed: Long, curriculum: CurriculumLevel): Observation {
            this.seed = seed
            step = 0
            done = false
            won = false
            return observation()
        }

        override fun step(action: MovementIntent): EnvironmentStep {
            if (done) return EnvironmentStep(observation(), RewardBreakdown(), terminated = true, success = won)
            step += 1
            won = action == targetAction()
            done = won || step >= 2
            return EnvironmentStep(
                observation = observation(),
                reward = RewardBreakdown(objectiveReward = if (won) 10f else -0.05f),
                terminated = won,
                truncated = !won && done,
                actionWasValid = action == targetAction() || action == MovementIntent.WAIT,
                success = won,
                preview = preview()
            )
        }

        override fun preview(): EnvironmentPreview = EnvironmentPreview(
            environmentId, seed, CurriculumLevel.VISIBLE_TARGET, 1 + step, 1,
            if (targetAction() == MovementIntent.MOVE_E) 2 else 0, 1, step,
            if (won) targetAction() else MovementIntent.WAIT, done, won
        )

        private fun observation(): Observation {
            val tiles = FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS)
            val centre = (PolicySpec.OBSERVATION_RADIUS * PolicySpec.OBSERVATION_SIDE + PolicySpec.OBSERVATION_RADIUS) * PolicySpec.TILE_CHANNELS
            val goal = centre + if (targetAction() == MovementIntent.MOVE_E) PolicySpec.TILE_CHANNELS else -PolicySpec.TILE_CHANNELS
            tiles[centre] = 1f
            tiles[goal + 12] = 1f
            val mask = BooleanArray(PolicySpec.ACTION_COUNT)
            mask[targetAction().wireId] = true
            mask[MovementIntent.WAIT.wireId] = true
            return Observation(tiles, FloatArray(PolicySpec.SCALAR_FEATURES), mask)
        }

        private fun targetAction(): MovementIntent =
            if ((seed and 1L) == 0L) MovementIntent.MOVE_E else MovementIntent.MOVE_W
    }

    /** A three-transition route whose target direction changes with every seed. */
    private class MultiStepGoalEnvironment(override val environmentId: Int) : TrainingWorldEnvironment {
        private var seed = 0L
        private var step = 0
        private var progress = 0
        private var done = false
        private var won = false

        override fun reset(seed: Long, curriculum: CurriculumLevel): Observation {
            this.seed = seed
            step = 0
            progress = 0
            done = false
            won = false
            return observation()
        }

        override fun step(action: MovementIntent): EnvironmentStep {
            if (done) return EnvironmentStep(observation(), RewardBreakdown(), terminated = true, success = won)
            step += 1
            val target = targetAction()
            if (action == target) progress += 1 else if (action != MovementIntent.WAIT) progress = 0
            won = progress >= ROUTE_LENGTH
            done = won || step >= MAX_STEPS
            return EnvironmentStep(
                observation = observation(),
                reward = RewardBreakdown(
                    objectiveReward = if (won) 8f else if (action == target) 0.8f else -0.1f
                ),
                terminated = won,
                truncated = !won && done,
                actionWasValid = action == target || action == MovementIntent.WAIT,
                success = won,
                preview = preview()
            )
        }

        override fun preview(): EnvironmentPreview = EnvironmentPreview(
            environmentId = environmentId,
            seed = seed,
            curriculum = CurriculumLevel.VISIBLE_TARGET,
            x = progress,
            y = 0,
            goalX = ROUTE_LENGTH,
            goalY = 0,
            episodeStep = step,
            lastAction = if (won) targetAction() else MovementIntent.WAIT,
            terminated = done,
            success = won
        )

        private fun observation(): Observation {
            val tiles = FloatArray(
                PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS
            )
            val centre = (PolicySpec.OBSERVATION_RADIUS * PolicySpec.OBSERVATION_SIDE +
                PolicySpec.OBSERVATION_RADIUS) * PolicySpec.TILE_CHANNELS
            tiles[centre] = 1f
            val (dx, dy) = when (targetAction()) {
                MovementIntent.MOVE_N -> 0 to -1
                MovementIntent.MOVE_S -> 0 to 1
                MovementIntent.MOVE_E -> 1 to 0
                else -> -1 to 0
            }
            val goalOffset = centre + (dy * PolicySpec.OBSERVATION_SIDE + dx) * PolicySpec.TILE_CHANNELS
            tiles[goalOffset + 12] = 1f
            val mask = BooleanArray(PolicySpec.ACTION_COUNT)
            mask[targetAction().wireId] = true
            mask[MovementIntent.WAIT.wireId] = true
            return Observation(tiles, FloatArray(PolicySpec.SCALAR_FEATURES), mask)
        }

        private fun targetAction(): MovementIntent = when (seed and 3L) {
            0L -> MovementIntent.MOVE_N
            1L -> MovementIntent.MOVE_S
            2L -> MovementIntent.MOVE_E
            else -> MovementIntent.MOVE_W
        }

        companion object {
            private const val ROUTE_LENGTH = 3
            private const val MAX_STEPS = 8
        }
    }
}
