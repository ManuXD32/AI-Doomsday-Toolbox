package com.example.llamadroid.tama.data

import java.util.Locale
import kotlinx.serialization.Serializable

private const val HOUR_MS = 60L * 60L * 1000L
const val LIVESTOCK_FEED_INTERVAL_MS = 24L * HOUR_MS
const val LIVESTOCK_FEED_ITEM_ID = "crop_wheat"
const val LIVESTOCK_FEED_ASSET_PATH = "farm/Crops/stage_final/wheat.png"

enum class FarmLivestockType(
    val id: String,
    val buyPrice: Int,
    val productInventoryId: String,
    val productSellPrice: Int,
    val productionIntervalMs: Long,
    val perAnimalStorageCap: Int,
    val maxAnimals: Int,
    val slotAssetPath: String,
    val animalAssetPath: String,
    val productAssetPath: String,
    val backgroundAssetPath: String
) {
    BARN(
        id = "barn",
        buyPrice = 500,
        productInventoryId = "produce_milk",
        productSellPrice = 50,
        productionIntervalMs = 6L * HOUR_MS,
        perAnimalStorageCap = 5,
        maxAnimals = 8,
        slotAssetPath = "farm/livestock/barn_tile.png",
        animalAssetPath = "farm/livestock/cow.png",
        productAssetPath = "farm/livestock/milk_bottle.png",
        backgroundAssetPath = "farm/livestock/barn_background.png"
    ),
    COOP(
        id = "coop",
        buyPrice = 300,
        productInventoryId = "produce_egg",
        productSellPrice = 20,
        productionIntervalMs = 4L * HOUR_MS,
        perAnimalStorageCap = 6,
        maxAnimals = 8,
        slotAssetPath = "farm/livestock/coop_tile.png",
        animalAssetPath = "farm/livestock/chicken.png",
        productAssetPath = "farm/livestock/egg.png",
        backgroundAssetPath = "farm/livestock/coop_background.png"
    );

    companion object {
        fun fromId(id: String): FarmLivestockType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

@Serializable
data class FarmLivestockSlot(
    val occupied: Boolean = false,
    val storedOutput: Int = 0,
    val lastProductionTime: Long? = null,
    val lastFedAt: Long? = null
)

fun emptyLivestockSlots(type: FarmLivestockType): List<FarmLivestockSlot> =
    List(type.maxAnimals) { FarmLivestockSlot() }

fun occupiedLivestockCount(slots: List<FarmLivestockSlot>): Int =
    slots.count { it.occupied }

fun storedLivestockOutput(slots: List<FarmLivestockSlot>): Int =
    slots.sumOf { it.storedOutput }

fun livestockStructureCapacity(type: FarmLivestockType, slots: List<FarmLivestockSlot>): Int =
    occupiedLivestockCount(slots) * type.perAnimalStorageCap

fun isLivestockStructureFull(type: FarmLivestockType, slots: List<FarmLivestockSlot>): Boolean {
    val occupied = slots.filter { it.occupied }
    return occupied.isNotEmpty() && occupied.all { it.storedOutput >= type.perAnimalStorageCap }
}

fun livestockNeedsFeed(slot: FarmLivestockSlot, now: Long = System.currentTimeMillis()): Boolean {
    if (!slot.occupied) return false
    val lastFedAt = slot.lastFedAt ?: return false
    return now >= lastFedAt + LIVESTOCK_FEED_INTERVAL_MS
}

fun hungryLivestockCount(slots: List<FarmLivestockSlot>, now: Long = System.currentTimeMillis()): Int =
    slots.count { livestockNeedsFeed(it, now) }

fun nextLivestockFeedDueAt(slots: List<FarmLivestockSlot>): Long? =
    slots.filter { it.occupied && it.lastFedAt != null }
        .minOfOrNull { checkNotNull(it.lastFedAt) + LIVESTOCK_FEED_INTERVAL_MS }

data class FarmTradeItemDefinition(
    val inventoryId: String,
    val displayText: TamaLocalizedText,
    val sellPrice: Int,
    val assetPath: String
)

object FarmTradeItemCatalog {
    private const val ROTTEN_CROP_ASSET_PATH = "farm/Others/rotten_crop.png"

    private val produceDefinitions = mapOf(
        "produce_milk" to FarmTradeItemDefinition(
            inventoryId = "produce_milk",
            displayText = TamaLocalizedText("Milk Bottle", "Botella de leche"),
            sellPrice = FarmLivestockType.BARN.productSellPrice,
            assetPath = FarmLivestockType.BARN.productAssetPath
        ),
        "produce_egg" to FarmTradeItemDefinition(
            inventoryId = "produce_egg",
            displayText = TamaLocalizedText("Egg", "Huevo"),
            sellPrice = FarmLivestockType.COOP.productSellPrice,
            assetPath = FarmLivestockType.COOP.productAssetPath
        )
    )

    fun definitionForInventoryId(inventoryId: String): FarmTradeItemDefinition? {
        produceDefinitions[inventoryId]?.let { return it }
        if (!inventoryId.startsWith("crop_")) return null
        val cropId = inventoryId.removePrefix("crop_")
        val cropInfo = CropDefinitions.CROPS[cropId] ?: return null
        return FarmTradeItemDefinition(
            inventoryId = inventoryId,
            displayText = cropDisplayText(cropId),
            sellPrice = cropInfo.sellPrice,
            assetPath = "farm/Crops/stage_final/$cropId.png"
        )
    }

    fun isTradeItem(inventoryId: String): Boolean = definitionForInventoryId(inventoryId) != null

    fun allDefinitions(): List<FarmTradeItemDefinition> {
        val cropDefinitions = CropDefinitions.CROPS.keys
            .sorted()
            .mapNotNull { cropId -> definitionForInventoryId("crop_$cropId") }
        return cropDefinitions + produceDefinitions.values.sortedBy { it.inventoryId }
    }

    fun isCompostableCropItem(inventoryId: String): Boolean {
        if (inventoryId == "rotten_crop") return true
        if (!inventoryId.startsWith("crop_")) return false
        val cropId = inventoryId.removePrefix("crop_")
        return cropId != "rotten_crop" && CropDefinitions.CROPS.containsKey(cropId)
    }

    fun displayText(inventoryId: String): TamaLocalizedText? = definitionForInventoryId(inventoryId)?.displayText

    fun displayName(inventoryId: String, locale: Locale): String =
        definitionForInventoryId(inventoryId)?.displayText?.resolve(locale)
            ?: inventoryId.removePrefix("crop_").replace('_', ' ').replaceFirstChar { it.titlecase(locale) }

    fun sellPrice(inventoryId: String): Int = definitionForInventoryId(inventoryId)?.sellPrice ?: 0

    fun assetPath(inventoryId: String): String? = definitionForInventoryId(inventoryId)?.assetPath

    fun compostableAssetPath(inventoryId: String): String? = when (inventoryId) {
        "rotten_crop" -> ROTTEN_CROP_ASSET_PATH
        else -> assetPath(inventoryId)
    }
}

object FarmShopCatalog {
    const val FERTILIZER_BUY_PRICE = 50
    const val FUEL_BUCKET_BUY_BATCH_SIZE = 50

    fun materialBuyPrice(inventoryId: String): Int = when (inventoryId) {
        "fertilizer" -> FERTILIZER_BUY_PRICE
        FARM_FUEL_BUCKET_ID -> 1
        "water" -> 5
        else -> 5
    }

    fun fuelBucketBatchPrice(): Int = materialBuyPrice(FARM_FUEL_BUCKET_ID) * FUEL_BUCKET_BUY_BATCH_SIZE
}
