package com.example.llamadroid.ui.dataset

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.*
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.DatasetExporter
import com.example.llamadroid.data.model.DatasetFormat
import com.example.llamadroid.service.DatasetForegroundService
import com.example.llamadroid.service.DatasetProcessor
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.normalizeDatasetBackend
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel to preserve state across navigation
 */
class DatasetProjectViewModel : ViewModel() {
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()
    
    fun setTab(index: Int) {
        _selectedTab.value = index
    }
}

// Tab data class for icons
data class TabItem(val icon: ImageVector, val label: String)

private enum class DatasetProcessStage {
    CLEAN,
    QUESTIONS,
    ANSWERS,
    RATING
}

private fun DatasetProcessor.Job.isImportJobFor(projectId: Long): Boolean =
    this.projectId == projectId && (this is DatasetProcessor.Job.ImportPdf || this is DatasetProcessor.Job.ImportTxt)

private fun DatasetProcessor.Job.importFileName(): String = when (this) {
    is DatasetProcessor.Job.ImportPdf -> sourceName.ifBlank { name }
    is DatasetProcessor.Job.ImportTxt -> sourceName.ifBlank { name }
    else -> name
}

private fun persistDatasetImportReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

/**
 * Dataset Project Detail Screen with 5 tabs (icon-based for better fit)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetProjectScreen(
    navController: NavController,
    projectId: Long
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.datasetDao()
    
    // ViewModel for state persistence
    val viewModel: DatasetProjectViewModel = viewModel()
    
    // State from ViewModel (survives navigation)
    val selectedTab by viewModel.selectedTab.collectAsState()
    
    // State from database - using Flow for project so settings changes auto-refresh
    val project by dao.getProjectFlow(projectId).collectAsState(initial = null)
    val sources by dao.getSourcesForProject(projectId).collectAsState(initial = emptyList())
    val chunks by dao.getChunksForProject(projectId).collectAsState(initial = emptyList())
    val qaList by dao.getQAForProject(projectId).collectAsState(initial = emptyList())
    val prompts by dao.getAllPrompts().collectAsState(initial = emptyList())
    
    // Processing state owned by the foreground runtime
    val processingProgress by DatasetForegroundService.progress.collectAsState()
    val isProcessing by DatasetForegroundService.isProcessing.collectAsState()
    val jobQueue by DatasetForegroundService.jobQueue.collectAsState()
    val activeJob by DatasetForegroundService.activeJob.collectAsState()
    val activeImportJob = activeJob?.takeIf { it.isImportJobFor(projectId) }
    val queuedImportJobs = jobQueue.filter { it.isImportJobFor(projectId) }
    
    // Icon-based tabs (no text cutting)
    val tabs = listOf(
        TabItem(Icons.Default.Home, stringResource(R.string.dataset_tab_sources)),
        TabItem(Icons.Default.Menu, stringResource(R.string.dataset_tab_chunks)),
        TabItem(Icons.Default.PlayArrow, stringResource(R.string.dataset_tab_process)),
        TabItem(Icons.Default.Star, stringResource(R.string.dataset_tab_review)),
        TabItem(Icons.Default.List, stringResource(R.string.dataset_tab_queue)),
        TabItem(Icons.Default.Settings, stringResource(R.string.dataset_tab_settings))
    )
    
    // File pickers
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pdfUri ->
            persistDatasetImportReadPermission(context, pdfUri)
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: resources.getString(R.string.file_type_pdf)
            DatasetForegroundService.enqueue(
                context,
                DatasetProcessor.Job.ImportPdf(
                    projectId = projectId,
                    sourceUri = pdfUri.toString(),
                    sourceName = name,
                    name = resources.getString(R.string.dataset_job_import_pdf)
                )
            )
        }
    }
    
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { txtUri ->
            persistDatasetImportReadPermission(context, txtUri)
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: resources.getString(R.string.file_type_text)
            DatasetForegroundService.enqueue(
                context,
                DatasetProcessor.Job.ImportTxt(
                    projectId = projectId,
                    sourceUri = txtUri.toString(),
                    sourceName = name,
                    name = resources.getString(R.string.dataset_job_import_txt)
                )
            )
        }
    }
    
    // State for selected IDs during export
    var selectedIdsForExport by remember { mutableStateOf(setOf<Long>()) }
    var showDatasetInfo by remember { mutableStateOf(false) }
    
    // Export launcher  
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportUri ->
            scope.launch {
                exportQAToFile(context, exportUri, qaList, chunks, selectedIdsForExport)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: stringResource(R.string.dataset_tab_sources), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showDatasetInfo = true }) {
                        Icon(Icons.Default.Info, stringResource(R.string.dataset_info_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Scrollable Tab Row with icons and short labels
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { viewModel.setTab(index) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        text = { Text(tab.label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }

            if (isProcessing && processingProgress != null) {
                DatasetRuntimeBanner(
                    progress = processingProgress,
                    queuedJobCount = jobQueue.size,
                    onStop = { DatasetForegroundService.cancelCurrent(context) }
                )
            }
            
            // Tab Content
            when (selectedTab) {
                0 -> SourcesTab(
                    sources = sources,
                    chunks = chunks,
                    importProgress = processingProgress.takeIf { activeImportJob != null },
                    activeImportJob = activeImportJob,
                    queuedImportJobs = queuedImportJobs,
                    onAddPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onAddTxt = { txtPicker.launch(arrayOf("text/*")) },
                    onDeleteSource = { source ->
                        scope.launch {
                            dao.deleteChunksForSource(source.id)
                            dao.deleteSource(source)
                        }
                    }
                )
                1 -> ChunksTab(
                    chunks = chunks,
                    sources = sources,
                    onUpdateChunk = { chunk ->
                        scope.launch { dao.updateChunk(chunk) }
                    },
                    onRegenQuestions = { chunkIds ->
                        project?.let { proj ->
                            val questionPrompt = prompts.find { it.type == PromptType.QUESTION && it.isDefault }?.content ?: DEFAULT_QUESTION_PROMPT
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenQuestions(
                                    chunkIds,
                                    proj.id,
                                    questionPrompt,
                                    resources.getString(R.string.dataset_regen_questions)
                                )
                            )
                        }
                    },
                    onDeleteSelectedChunks = { chunkIds ->
                        scope.launch {
                            dao.deleteChunksByIds(chunkIds.toList())
                            android.widget.Toast.makeText(
                                context,
                                resources.getString(R.string.dataset_chunks_removed_success, chunkIds.size),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    isProcessing = isProcessing
                )
                2 -> ProcessTab(
                    chunks = chunks,
                    qaList = qaList,
                    prompts = prompts,
                    isProcessing = isProcessing,
                    onRunClean = { prompt ->
                        project?.let { proj ->
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.Clean(proj.id, prompt, resources.getString(R.string.dataset_job_clean))
                            )
                        }
                    },
                    onRunQuestions = { prompt ->
                        project?.let { proj ->
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.Questions(proj.id, prompt, resources.getString(R.string.dataset_job_questions))
                            )
                        }
                    },
                    onRunAnswers = { prompt ->
                        project?.let { proj ->
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.Answers(proj.id, prompt, resources.getString(R.string.dataset_job_answers))
                            )
                        }
                    },
                    onRunRating = { prompt ->
                        project?.let { proj ->
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.Rating(proj.id, prompt, resources.getString(R.string.dataset_job_rating))
                            )
                        }
                    },
                    onRunSequence = { stages, cleanPrompt, questionPrompt, answerPrompt, reviewPrompt ->
                        project?.let { proj ->
                            val jobs = stages.map { stage ->
                                when (stage) {
                                    DatasetProcessStage.CLEAN -> DatasetProcessor.Job.Clean(
                                        proj.id,
                                        cleanPrompt,
                                        resources.getString(R.string.dataset_job_clean)
                                    )
                                    DatasetProcessStage.QUESTIONS -> DatasetProcessor.Job.Questions(
                                        proj.id,
                                        questionPrompt,
                                        resources.getString(R.string.dataset_job_questions)
                                    )
                                    DatasetProcessStage.ANSWERS -> DatasetProcessor.Job.Answers(
                                        proj.id,
                                        answerPrompt,
                                        resources.getString(R.string.dataset_job_answers)
                                    )
                                    DatasetProcessStage.RATING -> DatasetProcessor.Job.Rating(
                                        proj.id,
                                        reviewPrompt,
                                        resources.getString(R.string.dataset_job_rating)
                                    )
                                }
                            }
                            DatasetForegroundService.enqueueBatch(context, jobs)
                        }
                    }
                )
                3 -> ReviewTab(
                    qaList = qaList,
                    chunks = chunks,
                    onUpdateQA = { qa ->
                        scope.launch { dao.updateQA(qa) }
                    },
                    onDeleteQA = { qa ->
                        scope.launch { dao.deleteQA(qa) }
                    },
                    onDeleteSelected = { selectedIds ->
                        scope.launch {
                            selectedIds.forEach { id ->
                                qaList.find { it.id == id }?.let { dao.deleteQA(it) }
                            }
                        }
                    },
                    onRegenerateAnswer = { qa ->
                        project?.let { proj ->
                            val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenAnswer(
                                    qa.id,
                                    proj.id,
                                    answerPrompt,
                                    resources.getString(R.string.dataset_job_regen_answer)
                                )
                            )
                        }
                    },
                    onRegenerateRating = { qa ->
                        project?.let { proj ->
                            val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenRating(
                                    qa.id,
                                    proj.id,
                                    reviewPrompt,
                                    resources.getString(R.string.dataset_job_regen_rating)
                                )
                            )
                        }
                    },
                    onRegenerateSelectedAnswers = { selectedIds ->
                        project?.let { proj ->
                            val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenAnswers(
                                    selectedIds,
                                    proj.id,
                                    answerPrompt,
                                    resources.getString(R.string.dataset_job_regen_answers_param, selectedIds.size)
                                )
                            )
                        }
                    },
                    onRegenerateSelectedRatings = { selectedIds ->
                        project?.let { proj ->
                            val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenRatings(
                                    selectedIds,
                                    proj.id,
                                    reviewPrompt,
                                    resources.getString(R.string.dataset_job_regen_ratings_param, selectedIds.size)
                                )
                            )
                        }
                    },
                    onExport = { selectedIds ->
                        selectedIdsForExport = selectedIds
                        val filename = "dataset_${System.currentTimeMillis()}.json"
                        exportLauncher.launch(filename)
                    }
                )
                4 -> QueueTab(
                    activeJob = activeJob,
                    jobQueue = jobQueue,
                    onCancelAll = { DatasetForegroundService.cancelAll(context) },
                    onRemoveJob = { index -> DatasetForegroundService.removeQueuedJob(context, index) },
                    onMoveJobUp = { index -> DatasetForegroundService.moveQueuedJobUp(context, index) },
                    onMoveJobDown = { index -> DatasetForegroundService.moveQueuedJobDown(context, index) }
                )
                5 -> SettingsTab(
                    project = project,
                    prompts = prompts,
                    dao = dao,
                    onUpdateProject = { updated ->
                        scope.launch {
                            dao.updateProject(updated)
                            // Flow will auto-refresh project
                        }
                    }
                )
            }
        }
    }

    if (showDatasetInfo) {
        DatasetCreatorInfoDialog(onDismiss = { showDatasetInfo = false })
    }
}

@Composable
private fun DatasetCreatorInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dataset_info_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.dataset_info_intro))
                Text(stringResource(R.string.dataset_info_sources), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dataset_info_sources_body))
                Text(stringResource(R.string.dataset_info_process), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dataset_info_process_body))
                Text(stringResource(R.string.dataset_info_review), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dataset_info_review_body))
                Text(stringResource(R.string.dataset_info_queue), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dataset_info_queue_body))
                Text(stringResource(R.string.dataset_info_settings), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dataset_info_settings_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

// Export helper
private suspend fun exportQAToFile(
    context: Context,
    uri: Uri,
    qaList: List<DatasetQAEntity>,
    chunks: List<DatasetChunkEntity>,
    selectedIds: Set<Long> = emptySet()
) {
    try {
        com.example.llamadroid.util.DebugLog.log("[Export] Starting export, selected=${selectedIds.size}, total=${qaList.size}")
        
        // If specific items selected, export those; otherwise export all with score >= 3
        val toExport = if (selectedIds.isNotEmpty()) {
            qaList.filter { it.id in selectedIds && it.answer != null }
        } else {
            qaList.filter { (it.score ?: 0) >= 3 && it.answer != null }
        }
        
        com.example.llamadroid.util.DebugLog.log("[Export] Items to export: ${toExport.size}")
        
        val entries = toExport.map { qa ->
            com.example.llamadroid.data.model.DatasetEntry(
                source = com.example.llamadroid.data.model.DatasetSource.MANUAL,
                sourceName = "Generated",
                instruction = qa.question,
                input = "",
                output = qa.answer ?: ""
            )
        }
        
        val output = DatasetExporter.toAlpaca(entries)
        com.example.llamadroid.util.DebugLog.log("[Export] JSON size: ${output.length} chars")
        
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(output.toByteArray())
            }
        }
        
        com.example.llamadroid.util.DebugLog.log("[Export] Export completed successfully")
        android.widget.Toast.makeText(context, context.getString(R.string.dataset_export_success, toExport.size), android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        com.example.llamadroid.util.DebugLog.log("[Export] ERROR: ${e.message}")
        e.printStackTrace()
        android.widget.Toast.makeText(context, context.getString(R.string.dataset_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun DatasetRuntimeBanner(
    progress: DatasetProcessor.Progress?,
    queuedJobCount: Int,
    onStop: () -> Unit
) {
    if (progress == null) return
    var expanded by remember { mutableStateOf(false) }
    val progressFraction = progress.current.toFloat() / progress.total.coerceAtLeast(1)

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.dataset_running),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand)
                        )
                    }
                    TextButton(
                        onClick = onStop,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
            Text(progress.stage, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                "${progress.current} / ${progress.total}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            if (expanded) {
                Text(
                    progress.currentItem,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    stringResource(R.string.dataset_runtime_queue_count, queuedJobCount),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ========== SOURCES TAB ==========
@Composable
private fun SourcesTab(
    sources: List<DatasetSourceEntity>,
    chunks: List<DatasetChunkEntity>,
    importProgress: DatasetProcessor.Progress?,
    activeImportJob: DatasetProcessor.Job?,
    queuedImportJobs: List<DatasetProcessor.Job>,
    onAddPdf: () -> Unit,
    onAddTxt: () -> Unit,
    onDeleteSource: (DatasetSourceEntity) -> Unit
) {
    val hasImportPending = activeImportJob != null || queuedImportJobs.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Stats row
        item {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        Text("${sources.size} ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(stringResource(R.string.dataset_sources_count), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        Text("${chunks.size} ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(stringResource(R.string.dataset_chunks_count), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Add buttons row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onAddPdf,
                    enabled = !hasImportPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.dataset_add_pdf))
                }
                FilledTonalButton(
                    onClick = onAddTxt,
                    enabled = !hasImportPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.dataset_add_txt))
                }
            }
        }

        importProgress?.let { progress ->
            item {
                DatasetImportProgressCard(
                    progress = progress,
                    activeImportJob = activeImportJob,
                    queuedImportJobs = queuedImportJobs
                )
            }
        }

        if (importProgress == null && queuedImportJobs.isNotEmpty()) {
            item {
                DatasetImportQueuedCard(queuedImportJobs = queuedImportJobs)
            }
        }
        
        // Source items
        items(sources) { source ->
            val chunkCount = chunks.count { it.sourceId == source.id }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            when (source.type) {
                                SourceType.PDF -> "📄"
                                SourceType.TXT -> "📝"
                                SourceType.NOTE -> "🗒️"
                            },
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(source.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                             Text(stringResource(R.string.dataset_chunks_count_param, chunkCount), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onDeleteSource(source) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Empty state
        if (sources.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📂", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.dataset_no_sources), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(stringResource(R.string.dataset_add_sources_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DatasetImportProgressCard(
    progress: DatasetProcessor.Progress,
    activeImportJob: DatasetProcessor.Job?,
    queuedImportJobs: List<DatasetProcessor.Job>
) {
    val diagnostics = progress.currentItem
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val fileName = activeImportJob?.importFileName()?.takeIf { it.isNotBlank() }
    val indicatorColor = MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(false) }
    val progressFraction = progress.current.toFloat() / progress.total.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dataset_import_title_running), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        progress.stage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    fileName?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = indicatorColor
            )

            Text(
                stringResource(R.string.dataset_import_exit_warning),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )

            if (expanded) {
                diagnostics.forEach { line ->
                    Text(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = line,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (queuedImportJobs.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dataset_import_queued_count, queuedImportJobs.size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DatasetImportQueuedCard(
    queuedImportJobs: List<DatasetProcessor.Job>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dataset_import_queued_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                stringResource(R.string.dataset_import_queued_count, queuedImportJobs.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            queuedImportJobs.take(3).forEach { job ->
                Text(
                    job.importFileName(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                stringResource(R.string.dataset_import_exit_warning),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ========== CHUNKS TAB ==========
@Composable
fun ChunksTab(
    chunks: List<DatasetChunkEntity>,
    sources: List<DatasetSourceEntity>,
    onUpdateChunk: (DatasetChunkEntity) -> Unit,
    onRegenQuestions: (Set<Long>) -> Unit = {},
    onDeleteSelectedChunks: (Set<Long>) -> Unit = {},
    isProcessing: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    var editingChunk by remember { mutableStateOf<DatasetChunkEntity?>(null) }
    var selectedChunkIds by remember { mutableStateOf(setOf<Long>()) }
    var pendingDeleteChunkIds by remember { mutableStateOf<Set<Long>?>(null) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Compact action bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { selectedChunkIds = if (selectedChunkIds.size == chunks.size) emptySet() else chunks.map { it.id }.toSet() }
                ) {
                    Text(
                        if (selectedChunkIds.isEmpty()) stringResource(R.string.dataset_select_all)
                        else stringResource(R.string.dataset_selected, selectedChunkIds.size),
                        fontSize = 13.sp
                    )
                }
                Button(
                    onClick = { if (selectedChunkIds.isNotEmpty()) onRegenQuestions(selectedChunkIds) },
                    enabled = selectedChunkIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.dataset_regen_questions), fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { pendingDeleteChunkIds = selectedChunkIds },
                    enabled = selectedChunkIds.isNotEmpty() && !isProcessing,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.dataset_remove_selected_chunks), fontSize = 13.sp)
                }
                if (selectedChunkIds.isNotEmpty()) {
                    TextButton(onClick = { selectedChunkIds = emptySet() }) {
                        Text(stringResource(R.string.action_clear), fontSize = 12.sp)
                    }
                }
            }
        }
        
        // Chunk items
        items(chunks) { chunk ->
            val source = sources.find { it.id == chunk.sourceId }
            val isSelected = chunk.id in selectedChunkIds
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isSelected) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { 
                    selectedChunkIds = if (isSelected) selectedChunkIds - chunk.id else selectedChunkIds + chunk.id 
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { selectedChunkIds = if (it) selectedChunkIds + chunk.id else selectedChunkIds - chunk.id },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${source?.name?.take(15) ?: "?"} #${chunk.chunkIndex}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(if (chunk.status == ChunkStatus.CLEANED) "✓" else "○", fontSize = 12.sp, color = if (chunk.status == ChunkStatus.CLEANED) Color(0xFF4CAF50) else Color.Gray)
                        }
                        // Original text
                        Text(stringResource(R.string.dataset_original_label), fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            chunk.originalText.take(80) + if (chunk.originalText.length > 80) "…" else "",
                            fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                        // Cleaned text (if available)
                        if (chunk.cleanedText != null) {
                            Text(stringResource(R.string.dataset_cleaned_label), fontSize = 10.sp, color = Color(0xFF4CAF50), modifier = Modifier.padding(top = 4.dp))
                            Text(
                                chunk.cleanedText.take(80) + if (chunk.cleanedText.length > 80) "…" else "",
                                fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    IconButton(onClick = { editingChunk = chunk }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    
    editingChunk?.let { chunk ->
        var originalText by remember { mutableStateOf(chunk.originalText) }
        var cleanedText by remember { mutableStateOf(chunk.cleanedText ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingChunk = null },
            title = { 
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dataset_edit_chunk, chunk.chunkIndex))
                    // Regen cleaning button
                    TextButton(
                        onClick = {
                            // Queue regen clean for this chunk
                            DatasetForegroundService.enqueue(
                                context,
                                DatasetProcessor.Job.RegenClean(
                                    chunk.id,
                                    chunk.projectId,
                                    "",
                                    resources.getString(R.string.dataset_job_regen_clean)
                                )
                            )
                            editingChunk = null
                        }
                    ) {
                        Text(stringResource(R.string.dataset_regen_clean_btn), fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Original text
                    Text(stringResource(R.string.dataset_context_original), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = originalText,
                        onValueChange = { originalText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 8,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // Cleaned text
                    Text(stringResource(R.string.dataset_cleaned_label), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4CAF50))
                    OutlinedTextField(
                        value = cleanedText,
                        onValueChange = { cleanedText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 8,
                        textStyle = TextStyle(fontSize = 12.sp),
                        placeholder = { Text(stringResource(R.string.dataset_not_cleaned), fontSize = 12.sp) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = chunk.copy(
                        originalText = originalText,
                        cleanedText = if (cleanedText.isNotBlank()) cleanedText else null
                    )
                    onUpdateChunk(updated)
                    editingChunk = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingChunk = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    pendingDeleteChunkIds?.let { chunkIds ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChunkIds = null },
            title = { Text(stringResource(R.string.dataset_remove_chunks_title, chunkIds.size)) },
            text = { Text(stringResource(R.string.dataset_remove_chunks_message, chunkIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSelectedChunks(chunkIds)
                    selectedChunkIds = emptySet()
                    pendingDeleteChunkIds = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChunkIds = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ========== PROCESS TAB ==========
@Composable
private fun ProcessTab(
    chunks: List<DatasetChunkEntity>,
    qaList: List<DatasetQAEntity>,
    prompts: List<DatasetPromptEntity>,
    isProcessing: Boolean,
    onRunClean: (String) -> Unit,
    onRunQuestions: (String) -> Unit,
    onRunAnswers: (String) -> Unit,
    onRunRating: (String) -> Unit,
    onRunSequence: (List<DatasetProcessStage>, String, String, String, String) -> Unit
) {
    val pendingChunks = chunks.count { it.status == ChunkStatus.PENDING }
    val cleanedChunks = chunks.count { it.status == ChunkStatus.CLEANED }
    val questionsGenerated = qaList.count { it.status >= QAStatus.QUESTIONED }
    val answersGenerated = qaList.count { it.status >= QAStatus.ANSWERED }
    val rated = qaList.count { it.status == QAStatus.REVIEWED }
    val cleanPrompt = prompts.find { it.type == PromptType.CLEAN && it.isDefault }?.content ?: DEFAULT_CLEAN_PROMPT
    val questionPrompt = prompts.find { it.type == PromptType.QUESTION && it.isDefault }?.content ?: DEFAULT_QUESTION_PROMPT
    val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
    val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
    var showSequenceDialog by remember { mutableStateOf(false) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_pipeline_status), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$pendingChunks", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_pending), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$cleanedChunks", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_cleaned), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$questionsGenerated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_questions), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$answersGenerated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_answers), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$rated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_rated), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { showSequenceDialog = true },
                    enabled = chunks.isNotEmpty() && !isProcessing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dataset_run_sequence))
                }
                Button(onClick = { onRunClean(cleanPrompt) }, enabled = pendingChunks > 0 && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_clean, pendingChunks))
                }
                Button(onClick = { onRunQuestions(questionPrompt) }, enabled = cleanedChunks > 0 && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_questions, cleanedChunks))
                }
                Button(onClick = { onRunAnswers(answerPrompt) }, enabled = questionsGenerated > answersGenerated && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_answers, questionsGenerated - answersGenerated))
                }
                Button(onClick = { onRunRating(reviewPrompt) }, enabled = answersGenerated > rated && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_rating, answersGenerated - rated))
                }
            }
        }
    }

    if (showSequenceDialog) {
        DatasetRunSequenceDialog(
            pendingChunks = pendingChunks,
            cleanedChunks = cleanedChunks,
            unansweredQuestions = questionsGenerated - answersGenerated,
            unratedAnswers = answersGenerated - rated,
            isProcessing = isProcessing,
            onDismiss = { showSequenceDialog = false },
            onQueue = { stages ->
                onRunSequence(stages, cleanPrompt, questionPrompt, answerPrompt, reviewPrompt)
                showSequenceDialog = false
            }
        )
    }
}

@Composable
private fun DatasetRunSequenceDialog(
    pendingChunks: Int,
    cleanedChunks: Int,
    unansweredQuestions: Int,
    unratedAnswers: Int,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onQueue: (List<DatasetProcessStage>) -> Unit
) {
    var stageOrder by remember {
        mutableStateOf(
            listOf(
                DatasetProcessStage.CLEAN,
                DatasetProcessStage.QUESTIONS,
                DatasetProcessStage.ANSWERS,
                DatasetProcessStage.RATING
            )
        )
    }
    var selectedStages by remember { mutableStateOf(stageOrder.toSet()) }

    fun moveStage(from: Int, to: Int) {
        val updated = stageOrder.toMutableList()
        val item = updated.removeAt(from)
        updated.add(to, item)
        stageOrder = updated
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dataset_run_sequence_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.dataset_run_sequence_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stageOrder.forEachIndexed { index, stage ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = stage in selectedStages,
                                onCheckedChange = { checked ->
                                    selectedStages = if (checked) {
                                        selectedStages + stage
                                    } else {
                                        selectedStages - stage
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (stage) {
                                        DatasetProcessStage.CLEAN -> stringResource(R.string.dataset_job_clean)
                                        DatasetProcessStage.QUESTIONS -> stringResource(R.string.dataset_job_questions)
                                        DatasetProcessStage.ANSWERS -> stringResource(R.string.dataset_job_answers)
                                        DatasetProcessStage.RATING -> stringResource(R.string.dataset_job_rating)
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = when (stage) {
                                        DatasetProcessStage.CLEAN -> stringResource(R.string.dataset_sequence_clean_hint, pendingChunks)
                                        DatasetProcessStage.QUESTIONS -> stringResource(R.string.dataset_sequence_questions_hint, cleanedChunks)
                                        DatasetProcessStage.ANSWERS -> stringResource(R.string.dataset_sequence_answers_hint, maxOf(0, unansweredQuestions))
                                        DatasetProcessStage.RATING -> stringResource(R.string.dataset_sequence_rating_hint, maxOf(0, unratedAnswers))
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { moveStage(index, index - 1) },
                                enabled = index > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    stringResource(R.string.dataset_move_up),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = { moveStage(index, index + 1) },
                                enabled = index < stageOrder.size - 1,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    stringResource(R.string.dataset_move_down),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedStages.isNotEmpty() && !isProcessing,
                onClick = { onQueue(stageOrder.filter { it in selectedStages }) }
            ) {
                Text(stringResource(R.string.dataset_queue_sequence))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ========== REVIEW TAB ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewTab(
    qaList: List<DatasetQAEntity>,
    chunks: List<DatasetChunkEntity>,
    onUpdateQA: (DatasetQAEntity) -> Unit,
    onDeleteQA: (DatasetQAEntity) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    onRegenerateAnswer: (DatasetQAEntity) -> Unit,
    onRegenerateRating: (DatasetQAEntity) -> Unit,
    onRegenerateSelectedAnswers: (Set<Long>) -> Unit,
    onRegenerateSelectedRatings: (Set<Long>) -> Unit,
    onExport: (Set<Long>) -> Unit
) {
    var filter by remember { mutableStateOf("all") }
    var selectedScoreFilters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var editingQA by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showJustification by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var showContext by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var filterDropdownExpanded by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    
    val scoreOptions = (1..5).toList()
    val selectedScoresLabel = when {
        selectedScoreFilters.isEmpty() -> null
        selectedScoreFilters == (4..5).toSet() -> stringResource(R.string.dataset_filter_greater_than, 3)
        selectedScoreFilters == (3..5).toSet() -> stringResource(R.string.dataset_filter_greater_than, 2)
        selectedScoreFilters == (2..5).toSet() -> stringResource(R.string.dataset_filter_greater_than, 1)
        selectedScoreFilters == (1..5).toSet() -> stringResource(R.string.dataset_filter_greater_than, 0)
        selectedScoreFilters.size == 1 -> selectedScoreFilters.first().toString()
        else -> selectedScoreFilters.sorted().joinToString(",")
    }
    val filterLabel = when {
        filter == "unrated" -> stringResource(R.string.dataset_filter_unrated)
        filter == "answered" && selectedScoresLabel == null -> stringResource(R.string.dataset_filter_answered)
        selectedScoresLabel != null -> stringResource(R.string.dataset_filter_score, selectedScoresLabel)
        else -> stringResource(R.string.dataset_filter_all)
    }

    val filteredList = qaList.filter { qa ->
        val baseMatches = when (filter) {
            "answered" -> qa.answer != null
            "unrated" -> qa.score == null
            else -> true
        }
        val scoreMatches = selectedScoreFilters.isEmpty() ||
            qa.score?.let { it in selectedScoreFilters } == true
        baseMatches && scoreMatches
    }

    fun updateBaseFilter(newFilter: String) {
        filter = newFilter
        if (newFilter == "unrated") {
            selectedScoreFilters = emptySet()
        }
        selectedIds = emptySet()
    }

    fun updateScoreFilters(scores: Set<Int>) {
        selectedScoreFilters = scores
        if (filter == "unrated" && scores.isNotEmpty()) {
            filter = "all"
        }
        selectedIds = emptySet()
    }
    val canExport = selectedIds.isNotEmpty() || filteredList.any { (it.score ?: 0) >= 3 }
    val canActOnSelection = selectedIds.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Combined filter + actions bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter dropdown (compact)
                ExposedDropdownMenuBox(
                    expanded = filterDropdownExpanded,
                    onExpandedChange = { filterDropdownExpanded = it },
                    modifier = Modifier.width(132.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(filterLabel, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = filterDropdownExpanded,
                        onDismissRequest = { filterDropdownExpanded = false },
                        modifier = Modifier.width(220.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dataset_filter_all), fontSize = 13.sp) },
                            onClick = { updateBaseFilter("all"); filterDropdownExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dataset_filter_answered), fontSize = 13.sp) },
                            onClick = { updateBaseFilter("answered"); filterDropdownExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dataset_filter_unrated), fontSize = 13.sp) },
                            onClick = { updateBaseFilter("unrated"); filterDropdownExpanded = false }
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.dataset_filter_scores),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        scoreOptions.forEach { score ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dataset_filter_score, score.toString()), fontSize = 13.sp) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = score in selectedScoreFilters,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    updateScoreFilters(
                                        if (score in selectedScoreFilters) {
                                            selectedScoreFilters - score
                                        } else {
                                            selectedScoreFilters + score
                                        }
                                    )
                                }
                            )
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            (0..4).forEach { threshold ->
                                TextButton(
                                    onClick = { updateScoreFilters(((threshold + 1)..5).toSet()) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.dataset_filter_greater_than, threshold), fontSize = 12.sp)
                                }
                            }
                        }
                        TextButton(
                            onClick = { updateScoreFilters(emptySet()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_clear), fontSize = 12.sp)
                        }
                    }
                }
                
                // Selection badge
                Surface(shape = MaterialTheme.shapes.small, color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent) {
                    Text(
                        if (selectedIds.isEmpty()) "${filteredList.size}" else "✓${selectedIds.size}",
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                
                Spacer(Modifier.weight(1f))
                
                // Select all icon
                IconButton(onClick = { selectedIds = if (selectedIds.size == filteredList.size) emptySet() else filteredList.map { it.id }.toSet() }, modifier = Modifier.size(32.dp)) {
                    Icon(if (selectedIds.size == filteredList.size && filteredList.isNotEmpty()) Icons.Default.Check else Icons.Default.Add, stringResource(R.string.action_select), modifier = Modifier.size(20.dp))
                }
                
                // Actions dropdown
                Box {
                    FilledTonalButton(onClick = { actionsExpanded = true }, enabled = canExport || canActOnSelection) {
                        Text(stringResource(R.string.dataset_actions), fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export)) },
                            enabled = canExport,
                            onClick = { actionsExpanded = false; onExport(selectedIds) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dataset_action_regen_answers)) },
                            enabled = canActOnSelection,
                            onClick = { actionsExpanded = false; onRegenerateSelectedAnswers(selectedIds) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dataset_action_regen_ratings)) },
                            enabled = canActOnSelection,
                            onClick = { actionsExpanded = false; onRegenerateSelectedRatings(selectedIds) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            enabled = canActOnSelection,
                            onClick = { actionsExpanded = false; onDeleteSelected(selectedIds); selectedIds = emptySet() }
                        )
                    }
                }
            }
        }
        
        // Q&A items
        items(filteredList) { qa ->
            val isSelected = qa.id in selectedIds
            
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { selectedIds = if (isSelected) selectedIds - qa.id else selectedIds + qa.id }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Question row
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { selectedIds = if (it) selectedIds + qa.id else selectedIds - qa.id },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(qa.question, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f), lineHeight = 16.sp)
                        // Score badge
                        Surface(shape = MaterialTheme.shapes.small, color = when(qa.score) { 5 -> Color(0xFF4CAF50); 4 -> Color(0xFF8BC34A); 3 -> Color(0xFFFFC107); 2 -> Color(0xFFFF9800); 1 -> Color(0xFFF44336); else -> Color.Gray }.copy(alpha = 0.2f)) {
                            Text("${qa.score ?: "?"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    
                    // Answer
                    if (qa.answer != null) {
                        Text(qa.answer.take(150) + if (qa.answer.length > 150) "…" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 4.dp), lineHeight = 15.sp, maxLines = 3)
                    }
                    
                    // Actions row (compact)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Context view button (shows text sent to LLM)
                         IconButton(onClick = { showContext = qa }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Info, stringResource(R.string.dataset_context_sent), modifier = Modifier.size(16.dp), tint = Color.Gray)
                        }
                        if (qa.scoreJustification != null) {
                            IconButton(onClick = { showJustification = qa }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Star, stringResource(R.string.dataset_score_justification), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { onRegenerateAnswer(qa) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.dataset_job_regen_answer), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                        if (qa.answer != null) {
                            IconButton(onClick = { onRegenerateRating(qa) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Star, stringResource(R.string.dataset_job_regen_rating), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        IconButton(onClick = { editingQA = qa }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDeleteQA(qa) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_delete), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    
    // Edit dialog
    editingQA?.let { qa ->
        var question by remember { mutableStateOf(qa.question) }
        var answer by remember { mutableStateOf(qa.answer ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingQA = null },
            title = { Text(stringResource(R.string.dataset_edit_qa)) },
            text = {
                Column {
                    OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text(stringResource(R.string.dataset_qa_question)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text(stringResource(R.string.dataset_qa_answer)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateQA(qa.copy(question = question, answer = answer))
                    editingQA = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingQA = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    
    // Justification dialog
    showJustification?.let { qa ->
        AlertDialog(
            onDismissRequest = { showJustification = null },
            title = { Text(stringResource(R.string.dataset_score_justification)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.dataset_status_rated) + ": ${qa.score}/5", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(qa.scoreJustification ?: stringResource(R.string.dataset_no_justification), fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showJustification = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
    
    // Context dialog - shows the text sent to LLM
    showContext?.let { qa ->
        val chunk = chunks.find { it.id == qa.chunkId }
        AlertDialog(
            onDismissRequest = { showContext = null },
            title = { Text(stringResource(R.string.dataset_context_sent)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("📝 " + stringResource(R.string.dataset_qa_question) + ":", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(qa.question, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    
                    Text(stringResource(R.string.dataset_context_raw), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            chunk?.cleanedText ?: chunk?.originalText ?: stringResource(R.string.dataset_no_context),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    
                    if (chunk?.cleanedText != null && chunk.originalText != chunk.cleanedText) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.dataset_context_original), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                chunk.originalText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp),
                                color = Color.Gray
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContext = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

// ========== QUEUE TAB ==========
@Composable
fun QueueTab(
    activeJob: DatasetProcessor.Job?,
    jobQueue: List<DatasetProcessor.Job>,
    onCancelAll: () -> Unit,
    onRemoveJob: (Int) -> Unit,
    onMoveJobUp: (Int) -> Unit,
    onMoveJobDown: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Queue header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dataset_pending_jobs, jobQueue.size), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (jobQueue.isNotEmpty()) {
                    TextButton(onClick = onCancelAll, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.dataset_clear_all))
                    }
                }
            }
        }

        activeJob?.let { job ->
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dataset_active_job_title), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(job.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                stringResource(R.string.dataset_active_job_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Empty state
        if (jobQueue.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✓", fontSize = 40.sp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.dataset_no_jobs), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(stringResource(R.string.dataset_no_jobs_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Pending jobs list
        itemsIndexed(jobQueue) { index, job ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Index badge
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    
                    // Job name
                    Column(modifier = Modifier.weight(1f)) {
                        Text(job.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    
                    // Move up
                    IconButton(
                        onClick = { onMoveJobUp(index) },
                        enabled = index > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            stringResource(R.string.dataset_move_up),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    // Move down
                    IconButton(
                        onClick = { onMoveJobDown(index) },
                        enabled = index < jobQueue.size - 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            stringResource(R.string.dataset_move_down),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    // Remove
                    IconButton(
                        onClick = { onRemoveJob(index) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ========== SETTINGS TAB ==========
private fun mergeDatasetOllamaModels(selectedModel: String, models: List<String>): List<String> {
    val normalizedSelected = selectedModel.trim()
    if (normalizedSelected.isBlank() || models.contains(normalizedSelected)) return models
    return listOf(normalizedSelected) + models
}

@Composable
private fun DatasetBackendChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SettingsTab(
    project: DatasetProjectEntity?,
    prompts: List<DatasetPromptEntity>,
    dao: DatasetDao,
    onUpdateProject: (DatasetProjectEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    
    var backend by remember(project) {
        mutableStateOf(normalizeDatasetBackend(project?.backend))
    }
    var serverUrl by remember(project) {
        mutableStateOf(project?.serverUrl ?: SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL)
    }
    var ollamaUrl by remember(project) {
        mutableStateOf(project?.ollamaUrl ?: OllamaService.DEFAULT_URL)
    }
    var ollamaModel by remember(project) { mutableStateOf(project?.ollamaModel.orEmpty()) }
    var ollamaNumCtx by remember(project) { mutableStateOf(project?.ollamaNumCtx ?: 4096) }
    var ollamaThreads by remember(project) { mutableStateOf(project?.ollamaThreads ?: 4) }
    var ollamaMmap by remember(project) { mutableStateOf(project?.ollamaMmap ?: false) }
    var temperature by remember(project) { mutableStateOf(project?.temperature ?: 0.7f) }
    var maxTokens by remember(project) { mutableStateOf(project?.maxTokens ?: 512) }
    var useCoT by remember(project) { mutableStateOf(project?.useCoT ?: false) }
    var finalLanguage by remember(project) { mutableStateOf(project?.finalLanguage.orEmpty()) }
    var availableOllamaModels by remember(project?.ollamaModel) {
        mutableStateOf(project?.ollamaModel?.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList())
    }
    var showModelMenu by remember { mutableStateOf(false) }
    var isRefreshingMetadata by remember { mutableStateOf(false) }
    var metadataMessage by remember(project) { mutableStateOf<String?>(null) }
    var llamaServerModelLabel by remember(project) { mutableStateOf<String?>(null) }
    var llamaServerContextLabel by remember(project) { mutableStateOf<String?>(null) }
    
    // Prompt editor state
    var editingPromptType by remember { mutableStateOf<PromptType?>(null) }
    var editingPromptContent by remember { mutableStateOf("") }
    var projectName by remember(project) { mutableStateOf(project?.name ?: "") }
    val normalizedBackend = SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
    val currentUrl = if (SettingsRepository.usesOpenAiChatBackend(normalizedBackend)) serverUrl else ollamaUrl
    val defaultUrl = when (normalizedBackend) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL
        else -> OllamaService.DEFAULT_URL
    }
    val visibleOllamaModels = mergeDatasetOllamaModels(ollamaModel, availableOllamaModels)

    fun refreshMetadata() {
        if (currentUrl.isBlank()) return
        scope.launch {
            isRefreshingMetadata = true
            metadataMessage = null
            RemoteSummaryClientFactory.fromConfig(
                RemoteSummaryBackendConfig(
                    backend = backend,
                    baseUrl = currentUrl.trim(),
                    model = ollamaModel.trim().ifBlank { null },
                    timeoutMinutes = 1
                )
            ).fetchMetadata()
                .onSuccess { metadata ->
                    if (SettingsRepository.requiresSelectedRemoteModel(metadata.backend)) {
                        availableOllamaModels = mergeDatasetOllamaModels(ollamaModel, metadata.availableModels)
                        metadataMessage = if (SettingsRepository.isLlamaSwapBackend(metadata.backend)) {
                            resources.getString(R.string.pdf_metadata_llama_swap_loaded, metadata.availableModels.size)
                        } else {
                            resources.getString(R.string.pdf_metadata_ollama_loaded, metadata.availableModels.size)
                        }
                    } else {
                        llamaServerModelLabel = metadata.serverModelLabel
                        llamaServerContextLabel = metadata.serverContextLabel
                        metadataMessage = resources.getString(
                            R.string.pdf_metadata_llama_loaded,
                            metadata.serverModelLabel ?: resources.getString(R.string.pdf_server_value_unavailable),
                            metadata.serverContextLabel ?: resources.getString(R.string.pdf_server_value_unavailable)
                        )
                    }
                }
                .onFailure {
                    metadataMessage = resources.getString(
                        R.string.pdf_metadata_refresh_failed,
                        it.message ?: resources.getString(R.string.error_generic)
                    )
                }
            isRefreshingMetadata = false
        }
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        
        // Project name
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_project_name), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text(stringResource(R.string.dataset_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (projectName != project?.name) {
                                IconButton(onClick = {
                                    project?.let { onUpdateProject(it.copy(name = projectName)) }
                                }) {
                                    Icon(Icons.Default.Check, stringResource(R.string.action_save), tint = Color(0xFF4CAF50))
                                }
                            }
                        }
                    )
                }
            }
        }
        
        // Server settings
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_server_settings), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.dataset_server_desc), fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DatasetBackendChoiceButton(
                            label = stringResource(R.string.pdf_backend_ollama),
                            selected = normalizedBackend == SettingsRepository.PDF_BACKEND_OLLAMA,
                            onClick = { backend = SettingsRepository.PDF_BACKEND_OLLAMA },
                            modifier = Modifier.weight(1f)
                        )
                        DatasetBackendChoiceButton(
                            label = stringResource(R.string.pdf_backend_llama_server),
                            selected = normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                            onClick = {
                                backend = SettingsRepository.PDF_BACKEND_LLAMA_SERVER
                                if (serverUrl.isBlank() || serverUrl == SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL) {
                                    serverUrl = SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DatasetBackendChoiceButton(
                            label = stringResource(R.string.pdf_backend_llama_swap),
                            selected = normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
                            onClick = {
                                backend = SettingsRepository.PDF_BACKEND_LLAMA_SWAP
                                if (serverUrl.isBlank() || serverUrl == SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL) {
                                    serverUrl = SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = currentUrl,
                        onValueChange = {
                            if (SettingsRepository.usesOpenAiChatBackend(normalizedBackend)) {
                                serverUrl = it
                            } else {
                                ollamaUrl = it
                            }
                        },
                        label = {
                            Text(
                                when (normalizedBackend) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.pdf_llama_server_url_label)
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.pdf_llama_swap_url_label)
                                    else -> stringResource(R.string.pdf_ollama_url_label)
                                }
                            )
                        },
                        placeholder = { Text(stringResource(R.string.dataset_backend_default_url, defaultUrl)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        stringResource(R.string.dataset_backend_default_url, defaultUrl),
                        fontSize = 11.sp, 
                        color = Color.Gray
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = ::refreshMetadata,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = currentUrl.isNotBlank() && !isRefreshingMetadata
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRefreshingMetadata) {
                                stringResource(R.string.pdf_refreshing_metadata)
                            } else {
                                stringResource(R.string.pdf_refresh_backend_info)
                            }
                        )
                    }

                    metadataMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (SettingsRepository.requiresSelectedRemoteModel(normalizedBackend)) {
                        Text(
                            stringResource(
                                if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
                                    R.string.dataset_llama_swap_model_label
                                } else {
                                    R.string.dataset_ollama_model_label
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        Box {
                            OutlinedButton(
                                onClick = { showModelMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = visibleOllamaModels.isNotEmpty()
                            ) {
                                Text(
                                    text = ollamaModel.ifBlank {
                                        resources.getString(
                                            if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
                                                R.string.pdf_select_llama_swap_model
                                            } else {
                                                R.string.pdf_select_ollama_model
                                            }
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                if (visibleOllamaModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_no_remote_models_loaded)) },
                                        onClick = { showModelMenu = false }
                                    )
                                } else {
                                    visibleOllamaModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                ollamaModel = model
                                                showModelMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (normalizedBackend == SettingsRepository.PDF_BACKEND_OLLAMA) {
                            Spacer(Modifier.height(12.dp))

                            IntSliderWithInput(
                                value = ollamaNumCtx,
                                onValueChange = { ollamaNumCtx = it },
                                valueRange = 512..32768,
                                label = stringResource(R.string.tama_chat_context_size_label)
                            )

                            IntSliderWithInput(
                                value = ollamaThreads,
                                onValueChange = { ollamaThreads = it },
                                valueRange = 1..16,
                                label = stringResource(R.string.ollama_threads_label, ollamaThreads)
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.ollama_mmap_label))
                                    Text(
                                        stringResource(R.string.ollama_mmap_desc),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Switch(checked = ollamaMmap, onCheckedChange = { ollamaMmap = it })
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.pdf_llama_server_model_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            llamaServerModelLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            stringResource(R.string.pdf_llama_server_context_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            llamaServerContextLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dataset_llama_server_dashboard_hint),
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    IntSliderWithInput(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        valueRange = 64..4096,
                        label = stringResource(R.string.dataset_max_tokens)
                    )
                    
                    SliderWithInput(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        label = stringResource(R.string.action_temperature)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dataset_cot_label))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = useCoT, onCheckedChange = { useCoT = it })
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = finalLanguage,
                        onValueChange = { finalLanguage = it },
                        label = { Text(stringResource(R.string.dataset_final_language_label)) },
                        placeholder = { Text(stringResource(R.string.dataset_final_language_placeholder)) },
                        supportingText = { Text(stringResource(R.string.dataset_final_language_help)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            project?.let {
                                onUpdateProject(it.copy(
                                    backend = backend,
                                    serverUrl = serverUrl.trim(),
                                    ollamaUrl = ollamaUrl.trim(),
                                    ollamaModel = ollamaModel.trim().ifBlank { null },
                                    ollamaNumCtx = ollamaNumCtx,
                                    ollamaThreads = ollamaThreads,
                                    ollamaMmap = ollamaMmap,
                                    temperature = temperature,
                                    maxTokens = maxTokens,
                                    useCoT = useCoT,
                                    finalLanguage = finalLanguage.trim()
                                ))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_save_settings))
                    }
                }
            }
        }
        
        // Prompts Editor
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_prompts_title), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    
                    PromptType.entries.forEach { type ->
                        val savedPrompt = prompts.find { it.type == type && it.isDefault }
                        val defaultPrompt = when (type) {
                            PromptType.CLEAN -> DEFAULT_CLEAN_PROMPT
                            PromptType.QUESTION -> DEFAULT_QUESTION_PROMPT
                            PromptType.ANSWER -> DEFAULT_ANSWER_PROMPT
                            PromptType.REVIEW -> DEFAULT_REVIEW_PROMPT
                        }
                        val currentPrompt = savedPrompt?.content ?: defaultPrompt
                        
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(type.name, fontWeight = FontWeight.Medium)
                                    Row {
                                        TextButton(onClick = {
                                            editingPromptType = type
                                            editingPromptContent = currentPrompt
                                        }) {
                                            Text(stringResource(R.string.action_edit), fontSize = 12.sp)
                                        }
                                        if (savedPrompt != null) {
                                            TextButton(onClick = {
                                                scope.launch { dao.deletePrompt(savedPrompt) }
                                            }) {
                                                Text(stringResource(R.string.action_reset), fontSize = 12.sp, color = Color.Red)
                                            }
                                        }
                                    }
                                }
                                Text(
                                    currentPrompt.take(100) + if (currentPrompt.length > 100) "..." else "",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Prompt edit dialog
    editingPromptType?.let { type ->
        AlertDialog(
            onDismissRequest = { editingPromptType = null },
            title = { Text(stringResource(R.string.dataset_edit_prompt, type.name)) },
            text = {
                OutlinedTextField(
                    value = editingPromptContent,
                    onValueChange = { editingPromptContent = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    maxLines = 20
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val existing = prompts.find { it.type == type && it.isDefault }
                    if (existing != null) {
                        scope.launch { dao.updatePrompt(existing.copy(content = editingPromptContent)) }
                    } else {
                        scope.launch {
                            dao.insertPrompt(DatasetPromptEntity(
                                name = "${type.name} Custom",
                                type = type,
                                content = editingPromptContent,
                                isDefault = true
                            ))
                        }
                    }
                    editingPromptType = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingPromptType = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// Default prompts
const val DEFAULT_CLEAN_PROMPT = """Clean this text aggressively for dataset training.

Remove OCR mistakes, broken words, duplicate fragments, page numbers, headers, footers, navigation text, captions without useful meaning, watermarks, boilerplate, citation clutter, malformed tables, stray punctuation, and formatting artifacts.
Preserve factual content, terminology, names, dates, numbers, relationships, and the original meaning. Do not summarize, invent, or add commentary.
Return only the cleaned text.

Previous context: {prev_chunk_50%}
Text to clean: {chunk}
Next context: {next_chunk_50%}

Cleaned text:"""

const val DEFAULT_QUESTION_PROMPT = """Generate exactly 1 useful question that is directly answerable from the meaningful content in this text.

{final_language_instruction}

Ignore leaked cleanup artifacts, formatting leftovers, page headers, footers, citation noise, navigation text, and unrelated fragments.
Prefer specific factual, conceptual, or relationship questions that reflect the central information in the text.
Do not ask about the text, passage, document, chunk, authoring process, or source. Do not include explanations.
Return one question only.

Previous: {prev_chunk_50%}
Text: {chunk}
Next: {next_chunk_50%}

Question:"""

const val DEFAULT_ANSWER_PROMPT = """Answer the question naturally and confidently, as if the knowledge is your own.

{final_language_instruction}

Use only the information contained in the provided context. Do not use outside knowledge, assumptions, or speculation.
Never mention or imply that the answer comes from a text, context, passage, excerpt, document, source, or provided material.
If the provided context does not contain enough information to answer the question, output exactly:
there is not enough information in the provided text

{if CoT: Think step by step before answering.}

Context:
{context}

Question: {question}

Answer:"""

const val DEFAULT_REVIEW_PROMPT = """You are a strict dataset quality reviewer. Evaluate whether this Q&A pair is useful for training.

{final_language_instruction}

Use only the context as ground truth.
Penalize hallucinations, unsupported details, vague or trivial questions, answers that mention the context/text/source, cleanup artifacts, formatting leakage, irrelevant content, partial answers, and unclear wording.

Score from 1 to 5:
5 = Excellent: specific, useful, fully supported, complete, natural, and source-free.
4 = Good: supported and useful with only minor clarity or completeness issues.
3 = Acceptable: mostly supported but somewhat shallow, incomplete, or awkward.
2 = Poor: weakly related, vague, artifact-contaminated, incomplete, or partly unsupported.
1 = Bad: wrong, hallucinated, unanswerable from context, irrelevant, or explicitly source-referential.

Your response must include brief reasoning and end with exactly "FINAL_SCORE: X" where X is 1, 2, 3, 4, or 5.

Question: {question}
Answer: {answer}
Context: {context}

Justification:
FINAL_SCORE:"""
