package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.farmTileNeedsWater
import com.example.llamadroid.tama.data.inventoryItemDisplayName
import com.example.llamadroid.tama.data.isWet

private val FarmWetBlue = Color(0xFF4FC3F7)
private val FarmThirstyOrange = Color(0xFFFFB74D)
private val FarmProgressGreen = Color(0xFF8BC34A)
private val FarmSparkleGold = Color(0xFFFFE082)

/** Scale bounce played on a plot when an action succeeded on it. No-op without a [LocalFarmJuice]. */
@Composable
internal fun Modifier.farmTileBounce(plotId: Int): Modifier {
    val juice = LocalFarmJuice.current ?: return this
    val key = juice.bounces[plotId] ?: 0
    val scale = remember { Animatable(1f) }
    LaunchedEffect(key) {
        if (key > 0 && !juice.reducedMotion) {
            scale.animateTo(0.86f, tween(70))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Per-tile feedback layer: wet/thirsty droplet, live growth ring, ready sparkle and gain pops. Plugged into
 * [FarmTileItem]'s `overlay` slot. It never consumes input.
 */
@Composable
internal fun BoxScope.FarmTileOverlay(tile: FarmTile, now: Long) {
    val juice = LocalFarmJuice.current
    val crop = tile.crop
    if (tile.isWet(now)) {
        Icon(
            Icons.Default.WaterDrop,
            contentDescription = stringResource(R.string.tama_farm_wet_hint),
            tint = FarmWetBlue,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp)
        )
    } else if (crop != null && farmTileNeedsWater(tile, now)) {
        ThirstyDrop(reducedMotion = juice?.reducedMotion == true)
    }
    if (crop != null && !crop.isDecayed) {
        if (crop.stage < 3) {
            val progress = farmCropProgress(tile, now)
            if (progress != null) GrowthRing(progress)
        } else {
            ReadySparkle(juice)
        }
    }
    if (juice != null) WaterSplash(juice, tile.id)
    juice?.pops?.filter { it.plotId == tile.id }?.forEach { pop ->
        key(pop.id) { GainPop(pop) { juice.removePop(pop.id) } }
    }
}

/** Plays the 4-frame fx_water_drop strip once on the plot each time [FarmJuiceState.waterSplash] fires for it. */
@Composable
private fun BoxScope.WaterSplash(juice: FarmJuiceState, plotId: Int) {
    val strip = juice.waterDropStrip ?: return
    val trigger = juice.waterSplashes[plotId] ?: 0
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0 && !juice.reducedMotion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(520, easing = LinearEasing))
        }
    }
    if (progress.value < 1f) {
        FarmFxFrame(strip, (progress.value * FARM_FX_STRIP_FRAMES).toInt())
    }
}

@Composable
private fun BoxScope.ThirstyDrop(reducedMotion: Boolean) {
    val alphaValue = if (reducedMotion) {
        0.9f
    } else {
        val transition = rememberInfiniteTransition(label = "farmThirsty")
        val pulse by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "farmThirstyAlpha"
        )
        pulse
    }
    Icon(
        Icons.Default.WaterDrop,
        contentDescription = stringResource(R.string.tama_farm_thirsty_hint),
        tint = FarmThirstyOrange,
        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp).alpha(alphaValue)
    )
}

@Composable
private fun BoxScope.GrowthRing(progress: Float) {
    Canvas(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(3.dp)
            .size(16.dp)
            .clearAndSetSemantics {}
    ) {
        val stroke = size.minDimension * 0.18f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = Color.Black.copy(alpha = 0.4f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = stroke)
        )
        drawArc(
            color = FarmProgressGreen, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun BoxScope.ReadySparkle(juice: FarmJuiceState?) {
    val reduced = juice?.reducedMotion == true
    val strip = juice?.sparkleStrip
    val phase = if (reduced) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "farmSparkle")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "farmSparklePhase"
        )
        value
    }
    if (strip != null) {
        // fx_ready_sparkle.png: 4-frame strip, frame width = strip width / 4.
        FarmFxFrame(strip, (phase * FARM_FX_STRIP_FRAMES).toInt())
    } else {
        Canvas(modifier = Modifier.matchParentSize().clearAndSetSemantics {}) {
            val w = size.width
            val h = size.height
            drawTwinkle(Offset(w * 0.22f, h * 0.26f), w * 0.10f, twinkleAlpha(phase, 0f))
            drawTwinkle(Offset(w * 0.78f, h * 0.38f), w * 0.08f, twinkleAlpha(phase, 0.4f))
            drawTwinkle(Offset(w * 0.55f, h * 0.18f), w * 0.06f, twinkleAlpha(phase, 0.7f))
        }
    }
}

internal fun twinkleAlpha(phase: Float, offset: Float): Float {
    val t = (phase + offset) % 1f
    // Triangle wave 0 -> 1 -> 0 over one cycle.
    return 1f - kotlin.math.abs(2f * t - 1f)
}

private fun DrawScope.drawTwinkle(center: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0.02f) return
    val r = radius * (0.6f + 0.4f * alpha)
    val width = r * 0.28f
    val color = FarmSparkleGold.copy(alpha = alpha)
    drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), strokeWidth = width, cap = StrokeCap.Round)
    drawCircle(Color.White.copy(alpha = alpha), radius = r * 0.32f, center = center)
}

@Composable
private fun BoxScope.GainPop(pop: FarmJuiceState.Pop, onDone: () -> Unit) {
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing))
        onDone()
    }
    val assetPath = FarmTradeItemCatalog.assetPath(pop.item.id)
    val label = stringResource(R.string.tama_farm_pop_gain, pop.count, inventoryItemDisplayName(context, pop.item))
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
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (assetPath != null) {
                AsyncImage(
                    model = "file:///android_asset/$assetPath",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    filterQuality = FilterQuality.None
                )
            }
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
