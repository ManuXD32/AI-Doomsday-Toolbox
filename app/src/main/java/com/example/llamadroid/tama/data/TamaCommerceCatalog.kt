package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.world.core.LegacyLocationAliases

/** Existing vendor catalogs are the authority for item identity, type and price. */
object TamaCommerceCatalog {
    data class Offer(val item: InventoryItem, val price: Int, val vendorId: String)

    fun legacyOffer(context: Context, itemName: String): Offer? {
        val normalized = itemName.trim().lowercase(java.util.Locale.ROOT).replace(' ', '_')
        offer(context, normalized, LegacyLocationAliases.SHOP)?.let { return it }
        val food = TamaFoodCatalog.shopFoods.firstOrNull {
            context.getString(it.titleRes).equals(itemName.trim(), ignoreCase = true)
        } ?: return null
        return offer(context, food.id, LegacyLocationAliases.SHOP)
    }

    fun offer(context: Context, id: String, vendorId: String): Offer? {
        TamaPotionCatalog.byId(id)?.let { potion ->
            val expected = if (potion.vendor == TamaPotionVendor.HOSPITAL) LegacyLocationAliases.HOSPITAL
                else LegacyLocationAliases.ALCHEMIST
            return if (vendorId == expected) Offer(potion.toInventoryItem(context), potion.price, vendorId) else null
        }
        if (vendorId != LegacyLocationAliases.SHOP) return null
        TamaFoodCatalog.byId(id)?.let { food ->
            return food.price?.let { Offer(InventoryItem(id, context.getString(food.titleRes), ItemType.FOOD), it, vendorId) }
        }
        TamaDecorCatalog.decorById(id)?.let { decor ->
            return Offer(requireNotNull(TamaDecorCatalog.decorInventoryItem(context, id)), decor.price, vendorId)
        }
        TamaRoomCatalog.roomById(id)?.takeIf { it.price > 0 }?.let { room ->
            return Offer(requireNotNull(TamaRoomCatalog.roomInventoryItem(context, id)), room.price, vendorId)
        }
        if (id.startsWith("seed_")) {
            val cropId = id.removePrefix("seed_")
            val crop = CropDefinitions.CROPS[cropId] ?: return null
            return Offer(InventoryItem(id, seedDisplayText(cropId).resolve(context.resources.configuration.locales[0]),
                ItemType.SEED), crop.seedPrice, vendorId)
        }
        val toolPrice = when (id) {
            "hoe" -> 100
            "watering_can" -> 150
            else -> WorldToolCatalog.price(id)
        }
        if (toolPrice != null) {
            val title = when (id) {
                "hoe" -> context.getString(R.string.tama_inventory_hoe)
                "watering_can" -> context.getString(R.string.tama_inventory_watering_can)
                else -> WorldResourceCatalog.displayName(id, context.resources.configuration.locales[0]) ?: id
            }
            return Offer(InventoryItem(id, title, ItemType.TOOL,
                durability = FARM_TOOL_REPAIR_AMOUNT, maxDurability = FARM_TOOL_DURABILITY_CAP), toolPrice, vendorId)
        }
        if (id in setOf("water", "fertilizer", FARM_FUEL_BUCKET_ID)) {
            val title = if (id == FARM_FUEL_BUCKET_ID) context.getString(R.string.tama_item_fuel_bucket)
                else WorldResourceCatalog.displayName(id, context.resources.configuration.locales[0]) ?: id
            return Offer(InventoryItem(id, title, ItemType.MATERIAL), FarmShopCatalog.materialBuyPrice(id), vendorId)
        }
        return null
    }

    fun sellPrice(id: String): Int? = FarmTradeItemCatalog.definitionForInventoryId(id)?.sellPrice?.coerceAtLeast(5)
}
