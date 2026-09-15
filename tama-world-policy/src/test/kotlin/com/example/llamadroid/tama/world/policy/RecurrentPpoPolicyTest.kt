package com.example.llamadroid.tama.world.policy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class RecurrentPpoPolicyTest {
    @Test
    fun defaultArchitectureStaysWithinMobileBudget() {
        val policy = RecurrentPpoPolicy(seed = 42L)
        assertEquals(PolicySpec.INPUT_SIZE, policy.architecture.inputSize)
        assertEquals(PolicySpec.RECURRENT_UNITS, policy.architecture.recurrentUnits)
        assertEquals(PolicySpec.ACTION_COUNT, policy.architecture.actionCount)
        assertTrue(policy.parameterCount < 250_000)
    }

    @Test
    fun policyEncoderKeepsLocalGeometryAndTargetCentroidWithoutUsingTail() {
        val observation = Observation.blank()
        val side = PolicySpec.OBSERVATION_SIDE
        val centre = PolicySpec.OBSERVATION_RADIUS
        val targetX = centre + 2
        val targetY = centre - 1
        val targetOffset = (targetY * side + targetX) * PolicySpec.TILE_CHANNELS
        observation.tileFeatures[targetOffset + 12] = 1f
        observation.tileFeatures[(centre * side + centre) * PolicySpec.TILE_CHANNELS] = 1f

        val encoded = observation.encodedForPolicy()
        val summaryStart = PolicySpec.SCALAR_FEATURES + PolicySpec.LOCAL_PATCH_FEATURES +
            PolicySpec.SPATIAL_BIN_FEATURES
        val goalSummary = summaryStart + 12 * 4
        assertEquals(1f / (side * side), encoded[goalSummary], 1e-6f)
        assertEquals(2f / PolicySpec.OBSERVATION_RADIUS, encoded[goalSummary + 1], 1e-6f)
        assertEquals(-1f / PolicySpec.OBSERVATION_RADIUS, encoded[goalSummary + 2], 1e-6f)
        val patchRow = targetY - centre + PolicySpec.LOCAL_PATCH_RADIUS
        val patchColumn = targetX - centre + PolicySpec.LOCAL_PATCH_RADIUS
        val localPatchGoal = PolicySpec.SCALAR_FEATURES +
            (patchRow * PolicySpec.LOCAL_PATCH_SIDE + patchColumn) * PolicySpec.TILE_CHANNELS + 12
        assertTrue("local patch should retain the visible goal", encoded[localPatchGoal] > 0f)
        for (index in PolicySpec.ENCODED_INPUT_SIZE until PolicySpec.INPUT_SIZE) {
            assertEquals("encoded tail must be zero", 0f, encoded[index], 0f)
        }
    }

    @Test
    fun maskedActionsAreNeverSampled() {
        val policy = RecurrentPpoPolicy(seed = 7L)
        val observation = Observation(
            tileFeatures = FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS),
            scalarFeatures = FloatArray(PolicySpec.SCALAR_FEATURES),
            actionMask = BooleanArray(PolicySpec.ACTION_COUNT) { it == MovementIntent.WAIT.wireId }
        )
        val rng = DeterministicRng(9L)
        repeat(100) {
            assertEquals(MovementIntent.WAIT.wireId, policy.infer(observation, deterministic = false, rng = rng).actionIndex)
        }
    }

    @Test
    fun recurrentStateChangesTheForwardPass() {
        val policy = RecurrentPpoPolicy(seed = 11L)
        val observation = Observation.blank()
        val state = RecurrentState(FloatArray(PolicySpec.RECURRENT_UNITS) { 0.5f })
        val zero = policy.forward(observation, RecurrentState.zero())
        val nonZero = policy.forward(observation, state)
        assertTrue(zero.logits.indices.any { kotlin.math.abs(zero.logits[it] - nonZero.logits[it]) > 1e-7f })
    }

    @Test
    fun analyticPpoGradientMatchesFiniteDifference() {
        val policy = RecurrentPpoPolicy(seed = 123L)
        val observation = Observation.blank().also {
            it.tileFeatures[PolicySpec.TILE_CHANNELS * (PolicySpec.OBSERVATION_SIDE + 1) + 0] = 1f
            it.scalarFeatures[0] = 0.75f
        }
        val forward = policy.forward(observation)
        val actionIndex = MovementIntent.MOVE_E.wireId
        val oldLogProbability = ActionDistribution.logProbability(forward.logits, actionIndex, observation.actionMask)
        val sample = PpoTrainingSample(
            observation = observation,
            actionIndex = actionIndex,
            oldLogProbability = oldLogProbability,
            oldValue = forward.value,
            advantage = 1.3f,
            returnValue = 0.2f
        )
        val config = PpoUpdateConfig(entropyCoefficient = 0f, valueCoefficient = 1f)
        val gradient = policy.gradientForSingleStep(sample, config = config)
        val snapshot = policy.snapshot()
        val parameterIndex = policy.architecture.inputSize * policy.architecture.recurrentUnits +
            policy.architecture.recurrentUnits * policy.architecture.recurrentUnits +
            policy.architecture.recurrentUnits + policy.architecture.actionCount * policy.architecture.recurrentUnits
        val epsilon = 1e-3f

        val plus = policy.snapshot().also { it.actorBias[0] += epsilon }
        policy.restore(plus)
        val plusLoss = policy.lossForSample(sample, clipEpsilon = config.clipEpsilon)
        val minus = snapshot.copy(actorBias = snapshot.actorBias.copyOf().also { it[0] -= epsilon })
        policy.restore(minus)
        val minusLoss = policy.lossForSample(sample, clipEpsilon = config.clipEpsilon)
        policy.restore(snapshot)

        val numerical = (plusLoss - minusLoss) / (2f * epsilon)
        assertEquals(numerical.toDouble(), gradient[parameterIndex].toDouble(), 2e-2)
    }

    @Test
    fun policyCheckpointRestoresWeightsAndOptimizerState() {
        val policy = RecurrentPpoPolicy(seed = 44L)
        val observation = Observation.blank()
        val forward = policy.forward(observation)
        val sample = PpoTrainingSample(
            observation = observation,
            actionIndex = MovementIntent.WAIT.wireId,
            oldLogProbability = ActionDistribution.logProbability(forward.logits, MovementIntent.WAIT.wireId, observation.actionMask),
            oldValue = forward.value,
            advantage = 1f,
            returnValue = 1f
        )
        policy.updatePpo(listOf(PpoSequence(samples = listOf(sample))))
        val restored = RecurrentPpoPolicy.fromByteArray(policy.toByteArray())
        assertArrayEquals(policy.forward(observation).logits, restored.forward(observation).logits, 0f)
        assertEquals(policy.snapshot().optimizerStep, restored.snapshot().optimizerStep)
    }

    @Test
    fun inferenceArtifactIsSmallChecksummedAndHasNoOptimizerState() {
        val policy = RecurrentPpoPolicy(seed = 55L, policyVersion = "candidate-7")
        val observation = Observation.blank()
        val forward = policy.forward(observation)
        policy.updatePpo(
            listOf(
                PpoSequence(
                    samples = listOf(
                        PpoTrainingSample(
                            observation = observation,
                            actionIndex = MovementIntent.WAIT.wireId,
                            oldLogProbability = ActionDistribution.logProbability(
                                forward.logits,
                                MovementIntent.WAIT.wireId,
                                observation.actionMask
                            ),
                            oldValue = forward.value,
                            advantage = 1f,
                            returnValue = 1f
                        )
                    )
                )
            )
        )
        val artifactBytes = policy.inferenceArtifact().toByteArray()
        assertTrue("living policy artifact must stay below one megabyte", artifactBytes.size < 1_000_000)
        val artifact = RecurrentPolicyInferenceArtifact.fromByteArray(artifactBytes)
        val restored = RecurrentPpoPolicy.fromInferenceArtifact(artifactBytes)
        assertEquals("candidate-7", artifact.policyVersion)
        assertEquals(0L, restored.snapshot().optimizerStep)
        assertArrayEquals(policy.forward(observation).logits, artifact.infer(observation).logits, 0f)
        assertArrayEquals(policy.forward(observation).logits, restored.forward(observation).logits, 0f)

        val corrupted = artifactBytes.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted[corrupted.lastIndex].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            RecurrentPolicyInferenceArtifact.fromByteArray(corrupted)
        }

        val oldSchemaPayload = artifactBytes.copyOfRange(0, artifactBytes.size - 32)
        // The schema integer follows the 8-byte magic and 4-byte format version in the payload.
        oldSchemaPayload[15] = 2
        val oldSchema = oldSchemaPayload + MessageDigest.getInstance("SHA-256").digest(oldSchemaPayload)
        assertThrows(IllegalArgumentException::class.java) {
            RecurrentPolicyInferenceArtifact.fromByteArray(oldSchema)
        }
    }

    @Test
    fun fullCheckpointRejectsAnOlderSemanticEncoderSchema() {
        val checkpoint = RecurrentPpoPolicy(seed = 91L).toByteArray()
        // Full checkpoints have no digest; the schema integer is at byte 12..15.
        checkpoint[15] = 2
        assertThrows(IllegalArgumentException::class.java) {
            RecurrentPpoPolicy.fromByteArray(checkpoint)
        }
    }
}
