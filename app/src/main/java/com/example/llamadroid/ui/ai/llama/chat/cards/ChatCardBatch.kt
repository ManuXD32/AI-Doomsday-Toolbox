package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCardFence
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardParser

/** The answer a choices/form card currently holds: [ready] = it may be sent, [text] = its user-message part. */
internal data class ChatCardDraft(val ready: Boolean, val text: String, val stateJson: String)

/**
 * Per-message collection point for the drafts of cards answered together. Each member card reports its draft;
 * the single "Send answers" button reads them. Reads and writes go through snapshot state so the button recomposes.
 */
internal class ChatCardBatch {
    private val drafts = mutableStateMapOf<Int, ChatCardDraft>()

    fun draft(cardIndex: Int): ChatCardDraft? = drafts[cardIndex]

    fun bind(cardIndex: Int) = ChatCardBatchBinding(cardIndex, this)

    fun report(cardIndex: Int, draft: ChatCardDraft) {
        if (drafts[cardIndex] != draft) drafts[cardIndex] = draft
    }
}

/** What a member card gets: its own index inside [ChatCardBatch]. A card with a binding hides its Send button. */
internal class ChatCardBatchBinding(private val cardIndex: Int, private val batch: ChatCardBatch) {
    fun report(draft: ChatCardDraft) = batch.report(cardIndex, draft)
}

/**
 * Indexes (among the message's `card` fences, same counting as the markdown renderer) of the unanswered
 * choices/form/input cards. Two or more of them are answered with one message.
 */
internal fun batchMembers(content: String, states: Map<Int, String>): List<Int> {
    val out = ArrayList<Int>()
    var cardIndex = 0
    for (code in cardFenceCodes(content)) {
        val card = ChatCardParser.parse(code)
        if ((card is ChatCard.Choices || card is ChatCard.Form || card is ChatCard.Input) && !states.containsKey(cardIndex)) out += cardIndex
        cardIndex++
    }
    return out
}

/** Bodies of the card fences of [raw] (see [ChatCardFence]), in order. Mirrors the fence scan of the markdown block parser. */
internal fun cardFenceCodes(content: String): List<String> {
    val raw = ChatCardFence.normalize(content)
    val out = ArrayList<String>()
    var cursor = 0
    while (cursor < raw.length) {
        val fenceStart = raw.indexOf("```", cursor)
        if (fenceStart < 0) break
        val afterFence = fenceStart + 3
        val headerEnd = raw.indexOf('\n', afterFence)
        if (headerEnd < 0) {
            if (ChatCardFence.isLabeled(raw.substring(afterFence))) out += ""
            break
        }
        val language = raw.substring(afterFence, headerEnd).trim()
        val codeStart = headerEnd + 1
        val closingFence = raw.indexOf("```", codeStart)
        if (closingFence < 0) {
            // Unclosed (still streaming) fence: only a labelled card counts, so partial JSON is never parsed per token.
            if (ChatCardFence.isLabeled(language)) out += raw.substring(codeStart).trimEnd()
            break
        }
        val code = raw.substring(codeStart, closingFence).trimEnd()
        if (ChatCardFence.isCard(language, code)) out += code
        cursor = closingFence + 3
    }
    return out
}

/**
 * The one bar that sends every member card's answer as a single message (blank line between them): muted progress
 * caption ("Answered: 2/3") on the left, ink pill "Send answers" on the right.
 */
@Composable
internal fun ChatCardSendAnswersButton(
    batch: ChatCardBatch,
    members: List<Int>,
    submitEnabled: Boolean,
    onSend: (answers: List<ChatCardBatchAnswer>, text: String) -> Unit
) {
    val drafts = members.map { batch.draft(it) }
    val ready = drafts.all { it != null && it.ready } && drafts.any { it != null && it.text.isNotBlank() }
    val done = drafts.count { it != null && it.ready }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.chat_card_answers_progress, done, members.size),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CardPrimaryButton(
            text = stringResource(R.string.chat_card_send_answers),
            enabled = submitEnabled && ready,
            onClick = {
                val answers = members.mapIndexedNotNull { i, cardIndex -> drafts[i]?.let { ChatCardBatchAnswer(cardIndex, it.stateJson) } }
                val text = drafts.mapNotNull { it?.text?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
                onSend(answers, text)
            }
        )
    }
}
