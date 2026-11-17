package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardState
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniEnv
import com.example.llamadroid.chat.cards.omni.OmniEnvs
import com.example.llamadroid.chat.cards.omni.OmniExpr
import com.example.llamadroid.ui.ai.llama.chat.cards.CardShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

/** Live values of one omni card: seeded from the saved state, changed by the blocks, persisted (debounced) by the card. */
internal class OmniRuntime(private val blocks: List<OmniBlock>, saved: ChatCardState.OmniState?) {
    val values = mutableStateMapOf<String, Double>()
    /** Text-input contents, function-block formulas and block JSON state (quiz/log) by id. */
    val texts = mutableStateMapOf<String, String>()
    val checks = mutableStateMapOf<String, List<Boolean>>()
    var sent by mutableStateOf(saved?.sent)

    init {
        for ((id, initial) in OmniEnvs.initialNumbers(blocks)) values[id] = saved?.values?.get(id) ?: initial
        for ((id, initial) in OmniEnvs.initialTexts(blocks)) texts[id] = saved?.texts?.get(id) ?: initial
        saved?.texts?.forEach { (id, t) -> if (!texts.containsKey(id)) texts[id] = t }
        for (b in blocks) {
            if (b is OmniBlock.Checklist) {
                val old = saved?.checks?.get(b.id).orEmpty()
                checks[b.id] = List(b.items.size) { old.getOrNull(it) ?: false }
            }
        }
    }

    /** Expression environment of the current values; [x] sets the free variable `x` (plots and tables). */
    fun env(x: Double? = null): OmniEnv = OmniEnvs.build(blocks, values.toMap(), texts.toMap(), x)

    fun encode(): String = ChatCardState.encodeOmniValues(values.toMap(), checks.toMap(), sent, texts.toMap())

    /** Replaces `{id}` placeholders with the formatted value (the option label for selects, the content for text inputs). */
    fun fill(text: String, blocks: List<OmniBlock>): String =
        PLACEHOLDER.replace(text) { m ->
            val id = m.groupValues[1]
            val block = blocks.firstOrNull { it is OmniBlock.Variable && it.id == id }
            if (block is OmniBlock.Text) return@replace texts[id] ?: m.value
            val v = values[id] ?: return@replace m.value
            if (block is OmniBlock.Select) block.options.getOrNull(block.indexOfNumber(v))?.label ?: OmniExpr.format(v, 4)
            else OmniExpr.format(v, 4)
        }

    private companion object {
        val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
    }
}

/**
 * Omni card: the model's building blocks rendered inline in the bubble (no WebView, no scripting). Values change live,
 * results recompute from them, and every change is saved through [onSaveState] (debounced; it sends nothing). A Send block
 * sends one user message through [onSubmit] while the chat is idle.
 */
@Composable
internal fun ChatOmniCard(
    card: ChatCard.Omni,
    answerState: String?,
    submitEnabled: Boolean,
    onSaveState: ((String) -> Unit)? = null,
    onSubmit: (String, String) -> Unit,
) {
    val rt = remember(card) { OmniRuntime(card.blocks, answerState?.let(ChatCardState::decodeOmni)) }
    val save by rememberUpdatedState(onSaveState)
    LaunchedEffect(rt) {
        snapshotFlow { rt.encode() }.drop(1).collect { json ->
            delay(SAVE_DEBOUNCE_MS)
            save?.invoke(json)
        }
    }
    val currentSubmit by rememberUpdatedState(onSubmit)
    CardShell(title = card.title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card.issues.isNotEmpty()) {
                OmniIssuesPanel(card, submitEnabled) {
                    rt.sent = it
                    currentSubmit(it, rt.encode())
                }
            }
            card.blocks.forEach { block ->
                OmniBlockView(block, rt, submitEnabled) { template ->
                    val text = rt.fill(template, card.blocks)
                    rt.sent = text
                    currentSubmit(text, rt.encode())
                }
            }
            rt.sent?.let {
                Text(
                    stringResource(R.string.chat_card_omni_sent, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Static sample for the card-types gallery: the same blocks, with sending off and nothing saved. */
@Composable
internal fun ChatOmniStaticPreview(card: ChatCard.Omni) {
    ChatOmniCard(card, answerState = null, submitEnabled = false, onSaveState = null, onSubmit = { _, _ -> })
}

private const val SAVE_DEBOUNCE_MS = 600L
private const val SHOWN_ISSUES = 5

/** Error-colored box listing the card's parse/formula problems, with a button that asks the model for a corrected card. */
@Composable
private fun OmniIssuesPanel(card: ChatCard.Omni, submitEnabled: Boolean, onAskFix: (String) -> Unit) {
    val lines = card.issues.map { "${it.where}: ${it.reason}" }
    val title = card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_card_type_omni_title)
    val message = stringResource(R.string.chat_card_omni_issues_message, title, lines.joinToString("\n"))
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pluralStringResource(R.plurals.chat_card_omni_issues_title, lines.size, lines.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            lines.take(SHOWN_ISSUES).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { onAskFix(message) }, enabled = submitEnabled, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.chat_card_omni_issues_fix), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
