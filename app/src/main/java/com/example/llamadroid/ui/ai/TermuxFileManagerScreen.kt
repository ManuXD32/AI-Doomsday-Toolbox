package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.model.TermuxTool
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.io.File
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * File entry from SSH ls output with raw bytes for sorting
 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: String,
    val sizeBytes: Long,
    val permissions: String,
    val modifiedTime: String
)

data class UploadProgressState(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesSent: Long,
    val totalBytes: Long
) {
    val progressFraction: Float
        get() = when {
            totalBytes > 0L -> (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100).toInt().coerceIn(0, 100)
}

/**
 * Sort options for file listing
 */
enum class SortOption(val labelResId: Int) {
    NAME_ASC(R.string.file_sort_name_asc),
    NAME_DESC(R.string.file_sort_name_desc),
    SIZE_ASC(R.string.file_sort_size_asc),
    SIZE_DESC(R.string.file_sort_size_desc),
    TIME_ASC(R.string.file_sort_time_asc),
    TIME_DESC(R.string.file_sort_time_desc)
}

/**
 * Termux File Manager Screen
 * Visual file browser for managing files in proot tools
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TermuxFileManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val sshService = remember { SSHService(context) }
    
    val isConnected by SSHService.isConnected.collectAsState()
    
    // Installed tools (re-detected on screen entry)
    var installedTools by remember { mutableStateOf<List<TermuxTool>>(emptyList()) }
    var isDetecting by remember { mutableStateOf(true) }
    
    // Selected tool and current path
    var selectedTool by remember { mutableStateOf<TermuxTool?>(null) }
    var currentPath by remember { mutableStateOf("") }
    
    // File listing
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Sorting
    var sortOption by remember { mutableStateOf(SortOption.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Track file count to detect changes
    var lastFileCount by remember { mutableStateOf(-1) }
    var isCalculatingSizes by remember { mutableStateOf(false) }
    
    // Multi-selection
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    
    // Download dialog
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<UploadProgressState?>(null) }
    
    // Delete confirmation
    var fileToDelete by remember { mutableStateOf<FileEntry?>(null) }
    
    // Folder picker for downloads
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { targetUri ->
            context.contentResolver.takePersistableUriPermission(
                targetUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Download selected files to this folder
            scope.launch {
                isDownloading = true
                downloadFilesToFolder(context, sshService, currentPath, selectedFiles.toList(), targetUri)
                isDownloading = false
                selectedFiles = emptySet()
                isSelectionMode = false
                Toast.makeText(context, resources.getString(R.string.file_download_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult

        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        scope.launch {
            isUploading = true
            uploadProgress = null
            val result = uploadFilesToRemoteFolder(
                context = context,
                sshService = sshService,
                remotePath = currentPath,
                uris = uris,
                onProgress = { progress -> uploadProgress = progress }
            )
            isUploading = false
            uploadProgress = null

            result.onSuccess { uploadedNames ->
                lastFileCount = -1
                files = emptyList()
                Toast.makeText(
                    context,
                    resources.getString(R.string.file_upload_success_count, uploadedNames.size),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    resources.getString(
                        R.string.file_upload_failed,
                        throwable.message ?: resources.getString(R.string.error_unknown)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    // Sort files based on current option
    val sortedFiles = remember(files, sortOption) {
        when (sortOption) {
            SortOption.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortOption.SIZE_ASC -> files.sortedBy { it.sizeBytes }
            SortOption.SIZE_DESC -> files.sortedByDescending { it.sizeBytes }
            SortOption.TIME_ASC -> files.sortedBy { it.modifiedTime }
            SortOption.TIME_DESC -> files.sortedByDescending { it.modifiedTime }
        }
    }
    
    // Detect installed tools on screen entry
    LaunchedEffect(Unit) {
        if (!isConnected) return@LaunchedEffect
        
        isDetecting = true
        val detected = mutableListOf<TermuxTool>()
        
        // Check all tools by their install path
        for (tool in TermuxTools.allTools) {
            val result = sshService.executeCommand(tool.installCheckCommand)
            result.onSuccess { output ->
                if (output.trim() == "installed") {
                    detected.add(tool)
                }
            }
        }
        
        installedTools = detected
        isDetecting = false
        
        // Auto-select first tool
        if (detected.isNotEmpty()) {
            selectedTool = detected.first()
            currentPath = expandRemotePath(detected.first().installPath)
        }
    }
    
    // Load files when path changes and auto-refresh every 10 seconds
    LaunchedEffect(currentPath) {
        if (currentPath.isBlank() || !isConnected) return@LaunchedEffect
        
        // Reset on path change - force reload
        lastFileCount = -1
        files = emptyList()
        isLoading = true
        
        while (true) {
            if (!isDownloading && !isUploading) {  // Don't refresh during transfers
                error = null
                val expandedPath = expandRemotePath(currentPath)
                
                // Step 1: Quick file listing (no sizes, instant)
                if (files.isEmpty()) {
                    val quickResult = sshService.executeCommand("ls -1A ${shellQuote(expandedPath)} 2>/dev/null")
                    quickResult.onSuccess { output ->
                        // Show files immediately with "Calculating..." as size
                        files = output.lines()
                            .filter { it.isNotBlank() && it != "." && it != ".." }
                            .map { name ->
                                FileEntry(
                                    name = name,
                                    isDirectory = false, // Will be corrected
                                    size = resources.getString(R.string.file_calculating_size),
                                    sizeBytes = 0,
                                    permissions = "",
                                    modifiedTime = ""
                                )
                            }
                        isLoading = false
                    }.onFailure {
                        isLoading = false
                    }
                }
                
                // Step 2: Get types and sizes (may be slow for folders)
                val countResult = sshService.executeCommand("ls -1A ${shellQuote(expandedPath)} 2>/dev/null | wc -l")
                val currentCount = countResult.getOrNull()?.trim()?.toIntOrNull() ?: 0
                
                if (currentCount != lastFileCount) {
                    lastFileCount = currentCount
                    isCalculatingSizes = true
                    
                    // Get detailed info with sizes
                    val result = sshService.executeCommand("""
                        cd ${shellQuote(expandedPath)} 2>/dev/null && for f in * .[^.]*; do
                            if [ -e "${'$'}f" ]; then
                                if [ -d "${'$'}f" ]; then
                                    size=${'$'}(du -sh "${'$'}f" 2>/dev/null | cut -f1)
                                    echo "DIR|${'$'}size|${'$'}f"
                                else
                                    size=${'$'}(ls -lh "${'$'}f" 2>/dev/null | awk '{print ${'$'}5}')
                                    echo "FILE|${'$'}size|${'$'}f"
                                fi
                            fi
                        done 2>/dev/null
                    """.trimIndent())
                    
                    result.onSuccess { output ->
                        files = parseCustomLsOutput(output)
                    }
                    isCalculatingSizes = false
                }
            }
            
            kotlinx.coroutines.delay(10000)  // Refresh every 10 seconds
        }
    }
    
    // Navigate to folder
    fun navigateTo(folder: String) {
        currentPath = if (folder.startsWith("/")) {
            expandRemotePath(folder)
        } else {
            buildChildRemotePath(currentPath, folder)
        }
    }
    
    // Navigate back
    fun navigateBack() {
        if (canNavigateUp(currentPath)) {
            currentPath = parentRemotePath(currentPath)
        }
    }
    
    // Download file via URL
    fun downloadFile(url: String) {
        if (url.isBlank()) return
        scope.launch {
            isDownloading = true
            showDownloadDialog = false
            
            val filename = url.substringAfterLast("/").ifBlank { "model.bin" }
            val expandedPath = expandRemotePath(currentPath)
            
            sshService.executeCommandStreaming(
                "cd ${shellQuote(expandedPath)} && wget --progress=dot:mega -O ${shellQuote(filename)} ${shellQuote(url)}"
            )
            
            isDownloading = false
            downloadUrl = ""
            
            // Refresh file list
            val result = sshService.executeCommand("ls -la --time-style=long-iso ${shellQuote(expandedPath)} 2>/dev/null || ls -la ${shellQuote(expandedPath)} 2>/dev/null")
            result.onSuccess { output ->
                files = parseLsOutputEnhanced(output)
            }
            
            Toast.makeText(context, resources.getString(R.string.file_download_success) + ": $filename", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Delete file
    fun deleteFile(file: FileEntry) {
        scope.launch {
            val expandedPath = expandRemotePath(currentPath)
            val cmd = if (file.isDirectory) "rm -rf" else "rm -f"
            
            sshService.executeCommand("$cmd ${shellQuote(buildChildRemotePath(expandedPath, file.name))}")
            
            // Refresh
            val result = sshService.executeCommand("ls -la --time-style=long-iso ${shellQuote(expandedPath)} 2>/dev/null || ls -la ${shellQuote(expandedPath)} 2>/dev/null")
            result.onSuccess { output ->
                files = parseLsOutputEnhanced(output)
            }
            
            fileToDelete = null
            Toast.makeText(context, resources.getString(R.string.file_deleted_success, file.name), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Toggle file selection
    fun toggleSelection(fileName: String) {
        selectedFiles = if (selectedFiles.contains(fileName)) {
            selectedFiles - fileName
        } else {
            selectedFiles + fileName
        }
        if (selectedFiles.isEmpty()) {
            isSelectionMode = false
        }
    }
    
    AppScreenScaffold(
        title = stringResource(R.string.file_manager_title),
        onBack = { navController.popBackStack() },
        actions = {
            // Sort button
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.action_sort))
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelResId)) },
                            onClick = {
                                sortOption = option
                                showSortMenu = false
                            },
                            leadingIcon = {
                                if (sortOption == option) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
            // Refresh button
            IconButton(onClick = {
                val expandedPath = expandRemotePath(currentPath)
                scope.launch {
                    val result = sshService.executeCommand(
                        "ls -la --time-style=long-iso ${shellQuote(expandedPath)} 2>/dev/null || ls -la ${shellQuote(expandedPath)} 2>/dev/null"
                    )
                    result.onSuccess { output ->
                        files = parseLsOutputEnhanced(output)
                    }
                }
            }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
            }
        },
        bottomBar = {
            // Selection action bar
            if (isSelectionMode && selectedFiles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.file_selected_count, selectedFiles.size),
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Select All
                            OutlinedButton(onClick = {
                                selectedFiles = sortedFiles.map { it.name }.toSet()
                            }) {
                                Text(stringResource(R.string.dataset_select_all))
                            }
                            // Download selected
                            Button(onClick = {
                                folderPicker.launch(null)
                            }) {
                                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_download))
                            }
                            // Cancel selection
                            IconButton(onClick = {
                                selectedFiles = emptySet()
                                isSelectionMode = false
                            }) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .walkthroughTarget("termux.files")
        ) {
            // Not connected warning
            if (!isConnected) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ssh_not_connected_error))
                    }
                }
                return@AppScreenScaffold
            }
            
            // Detecting tools
            if (isDetecting) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.file_detecting_tools))
                    }
                }
                return@AppScreenScaffold
            }
            
            // No tools installed
            if (installedTools.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.file_no_tools_installed), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.file_no_tools_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                return@AppScreenScaffold
            }
            
            // Tool tabs
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Root filesystem chip
                item {
                    FilterChip(
                        selected = selectedTool == null && currentPath == "/",
                        onClick = {
                            selectedTool = null
                            currentPath = "/"
                        },
                        label = {
                            Text(stringResource(R.string.file_root_label))
                        }
                    )
                }
                // HuggingFace cache shortcut
                item {
                    FilterChip(
                        selected = currentPath == "/root/.cache/huggingface",
                        onClick = {
                            selectedTool = null
                            currentPath = "/root/.cache/huggingface"
                        },
                        label = {
                            Text(stringResource(R.string.file_hf_cache_label))
                        }
                    )
                }
                // FastSD results shortcut
                item {
                    FilterChip(
                        selected = currentPath == "/root/fastsdcpu/results",
                        onClick = {
                            selectedTool = null
                            currentPath = "/root/fastsdcpu/results"
                        },
                        label = {
                            Text(stringResource(R.string.file_sd_results_label))
                        }
                    )
                }
                // Tool chips
                items(installedTools) { tool ->
                    FilterChip(
                        selected = selectedTool?.id == tool.id,
                        onClick = {
                            selectedTool = tool
                            currentPath = expandRemotePath(tool.installPath)
                        },
                        label = {
                            Text("${tool.emoji} ${tool.name}")
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Current path breadcrumb
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    if (canNavigateUp(currentPath)) {
                        IconButton(
                            onClick = { navigateBack() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.agent_parent_folder),
                                tint = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                    
                    Text(
                        currentPath,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // File count and calculating indicator
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${sortedFiles.size} items",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        if (isCalculatingSizes) {
                            Text(
                                stringResource(R.string.file_calculating_sizes_status),
                                color = MaterialTheme.colorScheme.tertiary,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Download button (URL)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        walkthroughTargets?.recordEvent("termux.files")
                        uploadPicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isDownloading && !isUploading
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_uploading_status))
                    } else {
                        Icon(Icons.Default.Upload, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_upload_from_device))
                    }
                }

                Button(
                    onClick = {
                        walkthroughTargets?.recordEvent("termux.files")
                        showDownloadDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isDownloading && !isUploading
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_downloading_status))
                    } else {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_download_url))
                    }
                }
            }

            if (isUploading) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            uploadProgress?.let {
                                stringResource(
                                    R.string.file_upload_progress_files,
                                    it.fileIndex,
                                    it.fileCount
                                )
                            } ?: stringResource(R.string.file_uploading_status),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        uploadProgress?.let { progress ->
                            Text(
                                progress.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            LinearProgressIndicator(
                                progress = { progress.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                stringResource(
                                    R.string.file_upload_progress_detail,
                                    formatSizeEnhanced(progress.bytesSent),
                                    formatSizeEnhanced(progress.totalBytes),
                                    progress.progressPercent
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Selection mode hint
            if (!isSelectionMode && sortedFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.file_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // File grid
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Error: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (sortedFiles.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.file_empty_folder),
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(sortedFiles) { file ->
                        val isSelected = selectedFiles.contains(file.name)
                        FileItemEnhanced(
                            file = file,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(file.name)
                                } else if (file.isDirectory) {
                                    navigateTo(file.name)
                                }
                            },
                            onLongClick = {
                                isSelectionMode = true
                                toggleSelection(file.name)
                            },
                            onDelete = { fileToDelete = file }
                        )
                    }
                }
            }
        }
    }
    
    // Download dialog
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.file_download_url_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_download_url_hint))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text(stringResource(R.string.file_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.file_target_path, currentPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadFile(downloadUrl) },
                    enabled = downloadUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Delete confirmation dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.file_delete_confirm_title, file.name)) },
            text = {
                Text(stringResource(R.string.file_delete_confirm_text, if (file.isDirectory) "folder" else "file", file.name))
            },
            confirmButton = {
                Button(
                    onClick = { deleteFile(file) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Enhanced file item with selection support
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemEnhanced(
    file: FileEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                file.isDirectory -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Selection checkbox overlay
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Icon
            Text(
                if (file.isDirectory) "📁" else "📄",
                fontSize = if (isSelectionMode) 24.sp else 32.sp
            )
            
            // Name
            Text(
                file.name,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            // Size
            Text(
                file.size,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Modified time (compact)
            if (file.modifiedTime.isNotBlank()) {
                Text(
                    file.modifiedTime.take(10),  // Just date part
                    fontSize = 7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            // Delete button (not in selection mode)
            if (!isSelectionMode) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

private fun expandRemotePath(path: String): String {
    return when {
        path == "~" -> "/root"
        path.startsWith("~/") -> path.replaceFirst("~", "/root")
        path.isBlank() -> "/"
        else -> path
    }
}

private fun buildChildRemotePath(parent: String, child: String): String {
    val normalizedParent = expandRemotePath(parent).trimEnd('/')
    return if (normalizedParent.isBlank() || normalizedParent == "/") {
        "/$child"
    } else {
        "$normalizedParent/$child"
    }
}

private fun parentRemotePath(path: String): String {
    val normalized = expandRemotePath(path).trimEnd('/')
    if (normalized.isBlank() || normalized == "/") return "/"
    return normalized.substringBeforeLast("/", missingDelimiterValue = "/").ifBlank { "/" }
}

private fun canNavigateUp(path: String): Boolean = expandRemotePath(path).trimEnd('/').let { normalized ->
    normalized.isNotBlank() && normalized != "/"
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    }
}

/**
 * Parse `ls -la` output into FileEntry list with size and time.
 * Handles both:
 *   - long-iso format (8 columns): drwxr-xr-x 2 root root 4096 2024-01-01 12:00 dirname
 *   - traditional format (9 columns): drwxr-xr-x 2 root root 4096 Dec 30 12:00 dirname
 */
fun parseLsOutputEnhanced(output: String): List<FileEntry> {
    return output.lines()
        .drop(1) // Skip "total" line
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 9)
            
            // Need at least 8 parts for long-iso format
            if (parts.size < 8) return@mapNotNull null
            
            // Determine format based on date column (index 5)
            // long-iso has YYYY-MM-DD, traditional has month name like "Dec"
            val isLongIso = parts[5].contains("-")
            
            val name: String
            val modifiedTime: String
            
            if (isLongIso) {
                // 8-column format: perms links owner group size YYYY-MM-DD HH:MM name
                name = parts[7]
                modifiedTime = "${parts[5]} ${parts[6]}"
            } else {
                // 9-column format: perms links owner group size Mon DD HH:MM name
                if (parts.size < 9) return@mapNotNull null
                name = parts[8]
                modifiedTime = "${parts[5]} ${parts[6]} ${parts[7]}"
            }
            
            if (name == "." || name == "..") return@mapNotNull null
            
            val sizeBytes = parts[4].toLongOrNull() ?: 0
            
            FileEntry(
                name = name,
                isDirectory = parts[0].startsWith("d"),
                size = formatSizeEnhanced(sizeBytes),
                sizeBytes = sizeBytes,
                permissions = parts[0],
                modifiedTime = modifiedTime
            )
        }
}

/**
 * Format file size with appropriate units
 */
fun formatSizeEnhanced(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

/**
 * Parse custom ls output format: DIR|size|name or FILE|size|name
 * This format includes real folder sizes from du -sh
 */
fun parseCustomLsOutput(output: String): List<FileEntry> {
    return output.lines()
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            
            val type = parts[0]
            val size = parts[1]
            val name = parts[2]
            
            if (name == "." || name == ".." || name == "*" || name == ".[^.]*") return@mapNotNull null
            
            // Parse human-readable size to bytes for sorting
            val sizeBytes = parseSizeToBytes(size)
            
            FileEntry(
                name = name,
                isDirectory = type == "DIR",
                size = size.ifBlank { "0 B" },
                sizeBytes = sizeBytes,
                permissions = if (type == "DIR") "d---------" else "----------",
                modifiedTime = ""  // Not available in this format
            )
        }
}

/**
 * Parse human-readable size (1.4G, 53K, etc) to bytes for sorting
 */
fun parseSizeToBytes(sizeStr: String): Long {
    val cleaned = sizeStr.trim().uppercase()
    if (cleaned.isBlank()) return 0
    
    val numPart = cleaned.filter { it.isDigit() || it == '.' }
    val num = numPart.toDoubleOrNull() ?: return 0
    
    return when {
        cleaned.endsWith("T") -> (num * 1_000_000_000_000).toLong()
        cleaned.endsWith("G") -> (num * 1_000_000_000).toLong()
        cleaned.endsWith("M") -> (num * 1_000_000).toLong()
        cleaned.endsWith("K") -> (num * 1_000).toLong()
        else -> num.toLong()
    }
}

/**
 * Download selected files to a folder (via SCP or cat+base64)
 */
suspend fun downloadFilesToFolder(
    context: android.content.Context,
    sshService: SSHService,
    remotePath: String,
    fileNames: List<String>,
    targetUri: android.net.Uri
) = withContext(Dispatchers.IO) {
    val expandedPath = expandRemotePath(remotePath)
    
    for (fileName in fileNames) {
        try {
            // Get file content via base64 encoding (works for all file types)
            val result = sshService.executeCommand("base64 ${shellQuote(buildChildRemotePath(expandedPath, fileName))} 2>/dev/null")
            result.onSuccess { base64Content ->
                if (base64Content.isNotBlank()) {
                    // Decode and write to target folder
                    val bytes = android.util.Base64.decode(base64Content.trim(), android.util.Base64.DEFAULT)
                    
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetUri)
                    val newFile = docFile?.createFile("application/octet-stream", fileName)
                    newFile?.uri?.let { fileUri ->
                        context.contentResolver.openOutputStream(fileUri)?.use { os ->
                            os.write(bytes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but continue with other files
            e.printStackTrace()
        }
    }
}

suspend fun uploadFilesToRemoteFolder(
    context: android.content.Context,
    sshService: SSHService,
    remotePath: String,
    uris: List<Uri>,
    onProgress: ((UploadProgressState) -> Unit)? = null
): Result<List<String>> = withContext(Dispatchers.IO) {
    try {
        val expandedPath = expandRemotePath(remotePath)
        sshService.executeCommand("mkdir -p ${shellQuote(expandedPath)}").getOrThrow()

        val uploadedNames = mutableListOf<String>()
        uris.forEachIndexed { index, uri ->
            val fileName = sanitizeRemoteFileName(
                queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: return@withContext Result.failure(Exception("Could not read file name"))
            )
            val remoteFilePath = buildChildRemotePath(expandedPath, fileName)
            val stagedFile = stageUriForRemoteUpload(context, uri, fileName)
            val fileCount = uris.size
            val totalBytes = stagedFile.length().coerceAtLeast(0L)

            try {
                if (onProgress != null) {
                    withContext(Dispatchers.Main) {
                        onProgress.invoke(
                            UploadProgressState(
                                fileName = fileName,
                                fileIndex = index + 1,
                                fileCount = fileCount,
                                bytesSent = 0L,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
                sshService.uploadToRemotePath(
                    localFile = stagedFile,
                    remotePath = remoteFilePath,
                    onProgress = { sent, total ->
                        onProgress?.invoke(
                            UploadProgressState(
                                fileName = fileName,
                                fileIndex = index + 1,
                                fileCount = fileCount,
                                bytesSent = sent,
                                totalBytes = total
                            )
                        )
                    }
                ).getOrThrow()
            } finally {
                stagedFile.delete()
            }

            uploadedNames += fileName
        }

        Result.success(uploadedNames)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun sanitizeRemoteFileName(name: String): String {
    return name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "upload.bin" }
}

private fun stageUriForRemoteUpload(
    context: android.content.Context,
    uri: Uri,
    fileName: String
): File {
    val safeBaseName = fileName
        .substringBeforeLast('.', missingDelimiterValue = fileName)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "upload" }
        .take(32)
        .padEnd(3, '_')
    val safeSuffix = fileName
        .substringAfterLast('.', missingDelimiterValue = "")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .take(12)
        .let { extension ->
            if (extension.isBlank()) ".tmp" else ".$extension"
        }
    val tempFile = File.createTempFile(safeBaseName, safeSuffix, context.cacheDir)

    try {
        val contentResolver = context.contentResolver
        val typedMime = contentResolver.getType(uri)
        val openers = listOf<() -> InputStream?>(
            { contentResolver.openInputStream(uri)?.buffered() },
            { contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()?.buffered() },
            {
                contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).buffered()
                }
            },
            {
                typedMime?.let { mime ->
                    contentResolver.openTypedAssetFileDescriptor(uri, mime, null)
                        ?.createInputStream()
                        ?.buffered()
                }
            },
            {
                contentResolver.openTypedAssetFileDescriptor(uri, "*/*", null)
                    ?.createInputStream()
                    ?.buffered()
            }
        )

        var lastError: Exception? = null
        for (openStream in openers) {
            try {
                val input = openStream() ?: continue
                input.use { stream ->
                    copyUploadInputToTempFile(stream, tempFile)
                }
                return tempFile
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw lastError ?: IOException("Could not open input stream for upload")
    } catch (error: Exception) {
        tempFile.delete()
        throw error
    }
}

private fun copyUploadInputToTempFile(
    inputStream: InputStream,
    tempFile: File
) {
    tempFile.outputStream().buffered().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
        output.flush()
    }
}
