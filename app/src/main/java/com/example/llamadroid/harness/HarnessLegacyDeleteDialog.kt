package com.example.llamadroid.harness

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import kotlinx.coroutines.launch

@Composable
internal fun HarnessLegacyDeleteDialog(
    manager: HarnessLegacyDeletion,
    id: Long,
    pending: Boolean,
    onDeleted: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember(id) { mutableStateOf<HarnessLegacyDeletePreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        preview = runCatching { manager.preview(id) }.getOrNull()
        loading = false
        failed = preview == null && !pending
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.harness_legacy_delete)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                preview?.let { row ->
                    Text(row.conversation.title, style = MaterialTheme.typography.titleSmall)
                    Text(row.conversation.projectFolder, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.harness_legacy_delete_description))
                    if (!pending) Row {
                        Checkbox(checked = files, onCheckedChange = { files = it }, enabled = !busy && row.otherThreads == 0)
                        Text(stringResource(R.string.harness_legacy_delete_files), Modifier.weight(1f).padding(top = 12.dp))
                    }
                    if (row.otherThreads > 0) Text(stringResource(R.string.harness_legacy_files_shared, row.otherThreads))
                }
                if (pending) Text(stringResource(R.string.harness_legacy_retry_description))
                if (failed) Text(stringResource(R.string.harness_legacy_delete_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = !loading && !busy && (preview != null || pending), onClick = {
            scope.launch {
                busy = true
                val result = runCatching { if (pending) manager.retry(id) else manager.delete(id, files) }
                busy = false
                failed = result.isFailure
                if (result.isSuccess) onDeleted()
            }
        }) { Text(stringResource(if (pending) R.string.harness_runtime_retry else R.string.harness_legacy_delete)) } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.harness_runtime_close)) } },
    )
}
