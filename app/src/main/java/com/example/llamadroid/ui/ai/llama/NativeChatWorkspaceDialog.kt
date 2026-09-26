package com.example.llamadroid.ui.ai.llama

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class NativeWorkspaceEntry(val name: String, val isDirectory: Boolean)

@Composable
fun NativeChatWorkspaceDialog(context: Context, chatId: Long, onDismiss: () -> Unit) {
    var directory by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NativeWorkspaceEntry>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var createDirectory by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

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
                        onValueChange = { if (it.length <= NativeChatWorkspaceSupport.MAX_MUTATION_CHARS) content = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text(context.getString(R.string.native_chat_workspace_content)) },
                        supportingText = { Text("${content.length}/${NativeChatWorkspaceSupport.MAX_MUTATION_CHARS}") }
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        NativeChatWorkspaceSupport.write(context, chatId, selectedFile.orEmpty(), content, false)
                                    }
                                }.onSuccess { refresh += 1; error = null }
                                    .onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(context.getString(R.string.action_save)) }
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
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(entries, key = { "${it.isDirectory}:${it.name}" }) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val path = listOf(directory, entry.name).filter(String::isNotBlank).joinToString("/")
                                    if (entry.isDirectory) directory = path else {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    NativeChatWorkspaceSupport.read(context, chatId, path)
                                                }
                                            }.onSuccess {
                                                content = it.substringBefore("\n[truncated;")
                                                selectedFile = path
                                                error = null
                                            }.onFailure { error = context.getString(R.string.native_chat_workspace_io_error) }
                                        }
                                    }
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
