package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.BenchmarkService
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.launch

/**
 * Benchmark screen to test optimal thread count for LLM inference.
 * Shows saved results and allows running new benchmarks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val benchmarkService = remember { BenchmarkService(context) }
    val scope = rememberCoroutineScope()
    
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val llmModels by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    
    // Load saved results for current model
    val savedResults by selectedModelPath?.let { path ->
        db.benchmarkDao().getLatestRunResultsForModel(path).collectAsState(initial = emptyList())
    } ?: remember { mutableStateOf(emptyList<BenchmarkResult>()) }
    
    var runName by remember { mutableStateOf("") }
    var minThreads by remember { mutableIntStateOf(2) }
    var maxThreads by remember { mutableIntStateOf(8) }
    var promptTokens by remember { mutableIntStateOf(512) }
    var genTokens by remember { mutableIntStateOf(128) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var queueModelPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var queueSelectionInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(selectedModelPath, llmModels) {
        if (!queueSelectionInitialized && llmModels.isNotEmpty()) {
            queueModelPaths = selectedModelPath?.let { setOf(it) }
                ?: llmModels.firstOrNull()?.path?.let { setOf(it) }
                ?: emptySet()
            queueSelectionInitialized = true
        }
    }
    
    // Use global state from BenchmarkService (persists across navigation)
    val isRunning by BenchmarkService.isRunning.collectAsState()
    val progressText by BenchmarkService.progressText.collectAsState()
    val progress by BenchmarkService.progress.collectAsState()
    val runningModelPath by BenchmarkService.runningModelPath.collectAsState()
    val currentRunStartedAt by BenchmarkService.currentRunStartedAt.collectAsState()
    
    // Display results from database (auto-updates as benchmark runs)
    val isSelectedModelRunning = isRunning && runningModelPath == selectedModelPath
    val displayResults = if (isSelectedModelRunning && currentRunStartedAt != null) {
        savedResults.filter { it.runStartedAt == currentRunStartedAt }
    } else {
        savedResults
    }
    val queuedModels = llmModels.filter { it.path in queueModelPaths }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.benchmark_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.BenchmarkHistory.route) }) {
                        Icon(Icons.Default.History, stringResource(R.string.benchmark_history_title))
                    }
                    if (displayResults.isNotEmpty() && !isRunning) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.benchmark_delete_title))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Model Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.benchmark_model_label), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.benchmark_no_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedModelPath == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.benchmark_select_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            // Queue Models
            item {
                BenchmarkQueueSelectionCard(
                    models = llmModels,
                    selectedPaths = queueModelPaths,
                    onSelectionChange = { queueModelPaths = it },
                    enabled = !isRunning
                )
            }

            // Results Table (shows live or saved)
            if (displayResults.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (isSelectedModelRunning) stringResource(R.string.benchmark_live_results) else stringResource(R.string.benchmark_saved_results),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.benchmark_threads), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.benchmark_prompt_ts), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.benchmark_gen_ts), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // Find best results
                            val bestGen = displayResults.maxByOrNull { it.genTokensPerSecond }
                            
                            displayResults.forEach { result ->
                                val isBest = result == bestGen
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${result.threads}${if (isBest) " ⭐" else ""}",
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        stringResource(R.string.benchmark_speed_value, String.format("%.1f", result.promptTokensPerSecond)),
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(R.string.benchmark_speed_value, String.format("%.1f", result.genTokensPerSecond)),
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            
                            if (bestGen != null && !isRunning) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            stringResource(R.string.benchmark_optimal, bestGen.threads),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            stringResource(R.string.benchmark_gen_speed, String.format("%.1f", bestGen.genTokensPerSecond)),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Configuration
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.benchmark_new_title), fontWeight = FontWeight.Bold)
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = runName,
                            onValueChange = { runName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.benchmark_run_name)) },
                            placeholder = { Text(stringResource(R.string.benchmark_run_name_placeholder)) },
                            singleLine = true,
                            enabled = !isRunning
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Min Threads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_min_threads))
                            Text("$minThreads", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = minThreads.toFloat(),
                            onValueChange = {
                                val nextMin = it.toInt()
                                minThreads = nextMin
                                if (maxThreads < nextMin) {
                                    maxThreads = nextMin
                                }
                            },
                            valueRange = 1f..12f,
                            steps = 10,
                            enabled = !isRunning
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Max Threads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_max_threads))
                            Text("$maxThreads", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = maxThreads.toFloat(),
                            onValueChange = {
                                val nextMax = it.toInt()
                                maxThreads = nextMax
                                if (minThreads > nextMax) {
                                    minThreads = nextMax
                                }
                            },
                            valueRange = 1f..12f,
                            steps = 10,
                            enabled = !isRunning
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Prompt tokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_prompt_tokens))
                            Text("$promptTokens", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = promptTokens.toFloat(),
                            onValueChange = { promptTokens = it.toInt() },
                            valueRange = 128f..2048f,
                            steps = 14,
                            enabled = !isRunning
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Gen tokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_gen_tokens))
                            Text("$genTokens", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = genTokens.toFloat(),
                            onValueChange = { genTokens = it.toInt() },
                            valueRange = 32f..512f,
                            steps = 14,
                            enabled = !isRunning
                        )
                    }
                }
            }
            
            // Progress
            if (isRunning) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(progressText, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            
            // Run/Stop Buttons
            item {
                if (isRunning) {
                    // Stop Button
                    Button(
                        onClick = {
                            BenchmarkService.cancel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.benchmark_stop))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Run Button
                        Button(
                            onClick = {
                                val modelPath = selectedModelPath ?: return@Button
                                benchmarkService.startBenchmark(
                                    modelPath = modelPath,
                                    minThreads = minThreads,
                                    maxThreads = maxThreads,
                                    promptTokens = promptTokens,
                                    genTokens = genTokens,
                                    runName = runName
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedModelPath != null,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.benchmark_run_range, minThreads, maxThreads))
                        }

                        OutlinedButton(
                            onClick = {
                                benchmarkService.startBenchmarkQueue(
                                    models = queuedModels.map { model ->
                                        BenchmarkService.QueuedModel(
                                            modelPath = model.path,
                                            modelName = model.filename
                                        )
                                    },
                                    minThreads = minThreads,
                                    maxThreads = maxThreads,
                                    promptTokens = promptTokens,
                                    genTokens = genTokens,
                                    runName = runName
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = queuedModels.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.QueuePlayNext, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.benchmark_queue_run, queuedModels.size))
                        }
                    }
                }
            }
            
            // Info
            item {
                Text(
                    stringResource(R.string.benchmark_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.benchmark_delete_title)) },
            text = { Text(stringResource(R.string.benchmark_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            selectedModelPath?.let { path ->
                                db.benchmarkDao().deleteResultsForModel(path)
                            }
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun BenchmarkQueueSelectionCard(
    models: List<ModelEntity>,
    selectedPaths: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    enabled: Boolean
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedCount = models.count { it.path in selectedPaths }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.benchmark_queue_title), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.benchmark_queue_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && models.isNotEmpty()
            ) {
                Icon(Icons.Default.Checklist, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (models.isEmpty()) {
                        stringResource(R.string.benchmark_queue_no_models)
                    } else {
                        stringResource(R.string.benchmark_queue_selected_count, selectedCount)
                    }
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.benchmark_queue_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = { onSelectionChange(models.map { it.path }.toSet()) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.benchmark_queue_select_all))
                        }
                        TextButton(
                            onClick = { onSelectionChange(emptySet()) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.benchmark_queue_clear))
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(models, key = { it.path }) { model ->
                            val checked = model.path in selectedPaths
                            TextButton(
                                onClick = {
                                    onSelectionChange(
                                        if (checked) {
                                            selectedPaths - model.path
                                        } else {
                                            selectedPaths + model.path
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    model.filename,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
}
