package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.FarmActionKind
import com.example.llamadroid.tama.world.core.WorldEffectRequest

/** Shared plot rules. The caller owns the action gate and Room transaction. */
internal object CanonicalFarmTransitions {
    suspend fun apply(
        context: Context, database: TamaDatabase, farm: FarmRepository,
        engine: TamaGameEngine, petId: String, effect: WorldEffectRequest.FarmTransition,
        now: Long, worldSeed: Long
    ) {
        val tileId = effect.plotId.removePrefix("farm_plot_").toIntOrNull() ?: error("unknown_farm_plot")
        val tile = farm.getTiles(petId).firstOrNull { it.id == tileId } ?: error("farm_plot_locked")
        val pet = database.tamaDao().getPet(petId)?.let(PetMapper::toDomain) ?: error("pet_missing")
        val changed = when (effect.action) {
            FarmActionKind.TILL_SOIL -> {
                require(tile.status == TileStatus.SOIL && tile.crop == null) { "already_tilled" }
                require(engine.consumeFarmToolDurability("hoe", 1) == 1) { "tool_required" }
                tile.copy(status = TileStatus.FARMLAND)
            }
            FarmActionKind.PLANT -> {
                require(tile.status != TileStatus.SOIL && tile.crop == null) { "empty_tilled_plot_required" }
                val seed = pet.inventory.firstOrNull { it.type == ItemType.SEED && it.quantity > 0 &&
                    (effect.cropId == null || it.id == "seed_${effect.cropId}") } ?: error("seed_required")
                val cropId = seed.id.removePrefix("seed_")
                require(cropId in CropDefinitions.CROPS && engine.consumeItem(seed, 1)) { "seed_required" }
                engine.logEvent(petId, EventType.PLANTED, context.getString(R.string.tama_event_planted, cropDisplayName(context, cropId)))
                tile.copy(crop = PlantedCrop(cropId, plantedTime = now, lastStageUpdateTime = now))
            }
            FarmActionKind.WATER, FarmActionKind.POUR_WATER -> {
                require(tile.status == TileStatus.FARMLAND) { "dry_tilled_plot_required" }
                val water = pet.inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
                require(water != null || farm.consumeWater(petId, rescheduleNotifications = false)) { "water_required" }
                if (water != null) require(engine.consumeItem(water, 1)) { "water_required" }
                if (effect.action == FarmActionKind.WATER) {
                    require(engine.consumeFarmToolDurability("watering_can", 1) == 1) { "tool_required" }
                }
                engine.logEvent(petId, EventType.WATERED, context.getString(R.string.tama_event_poured_water))
                tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = now)
            }
            FarmActionKind.HARVEST_CROP -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.stage == 3 && !crop.isDecayed) { "crop_not_ready" }
                val seed = "$worldSeed:$tileId:${crop.type}:${crop.plantedTime}"
                val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                val outcome = (java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL) / 4294967296.0
                require(engine.harvestCrop(crop, outcome.toFloat()).success) { "harvest_failed" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmActionKind.REMOVE_DEAD_CROP -> {
                val crop = tile.crop ?: error("dead_crop_required")
                require(crop.isDecayed && engine.harvestCrop(crop).success) { "dead_crop_required" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmActionKind.FERTILIZE -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.isDecayed || (crop.stage < 3 && !crop.isFertilized)) { "fertilizer_not_needed" }
                val fertilizer = pet.inventory.firstOrNull { it.id == "fertilizer" && it.quantity > 0 }
                    ?: error("fertilizer_required")
                require(engine.consumeItem(fertilizer, 1)) { "fertilizer_required" }
                val revived = if (crop.isDecayed) crop.copy(isDecayed = false, lastStageUpdateTime = now)
                    else crop.copy(isFertilized = true)
                engine.logEvent(petId, EventType.OTHER, context.getString(if (crop.isDecayed)
                    R.string.tama_event_revived_plant else R.string.tama_event_applied_fertilizer))
                tile.copy(crop = revived)
            }
            FarmActionKind.STORE_PRODUCE -> error("canonical_action_required")
        }
        if (changed != tile) farm.saveTile(petId, changed, rescheduleNotifications = false)
    }
}
