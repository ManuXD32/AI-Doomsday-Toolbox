package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniEnv
import com.example.llamadroid.chat.cards.omni.OmniExpr

private const val PLOT_SAMPLES = 200

/** Evaluates [expr] at [x] with the card's live values (`x` is the free variable). */
private fun OmniEnv.at(expr: String, x: Double): Double {
    val withX = OmniEnv(numbers + ("x" to x), texts, functions)
    return (OmniExpr.evaluate(expr, withX) as? OmniExpr.Result.Ok)?.value?.takeIf { it.isFinite() } ?: Double.NaN
}

/** Line plot of 1..4 formulas in x over [OmniBlock.Plot.from]..[OmniBlock.Plot.to]; recomputed live from the card's values. */
@Composable
internal fun OmniPlot(b: OmniBlock.Plot, rt: OmniRuntime) {
    val numbers = rt.values.toMap()
    val texts = rt.texts.toMap()
    val exprs = b.exprs.take(4)
    val from = b.from
    val to = if (b.to > b.from) b.to else b.from + 1.0
    val series = remember(b, numbers, texts) {
        val env = rt.env()
        exprs.map { e -> DoubleArray(PLOT_SAMPLES + 1) { i -> env.at(e, from + (to - from) * i / PLOT_SAMPLES) } }
    }
    val (lo, hi) = remember(series, b.yMin, b.yMax) { yRange(series, b.yMin, b.yMax) }
    val colors = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error,
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val desc = stringResource(R.string.chat_card_omni_plot_desc, exprs.joinToString(", "))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        val empty = lo == null || hi == null
        if (empty) {
            Text(stringResource(R.string.chat_card_omni_plot_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val yLo = lo!!
            val yHi = hi!!
            Canvas(
                Modifier.fillMaxWidth().height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .semantics { contentDescription = desc }
            ) {
                fun px(x: Double) = ((x - from) / (to - from) * size.width).toFloat()
                fun py(y: Double) = (size.height - (y - yLo) / (yHi - yLo) * size.height).toFloat()
                val hair = Stroke(width = 1.dp.toPx())
                for (k in 1..3) {
                    val gx = size.width * k / 4f
                    val gy = size.height * k / 4f
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), hair.width)
                    drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), hair.width)
                }
                if (yLo <= 0.0 && 0.0 <= yHi) drawLine(axisColor, Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 1.5.dp.toPx())
                if (from <= 0.0 && 0.0 <= to) drawLine(axisColor, Offset(px(0.0), 0f), Offset(px(0.0), size.height), 1.5.dp.toPx())
                clipRect {
                    series.forEachIndexed { si, ys ->
                        val path = Path()
                        var pen = false
                        for (i in ys.indices) {
                            val y = ys[i]
                            val inRange = !y.isNaN() && y >= yLo - (yHi - yLo) && y <= yHi + (yHi - yLo)
                            if (!inRange) { pen = false; continue }
                            val x = px(from + (to - from) * i / PLOT_SAMPLES)
                            if (pen) path.lineTo(x, py(y)) else path.moveTo(x, py(y))
                            pen = true
                        }
                        drawPath(path, colors[si % colors.size], style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            Text(
                stringResource(
                    R.string.chat_card_omni_plot_range,
                    OmniExpr.format(from, 4), OmniExpr.format(to, 4), OmniExpr.format(yLo, 4), OmniExpr.format(yHi, 4)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        exprs.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(colors[i % colors.size], MaterialTheme.shapes.extraSmall))
                Text(
                    e,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** y-range of the plot: explicit bounds win; otherwise the 1st..99th percentile of all finite samples, padded 5%. Null when nothing is finite. */
private fun yRange(series: List<DoubleArray>, yMin: Double?, yMax: Double?): Pair<Double?, Double?> {
    if (yMin != null && yMax != null && yMax > yMin) return yMin to yMax
    val all = series.flatMap { it.asList() }.filter { it.isFinite() }.sorted()
    if (all.isEmpty()) return null to null
    var lo = yMin ?: all[((all.size - 1) * 0.01).toInt()]
    var hi = yMax ?: all[((all.size - 1) * 0.99).toInt()]
    if (yMin == null && yMax == null) {
        val pad = (hi - lo) * 0.05
        lo -= pad; hi += pad
    }
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
    return lo to hi
}

/** Values table: `x | expr...`, at most [OmniBlock.MAX_TABLE_ROWS] rows, recomputed live. */
@Composable
internal fun OmniTable(b: OmniBlock.Table, rt: OmniRuntime) {
    val numbers = rt.values.toMap()
    val texts = rt.texts.toMap()
    val exprs = b.exprs.take(3)
    val rows = remember(b, numbers, texts) {
        val env = rt.env()
        val step = if (b.step > 0 && b.step.isFinite()) b.step else 1.0
        val count = (((b.to - b.from) / step + 1e-9).toInt() + 1).coerceIn(1, OmniBlock.MAX_TABLE_ROWS)
        List(count) { i ->
            val x = b.from + i * step
            listOf(OmniExpr.format(x, 4)) + exprs.map { e ->
                env.at(e, x).let { if (it.isNaN()) "—" else OmniExpr.format(it, 4) }
            }
        }
    }
    val desc = stringResource(R.string.chat_card_omni_table_desc, exprs.joinToString(", "))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics { contentDescription = desc }) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        TableRow(listOf(stringResource(R.string.chat_card_omni_table_x)) + exprs, header = true)
        rows.forEach { TableRow(it, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(
        Modifier.fillMaxWidth().then(
            if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall) else Modifier
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        cells.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(vertical = 2.dp, horizontal = 4.dp)
            )
        }
    }
}
