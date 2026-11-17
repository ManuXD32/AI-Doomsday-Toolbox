package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardAnswerText
import com.example.llamadroid.chat.cards.ChatCardState
import com.example.llamadroid.chat.cards.FormFieldKind
import com.example.llamadroid.ui.components.AppChromeDefaults
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val TOGGLE_ON = "true"
private const val TOGGLE_OFF = "false"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatFormCard(
    card: ChatCard.Form,
    answerState: String?,
    submitEnabled: Boolean,
    batch: ChatCardBatchBinding?,
    onSubmit: (String, String) -> Unit
) {
    val answered = remember(answerState) { answerState?.let(ChatCardState::decodeForm) }
    val values = remember(card, answerState) { mutableStateMapOf<String, String>().apply { answered?.let { putAll(it) } } }
    val locked = answerState != null
    var showMissing by remember(card) { mutableStateOf(false) }
    val missing = card.fields.any { it.required && values[it.id].isNullOrBlank() && it.kind != FormFieldKind.TOGGLE }
    if (batch != null && !locked) {
        val map = values.toMap()
        val draft = ChatCardDraft(!missing, ChatCardAnswerText.form(card, map), ChatCardState.encodeForm(map))
        SideEffect { batch.report(draft) }
    }
    CardShell(
        title = card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_card_form_default_title),
        modifier = Modifier.animateContentSize()
    ) {
        if (locked) {
            // Answered: compact "Label  value" rows separated by hairlines.
            Column(Modifier.fillMaxWidth()) {
                card.fields.forEachIndexed { i, field ->
                    val v = values[field.id].orEmpty()
                    val shown = if (field.kind == FormFieldKind.TOGGLE) stringResource(if (v == TOGGLE_ON) R.string.chat_card_yes else R.string.chat_card_no) else v.ifBlank { "—" }
                    if (i > 0) CardHairline()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(field.label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(shown, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp), textAlign = TextAlign.End, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SubmitRow(answered = true, enabled = false) {}
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                card.fields.forEach { field -> ChatFormField(field, values[field.id].orEmpty()) { values[field.id] = it } }
            }
            if (showMissing && missing && batch == null) {
                Text(stringResource(R.string.chat_card_required_missing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (batch == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SubmitRow(answered = false, enabled = submitEnabled, label = card.submit?.takeIf { it.isNotBlank() }) {
                        if (missing) showMissing = true
                        else {
                            val map = values.toMap()
                            onSubmit(ChatCardAnswerText.form(card, map), ChatCardState.encodeForm(map))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun placeholderFor(field: ChatCard.FormField): String? =
    field.placeholder?.takeIf { it.isNotBlank() }
        ?: if (field.kind == FormFieldKind.DATE) stringResource(R.string.chat_card_date_hint) else null

/** Field with its label above (never placeholder-only); toggles are a single row with the label on the left. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatFormField(field: ChatCard.FormField, value: String, onChange: (String) -> Unit) {
    val label = if (field.required) "${field.label} *" else field.label
    if (field.kind == FormFieldKind.TOGGLE) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp))
                Switch(checked = value == TOGGLE_ON, onCheckedChange = { onChange(if (it) TOGGLE_ON else TOGGLE_OFF) })
            }
            CardHairline()
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (field.kind) {
            FormFieldKind.SELECT ->
                if (field.options.size <= 4) {
                    // Few options: segmented chips, one tap.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        field.options.forEach { opt -> CardChip(opt, selected = opt == value, onClick = { onChange(opt) }) }
                    }
                } else ChatSelectDropdown(field.options, value, onChange)
            FormFieldKind.DATE -> ChatDateField(value, placeholderFor(field), onChange)
            else -> CardTextField(
                value = value, onValueChange = onChange,
                placeholder = placeholderFor(field),
                keyboardOptions = KeyboardOptions(keyboardType = if (field.kind == FormFieldKind.NUMBER) KeyboardType.Number else KeyboardType.Text),
                textAlign = if (field.kind == FormFieldKind.NUMBER) TextAlign.End else TextAlign.Start
            )
        }
    }
}

/** More than four options: a field-looking row with a chevron that opens a dropdown menu. */
@Composable
private fun ChatSelectDropdown(options: List<String>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CardTextField(
            value = value, onValueChange = {}, readOnly = true,
            placeholder = stringResource(R.string.chat_card_select_hint),
            trailing = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            }
        )
        // Transparent overlay is the tap target; the read-only field underneath would otherwise swallow the click.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = AppChromeDefaults.CompactShape) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { onChange(opt); open = false })
            }
        }
    }
}

/** YYYY-MM-DD field (typing stays possible) with a calendar icon that opens a Material 3 date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDateField(value: String, placeholder: String?, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    CardTextField(
        value = value, onValueChange = onChange, placeholder = placeholder,
        trailing = {
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.chat_card_pick_date), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToMillis(value))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(millisToIso(it)) }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) } }
        ) {
            // Scrollable so the calendar stays reachable on short or landscape screens.
            Column(Modifier.verticalScroll(rememberScrollState())) { DatePicker(state = state) }
        }
    }
}

private fun isoToMillis(value: String): Long? = runCatching {
    LocalDate.parse(value.trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun millisToIso(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
