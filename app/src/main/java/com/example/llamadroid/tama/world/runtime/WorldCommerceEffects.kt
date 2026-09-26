package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.tama.data.TamaCommerceCatalog
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.TamaCanonicalActions
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.ActionId

/** Converts generic simulator commerce into the same catalog-backed living operation as the UI. */
internal object WorldCommerceEffects {
    fun allowsCommand(context: Context, state: WorldState, command: WorldCommand): Boolean {
        if (command !is WorldCommand.PerformAction || command.action !in setOf(ActionId.BUY, ActionId.SELL) ||
            !command.arguments["canonicalAction"].isNullOrBlank()) return true
        val itemId = command.arguments["itemId"] ?: command.arguments["item"] ?: command.targetId ?: return false
        val quantity = command.arguments["quantity"]?.toIntOrNull() ?: 1
        val price = (command.arguments["pricePerUnit"] ?: command.arguments["unitPrice"] ?: command.arguments["price"])
            ?.toIntOrNull() ?: return false
        if (quantity !in 1..1_000_000 || price <= 0 || quantity.toLong() * price > Int.MAX_VALUE) return false
        val vendor = state.actor.structureId ?: LegacyLocationAliases.SHOP
        return if (command.action == ActionId.BUY) TamaCommerceCatalog.offer(context, itemId, vendor)?.price == price
            else vendor == LegacyLocationAliases.SHOP && TamaCommerceCatalog.sellPrice(itemId) == price
    }

    fun isCommerceDelta(effect: WorldEffectRequest): Boolean = when (effect) {
        is WorldEffectRequest.InventoryDelta -> effect.reason in setOf("buy", "sell")
        is WorldEffectRequest.MoneyDelta -> effect.reason in setOf("buy", "sell")
        else -> false
    }

    fun canonical(context: Context, pet: TamaPet, state: WorldState,
                  effects: List<WorldEffectRequest>): List<WorldEffectRequest.CanonicalAction> {
        val inventory = effects.filterIsInstance<WorldEffectRequest.InventoryDelta>().filter(::isCommerceDelta)
        val money = effects.filterIsInstance<WorldEffectRequest.MoneyDelta>().filter(::isCommerceDelta)
        if (inventory.isEmpty() && money.isEmpty()) return emptyList()
        require(inventory.size == money.size) { "invalid_commerce_effects" }
        require(state.actor.presence == PresenceMode.INTERIOR) { "vendor_required" }
        val vendor = state.actor.structureId ?: error("vendor_required")
        return inventory.zip(money).map { (item, payment) ->
            require(item.reason == payment.reason) { "invalid_commerce_effects" }
            if (item.reason == "buy") {
                val offer = TamaCommerceCatalog.offer(context, item.itemId, vendor) ?: error("unknown_vendor_offer")
                require(item.quantity > 0 && -payment.amount.toLong() == offer.price.toLong() * item.quantity) { "invalid_purchase_price" }
                WorldEffectRequest.CanonicalAction("buyItem", TamaCanonicalActions.itemArguments(offer.item) + mapOf(
                    "quantity" to item.quantity.toString(), "pricePerUnit" to offer.price.toString(), "vendorId" to vendor
                ))
            } else {
                require(vendor == LegacyLocationAliases.SHOP) { "seller_receipt_required" }
                val owned = pet.inventory.firstOrNull { it.id == item.itemId } ?: error("item_missing")
                val price = TamaCommerceCatalog.sellPrice(item.itemId) ?: error("item_not_for_sale")
                require(item.quantity < 0 && payment.amount.toLong() == price.toLong() * -item.quantity.toLong()) { "invalid_sale_price" }
                WorldEffectRequest.CanonicalAction("sellItem", TamaCanonicalActions.itemArguments(owned) + mapOf(
                    "quantity" to (-item.quantity).toString(), "price" to price.toString()
                ))
            }
        }
    }
}
