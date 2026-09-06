package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_DISCARDED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_STALE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.DownloadTaskArtifacts
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadTaskSection(
    modelTypes: List<ModelType>,
    modifier: Modifier = Modifier,
    includeTask: (DownloadTaskEntity) -> Boolean = { true },
    staleRoots: List<File>? = null
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val modelTypeNames = remember(modelTypes) { modelTypes.map { it.name } }
    val storedTasks by db.downloadTaskDao().observeByModelTypes(modelTypeNames).collectAsState(initial = emptyList())
    val installedModels by db.modelDao().getModelsByTypes(modelTypes).collectAsState(initial = emptyList())
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    var staleTasks by remember { mutableStateOf<List<DownloadTaskEntity>>(emptyList()) }
    var confirmTask by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var confirmClean by remember { mutableStateOf(false) }

    suspend fun refreshStaleTasks() {
        staleTasks = withContext(Dispatchers.IO) {
            DownloadTaskArtifacts.discoverStalePartFiles(
                context = context,
                modelTypes = modelTypes,
                knownTasks = storedTasks,
                installedModels = installedModels,
                rootsOverride = staleRoots
            ).filter(includeTask)
        }
    }

    LaunchedEffect(storedTasks, installedModels, modelTypes, staleRoots) {
        refreshStaleTasks()
    }

    val visibleStored = storedTasks
        .filter(includeTask)
        .filterNot { it.status in setOf(DOWNLOAD_TASK_STATUS_COMPLETED, DOWNLOAD_TASK_STATUS_CANCELLED, DOWNLOAD_TASK_STATUS_DISCARDED) }
        .filterNot { it.status == DOWNLOAD_TASK_STATUS_ACTIVE && progressMap.containsKey(it.progressKey) }
    val visibleTasks = (visibleStored + staleTasks).distinctBy { it.id }

    if (visibleTasks.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.download_recovery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (staleTasks.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmClean = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.download_clean_stale))
                }
            }
        }

        visibleTasks.forEach { task ->
            DownloadTaskCard(
                task = task,
                progress = progressMap[task.progressKey],
                onResume = {
                    if (task.url.isNotBlank()) {
                        PendingDownloadHolder.addPendingFrom(task)
                        DownloadProgressHolder.updateProgress(
                            task.progressKey,
                            task.filename,
                            if (task.hasUsablePartial()) DownloadProgressHolder.INDETERMINATE else 0f
                        )
                        DownloadService.resumeDownload(context, task.id)
                    }
                },
                onDiscard = { DownloadService.discardDownload(context, task.id) },
                onRemovePartial = { confirmTask = task }
            )
        }
    }

    confirmTask?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmTask = null },
            title = { Text(stringResource(R.string.download_remove_partial_title)) },
            text = { Text(stringResource(R.string.download_remove_partial_desc, task.filename)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                DownloadTaskArtifacts.deletePartialArtifact(task)
                                db.downloadTaskDao().deleteById(task.id)
                            }
                            confirmTask = null
                            refreshStaleTasks()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTask = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text(stringResource(R.string.download_clean_stale_title)) },
            text = { Text(stringResource(R.string.download_clean_stale_desc, staleTasks.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                staleTasks.forEach { DownloadTaskArtifacts.deletePartialArtifact(it) }
                            }
                            confirmClean = false
                            refreshStaleTasks()
                        }
                    }
                ) {
                    Text(stringResource(R.string.download_clean_stale))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskEntity,
    progress: Float?,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onRemovePartial: () -> Unit
) {
    val statusLabel = when (task.status) {
        DOWNLOAD_TASK_STATUS_ACTIVE ->
            if (progress == null) {
                stringResource(R.string.download_status_resumable)
            } else {
                stringResource(R.string.download_status_active)
            }
        DOWNLOAD_TASK_STATUS_RESUMABLE -> stringResource(R.string.download_status_resumable)
        DOWNLOAD_TASK_STATUS_FAILED -> stringResource(R.string.download_status_failed)
        DOWNLOAD_TASK_STATUS_STALE -> stringResource(R.string.download_status_stale)
        else -> task.status
    }
    val isLiveActive = task.status == DOWNLOAD_TASK_STATUS_ACTIVE && progress != null
    val isInterruptedActive = task.status == DOWNLOAD_TASK_STATUS_ACTIVE && progress == null
    val canResume = task.url.isNotBlank() &&
        (task.status in setOf(DOWNLOAD_TASK_STATUS_RESUMABLE, DOWNLOAD_TASK_STATUS_FAILED) || isInterruptedActive)
    val canRemovePartial = !isLiveActive && task.hasUsablePartial()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.filename,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val bytes = FormatUtils.Display.formatBytes(LocalContext.current, task.bytesDownloaded.coerceAtLeast(task.partFile().length()))
                    Text(
                        stringResource(R.string.download_partial_size, bytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    task.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AssistChip(onClick = {}, label = { Text(statusLabel, maxLines = 1) })
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (progress == DownloadProgressHolder.INDETERMINATE) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (progress != null && progress in 0f..0.999f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canResume) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_resume))
                    }
                }
                if (canRemovePartial) {
                    IconButton(onClick = onRemovePartial) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.download_remove_partial)
                        )
                    }
                }
                if (!isLiveActive && task.status != DOWNLOAD_TASK_STATUS_STALE) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_discard))
                    }
                }
                if (isLiveActive) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                if (task.status == DOWNLOAD_TASK_STATUS_STALE) {
                    IconButton(onClick = onRemovePartial) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.download_remove_partial)
                        )
                    }
                }
            }
        }
    }
}

private fun DownloadTaskEntity.hasUsablePartial(): Boolean = partFile().isFile && partFile().length() > 0L
