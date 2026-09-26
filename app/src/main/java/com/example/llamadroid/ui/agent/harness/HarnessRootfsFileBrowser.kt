package com.example.llamadroid.ui.agent.harness

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessRootfsFileEntry
import com.example.llamadroid.harness.HarnessRootfsFileStore
import kotlinx.coroutines.launch

/** Full-screen native explorer content for the managed Debian rootfs. */
@Composable
fun HarnessRootfsFileBrowser(
    onClose: () -> Unit,
    onOpenAdvancedSftp: (() -> Unit)? = null,
    store: HarnessRootfsFileStore? = null,
) {
    val context = LocalContext.current
    val rootfsStore = store ?: remember(context) { HarnessRootfsFileStore(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<HarnessRootfsFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorLoading by remember { mutableStateOf(false) }
    var namingAction by remember { mutableStateOf<RootfsNamingAction?>(null) }
    var namingText by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<HarnessRootfsFileEntry?>(null) }
    var downloadEntry by remember { mutableStateOf<HarnessRootfsFileEntry?>(null) }
    var transferAction by remember { mutableStateOf<RootfsTransferAction?>(null) }
    var transferText by remember { mutableStateOf("") }
    var archiveAction by remember { mutableStateOf<RootfsArchiveAction?>(null) }
    var archiveText by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            rootfsStore.listDirectory(path).onSuccess { entries = it }
                .onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
            loading = false
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = documentName(context, uri)
            val destination = joinRootfsPath(path, name)
            context.contentResolver.openInputStream(uri)?.let { input ->
                rootfsStore.upload(destination, input)
                    .onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                    .onSuccess { refresh() }
            } ?: run { error = "ROOTFS_UPLOAD_UNREADABLE" }
        }
    }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = downloadEntry
        downloadEntry = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = if (entry.isDirectory) {
                context.contentResolver.openOutputStream(uri)?.let { output ->
                    rootfsStore.exportArchive(entry.path, output)
                } ?: Result.failure(IllegalStateException("ROOTFS_DOWNLOAD_UNWRITABLE"))
            } else {
                rootfsStore.readBytes(entry.path).mapCatching { bytes ->
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("ROOTFS_DOWNLOAD_UNWRITABLE")
                }
            }
            result.onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
        }
    }

    LaunchedEffect(path) { refresh() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.harness_recovery_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.harness_rootfs_browser_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.harness_rootfs_browser_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = ::refresh) {
                Icon(Icons.Default.Refresh, stringResource(R.string.harness_rootfs_refresh))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = path != "/", onClick = { path = parentRootfsPath(path) }) {
                Text(stringResource(R.string.harness_rootfs_up))
            }
            Text(
                path,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { upload.launch("*/*") }) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.harness_rootfs_upload))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                namingText = ""
                namingAction = RootfsNamingAction.CREATE_FOLDER
            }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.harness_rootfs_new_folder))
            }
            OutlinedButton(onClick = {
                namingText = ""
                namingAction = RootfsNamingAction.CREATE_FILE
            }) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.harness_rootfs_new_file))
            }
        }
        onOpenAdvancedSftp?.let { openAdvancedSftp ->
            OutlinedButton(
                onClick = openAdvancedSftp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Storage, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.harness_rootfs_advanced_sftp))
            }
        }
        error?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    stringResource(R.string.harness_rootfs_operation_failed, message),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        if (!loading && entries.isEmpty() && error == null) {
            Text(stringResource(R.string.harness_rootfs_empty), modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries, key = { it.path }) { entry ->
                RootfsEntryRow(
                    entry = entry,
                    onOpen = {
                        if (entry.isDirectory) path = entry.path else scope.launch {
                            editorLoading = true
                            rootfsStore.readText(entry.path).onSuccess {
                                editorPath = entry.path
                                editorText = it
                            }.onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                            editorLoading = false
                        }
                    },
                    onDownload = {
                        downloadEntry = entry
                        download.launch(if (entry.isDirectory) "${entry.name}.tar.gz" else entry.name)
                    },
                    onRename = {
                        namingText = entry.name
                        namingAction = RootfsNamingAction.RENAME(entry)
                    },
                    onDelete = { deleteEntry = entry },
                    onCopy = {
                        transferText = path
                        transferAction = RootfsTransferAction.COPY(entry)
                    },
                    onMove = {
                        transferText = path
                        transferAction = RootfsTransferAction.MOVE(entry)
                    },
                    onCompress = {
                        archiveText = "${entry.name}.tar.gz"
                        archiveAction = RootfsArchiveAction.COMPRESS(entry)
                    },
                    onExtract = {
                        transferText = path
                        transferAction = RootfsTransferAction.EXTRACT(entry)
                    },
                )
            }
        }
    }

    editorPath?.let { openedPath ->
        AlertDialog(
            onDismissRequest = { editorPath = null },
            title = { Text(openedPath) },
            text = {
                if (editorLoading) CircularProgressIndicator()
                else OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        rootfsStore.writeText(openedPath, editorText).onFailure {
                            error = it.message ?: "ROOTFS_OPERATION_FAILED"
                        }.onSuccess { editorPath = null; refresh() }
                    }
                }) { Text(stringResource(R.string.harness_rootfs_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editorPath = null }) { Text(stringResource(R.string.harness_runtime_close)) }
            },
        )
    }

    namingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { namingAction = null },
            title = { Text(stringResource(action.titleRes)) },
            text = {
                OutlinedTextField(
                    value = namingText,
                    onValueChange = { namingText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.harness_rootfs_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = namingText.trim().isNotEmpty(), onClick = {
                    val name = namingText.trim()
                    scope.launch {
                        val result = when (action) {
                            RootfsNamingAction.CREATE_FOLDER -> rootfsStore.createFolder(joinRootfsPath(path, name))
                            RootfsNamingAction.CREATE_FILE -> rootfsStore.createFile(joinRootfsPath(path, name))
                            is RootfsNamingAction.RENAME -> rootfsStore.rename(action.entry.path, joinRootfsPath(path, name))
                        }
                        result.onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                            .onSuccess { namingAction = null; refresh() }
                    }
                }) { Text(stringResource(R.string.harness_rootfs_apply)) }
            },
            dismissButton = { TextButton(onClick = { namingAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.harness_rootfs_delete_title)) },
            text = { Text(stringResource(R.string.harness_rootfs_delete_message, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        rootfsStore.delete(entry.path).onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                            .onSuccess { deleteEntry = null; refresh() }
                    }
                }) { Text(stringResource(R.string.harness_rootfs_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    transferAction?.let { action ->
        val title = when (action) {
            is RootfsTransferAction.COPY -> R.string.harness_rootfs_copy_title
            is RootfsTransferAction.MOVE -> R.string.harness_rootfs_move_title
            is RootfsTransferAction.EXTRACT -> R.string.harness_rootfs_extract_title
        }
        val isExtract = action is RootfsTransferAction.EXTRACT
        AlertDialog(
            onDismissRequest = { transferAction = null },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (isExtract) R.string.harness_rootfs_extract_destination_hint
                            else R.string.harness_rootfs_transfer_destination_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = transferText,
                        onValueChange = { transferText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.harness_rootfs_destination)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = transferText.trim().isNotBlank(), onClick = {
                    val destination = transferText.trim()
                    scope.launch {
                        val result = when (action) {
                            is RootfsTransferAction.COPY -> rootfsStore.copy(
                                action.entry.path,
                                joinRootfsPath(destination, action.entry.name),
                            )
                            is RootfsTransferAction.MOVE -> rootfsStore.move(
                                action.entry.path,
                                joinRootfsPath(destination, action.entry.name),
                            )
                            is RootfsTransferAction.EXTRACT -> rootfsStore.extractArchive(
                                action.entry.path,
                                destination,
                            )
                        }
                        result.onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                            .onSuccess { transferAction = null; refresh() }
                    }
                }) { Text(stringResource(R.string.harness_rootfs_apply)) }
            },
            dismissButton = { TextButton(onClick = { transferAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    archiveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { archiveAction = null },
            title = { Text(stringResource(R.string.harness_rootfs_compress_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.harness_rootfs_compress_destination_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = archiveText,
                        onValueChange = { archiveText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.harness_rootfs_archive_name)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = archiveText.trim().isNotBlank(), onClick = {
                    val archiveName = archiveText.trim()
                    scope.launch {
                        val result = rootfsStore.compressToArchive(
                            action.entry.path,
                            joinRootfsPath(path, archiveName),
                        )
                        result.onFailure { error = it.message ?: "ROOTFS_OPERATION_FAILED" }
                            .onSuccess { archiveAction = null; refresh() }
                    }
                }) { Text(stringResource(R.string.harness_rootfs_apply)) }
            },
            dismissButton = { TextButton(onClick = { archiveAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private sealed interface RootfsNamingAction {
    val titleRes: Int

    data object CREATE_FOLDER : RootfsNamingAction {
        override val titleRes: Int = R.string.harness_rootfs_new_folder_title
    }

    data object CREATE_FILE : RootfsNamingAction {
        override val titleRes: Int = R.string.harness_rootfs_new_file_title
    }

    data class RENAME(val entry: HarnessRootfsFileEntry) : RootfsNamingAction {
        override val titleRes: Int = R.string.harness_rootfs_rename_title
    }
}

private sealed interface RootfsTransferAction {
    val entry: HarnessRootfsFileEntry

    data class COPY(override val entry: HarnessRootfsFileEntry) : RootfsTransferAction
    data class MOVE(override val entry: HarnessRootfsFileEntry) : RootfsTransferAction
    data class EXTRACT(override val entry: HarnessRootfsFileEntry) : RootfsTransferAction
}

private sealed interface RootfsArchiveAction {
    val entry: HarnessRootfsFileEntry

    data class COMPRESS(override val entry: HarnessRootfsFileEntry) : RootfsArchiveAction
}

@Composable
private fun RootfsEntryRow(
    entry: HarnessRootfsFileEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isDirectory) stringResource(R.string.harness_rootfs_directory)
                else stringResource(R.string.harness_rootfs_size, entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDownload) { Icon(Icons.Default.Download, stringResource(R.string.harness_rootfs_download)) }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.harness_rootfs_more_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_rootfs_rename)) },
                    onClick = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_rootfs_delete)) },
                    onClick = { menuExpanded = false; onDelete() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_rootfs_copy)) },
                    onClick = { menuExpanded = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_rootfs_move)) },
                    onClick = { menuExpanded = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_rootfs_compress)) },
                    onClick = { menuExpanded = false; onCompress() },
                )
                if (!entry.isDirectory && (entry.name.endsWith(".tar.gz", true) || entry.name.endsWith(".tgz", true))) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.harness_rootfs_extract)) },
                        onClick = { menuExpanded = false; onExtract() },
                    )
                }
            }
        }
    }
}

private fun joinRootfsPath(parent: String, name: String): String {
    val safeName = name.trim().replace('\\', '/')
    require(safeName.isNotBlank() && '/' !in safeName && safeName != "." && safeName != "..")
    return if (parent == "/") "/$safeName" else "${parent.trimEnd('/')}/$safeName"
}

private fun parentRootfsPath(path: String): String {
    val normalized = path.trimEnd('/').ifBlank { "/" }
    val parent = normalized.substringBeforeLast('/', "")
    return if (parent.isBlank()) "/" else parent
}

private fun documentName(context: Context, uri: android.net.Uri): String = context.contentResolver.query(
    uri,
    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
    null,
    null,
    null,
).use { cursor ->
    cursor?.takeIf { it.moveToFirst() }?.getString(0)
}.orEmpty().ifBlank { "upload" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
