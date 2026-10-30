package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.example.llamadroid.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.WorldFarmMaintenance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.llamadroid.tama.game.FarmPlotAction
import com.example.llamadroid.tama.game.ClassicFarmPlots
import com.example.llamadroid.tama.game.FARM_COMPOSTER_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_COMPOSTER_PROCESS_MS
import com.example.llamadroid.tama.game.FARM_WELL_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_MAX_SPEED_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.FARM_COMPOSTER_COST
import com.example.llamadroid.tama.game.composterCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.composterSlotCapacityForLevel
import com.example.llamadroid.tama.game.wellCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.wellIntervalHoursForSpeedLevel
import com.example.llamadroid.tama.game.wellProductionIntervalForSpeedLevel
import com.example.llamadroid.tama.game.wellSpeedUpgradeCostForLevel
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.wellCapacityForLevel
import com.example.llamadroid.ui.components.pressAndHoldRepeat
import com.example.llamadroid.ui.components.rememberPressAndHoldRepeatState
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FarmPlotFrame = Color(0xFF3B2412)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FarmTileItem(
    tile: FarmTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    currentTime: Long = System.currentTimeMillis(),
    // Extension slot drawn above the crop and below nothing else (F5: juice, smart-tap hints, particles).
    overlay: @Composable BoxScope.(FarmTile) -> Unit = {}
) {
    val context = LocalContext.current
    val plotState = tile.obstacle?.takeIf { tile.crop == null }?.let { obstacle ->
        stringResource(farmObstacleNameRes(obstacle))
    } ?: tile.crop?.let { crop ->
        val name = cropDisplayName(context, crop.type)
        if (crop.isDecayed) stringResource(R.string.soft_studio_farm_decayed, name)
        else stringResource(R.string.soft_studio_farm_crop, name, crop.stage + 1)
    } ?: stringResource(when (tile.status) {
        TileStatus.SOIL -> R.string.soft_studio_farm_soil
        TileStatus.FARMLAND -> R.string.soft_studio_farm_tilled
        TileStatus.WET_FARMLAND -> R.string.soft_studio_farm_watered
    })
    val plotLabel = stringResource(R.string.soft_studio_farm_plot, tile.id + 1, plotState)
    val inspectLabel = stringResource(R.string.soft_studio_farm_inspect)
    val bounceModifier = Modifier.farmTileBounce(tile.id)
    // The stored status can lag behind the clock: show a plot dry as soon as its watering has run out.
    val visibleStatus = if (tile.status == TileStatus.WET_FARMLAND && !tile.isWet(currentTime)) TileStatus.FARMLAND else tile.status
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .then(bounceModifier)
            .semantics(mergeDescendants = true) { contentDescription = plotLabel }
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = inspectLabel,
                onLongClick = if (tile.crop != null) onLongPress else null
            ),
        shape = RoundedCornerShape(8.dp),
        // Dark frame so plots read clearly on the painted open field behind the grid.
        border = BorderStroke(2.dp, FarmPlotFrame),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FarmTileBackdrop(status = visibleStatus, hasCrop = tile.crop != null)

            // Stump / rock / weeds sprite on idle soil; a missing asset simply draws nothing.
            tile.obstacle?.takeIf { tile.crop == null }?.let { obstacle ->
                AsyncImage(
                    model = "file:///android_asset/${obstacle.assetPath}",
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .scale(FARM_OBSTACLE_TILE_SCALE),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
            }

            tile.crop?.let { crop ->
                val cropPath = when (crop.stage) {
                    0 -> "seed/${crop.type}.png"
                    1 -> "stage_1/${crop.type}.png"
                    2 -> "stage_2/${crop.type}.png"
                    3 -> if (crop.isDecayed) null else "stage_final/${crop.type}.png"
                    else -> ""
                }

                if (crop.isDecayed) {
                    AsyncImage(
                        model = "file:///android_asset/farm/Others/rotten_crop.png",
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(1.dp)
                            .scale(FARM_DECAY_TILE_SCALE),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                } else {
                    val cropAssetUri = remember(cropPath) { "file:///android_asset/farm/Crops/$cropPath" }
                    AsyncImage(
                        model = cropAssetUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(1.dp)
                            .scale(farmTileScaleForCrop(crop))
                            .farmCropSway(tile.id),
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                    )
                }

                // Timer
                if (crop.stage < 3 && !crop.isDecayed) {
                    val remaining = calculateRemaining(tile, currentTime)
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (crop.isFertilized) {
                            AsyncImage(
                                model = "file:///android_asset/farm/Others/fertilizer.png",
                                contentDescription = null,
                                modifier = Modifier
                                    .size(30.dp)
                                    .padding(end = 4.dp),
                                    filterQuality = FilterQuality.None
                                )
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                remaining,
                                color = Color.White,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            overlay(tile)
            FarmObstacleClearFx(tile)
            LocalFarmJuice.current?.let { juice ->
                juice.materialPops.filter { it.plotId == tile.id }.forEach { pop ->
                    key(pop.id) { FarmMaterialGainPop(pop) { juice.removeMaterialPop(pop.id) } }
                }
            }
        }
    }
}

@StringRes
internal fun farmObstacleNameRes(obstacle: FarmObstacle): Int = when (obstacle) {
    FarmObstacle.STUMP -> R.string.tama_farm_obstacle_stump
    FarmObstacle.ROCK -> R.string.tama_farm_obstacle_rock
    FarmObstacle.WEEDS -> R.string.tama_farm_obstacle_weeds
}

/**
 * Plays the 4-frame wood-chips / rock-dust strip once when this plot's stump or rock disappears (any path: smart
 * tap, tool tap, "Clear" quick action). Weeds have no strip. No-op without a [LocalFarmJuice] or in reduced motion.
 */
@Composable
private fun BoxScope.FarmObstacleClearFx(tile: FarmTile) {
    val juice = LocalFarmJuice.current ?: return
    var previous by remember { mutableStateOf(tile.obstacle) }
    var fxStrip by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val progress = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(tile.obstacle) {
        val was = previous
        previous = tile.obstacle
        if (was != null && tile.obstacle == null && !juice.reducedMotion) {
            fxStrip = when (was) {
                FarmObstacle.STUMP -> juice.woodChipsStrip
                FarmObstacle.ROCK -> juice.rockDustStrip
                FarmObstacle.WEEDS -> null
            }
            if (fxStrip != null) {
                progress.snapTo(0f)
                progress.animateTo(1f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.LinearEasing))
            }
        }
    }
    val strip = fxStrip
    if (strip != null && progress.value < 1f) {
        FarmFxFrame(strip, (progress.value * FARM_FX_STRIP_FRAMES).toInt())
    }
}

/** Pixel-art soil for a plot: dry, tilled, watered, or the planted variant (watered plots keep the wet soil under the crop). */
internal fun farmSoilAssetFor(status: TileStatus, hasCrop: Boolean): String {
    val name = when {
        hasCrop && status == TileStatus.WET_FARMLAND -> "soil_wet"
        hasCrop -> "soil_planted"
        status == TileStatus.SOIL -> "soil_dry"
        status == TileStatus.FARMLAND -> "soil_tilled"
        else -> "soil_wet"
    }
    return "file:///android_asset/farm/Others/$name.png"
}

@Composable
private fun FarmTileBackdrop(status: TileStatus, hasCrop: Boolean) {
    AsyncImage(
        model = farmSoilAssetFor(status, hasCrop),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.None
    )
}

internal fun farmTileScaleForCrop(crop: PlantedCrop): Float {
    if (crop.isDecayed) return FARM_DECAY_TILE_SCALE
    return when (crop.stage) {
        0 -> FARM_SEED_TILE_SCALE
        1 -> FARM_STAGE_1_TILE_SCALE
        2 -> FARM_STAGE_2_TILE_SCALE
        else -> FARM_STAGE_FINAL_TILE_SCALE
    }
}

internal fun canWaterFarmTile(tile: FarmTile): Boolean {
    return tile.status == TileStatus.FARMLAND
}

/** True when [toolId] is [family] itself or one of its variants ("axe", "axe_starter"). */
internal fun farmToolIsFamily(toolId: String?, family: String): Boolean =
    toolId == family || toolId?.startsWith("${family}_") == true

/**
 * The plot action a selected tool performs on [tile], or null when it does nothing there. The axe clears stumps and
 * the pickaxe rocks (either also pulls weeds); the hoe tills (clearing weeds on the way). A missing tool surfaces
 * through the action's own "Needs an axe/pickaxe" message.
 */
internal fun farmTileActionForTool(tile: FarmTile, toolId: String?): FarmPlotAction? = when {
    farmToolIsFamily(toolId, "hoe") ->
        FarmPlotAction.TILL_SOIL.takeIf { tile.status == TileStatus.SOIL && tile.crop == null }
    farmToolIsFamily(toolId, "axe") || farmToolIsFamily(toolId, "pickaxe") -> {
        val family = if (farmToolIsFamily(toolId, "axe")) "axe" else "pickaxe"
        FarmPlotAction.CLEAR_OBSTACLE.takeIf {
            tile.obstacle != null && (tile.obstacle.toolFamily == null || tile.obstacle.toolFamily == family)
        }
    }
    farmToolIsFamily(toolId, "watering_can") ->
        FarmPlotAction.WATER.takeIf { canWaterFarmTile(tile) }
    toolId == "water" -> FarmPlotAction.POUR_WATER.takeIf { canWaterFarmTile(tile) }
    toolId == "fertilizer" -> FarmPlotAction.FERTILIZE.takeIf {
        tile.crop?.isDecayed == true || (tile.crop != null && tile.crop.stage < 3 && !tile.crop.isFertilized)
    }
    else -> null
}

internal fun handleTileClick(
    tile: FarmTile,
    tool: InventoryItem?,
    gameEngine: TamaGameEngine,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    notice: TamaNoticeState,
    onActionRequested: (FarmPlotAction) -> Unit = {},
    // When given, a stump/rock clear also floats the "+N wood/stone" pop.
    juice: FarmJuiceState? = null
) {
    val action = farmTileActionForTool(tile, tool?.id)
    action?.let { requested ->
        scope.launch {
            val before = gameEngine.pet.value?.inventory.orEmpty()
            val message = ClassicFarmPlots.request(context, gameEngine, tile.id, requested)
            notice.show(message)
            if (requested == FarmPlotAction.CLEAR_OBSTACLE && juice != null) {
                farmMaterialGains(before, gameEngine.pet.value?.inventory.orEmpty())
                    .forEach { (itemId, count) -> juice.addMaterialPop(tile.id, itemId, count) }
            }
            onActionRequested(requested)
        }
    }
}

@Composable
fun SeedPickerDialog(
    inventory: List<InventoryItem>,
    onDismiss: () -> Unit,
    onSeedSelected: (InventoryItem) -> Unit
) {
    val context = LocalContext.current
    val seeds = inventory.filter { it.type == ItemType.SEED }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_farm_select_seed)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                items(seeds) { seed ->
                    val cropType = seed.id.removePrefix("seed_")
                    ListItem(
                        leadingContent = {
                            AsyncImage(
                                model = "file:///android_asset/farm/Crops/stage_final/$cropType.png",
                                contentDescription = stringResource(R.string.tama_farm_mature_preview_description, inventoryItemDisplayName(context, seed)),
                                modifier = Modifier
                                    .size(58.dp)
                                    .scale(FARM_MATURE_PREVIEW_SCALE),
                                contentScale = ContentScale.Fit,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                            )
                        },
                        headlineContent = { Text(inventoryItemDisplayName(context, seed)) },
                        supportingContent = { Text(stringResource(R.string.tama_farm_available_quantity, seed.quantity)) },
                        modifier = Modifier.clickable { onSeedSelected(seed) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
internal fun CropInspectDialog(
    tile: FarmTile,
    now: Long,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val crop = tile.crop ?: return
    val cropName = cropDisplayName(context, crop.type)
    val status = when {
        crop.isDecayed -> stringResource(R.string.tama_farm_crop_inspect_wilted)
        crop.stage >= 3 -> stringResource(R.string.tama_farm_crop_inspect_ready)
        else -> stringResource(R.string.tama_farm_crop_inspect_growing, calculateRemaining(tile, now))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cropName, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.tama_farm_crop_inspect_title, cropName))
                Text(status)
                if (crop.stage < 3 && !crop.isDecayed && farmTileNeedsWater(tile, now)) {
                    Text(stringResource(R.string.tama_farm_crop_inspect_thirsty), color = TamaMutedText)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
