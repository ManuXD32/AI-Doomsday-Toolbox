package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.cropDisplayName
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.FARM_OBSTACLE_TOOL_DURABILITY
import com.example.llamadroid.tama.data.FarmObstacle
import com.example.llamadroid.tama.data.WorldResourceCatalog
import com.example.llamadroid.tama.data.farmObstacleYield
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.data.advanceFarmTileCrop
import com.example.llamadroid.tama.data.farmCropDryFrom
import com.example.llamadroid.tama.data.farmHarvestOutcome
import com.example.llamadroid.tama.data.farmToolTotalDurability
import com.example.llamadroid.tama.data.farmVirtualTime
import com.example.llamadroid.tama.data.farmWaterTile
import com.example.llamadroid.tama.data.isWet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import kotlinx.coroutines.CancellationException

/** Plot actions the farm panel can issue; each one is a single classic transaction. */
enum class FarmPlotAction { TILL_SOIL, PLANT, WATER, POUR_WATER, FERTILIZE, HARVEST_CROP, REMOVE_DEAD_CROP, CLEAR_OBSTACLE }

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
                val message = apply(context, database, farm, engine, pet.id, plotId, action, arguments["cropId"],
                    System.currentTimeMillis(), pet.id.hashCode().toLong())
                workKindFor(action)?.let { PetFarmWork.onPetFarmWork(context, engine, pet.id, it, 1) }
                TamaCommitEffects.deferOrRun("notifications:${pet.id}") {
                    TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
                }
                message
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FarmActionMessages.describe(context, error)
        }
    }

    /** The pet-participation category of an action, or null when the pet does not help with it. */
    internal fun workKindFor(action: FarmPlotAction): PetFarmWorkKind? = when (action) {
        FarmPlotAction.HARVEST_CROP -> PetFarmWorkKind.HARVEST
        FarmPlotAction.PLANT -> PetFarmWorkKind.PLANT
        FarmPlotAction.WATER, FarmPlotAction.POUR_WATER -> PetFarmWorkKind.WATER
        FarmPlotAction.CLEAR_OBSTACLE -> PetFarmWorkKind.CLEAR
        else -> null
    }

    /**
     * Plot rules. The caller owns the action gate and the Room transaction. Returns the localized
     * result (e.g. the harvest toast); failures throw and are described by [FarmActionMessages].
     */
    internal suspend fun apply(
        context: Context, database: TamaDatabase, farm: FarmRepository,
        engine: TamaGameEngine, petId: String, plotId: Int, action: FarmPlotAction,
        cropId: String?, now: Long, seedBase: Long
    ): String {
        // Bring the crop up to date first so a stale stage-3 plot is rotten, not harvestable fresh.
        val tile = farm.getTiles(petId).firstOrNull { it.id == plotId }?.let { advanceFarmTileCrop(it, now) }
            ?: error("farm_plot_locked")
        var message = context.getString(R.string.tama_classic_action_done)
        val pet = database.tamaDao().getPet(petId)?.let(PetMapper::toDomain) ?: error("pet_missing")
        val changed = when (action) {
            FarmPlotAction.TILL_SOIL -> {
                require(tile.status == TileStatus.SOIL && tile.crop == null) { "already_tilled" }
                // Weeds are cleared by the hoe as it tills; a stump or rock must be cleared with its own tool first.
                require(tile.obstacle == null || tile.obstacle == FarmObstacle.WEEDS) { "obstacle_blocked" }
                require(farmToolTotalDurability(pet.inventory, "hoe") >= 1) { "tool_required" }
                require(engine.consumeFarmToolDurability("hoe", 1) == 1) { "tool_required" }
                tile.copy(status = TileStatus.FARMLAND, obstacle = null)
            }
            FarmPlotAction.PLANT -> {
                require(tile.obstacle == null) { "obstacle_blocked" }
                require(tile.status != TileStatus.SOIL && tile.crop == null) { "empty_tilled_plot_required" }
                val seed = pet.inventory.firstOrNull { it.type == ItemType.SEED && it.quantity > 0 &&
                    (cropId == null || it.id == "seed_$cropId") } ?: error("seed_required")
                val plantedId = seed.id.removePrefix("seed_")
                require(plantedId in CropDefinitions.CROPS && engine.consumeItem(seed, 1)) { "seed_required" }
                message = context.getString(R.string.tama_event_planted, cropDisplayName(context, plantedId))
                engine.logEvent(petId, EventType.PLANTED, message)
                tile.copy(crop = PlantedCrop(plantedId, plantedTime = now, lastStageUpdateTime = now, moistureTracked = true))
            }
            FarmPlotAction.WATER, FarmPlotAction.POUR_WATER -> {
                require(tile.status != TileStatus.SOIL) { "dry_tilled_plot_required" }
                require(!tile.isWet(now)) { "already_wet" }
                val waterItem = pet.inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
                if (action == FarmPlotAction.WATER) {
                    // The can refills from the well: no inventory item, only tool durability. It falls back to a
                    // water item when the well has nothing ready, so owning a can is never a regression.
                    require(farmToolTotalDurability(pet.inventory, "watering_can") >= 1) { "tool_required" }
                    val fromWell = farm.wellWaterAvailable(petId, now) > 0
                    require(fromWell || waterItem != null) { "water_required" }
                    require(engine.consumeFarmToolDurability("watering_can", 1) == 1) { "tool_required" }
                    if (fromWell) check(farm.consumeWellWater(petId, now)) else check(engine.consumeItem(waterItem!!, 1))
                } else {
                    require(waterItem != null && engine.consumeItem(waterItem, 1)) { "water_required" }
                }
                message = context.getString(R.string.tama_event_poured_water)
                engine.logEvent(petId, EventType.WATERED, message)
                farmWaterTile(tile, now)
            }
            FarmPlotAction.HARVEST_CROP -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.stage == 3 && !crop.isDecayed) { "crop_not_ready" }
                val outcome = farmHarvestOutcome(seedBase, plotId, crop)
                val harvest = engine.harvestCrop(crop, outcome)
                require(harvest.success) { "harvest_failed" }
                message = harvest.message
                tile.copy(crop = null, status = TileStatus.FARMLAND, lastWateredTime = null)
            }
            FarmPlotAction.REMOVE_DEAD_CROP -> {
                val crop = tile.crop ?: error("dead_crop_required")
                require(crop.isDecayed) { "dead_crop_required" }
                val cleared = engine.harvestCrop(crop)
                require(cleared.success) { "dead_crop_required" }
                message = cleared.message
                tile.copy(crop = null, status = TileStatus.FARMLAND, lastWateredTime = null)
            }
            FarmPlotAction.CLEAR_OBSTACLE -> {
                val obstacle = tile.obstacle ?: error("obstacle_required")
                val family = obstacle.toolFamily
                if (family != null) {
                    // Pre-check, then spend: a missing/broken tool throws before anything is consumed.
                    require(farmToolTotalDurability(pet.inventory, family) >= FARM_OBSTACLE_TOOL_DURABILITY) {
                        obstacle.missingToolCode ?: "tool_required"
                    }
                    require(engine.consumeFarmToolDurability(family, FARM_OBSTACLE_TOOL_DURABILITY) ==
                        FARM_OBSTACLE_TOOL_DURABILITY) { obstacle.missingToolCode ?: "tool_required" }
                }
                val amount = obstacle.yieldItemId?.let { itemId ->
                    farmObstacleYield(java.util.Random(seedBase xor (plotId * 131L) xor now).nextInt()).also { qty ->
                        check(engine.grantItem(WorldResourceCatalog.item(itemId), qty))
                    }
                } ?: 0
                message = when (obstacle) {
                    FarmObstacle.STUMP -> context.getString(R.string.tama_farm_obstacle_cleared_stump, amount)
                    FarmObstacle.ROCK -> context.getString(R.string.tama_farm_obstacle_cleared_rock, amount)
                    FarmObstacle.WEEDS -> context.getString(R.string.tama_farm_obstacle_cleared_weeds)
                }
                engine.logEvent(petId, EventType.OTHER, message)
                tile.copy(obstacle = null)
            }
            FarmPlotAction.FERTILIZE -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.isDecayed || (crop.stage < 3 && !crop.isFertilized)) { "fertilizer_not_needed" }
                val fertilizer = pet.inventory.firstOrNull { it.id == "fertilizer" && it.quantity > 0 }
                    ?: error("fertilizer_required")
                require(engine.consumeItem(fertilizer, 1)) { "fertilizer_required" }
                val revived = if (crop.isDecayed) crop.copy(isDecayed = false,
                    lastStageUpdateTime = farmVirtualTime(now, farmCropDryFrom(tile, crop)))
                    else crop.copy(isFertilized = true)
                message = context.getString(if (crop.isDecayed)
                    R.string.tama_event_revived_plant else R.string.tama_event_applied_fertilizer)
                engine.logEvent(petId, EventType.OTHER, message)
                tile.copy(crop = revived)
            }
        }
        farm.saveTile(petId, changed, rescheduleNotifications = false)
        return message
    }
}
