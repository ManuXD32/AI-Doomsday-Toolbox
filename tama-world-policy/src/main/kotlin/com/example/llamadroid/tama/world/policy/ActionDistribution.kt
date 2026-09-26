package com.example.llamadroid.tama.world.policy

import kotlin.math.exp
import kotlin.math.ln

/** Safety-first action distribution helpers. Invalid actions are never sampled. */
object ActionDistribution {
    const val NEGATIVE_INFINITY = -1.0e30f

    fun maskedLogits(logits: FloatArray, actionMask: BooleanArray): FloatArray {
        require(logits.size == PolicySpec.ACTION_COUNT)
        require(actionMask.size == PolicySpec.ACTION_COUNT)
        val output = logits.copyOf()
        var hasValid = false
        for (i in output.indices) {
            if (actionMask[i]) {
                hasValid = true
            } else {
                output[i] = NEGATIVE_INFINITY
            }
        }
        if (!hasValid) output[MovementIntent.WAIT.wireId] = logits[MovementIntent.WAIT.wireId]
        return output
    }

    fun probabilities(logits: FloatArray, actionMask: BooleanArray): FloatArray {
        val masked = maskedLogits(logits, actionMask)
        val maxLogit = masked.maxOrNull() ?: 0f
        val probabilities = FloatArray(masked.size)
        var total = 0.0
        for (i in masked.indices) {
            if (masked[i] > NEGATIVE_INFINITY / 2f) {
                val weight = exp((masked[i] - maxLogit).toDouble())
                probabilities[i] = weight.toFloat()
                total += weight
            }
        }
        if (total <= 0.0 || !total.isFinite()) {
            probabilities.fill(0f)
            probabilities[MovementIntent.WAIT.wireId] = 1f
            return probabilities
        }
        for (i in probabilities.indices) probabilities[i] /= total.toFloat()
        return probabilities
    }

    fun logProbability(logits: FloatArray, actionIndex: Int, actionMask: BooleanArray): Float {
        require(actionIndex in logits.indices)
        val probabilities = probabilities(logits, actionMask)
        return ln(probabilities[actionIndex].coerceAtLeast(1.0e-12f).toDouble()).toFloat()
    }

    fun entropy(logits: FloatArray, actionMask: BooleanArray): Float {
        val probabilities = probabilities(logits, actionMask)
        var entropy = 0.0
        probabilities.forEach { probability ->
            if (probability > 1.0e-12f) entropy -= probability * ln(probability.toDouble())
        }
        return entropy.toFloat()
    }

    fun select(
        logits: FloatArray,
        actionMask: BooleanArray,
        rng: DeterministicRng,
        deterministic: Boolean
    ): Int {
        val probabilities = probabilities(logits, actionMask)
        if (deterministic) {
            var best = MovementIntent.WAIT.wireId
            var bestProbability = -1f
            probabilities.forEachIndexed { index, probability ->
                if (probability > bestProbability) {
                    best = index
                    bestProbability = probability
                }
            }
            return best
        }
        var remaining = rng.nextFloat()
        probabilities.forEachIndexed { index, probability ->
            remaining -= probability
            if (remaining <= 0f) return index
        }
        return probabilities.indices.lastOrNull { probabilities[it] > 0f } ?: MovementIntent.WAIT.wireId
    }
}
