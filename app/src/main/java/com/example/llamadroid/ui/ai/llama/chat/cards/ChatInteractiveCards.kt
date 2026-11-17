package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardAnswerText
import com.example.llamadroid.chat.cards.ChatCardState
import com.example.llamadroid.ui.components.AppChromeDefaults

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatChoicesCard(
    card: ChatCard.Choices,
    answerState: String?,
    submitEnabled: Boolean,
    batch: ChatCardBatchBinding?,
    onSubmit: (String, String) -> Unit
) {
    val answered = remember(answerState) { answerState?.let(ChatCardState::decodeChoices) }
    val selected = remember(card, answerState) { mutableStateListOf<String>().apply { answered?.let { addAll(it.selected) } } }
    var other by remember(card, answerState) { mutableStateOf(answered?.other.orEmpty()) }
    var otherOpen by remember(card, answerState) { mutableStateOf(answered?.other.orEmpty().isNotBlank()) }
    val locked = answerState != null
    val otherOrNull = other.takeIf { it.isNotBlank() }
    val hasAnswer = selected.isNotEmpty() || otherOrNull != null
    if (batch != null && !locked) {
        val draft = ChatCardDraft(hasAnswer, ChatCardAnswerText.choices(card, selected.toList(), otherOrNull), ChatCardState.encodeChoices(selected.toList(), otherOrNull))
        SideEffect { batch.report(draft) }
    }
    CardShell(
        title = card.question,
        subtitle = if (locked) null else stringResource(if (card.multi) R.string.chat_card_choose_any else R.string.chat_card_choose_one),
        modifier = Modifier.animateContentSize()
    ) {
        if (locked) {
            // Answered: collapse to chips of the chosen values + the "Answered" micro label.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { CardChip(it, selected = true, showCheck = true) }
                otherOrNull?.let { CardChip(it, selected = true, showCheck = true) }
            }
            SubmitRow(answered = true, enabled = false) {}
        } else {
            // Long lists scroll inside a bounded area so the card never outgrows the bubble.
            val scroll = card.options.size > 5
            Column(
                Modifier.fillMaxWidth().then(if (scroll) Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                card.options.forEach { option ->
                    val checked = option in selected
                    ChatChoiceTile(option, checked, card.multi, true) {
                        if (card.multi) { if (checked) selected.remove(option) else selected.add(option) }
                        else { selected.clear(); selected.add(option); other = ""; otherOpen = false }
                    }
                }
                if (card.allowOther) {
                    ChatChoiceTile(stringResource(R.string.chat_card_other_hint), otherOpen, card.multi, true, other = true) {
                        if (otherOpen) { otherOpen = false; other = "" }
                        else { if (!card.multi) selected.clear(); otherOpen = true }
                    }
                    if (otherOpen) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        CardTextField(
                            value = other, onValueChange = { other = it },
                            placeholder = stringResource(R.string.chat_card_other_hint),
                            modifier = Modifier.focusRequester(focus)
                        )
                    }
                }
            }
            if (batch == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SubmitRow(answered = false, enabled = submitEnabled && hasAnswer) {
                        onSubmit(ChatCardAnswerText.choices(card, selected.toList(), otherOrNull), ChatCardState.encodeChoices(selected.toList(), otherOrNull))
                    }
                }
            }
        }
    }
}

/**
 * Whole-row option tile: filled indicator (disc for single, rounded check box for multi), tonal fill and 1.5 dp ink border
 * when selected. The entire row is the click target. [other] swaps the indicator for a pencil.
 */
@Composable
private fun ChatChoiceTile(label: String, selected: Boolean, multi: Boolean, enabled: Boolean, other: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) scheme.surfaceContainerHigh else Color.Transparent, label = "tileFill")
    val border by animateColorAsState(if (selected) scheme.onSurface else chatCardLine(), label = "tileBorder")
    val shape = AppChromeDefaults.CompactShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .background(fill, shape)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChoiceIndicator(selected, multi, other)
        Text(
            label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            maxLines = Int.MAX_VALUE
        )
    }
}

@Composable
private fun ChoiceIndicator(selected: Boolean, multi: Boolean, other: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) scheme.onSurface else Color.Transparent, label = "indicatorFill")
    val ring by animateColorAsState(if (selected) scheme.onSurface else scheme.outline, label = "indicatorRing")
    if (other && !selected) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        return
    }
    if (multi || other) {
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.size(20.dp).background(fill, shape).border(1.5.dp, ring, shape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.surface, modifier = Modifier.size(14.dp))
        }
    } else {
        Box(Modifier.size(22.dp).background(fill, CircleShape).border(1.5.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(scheme.surface, CircleShape))
        }
    }
}

/**
 * Follow-ups: Frontier list rows (hairline above and between, text + arrow). Tapping sends the row as a user message;
 * afterwards the chosen row keeps a check and the others dim. Long text wraps to several lines.
 */
@Composable
internal fun ChatFollowupsCard(
    card: ChatCard.Followups,
    answerState: String?,
    submitEnabled: Boolean,
    onSubmit: (String, String) -> Unit
) {
    val chosen = remember(answerState) { answerState?.let(ChatCardState::decodeChoices)?.selected.orEmpty() }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.items.forEach { item ->
            val picked = item in chosen
            val canTap = submitEnabled && answerState == null
            CardHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (answerState != null && !picked) 0.5f else 1f)
                    .then(if (canTap) Modifier.clickable { onSubmit(item, ChatCardState.encodeChoices(listOf(item), null)) } else Modifier)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    item, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal),
                    color = scheme.onSurface, maxLines = Int.MAX_VALUE
                )
                Icon(
                    if (picked) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
                )
            }
        }
        CardHairline()
    }
}

/** Answered: check + "Answered" micro label. Otherwise the ink pill Send button (right-aligned by the caller). */
@Composable
internal fun SubmitRow(answered: Boolean, enabled: Boolean, label: String? = null, onClick: () -> Unit) {
    if (answered) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.chat_card_answered), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        CardPrimaryButton(label ?: stringResource(R.string.chat_card_send), enabled, onClick)
    }
}
