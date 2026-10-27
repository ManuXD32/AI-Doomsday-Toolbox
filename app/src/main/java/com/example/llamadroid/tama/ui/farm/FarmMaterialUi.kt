package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FARM_STONE_ID
import com.example.llamadroid.tama.data.FARM_WOOD_ID
import com.example.llamadroid.tama.data.FarmMaterialCost
import com.example.llamadroid.tama.data.FarmMaterialCosts
import com.example.llamadroid.tama.data.InventoryItem

/*
 * Wood and stone in the farm UI: owned stock, the "have / need" cost chips shown next to coin prices in the
 * upgrade dialogs, the store and the livestock slots, and the floating "+N wood/stone" pop of an obstacle clear.
 * Icons are assets/farm/Others/res_{wood,stone}.png; when an icon is missing the chip falls back to the item name.
 */

/** Wood and stone the pet owns. A null stock in the cost UI means "unknown": only the cost is shown, nothing is blocked. */
data class FarmMaterialStock(val wood: Int, val stone: Int) {
    fun covers(cost: FarmMaterialCost): Boolean = wood >= cost.wood && stone >= cost.stone

    companion object {
        fun of(inventory: List<InventoryItem>): FarmMaterialStock = FarmMaterialStock(
            wood = FarmMaterialCosts.owned(inventory, FARM_WOOD_ID),
            stone = FarmMaterialCosts.owned(inventory, FARM_STONE_ID)
        )
    }
}

/** True when [cost] is affordable in materials; an unknown (null) stock never blocks (the engine still validates). */
internal fun FarmMaterialCost.isCoveredBy(stock: FarmMaterialStock?): Boolean = stock == null || stock.covers(this)

internal fun farmMaterialIconUri(itemId: String): String = "file:///android_asset/farm/Others/res_$itemId.png"

private val MaterialShortColor = Color(0xFFC62828)
private val MaterialShortColorOnDark = Color(0xFFFF8A80)

@Composable
private fun FarmMaterialChip(itemId: String, owned: Int?, need: Int, iconSize: Dp, fontSize: Int, onDark: Boolean) {
    val name = stringResource(if (itemId == FARM_WOOD_ID) R.string.tama_farm_material_wood else R.string.tama_farm_material_stone)
    val short = owned != null && owned < need
    var iconFailed by remember(itemId) { mutableStateOf(false) }
    val description = if (owned == null) {
        stringResource(R.string.tama_farm_material_cost_need_desc, name, need)
    } else {
        stringResource(R.string.tama_farm_material_cost_have_desc, name, owned, need)
    }
    val color = when {
        short -> if (onDark) MaterialShortColorOnDark else MaterialShortColor
        onDark -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconFailed) {
            Text(name, fontSize = fontSize.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 72.dp).padding(end = 3.dp))
        } else {
            AsyncImage(
                model = farmMaterialIconUri(itemId),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                filterQuality = FilterQuality.None,
                onError = { iconFailed = true }
            )
        }
        Text(
            text = if (owned == null) need.toString() else stringResource(R.string.tama_farm_material_have_need, owned, need),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

/**
 * Wood/stone cost chips ("have / need", red while short). Draws nothing for a free [cost]. With [showMissingNote]
 * and a known [stock] that falls short, a one-line "not enough materials" note follows the chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FarmMaterialCostRow(
    cost: FarmMaterialCost,
    stock: FarmMaterialStock?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showMissingNote: Boolean = true,
    onDark: Boolean = false
) {
    if (cost.isFree) return
    val iconSize = if (compact) 14.dp else 20.dp
    val fontSize = if (compact) 11 else 14
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (cost.wood > 0) FarmMaterialChip(FARM_WOOD_ID, stock?.wood, cost.wood, iconSize, fontSize, onDark)
            if (cost.stone > 0) FarmMaterialChip(FARM_STONE_ID, stock?.stone, cost.stone, iconSize, fontSize, onDark)
        }
        if (showMissingNote && stock != null && !stock.covers(cost)) {
            Text(
                text = stringResource(R.string.tama_farm_materials_missing_note),
                fontSize = fontSize.sp,
                color = if (onDark) MaterialShortColorOnDark else MaterialShortColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Floating "+N wood/stone" that rises from the plot where an obstacle was cleared. Never consumes input. */
@Composable
internal fun BoxScope.FarmMaterialGainPop(pop: FarmJuiceState.MaterialPop, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing))
        onDone()
    }
    val name = stringResource(if (pop.itemId == FARM_WOOD_ID) R.string.tama_farm_material_wood else R.string.tama_farm_material_stone)
    val label = stringResource(R.string.tama_farm_pop_gain, pop.count, name)
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .align(Alignment.Center)
            .widthIn(max = 96.dp)
            .graphicsLayer {
                translationY = -progress.value * 44.dp.toPx()
                alpha = (1f - ((progress.value - 0.55f) / 0.45f)).coerceIn(0f, 1f)
            }
            .clearAndSetSemantics {}
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = farmMaterialIconUri(pop.itemId),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                filterQuality = FilterQuality.None
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 3.dp)
            )
        }
    }
}
