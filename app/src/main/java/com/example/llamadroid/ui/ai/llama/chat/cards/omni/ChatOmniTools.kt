package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniExpr
import kotlinx.coroutines.delay
import kotlin.random.Random

private val KEY_ROWS = listOf(
    listOf("C", "⌫", "(", ")"),
    listOf("7", "8", "9", "÷"),
    listOf("4", "5", "6", "×"),
    listOf("1", "2", "3", "−"),
    listOf("0", ".", "^", "+"),
)

private const val KEYPAD_MAX_LENGTH = 120

/** Self-contained calculator: a display string, keys append, `=` evaluates with [OmniExpr]. State is not persisted. */
@Composable
internal fun OmniKeypad(b: OmniBlock.Keypad) {
    var display by remember { mutableStateOf("") }
    val errorText = stringResource(R.string.chat_card_omni_error)
    val backspace = stringResource(R.string.chat_card_omni_backspace)
    fun press(key: String) {
        val base = if (display == errorText) "" else display
        display = when (key) {
            "C" -> ""
            "⌫" -> base.dropLast(1)
            "=" -> if (base.isBlank()) base else when (val r = OmniExpr.evaluate(base)) {
                is OmniExpr.Result.Ok -> OmniExpr.format(r.value, 8)
                is OmniExpr.Result.Error -> errorText
            }
            else -> if (base.length >= KEYPAD_MAX_LENGTH) base else base + key
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(
                display.ifEmpty { "0" },
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (display.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        KEY_ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = { press(key) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics {
                            if (key == "⌫") contentDescription = backspace
                        }
                    ) { Text(key, fontSize = 18.sp, maxLines = 1) }
                }
            }
        }
        FilledTonalButton(onClick = { press("=") }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text("=", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun clock(totalSeconds: Int): String = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

@Composable
internal fun OmniTimer(b: OmniBlock.Timer) {
    var remaining by rememberSaveable { mutableIntStateOf(b.seconds) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        while (running && remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        if (remaining <= 0) running = false
    }
    OmniToolRow(
        label = b.label ?: stringResource(R.string.chat_card_omni_timer),
        display = if (remaining == 0) stringResource(R.string.chat_card_omni_done) else clock(remaining)
    ) {
        FilledTonalButton(onClick = {
            if (remaining == 0) remaining = b.seconds
            running = !running
        }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(if (running) R.string.chat_card_omni_pause else R.string.chat_card_omni_start), maxLines = 1)
        }
        OutlinedButton(onClick = { running = false; remaining = b.seconds }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.chat_card_omni_reset), maxLines = 1)
        }
    }
}

@Composable
internal fun OmniStopwatch(b: OmniBlock.Stopwatch) {
    var elapsed by rememberSaveable { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            val start = SystemClock.elapsedRealtime() - elapsed
            while (running) {
                elapsed = SystemClock.elapsedRealtime() - start
                delay(100)
            }
        }
    }
    val tenths = (elapsed / 100) % 10
    OmniToolRow(
        label = b.label ?: stringResource(R.string.chat_card_omni_stopwatch),
        display = clock((elapsed / 1000).toInt()) + "." + tenths
    ) {
        FilledTonalButton(onClick = { running = !running }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(if (running) R.string.chat_card_omni_pause else R.string.chat_card_omni_start), maxLines = 1)
        }
        OutlinedButton(onClick = { running = false; elapsed = 0L }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.chat_card_omni_reset), maxLines = 1)
        }
    }
}

@Composable
internal fun OmniDice(b: OmniBlock.Dice, rt: OmniRuntime) {
    var rolls by remember { mutableStateOf<List<Int>>(emptyList()) }
    val display = when {
        rolls.isEmpty() -> "—"
        rolls.size == 1 -> rolls[0].toString()
        else -> rolls.joinToString(" + ") + " = " + rolls.sum()
    }
    OmniToolRow(label = b.label.ifBlank { stringResource(R.string.chat_card_omni_dice) } + " ${b.count}d${b.sides}", display = display) {
        FilledTonalButton(
            onClick = {
                rolls = List(b.count) { Random.nextInt(1, b.sides + 1) }
                rt.values[b.id] = rolls.sum().toDouble()
            },
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_roll), maxLines = 1) }
    }
}

@Composable
internal fun OmniCoin(b: OmniBlock.Coin) {
    var heads by remember { mutableStateOf<Boolean?>(null) }
    val display = when (heads) {
        null -> "—"
        true -> stringResource(R.string.chat_card_omni_heads)
        false -> stringResource(R.string.chat_card_omni_tails)
    }
    OmniToolRow(label = b.label ?: stringResource(R.string.chat_card_omni_coin), display = display) {
        FilledTonalButton(onClick = { heads = Random.nextBoolean() }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.chat_card_omni_flip), maxLines = 1)
        }
    }
}

@Composable
internal fun OmniPick(b: OmniBlock.Pick) {
    var chosen by remember { mutableStateOf<String?>(null) }
    OmniToolRow(label = b.label ?: stringResource(R.string.chat_card_omni_pick_label), display = chosen ?: "—") {
        FilledTonalButton(
            onClick = { if (b.items.isNotEmpty()) chosen = b.items[Random.nextInt(b.items.size)] },
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_pick), maxLines = 1) }
    }
}

/** Label + big value on one line, action buttons under it. */
@Composable
private fun OmniToolRow(label: String, display: String, actions: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OmniLabel(label, Modifier.weight(1f))
            Text(
                display,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
