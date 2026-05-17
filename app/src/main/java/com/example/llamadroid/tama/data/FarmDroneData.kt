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
const val FARM_PLANTING_DRONE_FUEL_COST = 10
const val FARM_HARVESTING_DRONE_FUEL_COST = 5
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
            val harvested = runHarvesterDroneTick(simulatedTiles, harvester)
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

fun advanceFarmTileCrop(tile: FarmTile, now: Long): FarmTile {
    val crop = tile.crop ?: return tile
    if (crop.isDecayed) return tile
    val definition = CropDefinitions.CROPS[crop.type] ?: return tile
    var currentStage = crop.stage
    var lastUpdate = crop.lastStageUpdateTime
    var changed = false
    var decayed = false

    while (currentStage < 3) {
        val baseTime = definition.stageTimes[currentStage]
        val timeToNext = if (crop.isFertilized) baseTime / 2 else baseTime
        if (now < lastUpdate + timeToNext) break
        lastUpdate += timeToNext
        currentStage++
        changed = true
    }

    if (currentStage == 3 && now >= lastUpdate + 15L * 60L * 60L * 1000L) {
        decayed = true
        changed = true
    }

    return if (changed) {
        tile.copy(
            crop = crop.copy(
                stage = currentStage,
                lastStageUpdateTime = lastUpdate,
                isDecayed = decayed
            )
        )
    } else {
        tile
    }
}

private data class HarvesterTickResult(
    val tiles: List<FarmTile>,
    val harvester: HarvesterDroneState,
    val harvestedTileIds: List<Int>
)

private fun runHarvesterDroneTick(
    tiles: List<FarmTile>,
    harvester: HarvesterDroneState
): HarvesterTickResult {
    var current = harvester.copy(statusKey = null)
    val updatedTiles = tiles.toMutableList()
    val harvestedIds = mutableListOf<Int>()

    for (index in updatedTiles.indices) {
        if (current.fuel < FARM_HARVESTING_DRONE_FUEL_COST) {
            current = current.copy(enabled = false, statusKey = "fuel_empty")
            break
        }
        val tile = updatedTiles[index]
        val crop = tile.crop ?: continue
        if (crop.stage < 3) continue
        if (!harvesterAllowsCrop(current, crop.type)) continue

        val inventoryId = if (crop.isDecayed) "rotten_crop" else "crop_${crop.type}"
        val quantity = if (crop.isDecayed) 1 else if (crop.isFertilized) 2 else 1
        current = current.copy(
            fuel = current.fuel - FARM_HARVESTING_DRONE_FUEL_COST,
            storage = addDroneStoredCrop(current.storage, inventoryId, quantity)
        )
        updatedTiles[index] = tile.copy(status = TileStatus.SOIL, crop = null, lastWateredTime = null)
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

    for (index in updatedTiles.indices) {
        val tile = updatedTiles[index]
        if (tile.crop != null) continue
        val emptySinceAt = emptySince[tile.id] ?: now
        if (now < emptySinceAt + FARM_PLANTING_DRONE_EMPTY_WAIT_MS) continue

        val seed = current.seeds.firstOrNull { it.quantity > 0 && CropDefinitions.CROPS.containsKey(it.cropId) }
        val hoe = current.hoe
        val wateringCan = current.wateringCan
        val stopKey = when {
            current.fuel < FARM_PLANTING_DRONE_FUEL_COST -> "fuel_empty"
            hoe == null || hoe.durability <= 0 -> "hoe_missing"
            wateringCan == null || wateringCan.durability <= 0 -> "watering_can_missing"
            current.water <= 0 -> "water_empty"
            seed == null -> "seeds_empty"
            else -> null
        }
        if (stopKey != null || seed == null || hoe == null || wateringCan == null) {
            current = current.copy(enabled = false, statusKey = stopKey)
            break
        }

        val updatedHoe = hoe.copy(durability = hoe.durability - 1)
        val updatedWateringCan = wateringCan.copy(durability = wateringCan.durability - 1)
        val usesFertilizer = current.fertilizer > 0
        current = current.copy(
            fuel = current.fuel - FARM_PLANTING_DRONE_FUEL_COST,
            water = current.water - 1,
            fertilizer = if (usesFertilizer) current.fertilizer - 1 else current.fertilizer,
            hoe = updatedHoe.takeIf { it.durability > 0 },
            wateringCan = updatedWateringCan.takeIf { it.durability > 0 },
            seeds = consumeDroneSeed(current.seeds, seed.cropId),
            enabled = updatedHoe.durability > 0 && updatedWateringCan.durability > 0,
            statusKey = when {
                updatedHoe.durability <= 0 -> "hoe_broken"
                updatedWateringCan.durability <= 0 -> "watering_can_broken"
                else -> null
            }
        )
        updatedTiles[index] = tile.copy(
            status = TileStatus.WET_FARMLAND,
            crop = PlantedCrop(
                type = seed.cropId,
                plantedTime = now,
                lastStageUpdateTime = now,
                isFertilized = usesFertilizer
            ),
            lastWateredTime = now
        )
        emptySince.remove(tile.id)
        if (!current.enabled) break
    }

    return PlantingTickResult(updatedTiles, current.copy(emptySinceByTile = emptySince))
}

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
