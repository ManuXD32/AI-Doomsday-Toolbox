package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.TamaFoodOptions
import com.example.llamadroid.tama.data.TamaPotionCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCatalog

/** Inventory use is an action with an effect; it never means discarding an arbitrary item. */
internal object TamaInventoryUse {
    suspend fun request(context: Context, engine: TamaGameEngine, itemId: String, quantity: Int): TamaGameEngine.ActionResult {
        fun unavailable() = TamaGameEngine.ActionResult(false, context.getString(R.string.tama_action_unavailable))
        if (quantity != 1) return TamaGameEngine.ActionResult(false,
            context.getString(R.string.tama_action_use_one_item))
        val item = engine.pet.value?.inventory?.firstOrNull { it.id == itemId && it.quantity > 0 }
            ?: return unavailable()
        return when {
            item.type in setOf(ItemType.FOOD, ItemType.CROP) && TamaFoodOptions.definition(itemId) != null -> {
                val food = requireNotNull(TamaFoodOptions.definition(itemId))
                engine.feedWithFood(food.id, food.hungerGain, food.happinessGain)
            }
            item.type == ItemType.POTION && TamaPotionCatalog.byId(itemId) != null -> engine.usePotion(itemId)
            item.type in setOf(ItemType.POTION, ItemType.TREASURE) && AdventureGateCatalog.supply(itemId) != null ->
                engine.useAdventureGateSupply(itemId)
            // Berries, mushrooms, cactus, medicine and water had world-only effects.
            else -> unavailable()
        }
    }
}
