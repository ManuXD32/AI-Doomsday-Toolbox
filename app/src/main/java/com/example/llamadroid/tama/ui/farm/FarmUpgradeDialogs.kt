package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

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
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
internal fun WellDialog(
    upgrade: FarmUpgradeEntity?,
    state: WellUpgradeState,
    now: Long,
    money: Long,
    onDismiss: () -> Unit,
    onCollectTile: (Int) -> Unit,
    onUpgradeCapacity: () -> Unit,
    onUpgradeSpeed: () -> Unit,
    // Owned wood/stone; null (unknown) shows the costs without blocking the buttons.
    materials: FarmMaterialStock? = null
) {
    val level = upgrade?.level ?: 1
    val capacity = state.slots.size.coerceAtLeast(wellCapacityForLevel(level))
    val readyTiles = state.slots.count { wellTileIsReady(it, state.speedLevel, now) }
    val maxedCapacity = level >= FARM_WELL_MAX_LEVEL
    val capacityUpgradeCost = wellCapacityUpgradeCostForLevel(level)
    val capacityMaterials = FarmMaterialCosts.wellCapacity(level)
    val canUpgradeCapacity = !maxedCapacity && money >= capacityUpgradeCost.toLong() && capacityMaterials.isCoveredBy(materials)
    val speedLevel = state.speedLevel
    val speedUpgradeCost = wellSpeedUpgradeCostForLevel(speedLevel)
    val speedMaxed = speedUpgradeCost == null || speedLevel >= FARM_WELL_MAX_SPEED_LEVEL
    val speedMaterials = FarmMaterialCosts.wellSpeed(speedLevel)
    val canUpgradeSpeed = !speedMaxed && money >= (speedUpgradeCost?.toLong() ?: Long.MAX_VALUE) && speedMaterials.isCoveredBy(materials)
    val context = LocalContext.current
    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_well_menu_title),
        backgroundAssetPath = "file:///android_asset/farm/Others/well_dialog_background.png",
        onDismiss = onDismiss,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onUpgradeCapacity,
                    enabled = canUpgradeCapacity,
                    modifier = Modifier.fillMaxWidth(),
                    colors = farmPopupButtonColors()
                ) {
                    Text(
                        if (maxedCapacity) {
                            stringResource(R.string.tama_farm_upgrade_maxed)
                        } else {
                            stringResource(R.string.tama_farm_well_capacity_upgrade_action, capacityUpgradeCost)
                        }
                    )
                }
                if (!maxedCapacity) FarmMaterialCostRow(capacityMaterials, materials, compact = true, onDark = true)
              }
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onUpgradeSpeed,
                    enabled = canUpgradeSpeed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = farmPopupButtonColors()
                ) {
                    Text(
                        if (speedMaxed) {
                            stringResource(R.string.tama_farm_upgrade_maxed)
                        } else {
                            stringResource(R.string.tama_farm_well_speed_upgrade_action, speedUpgradeCost ?: 0)
                        }
                    )
                }
                if (!speedMaxed) FarmMaterialCostRow(speedMaterials, materials, compact = true, onDark = true)
              }
            }
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_well_status_section)) {
            Text(
                stringResource(R.string.tama_farm_well_storage_summary, readyTiles, capacity),
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            Text(
                stringResource(R.string.tama_farm_well_speed_summary, wellIntervalHoursForSpeedLevel(speedLevel)),
                color = TamaDark
            )
            Text(stringResource(R.string.tama_farm_well_level, level), color = TamaDark)
            Text(overallWellStatusText(context, state, capacity, now), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_well_direct_use_hint), color = TamaMutedText)
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_well_tiles_section, capacity)) {
            FarmUtilityTileGrid(count = capacity) { index ->
                WellTileCard(
                    slot = state.slots.getOrElse(index) { WellSlot() },
                    speedLevel = speedLevel,
                    now = now,
                    onClick = { onCollectTile(index) }
                )
            }
        }
    }
}

@Composable
internal fun ComposterDialog(
    upgrade: FarmUpgradeEntity?,
    money: Long,
    availableCrops: List<InventoryItem>,
    slots: List<ComposterSlot>,
    now: Long,
    onDismiss: () -> Unit,
    onTileClick: (Int, ComposterSlot) -> Unit,
    onUpgrade: () -> Unit,
    // Owned wood/stone; null (unknown) shows the costs without blocking the button.
    materials: FarmMaterialStock? = null
) {
    val stored = upgrade?.storedOutput ?: 0
    val level = upgrade?.level ?: 1
    val slotCapacity = composterSlotCapacityForLevel(level)
    val availableSlots = slots.count { it.state == ComposterSlotState.EMPTY }
    val processingSlots = slots.count { it.state == ComposterSlotState.PROCESSING }
    val availableCropCount = availableCrops.sumOf { it.quantity }
    val upgradeCost = composterCapacityUpgradeCostForLevel(level)
    val maxed = level >= FARM_COMPOSTER_MAX_LEVEL
    val upgradeMaterials = FarmMaterialCosts.composterCapacity(level)
    val canUpgrade = !maxed && money >= upgradeCost.toLong() && upgradeMaterials.isCoveredBy(materials)
    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_composter_menu_title),
        backgroundAssetPath = "file:///android_asset/farm/Others/composter_dialog_background.png",
        onDismiss = onDismiss,
        actions = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onUpgrade,
                    enabled = canUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                    colors = farmPopupButtonColors()
                ) {
                    Text(
                        if (maxed) {
                            stringResource(R.string.tama_farm_upgrade_maxed)
                        } else {
                            stringResource(R.string.tama_farm_composter_upgrade_action, upgradeCost)
                        }
                    )
                }
                if (!maxed) FarmMaterialCostRow(upgradeMaterials, materials, compact = true, onDark = true)
            }
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_composter_status_section)) {
            Text(stringResource(R.string.tama_farm_composter_available_crops, availableCropCount), color = TamaDark)
            Text(
                stringResource(R.string.tama_farm_composter_ready_fertilizer, stored),
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            Text(stringResource(R.string.tama_farm_composter_slots, availableSlots, slotCapacity), color = TamaDark)
            Text(stringResource(R.string.tama_farm_composter_processing, processingSlots), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_composter_level, level), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_composter_tile_hint), color = TamaMutedText)
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_composter_slots_section)) {
            FarmUtilityTileGrid(count = slotCapacity) { index ->
                val slot = slots.getOrElse(index) { ComposterSlot() }
                ComposterTileCard(
                    slot = slot,
                    now = now,
                    hasAvailableCrops = availableCropCount > 0,
                    onClick = { onTileClick(index, slot) }
                )
            }
        }
    }
}

@Composable
private fun WellTileCard(
    slot: WellSlot,
    speedLevel: Int,
    now: Long,
    onClick: () -> Unit
) {
    val ready = wellTileIsReady(slot, speedLevel, now)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = ready, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EEE0).copy(alpha = 0.94f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = if (ready) WELL_TILE_FULL_ASSET else WELL_TILE_EMPTY_ASSET,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Surface(
                color = Color.Black.copy(alpha = 0.64f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = wellTileStatusText(slot, speedLevel, now),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            if (ready) {
                Surface(
                    color = Color(0xFF1E88E5).copy(alpha = 0.90f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tama_farm_tile_collect),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposterTileCard(
    slot: ComposterSlot,
    now: Long,
    hasAvailableCrops: Boolean,
    onClick: () -> Unit
) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val processingItemName = slot.inputItemId?.let { FarmTradeItemCatalog.displayName(it, locale) }
        ?: resources.getString(R.string.tama_item_rotten_crop)
    val processingAsset = FarmTradeItemCatalog.compostableAssetPath(slot.inputItemId ?: "rotten_crop")
        ?.let { "file:///android_asset/$it" }
        ?: "file:///android_asset/farm/Others/rotten_crop.png"
    val timerText = when (slot.state) {
        ComposterSlotState.PROCESSING -> formatComposterRemaining(slot.startedAt ?: now, now)
        ComposterSlotState.READY -> stringResource(R.string.tama_farm_composter_slot_ready)
        ComposterSlotState.EMPTY -> stringResource(R.string.tama_farm_composter_slot_empty_short)
    }
    val clickable = slot.state == ComposterSlotState.READY || (slot.state == ComposterSlotState.EMPTY && hasAvailableCrops)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = clickable, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EEE0).copy(alpha = 0.94f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = COMPOSTER_TILE_ASSET,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            when (slot.state) {
                ComposterSlotState.EMPTY -> {
                    Text(
                        text = if (hasAvailableCrops) {
                            stringResource(R.string.tama_farm_tile_add_crop)
                        } else {
                            stringResource(R.string.tama_farm_composter_picker_empty)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        color = TamaDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ComposterSlotState.PROCESSING -> {
                    AsyncImage(
                        model = processingAsset,
                        contentDescription = processingItemName,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 20.dp)
                            .size(76.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
                ComposterSlotState.READY -> {
                    AsyncImage(
                        model = "file:///android_asset/farm/Others/fertilizer.png",
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(84.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                    Surface(
                        color = Color(0xFF7CB342).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tama_farm_tile_collect),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Surface(
                color = TamaDark.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    timerText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun ComposterCropPickerDialog(
    crops: List<InventoryItem>,
    onDismiss: () -> Unit,
    onCropSelected: (InventoryItem) -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_farm_composter_picker_title)) },
        text = {
            if (crops.isEmpty()) {
                Text(stringResource(R.string.tama_farm_composter_picker_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(crops) { item ->
                        val assetPath = FarmTradeItemCatalog.compostableAssetPath(item.id)
                        ListItem(
                            modifier = Modifier.clickable { onCropSelected(item) },
                            leadingContent = {
                                if (assetPath != null) {
                                    AsyncImage(
                                        model = "file:///android_asset/$assetPath",
                                        contentDescription = inventoryItemDisplayName(context, item),
                                        modifier = Modifier
                                            .size(52.dp)
                                            .scale(FARM_MATURE_PREVIEW_SCALE),
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.None
                                    )
                                }
                            },
                            headlineContent = { Text(inventoryItemDisplayName(context, item)) },
                            supportingContent = {
                                Text(stringResource(R.string.tama_farm_available_quantity, item.quantity))
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun overallWellStatusText(
    context: android.content.Context,
    state: WellUpgradeState,
    capacity: Int,
    now: Long
): String {
    val readyCount = state.slots.count { wellTileIsReady(it, state.speedLevel, now) }
    return when {
        readyCount >= capacity -> context.getString(R.string.tama_farm_well_storage_full)
        readyCount > 0 -> context.getString(R.string.tama_farm_well_ready_tiles, readyCount, capacity)
        else -> {
            val nextReadyIn = state.slots
                .filterNot { wellTileIsReady(it, state.speedLevel, now) }
                .mapNotNull { slot ->
                    slot.cycleStartedAt?.let { startedAt ->
                        (startedAt + wellProductionIntervalForSpeedLevel(state.speedLevel) - now).coerceAtLeast(0L)
                    }
                }
                .minOrNull()
            if (nextReadyIn == null) {
                context.getString(R.string.tama_farm_well_ready_now)
            } else {
                context.getString(
                    R.string.tama_farm_well_next_water,
                    formatDurationToHoursMinutes(nextReadyIn)
                )
            }
        }
    }
}

private fun wellTileIsReady(
    slot: WellSlot,
    speedLevel: Int,
    now: Long
): Boolean {
    if (slot.hasWater) return true
    val startedAt = slot.cycleStartedAt ?: return false
    return now >= startedAt + wellProductionIntervalForSpeedLevel(speedLevel)
}

@Composable
private fun wellTileStatusText(
    slot: WellSlot,
    speedLevel: Int,
    now: Long
): String {
    return if (wellTileIsReady(slot, speedLevel, now)) {
        stringResource(R.string.tama_farm_well_ready_now)
    } else {
        val startedAt = slot.cycleStartedAt ?: now
        formatDurationToHoursMinutes(
            (startedAt + wellProductionIntervalForSpeedLevel(speedLevel) - now).coerceAtLeast(0L)
        )
    }
}
