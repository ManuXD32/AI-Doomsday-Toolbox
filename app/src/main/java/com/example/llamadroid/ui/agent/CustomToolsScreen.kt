package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.CustomToolEntity
import kotlinx.coroutines.launch

/**
 * Screen for managing custom tools
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomToolsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    val tools by db.customToolDao().getAllTools().collectAsState(initial = emptyList())
    var showEditor by remember { mutableStateOf(false) }
    var editingTool by remember { mutableStateOf<CustomToolEntity?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_custom_tools_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                    IconButton(onClick = { 
                        editingTool = null
                        showEditor = true 
                    }) {
                        Icon(Icons.Default.Add, stringResource(R.string.agent_add_tool))
                    }
                }
            )
        }
    ) { padding ->
        if (tools.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔧", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.agent_no_tools), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.agent_tap_plus_hint), color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tools, key = { it.name }) { tool ->
                    CustomToolCard(
                        tool = tool,
                        onEdit = {
                            editingTool = tool
                            showEditor = true
                        },
                        onDelete = {
                            scope.launch {
                                db.customToolDao().deleteTool(tool)
                            }
                        },
                        onToggle = { enabled ->
                            scope.launch {
                                db.customToolDao().setToolEnabled(tool.name, enabled)
                            }
                        }
                    )
                }
            }
        }
    }
    
    if (showEditor) {
        CustomToolEditorDialog(
            tool = editingTool,
            onDismiss = { showEditor = false },
            onSave = { savedTool ->
                scope.launch {
                    db.customToolDao().insertTool(savedTool)
                    showEditor = false
                }
            }
        )
    }
}

@Composable
private fun CustomToolCard(
    tool: CustomToolEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (tool.isEnabled) 
                MaterialTheme.colorScheme.surfaceVariant 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tool.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        tool.description,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                
                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Command template preview
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    tool.commandTemplate.take(100) + if (tool.commandTemplate.length > 100) "..." else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.padding(8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomToolEditorDialog(
    tool: CustomToolEntity?,
    onDismiss: () -> Unit,
    onSave: (CustomToolEntity) -> Unit
) {
    var name by remember { mutableStateOf(tool?.name ?: "") }
    var description by remember { mutableStateOf(tool?.description ?: "") }
    var commandTemplate by remember { mutableStateOf(tool?.commandTemplate ?: "") }
    var exampleUsage by remember { mutableStateOf(tool?.exampleUsage ?: "") }
    var parameters by remember { mutableStateOf(tool?.parametersJson ?: "{}") }
    var requiredParams by remember { mutableStateOf(tool?.requiredParamsJson ?: "[]") }
    var workingDirectory by remember { mutableStateOf(tool?.workingDirectory ?: ".") }
    var needsApproval by remember { mutableStateOf(tool?.needsApproval ?: true) }
    
    val isEditing = tool != null
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isEditing) stringResource(R.string.agent_edit_tool) else stringResource(R.string.agent_new_tool),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                if (!isEditing) {
                    OutlinedButton(
                        onClick = {
                            name = "get_weather"
                            description = "Get the current weather for a city."
                            commandTemplate = "curl -s https://wttr.in/{city}?format=3"
                            parameters = "{\"city\": \"string\"}"
                            requiredParams = "[\"city\"]"
                            exampleUsage = "tool_call=get_weather(city=\"London\")"
                            workingDirectory = "."
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_tool_example_template_title))
                    }
                }
                
                // Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace(" ", "_").lowercase() },
                        label = { Text(stringResource(R.string.agent_tool_name_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_name_hint)) },
                        enabled = !isEditing,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.agent_tool_desc_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_desc_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    
                    OutlinedTextField(
                        value = commandTemplate,
                        onValueChange = { commandTemplate = it },
                        label = { Text(stringResource(R.string.agent_tool_template_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_template_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        supportingText = { Text(stringResource(R.string.agent_tool_template_desc)) }
                    )

                    OutlinedTextField(
                        value = workingDirectory,
                        onValueChange = { workingDirectory = it },
                        label = { Text(stringResource(R.string.agent_tool_working_dir_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_working_dir_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.agent_tool_working_dir_desc)) }
                    )
                    
                    OutlinedTextField(
                        value = parameters,
                        onValueChange = { parameters = it },
                        label = { Text(stringResource(R.string.agent_tool_params_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_params_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    
                    OutlinedTextField(
                        value = requiredParams,
                        onValueChange = { requiredParams = it },
                        label = { Text(stringResource(R.string.agent_tool_required_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_required_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = exampleUsage,
                        onValueChange = { exampleUsage = it },
                        label = { Text(stringResource(R.string.agent_tool_example_label)) },
                        placeholder = { Text(stringResource(R.string.agent_tool_example_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        supportingText = { Text(stringResource(R.string.agent_tool_example_desc)) }
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.agent_tool_approval_label))
                        Switch(
                            checked = needsApproval,
                            onCheckedChange = { needsApproval = it }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Save button
                Button(
                    onClick = {
                        val newTool = CustomToolEntity(
                            name = name.trim(),
                            description = description.trim(),
                            commandTemplate = commandTemplate,
                            parametersJson = parameters,
                            requiredParamsJson = requiredParams,
                            exampleUsage = exampleUsage,
                            workingDirectory = workingDirectory.ifBlank { "." },
                            needsApproval = needsApproval,
                            isEnabled = tool?.isEnabled ?: true,
                            createdAt = tool?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        onSave(newTool)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && description.isNotBlank() && 
                              commandTemplate.isNotBlank() && exampleUsage.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEditing) stringResource(R.string.agent_tool_save_changes) else stringResource(R.string.agent_tool_create_btn))
                }
            }
        }
    }
}
