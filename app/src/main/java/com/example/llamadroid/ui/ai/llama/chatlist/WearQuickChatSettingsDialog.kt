package com.example.llamadroid.ui.ai.llama.chatlist

import androidx.core.content.edit
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.ai.llama.servers.ServerPickerSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel

private const val WEAR_QUICK_CHAT_PREFS = "adt_wear_quick_chat_v1"
private const val WEAR_QUICK_CHAT_SELECTED_SERVER_ID = "selected_server_id"
private const val WEAR_QUICK_CHAT_SYSTEM_PROMPT = "system_prompt"
private const val WEAR_QUICK_CHAT_ALLOWED_TOOLS = "allowed_tools"
private const val WEAR_QUICK_CHAT_AUTO_START = "auto_start_server"
private const val WEAR_QUICK_CHAT_AUTO_TTS = "auto_play_tts"
private const val WEAR_TOOL_CALENDAR = "calendar"
private const val WEAR_TOOL_ALARMS = "alarms"
private const val WEAR_TOOL_NOTES = "notes"
private const val WEAR_TOOL_PINNED_NOTE = "pinned_note"
private const val WEAR_TOOL_WEB = "web_search"
private const val WEAR_TOOL_IMAGES = "image_generation"

/** Wear Quick Chat settings (moved out of the chat list's fake folder into its overflow menu). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WearQuickChatSettingsDialog(
    serversViewModel: ServerRegistryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val servers by serversViewModel.servers.collectAsState()
    var showServerPicker by remember { mutableStateOf(false) }
    val prefs = remember(context) {
        context.getSharedPreferences(WEAR_QUICK_CHAT_PREFS, Context.MODE_PRIVATE)
    }
    val defaultPrompt = stringResource(R.string.wear_quick_chat_default_prompt)
    var selectedServerId by remember {
        mutableLongStateOf(prefs.getLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, -1L))
    }
    var systemPrompt by remember {
        mutableStateOf(prefs.getString(WEAR_QUICK_CHAT_SYSTEM_PROMPT, defaultPrompt) ?: defaultPrompt)
    }
    var autoStartServer by remember {
        mutableStateOf(prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_START, true))
    }
    var autoPlayTts by remember {
        mutableStateOf(prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_TTS, false))
    }
    var allowedTools by remember {
        mutableStateOf(
            prefs.getStringSet(
                WEAR_QUICK_CHAT_ALLOWED_TOOLS,
                setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES)
            )?.toSet() ?: setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES)
        )
    }

    if (showServerPicker) {
        // Same prefs key as before: the value is the connection row id.
        ServerPickerSheet(
            viewModel = serversViewModel,
            selectedRowId = selectedServerId.takeIf { it > 0 },
            onDismiss = { showServerPicker = false },
            onPick = { rowId ->
                selectedServerId = rowId
                prefs.edit { putLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, rowId) }
                showServerPicker = false
            },
            onCreateNew = null
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wear_quick_chat_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.llama_server_label),
                    style = MaterialTheme.typography.labelLarge
                )
                val selectedServer = servers.firstOrNull { it.rowId != null && it.rowId == selectedServerId }
                OutlinedButton(
                    onClick = { showServerPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedServer?.let { stringResource(R.string.wear_quick_chat_settings_server, it.name) }
                            ?: stringResource(R.string.wear_quick_chat_settings_no_server),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        systemPrompt = it
                        prefs.edit { putString(WEAR_QUICK_CHAT_SYSTEM_PROMPT, it) }
                    },
                    label = { Text(stringResource(R.string.llama_system_prompt)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    minLines = 3
                )
                WearQuickChatSettingsSwitchRow(
                    label = stringResource(R.string.wear_quick_chat_auto_start),
                    checked = autoStartServer,
                    onCheckedChange = {
                        autoStartServer = it
                        prefs.edit { putBoolean(WEAR_QUICK_CHAT_AUTO_START, it) }
                    }
                )
                WearQuickChatSettingsSwitchRow(
                    label = stringResource(R.string.llama_tool_assistant_tts),
                    checked = autoPlayTts,
                    onCheckedChange = {
                        autoPlayTts = it
                        prefs.edit { putBoolean(WEAR_QUICK_CHAT_AUTO_TTS, it) }
                    }
                )
                Text(
                    text = stringResource(R.string.llama_tools_title),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        WEAR_TOOL_CALENDAR to stringResource(R.string.llama_tool_calendar),
                        WEAR_TOOL_ALARMS to stringResource(R.string.llama_tool_alarms),
                        WEAR_TOOL_NOTES to stringResource(R.string.llama_tool_notes),
                        WEAR_TOOL_PINNED_NOTE to stringResource(R.string.wear_quick_chat_modify_pinned_note),
                        WEAR_TOOL_WEB to stringResource(R.string.llama_tool_web_search),
                        WEAR_TOOL_IMAGES to stringResource(R.string.llama_tool_image_generation)
                    ).forEach { (tool, label) ->
                        FilterChip(
                            selected = tool in allowedTools,
                            onClick = {
                                val enabled = tool !in allowedTools
                                allowedTools = if (enabled) allowedTools + tool else allowedTools - tool
                                prefs.edit { putStringSet(WEAR_QUICK_CHAT_ALLOWED_TOOLS, allowedTools) }
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun WearQuickChatSettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
