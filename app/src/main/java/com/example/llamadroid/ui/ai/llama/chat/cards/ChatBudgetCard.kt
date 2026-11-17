package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import java.text.NumberFormat
import java.util.Locale

private val FixedPalette = listOf(
    Color(0xFF4F8EF7), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFEF4444),
    Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFF84CC16)
)

/** Whole values print as integers, otherwise at most 2 decimals (no ragged "1,632.5"), with the locale's separators. */
private fun formatNumber(v: Double, wholeOnly: Boolean = false): String = NumberFormat.getInstance(Locale.getDefault()).apply {
    val whole = wholeOnly || Math.abs(v - Math.rint(v)) < 0.005
    maximumFractionDigits = if (whole) 0 else 2
    minimumFractionDigits = if (whole) 0 else 2
}.format(v)

/** Symbols ($, EUR sign) go in front, alphabetic codes (EUR) after the number. */
private fun formatMoney(v: Double, currency: String, wholeOnly: Boolean = false): String {
    val n = formatNumber(v, wholeOnly)
    val c = currency.trim()
    return when {
        c.isEmpty() -> n
        c.any { it.isLetter() } -> "$n $c"
        else -> "$c$n"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatBudgetCard(card: ChatCard.Budget) {
    val scheme = MaterialTheme.colorScheme
    // Local what-if state only; nothing is sent to the model. Keyed on the card so it survives scroll/recomposition.
    val amounts = remember(card) { mutableStateListOf(*card.items.map { it.amount }.toTypedArray()) }
    val total = amounts.sum()
    val series = listOf(scheme.primary, scheme.tertiary, scheme.secondary) + FixedPalette
    fun colorOf(i: Int) = series[i % series.size]

    CardShell(title = card.title) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.chat_card_budget_estimated), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(formatMoney(total, card.currency), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val people = card.people
            if (people != null && people >= 1) {
                Text(
                    stringResource(R.string.chat_card_budget_per_person, formatMoney(total / people, card.currency, wholeOnly = total >= 100.0)),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                )
            }
        }
        if (total > 0.0) {
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                amounts.forEachIndexed { i, a ->
                    if (a > 0.0) Box(Modifier.weight(a.toFloat().coerceAtLeast(0.0001f)).height(8.dp).background(colorOf(i)))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.items.forEachIndexed { i, item ->
                    if (amounts[i] > 0.0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).background(colorOf(i), RoundedCornerShape(2.dp)))
                            Text(
                                stringResource(R.string.chat_card_budget_percent, item.label, Math.round(amounts[i] / total * 100).toString()),
                                fontSize = 11.5.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        val cap = card.cap
        if (cap != null && cap > 0.0) {
            val left = cap - total
            val tone = if (left >= 0) chatCardPositiveColor() else scheme.error
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (left >= 0) stringResource(R.string.chat_card_budget_left, formatMoney(left, card.currency), formatMoney(cap, card.currency))
                    else stringResource(R.string.chat_card_budget_over, formatMoney(-left, card.currency), formatMoney(cap, card.currency)),
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                // Plain two-box bar (no stop dot like the M3 indicator draws).
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth((total / cap).toFloat().coerceIn(0f, 1f)).height(6.dp).background(tone))
                }
            }
        }
        Column {
            card.items.forEachIndexed { i, item ->
                if (i > 0) CardHairline()
                BudgetRow(item, amounts[i], card.currency, colorOf(i)) { amounts[i] = it }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BudgetRow(item: ChatCard.Budget.Item, amount: Double, currency: String, accent: Color, onChange: (Double) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lo = item.min
    val hi = item.max
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            cardEmoji(item.icon)?.let { Text(it, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!item.note.isNullOrBlank()) {
                    Text(item.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(formatMoney(amount, currency), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (lo != null && hi != null && hi > lo) {
            val desc = stringResource(R.string.chat_card_budget_slider, item.label)
            val range = lo.toFloat()..hi.toFloat()
            Slider(
                value = amount.coerceIn(lo, hi).toFloat(),
                onValueChange = { onChange(it.toDouble()) },
                valueRange = range,
                modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = desc },
                thumb = { Box(Modifier.size(16.dp).background(scheme.onSurface, CircleShape)) },
                track = { state ->
                    val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(0.0001f)
                    val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(scheme.outlineVariant)) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(accent))
                    }
                }
            )
        }
    }
}
