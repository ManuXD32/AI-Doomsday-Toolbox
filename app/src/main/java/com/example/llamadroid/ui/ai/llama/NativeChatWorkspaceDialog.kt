package com.example.llamadroid.ui.ai.llama

import android.content.Context
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.NativeChatWorkspaceSupport
import com.example.llamadroid.ui.ai.llama.workspace.WorkspaceExport
import com.example.llamadroid.ui.ai.llama.workspace.WorkspacePaths
import com.example.llamadroid.worlds.WorldPageBuilder
import com.example.llamadroid.worlds.ui.WorldSourceViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class NativeWorkspaceEntry(val name: String, val isDirectory: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NativeChatWorkspaceDialog(context: Context, chatId: Long, onDismiss: () -> Unit, chatTitle: String? = null) {
    var directory by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NativeWorkspaceEntry>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var createDirectory by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showWorld by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingTreeExport by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun export(paths: List<String>, tree: Uri?) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val items = WorkspacePaths.expandSelection(NativeChatWorkspaceSupport.root(context, chatId), paths)
                    when {
                        tree != null -> WorkspaceExport.saveToTree(context, tree, items)
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                            WorkspaceExport.saveToDownloads(context, items, chatTitle, chatId)
                        else -> 0 // API 26-28 always pass a picked folder (needsTreePicker)
                    }
                }
            }.onSuccess { saved ->
                val where = if (tree != null) context.getString(R.string.native_chat_workspace_chosen_folder)
                else WorkspacePaths.downloadFolder(chatTitle, chatId).removePrefix("Download/").let { "Downloads/$it" }
                toast(context.resources.getQuantityString(R.plurals.native_chat_workspace_saved, saved, saved, where))
                selection = emptySet()
            }.onFailure { toast(context.getString(R.string.native_chat_workspace_io_error)) }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) export(pendingTreeExport, tree)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(context.getString(R.string.action_delete)) },
            text = {
                Text(context.resources.getQuantityString(R.plurals.native_chat_workspace_delete_confirm, selection.size, selection.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    val paths = selection.toList()
                    confirmDelete = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { WorkspaceExport.delete(NativeChatWorkspaceSupport.root(context, chatId), paths) }
                        }.onSuccess { selection = emptySet(); refresh += 1; error = null }
                            .onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                    }
                }) { Text(context.getString(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(context.getString(R.string.action_cancel)) } }
        )
    }

    if (showWorld) {
        WorldSourceViewerDialog(
            source = content,
            savedId = null,
            chatId = chatId,
            messageId = null,
            onSaved = {},
            onDismiss = { showWorld = false }
        )
    }

    LaunchedEffect(directory) { selection = emptySet() }

    LaunchedEffect(directory, refresh) {
        runCatching {
            withContext(Dispatchers.IO) {
                val folder = NativeChatWorkspaceSupport.resolve(context, chatId, directory)
                folder.listFiles().orEmpty()
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    .take(200)
                    .map { NativeWorkspaceEntry(it.name, it.isDirectory) }
            }
        }.onSuccess { entries = it; error = null }
            .onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.native_chat_workspace_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedFile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedFile = null; content = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.action_back))
                        }
                        Text(selectedFile.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { if (it.length <= NativeChatWorkspaceSupport.MAX_USER_WRITE_BYTES) content = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text(context.getString(R.string.native_chat_workspace_content)) },
                        supportingText = { Text("${content.length}/${NativeChatWorkspaceSupport.MAX_USER_WRITE_BYTES}") }
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        NativeChatWorkspaceSupport.writeUser(context, chatId, selectedFile.orEmpty(), content)
                                    }
                                }.onSuccess { refresh += 1; error = null }
                                    .onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(context.getString(R.string.action_save)) }
                    if (selectedFile.orEmpty().endsWith(".html", ignoreCase = true)) {
                        val detectedWorld = remember(content) { WorldPageBuilder.detect(content) }
                        OutlinedButton(
                            onClick = { showWorld = true },
                            enabled = detectedWorld != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (detectedWorld?.isThree == false) Icons.Default.Language else Icons.Default.ViewInAr,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.worlds_ui_run_html), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { directory = directory.substringBeforeLast('/', "") },
                            enabled = directory.isNotBlank()
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.action_back)) }
                        Text(if (directory.isBlank()) "/" else "/$directory", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { createDirectory = true; showCreate = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = context.getString(R.string.native_chat_workspace_new_folder))
                        }
                        IconButton(onClick = { createDirectory = false; showCreate = true }) {
                            Icon(Icons.Default.NoteAdd, contentDescription = context.getString(R.string.native_chat_workspace_new_file))
                        }
                    }
                    if (showCreate) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it.take(120) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(context.getString(if (createDirectory) R.string.native_chat_workspace_new_folder else R.string.native_chat_workspace_new_file)) }
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showCreate = false; newName = "" }) { Text(context.getString(R.string.action_cancel)) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val path = listOf(directory, newName.trim()).filter(String::isNotBlank).joinToString("/")
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                val target = NativeChatWorkspaceSupport.resolve(context, chatId, path)
                                                if (createDirectory) {
                                                    require(target.isDirectory || target.mkdirs()) { "Could not create folder." }
                                                } else {
                                                    val parent = requireNotNull(target.parentFile)
                                                    require(parent.isDirectory || parent.mkdirs()) { "Could not create folder." }
                                                    require(target.isFile || target.createNewFile()) { "Could not create file." }
                                                }
                                            }
                                        }.onSuccess { showCreate = false; newName = ""; refresh += 1; error = null }
                                            .onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                                    }
                                },
                                enabled = newName.isNotBlank()
                            ) { Text(context.getString(R.string.action_create)) }
                        }
                    }
                    if (selection.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                context.resources.getQuantityString(R.plurals.native_chat_workspace_selected, selection.size, selection.size),
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = {
                                selection = entries.map { WorkspacePaths.join(directory, it.name) }.toSet()
                            }) { Text(context.getString(R.string.native_chat_workspace_select_all), maxLines = 1) }
                            IconButton(onClick = {
                                val paths = selection.toList()
                                if (WorkspaceExport.needsTreePicker) { pendingTreeExport = paths; treePicker.launch(null) }
                                else export(paths, null)
                            }) { Icon(Icons.Default.Download, contentDescription = context.getString(R.string.action_download)) }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Default.Delete, contentDescription = context.getString(R.string.action_delete))
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(entries, key = { "${it.isDirectory}:${it.name}" }) { entry ->
                            val entryPath = WorkspacePaths.join(directory, entry.name)
                            val open: () -> Unit = {
                                val path = entryPath
                                if (entry.isDirectory) directory = path else {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                // Raw text: `read` returns a numbered view meant for the model.
                                                val file = NativeChatWorkspaceSupport.resolve(context, chatId, path)
                                                require(file.isFile && file.length() <= 1_048_576L) { "Unreadable file." }
                                                file.readText(Charsets.UTF_8)
                                            }
                                        }.onSuccess {
                                            content = it
                                            selectedFile = path
                                            error = null
                                        }.onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                                    }
                                }
                            }
                            val toggle: () -> Unit = {
                                selection = if (entryPath in selection) selection - entryPath else selection + entryPath
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { if (selection.isNotEmpty()) toggle() else open() },
                                    onLongClick = toggle
                                ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = entryPath in selection, onCheckedChange = { toggle() })
                                Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (!entry.isDirectory) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { NativeChatWorkspaceSupport.resolve(context, chatId, entryPath) }
                                            }.onSuccess { file ->
                                                try {
                                                    context.startActivity(
                                                        android.content.Intent.createChooser(WorkspaceExport.openIntent(context, file), null)
                                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                } catch (_: ActivityNotFoundException) {
                                                    toast(context.getString(R.string.native_chat_workspace_no_app))
                                                }
                                            }.onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                                        }
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = context.getString(R.string.native_chat_workspace_open_with))
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) } }
    )
}
