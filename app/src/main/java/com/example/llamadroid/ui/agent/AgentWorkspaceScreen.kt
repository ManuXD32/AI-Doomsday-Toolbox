package com.example.llamadroid.ui.agent

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentService.Companion.setIsLoading
import com.example.llamadroid.service.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.io.File

/**
 * AgentWorkspaceScreen - File manager for AI Agent workspace
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentWorkspaceScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val agentService = remember { AgentForegroundService.getAgentService(context) }
    
    val currentProjectFolder by AgentService.currentProjectFolder.collectAsState()
    val activeConversationId by AgentService.activeConversationId.collectAsState()
    val preferredConversationId by AgentService.preferredConversationId.collectAsState()
    val workspaceTerminalStates by AgentService.workspaceTerminalStates.collectAsState()
    val workspaceConversationAnchor = remember(preferredConversationId, activeConversationId) {
        resolveWorkspaceConversationAnchor(preferredConversationId, activeConversationId)
    }
    val resolvedProjectRoot = remember(workspaceConversationAnchor, currentProjectFolder) {
        resolveWorkspaceProjectRoot(workspaceConversationAnchor, currentProjectFolder)
    }
    val workspaceTerminalState = resolvedProjectRoot?.let { workspaceTerminalStates[it] }
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTerminalDialog by remember { mutableStateOf(false) }
    var stopProjectShellSummary by remember { mutableStateOf<com.example.llamadroid.service.ProjectShellSessionSummary?>(null) }
    
    // File viewer state
    var viewingFile by remember { mutableStateOf<FileInfo?>(null) }
    var viewingImage by remember { mutableStateOf<FileInfo?>(null) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var editedContent by remember { mutableStateOf("") }
    
    // Delete confirmation
    var deleteTarget by remember { mutableStateOf<FileInfo?>(null) }
    
    // New file/folder dialog
    var showNewDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemIsFolder by remember { mutableStateOf(false) }
    
    // Multi-selection & Actions
    var selectedFiles by remember { mutableStateOf(setOf<FileInfo>()) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var clipboardAction by remember { mutableStateOf<Pair<String, List<FileInfo>>?>(null) } // "copy" or "move" to files
    
    val isConnected by AgentService.isConnected.collectAsState()
    val agentConnectionStatus by AgentService.connectionStatus.collectAsState()
    val retryMessage by AgentService.retryMessage.collectAsState()
    val showSshWarning = !isConnected &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.CONNECTING &&
        agentConnectionStatus != AgentService.Companion.ConnectionStatus.RECONNECTING
    
    // Download state
    var downloadTarget by remember { mutableStateOf<FileInfo?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let {
            val target = downloadTarget ?: return@let
            scope.launch {
                setIsLoading(true, context.getString(R.string.status_downloading))
                agentService.downloadFile(target.path, it).onSuccess {
                    Toast.makeText(context, context.getString(R.string.status_complete), Toast.LENGTH_SHORT).show()
                }.onFailure { e: Throwable ->
                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                setIsLoading(false)
            }
        }
        downloadTarget = null
    }
    
    // Load files
    fun loadFiles() {
        if (currentPath.isBlank()) {
            files = emptyList()
            error = null
            isLoading = false
            return
        }
        isLoading = true
        error = null
        scope.launch {
            agentService.listDirectory(currentPath).onSuccess { fileList ->
                files = fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            }.onFailure { e: Throwable ->
                error = e.message
                AgentService.addDebugLog("📁 Workspace load failed for $currentPath: ${e.message}")
            }
            isLoading = false
        }
    }

    suspend fun persistPreviewImage(fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val previewDir = File(context.cacheDir, "agent_workspace_previews").apply { mkdirs() }
        val previewFile = File(
            previewDir,
            "${System.currentTimeMillis()}_${fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        )
        previewFile.writeBytes(bytes)
        previewFile.absolutePath
    }

    // File Picker for Upload
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                if (currentPath.isBlank()) return@launch
                setIsLoading(true, "Uploading...")
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "uploaded_file"
                
                agentService.uploadFile(uri, "$currentPath/$fileName").onSuccess {
                    Toast.makeText(context, context.getString(R.string.agent_upload_success, fileName), Toast.LENGTH_SHORT).show()
                    loadFiles()
                }.onFailure { e: Throwable ->
                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                setIsLoading(false)
            }
        }
    }
    

    LaunchedEffect(resolvedProjectRoot) {
        viewingFile = null
        viewingImage = null
        imagePreviewPath = null
        fileContent = ""
        editedContent = ""
        isEditMode = false
        files = emptyList()
        error = null
        if (resolvedProjectRoot.isNullOrBlank()) {
            currentPath = ""
            isLoading = false
        } else if (currentPath != resolvedProjectRoot && !currentPath.startsWith("$resolvedProjectRoot/")) {
            currentPath = resolvedProjectRoot
            isLoading = true
        }
    }

    // Connect and load using a single path to avoid double startup reloads
    LaunchedEffect(isConnected, currentPath, resolvedProjectRoot) {
        if (resolvedProjectRoot.isNullOrBlank() || currentPath.isBlank()) return@LaunchedEffect
        if (!isConnected) {
            val connectResult = agentService.connect()
            if (connectResult.isFailure) {
                val connectError = connectResult.exceptionOrNull()
                error = connectError?.message
                AgentService.addDebugLog("📡 Workspace SSH connect failed: ${connectError?.message}")
                isLoading = false
                return@LaunchedEffect
            }
        }
        loadFiles()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.agent_workspace_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (currentPath.isNotBlank()) currentPath else stringResource(R.string.agent_workspace_no_project_path),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val projectRoot = resolvedProjectRoot ?: return@IconButton
                            showTerminalDialog = true
                            scope.launch {
                                agentService.openWorkspaceTerminal(projectRoot).onFailure { e: Throwable ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.agent_error_prefix, e.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = resolvedProjectRoot != null
                    ) {
                        val tint = when {
                            workspaceTerminalState?.isConnecting == true -> MaterialTheme.colorScheme.tertiary
                            workspaceTerminalState?.isConnected == true -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        BadgedBox(
                            badge = {
                                workspaceTerminalState?.let { terminalState ->
                                    Badge(
                                        containerColor = when {
                                            terminalState.isConnecting -> MaterialTheme.colorScheme.tertiary
                                            terminalState.isConnected -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Code,
                                stringResource(R.string.agent_workspace_terminal_title),
                                tint = tint
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val projectRoot = resolvedProjectRoot ?: return@IconButton
                            val summary = agentService.getProjectShellSessionSummary(projectRoot)
                            if (summary.totalActiveSessions == 0) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.agent_workspace_stop_project_shells_none),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                stopProjectShellSummary = summary
                            }
                        },
                        enabled = resolvedProjectRoot != null
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            stringResource(R.string.agent_workspace_stop_project_shells),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    // Upload
                    IconButton(onClick = { uploadLauncher.launch("*/*") }, enabled = currentPath.isNotBlank()) {
                        Icon(Icons.Default.Upload, stringResource(R.string.action_upload))
                    }
                    // New file/folder
                    IconButton(onClick = { showNewDialog = true }, enabled = currentPath.isNotBlank()) {
                        Icon(Icons.Default.Add, stringResource(R.string.agent_new))
                    }
                    // Refresh
                    IconButton(onClick = { loadFiles() }, enabled = currentPath.isNotBlank()) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.agent_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStatusBar(
                isBackendConnected = true,
                backendIsRecovering = false,
                backendHasChecked = true,
                backendOfflineMessage = "",
                backendReconnectingMessage = "",
                agentConnectionStatus = agentConnectionStatus,
                retryMessage = retryMessage,
                onRetry = { scope.launch { agentService.connect() } }
            )

            if (showSshWarning) {
                SshConnectionWarningCard(
                    title = stringResource(R.string.agent_ssh_required_title),
                    message = stringResource(R.string.agent_ssh_required_desc),
                    onRetry = { scope.launch { agentService.connect() } }
                )
            }
            
            // Selection Toolbar
            AnimatedVisibility(visible = isMultiSelectMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { 
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                            }) {
                                Icon(Icons.Default.Close, null)
                            }
                            Text(stringResource(R.string.agent_selected_count, selectedFiles.size), fontWeight = FontWeight.Bold)
                        }
                        Row {
                            IconButton(onClick = { 
                                clipboardAction = "copy" to selectedFiles.toList()
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                                Toast.makeText(context, context.getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                            }
                            IconButton(onClick = { 
                                clipboardAction = "move" to selectedFiles.toList()
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                                Toast.makeText(context, context.getString(R.string.action_move), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCut, stringResource(R.string.action_move))
                            }
                            IconButton(onClick = { 
                                scope.launch {
                                    if (currentPath.isBlank()) return@launch
                                    val tarName = "archive_${System.currentTimeMillis()}.tar.gz"
                                        agentService.compress(selectedFiles.map { it.path }, "$currentPath/$tarName").onSuccess {
                                            Toast.makeText(context, context.getString(R.string.agent_compress_success, tarName), Toast.LENGTH_SHORT).show()
                                            loadFiles()
                                            isMultiSelectMode = false
                                            selectedFiles = emptySet()
                                    }.onFailure { e: Throwable ->
                                        Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Archive, stringResource(R.string.action_compress))
                            }
                            IconButton(onClick = { 
                                // Bulk Delete
                                scope.launch {
                                    if (currentPath.isBlank()) return@launch
                                    var failure: Throwable? = null
                                    selectedFiles.forEach { file ->
                                        agentService.deletePath(file.path, recursive = file.isDirectory)
                                            .onFailure { error -> failure = failure ?: error }
                                    }
                                    failure?.let { error ->
                                        Toast.makeText(context, context.getString(R.string.agent_error_prefix, error.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                    loadFiles()
                                    isMultiSelectMode = false
                                    selectedFiles = emptySet()
                                }
                            }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Clipboard Action Bar (Paste)
            AnimatedVisibility(visible = clipboardAction != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${clipboardAction?.second?.size} ${stringResource(R.string.agent_ready_to, clipboardAction?.first ?: "")}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            TextButton(onClick = { clipboardAction = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(onClick = {
                                val (action, targets) = clipboardAction ?: return@Button
                                scope.launch {
                                    if (currentPath.isBlank()) return@launch
                                    setIsLoading(true, context.getString(R.string.status_processing))
                                    targets.forEach { target ->
                                        val dest = "$currentPath/${target.name}"
                                        if (action == "copy") {
                                            agentService.copy(target.path, dest)
                                        } else {
                                            agentService.move(target.path, dest)
                                        }
                                    }
                                    loadFiles()
                                    clipboardAction = null
                                    setIsLoading(false)
                                }
                            }) {
                                Text(stringResource(R.string.action_paste_here))
                            }
                        }
                    }
                }
            }

            // Breadcrumb navigation
            if (resolvedProjectRoot != null && currentPath.isNotBlank() && currentPath != resolvedProjectRoot) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentPath = clampWorkspaceParentPath(currentPath, resolvedProjectRoot)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_parent_folder), fontSize = 14.sp)
                }
                HorizontalDivider()
            }
            
            when {
                resolvedProjectRoot == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.FolderOff, null, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.agent_no_project_loaded_title), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.agent_workspace_no_project_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(error ?: stringResource(R.string.error_unknown))
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { loadFiles() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                files.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📂", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.agent_empty_folder))
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            val isSelected = selectedFiles.contains(file)
                            FileItem(
                                file = file,
                                isSelected = isSelected,
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = {
                                    if (isMultiSelectMode) {
                                        selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                    } else if (file.isDirectory) {
                                        currentPath = file.path
                                    } else if (AgentService.isSupportedImagePath(file.path)) {
                                        viewingImage = file
                                        imagePreviewPath = null
                                        scope.launch {
                                            agentService.readFileBytes(file.path).onSuccess { bytes ->
                                                imagePreviewPath = persistPreviewImage(file.name, bytes)
                                            }.onFailure { e: Throwable ->
                                                Toast.makeText(context, context.getString(R.string.error_param, e.message), Toast.LENGTH_SHORT).show()
                                                viewingImage = null
                                            }
                                        }
                                    } else {
                                        // View file
                                        viewingFile = file
                                        scope.launch {
                                            agentService.readFile(file.path).onSuccess { content: String ->
                                                fileContent = content
                                                editedContent = content
                                            }.onFailure { e: Throwable ->
                                                Toast.makeText(context, context.getString(R.string.error_param, e.message), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!isMultiSelectMode) {
                                        isMultiSelectMode = true
                                        selectedFiles = setOf(file)
                                    }
                                },
                                onDelete = { deleteTarget = file },
                                onAction = { action ->
                                    when (action) {
                                        "compress" -> {
                                            scope.launch {
                                                if (currentPath.isBlank()) return@launch
                                                setIsLoading(true, context.getString(R.string.status_processing))
                                                val archiveName = "${file.name}.tar.gz"
                                                agentService.compress(
                                                    listOf(file.path),
                                                    "$currentPath/$archiveName"
                                                ).onSuccess {
                                                    Toast.makeText(context, context.getString(R.string.agent_compress_success), Toast.LENGTH_SHORT).show()
                                                    loadFiles()
                                                }.onFailure { e: Throwable ->
                                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                                }
                                                setIsLoading(false)
                                            }
                                        }
                                        "uncompress" -> {
                                            scope.launch {
                                                if (currentPath.isBlank()) return@launch
                                                setIsLoading(true, context.getString(R.string.status_processing))
                                                agentService.uncompress(file.path, currentPath).onSuccess {
                                                    Toast.makeText(context, context.getString(R.string.agent_uncompress_success), Toast.LENGTH_SHORT).show()
                                                    loadFiles()
                                                }.onFailure { e: Throwable ->
                                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                                }
                                                setIsLoading(false)
                                            }
                                        }
                                        "download" -> {
                                            downloadTarget = file
                                            downloadLauncher.launch(file.name)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        } // end Column(padding)
    
    // File viewer/editor dialog
    viewingFile?.let { file ->
        FileViewerDialog(
            file = file,
            content = fileContent,
            isEditMode = isEditMode,
            editedContent = editedContent,
            onEditedContentChange = { editedContent = it },
            onToggleEdit = { isEditMode = !isEditMode },
            onSave = {
                scope.launch {
                    agentService.writeFile(file.path, editedContent).onSuccess {
                        Toast.makeText(context, context.getString(R.string.agent_save_toast), Toast.LENGTH_SHORT).show()
                        fileContent = editedContent
                        isEditMode = false
                    }.onFailure { e: Throwable ->
                        Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                viewingFile = null
                isEditMode = false
            }
        )
    }

    viewingImage?.let { file ->
        val previewPath = imagePreviewPath
        if (previewPath != null) {
            ImageViewerDialog(
                file = file,
                previewPath = previewPath,
                onDismiss = {
                    viewingImage = null
                    imagePreviewPath = null
                }
            )
        }
    }

    stopProjectShellSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { stopProjectShellSummary = null },
            title = { Text(stringResource(R.string.agent_workspace_stop_project_shells_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(
                            R.string.agent_workspace_stop_project_shells_confirm_body,
                            summary.workspaceRoot
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.agent_workspace_stop_project_shells_running_commands,
                            summary.runningCommandCount
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.agent_workspace_stop_project_shells_workspace_terminal,
                            if (summary.workspaceTerminalOpen) {
                                stringResource(R.string.action_yes)
                            } else {
                                stringResource(R.string.action_no)
                            }
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_workspace_stop_project_shells_warning))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val projectRoot = summary.workspaceRoot
                        stopProjectShellSummary = null
                        scope.launch {
                            agentService.stopProjectShellSessions(projectRoot)
                                .onSuccess { result ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.agent_workspace_stop_project_shells_success,
                                            result.totalStopped
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.agent_error_prefix, error.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.agent_workspace_stop_project_shells_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { stopProjectShellSummary = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showTerminalDialog && resolvedProjectRoot != null) {
        WorkspaceTerminalDialog(
            workspaceRoot = resolvedProjectRoot,
            state = workspaceTerminalState,
            onDismiss = { showTerminalDialog = false },
            onSend = { input ->
                scope.launch {
                    agentService.sendWorkspaceTerminalInput(resolvedProjectRoot, input).onFailure { e: Throwable ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.agent_error_prefix, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onInterrupt = {
                scope.launch {
                    agentService.interruptWorkspaceTerminal(resolvedProjectRoot).onFailure { e: Throwable ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.agent_error_prefix, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onReconnect = {
                scope.launch {
                    agentService.reconnectWorkspaceTerminal(resolvedProjectRoot).onFailure { e: Throwable ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.agent_error_prefix, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onClear = { agentService.clearWorkspaceTerminalTranscript(resolvedProjectRoot) },
            onStop = {
                agentService.closeWorkspaceTerminal(resolvedProjectRoot)
                showTerminalDialog = false
            }
        )
    }
    
    // Delete confirmation
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (file.isDirectory) stringResource(R.string.agent_confirm_delete_folder) else stringResource(R.string.agent_confirm_delete_file)) },
            text = { Text(stringResource(R.string.agent_confirm_delete_msg, file.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (currentPath.isBlank()) return@launch
                            agentService.deletePath(file.path, recursive = file.isDirectory).onSuccess {
                                Toast.makeText(context, context.getString(R.string.agent_delete_toast), Toast.LENGTH_SHORT).show()
                                loadFiles()
                            }.onFailure { e: Throwable ->
                                Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // New file/folder dialog
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(if (newItemIsFolder) stringResource(R.string.agent_new_folder) else stringResource(R.string.agent_new_file)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.agent_new_folder))
                        Switch(
                            checked = !newItemIsFolder,
                            onCheckedChange = { newItemIsFolder = !it }
                        )
                        Text(stringResource(R.string.agent_new_file))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text(stringResource(R.string.agent_name_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newItemName.isNotBlank()) {
                            scope.launch {
                                if (currentPath.isBlank()) return@launch
                                val fullPath = if (newItemName.startsWith("/")) newItemName else "$currentPath/$newItemName"
                                val result = if (newItemIsFolder) {
                                    agentService.createFolder(fullPath)
                                } else {
                                    agentService.writeFile(fullPath, "")
                                }
                                result.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.agent_create_toast), Toast.LENGTH_SHORT).show()
                                    loadFiles()
                                }.onFailure { e: Throwable ->
                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                }
                            }
                            showNewDialog = false
                            newItemName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.agent_create_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false; newItemName = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: FileInfo,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!file.isDirectory) {
                Text(formatSize(file.size), fontSize = 11.sp)
            }
        },
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    null,
                    tint = if (file.isDirectory) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        },
        trailingContent = {
            Row {
                if (file.name.endsWith(".tar.gz") || file.name.endsWith(".zip") || file.name.endsWith(".tgz")) {
                    IconButton(onClick = { onAction("uncompress") }) {
                        Icon(Icons.Default.Unarchive, "Uncompress")
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_compress)) },
                            onClick = { showMenu = false; onAction("compress") },
                            leadingIcon = { Icon(Icons.Default.Archive, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_download)) },
                            onClick = { showMenu = false; onAction("download") },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
    )
}

@Composable
fun FileViewerDialog(
    file: FileInfo,
    content: String,
    isEditMode: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        file.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        IconButton(onClick = onToggleEdit) {
                            Icon(
                                if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                if (isEditMode) stringResource(R.string.action_info) else stringResource(R.string.action_edit)
                            )
                        }
                        if (isEditMode) {
                            IconButton(onClick = onSave) {
                                Icon(Icons.Default.Done, stringResource(R.string.action_save), tint = Color(0xFF4CAF50))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close))
                        }
                    }
                }
                
                HorizontalDivider()
                
                // Content
                if (isEditMode) {
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = onEditedContentChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                } else {
                    Surface(
                        color = Color.Black.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = content,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    softWrap = false,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageViewerDialog(
    file: FileInfo,
    previewPath: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        file.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }

                HorizontalDivider()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(previewPath),
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun WorkspaceTerminalDialog(
    workspaceRoot: String,
    state: com.example.llamadroid.service.WorkspaceTerminalUiState?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit
) {
    var inputText by remember(workspaceRoot) { mutableStateOf("") }
    var draftInput by remember(workspaceRoot) { mutableStateOf("") }
    var historyIndex by remember(workspaceRoot) { mutableStateOf<Int?>(null) }
    var terminalFontSizeSp by rememberSaveable(workspaceRoot) { mutableStateOf(13f) }
    var fitToWidth by rememberSaveable(workspaceRoot) { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val pasteHereLabel = stringResource(R.string.action_paste_here)
    val copyAllLabel = stringResource(R.string.action_copy_all)
    val fitLabel = stringResource(R.string.action_fit)
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val transcript = state?.transcript.orEmpty()
    val commandHistory = state?.commandHistory.orEmpty()
    val inputFontSize = (terminalFontSizeSp + 1f).coerceIn(12f, 22f)

    fun recallPreviousCommand() {
        if (commandHistory.isEmpty()) return
        val nextIndex = when (val currentIndex = historyIndex) {
            null -> {
                draftInput = inputText
                commandHistory.lastIndex
            }
            else -> (currentIndex - 1).coerceAtLeast(0)
        }
        historyIndex = nextIndex
        inputText = commandHistory[nextIndex]
    }

    fun recallNextCommand() {
        val currentIndex = historyIndex ?: return
        val nextIndex = currentIndex + 1
        if (nextIndex > commandHistory.lastIndex) {
            historyIndex = null
            inputText = draftInput
        } else {
            historyIndex = nextIndex
            inputText = commandHistory[nextIndex]
        }
    }

    LaunchedEffect(transcript) {
        verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.agent_workspace_terminal_title),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            workspaceRoot,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val statusText = when {
                    state?.isConnecting == true -> stringResource(R.string.agent_workspace_terminal_status_connecting)
                    state?.isConnected == true -> stringResource(R.string.agent_workspace_terminal_status_connected)
                    else -> stringResource(R.string.agent_workspace_terminal_status_disconnected)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text(statusText) },
                        leadingIcon = {
                            Icon(
                                if (state?.isConnected == true) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                null,
                                tint = if (state?.isConnected == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            state?.isConnecting == true -> stringResource(R.string.agent_workspace_terminal_connecting_body)
                            state?.isConnected == true -> stringResource(R.string.agent_workspace_terminal_input_placeholder)
                            else -> state?.errorMessage ?: stringResource(R.string.agent_workspace_terminal_status_disconnected)
                        },
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                state?.errorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onReconnect) {
                        Text(stringResource(R.string.agent_workspace_terminal_reconnect))
                    }
                    OutlinedButton(onClick = onInterrupt, enabled = state?.isConnected == true) {
                        Text(stringResource(R.string.agent_workspace_terminal_interrupt))
                    }
                    OutlinedButton(onClick = onClear) {
                        Text(stringResource(R.string.action_clear))
                    }
                    OutlinedButton(
                        onClick = {
                            terminalFontSizeSp = (terminalFontSizeSp - 1f).coerceAtLeast(10f)
                        }
                    ) {
                        Text("A-")
                    }
                    FilterChip(
                        selected = fitToWidth,
                        onClick = { fitToWidth = !fitToWidth },
                        label = { Text(fitLabel) }
                    )
                    OutlinedButton(
                        onClick = {
                            terminalFontSizeSp = (terminalFontSizeSp + 1f).coerceAtMost(24f)
                        }
                    ) {
                        Text("A+")
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("${terminalFontSizeSp.toInt()}sp") }
                    )
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_stop))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = Color.Black.copy(alpha = 0.96f),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState)
                            .then(
                                if (fitToWidth) {
                                    Modifier
                                } else {
                                    Modifier.horizontalScroll(horizontalScrollState)
                                }
                            )
                            .padding(14.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = when {
                                    transcript.isNotBlank() -> transcript
                                    state?.isConnecting == true -> stringResource(R.string.agent_workspace_terminal_connecting_body)
                                    else -> stringResource(R.string.agent_workspace_terminal_empty)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = terminalFontSizeSp.sp,
                                color = Color(0xFF4CAF50),
                                softWrap = fitToWidth
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { recallPreviousCommand() },
                                enabled = state?.isConnected == true && commandHistory.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.agent_workspace_terminal_previous_command))
                            }
                            OutlinedButton(
                                onClick = { recallNextCommand() },
                                enabled = state?.isConnected == true && historyIndex != null
                            ) {
                                Text(stringResource(R.string.agent_workspace_terminal_next_command))
                            }
                            OutlinedButton(
                                onClick = {
                                    val pastedText = clipboardManager.getText()?.text.orEmpty()
                                    if (pastedText.isNotBlank()) {
                                        inputText += pastedText
                                        draftInput = inputText
                                        historyIndex = null
                                    } else {
                                        Toast.makeText(
                                            context,
                                            pasteHereLabel,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text(pasteHereLabel)
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(transcript))
                                    Toast.makeText(
                                        context,
                                        copyAllLabel,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                enabled = transcript.isNotBlank()
                            ) {
                                Text(copyAllLabel)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    if (historyIndex != null) {
                                        historyIndex = null
                                    }
                                    draftInput = it
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 88.dp, max = 160.dp)
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (keyEvent.key) {
                                            Key.DirectionUp -> {
                                                recallPreviousCommand()
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                recallNextCommand()
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                                label = { Text(stringResource(R.string.agent_workspace_terminal_input_label)) },
                                placeholder = { Text(stringResource(R.string.agent_workspace_terminal_input_placeholder)) },
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = inputFontSize.sp
                                ),
                                minLines = 3,
                                maxLines = 6,
                                enabled = state?.isConnected == true
                            )
                            Button(
                                onClick = {
                                    val command = inputText.trimEnd('\n', '\r')
                                    if (command.isNotBlank()) {
                                        onSend(command)
                                        inputText = ""
                                        draftInput = ""
                                        historyIndex = null
                                    }
                                },
                                enabled = state?.isConnected == true && inputText.isNotBlank(),
                                modifier = Modifier.heightIn(min = 56.dp)
                            ) {
                                Text(stringResource(R.string.action_send))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes ${stringResource(R.string.agent_unit_b)}"
        bytes < 1024 * 1024 -> "${bytes / 1024} ${stringResource(R.string.agent_unit_kb)}"
        else -> String.format("%.1f %s", bytes / (1024.0 * 1024.0), stringResource(R.string.agent_unit_mb))
    }
}
