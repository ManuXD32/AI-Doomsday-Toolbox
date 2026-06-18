package com.example.llamadroid.ui.knowledge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeBaseSourceType
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.KnowledgeEmbeddingServerStatus
import com.example.llamadroid.service.KnowledgeBaseDiagnostics
import com.example.llamadroid.service.KnowledgeBaseIndexingService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.util.LogEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun KnowledgeBaseScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { KnowledgeBaseRepository(context, database) }
    val settingsRepository = remember { SettingsRepository(context) }

    val bases by repository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val totalSources by repository.observeSourceCount().collectAsState(initial = 0)
    val vectorChunks by repository.observeChunkCount().collectAsState(initial = 0)
    val errorSources by repository.observeErrorSourceCount().collectAsState(initial = 0)
    val pendingSources by repository.observePendingSourceCount().collectAsState(initial = 0)
    val embeddingServerStatus by repository.observeEmbeddingServerStatus().collectAsState()
    val knowledgeLogs by KnowledgeBaseDiagnostics.logs.collectAsState()
    val notes by database.noteDao().getAllNotes().collectAsState(initial = emptyList())
    val embeddingModels by database.modelDao().getModelsByType(ModelType.EMBEDDING).collectAsState(initial = emptyList())

    val embeddingBackend by settingsRepository.knowledgeEmbeddingBackend.collectAsState()
    val localEmbeddingModelPath by settingsRepository.selectedEmbeddingModelPath.collectAsState()
    val llamaServerUrl by settingsRepository.knowledgeEmbeddingLlamaServerUrl.collectAsState()
    val ollamaUrl by settingsRepository.knowledgeEmbeddingOllamaUrl.collectAsState()
    val ollamaModel by settingsRepository.knowledgeEmbeddingOllamaModel.collectAsState()
    val llamaSwapUrl by settingsRepository.knowledgeEmbeddingLlamaSwapUrl.collectAsState()
    val llamaSwapModel by settingsRepository.knowledgeEmbeddingLlamaSwapModel.collectAsState()
    val chunkSize by settingsRepository.knowledgeBaseChunkSize.collectAsState()
    val embeddingBatchSize by settingsRepository.knowledgeEmbeddingBatchSize.collectAsState()
    val embeddingThreads by settingsRepository.knowledgeEmbeddingThreads.collectAsState()
    val embeddingNetworkVisible by settingsRepository.knowledgeEmbeddingNetworkVisible.collectAsState()
    val embeddingConfig = remember(
        embeddingBackend,
        localEmbeddingModelPath,
        llamaServerUrl,
        ollamaUrl,
        ollamaModel,
        llamaSwapUrl,
        llamaSwapModel
    ) { repository.currentEmbeddingConfig() }

    var selectedBaseId by remember { mutableStateOf<Long?>(null) }
    val selectedBase = bases.firstOrNull { it.id == selectedBaseId }
    val sources by remember(selectedBase?.id) {
        selectedBase?.id?.let { repository.observeSources(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var newBaseName by remember { mutableStateOf("") }
    var newBaseSummary by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf("") }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var showNoteImport by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var basePendingDelete by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    val knowledgeLogListState = rememberLazyListState()
    val logDateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun showError(error: Throwable) {
        Toast.makeText(context, error.message ?: context.getString(R.string.kb_action_failed), Toast.LENGTH_LONG).show()
    }

    fun launchBusy(label: String, block: suspend () -> Unit) {
        scope.launch {
            busyMessage = label
            try {
                runCatching { block() }
                    .onFailure { error ->
                        if (error !is CancellationException) showError(error)
                    }
            } finally {
                if (isActive) busyMessage = null
            }
        }
    }

    fun markStaleSoon() {
        scope.launch {
            runCatching { repository.markIndexedSourcesStaleForCurrentConfig() }
        }
    }

    LaunchedEffect(Unit) {
        repository.repairAllSourceProgress()
    }

    LaunchedEffect(showDiagnostics, knowledgeLogs.size) {
        if (showDiagnostics && knowledgeLogs.isNotEmpty()) {
            knowledgeLogListState.animateScrollToItem(1)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val baseId = selectedBase?.id ?: return@rememberLauncherForActivityResult
        val selectedUris = uris.distinctBy { it.toString() }
        if (selectedUris.isNotEmpty()) {
            scope.launch {
                val queuedSourceIds = selectedUris.mapNotNull { uri ->
                    runCatching {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        repository.queueFile(baseId, uri, resolveDisplayName(context, uri))
                    }.onFailure { error ->
                        KnowledgeBaseDiagnostics.log(
                            context.getString(
                                R.string.kb_log_queue_file_failed,
                                uri.lastPathSegment ?: uri.toString(),
                                error.message ?: error::class.java.simpleName
                            )
                        )
                    }.getOrNull()
                }
                queuedSourceIds.forEach { sourceId ->
                    KnowledgeBaseIndexingService.enqueueQueuedFile(context, sourceId)
                }
                if (queuedSourceIds.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.kb_import_files_queued, queuedSourceIds.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(selectedBaseId, bases) {
        if (selectedBaseId != null && bases.none { it.id == selectedBaseId }) {
            selectedBaseId = null
        }
    }

    AppPageBackground {
        AppContentColumn(modifier = Modifier.fillMaxSize(), bottomPadding = 0.dp) {
            AppPageHeader(
                eyebrow = stringResource(R.string.kb_dashboard_eyebrow),
                title = if (showDiagnostics) {
                    stringResource(R.string.kb_logs_title)
                } else {
                    selectedBase?.name ?: stringResource(R.string.kb_title)
                },
                subtitle = if (showDiagnostics) {
                    stringResource(R.string.kb_logs_subtitle)
                } else if (selectedBase == null) {
                    stringResource(R.string.kb_folder_subtitle)
                } else {
                    stringResource(R.string.kb_folder_detail_subtitle)
                },
                trailing = {
                    IconButton(
                        onClick = {
                            if (showDiagnostics) {
                                showDiagnostics = false
                            } else if (selectedBase != null) {
                                selectedBaseId = null
                            } else {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )

            LazyColumn(
                state = knowledgeLogListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                if (showDiagnostics) {
                    item {
                        KnowledgeDiagnosticsControlCard(
                            status = embeddingServerStatus,
                            onStopServer = {
                                launchBusy(context.getString(R.string.kb_stopping_embedding_server)) {
                                    repository.stopManagedEmbeddingServer("user")
                                }
                            },
                            onClearLogs = KnowledgeBaseDiagnostics::clear,
                            onCopyLogs = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        context.getString(R.string.kb_logs_clip_label),
                                        buildKnowledgeLogExport(knowledgeLogs, logDateFormat)
                                    )
                                )
                                Toast.makeText(context, context.getString(R.string.kb_logs_copied), Toast.LENGTH_SHORT).show()
                            },
                            hasLogs = knowledgeLogs.isNotEmpty()
                        )
                    }
                    if (knowledgeLogs.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.kb_no_logs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(knowledgeLogs.asReversed()) { entry ->
                            KnowledgeLogRow(entry)
                        }
                    }
                    busyMessage?.let { message ->
                        item {
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {

                item {
                    KnowledgeEmbeddingServerCard(
                        status = embeddingServerStatus,
                        backend = embeddingBackend,
                        modelLabel = embeddingConfig.label.ifBlank { stringResource(R.string.kb_embedding_model_none) },
                        embeddingConfigReady = embeddingConfig.isConfigured,
                        networkVisible = embeddingNetworkVisible,
                        chunkSize = chunkSize,
                        embeddingBatchSize = embeddingBatchSize,
                        embeddingThreads = embeddingThreads,
                        onStartServer = {
                            launchBusy(context.getString(R.string.kb_testing_embedding)) {
                                val port = repository.startManagedEmbeddingServer()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.kb_embedding_server_started, port),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onStopServer = {
                            launchBusy(context.getString(R.string.kb_stopping_embedding_server)) {
                                repository.stopManagedEmbeddingServer("user")
                            }
                        },
                        onOpenLogs = { showDiagnostics = true }
                    )
                }

                item {
                    KnowledgeEmbeddingPanel(
                        backend = embeddingBackend,
                        embeddingModels = embeddingModels,
                        selectedModelPath = localEmbeddingModelPath,
                        llamaServerUrl = llamaServerUrl,
                        ollamaUrl = ollamaUrl,
                        ollamaModel = ollamaModel.orEmpty(),
                        llamaSwapUrl = llamaSwapUrl,
                        llamaSwapModel = llamaSwapModel.orEmpty(),
                        chunkSize = chunkSize,
                        embeddingBatchSize = embeddingBatchSize,
                        embeddingThreads = embeddingThreads,
                        embeddingNetworkVisible = embeddingNetworkVisible,
                        embeddingConfigReady = embeddingConfig.isConfigured,
                        onBackendChange = {
                            settingsRepository.setKnowledgeEmbeddingBackend(it)
                            markStaleSoon()
                        },
                        onModelPathChange = {
                            settingsRepository.setSelectedEmbeddingModelPath(it)
                            markStaleSoon()
                        },
                        onLlamaServerUrlChange = settingsRepository::setKnowledgeEmbeddingLlamaServerUrl,
                        onOllamaUrlChange = settingsRepository::setKnowledgeEmbeddingOllamaUrl,
                        onOllamaModelChange = { settingsRepository.setKnowledgeEmbeddingOllamaModel(it.ifBlank { null }) },
                        onLlamaSwapUrlChange = settingsRepository::setKnowledgeEmbeddingLlamaSwapUrl,
                        onLlamaSwapModelChange = { settingsRepository.setKnowledgeEmbeddingLlamaSwapModel(it.ifBlank { null }) },
                        onChunkSizeChange = { size ->
                            settingsRepository.setKnowledgeBaseChunkSize(size)
                            val recommendedBatch = SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(size)
                            if (embeddingBatchSize < recommendedBatch) {
                                settingsRepository.setKnowledgeEmbeddingBatchSize(recommendedBatch)
                            }
                        },
                        onEmbeddingBatchSizeChange = settingsRepository::setKnowledgeEmbeddingBatchSize,
                        onEmbeddingThreadsChange = settingsRepository::setKnowledgeEmbeddingThreads,
                        onNetworkVisibleChange = settingsRepository::setKnowledgeEmbeddingNetworkVisible
                    )
                }

                item {
                    KnowledgeSummaryCard(
                        bases = bases.size,
                        sources = totalSources,
                        vectorChunks = vectorChunks,
                        pending = pendingSources,
                        errors = errorSources,
                        embeddingLabel = embeddingConfig.label.ifBlank { stringResource(R.string.kb_embedding_model_none) }
                    )
                }

                if (selectedBase == null) {
                    item {
                        CreateKnowledgeBaseCard(
                            name = newBaseName,
                            contentSummary = newBaseSummary,
                            onNameChange = { newBaseName = it },
                            onContentSummaryChange = { newBaseSummary = it },
                            onCreate = {
                                val name = newBaseName.trim()
                                if (name.isNotBlank()) {
                                    launchBusy(context.getString(R.string.kb_creating_base)) {
                                        selectedBaseId = repository.createKnowledgeBase(
                                            name = name,
                                            contentSummary = newBaseSummary
                                        )
                                        newBaseName = ""
                                        newBaseSummary = ""
                                    }
                                }
                            }
                        )
                    }
                    if (bases.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.kb_no_bases_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(bases, key = { it.id }) { base ->
                            KnowledgeFolderCard(
                                base = base,
                                onOpen = { selectedBaseId = base.id }
                            )
                        }
                    }
                } else {
                    item {
                        FolderActionsCard(
                            base = selectedBase,
                            canImport = embeddingConfig.isConfigured,
                            canResume = sources.any { canContinueSource(it, embeddingConfig.hash) },
                            onUpload = { filePicker.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) },
                            onAddUrl = { showUrlImport = true },
                            onImportNotes = { showNoteImport = !showNoteImport },
                            onResume = { KnowledgeBaseIndexingService.enqueueResumeBase(context, selectedBase.id) },
                            onReindex = { KnowledgeBaseIndexingService.enqueueReindexBase(context, selectedBase.id) },
                            onDelete = { basePendingDelete = selectedBase }
                        )
                    }

                    item {
                        KnowledgeBaseContentSummaryCard(
                            base = selectedBase,
                            onSave = { summary ->
                                launchBusy(context.getString(R.string.kb_content_summary_saving)) {
                                    repository.updateKnowledgeBaseContentSummary(selectedBase.id, summary)
                                }
                            }
                        )
                    }

                    if (showNoteImport) {
                        item {
                            NoteImportCard(
                                notes = notes.filter { it.isLlmWhitelisted },
                                onImport = { note ->
                                    KnowledgeBaseIndexingService.enqueueNote(context, selectedBase.id, note.id)
                                }
                            )
                        }
                    }

                    item {
                        SourcesCard(
                            sources = sources,
                            currentEmbeddingConfigHash = embeddingConfig.hash,
                            onEnabledChange = { source, enabled ->
                                launchBusy(context.getString(R.string.kb_updating_source)) {
                                    repository.setSourceEnabled(source.id, enabled)
                                }
                            },
                            onResume = { KnowledgeBaseIndexingService.enqueueResumeSource(context, it.id) },
                            onReindex = { KnowledgeBaseIndexingService.enqueueReindexSource(context, it.id) },
                            onDelete = {
                                launchBusy(context.getString(R.string.kb_deleting_source)) {
                                    repository.deleteSource(it.id)
                                }
                            }
                        )
                    }

                    item {
                        TestSearchCard(
                            query = query,
                            result = searchResult,
                            canSearch = embeddingConfig.isConfigured && sources.any { it.embeddedChunkCount > 0 },
                            onQueryChange = { query = it },
                            onSearch = {
                                launchBusy(context.getString(R.string.kb_searching)) {
                                    searchResult = repository.search(query, listOf(selectedBase.id))
                                        .joinToString("\n\n") { result ->
                                            "[${result.sourceTitle} #${result.chunkId}] ${result.text.take(500)}"
                                        }
                                        .ifBlank { context.getString(R.string.kb_search_empty) }
                                }
                            }
                        )
                    }
                }

                busyMessage?.let { message ->
                    item {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                }
            }
        }
    }

    val baseToDelete = basePendingDelete
    if (baseToDelete != null) {
        KnowledgeBaseDeleteConfirmDialog(
            baseName = baseToDelete.name,
            onDismiss = { basePendingDelete = null },
            onConfirm = {
                basePendingDelete = null
                val deleteId = baseToDelete.id
                launchBusy(context.getString(R.string.kb_deleting_base)) {
                    repository.deleteKnowledgeBase(deleteId)
                    if (selectedBaseId == deleteId) {
                        selectedBaseId = null
                    }
                }
            }
        )
    }

    val baseForUrlImport = selectedBase
    if (showUrlImport && baseForUrlImport != null) {
        AlertDialog(
            onDismissRequest = { showUrlImport = false },
            title = { Text(stringResource(R.string.kb_add_url_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.kb_add_url_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        label = { Text(stringResource(R.string.kb_add_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importUrl.isNotBlank(),
                    onClick = {
                        KnowledgeBaseIndexingService.enqueueWeb(context, baseForUrlImport.id, importUrl.trim())
                        Toast.makeText(context, context.getString(R.string.kb_add_url_queued), Toast.LENGTH_SHORT).show()
                        importUrl = ""
                        showUrlImport = false
                    }
                ) {
                    Text(stringResource(R.string.kb_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlImport = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun KnowledgeBaseDeleteConfirmDialog(
    baseName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kb_delete_base_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.kb_delete_base_confirm_message, baseName),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun KnowledgeEmbeddingServerCard(
    status: KnowledgeEmbeddingServerStatus,
    backend: String,
    modelLabel: String,
    embeddingConfigReady: Boolean,
    networkVisible: Boolean,
    chunkSize: Int,
    embeddingBatchSize: Int,
    embeddingThreads: Int,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val isLocal = backend == SettingsRepository.KB_EMBED_BACKEND_LOCAL
    val isRunning = status.running
    val isStarting = status.starting
    val endpointHost = if (isRunning || isStarting) {
        status.host
    } else if (networkVisible) {
        "0.0.0.0"
    } else {
        "127.0.0.1"
    }
    val statusText = when {
        !isLocal -> stringResource(R.string.kb_embedding_server_local_only)
        isRunning -> stringResource(R.string.status_running)
        isStarting -> stringResource(R.string.dashboard_starting)
        else -> stringResource(R.string.status_stopped)
    }
    val statusColor = when {
        isRunning -> Color(0xFF4CAF50)
        isStarting -> Color(0xFFFFC107)
        !isLocal -> Color(0xFF9E9E9E)
        else -> Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.kb_embedding_server_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.kb_embedding_server_endpoint, endpointHost, status.port),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (networkVisible) {
                            stringResource(R.string.kb_embedding_server_lan_visible)
                        } else {
                            stringResource(R.string.kb_embedding_server_local_visible)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.kb_embedding_server_model_detail, modelLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.kb_embedding_server_runtime_detail, chunkSize, embeddingBatchSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLocal) {
                        Text(
                            stringResource(R.string.kb_embedding_threads_value, embeddingThreads),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            status.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = if (isRunning || isStarting) onStopServer else onStartServer,
                    enabled = isLocal && (embeddingConfigReady || isRunning || isStarting),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (isRunning || isStarting) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Icon(
                        if (isRunning || isStarting) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRunning || isStarting) stringResource(R.string.kb_stop_embedding_server)
                        else stringResource(R.string.kb_test_embedding),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.kb_open_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnowledgeEmbeddingPanel(
    backend: String,
    embeddingModels: List<ModelEntity>,
    selectedModelPath: String?,
    llamaServerUrl: String,
    ollamaUrl: String,
    ollamaModel: String,
    llamaSwapUrl: String,
    llamaSwapModel: String,
    chunkSize: Int,
    embeddingBatchSize: Int,
    embeddingThreads: Int,
    embeddingNetworkVisible: Boolean,
    embeddingConfigReady: Boolean,
    onBackendChange: (String) -> Unit,
    onModelPathChange: (String?) -> Unit,
    onLlamaServerUrlChange: (String) -> Unit,
    onOllamaUrlChange: (String) -> Unit,
    onOllamaModelChange: (String) -> Unit,
    onLlamaSwapUrlChange: (String) -> Unit,
    onLlamaSwapModelChange: (String) -> Unit,
    onChunkSizeChange: (Int) -> Unit,
    onEmbeddingBatchSizeChange: (Int) -> Unit,
    onEmbeddingThreadsChange: (Int) -> Unit,
    onNetworkVisibleChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.kb_embedding_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                embeddingBackendOptions().forEach { option ->
                    FilterChip(
                        selected = backend == option.backend,
                        onClick = { onBackendChange(option.backend) },
                        label = { Text(stringResource(option.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            when (backend) {
                SettingsRepository.KB_EMBED_BACKEND_LOCAL -> {
                    Text(stringResource(R.string.kb_local_embedding_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (embeddingModels.isEmpty()) {
                        Text(stringResource(R.string.kb_no_embedding_models), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            embeddingModels.forEach { model ->
                                FilterChip(
                                    selected = selectedModelPath == model.path,
                                    onClick = { onModelPathChange(model.path) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }
                SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER -> {
                    OutlinedTextField(
                        value = llamaServerUrl,
                        onValueChange = onLlamaServerUrlChange,
                        label = { Text(stringResource(R.string.kb_llama_server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SettingsRepository.KB_EMBED_BACKEND_OLLAMA -> {
                    BackendUrlAndModelFields(
                        url = ollamaUrl,
                        model = ollamaModel,
                        urlLabel = stringResource(R.string.kb_ollama_url),
                        modelLabel = stringResource(R.string.kb_remote_embedding_model),
                        onUrlChange = onOllamaUrlChange,
                        onModelChange = onOllamaModelChange
                    )
                }
                SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP -> {
                    BackendUrlAndModelFields(
                        url = llamaSwapUrl,
                        model = llamaSwapModel,
                        urlLabel = stringResource(R.string.kb_llama_swap_url),
                        modelLabel = stringResource(R.string.kb_remote_embedding_model),
                        onUrlChange = onLlamaSwapUrlChange,
                        onModelChange = onLlamaSwapModelChange
                    )
                }
            }

            if (backend == SettingsRepository.KB_EMBED_BACKEND_LOCAL) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.kb_embedding_network_visibility_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(
                                    if (embeddingNetworkVisible) {
                                        R.string.kb_embedding_network_visibility_lan_desc
                                    } else {
                                        R.string.kb_embedding_network_visibility_local_desc
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = embeddingNetworkVisible,
                            onCheckedChange = onNetworkVisibleChange
                        )
                    }
                    Text(
                        text = stringResource(R.string.kb_embedding_network_visibility_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IntSliderWithInput(
                    value = chunkSize,
                    onValueChange = onChunkSizeChange,
                    valueRange = SettingsRepository.KB_CHUNK_SIZE_RANGE,
                    label = stringResource(R.string.kb_chunk_size_label),
                    suffix = stringResource(R.string.kb_chunk_size_suffix),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.kb_chunk_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IntSliderWithInput(
                    value = embeddingBatchSize,
                    onValueChange = onEmbeddingBatchSizeChange,
                    valueRange = SettingsRepository.KB_EMBED_BATCH_SIZE_RANGE,
                    label = stringResource(R.string.kb_embedding_batch_size_label),
                    suffix = stringResource(R.string.kb_embedding_batch_size_suffix),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.kb_embedding_batch_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.kb_embedding_batch_size_hint,
                        SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(chunkSize)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (backend == SettingsRepository.KB_EMBED_BACKEND_LOCAL) {
                    IntSliderWithInput(
                        value = embeddingThreads,
                        onValueChange = onEmbeddingThreadsChange,
                        valueRange = SettingsRepository.KB_EMBED_THREADS_RANGE,
                        label = stringResource(R.string.kb_embedding_threads_label),
                        suffix = stringResource(R.string.kb_embedding_threads_suffix),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.kb_embedding_threads_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AssistChip(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = if (embeddingConfigReady) stringResource(R.string.kb_embedding_ready)
                        else stringResource(R.string.kb_embedding_not_ready),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            Text(
                text = stringResource(R.string.kb_embedding_model_change_reminder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KnowledgeDiagnosticsControlCard(
    status: KnowledgeEmbeddingServerStatus,
    onStopServer: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    hasLogs: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.kb_embedding_server_status, embeddingServerStatusLabel(status)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            status.binaryName?.takeIf { it.isNotBlank() }?.let { binaryName ->
                Text(
                    text = stringResource(R.string.kb_embedding_server_binary, binaryName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            status.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onStopServer,
                    enabled = status.running || status.starting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.kb_stop_embedding_server), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyLogs,
                        enabled = hasLogs,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.kb_copy_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(
                        onClick = onClearLogs,
                        enabled = hasLogs,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.kb_clear_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeLogRow(entry: LogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatter.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Visible
            )
        }
    }
}

private fun buildKnowledgeLogExport(logs: List<LogEntry>, formatter: SimpleDateFormat): String =
    logs.joinToString("\n") { entry ->
        "[${formatter.format(Date(entry.timestamp))}] ${entry.message}"
    }

@Composable
private fun embeddingServerStatusLabel(status: KnowledgeEmbeddingServerStatus): String =
    when {
        status.running -> stringResource(R.string.kb_embedding_server_running, status.host, status.port)
        status.starting -> stringResource(R.string.kb_embedding_server_starting, status.host, status.port)
        else -> stringResource(R.string.kb_embedding_server_stopped)
    }

@Composable
private fun BackendUrlAndModelFields(
    url: String,
    model: String,
    urlLabel: String,
    modelLabel: String,
    onUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = url, onValueChange = onUrlChange, label = { Text(urlLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = onModelChange, label = { Text(modelLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnowledgeSummaryCard(
    bases: Int,
    sources: Int,
    vectorChunks: Int,
    pending: Int,
    errors: Int,
    embeddingLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                KnowledgeMetric(stringResource(R.string.kb_metric_folders), bases.toString(), Modifier.weight(1f))
                KnowledgeMetric(stringResource(R.string.kb_metric_sources), sources.toString(), Modifier.weight(1f))
                KnowledgeMetric(stringResource(R.string.kb_metric_vectors), vectorChunks.toString(), Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.kb_pending_count, pending)) })
                AssistChip(onClick = {}, label = { Text(if (errors > 0) stringResource(R.string.kb_errors_count, errors) else stringResource(R.string.kb_no_errors)) })
                AssistChip(onClick = {}, label = { Text(embeddingLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
}

@Composable
private fun CreateKnowledgeBaseCard(
    name: String,
    contentSummary: String,
    onNameChange: (String) -> Unit,
    onContentSummaryChange: (String) -> Unit,
    onCreate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.kb_create_folder), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.kb_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = contentSummary,
                onValueChange = onContentSummaryChange,
                label = { Text(stringResource(R.string.kb_content_summary_label)) },
                placeholder = { Text(stringResource(R.string.kb_content_summary_placeholder)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.kb_content_summary_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onCreate, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_create_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun KnowledgeFolderCard(base: KnowledgeBaseEntity, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(base.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = base.contentSummary.ifBlank { stringResource(R.string.kb_folder_open_desc) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

}

@Composable
private fun KnowledgeBaseContentSummaryCard(
    base: KnowledgeBaseEntity,
    onSave: (String) -> Unit
) {
    var summary by remember(base.id, base.contentSummary) { mutableStateOf(base.contentSummary) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.kb_content_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.kb_content_summary_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(stringResource(R.string.kb_content_summary_label)) },
                placeholder = { Text(stringResource(R.string.kb_content_summary_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(summary) },
                enabled = summary.trim() != base.contentSummary.trim(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.kb_content_summary_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderActionsCard(
    base: KnowledgeBaseEntity,
    canImport: Boolean,
    canResume: Boolean,
    onUpload: () -> Unit,
    onAddUrl: () -> Unit,
    onImportNotes: () -> Unit,
    onResume: () -> Unit,
    onReindex: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(base.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (canImport) stringResource(R.string.kb_upload_ready) else stringResource(R.string.kb_upload_needs_embedding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onUpload, enabled = canImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.kb_upload_documents), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onImportNotes, enabled = canImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.kb_import_notes_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onAddUrl, enabled = canImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.kb_add_url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onResume,
                enabled = canImport && canResume,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_continue_embeddings_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onReindex, enabled = canImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_reindex_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NoteImportCard(
    notes: List<com.example.llamadroid.data.db.NoteEntity>,
    onImport: (com.example.llamadroid.data.db.NoteEntity) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kb_import_notes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (notes.isEmpty()) {
                Text(stringResource(R.string.kb_no_whitelisted_notes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    notes.forEach { note ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onImport(note) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = false, onClick = { onImport(note) })
                            Text(note.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.kb_import), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(
    sources: List<KnowledgeSourceEntity>,
    currentEmbeddingConfigHash: String,
    onEnabledChange: (KnowledgeSourceEntity, Boolean) -> Unit,
    onResume: (KnowledgeSourceEntity) -> Unit,
    onReindex: (KnowledgeSourceEntity) -> Unit,
    onDelete: (KnowledgeSourceEntity) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.kb_sources_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (sources.isEmpty()) {
                Text(stringResource(R.string.kb_no_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sources.forEachIndexed { index, source ->
                    if (index > 0) HorizontalDivider()
                    SourceRow(
                        source = source,
                        displayStatus = displayStatusFor(source, currentEmbeddingConfigHash),
                        canResume = canContinueSource(source, currentEmbeddingConfigHash),
                        onEnabledChange = { onEnabledChange(source, it) },
                        onResume = { onResume(source) },
                        onReindex = { onReindex(source) },
                        onDelete = { onDelete(source) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: KnowledgeSourceEntity,
    displayStatus: String,
    canResume: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onResume: () -> Unit,
    onReindex: () -> Unit,
    onDelete: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val openableSourceRef = source.sourceRef.takeIf(::isOpenableSourceReference)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(source.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KnowledgeSourceTypeBadge(source)
                }
                if (openableSourceRef != null) {
                    Text(
                        openableSourceRef,
                        modifier = Modifier.clickable {
                            runCatching { uriHandler.openUri(openableSourceRef) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    knowledgeSourceProgressDetail(source, displayStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    knowledgeSourceStatusLabel(displayStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (displayStatus == KnowledgeBaseSourceStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = source.enabled, onCheckedChange = onEnabledChange)
            if (canResume) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.kb_continue_embeddings))
                }
            }
            IconButton(onClick = onReindex) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.kb_reindex))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
        val total = source.progressTotal.takeIf { it > 0 } ?: source.chunkCount
        if (displayStatus in listOf(KnowledgeBaseSourceStatus.QUEUED, KnowledgeBaseSourceStatus.EXTRACTING, KnowledgeBaseSourceStatus.CHUNKING, KnowledgeBaseSourceStatus.EMBEDDING) && total > 0) {
            LinearProgressIndicator(
                progress = { (source.progressDone.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        source.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun isOpenableSourceReference(value: String): Boolean {
    val clean = value.trim()
    return clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)
}

@Composable
private fun KnowledgeSourceTypeBadge(source: KnowledgeSourceEntity) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = knowledgeSourceTypeLabel(source),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun knowledgeSourceTypeLabel(source: KnowledgeSourceEntity): String {
    val reference = "${source.title} ${source.sourceRef}".lowercase(Locale.US)
    return when {
        source.type == KnowledgeBaseSourceType.NOTE -> stringResource(R.string.kb_source_type_note)
        reference.contains(".pdf") -> stringResource(R.string.kb_source_type_pdf)
        reference.contains("pubmed.ncbi.nlm.nih.gov") ||
            reference.contains("pmc.ncbi.nlm.nih.gov") ||
            reference.contains("doi.org") ||
            "journal" in reference ||
            "article" in reference -> stringResource(R.string.kb_source_type_article)
        source.type == KnowledgeBaseSourceType.WEB -> stringResource(R.string.kb_source_type_webpage)
        reference.endsWith(".txt") || reference.endsWith(".md") || reference.endsWith(".markdown") -> {
            stringResource(R.string.kb_source_type_text_file)
        }
        else -> stringResource(R.string.kb_source_type_file)
    }
}

@Composable
private fun TestSearchCard(
    query: String,
    result: String,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.kb_test_query), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = query, onValueChange = onQueryChange, label = { Text(stringResource(R.string.kb_search_label)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSearch, enabled = canSearch && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_search_action))
            }
            if (!canSearch) {
                Text(stringResource(R.string.kb_search_needs_vectors), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.isNotBlank()) {
                Text(result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun KnowledgeMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class EmbeddingBackendOption(val backend: String, val labelRes: Int)

private fun embeddingBackendOptions(): List<EmbeddingBackendOption> = listOf(
    EmbeddingBackendOption(SettingsRepository.KB_EMBED_BACKEND_LOCAL, R.string.kb_backend_local_llama),
    EmbeddingBackendOption(SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER, R.string.kb_backend_llama_server),
    EmbeddingBackendOption(SettingsRepository.KB_EMBED_BACKEND_OLLAMA, R.string.kb_backend_ollama),
    EmbeddingBackendOption(SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP, R.string.kb_backend_llama_swap)
)

private fun displayStatusFor(source: KnowledgeSourceEntity, currentHash: String): String =
    if (source.status == KnowledgeBaseSourceStatus.INDEXED &&
        source.embeddingConfigHash != currentHash
    ) {
        KnowledgeBaseSourceStatus.STALE
    } else {
        source.status
    }

private fun canContinueSource(source: KnowledgeSourceEntity, currentHash: String): Boolean =
    source.chunkCount > 0 &&
        source.embeddedChunkCount in 0 until source.chunkCount &&
        source.embeddingConfigHash == currentHash

@Composable
private fun knowledgeSourceProgressDetail(source: KnowledgeSourceEntity, status: String): String = when {
    status == KnowledgeBaseSourceStatus.QUEUED && source.chunkCount == 0 -> {
        stringResource(R.string.kb_source_pipeline_queued)
    }
    status == KnowledgeBaseSourceStatus.EXTRACTING && source.chunkCount == 0 -> {
        stringResource(R.string.kb_source_pipeline_pending_fragmenting)
    }
    status == KnowledgeBaseSourceStatus.CHUNKING && source.chunkCount == 0 -> {
        stringResource(R.string.kb_source_pipeline_pending_vectorizing)
    }
    else -> stringResource(R.string.kb_source_chunk_detail, source.chunkCount, source.embeddedChunkCount)
}

@Composable
private fun knowledgeSourceStatusLabel(status: String): String = when (status) {
    KnowledgeBaseSourceStatus.QUEUED -> stringResource(R.string.kb_status_queued)
    KnowledgeBaseSourceStatus.EXTRACTING -> stringResource(R.string.kb_status_extracting)
    KnowledgeBaseSourceStatus.CHUNKING -> stringResource(R.string.kb_status_chunking)
    KnowledgeBaseSourceStatus.EMBEDDING,
    KnowledgeBaseSourceStatus.INDEXING -> stringResource(R.string.kb_status_embedding)
    KnowledgeBaseSourceStatus.INDEXED -> stringResource(R.string.kb_status_indexed)
    KnowledgeBaseSourceStatus.STALE -> stringResource(R.string.kb_status_stale)
    KnowledgeBaseSourceStatus.ERROR -> stringResource(R.string.kb_status_error)
    else -> status
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "Document"
}
