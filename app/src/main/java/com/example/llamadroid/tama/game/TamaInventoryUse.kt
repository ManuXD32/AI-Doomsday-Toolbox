package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.TamaFoodCatalog
import com.example.llamadroid.tama.data.TamaPotionCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.world.core.ActionId

/** Inventory use is an action with an effect; it never means discarding an arbitrary item. */
internal object TamaInventoryUse {
    suspend fun request(context: Context, engine: TamaGameEngine, itemId: String, quantity: Int): TamaGameEngine.ActionResult {
        fun unavailable() = TamaGameEngine.ActionResult(false, context.getString(R.string.tama_world_runtime_action_unavailable))
        if (quantity != 1) return TamaGameEngine.ActionResult(false,
            context.getString(R.string.tama_world_use_one_item))
        val item = engine.pet.value?.inventory?.firstOrNull { it.id == itemId && it.quantity > 0 }
            ?: return unavailable()
        val action = when {
            item.type == ItemType.FOOD && TamaFoodCatalog.byId(itemId) != null -> {
                val food = requireNotNull(TamaFoodCatalog.byId(itemId))
                return engine.feedWithFood(itemId, food.hungerGain, food.happinessGain)
            }
            item.type == ItemType.POTION && TamaPotionCatalog.byId(itemId) != null -> return engine.usePotion(itemId)
            item.type in setOf(ItemType.POTION, ItemType.TREASURE) && AdventureGateCatalog.supply(itemId) != null ->
                return engine.useAdventureGateSupply(itemId)
            item.type == ItemType.FOOD && itemId in setOf("berry", "berries", "mushroom", "cactus") -> ActionId.EAT
            item.type == ItemType.MEDICINE -> ActionId.USE_MEDICINE
            itemId == "water" && item.type == ItemType.MATERIAL -> ActionId.DRINK
            else -> return unavailable()
        }
        if (!engine.canDoAction()) return unavailable()
        val result = engine.world.queueAction(action, itemId, emptyMap())
        return TamaGameEngine.ActionResult(result.acceptedCommand,
            context.getString(if (result.acceptedCommand) R.string.tama_world_runtime_action_queued
                else R.string.tama_world_runtime_action_unavailable), action.name.lowercase())
    }
}
