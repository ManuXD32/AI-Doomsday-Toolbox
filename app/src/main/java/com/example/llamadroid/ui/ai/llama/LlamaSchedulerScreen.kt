package com.example.llamadroid.ui.ai.llama

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SystemPromptEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogStatus
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.service.LlamaScheduledTaskSchedule
import com.example.llamadroid.service.LlamaScheduledTaskScheduler
import com.example.llamadroid.service.LlamaScheduledTaskService
import com.example.llamadroid.service.NativeChatImageGenerationEngine
import com.example.llamadroid.service.NativeChatImageToolParams
import com.example.llamadroid.service.NativeChatSdImageToolParams
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.supportsSdTxt2Img
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.DraftNullableIntTextField
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaSchedulerScreen(navController: NavController) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context.applicationContext) }
    val taskDao = remember { database.llamaScheduledTaskDao() }
    val serverDao = remember { database.llamaServerDao() }
    val tasks by taskDao.getAllTasks().collectAsState(initial = emptyList())
    val logs by taskDao.getRecentLogs(200).collectAsState(initial = emptyList())
    val servers by serverDao.getAllServers().collectAsState(initial = emptyList())
    val runningTaskIds = remember(logs) {
        logs.filter { it.status == LlamaScheduledTaskLogStatus.RUNNING }
            .mapNotNull { it.taskId }
            .toSet()
    }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingTask by remember { mutableStateOf<LlamaScheduledTaskEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var selectedLogIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(selectedTab, logs) {
        if (selectedTab != 1) {
            selectedLogIds = emptySet()
        } else {
            val visibleIds = logs.map { it.id }.toSet()
            selectedLogIds = selectedLogIds.intersect(visibleIds)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.llama_scheduler_title))
                        Text(
                            text = stringResource(R.string.llama_scheduler_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = {
                    editingTask = null
                    showEditor = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.llama_scheduler_new_task))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.llama_scheduler_tasks_tab)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.llama_scheduler_logs_tab)) }
                )
            }
            if (selectedTab == 0) {
                SchedulerTasksList(
                    tasks = tasks,
                    servers = servers,
                    runningTaskIds = runningTaskIds,
                    onEdit = {
                        editingTask = it
                        showEditor = true
                    },
                    onDelete = { task ->
                        scope.launch {
                            LlamaScheduledTaskScheduler.cancelTask(context, task.id)
                            taskDao.deleteTaskById(task.id)
                        }
                    },
                    onToggle = { task, enabled ->
                        scope.launch {
                            val updated = task.copy(
                                enabled = enabled,
                                nextRunAtMillis = LlamaScheduledTaskSchedule.computeNextRun(task.copy(enabled = enabled)),
                                updatedAt = System.currentTimeMillis()
                            )
                            taskDao.updateTask(updated)
                            if (updated.enabled && updated.nextRunAtMillis != null) {
                                LlamaScheduledTaskScheduler.scheduleTask(context, updated)
                            } else {
                                LlamaScheduledTaskScheduler.cancelTask(context, updated.id)
                            }
                        }
                    },
                    onRunNow = { task ->
                        LlamaScheduledTaskService.enqueue(context, task.id, System.currentTimeMillis(), force = true)
                        Toast.makeText(context, context.getString(R.string.llama_scheduler_run_now_started), Toast.LENGTH_SHORT).show()
                    },
                    onStop = { task ->
                        LlamaScheduledTaskService.cancelRunning(context, taskId = task.id)
                        Toast.makeText(context, context.getString(R.string.llama_scheduler_stop_requested), Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                SchedulerLogsList(
                    logs = logs,
                    selectedLogIds = selectedLogIds,
                    onToggleSelection = { logId ->
                        selectedLogIds = if (logId in selectedLogIds) {
                            selectedLogIds - logId
                        } else {
                            selectedLogIds + logId
                        }
                    },
                    onClearSelection = { selectedLogIds = emptySet() },
                    onSelectAll = { selectedLogIds = logs.map { it.id }.toSet() },
                    onDeleteLog = { log ->
                        scope.launch {
                            taskDao.deleteLogById(log.id)
                            selectedLogIds = selectedLogIds - log.id
                        }
                    },
                    onStopLog = { log ->
                        LlamaScheduledTaskService.cancelRunning(context, taskId = log.taskId, logId = log.id)
                        Toast.makeText(context, context.getString(R.string.llama_scheduler_stop_requested), Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSelected = {
                        val ids = selectedLogIds.toList()
                        if (ids.isNotEmpty()) {
                            scope.launch {
                                taskDao.deleteLogsByIds(ids)
                                selectedLogIds = emptySet()
                            }
                        }
                    },
                    onDeleteAll = {
                        scope.launch {
                            taskDao.deleteAllLogs()
                            selectedLogIds = emptySet()
                        }
                    }
                )
            }
        }
    }

    if (showEditor) {
        LlamaScheduledTaskEditorDialog(
            initialTask = editingTask,
            servers = servers,
            onDismiss = { showEditor = false },
            onSave = { task ->
                scope.launch {
                    val withNextRun = task.copy(
                        nextRunAtMillis = LlamaScheduledTaskSchedule.computeNextRun(task),
                        updatedAt = System.currentTimeMillis()
                    )
                    val savedId = if (withNextRun.id == 0L) {
                        taskDao.insertTask(withNextRun)
                    } else {
                        taskDao.updateTask(withNextRun)
                        withNextRun.id
                    }
                    val savedTask = withNextRun.copy(id = savedId)
                    if (savedTask.enabled && savedTask.nextRunAtMillis != null) {
                        LlamaScheduledTaskScheduler.scheduleTask(context, savedTask)
                    } else {
                        LlamaScheduledTaskScheduler.cancelTask(context, savedTask.id)
                    }
                    showEditor = false
                }
            }
        )
    }
}

@Composable
private fun SchedulerTasksList(
    tasks: List<LlamaScheduledTaskEntity>,
    servers: List<LlamaServerEntity>,
    runningTaskIds: Set<Long>,
    onEdit: (LlamaScheduledTaskEntity) -> Unit,
    onDelete: (LlamaScheduledTaskEntity) -> Unit,
    onToggle: (LlamaScheduledTaskEntity, Boolean) -> Unit,
    onRunNow: (LlamaScheduledTaskEntity) -> Unit,
    onStop: (LlamaScheduledTaskEntity) -> Unit
) {
    val serverNames = remember(servers) { servers.associate { it.id to it.name } }
    if (tasks.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.llama_scheduler_empty_tasks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            SchedulerTaskCard(
                task = task,
                serverName = task.serverId?.let { serverNames[it] } ?: stringResource(R.string.llama_scheduler_last_used_server),
                isRunning = task.id in runningTaskIds,
                onEdit = { onEdit(task) },
                onDelete = { onDelete(task) },
                onToggle = { onToggle(task, it) },
                onRunNow = { onRunNow(task) },
                onStop = { onStop(task) }
            )
        }
    }
}

@Composable
private fun SchedulerTaskCard(
    task: LlamaScheduledTaskEntity,
    serverName: String,
    isRunning: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onStop: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(scheduleLabel(task)) }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            task.nextRunAtMillis?.let { stringResource(R.string.llama_scheduler_next_run, formatDateTime(it)) }
                                ?: stringResource(R.string.llama_scheduler_no_next_run)
                        )
                    }
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    OutlinedButton(onClick = onStop) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.llama_scheduler_stop_task))
                    }
                }
                OutlinedButton(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.llama_scheduler_run_now))
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulerLogsList(
    logs: List<LlamaScheduledTaskLogEntity>,
    selectedLogIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteLog: (LlamaScheduledTaskLogEntity) -> Unit,
    onStopLog: (LlamaScheduledTaskLogEntity) -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit
) {
    if (logs.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.llama_scheduler_empty_logs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SchedulerLogActionsRow(
                totalLogs = logs.size,
                selectedCount = selectedLogIds.size,
                onClearSelection = onClearSelection,
                onSelectAll = onSelectAll,
                onDeleteSelected = onDeleteSelected,
                onDeleteAll = onDeleteAll
            )
        }
        items(logs, key = { it.id }) { log ->
            SchedulerLogCard(
                log = log,
                selected = log.id in selectedLogIds,
                selectionMode = selectedLogIds.isNotEmpty(),
                onToggleSelection = { onToggleSelection(log.id) },
                onDelete = { onDeleteLog(log) },
                onStop = { onStopLog(log) }
            )
        }
    }
}

@Composable
private fun SchedulerLogActionsRow(
    totalLogs: Int,
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (selectedCount > 0) {
                    stringResource(R.string.llama_scheduler_selected_logs, selectedCount)
                } else {
                    stringResource(R.string.llama_scheduler_logs_count, totalLogs)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedCount > 0) {
                    OutlinedButton(onClick = onDeleteSelected) {
                        Text(stringResource(R.string.llama_scheduler_delete_selected_logs))
                    }
                    OutlinedButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_clear))
                    }
                } else {
                    OutlinedButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.notes_select_all))
                    }
                    OutlinedButton(onClick = onDeleteAll) {
                        Text(stringResource(R.string.llama_scheduler_delete_all_logs))
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulerLogCard(
    log: LlamaScheduledTaskLogEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectionMode) { onToggleSelection() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = when (log.status) {
                LlamaScheduledTaskLogStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                LlamaScheduledTaskLogStatus.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(log.taskName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${localizedStatus(log.status)} · ${formatDateTime(log.scheduledAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.action_collapse else R.string.action_expand))
                }
            }
            log.durationMs?.let {
                Text(
                    text = stringResource(R.string.llama_scheduler_duration, formatDuration(it)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (log.status == LlamaScheduledTaskLogStatus.RUNNING) {
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.llama_scheduler_stop_task))
                }
            }
            val preview = log.finalOutput.ifBlank { log.error.orEmpty() }.take(240)
            if (preview.isNotBlank()) {
                Text(preview, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                if (!log.error.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.llama_scheduler_log_error, log.error),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (log.toolActivity.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.llama_scheduler_log_tools),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(log.toolActivity, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaScheduledTaskEditorDialog(
    initialTask: LlamaScheduledTaskEntity?,
    servers: List<LlamaServerEntity>,
    onDismiss: () -> Unit,
    onSave: (LlamaScheduledTaskEntity) -> Unit
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context.applicationContext) }
    val promptProfiles by remember(database) {
        database.llamaChatPromptProfileDao().getAllProfiles()
    }.collectAsState(initial = emptyList())
    val savedSystemPrompts by remember(database) {
        database.systemPromptDao().getAllPrompts()
    }.collectAsState(initial = emptyList())
    val onnxImageModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN)
    }.collectAsState(initial = emptyList())
    val sdImageMainModels by remember(database) {
        database.modelDao().getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
    }.collectAsState(initial = emptyList())
    val sdImageSupportModels by remember(database) {
        database.modelDao().getModelsByTypes(
            listOf(
                ModelType.SD_VAE,
                ModelType.SD_TAE,
                ModelType.SD_CLIP_L,
                ModelType.SD_CLIP_G,
                ModelType.SD_T5XXL,
                ModelType.LLM,
                ModelType.VISION_PROJECTOR,
                ModelType.SD_PHOTOMAKER
            )
        )
    }.collectAsState(initial = emptyList())
    val nativeChatImageModelOptions = remember(onnxImageModels) {
        onnxImageModels
            .filter { it.isOnnxTxt2ImgBundle() }
            .map { it.filename }
            .distinct()
    }
    val nativeChatSdImageModelOptions = remember(sdImageMainModels) {
        sdImageMainModels
            .filter { it.isSdImageMainModel() && it.supportsSdTxt2Img() }
            .map { it.filename }
            .distinct()
    }
    val now = remember { System.currentTimeMillis() }
    val defaultServer = servers.firstOrNull()
    val initialConfig = remember(initialTask, defaultServer) {
        NativeChatToolConfig.fromApiParams(initialTask?.apiParams ?: defaultServer?.defaultApiParams)
    }
    var name by remember { mutableStateOf(initialTask?.name ?: "") }
    var enabled by remember { mutableStateOf(initialTask?.enabled ?: true) }
    var selectedServerId by remember { mutableStateOf(initialTask?.serverId ?: defaultServer?.id ?: -1L) }
    var contextSizeText by remember { mutableStateOf((initialTask?.contextSize ?: 8192).toString()) }
    var systemPrompt by remember { mutableStateOf(initialTask?.systemPrompt.orEmpty()) }
    var taskPrompt by remember { mutableStateOf(initialTask?.taskPrompt.orEmpty()) }
    var scheduleType by remember { mutableStateOf(initialTask?.scheduleType ?: LlamaScheduledTaskScheduleType.DAILY) }
    var oneTimeAtMillis by remember { mutableLongStateOf(initialTask?.oneTimeAtMillis ?: now + 60 * 60 * 1000L) }
    var timeOfDayMinutesText by remember {
        mutableStateOf((initialTask?.timeOfDayMinutes ?: 7 * 60).let { "%02d:%02d".format(it / 60, it % 60) })
    }
    var weekdaysMask by remember { mutableIntStateOf(initialTask?.weekdaysMask ?: LlamaScheduledTaskSchedule.weekdayBit(DayOfWeek.MONDAY)) }
    var dayOfMonthText by remember { mutableStateOf((initialTask?.dayOfMonth ?: 1).toString()) }
    var toolsEnabled by remember { mutableStateOf(initialConfig.toolsEnabled) }
    var webEnabled by remember { mutableStateOf(initialConfig.webSearchEnabled) }
    var kiwixEnabled by remember { mutableStateOf(initialConfig.kiwixSearchEnabled) }
    var fetchEnabled by remember { mutableStateOf(initialConfig.fetchUrlEnabled) }
    var dateTimeEnabled by remember { mutableStateOf(initialConfig.dateTimeEnabled) }
    var calculatorEnabled by remember { mutableStateOf(initialConfig.calculatorEnabled) }
    var notesEnabled by remember { mutableStateOf(initialConfig.noteToolsEnabled) }
    var todoEnabled by remember { mutableStateOf(initialConfig.todoToolsEnabled) }
    var calendarEnabled by remember { mutableStateOf(initialConfig.calendarToolsEnabled) }
    var alarmsEnabled by remember { mutableStateOf(initialConfig.alarmToolsEnabled) }
    var imageEnabled by remember { mutableStateOf(initialConfig.imageGenerationEnabled) }
    var imageIterationEnabled by remember { mutableStateOf(initialConfig.imageIterationEnabled) }
    var webMaxPages by remember { mutableIntStateOf(initialConfig.webSearchMaxPages) }
    var webMaxChars by remember { mutableIntStateOf(initialConfig.webSearchMaxChars) }
    var kiwixServerUrl by remember { mutableStateOf(initialConfig.kiwixServerUrl) }
    var kiwixMaxPages by remember { mutableIntStateOf(initialConfig.kiwixMaxPages) }
    var kiwixMaxChars by remember { mutableIntStateOf(initialConfig.kiwixMaxChars) }
    var fetchMaxChars by remember { mutableIntStateOf(initialConfig.fetchUrlMaxChars) }
    var maxToolRounds by remember { mutableIntStateOf(initialConfig.maxToolRounds) }
    var imageToolModel by remember { mutableStateOf(initialConfig.imageParams.model.orEmpty()) }
    var imageToolEngine by remember { mutableStateOf(initialConfig.imageParams.engine) }
    var imageToolWidth by remember { mutableIntStateOf(initialConfig.imageParams.width) }
    var imageToolHeight by remember { mutableIntStateOf(initialConfig.imageParams.height) }
    var imageToolSteps by remember { mutableIntStateOf(initialConfig.imageParams.steps) }
    var imageToolCfg by remember { mutableStateOf(initialConfig.imageParams.cfgScale) }
    var imageToolSeed by remember { mutableStateOf(initialConfig.imageParams.seed) }
    var imageToolNegativePrompt by remember { mutableStateOf(initialConfig.imageParams.negativePrompt) }
    var imageToolBackend by remember { mutableStateOf(initialConfig.imageParams.backend) }
    var imageToolRuntimeThreads by remember { mutableStateOf(initialConfig.imageParams.runtimeThreads) }
    var imageToolGraphOpt by remember { mutableStateOf(initialConfig.imageParams.graphOptimizationLevel) }
    var imageToolUnetBackend by remember { mutableStateOf(initialConfig.imageParams.unetBackendOverride) }
    var imageToolVaeDecoderBackend by remember { mutableStateOf(initialConfig.imageParams.vaeDecoderBackendOverride) }
    var imageToolVaeEncoderBackend by remember { mutableStateOf(initialConfig.imageParams.vaeEncoderBackendOverride) }
    var imageToolIntraThreads by remember { mutableStateOf(initialConfig.imageParams.intraOpThreads) }
    var imageToolInterThreads by remember { mutableStateOf(initialConfig.imageParams.interOpThreads) }
    var imageToolExecutionMode by remember { mutableStateOf(initialConfig.imageParams.executionMode) }
    var imageToolMemoryPattern by remember { mutableStateOf(initialConfig.imageParams.memoryPatternOptimization) }
    var imageToolCpuArena by remember { mutableStateOf(initialConfig.imageParams.cpuArenaAllocator) }
    var imageToolNnapiCpuDisabled by remember { mutableStateOf(initialConfig.imageParams.nnapiCpuDisabled) }
    var imageToolNnapiFp16 by remember { mutableStateOf(initialConfig.imageParams.nnapiUseFp16) }
    var imageToolSdModel by remember { mutableStateOf(initialConfig.imageParams.sdParams.model.orEmpty()) }
    var imageToolSdVae by remember { mutableStateOf(initialConfig.imageParams.sdParams.vaePath.orEmpty()) }
    var imageToolSdTae by remember { mutableStateOf(initialConfig.imageParams.sdParams.taePath.orEmpty()) }
    var imageToolSdClipL by remember { mutableStateOf(initialConfig.imageParams.sdParams.clipLPath.orEmpty()) }
    var imageToolSdClipG by remember { mutableStateOf(initialConfig.imageParams.sdParams.clipGPath.orEmpty()) }
    var imageToolSdT5xxl by remember { mutableStateOf(initialConfig.imageParams.sdParams.t5xxlPath.orEmpty()) }
    var imageToolSdLlm by remember { mutableStateOf(initialConfig.imageParams.sdParams.llmPath.orEmpty()) }
    var imageToolSdLlmVision by remember { mutableStateOf(initialConfig.imageParams.sdParams.llmVisionPath.orEmpty()) }
    var imageToolSdPhotoMaker by remember { mutableStateOf(initialConfig.imageParams.sdParams.photoMakerPath.orEmpty()) }
    var imageToolSdWidth by remember { mutableIntStateOf(initialConfig.imageParams.sdParams.width) }
    var imageToolSdHeight by remember { mutableIntStateOf(initialConfig.imageParams.sdParams.height) }
    var imageToolSdSteps by remember { mutableIntStateOf(initialConfig.imageParams.sdParams.steps) }
    var imageToolSdCfg by remember { mutableStateOf(initialConfig.imageParams.sdParams.cfgScale) }
    var imageToolSdSampler by remember { mutableStateOf(initialConfig.imageParams.sdParams.sampler) }
    var imageToolSdSeed by remember { mutableStateOf(initialConfig.imageParams.sdParams.seed) }
    var imageToolSdNegativePrompt by remember { mutableStateOf(initialConfig.imageParams.sdParams.negativePrompt) }
    var imageToolSdThreads by remember { mutableIntStateOf(initialConfig.imageParams.sdParams.threads) }
    var imageToolSdFlowShift by remember { mutableStateOf(initialConfig.imageParams.sdParams.flowShift) }
    var imageToolSdDiffusionFa by remember { mutableStateOf(initialConfig.imageParams.sdParams.diffusionFa) }
    var imageToolSdMmap by remember { mutableStateOf(initialConfig.imageParams.sdParams.mmap) }
    var imageToolSdVaeConvDirect by remember { mutableStateOf(initialConfig.imageParams.sdParams.vaeConvDirect) }
    var imageToolSdQwenZeroCondT by remember { mutableStateOf(initialConfig.imageParams.sdParams.qwenImageZeroCondT) }
    var imageToolSdChromaDisableDitMask by remember { mutableStateOf(initialConfig.imageParams.sdParams.chromaDisableDitMask) }
    var serverMenuExpanded by remember { mutableStateOf(false) }
    var scheduleMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initialTask == null) R.string.llama_scheduler_new_task else R.string.llama_scheduler_edit_task)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.llama_scheduler_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.llama_scheduler_task_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { serverMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(servers.firstOrNull { it.id == selectedServerId }?.name ?: stringResource(R.string.llama_scheduler_last_used_server))
                    }
                    DropdownMenu(expanded = serverMenuExpanded, onDismissRequest = { serverMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.llama_scheduler_last_used_server)) },
                            onClick = {
                                selectedServerId = -1L
                                serverMenuExpanded = false
                            }
                        )
                        servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.name) },
                                onClick = {
                                    selectedServerId = server.id
                                    serverMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = contextSizeText,
                    onValueChange = { contextSizeText = it },
                    label = { Text(stringResource(R.string.llama_context_size)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SchedulerPromptPickerField(
                    customProfiles = promptProfiles,
                    savedSystemPrompts = savedSystemPrompts,
                    onSelected = { systemPrompt = it }
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.llama_system_prompt)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = taskPrompt,
                    onValueChange = { taskPrompt = it },
                    label = { Text(stringResource(R.string.llama_scheduler_task_prompt)) },
                    minLines = 4,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { scheduleMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(scheduleName(scheduleType))
                    }
                    DropdownMenu(expanded = scheduleMenuExpanded, onDismissRequest = { scheduleMenuExpanded = false }) {
                        listOf(
                            LlamaScheduledTaskScheduleType.ONE_TIME,
                            LlamaScheduledTaskScheduleType.DAILY,
                            LlamaScheduledTaskScheduleType.WEEKLY,
                            LlamaScheduledTaskScheduleType.MONTHLY
                        ).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(scheduleName(type)) },
                                onClick = {
                                    scheduleType = type
                                    scheduleMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                StylizedSchedulePicker(
                    scheduleType = scheduleType,
                    oneTimeAtMillis = oneTimeAtMillis,
                    onOneTimeAtMillisChange = { oneTimeAtMillis = it },
                    timeOfDayText = timeOfDayMinutesText,
                    onTimeOfDayTextChange = { timeOfDayMinutesText = it },
                    weekdaysMask = weekdaysMask,
                    onWeekdaysMaskChange = { weekdaysMask = it },
                    dayOfMonthText = dayOfMonthText,
                    onDayOfMonthTextChange = { dayOfMonthText = it }
                )
                Text(stringResource(R.string.llama_scheduler_tools_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ToolToggleRow(stringResource(R.string.llama_tools_enable), toolsEnabled) { toolsEnabled = it }
                if (toolsEnabled) {
                    ToolToggleRow(stringResource(R.string.llama_tool_web_search), webEnabled) { webEnabled = it }
                    if (webEnabled) {
                        SchedulerToolNumberRow(
                            label = stringResource(R.string.llama_tool_pages),
                            value = webMaxPages,
                            onValueChange = { webMaxPages = it },
                            range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
                        )
                        SchedulerToolNumberRow(
                            label = stringResource(R.string.llama_tool_chars_per_page),
                            value = webMaxChars,
                            onValueChange = { webMaxChars = it },
                            range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
                        )
                    }

                    ToolToggleRow(stringResource(R.string.llama_tool_fetch_url), fetchEnabled) { fetchEnabled = it }
                    if (fetchEnabled) {
                        SchedulerToolNumberRow(
                            label = stringResource(R.string.llama_tool_max_chars),
                            value = fetchMaxChars,
                            onValueChange = { fetchMaxChars = it },
                            range = NativeChatToolConfig.MIN_FETCH_CHARS..NativeChatToolConfig.MAX_FETCH_CHARS
                        )
                    }

                    ToolToggleRow(stringResource(R.string.llama_tool_kiwix_search), kiwixEnabled) { kiwixEnabled = it }
                    if (kiwixEnabled) {
                        OutlinedTextField(
                            value = kiwixServerUrl,
                            onValueChange = { kiwixServerUrl = it },
                            label = { Text(stringResource(R.string.llama_tool_kiwix_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SchedulerToolNumberRow(
                            label = stringResource(R.string.llama_tool_pages),
                            value = kiwixMaxPages,
                            onValueChange = { kiwixMaxPages = it },
                            range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
                        )
                        SchedulerToolNumberRow(
                            label = stringResource(R.string.llama_tool_chars_per_page),
                            value = kiwixMaxChars,
                            onValueChange = { kiwixMaxChars = it },
                            range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
                        )
                    }

                    ToolToggleRow(stringResource(R.string.llama_tool_datetime), dateTimeEnabled) { dateTimeEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_calculator), calculatorEnabled) { calculatorEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_notes), notesEnabled) { notesEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_todo_lists), todoEnabled) { todoEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_calendar), calendarEnabled) { calendarEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_alarms), alarmsEnabled) { alarmsEnabled = it }
                    ToolToggleRow(stringResource(R.string.llama_tool_image_generation), imageEnabled) { imageEnabled = it }
                    if (imageEnabled) {
                        ToolToggleRow(stringResource(R.string.llama_tool_image_iteration), imageIterationEnabled) { imageIterationEnabled = it }
                        SchedulerImageToolEnumDropdown(
                            label = stringResource(R.string.image_tool_engine_label),
                            selected = imageToolEngine,
                            values = NativeChatImageGenerationEngine.entries,
                            labelFor = {
                                when (it) {
                                    NativeChatImageGenerationEngine.ONNX -> stringResource(R.string.image_tool_engine_onnx)
                                    NativeChatImageGenerationEngine.SD -> stringResource(R.string.image_tool_engine_sd)
                                }
                            },
                            onSelected = { imageToolEngine = it }
                        )
                        if (imageToolEngine == NativeChatImageGenerationEngine.ONNX) {
                            SchedulerNativeImageToolSettings(
                                model = imageToolModel,
                                availableModels = nativeChatImageModelOptions,
                                onModelChange = { imageToolModel = it },
                                width = imageToolWidth,
                                onWidthChange = { imageToolWidth = it.coerceIn(NativeChatImageToolParams.MIN_SIZE, NativeChatImageToolParams.MAX_SIZE) },
                                height = imageToolHeight,
                                onHeightChange = { imageToolHeight = it.coerceIn(NativeChatImageToolParams.MIN_SIZE, NativeChatImageToolParams.MAX_SIZE) },
                                steps = imageToolSteps,
                                onStepsChange = { imageToolSteps = it.coerceIn(NativeChatImageToolParams.MIN_STEPS, NativeChatImageToolParams.MAX_STEPS) },
                                cfg = imageToolCfg,
                                onCfgChange = { imageToolCfg = it.coerceIn(NativeChatImageToolParams.MIN_CFG, NativeChatImageToolParams.MAX_CFG) },
                                seed = imageToolSeed,
                                onSeedChange = { imageToolSeed = it },
                                negativePrompt = imageToolNegativePrompt,
                                onNegativePromptChange = { imageToolNegativePrompt = it },
                                backend = imageToolBackend,
                                onBackendChange = { imageToolBackend = it },
                                runtimeThreads = imageToolRuntimeThreads,
                                onRuntimeThreadsChange = { imageToolRuntimeThreads = it },
                                graphOptimizationLevel = imageToolGraphOpt,
                                onGraphOptimizationLevelChange = { imageToolGraphOpt = it },
                                unetBackendOverride = imageToolUnetBackend,
                                onUnetBackendOverrideChange = { imageToolUnetBackend = it },
                                vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                                onVaeDecoderBackendOverrideChange = { imageToolVaeDecoderBackend = it },
                                vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                                onVaeEncoderBackendOverrideChange = { imageToolVaeEncoderBackend = it },
                                intraOpThreads = imageToolIntraThreads,
                                onIntraOpThreadsChange = { imageToolIntraThreads = it },
                                interOpThreads = imageToolInterThreads,
                                onInterOpThreadsChange = { imageToolInterThreads = it },
                                executionMode = imageToolExecutionMode,
                                onExecutionModeChange = { imageToolExecutionMode = it },
                                memoryPatternOptimization = imageToolMemoryPattern,
                                onMemoryPatternOptimizationChange = { imageToolMemoryPattern = it },
                                cpuArenaAllocator = imageToolCpuArena,
                                onCpuArenaAllocatorChange = { imageToolCpuArena = it },
                                nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                                onNnapiCpuDisabledChange = { imageToolNnapiCpuDisabled = it },
                                nnapiUseFp16 = imageToolNnapiFp16,
                                onNnapiUseFp16Change = { imageToolNnapiFp16 = it }
                            )
                        } else {
                            val selectedSdModel = sdImageMainModels.firstOrNull {
                                it.filename == imageToolSdModel || it.path == imageToolSdModel
                            } ?: sdImageMainModels.firstOrNull { it.supportsSdTxt2Img() }
                            val selectedSdSpec = selectedSdModel?.resolvedSdFamily()
                                ?.let { (family, variant) -> family?.let { resolveSdFamilySpec(it, variant) } }
                            val supportedComponentRoles = remember(selectedSdSpec) {
                                val allowedRoles = setOf(
                                    SdComponentRole.VAE,
                                    SdComponentRole.TAE,
                                    SdComponentRole.CLIP_L,
                                    SdComponentRole.CLIP_G,
                                    SdComponentRole.T5XXL,
                                    SdComponentRole.LLM,
                                    SdComponentRole.LLM_VISION,
                                    SdComponentRole.PHOTOMAKER
                                )
                                ((selectedSdSpec?.requiredRoles.orEmpty() + selectedSdSpec?.optionalRoles.orEmpty()) intersect allowedRoles)
                                    .toList()
                            }
                            SchedulerNativeSdImageToolSettings(
                                model = imageToolSdModel,
                                availableModels = nativeChatSdImageModelOptions,
                                onModelChange = { imageToolSdModel = it },
                                componentRoles = supportedComponentRoles,
                                requiredRoles = selectedSdSpec?.requiredRoles.orEmpty(),
                                componentOptions = { role -> schedulerSdComponentOptions(sdImageSupportModels, selectedSdModel, role) },
                                selectedComponent = { role ->
                                    when (role) {
                                        SdComponentRole.VAE -> imageToolSdVae
                                        SdComponentRole.TAE -> imageToolSdTae
                                        SdComponentRole.CLIP_L -> imageToolSdClipL
                                        SdComponentRole.CLIP_G -> imageToolSdClipG
                                        SdComponentRole.T5XXL -> imageToolSdT5xxl
                                        SdComponentRole.LLM -> imageToolSdLlm
                                        SdComponentRole.LLM_VISION -> imageToolSdLlmVision
                                        SdComponentRole.PHOTOMAKER -> imageToolSdPhotoMaker
                                        else -> ""
                                    }
                                },
                                onComponentChange = { role, value ->
                                    when (role) {
                                        SdComponentRole.VAE -> imageToolSdVae = value
                                        SdComponentRole.TAE -> imageToolSdTae = value
                                        SdComponentRole.CLIP_L -> imageToolSdClipL = value
                                        SdComponentRole.CLIP_G -> imageToolSdClipG = value
                                        SdComponentRole.T5XXL -> imageToolSdT5xxl = value
                                        SdComponentRole.LLM -> imageToolSdLlm = value
                                        SdComponentRole.LLM_VISION -> imageToolSdLlmVision = value
                                        SdComponentRole.PHOTOMAKER -> imageToolSdPhotoMaker = value
                                        else -> Unit
                                    }
                                },
                                width = imageToolSdWidth,
                                onWidthChange = { imageToolSdWidth = it.coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE) },
                                height = imageToolSdHeight,
                                onHeightChange = { imageToolSdHeight = it.coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE) },
                                steps = imageToolSdSteps,
                                onStepsChange = { imageToolSdSteps = it.coerceIn(NativeChatSdImageToolParams.MIN_STEPS, NativeChatSdImageToolParams.MAX_STEPS) },
                                cfg = imageToolSdCfg,
                                onCfgChange = { imageToolSdCfg = it.coerceIn(NativeChatSdImageToolParams.MIN_CFG, NativeChatSdImageToolParams.MAX_CFG) },
                                sampler = imageToolSdSampler,
                                onSamplerChange = { imageToolSdSampler = it },
                                seed = imageToolSdSeed,
                                onSeedChange = { imageToolSdSeed = it },
                                negativePrompt = imageToolSdNegativePrompt,
                                onNegativePromptChange = { imageToolSdNegativePrompt = it },
                                threads = imageToolSdThreads,
                                onThreadsChange = { imageToolSdThreads = it.coerceIn(NativeChatSdImageToolParams.MIN_THREADS, NativeChatSdImageToolParams.MAX_THREADS) },
                                flowShift = imageToolSdFlowShift,
                                onFlowShiftChange = { imageToolSdFlowShift = it },
                                showFlowShift = selectedSdSpec?.supportsFlowShift == true,
                                diffusionFa = imageToolSdDiffusionFa,
                                onDiffusionFaChange = { imageToolSdDiffusionFa = it },
                                showDiffusionFa = selectedSdSpec?.supportsDiffusionFa == true,
                                mmap = imageToolSdMmap,
                                onMmapChange = { imageToolSdMmap = it },
                                showMmap = selectedSdSpec?.supportsMmap == true,
                                vaeConvDirect = imageToolSdVaeConvDirect,
                                onVaeConvDirectChange = { imageToolSdVaeConvDirect = it },
                                showVaeConvDirect = selectedSdSpec?.supportsVaeConvDirect == true,
                                qwenZeroCondT = imageToolSdQwenZeroCondT,
                                onQwenZeroCondTChange = { imageToolSdQwenZeroCondT = it },
                                showQwenZeroCondT = selectedSdSpec?.supportsQwenImageZeroCondT == true,
                                chromaDisableDitMask = imageToolSdChromaDisableDitMask,
                                onChromaDisableDitMaskChange = { imageToolSdChromaDisableDitMask = it },
                                showChromaDisableDitMask = selectedSdSpec?.supportsChromaDisableDitMask == true
                            )
                        }
                    }
                    SchedulerToolNumberRow(
                        label = stringResource(R.string.llama_tool_max_rounds),
                        value = maxToolRounds,
                        onValueChange = { maxToolRounds = it },
                        range = NativeChatToolConfig.MIN_TOOL_ROUNDS..NativeChatToolConfig.MAX_TOOL_ROUNDS
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minutes = parseTimeOfDayMinutes(timeOfDayMinutesText) ?: 7 * 60
                    val finalOneTime = if (scheduleType == LlamaScheduledTaskScheduleType.ONE_TIME) {
                        val currentDate = Calendar.getInstance().apply { timeInMillis = oneTimeAtMillis }
                        currentDate.set(Calendar.HOUR_OF_DAY, minutes / 60)
                        currentDate.set(Calendar.MINUTE, minutes % 60)
                        currentDate.set(Calendar.SECOND, 0)
                        currentDate.set(Calendar.MILLISECOND, 0)
                        currentDate.timeInMillis
                    } else {
                        oneTimeAtMillis
                    }
                    val config = initialConfig.copy(
                        toolsEnabled = toolsEnabled,
                        webSearchEnabled = webEnabled,
                        webSearchMaxPages = webMaxPages,
                        webSearchMaxChars = webMaxChars,
                        kiwixSearchEnabled = kiwixEnabled,
                        kiwixServerUrl = kiwixServerUrl,
                        kiwixMaxPages = kiwixMaxPages,
                        kiwixMaxChars = kiwixMaxChars,
                        fetchUrlEnabled = fetchEnabled,
                        fetchUrlMaxChars = fetchMaxChars,
                        dateTimeEnabled = dateTimeEnabled,
                        calculatorEnabled = calculatorEnabled,
                        noteToolsEnabled = notesEnabled,
                        todoToolsEnabled = todoEnabled,
                        calendarToolsEnabled = calendarEnabled,
                        alarmToolsEnabled = alarmsEnabled,
                        imageGenerationEnabled = imageEnabled,
                        imageIterationEnabled = imageIterationEnabled,
                        imageParams = NativeChatImageToolParams(
                            engine = imageToolEngine,
                            model = imageToolModel.takeIf { it.isNotBlank() },
                            width = imageToolWidth,
                            height = imageToolHeight,
                            steps = imageToolSteps,
                            cfgScale = imageToolCfg,
                            seed = imageToolSeed,
                            negativePrompt = imageToolNegativePrompt,
                            backend = imageToolBackend,
                            runtimeThreads = imageToolRuntimeThreads,
                            graphOptimizationLevel = imageToolGraphOpt,
                            unetBackendOverride = imageToolUnetBackend,
                            vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                            vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                            intraOpThreads = imageToolIntraThreads,
                            interOpThreads = imageToolInterThreads,
                            executionMode = imageToolExecutionMode,
                            memoryPatternOptimization = imageToolMemoryPattern,
                            cpuArenaAllocator = imageToolCpuArena,
                            nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                            nnapiUseFp16 = imageToolNnapiFp16,
                            sdParams = NativeChatSdImageToolParams(
                                model = imageToolSdModel.takeIf { it.isNotBlank() },
                                vaePath = imageToolSdVae.takeIf { it.isNotBlank() },
                                taePath = imageToolSdTae.takeIf { it.isNotBlank() },
                                clipLPath = imageToolSdClipL.takeIf { it.isNotBlank() },
                                clipGPath = imageToolSdClipG.takeIf { it.isNotBlank() },
                                t5xxlPath = imageToolSdT5xxl.takeIf { it.isNotBlank() },
                                llmPath = imageToolSdLlm.takeIf { it.isNotBlank() },
                                llmVisionPath = imageToolSdLlmVision.takeIf { it.isNotBlank() },
                                photoMakerPath = imageToolSdPhotoMaker.takeIf { it.isNotBlank() },
                                width = imageToolSdWidth,
                                height = imageToolSdHeight,
                                steps = imageToolSdSteps,
                                cfgScale = imageToolSdCfg,
                                sampler = imageToolSdSampler,
                                seed = imageToolSdSeed,
                                negativePrompt = imageToolSdNegativePrompt,
                                threads = imageToolSdThreads,
                                flowShift = imageToolSdFlowShift,
                                diffusionFa = imageToolSdDiffusionFa,
                                mmap = imageToolSdMmap,
                                vaeConvDirect = imageToolSdVaeConvDirect,
                                qwenImageZeroCondT = imageToolSdQwenZeroCondT,
                                chromaDisableDitMask = imageToolSdChromaDisableDitMask
                            )
                        ),
                        maxToolRounds = maxToolRounds
                    )
                    val cleanName = name.trim()
                    val cleanPrompt = taskPrompt.trim()
                    if (cleanName.isBlank() || cleanPrompt.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.llama_scheduler_error_required_fields), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onSave(
                        LlamaScheduledTaskEntity(
                            id = initialTask?.id ?: 0,
                            name = cleanName,
                            enabled = enabled,
                            serverId = selectedServerId.takeIf { it > 0L },
                            contextSize = contextSizeText.toIntOrNull()?.coerceAtLeast(512) ?: 8192,
                            systemPrompt = systemPrompt.trim().ifBlank { null },
                            taskPrompt = cleanPrompt,
                            apiParams = JSONObject(config.toParamMap()).toString(),
                            scheduleType = scheduleType,
                            oneTimeAtMillis = finalOneTime,
                            timeOfDayMinutes = minutes,
                            weekdaysMask = weekdaysMask,
                            dayOfMonth = dayOfMonthText.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                            timezoneId = ZoneId.systemDefault().id,
                            createdAt = initialTask?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            lastRunAtMillis = initialTask?.lastRunAtMillis
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private data class SchedulerPromptChoice(
    val name: String,
    val source: String,
    val content: String
)

@Composable
private fun SchedulerPromptPickerField(
    customProfiles: List<LlamaChatPromptProfileEntity>,
    savedSystemPrompts: List<SystemPromptEntity>,
    onSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val choices = remember(customProfiles, savedSystemPrompts, context) {
        val builtIns = LlamaBuiltInPromptProfiles.all.map { profile ->
            SchedulerPromptChoice(
                name = context.getString(profile.nameRes),
                source = context.getString(R.string.llama_scheduler_prompt_source_default),
                content = context.getString(profile.contentRes)
            )
        }
        val nativeProfiles = customProfiles.map { profile ->
            SchedulerPromptChoice(
                name = profile.name,
                source = context.getString(R.string.llama_scheduler_prompt_source_profile),
                content = profile.content
            )
        }
        val systemPrompts = savedSystemPrompts.map { prompt ->
            SchedulerPromptChoice(
                name = prompt.name,
                source = context.getString(R.string.llama_scheduler_prompt_source_system),
                content = prompt.content
            )
        }
        builtIns + nativeProfiles + systemPrompts
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = choices.isNotEmpty()
        ) {
            Text(
                text = if (choices.isEmpty()) {
                    stringResource(R.string.llama_scheduler_prompt_picker_empty)
                } else {
                    stringResource(R.string.llama_scheduler_prompt_picker)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                choice.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelected(choice.content)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulerToolNumberRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && text.toIntOrNull() != value) {
            text = value.toString()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }
                text = filtered
                filtered.toIntOrNull()
                    ?.takeIf { it in range }
                    ?.let(onValueChange)
            },
            modifier = Modifier
                .width(124.dp)
                .onFocusChanged { focusState ->
                    if (focused && !focusState.isFocused) {
                        val coerced = text.toIntOrNull()?.coerceIn(range) ?: value
                        text = coerced.toString()
                        onValueChange(coerced)
                    }
                    focused = focusState.isFocused
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun SchedulerNativeImageToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    backend: OnnxRuntimeBackend,
    onBackendChange: (OnnxRuntimeBackend) -> Unit,
    runtimeThreads: Int?,
    onRuntimeThreadsChange: (Int?) -> Unit,
    graphOptimizationLevel: OnnxGraphOptimizationLevel,
    onGraphOptimizationLevelChange: (OnnxGraphOptimizationLevel) -> Unit,
    unetBackendOverride: OnnxBackendOverride,
    onUnetBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeDecoderBackendOverride: OnnxBackendOverride,
    onVaeDecoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    vaeEncoderBackendOverride: OnnxBackendOverride,
    onVaeEncoderBackendOverrideChange: (OnnxBackendOverride) -> Unit,
    intraOpThreads: Int?,
    onIntraOpThreadsChange: (Int?) -> Unit,
    interOpThreads: Int?,
    onInterOpThreadsChange: (Int?) -> Unit,
    executionMode: OnnxExecutionMode,
    onExecutionModeChange: (OnnxExecutionMode) -> Unit,
    memoryPatternOptimization: Boolean,
    onMemoryPatternOptimizationChange: (Boolean) -> Unit,
    cpuArenaAllocator: Boolean,
    onCpuArenaAllocatorChange: (Boolean) -> Unit,
    nnapiCpuDisabled: Boolean,
    onNnapiCpuDisabledChange: (Boolean) -> Unit,
    nnapiUseFp16: Boolean,
    onNnapiUseFp16Change: (Boolean) -> Unit
) {
    var settingsExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.native_chat_image_generation_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.native_chat_image_generation_collapsed_summary,
                            width,
                            height,
                            steps,
                            cfg
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_image_generation_settings_title)
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.native_chat_image_generation_settings_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box {
                        OutlinedTextField(
                            value = model,
                            onValueChange = onModelChange,
                            label = { Text(stringResource(R.string.agent_image_generation_model_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { modelMenuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = stringResource(R.string.agent_image_generation_model_label)
                                    )
                                }
                            },
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            availableModels.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        onModelChange(option)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolNumberField(
                            value = width,
                            onValueChange = onWidthChange,
                            label = stringResource(R.string.onnx_image_gen_width_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolNumberField(
                            value = height,
                            onValueChange = onHeightChange,
                            label = stringResource(R.string.onnx_image_gen_height_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolNumberField(
                            value = steps,
                            onValueChange = onStepsChange,
                            label = stringResource(R.string.onnx_image_gen_steps_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolFloatField(
                            value = cfg,
                            onValueChange = onCfgChange,
                            label = stringResource(R.string.onnx_image_gen_cfg_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = seed,
                        onValueChange = onSeedChange,
                        label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.onnx_image_gen_seed_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = negativePrompt,
                        onValueChange = onNegativePromptChange,
                        label = { Text(stringResource(R.string.native_chat_image_generation_negative_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Text(
                        text = stringResource(R.string.native_chat_image_generation_runtime_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.onnx_image_gen_backend_label),
                        selected = backend,
                        values = OnnxRuntimeBackend.entries,
                        labelFor = {
                            when (it) {
                                OnnxRuntimeBackend.CPU -> stringResource(R.string.onnx_image_gen_backend_cpu)
                                OnnxRuntimeBackend.NNAPI -> stringResource(R.string.onnx_image_gen_backend_nnapi)
                            }
                        },
                        onSelected = onBackendChange
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolOptionalNumberField(
                            value = runtimeThreads,
                            onValueChange = onRuntimeThreadsChange,
                            label = stringResource(R.string.onnx_image_gen_runtime_threads_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolEnumDropdown(
                            label = stringResource(R.string.onnx_image_gen_graph_opt_title),
                            selected = graphOptimizationLevel,
                            values = OnnxGraphOptimizationLevel.entries,
                            labelFor = { it.name },
                            onSelected = onGraphOptimizationLevelChange,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolOptionalNumberField(
                            value = intraOpThreads,
                            onValueChange = onIntraOpThreadsChange,
                            label = stringResource(R.string.onnx_image_gen_intra_threads_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolOptionalNumberField(
                            value = interOpThreads,
                            onValueChange = onInterOpThreadsChange,
                            label = stringResource(R.string.onnx_image_gen_inter_threads_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.onnx_image_gen_execution_mode_title),
                        selected = executionMode,
                        values = OnnxExecutionMode.entries,
                        labelFor = { it.name },
                        onSelected = onExecutionModeChange
                    )

                    Text(
                        text = stringResource(R.string.native_chat_image_generation_component_backends_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.onnx_image_gen_component_backend_unet),
                        selected = unetBackendOverride,
                        values = OnnxBackendOverride.entries,
                        labelFor = { it.name },
                        onSelected = onUnetBackendOverrideChange
                    )
                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.onnx_image_gen_component_backend_vae_decoder),
                        selected = vaeDecoderBackendOverride,
                        values = OnnxBackendOverride.entries,
                        labelFor = { it.name },
                        onSelected = onVaeDecoderBackendOverrideChange
                    )
                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.onnx_image_gen_component_backend_vae_encoder),
                        selected = vaeEncoderBackendOverride,
                        values = OnnxBackendOverride.entries,
                        labelFor = { it.name },
                        onSelected = onVaeEncoderBackendOverrideChange
                    )

                    SchedulerImageToolSwitchRow(
                        title = stringResource(R.string.onnx_image_gen_memory_pattern_label),
                        checked = memoryPatternOptimization,
                        onCheckedChange = onMemoryPatternOptimizationChange
                    )
                    SchedulerImageToolSwitchRow(
                        title = stringResource(R.string.onnx_image_gen_cpu_arena_label),
                        checked = cpuArenaAllocator,
                        onCheckedChange = onCpuArenaAllocatorChange
                    )
                    SchedulerImageToolSwitchRow(
                        title = stringResource(R.string.onnx_image_gen_nnapi_cpu_disabled_label),
                        checked = nnapiCpuDisabled,
                        onCheckedChange = onNnapiCpuDisabledChange
                    )
                    SchedulerImageToolSwitchRow(
                        title = stringResource(R.string.onnx_image_gen_nnapi_fp16_label),
                        checked = nnapiUseFp16,
                        onCheckedChange = onNnapiUseFp16Change
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulerNativeSdImageToolSettings(
    model: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    componentRoles: List<SdComponentRole>,
    requiredRoles: Set<SdComponentRole>,
    componentOptions: (SdComponentRole) -> List<String>,
    selectedComponent: (SdComponentRole) -> String,
    onComponentChange: (SdComponentRole, String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    sampler: SamplingMethod,
    onSamplerChange: (SamplingMethod) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    flowShift: String,
    onFlowShiftChange: (String) -> Unit,
    showFlowShift: Boolean,
    diffusionFa: Boolean,
    onDiffusionFaChange: (Boolean) -> Unit,
    showDiffusionFa: Boolean,
    mmap: Boolean,
    onMmapChange: (Boolean) -> Unit,
    showMmap: Boolean,
    vaeConvDirect: Boolean,
    onVaeConvDirectChange: (Boolean) -> Unit,
    showVaeConvDirect: Boolean,
    qwenZeroCondT: Boolean,
    onQwenZeroCondTChange: (Boolean) -> Unit,
    showQwenZeroCondT: Boolean,
    chromaDisableDitMask: Boolean,
    onChromaDisableDitMaskChange: (Boolean) -> Unit,
    showChromaDisableDitMask: Boolean
) {
    var settingsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.native_chat_sd_image_generation_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.native_chat_image_generation_collapsed_summary,
                            width,
                            height,
                            steps,
                            cfg
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.native_chat_sd_image_generation_settings_title)
                )
            }

            AnimatedVisibility(visible = settingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SchedulerImageToolStringDropdown(
                        label = stringResource(R.string.agent_sd_image_generation_model_label),
                        selected = model,
                        values = availableModels,
                        onSelected = onModelChange
                    )

                    componentRoles.forEach { role ->
                        SchedulerSdComponentDropdown(
                            label = stringResource(schedulerSdComponentLabelRes(role)) +
                                if (role in requiredRoles) " *" else "",
                            selected = selectedComponent(role),
                            values = componentOptions(role),
                            allowNone = role !in requiredRoles,
                            onSelected = { onComponentChange(role, it) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolNumberField(
                            value = width,
                            onValueChange = onWidthChange,
                            label = stringResource(R.string.onnx_image_gen_width_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolNumberField(
                            value = height,
                            onValueChange = onHeightChange,
                            label = stringResource(R.string.onnx_image_gen_height_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolNumberField(
                            value = steps,
                            onValueChange = onStepsChange,
                            label = stringResource(R.string.onnx_image_gen_steps_label),
                            modifier = Modifier.weight(1f)
                        )
                        SchedulerImageToolFloatField(
                            value = cfg,
                            onValueChange = onCfgChange,
                            label = stringResource(R.string.onnx_image_gen_cfg_label),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SchedulerImageToolEnumDropdown(
                        label = stringResource(R.string.imagegen_sampler_label),
                        selected = sampler,
                        values = SamplingMethod.entries,
                        labelFor = { it.cliName },
                        onSelected = onSamplerChange
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulerImageToolNumberField(
                            value = threads,
                            onValueChange = onThreadsChange,
                            label = stringResource(R.string.imagegen_threads_label),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = seed,
                            onValueChange = onSeedChange,
                            label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    OutlinedTextField(
                        value = negativePrompt,
                        onValueChange = onNegativePromptChange,
                        label = { Text(stringResource(R.string.native_chat_image_generation_negative_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    if (showFlowShift) {
                        OutlinedTextField(
                            value = flowShift,
                            onValueChange = onFlowShiftChange,
                            label = { Text(stringResource(R.string.imagegen_flow_shift_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    if (showDiffusionFa) {
                        SchedulerImageToolSwitchRow(
                            title = stringResource(R.string.video_gen_diffusion_fa_label),
                            checked = diffusionFa,
                            onCheckedChange = onDiffusionFaChange
                        )
                    }
                    if (showMmap) {
                        SchedulerImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_mmap_label),
                            checked = mmap,
                            onCheckedChange = onMmapChange
                        )
                    }
                    if (showVaeConvDirect) {
                        SchedulerImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_vae_conv_direct_label),
                            checked = vaeConvDirect,
                            onCheckedChange = onVaeConvDirectChange
                        )
                    }
                    if (showQwenZeroCondT) {
                        SchedulerImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_qwen_zero_cond_t_label),
                            checked = qwenZeroCondT,
                            onCheckedChange = onQwenZeroCondTChange
                        )
                    }
                    if (showChromaDisableDitMask) {
                        SchedulerImageToolSwitchRow(
                            title = stringResource(R.string.imagegen_chroma_disable_dit_mask_label),
                            checked = chromaDisableDitMask,
                            onCheckedChange = onChromaDisableDitMaskChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulerImageToolNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun SchedulerImageToolFloatField(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftFloatTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun SchedulerImageToolOptionalNumberField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    DraftNullableIntTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun <T : Enum<T>> SchedulerImageToolEnumDropdown(
    label: String,
    selected: T,
    values: List<T>,
    labelFor: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = labelFor(selected),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = label
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulerImageToolStringDropdown(
    label: String,
    selected: String,
    values: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = values.isNotEmpty()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = selected.ifBlank { stringResource(R.string.image_tool_component_none) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulerSdComponentDropdown(
    label: String,
    selected: String,
    values: List<String>,
    allowNone: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selected.ifBlank { stringResource(R.string.image_tool_component_none) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(displayValue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.image_tool_component_none)) },
                    onClick = {
                        onSelected("")
                        expanded = false
                    }
                )
            }
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulerImageToolSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun schedulerSdComponentOptions(
    models: List<ModelEntity>,
    selectedModel: ModelEntity?,
    role: SdComponentRole
): List<String> {
    val (family, variant) = selectedModel?.resolvedSdFamily() ?: return emptyList()
    val modelType = role.toSchedulerModelType() ?: return emptyList()
    val resolvedFamily = family ?: return emptyList()
    return models
        .filter { model -> model.type == modelType && model.matchesSdFamily(resolvedFamily, variant) }
        .map { it.filename }
        .distinct()
}

private fun SdComponentRole.toSchedulerModelType(): ModelType? = when (this) {
    SdComponentRole.VAE -> ModelType.SD_VAE
    SdComponentRole.TAE -> ModelType.SD_TAE
    SdComponentRole.CLIP_L -> ModelType.SD_CLIP_L
    SdComponentRole.CLIP_G -> ModelType.SD_CLIP_G
    SdComponentRole.T5XXL -> ModelType.SD_T5XXL
    SdComponentRole.LLM -> ModelType.LLM
    SdComponentRole.LLM_VISION -> ModelType.VISION_PROJECTOR
    SdComponentRole.PHOTOMAKER -> ModelType.SD_PHOTOMAKER
    else -> null
}

private fun schedulerSdComponentLabelRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.VAE -> R.string.imagegen_component_vae
    SdComponentRole.TAE -> R.string.imagegen_component_tae
    SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_component_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
    else -> R.string.imagegen_component_main_model
}

@Composable
private fun StylizedSchedulePicker(
    scheduleType: String,
    oneTimeAtMillis: Long,
    onOneTimeAtMillisChange: (Long) -> Unit,
    timeOfDayText: String,
    onTimeOfDayTextChange: (String) -> Unit,
    weekdaysMask: Int,
    onWeekdaysMaskChange: (Int) -> Unit,
    dayOfMonthText: String,
    onDayOfMonthTextChange: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.llama_scheduler_when),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (scheduleType == LlamaScheduledTaskScheduleType.ONE_TIME) {
                DateSelector(
                    millis = oneTimeAtMillis,
                    onMillisChange = onOneTimeAtMillisChange
                )
            }
            TimeSelector(
                timeOfDayText = timeOfDayText,
                onTimeOfDayTextChange = onTimeOfDayTextChange
            )
            if (scheduleType == LlamaScheduledTaskScheduleType.WEEKLY) {
                Text(
                    text = stringResource(R.string.llama_scheduler_weekdays),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.values().forEach { day ->
                        FilterChip(
                            selected = LlamaScheduledTaskSchedule.hasWeekday(weekdaysMask, day),
                            onClick = {
                                onWeekdaysMaskChange(weekdaysMask xor LlamaScheduledTaskSchedule.weekdayBit(day))
                            },
                            label = { Text(localizedWeekdayShort(day)) }
                        )
                    }
                }
            }
            if (scheduleType == LlamaScheduledTaskScheduleType.MONTHLY) {
                OutlinedTextField(
                    value = dayOfMonthText,
                    onValueChange = onDayOfMonthTextChange,
                    label = { Text(stringResource(R.string.llama_scheduler_day_of_month)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.llama_scheduler_month_clamp_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.llama_scheduler_local_timezone, ZoneId.systemDefault().id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DateSelector(
    millis: Long,
    onMillisChange: (Long) -> Unit
) {
    val initialCalendar = remember(millis) { Calendar.getInstance().apply { timeInMillis = millis } }
    var yearText by remember { mutableStateOf(initialCalendar.get(Calendar.YEAR).toString()) }
    var monthText by remember { mutableStateOf((initialCalendar.get(Calendar.MONTH) + 1).toString()) }
    var dayText by remember { mutableStateOf(initialCalendar.get(Calendar.DAY_OF_MONTH).toString()) }

    fun updateDate(year: Int?, month: Int?, day: Int?) {
        if (year == null || month == null || day == null) return
        val updated = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.YEAR, year.coerceIn(1970, 9999))
            set(Calendar.MONTH, (month.coerceIn(1, 12) - 1))
            set(Calendar.DAY_OF_MONTH, day.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        onMillisChange(updated.timeInMillis)
    }

    fun setPreset(offsetDays: Int) {
        val updated = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, offsetDays)
            set(Calendar.HOUR_OF_DAY, initialCalendar.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, initialCalendar.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        yearText = updated.get(Calendar.YEAR).toString()
        monthText = (updated.get(Calendar.MONTH) + 1).toString()
        dayText = updated.get(Calendar.DAY_OF_MONTH).toString()
        onMillisChange(updated.timeInMillis)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(
                R.string.llama_scheduler_selected_date,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
            ),
            style = MaterialTheme.typography.labelLarge
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = yearText,
                onValueChange = {
                    yearText = it
                    updateDate(it.toIntOrNull(), monthText.toIntOrNull(), dayText.toIntOrNull())
                },
                label = { Text(stringResource(R.string.llama_scheduler_year)) },
                singleLine = true,
                modifier = Modifier.weight(1.2f)
            )
            OutlinedTextField(
                value = monthText,
                onValueChange = {
                    monthText = it
                    updateDate(yearText.toIntOrNull(), it.toIntOrNull(), dayText.toIntOrNull())
                },
                label = { Text(stringResource(R.string.llama_scheduler_month)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = dayText,
                onValueChange = {
                    dayText = it
                    updateDate(yearText.toIntOrNull(), monthText.toIntOrNull(), it.toIntOrNull())
                },
                label = { Text(stringResource(R.string.llama_scheduler_day)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { setPreset(0) }, label = { Text(stringResource(R.string.llama_scheduler_today)) })
            AssistChip(onClick = { setPreset(1) }, label = { Text(stringResource(R.string.llama_scheduler_tomorrow)) })
            AssistChip(onClick = { setPreset(7) }, label = { Text(stringResource(R.string.llama_scheduler_next_week)) })
        }
    }
}

@Composable
private fun TimeSelector(
    timeOfDayText: String,
    onTimeOfDayTextChange: (String) -> Unit
) {
    val initialMinutes = parseTimeOfDayMinutes(timeOfDayText) ?: 7 * 60
    var hourText by remember { mutableStateOf((initialMinutes / 60).toString().padStart(2, '0')) }
    var minuteText by remember { mutableStateOf((initialMinutes % 60).toString().padStart(2, '0')) }

    fun applyMinutes(rawMinutes: Int) {
        val normalized = ((rawMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
        hourText = (normalized / 60).toString().padStart(2, '0')
        minuteText = (normalized % 60).toString().padStart(2, '0')
        onTimeOfDayTextChange("%02d:%02d".format(normalized / 60, normalized % 60))
    }

    fun applyTyped(hour: String, minute: String) {
        val hourValue = hour.toIntOrNull()
        val minuteValue = minute.toIntOrNull()
        if (hourValue != null && minuteValue != null && hourValue in 0..23 && minuteValue in 0..59) {
            onTimeOfDayTextChange("%02d:%02d".format(hourValue, minuteValue))
        }
    }

    val currentMinutes = parseTimeOfDayMinutes("%s:%s".format(hourText, minuteText)) ?: initialMinutes
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.llama_scheduler_time),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = "%s:%s".format(hourText.padStart(2, '0'), minuteText.padStart(2, '0')),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { applyMinutes(currentMinutes - 15) }) {
                Text(stringResource(R.string.llama_scheduler_time_minus_15))
            }
            OutlinedButton(onClick = { applyMinutes(currentMinutes - 5) }) {
                Text(stringResource(R.string.llama_scheduler_time_minus_5))
            }
            OutlinedButton(onClick = { applyMinutes(currentMinutes + 5) }) {
                Text(stringResource(R.string.llama_scheduler_time_plus_5))
            }
            OutlinedButton(onClick = { applyMinutes(currentMinutes + 15) }) {
                Text(stringResource(R.string.llama_scheduler_time_plus_15))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hourText,
                onValueChange = {
                    hourText = it
                    applyTyped(it, minuteText)
                },
                label = { Text(stringResource(R.string.llama_scheduler_hour)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = minuteText,
                onValueChange = {
                    minuteText = it
                    applyTyped(hourText, it)
                },
                label = { Text(stringResource(R.string.llama_scheduler_minute)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun localizedWeekdayShort(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> stringResource(R.string.llama_scheduler_monday_short)
    DayOfWeek.TUESDAY -> stringResource(R.string.llama_scheduler_tuesday_short)
    DayOfWeek.WEDNESDAY -> stringResource(R.string.llama_scheduler_wednesday_short)
    DayOfWeek.THURSDAY -> stringResource(R.string.llama_scheduler_thursday_short)
    DayOfWeek.FRIDAY -> stringResource(R.string.llama_scheduler_friday_short)
    DayOfWeek.SATURDAY -> stringResource(R.string.llama_scheduler_saturday_short)
    DayOfWeek.SUNDAY -> stringResource(R.string.llama_scheduler_sunday_short)
}

@Composable
private fun ToolToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun scheduleName(type: String): String = when (type) {
    LlamaScheduledTaskScheduleType.ONE_TIME -> stringResource(R.string.llama_scheduler_once)
    LlamaScheduledTaskScheduleType.DAILY -> stringResource(R.string.llama_scheduler_daily)
    LlamaScheduledTaskScheduleType.WEEKLY -> stringResource(R.string.llama_scheduler_weekly)
    LlamaScheduledTaskScheduleType.MONTHLY -> stringResource(R.string.llama_scheduler_monthly)
    else -> type
}

@Composable
private fun scheduleLabel(task: LlamaScheduledTaskEntity): String =
    when (task.scheduleType) {
        LlamaScheduledTaskScheduleType.ONE_TIME -> stringResource(R.string.llama_scheduler_once)
        LlamaScheduledTaskScheduleType.DAILY -> stringResource(R.string.llama_scheduler_daily_at, minutesToText(task.timeOfDayMinutes))
        LlamaScheduledTaskScheduleType.WEEKLY -> stringResource(R.string.llama_scheduler_weekly_at, minutesToText(task.timeOfDayMinutes))
        LlamaScheduledTaskScheduleType.MONTHLY -> stringResource(R.string.llama_scheduler_monthly_at, task.dayOfMonth, minutesToText(task.timeOfDayMinutes))
        else -> task.scheduleType
    }

@Composable
private fun localizedStatus(status: String): String = when (status) {
    LlamaScheduledTaskLogStatus.PENDING_CATCH_UP -> stringResource(R.string.llama_scheduler_status_pending_catch_up)
    LlamaScheduledTaskLogStatus.QUEUED -> stringResource(R.string.llama_scheduler_status_queued)
    LlamaScheduledTaskLogStatus.RUNNING -> stringResource(R.string.llama_scheduler_status_running)
    LlamaScheduledTaskLogStatus.SUCCESS -> stringResource(R.string.llama_scheduler_status_success)
    LlamaScheduledTaskLogStatus.FAILED -> stringResource(R.string.llama_scheduler_status_failed)
    LlamaScheduledTaskLogStatus.SKIPPED -> stringResource(R.string.llama_scheduler_status_skipped)
    LlamaScheduledTaskLogStatus.CANCELLED -> stringResource(R.string.llama_scheduler_status_cancelled)
    else -> status
}

private fun parseTimeOfDayMinutes(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun minutesToText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun formatDateTime(millis: Long): String {
    val date = Date(millis)
    return "${DateFormat.getDateInstance(DateFormat.SHORT).format(date)} ${DateFormat.getTimeInstance(DateFormat.SHORT).format(date)}"
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
}
