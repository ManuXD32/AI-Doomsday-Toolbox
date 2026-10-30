package com.example.llamadroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.titlegen.TitleGenerationLogic
import com.example.llamadroid.service.titlegen.TitleGenerationManager
import com.example.llamadroid.service.titlegen.TitleGenerationSettings
import com.example.llamadroid.service.titlegen.TitleGenerationStatus
import java.io.File
import java.util.Locale

/** General-settings card: opt-in chat title generation with a user-selected installed LLM. */
@Composable
fun TitleGenerationSettingsCard() {
    val context = LocalContext.current
    val settings = remember { TitleGenerationSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var modelPath by remember { mutableStateOf(settings.modelPath) }
    var showPicker by remember { mutableStateOf(false) }
    val models by remember { AppDatabase.getDatabase(context).modelDao().getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION)) }
        .collectAsState(initial = emptyList())
    val downloaded = models.filter { it.isDownloaded && File(it.path).isFile }.sortedBy { it.filename.lowercase() }
    val selectedName = downloaded.firstOrNull { it.path == modelPath }?.filename
        ?: modelPath.substringAfterLast('/').takeIf { it.isNotBlank() }

    val status by TitleGenerationManager.status.collectAsState()
    val shownStatus = if (status is TitleGenerationStatus.Idle || status is TitleGenerationStatus.Disabled ||
        status is TitleGenerationStatus.NoModel
    ) TitleGenerationLogic.baselineStatus(enabled, modelPath) else status

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.title_generation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.title_generation_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.title_generation_switch),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(
                    checked = enabled,
                    enabled = modelPath.isNotBlank(),
                    onCheckedChange = {
                        enabled = it
                        settings.enabled = it
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.title_generation_model_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        selectedName ?: stringResource(R.string.title_generation_model_none),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { showPicker = true }) {
                    Text(
                        stringResource(
                            if (modelPath.isBlank()) R.string.title_generation_choose_model
                            else R.string.title_generation_change_model
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (modelPath.isBlank()) {
                Text(
                    stringResource(R.string.title_generation_needs_model),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                titleGenerationStatusText(shownStatus),
                style = MaterialTheme.typography.bodySmall,
                color = if (shownStatus is TitleGenerationStatus.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { TitleGenerationManager.titleUntitledNow(context) },
                enabled = enabled && modelPath.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.title_generation_run_now),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.title_generation_picker_title)) },
            text = {
                if (downloaded.isEmpty()) {
                    Text(stringResource(R.string.title_generation_picker_empty))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(downloaded, key = { it.filename }) { model ->
                            Text(
                                model.filename,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (model.path == modelPath) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        modelPath = model.path
                                        settings.modelPath = model.path
                                        showPicker = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

/**
 * "Helper models" sheet opened from the chats home menu and from the new-chat helper warning: chat
 * title generation (also kept in General settings) and the global vision helper (only here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleGenerationSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.helper_models_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.helper_models_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TitleGenerationSettingsCard()
            VisionHelperSettingsCard()
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.title_generation_sheet_close))
            }
        }
    }
}

@Composable
private fun titleGenerationStatusText(status: TitleGenerationStatus): String = when (status) {
    TitleGenerationStatus.Idle -> stringResource(R.string.title_generation_status_idle)
    TitleGenerationStatus.Disabled -> stringResource(R.string.title_generation_status_disabled)
    TitleGenerationStatus.NoModel -> stringResource(R.string.title_generation_status_no_model)
    is TitleGenerationStatus.WaitingForRam -> stringResource(
        R.string.title_generation_status_waiting,
        formatGb(status.neededBytes),
        formatGb(status.availableBytes)
    )
    is TitleGenerationStatus.Generating -> status.chatTitle?.let {
        stringResource(R.string.title_generation_status_generating_chat, it)
    } ?: stringResource(R.string.title_generation_status_generating)
    is TitleGenerationStatus.Error -> stringResource(R.string.title_generation_status_error, status.message)
}

private fun formatGb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_073_741_824.0)
