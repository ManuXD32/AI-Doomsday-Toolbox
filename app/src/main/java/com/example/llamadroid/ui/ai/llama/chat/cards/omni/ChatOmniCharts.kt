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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniExpr

private fun OmniRuntime.number(expr: String): Double? =
    (OmniExpr.evaluate(expr, env()) as? OmniExpr.Result.Ok)?.value?.takeIf { it.isFinite() }

/** Progress toward a maximum, as a bar or a half-circle gauge, with "value / max unit". */
@Composable
internal fun OmniProgress(b: OmniBlock.Progress, rt: OmniRuntime) {
    val value = rt.number(b.expr)
    val max = rt.number(b.max)
    val fraction = if (value != null && max != null && max > 0) (value / max).coerceIn(0.0, 1.0).toFloat() else 0f
    val valueText = value?.let { OmniExpr.format(it, 2) } ?: "—"
    val maxText = max?.let { OmniExpr.format(it, 2) } ?: "—"
    val summary = withUnit("$valueText / $maxText", b.unit)
    val desc = stringResource(R.string.chat_card_omni_progress_desc, b.label, valueText, maxText)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.semantics { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (b.style == "gauge") {
            OmniLabel(b.label)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.size(width = 160.dp, height = 88.dp)) {
                    val stroke = 14.dp.toPx()
                    val arcSize = Size(size.width - stroke, (size.width - stroke))
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    drawArc(track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (fraction > 0f) drawArc(primary, 180f, 180f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmniLabel(b.label, Modifier.weight(1f))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
        }
    }
}

/** Horizontal bars scaled to the largest value; label on the left, value on the right. */
@Composable
internal fun OmniBars(b: OmniBlock.Bars, rt: OmniRuntime) {
    val values = b.items.map { rt.number(it.second) }
    val top = values.filterNotNull().filter { it > 0 }.maxOrNull() ?: 1.0
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        b.items.forEachIndexed { i, item ->
            val v = values[i]
            val text = v?.let { OmniExpr.format(it, 2) } ?: "—"
            val fraction = if (v != null && v > 0) (v / top).coerceIn(0.0, 1.0).toFloat() else 0f
            val desc = stringResource(R.string.chat_card_omni_bar_desc, item.first, text)
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.first, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.32f))
                Box(Modifier.weight(0.46f).height(14.dp).background(trackColor, RoundedCornerShape(7.dp))) {
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(14.dp).background(barColor, RoundedCornerShape(7.dp)))
                }
                Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(0.22f))
            }
        }
    }
}
