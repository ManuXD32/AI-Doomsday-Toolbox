package com.example.llamadroid.tama.data

import kotlinx.serialization.Serializable

const val FARM_PLANTING_DRONE_ID = "planting_drone"
const val FARM_HARVESTING_DRONE_ID = "harvesting_drone"
const val FARM_PLANTING_DRONE_FUEL_UPGRADE_ID = "planting_drone_fuel_upgrade"
const val FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID = "harvesting_drone_fuel_upgrade"
const val FARM_FUEL_BUCKET_ID = "fuel_bucket"
const val FARM_DRONE_BUY_PRICE = 10_000
const val FARM_DRONE_FUEL_CAPACITY = 500
const val FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL = 2
// Fuel is one coin per unit (see FarmShopCatalog); a plant + harvest cycle costs 6 fuel plus 1 water, which
// stays profitable on the cheapest crop at full plots (see docs/tama-maintainer-reference.md, farm economy).
const val FARM_PLANTING_DRONE_FUEL_COST = 4
const val FARM_HARVESTING_DRONE_FUEL_COST = 2
const val FARM_PLANTING_DRONE_WATER_FUEL_COST = 2
const val FARM_PLANTING_DRONE_EMPTY_WAIT_MS = 5L * 60L * 1000L
const val FARM_DRONE_OFFLINE_PLAN_MS = 48L * 60L * 60L * 1000L
const val FARM_TOOL_DURABILITY_CAP = 500
const val FARM_TOOL_REPAIR_AMOUNT = 100

fun farmDroneFuelCapacityForUpgradeLevel(level: Int): Int = when (level.coerceIn(0, FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL)) {
    0 -> FARM_DRONE_FUEL_CAPACITY
    1 -> 1_000
    else -> 2_000
}

fun farmDroneFuelTransferAmountForUpgradeLevel(level: Int): Int = when (level.coerceIn(0, FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL)) {
    0 -> 50
    1 -> 100
    else -> 200
}

fun farmDroneFuelUpgradeCostForLevel(level: Int): Int? = when (level.coerceIn(0, FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL)) {
    0 -> 3_000
    1 -> 6_000
    else -> null
}

fun farmDroneIdForFuelUpgradeId(upgradeId: String): String? = when (upgradeId) {
    FARM_PLANTING_DRONE_FUEL_UPGRADE_ID -> FARM_PLANTING_DRONE_ID
    FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID -> FARM_HARVESTING_DRONE_ID
    else -> null
}

fun farmToolFamilyId(itemId: String): String? = when (itemId) {
    "hoe", "hoe_starter" -> "hoe"
    "watering_can", "watering_can_starter" -> "watering_can"
    "axe", "axe_starter" -> "axe"
    "pickaxe", "pickaxe_starter" -> "pickaxe"
    else -> null
}

fun farmToolTotalDurability(inventory: List<InventoryItem>, familyId: String): Int =
    inventory.filter { farmToolFamilyId(it.id) == familyId }
        .sumOf { it.durability ?: it.maxDurability ?: FARM_TOOL_REPAIR_AMOUNT }
        .coerceIn(0, FARM_TOOL_DURABILITY_CAP)

@Serializable
data class DroneToolState(
    val id: String,
    val name: String,
    val durability: Int,
    val maxDurability: Int
)

@Serializable
data class DroneSeedStock(
    val cropId: String,
    val quantity: Int
)

@Serializable
data class DroneStoredCrop(
    val inventoryId: String,
    val quantity: Int
)

@Serializable
data class PlantingDroneState(
    val enabled: Boolean = false,
    val fuel: Int = 0,
    val fuelUpgradeLevel: Int = 0,
    val hoe: DroneToolState? = null,
    val wateringCan: DroneToolState? = null,
    val water: Int = 0,
    val fertilizer: Int = 0,
    val seeds: List<DroneSeedStock> = emptyList(),
    val emptySinceByTile: Map<Int, Long> = emptyMap(),
    val lastUpdatedAt: Long = 0L,
    val statusKey: String? = null
)

@Serializable
enum class HarvesterDroneMode {
    BLACKLIST,
    WHITELIST
}

@Serializable
data class HarvesterDroneState(
    val enabled: Boolean = false,
    val fuel: Int = 0,
    val fuelUpgradeLevel: Int = 0,
    val mode: HarvesterDroneMode = HarvesterDroneMode.BLACKLIST,
    val cropFilter: Set<String> = emptySet(),
    val storage: List<DroneStoredCrop> = emptyList(),
    val lastUpdatedAt: Long = 0L,
    val statusKey: String? = null
)

data class FarmDroneSimulationResult(
    val tiles: List<FarmTile>,
    val plantingDrone: PlantingDroneState,
    val harvesterDrone: HarvesterDroneState
)

fun InventoryItem.toDroneToolState(): DroneToolState =
    DroneToolState(
        id = id,
        name = name,
        durability = durability ?: maxDurability ?: 100,
        maxDurability = maxDurability ?: durability ?: 100
    )

fun simulateFarmDrones(
    tiles: List<FarmTile>,
    plantingDrone: PlantingDroneState,
    harvesterDrone: HarvesterDroneState,
    now: Long
): FarmDroneSimulationResult {
    val activeStarts = listOfNotNull(
        plantingDrone.lastUpdatedAt.takeIf { plantingDrone.enabled && it > 0L },
        harvesterDrone.lastUpdatedAt.takeIf { harvesterDrone.enabled && it > 0L }
    )
    if (activeStarts.isEmpty()) {
        return FarmDroneSimulationResult(
            tiles = tiles.map { advanceFarmTileCrop(it, now) },
            plantingDrone = plantingDrone.copy(lastUpdatedAt = now),
            harvesterDrone = harvesterDrone.copy(lastUpdatedAt = now)
        )
    }

    val start = activeStarts.minOrNull()?.coerceAtMost(now) ?: now
    val simulationEnd = minOf(now, start + FARM_DRONE_OFFLINE_PLAN_MS)
    var simulatedTiles = tiles.map { advanceFarmTileCrop(it, start) }
    var planter = plantingDrone
    var harvester = harvesterDrone
    var tick = start

    while (tick <= simulationEnd) {
        simulatedTiles = simulatedTiles.map { advanceFarmTileCrop(it, tick) }
        if (harvester.enabled && tick >= harvester.lastUpdatedAt) {
            val harvested = runHarvesterDroneTick(simulatedTiles, harvester, tick)
            simulatedTiles = harvested.tiles
            harvester = harvested.harvester
            if (harvested.harvestedTileIds.isNotEmpty()) {
                planter = planter.copy(
                    emptySinceByTile = planter.emptySinceByTile + harvested.harvestedTileIds.associateWith { tick }
                )
            }
        }
        if (planter.enabled && tick >= planter.lastUpdatedAt) {
            val planted = runPlantingDroneTick(simulatedTiles, planter, tick)
            simulatedTiles = planted.tiles
            planter = planted.planter
        }
        tick += 60_000L
    }

    return FarmDroneSimulationResult(
        tiles = simulatedTiles.map { advanceFarmTileCrop(it, now) },
        plantingDrone = planter.copy(lastUpdatedAt = now),
        harvesterDrone = harvester.copy(lastUpdatedAt = now)
    )
}

/**
 * Advances a tile to [now]: crop growth (slowed to [FARM_DRY_GROWTH_PERCENT] percent while the plot is
 * dry), rot, and the wet to dry status flip. Idempotent for a fixed [now]; see FarmMoisture.kt for the model.
 */
fun advanceFarmTileCrop(tile: FarmTile, now: Long): FarmTile {
    val current = farmNormalizeMoisture(tile, now)
    val crop = current.crop ?: return current
    if (crop.isDecayed) return current
    val definition = CropDefinitions.CROPS[crop.type] ?: return current
    val virtualNow = farmVirtualTime(now, farmCropDryFrom(current, crop))
    var currentStage = crop.stage
    var lastUpdate = crop.lastStageUpdateTime
    var changed = false
    var decayed = false

    while (currentStage < 3) {
        val timeToNext = farmStageDurationMs(definition, crop.isFertilized, currentStage)
        if (virtualNow < lastUpdate + timeToNext) break
        lastUpdate += timeToNext
        currentStage++
        changed = true
    }

    if (currentStage == 3 && virtualNow >= lastUpdate + FARM_ROT_AFTER_RIPE_MS) {
        decayed = true
        changed = true
    }

    return if (changed) {
        current.copy(
            crop = crop.copy(
                stage = currentStage,
                lastStageUpdateTime = lastUpdate,
                isDecayed = decayed
            )
        )
    } else {
        current
    }
}

private data class HarvesterTickResult(
    val tiles: List<FarmTile>,
    val harvester: HarvesterDroneState,
    val harvestedTileIds: List<Int>
)

private fun runHarvesterDroneTick(
    tiles: List<FarmTile>,
    harvester: HarvesterDroneState,
    tick: Long
): HarvesterTickResult {
    var current = harvester.copy(statusKey = null)
    val updatedTiles = tiles.toMutableList()
    val harvestedIds = mutableListOf<Int>()

    for (index in farmDroneTileWorkOrder(updatedTiles)) {
        if (current.fuel < FARM_HARVESTING_DRONE_FUEL_COST) {
            current = current.copy(enabled = false, statusKey = "fuel_empty")
            break
        }
        val tile = updatedTiles[index]
        val crop = tile.crop ?: continue
        if (crop.stage < 3) continue
        // A crop whose last stage change lies after this tick was stored in a later state than the replay.
        if (crop.lastStageUpdateTime > tick) continue
        if (!harvesterAllowsCrop(current, crop.type)) continue

        val inventoryId = if (crop.isDecayed) "rotten_crop" else "crop_${crop.type}"
        val quantity = if (crop.isDecayed) 1 else farmHarvestYield(crop, farmHarvestOutcome(0L, tile.id, crop))
        current = current.copy(
            fuel = current.fuel - FARM_HARVESTING_DRONE_FUEL_COST,
            storage = addDroneStoredCrop(current.storage, inventoryId, quantity)
        )
        updatedTiles[index] = tile.copy(status = TileStatus.FARMLAND, crop = null, lastWateredTime = null)
        harvestedIds += tile.id
    }

    return HarvesterTickResult(updatedTiles, current, harvestedIds)
}

private data class PlantingTickResult(
    val tiles: List<FarmTile>,
    val planter: PlantingDroneState
)

private fun runPlantingDroneTick(
    tiles: List<FarmTile>,
    planter: PlantingDroneState,
    now: Long
): PlantingTickResult {
    var current = planter.copy(statusKey = null)
    val updatedTiles = tiles.toMutableList()
    var emptySince = current.emptySinceByTile.toMutableMap()

    updatedTiles.forEach { tile ->
        if (tile.crop == null) {
            emptySince.putIfAbsent(tile.id, now)
        } else {
            emptySince.remove(tile.id)
        }
    }

    // Pass 1: re-water growing crops whose plot dried out (the drone's own water stock, can durability, a little fuel).
    for (index in farmDroneTileWorkOrder(updatedTiles)) {
        val tile = updatedTiles[index]
        val crop = tile.crop ?: continue
        if (!crop.moistureTracked || crop.isDecayed || crop.stage >= 3 || tile.isWet(now)) continue
        val can = current.wateringCan
        if (can == null || can.durability <= 0 || current.water <= 0 ||
            current.fuel < FARM_PLANTING_DRONE_WATER_FUEL_COST) break
        val usedCan = can.copy(durability = can.durability - 1)
        current = current.copy(
            fuel = current.fuel - FARM_PLANTING_DRONE_WATER_FUEL_COST,
            water = current.water - 1,
            wateringCan = usedCan.takeIf { it.durability > 0 },
            enabled = usedCan.durability > 0,
            statusKey = if (usedCan.durability <= 0) "watering_can_broken" else null
        )
        updatedTiles[index] = farmWaterTile(tile, now)
        if (!current.enabled) break
    }

    if (!current.enabled) return PlantingTickResult(updatedTiles, current.copy(emptySinceByTile = emptySince))

    for (index in farmDroneTileWorkOrder(updatedTiles)) {
        val tile = updatedTiles[index]
        if (tile.crop != null || tile.isBlockedByObstacle()) continue // drones never work obstacle plots
        val emptySinceAt = emptySince[tile.id] ?: now
        if (now < emptySinceAt + FARM_PLANTING_DRONE_EMPTY_WAIT_MS) continue

        val seed = current.seeds.firstOrNull { it.quantity > 0 && CropDefinitions.CROPS.containsKey(it.cropId) }
        val hoe = current.hoe
        val wateringCan = current.wateringCan
        val needsHoe = tile.status == TileStatus.SOIL
        val needsWater = !tile.isWet(now)
        val stopKey = when {
            current.fuel < FARM_PLANTING_DRONE_FUEL_COST -> "fuel_empty"
            needsHoe && (hoe == null || hoe.durability <= 0) -> "hoe_missing"
            needsWater && (wateringCan == null || wateringCan.durability <= 0) -> "watering_can_missing"
            needsWater && current.water <= 0 -> "water_empty"
            seed == null -> "seeds_empty"
            else -> null
        }
        if (stopKey != null || seed == null) {
            current = current.copy(enabled = false, statusKey = stopKey)
            break
        }

        val updatedHoe = if (needsHoe) hoe?.let { it.copy(durability = it.durability - 1) } else hoe
        val updatedWateringCan = if (needsWater) wateringCan?.let { it.copy(durability = it.durability - 1) } else wateringCan
        val usesFertilizer = current.fertilizer > 0
        val hoeBroken = needsHoe && updatedHoe != null && updatedHoe.durability <= 0
        val canBroken = needsWater && updatedWateringCan != null && updatedWateringCan.durability <= 0
        current = current.copy(
            fuel = current.fuel - FARM_PLANTING_DRONE_FUEL_COST,
            water = if (needsWater) current.water - 1 else current.water,
            fertilizer = if (usesFertilizer) current.fertilizer - 1 else current.fertilizer,
            hoe = if (needsHoe) updatedHoe?.takeIf { it.durability > 0 } else hoe,
            wateringCan = if (needsWater) updatedWateringCan?.takeIf { it.durability > 0 } else wateringCan,
            seeds = consumeDroneSeed(current.seeds, seed.cropId),
            enabled = !hoeBroken && !canBroken,
            statusKey = when {
                hoeBroken -> "hoe_broken"
                canBroken -> "watering_can_broken"
                else -> null
            }
        )
        val prepared = if (needsWater) farmWaterTile(tile, now) else tile
        updatedTiles[index] = prepared.copy(
            status = if (prepared.status == TileStatus.SOIL) TileStatus.FARMLAND else prepared.status,
            crop = PlantedCrop(
                type = seed.cropId,
                plantedTime = now,
                lastStageUpdateTime = now,
                isFertilized = usesFertilizer,
                moistureTracked = true
            )
        )
        emptySince.remove(tile.id)
        if (!current.enabled) break
    }

    return PlantingTickResult(updatedTiles, current.copy(emptySinceByTile = emptySince))
}

private fun farmDroneTileWorkOrder(tiles: List<FarmTile>): List<Int> =
    tiles.indices.sortedWith(
        compareBy<Int>(
            { tiles[it].id.floorMod(FARM_TILES_PER_PAGE) },
            { farmPageForTileId(tiles[it].id) },
            { tiles[it].id }
        )
    )

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

private fun harvesterAllowsCrop(harvester: HarvesterDroneState, cropId: String): Boolean {
    val listed = cropId in harvester.cropFilter
    return when (harvester.mode) {
        HarvesterDroneMode.BLACKLIST -> !listed
        HarvesterDroneMode.WHITELIST -> listed
    }
}

private fun consumeDroneSeed(seeds: List<DroneSeedStock>, cropId: String): List<DroneSeedStock> =
    seeds.mapNotNull { seed ->
        if (seed.cropId == cropId) {
            val updatedQuantity = seed.quantity - 1
            if (updatedQuantity > 0) seed.copy(quantity = updatedQuantity) else null
        } else {
            seed
        }
    }

fun addDroneSeed(seeds: List<DroneSeedStock>, cropId: String, quantity: Int): List<DroneSeedStock> {
    if (quantity <= 0) return seeds
    val index = seeds.indexOfFirst { it.cropId == cropId }
    return if (index >= 0) {
        seeds.toMutableList().also { current ->
            val existing = current[index]
            current[index] = existing.copy(quantity = existing.quantity + quantity)
        }
    } else {
        seeds + DroneSeedStock(cropId, quantity)
    }
}

fun addDroneStoredCrop(storage: List<DroneStoredCrop>, inventoryId: String, quantity: Int): List<DroneStoredCrop> {
    if (quantity <= 0) return storage
    val index = storage.indexOfFirst { it.inventoryId == inventoryId }
    return if (index >= 0) {
        storage.toMutableList().also { current ->
            val existing = current[index]
            current[index] = existing.copy(quantity = existing.quantity + quantity)
        }
    } else {
        storage + DroneStoredCrop(inventoryId, quantity)
    }
}
