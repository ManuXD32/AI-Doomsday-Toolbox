package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * The collapsible groups of the chat settings, shared by the chat's settings sheet and by the
 * server editors' "defaults for new chats" section (`env.defaultsMode = true`). Add every new
 * per-chat option here (or inside one of these groups) and it reaches both places.
 *
 * In defaults mode the groups that only make sense for an existing chat are skipped: the server
 * context/actions and the chat's KV-cache memory controls. [delegationSection] supplies the
 * DeepSeek runtime controls in both modes.
 */
@Composable
internal fun ChatSettingsGroupList(
    state: ChatSettingsState,
    env: ChatSettingsEnv,
    serverSummary: String = "",
    onOpenServerSheet: () -> Unit = {},
    onChangeServer: () -> Unit = {},
    memorySection: (@Composable () -> Unit)? = null,
    delegationSection: (@Composable () -> Unit)? = null
) {
    val defaultsMode = env.defaultsMode
    val summary = state.toolsSummary
    // Expansion state is keyed per mode so the two hosts never share it.
    fun groupKey(name: String) = if (defaultsMode) "defaults_$name" else name

    if (!defaultsMode) {
        ChatSettingsGroup(
            key = groupKey("server"),
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
    }
    ChatSettingsGroup(
        key = groupKey("sampling"),
        title = stringResource(R.string.native_chat_settings_section_sampling),
        summary = state.samplerSummary(stringResource(R.string.chat_sampler_auto)),
        initiallyExpanded = true
    ) { state.SamplingSection() }
    ChatSettingsGroup(
        key = groupKey("thinking"),
        title = stringResource(R.string.native_chat_settings_section_thinking),
        summary = stringResource(
            if (state.enableThinking) R.string.native_chat_settings_on else R.string.native_chat_settings_off
        )
    ) { state.ThinkingSection() }
    ChatSettingsGroup(
        key = groupKey("voice"),
        title = stringResource(R.string.native_chat_settings_section_voice),
        summary = stringResource(
            if (state.assistantTtsEnabled) R.string.native_chat_settings_voice_on else R.string.native_chat_settings_voice_off
        )
    ) { state.VoiceCallSection(env) }
    ChatSettingsGroup(
        key = groupKey("tools"),
        title = stringResource(R.string.native_chat_settings_section_tools),
        summary = stringResource(R.string.native_chat_settings_tools_summary, summary.on, summary.total)
    ) { state.ToolsSection(env) }
    ChatSettingsGroup(
        key = groupKey("image_tools"),
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
        key = groupKey("knowledge"),
        title = stringResource(R.string.native_chat_settings_section_knowledge),
        summary = stringResource(
            if (state.effectiveKnowledgeBaseEnabled) R.string.native_chat_settings_on else R.string.native_chat_settings_off
        )
    ) { state.KnowledgeBaseSection(env) }
    if (memorySection != null && !defaultsMode) {
        ChatSettingsGroup(
            key = groupKey("memory"),
            title = stringResource(R.string.native_chat_settings_section_memory),
            summary = null
        ) { memorySection() }
    }
    if (delegationSection != null) {
        ChatSettingsGroup(
            key = groupKey("delegation"),
            title = stringResource(R.string.native_chat_delegation_group_title),
            summary = stringResource(
                if (state.delegation.enabled) R.string.native_chat_settings_on else R.string.native_chat_settings_off
            )
        ) { delegationSection() }
    }
    ChatSettingsGroup(
        key = groupKey("compaction"),
        title = stringResource(R.string.native_chat_compaction_gauge_title),
        summary = stringResource(
            if (state.autoCompactEnabled) R.string.native_chat_settings_on else R.string.native_chat_settings_off
        )
    ) { state.CompactionSection() }
    ChatSettingsGroup(
        key = groupKey("advanced"),
        title = stringResource(R.string.native_chat_settings_section_advanced),
        summary = null
    ) { state.AdvancedSection(defaultsMode) }
}

/** Collapsible settings group; the expanded state survives rotation per [key]. */
@Composable
internal fun ChatSettingsGroup(
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
