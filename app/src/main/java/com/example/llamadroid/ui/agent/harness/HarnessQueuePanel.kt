package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

@Composable
internal fun HarnessQueueCard(
    queueItem: HarnessQueueItemUi,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    var editing by rememberSaveable(queueItem.id) { mutableStateOf(false) }
    var draft by rememberSaveable(queueItem.id) {
        mutableStateOf(queueItem.editText ?: queueItem.text)
    }
    AppSectionCard(
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ) {
        Text(
            stringResource(R.string.harness_queue_item_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_queue_edit_label)) },
                minLines = 2,
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onAction(NativeHarnessUiAction.EditQueueItem(queueItem.id, draft))
                        editing = false
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.harness_save))
                }
                OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_cancel))
                }
            }
        } else {
            Text(
                boundedHarnessMessage(queueItem.text),
                style = MaterialTheme.typography.bodyMedium,
            )
            queueItem.attachments.forEach { attachment ->
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = attachment.name ?: attachment.id,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata = listOfNotNull(
                        if (attachment.isImage) attachment.mediaType
                        else stringResource(R.string.harness_structured_file),
                        attachment.bytes?.let {
                            stringResource(R.string.harness_structured_bytes, it)
                        },
                        if (attachment.isImage && attachment.width != null && attachment.height != null) {
                            stringResource(
                                R.string.harness_structured_dimensions,
                                attachment.width,
                                attachment.height,
                            )
                        } else null,
                    ).joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(metadata, style = MaterialTheme.typography.labelSmall)
                    }
                    if (attachment.canOpen) {
                        OutlinedButton(
                            onClick = {
                                onAction(NativeHarnessUiAction.OpenAttachment(attachment.id))
                            },
                        ) {
                            Text(stringResource(R.string.harness_structured_open_attachment))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (queueItem.canEdit) {
                    OutlinedButton(
                        onClick = {
                            draft = queueItem.editText ?: queueItem.text
                            editing = true
                        },
                    ) {
                        Text(stringResource(R.string.harness_edit))
                    }
                }
                if (queueItem.canSteer) {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.SteerQueueItem(queueItem.id)) },
                    ) {
                        Text(stringResource(R.string.harness_steer))
                    }
                }
                if (queueItem.canRemove) {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.RemoveQueueItem(queueItem.id)) },
                    ) {
                        Text(stringResource(R.string.harness_remove))
                    }
                }
            }
        }
    }
}
