package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniExpr
import com.example.llamadroid.ui.ai.llama.chat.cards.CardPrimaryButton
import kotlin.math.roundToLong

/** Dispatches one block to its renderer. [onSend] receives the raw template of a Send block. */
@Composable
internal fun OmniBlockView(block: OmniBlock, rt: OmniRuntime, submitEnabled: Boolean, onSend: (String) -> Unit) {
    when (block) {
        is OmniBlock.Number -> OmniNumber(block, rt)
        is OmniBlock.Slider -> OmniSlider(block, rt)
        is OmniBlock.Toggle -> OmniToggle(block, rt)
        is OmniBlock.Select -> OmniSelect(block, rt)
        is OmniBlock.Result -> OmniResult(block, rt)
        is OmniBlock.Counter -> OmniCounter(block, rt)
        is OmniBlock.Checklist -> OmniChecklist(block, rt)
        is OmniBlock.Keypad -> OmniKeypad(block)
        is OmniBlock.Timer -> OmniTimer(block)
        is OmniBlock.Stopwatch -> OmniStopwatch(block)
        is OmniBlock.Dice -> OmniDice(block, rt)
        is OmniBlock.Coin -> OmniCoin(block)
        is OmniBlock.Pick -> OmniPick(block)
        is OmniBlock.Send -> CardPrimaryButton(block.label, enabled = submitEnabled, onClick = { onSend(block.text) })
        is OmniBlock.Text -> OmniTextInput(block, rt)
        is OmniBlock.Note -> OmniNote(block)
        is OmniBlock.Function -> OmniFunction(block, rt)
        is OmniBlock.Plot -> OmniPlot(block, rt)
        is OmniBlock.Table -> OmniTable(block, rt)
        is OmniBlock.Matrix -> OmniMatrixView(block)
        is OmniBlock.Quiz -> OmniQuiz(block, rt)
        is OmniBlock.Flashcards -> OmniFlashcards(block)
        is OmniBlock.Steps -> OmniSteps(block)
        is OmniBlock.Progress -> OmniProgress(block, rt)
        is OmniBlock.Bars -> OmniBars(block, rt)
        is OmniBlock.Date -> OmniDate(block, rt)
        is OmniBlock.Log -> OmniLog(block, rt)
        is OmniBlock.Intervals -> OmniIntervals(block)
        is OmniBlock.RandomNumber -> OmniRandom(block, rt)
        is OmniBlock.Scoreboard -> OmniScoreboard(block, rt)
    }
}

@Composable
internal fun OmniLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Small round-ish stepper button (-, +) with a 44 dp touch target. */
@Composable
internal fun OmniStepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.widthIn(min = 44.dp).heightIn(min = 44.dp).semantics { contentDescription = description }
    ) { Text(symbol, fontSize = 18.sp, maxLines = 1) }
}

private fun roundStep(v: Double): Double = (v * 1e9).roundToLong() / 1e9

internal fun withUnit(text: String, unit: String?) = if (unit.isNullOrBlank()) text else "$text $unit"

@Composable
private fun OmniNumber(b: OmniBlock.Number, rt: OmniRuntime) {
    val value = rt.values[b.id] ?: b.initial
    fun set(v: Double) {
        if (!v.isFinite()) return
        rt.values[b.id] = roundStep(v).coerceIn(b.min ?: -Double.MAX_VALUE, b.max ?: Double.MAX_VALUE)
    }
    var text by remember(b.id) { mutableStateOf(OmniExpr.format(value, 6)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = OmniExpr.format(value, 6) }
    val step = if (b.step > 0) b.step else 1.0
    val dec = stringResource(R.string.chat_card_omni_decrease)
    val inc = stringResource(R.string.chat_card_omni_increase)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OmniLabel(b.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OmniStepButton("−", dec, enabled = b.min == null || value > b.min) { set(value - step) }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val clean = raw.filter { it.isDigit() || it == '.' || it == '-' }.take(16)
                    text = clean
                    clean.toDoubleOrNull()?.let(::set)
                },
                singleLine = true,
                suffix = b.unit?.takeIf { it.isNotBlank() }?.let { u -> { Text(u, maxLines = 1) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OmniStepButton("+", inc, enabled = b.max == null || value < b.max) { set(value + step) }
        }
    }
}

@Composable
private fun OmniSlider(b: OmniBlock.Slider, rt: OmniRuntime) {
    val value = (rt.values[b.id] ?: b.initial)
    val hasRange = b.max > b.min
    val steps = if (hasRange && b.step != null && b.step > 0) {
        (((b.max - b.min) / b.step).roundToLong() - 1).coerceIn(0, 1000).toInt()
    } else 0
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmniLabel(b.label, Modifier.weight(1f))
            Text(
                withUnit(OmniExpr.format(value, 2), b.unit),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1
            )
        }
        if (hasRange) {
            Slider(
                value = value.coerceIn(b.min, b.max).toFloat(),
                onValueChange = { rt.values[b.id] = roundStep(it.toDouble()).coerceIn(b.min, b.max) },
                valueRange = b.min.toFloat()..b.max.toFloat(),
                steps = steps,
                modifier = Modifier.heightIn(min = 44.dp)
            )
        }
    }
}

@Composable
private fun OmniToggle(b: OmniBlock.Toggle, rt: OmniRuntime) {
    val on = (rt.values[b.id] ?: b.initial) != 0.0
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OmniLabel(b.label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { rt.values[b.id] = if (it) 1.0 else 0.0 })
    }
}

@Composable
private fun OmniSelect(b: OmniBlock.Select, rt: OmniRuntime) {
    var open by remember { mutableStateOf(false) }
    val index = b.indexOfNumber(rt.values[b.id] ?: b.initial)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OmniLabel(b.label)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text(
                b.options.getOrNull(index)?.label.orEmpty() + "  ▾",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            b.options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { rt.values[b.id] = b.numberAt(i); open = false }
                )
            }
        }
    }
}

@Composable
private fun OmniResult(b: OmniBlock.Result, rt: OmniRuntime) {
    var reason: String? = null
    val shown = when (val r = OmniExpr.evaluate(b.expr, rt.env())) {
        is OmniExpr.Result.Ok -> when (b.format) {
            "date" -> omniDateText(r.value)
            "duration" -> omniDurationText(r.value)
            else -> withUnit(OmniExpr.format(r.value, b.decimals), b.unit)
        }
        is OmniExpr.Result.Error -> {
            if (OmniExpr.isStaticError(r.reason)) reason = r.reason
            if (r.reason == "div0") "— (÷0)" else "—"
        }
    }
    val reasonText = reason?.let { omniErrorText(it) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OmniLabel(b.label, Modifier.weight(1f))
            Text(
                shown,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        if (reasonText != null) {
            Text(
                reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Localized explanation of a static formula error reason (`unknown:x`, `unknown_fn:f`, `text_in_math`, ...). */
@Composable
private fun omniErrorText(reason: String): String = when {
    reason.startsWith("unknown_fn:") -> stringResource(R.string.chat_card_omni_err_unknown_fn, reason.substringAfter(':'))
    reason.startsWith("unknown:") -> stringResource(R.string.chat_card_omni_err_unknown, reason.substringAfter(':'))
    reason == "text_in_math" -> stringResource(R.string.chat_card_omni_err_text)
    reason == "else_missing" -> stringResource(R.string.chat_card_omni_err_else)
    else -> stringResource(R.string.chat_card_omni_err_formula)
}

@Composable
private fun OmniTextInput(b: OmniBlock.Text, rt: OmniRuntime) {
    val content = rt.texts[b.id].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OmniLabel(b.label)
        OutlinedTextField(
            value = content,
            onValueChange = { raw ->
                val t = raw.take(TEXT_MAX_LENGTH)
                rt.texts[b.id] = t
                rt.values[b.id] = OmniBlock.Text.parse(t)
            },
            singleLine = true,
            placeholder = { Text(b.placeholder?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_card_omni_text_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OmniNote(b: OmniBlock.Note) {
    Text(b.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun OmniFunction(b: OmniBlock.Function, rt: OmniRuntime) {
    val formula = rt.texts[b.id] ?: b.value
    val desc = stringResource(R.string.chat_card_omni_function_desc, formula)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (b.label.isNotBlank()) OmniLabel(b.label)
        OutlinedTextField(
            value = formula,
            onValueChange = { rt.texts[b.id] = it.take(OmniExpr.MAX_LENGTH) },
            singleLine = true,
            prefix = { Text(stringResource(R.string.chat_card_omni_function_label), fontFamily = FontFamily.Monospace, maxLines = 1) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc }
        )
    }
}

private const val TEXT_MAX_LENGTH = 500

@Composable
private fun OmniCounter(b: OmniBlock.Counter, rt: OmniRuntime) {
    val value = rt.values[b.id] ?: b.initial
    val step = if (b.step > 0) b.step else 1.0
    val dec = stringResource(R.string.chat_card_omni_decrease)
    val inc = stringResource(R.string.chat_card_omni_increase)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OmniLabel(b.label, Modifier.weight(1f))
        OmniStepButton("−", dec, true) { rt.values[b.id] = roundStep(value - step) }
        Text(
            OmniExpr.format(value, 4),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.widthIn(min = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        OmniStepButton("+", inc, true) { rt.values[b.id] = roundStep(value + step) }
    }
}

@Composable
private fun OmniChecklist(b: OmniBlock.Checklist, rt: OmniRuntime) {
    val ticks = rt.checks[b.id] ?: List(b.items.size) { false }
    val done = ticks.count { it }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmniLabel(b.title.orEmpty(), Modifier.weight(1f))
            Text("$done/${b.items.size}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        LinearProgressIndicator(progress = { if (b.items.isEmpty()) 0f else done.toFloat() / b.items.size }, modifier = Modifier.fillMaxWidth())
        b.items.forEachIndexed { i, item ->
            val checked = ticks.getOrNull(i) ?: false
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { c -> rt.checks[b.id] = List(b.items.size) { j -> if (j == i) c else (ticks.getOrNull(j) ?: false) } }
                )
                Text(item, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}
