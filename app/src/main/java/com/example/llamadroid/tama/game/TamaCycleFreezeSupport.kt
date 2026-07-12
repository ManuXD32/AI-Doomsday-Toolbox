package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.ComposterSlot
import com.example.llamadroid.tama.data.FARM_HARVESTING_DRONE_ID
import com.example.llamadroid.tama.data.FARM_PLANTING_DRONE_ID
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.HarvesterDroneState
import com.example.llamadroid.tama.data.PlantingDroneState
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.WellUpgradeState
import com.example.llamadroid.tama.db.FarmLivestockEntity
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.db.TamaQuestEntity
import com.example.llamadroid.tama.db.TamaStudySessionEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val freezeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun TamaPet.withCycleTimestampsShifted(durationMs: Long): TamaPet {
    if (durationMs <= 0L) return this
    return copy(
        birthTimestamp = birthTimestamp + durationMs,
        stageProgressStartTime = stageProgressStartTime + durationMs,
        lastDecayTime = lastDecayTime + durationMs,
        growthLockStartedAt = growthLockStartedAt?.plus(durationMs),
        activityStartTime = activityStartTime?.plus(durationMs),
        sleepStartTime = sleepStartTime?.plus(durationMs),
        nextPoopAt = nextPoopAt?.plus(durationMs),
        poopCreatedAt = poopCreatedAt?.plus(durationMs),
        lastPoopMiscareAt = lastPoopMiscareAt?.plus(durationMs),
        lastSleepWarningTime = lastSleepWarningTime?.plus(durationMs)
    )
}

internal fun TamaStudySessionEntity.withCycleTimestampsShifted(durationMs: Long): TamaStudySessionEntity {
    if (durationMs <= 0L) return this
    return copy(
        phaseStartedAt = phaseStartedAt?.plus(durationMs),
        phaseEndsAt = phaseEndsAt?.plus(durationMs),
        startedAt = startedAt + durationMs,
        completedAt = completedAt?.plus(durationMs),
        stoppedAt = stoppedAt?.plus(durationMs),
        lastUpdatedAt = lastUpdatedAt + durationMs
    )
}

internal fun TamaQuestEntity.withCycleTimestampsShifted(durationMs: Long): TamaQuestEntity {
    if (durationMs <= 0L) return this
    return copy(
        acceptedAt = acceptedAt?.plus(durationMs),
        expiresAt = expiresAt?.plus(durationMs),
        completedAt = completedAt?.plus(durationMs)
    )
}

internal fun FarmTile.withCycleTimestampsShifted(durationMs: Long): FarmTile {
    if (durationMs <= 0L) return this
    return copy(
        crop = crop?.copy(
            plantedTime = crop.plantedTime + durationMs,
            lastStageUpdateTime = crop.lastStageUpdateTime + durationMs
        ),
        lastWateredTime = lastWateredTime?.plus(durationMs)
    )
}

internal fun FarmUpgradeEntity.withCycleTimestampsShifted(durationMs: Long): FarmUpgradeEntity {
    if (durationMs <= 0L) return this
    return copy(
        lastProductionTime = lastProductionTime + durationMs,
        extraDataJson = shiftedUpgradeExtraData(durationMs)
    )
}

private fun FarmUpgradeEntity.shiftedUpgradeExtraData(durationMs: Long): String? {
    val raw = extraDataJson ?: return null
    return when (type) {
        "well" -> runCatching {
            val state = freezeJson.decodeFromString<WellUpgradeState>(raw)
            freezeJson.encodeToString(
                state.copy(
                    slots = state.slots.map { slot ->
                        slot.copy(cycleStartedAt = slot.cycleStartedAt?.plus(durationMs))
                    }
                )
            )
        }.getOrDefault(raw)
        "composter" -> runCatching {
            val slots = freezeJson.decodeFromString<List<ComposterSlot>>(raw)
            freezeJson.encodeToString(
                slots.map { slot ->
                    slot.copy(
                        startedAt = slot.startedAt?.plus(durationMs),
                        readyAt = slot.readyAt?.plus(durationMs)
                    )
                }
            )
        }.getOrDefault(raw)
        FARM_PLANTING_DRONE_ID -> runCatching {
            val state = freezeJson.decodeFromString<PlantingDroneState>(raw)
            freezeJson.encodeToString(
                state.copy(
                    emptySinceByTile = state.emptySinceByTile.mapValues { it.value + durationMs },
                    lastUpdatedAt = state.lastUpdatedAt + durationMs
                )
            )
        }.getOrDefault(raw)
        FARM_HARVESTING_DRONE_ID -> runCatching {
            val state = freezeJson.decodeFromString<HarvesterDroneState>(raw)
            freezeJson.encodeToString(state.copy(lastUpdatedAt = state.lastUpdatedAt + durationMs))
        }.getOrDefault(raw)
        else -> raw
    }
}

internal fun FarmLivestockEntity.withCycleTimestampsShifted(durationMs: Long): FarmLivestockEntity {
    if (durationMs <= 0L) return this
    val shifted = runCatching {
        val slots = freezeJson.decodeFromString<List<com.example.llamadroid.tama.data.FarmLivestockSlot>>(slotsJson)
        freezeJson.encodeToString(
            slots.map { slot ->
                slot.copy(
                    lastProductionTime = slot.lastProductionTime?.plus(durationMs),
                    lastFedAt = slot.lastFedAt?.plus(durationMs)
                )
            }
        )
    }.getOrDefault(slotsJson)
    return copy(slotsJson = shifted)
}
