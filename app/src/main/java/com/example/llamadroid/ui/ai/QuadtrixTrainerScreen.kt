package com.example.llamadroid.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.QuadtrixMetricEntity
import com.example.llamadroid.data.db.QuadtrixProfileEntity
import com.example.llamadroid.quadtrix.QuadtrixOptionKeys
import com.example.llamadroid.quadtrix.QuadtrixWorkspaceManager
import com.example.llamadroid.service.QuadtrixRuntimeState
import com.example.llamadroid.service.QuadtrixSystemSnapshot
import com.example.llamadroid.service.QuadtrixTrainingService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val QUADTRIX_RUNTIME_PROFILE = "ADT Quadtrix WebUI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuadtrixTrainerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val database = remember { AppDatabase.getDatabase(context) }
    val runtime by QuadtrixTrainingService.state.collectAsState()

    val workspaceUri by settingsRepo.quadtrixWorkspaceUri.collectAsState()
    val workspacePath by settingsRepo.quadtrixWorkspacePath.collectAsState()
    val savedWebHost by settingsRepo.quadtrixWebHost.collectAsState()
    val savedWebPort by settingsRepo.quadtrixWebPort.collectAsState()
    val savedWorkerHost by settingsRepo.quadtrixWorkerHost.collectAsState()
    val savedWorkerPort by settingsRepo.quadtrixWorkerPort.collectAsState()
    val savedWorkerToken by settingsRepo.quadtrixWorkerToken.collectAsState()
    val savedWorkerThreads by settingsRepo.quadtrixWorkerThreads.collectAsState()

    var webHost by rememberSaveable { mutableStateOf(savedWebHost) }
    var webPort by rememberSaveable { mutableStateOf(savedWebPort.toString()) }
    var workerHost by rememberSaveable { mutableStateOf(savedWorkerHost) }
    var workerPort by rememberSaveable { mutableStateOf(savedWorkerPort.toString()) }
    var workerToken by rememberSaveable { mutableStateOf(savedWorkerToken) }
    var workerThreads by rememberSaveable { mutableStateOf(savedWorkerThreads.toString()) }
    var startWebUiAfterFolderPick by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(savedWebHost, savedWebPort, savedWorkerHost, savedWorkerPort, savedWorkerToken, savedWorkerThreads) {
        webHost = savedWebHost
        webPort = savedWebPort.toString()
        workerHost = savedWorkerHost
        workerPort = savedWorkerPort.toString()
        workerToken = savedWorkerToken
        workerThreads = savedWorkerThreads.toString()
    }

    suspend fun saveRuntimeProfile(overrideWorkspacePath: String? = null): Long {
        val resolvedWorkspace = QuadtrixWorkspaceManager
            .rootForRuntime(context, overrideWorkspacePath ?: workspacePath)
            .absolutePath
        val webPortNumber = webPort.toPortOrDefault(8080)
        val workerPortNumber = workerPort.toPortOrDefault(9091)
        val workerThreadNumber = workerThreads.toPortOrDefault(4).coerceAtLeast(1)
        settingsRepo.setQuadtrixWebEndpoint(webHost.ifBlank { "127.0.0.1" }, webPortNumber)
        settingsRepo.setQuadtrixWorkerEndpoint(workerHost.ifBlank { "0.0.0.0" }, workerPortNumber, workerToken, workerThreadNumber)

        return withContext(Dispatchers.IO) {
            val dao = database.quadtrixProfileDao()
            val existing = dao.getProfileByName(QUADTRIX_RUNTIME_PROFILE)
            val tokenizerPath = resolvedWorkspace
                ?.let { File(it, QuadtrixWorkspaceManager.TOKENIZER_FILE).absolutePath }
                .orEmpty()
            val enabledOptions = QuadtrixOptionKeys.serialize(
                setOf(
                    QuadtrixOptionKeys.ARCH,
                    QuadtrixOptionKeys.TOKENIZER,
                    QuadtrixOptionKeys.WEB_HOST,
                    QuadtrixOptionKeys.WEB_PORT,
                    QuadtrixOptionKeys.WORKER_HOST,
                    QuadtrixOptionKeys.WORKER_PORT,
                    QuadtrixOptionKeys.WORKER_TOKEN,
                    QuadtrixOptionKeys.THREADS,
                    QuadtrixOptionKeys.NO_GENERATE_AFTER_TRAIN
                )
            )
            val profile = (existing ?: QuadtrixProfileEntity(name = QUADTRIX_RUNTIME_PROFILE)).copy(
                webHost = webHost.ifBlank { "127.0.0.1" },
                webPort = webPortNumber,
                workerHost = workerHost.ifBlank { "0.0.0.0" },
                workerPort = workerPortNumber,
                workerToken = workerToken,
                threads = workerThreadNumber,
                arch = "qwen3",
                tokenizer = "qwen3",
                qwenTokenizerJsonPath = tokenizerPath,
                noGenerateAfterTrain = true,
                enabledOptions = enabledOptions,
                updatedAt = System.currentTimeMillis()
            )
            if (existing == null) dao.insertProfile(profile) else {
                dao.updateProfile(profile)
                existing.id
            }
        }
    }

    val workspacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            startWebUiAfterFolderPick = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                QuadtrixWorkspaceManager.configureWorkspace(context, uri)
            }
            result.onSuccess { selection ->
                settingsRepo.setQuadtrixWorkspace(selection.uri, selection.directPath)
                Toast.makeText(context, context.getString(R.string.quadtrix_workspace_ready), Toast.LENGTH_LONG).show()
                if (startWebUiAfterFolderPick) {
                    val profileId = saveRuntimeProfile(selection.directPath)
                    QuadtrixTrainingService.startWebUi(context, profileId)
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.quadtrix_workspace_setup_failed, error.message ?: context.getString(R.string.error_generic)),
                    Toast.LENGTH_LONG
                ).show()
            }
            startWebUiAfterFolderPick = false
        }
    }

    fun startWebUi() {
        if (workspacePath.isNullOrBlank()) {
            startWebUiAfterFolderPick = true
            workspacePicker.launch(null)
            return
        }
        scope.launch {
            val profileId = saveRuntimeProfile()
            QuadtrixTrainingService.startWebUi(context, profileId)
        }
    }

    fun startWorker() {
        scope.launch {
            val profileId = saveRuntimeProfile()
            QuadtrixTrainingService.startWorker(context, profileId)
        }
    }

    if (showInfo) {
        QuadtrixInfoDialog(onDismiss = { showInfo = false })
    }

    AppScreenScaffold(
        title = stringResource(R.string.quadtrix_title),
        subtitle = stringResource(R.string.quadtrix_simple_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.quadtrix_info))
            }
        }
    ) {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CompactStatusHeader(runtime)
            WebUiCard(
                runtime = runtime,
                webHost = webHost,
                onWebHostChange = { webHost = it },
                webPort = webPort,
                onWebPortChange = { webPort = it.filter { ch -> ch.isDigit() }.take(5) },
                workspaceUri = workspaceUri,
                workspacePath = workspacePath,
                onPickWorkspace = {
                    startWebUiAfterFolderPick = false
                    workspacePicker.launch(null)
                },
                onStart = { startWebUi() },
                onStop = { QuadtrixTrainingService.stop(context) },
                onOpen = {
                    navController.navigate(
                        Screen.QuadtrixWebUi.createRoute(
                            localWebUrl(webHost, webPort.toPortOrDefault(8080))
                        )
                    )
                }
            )
            WorkerCard(
                runtime = runtime,
                workerHost = workerHost,
                onWorkerHostChange = { workerHost = it },
                workerPort = workerPort,
                onWorkerPortChange = { workerPort = it.filter { ch -> ch.isDigit() }.take(5) },
                workerToken = workerToken,
                onWorkerTokenChange = { workerToken = it },
                workerThreads = workerThreads,
                onWorkerThreadsChange = { workerThreads = it.filter { ch -> ch.isDigit() }.take(3) },
                onStart = { startWorker() },
                onStop = { QuadtrixTrainingService.stop(context) }
            )
            MonitorCard(
                runtime = runtime,
                onCopyLogs = {
                    copyText(context, context.getString(R.string.quadtrix_debug_logs), runtime.logs)
                    Toast.makeText(context, context.getString(R.string.quadtrix_logs_copied), Toast.LENGTH_SHORT).show()
                },
                onClearLogs = { QuadtrixTrainingService.clearLogs() }
            )
        }
    }
}

@Composable
private fun CompactStatusHeader(runtime: QuadtrixRuntimeState) {
    AppSectionCard(
        tonalAccent = statusColor(runtime).copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.quadtrix_eyebrow),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.quadtrix_simple_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            StatusChip(runtime)
        }
        runtime.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun WebUiCard(
    runtime: QuadtrixRuntimeState,
    webHost: String,
    onWebHostChange: (String) -> Unit,
    webPort: String,
    onWebPortChange: (String) -> Unit,
    workspaceUri: String?,
    workspacePath: String?,
    onPickWorkspace: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit
) {
    val running = runtime.status == "webui_running" || (runtime.activeMode == "webui" && runtime.status != "stopped")
    val port = webPort.toPortOrDefault(8080)
    val previewUrl = localWebUrl(webHost, port)
    AppSectionCard {
        SectionHeading(
            title = stringResource(R.string.quadtrix_webui_card_title),
            body = stringResource(R.string.quadtrix_webui_card_desc)
        )
        LabeledValue(
            label = stringResource(R.string.quadtrix_workspace_folder),
            value = workspaceUri ?: workspacePath ?: stringResource(R.string.quadtrix_workspace_not_set)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            CompactTextField(
                label = stringResource(R.string.quadtrix_web_host),
                value = webHost,
                onValueChange = onWebHostChange,
                modifier = Modifier.weight(1f)
            )
            CompactTextField(
                label = stringResource(R.string.quadtrix_web_port),
                value = webPort,
                onValueChange = onWebPortChange,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.quadtrix_workspace_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPickWorkspace, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (workspaceUri == null) R.string.quadtrix_workspace_choose else R.string.quadtrix_workspace_change))
            }
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.quadtrix_stop_webui))
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quadtrix_start_webui))
                }
            }
        }
        if (running) {
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.quadtrix_open_webui), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LabeledValue(stringResource(R.string.quadtrix_webui_url), previewUrl)
        }
    }
}

@Composable
private fun WorkerCard(
    runtime: QuadtrixRuntimeState,
    workerHost: String,
    onWorkerHostChange: (String) -> Unit,
    workerPort: String,
    onWorkerPortChange: (String) -> Unit,
    workerToken: String,
    onWorkerTokenChange: (String) -> Unit,
    workerThreads: String,
    onWorkerThreadsChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val running = runtime.status == "worker_running" || (runtime.activeMode == "worker" && runtime.status != "stopped")
    AppSectionCard(tonalAccent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)) {
        SectionHeading(
            title = stringResource(R.string.quadtrix_worker_only_title),
            body = stringResource(R.string.quadtrix_worker_card_desc)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            CompactTextField(
                label = stringResource(R.string.quadtrix_worker_host),
                value = workerHost,
                onValueChange = onWorkerHostChange,
                modifier = Modifier.weight(1f)
            )
            CompactTextField(
                label = stringResource(R.string.quadtrix_worker_port),
                value = workerPort,
                onValueChange = onWorkerPortChange,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            CompactTextField(
                label = stringResource(R.string.quadtrix_worker_token),
                value = workerToken,
                onValueChange = onWorkerTokenChange,
                modifier = Modifier.weight(1f)
            )
            CompactTextField(
                label = stringResource(R.string.quadtrix_threads),
                value = workerThreads,
                onValueChange = onWorkerThreadsChange,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.quadtrix_stop_worker))
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quadtrix_start_worker))
                }
            }
        }
    }
}

@Composable
private fun MonitorCard(
    runtime: QuadtrixRuntimeState,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    AppSectionCard(tonalAccent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)) {
        SectionHeading(
            title = stringResource(R.string.quadtrix_area_monitor),
            body = stringResource(R.string.quadtrix_monitor_desc)
        )
        StatusOverview(runtime)
        runtime.latestMetric?.let { metric ->
            val fraction = if (metric.maxIter > 0) (metric.iter.toFloat() / metric.maxIter.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        runtime.tokenization?.let { tokenization ->
            LinearProgressIndicator(progress = { (tokenization.percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            LabeledValue(
                label = stringResource(R.string.quadtrix_token_stage),
                value = "${tokenization.stage} • ${tokenization.percent.formatPercent()} • ${tokenization.tokens}"
            )
        }
        runtime.workerProgress?.let { worker ->
            LabeledValue(
                label = stringResource(R.string.quadtrix_worker_iter),
                value = "iter=${worker.iter} micro=${worker.microSteps ?: "-"} loss=${worker.loss?.formatNumber() ?: "-"}"
            )
        }
        MonitorMetrics(runtime.latestMetric, runtime.system)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopyLogs, modifier = Modifier.weight(1f), enabled = runtime.logs.isNotBlank()) {
                Text(stringResource(R.string.action_copy))
            }
            OutlinedButton(onClick = onClearLogs, modifier = Modifier.weight(1f), enabled = runtime.logs.isNotBlank()) {
                Text(stringResource(R.string.action_clear))
            }
        }
        TerminalBox(runtime.logs.ifBlank { stringResource(R.string.quadtrix_logs_empty) })
    }
}

@Composable
private fun StatusOverview(runtime: QuadtrixRuntimeState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricTile(
            label = stringResource(R.string.quadtrix_status),
            value = runtimeStatusLabel(runtime),
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = stringResource(R.string.quadtrix_elapsed),
            value = runtime.startedAtMillis?.let { formatDuration((System.currentTimeMillis() - it).coerceAtLeast(0L) / 1000L) } ?: "-",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MonitorMetrics(metric: QuadtrixMetricEntity?, system: QuadtrixSystemSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.quadtrix_iteration),
                value = metric?.let { "${it.iter}/${it.maxIter}" } ?: "-",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = stringResource(R.string.quadtrix_eta),
                value = metric?.etaSeconds?.let { formatDuration(it) } ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.quadtrix_batch_loss),
                value = metric?.batchLoss?.formatNumber() ?: "-",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = stringResource(R.string.quadtrix_ram_free),
                value = system.freeRamMb?.let { "${it} MB" } ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.quadtrix_cpu_avg_temp),
                value = system.cpuAvgTempC?.let { "${it.formatNumber()} C" } ?: "-",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = stringResource(R.string.quadtrix_battery),
                value = system.batteryPercent?.let { "$it%" } ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompactTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusChip(runtime: QuadtrixRuntimeState) {
    Surface(color = statusColor(runtime).copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text = runtimeStatusLabel(runtime),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = statusColor(runtime)
        )
    }
}

@Composable
private fun runtimeStatusLabel(runtime: QuadtrixRuntimeState): String = when {
    runtime.error != null || runtime.status == "error" -> stringResource(R.string.quadtrix_status_error)
    runtime.status == "worker_running" || runtime.activeMode == "worker" -> stringResource(R.string.quadtrix_status_worker)
    runtime.status == "webui_running" || runtime.activeMode == "webui" -> stringResource(R.string.quadtrix_status_webui)
    runtime.status == "tokenizing" || runtime.activeMode == "tokenize" -> stringResource(R.string.quadtrix_status_tokenizing)
    runtime.status == "converting_gguf" || runtime.activeMode == "convert" -> stringResource(R.string.quadtrix_status_converting)
    runtime.status == "chat_running" || runtime.activeMode == "chat" -> stringResource(R.string.quadtrix_status_chat)
    runtime.status == "running" || runtime.processAlive -> stringResource(R.string.quadtrix_status_running)
    else -> stringResource(R.string.quadtrix_status_stopped)
}

@Composable
private fun statusColor(runtime: QuadtrixRuntimeState): Color = when {
    runtime.error != null || runtime.status == "error" -> MaterialTheme.colorScheme.error
    runtime.status == "stopped" && !runtime.processAlive -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun TerminalBox(text: String) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 280.dp)
            .background(Color(0xFF05080B), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .horizontalScroll(horizontal)
            .verticalScroll(vertical)
    ) {
        Text(
            text = text,
            color = Color(0xFFD7FBE8),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun QuadtrixInfoDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quadtrix_info)) },
        text = {
            Text(
                text = stringResource(R.string.quadtrix_simple_info_body),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

private fun String.toPortOrDefault(default: Int): Int =
    trim().toIntOrNull()?.coerceIn(1, 65_535) ?: default

private fun localWebUrl(host: String, port: Int): String {
    val localHost = if (host.trim() == "0.0.0.0") "127.0.0.1" else host.trim().ifBlank { "127.0.0.1" }
    return "http://$localHost:$port"
}

private fun Double.formatNumber(): String = String.format(Locale.US, "%.2f", this)

private fun Double.formatPercent(): String = String.format(Locale.US, "%.0f%%", this)

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
