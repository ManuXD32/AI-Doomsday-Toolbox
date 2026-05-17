package com.example.llamadroid.tama.data

import kotlinx.serialization.Serializable

const val FARMLAND_UPGRADE_ID = "farmland"
const val FARM_BASE_TILE_COUNT = 9
const val FARM_TILES_PER_PAGE = 9
const val FARM_MAX_FARMLAND_LEVEL = 2
const val FARM_MAX_FARM_PAGES = 3

fun farmPageCountForFarmlandLevel(level: Int): Int =
    (1 + level.coerceIn(0, FARM_MAX_FARMLAND_LEVEL)).coerceIn(1, FARM_MAX_FARM_PAGES)

fun farmTileCountForFarmlandLevel(level: Int): Int =
    farmPageCountForFarmlandLevel(level) * FARM_TILES_PER_PAGE

fun farmlandUpgradeCostForLevel(level: Int): Int? = when (level.coerceIn(0, FARM_MAX_FARMLAND_LEVEL)) {
    0 -> 15_000
    1 -> 35_000
    else -> null
}

fun farmPageForTileId(tileId: Int): Int =
    (tileId / FARM_TILES_PER_PAGE).coerceIn(0, FARM_MAX_FARM_PAGES - 1)

fun farmTileIdsForPage(pageIndex: Int): IntRange {
    val safePage = pageIndex.coerceIn(0, FARM_MAX_FARM_PAGES - 1)
    val start = safePage * FARM_TILES_PER_PAGE
    return start until (start + FARM_TILES_PER_PAGE)
}

/**
 * Represents a single tile in the farm grid.
 */
@Serializable
data class FarmTile(
    val id: Int,
    val status: TileStatus = TileStatus.SOIL,
    val crop: PlantedCrop? = null,
    val lastWateredTime: Long? = null
)

enum class TileStatus {
    SOIL,       // Initial state
    FARMLAND,   // After using a hoe
    WET_FARMLAND // After watering
}

/**
 * Data for a crop planted on a tile.
 */
@Serializable
data class PlantedCrop(
    val type: String, // e.g., "carrot", "wheat"
    val stage: Int = 0, // 0: Seed, 1: Stage 1, 2: Stage 2, 3: Final
    val plantedTime: Long,
    val lastStageUpdateTime: Long,
    val isFertilized: Boolean = false,
    val isDecayed: Boolean = false
)

@Serializable
enum class ComposterSlotState {
    EMPTY,
    PROCESSING,
    READY
}

@Serializable
data class ComposterSlot(
    val state: ComposterSlotState = ComposterSlotState.EMPTY,
    val startedAt: Long? = null,
    val readyAt: Long? = null,
    val inputItemId: String? = null
)

@Serializable
data class WellSlot(
    val hasWater: Boolean = false,
    val cycleStartedAt: Long? = null
)

@Serializable
data class WellUpgradeState(
    val speedLevel: Int = 0,
    val slots: List<WellSlot> = emptyList()
)

/**
 * Static definitions for crop types and their properties.
 */
object CropDefinitions {
    data class CropInfo(
        val name: String,
        val seedPrice: Int,
        val sellPrice: Int,
        val stageTimes: List<Long>, // Time in milliseconds for each stage
        val yieldModifier: Float
    )

    private const val HOUR = 3600000L

    val CROPS = mapOf(
        "wheat" to CropInfo("Wheat", 10, 25, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.0f),
        "rice" to CropInfo("Rice", 12, 30, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.1f),
        "carrot" to CropInfo("Carrot", 15, 40, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.2f),
        "tomato" to CropInfo("Tomato", 20, 55, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.3f),
        "corn" to CropInfo("Corn", 25, 70, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.4f),
        "strawberry" to CropInfo("Strawberry", 30, 85, listOf(1 * HOUR, 2 * HOUR, 3 * HOUR), 1.5f),
        "daisy" to CropInfo("Daisy", 15, 35, listOf(45 * 60000L, 90 * 60000L, 2 * HOUR), 1.0f),
        "sunflower" to CropInfo("Sunflower", 25, 65, listOf(45 * 60000L, 90 * 60000L, 2 * HOUR), 1.2f),
        "rose" to CropInfo("Rose", 40, 120, listOf(2 * HOUR, 4 * HOUR, 6 * HOUR), 2.0f),
        "tullip" to CropInfo("Tullip", 35, 100, listOf(2 * HOUR, 4 * HOUR, 6 * HOUR), 1.8f),
        "melon" to CropInfo("Melon", 50, 150, listOf(3 * HOUR, 6 * HOUR, 9 * HOUR), 2.5f),
        "pumpkin" to CropInfo("Pumpkin", 45, 140, listOf(3 * HOUR, 6 * HOUR, 9 * HOUR), 2.2f)
    )
}

/**
 * Enhanced inventory item.
 */
@Serializable
data class InventoryItem(
    val id: String,
    val name: String,
    val type: ItemType,
    val quantity: Int = 1,
    val durability: Int? = null, // For tools
    val maxDurability: Int? = null
)

@Serializable
enum class ItemType(val emoji: String) {
    FOOD("🍖"),
    POTION("🧪"),
    TOY("🎮"),
    MEDICINE("💊"),
    SEED("🌱"),
    CROP("🌾"),
    DECORATION("🖼️"),
    WEAPON("⚔️"),
    ARMOR("🛡️"),
    ACCESSORY("💍"),
    QUEST_ITEM("📜"),
    TREASURE("💎"),
    RECIPE("📜"),
    TOOL("🛠️"),
    MATERIAL("📦")
}
