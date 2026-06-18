package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.data.db.TestedModel
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private enum class BenchmarkCompareMetric {
    Generation,
    Prompt
}

@Composable
fun BenchmarkHistoryScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val allResults by db.benchmarkDao().getAllResults().collectAsState(initial = emptyList())
    val testedModels by db.benchmarkDao().getTestedModels().collectAsState(initial = emptyList())
    val runSummaries = remember(allResults) { allResults.toBenchmarkRunSummaries() }
    
    var firstModelPath by remember { mutableStateOf<String?>(null) }
    var secondModelPath by remember { mutableStateOf<String?>(null) }
    var filterModelPath by remember { mutableStateOf<String?>(null) }
    var compareMetric by remember { mutableStateOf(BenchmarkCompareMetric.Generation) }
    var renameTarget by remember { mutableStateOf<BenchmarkRunSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<BenchmarkRunSummary?>(null) }
    var selectedChartRunKeys by remember { mutableStateOf<Set<BenchmarkRunKey>>(emptySet()) }
    var chartSelectionInitialized by remember { mutableStateOf(false) }
    val filteredRunSummaries = remember(runSummaries, filterModelPath) {
        filterModelPath?.let { path -> runSummaries.filter { it.modelPath == path } } ?: runSummaries
    }
    
    LaunchedEffect(testedModels) {
        val paths = testedModels.map { it.modelPath }
        if (firstModelPath !in paths) {
            firstModelPath = paths.firstOrNull()
        }
        if (secondModelPath !in paths || secondModelPath == firstModelPath) {
            secondModelPath = paths.firstOrNull { it != firstModelPath }
        }
        if (filterModelPath != null && filterModelPath !in paths) {
            filterModelPath = null
        }
    }

    LaunchedEffect(runSummaries) {
        val availableKeys = runSummaries.map { it.chartKey }.toSet()
        selectedChartRunKeys = selectedChartRunKeys.intersect(availableKeys)
        if (!chartSelectionInitialized && runSummaries.isNotEmpty()) {
            selectedChartRunKeys = runSummaries.take(6).map { it.chartKey }.toSet()
            chartSelectionInitialized = true
        }
    }
    
    AppScreenScaffold(
        title = stringResource(R.string.benchmark_history_title),
        subtitle = stringResource(R.string.benchmark_history_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppChromeDefaults.ScreenPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = AppChromeDefaults.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
        ) {
            item {
                BenchmarkComparisonCard(
                    models = testedModels,
                    allResults = allResults,
                    firstModelPath = firstModelPath,
                    secondModelPath = secondModelPath,
                    metric = compareMetric,
                    onFirstModelSelected = { firstModelPath = it },
                    onSecondModelSelected = { secondModelPath = it },
                    onMetricSelected = { compareMetric = it }
                )
            }
            
            item {
                BenchmarkHistoryFilter(
                    models = testedModels,
                    selectedModelPath = filterModelPath,
                    onSelected = { filterModelPath = it }
                )
            }

            item {
                BenchmarkChartsCard(
                    runs = filteredRunSummaries.map { it.toChartRun() },
                    selectedRunKeys = selectedChartRunKeys,
                    onSelectVisible = {
                        selectedChartRunKeys = selectedChartRunKeys + filteredRunSummaries.map { it.chartKey }
                    },
                    onClearSelection = { selectedChartRunKeys = emptySet() }
                )
            }
            
            item {
                Text(
                    text = stringResource(R.string.benchmark_history_runs_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (runSummaries.isEmpty()) {
                item {
                    AppSectionCard {
                        Text(
                            text = stringResource(R.string.benchmark_history_empty),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(R.string.benchmark_history_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredRunSummaries.isEmpty()) {
                item {
                    AppSectionCard {
                        Text(
                            text = stringResource(R.string.benchmark_history_empty_filter),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(R.string.benchmark_history_empty_filter_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = filteredRunSummaries,
                    key = { "${it.modelPath}:${it.runStartedAt}" }
                ) { summary ->
                    BenchmarkRunCard(
                        summary = summary,
                        isSelectedForCharts = summary.chartKey in selectedChartRunKeys,
                        onChartSelectionChange = { checked ->
                            selectedChartRunKeys = if (checked) {
                                selectedChartRunKeys + summary.chartKey
                            } else {
                                selectedChartRunKeys - summary.chartKey
                            }
                        },
                        onRename = {
                            renameTarget = summary
                            renameText = summary.runName
                        },
                        onDelete = { deleteTarget = summary }
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.benchmark_rename_run_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.benchmark_rename_run_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.benchmarkDao().renameRun(target.modelPath, target.runStartedAt, renameText.trim())
                        }
                        renameTarget = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.benchmark_delete_run_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.benchmark_delete_run_desc,
                        target.runName.ifBlank { stringResource(R.string.benchmark_untitled_run) }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.benchmarkDao().deleteRun(target.modelPath, target.runStartedAt)
                        }
                        deleteTarget = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun BenchmarkHistoryFilter(
    models: List<TestedModel>,
    selectedModelPath: String?,
    onSelected: (String?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = models.firstOrNull { it.modelPath == selectedModelPath }?.modelName
        ?: stringResource(R.string.benchmark_filter_all_models)

    AppSectionCard {
        Text(
            text = stringResource(R.string.benchmark_filter_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = models.isNotEmpty()
        ) {
            Icon(Icons.Default.FilterList, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.benchmark_filter_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    item {
                        TextButton(
                            onClick = {
                                onSelected(null)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.benchmark_filter_all_models),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    items(models, key = { it.modelPath }) { model ->
                        TextButton(
                            onClick = {
                                onSelected(model.modelPath)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = model.modelName,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun BenchmarkComparisonCard(
    models: List<TestedModel>,
    allResults: List<BenchmarkResult>,
    firstModelPath: String?,
    secondModelPath: String?,
    metric: BenchmarkCompareMetric,
    onFirstModelSelected: (String) -> Unit,
    onSecondModelSelected: (String) -> Unit,
    onMetricSelected: (BenchmarkCompareMetric) -> Unit
) {
    val firstModel = models.firstOrNull { it.modelPath == firstModelPath }
    val secondModel = models.firstOrNull { it.modelPath == secondModelPath }
    
    AppSectionCard {
        Text(
            text = stringResource(R.string.benchmark_compare_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = stringResource(R.string.benchmark_compare_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        BenchmarkModelSelector(
            label = stringResource(R.string.benchmark_compare_model_a),
            models = models,
            selectedModel = firstModel,
            onSelected = onFirstModelSelected
        )
        BenchmarkModelSelector(
            label = stringResource(R.string.benchmark_compare_model_b),
            models = models,
            selectedModel = secondModel,
            onSelected = onSecondModelSelected
        )
        BenchmarkMetricSelector(
            selectedMetric = metric,
            onSelected = onMetricSelected
        )
        
        when {
            models.size < 2 -> BenchmarkHelperText(text = stringResource(R.string.benchmark_compare_need_two_models))
            firstModel == null || secondModel == null -> BenchmarkHelperText(text = stringResource(R.string.benchmark_compare_select_two))
            firstModel.modelPath == secondModel.modelPath -> BenchmarkHelperText(text = stringResource(R.string.benchmark_compare_same_model))
            else -> BenchmarkComparisonDetails(
                firstModel = firstModel,
                secondModel = secondModel,
                allResults = allResults,
                metric = metric
            )
        }
    }
}

@Composable
private fun BenchmarkMetricSelector(
    selectedMetric: BenchmarkCompareMetric,
    onSelected: (BenchmarkCompareMetric) -> Unit
) {
    Text(
        text = stringResource(R.string.benchmark_compare_metric),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedMetric == BenchmarkCompareMetric.Generation,
            onClick = { onSelected(BenchmarkCompareMetric.Generation) },
            label = { Text(stringResource(R.string.benchmark_metric_generation)) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selectedMetric == BenchmarkCompareMetric.Prompt,
            onClick = { onSelected(BenchmarkCompareMetric.Prompt) },
            label = { Text(stringResource(R.string.benchmark_metric_prompt)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BenchmarkModelSelector(
    label: String,
    models: List<TestedModel>,
    selectedModel: TestedModel?,
    onSelected: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    
    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = models.isNotEmpty()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedModel?.modelName ?: stringResource(R.string.benchmark_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }
    
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(models, key = { it.modelPath }) { model ->
                        TextButton(
                            onClick = {
                                onSelected(model.modelPath)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = model.modelName,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun BenchmarkComparisonDetails(
    firstModel: TestedModel,
    secondModel: TestedModel,
    allResults: List<BenchmarkResult>,
    metric: BenchmarkCompareMetric
) {
    val firstResults = allResults.filter { it.modelPath == firstModel.modelPath }
    val secondResults = allResults.filter { it.modelPath == secondModel.modelPath }
    val firstBest = firstResults.maxByOrNull { it.metricSpeed(metric) }
    val secondBest = secondResults.maxByOrNull { it.metricSpeed(metric) }
    val metricLabel = metric.label()
    
    if (firstBest == null || secondBest == null) {
        BenchmarkHelperText(text = stringResource(R.string.benchmark_compare_select_two))
        return
    }
    
    HorizontalDivider()
    Text(
        text = stringResource(R.string.benchmark_compare_best_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    )
    BenchmarkBestResultLine(label = firstModel.modelName, result = firstBest, metric = metric)
    BenchmarkBestResultLine(label = secondModel.modelName, result = secondBest, metric = metric)
    
    val speedDifference = firstBest.metricSpeed(metric) - secondBest.metricSpeed(metric)
    val fasterModel = if (speedDifference > 0f) firstModel else secondModel
    val slowerSpeed = if (speedDifference > 0f) secondBest.metricSpeed(metric) else firstBest.metricSpeed(metric)
    val percentDifference = if (slowerSpeed > 0f) abs(speedDifference) / slowerSpeed * 100f else 0f
    
    Text(
        text = if (abs(speedDifference) < 0.05f) {
            stringResource(R.string.benchmark_compare_tie_metric, metricLabel)
        } else {
            stringResource(
                R.string.benchmark_compare_faster_metric,
                fasterModel.modelName,
                formatBenchmarkNumber(abs(speedDifference)),
                formatBenchmarkNumber(percentDifference),
                metricLabel
            )
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    
    val firstByThread = firstResults.bestResultByThread(metric)
    val secondByThread = secondResults.bestResultByThread(metric)
    val commonThreads = firstByThread.keys.intersect(secondByThread.keys).sorted()
    
    HorizontalDivider()
    Text(
        text = stringResource(R.string.benchmark_compare_common_threads),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    )
    
    if (commonThreads.isEmpty()) {
        BenchmarkHelperText(text = stringResource(R.string.benchmark_compare_no_overlap))
    } else {
        BenchmarkComparisonHeader()
        commonThreads.forEach { threads ->
            val firstResult = firstByThread.getValue(threads)
            val secondResult = secondByThread.getValue(threads)
            BenchmarkComparisonRow(
                threads = threads,
                firstSpeed = firstResult.metricSpeed(metric),
                secondSpeed = secondResult.metricSpeed(metric)
            )
        }
    }
}

@Composable
private fun BenchmarkBestResultLine(
    label: String,
    result: BenchmarkResult,
    metric: BenchmarkCompareMetric
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                R.string.benchmark_best_result_detail,
                stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(result.metricSpeed(metric))),
                result.threads,
                result.runName.ifBlank { stringResource(R.string.benchmark_untitled_run) }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BenchmarkComparisonHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.benchmark_threads), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.8f))
        Text(stringResource(R.string.benchmark_compare_model_a_short), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.benchmark_compare_model_b_short), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.benchmark_compare_delta), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BenchmarkComparisonRow(
    threads: Int,
    firstSpeed: Float,
    secondSpeed: Float
) {
    val delta = firstSpeed - secondSpeed
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$threads", modifier = Modifier.weight(0.8f), fontFamily = FontFamily.Monospace)
        Text(
            stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(firstSpeed)),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(secondSpeed)),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            stringResource(R.string.benchmark_speed_value, formatBenchmarkSignedNumber(delta)),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = when {
                delta > 0f -> MaterialTheme.colorScheme.primary
                delta < 0f -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun BenchmarkRunCard(
    summary: BenchmarkRunSummary,
    isSelectedForCharts: Boolean,
    onChartSelectionChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateText = remember(summary.runStartedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(summary.runStartedAt))
    }
    val title = summary.runName.ifBlank { stringResource(R.string.benchmark_untitled_run) }
    
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = isSelectedForCharts,
                onCheckedChange = onChartSelectionChange
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.benchmark_chart_selected_run),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelectedForCharts) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.benchmark_rename_run_title))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.benchmark_delete_run_title))
            }
        }
        Text(
            text = stringResource(R.string.benchmark_history_model_date, summary.modelName, dateText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                R.string.benchmark_history_range_and_tokens,
                summary.minThreads,
                summary.maxThreads,
                summary.promptTokens,
                summary.genTokens
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        summary.bestGen?.let { best ->
            Text(
                text = stringResource(
                    R.string.benchmark_history_best_generation,
                    stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(best.genTokensPerSecond)),
                    best.threads
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        summary.bestPrompt?.let { best ->
            Text(
                text = stringResource(
                    R.string.benchmark_history_best_prompt,
                    stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(best.promptTokensPerSecond)),
                    best.threads
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        HorizontalDivider()
        BenchmarkRunHeader()
        summary.results.forEach { result ->
            BenchmarkRunResultRow(result)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.benchmark_history_run_count, summary.results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BenchmarkRunHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.benchmark_threads), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.benchmark_prompt_ts), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.benchmark_gen_ts), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BenchmarkRunResultRow(result: BenchmarkResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("${result.threads}", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
        Text(
            stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(result.promptTokensPerSecond)),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            stringResource(R.string.benchmark_speed_value, formatBenchmarkNumber(result.genTokensPerSecond)),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BenchmarkHelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private data class BenchmarkRunSummary(
    val modelPath: String,
    val modelName: String,
    val runStartedAt: Long,
    val runName: String,
    val promptTokens: Int,
    val genTokens: Int,
    val minThreads: Int,
    val maxThreads: Int,
    val bestGen: BenchmarkResult?,
    val bestPrompt: BenchmarkResult?,
    val results: List<BenchmarkResult>
)

private val BenchmarkRunSummary.chartKey: BenchmarkRunKey
    get() = BenchmarkRunKey(modelPath, runStartedAt)

private fun BenchmarkRunSummary.toChartRun(): BenchmarkChartRun {
    return BenchmarkChartRun(
        key = chartKey,
        modelName = modelName,
        runName = runName,
        runStartedAt = runStartedAt,
        results = results
    )
}

private fun List<BenchmarkResult>.toBenchmarkRunSummaries(): List<BenchmarkRunSummary> {
    return groupBy { it.modelPath to it.runStartedAt }
        .mapNotNull { (_, runResults) ->
            val newest = runResults.maxByOrNull { it.timestamp } ?: return@mapNotNull null
            val sortedResults = runResults.sortedBy { it.threads }
            val threadValues = sortedResults.map { it.threads }
            BenchmarkRunSummary(
                modelPath = newest.modelPath,
                modelName = newest.modelName,
                runStartedAt = newest.runStartedAt,
                runName = newest.runName,
                promptTokens = newest.promptTokens,
                genTokens = newest.genTokens,
                minThreads = threadValues.minOrNull() ?: 0,
                maxThreads = threadValues.maxOrNull() ?: 0,
                bestGen = runResults.maxByOrNull { it.genTokensPerSecond },
                bestPrompt = runResults.maxByOrNull { it.promptTokensPerSecond },
                results = sortedResults
            )
        }
        .sortedByDescending { it.runStartedAt }
}

private fun List<BenchmarkResult>.bestResultByThread(metric: BenchmarkCompareMetric): Map<Int, BenchmarkResult> {
    return groupBy { it.threads }
        .mapValues { (_, results) -> results.maxByOrNull { it.metricSpeed(metric) } ?: results.first() }
}

private fun BenchmarkResult.metricSpeed(metric: BenchmarkCompareMetric): Float {
    return when (metric) {
        BenchmarkCompareMetric.Generation -> genTokensPerSecond
        BenchmarkCompareMetric.Prompt -> promptTokensPerSecond
    }
}

@Composable
private fun BenchmarkCompareMetric.label(): String {
    return when (this) {
        BenchmarkCompareMetric.Generation -> stringResource(R.string.benchmark_metric_generation)
        BenchmarkCompareMetric.Prompt -> stringResource(R.string.benchmark_metric_prompt)
    }
}

private fun formatBenchmarkNumber(value: Float): String {
    return String.format("%.1f", value)
}

private fun formatBenchmarkSignedNumber(value: Float): String {
    val prefix = if (value > 0f) "+" else ""
    return prefix + formatBenchmarkNumber(value)
}
