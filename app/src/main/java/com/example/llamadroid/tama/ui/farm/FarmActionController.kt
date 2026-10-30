package com.example.llamadroid.tama.ui

import android.content.Context
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.farmTileIdsForPage
import com.example.llamadroid.tama.game.FarmBatchResult
import com.example.llamadroid.tama.game.FarmPlotAction
import com.example.llamadroid.tama.game.FarmSmartActions
import com.example.llamadroid.tama.game.TamaGameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the smart tap and the bulk chores of [FarmSmartActions] and turns the outcome into feedback: result toast,
 * plot bounce, light haptic on harvest/plant and floating "+N crop" pops (diffed from the pet inventory).
 */
internal class FarmActionController(
    private val context: Context,
    private val notice: TamaNoticeState,
    private val engine: TamaGameEngine,
    private val scope: CoroutineScope,
    private val juice: FarmJuiceState,
    private val haptic: HapticFeedback
) {
    /** Smart tap on one plot (no tool selected). [seedId] is the crop id to plant when the plot is ready for seeds. */
    fun smartTap(plotId: Int, seedId: String?) {
        scope.launch {
            val before = engine.pet.value?.inventory.orEmpty()
            val result = FarmSmartActions.smartAction(context, engine, plotId, seedId)
            if (result.count > 0) {
                juice.bounce(plotId)
                if (result.action == FarmPlotAction.WATER || result.action == FarmPlotAction.POUR_WATER) juice.waterSplash(plotId)
                if (farmActionHasHaptic(result.action)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (result.action == FarmPlotAction.HARVEST_CROP) addGainPops(plotId, before)
                if (result.action == FarmPlotAction.CLEAR_OBSTACLE) addMaterialPops(plotId, before)
            }
            toast(result.message)
        }
    }

    /** Bulk chore on one farm [page]. [tiles] is the current grid, used only to place the gain pops. */
    fun batch(kind: FarmQuickAction, page: Int, tiles: List<FarmTile>, seedId: String? = null) {
        scope.launch {
            val before = engine.pet.value?.inventory.orEmpty()
            val result = when (kind) {
                FarmQuickAction.HARVEST -> FarmSmartActions.harvestAll(context, engine, page)
                FarmQuickAction.WATER -> FarmSmartActions.waterAll(context, engine, page, allowItems = true)
                FarmQuickAction.PLANT ->
                    if (seedId == null) null else FarmSmartActions.plantAll(context, engine, page, seedId)
                FarmQuickAction.TILL -> FarmSmartActions.tillAll(context, engine, page)
                FarmQuickAction.CLEAR -> FarmSmartActions.clearObstaclesAll(context, engine, page)
            }
            if (result == null) {
                toast(context.getString(R.string.tama_farm_limit_seeds))
                return@launch
            }
            val ids = farmTileIdsForPage(page)
            var gained = false
            if (result.count > 0) {
                ids.forEach { juice.bounce(it) }
                if (kind == FarmQuickAction.WATER) ids.forEach { juice.waterSplash(it) }
                if (farmActionHasHaptic(result.action)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (kind == FarmQuickAction.CLEAR) {
                    // Pops rise from the first plot that held an obstacle (the tiles list is the pre-clear grid).
                    val anchor = tiles.filter { it.id in ids && it.obstacle != null }.minOfOrNull { it.id } ?: ids.first
                    addMaterialPops(anchor, before)
                }
                if (kind == FarmQuickAction.HARVEST) {
                    val anchor = tiles.filter { it.id in ids && it.crop?.let { c -> c.stage >= 3 && !c.isDecayed } == true }
                        .minOfOrNull { it.id } ?: ids.first
                    gained = addGainPops(anchor, before)
                }
            }
            toast(batchMessage(kind, result, gained))
        }
    }

    private fun addGainPops(anchorPlot: Int, before: List<InventoryItem>): Boolean {
        val gains = farmInventoryGains(before, engine.pet.value?.inventory.orEmpty())
        gains.take(MAX_POPS).forEach { (item, count) -> juice.addPop(anchorPlot, item, count) }
        return gains.isNotEmpty()
    }

    private fun addMaterialPops(anchorPlot: Int, before: List<InventoryItem>) {
        farmMaterialGains(before, engine.pet.value?.inventory.orEmpty())
            .forEach { (itemId, count) -> juice.addMaterialPop(anchorPlot, itemId, count) }
    }

    private fun batchMessage(kind: FarmQuickAction, result: FarmBatchResult, gained: Boolean): String {
        val limit = farmLimitRes(result.limitCode)?.let { context.getString(it) }
        if (result.count <= 0) return limit ?: result.message
        // Harvest-all that only cleared dead plants has no gain to report; keep the engine's own text then.
        if (kind == FarmQuickAction.HARVEST && !gained) return result.message
        val done = context.resources.getQuantityString(farmBatchPluralRes(kind), result.count, result.count)
        return if (limit != null) context.getString(R.string.tama_farm_batch_with_limit, done, limit) else done
    }

    private fun toast(message: String) {
        notice.show(message)
    }

    private companion object {
        const val MAX_POPS = 3
    }
}
