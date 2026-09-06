package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.*
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dataset Creator - Project List Screen
 * Enhanced with LLM-powered Q&A generation pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.datasetDao()
    
    val projects by dao.getAllProjects().collectAsState(initial = emptyList())
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<DatasetProjectEntity?>(null) }
    
    AppScreenScaffold(
        title = stringResource(R.string.dataset_title),
        onBack = { navController.popBackStack() },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.dataset_new))
                }
            }
        }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .walkthroughTarget("documents.dataset.tabs"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Info card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dataset_header), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dataset_desc),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Stats
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${projects.size}", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text(stringResource(R.string.dataset_count_label), fontSize = 12.sp)
                        }
                    }
                }
            }
            
            // Project list
            items(projects) { project ->
                ProjectCard(
                    project = project,
                    onClick = {
                        navController.navigate(Screen.DatasetProject.createRoute(project.id))
                    },
                    onDelete = { projectToDelete = project }
                )
            }
            
            // Empty state
            if (projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.dataset_empty), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.dataset_empty_hint),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Create dialog
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.dataset_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dataset_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch {
                                val id = dao.insertProject(DatasetProjectEntity(name = name))
                                showCreateDialog = false
                                navController.navigate(Screen.DatasetProject.createRoute(id))
                            }
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Delete confirmation
    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text(stringResource(R.string.dataset_delete_title)) },
            text = { Text(stringResource(R.string.dataset_delete_message, project.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dao.deleteProject(project)
                            projectToDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectCard(
    project: DatasetProjectEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.dataset_created_at, dateFormat.format(Date(project.createdAt))),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(project.serverUrl, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                    if (project.useCoT) {
                        Text("•", fontSize = 11.sp, color = Color.Gray)
                        Text("CoT", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = Color.Red)
            }
        }
    }
}
