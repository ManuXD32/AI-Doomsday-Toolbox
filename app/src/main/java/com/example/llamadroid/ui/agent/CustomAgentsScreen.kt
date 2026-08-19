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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.CustomAgentEntity
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.AgentRuntimeProfileKeys
import com.example.llamadroid.data.runtime.AgentRuntimeProfileStore
import com.example.llamadroid.data.runtime.AgentRuntimeContinueAction
import com.example.llamadroid.data.runtime.EmptyAgentRuntimeProfileStore
import com.example.llamadroid.data.runtime.ManagedLlamaServerDescriptor
import kotlinx.coroutines.launch

/**
 * Screen for managing custom agents
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAgentsScreen(
    onBack: () -> Unit,
    runtimeProfileStore: AgentRuntimeProfileStore = EmptyAgentRuntimeProfileStore,
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    ollamaModels: List<String> = emptyList(),
    llamaSwapModels: List<String> = emptyList(),
    onRuntimeContinue: ((AgentRuntimeContinueAction) -> Unit)? = null
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    val agents by db.customAgentDao().getAllAgents().collectAsState(initial = emptyList())
    val runtimeProfiles by runtimeProfileStore.observeProfiles().collectAsState(initial = emptyList())
    val runtimeEndpointConfigs by runtimeProfileStore.observeEndpointConfigs().collectAsState(initial = emptyList())
    val runtimeProfilesByKey = remember(runtimeProfiles) {
        runtimeProfiles.associateBy { it.agentKey }
    }
    val liteRtModels by db.liteRtModelDao().observeAll().collectAsState(initial = emptyList())
    val liteRtOptions = remember(liteRtModels) {
        liteRtModels.map { model ->
            AgentLiteRtProfileOption(
                id = model.id,
                displayName = model.displayName,
                filename = model.filename
            )
        }
    }
    var showEditor by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<CustomAgentEntity?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_custom_agents_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        editingAgent = null
                        showEditor = true 
                    }) {
                        Icon(Icons.Default.Add, stringResource(R.string.agent_add_agent))
                    }
                }
            )
        }
    ) { padding ->
        if (agents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🤖", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.agent_no_custom_agents), fontWeight = FontWeight.Bold)
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
                items(agents, key = { it.name }) { agent ->
                    val profile = runtimeProfilesByKey[AgentRuntimeProfileKeys.custom(agent.name)]
                        ?: AgentRuntimeProfile(
                            agentKey = AgentRuntimeProfileKeys.custom(agent.name),
                            backend = AgentRuntimeBackend.OLLAMA.id,
                            model = agent.model
                        )
                    CustomAgentCard(
                        agent = agent,
                        runtimeProfile = profile,
                        endpointConfigs = runtimeEndpointConfigs,
                        managedLlamaServers = managedLlamaServers,
                        liteRtModels = liteRtOptions,
                        onEdit = {
                            editingAgent = agent
                            showEditor = true
                        },
                        onDelete = {
                            scope.launch {
                                db.customAgentDao().deleteAgent(agent)
                                runtimeProfileStore.delete(AgentRuntimeProfileKeys.custom(agent.name))
                            }
                        },
                        onToggle = { enabled ->
                            scope.launch {
                                db.customAgentDao().setAgentEnabled(agent.name, enabled)
                            }
                        }
                    )
                }
            }
        }
    }
    
    if (showEditor) {
        CustomAgentEditorDialog(
            agent = editingAgent,
            runtimeProfile = editingAgent?.let { current ->
                runtimeProfilesByKey[AgentRuntimeProfileKeys.custom(current.name)]
                    ?: AgentRuntimeProfile(
                        agentKey = AgentRuntimeProfileKeys.custom(current.name),
                        backend = AgentRuntimeBackend.OLLAMA.id,
                        model = current.model
                    )
            },
            endpointConfigs = runtimeEndpointConfigs,
            managedLlamaServers = managedLlamaServers,
            ollamaModels = ollamaModels,
            llamaSwapModels = llamaSwapModels,
            liteRtModels = liteRtOptions,
            onRuntimeContinue = onRuntimeContinue,
            onSaveEndpointConfig = { config ->
                scope.launch { runtimeProfileStore.saveEndpointConfig(config) }
            },
            onDeleteEndpointConfig = { endpointId ->
                scope.launch { runtimeProfileStore.deleteEndpointConfig(endpointId) }
            },
            onDismiss = { showEditor = false },
            onSave = { savedAgent, savedProfile ->
                scope.launch {
                    editingAgent?.takeIf { previous -> previous.name != savedAgent.name }?.let { previous ->
                        db.customAgentDao().deleteAgent(previous)
                        runtimeProfileStore.delete(AgentRuntimeProfileKeys.custom(previous.name))
                    }
                    db.customAgentDao().insertAgent(savedAgent)
                    runtimeProfileStore.save(savedProfile)
                    showEditor = false
                }
            }
        )
    }
}

@Composable
private fun CustomAgentCard(
    agent: CustomAgentEntity,
    runtimeProfile: AgentRuntimeProfile,
    endpointConfigs: List<AgentRuntimeEndpointConfig>,
    managedLlamaServers: List<ManagedLlamaServerDescriptor>,
    liteRtModels: List<AgentLiteRtProfileOption>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (agent.isEnabled) 
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(agent.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            agent.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            agent.name.uppercase(),
                            fontSize = 10.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Switch(
                    checked = agent.isEnabled,
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // System prompt preview
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    agent.systemPrompt.take(100) + if (agent.systemPrompt.length > 100) "..." else "",
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.padding(8.dp)
                )
            }
            
            AgentRuntimeProfileSummary(
                profile = runtimeProfile,
                endpointConfigs = endpointConfigs,
                managedLlamaServers = managedLlamaServers,
                liteRtModels = liteRtModels,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
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
private fun CustomAgentEditorDialog(
    agent: CustomAgentEntity?,
    runtimeProfile: AgentRuntimeProfile?,
    endpointConfigs: List<AgentRuntimeEndpointConfig>,
    managedLlamaServers: List<ManagedLlamaServerDescriptor>,
    ollamaModels: List<String>,
    llamaSwapModels: List<String>,
    liteRtModels: List<AgentLiteRtProfileOption>,
    onRuntimeContinue: ((AgentRuntimeContinueAction) -> Unit)?,
    onSaveEndpointConfig: (AgentRuntimeEndpointConfig) -> Unit,
    onDeleteEndpointConfig: (Long) -> Unit,
    onDismiss: () -> Unit,
    onSave: (CustomAgentEntity, AgentRuntimeProfile) -> Unit
) {
    var name by remember { mutableStateOf(agent?.name ?: "") }
    var displayName by remember { mutableStateOf(agent?.displayName ?: "") }
    var emoji by remember { mutableStateOf(agent?.emoji ?: "🤖") }
    var systemPrompt by remember { mutableStateOf(agent?.systemPrompt ?: "") }
    var allowedToolsJson by remember { mutableStateOf(agent?.allowedToolsJson ?: "[]") }
    var exampleUsage by remember { mutableStateOf(agent?.exampleUsage ?: "") }
    var canDelegate by remember { mutableStateOf(agent?.canDelegateToOthers ?: false) }
    var visionEnabled by remember { mutableStateOf(agent?.visionEnabled ?: false) }
    var editedRuntimeProfile by remember(agent?.name, runtimeProfile?.updatedAt) {
        mutableStateOf(
            runtimeProfile ?: AgentRuntimeProfile(
                agentKey = AgentRuntimeProfileKeys.custom(agent?.name ?: "NEW_AGENT"),
                backend = AgentRuntimeBackend.OLLAMA.id,
                model = agent?.model
            )
        )
    }
    
    val isEditing = agent != null
    
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
                        if (isEditing) stringResource(R.string.agent_edit_agent) else stringResource(R.string.agent_new_agent),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AgentRuntimeProfileControls(
                        profile = editedRuntimeProfile,
                        // The parent can supply a model-aware profile store/catalog. A free-form
                        // model field remains available for Ollama/llama-swap during migration.
                        ollamaModels = ollamaModels,
                        llamaSwapModels = llamaSwapModels,
                        managedLlamaServers = managedLlamaServers,
                        liteRtModels = liteRtModels,
                        endpointConfigs = endpointConfigs,
                        onSaveEndpointConfig = onSaveEndpointConfig,
                        onDeleteEndpointConfig = onDeleteEndpointConfig,
                        onProfileChange = { editedRuntimeProfile = it },
                        onContinue = onRuntimeContinue,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isEditing) {
                        OutlinedButton(
                            onClick = {
                                name = "RESEARCHER"
                                displayName = "Internet Researcher"
                                emoji = "🌐"
                                systemPrompt = "You are the Internet Researcher agent. Your job is to search the web for information and provide concise summaries to the Orchestrator."
                                allowedToolsJson = "[\"web_search\", \"fetch_url\", \"read_file\", \"read_memory\", \"write_memory\", \"list_memory\", \"finish_task\"]"
                                exampleUsage = "Use the RESEARCHER agent when you need up-to-date information from the internet."
                                canDelegate = false
                                visionEnabled = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.agent_agent_example_template_title))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = emoji,
                            onValueChange = { emoji = it },
                            label = { Text(stringResource(R.string.agent_agent_emoji_label)) },
                            modifier = Modifier.weight(0.3f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.uppercase() },
                            label = { Text(stringResource(R.string.agent_agent_name_label)) },
                            placeholder = { Text(stringResource(R.string.agent_agent_name_hint)) },
                            enabled = !isEditing,
                            modifier = Modifier.weight(0.7f),
                            singleLine = true
                        )
                    }
                    
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.agent_agent_display_name_label)) },
                        placeholder = { Text(stringResource(R.string.agent_agent_display_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.agent_prompt_label)) },
                        placeholder = { Text(stringResource(R.string.agent_agent_prompt_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        maxLines = 10
                    )
                    
                    OutlinedTextField(
                        value = allowedToolsJson,
                        onValueChange = { allowedToolsJson = it },
                        label = { Text(stringResource(R.string.agent_agent_allowed_tools_label)) },
                        placeholder = { Text(stringResource(R.string.agent_agent_allowed_tools_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        supportingText = { Text(stringResource(R.string.agent_agent_allowed_tools_desc)) }
                    )
                    
                    OutlinedTextField(
                        value = exampleUsage,
                        onValueChange = { exampleUsage = it },
                        label = { Text(stringResource(R.string.agent_agent_example_label)) },
                        placeholder = { Text("call_agent(agent=\"$name\", name=\"Darwin\", task=\"Debug the crash\")") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        supportingText = { Text(stringResource(R.string.agent_agent_example_desc)) }
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.agent_agent_delegate_label))
                        Switch(
                            checked = canDelegate,
                            onCheckedChange = { canDelegate = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.agent_vision_enabled))
                        Switch(
                            checked = visionEnabled,
                            onCheckedChange = { visionEnabled = it }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Save button
                Button(
                    onClick = {
                        val newAgent = CustomAgentEntity(
                            name = name.trim().uppercase(),
                            displayName = displayName.trim(),
                            emoji = emoji.ifBlank { "🤖" },
                            systemPrompt = systemPrompt,
                            // Compatibility mirror only. Runtime dispatch reads the profile store.
                            model = editedRuntimeProfile.model?.takeIf { it.isNotBlank() },
                            allowedToolsJson = allowedToolsJson.ifBlank { "[]" },
                            exampleUsage = exampleUsage,
                            canDelegateToOthers = canDelegate,
                            visionEnabled = visionEnabled,
                            isEnabled = agent?.isEnabled ?: true,
                            createdAt = agent?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        onSave(
                            newAgent,
                            editedRuntimeProfile.copy(
                                agentKey = AgentRuntimeProfileKeys.custom(name),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && displayName.isNotBlank() && systemPrompt.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEditing) stringResource(R.string.agent_tool_save_changes) else stringResource(R.string.agent_agent_create_btn))
                }
            }
        }
    }
}
