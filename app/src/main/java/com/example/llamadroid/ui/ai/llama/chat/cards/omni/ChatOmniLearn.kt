package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock

/** Stacked multiple-choice quiz. Tapping an option locks the question (green = correct, red = your wrong answer). */
@Composable
internal fun OmniQuiz(b: OmniBlock.Quiz, rt: OmniRuntime) {
    val n = b.questions.size
    val saved = OmniBlock.Quiz.decodeAnswers(rt.texts[b.id])
    val answers = List(n) { saved.getOrNull(it) ?: -1 }
    val score = b.score(answers)
    fun choose(q: Int, o: Int) {
        if (answers[q] >= 0) return
        val next = answers.toMutableList().also { it[q] = o }
        rt.texts[b.id] = OmniBlock.Quiz.encodeAnswers(next)
        rt.values[b.id] = b.score(next).toDouble()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OmniLabel(b.label, Modifier.weight(1f))
            Text(
                stringResource(R.string.chat_card_omni_quiz_score, score, n),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1
            )
        }
        b.questions.forEachIndexed { qi, q ->
            val chosen = answers[qi]
            Text(q.q, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            q.options.forEachIndexed { oi, option ->
                val right = chosen >= 0 && oi == q.answer
                val wrong = chosen == oi && oi != q.answer
                val shown = when {
                    right -> "✓ $option"
                    wrong -> "✗ $option"
                    else -> option
                }
                val desc = when {
                    right -> stringResource(R.string.chat_card_omni_quiz_correct, option)
                    wrong -> stringResource(R.string.chat_card_omni_quiz_wrong, option)
                    else -> option
                }
                Surface(
                    onClick = { choose(qi, oi) },
                    enabled = chosen < 0,
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        right -> MaterialTheme.colorScheme.tertiaryContainer
                        wrong -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = desc }
                ) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { rt.texts[b.id] = ""; rt.values[b.id] = 0.0 },
            enabled = answers.any { it >= 0 },
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(stringResource(R.string.chat_card_omni_reset), maxLines = 1) }
    }
}

/** One flashcard at a time; tapping the face flips between front and back. */
@Composable
internal fun OmniFlashcards(b: OmniBlock.Flashcards) {
    if (b.cards.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(0) }
    var flipped by rememberSaveable { mutableStateOf(false) }
    val i = index.coerceIn(0, b.cards.lastIndex)
    val card = b.cards[i]
    val prev = stringResource(R.string.chat_card_omni_card_prev)
    val next = stringResource(R.string.chat_card_omni_card_next)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        Surface(
            onClick = { flipped = !flipped },
            shape = MaterialTheme.shapes.medium,
            color = if (flipped) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (flipped) card.second else card.first,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.chat_card_omni_card_flip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OmniStepButton("‹", prev, enabled = i > 0) { index = i - 1; flipped = false }
            Text(
                stringResource(R.string.chat_card_omni_counter, i + 1, b.cards.size),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            OmniStepButton("›", next, enabled = i < b.cards.lastIndex) { index = i + 1; flipped = false }
        }
    }
}

/** Step-by-step guide: "Step 2/5", the step text and Back / Next. */
@Composable
internal fun OmniSteps(b: OmniBlock.Steps) {
    if (b.items.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(0) }
    val i = index.coerceIn(0, b.items.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        Text(
            stringResource(R.string.chat_card_omni_step_counter, i + 1, b.items.size),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        LinearProgressIndicator(progress = { (i + 1).toFloat() / b.items.size }, modifier = Modifier.fillMaxWidth())
        Text(b.items[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 44.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { index = i - 1 }, enabled = i > 0, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(stringResource(R.string.chat_card_omni_step_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = { index = i + 1 }, enabled = i < b.items.lastIndex, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(stringResource(R.string.chat_card_omni_step_next), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
