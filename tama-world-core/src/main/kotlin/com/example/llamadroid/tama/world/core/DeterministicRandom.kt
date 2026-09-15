package com.example.llamadroid.tama.world.core

/**
 * Small, platform-independent hash/PRNG helpers. Kotlin's collection hash
 * implementations are deliberately not used for world decisions because a
 * world must reproduce the same way on Android, desktop tests, and training.
 */
object DeterministicRandom {
    private const val GOLDEN_GAMMA = -7046029254386353131L
    private const val MIX_MULTIPLIER_A = -49064778989728563L
    private const val MIX_MULTIPLIER_B = -4265267296055464877L

    fun hash(seed: Long, vararg parts: Long): Long {
        var value = seed + GOLDEN_GAMMA
        parts.forEachIndexed { index, part ->
            value = mix(value xor (part + GOLDEN_GAMMA * (index + 1L)))
        }
        return mix(value)
    }

    fun hashString(seed: Long, value: String): Long {
        var result = hash(seed, value.length.toLong())
        value.toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
            result = hash(result, byte.toLong(), index.toLong())
        }
        return result
    }

    fun unit(seed: Long, vararg parts: Long): Double {
        val value = hash(seed, *parts)
        return ((value ushr 11).toDouble()) * (1.0 / (1L shl 53).toDouble())
    }

    fun signedUnit(seed: Long, vararg parts: Long): Double = unit(seed, *parts) * 2.0 - 1.0

    fun int(seed: Long, bound: Int, vararg parts: Long): Int {
        require(bound > 0) { "bound must be positive" }
        val unsigned = hash(seed, *parts).ushr(1)
        return (unsigned % bound.toLong()).toInt()
    }

    fun boolean(seed: Long, vararg parts: Long): Boolean = (hash(seed, *parts) and 1L) == 0L

    fun mix(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * MIX_MULTIPLIER_A
        mixed = (mixed xor (mixed ushr 27)) * MIX_MULTIPLIER_B
        return mixed xor (mixed ushr 31)
    }
}

class DeterministicSequence(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        return DeterministicRandom.mix(state)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return (nextLong().ushr(1) % bound.toLong()).toInt()
    }

    fun nextDouble(): Double = ((nextLong().ushr(11)).toDouble()) * (1.0 / (1L shl 53).toDouble())
}
