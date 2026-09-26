package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Native equivalents of the official settings document and reset controls. */
@Composable
internal fun HarnessSettingsDocumentAction(
    hasDocument: Boolean,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    if (!hasDocument) return
    OutlinedButton(
        onClick = { onAction(NativeHarnessUiAction.OpenSettingsDocument) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.harness_open_settings_document))
    }
}

@Composable
internal fun HarnessSettingsDocumentDialog(
    document: HarnessSettingsDocumentUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var draft by remember(document.text) { mutableStateOf(document.text) }
    var wasTruncated by remember(document.text) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            if (!document.isSaving) onAction(NativeHarnessUiAction.CloseSettingsDocument)
        },
        title = { Text(stringResource(R.string.harness_settings_document_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.harness_settings_document_description))
                if (!document.canEdit) {
                    Text(
                        stringResource(R.string.harness_settings_document_read_only),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        wasTruncated = it.length > HARNESS_MAX_SETTINGS_DOCUMENT_CHARS
                        draft = it.take(HARNESS_MAX_SETTINGS_DOCUMENT_CHARS)
                    },
                    enabled = document.canEdit && !document.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    minLines = 8,
                    maxLines = 16,
                    label = { Text(stringResource(R.string.harness_settings_document_title)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.harness_settings_document_character_count,
                                draft.length,
                                HARNESS_MAX_SETTINGS_DOCUMENT_CHARS
                            )
                        )
                    }
                )
                if (wasTruncated) {
                    Text(
                        stringResource(R.string.harness_notice_settings_document_too_large),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(NativeHarnessUiAction.SaveSettingsDocument(draft)) },
                enabled = document.canEdit && !document.isSaving
            ) {
                Text(stringResource(R.string.harness_settings_document_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(NativeHarnessUiAction.CloseSettingsDocument) },
                enabled = !document.isSaving
            ) {
                Text(stringResource(R.string.harness_settings_document_close))
            }
        }
    )
}

@Composable
internal fun HarnessSchemaFieldReset(
    field: HarnessSchemaField,
    enabled: Boolean,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    if (!enabled || !field.isOverridden) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            stringResource(R.string.harness_setting_overridden),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = { onAction(NativeHarnessUiAction.ResetSchemaField(field.key)) },
            enabled = field.enabled
        ) {
            Text(stringResource(R.string.harness_reset_setting))
        }
    }
}
