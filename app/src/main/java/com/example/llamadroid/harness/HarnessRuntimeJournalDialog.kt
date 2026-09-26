package com.example.llamadroid.harness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A stable snapshot keeps incoming events from shifting pages while the user reads. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HarnessRuntimeJournalDialog(diagnostics: HarnessDiagnostics, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(diagnostics.runtimeEntries.value) }
    var filter by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf(emptyList<HarnessRuntimeDiagnostic>()) }
    val filtered = remember(snapshot, filter) { snapshot.asReversed().filter { row ->
        when (filter) {
            1 -> row.errorCode != null || row.outcome in setOf("failure", "interrupted")
            2 -> row.transport == null
            3 -> row.transport != null
            else -> true
        }
    } }
    val pages = ((filtered.size + 99) / 100).coerceAtLeast(1)
    val shownPage = page.coerceAtMost(pages - 1)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            failed = runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { writer ->
                    writer.appendLine("appVersion=${BuildConfig.VERSION_NAME} androidApi=${android.os.Build.VERSION.SDK_INT} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
                    exportSnapshot.forEach { writer.appendLine(runtimeDiagnosticsText(listOf(it))) }
                }
            } }.isFailure
            busy = false
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.harness_runtime_diagnostics_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.harness_journal_retention), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.harness_runtime_close)) }
                        TextButton(onClick = { snapshot = diagnostics.runtimeEntries.value; page = 0 }) { Text(stringResource(R.string.harness_runtime_refresh_diagnostics)) }
                        TextButton(onClick = { exportSnapshot = diagnostics.runtimeEntries.value; export.launch("harness-diagnostics.txt") }, enabled = !busy) { Text(stringResource(R.string.harness_journal_export)) }
                        TextButton(onClick = { confirmClear = true }, enabled = !busy) { Text(stringResource(R.string.harness_journal_clear)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.harness_journal_all, R.string.harness_journal_errors, R.string.harness_journal_lifecycle, R.string.harness_journal_connections).forEachIndexed { index, label ->
                            FilterChip(selected = filter == index, onClick = { filter = index; page = 0 }, label = { Text(stringResource(label)) })
                        }
                    }
                    Text(stringResource(R.string.harness_journal_page, shownPage + 1, pages, filtered.size))
                    if (failed) Text(stringResource(R.string.harness_journal_failed), color = MaterialTheme.colorScheme.error)
                }
                items(filtered.drop(shownPage * 100).take(100), key = { it.id }) { row ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        SelectionContainer { Text(runtimeDiagnosticsText(listOf(row)), Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
                item {
                    FlowRow {
                        TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text(stringResource(R.string.harness_history_previous_page)) }
                        TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage + 1 < pages) { Text(stringResource(R.string.harness_history_next_page)) }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.harness_journal_clear)) },
        text = { Text(stringResource(R.string.harness_journal_clear_confirm), Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                busy = true
                failed = runCatching { withContext(Dispatchers.IO) { diagnostics.clearRuntimeJournal() } }.isFailure
                snapshot = diagnostics.runtimeEntries.value
                page = 0
                busy = false
            }
        }) { Text(stringResource(R.string.harness_journal_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.harness_runtime_close)) } })
}
