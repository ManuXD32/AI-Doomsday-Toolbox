package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard

/** Pie / donut: first series only, legend with value and percentage beside the ring. */
@Composable
internal fun ChatPieChartCard(card: ChatCard.Chart) {
    val palette = chartPalette()
    val description = stringResource(R.string.chat_card_chart_description)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val data = card.series.firstOrNull()?.data.orEmpty()
    val total = data.sum().takeIf { it > 0.0 } ?: 1.0
    CardShell(title = card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Canvas(Modifier.size(128.dp).semantics { contentDescription = description }) {
                val stroke = if (card.donut) size.minDimension * 0.22f else size.minDimension / 2f
                val inset = stroke / 2f
                var start = -90f
                data.forEachIndexed { i, v ->
                    val sweep = (v / total * 360.0).toFloat()
                    drawArc(
                        color = palette[i % palette.size], startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke)
                    )
                    start += sweep
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                data.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[i % palette.size], RoundedCornerShape(3.dp)))
                        Text(
                            card.labels.getOrNull(i).orEmpty(), Modifier.weight(1f).padding(start = 6.dp, end = 6.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatChartValue(v) + " (" + formatChartValue(v / total * 100.0) + "%)",
                            style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1
                        )
                    }
                }
            }
        }
        card.unit?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Horizontal bars: one row per label, one thin bar per series, value at the end of the bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatHBarChartCard(card: ChatCard.Chart) {
    val palette = chartPalette()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val maxAbs = (card.series.flatMap { it.data }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0).takeIf { it > 0.0 } ?: 1.0
    val names = card.series.map { it.name }.filter { it.isNotBlank() }
    val unit = card.unit?.takeIf { it.isNotBlank() && it !in names }
    CardShell(title = card.title) {
        if (card.series.size > 1 && names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.series.forEachIndexed { si, s ->
                    if (s.name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[si % palette.size], RoundedCornerShape(3.dp)))
                        Text(s.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val rows = card.labels.size.coerceAtLeast(card.series.maxOfOrNull { it.data.size } ?: 0)
        for (i in 0 until rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.labels.getOrNull(i).orEmpty(), Modifier.width(84.dp).padding(end = 8.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    card.series.forEachIndexed { si, s ->
                        val v = s.data.getOrNull(i) ?: return@forEachIndexed
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(12.dp)) {
                                Box(
                                    Modifier.fillMaxWidth((kotlin.math.abs(v) / maxAbs).toFloat().coerceIn(0.01f, 1f)).fillMaxHeight()
                                        .background(palette[si % palette.size], RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                )
                            }
                            Text(
                                formatChartValue(v), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall,
                                color = muted, maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        unit?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Scatter: x/y points per series on shared axes, with min/max tick text and optional axis labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatScatterChartCard(card: ChatCard.Chart) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val grid = chatCardLine()
    val description = stringResource(R.string.chat_card_chart_description)
    val all = card.series.flatMap { it.points }
    val minX = all.minOfOrNull { it.first } ?: 0.0
    val maxX = all.maxOfOrNull { it.first } ?: 1.0
    val minY = all.minOfOrNull { it.second } ?: 0.0
    val maxY = all.maxOfOrNull { it.second } ?: 1.0
    val rx = (maxX - minX).takeIf { it > 1e-9 } ?: 1.0
    val ry = (maxY - minY).takeIf { it > 1e-9 } ?: 1.0
    val names = card.series.map { it.name }.filter { it.isNotBlank() }
    CardShell(title = card.title) {
        if (card.series.size > 1 && names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.series.forEachIndexed { si, s ->
                    if (s.name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[si % palette.size], RoundedCornerShape(5.dp)))
                        Text(s.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        card.yLabel?.takeIf { it.isNotBlank() }?.let {
            Text("$it (${formatChartValue(minY)} - ${formatChartValue(maxY)})", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description }) {
            val pad = 6.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            for (k in 0..4) {
                val gy = pad + h * k / 4f
                drawLine(grid, Offset(pad, gy), Offset(pad + w, gy), 1.dp.toPx())
            }
            drawLine(scheme.outlineVariant, Offset(pad, pad + h), Offset(pad + w, pad + h), 2f)
            drawLine(scheme.outlineVariant, Offset(pad, pad), Offset(pad, pad + h), 2f)
            card.series.forEachIndexed { si, s ->
                val c = palette[si % palette.size].copy(alpha = 0.85f)
                s.points.forEach { (x, y) ->
                    drawCircle(c, 3.6.dp.toPx(), Offset(pad + ((x - minX) / rx * w).toFloat(), pad + (h - ((y - minY) / ry * h)).toFloat()))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatChartValue(minX), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
            card.xLabel?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.weight(2f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatChartValue(maxX), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End, maxLines = 1)
        }
        card.unit?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
