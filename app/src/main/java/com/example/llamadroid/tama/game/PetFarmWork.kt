package com.example.llamadroid.tama.game

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/** Farm chores the pet helps with. */
enum class PetFarmWorkKind(val points: Int) { HARVEST(3), PLANT(2), CLEAR(2), WATER(1) }

/** Small stat reward for farm help. */
data class PetFarmWorkReward(val happiness: Float, val bond: Float, val exercise: Float) {
    val isEmpty: Boolean get() = happiness <= 0f && bond <= 0f && exercise <= 0f
}

/**
 * Pet participation in farm work. Doing chores gives a few points of happiness, owner bond and exercise,
 * capped per day so it cannot be farmed. Maximum per day: 90 points = +9 happiness, +2.7 bond, +3.6 exercise.
 * The daily budget lives in SharedPreferences (no database change); the stat change goes through
 * [TamaGameEngine.awardFarmWorkBonus] under the normal mutation gate.
 */
object PetFarmWork {
    const val DAILY_CAP_POINTS = 90
    const val HAPPINESS_PER_POINT = 0.10f
    const val BOND_PER_POINT = 0.03f
    const val EXERCISE_PER_POINT = 0.04f
    private const val PREFS = "tama_farm_pet_work"

    /** Pure reward rule: how many of [count] chores still earn points given [usedToday] points. */
    fun reward(kind: PetFarmWorkKind, count: Int, usedToday: Int): Pair<PetFarmWorkReward, Int> {
        val wanted = count.coerceAtLeast(0) * kind.points
        val granted = wanted.coerceAtMost((DAILY_CAP_POINTS - usedToday).coerceAtLeast(0))
        return PetFarmWorkReward(
            happiness = granted * HAPPINESS_PER_POINT,
            bond = granted * BOND_PER_POINT,
            exercise = granted * EXERCISE_PER_POINT
        ) to granted
    }

    /** Engine-facing hook: call after [count] successful chores of [kind]. Returns the reward actually applied. */
    suspend fun onPetFarmWork(
        context: Context,
        engine: TamaGameEngine,
        petId: String,
        kind: PetFarmWorkKind,
        count: Int,
        today: LocalDate = LocalDate.now()
    ): PetFarmWorkReward {
        if (count <= 0) return PetFarmWorkReward(0f, 0f, 0f)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(petId, null)?.split(':', limit = 2)
        val used = if (stored?.getOrNull(0) == today.toString()) stored.getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val (reward, granted) = reward(kind, count, used)
        if (reward.isEmpty) return reward
        if (!engine.awardFarmWorkBonus(reward.happiness, reward.bond, reward.exercise)) {
            return PetFarmWorkReward(0f, 0f, 0f)
        }
        prefs.edit { putString(petId, "$today:${used + granted}") }
        return reward
    }
}
