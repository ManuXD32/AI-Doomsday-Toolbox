package com.example.llamadroid.ui.settings.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCardType
import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.chat.cards.ChatCardsDefaults
import com.example.llamadroid.ui.ai.llama.chat.ChatCardsGlobalDefaultRow
import com.example.llamadroid.ui.ai.llama.chat.cards.ChatCardTypesGalleryDialog
import com.example.llamadroid.ui.ai.llama.chatlist.WearQuickChatSettingsDialog
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.knowledge.EmbeddingModelChooser
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.settings.ChatKvCacheStorageCard
import com.example.llamadroid.ui.settings.TitleGenerationSettingsCard
import com.example.llamadroid.ui.settings.VisionHelperSettingsCard
import com.example.llamadroid.ui.settings.WebSearchSettingsCard
import kotlinx.coroutines.delay

/**
 * Global "Chat settings": the single home of every app-wide chat preference. Each section reuses the
 * existing composable and storage (no stored value moved); see `docs/chat-settings.md`.
 * [initialSection] scrolls to a section once (route argument `section`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatGlobalSettingsScreen(navController: NavController, initialSection: ChatSettingsSection? = null) {
    val serversViewModel: ServerRegistryViewModel = viewModel()
    val requesters = remember { ChatSettingsSection.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(initialSection) {
        if (initialSection != null) {
            delay(150)
            requesters.getValue(initialSection).bringIntoView()
        }
    }
    AppScreenScaffold(
        title = stringResource(R.string.chat_global_settings_title),
        subtitle = stringResource(R.string.chat_global_settings_subtitle),
        onBack = { navController.popBackStack() }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ChatSettingsSection.entries.forEach { section ->
                Column(
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requesters.getValue(section)),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    when (section) {
                        ChatSettingsSection.DOCUMENTS -> DocumentsSection(navController)
                        ChatSettingsSection.HELPERS -> {
                            VisionHelperSettingsCard()
                            TitleGenerationSettingsCard()
                        }
                        ChatSettingsSection.CARDS -> CardsSection()
                        ChatSettingsSection.WEB -> WebSearchSettingsCard()
                        ChatSettingsSection.CACHES -> {
                            ChatKvCacheStorageCard(
                                titleRes = R.string.general_prompt_caches_title,
                                deleteAllLabelRes = R.string.general_prompt_caches_erase_all,
                                deleteAllTitleRes = R.string.general_prompt_caches_erase_title,
                                deleteAllMessageRes = R.string.general_prompt_caches_erase_message
                            )
                            SectionHint(R.string.chat_global_caches_prepare_hint)
                        }
                        ChatSettingsSection.NEW_CHATS -> NewChatsSection(serversViewModel)
                        ChatSettingsSection.DELEGATION -> DelegationSection()
                        ChatSettingsSection.WEAR -> WearSection(serversViewModel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionHint(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DocumentsSection(navController: NavController) {
    SectionHint(R.string.chat_global_documents_hint)
    EmbeddingModelChooser(modifier = Modifier.fillMaxWidth())
    OutlinedButton(
        onClick = { navController.navigate(Screen.KnowledgeBase.route) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.chat_global_documents_advanced), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var galleryOpen by remember { mutableStateOf(false) }
    val disabled = remember(context, galleryOpen) { ChatCardsDefaults.disabledTypes(context) }
    val tokens = ChatCardsAwareness.approxTokens(ChatCardType.entries.toSet() - disabled)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatCardsGlobalDefaultRow()
            Text(
                text = pluralStringResource(R.plurals.chat_cards_setting_tokens_global, tokens, tokens),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SectionHint(R.string.chat_card_gallery_open_desc)
            OutlinedButton(onClick = { galleryOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_card_gallery_open), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (galleryOpen) ChatCardTypesGalleryDialog(onDismiss = { galleryOpen = false })
}

@Composable
private fun WearSection(serversViewModel: ServerRegistryViewModel) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wear_quick_chat_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) WearQuickChatSettingsDialog(serversViewModel = serversViewModel, onDismiss = { open = false })
}
