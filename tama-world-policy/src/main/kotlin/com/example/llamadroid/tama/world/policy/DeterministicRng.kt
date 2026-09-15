package com.example.llamadroid.tama.world.policy

/**
 * Small reproducible PRNG used by policy initialisation, action sampling, and training.
 * It is deliberately independent of kotlin.random.Random so a checkpoint can restore it
 * exactly on every supported JVM/Android runtime.
 */
class DeterministicRng(seed: Long) {
    private var state: Long = if (seed == 0L) GOLDEN_GAMMA else seed

    fun state(): Long = state

    fun restore(newState: Long) {
        state = if (newState == 0L) GOLDEN_GAMMA else newState
    }

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun nextFloat(): Float {
        // Keep 24 random bits so the result is stable and has no signed conversion edge cases.
        return ((nextLong() ushr 40).toInt() and 0xFFFFFF) / 16_777_216f
    }

    fun nextGaussian(): Float {
        var u1 = nextFloat()
        while (u1 <= 0f) u1 = nextFloat()
        val u2 = nextFloat()
        val radius = kotlin.math.sqrt((-2f * kotlin.math.ln(u1.toDouble())).toDouble()).toFloat()
        val angle = (2f * kotlin.math.PI.toFloat() * u2)
        return radius * kotlin.math.cos(angle)
    }

    fun split(tag: Long): DeterministicRng {
        var mixed = nextLong() xor tag
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        return DeterministicRng(mixed xor (mixed ushr 31))
    }

    companion object {
        private const val GOLDEN_GAMMA = -7046029254386353131L
    }
}
