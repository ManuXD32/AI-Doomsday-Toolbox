package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.WhisperLanguages
import java.io.File

/**
 * Per-chat options reached from the chat menu: how audio input is handled (direct vs Whisper,
 * model, language), a pointer to the per-chat tool permissions and the bound server. Every change
 * writes straight into [ChatSettingsState]; the screen's auto-save persists it (and flushes on dismiss).
 */
@Composable
internal fun ChatOptionsDialog(
    settings: ChatSettingsState,
    server: LlamaServerEntity?,
    serverSummary: String,
    /** True when the bound server/model can take audio itself (LiteRT audio models, llama-server audio cards). */
    directAudioCapable: Boolean,
    /** True when the server rejected direct audio earlier in this process; the chat now uses Whisper. */
    directAudioRejected: Boolean,
    whisperModelPaths: List<String>,
    onOpenSettings: () -> Unit,
    onChangeServer: () -> Unit,
    onDismiss: () -> Unit
) {
    val mode = NativeChatToolConfig.normalizeAudioInputMode(settings.audioInputMode)
    val videoMode = NativeChatToolConfig.normalizeVideoInputMode(settings.videoInputMode)
    // Direct mode keeps Whisper as the fallback, so its model/language pickers stay visible.
    val usesWhisper = mode != NativeChatToolConfig.AUDIO_INPUT_SERVER || !directAudioCapable ||
        server?.preferWhisperAudioTranscription == true
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("native_chat_options_dialog"),
        title = { Text(stringResource(R.string.native_chat_options_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle(R.string.native_chat_options_audio_title)
                AudioModeRow(
                    selected = mode == NativeChatToolConfig.AUDIO_INPUT_SERVER,
                    title = stringResource(R.string.native_chat_options_audio_server),
                    description = stringResource(R.string.native_chat_options_audio_server_desc),
                    onSelect = { settings.audioInputMode = NativeChatToolConfig.AUDIO_INPUT_SERVER }
                )
                AudioModeRow(
                    selected = mode == NativeChatToolConfig.AUDIO_INPUT_WHISPER,
                    title = stringResource(R.string.native_chat_options_audio_whisper),
                    description = stringResource(R.string.llama_use_whisper_for_audio_desc),
                    onSelect = { settings.audioInputMode = NativeChatToolConfig.AUDIO_INPUT_WHISPER }
                )
                AudioModeRow(
                    selected = mode == NativeChatToolConfig.AUDIO_INPUT_DIRECT,
                    title = stringResource(R.string.native_chat_options_audio_direct),
                    description = stringResource(
                        if (directAudioCapable) R.string.native_chat_options_audio_direct_desc
                        else R.string.native_chat_options_audio_direct_try_desc
                    ),
                    onSelect = { settings.audioInputMode = NativeChatToolConfig.AUDIO_INPUT_DIRECT }
                )
                if (directAudioRejected && mode == NativeChatToolConfig.AUDIO_INPUT_DIRECT) {
                    Text(
                        stringResource(R.string.native_chat_options_audio_rejected_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (usesWhisper) {
                    WhisperModelPicker(
                        selectedPath = settings.whisperModelPath,
                        serverPath = server?.whisperModelPath,
                        paths = whisperModelPaths,
                        onSelect = { settings.whisperModelPath = it }
                    )
                    WhisperLanguagePicker(
                        selectedCode = settings.whisperLanguage.ifBlank {
                            server?.whisperLanguage?.ifBlank { null } ?: LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE
                        },
                        onSelect = { settings.whisperLanguage = it }
                    )
                }

                HorizontalDivider()
                SectionTitle(R.string.native_chat_options_video_title)
                AudioModeRow(
                    selected = videoMode == NativeChatToolConfig.VIDEO_INPUT_SERVER,
                    title = stringResource(R.string.native_chat_options_video_server),
                    description = stringResource(R.string.native_chat_options_video_server_desc),
                    onSelect = { settings.videoInputMode = NativeChatToolConfig.VIDEO_INPUT_SERVER }
                )
                AudioModeRow(
                    selected = videoMode == NativeChatToolConfig.VIDEO_INPUT_NATIVE,
                    title = stringResource(R.string.native_chat_options_video_native),
                    description = stringResource(R.string.native_chat_options_video_native_desc),
                    onSelect = { settings.videoInputMode = NativeChatToolConfig.VIDEO_INPUT_NATIVE }
                )
                AudioModeRow(
                    selected = videoMode == NativeChatToolConfig.VIDEO_INPUT_TRANSCRIPT,
                    title = stringResource(R.string.native_chat_options_video_transcript),
                    description = stringResource(R.string.native_chat_options_video_transcript_desc),
                    onSelect = { settings.videoInputMode = NativeChatToolConfig.VIDEO_INPUT_TRANSCRIPT }
                )

                HorizontalDivider()
                SectionTitle(R.string.native_chat_options_tools_title)
                Text(
                    stringResource(R.string.native_chat_options_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.native_chat_options_open_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HorizontalDivider()
                SectionTitle(R.string.native_chat_options_server_title)
                Text(
                    serverSummary.ifBlank { stringResource(R.string.native_chat_options_server_none) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.native_chat_change_server),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_chat_options_done)) }
        }
    )
}

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AudioModeRow(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(end = 10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WhisperModelPicker(
    selectedPath: String,
    serverPath: String?,
    paths: List<String>,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val shown = (selectedPath.ifBlank { serverPath.orEmpty() }).takeIf { it.isNotBlank() }
        ?.let { File(it).name }
        ?: stringResource(R.string.llama_whisper_model_auto)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.llama_whisper_model_label)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { open = !open }, enabled = paths.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.llama_whisper_model_label))
                }
            }
        )
        DropdownMenu(expanded = open && paths.isNotEmpty(), onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.llama_whisper_model_auto)) },
                onClick = { onSelect(""); open = false }
            )
            paths.forEach { path ->
                DropdownMenuItem(
                    text = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(path); open = false }
                )
            }
        }
    }
    if (paths.isEmpty()) {
        Text(
            stringResource(R.string.llama_whisper_no_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WhisperLanguagePicker(selectedCode: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = WhisperLanguages.languages.firstOrNull { it.first == selectedCode }?.second
                ?: stringResource(R.string.whisper_auto_detect),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.llama_whisper_language_label)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { open = !open }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.llama_whisper_language_label))
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WhisperLanguages.languages.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(code); open = false }
                )
            }
        }
    }
}
