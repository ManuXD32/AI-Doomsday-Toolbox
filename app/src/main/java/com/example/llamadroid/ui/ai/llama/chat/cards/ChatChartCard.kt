package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChartKind
import com.example.llamadroid.chat.cards.ChatCard

internal fun formatChartValue(v: Double): String {
    val a = kotlin.math.abs(v)
    fun trim(x: String) = if (x.contains('.')) x.trimEnd('0').trimEnd('.') else x
    return when {
        a >= 1000.0 -> trim(String.format(java.util.Locale.US, "%.1f", v / 1000.0)) + "k"
        a == Math.floor(a) -> v.toLong().toString()
        else -> trim(String.format(java.util.Locale.US, "%.1f", v))
    }
}

/** Restrained palette: theme roles first, then the fixed chart colours of the design brief. */
@Composable
internal fun chartPalette(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(scheme.primary, scheme.tertiary, scheme.secondary, Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFFEC4899))
}

@Composable
internal fun ChatChartCard(card: ChatCard.Chart) {
    when (card.kind) {
        ChartKind.PIE -> ChatPieChartCard(card)
        ChartKind.HBAR -> ChatHBarChartCard(card)
        ChartKind.SCATTER -> ChatScatterChartCard(card)
        else -> ChatCartesianChartCard(card)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatCartesianChartCard(card: ChatCard.Chart) {
    val scheme = MaterialTheme.colorScheme
    val palette = chartPalette()
    val axis = scheme.outlineVariant
    val grid = chatCardLine()
    val description = stringResource(R.string.chat_card_chart_description)
    val values = card.series.flatMap { it.data }
    val max = (values.maxOrNull() ?: 0.0).takeIf { it > 0.0 } ?: 1.0
    val min = minOf(0.0, values.minOrNull() ?: 0.0)
    val n = card.labels.size.coerceAtLeast(card.series.maxOfOrNull { it.data.size } ?: 1).coerceAtLeast(1)
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val names = card.series.map { it.name }.filter { it.isNotBlank() }
    val unit = card.unit?.takeIf { it.isNotBlank() && it !in names }
    val muted = scheme.onSurfaceVariant
    CardShell(title = card.title) {
        // Legend first (multi-series) so the reader knows the colours before the plot.
        if (card.series.size > 1 && names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.series.forEachIndexed { si, s ->
                    if (s.name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[si % palette.size], RoundedCornerShape(3.dp)))
                        Text(s.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                unit?.let { Text("($it)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = muted, maxLines = 1) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
            val topPad = 14.dp.toPx()
            val plotH = size.height - topPad
            val range = (max - min).coerceAtLeast(1e-9)
            fun y(v: Double) = topPad + (plotH - ((v - min) / range * plotH)).toFloat()
            val baseY = y(0.0)
            // Subtle hairline grid (quarters of the range), then the solid zero axis.
            for (k in 1..4) {
                val gy = y(min + range * k / 4.0)
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
            }
            drawLine(axis, Offset(0f, baseY), Offset(size.width, baseY), 2f)
            val slot = size.width / n
            when (card.kind) {
                ChartKind.BAR -> {
                    val seriesCount = card.series.size.coerceAtLeast(1)
                    val barW = slot * 0.8f / seriesCount
                    val showValues = barW >= 16.dp.toPx() || seriesCount == 1
                    card.series.forEachIndexed { si, s ->
                        s.data.forEachIndexed { i, v ->
                            val top = y(v)
                            val x = i * slot + slot * 0.1f + si * barW
                            val barH = kotlin.math.abs(baseY - top)
                            val radius = minOf(4.dp.toPx(), barW / 2f, barH)
                            val bar = Path().apply {
                                // Rounded on the free end only: the top of a positive bar, the bottom of a negative one.
                                addRoundRect(
                                    RoundRect(
                                        x, minOf(top, baseY), x + barW, minOf(top, baseY) + barH,
                                        topLeftCornerRadius = if (v >= 0) CornerRadius(radius) else CornerRadius.Zero,
                                        topRightCornerRadius = if (v >= 0) CornerRadius(radius) else CornerRadius.Zero,
                                        bottomLeftCornerRadius = if (v < 0) CornerRadius(radius) else CornerRadius.Zero,
                                        bottomRightCornerRadius = if (v < 0) CornerRadius(radius) else CornerRadius.Zero
                                    )
                                )
                            }
                            drawPath(bar, palette[si % palette.size])
                            if (showValues) {
                                val t = measurer.measure(formatChartValue(v), valueStyle, maxLines = 1, softWrap = false)
                                val ty = (minOf(top, baseY) - t.size.height).coerceAtLeast(0f)
                                drawText(t, topLeft = Offset((x + barW / 2f - t.size.width / 2f).coerceIn(0f, (size.width - t.size.width).coerceAtLeast(0f)), ty))
                            }
                        }
                    }
                }
                ChartKind.LINE, ChartKind.AREA -> card.series.forEachIndexed { si, s ->
                    val color = palette[si % palette.size]
                    val path = Path()
                    if (card.kind == ChartKind.AREA && s.data.isNotEmpty()) {
                        val fill = Path()
                        fill.moveTo(0.5f * slot, baseY)
                        s.data.forEachIndexed { i, v -> fill.lineTo((i + 0.5f) * slot, y(v)) }
                        fill.lineTo((s.data.size - 0.5f) * slot, baseY)
                        fill.close()
                        drawPath(fill, color.copy(alpha = 0.22f))
                    }
                    s.data.forEachIndexed { i, v -> if (i == 0) path.moveTo((i + 0.5f) * slot, y(v)) else path.lineTo((i + 0.5f) * slot, y(v)) }
                    drawPath(path, color, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    s.data.forEachIndexed { i, v -> drawCircle(color, 3.4.dp.toPx(), Offset((i + 0.5f) * slot, y(v))) }
                }
                else -> Unit
            }
        }
        if (card.labels.isNotEmpty()) {
            // Same equal-width slots as the canvas, so each label is centred under its own bar/point.
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until n) {
                    Text(
                        card.labels.getOrNull(i).orEmpty(), Modifier.weight(1f).padding(horizontal = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (card.series.size <= 1 || names.isEmpty()) {
            val text = listOfNotNull(names.firstOrNull(), unit?.let { if (names.isEmpty()) it else "($it)" }).joinToString(" ")
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
