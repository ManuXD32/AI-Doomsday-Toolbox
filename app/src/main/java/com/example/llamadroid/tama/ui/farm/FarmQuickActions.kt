package com.example.llamadroid.tama.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

private fun FarmQuickAction.icon(): ImageVector = when (this) {
    FarmQuickAction.HARVEST -> Icons.Default.Agriculture
    FarmQuickAction.WATER -> Icons.Default.WaterDrop
    FarmQuickAction.PLANT -> Icons.Default.Grass
    FarmQuickAction.TILL -> Icons.Default.Landscape
    FarmQuickAction.CLEAR -> Icons.Default.Handyman
}

private fun FarmQuickAction.labelRes(): Int = when (this) {
    FarmQuickAction.HARVEST -> R.string.tama_farm_action_harvest_all
    FarmQuickAction.WATER -> R.string.tama_farm_action_water_all
    FarmQuickAction.PLANT -> R.string.tama_farm_action_plant_all
    FarmQuickAction.TILL -> R.string.tama_farm_action_till_all
    FarmQuickAction.CLEAR -> R.string.tama_farm_action_clear_all
}

/** The chores to show: CLEAR only while the page has [clearableCount] obstacles to clear. */
private fun visibleQuickActions(clearableCount: Int): List<FarmQuickAction> =
    FarmQuickAction.entries.filter { it != FarmQuickAction.CLEAR || clearableCount > 0 }

@Composable
private fun FarmQuickAction.label(clearableCount: Int): String =
    if (this == FarmQuickAction.CLEAR) stringResource(labelRes(), clearableCount) else stringResource(labelRes())

@Composable
private fun FarmActionCell(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(width = 72.dp, height = 68.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Bulk chores (harvest/water/plant/till all, plus "Clear (N)" while [clearableCount] > 0) as the tail of the phone
 * tool strip (`ToolSidebar(extra = …)`).
 */
@Composable
internal fun RowScope.FarmQuickActionRow(onAction: (FarmQuickAction) -> Unit, clearableCount: Int = 0) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VerticalDivider(modifier = Modifier.height(48.dp))
        visibleQuickActions(clearableCount).forEach { action ->
            FarmActionCell(action.icon(), action.label(clearableCount)) { onAction(action) }
        }
    }
}

/** Wrapping version of the bulk chores for the side panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FarmQuickActionPalette(
    onAction: (FarmQuickAction) -> Unit,
    modifier: Modifier = Modifier,
    clearableCount: Int = 0
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visibleQuickActions(clearableCount).forEach { action ->
            FarmActionCell(action.icon(), action.label(clearableCount)) { onAction(action) }
        }
    }
}

/** Barn and coop shortcuts for the side panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FarmAnimalShortcuts(onOpenBarn: (() -> Unit)?, onOpenCoop: (() -> Unit)?, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        onOpenBarn?.let { FarmActionCell(Icons.Default.Home, stringResource(R.string.tama_farm_open_barn), it) }
        onOpenCoop?.let { FarmActionCell(Icons.Default.Pets, stringResource(R.string.tama_farm_open_coop), it) }
    }
}
