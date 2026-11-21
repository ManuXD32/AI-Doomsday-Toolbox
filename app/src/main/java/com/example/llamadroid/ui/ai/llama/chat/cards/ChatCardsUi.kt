package com.example.llamadroid.ui.ai.llama.chat.cards

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageCardStateEntity
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.ui.ai.llama.chat.cards.omni.ChatOmniCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the message list needs to draw interactive cards. Null at every call site means "cards off for this chat".
 * [states] maps message id -> card index -> stored `stateJson` of the answered card.
 */
class ChatCardsUi(
    val states: Map<Long, Map<Int, String>>,
    /** False while a reply is generating: answers are queued by the user, not mid-stream. */
    val submitEnabled: Boolean,
    /**
     * Several unanswered choices/form cards of ONE message: saves every card's state and sends ONE user message [text].
     * When null the answers fall back to [onSubmit] with the first card's state (later states are then not saved).
     */
    val onSubmitBatch: ((messageId: Long, answers: List<ChatCardBatchAnswer>, text: String) -> Unit)? = null,
    /** Persists a card's state without sending anything (omni cards save their values here, debounced by the card). */
    val onSaveState: ((messageId: Long, cardIndex: Int, stateJson: String) -> Unit)? = null,
    val onSubmit: (messageId: Long, cardIndex: Int, text: String, stateJson: String) -> Unit
) {
    internal fun submitBatch(messageId: Long, answers: List<ChatCardBatchAnswer>, text: String) {
        val batch = onSubmitBatch
        if (batch != null) batch(messageId, answers, text)
        else answers.firstOrNull()?.let { onSubmit(messageId, it.cardIndex, text, it.stateJson) }
    }
}

/** State of one card answered as part of a batch (see [ChatCardsUi.onSubmitBatch]). */
data class ChatCardBatchAnswer(val cardIndex: Int, val stateJson: String)

/** Answered-card state for the chat, collected once and grouped per message. */
@Composable
internal fun rememberChatCardStates(chatId: Long, enabled: Boolean): Map<Long, Map<Int, String>> {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!enabled) return emptyMap()
    val rows by remember(chatId) {
        AppDatabase.getDatabase(context).llamaMessageCardStateDao().observeForChat(chatId)
    }.collectAsState(initial = emptyList<LlamaMessageCardStateEntity>())
    return remember(rows) {
        rows.groupBy { it.messageId }.mapValues { (_, list) -> list.associate { it.cardIndex to it.stateJson } }
    }
}

/**
 * Stores the answer for a card, then sends [text] as a normal user message (same ACTION_GENERATE path as the composer).
 * The state row is written first so the card shows as answered even if the service takes time to start.
 */
internal fun submitChatCardAnswer(
    context: Context,
    scope: CoroutineScope,
    chatId: Long,
    serverId: Long,
    messageId: Long,
    cardIndex: Int,
    text: String,
    stateJson: String
) = submitChatCardAnswers(context, scope, chatId, serverId, messageId, listOf(ChatCardBatchAnswer(cardIndex, stateJson)), text)

/** Batch variant: upserts every card's state, then sends ONE user message ([text], the answers joined in order). */
internal fun submitChatCardAnswers(
    context: Context,
    scope: CoroutineScope,
    chatId: Long,
    serverId: Long,
    messageId: Long,
    answers: List<ChatCardBatchAnswer>,
    text: String
) {
    scope.launch(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).llamaMessageCardStateDao()
        val now = System.currentTimeMillis()
        answers.forEach { dao.upsert(LlamaMessageCardStateEntity(messageId, it.cardIndex, it.stateJson, now)) }
    }
    val intent = Intent(context, LlamaClientService::class.java).apply {
        action = LlamaClientService.ACTION_GENERATE
        putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
        putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
        putExtra(LlamaClientService.EXTRA_USER_MESSAGE, text)
    }
    context.startForegroundService(intent)
}

/** Upserts a card's state row (IO) without sending a message; used by omni cards to keep their values. */
internal fun saveChatCardState(context: Context, scope: CoroutineScope, messageId: Long, cardIndex: Int, stateJson: String) {
    scope.launch(Dispatchers.IO) {
        AppDatabase.getDatabase(context).llamaMessageCardStateDao()
            .upsert(LlamaMessageCardStateEntity(messageId, cardIndex, stateJson, System.currentTimeMillis()))
    }
}

/**
 * Slot for `MarkdownText(cardContent = ...)`: one rendered card of message [messageId] whose raw text is [content].
 * Two or more unanswered choices/form cards in the same message are answered together: their own Send buttons are
 * hidden and the last of them is followed by one "Send answers" button (see [ChatCardBatch]).
 */
@Composable
internal fun rememberChatCardSlot(messageId: Long, content: String, cards: ChatCardsUi): @Composable (Int, ChatCard) -> Unit {
    val batch = remember(messageId, content) { ChatCardBatch() }
    return { index, card ->
        val answerState = cards.states[messageId]?.get(index)
        val members = remember(content, cards.states[messageId]) { batchMembers(content, cards.states[messageId].orEmpty()) }
        val inBatch = index in members && members.size >= 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val save = cards.onSaveState
            ChatCardView(
                card, answerState, cards.submitEnabled, if (inBatch) batch.bind(index) else null,
                onSaveState = save?.let { f -> { state: String -> f(messageId, index, state) } }
            ) { text, state ->
                cards.onSubmit(messageId, index, text, state)
            }
            if (inBatch && index == members.last()) {
                ChatCardSendAnswersButton(batch, members, cards.submitEnabled) { answers, text ->
                    cards.submitBatch(messageId, answers, text)
                }
            }
        }
    }
}

/** Streaming placeholder: the card is parsed once, when the message is complete. */
@Composable
internal fun ChatCardPreparingLine() {
    Text(
        text = stringResource(R.string.chat_card_preparing),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Dispatches one parsed card to its renderer. [answerState] non-null = already answered (read-only). */
@Composable
internal fun ChatCardView(
    card: ChatCard,
    answerState: String?,
    submitEnabled: Boolean,
    batch: ChatCardBatchBinding? = null,
    onSaveState: ((stateJson: String) -> Unit)? = null,
    onSubmit: (text: String, stateJson: String) -> Unit
) {
    when (card) {
        is ChatCard.Choices -> ChatChoicesCard(card, answerState, submitEnabled, batch, onSubmit)
        is ChatCard.Form -> ChatFormCard(card, answerState, submitEnabled, batch, onSubmit)
        is ChatCard.Input -> ChatInputCard(card, answerState, submitEnabled, batch, onSubmit)
        is ChatCard.Followups -> ChatFollowupsCard(card, answerState, submitEnabled, onSubmit)
        is ChatCard.MapCard -> ChatMapCardContent(card, Modifier.fillMaxWidth())
        is ChatCard.Stats -> ChatStatsCard(card)
        is ChatCard.Timeline -> ChatTimelineCard(card)
        is ChatCard.Callout -> ChatCalloutCard(card)
        is ChatCard.Links -> ChatLinksCard(card)
        is ChatCard.Chart -> ChatChartCard(card)
        is ChatCard.Places -> ChatPlacesCard(card)
        is ChatCard.Images -> ChatImagesCard(card)
        is ChatCard.Weather -> ChatWeatherCard(card)
        is ChatCard.Budget -> ChatBudgetCard(card)
        is ChatCard.Omni -> ChatOmniCard(card, answerState, submitEnabled, onSaveState, onSubmit)
    }
}
