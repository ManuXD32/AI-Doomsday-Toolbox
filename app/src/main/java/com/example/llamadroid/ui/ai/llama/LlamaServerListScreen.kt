package com.example.llamadroid.ui.ai.llama

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.api.OllamaModel
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.navigation.Screen
import com.google.gson.Gson
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaServerListScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val database = AppDatabase.getDatabase(context)
    val knowledgeBaseRepository = remember { KnowledgeBaseRepository(context, database) }
    val liteRtModelRepository = remember {
        LiteRtModelRepository(
            context = context,
            modelDao = database.liteRtModelDao()
        )
    }
    val repository = remember {
        LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
    }
    val viewModel: LlamaServerViewModel = viewModel(factory = LlamaServerViewModelFactory(repository))
    val servers by viewModel.servers.collectAsState()
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val whisperModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.WHISPER)
    }.collectAsState(initial = emptyList())
    val liteRtModels by remember(liteRtModelRepository) {
        liteRtModelRepository.observeModels()
    }.collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<LlamaServerEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.llama_servers_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.llama_add_server))
                    }
                }
            )
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.llama_no_servers),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = servers, key = { it.id }) { server ->
                    LlamaServerCard(
                        server = server,
                        onConnect = {
                            viewModel.selectServer(server)
                            navController.navigate(Screen.LlamaChatList.route)
                        },
                        onEdit = { serverToEdit = server },
                        onDelete = { viewModel.deleteServer(server) },
                        onReload = { viewModel.refreshServerMetadata(server) }
                    )
                }
            }
        }
    }

    if (showAddDialog || serverToEdit != null) {
        LlamaServerDialog(
            initialServer = serverToEdit,
            whisperModelPaths = whisperModels.map { it.path },
            liteRtModels = liteRtModels,
            knowledgeBases = knowledgeBases,
            onDismiss = {
                showAddDialog = false
                serverToEdit = null
            },
            onSave = { server ->
                viewModel.saveServer(server)
                showAddDialog = false
                serverToEdit = null
            },
            onLoadOllamaModels = viewModel::loadOllamaModels,
            onLoadOllamaCapabilities = viewModel::loadOllamaCapabilities,
            onLoadLlamaSwapModels = viewModel::loadLlamaSwapModels
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaServerDialog(
    initialServer: LlamaServerEntity?,
    whisperModelPaths: List<String>,
    liteRtModels: List<LiteRtModelEntity>,
    knowledgeBases: List<KnowledgeBaseEntity>,
    onDismiss: () -> Unit,
    onSave: (LlamaServerEntity) -> Unit,
    onLoadOllamaModels: (String, Int, (Result<List<OllamaModel>>) -> Unit) -> Unit,
    onLoadOllamaCapabilities: (String, Int, String, (Result<Pair<Boolean, Boolean>>) -> Unit) -> Unit,
    onLoadLlamaSwapModels: (String, Int, (Result<List<String>>) -> Unit) -> Unit
) {
    val dialogKey = initialServer?.id ?: -1L
    val scrollState = rememberScrollState()

    var name by remember(dialogKey) { mutableStateOf(initialServer?.name ?: "") }
    var host by remember(dialogKey) { mutableStateOf(initialServer?.host ?: "") }
    var port by remember(dialogKey) { mutableStateOf((initialServer?.port ?: 8080).toString()) }
    var engine by remember(dialogKey) {
        mutableStateOf(initialServer?.normalizedEngine() ?: LlamaServerEntity.ENGINE_LLAMA_SERVER)
    }
    var supportsVision by remember(dialogKey) { mutableStateOf(initialServer?.supportsVision ?: false) }
    var supportsAudio by remember(dialogKey) { mutableStateOf(initialServer?.supportsAudio ?: false) }
    var whisperModelPath by remember(dialogKey) { mutableStateOf(initialServer?.whisperModelPath.orEmpty()) }
    var whisperLanguage by remember(dialogKey) {
        mutableStateOf(initialServer?.whisperLanguage?.ifBlank { LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE }
            ?: LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE)
    }
    var preferWhisperAudioTranscription by remember(dialogKey) {
        mutableStateOf(initialServer?.preferWhisperAudioTranscription ?: false)
    }
    var liteRtModelId by remember(dialogKey) { mutableStateOf(initialServer?.liteRtModelId) }
    var liteRtBackend by remember(dialogKey) {
        mutableStateOf(normalizeLiteRtBackend(initialServer?.liteRtBackend))
    }
    var ollamaModelName by remember(dialogKey) { mutableStateOf(initialServer?.modelName.orEmpty()) }
    var availableOllamaModels by remember(dialogKey) {
        mutableStateOf(initialServer?.modelName?.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList())
    }
    var showWhisperModelMenu by remember(dialogKey) { mutableStateOf(false) }
    var showWhisperLanguageMenu by remember(dialogKey) { mutableStateOf(false) }
    var showOllamaModelMenu by remember(dialogKey) { mutableStateOf(false) }
    var showLiteRtModelMenu by remember(dialogKey) { mutableStateOf(false) }
    var statusMessage by remember(dialogKey) { mutableStateOf<String?>(null) }
    var isLoadingOllamaModels by remember(dialogKey) { mutableStateOf(false) }
    var isLoadingOllamaCapabilities by remember(dialogKey) { mutableStateOf(false) }
    val initialToolDefaults = remember(dialogKey) {
        parseServerDefaultToolConfig(initialServer?.defaultApiParams)
    }
    var defaultToolsEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.toolsEnabled) }
    var defaultWebSearchEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.webSearchEnabled) }
    var defaultKiwixSearchEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.kiwixSearchEnabled) }
    var defaultKiwixServerUrl by remember(dialogKey) { mutableStateOf(initialToolDefaults.kiwixServerUrl) }
    var defaultFetchUrlEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.fetchUrlEnabled) }
    var defaultDeepResearchEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.deepResearchEnabled) }
    var defaultDeepResearchImportIntoSelectedKbEnabled by remember(dialogKey) {
        mutableStateOf(initialToolDefaults.deepResearchImportIntoSelectedKbEnabled)
    }
    var defaultDeepResearchSourceLimit by remember(dialogKey) { mutableStateOf(initialToolDefaults.deepResearchSourceLimit) }
    var defaultDateTimeEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.dateTimeEnabled) }
    var defaultCalculatorEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.calculatorEnabled) }
    var defaultNoteToolsEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.noteToolsEnabled) }
    var defaultTodoToolsEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.todoToolsEnabled) }
    var defaultCalendarToolsEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.calendarToolsEnabled) }
    var defaultAlarmToolsEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.alarmToolsEnabled) }
    var defaultKnowledgeBaseEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.knowledgeBaseEnabled) }
    var defaultKnowledgeBaseAutoContextEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.knowledgeBaseAutoContextEnabled) }
    var defaultSelectedKnowledgeBaseIds by remember(dialogKey) { mutableStateOf(initialToolDefaults.selectedKnowledgeBaseIds) }
    var defaultKnowledgeBaseMaxResults by remember(dialogKey) { mutableStateOf(initialToolDefaults.knowledgeBaseMaxResults) }
    var defaultImageGenerationEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.imageGenerationEnabled) }
    var defaultImageIterationEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.imageIterationEnabled) }
    var defaultBackgroundRemovalEnabled by remember(dialogKey) { mutableStateOf(initialToolDefaults.backgroundRemovalEnabled) }
    var defaultMaxToolRounds by remember(dialogKey) { mutableStateOf(initialToolDefaults.maxToolRounds) }

    val portInt = port.toIntOrNull() ?: 8080
    val mergedOllamaModels = remember(availableOllamaModels, ollamaModelName) {
        buildList {
            if (ollamaModelName.isNotBlank()) add(ollamaModelName)
            addAll(availableOllamaModels)
        }.distinct()
    }
    val selectedLiteRtModel = remember(liteRtModels, liteRtModelId) {
        liteRtModels.firstOrNull { it.id == liteRtModelId }
    }
    val directAudioInputAvailable = remember(engine, supportsAudio) {
        supportsAudio && (
            engine == LlamaServerEntity.ENGINE_LLAMA_SERVER ||
                engine == LlamaServerEntity.ENGINE_LLAMA_SWAP ||
                engine == LlamaServerEntity.ENGINE_LITERT_LM
            )
    }
    LaunchedEffect(engine, liteRtModels, selectedLiteRtModel) {
        if (engine == LlamaServerEntity.ENGINE_LITERT_LM && selectedLiteRtModel == null) {
            val firstModel = liteRtModels.firstOrNull()
            if (firstModel != null) {
                liteRtModelId = firstModel.id
                if (name.isBlank()) name = firstModel.displayName
            }
        }
    }
    LaunchedEffect(engine, selectedLiteRtModel?.id) {
        if (engine == LlamaServerEntity.ENGINE_LITERT_LM) {
            supportsVision = selectedLiteRtModel?.supportsLiteRtVision() == true
            supportsAudio = selectedLiteRtModel?.supportsLiteRtAudio() == true
        }
    }
    val canSave = name.isNotBlank() && when (engine) {
        LlamaServerEntity.ENGINE_LITERT_LM -> selectedLiteRtModel != null
        LlamaServerEntity.ENGINE_LLAMA_SERVER -> host.isNotBlank()
        else -> host.isNotBlank() && ollamaModelName.isNotBlank()
    }

    fun refreshOllamaModels() {
        if (host.isBlank()) return
        isLoadingOllamaModels = true
        statusMessage = null
        onLoadOllamaModels(host, portInt) { result ->
            isLoadingOllamaModels = false
            result.onSuccess { models ->
                availableOllamaModels = models.map { it.name }
                statusMessage = if (models.isEmpty()) {
                    null
                } else {
                    null
                }
            }.onFailure {
                statusMessage = it.message
            }
        }
    }

    fun refreshLlamaSwapModels() {
        if (host.isBlank()) return
        isLoadingOllamaModels = true
        statusMessage = null
        onLoadLlamaSwapModels(host, portInt) { result ->
            isLoadingOllamaModels = false
            result.onSuccess { models ->
                availableOllamaModels = models
                if (ollamaModelName.isBlank() && models.isNotEmpty()) {
                    ollamaModelName = models.first()
                }
            }.onFailure {
                statusMessage = it.message
            }
        }
    }

    fun refreshOllamaCapabilities() {
        if (host.isBlank() || ollamaModelName.isBlank()) {
            supportsVision = false
            supportsAudio = false
            return
        }
        isLoadingOllamaCapabilities = true
        statusMessage = null
        onLoadOllamaCapabilities(host, portInt, ollamaModelName.trim()) { result ->
            isLoadingOllamaCapabilities = false
            result.onSuccess { (vision, audio) ->
                supportsVision = vision
                supportsAudio = audio
            }.onFailure {
                supportsVision = false
                supportsAudio = false
                statusMessage = it.message
            }
        }
    }

    LaunchedEffect(dialogKey, engine) {
        if (engine == LlamaServerEntity.ENGINE_OLLAMA &&
            host.isNotBlank() &&
            availableOllamaModels.isEmpty()
        ) {
            refreshOllamaModels()
        } else if (engine == LlamaServerEntity.ENGINE_LLAMA_SWAP &&
            host.isNotBlank() &&
            availableOllamaModels.isEmpty()
        ) {
            refreshLlamaSwapModels()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialServer == null) {
                    stringResource(R.string.llama_add_server)
                } else {
                    stringResource(R.string.llama_edit_server)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.llama_server_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (engine != LlamaServerEntity.ENGINE_LITERT_LM) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it
                            if (engine == LlamaServerEntity.ENGINE_OLLAMA) {
                                supportsVision = false
                                supportsAudio = false
                            }
                            if (engine == LlamaServerEntity.ENGINE_LLAMA_SWAP) {
                                availableOllamaModels = emptyList()
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_server_host_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it.filter { char -> char.isDigit() }
                            if (engine == LlamaServerEntity.ENGINE_OLLAMA) {
                                supportsVision = false
                                supportsAudio = false
                            }
                            if (engine == LlamaServerEntity.ENGINE_LLAMA_SWAP) {
                                availableOllamaModels = emptyList()
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_server_port_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = stringResource(R.string.llama_server_engine_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = engine == LlamaServerEntity.ENGINE_LLAMA_SERVER,
                        onClick = {
                            engine = LlamaServerEntity.ENGINE_LLAMA_SERVER
                            if (port == "9292") {
                                port = "8080"
                            }
                            supportsVision = if (initialServer?.isLlamaServerEngine() == true) {
                                initialServer.supportsVision
                            } else {
                                false
                            }
                            supportsAudio = if (initialServer?.isLlamaServerEngine() == true) {
                                initialServer.supportsAudio
                            } else {
                                false
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_engine_llama_server)) }
                    )
                    FilterChip(
                        selected = engine == LlamaServerEntity.ENGINE_OLLAMA,
                        onClick = {
                            engine = LlamaServerEntity.ENGINE_OLLAMA
                            if (initialServer?.isOllamaEngine() != true) {
                                ollamaModelName = ""
                                availableOllamaModels = emptyList()
                            }
                            supportsVision = if (initialServer?.isOllamaEngine() == true) {
                                initialServer.supportsVision
                            } else {
                                false
                            }
                            supportsAudio = if (initialServer?.isOllamaEngine() == true) {
                                initialServer.supportsAudio
                            } else {
                                false
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_engine_ollama)) }
                    )
                    FilterChip(
                        selected = engine == LlamaServerEntity.ENGINE_LLAMA_SWAP,
                        onClick = {
                            engine = LlamaServerEntity.ENGINE_LLAMA_SWAP
                            if (initialServer?.isLlamaSwapEngine() != true) {
                                ollamaModelName = ""
                                availableOllamaModels = emptyList()
                            }
                            if (port == "8080") {
                                port = "9292"
                            }
                            supportsVision = if (initialServer?.isLlamaSwapEngine() == true) {
                                initialServer.supportsVision
                            } else {
                                false
                            }
                            supportsAudio = if (initialServer?.isLlamaSwapEngine() == true) {
                                initialServer.supportsAudio
                            } else {
                                false
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_engine_llama_swap)) }
                    )
                    FilterChip(
                        selected = engine == LlamaServerEntity.ENGINE_LITERT_LM,
                        onClick = {
                            engine = LlamaServerEntity.ENGINE_LITERT_LM
                            host = "local"
                            port = "0"
                            supportsVision = selectedLiteRtModel?.supportsLiteRtVision() == true
                            supportsAudio = selectedLiteRtModel?.supportsLiteRtAudio() == true
                            if (liteRtModelId == null) {
                                liteRtModelId = liteRtModels.firstOrNull()?.id
                            }
                            statusMessage = null
                        },
                        label = { Text(stringResource(R.string.llama_engine_litert)) }
                    )
                }

                if (engine == LlamaServerEntity.ENGINE_LLAMA_SERVER || engine == LlamaServerEntity.ENGINE_LLAMA_SWAP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.llama_supports_vision),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = supportsVision,
                            onCheckedChange = { supportsVision = it }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.llama_audio_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = supportsAudio,
                            onCheckedChange = { supportsAudio = it }
                        )
                    }
                    Text(
                        text = stringResource(R.string.llama_audio_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (engine == LlamaServerEntity.ENGINE_LLAMA_SWAP) {
                        Text(
                            text = stringResource(R.string.llama_swap_model_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = ollamaModelName,
                                onValueChange = {
                                    ollamaModelName = it
                                    statusMessage = null
                                },
                                label = { Text(stringResource(R.string.llama_swap_model_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showOllamaModelMenu = !showOllamaModelMenu }
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.llama_swap_available_models)
                                        )
                                    }
                                }
                            )
                            androidx.compose.material3.DropdownMenu(
                                expanded = showOllamaModelMenu && mergedOllamaModels.isNotEmpty(),
                                onDismissRequest = { showOllamaModelMenu = false }
                            ) {
                                mergedOllamaModels.forEach { modelName ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(modelName) },
                                        onClick = {
                                            ollamaModelName = modelName
                                            showOllamaModelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { refreshLlamaSwapModels() },
                            enabled = !isLoadingOllamaModels && host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ollama_refresh_models))
                        }
                        if (isLoadingOllamaModels) {
                            Text(
                                text = stringResource(R.string.llama_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (mergedOllamaModels.isEmpty()) {
                            Text(
                                text = stringResource(R.string.llama_swap_no_models_loaded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (engine == LlamaServerEntity.ENGINE_LITERT_LM) {
                    Text(
                        text = stringResource(R.string.litert_models_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedLiteRtModel?.displayName ?: stringResource(R.string.litert_models_empty),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.litert_models_display_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showLiteRtModelMenu = !showLiteRtModelMenu },
                                    enabled = liteRtModels.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.litert_models_title)
                                    )
                                }
                            }
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = showLiteRtModelMenu && liteRtModels.isNotEmpty(),
                            onDismissRequest = { showLiteRtModelMenu = false }
                        ) {
                            liteRtModels.forEach { model ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        liteRtModelId = model.id
                                        if (name.isBlank()) name = model.displayName
                                        showLiteRtModelMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (liteRtModels.isEmpty()) {
                        Text(
                            text = stringResource(R.string.litert_models_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.litert_backend_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            LITERT_BACKEND_AUTO to stringResource(R.string.general_acceleration_mode_auto),
                            LITERT_BACKEND_CPU to stringResource(R.string.general_acceleration_mode_cpu),
                            LITERT_BACKEND_GPU to stringResource(R.string.litert_backend_gpu)
                        ).forEach { (backend, label) ->
                            FilterChip(
                                selected = liteRtBackend == backend,
                                onClick = { liteRtBackend = backend },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.litert_backend_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.litert_gpu_isolation_required_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.llama_ollama_model_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = ollamaModelName,
                            onValueChange = {
                                ollamaModelName = it
                                supportsVision = false
                                supportsAudio = false
                                statusMessage = null
                            },
                            label = { Text(stringResource(R.string.llama_ollama_model_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showOllamaModelMenu = !showOllamaModelMenu }
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.llama_ollama_available_models)
                                    )
                                }
                            }
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = showOllamaModelMenu && mergedOllamaModels.isNotEmpty(),
                            onDismissRequest = { showOllamaModelMenu = false }
                        ) {
                            mergedOllamaModels.forEach { modelName ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(modelName) },
                                    onClick = {
                                        ollamaModelName = modelName
                                        showOllamaModelMenu = false
                                        refreshOllamaCapabilities()
                                    }
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { refreshOllamaModels() },
                            enabled = !isLoadingOllamaModels && host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ollama_refresh_models))
                        }
                        OutlinedButton(
                            onClick = { refreshOllamaCapabilities() },
                            enabled = !isLoadingOllamaCapabilities && host.isNotBlank() && ollamaModelName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.llama_refresh_capabilities))
                        }
                    }
                    if (isLoadingOllamaModels || isLoadingOllamaCapabilities) {
                        Text(
                            text = stringResource(R.string.llama_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (mergedOllamaModels.isEmpty()) {
                        Text(
                            text = stringResource(R.string.llama_ollama_no_models_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (supportsVision) {
                            LlamaServerBadge(
                                label = stringResource(R.string.llama_badge_vision),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (supportsAudio) {
                            LlamaServerBadge(
                                label = stringResource(R.string.llama_badge_audio),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.llama_whisper_fallback_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                if (directAudioInputAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.llama_use_whisper_for_audio),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = preferWhisperAudioTranscription,
                            onCheckedChange = { preferWhisperAudioTranscription = it }
                        )
                    }
                    Text(
                        text = stringResource(R.string.llama_use_whisper_for_audio_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.llama_whisper_required_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = whisperModelPath.ifBlank { stringResource(R.string.llama_whisper_model_auto) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.llama_whisper_model_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showWhisperModelMenu = !showWhisperModelMenu },
                                enabled = whisperModelPaths.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.llama_whisper_model_label)
                                )
                            }
                        }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = showWhisperModelMenu && whisperModelPaths.isNotEmpty(),
                        onDismissRequest = { showWhisperModelMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.llama_whisper_model_auto)) },
                            onClick = {
                                whisperModelPath = ""
                                showWhisperModelMenu = false
                            }
                        )
                        whisperModelPaths.forEach { path ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(File(path).name) },
                                onClick = {
                                    whisperModelPath = path
                                    showWhisperModelMenu = false
                                }
                            )
                        }
                    }
                }
                if (whisperModelPaths.isEmpty()) {
                    Text(
                        text = stringResource(R.string.llama_whisper_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = WhisperLanguages.languages.firstOrNull { it.first == whisperLanguage }?.second
                            ?: stringResource(R.string.whisper_auto_detect),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.llama_whisper_language_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showWhisperLanguageMenu = !showWhisperLanguageMenu }) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.llama_whisper_language_label)
                                )
                            }
                        }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = showWhisperLanguageMenu,
                        onDismissRequest = { showWhisperLanguageMenu = false }
                    ) {
                        WhisperLanguages.languages.forEach { (code, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    whisperLanguage = code
                                    showWhisperLanguageMenu = false
                                }
                            )
                        }
                    }
                }

                if (engine == LlamaServerEntity.ENGINE_LITERT_LM) {
                    Text(
                        text = stringResource(R.string.litert_tools_unavailable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NativeServerToolDefaultsSection(
                    toolsEnabled = defaultToolsEnabled,
                    onToolsEnabledChange = { defaultToolsEnabled = it },
                    webSearchEnabled = defaultWebSearchEnabled,
                    onWebSearchEnabledChange = { defaultWebSearchEnabled = it },
                    kiwixSearchEnabled = defaultKiwixSearchEnabled,
                    onKiwixSearchEnabledChange = { defaultKiwixSearchEnabled = it },
                    kiwixServerUrl = defaultKiwixServerUrl,
                    onKiwixServerUrlChange = { defaultKiwixServerUrl = it },
                    fetchUrlEnabled = defaultFetchUrlEnabled,
                    onFetchUrlEnabledChange = { defaultFetchUrlEnabled = it },
                    deepResearchEnabled = defaultDeepResearchEnabled,
                    onDeepResearchEnabledChange = { defaultDeepResearchEnabled = it },
                    deepResearchImportIntoSelectedKbEnabled = defaultDeepResearchImportIntoSelectedKbEnabled,
                    onDeepResearchImportIntoSelectedKbEnabledChange = { defaultDeepResearchImportIntoSelectedKbEnabled = it },
                    deepResearchSourceLimit = defaultDeepResearchSourceLimit,
                    onDeepResearchSourceLimitChange = { defaultDeepResearchSourceLimit = it },
                    dateTimeEnabled = defaultDateTimeEnabled,
                    onDateTimeEnabledChange = { defaultDateTimeEnabled = it },
                    calculatorEnabled = defaultCalculatorEnabled,
                    onCalculatorEnabledChange = { defaultCalculatorEnabled = it },
                    noteToolsEnabled = defaultNoteToolsEnabled,
                    onNoteToolsEnabledChange = { defaultNoteToolsEnabled = it },
                    todoToolsEnabled = defaultTodoToolsEnabled,
                    onTodoToolsEnabledChange = { defaultTodoToolsEnabled = it },
                    knowledgeBases = knowledgeBases,
                    knowledgeBaseEnabled = defaultKnowledgeBaseEnabled,
                    onKnowledgeBaseEnabledChange = { defaultKnowledgeBaseEnabled = it },
                    knowledgeBaseAutoContextEnabled = defaultKnowledgeBaseAutoContextEnabled,
                    onKnowledgeBaseAutoContextEnabledChange = { defaultKnowledgeBaseAutoContextEnabled = it },
                    selectedKnowledgeBaseIds = defaultSelectedKnowledgeBaseIds,
                    onSelectedKnowledgeBaseIdsChange = { defaultSelectedKnowledgeBaseIds = it },
                    knowledgeBaseMaxResults = defaultKnowledgeBaseMaxResults,
                    onKnowledgeBaseMaxResultsChange = { defaultKnowledgeBaseMaxResults = it },
                    calendarToolsEnabled = defaultCalendarToolsEnabled,
                    onCalendarToolsEnabledChange = { defaultCalendarToolsEnabled = it },
                    alarmToolsEnabled = defaultAlarmToolsEnabled,
                    onAlarmToolsEnabledChange = { defaultAlarmToolsEnabled = it },
                    imageGenerationEnabled = defaultImageGenerationEnabled,
                    onImageGenerationEnabledChange = { defaultImageGenerationEnabled = it },
                    imageIterationEnabled = defaultImageIterationEnabled,
                    onImageIterationEnabledChange = { defaultImageIterationEnabled = it },
                    backgroundRemovalEnabled = defaultBackgroundRemovalEnabled,
                    onBackgroundRemovalEnabledChange = { defaultBackgroundRemovalEnabled = it },
                    maxToolRounds = defaultMaxToolRounds,
                    onMaxToolRoundsChange = { defaultMaxToolRounds = it }
                )

                statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!canSave) return@TextButton
                    val isLiteRtEngine = engine == LlamaServerEntity.ENGINE_LITERT_LM
                    onSave(
                        (initialServer ?: LlamaServerEntity(
                            name = name.trim(),
                            host = if (isLiteRtEngine) "local" else host.trim(),
                            port = if (isLiteRtEngine) 0 else portInt
                        )).copy(
                            name = name.trim(),
                            host = if (isLiteRtEngine) "local" else host.trim(),
                            port = if (isLiteRtEngine) 0 else portInt,
                            engine = engine,
                            supportsVision = if (isLiteRtEngine) selectedLiteRtModel?.supportsLiteRtVision() == true else supportsVision,
                            supportsAudio = if (isLiteRtEngine) selectedLiteRtModel?.supportsLiteRtAudio() == true else supportsAudio,
                            modelName = when (engine) {
                                LlamaServerEntity.ENGINE_OLLAMA,
                                LlamaServerEntity.ENGINE_LLAMA_SWAP -> ollamaModelName.trim().ifBlank { null }
                                LlamaServerEntity.ENGINE_LITERT_LM -> selectedLiteRtModel?.displayName
                                else -> initialServer?.modelName
                            },
                            liteRtModelId = if (isLiteRtEngine) selectedLiteRtModel?.id else null,
                            liteRtBackend = if (isLiteRtEngine) normalizeLiteRtBackend(liteRtBackend) else LITERT_BACKEND_AUTO,
                            whisperModelPath = whisperModelPath.ifBlank { null },
                            whisperLanguage = whisperLanguage.ifBlank { LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE },
                            preferWhisperAudioTranscription = preferWhisperAudioTranscription && directAudioInputAvailable,
                            defaultApiParams = buildServerDefaultToolApiParams(
                                toolsEnabled = defaultToolsEnabled,
                                webSearchEnabled = defaultWebSearchEnabled,
                                kiwixSearchEnabled = defaultKiwixSearchEnabled,
                                kiwixServerUrl = defaultKiwixServerUrl,
                                fetchUrlEnabled = defaultFetchUrlEnabled,
                                deepResearchEnabled = defaultDeepResearchEnabled,
                                deepResearchImportIntoSelectedKbEnabled = defaultDeepResearchImportIntoSelectedKbEnabled,
                                deepResearchSourceLimit = defaultDeepResearchSourceLimit,
                                dateTimeEnabled = defaultDateTimeEnabled,
                                calculatorEnabled = defaultCalculatorEnabled,
                                noteToolsEnabled = defaultNoteToolsEnabled,
                                todoToolsEnabled = defaultTodoToolsEnabled,
                                knowledgeBaseEnabled = defaultKnowledgeBaseEnabled,
                                knowledgeBaseAutoContextEnabled = defaultKnowledgeBaseAutoContextEnabled,
                                selectedKnowledgeBaseIds = defaultSelectedKnowledgeBaseIds,
                                knowledgeBaseMaxResults = defaultKnowledgeBaseMaxResults,
                                calendarToolsEnabled = defaultCalendarToolsEnabled,
                                alarmToolsEnabled = defaultAlarmToolsEnabled,
                                imageGenerationEnabled = defaultImageGenerationEnabled,
                                imageIterationEnabled = defaultImageIterationEnabled,
                                backgroundRemovalEnabled = defaultBackgroundRemovalEnabled,
                                maxToolRounds = defaultMaxToolRounds
                            )
                        )
                    )
                },
                enabled = canSave
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

@Composable
private fun NativeServerToolDefaultsSection(
    toolsEnabled: Boolean,
    onToolsEnabledChange: (Boolean) -> Unit,
    webSearchEnabled: Boolean,
    onWebSearchEnabledChange: (Boolean) -> Unit,
    kiwixSearchEnabled: Boolean,
    onKiwixSearchEnabledChange: (Boolean) -> Unit,
    kiwixServerUrl: String,
    onKiwixServerUrlChange: (String) -> Unit,
    fetchUrlEnabled: Boolean,
    onFetchUrlEnabledChange: (Boolean) -> Unit,
    deepResearchEnabled: Boolean,
    onDeepResearchEnabledChange: (Boolean) -> Unit,
    deepResearchImportIntoSelectedKbEnabled: Boolean,
    onDeepResearchImportIntoSelectedKbEnabledChange: (Boolean) -> Unit,
    deepResearchSourceLimit: Int,
    onDeepResearchSourceLimitChange: (Int) -> Unit,
    dateTimeEnabled: Boolean,
    onDateTimeEnabledChange: (Boolean) -> Unit,
    calculatorEnabled: Boolean,
    onCalculatorEnabledChange: (Boolean) -> Unit,
    noteToolsEnabled: Boolean,
    onNoteToolsEnabledChange: (Boolean) -> Unit,
    todoToolsEnabled: Boolean,
    onTodoToolsEnabledChange: (Boolean) -> Unit,
    knowledgeBases: List<KnowledgeBaseEntity>,
    knowledgeBaseEnabled: Boolean,
    onKnowledgeBaseEnabledChange: (Boolean) -> Unit,
    knowledgeBaseAutoContextEnabled: Boolean,
    onKnowledgeBaseAutoContextEnabledChange: (Boolean) -> Unit,
    selectedKnowledgeBaseIds: List<Long>,
    onSelectedKnowledgeBaseIdsChange: (List<Long>) -> Unit,
    knowledgeBaseMaxResults: Int,
    onKnowledgeBaseMaxResultsChange: (Int) -> Unit,
    calendarToolsEnabled: Boolean,
    onCalendarToolsEnabledChange: (Boolean) -> Unit,
    alarmToolsEnabled: Boolean,
    onAlarmToolsEnabledChange: (Boolean) -> Unit,
    imageGenerationEnabled: Boolean,
    onImageGenerationEnabledChange: (Boolean) -> Unit,
    imageIterationEnabled: Boolean,
    onImageIterationEnabledChange: (Boolean) -> Unit,
    backgroundRemovalEnabled: Boolean,
    onBackgroundRemovalEnabledChange: (Boolean) -> Unit,
    maxToolRounds: Int,
    onMaxToolRoundsChange: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.llama_server_default_tools_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.llama_server_default_tools_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ServerToolDefaultSwitchRow(
                title = stringResource(R.string.llama_tools_master_switch),
                checked = toolsEnabled,
                onCheckedChange = onToolsEnabledChange
            )
            if (toolsEnabled) {
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_web_search),
                    checked = webSearchEnabled,
                    onCheckedChange = onWebSearchEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_kiwix_search),
                    checked = kiwixSearchEnabled,
                    onCheckedChange = onKiwixSearchEnabledChange
                )
                if (kiwixSearchEnabled) {
                    OutlinedTextField(
                        value = kiwixServerUrl,
                        onValueChange = onKiwixServerUrlChange,
                        label = { Text(stringResource(R.string.llama_tool_kiwix_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_fetch_url),
                    checked = fetchUrlEnabled,
                    onCheckedChange = onFetchUrlEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_deep_research),
                    checked = deepResearchEnabled,
                    onCheckedChange = onDeepResearchEnabledChange
                )
                if (deepResearchEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_deep_research_import_selected_kb),
                        checked = deepResearchImportIntoSelectedKbEnabled,
                        onCheckedChange = onDeepResearchImportIntoSelectedKbEnabledChange
                    )
                    DraftIntTextField(
                        value = deepResearchSourceLimit,
                        onValueChange = onDeepResearchSourceLimitChange,
                        valueRange = NativeChatToolConfig.MIN_DEEP_RESEARCH_SOURCE_LIMIT..Int.MAX_VALUE,
                        label = { Text(stringResource(R.string.llama_tool_deep_research_source_limit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_datetime),
                    checked = dateTimeEnabled,
                    onCheckedChange = onDateTimeEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_calculator),
                    checked = calculatorEnabled,
                    onCheckedChange = onCalculatorEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_notes),
                    checked = noteToolsEnabled,
                    onCheckedChange = onNoteToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_todo_lists),
                    checked = todoToolsEnabled,
                    onCheckedChange = onTodoToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_knowledge_bases),
                    checked = knowledgeBaseEnabled,
                    onCheckedChange = onKnowledgeBaseEnabledChange
                )
                if (knowledgeBaseEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_kb_auto_context),
                        checked = knowledgeBaseAutoContextEnabled,
                        onCheckedChange = onKnowledgeBaseAutoContextEnabledChange
                    )
                    if (knowledgeBases.isEmpty()) {
                        Text(
                            text = stringResource(R.string.kb_no_bases_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            knowledgeBases.forEach { kb ->
                                val selected = kb.id in selectedKnowledgeBaseIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            onSelectedKnowledgeBaseIdsChange(
                                                if (selected) {
                                                    selectedKnowledgeBaseIds - kb.id
                                                } else {
                                                    (selectedKnowledgeBaseIds + kb.id).distinct()
                                                }
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            onSelectedKnowledgeBaseIdsChange(
                                                if (checked) {
                                                    (selectedKnowledgeBaseIds + kb.id).distinct()
                                                } else {
                                                    selectedKnowledgeBaseIds - kb.id
                                                }
                                            )
                                        }
                                    )
                                    Text(
                                        text = kb.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    DraftIntTextField(
                        value = knowledgeBaseMaxResults,
                        onValueChange = onKnowledgeBaseMaxResultsChange,
                        valueRange = NativeChatToolConfig.MIN_KB_RESULTS..NativeChatToolConfig.MAX_KB_RESULTS,
                        label = { Text(stringResource(R.string.llama_tool_kb_max_results)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_calendar),
                    checked = calendarToolsEnabled,
                    onCheckedChange = onCalendarToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_alarms),
                    checked = alarmToolsEnabled,
                    onCheckedChange = onAlarmToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_image_generation),
                    checked = imageGenerationEnabled,
                    onCheckedChange = onImageGenerationEnabledChange
                )
                if (imageGenerationEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_image_iteration),
                        checked = imageIterationEnabled,
                        onCheckedChange = onImageIterationEnabledChange
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_bgr),
                    checked = backgroundRemovalEnabled,
                    onCheckedChange = onBackgroundRemovalEnabledChange
                )
                DraftIntTextField(
                    value = maxToolRounds,
                    onValueChange = onMaxToolRoundsChange,
                    valueRange = NativeChatToolConfig.MIN_TOOL_ROUNDS..NativeChatToolConfig.MAX_TOOL_ROUNDS,
                    label = { Text(stringResource(R.string.llama_tool_max_rounds)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ServerToolDefaultSwitchRow(
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
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun parseServerDefaultToolConfig(apiParams: String?): NativeChatToolConfig {
    if (apiParams.isNullOrBlank()) return NativeChatToolConfig()
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        val params = Gson().fromJson(apiParams, Map::class.java) as? Map<String, Any>
        NativeChatToolConfig.fromParams(params.orEmpty())
    }.getOrDefault(NativeChatToolConfig())
}

private fun buildServerDefaultToolApiParams(
    toolsEnabled: Boolean,
    webSearchEnabled: Boolean,
    kiwixSearchEnabled: Boolean,
    kiwixServerUrl: String,
    fetchUrlEnabled: Boolean,
    deepResearchEnabled: Boolean,
    deepResearchImportIntoSelectedKbEnabled: Boolean,
    deepResearchSourceLimit: Int,
    dateTimeEnabled: Boolean,
    calculatorEnabled: Boolean,
    noteToolsEnabled: Boolean,
    todoToolsEnabled: Boolean,
    knowledgeBaseEnabled: Boolean,
    knowledgeBaseAutoContextEnabled: Boolean,
    selectedKnowledgeBaseIds: List<Long>,
    knowledgeBaseMaxResults: Int,
    calendarToolsEnabled: Boolean,
    alarmToolsEnabled: Boolean,
    imageGenerationEnabled: Boolean,
    imageIterationEnabled: Boolean,
    backgroundRemovalEnabled: Boolean,
    maxToolRounds: Int
): String {
    return Gson().toJson(
        NativeChatToolConfig(
            toolsEnabled = toolsEnabled,
            webSearchEnabled = webSearchEnabled,
            kiwixSearchEnabled = kiwixSearchEnabled,
            kiwixServerUrl = kiwixServerUrl.ifBlank { NativeChatToolConfig.DEFAULT_KIWIX_URL },
            fetchUrlEnabled = fetchUrlEnabled,
            deepResearchEnabled = deepResearchEnabled,
            deepResearchImportIntoSelectedKbEnabled = deepResearchImportIntoSelectedKbEnabled,
            deepResearchSourceLimit = deepResearchSourceLimit,
            dateTimeEnabled = dateTimeEnabled,
            calculatorEnabled = calculatorEnabled,
            noteToolsEnabled = noteToolsEnabled,
            todoToolsEnabled = todoToolsEnabled,
            knowledgeBaseEnabled = knowledgeBaseEnabled,
            knowledgeBaseAutoContextEnabled = knowledgeBaseAutoContextEnabled,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            knowledgeBaseMaxResults = knowledgeBaseMaxResults,
            calendarToolsEnabled = calendarToolsEnabled,
            alarmToolsEnabled = alarmToolsEnabled,
            imageGenerationEnabled = imageGenerationEnabled,
            imageIterationEnabled = imageIterationEnabled,
            backgroundRemovalEnabled = backgroundRemovalEnabled,
            maxToolRounds = maxToolRounds
        ).toParamMap()
    )
}

@Composable
fun LlamaServerCard(
    server: LlamaServerEntity,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReload: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onConnect
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onReload
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.llama_reload_model),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onConnect
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.llama_connect),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onEdit
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.llama_edit_server))
                    }
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onDelete
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.llama_delete_server),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            server.modelName?.takeIf { it.isNotBlank() }?.let { modelName ->
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LlamaServerBadge(
                    label = when {
                        server.isOllamaEngine() -> stringResource(R.string.llama_engine_ollama)
                        server.isLlamaSwapEngine() -> stringResource(R.string.llama_engine_llama_swap)
                        server.isLiteRtEngine() -> stringResource(R.string.llama_engine_litert)
                        else -> stringResource(R.string.llama_engine_llama_server)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (server.supportsVision) {
                    LlamaServerBadge(
                        label = stringResource(R.string.llama_badge_vision),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (server.supportsAudio) {
                    LlamaServerBadge(
                        label = stringResource(R.string.llama_badge_audio),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (server.preferWhisperAudioTranscription || !server.whisperModelPath.isNullOrBlank()) {
                    LlamaServerBadge(
                        label = stringResource(R.string.llama_badge_whisper),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!server.isLiteRtEngine() && parseServerDefaultToolConfig(server.defaultApiParams).toolsEnabled) {
                    LlamaServerBadge(
                        label = stringResource(R.string.llama_badge_default_tools),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            server.whisperModelPath?.takeIf { it.isNotBlank() }?.let { whisperPath ->
                Text(
                    text = stringResource(
                        R.string.llama_whisper_fallback_summary,
                        File(whisperPath).name
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LlamaServerBadge(
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
