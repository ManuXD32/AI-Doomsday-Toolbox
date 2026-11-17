package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCardType
import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.chat.cards.ChatCardsDefaults
import com.example.llamadroid.ui.settings.chat.ChatSettingsSection
import com.example.llamadroid.ui.settings.chat.LocalChatGlobalSettingsOpener

/**
 * "Interactive cards" opt-in (off by default). The cost and network warnings are always visible,
 * not hidden behind the switch, so the user sees them before enabling.
 */
@Composable
internal fun ChatSettingsState.ChatCardsSettingsRow() {
    val context = LocalContext.current.applicationContext
    val disabled by remember(context) { ChatCardsDefaults.observeDisabledTypes(context) }
        .collectAsState(initial = ChatCardsDefaults.disabledTypes(context))
    val tokens = ChatCardsAwareness.approxTokens(ChatCardType.entries.toSet() - disabled)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.chat_cards_setting_title),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.chat_cards_setting_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = pluralStringResource(R.plurals.chat_cards_setting_tokens, tokens, tokens),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.chat_cards_setting_output_cost),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.chat_cards_setting_map_network),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.chat_cards_setting_cache_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = cardsEnabled, onCheckedChange = { cardsEnabled = it })
    }
    val openGlobalSettings = LocalChatGlobalSettingsOpener.current
    OutlinedButton(onClick = { openGlobalSettings(ChatSettingsSection.CARDS) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.chat_settings_for_all_chats), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(
        text = stringResource(R.string.chat_cards_global_moved_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    }
}

/** App-wide "use in new chats" switch; writes immediately and is independent of the per-chat save. */
@Composable
internal fun ChatCardsGlobalDefaultRow() {
    val context = LocalContext.current.applicationContext
    val defaultOn by remember(context) { ChatCardsDefaults.observe(context) }
        .collectAsState(initial = ChatCardsDefaults.isDefaultOn(context))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.chat_cards_default_title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.chat_cards_default_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = defaultOn, onCheckedChange = { ChatCardsDefaults.setDefaultOn(context, it) })
    }
}
