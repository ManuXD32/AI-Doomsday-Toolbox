package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCardType
import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.chat.cards.ChatCardsDefaults

/** Test tag of the switch of [type] in the gallery. */
internal fun chatCardTypeSwitchTag(type: ChatCardType): String = "chat_card_type_switch_${type.key}"

/**
 * Full-screen gallery of every card type the model can write: title, one-line description, a preview drawn with the real
 * card composables and sample data (cards that need the internet show an offline placeholder instead) and a global switch.
 * One implementation, opened from the chat cards settings row.
 */
@Composable
internal fun ChatCardTypesGalleryDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            ChatCardTypesGallery(onClose = onDismiss)
        }
    }
}

@Composable
internal fun ChatCardTypesGallery(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val disabled by remember(context) { ChatCardsDefaults.observeDisabledTypes(context) }
        .collectAsState(initial = ChatCardsDefaults.disabledTypes(context))
    val tokens = ChatCardsAwareness.approxTokens(ChatCardType.entries.toSet() - disabled)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.chat_card_gallery_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.chat_card_gallery_close), maxLines = 1) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(CHAT_CARD_TYPES_LIST_TAG),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hint") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.chat_card_gallery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        pluralStringResource(R.plurals.chat_cards_setting_tokens_global, tokens, tokens),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(ChatCardType.entries.toList(), key = { it.key }) { type ->
                val enabled = type !in disabled
                ChatCardTypeGalleryItem(type, enabled) { on ->
                    ChatCardsDefaults.setDisabledTypes(context, if (on) disabled - type else disabled + type)
                }
                HorizontalDivider(color = chatCardLine())
            }
        }
    }
}

@Composable
private fun ChatCardTypeGalleryItem(type: ChatCardType, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val title = stringResource(chatCardTypeTitle(type))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(chatCardTypeDescription(type)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val label = stringResource(R.string.chat_card_gallery_toggle, title)
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(chatCardTypeSwitchTag(type)).semantics { contentDescription = label }
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChatCardTypePreview(type) }
    }
}

/** Test tag of the gallery's scrolling list (the previews contain scrollable rows of their own). */
internal const val CHAT_CARD_TYPES_LIST_TAG = "chat_card_types_list"
