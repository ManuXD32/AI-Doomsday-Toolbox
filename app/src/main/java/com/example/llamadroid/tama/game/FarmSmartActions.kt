package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.tama.data.FARM_OBSTACLE_TOOL_DURABILITY
import com.example.llamadroid.tama.data.FarmObstacle
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.data.advanceFarmTileCrop
import com.example.llamadroid.tama.data.farmTileIdsForPage
import com.example.llamadroid.tama.data.farmTileNeedsWater
import com.example.llamadroid.tama.data.farmToolTotalDurability
import com.example.llamadroid.tama.data.isWet
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import kotlinx.coroutines.CancellationException

/**
 * Outcome of a smart tap or a bulk action. [action] is what was done (null when nothing applied), [count]
 * the plots changed, [skipped] the plots that did not qualify, [message] the localized text to show (the last
 * action's result, or the reason it failed), and [limitCode] the failure code that stopped a bulk run early
 * ("water_required", "tool_required", "seed_required", ...) so the UI can offer a shortcut (e.g. open the shop).
 */
data class FarmBatchResult(
    val action: FarmPlotAction?,
    val success: Boolean,
    val count: Int,
    val skipped: Int,
    val message: String,
    val limitCode: String? = null
)

/**
 * One-tap farm controls built on [ClassicFarmPlots]: [smartAction] picks the right chore for a plot, the
 * `*All` functions repeat a chore across one farm page. Each call is a single classic transaction.
 */
object FarmSmartActions {
    /** Which water action the pet can afford right now: the can (well water) first, then a water item. */
    internal fun waterActionFor(inventory: List<InventoryItem>, wellCharges: Int, allowItems: Boolean): FarmPlotAction? {
        val item = inventory.any { it.id == "water" && it.quantity > 0 }
        val canUsable = farmToolTotalDurability(inventory, "watering_can") >= 1
        return when {
            canUsable && (wellCharges > 0 || (allowItems && item)) -> FarmPlotAction.WATER
            allowItems && item -> FarmPlotAction.POUR_WATER
            else -> null
        }
    }

    /**
     * Pure decision for a smart tap. [tile] must already be advanced to `now`. Returns WATER as the generic
     * "water" intent (the caller resolves can vs pour), or null when there is nothing useful to do.
     */
    internal fun decide(tile: FarmTile, now: Long, hasSeed: Boolean, canWater: Boolean): FarmPlotAction? {
        // An obstacle always comes first; a missing axe/pickaxe surfaces as "axe_required"/"pickaxe_required".
        if (tile.obstacle != null) return FarmPlotAction.CLEAR_OBSTACLE
        val crop = tile.crop
        if (crop != null) {
            return when {
                crop.isDecayed -> FarmPlotAction.REMOVE_DEAD_CROP
                crop.stage >= 3 -> FarmPlotAction.HARVEST_CROP
                canWater && farmTileNeedsWater(tile, now) -> FarmPlotAction.WATER
                else -> null
            }
        }
        if (tile.status == TileStatus.SOIL) return FarmPlotAction.TILL_SOIL
        if (!hasSeed) return null
        // Tilled and empty: water first so the crop starts wet; without any water source plant anyway (50% speed).
        return if (!tile.isWet(now) && canWater) FarmPlotAction.WATER else FarmPlotAction.PLANT
    }

    /** Smart tap: till, water, plant [seedId], harvest or clear, whichever fits the plot. */
    suspend fun smartAction(context: Context, engine: TamaGameEngine, plotId: Int, seedId: String? = null): FarmBatchResult =
        transaction(context, engine) { env ->
            val tile = env.tile(plotId)
            val pet = env.pet()
            val hasSeed = seedId != null && pet.inventory.any { it.id == "seed_$seedId" && it.quantity > 0 }
            val waterAction = waterActionFor(pet.inventory, env.farm.wellWaterAvailable(env.petId, env.now), allowItems = true)
            val decision = decide(tile, env.now, hasSeed, waterAction != null)
                ?: return@transaction env.nothing(
                    if (tile.crop == null && tile.status != TileStatus.SOIL && !hasSeed) "seed_required" else "already_wet")
            val action = if (decision == FarmPlotAction.WATER) waterAction ?: decision else decision
            val message = env.apply(plotId, action, seedId)
            env.award(action, 1)
            FarmBatchResult(action, true, 1, 0, message)
        }

    /** Harvests every ripe crop on [page] and clears dead ones. */
    suspend fun harvestAll(context: Context, engine: TamaGameEngine, page: Int): FarmBatchResult =
        batch(context, engine, page, FarmPlotAction.HARVEST_CROP) { tile, _, _, _ ->
            val crop = tile.crop
            when {
                crop == null -> null
                crop.isDecayed -> FarmPlotAction.REMOVE_DEAD_CROP
                crop.stage >= 3 -> FarmPlotAction.HARVEST_CROP
                else -> null
            }
        }

    /**
     * Waters every dry growing crop on [page], limited by well water and watering-can durability. Water items
     * are only spent when [allowItems] is true (it falls back to pouring once the well or the can runs out).
     */
    suspend fun waterAll(context: Context, engine: TamaGameEngine, page: Int, allowItems: Boolean = false): FarmBatchResult =
        batch(context, engine, page, FarmPlotAction.WATER) { tile, pet, wells, now ->
            if (tile.crop == null || !farmTileNeedsWater(tile, now)) null
            else waterActionFor(pet.inventory, wells, allowItems) ?: FarmLimitSignal.WATER
        }

    /** Plants [seedId] on every empty tilled plot of [page] while seeds last. */
    suspend fun plantAll(context: Context, engine: TamaGameEngine, page: Int, seedId: String): FarmBatchResult =
        batch(context, engine, page, FarmPlotAction.PLANT, seedId) { tile, pet, _, _ ->
            if (tile.crop != null || tile.status == TileStatus.SOIL) null
            else if (pet.inventory.none { it.type == ItemType.SEED && it.id == "seed_$seedId" && it.quantity > 0 }) FarmLimitSignal.SEED
            else FarmPlotAction.PLANT
        }

    /**
     * Clears every obstacle of [page] it can: weeds by hand, stumps with an owned axe, rocks with an owned
     * pickaxe. Plots whose tool is missing are skipped (counted in `skipped`) so one missing tool never blocks
     * the rest; when nothing could be cleared the result carries "axe_required"/"pickaxe_required".
     */
    suspend fun clearObstaclesAll(context: Context, engine: TamaGameEngine, page: Int): FarmBatchResult {
        var missingCode: String? = null
        val result = batch(context, engine, page, FarmPlotAction.CLEAR_OBSTACLE) { tile, pet, _, _ ->
            val obstacle = tile.obstacle
            val family = obstacle?.toolFamily
            when {
                obstacle == null -> null
                family == null || farmToolTotalDurability(pet.inventory, family) >= FARM_OBSTACLE_TOOL_DURABILITY ->
                    FarmPlotAction.CLEAR_OBSTACLE
                else -> { missingCode = missingCode ?: obstacle.missingToolCode; null }
            }
        }
        val code = missingCode
        return if (result.count == 0 && code != null && result.limitCode == null) {
            result.copy(limitCode = code, message = FarmActionMessages.describe(context, IllegalStateException(code)))
        } else result
    }

    /** Tills every untilled plot of [page] while hoe durability lasts (weeds are cleared as part of tilling). */
    suspend fun tillAll(context: Context, engine: TamaGameEngine, page: Int): FarmBatchResult =
        batch(context, engine, page, FarmPlotAction.TILL_SOIL) { tile, pet, _, _ ->
            if (tile.crop != null || tile.status != TileStatus.SOIL) null
            else if (tile.obstacle != null && tile.obstacle != FarmObstacle.WEEDS) null
            else if (farmToolTotalDurability(pet.inventory, "hoe") < 1) FarmLimitSignal.TOOL
            else FarmPlotAction.TILL_SOIL
        }

    // ---- implementation ----

    /** Marker returned by a bulk picker to stop the run with a "limit reached" code. */
    private sealed class FarmLimitSignal {
        object WATER : FarmLimitSignal()
        object SEED : FarmLimitSignal()
        object TOOL : FarmLimitSignal()
    }

    private class Env(
        val context: Context,
        val engine: TamaGameEngine,
        val database: com.example.llamadroid.tama.db.TamaDatabase,
        val farm: FarmRepository,
        val petId: String,
        val now: Long
    ) {
        private val seedBase = petId.hashCode().toLong()
        suspend fun tile(plotId: Int): FarmTile =
            farm.getTiles(petId).firstOrNull { it.id == plotId }?.let { advanceFarmTileCrop(it, now) } ?: error("farm_plot_locked")

        suspend fun pet(): TamaPet =
            database.tamaDao().getPet(petId)?.let(PetMapper::toDomain) ?: error("pet_missing")

        suspend fun apply(plotId: Int, action: FarmPlotAction, cropId: String?): String =
            ClassicFarmPlots.apply(context, database, farm, engine, petId, plotId, action, cropId, now, seedBase)

        suspend fun award(action: FarmPlotAction, count: Int) {
            ClassicFarmPlots.workKindFor(action)?.let { PetFarmWork.onPetFarmWork(context, engine, petId, it, count) }
        }

        fun nothing(code: String): FarmBatchResult =
            FarmBatchResult(null, false, 0, 0, FarmActionMessages.describe(context, IllegalStateException(code)), code)
    }

    private suspend fun transaction(
        context: Context,
        engine: TamaGameEngine,
        block: suspend (Env) -> FarmBatchResult
    ): FarmBatchResult {
        return try {
            engine.runClassicTransaction { database, farm ->
                val pet = engine.pet.value ?: error("pet_missing")
                check(!pet.cycleFrozen) { "cycle_frozen" }
                val result = block(Env(context, engine, database, farm, pet.id, System.currentTimeMillis()))
                TamaCommitEffects.deferOrRun("notifications:${pet.id}") {
                    TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
                }
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FarmBatchResult(null, false, 0, 0, FarmActionMessages.describe(context, error), error.message)
        }
    }

    /**
     * Runs [pick] over the unlocked plots of [page] in id order. A pick that returns a [FarmPlotAction] is applied;
     * null skips the plot; a [FarmLimitSignal] stops the run. Every action pre-checks its costs before spending, so
     * stopping on a failure never leaves a half-spent plot.
     */
    private suspend fun batch(
        context: Context,
        engine: TamaGameEngine,
        page: Int,
        label: FarmPlotAction,
        cropId: String? = null,
        pick: suspend (FarmTile, TamaPet, Int, Long) -> Any?
    ): FarmBatchResult = transaction(context, engine) { env ->
        val ids = farmTileIdsForPage(page)
        val plots = env.farm.getTiles(env.petId).filter { it.id in ids }.sortedBy { it.id }
        val counts = HashMap<PetFarmWorkKind, Int>()
        var done = 0
        var skipped = 0
        var lastMessage: String? = null
        var lastAction: FarmPlotAction? = null
        var limit: String? = null
        for (plot in plots) {
            val tile = advanceFarmTileCrop(plot, env.now)
            val pet = env.pet()
            val choice = pick(tile, pet, env.farm.wellWaterAvailable(env.petId, env.now), env.now)
            when (choice) {
                null -> skipped++
                is FarmLimitSignal -> {
                    limit = when (choice) {
                        FarmLimitSignal.WATER -> "water_required"
                        FarmLimitSignal.SEED -> "seed_required"
                        FarmLimitSignal.TOOL -> "tool_required"
                    }
                    break
                }
                is FarmPlotAction -> try {
                    lastMessage = env.apply(plot.id, choice, cropId)
                    lastAction = choice
                    done++
                    ClassicFarmPlots.workKindFor(choice)?.let { counts.merge(it, 1, Int::plus) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    limit = error.message
                    break
                }
            }
        }
        for ((kind, count) in counts) {
            PetFarmWork.onPetFarmWork(env.context, env.engine, env.petId, kind, count)
        }
        val message = lastMessage
            ?: FarmActionMessages.describe(env.context, IllegalStateException(limit ?: "already_wet"))
        FarmBatchResult(lastAction ?: label.takeIf { done > 0 }, done > 0, done, skipped, message, limit)
    }
}
