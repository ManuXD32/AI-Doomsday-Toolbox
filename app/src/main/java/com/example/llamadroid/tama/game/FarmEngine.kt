package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.example.llamadroid.tama.transfer.TamaTransferFreeze
import java.time.Instant
import java.time.ZoneId

class FarmEngine(
    private val repository: FarmRepository
) {
    /**
     * Update all farm elements based on current time.
     * This handles "offline" progress.
     *
     * Nothing advances while the pet's cycle is frozen ([cycleFrozen], or the process-wide
     * transfer freeze): [FarmRepository.shiftCycleTimestamps] moves every timestamp by the freeze
     * length on unfreeze, so skipping here loses and gains no time.
     *
     * Tiles are advanced exactly once. When a drone is bought, the drone simulation owns tile
     * growth (it replays [lastUpdate, now] interleaved with growth), so tiles must not be moved
     * to `now` beforehand or the replay would start from an already-rotten future state.
     * Everything is written in one Room transaction.
     */
    suspend fun updateFarm(
        petId: String,
        now: Long = System.currentTimeMillis(),
        cycleFrozen: Boolean = false
    ) = TamaActionGate.run {
        if (cycleFrozen || TamaTransferFreeze.frozen) return@run
        val tiles = repository.ensureUnlockedFarmTiles(petId)
        val storedUpgrades = repository.getUpgrades(petId)
        val changedUpgrades = LinkedHashMap<String, FarmUpgradeEntity>()
        storedUpgrades.forEach { upgrade ->
            val updated = updateUpgrade(upgrade, now)
            if (updated != upgrade) changedUpgrades[upgrade.type] = updated
        }

        val plantingUpgrade = storedUpgrades.firstOrNull { it.type == FARM_PLANTING_DRONE_ID }
        val harvesterUpgrade = storedUpgrades.firstOrNull { it.type == FARM_HARVESTING_DRONE_ID }
        // Rain: same per-pet seed as the weather chip. Without drones each shower is applied in time order to the
        // stored tiles; with drones the replay owns tile history, so only the shower falling right now is applied
        // to the simulated result (earlier showers inside the drone replay window are not back-dated).
        val weatherRow = storedUpgrades.firstOrNull { it.type == FARM_WEATHER_STATE_ID }
        val lastRainCheck = weatherRow?.lastProductionTime?.takeIf { it in 1..now } ?: now
        val waterings = farmRainWaterings(
            seed = farmWeatherSeed(petId),
            tiles = tiles.map { it.toRainTile() },
            lastCheckMs = lastRainCheck,
            nowMs = now
        )
        val advancedTiles = if (plantingUpgrade?.isPurchased != true && harvesterUpgrade?.isPurchased != true) {
            farmApplyRainWaterings(tiles, waterings).map { advanceFarmTileCrop(it, now) }
        } else {
            val plantingState = repository.decodePlantingDroneState(plantingUpgrade, now)
            val harvesterState = repository.decodeHarvesterDroneState(harvesterUpgrade, now)
            val result = simulateFarmDrones(tiles, plantingState, harvesterState, now)
            if (plantingUpgrade?.isPurchased == true && result.plantingDrone != plantingState) {
                changedUpgrades[FARM_PLANTING_DRONE_ID] = (changedUpgrades[FARM_PLANTING_DRONE_ID] ?: plantingUpgrade).copy(
                    isPurchased = true,
                    storedOutput = result.plantingDrone.fuel,
                    extraDataJson = Json.encodeToString(result.plantingDrone)
                )
            }
            if (harvesterUpgrade?.isPurchased == true && result.harvesterDrone != harvesterState) {
                changedUpgrades[FARM_HARVESTING_DRONE_ID] = (changedUpgrades[FARM_HARVESTING_DRONE_ID] ?: harvesterUpgrade).copy(
                    isPurchased = true,
                    storedOutput = result.harvesterDrone.fuel,
                    extraDataJson = Json.encodeToString(result.harvesterDrone)
                )
            }
            farmApplyRainWaterings(result.tiles, waterings.filter { it.wateredAt >= now })
        }
        if (weatherRow == null || waterings.isNotEmpty() || now - weatherRow.lastProductionTime >= FARM_WEATHER_CHECKPOINT_MS) {
            changedUpgrades[FARM_WEATHER_STATE_ID] = (weatherRow ?: FarmUpgradeEntity(
                type = FARM_WEATHER_STATE_ID, petId = petId, isPurchased = false, lastProductionTime = now
            )).copy(lastProductionTime = now)
        }
        val obstacleRow = storedUpgrades.firstOrNull { it.type == FARM_OBSTACLE_STATE_ID }
        val (updatedTiles, obstacleState) = FarmObstacles.plan(
            state = repository.decodeObstacleState(obstacleRow),
            tiles = advancedTiles.sortedBy { it.id },
            today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
            petSeed = petId.hashCode().toLong()
        )
        val encodedObstacleState = Json.encodeToString(obstacleState)
        if (obstacleRow == null || obstacleRow.extraDataJson != encodedObstacleState) {
            changedUpgrades[FARM_OBSTACLE_STATE_ID] = (obstacleRow ?: FarmUpgradeEntity(
                type = FARM_OBSTACLE_STATE_ID, petId = petId, isPurchased = false, lastProductionTime = now
            )).copy(extraDataJson = encodedObstacleState)
        }
        val changedTiles = tiles.sortedBy { it.id }
            .zip(updatedTiles.sortedBy { it.id })
            .mapNotNull { (original, updated) -> updated.takeIf { it != original } }

        val changedLivestock = repository.getLivestock(petId).mapNotNull { structure ->
            updateLivestock(structure, now).takeIf { it != structure }
        }
        repository.saveFarmUpdate(
            petId = petId,
            tiles = changedTiles,
            upgrades = changedUpgrades.values.toList(),
            livestock = changedLivestock,
            rescheduleNotifications = false
        )
    }

    private fun updateUpgrade(upgrade: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        return when (upgrade.type) {
            "well" -> updateWell(upgrade, now)
            "composter" -> updateComposter(upgrade, now)
            else -> upgrade
        }
    }

    private fun updateWell(well: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        if (!well.isPurchased) return well
        val state = repository.decodeWellState(well, now)
        val interval = wellProductionIntervalForSpeedLevel(state.speedLevel)
        val updatedSlots = state.slots.map { slot ->
            when {
                slot.hasWater -> slot
                slot.cycleStartedAt == null -> slot.copy(cycleStartedAt = now)
                now >= slot.cycleStartedAt + interval -> slot.copy(hasWater = true)
                else -> slot
            }
        }
        val encodedState = Json.encodeToString(state.copy(slots = updatedSlots))
        val stored = updatedSlots.count { it.hasWater }
        val nextCycleStart = updatedSlots.firstOrNull { !it.hasWater }?.cycleStartedAt ?: well.lastProductionTime
        return if (
            stored != well.storedOutput ||
            encodedState != (well.extraDataJson ?: "") ||
            nextCycleStart != well.lastProductionTime
        ) {
            well.copy(
                lastProductionTime = nextCycleStart,
                storedOutput = stored,
                extraDataJson = encodedState
            )
        } else {
            well
        }
    }

    private fun updateComposter(composter: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        if (!composter.isPurchased) return composter
        
        val slots = repository.decodeComposterSlots(composter, now)

        val updatedSlots = slots.map { slot ->
            if (slot.state == ComposterSlotState.PROCESSING && slot.startedAt != null && now >= slot.startedAt + FARM_COMPOSTER_PROCESS_MS) {
                slot.copy(
                    state = ComposterSlotState.READY,
                    readyAt = slot.startedAt + FARM_COMPOSTER_PROCESS_MS
                )
            } else {
                slot
            }
        }
        val readyCount = updatedSlots.count { it.state == ComposterSlotState.READY }
        val encodedSlots = Json.encodeToString(updatedSlots)

        return if (readyCount != composter.storedOutput || encodedSlots != (composter.extraDataJson ?: "")) {
            composter.copy(
                storedOutput = readyCount,
                extraDataJson = encodedSlots
            )
        } else {
            composter
        }
    }

    private fun updateLivestock(
        livestock: FarmLivestockEntity,
        now: Long
    ): FarmLivestockEntity {
        val type = FarmLivestockType.fromId(livestock.type) ?: return livestock
        val slots = repository.decodeLivestockSlots(livestock, type)
        val updatedSlots = slots.map { slot ->
            if (!slot.occupied || slot.lastProductionTime == null) {
                slot
            } else {
                val feedDueAt = (slot.lastFedAt ?: slot.lastProductionTime) + LIVESTOCK_FEED_INTERVAL_MS
                val productionWindowEnd = minOf(now, feedDueAt)
                val timePassed = (productionWindowEnd - slot.lastProductionTime).coerceAtLeast(0L)
                val unitsProduced = (timePassed / type.productionIntervalMs).toInt()
                if (unitsProduced <= 0) {
                    slot
                } else {
                    slot.copy(
                        storedOutput = minOf(type.perAnimalStorageCap, slot.storedOutput + unitsProduced),
                        lastProductionTime = slot.lastProductionTime + (unitsProduced * type.productionIntervalMs)
                    )
                }
            }
        }
        val encoded = Json.encodeToString(updatedSlots)
        return if (encoded != livestock.slotsJson) {
            livestock.copy(slotsJson = encoded)
        } else {
            livestock
        }
    }
}
