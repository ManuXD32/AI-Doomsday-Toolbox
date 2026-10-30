package com.example.llamadroid.ui.filestation

import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.filestation.VolumeConfig

/** Add/edit dialog for one folder: display name and read-only flag. Scrolls on short screens. */
@Composable
fun FileStationVolumeDialog(
    volume: VolumeConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (VolumeConfig) -> Unit,
) {
    var label by rememberSaveable(volume.id) { mutableStateOf(volume.label) }
    var readOnly by rememberSaveable(volume.id) { mutableStateOf(volume.readOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (isNew) R.string.fs_volume_dialog_add else R.string.fs_volume_dialog_edit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    pathTail(volume.treeUri),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(60) },
                    label = { Text(stringResource(R.string.fs_volume_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.fs_read_only_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.fs_read_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(volume.copy(label = label.trim().ifBlank { volume.label }, readOnly = readOnly)) }
            ) { Text(stringResource(R.string.fs_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fs_cancel)) } }
    )
}

/** Human-readable tail of a SAF tree uri (for example `Download/Docs`). */
internal fun pathTail(treeUri: String): String =
    treeUri.toUri().lastPathSegment?.substringAfter(':')?.ifBlank { null } ?: treeUri
