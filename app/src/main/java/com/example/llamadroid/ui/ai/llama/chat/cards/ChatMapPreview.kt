package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.map.MapPinLayout
import com.example.llamadroid.chat.cards.map.MapTileMath
import com.example.llamadroid.chat.cards.map.MapViewport
import com.example.llamadroid.chat.cards.map.PinInput
import com.example.llamadroid.chat.cards.map.ResolvedPlace
import com.example.llamadroid.chat.cards.map.TileKey
import com.example.llamadroid.ui.components.AppChromeDefaults
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Static OSM tile preview: rounded clip, ink route line with a white casing, round pin badges (the place's emoji from
 * [icons], indexed by place index, else its number) with the name under each pin on a white halo, a floating "Expand"
 * pill and a translucent attribution strip. Tapping anywhere opens the full map via [onOpen].
 */
@Composable
internal fun ChatMapPreview(
    viewport: MapViewport,
    tiles: Map<TileKey, ImageBitmap>,
    places: List<ResolvedPlace>,
    route: Boolean,
    onOpen: () -> Unit,
    icons: List<String?> = emptyList(),
) {
    // OSM raster tiles are always light, so pins and labels use fixed ink-on-white regardless of the app theme.
    val ink = Color(0xFF16161A)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.chat_card_map_preview_description)
    val slots = remember(viewport) { MapTileMath.visibleTiles(viewport) }
    val located = remember(places) { places.filter { it.point != null } }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(MAP_PREVIEW_W.toFloat() / MAP_PREVIEW_H)
            .clip(AppChromeDefaults.CompactShape)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(background)
            val scale = size.width / MAP_PREVIEW_W
            val tileSize = ceil(MapTileMath.TILE_SIZE * scale).toInt() + 1
            slots.forEach { slot ->
                val image = tiles[slot.key] ?: return@forEach
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset((slot.dx * scale).roundToInt(), (slot.dy * scale).roundToInt()),
                    dstSize = IntSize(tileSize, tileSize),
                )
            }
            val screen = located.map { p ->
                val s = MapTileMath.project(p.point!!, viewport)
                Offset((s.x * scale).toFloat(), (s.y * scale).toFloat())
            }
            if (route && screen.size > 1) {
                for (i in 0 until screen.size - 1) {
                    drawLine(Color.White, screen[i], screen[i + 1], strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(ink, screen[i], screen[i + 1], strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            val radius = 14.dp.toPx()
            val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink)
            val haloStroke = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round)
            val labelConstraints = Constraints(maxWidth = (size.width * 0.42f).toInt())
            val texts = located.map {
                measurer.measure(it.name, labelStyle, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = labelConstraints)
            }
            val placed = MapPinLayout.layout(
                pins = screen.mapIndexed { i, o -> PinInput(o.x, o.y, texts[i].size.width.toFloat(), texts[i].size.height.toFloat()) },
                radius = radius,
                width = size.width,
                height = size.height,
                labelGap = 3.dp.toPx(),
                margin = 2.dp.toPx(),
            )
            placed.forEachIndexed { i, pl ->
                val o = Offset(pl.x, pl.y)
                // Pin badge: soft shadow, white fill, 1 dp dark hairline, emoji or number.
                drawCircle(Color.Black.copy(alpha = 0.25f), radius + 1.dp.toPx(), o + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, radius, o)
                drawCircle(ink.copy(alpha = 0.6f), radius, o, style = Stroke(width = 1.dp.toPx()))
                val glyph = icons.getOrNull(located[i].index)
                val layout = measurer.measure(
                    glyph ?: (located[i].index + 1).toString(),
                    TextStyle(fontSize = if (glyph != null) 15.sp else 12.sp, fontWeight = FontWeight.Bold, color = ink)
                )
                drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
            }
            // Labels after all pins so no pin covers a label; colliding labels are hidden by the layout.
            placed.forEachIndexed { i, pl ->
                if (!pl.labelVisible) return@forEachIndexed
                val at = Offset(pl.labelX, pl.labelY)
                drawText(texts[i], color = Color.White, topLeft = at, drawStyle = haloStroke)
                drawText(texts[i], color = ink, topLeft = at, drawStyle = Fill)
            }
        }
        Surface(
            onClick = onOpen,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.chat_card_map_expand), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            stringResource(R.string.chat_card_map_attribution),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
