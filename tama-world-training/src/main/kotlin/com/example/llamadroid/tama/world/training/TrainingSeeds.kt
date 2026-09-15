package com.example.llamadroid.tama.world.training

import kotlinx.serialization.Serializable

/**
 * Domain-separated seed stream. Training seeds are always non-negative and evaluation seeds are
 * always negative, making accidental overlap detectable without retaining every prior seed.
 */
class HoldoutSeedPlan(val baseSeed: Long) {
    fun trainingSeed(index: Long): Long = mix(baseSeed xor TRAINING_TAG xor index) and Long.MAX_VALUE

    fun evaluationSeed(index: Long): Long = mix(baseSeed xor EVALUATION_TAG xor index).or(Long.MIN_VALUE)

    fun isTrainingSeed(seed: Long): Boolean = seed >= 0L

    fun isEvaluationSeed(seed: Long): Boolean = seed < 0L

    fun trainingSeeds(count: Int, offset: Long = 0L): LongArray = LongArray(count.coerceAtLeast(0)) {
        trainingSeed(offset + it)
    }

    fun evaluationSeeds(count: Int, offset: Long = 0L): LongArray = LongArray(count.coerceAtLeast(0)) {
        evaluationSeed(offset + it)
    }

    companion object {
        private const val TRAINING_TAG = 0x13579bdf2468ace0L
        private const val EVALUATION_TAG = -163971054138006496L

        private fun mix(value: Long): Long {
            var mixed = value + -7046029254386353131L
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }
    }
}

/** Immutable ledger used by the trainer to remove common reward exploits from environment signals. */
@Serializable
data class RewardGuardEntry(val key: String, val step: Int)

@Serializable
data class RewardGuardState(
    val discoveries: List<String> = emptyList(),
    val socialLastSeen: List<RewardGuardEntry> = emptyList(),
    val repetitions: List<RewardGuardEntry> = emptyList()
)

class RewardGuard(private val socialCooldownSteps: Int) {
    private val discoveries = HashSet<String>()
    private val socialLastSeen = HashMap<String, Int>()
    private val repetitions = HashMap<String, Int>()

    fun reset() {
        discoveries.clear()
        socialLastSeen.clear()
        repetitions.clear()
    }

    fun snapshot(): RewardGuardState = RewardGuardState(
        discoveries = discoveries.toList().sorted(),
        socialLastSeen = socialLastSeen.entries
            .map { RewardGuardEntry(it.key, it.value) }
            .sortedBy { it.key },
        repetitions = repetitions.entries
            .map { RewardGuardEntry(it.key, it.value) }
            .sortedBy { it.key }
    )

    fun restore(state: RewardGuardState) {
        reset()
        discoveries += state.discoveries
        state.socialLastSeen.forEach { socialLastSeen[it.key] = it.step }
        state.repetitions.forEach { repetitions[it.key] = it.step }
    }

    fun sanitize(step: Int, reward: RewardBreakdown, signals: RewardSignals): RewardBreakdown {
        val finite = reward.finite()
        val firstDiscovery = signals.discoveryKey?.let(discoveries::add) ?: true
        val socialAllowed = signals.socialInteractionKey?.let { key ->
            val previous = socialLastSeen[key]
            val allowed = previous == null || step - previous >= socialCooldownSteps
            socialLastSeen[key] = step
            allowed
        } ?: true
        val repeated = signals.repetitionKey?.let { key ->
            val count = (repetitions[key] ?: 0) + 1
            repetitions[key] = count
            count > 2
        } ?: false
        return finite.copy(
            explorationReward = if (firstDiscovery) finite.explorationReward else 0f,
            socialReward = if (socialAllowed) finite.socialReward else 0f,
            needReward = if (signals.foodConsumedWhileNeedy || signals.waterConsumedWhileThirsty || finite.needReward <= 0f) {
                finite.needReward
            } else {
                0f
            },
            repetitionPenalty = maxOf(finite.repetitionPenalty, if (repeated) 0.2f else 0f),
            stuckPenalty = maxOf(finite.stuckPenalty, if (signals.stuck) 1f else 0f),
            dangerPenalty = maxOf(finite.dangerPenalty, if (signals.dangerous) 1f else 0f)
        )
    }
}
