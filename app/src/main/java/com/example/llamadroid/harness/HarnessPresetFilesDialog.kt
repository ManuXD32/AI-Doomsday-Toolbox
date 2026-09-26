package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.service.FileInfo
import com.example.llamadroid.ui.agent.FileViewerDialog
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PresetEditKind { NEW_FILE, NEW_DIRECTORY, RENAME }
private data class PresetFileEdit(val kind: PresetEditKind, val path: String, val initialName: String = "")

/** Configuration files reuse the workspace editor without creating a spurious project/session. */
@Composable
internal fun HarnessPresetFilesDialog(files: HarnessPresetFiles, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var directory by remember(files) { mutableStateOf("") }
    var entries by remember(files) { mutableStateOf(emptyList<HarnessPresetEntry>()) }
    var document by remember(files) { mutableStateOf<HarnessPresetDocument?>(null) }
    var draft by remember(files) { mutableStateOf("") }
    var editing by remember(files) { mutableStateOf(false) }
    var pending by remember(files) { mutableStateOf<PresetFileEdit?>(null) }
    var deletion by remember(files) { mutableStateOf<HarnessPresetEntry?>(null) }
    var busy by remember(files) { mutableStateOf(false) }
    var error by remember(files) { mutableStateOf<String?>(null) }
    var revision by remember(files) { mutableStateOf(0) }
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(files, directory, revision) {
        busy = true
        try { entries = withContext(Dispatchers.IO) { files.list(directory) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message; entries = emptyList() }
        finally { busy = false }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppScreenScaffold(title = stringResource(R.string.harness_preset_files_title, files.presetId), onBack = { if (!busy) onDismiss() }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(stringResource(R.string.harness_preset_files_description), style = MaterialTheme.typography.bodySmall)
                    Text(directory.ifEmpty { files.presetId }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ResponsiveActionGroup(actions = listOf(
                        ResponsiveAction(stringResource(R.string.action_close), { if (!busy) onDismiss() }, enabled = !busy, style = ResponsiveActionStyle.Text),
                        ResponsiveAction(stringResource(R.string.harness_preset_files_parent), { directory = directory.substringBeforeLast('/', "") }, enabled = directory.isNotEmpty() && !busy, style = ResponsiveActionStyle.Secondary),
                        ResponsiveAction(stringResource(R.string.harness_preset_files_refresh), { revision++ }, enabled = !busy, style = ResponsiveActionStyle.Secondary),
                        ResponsiveAction(stringResource(R.string.harness_preset_files_new), { pending = PresetFileEdit(PresetEditKind.NEW_FILE, directory) }, enabled = !busy, style = ResponsiveActionStyle.Secondary),
                        ResponsiveAction(stringResource(R.string.harness_preset_files_new_directory), { pending = PresetFileEdit(PresetEditKind.NEW_DIRECTORY, directory) }, enabled = !busy, style = ResponsiveActionStyle.Secondary)
                    ))
                    if (busy) CircularProgressIndicator()
                }
                items(entries, key = { it.path }) { entry ->
                    AppSectionCard {
                        TextButton(onClick = {
                            if (entry.directory) directory = entry.path
                            else operation {
                                val loaded = withContext(Dispatchers.IO) { files.read(entry.path) }
                                document = loaded; draft = loaded.text; editing = false
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.Description, contentDescription = null)
                            Text(entry.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        ResponsiveActionGroup(actions = listOf(
                            ResponsiveAction(stringResource(R.string.harness_preset_files_rename), { pending = PresetFileEdit(PresetEditKind.RENAME, entry.path, entry.name) }, enabled = !busy, style = ResponsiveActionStyle.Text),
                            ResponsiveAction(stringResource(R.string.action_delete), { deletion = entry }, enabled = !busy, style = ResponsiveActionStyle.Text)
                        ))
                    }
                }
            }
        }
    }
    document?.let { captured ->
        FileViewerDialog(
            file = FileInfo(captured.path.substringAfterLast('/'), captured.path, false, captured.text.length.toLong(), ""),
            content = captured.text, isEditMode = editing, editedContent = draft,
            onEditedContentChange = {
                if (it.length <= HarnessPresetFiles.MAX_TEXT_BYTES) draft = it else error = "PRESET_FILE_TOO_LARGE"
            },
            onToggleEdit = { editing = !editing },
            onSave = {
                val submitted = draft
                operation {
                    val saved = withContext(Dispatchers.IO) { files.write(captured, submitted) }
                    document = saved
                    if (draft == submitted) editing = false
                    revision++
                }
            },
            onDismiss = { if (!busy) document = null }
        )
    }
    pending?.let { edit ->
        var name by remember(edit) { mutableStateOf(edit.initialName) }
        AlertDialog(onDismissRequest = { if (!busy) pending = null },
            title = { Text(stringResource(R.string.harness_preset_files_name)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { operation {
                withContext(Dispatchers.IO) {
                    if (edit.kind == PresetEditKind.RENAME) files.rename(edit.path, name)
                    else files.create(edit.path, name, edit.kind == PresetEditKind.NEW_DIRECTORY)
                }
                pending = null; revision++
            } }, enabled = !busy && name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { if (!busy) pending = null }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
    }
    deletion?.let { entry ->
        AlertDialog(onDismissRequest = { if (!busy) deletion = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.harness_preset_files_delete, entry.name), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { operation {
                withContext(Dispatchers.IO) { files.delete(entry.path) }
                deletion = null; revision++
            } }, enabled = !busy) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { if (!busy) deletion = null }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null },
        text = { Text(stringResource(when (error) {
            "PRESET_FILE_CONFLICT" -> R.string.harness_preset_files_conflict
            "PRESET_FILE_TOO_LARGE", "PRESET_FILE_NOT_TEXT" -> R.string.harness_preset_files_text_limit
            else -> R.string.harness_preset_files_failure
        }), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) } })
}
