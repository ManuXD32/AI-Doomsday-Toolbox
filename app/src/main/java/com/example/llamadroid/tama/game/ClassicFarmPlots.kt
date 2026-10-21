package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.cropDisplayName
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import kotlinx.coroutines.CancellationException

/** Plot actions the farm panel can issue; each one is a single classic transaction. */
enum class FarmPlotAction { TILL_SOIL, PLANT, WATER, POUR_WATER, FERTILIZE, HARVEST_CROP, REMOVE_DEAD_CROP }

/** Direct plot controls: no route, no queue, and no partial inventory spending. */
object ClassicFarmPlots {
    suspend fun request(
        context: Context,
        engine: TamaGameEngine,
        plotId: Int,
        action: FarmPlotAction,
        arguments: Map<String, String> = emptyMap()
    ): String {
        return try {
            engine.runClassicTransaction { database, farm ->
                val pet = engine.pet.value ?: error("pet_missing")
                check(!pet.cycleFrozen) { "cycle_frozen" }
                apply(context, database, farm, engine, pet.id, plotId, action, arguments["cropId"],
                    System.currentTimeMillis(), pet.id.hashCode().toLong())
                TamaCommitEffects.deferOrRun("notifications:${pet.id}") {
                    TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
                }
                context.getString(R.string.tama_classic_action_done)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            context.getString(R.string.tama_action_unavailable)
        }
    }

    /** Plot rules. The caller owns the action gate and the Room transaction. */
    internal suspend fun apply(
        context: Context, database: TamaDatabase, farm: FarmRepository,
        engine: TamaGameEngine, petId: String, plotId: Int, action: FarmPlotAction,
        cropId: String?, now: Long, seedBase: Long
    ) {
        val tile = farm.getTiles(petId).firstOrNull { it.id == plotId } ?: error("farm_plot_locked")
        val pet = database.tamaDao().getPet(petId)?.let(PetMapper::toDomain) ?: error("pet_missing")
        val changed = when (action) {
            FarmPlotAction.TILL_SOIL -> {
                require(tile.status == TileStatus.SOIL && tile.crop == null) { "already_tilled" }
                require(engine.consumeFarmToolDurability("hoe", 1) == 1) { "tool_required" }
                tile.copy(status = TileStatus.FARMLAND)
            }
            FarmPlotAction.PLANT -> {
                require(tile.status != TileStatus.SOIL && tile.crop == null) { "empty_tilled_plot_required" }
                val seed = pet.inventory.firstOrNull { it.type == ItemType.SEED && it.quantity > 0 &&
                    (cropId == null || it.id == "seed_$cropId") } ?: error("seed_required")
                val plantedId = seed.id.removePrefix("seed_")
                require(plantedId in CropDefinitions.CROPS && engine.consumeItem(seed, 1)) { "seed_required" }
                engine.logEvent(petId, EventType.PLANTED, context.getString(R.string.tama_event_planted, cropDisplayName(context, plantedId)))
                tile.copy(crop = PlantedCrop(plantedId, plantedTime = now, lastStageUpdateTime = now))
            }
            FarmPlotAction.WATER, FarmPlotAction.POUR_WATER -> {
                require(tile.status == TileStatus.FARMLAND) { "dry_tilled_plot_required" }
                val water = pet.inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
                require(water != null || farm.consumeWater(petId, rescheduleNotifications = false)) { "water_required" }
                if (water != null) require(engine.consumeItem(water, 1)) { "water_required" }
                if (action == FarmPlotAction.WATER) {
                    require(engine.consumeFarmToolDurability("watering_can", 1) == 1) { "tool_required" }
                }
                engine.logEvent(petId, EventType.WATERED, context.getString(R.string.tama_event_poured_water))
                tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = now)
            }
            FarmPlotAction.HARVEST_CROP -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.stage == 3 && !crop.isDecayed) { "crop_not_ready" }
                val seed = "$seedBase:$plotId:${crop.type}:${crop.plantedTime}"
                val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                val outcome = (java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL) / 4294967296.0
                require(engine.harvestCrop(crop, outcome.toFloat()).success) { "harvest_failed" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmPlotAction.REMOVE_DEAD_CROP -> {
                val crop = tile.crop ?: error("dead_crop_required")
                require(crop.isDecayed && engine.harvestCrop(crop).success) { "dead_crop_required" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmPlotAction.FERTILIZE -> {
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
        }
        if (changed != tile) farm.saveTile(petId, changed, rescheduleNotifications = false)
    }
}
