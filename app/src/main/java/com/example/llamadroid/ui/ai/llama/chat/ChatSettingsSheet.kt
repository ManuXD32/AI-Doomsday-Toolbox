package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * Chat settings as a scrollable bottom sheet with collapsible sections. Every valid change is
 * persisted by the screen's auto-save effect, so there are no Cancel/Save buttons and closing the
 * sheet loses nothing.
 *
 * @param serverSummary one-line description of the current server for the first section header
 * @param onOpenServerSheet closes this sheet and opens the chat server sheet
 * @param memorySection optional slot for the per-chat memory (KV cache) controls; when null the
 *        Memory section is not shown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
    state: ChatSettingsState,
    env: ChatSettingsEnv,
    serverSummary: String,
    onOpenServerSheet: () -> Unit,
    onChangeServer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    memorySection: (@Composable () -> Unit)? = null
) {
    val summary = state.toolsSummary
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (state.activeServerIsLiteRt) R.string.litert_gallery_model_configs else R.string.native_chat_settings_title
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.native_chat_settings_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChatSettingsGroup(
                key = "server",
                title = stringResource(R.string.native_chat_settings_section_server),
                summary = serverSummary,
                initiallyExpanded = true
            ) {
                state.ServerContextSection(env)
                OutlinedButton(onClick = onOpenServerSheet, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.native_chat_settings_open_server),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.native_chat_change_server),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            ChatSettingsGroup(
                key = "sampling",
                title = stringResource(R.string.native_chat_settings_section_sampling),
                summary = "${state.temperatureText} · ${state.topPText} · ${state.topKText}",
                initiallyExpanded = true
            ) { state.SamplingSection() }
            ChatSettingsGroup(
                key = "thinking",
                title = stringResource(R.string.native_chat_settings_section_thinking),
                summary = stringResource(
                    if (state.enableThinking) R.string.native_chat_settings_on else R.string.native_chat_settings_off
                )
            ) { state.ThinkingSection() }
            ChatSettingsGroup(
                key = "voice",
                title = stringResource(R.string.native_chat_settings_section_voice),
                summary = stringResource(
                    if (state.assistantTtsEnabled) R.string.native_chat_settings_voice_on else R.string.native_chat_settings_voice_off
                )
            ) { state.VoiceCallSection(env) }
            ChatSettingsGroup(
                key = "tools",
                title = stringResource(R.string.native_chat_settings_section_tools),
                summary = stringResource(R.string.native_chat_settings_tools_summary, summary.on, summary.total)
            ) { state.ToolsSection(env) }
            ChatSettingsGroup(
                key = "image_tools",
                title = stringResource(R.string.native_chat_settings_section_image_tools),
                summary = stringResource(
                    if (state.effectiveImageGenerationEnabled || state.effectiveBackgroundRemovalEnabled) {
                        R.string.native_chat_settings_on
                    } else {
                        R.string.native_chat_settings_off
                    }
                )
            ) { state.ImageToolsSection(env) }
            ChatSettingsGroup(
                key = "knowledge",
                title = stringResource(R.string.native_chat_settings_section_knowledge),
                summary = stringResource(
                    if (state.effectiveKnowledgeBaseEnabled) R.string.native_chat_settings_on else R.string.native_chat_settings_off
                )
            ) { state.KnowledgeBaseSection(env) }
            if (memorySection != null) {
                ChatSettingsGroup(
                    key = "memory",
                    title = stringResource(R.string.native_chat_settings_section_memory),
                    summary = null
                ) { memorySection() }
            }
            ChatSettingsGroup(
                key = "advanced",
                title = stringResource(R.string.native_chat_settings_section_advanced),
                summary = null
            ) { state.AdvancedSection() }
        }
    }
}

/** Collapsible settings group; the expanded state survives rotation per [key]. */
@Composable
private fun ChatSettingsGroup(
    key: String,
    title: String,
    summary: String?,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.native_chat_settings_collapse else R.string.native_chat_settings_expand
                    )
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
