package com.example.llamadroid.tama.adventure

import kotlin.math.roundToInt

/** Ending and reward rules for LLM runs. Pure, so it is unit tested. */
object AdventureRewards {
    const val BASE_COINS = 40
    const val BASE_XP = 6

    data class Computation(
        val coins: Int,
        val xp: Int,
        val bond: Int,
        val grants: List<ChronicleGrant>
    ) {
        companion object {
            val NONE = Computation(0, 0, 0, emptyList())
        }
    }

    fun difficultyMultiplier(difficulty: String): Float = when {
        difficulty.contains("hard", ignoreCase = true) || difficulty.contains("dif\u00edcil", ignoreCase = true) -> 2f
        difficulty.contains("easy", ignoreCase = true) || difficulty.contains("f\u00e1cil", ignoreCase = true) -> 1f
        else -> 1.5f
    }

    /** The run is a victory only when the final scene was reached with hope left. */
    fun outcomeFor(finalHope: Int, reachedFinalScene: Boolean): AdventureOutcome =
        if (reachedFinalScene && finalHope > 0) AdventureOutcome.VICTORY else AdventureOutcome.CARRIED_BACK

    /** Reward id the dungeon's retheme promises (see AdventureLore.dungeons chronicleReward). */
    fun dungeonRewardId(type: DungeonType): String = when (type) {
        DungeonType.SHADOW_CRYPTS, DungeonType.FROSTBOUND_HALLS, DungeonType.VOID_SANCTUM -> "memory_fragment"
        DungeonType.ARCANE_DEPTHS, DungeonType.INFERNAL_SPIRE, DungeonType.VERDANT_MAW -> "gate_item"
        DungeonType.CHAOS_REALM -> "lantern_spark"
    }

    /**
     * Coins, XP (added to the pet's introspection level) and bond scale with difficulty and the hope left.
     * A carried-back run still writes a Chronicle page but grants nothing.
     */
    fun compute(
        difficulty: String,
        finalHope: Int,
        outcome: AdventureOutcome,
        dungeon: DungeonType
    ): Computation {
        if (outcome != AdventureOutcome.VICTORY) return Computation.NONE
        val hope = finalHope.coerceIn(0, AdventureRunState.MAX_HOPE)
        val multiplier = difficultyMultiplier(difficulty)
        val hopeFactor = 0.5f + hope / 200f
        val bond = multiplier.roundToInt() + if (hope >= 80) 1 else 0
        val grants = buildList {
            add(ChronicleGrant(dungeonRewardId(dungeon)))
            if (hope >= 80 && dungeonRewardId(dungeon) != "lantern_spark") add(ChronicleGrant("lantern_spark"))
        }
        return Computation(
            coins = (BASE_COINS * multiplier * hopeFactor).roundToInt(),
            xp = (BASE_XP * multiplier * hopeFactor).roundToInt(),
            bond = bond,
            grants = grants
        )
    }
}
