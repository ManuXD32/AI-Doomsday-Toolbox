package com.example.llamadroid.tama.world.training

import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.policy.Observation
import com.example.llamadroid.tama.world.policy.PolicyAction
import com.example.llamadroid.tama.world.policy.PolicySpec
import com.example.llamadroid.tama.world.policy.RecurrentState
import kotlinx.serialization.Serializable

enum class CurriculumLevel(val id: Int) {
    LOCOMOTION_VALIDATION(0),
    VISIBLE_TARGET(1),
    HIDDEN_TARGET_SEARCH(2),
    FOOD_AND_WATER(3),
    ENERGY_MANAGEMENT(4),
    BIOME_NAVIGATION(5),
    RESOURCE_GATHERING(6),
    SOCIAL_NAVIGATION(7),
    FARMING(8),
    MULTI_NEED_SURVIVAL(9),
    UNRESTRICTED_GENERALIZATION(10);

    /** Level 0 exercises the environment contract without changing policy weights. */
    val trainsPolicy: Boolean
        get() = this != LOCOMOTION_VALIDATION

    companion object {
        fun fromId(id: Int): CurriculumLevel = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown curriculum level: $id")
    }
}

data class CurriculumDefinition(
    val level: CurriculumLevel,
    val worldWidth: Int,
    val worldHeight: Int,
    val visibleGoal: Boolean,
    val hiddenGoal: Boolean,
    val hasNeeds: Boolean,
    val hasBiomes: Boolean,
    val hasResources: Boolean,
    val hasSocialGoals: Boolean,
    val hasFarming: Boolean,
    val maxEpisodeSteps: Int,
    val successThreshold: Float
)

/** The complete curriculum is kept in one versioned catalog so policy metadata is unambiguous. */
object CurriculumCatalog {
    const val VERSION = 1

    val all: List<CurriculumDefinition> = listOf(
        CurriculumDefinition(CurriculumLevel.LOCOMOTION_VALIDATION, 32, 32, false, false, false, false, false, false, false, 64, 0.95f),
        CurriculumDefinition(CurriculumLevel.VISIBLE_TARGET, 32, 32, true, false, false, false, false, false, false, 96, 0.80f),
        CurriculumDefinition(CurriculumLevel.HIDDEN_TARGET_SEARCH, 32, 32, false, true, true, false, false, false, false, 160, 0.72f),
        CurriculumDefinition(CurriculumLevel.FOOD_AND_WATER, 48, 48, false, true, true, false, false, false, false, 192, 0.68f),
        CurriculumDefinition(CurriculumLevel.ENERGY_MANAGEMENT, 48, 48, false, true, true, false, false, false, false, 224, 0.65f),
        CurriculumDefinition(CurriculumLevel.BIOME_NAVIGATION, 64, 64, false, true, true, true, false, false, false, 256, 0.60f),
        CurriculumDefinition(CurriculumLevel.RESOURCE_GATHERING, 64, 64, false, true, true, true, true, false, false, 288, 0.58f),
        CurriculumDefinition(CurriculumLevel.SOCIAL_NAVIGATION, 80, 80, false, true, true, true, true, true, false, 320, 0.56f),
        CurriculumDefinition(CurriculumLevel.FARMING, 96, 96, false, true, true, true, true, true, true, 384, 0.54f),
        CurriculumDefinition(CurriculumLevel.MULTI_NEED_SURVIVAL, 112, 112, false, true, true, true, true, true, true, 448, 0.52f),
        CurriculumDefinition(CurriculumLevel.UNRESTRICTED_GENERALIZATION, 128, 128, false, true, true, true, true, true, true, 512, 0.50f)
    )

    fun definition(level: CurriculumLevel): CurriculumDefinition = all.first { it.level == level }
}

/** Reward channels are retained separately so reward exploitation can be diagnosed. */
data class RewardBreakdown(
    val objectiveReward: Float = 0f,
    val needReward: Float = 0f,
    val explorationReward: Float = 0f,
    val socialReward: Float = 0f,
    val efficiencyReward: Float = 0f,
    val invalidPenalty: Float = 0f,
    val dangerPenalty: Float = 0f,
    val repetitionPenalty: Float = 0f,
    val stuckPenalty: Float = 0f
) {
    val total: Float
        get() = objectiveReward + needReward + explorationReward + socialReward + efficiencyReward -
            invalidPenalty - dangerPenalty - repetitionPenalty - stuckPenalty

    fun finite(): RewardBreakdown = RewardBreakdown(
        objectiveReward = finiteOrZero(objectiveReward),
        needReward = finiteOrZero(needReward),
        explorationReward = finiteOrZero(explorationReward),
        socialReward = finiteOrZero(socialReward),
        efficiencyReward = finiteOrZero(efficiencyReward),
        invalidPenalty = finiteOrZero(invalidPenalty).coerceAtLeast(0f),
        dangerPenalty = finiteOrZero(dangerPenalty).coerceAtLeast(0f),
        repetitionPenalty = finiteOrZero(repetitionPenalty).coerceAtLeast(0f),
        stuckPenalty = finiteOrZero(stuckPenalty).coerceAtLeast(0f)
    )

    companion object {
        private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f
    }
}

/** Optional semantic signals let the trainer guard common reward exploits independent of UI. */
data class RewardSignals(
    val discoveryKey: String? = null,
    val socialInteractionKey: String? = null,
    val foodConsumedWhileNeedy: Boolean = false,
    val waterConsumedWhileThirsty: Boolean = false,
    val completedFarmingTransition: Boolean = false,
    val repetitionKey: String? = null,
    val stuck: Boolean = false,
    val dangerous: Boolean = false
)

data class EnvironmentPreview(
    val environmentId: Int,
    val seed: Long,
    val curriculum: CurriculumLevel,
    val x: Int,
    val y: Int,
    val goalX: Int,
    val goalY: Int,
    val episodeStep: Int,
    val lastAction: MovementIntent,
    val terminated: Boolean,
    val success: Boolean,
    /** Semantic data for a bounded mini-view; never a rendered frame or unbounded log. */
    val observation: Observation? = null,
    val needs: NeedSnapshot = NeedSnapshot(),
    val wood: Int = 0,
    val food: Int = 0,
    val cropStage: Int = 0,
    val socialInteractions: Int = 0,
    val berries: Int = 0,
    val herbs: Int = 0
) {
    // FloatArray/BooleanArray use identity equality; previews are checkpoint-visible values, so
    // compare their semantic contents to make deterministic resume checks meaningful.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnvironmentPreview) return false
        return environmentId == other.environmentId && seed == other.seed && curriculum == other.curriculum &&
            x == other.x && y == other.y && goalX == other.goalX && goalY == other.goalY &&
            episodeStep == other.episodeStep && lastAction == other.lastAction &&
            terminated == other.terminated && success == other.success &&
            observationContentEquals(observation, other.observation) && needs == other.needs &&
            wood == other.wood && food == other.food && cropStage == other.cropStage &&
            socialInteractions == other.socialInteractions && berries == other.berries && herbs == other.herbs
    }

    override fun hashCode(): Int {
        var result = environmentId
        result = 31 * result + seed.hashCode()
        result = 31 * result + curriculum.hashCode()
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + goalX
        result = 31 * result + goalY
        result = 31 * result + episodeStep
        result = 31 * result + lastAction.hashCode()
        result = 31 * result + terminated.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + observationContentHash(observation)
        result = 31 * result + needs.hashCode()
        result = 31 * result + wood
        result = 31 * result + food
        result = 31 * result + cropStage
        result = 31 * result + socialInteractions
        result = 31 * result + berries
        result = 31 * result + herbs
        return result
    }

    private fun observationContentEquals(left: Observation?, right: Observation?): Boolean {
        if (left === right) return true
        if (left == null || right == null) return false
        return left.tileFeatures.contentEquals(right.tileFeatures) &&
            left.scalarFeatures.contentEquals(right.scalarFeatures) &&
            left.actionMask.contentEquals(right.actionMask)
    }

    private fun observationContentHash(value: Observation?): Int {
        if (value == null) return 0
        var result = value.tileFeatures.contentHashCode()
        result = 31 * result + value.scalarFeatures.contentHashCode()
        result = 31 * result + value.actionMask.contentHashCode()
        return result
    }
}

data class NeedSnapshot(
    val hunger: Float = 1f,
    val hydration: Float = 1f,
    val energy: Float = 1f,
    val health: Float = 1f,
    val hygiene: Float = 1f,
    val happiness: Float = 1f,
    val social: Float = 1f,
    val curiosity: Float = 1f
)

data class EnvironmentStep(
    val observation: Observation,
    val reward: RewardBreakdown,
    val terminated: Boolean = false,
    val truncated: Boolean = false,
    val actionWasValid: Boolean = true,
    val success: Boolean = false,
    val needFailure: Boolean = false,
    val signals: RewardSignals = RewardSignals(),
    val preview: EnvironmentPreview? = null,
    /** Terminal-only outcome facts used for honest efficiency and exploration telemetry. */
    val outcome: EnvironmentOutcome? = null
) {
    init {
        require(observation.actionMask.size == PolicySpec.ACTION_COUNT)
    }

    val done: Boolean
        get() = terminated || truncated
}

/**
 * Facts about an episode outcome that are intentionally kept outside the policy observation.
 * Environments may omit facts they cannot measure; the trainer then reports zero for that
 * aggregate rather than deriving a proxy from reward components.
 */
data class EnvironmentOutcome(
    /** Number of environment actions from reset through objective completion. */
    val objectiveSteps: Int? = null,
    /** Number of movement actions used for the completed objective, when available. */
    val actualMovementSteps: Int? = null,
    /** Shortest feasible route under this world instance's movement rules, when known. */
    val shortestFeasibleSteps: Int? = null,
    /** Number of traversable tiles discovered by the end of the episode, when measurable. */
    val discoveredTraversableTiles: Int? = null,
    /** Total traversable tiles in the generated world, kept outside policy inputs. */
    val totalTraversableTiles: Int? = null
) {
    init {
        require(objectiveSteps == null || objectiveSteps >= 0)
        require(actualMovementSteps == null || actualMovementSteps >= 0)
        require(shortestFeasibleSteps == null || shortestFeasibleSteps >= 0)
        require(discoveredTraversableTiles == null || discoveredTraversableTiles >= 0)
        require(totalTraversableTiles == null || totalTraversableTiles >= 0)
        require(
            discoveredTraversableTiles == null || totalTraversableTiles == null ||
                discoveredTraversableTiles <= totalTraversableTiles
        )
    }

    val pathEfficiency: Float?
        get() {
            val actual = (actualMovementSteps ?: objectiveSteps) ?: return null
            val shortest = shortestFeasibleSteps ?: return null
            if (actual <= 0) return null
            return (shortest.toFloat() / actual.toFloat()).coerceIn(0f, 1f)
        }

    val explorationFraction: Float?
        get() {
            val discovered = discoveredTraversableTiles ?: return null
            val total = totalTraversableTiles ?: return null
            if (total <= 0) return null
            return (discovered.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * Pure training environment contract. Implementations must never receive a TamaDatabase or
 * living-world event repository. World-core can implement this interface without depending on
 * Android, Room, Compose, or the pet UI.
 */
interface TrainingWorldEnvironment : AutoCloseable {
    val environmentId: Int

    fun reset(seed: Long, curriculum: CurriculumLevel): Observation

    fun step(action: MovementIntent): EnvironmentStep

    fun preview(): EnvironmentPreview?

    /** Optional deterministic state for pause/resume checkpoints. */
    fun checkpointState(): ByteArray? = null

    /** Implementations that return [checkpointState] must support restoring it. */
    fun restoreCheckpointState(state: ByteArray) {
        throw UnsupportedOperationException("This environment does not support state restoration")
    }

    override fun close() = Unit
}

/** The returned environments must be state-isolated because their step calls may run concurrently. */
fun interface TrainingWorldEnvironmentFactory {
    fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment
}

enum class TrainerProfile {
    ECO,
    BALANCED,
    FAST,
    CUSTOM
}

data class TrainerConfig(
    val seed: Long = 0x3e2b1a907c5d4f61L,
    val curriculum: CurriculumLevel = CurriculumLevel.VISIBLE_TARGET,
    val environmentCount: Int = 4,
    val rolloutStepsPerEnvironment: Int = 128,
    val ppoEpochs: Int = 4,
    val gamma: Float = 0.99f,
    val gaeLambda: Float = 0.95f,
    val clipEpsilon: Float = 0.2f,
    val entropyCoefficient: Float = 0.01f,
    val valueCoefficient: Float = 0.5f,
    val learningRate: Float = 3e-4f,
    val maxGradientNorm: Float = 0.5f,
    val socialCooldownSteps: Int = 12,
    val profile: TrainerProfile = TrainerProfile.BALANCED,
    val allowTrainingOnBattery: Boolean = false,
    /** Maximum number of environment transition workers; policy and PPO remain owner-serial. */
    val maxThreads: Int = 1
) {
    init {
        require(environmentCount > 0)
        require(rolloutStepsPerEnvironment > 0)
        require(ppoEpochs > 0)
        require(gamma in 0f..1f)
        require(gaeLambda in 0f..1f)
        require(socialCooldownSteps >= 0)
        require(maxThreads > 0)
    }
}

data class TrainerResourceDefaults(
    val maxThreads: Int,
    val environmentCount: Int
)

fun TrainerProfile.resourceDefaults(): TrainerResourceDefaults = when (this) {
    TrainerProfile.ECO -> TrainerResourceDefaults(maxThreads = 1, environmentCount = 2)
    TrainerProfile.BALANCED -> TrainerResourceDefaults(maxThreads = 2, environmentCount = 4)
    TrainerProfile.FAST -> TrainerResourceDefaults(maxThreads = 4, environmentCount = 8)
    // Custom values are supplied by the Android resource controls. These conservative values
    // keep a newly constructed custom session valid until the operator chooses its limits.
    TrainerProfile.CUSTOM -> TrainerResourceDefaults(maxThreads = 1, environmentCount = 2)
}

@Serializable
data class ComponentTotals(
    val objective: Double = 0.0,
    val needs: Double = 0.0,
    val exploration: Double = 0.0,
    val social: Double = 0.0,
    val efficiency: Double = 0.0,
    val invalidPenalty: Double = 0.0,
    val dangerPenalty: Double = 0.0,
    val repetitionPenalty: Double = 0.0,
    val stuckPenalty: Double = 0.0
) {
    fun plus(reward: RewardBreakdown): ComponentTotals = copy(
        objective = objective + reward.objectiveReward,
        needs = needs + reward.needReward,
        exploration = exploration + reward.explorationReward,
        social = social + reward.socialReward,
        efficiency = efficiency + reward.efficiencyReward,
        invalidPenalty = invalidPenalty + reward.invalidPenalty,
        dangerPenalty = dangerPenalty + reward.dangerPenalty,
        repetitionPenalty = repetitionPenalty + reward.repetitionPenalty,
        stuckPenalty = stuckPenalty + reward.stuckPenalty
    )

    fun dividedBy(value: Int): ComponentTotals {
        val divisor = value.coerceAtLeast(1).toDouble()
        return ComponentTotals(
            objective / divisor,
            needs / divisor,
            exploration / divisor,
            social / divisor,
            efficiency / divisor,
            invalidPenalty / divisor,
            dangerPenalty / divisor,
            repetitionPenalty / divisor,
            stuckPenalty / divisor
        )
    }
}

@Serializable
data class TrainingMetrics(
    val updateCount: Long = 0,
    val environmentSteps: Long = 0,
    val episodes: Long = 0,
    val successfulEpisodes: Long = 0,
    val needFailures: Long = 0,
    val stuckEpisodes: Long = 0,
    val invalidActions: Long = 0,
    val totalReturn: Double = 0.0,
    val totalEpisodeSteps: Long = 0,
    val objectiveEpisodes: Long = 0,
    val totalObjectiveSteps: Long = 0,
    val pathEfficiencyTotal: Double = 0.0,
    val pathEfficiencySamples: Long = 0,
    val explorationTotal: Double = 0.0,
    val explorationSamples: Long = 0,
    val components: ComponentTotals = ComponentTotals(),
    val lastPolicyLoss: Float = 0f,
    val lastValueLoss: Float = 0f,
    val lastEntropy: Float = 0f,
    val lastApproximateKl: Float = 0f,
    val lastClippedFraction: Float = 0f,
    val lastGradientNorm: Float = 0f
) {
    val successRate: Float
        get() = if (episodes == 0L) 0f else successfulEpisodes.toFloat() / episodes
    val meanEpisodeReturn: Float
        get() = if (episodes == 0L) 0f else totalReturn.toFloat() / episodes
    val meanEpisodeSteps: Float
        get() = if (episodes == 0L) 0f else totalEpisodeSteps.toFloat() / episodes
    val meanObjectiveSteps: Float
        get() = if (objectiveEpisodes == 0L) 0f else totalObjectiveSteps.toFloat() / objectiveEpisodes
    /** Alias used by the Android projection and chart contract. */
    val objectiveSteps: Float
        get() = meanObjectiveSteps
    val pathEfficiency: Float
        get() = if (pathEfficiencySamples == 0L) 0f else
            (pathEfficiencyTotal / pathEfficiencySamples.toDouble()).toFloat().coerceIn(0f, 1f)
    val explorationScore: Float
        get() = if (explorationSamples == 0L) 0f else
            (explorationTotal / explorationSamples.toDouble()).toFloat().coerceIn(0f, 1f)
    val stuckRate: Float
        get() = if (episodes == 0L) 0f else stuckEpisodes.toFloat() / episodes
    val criticalNeedsRate: Float
        get() = if (episodes == 0L) 0f else needFailures.toFloat() / episodes
    val invalidRate: Float
        get() = if (environmentSteps == 0L) 0f else invalidActions.toFloat() / environmentSteps
    val invalidActionRate: Float
        get() = invalidRate
}

data class TrainerSnapshot(
    val paused: Boolean,
    val curriculum: CurriculumLevel,
    val metrics: TrainingMetrics,
    val candidatePolicyVersion: String,
    val activePolicyVersion: String,
    val previews: List<EnvironmentPreview>,
    val lastUpdateMillis: Long? = null
)

data class LivingInference(
    val action: PolicyAction,
    val policyVersion: String
)

/**
 * Raised when the owner of a synchronous holdout evaluation asks it to stop.
 * The callback is deliberately supplied by the platform layer so this pure
 * module never reads Android power, thermal, or lifecycle state.
 */
class EvaluationAbortedException : IllegalStateException("evaluation_aborted")

@Serializable
data class EvaluationMetrics(
    val policyVersion: String,
    val curriculumId: Int,
    val seedCount: Int,
    val successfulEpisodes: Int,
    val needFailures: Int,
    val stuckEpisodes: Int,
    val meanReturn: Double,
    val meanSteps: Double,
    val meanObjectiveReward: Double,
    val meanNeedReward: Double,
    val meanExplorationReward: Double,
    val meanSocialReward: Double,
    val meanEfficiencyReward: Double,
    val meanInvalidPenalty: Double,
    val meanDangerPenalty: Double,
    val meanRepetitionPenalty: Double,
    val meanStuckPenalty: Double,
    /** Average action count to a completed objective, when the environment measured one. */
    val meanObjectiveSteps: Double = 0.0,
    /** Mean shortest-feasible-route / actual-movement ratio for measured completions. */
    val pathEfficiency: Double = 0.0,
    /** Mean discovered traversable fraction for episodes with world telemetry. */
    val explorationScore: Double = 0.0,
    /** Explicit invalid actions observed during holdout rollouts. */
    val invalidActions: Int = 0,
    val invalidActionRate: Double = 0.0
) {
    val successRate: Double
        get() = if (seedCount == 0) 0.0 else successfulEpisodes.toDouble() / seedCount
    val stuckRate: Double
        get() = if (seedCount == 0) 0.0 else stuckEpisodes.toDouble() / seedCount
    val criticalNeedsRate: Double
        get() = if (seedCount == 0) 0.0 else needFailures.toDouble() / seedCount
}

@Serializable
data class PolicyComparison(
    val current: EvaluationMetrics,
    val candidate: EvaluationMetrics
) {
    val candidateImproves: Boolean
        get() = candidate.successRate > current.successRate ||
            (candidate.successRate == current.successRate && candidate.meanReturn > current.meanReturn)
}

@Serializable
data class TrainerSessionMetadata(
    val formatVersion: Int = 1,
    val curriculumId: Int,
    val totalUpdates: Long,
    val environmentSteps: Long,
    val rngState: Long,
    val policyVersion: String,
    val activePolicyVersion: String,
    val adoptedPolicyVersion: String? = null,
    val holdoutDomain: String = "evaluation-high-bit",
    /** Persisted for operators; reconfiguration may intentionally change this value. */
    val profile: TrainerProfile = TrainerProfile.BALANCED,
    /** Informational sampler setting; loadReconfigured may use a different worker count. */
    val maxThreads: Int = 1
)
