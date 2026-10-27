package com.example.llamadroid.tama.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FARM_STONE_ID
import com.example.llamadroid.tama.data.FARM_WOOD_ID
import com.example.llamadroid.tama.data.FarmMaterialCosts
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.farmTileIdsForPage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.farmCropDryFrom
import com.example.llamadroid.tama.data.farmStageDurationMs
import com.example.llamadroid.tama.data.farmVirtualTime
import com.example.llamadroid.tama.game.FarmPlotAction

/*
 * Farm "juice": transient visual feedback (bounce, gain pops, sparkle) plus the pure helpers behind it.
 * All animation is skipped when the system animator scale is 0 (reduced motion).
 */

/** Holder for the farm's transient feedback. Created once per [FarmScreen] and shared through [LocalFarmJuice]. */
@Stable
internal class FarmJuiceState(context: Context) {
    /** A floating "+N crop" gain anchored to a plot. */
    class Pop(val id: Long, val plotId: Int, val item: InventoryItem, val count: Int)

    val reducedMotion: Boolean = farmReducedMotion(context)
    /** A floating "+N wood/stone" anchored to the plot whose obstacle was just cleared. */
    class MaterialPop(val id: Long, val plotId: Int, val itemId: String, val count: Int)

    val pops = mutableStateListOf<Pop>()
    val materialPops = mutableStateListOf<MaterialPop>()
    val bounces = mutableStateMapOf<Int, Int>()

    /** 4-frame strips under assets/farm/Others/ (frame width = strip width / 4); null falls back to the Compose twinkle / nothing. */
    val sparkleStrip: ImageBitmap? = loadFarmFxBitmap(context, "fx_ready_sparkle")
    val waterDropStrip: ImageBitmap? = loadFarmFxBitmap(context, "fx_water_drop")
    val woodChipsStrip: ImageBitmap? = loadFarmFxBitmap(context, "fx_wood_chips")
    val rockDustStrip: ImageBitmap? = loadFarmFxBitmap(context, "fx_rock_dust")

    /** Per-plot counter bumped on every water action so the splash replays. */
    val waterSplashes = mutableStateMapOf<Int, Int>()

    private var nextPopId = 0L

    fun bounce(plotId: Int) {
        if (reducedMotion) return
        bounces[plotId] = (bounces[plotId] ?: 0) + 1
    }

    fun waterSplash(plotId: Int) {
        if (reducedMotion) return
        waterSplashes[plotId] = (waterSplashes[plotId] ?: 0) + 1
    }

    fun addPop(plotId: Int, item: InventoryItem, count: Int) {
        if (reducedMotion || count <= 0) return
        pops.add(Pop(nextPopId++, plotId, item, count))
    }

    fun removePop(id: Long) {
        pops.removeAll { it.id == id }
    }

    fun addMaterialPop(plotId: Int, itemId: String, count: Int) {
        if (reducedMotion || count <= 0) return
        materialPops.add(MaterialPop(nextPopId++, plotId, itemId, count))
    }

    fun removeMaterialPop(id: Long) {
        materialPops.removeAll { it.id == id }
    }
}

internal val LocalFarmJuice = staticCompositionLocalOf<FarmJuiceState?> { null }

/** True when the user turned system animations off (Settings.Global.ANIMATOR_DURATION_SCALE == 0). */
internal fun farmReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * Overall growth progress 0..1 of a growing crop on the plot's moisture-aware clock, null when there is no
 * growing crop. Ripe crops report 1.
 */
internal fun farmCropProgress(tile: FarmTile, now: Long): Float? {
    val crop = tile.crop ?: return null
    if (crop.isDecayed) return null
    if (crop.stage >= 3) return 1f
    val definition = CropDefinitions.CROPS[crop.type] ?: return null
    var total = 0L
    var remaining = 0L
    for (stage in 0 until 3) {
        val duration = farmStageDurationMs(definition, crop.isFertilized, stage)
        total += duration
        if (stage >= crop.stage) remaining += duration
    }
    if (total <= 0L) return null
    val virtualNow = farmVirtualTime(now, farmCropDryFrom(tile, crop))
    val remainingVirtual = (crop.lastStageUpdateTime + remaining - virtualNow).coerceIn(0L, total)
    return (1f - remainingVirtual.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** Crop items whose stock grew between two inventory snapshots, with the gained amount (used for harvest pops). */
internal fun farmInventoryGains(before: List<InventoryItem>, after: List<InventoryItem>): List<Pair<InventoryItem, Int>> {
    val oldTotals = before.groupBy { it.id }.mapValues { (_, items) -> items.sumOf { it.quantity } }
    return after.filter { it.type == ItemType.CROP }
        .groupBy { it.id }
        .mapNotNull { (id, items) ->
            val delta = items.sumOf { it.quantity } - (oldTotals[id] ?: 0)
            if (delta > 0) items.first() to delta else null
        }
}

/** Wood/stone whose stock grew between two inventory snapshots (obstacle-clear pops), as item id to gained amount. */
internal fun farmMaterialGains(before: List<InventoryItem>, after: List<InventoryItem>): List<Pair<String, Int>> =
    listOf(FARM_WOOD_ID, FARM_STONE_ID).mapNotNull { id ->
        val delta = FarmMaterialCosts.owned(after, id) - FarmMaterialCosts.owned(before, id)
        if (delta > 0) id to delta else null
    }

/** Obstacles on the plots of farm [page]; the "Clear" quick action only shows while this is above zero. */
internal fun farmClearableObstacleCount(tiles: List<FarmTile>, page: Int): Int {
    val ids = farmTileIdsForPage(page).toSet()
    return tiles.count { it.id in ids && it.obstacle != null }
}

/** The bulk chores shown in the farm action bar. CLEAR is only offered while the page has obstacles. */
internal enum class FarmQuickAction { HARVEST, WATER, PLANT, TILL, CLEAR }

internal fun farmBatchPluralRes(kind: FarmQuickAction): Int = when (kind) {
    FarmQuickAction.HARVEST -> R.plurals.tama_farm_batch_harvested
    FarmQuickAction.WATER -> R.plurals.tama_farm_batch_watered
    FarmQuickAction.PLANT -> R.plurals.tama_farm_batch_planted
    FarmQuickAction.TILL -> R.plurals.tama_farm_batch_tilled
    FarmQuickAction.CLEAR -> R.plurals.tama_farm_batch_cleared
}

/** Short "Need water/seeds/tool" text for the limit codes of [com.example.llamadroid.tama.game.FarmBatchResult], or null. */
internal fun farmLimitRes(limitCode: String?): Int? = when (limitCode) {
    "water_required" -> R.string.tama_farm_limit_water
    "seed_required" -> R.string.tama_farm_limit_seeds
    "tool_required" -> R.string.tama_farm_limit_tool
    "axe_required" -> R.string.tama_farm_err_axe
    "pickaxe_required" -> R.string.tama_farm_err_pickaxe
    else -> null
}

/** Actions that earn a light haptic tick. */
internal fun farmActionHasHaptic(action: FarmPlotAction?): Boolean =
    action == FarmPlotAction.HARVEST_CROP || action == FarmPlotAction.PLANT
