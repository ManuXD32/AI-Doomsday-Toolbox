package com.example.llamadroid.tama.world.training

import com.example.llamadroid.tama.world.policy.DeterministicRng
import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.policy.Observation
import com.example.llamadroid.tama.world.policy.PpoSequence
import com.example.llamadroid.tama.world.policy.PpoTrainingSample
import com.example.llamadroid.tama.world.policy.PpoUpdateConfig
import com.example.llamadroid.tama.world.policy.PpoUpdateStats
import com.example.llamadroid.tama.world.policy.PolicyAction
import com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy
import com.example.llamadroid.tama.world.policy.RecurrentState
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.math.sqrt

private data class EnvironmentSlot(
    val environment: TrainingWorldEnvironment,
    var seed: Long,
    var seedIndex: Long,
    var observation: Observation,
    var recurrentState: RecurrentState,
    val rewardGuard: RewardGuard,
    var episodeReturn: Float = 0f,
    var episodeSteps: Int = 0
)

private data class RawTransition(
    val observation: Observation,
    val actionIndex: Int,
    val oldLogProbability: Float,
    val oldValue: Float,
    val reward: RewardBreakdown,
    val nextValue: Float,
    val done: Boolean
)

private data class ActiveSequence(
    val initialState: RecurrentState,
    val transitions: MutableList<RawTransition> = ArrayList()
)

private data class PreparedStep(
    val slotIndex: Int,
    val slot: EnvironmentSlot,
    val observation: Observation,
    val action: PolicyAction
)

private data class RawSequence(
    val initialState: RecurrentState,
    val transitions: List<RawTransition>
)

@Serializable
private data class SlotCheckpoint(
    val environmentId: Int,
    val seed: Long,
    val seedIndex: Long,
    val observationTiles: List<Float>,
    val observationScalars: List<Float>,
    val actionMask: List<Boolean>,
    val recurrentState: List<Float>,
    val environmentStateBase64: String,
    val rewardGuard: RewardGuardState,
    val episodeReturn: Float,
    val episodeSteps: Int
)

@Serializable
private data class TrainerCheckpointEnvelope(
    val magic: String,
    val formatVersion: Int,
    val metadata: TrainerSessionMetadata,
    val candidatePolicyBase64: String,
    val currentPolicyBase64: String,
    val previousPolicyBase64: String? = null,
    val previousPolicyVersion: String? = null,
    val rngState: Long,
    val nextSeedIndex: Long,
    val paused: Boolean,
    val metrics: TrainingMetrics,
    val slots: List<SlotCheckpoint>
)

/**
 * Synchronous vectorized PPO controller. All methods are pure JVM calls so an Android service can
 * invoke them from a worker coroutine while keeping lifecycle, battery, and thermal policy in the
 * Android layer.
 */
class PpoTrainer(
    private val environmentFactory: TrainingWorldEnvironmentFactory,
    val config: TrainerConfig = TrainerConfig(),
    candidatePolicy: RecurrentPpoPolicy = RecurrentPpoPolicy(seed = config.seed),
    initialCurrentPolicy: RecurrentPpoPolicy? = null
) : AutoCloseable {
    private val seedPlan = HoldoutSeedPlan(config.seed)
    private val actionRng = DeterministicRng(config.seed xor ACTION_RNG_TAG)
    private var nextSeedIndex = config.environmentCount.toLong()
    private var paused = false
    private var metrics = TrainingMetrics()
    private var candidatePolicy = candidatePolicy
    private var currentPolicy = initialCurrentPolicy?.copyPolicy() ?: candidatePolicy.copyPolicy()
    private var previousPolicy: RecurrentPpoPolicy? = null
    private var previousPolicyVersion: String? = null
    private var candidatePolicyVersion = "candidate-0"
    private var activePolicyVersion = "current-0"
    private var lastUpdateMillis: Long? = null
    private val slots = ArrayList<EnvironmentSlot>(config.environmentCount)
    /**
     * Only environment transitions run here. The mutable policy, RNG, reward guards, resets,
     * metrics, and PPO update remain on the trainer owner thread. A single worker keeps the exact
     * serial path for low-power profiles and the parallel path still applies results by slot index.
     */
    private val samplerExecutor: ExecutorService? = if (config.maxThreads > 1) {
        Executors.newFixedThreadPool(config.maxThreads.coerceAtMost(config.environmentCount)) { task ->
            Thread(task, "TamaPpoEnvironment").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    } else {
        null
    }
    private var closed = false

    init {
        require(candidatePolicy.architecture.inputSize == com.example.llamadroid.tama.world.policy.PolicySpec.INPUT_SIZE)
        require(candidatePolicy.architecture.recurrentUnits == com.example.llamadroid.tama.world.policy.PolicySpec.RECURRENT_UNITS)
        repeat(config.environmentCount) { environmentId ->
            val seedIndex = environmentId.toLong()
            val seed = seedPlan.trainingSeed(seedIndex)
            val environment = environmentFactory.create(environmentId, seed, config.curriculum)
            val observation = environment.reset(seed, config.curriculum)
            slots += EnvironmentSlot(
                environment = environment,
                seed = seed,
                seedIndex = seedIndex,
                observation = observation,
                recurrentState = RecurrentState.zero(),
                rewardGuard = RewardGuard(config.socialCooldownSteps)
            )
        }
    }

    val isPaused: Boolean
        get() = paused

    val activePolicyVersionName: String
        get() = activePolicyVersion

    val candidatePolicyVersionName: String
        get() = candidatePolicyVersion

    val parameterCount: Int
        get() = candidatePolicy.parameterCount

    /** Actual maximum sampler concurrency after accounting for the vector width. */
    val samplerThreadCount: Int
        get() = config.maxThreads.coerceAtMost(config.environmentCount)

    fun snapshot(): TrainerSnapshot = TrainerSnapshot(
        paused = paused,
        curriculum = config.curriculum,
        metrics = metrics,
        candidatePolicyVersion = candidatePolicyVersion,
        activePolicyVersion = activePolicyVersion,
        previews = slots.mapNotNull { it.environment.preview() },
        lastUpdateMillis = lastUpdateMillis
    )

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * Collect [batchSteps] transitions across all environments and apply PPO epochs. The method
     * does no work while paused, which makes it safe for an Android controller to call repeatedly.
     */
    fun advance(batchSteps: Int = config.rolloutStepsPerEnvironment * config.environmentCount): TrainerSnapshot {
        if (closed || paused || batchSteps <= 0) return snapshot()
        val activeSequences = slots.map {
            ActiveSequence(it.recurrentState.copyOf())
        }.toMutableList()
        val rawSequences = ArrayList<RawSequence>()
        var stepsRemaining = batchSteps
        while (stepsRemaining > 0) {
            // Actions are generated serially so the single deterministic RNG stream and recurrent
            // policy are never touched from sampler workers. One round contains at most one step
            // per slot; this is the unit that can safely execute in parallel.
            val roundSize = minOf(stepsRemaining, slots.size)
            val prepared = ArrayList<PreparedStep>(roundSize)
            for (slotIndex in 0 until roundSize) {
                val slot = slots[slotIndex]
                prepared += prepareStep(slotIndex, slot)
            }
            val environmentSteps = executeEnvironmentSteps(prepared)
            prepared.indices.forEach { preparedIndex ->
                val step = applyStep(
                    prepared[preparedIndex],
                    activeSequences[prepared[preparedIndex].slotIndex],
                    environmentSteps[preparedIndex]
                )
                if (step != null) {
                    val active = activeSequences[prepared[preparedIndex].slotIndex]
                    rawSequences += RawSequence(active.initialState, step)
                    activeSequences[prepared[preparedIndex].slotIndex] = ActiveSequence(RecurrentState.zero())
                }
            }
            stepsRemaining -= roundSize
        }
        for ((slotIndex, _) in slots.withIndex()) {
            val active = activeSequences[slotIndex]
            if (active.transitions.isNotEmpty()) rawSequences += RawSequence(active.initialState, active.transitions.toList())
        }
        // Curriculum 0 is a validation-only environment contract. It still advances disposable
        // episodes and records telemetry, but it must never change PPO weights, Adam moments,
        // policy versions, or update counters.
        if (rawSequences.isNotEmpty() && config.curriculum.trainsPolicy) {
            val sequences = makePpoSequences(rawSequences)
            var updateStats = PpoUpdateStats(0f, 0f, 0f, 0f, 0f, 0f, 0L)
            repeat(config.ppoEpochs) {
                updateStats = candidatePolicy.updatePpo(sequences, ppoUpdateConfig())
            }
            metrics = metrics.copy(
                updateCount = metrics.updateCount + 1,
                lastPolicyLoss = updateStats.policyLoss,
                lastValueLoss = updateStats.valueLoss,
                lastEntropy = updateStats.entropy,
                lastApproximateKl = updateStats.approximateKl,
                lastClippedFraction = updateStats.clippedFraction,
                lastGradientNorm = updateStats.gradientNorm
            )
            candidatePolicyVersion = "candidate-${metrics.updateCount}"
            lastUpdateMillis = System.currentTimeMillis()
        }
        return snapshot()
    }

    private fun prepareStep(
        slotIndex: Int,
        slot: EnvironmentSlot
    ): PreparedStep {
        val action = candidatePolicy.infer(
            observation = slot.observation,
            state = slot.recurrentState,
            deterministic = false,
            rng = actionRng
        )
        return PreparedStep(slotIndex, slot, slot.observation.copyOfArrays(), action)
    }

    private fun executeEnvironmentSteps(prepared: List<PreparedStep>): List<EnvironmentStep> {
        val executor = samplerExecutor
        if (executor == null || prepared.size <= 1) {
            return prepared.map { item ->
                item.slot.environment.step(MovementIntent.fromWireId(item.action.actionIndex))
            }
        }
        // Future order, rather than completion order, is the application order. This preserves
        // serial trainer metrics and optimizer inputs even when worker scheduling differs.
        val futures = prepared.map { item ->
            executor.submit<EnvironmentStep> {
                item.slot.environment.step(MovementIntent.fromWireId(item.action.actionIndex))
            }
        }
        return futures.map { it.get() }
    }

    private fun applyStep(
        prepared: PreparedStep,
        active: ActiveSequence,
        environmentStep: EnvironmentStep
    ): List<RawTransition>? {
        val slot = prepared.slot
        val guardedReward = slot.rewardGuard.sanitize(slot.episodeSteps, environmentStep.reward, environmentStep.signals)
        val done = environmentStep.done
        val nextValue = if (done) {
            0f
        } else {
            candidatePolicy.forward(environmentStep.observation, prepared.action.nextState).value
        }
        active.transitions += RawTransition(
            observation = prepared.observation,
            actionIndex = prepared.action.actionIndex,
            oldLogProbability = prepared.action.logProbability,
            oldValue = prepared.action.valueEstimate,
            reward = guardedReward,
            nextValue = nextValue,
            done = done
        )
        recordStep(slot, guardedReward, environmentStep)
        slot.observation = environmentStep.observation
        slot.recurrentState = prepared.action.nextState
        if (done) {
            finishEpisode(slot, environmentStep)
            resetSlot(slot)
            return active.transitions.toList()
        }
        return null
    }

    private fun recordStep(slot: EnvironmentSlot, reward: RewardBreakdown, environmentStep: EnvironmentStep) {
        metrics = metrics.copy(
            environmentSteps = metrics.environmentSteps + 1,
            invalidActions = metrics.invalidActions + if (environmentStep.actionWasValid) 0 else 1,
            components = metrics.components.plus(reward)
        )
        slot.episodeReturn += reward.total
        slot.episodeSteps += 1
    }

    private fun finishEpisode(slot: EnvironmentSlot, environmentStep: EnvironmentStep) {
        val episodeWasStuck = environmentStep.signals.stuck || environmentStep.reward.stuckPenalty > 0f
        val outcome = environmentStep.outcome ?: if (environmentStep.success) {
            // This fallback is the actual number of environment actions for a custom environment
            // that reports success but has no richer route telemetry.
            EnvironmentOutcome(objectiveSteps = slot.episodeSteps)
        } else {
            null
        }
        val objectiveSteps = outcome?.objectiveSteps
        val pathEfficiency = outcome?.pathEfficiency
        val explorationFraction = outcome?.explorationFraction
        metrics = metrics.copy(
            episodes = metrics.episodes + 1,
            successfulEpisodes = metrics.successfulEpisodes + if (environmentStep.success) 1 else 0,
            needFailures = metrics.needFailures + if (environmentStep.needFailure) 1 else 0,
            stuckEpisodes = metrics.stuckEpisodes + if (episodeWasStuck) 1 else 0,
            totalReturn = metrics.totalReturn + slot.episodeReturn,
            totalEpisodeSteps = metrics.totalEpisodeSteps + slot.episodeSteps,
            objectiveEpisodes = metrics.objectiveEpisodes + if (objectiveSteps != null) 1 else 0,
            totalObjectiveSteps = metrics.totalObjectiveSteps + (objectiveSteps ?: 0),
            pathEfficiencyTotal = metrics.pathEfficiencyTotal + (pathEfficiency ?: 0f),
            pathEfficiencySamples = metrics.pathEfficiencySamples + if (pathEfficiency != null) 1 else 0,
            explorationTotal = metrics.explorationTotal + (explorationFraction ?: 0f),
            explorationSamples = metrics.explorationSamples + if (explorationFraction != null) 1 else 0
        )
    }

    private fun resetSlot(slot: EnvironmentSlot) {
        val seedIndex = nextSeedIndex++
        val seed = seedPlan.trainingSeed(seedIndex)
        slot.seed = seed
        slot.seedIndex = seedIndex
        slot.observation = slot.environment.reset(seed, config.curriculum)
        slot.recurrentState = RecurrentState.zero()
        slot.rewardGuard.reset()
        slot.episodeReturn = 0f
        slot.episodeSteps = 0
    }

    private fun makePpoSequences(rawSequences: List<RawSequence>): List<PpoSequence> {
        val unnormalised = ArrayList<MutableList<PpoTrainingSample>>()
        val initialStates = ArrayList<RecurrentState>()
        val allAdvantages = ArrayList<Float>()
        rawSequences.forEach { rawSequence ->
            val raw = rawSequence.transitions
            if (raw.isEmpty()) return@forEach
            initialStates += rawSequence.initialState.copyOf()
            var gae = 0f
            val samples = arrayOfNulls<PpoTrainingSample>(raw.size)
            for (index in raw.indices.reversed()) {
                val transition = raw[index]
                val notDone = if (transition.done) 0f else 1f
                val delta = transition.reward.total + config.gamma * transition.nextValue * notDone - transition.oldValue
                gae = delta + config.gamma * config.gaeLambda * notDone * gae
                val returnValue = gae + transition.oldValue
                val sample = PpoTrainingSample(
                    observation = transition.observation,
                    actionIndex = transition.actionIndex,
                    oldLogProbability = transition.oldLogProbability,
                    oldValue = transition.oldValue,
                    advantage = gae,
                    returnValue = returnValue
                )
                samples[index] = sample
                allAdvantages += gae
            }
            @Suppress("UNCHECKED_CAST")
            unnormalised += samples.map { requireNotNull(it) }.toMutableList()
        }
        if (allAdvantages.isEmpty()) return emptyList()
        val mean = allAdvantages.average().toFloat()
        val variance = allAdvantages.sumOf { value ->
            val difference = value - mean
            difference.toDouble() * difference.toDouble()
        }.toFloat() / allAdvantages.size.toFloat()
        val scale = sqrt(variance.toDouble()).toFloat().coerceAtLeast(1e-6f)
        return unnormalised.mapIndexed { sequenceIndex, samples ->
            PpoSequence(
                initialState = initialStates[sequenceIndex],
                samples = samples.map { sample -> sample.copy(advantage = (sample.advantage - mean) / scale) }
            )
        }
    }

    private fun ppoUpdateConfig(): PpoUpdateConfig = PpoUpdateConfig(
        clipEpsilon = config.clipEpsilon,
        entropyCoefficient = config.entropyCoefficient,
        valueCoefficient = config.valueCoefficient,
        learningRate = config.learningRate,
        maxGradientNorm = config.maxGradientNorm
    )

    /** Run frozen-policy evaluation on holdout seeds only. */
    fun evaluateCurrent(seeds: LongArray = seedPlan.evaluationSeeds(DEFAULT_EVALUATION_EPISODES)): EvaluationMetrics =
        evaluatePolicy(currentPolicy, activePolicyVersion, seeds)

    fun evaluateCandidate(seeds: LongArray = seedPlan.evaluationSeeds(DEFAULT_EVALUATION_EPISODES)): EvaluationMetrics =
        evaluatePolicy(candidatePolicy, candidatePolicyVersion, seeds)

    fun evaluateCurrentVsCandidate(
        seeds: LongArray = seedPlan.evaluationSeeds(DEFAULT_EVALUATION_EPISODES)
    ): PolicyComparison = evaluateCurrentVsCandidate(seeds) { true }

    /**
     * Run a frozen comparison while allowing the platform owner to stop between holdout steps.
     * This keeps a long Android evaluation responsive to thermal, battery, and lifecycle changes
     * without giving the pure trainer any platform dependencies.
     */
    fun evaluateCurrentVsCandidate(
        seeds: LongArray,
        shouldContinue: () -> Boolean
    ): PolicyComparison {
        require(seeds.all(seedPlan::isEvaluationSeed)) { "Evaluation seeds must come from the holdout domain" }
        return PolicyComparison(
            evaluatePolicy(currentPolicy, activePolicyVersion, seeds, shouldContinue),
            evaluatePolicy(candidatePolicy, candidatePolicyVersion, seeds, shouldContinue)
        )
    }

    private fun evaluatePolicy(
        policy: RecurrentPpoPolicy,
        version: String,
        seeds: LongArray,
        shouldContinue: () -> Boolean = { true }
    ): EvaluationMetrics {
        require(seeds.all(seedPlan::isEvaluationSeed)) { "Evaluation seeds must come from the holdout domain" }
        if (seeds.isEmpty()) {
            return EvaluationMetrics(version, config.curriculum.id, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        var successful = 0
        var needFailures = 0
        var stuckEpisodes = 0
        var totalReturn = 0.0
        var totalSteps = 0.0
        var totalInvalidActions = 0
        var objectiveStepsTotal = 0.0
        var objectiveStepsSamples = 0
        var pathEfficiencyTotal = 0.0
        var pathEfficiencySamples = 0
        var explorationTotal = 0.0
        var explorationSamples = 0
        var components = ComponentTotals()
        seeds.forEachIndexed { index, seed ->
            if (!shouldContinue()) throw EvaluationAbortedException()
            val environment = environmentFactory.create(10_000 + index, seed, config.curriculum)
            val initialObservation = environment.reset(seed, config.curriculum)
            var observation = initialObservation
            var recurrentState = RecurrentState.zero()
            val guard = RewardGuard(config.socialCooldownSteps)
            var episodeReturn = 0.0
            var episodeSteps = 0
            var success = false
            var needFailure = false
            var stuck = false
            var lastOutcome: EnvironmentOutcome? = null
            try {
                for (stepIndex in 0 until CurriculumCatalog.definition(config.curriculum).maxEpisodeSteps) {
                    if (!shouldContinue()) throw EvaluationAbortedException()
                    if (observation.actionMask.none { it }) break
                    val action = policy.infer(observation, recurrentState, deterministic = true)
                    val step = environment.step(MovementIntent.fromWireId(action.actionIndex))
                    val reward = guard.sanitize(episodeSteps, step.reward, step.signals)
                    episodeReturn += reward.total
                    components = components.plus(reward)
                    if (!step.actionWasValid) totalInvalidActions += 1
                    episodeSteps += 1
                    observation = step.observation
                    recurrentState = action.nextState
                    success = success || step.success
                    needFailure = needFailure || step.needFailure
                    stuck = stuck || step.signals.stuck || step.reward.stuckPenalty > 0f
                    lastOutcome = step.outcome ?: lastOutcome
                    if (step.done) break
                }
            } finally {
                environment.close()
            }
            if (success) successful += 1
            if (needFailure) needFailures += 1
            if (stuck) stuckEpisodes += 1
            totalReturn += episodeReturn
            totalSteps += episodeSteps
            val outcome = lastOutcome ?: if (success) EnvironmentOutcome(objectiveSteps = episodeSteps) else null
            outcome?.objectiveSteps?.let {
                objectiveStepsTotal += it
                objectiveStepsSamples += 1
            }
            outcome?.pathEfficiency?.let {
                pathEfficiencyTotal += it
                pathEfficiencySamples += 1
            }
            outcome?.explorationFraction?.let {
                explorationTotal += it
                explorationSamples += 1
            }
        }
        val divisor = seeds.size.toDouble()
        return EvaluationMetrics(
            policyVersion = version,
            curriculumId = config.curriculum.id,
            seedCount = seeds.size,
            successfulEpisodes = successful,
            needFailures = needFailures,
            stuckEpisodes = stuckEpisodes,
            meanReturn = totalReturn / divisor,
            meanSteps = totalSteps / divisor,
            meanObjectiveReward = components.objective / divisor,
            meanNeedReward = components.needs / divisor,
            meanExplorationReward = components.exploration / divisor,
            meanSocialReward = components.social / divisor,
            meanEfficiencyReward = components.efficiency / divisor,
            meanInvalidPenalty = components.invalidPenalty / divisor,
            meanDangerPenalty = components.dangerPenalty / divisor,
            meanRepetitionPenalty = components.repetitionPenalty / divisor,
            meanStuckPenalty = components.stuckPenalty / divisor,
            meanObjectiveSteps = if (objectiveStepsSamples == 0) 0.0 else
                objectiveStepsTotal / objectiveStepsSamples.toDouble(),
            pathEfficiency = if (pathEfficiencySamples == 0) 0.0 else
                pathEfficiencyTotal / pathEfficiencySamples.toDouble(),
            explorationScore = if (explorationSamples == 0) 0.0 else
                explorationTotal / explorationSamples.toDouble(),
            invalidActions = totalInvalidActions,
            invalidActionRate = if (totalSteps == 0.0) 0.0 else totalInvalidActions / totalSteps
        )
    }

    /** Copy the candidate into the active brain. The caller is responsible for user approval. */
    fun adoptCandidate(): String {
        previousPolicy = currentPolicy.copyPolicy()
        previousPolicyVersion = activePolicyVersion
        currentPolicy.restore(candidatePolicy.snapshot())
        activePolicyVersion = candidatePolicyVersion
        return activePolicyVersion
    }

    /**
     * Replace only the policy used as the comparison/live reference.
     *
     * A trainer checkpoint contains a historical current policy, while the living-world store
     * may have adopted a newer inference artifact since that checkpoint was written. The Android
     * boundary calls this after restoring a session and immediately before evaluation. Candidate
     * weights, optimizer moments, rollout slots, metrics, and trainer RNG remain intact. The
     * rollback snapshot is cleared because it would otherwise restore the historical policy
     * after this explicit refresh.
     */
    fun replaceCurrentPolicy(policy: RecurrentPpoPolicy) {
        require(policy.architecture == candidatePolicy.architecture) {
            "Current comparison policy architecture does not match trainer"
        }
        currentPolicy = policy.copyPolicy()
        activePolicyVersion = policy.policyVersion
        previousPolicy = null
        previousPolicyVersion = null
    }

    fun restorePreviousPolicy(): Boolean {
        val previous = previousPolicy ?: return false
        currentPolicy.restore(previous.snapshot())
        activePolicyVersion = previousPolicyVersion ?: "current-restored"
        previousPolicy = null
        previousPolicyVersion = null
        return true
    }

    /** Full adopted training checkpoint, including Adam state; keep it in the training store. */
    fun activePolicyCheckpoint(): ByteArray = currentPolicy.toByteArray()

    fun candidatePolicyCheckpoint(): ByteArray = candidatePolicy.toByteArray()

    /**
     * Export only candidate inference weights. This boundary intentionally excludes Adam
     * moments, rollout state, and trainer RNG state; the app may show or adopt these bytes.
     */
    fun candidateInferenceArtifact(): ByteArray =
        candidatePolicy.inferenceArtifact(candidatePolicyVersion).toByteArray()

    /** Export only the currently adopted inference policy for the living-world adapter. */
    fun activeInferenceArtifact(): ByteArray =
        currentPolicy.inferenceArtifact(activePolicyVersion).toByteArray()

    fun inferForLiving(
        observation: Observation,
        state: RecurrentState = RecurrentState.zero(),
        deterministic: Boolean = true,
        rng: DeterministicRng = DeterministicRng(LIVING_RNG_TAG)
    ): LivingInference = LivingInference(
        action = currentPolicy.infer(observation, state, deterministic, rng),
        policyVersion = activePolicyVersion
    )

    /** Save policies, Adam state, trainer RNG, recurrent environment state, metrics, and metadata. */
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val checkpoint = TrainerCheckpointEnvelope(
            magic = CHECKPOINT_MAGIC,
            formatVersion = CHECKPOINT_VERSION,
            metadata = TrainerSessionMetadata(
                curriculumId = config.curriculum.id,
                totalUpdates = metrics.updateCount,
                environmentSteps = metrics.environmentSteps,
                rngState = actionRng.state(),
                policyVersion = candidatePolicyVersion,
                activePolicyVersion = activePolicyVersion,
                adoptedPolicyVersion = activePolicyVersion,
                profile = config.profile,
                maxThreads = config.maxThreads
            ),
            candidatePolicyBase64 = Base64.getEncoder().encodeToString(candidatePolicy.toByteArray()),
            currentPolicyBase64 = Base64.getEncoder().encodeToString(currentPolicy.toByteArray()),
            previousPolicyBase64 = previousPolicy?.let { Base64.getEncoder().encodeToString(it.toByteArray()) },
            previousPolicyVersion = previousPolicyVersion,
            rngState = actionRng.state(),
            nextSeedIndex = nextSeedIndex,
            paused = paused,
            metrics = metrics,
            slots = slots.map { slot ->
                val environmentState = requireNotNull(slot.environment.checkpointState()) {
                    "Training environment ${slot.environment.environmentId} must support checkpointState()"
                }
                SlotCheckpoint(
                    environmentId = slot.environment.environmentId,
                    seed = slot.seed,
                    seedIndex = slot.seedIndex,
                    observationTiles = slot.observation.tileFeatures.toList(),
                    observationScalars = slot.observation.scalarFeatures.toList(),
                    actionMask = slot.observation.actionMask.toList(),
                    recurrentState = slot.recurrentState.values.toList(),
                    environmentStateBase64 = Base64.getEncoder().encodeToString(environmentState),
                    rewardGuard = slot.rewardGuard.snapshot(),
                    episodeReturn = slot.episodeReturn,
                    episodeSteps = slot.episodeSteps
                )
            }
        )
        val json = CHECKPOINT_JSON.encodeToString(checkpoint)
        val temporary = File(file.parentFile ?: File("."), "${file.name}.part")
        temporary.writeText(json, StandardCharsets.UTF_8)
        require(temporary.renameTo(file) || file.delete() && temporary.renameTo(file)) {
            "Unable to atomically install trainer checkpoint ${file.absolutePath}"
        }
    }

    fun load(file: File) {
        val checkpoint = decodeCheckpoint(file)
        require(checkpoint.metadata.curriculumId == config.curriculum.id) { "Checkpoint curriculum does not match trainer" }
        require(checkpoint.slots.size == slots.size) { "Checkpoint environment count does not match trainer" }
        restoreCheckpoint(checkpoint, restoreSlots = true)
    }

    /**
     * Restore a checkpoint after changing the Android training profile or curriculum.
     *
     * Policy weights, Adam moments, trainer RNG, policy versions, and cumulative metrics are
     * always restored. Rollout/environment state is restored only when its curriculum and vector
     * width still match; otherwise the constructor's fresh slots are retained. This preserves
     * training age while preventing a slot from being resumed under the wrong world rules.
     */
    fun loadReconfigured(file: File) {
        val checkpoint = decodeCheckpoint(file)
        val compatibleSlots = checkpoint.metadata.curriculumId == config.curriculum.id &&
            checkpoint.slots.size == slots.size
        restoreCheckpoint(checkpoint, restoreSlots = compatibleSlots)
    }

    private fun decodeCheckpoint(file: File): TrainerCheckpointEnvelope {
        val checkpoint = CHECKPOINT_JSON.decodeFromString<TrainerCheckpointEnvelope>(
            file.readText(StandardCharsets.UTF_8)
        )
        require(checkpoint.magic == CHECKPOINT_MAGIC) { "Unsupported ADT trainer checkpoint" }
        require(checkpoint.formatVersion == CHECKPOINT_VERSION) { "Unsupported ADT trainer checkpoint version" }
        require(checkpoint.metadata.holdoutDomain == "evaluation-high-bit") {
            "Checkpoint holdout seed domain does not match trainer"
        }
        return checkpoint
    }

    private fun restoreCheckpoint(checkpoint: TrainerCheckpointEnvelope, restoreSlots: Boolean) {
        candidatePolicy = RecurrentPpoPolicy.fromByteArray(
            Base64.getDecoder().decode(checkpoint.candidatePolicyBase64)
        )
        currentPolicy = RecurrentPpoPolicy.fromByteArray(
            Base64.getDecoder().decode(checkpoint.currentPolicyBase64)
        )
        require(candidatePolicy.architecture == currentPolicy.architecture)
        previousPolicy = checkpoint.previousPolicyBase64?.let {
            RecurrentPpoPolicy.fromByteArray(Base64.getDecoder().decode(it))
        }
        require(previousPolicy?.architecture == null || previousPolicy?.architecture == candidatePolicy.architecture)
        previousPolicyVersion = checkpoint.previousPolicyVersion
        actionRng.restore(checkpoint.rngState)
        nextSeedIndex = checkpoint.nextSeedIndex
        paused = checkpoint.paused
        metrics = checkpoint.metrics
        candidatePolicyVersion = checkpoint.metadata.policyVersion
        activePolicyVersion = checkpoint.metadata.activePolicyVersion
        if (!restoreSlots) {
            // Fresh slots already occupy the first vector indices. Do not reuse one of those
            // training seeds when the new vector is wider than the saved one.
            nextSeedIndex = maxOf(nextSeedIndex, slots.size.toLong())
            return
        }
        require(checkpoint.slots.size == slots.size) { "Checkpoint environment count does not match trainer" }
        checkpoint.slots.forEach { saved ->
            val slot = slots.firstOrNull { it.environment.environmentId == saved.environmentId }
                ?: error("Checkpoint is missing environment ${saved.environmentId}")
            slot.environment.restoreCheckpointState(Base64.getDecoder().decode(saved.environmentStateBase64))
            slot.seed = saved.seed
            slot.seedIndex = saved.seedIndex
            slot.observation = Observation(
                tileFeatures = saved.observationTiles.toFloatArray(),
                scalarFeatures = saved.observationScalars.toFloatArray(),
                actionMask = BooleanArray(saved.actionMask.size) { saved.actionMask[it] }
            )
            slot.recurrentState = RecurrentState(saved.recurrentState.toFloatArray())
            slot.rewardGuard.restore(saved.rewardGuard)
            slot.episodeReturn = saved.episodeReturn
            slot.episodeSteps = saved.episodeSteps
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        samplerExecutor?.shutdown()
        slots.forEach { it.environment.close() }
    }

    companion object {
        private const val CHECKPOINT_MAGIC = "ADT_TRAINER_CHECKPOINT"
        private const val CHECKPOINT_VERSION = 1
        private const val DEFAULT_EVALUATION_EPISODES = 16
        private const val ACTION_RNG_TAG = 0x62e1a55d0f90c4b7L
        private const val LIVING_RNG_TAG = 0x7a91d31f0b4e5c26L
        private val CHECKPOINT_JSON = Json {
            encodeDefaults = true
            prettyPrint = false
        }
    }
}
