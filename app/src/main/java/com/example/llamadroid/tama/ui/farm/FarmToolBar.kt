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

/** True while a farm tool/supply can still be applied (quantity left and, for tools, durability left). */
internal fun farmToolIsUsable(item: InventoryItem): Boolean =
    item.quantity > 0 && (item.type != ItemType.TOOL || (item.durability ?: 100) > 0)

internal fun farmToolItems(inventory: List<InventoryItem>): List<InventoryItem> =
    inventory.filter { it.type == ItemType.TOOL || it.id == "fertilizer" || it.id == "water" }

private fun farmToolIconPath(itemId: String): String = when (itemId) {
    "hoe_starter", "hoe" -> "Others/hoe.png"
    "watering_can_starter", "watering_can" -> "Others/watering_can.png"
    FARM_PLANTING_DRONE_ID -> "Others/planting_drone.png"
    FARM_HARVESTING_DRONE_ID -> "Others/harvesting_drone.png"
    "fertilizer" -> "Others/fertilizer.png"
    "water" -> "Others/water.png"
    "axe", "axe_starter" -> "Others/axe.png"
    "pickaxe", "pickaxe_starter" -> "Others/pickaxe.png"
    else -> "Others/hoe.png"
}

/**
 * Horizontal tool strip used as the phone-portrait bottom bar. [extra] is an action-bar slot
 * rendered after the tools (F5: smart-tap / quick actions).
 */
@Composable
fun ToolSidebar(
    inventory: List<InventoryItem>,
    selectedTool: InventoryItem?,
    onToolSelect: (InventoryItem?) -> Unit,
    onPlantingDroneOpen: () -> Unit,
    onHarvesterDroneOpen: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable RowScope.() -> Unit = {}
) {
    val tools = farmToolItems(inventory)
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tools) { item ->
            FarmToolCell(
                item = item,
                isSelected = selectedTool?.id == item.id,
                onToolSelect = onToolSelect,
                onPlantingDroneOpen = onPlantingDroneOpen,
                onHarvesterDroneOpen = onHarvesterDroneOpen
            )
        }
        item(key = "farm_tool_bar_extra") {
            Row(verticalAlignment = Alignment.CenterVertically, content = extra)
        }
    }
}

/** Wrapping tool palette for the side panel of the landscape / tablet layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FarmToolPalette(
    inventory: List<InventoryItem>,
    selectedTool: InventoryItem?,
    onToolSelect: (InventoryItem?) -> Unit,
    onPlantingDroneOpen: () -> Unit,
    onHarvesterDroneOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tools = farmToolItems(inventory)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tools.forEach { item ->
            FarmToolCell(
                item = item,
                isSelected = selectedTool?.id == item.id,
                onToolSelect = onToolSelect,
                onPlantingDroneOpen = onPlantingDroneOpen,
                onHarvesterDroneOpen = onHarvesterDroneOpen
            )
        }
    }
}

@Composable
private fun FarmToolCell(
    item: InventoryItem,
    isSelected: Boolean,
    onToolSelect: (InventoryItem?) -> Unit,
    onPlantingDroneOpen: () -> Unit,
    onHarvesterDroneOpen: () -> Unit
) {
    val context = LocalContext.current
    val isDrone = item.id == FARM_PLANTING_DRONE_ID || item.id == FARM_HARVESTING_DRONE_ID
    val toolName = inventoryItemDisplayName(context, item)
    val durability = item.durability ?: 100
    val maxDurability = (item.maxDurability ?: 100).coerceAtLeast(1)
    val toolLabel = if (item.type == ItemType.TOOL && !isDrone)
        stringResource(R.string.soft_studio_farm_tool, toolName, durability, maxDurability)
    else stringResource(R.string.soft_studio_farm_supply, toolName, item.quantity)
    Surface(
        modifier = Modifier
            .size(68.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = toolLabel
                selected = isSelected && !isDrone
            }
            .clickable {
                when (item.id) {
                    FARM_PLANTING_DRONE_ID -> onPlantingDroneOpen()
                    FARM_HARVESTING_DRONE_ID -> onHarvesterDroneOpen()
                    else -> if (isSelected) onToolSelect(null) else onToolSelect(item)
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected && !isDrone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected && !isDrone) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = "file:///android_asset/farm/${farmToolIconPath(item.id)}",
                contentDescription = null,
                modifier = Modifier.size(48.dp).padding(bottom = 6.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            if (item.type == ItemType.TOOL && !isDrone) {
                LinearProgressIndicator(
                    progress = { (durability.toFloat() / maxDurability).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(8.dp).height(4.dp).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clearAndSetSemantics {}
                ) {
                    Text(
                        item.quantity.toString(),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
