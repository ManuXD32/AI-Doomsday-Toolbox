package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaFoodCatalog
import com.example.llamadroid.tama.world.core.WorldEffectRequest

/**
 * Bridges generic world eating to the catalog-backed Tama feeding operation.
 *
 * The world core deliberately emits a generic inventory and need pair because it
 * cannot depend on the Android food catalog. The adapter rewrites one complete
 * shop-food transaction at the Room boundary. Free foods and wild resources stay
 * on their existing generic path, so the world cannot grant a remote catalog
 * item or consume an item twice.
 */
internal object WorldFoodEffects {
    private const val EAT_REASON = "eat"
    private const val EAT_NEED_REASON = "action:eat"
    private const val FEED_WITH_FOOD_ACTION = "feedWithFood"

    /**
     * Replace one unambiguous generic shop-food eat with its canonical action.
     *
     * A batch containing more than one eat delta is returned unchanged because
     * its need deltas cannot be paired with an item without an action identity.
     * The core catch-up path stops at this boundary before another eat can be
     * accumulated, but retaining this guard keeps malformed or legacy batches
     * safe and replayable.
     */
    fun normalize(effects: List<WorldEffectRequest>): List<WorldEffectRequest> {
        if (effects.any { it is WorldEffectRequest.CanonicalAction && it.action == FEED_WITH_FOOD_ACTION }) {
            return effects
        }

        val eatDeltas = effects.mapIndexedNotNull { index, effect ->
            (effect as? WorldEffectRequest.InventoryDelta)
                ?.takeIf { it.reason == EAT_REASON }
                ?.let { index to it }
        }
        if (eatDeltas.size != 1) return effects

        val (inventoryIndex, inventory) = eatDeltas.single()
        if (inventory.quantity != -1) return effects
        val food = TamaFoodCatalog.shopFoods.firstOrNull { it.id == inventory.itemId } ?: return effects

        val pairedNeedIndices = effects.mapIndexedNotNull { index, effect ->
            (effect as? WorldEffectRequest.NeedDelta)
                ?.takeIf { it.reason == EAT_NEED_REASON }
                ?.let { index }
        }
        if (pairedNeedIndices.isEmpty()) return effects

        val canonical = WorldEffectRequest.CanonicalAction(
            action = FEED_WITH_FOOD_ACTION,
            arguments = mapOf(
                "foodId" to food.id,
                "hungerGain" to food.hungerGain.toString(),
                "happinessGain" to food.happinessGain.toString()
            )
        )
        return effects.mapIndexedNotNull { index, effect ->
            when {
                index == inventoryIndex -> canonical
                index in pairedNeedIndices -> null
                else -> effect
            }
        }
    }
}
