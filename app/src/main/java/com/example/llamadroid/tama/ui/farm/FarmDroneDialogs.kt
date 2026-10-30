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
internal fun PlantingDroneDialog(
    state: PlantingDroneState,
    inventory: List<InventoryItem>,
    currentTime: Long,
    onDismiss: () -> Unit,
    onStateChange: (PlantingDroneState) -> Unit,
    onTransferItem: (InventoryItem, Int, (PlantingDroneState) -> PlantingDroneState) -> Unit,
    onTransferToolDurability: (String, Int, (PlantingDroneState, Int) -> PlantingDroneState) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val fuelItem = inventory.firstOrNull { it.id == FARM_FUEL_BUCKET_ID && it.quantity > 0 }
    val waterItem = inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
    val fertilizerItem = inventory.firstOrNull { it.id == "fertilizer" && it.quantity > 0 }
    val hoeAvailableDurability = farmToolTotalDurability(inventory, "hoe")
    val wateringCanAvailableDurability = farmToolTotalDurability(inventory, "watering_can")
    val seedItems = inventory.filter { it.type == ItemType.SEED && it.quantity > 0 }
    val fuelCapacity = farmDroneFuelCapacityForUpgradeLevel(state.fuelUpgradeLevel)
    val fuelTransferAmount = farmDroneFuelTransferAmountForUpgradeLevel(state.fuelUpgradeLevel)
    val fuelSpace = (fuelCapacity - state.fuel).coerceAtLeast(0)
    val fuelTransfer = minOf(fuelTransferAmount, fuelSpace, fuelItem?.quantity ?: 0)
    val hoeTransfer = minOf(FARM_TOOL_REPAIR_AMOUNT, hoeAvailableDurability, (FARM_TOOL_DURABILITY_CAP - (state.hoe?.durability ?: 0)).coerceAtLeast(0))
    val wateringCanTransfer = minOf(FARM_TOOL_REPAIR_AMOUNT, wateringCanAvailableDurability, (FARM_TOOL_DURABILITY_CAP - (state.wateringCan?.durability ?: 0)).coerceAtLeast(0))

    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_planting_drone),
        backgroundAssetPath = "file:///android_asset/farm/Others/farm_field_background.png",
        onDismiss = onDismiss,
        actions = {
            Spacer(modifier = Modifier.height(0.dp))
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_status_section)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tama_farm_drone_enabled), color = TamaDark, fontWeight = FontWeight.Bold)
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { onStateChange(state.copy(enabled = it, statusKey = null, lastUpdatedAt = currentTime)) },
                    colors = tamaSwitchColors()
                )
            }
            Text(stringResource(R.string.tama_farm_drone_fuel_status, state.fuel, fuelCapacity), color = TamaDark)
            Text(plantingDroneStatusText(context, state), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_drone_job_cost_planting, FARM_PLANTING_DRONE_FUEL_COST), color = TamaMutedText)
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_refill_fuel, fuelTransferAmount),
                enabled = fuelItem != null && fuelTransfer > 0,
                onClick = {
                    if (fuelItem != null) {
                        val item = fuelItem
                        onTransferItem(item, fuelTransfer) { it.copy(fuel = (it.fuel + fuelTransfer).coerceAtMost(farmDroneFuelCapacityForUpgradeLevel(it.fuelUpgradeLevel))) }
                        true
                    } else {
                        false
                    }
                }
            )
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_owned_resources_section)) {
            DroneResourceLine(stringResource(R.string.tama_farm_drone_hoe), state.hoe?.let { "${it.durability}/${it.maxDurability}" } ?: stringResource(R.string.tama_farm_drone_missing))
            DroneResourceLine(stringResource(R.string.tama_farm_drone_watering_can), state.wateringCan?.let { "${it.durability}/${it.maxDurability}" } ?: stringResource(R.string.tama_farm_drone_missing))
            DroneResourceLine(stringResource(R.string.tama_item_water), state.water.toString())
            DroneResourceLine(stringResource(R.string.tama_item_fertilizer), state.fertilizer.toString())
            DroneResourceLine(stringResource(R.string.tama_farm_drone_seed_total), state.seeds.sumOf { it.quantity }.toString())
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_transfer_section)) {
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_give_hoe),
                enabled = hoeTransfer > 0,
                onClick = {
                    onTransferToolDurability("hoe", hoeTransfer) { latest, transferred ->
                        latest.copy(hoe = addDroneToolDurability(latest.hoe, "hoe", resources.getString(R.string.tama_inventory_hoe), transferred))
                    }
                    true
                }
            )
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_give_watering_can),
                enabled = wateringCanTransfer > 0,
                onClick = {
                    onTransferToolDurability("watering_can", wateringCanTransfer) { latest, transferred ->
                        latest.copy(wateringCan = addDroneToolDurability(latest.wateringCan, "watering_can", resources.getString(R.string.tama_inventory_watering_can), transferred))
                    }
                    true
                }
            )
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_add_water),
                enabled = waterItem != null,
                onClick = {
                    if (waterItem != null) {
                        val item = waterItem
                        onTransferItem(item, 1) { it.copy(water = it.water + 1) }
                        true
                    } else {
                        false
                    }
                }
            )
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_add_fertilizer),
                enabled = fertilizerItem != null,
                onClick = {
                    if (fertilizerItem != null) {
                        val item = fertilizerItem
                        onTransferItem(item, 1) { it.copy(fertilizer = it.fertilizer + 1) }
                        true
                    } else {
                        false
                    }
                }
            )
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_seed_priority_section)) {
            if (state.seeds.isEmpty()) {
                Text(stringResource(R.string.tama_farm_drone_no_seeds), color = TamaMutedText)
            } else {
                state.seeds.forEachIndexed { index, seed ->
                    DroneSeedPriorityRow(
                        cropId = seed.cropId,
                        quantity = seed.quantity,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.seeds.lastIndex,
                        onMoveUp = { onStateChange(state.copy(seeds = state.seeds.moveItem(index, index - 1), lastUpdatedAt = currentTime)) },
                        onMoveDown = { onStateChange(state.copy(seeds = state.seeds.moveItem(index, index + 1), lastUpdatedAt = currentTime)) }
                    )
                }
            }
            seedItems.forEach { item ->
                val cropId = item.id.removePrefix("seed_")
                DroneTransferButton(
                    label = stringResource(R.string.tama_farm_drone_add_seed, inventoryItemDisplayName(context, item), item.quantity),
                    enabled = item.quantity > 0,
                    onClick = {
                        onTransferItem(item, 1) { it.copy(seeds = addDroneSeed(it.seeds, cropId, 1)) }
                        true
                    }
                )
            }
        }
    }
}

@Composable
internal fun HarvesterDroneDialog(
    state: HarvesterDroneState,
    inventory: List<InventoryItem>,
    currentTime: Long,
    onDismiss: () -> Unit,
    onStateChange: (HarvesterDroneState) -> Unit,
    onTransferFuel: (InventoryItem, Int) -> Unit,
    onCollectStorage: () -> Unit
) {
    val fuelItem = inventory.firstOrNull { it.id == FARM_FUEL_BUCKET_ID && it.quantity > 0 }
    val fuelCapacity = farmDroneFuelCapacityForUpgradeLevel(state.fuelUpgradeLevel)
    val fuelTransferAmount = farmDroneFuelTransferAmountForUpgradeLevel(state.fuelUpgradeLevel)
    val fuelSpace = (fuelCapacity - state.fuel).coerceAtLeast(0)
    val fuelTransfer = minOf(fuelTransferAmount, fuelSpace, fuelItem?.quantity ?: 0)
    val context = LocalContext.current

    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_harvesting_drone),
        backgroundAssetPath = "file:///android_asset/farm/Others/farm_field_background.png",
        onDismiss = onDismiss,
        actions = {
            Spacer(modifier = Modifier.height(0.dp))
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_status_section)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tama_farm_drone_enabled), color = TamaDark, fontWeight = FontWeight.Bold)
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { onStateChange(state.copy(enabled = it, statusKey = null, lastUpdatedAt = currentTime)) },
                    colors = tamaSwitchColors()
                )
            }
            Text(stringResource(R.string.tama_farm_drone_fuel_status, state.fuel, fuelCapacity), color = TamaDark)
            Text(harvesterDroneStatusText(context, state), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_drone_job_cost_harvest, FARM_HARVESTING_DRONE_FUEL_COST), color = TamaMutedText)
            DroneTransferButton(
                label = stringResource(R.string.tama_farm_drone_refill_fuel, fuelTransferAmount),
                enabled = fuelItem != null && fuelTransfer > 0,
                onClick = {
                    if (fuelItem != null) {
                        onTransferFuel(fuelItem, fuelTransfer)
                        true
                    } else {
                        false
                    }
                }
            )
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_filter_section)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == HarvesterDroneMode.BLACKLIST,
                    onClick = { onStateChange(state.copy(mode = HarvesterDroneMode.BLACKLIST, lastUpdatedAt = currentTime)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = stringResource(R.string.tama_farm_drone_blacklist_mode),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                FilterChip(
                    selected = state.mode == HarvesterDroneMode.WHITELIST,
                    onClick = { onStateChange(state.copy(mode = HarvesterDroneMode.WHITELIST, lastUpdatedAt = currentTime)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = stringResource(R.string.tama_farm_drone_whitelist_mode),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
            CropDefinitions.CROPS.keys.sorted().forEach { cropId ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cropDisplayName(context, cropId), color = TamaDark)
                    Checkbox(
                        checked = cropId in state.cropFilter,
                        onCheckedChange = { checked ->
                            val updated = if (checked) state.cropFilter + cropId else state.cropFilter - cropId
                            onStateChange(state.copy(cropFilter = updated, lastUpdatedAt = currentTime))
                        }
                    )
                }
            }
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_drone_storage_section)) {
            if (state.storage.isEmpty()) {
                Text(stringResource(R.string.tama_farm_drone_storage_empty), color = TamaMutedText)
            } else {
                state.storage.forEach { stored ->
                    DroneResourceLine(inventoryIdDisplayName(context, stored.inventoryId), stored.quantity.toString())
                }
                Button(
                    onClick = onCollectStorage,
                    modifier = Modifier.fillMaxWidth(),
                    colors = farmPopupButtonColors()
                ) {
                    Text(stringResource(R.string.tama_farm_drone_collect_storage))
                }
            }
        }
    }
}

@Composable
private fun DroneResourceLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TamaDark)
        Text(value, color = TamaDark, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DroneTransferButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Boolean
) {
    val repeatState = rememberPressAndHoldRepeatState()
    Button(
        onClick = { repeatState.handleClick { onClick() } },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .pressAndHoldRepeat(
                state = repeatState,
                enabled = enabled
            ) { onClick() },
        colors = farmPopupButtonColors()
    ) {
        Text(label)
    }
}

@Composable
private fun DroneSeedPriorityRow(
    cropId: String,
    quantity: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.tama_farm_drone_seed_row, cropDisplayName(context, cropId), quantity),
            color = TamaDark,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.tama_farm_drone_priority_up))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.tama_farm_drone_priority_down))
        }
    }
}

private fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { current ->
        val item = current.removeAt(from)
        current.add(to, item)
    }
}

private fun plantingDroneStatusText(context: android.content.Context, state: PlantingDroneState): String {
    if (state.enabled) return context.getString(R.string.tama_farm_drone_status_ready)
    return when (state.statusKey) {
        "fuel_empty" -> context.getString(R.string.tama_farm_drone_status_no_fuel)
        "hoe_missing" -> context.getString(R.string.tama_farm_drone_status_no_hoe)
        "watering_can_missing" -> context.getString(R.string.tama_farm_drone_status_no_watering_can)
        "water_empty" -> context.getString(R.string.tama_farm_drone_status_no_water)
        "seeds_empty" -> context.getString(R.string.tama_farm_drone_status_no_seeds)
        "hoe_broken" -> context.getString(R.string.tama_farm_drone_status_hoe_broken)
        "watering_can_broken" -> context.getString(R.string.tama_farm_drone_status_watering_can_broken)
        else -> context.getString(R.string.tama_farm_drone_status_disabled)
    }
}

private fun harvesterDroneStatusText(context: android.content.Context, state: HarvesterDroneState): String {
    if (state.enabled) return context.getString(R.string.tama_farm_drone_status_ready)
    return when (state.statusKey) {
        "fuel_empty" -> context.getString(R.string.tama_farm_drone_status_no_fuel)
        else -> context.getString(R.string.tama_farm_drone_status_disabled)
    }
}

private fun addDroneToolDurability(
    current: DroneToolState?,
    id: String,
    name: String,
    amount: Int
): DroneToolState {
    val currentDurability = current?.durability ?: 0
    return DroneToolState(
        id = current?.id ?: id,
        name = current?.name ?: name,
        durability = (currentDurability + amount).coerceIn(0, FARM_TOOL_DURABILITY_CAP),
        maxDurability = FARM_TOOL_DURABILITY_CAP
    )
}

private fun inventoryIdDisplayName(context: android.content.Context, inventoryId: String): String {
    if (inventoryId == "rotten_crop") return context.getString(R.string.tama_item_rotten_crop)
    if (inventoryId.startsWith("crop_")) {
        return cropDisplayName(context, inventoryId.removePrefix("crop_"))
    }
    return FarmTradeItemCatalog.displayName(inventoryId, context.resources.configuration.locales[0])
}
