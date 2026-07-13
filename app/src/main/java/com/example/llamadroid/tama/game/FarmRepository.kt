package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val FARM_WELL_MAX_LEVEL = 9
internal const val FARM_WELL_MAX_SPEED_LEVEL = 4
internal const val FARM_COMPOSTER_MAX_LEVEL = 9
internal const val FARM_COMPOSTER_PROCESS_MS = 8 * 60 * 60 * 1000L
internal const val FARM_WELL_COST = 200
internal const val FARM_WELL_PRODUCTION_MS = 8 * 60 * 60 * 1000L

internal fun wellCapacityForLevel(level: Int): Int = level.coerceIn(1, FARM_WELL_MAX_LEVEL)

internal fun composterSlotCapacityForLevel(level: Int): Int = level.coerceIn(1, FARM_COMPOSTER_MAX_LEVEL)

internal fun wellCapacityUpgradeCostForLevel(level: Int): Int =
    ((level.coerceIn(1, FARM_WELL_MAX_LEVEL - 1) + 1) * 100)

internal fun composterCapacityUpgradeCostForLevel(level: Int): Int =
    wellCapacityUpgradeCostForLevel(level) * 2

internal fun wellProductionIntervalForSpeedLevel(speedLevel: Int): Long = when (speedLevel.coerceIn(0, FARM_WELL_MAX_SPEED_LEVEL)) {
    0 -> FARM_WELL_PRODUCTION_MS
    1 -> 7 * 60 * 60 * 1000L
    2 -> 6 * 60 * 60 * 1000L
    3 -> 5 * 60 * 60 * 1000L
    else -> 4 * 60 * 60 * 1000L
}

internal fun wellIntervalHoursForSpeedLevel(speedLevel: Int): Int =
    (wellProductionIntervalForSpeedLevel(speedLevel) / (60 * 60 * 1000L)).toInt()

internal fun wellSpeedUpgradeCostForLevel(speedLevel: Int): Int? = when (speedLevel.coerceIn(0, FARM_WELL_MAX_SPEED_LEVEL)) {
    0 -> 500
    1 -> 1000
    2 -> 1500
    3 -> 2000
    else -> null
}

class FarmRepository(
    private val farmDao: FarmDao,
    private val context: Context
) {

    fun observeTiles(petId: String): Flow<List<FarmTile>> {
        return farmDao.observeTiles(petId).map { entities ->
            entities.map { entityToTile(it) }
        }
    }

    suspend fun getTiles(petId: String): List<FarmTile> {
        return farmDao.getTiles(petId).map { entityToTile(it) }
    }

    suspend fun getUnlockedFarmTileCount(petId: String): Int {
        val farmland = farmDao.getUpgrade(petId, FARMLAND_UPGRADE_ID)
        val level = farmland?.takeIf { it.isPurchased }?.level ?: 0
        return farmTileCountForFarmlandLevel(level)
    }

    suspend fun ensureUnlockedFarmTiles(petId: String): List<FarmTile> {
        val unlockedCount = getUnlockedFarmTileCount(petId)
        val existing = getTiles(petId).associateBy { it.id }
        val missing = (0 until unlockedCount)
            .filterNot { id -> existing.containsKey(id) }
            .map { id -> FarmTile(id = id) }
        if (missing.isNotEmpty()) {
            farmDao.saveTiles(missing.map { tileToEntity(petId, it) })
        }
        return (0 until unlockedCount)
            .map { id -> existing[id] ?: FarmTile(id = id) }
            .sortedBy { it.id }
    }

    fun observeUpgrades(petId: String): Flow<List<FarmUpgradeEntity>> {
        return farmDao.observeUpgrades(petId)
    }

    fun observeLivestock(petId: String): Flow<List<FarmLivestockEntity>> {
        return farmDao.observeLivestock(petId)
    }

    suspend fun saveTile(
        petId: String,
        tile: FarmTile,
        rescheduleNotifications: Boolean = true,
        syncDroneWatchers: Boolean = true
    ) {
        farmDao.saveTile(tileToEntity(petId, tile))
        if (syncDroneWatchers) {
            syncPlantingDroneEmptyWatcher(petId, tile)
        }
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
    }

    suspend fun getUpgrades(petId: String): List<FarmUpgradeEntity> {
        return farmDao.getUpgrades(petId)
    }

    suspend fun getUpgrade(petId: String, type: String): FarmUpgradeEntity? {
        return farmDao.getUpgrade(petId, type)
    }

    suspend fun getLivestock(petId: String): List<FarmLivestockEntity> {
        return farmDao.getLivestock(petId)
    }

    suspend fun getLivestock(petId: String, type: String): FarmLivestockEntity? {
        return farmDao.getLivestockByType(petId, type)
    }

    suspend fun saveUpgrade(upgrade: FarmUpgradeEntity, rescheduleNotifications: Boolean = true) {
        farmDao.saveUpgrade(upgrade)
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, upgrade.petId)
        }
    }

    suspend fun saveDroneUpdateBatch(
        petId: String,
        tiles: List<FarmTile>,
        plantingState: PlantingDroneState? = null,
        harvesterState: HarvesterDroneState? = null,
        rescheduleNotifications: Boolean = true
    ) {
        val tileEntities = tiles.map { tileToEntity(petId, it) }
        val upgradeEntities = buildList {
            if (plantingState != null) {
                val existing = farmDao.getUpgrade(petId, FARM_PLANTING_DRONE_ID) ?: FarmUpgradeEntity(
                    type = FARM_PLANTING_DRONE_ID,
                    petId = petId,
                    isPurchased = true
                )
                add(
                    existing.copy(
                        isPurchased = true,
                        storedOutput = plantingState.fuel,
                        extraDataJson = Json.encodeToString(plantingState)
                    )
                )
            }
            if (harvesterState != null) {
                val existing = farmDao.getUpgrade(petId, FARM_HARVESTING_DRONE_ID) ?: FarmUpgradeEntity(
                    type = FARM_HARVESTING_DRONE_ID,
                    petId = petId,
                    isPurchased = true
                )
                add(
                    existing.copy(
                        isPurchased = true,
                        storedOutput = harvesterState.fuel,
                        extraDataJson = Json.encodeToString(harvesterState)
                    )
                )
            }
        }
        if (tileEntities.isEmpty() && upgradeEntities.isEmpty()) return
        farmDao.saveTilesAndUpgrades(tileEntities, upgradeEntities)
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
    }

    suspend fun saveLivestock(entity: FarmLivestockEntity, rescheduleNotifications: Boolean = true) {
        farmDao.saveLivestock(entity)
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, entity.petId)
        }
    }

    suspend fun refreshFarmState(
        petId: String,
        now: Long = System.currentTimeMillis()
    ) {
        FarmEngine(this).updateFarm(petId, now)
    }

    suspend fun shiftCycleTimestamps(petId: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val shiftedTiles = getTiles(petId).map { it.withCycleTimestampsShifted(durationMs) }
        val shiftedUpgrades = getUpgrades(petId).map { it.withCycleTimestampsShifted(durationMs) }
        val shiftedLivestock = getLivestock(petId).map { it.withCycleTimestampsShifted(durationMs) }
        farmDao.saveTiles(shiftedTiles.map { tileToEntity(petId, it) })
        farmDao.saveUpgrades(shiftedUpgrades)
        farmDao.saveLivestock(shiftedLivestock)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun buyUpgrade(petId: String, type: String, price: Int, rescheduleNotifications: Boolean = true) {
        val existing = farmDao.getUpgrade(petId, type)
        val purchasedAt = System.currentTimeMillis()
        if (existing == null) {
            farmDao.saveUpgrade(
                FarmUpgradeEntity(
                    type = type,
                    petId = petId,
                    isPurchased = true,
                    extraDataJson = when (type) {
                        "composter" -> Json.encodeToString(List(composterSlotCapacityForLevel(1)) { ComposterSlot() })
                        "well" -> Json.encodeToString(initialWellState(purchasedAt))
                        FARM_PLANTING_DRONE_ID -> Json.encodeToString(PlantingDroneState(lastUpdatedAt = purchasedAt))
                        FARM_HARVESTING_DRONE_ID -> Json.encodeToString(HarvesterDroneState(lastUpdatedAt = purchasedAt))
                        else -> null
                    }
                )
            )
        } else {
            farmDao.saveUpgrade(
                existing.copy(
                    isPurchased = true,
                    extraDataJson = when {
                        type == "composter" && existing.extraDataJson.isNullOrBlank() -> {
                            Json.encodeToString(List(composterSlotCapacityForLevel(existing.level)) { ComposterSlot() })
                        }
                        type == "well" && existing.extraDataJson.isNullOrBlank() -> {
                            Json.encodeToString(decodeWellState(existing, purchasedAt))
                        }
                        type == FARM_PLANTING_DRONE_ID && existing.extraDataJson.isNullOrBlank() -> {
                            Json.encodeToString(PlantingDroneState(lastUpdatedAt = purchasedAt))
                        }
                        type == FARM_HARVESTING_DRONE_ID && existing.extraDataJson.isNullOrBlank() -> {
                            Json.encodeToString(HarvesterDroneState(lastUpdatedAt = purchasedAt))
                        }
                        else -> {
                            existing.extraDataJson
                        }
                    }
                )
            )
        }
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun consumeWater(petId: String, rescheduleNotifications: Boolean = true): Boolean {
        return false
    }

    suspend fun collectStoredOutput(
        petId: String,
        type: String,
        collectedAt: Long = System.currentTimeMillis(),
        rescheduleNotifications: Boolean = true
    ): Int {
        val upgrade = farmDao.getUpgrade(petId, type) ?: return 0
        if (!upgrade.isPurchased || upgrade.storedOutput <= 0) return 0
        val collected = upgrade.storedOutput
        val updatedUpgrade = if (type == "composter") {
            val clearedSlots = decodeComposterSlots(upgrade).map { slot ->
                if (slot.state == ComposterSlotState.READY) ComposterSlot() else slot
            }
            upgrade.copy(
                storedOutput = 0,
                extraDataJson = Json.encodeToString(clearedSlots)
            )
        } else if (type == "well") {
            val state = decodeWellState(upgrade, collectedAt)
            applyWellState(
                upgrade = upgrade,
                state = state.copy(
                    slots = state.slots.map { slot ->
                        if (slot.hasWater) WellSlot(hasWater = false, cycleStartedAt = collectedAt) else slot
                    }
                ),
                level = upgrade.level,
                now = collectedAt
            )
        } else {
            upgrade.copy(storedOutput = 0)
        }
        farmDao.saveUpgrade(updatedUpgrade)
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
        return collected
    }

    suspend fun buyLivestockAnimal(
        petId: String,
        type: FarmLivestockType,
        purchasedAt: Long = System.currentTimeMillis(),
        rescheduleNotifications: Boolean = true
    ): Boolean {
        val entity = farmDao.getLivestockByType(petId, type.id) ?: FarmLivestockEntity(
            petId = petId,
            type = type.id,
            slotsJson = Json.encodeToString(emptyLivestockSlots(type))
        )
        val slots = decodeLivestockSlots(entity, type).toMutableList()
        val emptyIndex = slots.indexOfFirst { !it.occupied }
        if (emptyIndex == -1) return false
        slots[emptyIndex] = FarmLivestockSlot(
            occupied = true,
            storedOutput = 0,
            lastProductionTime = purchasedAt,
            lastFedAt = purchasedAt
        )
        saveLivestock(entity.copy(slotsJson = Json.encodeToString(slots)), rescheduleNotifications)
        return true
    }

    suspend fun collectLivestockOutput(
        petId: String,
        type: FarmLivestockType,
        rescheduleNotifications: Boolean = true
    ): Int {
        val entity = farmDao.getLivestockByType(petId, type.id) ?: return 0
        val slots = decodeLivestockSlots(entity, type)
        val collected = slots.sumOf { it.storedOutput }
        if (collected <= 0) return 0
        val cleared = slots.map { slot ->
            if (!slot.occupied || slot.storedOutput <= 0) slot else slot.copy(storedOutput = 0)
        }
        saveLivestock(entity.copy(slotsJson = Json.encodeToString(cleared)), rescheduleNotifications)
        return collected
    }

    suspend fun feedLivestockAnimal(
        petId: String,
        type: FarmLivestockType,
        slotIndex: Int,
        fedAt: Long = System.currentTimeMillis(),
        rescheduleNotifications: Boolean = true
    ): Boolean {
        val entity = farmDao.getLivestockByType(petId, type.id) ?: return false
        val slots = decodeLivestockSlots(entity, type).toMutableList()
        val slot = slots.getOrNull(slotIndex) ?: return false
        if (!slot.occupied) return false
        slots[slotIndex] = slot.copy(
            lastFedAt = fedAt,
            lastProductionTime = maxOf(slot.lastProductionTime ?: fedAt, fedAt)
        )
        saveLivestock(entity.copy(slotsJson = Json.encodeToString(slots)), rescheduleNotifications)
        return true
    }

    suspend fun addComposterInput(
        petId: String,
        inputItemId: String,
        slotIndex: Int? = null,
        startedAt: Long = System.currentTimeMillis(),
        rescheduleNotifications: Boolean = true
    ): Boolean {
        val composter = farmDao.getUpgrade(petId, "composter") ?: return false
        if (!composter.isPurchased) return false
        if (!FarmTradeItemCatalog.isCompostableCropItem(inputItemId)) return false

        val slots = decodeComposterSlots(composter).toMutableList()
        val targetIndex = slotIndex?.takeIf { index ->
            slots.getOrNull(index)?.state == ComposterSlotState.EMPTY
        } ?: slots.indexOfFirst { it.state == ComposterSlotState.EMPTY }
        if (targetIndex == -1) return false
        slots[targetIndex] = ComposterSlot(
            state = ComposterSlotState.PROCESSING,
            startedAt = startedAt,
            inputItemId = inputItemId
        )
        farmDao.saveUpgrade(composter.copy(extraDataJson = Json.encodeToString(slots)))
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
        return true
    }

    suspend fun collectWellTileOutput(
        petId: String,
        slotIndex: Int,
        collectedAt: Long = System.currentTimeMillis(),
        rescheduleNotifications: Boolean = true
    ): Int {
        val well = farmDao.getUpgrade(petId, "well") ?: return 0
        if (!well.isPurchased) return 0
        val state = decodeWellState(well, collectedAt)
        val slot = state.slots.getOrNull(slotIndex) ?: return 0
        val interval = wellProductionIntervalForSpeedLevel(state.speedLevel)
        val isReady = slot.hasWater || (
            slot.cycleStartedAt != null && collectedAt >= slot.cycleStartedAt + interval
        )
        if (!isReady) return 0
        val updatedSlots = state.slots.toMutableList()
        updatedSlots[slotIndex] = WellSlot(
            hasWater = false,
            cycleStartedAt = collectedAt
        )
        saveUpgrade(
            applyWellState(
                upgrade = well,
                state = state.copy(slots = updatedSlots),
                level = well.level,
                now = collectedAt
            ),
            rescheduleNotifications
        )
        return 1
    }

    suspend fun collectComposterTileOutput(
        petId: String,
        slotIndex: Int,
        rescheduleNotifications: Boolean = true
    ): Int {
        val composter = farmDao.getUpgrade(petId, "composter") ?: return 0
        if (!composter.isPurchased) return 0
        val slots = decodeComposterSlots(composter).toMutableList()
        val slot = slots.getOrNull(slotIndex) ?: return 0
        if (slot.state != ComposterSlotState.READY) return 0
        slots[slotIndex] = ComposterSlot()
        saveUpgrade(
            composter.copy(
                storedOutput = slots.count { it.state == ComposterSlotState.READY },
                extraDataJson = Json.encodeToString(slots)
            ),
            rescheduleNotifications
        )
        return 1
    }

    suspend fun upgradeWellCapacity(petId: String, rescheduleNotifications: Boolean = true): Boolean {
        val well = farmDao.getUpgrade(petId, "well") ?: return false
        if (!well.isPurchased || well.level >= FARM_WELL_MAX_LEVEL) return false
        val newLevel = well.level + 1
        val now = System.currentTimeMillis()
        val expandedState = normalizeWellState(
            state = decodeWellState(well, now),
            capacity = wellCapacityForLevel(newLevel),
            now = now,
            fallbackCycleStart = well.lastProductionTime.takeIf { it > 0L } ?: now
        )
        saveUpgrade(
            applyWellState(
                upgrade = well,
                state = expandedState,
                level = newLevel,
                now = now
            ),
            rescheduleNotifications
        )
        return true
    }

    suspend fun upgradeWellSpeed(petId: String, rescheduleNotifications: Boolean = true): Boolean {
        val well = farmDao.getUpgrade(petId, "well") ?: return false
        if (!well.isPurchased) return false
        val now = System.currentTimeMillis()
        val state = decodeWellState(well, now)
        if (state.speedLevel >= FARM_WELL_MAX_SPEED_LEVEL) return false
        saveUpgrade(
            applyWellState(
                upgrade = well,
                state = state.copy(speedLevel = state.speedLevel + 1),
                level = well.level,
                now = now
            ),
            rescheduleNotifications
        )
        return true
    }

    suspend fun upgradeComposterCapacity(petId: String, rescheduleNotifications: Boolean = true): Boolean {
        val composter = farmDao.getUpgrade(petId, "composter") ?: return false
        if (!composter.isPurchased || composter.level >= FARM_COMPOSTER_MAX_LEVEL) return false
        val newLevel = composter.level + 1
        val expandedSlots = normalizeComposterSlots(
            slots = decodeComposterSlots(composter),
            capacity = composterSlotCapacityForLevel(newLevel)
        )
        farmDao.saveUpgrade(
            composter.copy(
                level = newLevel,
                extraDataJson = Json.encodeToString(expandedSlots)
            )
        )
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
        return true
    }

    suspend fun upgradeFarmland(petId: String, rescheduleNotifications: Boolean = true): Boolean {
        val existing = farmDao.getUpgrade(petId, FARMLAND_UPGRADE_ID)
        val currentLevel = existing?.takeIf { it.isPurchased }?.level ?: 0
        if (currentLevel >= FARM_MAX_FARMLAND_LEVEL) return false
        val now = System.currentTimeMillis()
        val updated = (existing ?: FarmUpgradeEntity(
            type = FARMLAND_UPGRADE_ID,
            petId = petId,
            isPurchased = true,
            level = 0,
            lastProductionTime = now
        )).copy(
            isPurchased = true,
            level = currentLevel + 1,
            lastProductionTime = existing?.lastProductionTime ?: now
        )
        farmDao.saveUpgrade(updated)
        ensureUnlockedFarmTiles(petId)
        if (rescheduleNotifications) {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
        }
        return true
    }

    fun decodeComposterSlots(composter: FarmUpgradeEntity?): List<ComposterSlot> {
        if (composter == null) return List(composterSlotCapacityForLevel(1)) { ComposterSlot() }
        val capacity = composterSlotCapacityForLevel(composter.level)
        return try {
            val decoded = Json.decodeFromString<List<ComposterSlot>>(composter.extraDataJson ?: "[]")
            normalizeComposterSlots(decoded, capacity)
        } catch (_: Exception) {
            runCatching {
                val legacy = Json.decodeFromString<List<Long?>>(composter.extraDataJson ?: "[]")
                normalizeComposterSlots(
                    legacy.map { startedAt ->
                        if (startedAt == null) {
                            ComposterSlot()
                        } else {
                            ComposterSlot(
                                state = if (System.currentTimeMillis() >= startedAt + FARM_COMPOSTER_PROCESS_MS) {
                                    ComposterSlotState.READY
                                } else {
                                    ComposterSlotState.PROCESSING
                                },
                                startedAt = startedAt,
                                readyAt = (startedAt + FARM_COMPOSTER_PROCESS_MS).takeIf {
                                    System.currentTimeMillis() >= it
                                },
                                inputItemId = "rotten_crop"
                            )
                        }
                    },
                    capacity
                )
            }.getOrElse {
                List(capacity) { ComposterSlot() }
            }
        }
    }

    fun decodeWellState(
        well: FarmUpgradeEntity?,
        now: Long = System.currentTimeMillis()
    ): WellUpgradeState {
        if (well == null) {
            return initialWellState(now)
        }
        val capacity = wellCapacityForLevel(well.level)
        val fallbackCycleStart = well.lastProductionTime.takeIf { it > 0L } ?: now
        return try {
            val decoded = Json.decodeFromString<WellUpgradeState>(well.extraDataJson ?: "")
            normalizeWellState(
                state = decoded,
                capacity = capacity,
                now = now,
                fallbackCycleStart = fallbackCycleStart
            )
        } catch (_: Exception) {
            legacyWellState(
                well = well,
                capacity = capacity,
                now = now
            )
        }
    }

    fun decodeLivestockSlots(
        livestock: FarmLivestockEntity?,
        type: FarmLivestockType? = livestock?.let { FarmLivestockType.fromId(it.type) }
    ): List<FarmLivestockSlot> {
        val livestockType = type ?: return emptyList()
        if (livestock == null) return emptyLivestockSlots(livestockType)
        return runCatching {
            Json.decodeFromString<List<FarmLivestockSlot>>(livestock.slotsJson)
        }.getOrDefault(emptyList()).let { normalizeLivestockSlots(livestockType, it) }
    }

    fun decodePlantingDroneState(
        upgrade: FarmUpgradeEntity?,
        now: Long = System.currentTimeMillis()
    ): PlantingDroneState {
        if (upgrade == null || !upgrade.isPurchased) return PlantingDroneState(lastUpdatedAt = now)
        return runCatching {
            Json.decodeFromString<PlantingDroneState>(upgrade.extraDataJson ?: "")
        }.getOrDefault(PlantingDroneState()).let { state ->
            state.copy(
                fuelUpgradeLevel = state.fuelUpgradeLevel.coerceIn(0, FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL),
                fuel = state.fuel.coerceIn(0, farmDroneFuelCapacityForUpgradeLevel(state.fuelUpgradeLevel)),
                water = state.water.coerceAtLeast(0),
                fertilizer = state.fertilizer.coerceAtLeast(0),
                seeds = state.seeds.filter { it.quantity > 0 && CropDefinitions.CROPS.containsKey(it.cropId) },
                lastUpdatedAt = state.lastUpdatedAt.takeIf { it > 0L } ?: now
            )
        }
    }

    fun decodeHarvesterDroneState(
        upgrade: FarmUpgradeEntity?,
        now: Long = System.currentTimeMillis()
    ): HarvesterDroneState {
        if (upgrade == null || !upgrade.isPurchased) return HarvesterDroneState(lastUpdatedAt = now)
        return runCatching {
            Json.decodeFromString<HarvesterDroneState>(upgrade.extraDataJson ?: "")
        }.getOrDefault(HarvesterDroneState()).let { state ->
            state.copy(
                fuelUpgradeLevel = state.fuelUpgradeLevel.coerceIn(0, FARM_DRONE_MAX_FUEL_UPGRADE_LEVEL),
                fuel = state.fuel.coerceIn(0, farmDroneFuelCapacityForUpgradeLevel(state.fuelUpgradeLevel)),
                cropFilter = state.cropFilter.filter { CropDefinitions.CROPS.containsKey(it) }.toSet(),
                storage = state.storage.filter { it.quantity > 0 },
                lastUpdatedAt = state.lastUpdatedAt.takeIf { it > 0L } ?: now
            )
        }
    }

    suspend fun savePlantingDroneState(
        petId: String,
        state: PlantingDroneState,
        rescheduleNotifications: Boolean = true
    ) {
        val existing = farmDao.getUpgrade(petId, FARM_PLANTING_DRONE_ID) ?: FarmUpgradeEntity(
            type = FARM_PLANTING_DRONE_ID,
            petId = petId,
            isPurchased = true
        )
        saveUpgrade(
            existing.copy(
                isPurchased = true,
                storedOutput = state.fuel,
                extraDataJson = Json.encodeToString(state)
            ),
            rescheduleNotifications
        )
    }

    suspend fun saveHarvesterDroneState(
        petId: String,
        state: HarvesterDroneState,
        rescheduleNotifications: Boolean = true
    ) {
        val existing = farmDao.getUpgrade(petId, FARM_HARVESTING_DRONE_ID) ?: FarmUpgradeEntity(
            type = FARM_HARVESTING_DRONE_ID,
            petId = petId,
            isPurchased = true
        )
        saveUpgrade(
            existing.copy(
                isPurchased = true,
                storedOutput = state.fuel,
                extraDataJson = Json.encodeToString(state)
            ),
            rescheduleNotifications
        )
    }

    private fun normalizeComposterSlots(
        slots: List<ComposterSlot>,
        capacity: Int
    ): List<ComposterSlot> {
        val trimmed = if (slots.size >= capacity) slots.take(capacity) else slots
        return trimmed + List((capacity - trimmed.size).coerceAtLeast(0)) { ComposterSlot() }
    }

    private fun initialWellState(startedAt: Long): WellUpgradeState =
        WellUpgradeState(
            speedLevel = 0,
            slots = listOf(WellSlot(hasWater = false, cycleStartedAt = startedAt))
        )

    private fun legacyWellState(
        well: FarmUpgradeEntity,
        capacity: Int,
        now: Long
    ): WellUpgradeState {
        val stored = well.storedOutput.coerceIn(0, capacity)
        val fallbackCycleStart = well.lastProductionTime.takeIf { it > 0L } ?: now
        val legacySlots = buildList {
            repeat(stored) {
                add(WellSlot(hasWater = true, cycleStartedAt = fallbackCycleStart))
            }
            if (stored < capacity) {
                add(WellSlot(hasWater = false, cycleStartedAt = fallbackCycleStart))
            }
        }
        return normalizeWellState(
            state = WellUpgradeState(speedLevel = 0, slots = legacySlots),
            capacity = capacity,
            now = now,
            fallbackCycleStart = fallbackCycleStart
        )
    }

    private fun normalizeWellState(
        state: WellUpgradeState,
        capacity: Int,
        now: Long,
        fallbackCycleStart: Long
    ): WellUpgradeState {
        val normalizedSlots = state.slots.mapIndexed { index, slot ->
            when {
                slot.hasWater -> {
                    slot.copy(cycleStartedAt = slot.cycleStartedAt ?: fallbackCycleStart)
                }
                slot.cycleStartedAt != null -> {
                    slot
                }
                index == 0 -> {
                    slot.copy(cycleStartedAt = fallbackCycleStart)
                }
                else -> {
                    slot.copy(cycleStartedAt = now)
                }
            }
        }
        val trimmed = if (normalizedSlots.size >= capacity) {
            normalizedSlots.take(capacity)
        } else {
            normalizedSlots + List(capacity - normalizedSlots.size) {
                WellSlot(hasWater = false, cycleStartedAt = now)
            }
        }
        return WellUpgradeState(
            speedLevel = state.speedLevel.coerceIn(0, FARM_WELL_MAX_SPEED_LEVEL),
            slots = trimmed
        )
    }

    private fun applyWellState(
        upgrade: FarmUpgradeEntity,
        state: WellUpgradeState,
        level: Int,
        now: Long
    ): FarmUpgradeEntity {
        val normalized = normalizeWellState(
            state = state,
            capacity = wellCapacityForLevel(level),
            now = now,
            fallbackCycleStart = upgrade.lastProductionTime.takeIf { it > 0L } ?: now
        )
        return upgrade.copy(
            level = level,
            lastProductionTime = normalized.slots.firstOrNull { !it.hasWater }?.cycleStartedAt ?: now,
            storedOutput = normalized.slots.count { it.hasWater },
            extraDataJson = Json.encodeToString(normalized)
        )
    }

    private fun normalizeLivestockSlots(
        type: FarmLivestockType,
        slots: List<FarmLivestockSlot>
    ): List<FarmLivestockSlot> {
        val now = System.currentTimeMillis()
        val trimmed = if (slots.size >= type.maxAnimals) slots.take(type.maxAnimals) else slots
        val normalized = trimmed.map { slot ->
            if (!slot.occupied) {
                slot.copy(storedOutput = 0, lastProductionTime = null, lastFedAt = null)
            } else {
                val normalizedProductionTime = slot.lastProductionTime ?: now
                slot.copy(
                    lastProductionTime = normalizedProductionTime,
                    lastFedAt = slot.lastFedAt ?: normalizedProductionTime
                )
            }
        }
        return normalized + List((type.maxAnimals - normalized.size).coerceAtLeast(0)) { FarmLivestockSlot() }
    }

    private suspend fun syncPlantingDroneEmptyWatcher(petId: String, tile: FarmTile) {
        val upgrade = farmDao.getUpgrade(petId, FARM_PLANTING_DRONE_ID) ?: return
        if (!upgrade.isPurchased) return
        val now = System.currentTimeMillis()
        val state = decodePlantingDroneState(upgrade, now)
        val watchers = state.emptySinceByTile.toMutableMap()
        val changed = if (tile.crop == null) {
            watchers.putIfAbsent(tile.id, now) == null
        } else {
            watchers.remove(tile.id) != null
        }
        if (changed) {
            farmDao.saveUpgrade(
                upgrade.copy(
                    storedOutput = state.fuel,
                    extraDataJson = Json.encodeToString(
                        state.copy(emptySinceByTile = watchers, lastUpdatedAt = now)
                    )
                )
            )
        }
    }

    private fun entityToTile(entity: FarmTileEntity): FarmTile {
        return FarmTile(
            id = entity.id,
            status = TileStatus.valueOf(entity.status),
            crop = entity.cropJson?.let { Json.decodeFromString<PlantedCrop>(it) },
            lastWateredTime = entity.lastWateredTime
        )
    }

    private fun tileToEntity(petId: String, tile: FarmTile): FarmTileEntity {
        return FarmTileEntity(
            id = tile.id,
            petId = petId,
            status = tile.status.name,
            cropJson = tile.crop?.let { Json.encodeToString(it) },
            lastWateredTime = tile.lastWateredTime
        )
    }
}
