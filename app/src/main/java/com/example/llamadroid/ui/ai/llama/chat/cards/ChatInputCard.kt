package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardAnswerText
import com.example.llamadroid.chat.cards.ChatCardState

/**
 * Free-text question card. The question is shown in full (no truncation); the answer is sent as "Question: answer".
 * Answered cards show the stored answer read-only. Inside a multi-card message it only reports its draft
 * (the shared "Send answers" button sends it).
 */
@Composable
internal fun ChatInputCard(
    card: ChatCard.Input,
    answerState: String?,
    submitEnabled: Boolean,
    batch: ChatCardBatchBinding?,
    onSubmit: (String, String) -> Unit
) {
    val answered = remember(answerState) { answerState?.let(ChatCardState::decodeInput) }
    var text by remember(card, answerState) { mutableStateOf(answered.orEmpty()) }
    val locked = answerState != null
    val ready = text.isNotBlank()
    if (batch != null && !locked) {
        val draft = ChatCardDraft(ready, ChatCardAnswerText.input(card, text), ChatCardState.encodeInput(text.trim()))
        SideEffect { batch.report(draft) }
    }
    CardShell(title = card.question, modifier = Modifier.animateContentSize()) {
        if (locked) {
            Text(
                text.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SubmitRow(answered = true, enabled = false) {}
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (card.multiline) 96.dp else 56.dp),
                placeholder = card.placeholder?.let { { Text(it, maxLines = 2) } },
                singleLine = !card.multiline,
                minLines = if (card.multiline) 3 else 1,
                maxLines = if (card.multiline) 8 else 1,
                keyboardOptions = KeyboardOptions(imeAction = if (card.multiline) ImeAction.Default else ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (batch == null && submitEnabled && ready) {
                        onSubmit(ChatCardAnswerText.input(card, text), ChatCardState.encodeInput(text.trim()))
                    }
                }),
                shape = MaterialTheme.shapes.small
            )
            if (batch == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SubmitRow(answered = false, enabled = submitEnabled && ready, label = card.submit) {
                        onSubmit(ChatCardAnswerText.input(card, text), ChatCardState.encodeInput(text.trim()))
                    }
                }
            }
        }
    }
}
