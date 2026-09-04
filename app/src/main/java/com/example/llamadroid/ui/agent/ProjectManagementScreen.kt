package com.example.llamadroid.ui.agent

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.ProjectExportService
import com.example.llamadroid.service.ProjectSnapshotService
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Screen for project management - Export/Import and Snapshots
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagementScreen(
    projectFolder: String,
    conversationId: Long,
    agentService: com.example.llamadroid.service.AgentService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val exportSuccessText = stringResource(R.string.agent_export_success)
    val exportFailedFormat = stringResource(R.string.agent_export_failed)
    val importSuccessText = stringResource(R.string.agent_import_success)
    val importFailedFormat = stringResource(R.string.agent_import_failed)
    val rollbackFailedText = stringResource(R.string.agent_rollback_failed)
    val snapshotDeletedText = stringResource(R.string.agent_snapshot_deleted)
    val snapshotDefaultNameFormat = stringResource(R.string.agent_snapshot_default_name)
    val snapshotSuccessText = stringResource(R.string.agent_snapshot_success)
    val snapshotFailedText = stringResource(R.string.agent_snapshot_failed)
    val scope = rememberCoroutineScope()
    
    val exportService = remember { ProjectExportService(context, agentService) }
    val snapshotService = remember { ProjectSnapshotService(agentService) }
    
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var isCreatingSnapshot by remember { mutableStateOf(false) }
    var snapshotDescription by remember { mutableStateOf("") }
    var showSnapshotDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    
    val snapshots by snapshotService.snapshots.collectAsState()
    
    // Load snapshots on start
    LaunchedEffect(projectFolder) {
        snapshotService.listSnapshots(projectFolder)
    }
    
    // Export file picker
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch {
                isExporting = true
                val result = exportService.exportProject(projectFolder, conversationId, uri)
                statusMessage = if (result.isSuccess) {
                    exportSuccessText
                } else {
                    String.format(
                        Locale.getDefault(),
                        exportFailedFormat,
                        result.exceptionOrNull()?.message ?: ""
                    )
                }
                isExporting = false
            }
        }
    }
    
    // Import file picker
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                isImporting = true
                val result = exportService.importProject(projectFolder, conversationId, uri)
                statusMessage = if (result.isSuccess) {
                    importSuccessText
                } else {
                    String.format(
                        Locale.getDefault(),
                        importFailedFormat,
                        result.exceptionOrNull()?.message ?: ""
                    )
                }
                isImporting = false
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_project_mgmt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
            // Status message
            statusMessage?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.startsWith("✅")) Color(0xFF4CAF50).copy(alpha = 0.2f)
                            else Color(0xFFF44336).copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg)
                            IconButton(onClick = { statusMessage = null }) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_dismiss))
                            }
                        }
                    }
                }
            }
            
            // Project Info
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.agent_current_project), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("/workspace/$projectFolder/", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            
            // Export/Import Section
            item {
                Text(stringResource(R.string.agent_export_import_header), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { 
                            exportLauncher.launch(ProjectExportService.getExportFileName(projectFolder))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Icon(Icons.Default.Upload, null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_export_zip))
                    }
                    
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip")) },
                        modifier = Modifier.weight(1f),
                        enabled = !isImporting
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Download, null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_import_zip))
                    }
                }
            }
            
            // Snapshots Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.agent_snapshots_header), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    FilledTonalButton(
                        onClick = { showSnapshotDialog = true }
                    ) {
                        Icon(Icons.Default.AddAPhoto, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_new))
                    }
                }
            }
            
            if (snapshots.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📷", fontSize = 32.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.agent_no_snapshots), color = Color.Gray)
                                Text(stringResource(R.string.agent_rollback_hint), fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            } else {
                items(snapshots, key = { it.id }) { snapshot ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(snapshot.description, fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(R.string.agent_snapshot_item_desc, ProjectSnapshotService.formatTimestamp(snapshot.timestamp), snapshot.files.size, stringResource(R.string.agent_files_count_label)),
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Row {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val result = snapshotService.rollbackToSnapshot(projectFolder, snapshot.id)
                                            statusMessage = if (result.isSuccess) result.getOrThrow() else rollbackFailedText
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.action_restore))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            snapshotService.deleteSnapshot(projectFolder, snapshot.id)
                                            statusMessage = snapshotDeletedText
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Create Snapshot Dialog
    if (showSnapshotDialog) {
        AlertDialog(
            onDismissRequest = { showSnapshotDialog = false },
            title = { Text(stringResource(R.string.agent_create_snapshot_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_snapshot_desc_label))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = snapshotDescription,
                        onValueChange = { snapshotDescription = it },
                        placeholder = { Text(stringResource(R.string.agent_snapshot_desc_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isCreatingSnapshot = true
                            val result = snapshotService.createSnapshot(
                                projectFolder,
                                snapshotDescription.ifBlank {
                                    String.format(
                                        Locale.getDefault(),
                                        snapshotDefaultNameFormat,
                                        snapshots.size + 1
                                    )
                                }
                            )
                            statusMessage = if (result.isSuccess) snapshotSuccessText else snapshotFailedText
                            snapshotDescription = ""
                            showSnapshotDialog = false
                            isCreatingSnapshot = false
                        }
                    },
                    enabled = !isCreatingSnapshot
                ) {
                    if (isCreatingSnapshot) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text(stringResource(R.string.action_create))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSnapshotDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
