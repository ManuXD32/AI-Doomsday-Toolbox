package com.example.llamadroid.ui.ai

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.GenerationQueueListItem
import com.example.llamadroid.data.db.GenerationQueueRunSummary
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.GenerationQueueNotifications
import com.example.llamadroid.service.GenerationQueueProgress
import com.example.llamadroid.service.GenerationQueueRepository
import com.example.llamadroid.service.GenerationQueueRetryException
import com.example.llamadroid.service.GenerationQueueRetryIssue
import com.example.llamadroid.service.GenerationQueueScheduler
import com.example.llamadroid.service.GenerationQueueService
import com.example.llamadroid.service.GenerationQueueSnapshot
import com.example.llamadroid.service.SdGeneratedImageMetadata
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun GenerationQueueScreen(navController: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { GenerationQueueRepository(context) }
    val scope = rememberCoroutineScope()
    val queue by repository.queue.collectAsState(initial = emptyList())
    val control by repository.control.collectAsState(initial = null)
    val pendingCount by repository.pendingCountFlow.collectAsState(initial = 0)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val runId = control?.runId
    val summaryFlow = remember(repository, runId) { repository.runSummaryFlow(runId) }
    val summary by summaryFlow.collectAsState(initial = GenerationQueueRunSummary.EMPTY)
    val progress = GenerationQueueProgress(summary.total, summary.finished, summary.succeeded,
        summary.failed, summary.waiting)
    fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun actionFailed() {
        Toast.makeText(context, R.string.generation_queue_action_failed, Toast.LENGTH_LONG).show()
    }
    fun selectSchedule() {
        if (!GenerationQueueScheduler.hasExactAccess(context)) {
            Toast.makeText(context, R.string.generation_queue_alarm_access, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()))
            }.onFailure { actionFailed() }
            return
        }
        requestNotificationIfNeeded()
        val now = Calendar.getInstance()
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                scope.launch {
                    runCatching {
                        repository.setSchedule(selected)
                        try {
                            GenerationQueueScheduler.schedule(context, selected)
                            GenerationQueueNotifications.showScheduled(context, repository.pendingCount(), selected)
                        } catch (error: Exception) {
                            repository.cancelSchedule()
                            throw error
                        }
                    }.onFailure { actionFailed() }
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    AppScreenScaffold(
        title = stringResource(R.string.generation_queue_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = { navController.navigate(Screen.GenerationQueueHistory.route) }) {
                Icon(Icons.Default.History, contentDescription = stringResource(R.string.generation_queue_history_title))
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.generation_queue_run_count,
                                pendingCount), style = MaterialTheme.typography.titleMedium)
                            if (runId != null) {
                                Text(stringResource(R.string.generation_queue_notification_detail,
                                    progress.finished, progress.total, progress.succeeded, progress.failed,
                                    stringResource(if (control?.state == "PAUSED") R.string.generation_queue_paused
                                        else R.string.generation_queue_notification_title)),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            control?.scheduledAtMillis?.let { at ->
                                Text(stringResource(R.string.generation_queue_scheduled_at, formatQueueTime(at)),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            if (control?.state == "MISSED") {
                                Text(stringResource(R.string.generation_queue_missed_message),
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (control?.state == "PAUSED" && summary.hasMediaTimeout > 0) {
                                Text(stringResource(R.string.generation_queue_media_limit_message),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (queue.isEmpty()) {
                    item { Text(stringResource(R.string.generation_queue_empty),
                        style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(queue, key = { it.id }) { item ->
                        GenerationQueueItemCard(item,
                            queue.filter { it.status == "PENDING" }.indexOf(item),
                            pendingCount,
                            onMove = { delta -> scope.launch {
                                runCatching { repository.movePending(item.id, delta) }.onFailure { actionFailed() }
                            } },
                            onRemove = { scope.launch {
                                runCatching { repository.removePending(item.id) }.onFailure { actionFailed() }
                            } },
                            onStop = {
                                runCatching { context.startService(GenerationQueueService.stopItemIntent(context, item.id)) }
                                    .onFailure { actionFailed() }
                            })
                    }
                }
            }
            AppTaskActionFooter {
                val active = control?.state == "RUNNING" || control?.state == "PAUSING"
                if (active) {
                    OutlinedButton(onClick = {
                        runCatching { context.startService(GenerationQueueService.pauseIntent(context)) }
                            .onFailure { actionFailed() }
                    }, enabled = control?.state == "RUNNING", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.generation_queue_pause))
                    }
                } else {
                    Button(onClick = {
                        requestNotificationIfNeeded()
                        runCatching { GenerationQueueService.startNow(context) }.onFailure { actionFailed() }
                    }, enabled = queue.any { it.status == "PENDING" },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.generation_queue_run))
                    }
                    if (control?.state == "SCHEDULED") {
                        OutlinedButton(onClick = { scope.launch {
                            runCatching {
                                repository.cancelSchedule()
                                GenerationQueueScheduler.cancel(context)
                            }.onFailure { actionFailed() }
                        } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.generation_queue_cancel_schedule))
                        }
                    } else {
                        OutlinedButton(onClick = ::selectSchedule,
                            enabled = queue.any { it.status == "PENDING" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.generation_queue_schedule))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerationQueueItemCard(
    item: GenerationQueueListItem,
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(queueKindLabel(item.kind), style = MaterialTheme.typography.titleMedium)
            Text(item.promptPreview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (item.status == "RUNNING") R.string.generation_queue_active
                else R.string.generation_queue_pending), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.status == "PENDING") {
                    IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.generation_queue_move_up))
                    }
                    IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.generation_queue_move_down))
                    }
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.generation_queue_remove_item))
                    }
                } else {
                    TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.generation_queue_stop_item))
                    }
                }
            }
        }
    }
}

@Composable
fun GenerationQueueHistoryScreen(navController: NavController) {
    val context = LocalContext.current
    val historyViewModel: GenerationQueueHistoryViewModel = viewModel()
    val repository = historyViewModel.repository
    val scope = rememberCoroutineScope()
    val history = historyViewModel.history.collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<GenerationQueueListItem?>(null) }
    var discardCandidate by remember { mutableStateOf<GenerationQueueListItem?>(null) }
    var busyItemId by remember { mutableStateOf<String?>(null) }
    var retryFilesRevision by remember { mutableIntStateOf(0) }
    AppScreenScaffold(title = stringResource(R.string.generation_queue_history_title),
        onBack = { navController.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            if (history.loadState.refresh is LoadState.Loading) {
                item { Text(stringResource(R.string.generation_queue_history_loading)) }
            }
            if (history.loadState.refresh is LoadState.Error) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.generation_queue_history_load_failed))
                        Button(onClick = history::retry) {
                            Text(stringResource(R.string.generation_queue_history_retry_load))
                        }
                    }
                }
            }
            if (history.itemCount == 0 && history.loadState.refresh is LoadState.NotLoading) {
                item { Text(stringResource(R.string.generation_queue_history_empty)) }
            }
            items(history.itemCount, key = history.itemKey { it.id }) { index ->
                val item = history[index] ?: return@items
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(queueKindLabel(item.kind), style = MaterialTheme.typography.titleMedium)
                        Text(item.promptPreview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(queueStatusLabel(item.status), style = MaterialTheme.typography.labelMedium)
                        if (item.errorMessage == GenerationQueueService.MEDIA_PROCESSING_TIMEOUT_REASON) {
                            Text(stringResource(R.string.generation_queue_media_limit_message),
                                color = MaterialTheme.colorScheme.error)
                        }
                        item.startedAtMillis?.let { Text(stringResource(R.string.generation_queue_started_at, formatQueueTime(it))) }
                        if (item.startedAtMillis != null && item.finishedAtMillis != null) {
                            Text(stringResource(R.string.generation_queue_duration,
                                formatQueueDuration(item.finishedAtMillis - item.startedAtMillis)))
                        }
                        if (item.status == "SUCCEEDED") {
                            val available = item.resultPath?.let { File(it).isFile } == true
                            if (available) {
                                TextButton(onClick = { selected = item }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.generation_queue_result))
                                }
                            } else {
                                Text(stringResource(R.string.generation_queue_result_missing),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (item.status == "FAILED" || item.status == "INTERRUPTED") {
                            TextButton(onClick = {
                                busyItemId = item.id
                                scope.launch {
                                    val result = runCatching { repository.retry(item.id) }
                                    busyItemId = null
                                    retryFilesRevision++
                                    val message = result.fold(
                                        onSuccess = { R.string.generation_queue_retry_added },
                                        onFailure = { error -> retryErrorString(error) }
                                    )
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }, enabled = busyItemId == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.generation_queue_retry))
                            }
                            val hasRetryFiles = remember(item.id, retryFilesRevision) {
                                repository.hasRetryFiles(item.id)
                            }
                            if (hasRetryFiles) {
                                TextButton(onClick = { discardCandidate = item },
                                    enabled = busyItemId == null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.generation_queue_discard_retry_files))
                                }
                            }
                        }
                    }
                }
            }
            if (history.loadState.append is LoadState.Loading) {
                item { Text(stringResource(R.string.generation_queue_history_loading)) }
            }
            if (history.loadState.append is LoadState.Error) {
                item {
                    Button(onClick = history::retry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.generation_queue_history_retry_load))
                    }
                }
            }
        }
    }
    selected?.let { item ->
        if (item.kind == GenerationQueueSnapshot.VIDEO) {
            val metadata = remember(item.metadataPath) {
                runCatching { item.metadataPath?.let { GeneratedVideoMetadata.fromFile(File(it)) } }.getOrNull()
            }
            if (metadata != null && File(metadata.preferredArtifactPath).isFile) {
                VideoGalleryDetail(metadata, navController, onDismiss = { selected = null }, onDeleted = { selected = null })
            } else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { selected = null },
                    title = { Text(stringResource(R.string.generation_queue_result)) },
                    text = { Text(stringResource(R.string.generation_queue_result_missing)) },
                    confirmButton = { TextButton(onClick = { selected = null }) {
                        Text(stringResource(R.string.action_close))
                    } }
                )
            }
        } else {
            QueuedImageResultDialog(File(item.resultPath.orEmpty()), onDismiss = { selected = null })
        }
    }
    discardCandidate?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discardCandidate = null },
            title = { Text(stringResource(R.string.generation_queue_discard_retry_title)) },
            text = { Text(stringResource(R.string.generation_queue_discard_retry_message)) },
            confirmButton = {
                TextButton(onClick = {
                    discardCandidate = null
                    scope.launch {
                        val discarded = runCatching { repository.discardRetryFiles(item.id) }.getOrDefault(false)
                        retryFilesRevision++
                        Toast.makeText(context, if (discarded) R.string.generation_queue_retry_files_discarded
                            else R.string.generation_queue_action_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun retryErrorString(error: Throwable): Int = when ((error as? GenerationQueueRetryException)?.issue) {
    GenerationQueueRetryIssue.BINARY -> R.string.generation_queue_retry_missing_binary
    GenerationQueueRetryIssue.MODEL -> R.string.generation_queue_retry_missing_model
    GenerationQueueRetryIssue.INPUT -> R.string.generation_queue_retry_missing_input
    GenerationQueueRetryIssue.STORAGE -> R.string.generation_queue_retry_storage
    else -> R.string.generation_queue_retry_unavailable
}

@Composable
private fun QueuedImageResultDialog(file: File, onDismiss: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sample = generateSequence(1) { it * 2 }
                .first { bounds.outWidth / it <= 1600 && bounds.outHeight / it <= 1600 }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    val metadata = remember(file.absolutePath) {
        SdGeneratedImageMetadata.fromFile(SdGeneratedImageMetadata.metadataFileForImage(file))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imagegen_generated_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit) }
                metadata?.let { Text(it.prompt, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun queueKindLabel(kind: String): String = stringResource(when (kind) {
    GenerationQueueSnapshot.VIDEO -> R.string.video_gen_title
    GenerationQueueSnapshot.UPSCALE -> R.string.imagegen_upscale_btn
    else -> R.string.imagegen_title
})

@Composable
private fun queueStatusLabel(status: String): String = stringResource(when (status) {
    "SUCCEEDED" -> R.string.generation_queue_succeeded
    "FAILED" -> R.string.generation_queue_failed
    "STOPPED" -> R.string.generation_queue_stopped
    else -> R.string.generation_queue_item_interrupted
})

private fun formatQueueTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun formatQueueDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) / 1000L)
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
