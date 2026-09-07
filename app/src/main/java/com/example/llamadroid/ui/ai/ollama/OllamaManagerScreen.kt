package com.example.llamadroid.ui.ai.ollama

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.api.OllamaModel
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.OllamaServerEntity
import com.example.llamadroid.data.repository.OllamaRepository
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

private enum class ModelCreateMode {
    RemoteApi,
    LocalCli
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaManagerScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val database = AppDatabase.getDatabase(context)
    val repository = remember { OllamaRepository(database.ollamaServerDao()) }
    val sshService = remember { SSHService(context) }
    // Ideally use Hilt or a factory provider
    val viewModel: OllamaViewModel = viewModel(factory = OllamaViewModelFactory(repository))
    
    val uiState by viewModel.uiState.collectAsState()
    val termuxSshConnected by SSHService.isConnected.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddServerDialog by rememberSaveable { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<OllamaServerEntity?>(null) }
    var showModelPullDialog by remember { mutableStateOf(false) }

    // Modelfile Editor State
    var showModelfileDialog by remember { mutableStateOf(false) }
    var modelfileContent by remember { mutableStateOf("") }
    var modelfileOriginName by remember { mutableStateOf("") }
    var newModelName by remember { mutableStateOf("") }
    var createMode by remember(showModelfileDialog, modelfileOriginName) { mutableStateOf(ModelCreateMode.RemoteApi) }
    val modelfileAnalysis = remember(modelfileContent, modelfileOriginName, newModelName) {
        repository.inspectModelfile(
            name = if (newModelName.isBlank()) "preview" else newModelName,
            fallbackFromModel = modelfileOriginName,
            modelfile = modelfileContent
        )
    }
    val normalizedNewModelName = remember(newModelName) {
        repository.normalizeCreateModelName(newModelName)
    }
    val modelNameValid = remember(normalizedNewModelName) {
        normalizedNewModelName.isBlank() || repository.isValidCreateModelName(normalizedNewModelName)
    }
    val remoteBlocked = modelfileAnalysis.unsupportedDirectives.isNotEmpty() || !modelfileAnalysis.fromSource.remoteApiSupported
    val localBlocked = !modelfileAnalysis.fromSource.localCliSupported
    val canCreateModel = normalizedNewModelName.isNotBlank() && modelNameValid && when (createMode) {
        ModelCreateMode.RemoteApi -> !remoteBlocked
        ModelCreateMode.LocalCli -> termuxSshConnected && !localBlocked
    }

    AppScreenScaffold(
        title = stringResource(R.string.ollama_title),
        onBack = { navController.popBackStack() },
        actions = {
            if (selectedTab == 0) {
                IconButton(onClick = {
                    walkthroughTargets?.recordEvent("ollama.models")
                    showAddServerDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ollama_add_server))
                }
            } else {
                IconButton(
                    onClick = {
                        walkthroughTargets?.recordEvent("ollama.models")
                        viewModel.refreshSelectedServerModels()
                    },
                    enabled = uiState.selectedServer != null
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ollama_refresh_models))
                }
                IconButton(onClick = {
                    walkthroughTargets?.recordEvent("ollama.models")
                    showModelPullDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ollama_pull_model_title))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.walkthroughTarget("ollama.models")
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.ollama_servers)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        walkthroughTargets?.recordEvent("ollama.models")
                        selectedTab = 1
                    },
                    text = { Text(stringResource(R.string.ollama_models)) }
                )
            }

            when (selectedTab) {
                0 -> ServerList(
                    servers = uiState.servers,
                    selectedServer = uiState.selectedServer,
                    onSelect = { viewModel.selectServer(it) },
                    onEdit = { serverToEdit = it },
                    onAdd = { showAddServerDialog = true },
                    onDelete = { viewModel.deleteServer(it) }
                )
                1 -> ModelList(
                    models = uiState.models,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    downloadProgress = uiState.downloadProgress,
                    downloadStatus = uiState.downloadStatus,
                    onDelete = { viewModel.deleteModel(it) },
                    onShowModelfile = { modelName ->
                        viewModel.getModelInfo(modelName) { modelfile, template ->
                            val analysis = repository.inspectModelfile(
                                name = "preview",
                                fallbackFromModel = modelName,
                                modelfile = modelfile
                            )
                            modelfileContent = modelfile
                            modelfileOriginName = modelName
                            newModelName = repository.suggestCreateModelName(modelName, analysis)
                            showModelfileDialog = true
                        }
                    },
                    onClearStatus = { viewModel.clearDownloadStatus(it) },
                    onCancelOperation = { viewModel.cancelOperation(it) }
                )
            }
        }
    }



    if (showAddServerDialog || serverToEdit != null) {
        val server = serverToEdit
        var name by rememberSaveable(server?.id) { mutableStateOf(server?.name ?: "") }
        var url by rememberSaveable(server?.id) { mutableStateOf(server?.url ?: "http://") }
        
        AlertDialog(
            modifier = Modifier.safeDrawingPadding().imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            onDismissRequest = { 
                showAddServerDialog = false
                serverToEdit = null 
            },
            title = { Text(stringResource(if (server == null) R.string.ollama_add_server else R.string.ollama_edit_server)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.ollama_server_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.ollama_server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (server == null) {
                        viewModel.addServer(name, url)
                    } else {
                        viewModel.updateServer(server.copy(name = name, url = url))
                    }
                    showAddServerDialog = false
                    serverToEdit = null
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddServerDialog = false
                    serverToEdit = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    
    if (showModelfileDialog) {
        Dialog(
            onDismissRequest = { showModelfileDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.92f)
                    .navigationBarsPadding()
                    .imePadding(),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ollama_modelfile_editor_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = newModelName,
                            onValueChange = { newModelName = it },
                            label = { Text(stringResource(R.string.ollama_new_model_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = !modelNameValid,
                            supportingText = {
                                if (!modelNameValid) {
                                    Text(stringResource(R.string.ollama_invalid_model_name))
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.ollama_create_mode_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = createMode == ModelCreateMode.RemoteApi,
                                onClick = { createMode = ModelCreateMode.RemoteApi },
                                label = { Text(stringResource(R.string.ollama_create_mode_remote_api)) }
                            )
                            FilterChip(
                                selected = createMode == ModelCreateMode.LocalCli,
                                onClick = { createMode = ModelCreateMode.LocalCli },
                                label = { Text(stringResource(R.string.ollama_create_mode_local_cli)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                if (createMode == ModelCreateMode.RemoteApi) {
                                    R.string.ollama_create_mode_remote_desc
                                } else {
                                    R.string.ollama_create_mode_local_desc
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (modelfileAnalysis.fromSource.kind == OllamaRepository.FromSourceKind.HuggingFaceResolveUrl &&
                            modelfileAnalysis.fromSource.derivedHfReference != null
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.ollama_modelfile_hf_url_normalized,
                                        modelfileAnalysis.fromSource.derivedHfReference
                                    ),
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (remoteBlocked) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = when {
                                        modelfileAnalysis.unsupportedDirectives.isNotEmpty() -> stringResource(
                                            R.string.ollama_modelfile_remote_unsupported,
                                            modelfileAnalysis.unsupportedDirectives.joinToString(", ")
                                        )
                                        else -> stringResource(
                                            R.string.ollama_modelfile_remote_unsupported_source,
                                            modelfileAnalysis.fromSource.rawValue,
                                            modelfileAnalysis.fromSource.reason ?: ""
                                        )
                                    },
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (createMode == ModelCreateMode.LocalCli && localBlocked) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.ollama_modelfile_local_source_unsupported,
                                        modelfileAnalysis.fromSource.rawValue,
                                        modelfileAnalysis.fromSource.reason ?: ""
                                    ),
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (createMode == ModelCreateMode.LocalCli && !termuxSshConnected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ollama_local_cli_requires_ssh),
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = modelfileContent,
                            onValueChange = { modelfileContent = it },
                            label = { Text(stringResource(R.string.ollama_modelfile_content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 300.dp),
                            maxLines = Int.MAX_VALUE
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showModelfileDialog = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            enabled = canCreateModel,
                            onClick = {
                                if (createMode == ModelCreateMode.RemoteApi) {
                                    viewModel.createModel(normalizedNewModelName, modelfileOriginName, modelfileContent)
                                } else {
                                    viewModel.createModelLocally(sshService, normalizedNewModelName, modelfileOriginName, modelfileContent)
                                }
                                showModelfileDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.action_create))
                        }
                    }
                }
            }
        }
    }

    if (showModelPullDialog) {
        var modelName by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showModelPullDialog = false },
            title = { Text(stringResource(R.string.ollama_pull_model_title)) },
            text = {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text(stringResource(R.string.ollama_pull_model_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.pullModel(modelName)
                    showModelPullDialog = false
                }) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelPullDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Update ModelList callback
    if (selectedTab == 1) {
         // We are in ModelList, but since it's inside `when`, we can't easily hook the callback here
         // unless we restructure. 
         // Actually, ModelList is called above. We need to pass the callback to standard ModelList call.
         // Let's rely on the lambda passed to ModelList
    }
}

@Composable
fun ServerList(
    servers: List<OllamaServerEntity>,
    selectedServer: OllamaServerEntity?,
    onSelect: (OllamaServerEntity) -> Unit,
    onEdit: (OllamaServerEntity) -> Unit,
    onAdd: () -> Unit,
    onDelete: (OllamaServerEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (servers.isEmpty()) {
            item {
                AppStatePanel(kind = AppStateKind.Empty,
                    title = stringResource(R.string.llama_no_servers),
                    actionLabel = stringResource(R.string.ollama_add_server),
                    onAction = onAdd)
            }
        }
         items(servers, key = { it.id }) { server ->
            val isSelected = server.id == selectedServer?.id
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(server) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check, 
                                    contentDescription = stringResource(R.string.ollama_server_active), 
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = server.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        Row {
                            IconButton(onClick = { onEdit(server) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                            }
                            IconButton(onClick = { onDelete(server) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = server.url,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = if (isSelected) 32.dp else 0.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelList(
    models: List<OllamaModel>,
    isLoading: Boolean,
    error: String?,
    downloadProgress: Map<String, Float>,
    downloadStatus: Map<String, String>,
    onDelete: (String) -> Unit,
    onShowModelfile: (String) -> Unit,
    onClearStatus: (String) -> Unit,
    onCancelOperation: (String) -> Unit
) {
    val failedPrefix = stringResource(R.string.ollama_status_failed_prefix)
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Active/Failed Downloads
                if (downloadProgress.isNotEmpty() || downloadStatus.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.models_downloading),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, 
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    val allKeys = (downloadProgress.keys + downloadStatus.keys).distinct().sorted()
                    
                    items(allKeys) { modelName ->
                        val progress = downloadProgress[modelName] ?: 0f
                        val status = downloadStatus[modelName] ?: ""
                        val isFailed = status.startsWith(failedPrefix, ignoreCase = true)
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFailed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = modelName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            if (isFailed) onClearStatus(modelName) else onCancelOperation(modelName)
                                        }
                                    ) {
                                        Icon(
                                            if (isFailed) Icons.Default.Close else Icons.Default.Warning,
                                            contentDescription = stringResource(
                                                if (isFailed) R.string.ollama_dismiss_status else R.string.action_stop
                                            ),
                                            tint = if (isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                
                                if (!isFailed && progress < 1.0f) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Installed models
                if (models.isNotEmpty()) {
                     item {
                        Text(
                            text = stringResource(R.string.agent_installed_models),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, 
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                        )
                    }
                    
                    items(models) { model ->
                        ModelCard(model, onDelete, onShowModelfile)
                    }
                } else if (downloadProgress.isEmpty() && downloadStatus.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.models_no_models),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                stringResource(R.string.ollama_pull_to_get_started),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    model: OllamaModel,
    onDelete: (String) -> Unit,
    onShowModelfile: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ollama_family_format, model.details.family, model.details.format),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ollama_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { 
                                onDelete(model.name)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ollama_view_modelfile)) },
                            onClick = { 
                                onShowModelfile(model.name)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(formatBytes(model.size)) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) } // Example icon
                )
                
                if (model.details.parameter_size.isNotEmpty()) {
                    AssistChip(
                         onClick = {},
                         label = { Text(model.details.parameter_size) }
                    )
                }
                
                if (model.details.quantization_level.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(model.details.quantization_level) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = null
                    )
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.2f MB", mb)
        else -> String.format(java.util.Locale.getDefault(), "%.2f KB", kb)
    }
}
