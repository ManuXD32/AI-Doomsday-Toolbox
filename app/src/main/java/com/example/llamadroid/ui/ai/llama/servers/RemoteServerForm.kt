package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.ui.ai.llama.LlamaServerViewModel
import com.example.llamadroid.ui.ai.llama.LlamaServerViewModelFactory
import java.io.File

/**
 * Remote / other-app half of the unified server editor: host, port, engine (llama-server, Ollama,
 * llama-swap, LiteRT), model and capabilities, Whisper fallback and the default tool permissions.
 * It was extracted from the old connection dialog so the fields exist exactly once. Existing
 * `localLaunchProfileJson` of a legacy row is preserved untouched; use "Upgrade to managed
 * server" in the detail sheet to turn such a row into a card with its own preset.
 *
 * Saving goes through [LlamaServerViewModel.saveServer], which also refreshes the model metadata.
 */
@Composable
fun RemoteServerForm(
    initialServer: LlamaServerEntity?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val knowledgeBaseRepository = remember(database) { KnowledgeBaseRepository(context, database) }
    val liteRtModelRepository = remember(database) {
        LiteRtModelRepository(context = context, modelDao = database.liteRtModelDao())
    }
    val repository = remember(database) {
        LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
    }
    val serverViewModel: LlamaServerViewModel = viewModel(factory = LlamaServerViewModelFactory(repository))
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val whisperModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.WHISPER)
    }.collectAsState(initial = emptyList())
    val whisperModelPaths = remember(whisperModels) { whisperModels.map { it.path } }
    val liteRtModels by remember(liteRtModelRepository) {
        liteRtModelRepository.observeModels()
    }.collectAsState(initial = emptyList())
    val onLoadOllamaModels = serverViewModel::loadOllamaModels
    val onLoadOllamaCapabilities = serverViewModel::loadOllamaCapabilities
    val onLoadLlamaSwapModels = serverViewModel::loadLlamaSwapModels

    val dialogKey = initialServer?.id ?: -1L

    var name by remember(dialogKey) { mutableStateOf(initialServer?.name ?: "") }
    var host by remember(dialogKey) { mutableStateOf(initialServer?.host ?: "") }
    var port by remember(dialogKey) { mutableStateOf((initialServer?.port ?: 8080).toString()) }
    var engine by remember(dialogKey) {
        mutableStateOf(initialServer?.normalizedEngine() ?: LlamaServerEntity.ENGINE_LLAMA_SERVER)
    }
    var supportsVision by remember(dialogKey) { mutableStateOf(initialServer?.supportsVision ?: false) }
    var supportsAudio by remember(dialogKey) { mutableStateOf(initialServer?.supportsAudio ?: false) }
    var supportsVideo by remember(dialogKey) {
        mutableStateOf(
            initialServer?.takeIf {
                it.isLlamaServerEngine() || it.isLlamaSwapEngine()
            }?.supportsVideo == true
        )
    }
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
            supportsVideo = false
        }
    }
    val formErrors = validateRemoteForm(
        engine = engine,
        name = name,
        host = host,
        portText = port,
        modelName = ollamaModelName,
        liteRtModelSelected = selectedLiteRtModel != null
    )
    val canSave = formErrors.isEmpty()
    var attemptedSave by remember(dialogKey) { mutableStateOf(false) }

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
            supportsVideo = false
            return
        }
        isLoadingOllamaCapabilities = true
        statusMessage = null
        onLoadOllamaCapabilities(host, portInt, ollamaModelName.trim()) { result ->
            isLoadingOllamaCapabilities = false
            result.onSuccess { (vision, audio, video) ->
                supportsVision = vision
                supportsAudio = audio
                supportsVideo = video
            }.onFailure {
                supportsVision = false
                supportsAudio = false
                supportsVideo = false
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

    fun saveRemote() {
        val isLiteRtEngine = engine == LlamaServerEntity.ENGINE_LITERT_LM
        serverViewModel.saveServer(
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
                supportsVideo = if (
                    engine == LlamaServerEntity.ENGINE_LLAMA_SERVER ||
                    engine == LlamaServerEntity.ENGINE_LLAMA_SWAP
                ) supportsVideo else false,
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
                ),
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            supportsVideo = false
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
                            supportsVideo = false
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
                        supportsVideo = if (initialServer?.isLlamaServerEngine() == true) {
                            initialServer.supportsVideo
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
                        supportsVideo = false
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
                        supportsVideo = if (initialServer?.isLlamaSwapEngine() == true) {
                            initialServer.supportsVideo
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
                        supportsVideo = false
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
                if (engine == LlamaServerEntity.ENGINE_LLAMA_SERVER ||
                    engine == LlamaServerEntity.ENGINE_LLAMA_SWAP
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.llama_video_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = supportsVideo,
                            onCheckedChange = { supportsVideo = it }
                        )
                    }
                    Text(
                        text = stringResource(R.string.llama_video_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }


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
                            supportsVideo = false
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
                    if (supportsVideo) {
                        LlamaServerBadge(
                            label = stringResource(R.string.llama_video_mode),
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

    if (attemptedSave && formErrors.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            formErrors.forEach { error ->
                Text(
                    text = stringResource(remoteFormErrorRes(error)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = {
            if (!canSave) {
                attemptedSave = true
            } else {
                saveRemote()
                onSaved()
            }
        }) { Text(stringResource(R.string.action_save)) }
    }
}

internal fun remoteFormErrorRes(error: RemoteFormError): Int = when (error) {
    RemoteFormError.NAME_BLANK -> R.string.srv_error_name_blank
    RemoteFormError.HOST_BLANK -> R.string.srv_error_host_blank
    RemoteFormError.PORT_INVALID -> R.string.srv_error_port_invalid
    RemoteFormError.MODEL_BLANK -> R.string.srv_error_remote_model_blank
    RemoteFormError.LITERT_MODEL_MISSING -> R.string.srv_error_litert_model
}
