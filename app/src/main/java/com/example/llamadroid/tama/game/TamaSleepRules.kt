package com.example.llamadroid.tama.game

/**
 * Pure sleep-as-rest rules: continuous energy restoration, gentle hunger/hydration drift,
 * auto-wake thresholds. No Android dependencies so it is unit-testable.
 */
object TamaSleepRules {
    /** Energy per minute while asleep: 0 to 100 in ~3.3 h (a night's sleep fully restores). */
    const val ENERGY_PER_MINUTE = 0.5f
    /** Babies sleep more efficiently: 0 to 100 in ~100 min, matching their 5x faster decay. */
    const val BABY_ENERGY_PER_MINUTE = 1.0f
    /** Health regained per minute while asleep (a full night is about +10). */
    const val HEALTH_PER_MINUTE = 0.05f
    /** Hunger/hydration keep drifting at a quarter of the awake rate (a pet sleeping 8 h still gets hungry). */
    const val DECAY_FRACTION = 0.25f
    const val MIN_SLEEP_BEFORE_AUTO_WAKE_MINUTES = 20
    const val MAX_SLEEP_MINUTES = 12 * 60
    /** Flat energy bonus for an instant (under a minute) manual wake so a short nap is never worthless. */
    const val MIN_NAP_BONUS = 10f
    const val MIN_NAP_THRESHOLD_MS = 60_000L

    private const val MINUTE_MS = 60_000L

    data class Step(
        /** Time the step is applied up to (may be earlier than `toMs` when the pet wakes). */
        val endMs: Long,
        val minutes: Float,
        val energyGain: Float,
        val healthGain: Float,
        val autoWake: Boolean
    )

    fun energyPerMinute(isBaby: Boolean): Float = if (isBaby) BABY_ENERGY_PER_MINUTE else ENERGY_PER_MINUTE

    /** Hunger/hydration loss for [minutes] of sleep given the normal awake loss per hour. */
    fun drift(minutes: Float, awakeDecayPerHour: Float): Float =
        (minutes / 60f) * awakeDecayPerHour * DECAY_FRACTION

    /**
     * Computes the restoration for the window [fromMs, toMs] of a sleep that began at [sleepStartMs].
     * The window is clamped to the sleep start, so catch-up after the app was closed is capped by
     * real elapsed time, and to the auto-wake moment (energy full after >= 20 min, or 12 h max).
     */
    fun step(
        sleepStartMs: Long,
        fromMs: Long,
        toMs: Long,
        energy: Float,
        isBaby: Boolean
    ): Step {
        val from = maxOf(fromMs, sleepStartMs)
        val to = maxOf(toMs, from)
        val rate = energyPerMinute(isBaby)
        val minAutoWakeAt = sleepStartMs + MIN_SLEEP_BEFORE_AUTO_WAKE_MINUTES * MINUTE_MS
        val maxSleepAt = sleepStartMs + MAX_SLEEP_MINUTES * MINUTE_MS
        val missing = (100f - energy).coerceAtLeast(0f)
        val fullAt = from + (missing / rate * MINUTE_MS).toLong()
        val energyWakeAt = maxOf(fullAt, minAutoWakeAt)
        val wakeAt = minOf(energyWakeAt, maxSleepAt)
        val autoWake = wakeAt <= to
        val end = if (autoWake) maxOf(wakeAt, from) else to
        val minutes = (end - from) / MINUTE_MS.toFloat()
        return Step(
            endMs = end,
            minutes = minutes,
            energyGain = (minutes * rate).coerceAtMost(missing),
            healthGain = minutes * HEALTH_PER_MINUTE,
            autoWake = autoWake
        )
    }
}
