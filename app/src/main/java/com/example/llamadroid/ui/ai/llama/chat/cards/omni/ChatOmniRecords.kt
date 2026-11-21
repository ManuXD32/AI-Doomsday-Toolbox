package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniExpr
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

private const val MILLIS_PER_DAY = 86_400_000L

/** Epoch day (as a number) shown as a localized medium date; "—" when not a valid date. */
internal fun omniDateText(epochDay: Double): String {
    if (!epochDay.isFinite()) return "—"
    return runCatching {
        LocalDate.ofEpochDay(epochDay.roundToLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrDefault("—")
}

/** Seconds shown as h:mm:ss ("—" when not finite); negative values keep their sign. */
internal fun omniDurationText(seconds: Double): String {
    if (!seconds.isFinite()) return "—"
    val total = seconds.roundToLong()
    val abs = Math.abs(total)
    val text = "%d:%02d:%02d".format(abs / 3600, abs / 60 % 60, abs % 60)
    return if (total < 0) "-$text" else text
}

/** Button with the chosen date; opens a Material date picker. The variable is the epoch day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OmniDate(b: OmniBlock.Date, rt: OmniRuntime) {
    val day = rt.values[b.id] ?: b.initial
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OmniLabel(b.label)
        val pickLabel = stringResource(R.string.chat_card_omni_date_pick)
        OutlinedButton(
            onClick = { open = true },
            // Screen readers announce the action, not only the date shown on the button.
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { onClick(label = pickLabel) { open = true; true } }
        ) {
            Text(omniDateText(day), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day.takeIf { it.isFinite() }?.let { it.roundToLong() * MILLIS_PER_DAY })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { rt.values[b.id] = Math.floorDiv(it, MILLIS_PER_DAY).toDouble() }
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } }
        ) { DatePicker(state = state) }
    }
}

/** Entries list (label + amount) with a running total; state is the JSON in `texts[id]`, at most 100 entries. */
@Composable
internal fun OmniLog(b: OmniBlock.Log, rt: OmniRuntime) {
    val entries = OmniBlock.Log.decodeEntries(rt.texts[b.id])
    var label by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    val parsed = OmniBlock.Text.parse(amount)
    fun write(next: List<Pair<String, Double>>) {
        rt.texts[b.id] = OmniBlock.Log.encodeEntries(next)
        rt.values[b.id] = OmniBlock.Log.sum(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OmniLabel(b.label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(60) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_card_omni_log_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { raw -> amount = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(16) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_card_omni_log_amount), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(0.7f)
            )
        }
        FilledTonalButton(
            onClick = {
                write(entries + (label.trim() to parsed))
                label = ""; amount = ""
            },
            enabled = parsed.isFinite() && entries.size < OmniBlock.Log.MAX_ENTRIES,
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_log_add), maxLines = 1) }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.chat_card_omni_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEachIndexed { i, (name, value) ->
            val shownName = name.ifBlank { "#${i + 1}" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(shownName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(withUnit(OmniExpr.format(value, 2), b.unit), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 110.dp))
                OmniStepButton("×", stringResource(R.string.chat_card_omni_log_delete, shownName), enabled = true) {
                    write(entries.filterIndexed { j, _ -> j != i })
                }
            }
        }
        Text(
            stringResource(R.string.chat_card_omni_log_total, withUnit(OmniExpr.format(OmniBlock.Log.sum(entries), 2), b.unit)),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One row per player with − / + buttons; scores live in the numbers map as `${id}_1..n`. */
@Composable
internal fun OmniScoreboard(b: OmniBlock.Scoreboard, rt: OmniRuntime) {
    val step = if (b.step > 0) b.step else 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        b.players.forEachIndexed { i, name ->
            val key = "${b.id}_${i + 1}"
            val score = rt.values[key] ?: 0.0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                OmniStepButton("−", stringResource(R.string.chat_card_omni_score_sub, name), enabled = true) { rt.values[key] = scoreStep(score - step) }
                Text(
                    OmniExpr.format(score, 4),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                OmniStepButton("+", stringResource(R.string.chat_card_omni_score_add, name), enabled = true) { rt.values[key] = scoreStep(score + step) }
            }
        }
        OutlinedButton(
            onClick = { b.keys().forEach { rt.values[it] = 0.0 } },
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_reset), maxLines = 1) }
    }
}

private fun scoreStep(v: Double): Double = (v * 1e9).roundToLong() / 1e9

/** Big number plus a Roll button; integers when min and max are integers. */
@Composable
internal fun OmniRandom(b: OmniBlock.RandomNumber, rt: OmniRuntime) {
    val value = rt.values[b.id] ?: Double.NaN
    val lo = minOf(b.min, b.max)
    val hi = maxOf(b.min, b.max)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OmniLabel(b.label, Modifier.weight(1f))
        Text(
            OmniExpr.format(value, 2),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 48.dp)
        )
        FilledTonalButton(
            onClick = {
                rt.values[b.id] = when {
                    lo == hi -> lo
                    b.integers -> Random.nextLong(lo.toLong(), hi.toLong() + 1).toDouble()
                    else -> floor(Random.nextDouble(lo, hi) * 100) / 100
                }
            },
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_roll), maxLines = 1) }
    }
}

/** Work/rest timer: counts the current step down, auto-advances through all steps and rounds. */
@Composable
internal fun OmniIntervals(b: OmniBlock.Intervals) {
    if (b.steps.isEmpty()) return
    val total = b.steps.size * b.rounds
    var index by rememberSaveable { mutableIntStateOf(0) }
    var remaining by rememberSaveable { mutableIntStateOf(b.steps.first().second) }
    var running by remember { mutableStateOf(false) }
    val finished = index >= total
    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            remaining -= 1
            if (remaining <= 0) {
                index += 1
                if (index >= total) running = false else remaining = b.steps[index % b.steps.size].second
            }
        }
    }
    val current = b.steps[index.coerceAtMost(total - 1) % b.steps.size]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (finished) stringResource(R.string.chat_card_omni_done) else current.first,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (b.rounds > 1 && !finished) {
                    Text(
                        stringResource(R.string.chat_card_omni_interval_round, index / b.steps.size + 1, b.rounds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                if (finished) "—" else omniDurationText(remaining.toDouble()),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                if (finished) { index = 0; remaining = b.steps.first().second }
                running = !running
            }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(if (running) R.string.chat_card_omni_pause else R.string.chat_card_omni_start), maxLines = 1)
            }
            OutlinedButton(onClick = { running = false; index = 0; remaining = b.steps.first().second }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.chat_card_omni_reset), maxLines = 1)
            }
        }
    }
}
