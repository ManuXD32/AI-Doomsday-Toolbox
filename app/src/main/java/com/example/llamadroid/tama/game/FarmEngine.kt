package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject

class FarmEngine(
    private val repository: FarmRepository
) {
    /**
     * Update all farm elements based on current time.
     * This handles "offline" progress.
     */
    suspend fun updateFarm(petId: String, now: Long = System.currentTimeMillis()) {
        val tiles = repository.ensureUnlockedFarmTiles(petId)
        val updatedTiles = tiles.map { advanceFarmTileCrop(it, now) }
        // Compare each updated tile with its original using zip, not by ID index
        tiles.zip(updatedTiles).forEach { (original, updated) ->
            if (updated != original) repository.saveTile(petId, updated, rescheduleNotifications = false)
        }

        val upgrades = repository.getUpgrades(petId)
        upgrades.forEach { upgrade ->
            val updated = updateUpgrade(upgrade, now)
            if (updated != upgrade) repository.saveUpgrade(updated, rescheduleNotifications = false)
        }

        updateDrones(petId, now)

        val livestock = repository.getLivestock(petId)
        livestock.forEach { structure ->
            val updated = updateLivestock(structure, now)
            if (updated != structure) repository.saveLivestock(updated, rescheduleNotifications = false)
        }
    }

    private fun updateUpgrade(upgrade: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        return when (upgrade.type) {
            "well" -> updateWell(upgrade, now)
            "composter" -> updateComposter(upgrade, now)
            else -> upgrade
        }
    }

    private suspend fun updateDrones(petId: String, now: Long) {
        val plantingUpgrade = repository.getUpgrade(petId, FARM_PLANTING_DRONE_ID)
        val harvesterUpgrade = repository.getUpgrade(petId, FARM_HARVESTING_DRONE_ID)
        if (plantingUpgrade?.isPurchased != true && harvesterUpgrade?.isPurchased != true) return

        val currentTiles = repository.ensureUnlockedFarmTiles(petId)
        val plantingState = repository.decodePlantingDroneState(plantingUpgrade, now)
        val harvesterState = repository.decodeHarvesterDroneState(harvesterUpgrade, now)
        val result = simulateFarmDrones(
            tiles = currentTiles,
            plantingDrone = plantingState,
            harvesterDrone = harvesterState,
            now = now
        )

        val changedTiles = currentTiles.sortedBy { it.id }
            .zip(result.tiles.sortedBy { it.id })
            .mapNotNull { (original, updated) ->
                updated.takeIf { original != it }
            }
        val updatedPlantingState = result.plantingDrone
            .takeIf { plantingUpgrade?.isPurchased == true && it != plantingState }
        val updatedHarvesterState = result.harvesterDrone
            .takeIf { harvesterUpgrade?.isPurchased == true && it != harvesterState }
        repository.saveDroneUpdateBatch(
            petId = petId,
            tiles = changedTiles,
            plantingState = updatedPlantingState,
            harvesterState = updatedHarvesterState,
            rescheduleNotifications = false
        )
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
        
        val capacity = composterSlotCapacityForLevel(composter.level)
        val slots = try {
            Json.decodeFromString<List<ComposterSlot>>(composter.extraDataJson ?: "[]")
        } catch (_: Exception) {
            runCatching {
                Json.decodeFromString<List<Long?>>(composter.extraDataJson ?: "[]")
                    .map { startedAt ->
                        if (startedAt == null) {
                            ComposterSlot()
                        } else {
                            ComposterSlot(
                                state = if (now >= startedAt + FARM_COMPOSTER_PROCESS_MS) ComposterSlotState.READY else ComposterSlotState.PROCESSING,
                                startedAt = startedAt,
                                readyAt = (startedAt + FARM_COMPOSTER_PROCESS_MS).takeIf { now >= it },
                                inputItemId = "rotten_crop"
                            )
                        }
                    }
            }.getOrElse {
                List(capacity) { ComposterSlot() }
            }
        }.let { decoded ->
            val trimmed = if (decoded.size >= capacity) decoded.take(capacity) else decoded
            trimmed + List((capacity - trimmed.size).coerceAtLeast(0)) { ComposterSlot() }
        }

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
