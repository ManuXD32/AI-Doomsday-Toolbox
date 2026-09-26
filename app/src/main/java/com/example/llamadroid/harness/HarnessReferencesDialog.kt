package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.client.HarnessClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Captures the destination session for the whole picker, including preview and insertion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HarnessReferencesDialog(
    client: HarnessClient?,
    sessionId: String,
    onPick: (String) -> Unit,
    onPreview: suspend (String) -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember(sessionId) { mutableStateOf("") }
    var result by remember(sessionId) { mutableStateOf(HarnessReferenceResults(emptyList(), false)) }
    var loading by remember { mutableStateOf(false) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client, sessionId, query, retry) {
        loading = true
        try {
            delay(200)
            result = if (client == null) HarnessReferenceResults(emptyList(), true)
                else loadHarnessReferences(client, sessionId, query)
            if (result.incomplete) onFailure()
        } finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_references_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(2048) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_references_search)) }, maxLines = 3)
                FlowRow {
                    TextButton(onClick = { query = "" }) { Text(stringResource(R.string.harness_references_root)) }
                    if (query.contains('/')) TextButton(onClick = {
                        query = query.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    }) { Text(stringResource(R.string.harness_references_parent)) }
                }
                if (loading || previewLoading) CircularProgressIndicator()
                if (result.incomplete || previewError) {
                    Text(stringResource(R.string.harness_references_failed))
                    TextButton(onClick = { previewError = false; retry++ }) { Text(stringResource(R.string.harness_runtime_retry)) }
                }
                if (!loading && result.entries.isEmpty()) Text(stringResource(R.string.harness_references_empty))
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(result.entries, key = { it.key }) { entry ->
                        Column {
                            Text(entry.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (entry.path == null) R.string.harness_references_session else if (entry.directory)
                                R.string.harness_references_folder else R.string.harness_references_file), style = MaterialTheme.typography.labelSmall)
                            entry.workspace?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
                            FlowRow {
                                TextButton(onClick = { onPick(entry.mention) }) { Text(stringResource(R.string.harness_references_insert)) }
                                if (entry.directory) TextButton(onClick = { query = entry.path.orEmpty() + "/" }) {
                                    Text(stringResource(R.string.harness_references_browse))
                                } else if (entry.path != null) TextButton(enabled = !previewLoading, onClick = { scope.launch {
                                    previewLoading = true; previewError = false
                                    try { onPreview(entry.path) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { previewError = true; onFailure() }
                                    finally { previewLoading = false }
                                } }) { Text(stringResource(R.string.harness_references_preview)) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.harness_runtime_close)) } }
    )
}
