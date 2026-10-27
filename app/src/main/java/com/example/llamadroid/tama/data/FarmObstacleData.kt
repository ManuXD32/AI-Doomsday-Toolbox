package com.example.llamadroid.tama.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.random.Random

/*
 * Farm obstacles (pure rules, no Android types).
 *
 * Stumps, rocks and weeds occupy an idle untilled plot until cleared. Weeds clear by hand (or a hoe tilling over
 * them), a stump needs an owned axe and a rock an owned pickaxe; clearing a stump or rock yields wood or stone.
 * Wood and stone are also the materials for well/composter levels, plot pages and barn/coop animals, and they
 * can be bought (always, so nothing soft-locks) and sold back for less in the farm store.
 *
 * Persistence: the obstacle is the nullable `tama_farm_tiles.obstacle` column; the daily-roll bookkeeping lives
 * in a pet-scoped `tama_farm_upgrades` row ([FARM_OBSTACLE_STATE_ID]) so it already travels in the transfer bundle.
 */

const val FARM_OBSTACLE_STATE_ID = "obstacle_state"

/** Percent chance an idle plot grows an obstacle during the once-per-day roll. */
const val FARM_OBSTACLE_DAILY_CHANCE_PERCENT = 12
/** Share of new obstacles (out of 100): weeds 60, stump 20 (the remaining 20 are rocks). */
const val FARM_OBSTACLE_WEEDS_SHARE = 60
const val FARM_OBSTACLE_STUMP_SHARE = 20
/** Hard cap of obstacles on one 3x3 page, applied to daily spawns and page seeding alike. */
const val FARM_OBSTACLES_MAX_PER_PAGE = 2

const val FARM_OBSTACLE_MIN_YIELD = 2
const val FARM_OBSTACLE_MAX_YIELD = 4
/** Tool durability one stump/rock clear costs (same unit as one hoe or watering-can use). */
const val FARM_OBSTACLE_TOOL_DURABILITY = 1

/** Tool families whose durability [TamaGameEngine.consumeFarmToolDurability] may spend. */
val FARM_CONSUMABLE_TOOL_FAMILIES = setOf("hoe", "watering_can", "axe", "pickaxe")

const val FARM_WOOD_ID = "wood"
const val FARM_STONE_ID = "stone"

@Serializable
enum class FarmObstacle(
    /** Tool family needed to clear it, or null when it clears by hand. */
    val toolFamily: String?,
    /** Material id granted when it is cleared, or null for none. */
    val yieldItemId: String?,
    val assetPath: String
) {
    STUMP("axe", FARM_WOOD_ID, "farm/Others/obstacle_stump.png"),
    ROCK("pickaxe", FARM_STONE_ID, "farm/Others/obstacle_rock.png"),
    WEEDS(null, null, "farm/Others/obstacle_weeds.png");

    /** Failure code thrown when the matching tool is not owned ("axe_required", "pickaxe_required"). */
    val missingToolCode: String? get() = toolFamily?.let { "${it}_required" }

    companion object {
        fun fromName(name: String?): FarmObstacle? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/** An idle, untilled, empty plot: the only place an obstacle may exist or spawn. */
fun FarmTile.isIdleForObstacle(): Boolean = status == TileStatus.SOIL && crop == null

/** Plots with an obstacle cannot be tilled (except weeds, which a hoe clears), planted or driven over by drones. */
fun FarmTile.isBlockedByObstacle(): Boolean = obstacle != null

/** Wood/stone a stump/rock clear yields (2..4); [roll] is any integer (e.g. a random draw). */
fun farmObstacleYield(roll: Int): Int =
    FARM_OBSTACLE_MIN_YIELD + Math.floorMod(roll, FARM_OBSTACLE_MAX_YIELD - FARM_OBSTACLE_MIN_YIELD + 1)

// ---------------------------------------------------------------------------------------------------------------
// Spawning
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class FarmObstacleState(
    /** ISO local date of the last daily roll (null until the first farm update after the obstacle update). */
    val lastSpawnDay: String? = null,
    /** Page indexes that already got their starting stump + rock. */
    val seededPages: Set<Int> = emptySet()
)

object FarmObstacles {
    /**
     * Applies page seeding and the once-per-day roll to [tiles] (already advanced to now). Never touches tilled or
     * planted plots, never exceeds [FARM_OBSTACLES_MAX_PER_PAGE] per page, rolls at most once per local day (no
     * catch-up for days the app was closed), and is deterministic for a given [petSeed] and [today].
     */
    fun plan(state: FarmObstacleState, tiles: List<FarmTile>, today: LocalDate, petSeed: Long): Pair<List<FarmTile>, FarmObstacleState> {
        val byId = tiles.associateBy { it.id }.toMutableMap()
        var seeded = state.seededPages
        val pages = tiles.map { farmPageForTileId(it.id) }.distinct().sorted()

        // 1. Starting obstacles: a new page (or an existing farm right after the update) gets one stump + one rock.
        for (page in pages) {
            if (page in seeded) continue
            val ids = farmTileIdsForPage(page).filter { byId.containsKey(it) }
            if (ids.size < FARM_TILES_PER_PAGE) continue // partial page; wait until it is complete
            val existing = ids.count { byId.getValue(it).obstacle != null }
            val idle = ids.filter { byId.getValue(it).isIdleForObstacle() && byId.getValue(it).obstacle == null }
            if (existing >= FARM_OBSTACLES_MAX_PER_PAGE) {
                seeded = seeded + page
                continue
            }
            val needed = listOf(FarmObstacle.STUMP, FarmObstacle.ROCK).take(FARM_OBSTACLES_MAX_PER_PAGE - existing)
            if (idle.size < needed.size) continue // try again once plots are idle (nothing to place yet)
            val rng = Random(petSeed * 1_000_003L + page * 7919L + 17L)
            val picked = idle.shuffled(rng).take(needed.size)
            picked.zip(needed).forEach { (id, obstacle) -> byId[id] = byId.getValue(id).copy(obstacle = obstacle) }
            seeded = seeded + page
        }

        // 2. Daily roll. The first run after the update only records the day (seeding already gave obstacles).
        val todayKey = today.toString()
        var lastDay = state.lastSpawnDay
        if (lastDay == null) {
            lastDay = todayKey
        } else if (lastDay != todayKey) {
            lastDay = todayKey
            val rng = Random(petSeed * 31L + today.toEpochDay())
            for (page in pages) {
                val ids = farmTileIdsForPage(page).filter { byId.containsKey(it) }
                var count = ids.count { byId.getValue(it).obstacle != null }
                for (id in ids) {
                    if (count >= FARM_OBSTACLES_MAX_PER_PAGE) break
                    val tile = byId.getValue(id)
                    if (!tile.isIdleForObstacle() || tile.obstacle != null) continue
                    if (rng.nextInt(100) >= FARM_OBSTACLE_DAILY_CHANCE_PERCENT) continue
                    byId[id] = tile.copy(obstacle = rollKind(rng.nextInt(100)))
                    count++
                }
            }
        }

        val newState = FarmObstacleState(lastSpawnDay = lastDay, seededPages = seeded)
        return tiles.map { byId.getValue(it.id) } to newState
    }

    /** Maps a 0..99 roll to a kind: weeds 60, stump 20, rock 20. */
    fun rollKind(roll: Int): FarmObstacle = when {
        roll < FARM_OBSTACLE_WEEDS_SHARE -> FarmObstacle.WEEDS
        roll < FARM_OBSTACLE_WEEDS_SHARE + FARM_OBSTACLE_STUMP_SHARE -> FarmObstacle.STUMP
        else -> FarmObstacle.ROCK
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Material costs
// ---------------------------------------------------------------------------------------------------------------

/** Wood and stone an upgrade needs on top of its coin price. */
data class FarmMaterialCost(val wood: Int = 0, val stone: Int = 0) {
    val isFree: Boolean get() = wood <= 0 && stone <= 0

    /** What is still missing for a pet holding [haveWood] wood and [haveStone] stone. */
    fun missing(haveWood: Int, haveStone: Int): FarmMaterialCost =
        FarmMaterialCost((wood - haveWood).coerceAtLeast(0), (stone - haveStone).coerceAtLeast(0))

    companion object { val NONE = FarmMaterialCost() }
}

/** Material price list. Coin prices stay in their existing functions (`wellCapacityUpgradeCostForLevel`, ...). */
object FarmMaterialCosts {
    /** Well building. */
    val WELL_BUILD = FarmMaterialCost(wood = 6, stone = 4)
    /** Composter building. */
    val COMPOSTER_BUILD = FarmMaterialCost(wood = 8, stone = 6)

    /** Well capacity level [level] -> level + 1: wood 1 + level (2..9), stone (level + 1) / 2 (1..4). */
    fun wellCapacity(level: Int): FarmMaterialCost = tiered(level)
    /** Composter capacity level [level] -> level + 1: wood 2 + level (3..10), stone (level + 1) / 2 + 1 (2..5). */
    fun composterCapacity(level: Int): FarmMaterialCost {
        val l = level.coerceAtLeast(1)
        return FarmMaterialCost(wood = 2 + l, stone = (l + 1) / 2 + 1)
    }
    /** Well speed level [speedLevel] -> +1: stone only, 2 + 2 * speedLevel (2..8). */
    fun wellSpeed(speedLevel: Int): FarmMaterialCost = FarmMaterialCost(stone = 2 + 2 * speedLevel.coerceAtLeast(0))

    private fun tiered(level: Int): FarmMaterialCost {
        val l = level.coerceAtLeast(1)
        return FarmMaterialCost(wood = 1 + l, stone = (l + 1) / 2)
    }

    /** Farmland (extra 3x3 plot page) level [level] -> level + 1: 20 wood + 15 stone, then 40 + 30. */
    fun farmland(level: Int): FarmMaterialCost = when (level.coerceAtLeast(0)) {
        0 -> FarmMaterialCost(wood = 20, stone = 15)
        else -> FarmMaterialCost(wood = 40, stone = 30)
    }

    /** One more animal slot in the barn (4 wood + 2 stone) or coop (3 wood + 1 stone). */
    fun livestock(type: FarmLivestockType): FarmMaterialCost = when (type) {
        FarmLivestockType.BARN -> FarmMaterialCost(wood = 4, stone = 2)
        FarmLivestockType.COOP -> FarmMaterialCost(wood = 3, stone = 1)
    }

    fun owned(inventory: List<InventoryItem>, itemId: String): Int =
        inventory.filter { it.id == itemId }.sumOf { it.quantity.coerceAtLeast(0) }
}

/** Store prices for wood and stone: bought at a fixed price, sold back for less (never through the weekly market). */
object FarmMaterialPrices {
    const val WOOD_BUY = 12
    const val STONE_BUY = 15
    const val WOOD_SELL = 5
    const val STONE_SELL = 6

    fun isMaterial(id: String): Boolean = id == FARM_WOOD_ID || id == FARM_STONE_ID
    fun buyPrice(id: String): Int? = when (id) { FARM_WOOD_ID -> WOOD_BUY; FARM_STONE_ID -> STONE_BUY; else -> null }
    fun sellPrice(id: String): Int? = when (id) { FARM_WOOD_ID -> WOOD_SELL; FARM_STONE_ID -> STONE_SELL; else -> null }
}
