package com.example.llamadroid.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyFormat

/** Pure scaling used by [BranchyLossChart]; separate so it can be unit-tested. */
object BranchyChartMath {
    /** Axis bounds of both series: x = step range, y = loss range padded by 5%. Null when nothing can be drawn. */
    data class Bounds(val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float)

    fun bounds(series: List<List<Pair<Int, Float>>>): Bounds? {
        val pts = series.flatten().filter { it.second.isFinite() }
        if (pts.isEmpty()) return null
        val xMin = pts.minOf { it.first }.toFloat()
        var xMax = pts.maxOf { it.first }.toFloat()
        if (xMax <= xMin) xMax = xMin + 1f
        val lo = pts.minOf { it.second }
        val hi = pts.maxOf { it.second }
        val pad = ((hi - lo) * 0.05f).coerceAtLeast(0.01f)
        return Bounds(xMin, xMax, lo - pad, hi + pad)
    }

    /** Points mapped to 0..1 (x to the right, y up). Non-finite losses are skipped. */
    fun normalize(points: List<Pair<Int, Float>>, b: Bounds): List<Pair<Float, Float>> =
        points.filter { it.second.isFinite() }.map { (step, loss) ->
            (step - b.xMin) / (b.xMax - b.xMin) to (loss - b.yMin) / (b.yMax - b.yMin)
        }

    /** Exponential moving average of the finite losses ([alpha] = weight of the newest point). */
    fun ema(points: List<Pair<Int, Float>>, alpha: Float = 0.2f): List<Pair<Int, Float>> {
        var acc: Float? = null
        return points.filter { it.second.isFinite() }.map { (step, loss) ->
            val next = acc?.let { it + alpha * (loss - it) } ?: loss
            acc = next
            step to next
        }
    }

    /** The point whose step is closest to [step] (null for an empty list). */
    fun nearest(points: List<Pair<Int, Float>>, step: Float): Pair<Int, Float>? =
        points.filter { it.second.isFinite() }.minByOrNull { kotlin.math.abs(it.first - step) }

    /** Lowest finite loss of [points] (null when none). */
    fun best(points: List<Pair<Int, Float>>): Pair<Int, Float>? =
        points.filter { it.second.isFinite() }.minByOrNull { it.second }
}

/**
 * Training (EMA-smoothed over a faint raw line) and validation loss over steps, drawn on a Canvas. Tapping the chart
 * inspects the nearest point; the best loss is marked. Axis ends are labelled with the extremes.
 */
@Composable
fun BranchyLossChart(
    lossPoints: List<Pair<Int, Float>>,
    valPoints: List<Pair<Int, Float>>,
    modifier: Modifier = Modifier,
    /** Zoom resets whenever this changes (the run id). */
    resetKey: Any? = null
) {
    val fullBounds = BranchyChartMath.bounds(listOf(lossPoints, valPoints))
    var viewport by remember(resetKey) { mutableStateOf(BranchyChartViewport.Full) }
    val bounds = fullBounds?.let { b ->
        val range = b.xMax - b.xMin
        b.copy(xMin = b.xMin + viewport.lo * range, xMax = b.xMin + viewport.hi * range)
    }
    val trainColor = MaterialTheme.colorScheme.primary
    val valColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.secondary
    var selected by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bounds == null) {
            Text(
                text = stringResource(R.string.branchy_ui_chart_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val smooth = BranchyChartMath.ema(lossPoints)
            val best = BranchyChartMath.best(if (valPoints.isNotEmpty()) valPoints else lossPoints)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = BranchyFormat.loss(bounds.yMax.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = BranchyFormat.loss(bounds.yMin.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clipToBounds()
                    .branchyChartGestures(
                        key = resetKey,
                        viewport = { viewport },
                        onViewport = { viewport = it },
                        onTap = { fraction ->
                            val step = bounds.xMin + fraction * (bounds.xMax - bounds.xMin)
                            val a = BranchyChartMath.nearest(lossPoints, step)
                            val b = BranchyChartMath.nearest(valPoints, step)
                            selected = listOfNotNull(a, b).minByOrNull { kotlin.math.abs(it.first - step) }
                        }
                    )
            ) {
                val w = size.width
                val h = size.height
                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                fun at(step: Int, loss: Float): Offset {
                    val x = (step - bounds.xMin) / (bounds.xMax - bounds.xMin)
                    val y = (loss - bounds.yMin) / (bounds.yMax - bounds.yMin)
                    return Offset(x * w, h - y * h)
                }
                fun plot(points: List<Pair<Int, Float>>, color: Color, width: Float) {
                    val norm = points.filter { it.second.isFinite() }
                    if (norm.size == 1) {
                        drawCircle(color, radius = 4f, center = at(norm[0].first, norm[0].second))
                    } else if (norm.size > 1) {
                        val path = Path()
                        norm.forEachIndexed { i, (step, loss) ->
                            val o = at(step, loss)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        drawPath(path, color, style = Stroke(width = width))
                    }
                }
                plot(lossPoints, trainColor.copy(alpha = 0.25f), 1.5f)
                plot(smooth, trainColor, 3.5f)
                valPoints.filter { it.second.isFinite() }.forEach { (step, loss) ->
                    drawCircle(valColor, radius = 4.5f, center = at(step, loss))
                }
                best?.let { (step, loss) ->
                    val o = at(step, loss)
                    drawCircle(markerColor, radius = 9f, center = o, style = Stroke(width = 3f))
                }
                selected?.let { (step, loss) ->
                    val o = at(step, loss)
                    drawLine(gridColor, Offset(o.x, 0f), Offset(o.x, h), strokeWidth = 2f)
                    drawCircle(trainColor, radius = 7f, center = o)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = bounds.xMin.toInt().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.branchy_ui_chart_steps, bounds.xMax.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = selected?.let {
                    stringResource(R.string.branchy_dash_chart_point, it.first, BranchyFormat.loss(it.second.toDouble()))
                } ?: stringResource(R.string.branchy_dash_chart_hint),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(trainColor, stringResource(R.string.branchy_ui_chart_train))
                LegendDot(valColor, stringResource(R.string.branchy_ui_chart_val))
                best?.let {
                    LegendDot(markerColor, stringResource(R.string.branchy_dash_chart_best, BranchyFormat.loss(it.second.toDouble())))
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
