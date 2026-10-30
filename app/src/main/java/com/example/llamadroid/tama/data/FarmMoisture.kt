package com.example.llamadroid.tama.data

import java.security.MessageDigest
import java.nio.ByteBuffer
import kotlin.math.floor

/*
 * Plot moisture and harvest rules (pure functions, no Android types).
 *
 * Moisture is derived from the existing tile fields, so no database change is needed:
 *  - a watering lasts FARM_WATER_DURATION_MS from [FarmTile.lastWateredTime];
 *  - while wet a crop grows at full speed, once dry it grows at FARM_DRY_GROWTH_PERCENT percent (neglect
 *    slows a crop, it never kills it);
 *  - [advanceFarmTileCrop] flips an expired WET_FARMLAND tile back to FARMLAND, so status alone tells the UI
 *    whether the plot is dry (re-check [isWet] with `now` between ticks).
 *
 * Crop clock: stage timers live on a "virtual" clock V(t) = t while wet, then it runs at the dry rate. A
 * crop's lastStageUpdateTime is a virtual instant, which keeps the model stateless (no extra stored progress)
 * and equivariant under cycle-freeze shifting (planted/stage/watered times all move by the same amount).
 * Watering re-bases the cursor so progress made while dry is preserved ([farmWaterTile]).
 */

const val FARM_WATER_DURATION_MS = 8L * 60L * 60L * 1000L
const val FARM_DRY_GROWTH_PERCENT = 50
const val FARM_ROT_AFTER_RIPE_MS = 15L * 60L * 60L * 1000L

/** Wall-clock instant the last watering ends, or null when the plot was never (or no longer) watered. */
fun FarmTile.wetUntil(): Long? = lastWateredTime?.plus(FARM_WATER_DURATION_MS)

/**
 * True while the plot is moist. Legacy crops (planted before the moisture model) count as always wet so
 * nothing stalls after the update.
 */
fun FarmTile.isWet(now: Long): Boolean {
    if (status != TileStatus.WET_FARMLAND) return false
    val currentCrop = crop
    if (currentCrop != null && !currentCrop.moistureTracked) return true
    val until = wetUntil() ?: return false
    return now < until
}

/** Flips an expired WET_FARMLAND tile to FARMLAND (keeps lastWateredTime, which the crop clock still needs). */
fun farmNormalizeMoisture(tile: FarmTile, now: Long): FarmTile =
    if (tile.status == TileStatus.WET_FARMLAND && !tile.isWet(now)) tile.copy(status = TileStatus.FARMLAND) else tile

/** Real instant from which this crop's clock runs at the dry rate (Long.MAX_VALUE = never, for legacy crops). */
fun farmCropDryFrom(tile: FarmTile, crop: PlantedCrop): Long {
    if (!crop.moistureTracked) return Long.MAX_VALUE
    return maxOf(tile.wetUntil() ?: crop.plantedTime, crop.plantedTime)
}

fun farmVirtualTime(real: Long, dryFrom: Long): Long =
    if (real <= dryFrom) real else dryFrom + (real - dryFrom) * FARM_DRY_GROWTH_PERCENT / 100

fun farmRealTime(virtual: Long, dryFrom: Long): Long =
    if (virtual <= dryFrom) virtual else dryFrom + (virtual - dryFrom) * 100 / FARM_DRY_GROWTH_PERCENT

fun farmStageDurationMs(definition: CropDefinitions.CropInfo, fertilized: Boolean, stage: Int): Long {
    val base = definition.stageTimes[stage]
    return if (fertilized) base / 2 else base
}

/**
 * Applies a watering at [now]. The caller has already advanced the tile to [now] and checked the plot is dry.
 * The crop cursor moves later by the time lost to dryness so far, so progress is kept.
 */
fun farmWaterTile(tile: FarmTile, now: Long): FarmTile {
    val crop = tile.crop
    val rebased = if (crop != null && crop.moistureTracked && !crop.isDecayed) {
        val lost = now - farmVirtualTime(now, farmCropDryFrom(tile, crop))
        crop.copy(lastStageUpdateTime = crop.lastStageUpdateTime + lost)
    } else {
        crop
    }
    return tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = now, crop = rebased)
}

/**
 * Real instant the crop becomes harvestable, assuming the current moisture state runs out normally
 * (no further watering). Null when there is no growing crop.
 */
fun farmCropReadyAt(tile: FarmTile): Long? {
    val crop = tile.crop ?: return null
    if (crop.isDecayed || crop.stage >= 3) return null
    val definition = CropDefinitions.CROPS[crop.type] ?: return null
    var remaining = 0L
    for (stage in crop.stage until 3) remaining += farmStageDurationMs(definition, crop.isFertilized, stage)
    return farmRealTime(crop.lastStageUpdateTime + remaining, farmCropDryFrom(tile, crop))
}

/** Whether a growing, not yet ripe crop would benefit from water right now. */
fun farmTileNeedsWater(tile: FarmTile, now: Long): Boolean {
    val crop = tile.crop ?: return tile.status == TileStatus.FARMLAND
    return crop.moistureTracked && !crop.isDecayed && crop.stage < 3 && !tile.isWet(now)
}

// ---- Harvest yield (shared by manual harvest, the harvester drone and the engine) ----

/** Deterministic [0, 1) roll for a plot's harvest; manual and drone harvests use the same function. */
fun farmHarvestOutcome(seedBase: Long, plotId: Int, crop: PlantedCrop): Float {
    val seed = "$seedBase:$plotId:${crop.type}:${crop.plantedTime}"
    val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    return ((ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL) / 4294967296.0).toFloat()
}

/**
 * Items per harvest: floor(yieldModifier) plus one more with probability frac(yieldModifier), plus the
 * fertilizer bonus (15% chance +2, 30% chance +1, else +0; expected +0.6). Rotten crops give 1.
 * Expected unfertilized yield equals the crop's yieldModifier.
 */
fun farmHarvestYield(crop: PlantedCrop, outcome: Float): Int {
    if (crop.isDecayed) return 1
    val modifier = CropDefinitions.CROPS[crop.type]?.yieldModifier ?: 1f
    val whole = floor(modifier).toInt()
    val extraRoll = (outcome.toDouble() * 65536.0) % 1.0
    val base = whole + if (extraRoll < (modifier - whole).toDouble()) 1 else 0
    val bonus = if (!crop.isFertilized) 0 else when {
        outcome < 0.15f -> 2
        outcome < 0.45f -> 1
        else -> 0
    }
    return (base + bonus).coerceAtLeast(1)
}

/** Expected items per harvest (analytic version of [farmHarvestYield], used for balance docs and tests). */
fun farmExpectedYield(cropId: String, fertilized: Boolean): Double {
    val modifier = (CropDefinitions.CROPS[cropId]?.yieldModifier ?: 1f).toDouble()
    return modifier + if (fertilized) 0.6 else 0.0
}

/** Unfertilized net coins per plot-hour at full price, the number the economy table is balanced on. */
fun farmNetProfitPerPlotHour(cropId: String): Double {
    val info = CropDefinitions.CROPS[cropId] ?: return 0.0
    val hours = info.stageTimes.sum() / 3_600_000.0
    return (farmExpectedYield(cropId, false) * info.sellPrice - info.seedPrice) / hours
}
