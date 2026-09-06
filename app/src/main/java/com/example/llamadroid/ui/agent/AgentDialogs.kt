package com.example.llamadroid.ui.agent

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.AgentRuntimeProfileKeys
import com.example.llamadroid.data.db.normalizeAgentRuntimeBackend
import com.example.llamadroid.data.runtime.AgentRuntimeGlobalOverride
import com.example.llamadroid.data.runtime.AgentRuntimeContinueAction
import com.example.llamadroid.data.runtime.AgentRuntimeProfileStore
import com.example.llamadroid.data.runtime.EmptyAgentRuntimeProfileStore
import com.example.llamadroid.data.runtime.ManagedLlamaServerDescriptor
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.friendlyBackendModelLabel
import com.example.llamadroid.service.isCriticalAgentProtocolTool
import com.example.llamadroid.service.resolveAgentLiteRtContextTokens
import com.example.llamadroid.service.resolveAgentLiteRtMaxOutputTokens
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import kotlinx.coroutines.launch
import java.util.Locale

enum class AgentSettingsSection {
    AGENTS,
    TOOLS
}

/** UI-facing name for the persisted, dispatch-aware General override model. */
typealias AgentGlobalOverrideState = AgentRuntimeGlobalOverride

private const val GLOBAL_OVERRIDE_CONNECTION_KEY = "__global_override_connection__"
private const val MANAGED_OVERRIDE_CONNECTION_KEY = "__managed_override_connection__"

@Composable
fun ModelSelectorDialog(
    currentModel: String,
    availableModels: List<OllamaService.OllamaModel>,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onPullModel: (String) -> Unit
) {
    var customModel by remember { mutableStateOf("") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.agent_select_model), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Installed models
                if (availableModels.isNotEmpty()) {
                    Text(stringResource(R.string.agent_installed_models), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(availableModels) { model ->
                            ListItem(
                                headlineContent = { Text(model.name, fontSize = 14.sp) },
                                leadingContent = {
                                    RadioButton(
                                        selected = model.name == currentModel,
                                        onClick = { onModelSelected(model.name) }
                                    )
                                },
                                trailingContent = {
                                    Text(
                                        "${model.size / (1024 * 1024 * 1024)}${stringResource(R.string.agent_unit_gb)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.clickable { onModelSelected(model.name) }
                            )
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                // Custom model input
                Text(stringResource(R.string.agent_custom_model_label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.agent_custom_model_hint), fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (customModel.isNotBlank()) {
                            onPullModel(customModel)
                            onModelSelected(customModel)
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.action_download))
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Setup info dialog with installation instructions - styled like Termux tools info cards
 */
@Composable
fun SetupInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val copySuccessFormat = stringResource(R.string.agent_copy_success)
    val installCommandLabel = stringResource(R.string.agent_install_cmd_label)
    val startCommandLabel = stringResource(R.string.agent_start_cmd_label)
    val toolNameClipboardLabel = stringResource(R.string.soft_studio_conversations_tool_name_clipboard)
    
    // One-line install command - configures SSH on port 8023 (separate from Termux tools port 8025)
    val oneLineInstall = """pkg install proot-distro -y && proot-distro install ubuntu --override-alias ai-agent && proot-distro login ai-agent --isolated -- bash -c "apt update && apt install -y openssh-server git ripgrep python3 nodejs npm curl wget && mkdir -p /run/sshd && sed -i 's/#Port 22/Port 8023/' /etc/ssh/sshd_config && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config && echo 'root:agent' | chpasswd && mkdir -p /workspace""""
    
    // Start command - uses port 8023
    val startCommand = "proot-distro login ai-agent --isolated -- /usr/sbin/sshd -p 8023 -D &"
    
    fun copyToClipboard(text: String, label: String) {
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(
            context,
            String.format(Locale.getDefault(), copySuccessFormat, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    // Get available tools for Orchestrator
    val tools = remember { com.example.llamadroid.service.AgentService.getAgentTools(com.example.llamadroid.service.AgentService.Companion.AgentRole.ORCHESTRATOR) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 650.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.agent_setup_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // RAM requirement (like Termux tools)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💾", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_ram_req), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(stringResource(R.string.agent_ram_desc), fontSize = 12.sp, color = Color(0xFF4CAF50))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Low-RAM tips
                Text(stringResource(R.string.agent_recommended_models), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                listOf(
                    stringResource(R.string.agent_model_tip_qwen),
                    stringResource(R.string.agent_model_tip_llama),
                    stringResource(R.string.agent_model_tip_granite)
                ).forEach { tip ->
                    Row(modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                        Text("•", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Integration info
                Text(stringResource(R.string.agent_integration_title), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    stringResource(R.string.agent_integration_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Features
                Text(stringResource(R.string.agent_features_title), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(stringResource(R.string.agent_feature_codegen), stringResource(R.string.agent_feature_file_io), stringResource(R.string.agent_feature_commands), stringResource(R.string.agent_feature_multi_agent), stringResource(R.string.agent_feature_vision), stringResource(R.string.agent_feature_web_search)).forEach { feature ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                feature,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // AVAILABLE TOOLS SECTION
                var showTools by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showTools = !showTools }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.agent_available_tools), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Icon(
                        if (showTools) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                AnimatedVisibility(visible = showTools) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            stringResource(R.string.agent_tools_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        tools.forEach { tool ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically, 
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            tool.name, 
                                            fontWeight = FontWeight.Bold, 
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        IconButton(
                                            onClick = { copyToClipboard(tool.name, toolNameClipboardLabel) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy, 
                                                stringResource(R.string.agent_tool_copy),
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        tool.description, 
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                    if (tool.requiredParams.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(
                                                R.string.soft_studio_conversations_required_params,
                                                tool.requiredParams.joinToString(", ")
                                            ),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                // ONE-LINE INSTALL
                Text(stringResource(R.string.agent_one_line_install), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                
                Surface(
                    color = Color.Black.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = oneLineInstall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = Color(0xFF4CAF50),
                                lineHeight = 12.sp
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard(oneLineInstall, installCommandLabel) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                stringResource(R.string.action_copy),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // START COMMAND
                Text(stringResource(R.string.agent_start_ssh_server), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                
                Surface(
                    color = Color.Black.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = startCommand,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard(startCommand, startCommandLabel) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                stringResource(R.string.action_copy),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                // SSH Settings
                Text(stringResource(R.string.agent_default_ssh_settings), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
                    Text(stringResource(R.string.ssh_host_label), fontSize = 12.sp)
                    Text(stringResource(R.string.ssh_port_label), fontSize = 12.sp)
                    Text(stringResource(R.string.ssh_user_label), fontSize = 12.sp)
                    Text(stringResource(R.string.ssh_password_label), fontSize = 12.sp)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.agent_got_it))
                    }
                }
            }
        }
    }
}

/**
 * SSH and Ollama connection settings dialog
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionSettingsDialog(
    host: String,
    port: String,
    user: String,
    password: String,
    ollamaUrl: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onOllamaUrlChange: (String) -> Unit,
    ollamaService: OllamaService,
    settingsRepository: SettingsRepository,
    /** Legacy name retained for callers that have not migrated to the save contract. */
    onConnect: () -> Unit = {},
    onDismiss: () -> Unit,
    /** Persist the edited connection and retest that exact connection. */
    onSave: (() -> Unit)? = null
) {
    var editedOllamaUrl by remember(ollamaUrl) { mutableStateOf(ollamaUrl) }
    val context = LocalContext.current
    val settingsRepo = settingsRepository
    val liteRtModels by remember(context) {
        AppDatabase.getDatabase(context.applicationContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val agentLiteRtModelId by settingsRepo.agentLiteRtModelId.collectAsState()
    val agentLiteRtBackend by settingsRepo.agentLiteRtBackend.collectAsState()
    val agentLiteRtContextTokens by settingsRepo.agentLiteRtContextTokens.collectAsState()
    val agentLiteRtMaxOutputTokens by settingsRepo.agentLiteRtMaxOutputTokens.collectAsState()
    val agentLiteRtMtpEnabled by settingsRepo.agentLiteRtMtpEnabled.collectAsState()
    val agentLiteRtThinkingEnabled by settingsRepo.agentLiteRtThinkingEnabled.collectAsState()
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.connection_settings_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(stringResource(R.string.ssh_connection_title), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text(stringResource(R.string.ssh_host_label_short)) },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = { Text(stringResource(R.string.ssh_port_label_short)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = onUserChange,
                        label = { Text(stringResource(R.string.ssh_user_label_short)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.ssh_password_label_short)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                // Auto Mode Toggle
                val autoMode by settingsRepo.autoMode.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_auto_mode_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.agent_auto_mode_desc), fontSize = 10.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = autoMode,
                        onCheckedChange = { settingsRepo.setAutoMode(it) }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val llamaServerUrl by settingsRepo.llamaServerUrl.collectAsState()
                val llamaSwapUrl by settingsRepo.agentLlamaSwapUrl.collectAsState()
                Text(stringResource(R.string.agent_runtime_profile_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.agent_runtime_global_connections_note), fontSize = 10.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))

                // These are shared connection/tuning settings. Engine and model
                // ownership lives in each agent's runtime profile below.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.ollama_server_title), fontWeight = FontWeight.Medium, fontSize = 14.sp)

                    OutlinedTextField(
                        value = editedOllamaUrl,
                        onValueChange = { editedOllamaUrl = it },
                        label = { Text(stringResource(R.string.ollama_url_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("http://localhost:11434", fontSize = 12.sp) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val useMmap by settingsRepo.ollamaMmap.collectAsState()
                    val numThreads by settingsRepo.ollamaThreads.collectAsState()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ollama_mmap_label), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(stringResource(R.string.ollama_mmap_desc), fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = useMmap,
                            onCheckedChange = {
                                settingsRepo.setOllamaMmap(it)
                                ollamaService.setUseMmap(it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(R.string.ollama_threads_label, numThreads), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Slider(
                        value = numThreads.toFloat(),
                        onValueChange = {
                            val newVal = it.toInt()
                            settingsRepo.setOllamaThreads(newVal)
                            ollamaService.setNumThreads(newVal)
                        },
                        valueRange = 1f..16f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    var editedLlamaServerUrl by remember(llamaServerUrl) {
                        mutableStateOf(llamaServerUrl)
                    }
                    OutlinedTextField(
                        value = editedLlamaServerUrl,
                        onValueChange = { editedLlamaServerUrl = it },
                        label = { Text(stringResource(R.string.agent_llama_server_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    LaunchedEffect(editedLlamaServerUrl, llamaServerUrl) {
                        if (editedLlamaServerUrl != llamaServerUrl) {
                            settingsRepo.setLlamaServerUrl(editedLlamaServerUrl)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    var editedLlamaSwapUrl by remember(llamaSwapUrl) {
                        mutableStateOf(llamaSwapUrl)
                    }
                    OutlinedTextField(
                        value = editedLlamaSwapUrl,
                        onValueChange = { editedLlamaSwapUrl = it },
                        label = { Text(stringResource(R.string.agent_llama_swap_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    LaunchedEffect(editedLlamaSwapUrl, llamaSwapUrl) {
                        if (editedLlamaSwapUrl != llamaSwapUrl) {
                            settingsRepo.setAgentLlamaSwapUrl(editedLlamaSwapUrl)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.agent_llama_server_note),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.pdf_backend_litert), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    val selectedLiteRtModel = liteRtModels.firstOrNull { it.id == agentLiteRtModelId }
                        ?: liteRtModels.firstOrNull()
                    val liteRtContextCap = resolveAgentLiteRtContextTokens(
                        savedContextTokens = Int.MAX_VALUE,
                        model = selectedLiteRtModel
                    )
                    val liteRtDefaultContext = resolveAgentLiteRtContextTokens(
                        savedContextTokens = -1,
                        model = selectedLiteRtModel
                    )
                    val liteRtResolvedContext = resolveAgentLiteRtContextTokens(
                        savedContextTokens = agentLiteRtContextTokens,
                        model = selectedLiteRtModel
                    )
                    val liteRtResolvedMaxOutput = resolveAgentLiteRtMaxOutputTokens(
                        savedMaxOutputTokens = agentLiteRtMaxOutputTokens,
                        resolvedContextTokens = liteRtResolvedContext,
                        model = selectedLiteRtModel
                    )
                    Text(
                        text = stringResource(R.string.agent_runtime_litert_global_tuning_note),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.litert_gallery_accelerator), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            LITERT_BACKEND_AUTO to R.string.general_acceleration_mode_auto,
                            LITERT_BACKEND_CPU to R.string.general_acceleration_mode_cpu,
                            LITERT_BACKEND_GPU to R.string.litert_backend_gpu
                        ).forEach { (mode, labelRes) ->
                            FilterChip(
                                selected = normalizeLiteRtBackend(agentLiteRtBackend) == mode,
                                onClick = { settingsRepo.setAgentLiteRtBackend(mode) },
                                modifier = Modifier.defaultMinSize(minWidth = 104.dp),
                                label = { Text(stringResource(labelRes), maxLines = 1) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    DraftIntTextField(
                        value = liteRtResolvedContext,
                        onValueChange = { value -> settingsRepo.setAgentLiteRtContextTokens(value.takeIf { it > 0 }) },
                        label = { Text(stringResource(R.string.agent_litert_context_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        blankValue = 0
                    )
                    Text(
                        text = stringResource(R.string.agent_litert_context_hint, liteRtDefaultContext, liteRtContextCap),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DraftIntTextField(
                        value = liteRtResolvedMaxOutput,
                        onValueChange = { value -> settingsRepo.setAgentLiteRtMaxOutputTokens(value.takeIf { it > 0 }) },
                        label = { Text(stringResource(R.string.agent_litert_max_output_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        blankValue = 0
                    )
                    Text(
                        text = stringResource(R.string.agent_litert_max_output_hint, liteRtResolvedMaxOutput),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.agent_thinking_enabled), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(stringResource(R.string.agent_thinking_enabled_desc), fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = agentLiteRtThinkingEnabled,
                            onCheckedChange = settingsRepo::setAgentLiteRtThinkingEnabled
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.litert_gallery_mtp_title), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(stringResource(R.string.litert_gallery_mtp_desc), fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = agentLiteRtMtpEnabled,
                            onCheckedChange = settingsRepo::setAgentLiteRtMtpEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Command Auto-Accept Toggle
                val commandAutoAccept by settingsRepo.commandAutoAccept.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_command_auto_accept_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.agent_command_auto_accept_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                    Switch(
                        checked = commandAutoAccept,
                        onCheckedChange = { settingsRepo.setCommandAutoAccept(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.error,
                            checkedTrackColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            onOllamaUrlChange(editedOllamaUrl)
                            onSave?.invoke() ?: onConnect()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentSettingsDialog(
    settingsRepository: SettingsRepository,
    availableModels: List<String>,
    llamaSwapModels: List<String> = emptyList(),
    knowledgeBases: List<KnowledgeBaseEntity>,
    selectedKnowledgeBaseIds: List<Long>,
    availableImageGenerationModels: List<String>,
    availableSdImageMainModels: List<ModelEntity>,
    availableSdImageSupportModels: List<ModelEntity>,
    availableBackgroundRemovalModels: List<String>,
    onKnowledgeBaseSelectionChange: (List<Long>) -> Unit,
    onManageKnowledgeBases: () -> Unit,
    section: AgentSettingsSection = AgentSettingsSection.AGENTS,
    onDismiss: () -> Unit,
    runtimeProfileStore: AgentRuntimeProfileStore = EmptyAgentRuntimeProfileStore,
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    runtimeLiteRtModels: List<AgentLiteRtProfileOption>? = null,
    onRuntimeContinue: ((AgentRuntimeContinueAction) -> Unit)? = null,
    globalOverride: AgentGlobalOverrideState? = null,
    onGlobalOverrideChange: (AgentGlobalOverrideState) -> Unit = {}
) {
    val showAgentConfiguration = section == AgentSettingsSection.AGENTS
    val showToolConfiguration = section == AgentSettingsSection.TOOLS

    val context = LocalContext.current
    val runtimeProfiles by runtimeProfileStore.observeProfiles().collectAsState(initial = emptyList())
    val runtimeEndpointConfigs by runtimeProfileStore.observeEndpointConfigs().collectAsState(initial = emptyList())
    val persistedGlobalOverride by settingsRepository.agentGlobalRuntimeOverride.collectAsState()
    val effectiveGlobalOverride = globalOverride ?: persistedGlobalOverride
    val saveGlobalOverride: (AgentGlobalOverrideState) -> Unit = if (globalOverride == null) {
        settingsRepository::setAgentGlobalRuntimeOverride
    } else {
        onGlobalOverrideChange
    }
    val runtimeProfilesByKey = remember(runtimeProfiles) {
        runtimeProfiles.associateBy { it.agentKey }
    }
    val runtimeProfileScope = rememberCoroutineScope()
    val agentBackend by settingsRepository.agentBackend.collectAsState()
    val isAgentLiteRt = SettingsRepository.isLiteRtBackend(agentBackend)
    val liteRtModels by remember(context) {
        AppDatabase.getDatabase(context.applicationContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val runtimeLiteRtOptions = runtimeLiteRtModels ?: liteRtModels.map {
        AgentLiteRtProfileOption(id = it.id, displayName = it.displayName, filename = it.filename)
    }
    val llamaServerModelLabel by settingsRepository.agentLlamaServerModelLabel.collectAsState()
    val llamaServerContextLabel by settingsRepository.agentLlamaServerContextLabel.collectAsState()
    val globalOllamaUrl by settingsRepository.ollamaUrl.collectAsState()
    val globalLlamaServerUrl by settingsRepository.llamaServerUrl.collectAsState()
    val globalLlamaSwapUrl by settingsRepository.agentLlamaSwapUrl.collectAsState()
    val agentLiteRtModelId by settingsRepository.agentLiteRtModelId.collectAsState()
    val agentLiteRtBackend by settingsRepository.agentLiteRtBackend.collectAsState()
    val agentLiteRtContextTokens by settingsRepository.agentLiteRtContextTokens.collectAsState()
    val agentLiteRtMaxOutputTokens by settingsRepository.agentLiteRtMaxOutputTokens.collectAsState()
    val agentLiteRtMtpEnabled by settingsRepository.agentLiteRtMtpEnabled.collectAsState()
    val agentLiteRtThinkingEnabled by settingsRepository.agentLiteRtThinkingEnabled.collectAsState()
    val orchestratorModel by settingsRepository.agentOrchestratorModel.collectAsState()
    val coderModel by settingsRepository.agentCoderModel.collectAsState()
    val reviewerModel by settingsRepository.agentReviewerModel.collectAsState()
    val executorModel by settingsRepository.agentExecutorModel.collectAsState()
    val codebaseScoutModel by settingsRepository.agentCodebaseScoutModel.collectAsState()
    val researcherModel by settingsRepository.agentResearcherModel.collectAsState()
    val plannerModel by settingsRepository.agentPlannerModel.collectAsState()
    val codebaseScoutCtx by settingsRepository.agentCodebaseScoutCtx.collectAsState()
    val researcherCtx by settingsRepository.agentResearcherCtx.collectAsState()
    val plannerCtx by settingsRepository.agentPlannerCtx.collectAsState()
    val codebaseScoutMaxOutputTokens by
        settingsRepository.agentCodebaseScoutMaxOutputTokens.collectAsState()
    val researcherMaxOutputTokens by
        settingsRepository.agentResearcherMaxOutputTokens.collectAsState()
    val plannerMaxOutputTokens by
        settingsRepository.agentPlannerMaxOutputTokens.collectAsState()
    val codebaseScoutThinking by
        settingsRepository.agentCodebaseScoutThinkingEnabled.collectAsState()
    val researcherThinking by
        settingsRepository.agentResearcherThinkingEnabled.collectAsState()
    val plannerThinking by
        settingsRepository.agentPlannerThinkingEnabled.collectAsState()
    
    val orchestratorPrompt by settingsRepository.agentOrchestratorPrompt.collectAsState()
    val coderPrompt by settingsRepository.agentCoderPrompt.collectAsState()
    val reviewerPrompt by settingsRepository.agentReviewerPrompt.collectAsState()
    val executorPrompt by settingsRepository.agentExecutorPrompt.collectAsState()
    
    val orchestratorCtx by settingsRepository.agentOrchestratorCtx.collectAsState()
    val coderCtx by settingsRepository.agentCoderCtx.collectAsState()
    val reviewerCtx by settingsRepository.agentReviewerCtx.collectAsState()
    val executorCtx by settingsRepository.agentExecutorCtx.collectAsState()
    val orchestratorMaxOutputTokens by settingsRepository.agentOrchestratorMaxOutputTokens.collectAsState()
    val coderMaxOutputTokens by settingsRepository.agentCoderMaxOutputTokens.collectAsState()
    val reviewerMaxOutputTokens by settingsRepository.agentReviewerMaxOutputTokens.collectAsState()
    val executorMaxOutputTokens by settingsRepository.agentExecutorMaxOutputTokens.collectAsState()
    val summarizerMaxOutputTokens by settingsRepository.agentSummarizerMaxOutputTokens.collectAsState()
    val orchestratorVisionEnabled by settingsRepository.agentOrchestratorVisionEnabled.collectAsState()
    val coderVisionEnabled by settingsRepository.agentCoderVisionEnabled.collectAsState()
    val reviewerVisionEnabled by settingsRepository.agentReviewerVisionEnabled.collectAsState()
    val executorVisionEnabled by settingsRepository.agentExecutorVisionEnabled.collectAsState()
    val summarizerVisionEnabled by settingsRepository.agentSummarizerVisionEnabled.collectAsState()
    val visualTesterModel by settingsRepository.agentVisualTesterModel.collectAsState()
    val visualTestingEnabled by settingsRepository.agentVisualTestingEnabled.collectAsState()
    val visualTesterVisionEnabled by settingsRepository.agentVisualTesterVisionEnabled.collectAsState()
    val visualTesterCtx by settingsRepository.agentVisualTesterCtx.collectAsState()
    val visualTesterMaxOutputTokens by
        settingsRepository.agentVisualTesterMaxOutputTokens.collectAsState()
    val visualTesterThinking by
        settingsRepository.agentVisualTesterThinkingEnabled.collectAsState()
    val requirePlanReadOnlyDelegationApproval by
        settingsRepository.agentPlanReadOnlyDelegationApprovalRequired.collectAsState()
    val webSearchEnabledForReadiness by
        settingsRepository.agentWebSearchEnabled.collectAsState()
    val kiwixEnabledForReadiness by
        settingsRepository.agentKiwixEnabled.collectAsState()
    val imageGenerationToolEnabled by settingsRepository.agentImageGenerationToolEnabled.collectAsState()
    val imageGenerationEngine by settingsRepository.agentImageGenerationEngine.collectAsState()
    val imageGenerationModel by settingsRepository.agentImageGenerationModel.collectAsState()
    val imageGenerationSteps by settingsRepository.agentImageGenerationSteps.collectAsState()
    val imageGenerationCfg by settingsRepository.agentImageGenerationCfg.collectAsState()
    val imageGenerationResolution by settingsRepository.agentImageGenerationResolution.collectAsState()
    val sdImageGenerationModel by settingsRepository.agentSdImageGenerationModel.collectAsState()
    val sdImageGenerationVae by settingsRepository.agentSdImageGenerationVae.collectAsState()
    val sdImageGenerationTae by settingsRepository.agentSdImageGenerationTae.collectAsState()
    val sdImageGenerationClipL by settingsRepository.agentSdImageGenerationClipL.collectAsState()
    val sdImageGenerationClipG by settingsRepository.agentSdImageGenerationClipG.collectAsState()
    val sdImageGenerationT5xxl by settingsRepository.agentSdImageGenerationT5xxl.collectAsState()
    val sdImageGenerationLlm by settingsRepository.agentSdImageGenerationLlm.collectAsState()
    val sdImageGenerationLlmVision by settingsRepository.agentSdImageGenerationLlmVision.collectAsState()
    val sdImageGenerationPhotoMaker by settingsRepository.agentSdImageGenerationPhotoMaker.collectAsState()
    val sdImageGenerationWidth by settingsRepository.agentSdImageGenerationWidth.collectAsState()
    val sdImageGenerationHeight by settingsRepository.agentSdImageGenerationHeight.collectAsState()
    val sdImageGenerationSteps by settingsRepository.agentSdImageGenerationSteps.collectAsState()
    val sdImageGenerationCfg by settingsRepository.agentSdImageGenerationCfg.collectAsState()
    val sdImageGenerationSampler by settingsRepository.agentSdImageGenerationSampler.collectAsState()
    val sdImageGenerationSeed by settingsRepository.agentSdImageGenerationSeed.collectAsState()
    val sdImageGenerationNegativePrompt by settingsRepository.agentSdImageGenerationNegativePrompt.collectAsState()
    val sdImageGenerationThreads by settingsRepository.agentSdImageGenerationThreads.collectAsState()
    val sdImageGenerationFlowShift by settingsRepository.agentSdImageGenerationFlowShift.collectAsState()
    val sdImageGenerationDiffusionFa by settingsRepository.agentSdImageGenerationDiffusionFa.collectAsState()
    val sdImageGenerationMmap by settingsRepository.agentSdImageGenerationMmap.collectAsState()
    val sdImageGenerationVaeConvDirect by settingsRepository.agentSdImageGenerationVaeConvDirect.collectAsState()
    val sdImageGenerationQwenZeroCondT by settingsRepository.agentSdImageGenerationQwenZeroCondT.collectAsState()
    val sdImageGenerationChromaDisableDitMask by settingsRepository.agentSdImageGenerationChromaDisableDitMask.collectAsState()
    val backgroundRemovalToolEnabled by settingsRepository.agentBackgroundRemovalToolEnabled.collectAsState()
    val backgroundRemovalModel by settingsRepository.agentBackgroundRemovalModel.collectAsState()
    val backgroundRemovalBackend by settingsRepository.agentBackgroundRemovalBackend.collectAsState()
    val backgroundRemovalRuntimeThreads by settingsRepository.agentBackgroundRemovalRuntimeThreads.collectAsState()
    val backgroundRemovalGraphOptimization by settingsRepository.agentBackgroundRemovalGraphOptimization.collectAsState()
    val backgroundRemovalResizeBeforeProcessing by settingsRepository.agentBackgroundRemovalResizeBeforeProcessing.collectAsState()
    val backgroundRemovalResizeMaxEdge by settingsRepository.agentBackgroundRemovalResizeMaxEdge.collectAsState()
    val backgroundRemovalAlphaThreshold by settingsRepository.agentBackgroundRemovalAlphaThreshold.collectAsState()
    val backgroundRemovalFeatherRadius by settingsRepository.agentBackgroundRemovalFeatherRadius.collectAsState()
    val backgroundRemovalMaskSoftness by settingsRepository.agentBackgroundRemovalMaskSoftness.collectAsState()
    val backgroundRemovalMaskContrast by settingsRepository.agentBackgroundRemovalMaskContrast.collectAsState()
    val backgroundRemovalExportMask by settingsRepository.agentBackgroundRemovalExportMask.collectAsState()
    val selectedAgentLiteRtModel = liteRtModels.firstOrNull { it.id == agentLiteRtModelId }
        ?: liteRtModels.firstOrNull()

    fun saveRuntimeProfile(profile: AgentRuntimeProfile) {
        runtimeProfileScope.launch {
            runtimeProfileStore.save(profile.normalized())
        }
    }

    fun saveRuntimeEndpointConfig(config: AgentRuntimeEndpointConfig) {
        runtimeProfileScope.launch {
            runtimeProfileStore.saveEndpointConfig(config)
        }
    }

    fun deleteRuntimeEndpointConfig(id: Long) {
        runtimeProfileScope.launch {
            runtimeProfileStore.deleteEndpointConfig(id)
        }
    }

    fun storedRuntimeProfile(agentKey: String): AgentRuntimeProfile? =
        runtimeProfilesByKey[agentKey]

    LaunchedEffect(isAgentLiteRt, selectedAgentLiteRtModel?.id, agentLiteRtModelId) {
        if (isAgentLiteRt && selectedAgentLiteRtModel != null && agentLiteRtModelId != selectedAgentLiteRtModel.id) {
            settingsRepository.setAgentLiteRtModelId(selectedAgentLiteRtModel.id)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (showToolConfiguration) {
                        R.string.agent_tool_settings_title
                    } else {
                        R.string.agent_settings_title
                    }
                )
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        if (showToolConfiguration) {
                            R.string.agent_tool_settings_desc
                        } else {
                            R.string.agent_settings_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Load disabled agents state
                val disabledAgents by AgentService.disabledBuiltInAgents.collectAsState()
                val disabledStandardTools by AgentService.disabledStandardAgentTools.collectAsState()
                val autoReflectionEnabled by AgentService.autoReflectionEnabled.collectAsState()
                
                LaunchedEffect(Unit) {
                    AgentService.loadDisabledAgents()
                }

                if (showAgentConfiguration) {
                    AgentSettingsGroupHeader(
                        title = stringResource(R.string.agent_settings_section_general)
                    )
                    AgentGlobalOverrideCard(
                        state = effectiveGlobalOverride,
                        availableModels = availableModels,
                        llamaSwapModels = llamaSwapModels,
                        endpointConfigs = runtimeEndpointConfigs,
                        managedLlamaServers = managedLlamaServers,
                        liteRtModels = runtimeLiteRtOptions,
                        globalConnectionDescription = when (effectiveGlobalOverride.normalizedBackend) {
                            AgentRuntimeBackend.OLLAMA -> globalOllamaUrl
                            AgentRuntimeBackend.LLAMA_SERVER -> globalLlamaServerUrl
                            AgentRuntimeBackend.LLAMA_SWAP -> globalLlamaSwapUrl
                            AgentRuntimeBackend.LITERT -> null
                        },
                        onChange = saveGlobalOverride
                    )
                }

                if (showAgentConfiguration) {
                    AgentSettingsGroupHeader(
                        title = stringResource(R.string.agent_settings_section_workflow)
                    )
                    AgentWorkflowReadinessCard(
                        disabledAgents = disabledAgents,
                        researchBackendAvailable =
                            webSearchEnabledForReadiness ||
                            kiwixEnabledForReadiness
                    )
                    AgentKnowledgeBaseSelector(
                        knowledgeBases = knowledgeBases,
                        selectedIds = selectedKnowledgeBaseIds,
                        onSelectionChange = onKnowledgeBaseSelectionChange,
                        onManage = onManageKnowledgeBases
                    )
                }

                if (showToolConfiguration) {
                    AgentToolSettingsCard(
                        disabledStandardTools = disabledStandardTools,
                        autoReflectionEnabled = autoReflectionEnabled,
                        requirePlanReadOnlyDelegationApproval =
                            requirePlanReadOnlyDelegationApproval,
                        onAutoReflectionChanged =
                            AgentService::setAutoReflectionEnabled,
                        onPlanDelegationApprovalChanged =
                            settingsRepository::setAgentPlanReadOnlyDelegationApprovalRequired,
                        onToolEnabledChanged =
                            AgentService::setStandardAgentToolEnabled
                    )
                }

                if (showAgentConfiguration && (isAgentLiteRt || runtimeProfiles.any {
                        it.normalizedBackend == AgentRuntimeBackend.LITERT
                    })) {
                    AgentSettingsGroupHeader(
                        title = stringResource(R.string.agent_settings_section_runtime)
                    )
                    AgentLiteRtBackendCard(
                        liteRtModels = liteRtModels,
                        selectedModel = selectedAgentLiteRtModel,
                        selectedModelId = agentLiteRtModelId,
                        onModelSelected = settingsRepository::setAgentLiteRtModelId,
                        showModelPicker = false,
                        selectedBackend = agentLiteRtBackend,
                        onBackendSelected = settingsRepository::setAgentLiteRtBackend,
                        savedContextTokens = agentLiteRtContextTokens,
                        onContextTokensChange = settingsRepository::setAgentLiteRtContextTokens,
                        savedMaxOutputTokens = agentLiteRtMaxOutputTokens,
                        onMaxOutputTokensChange = settingsRepository::setAgentLiteRtMaxOutputTokens,
                        mtpEnabled = agentLiteRtMtpEnabled,
                        onMtpEnabledChange = settingsRepository::setAgentLiteRtMtpEnabled,
                        thinkingEnabled = agentLiteRtThinkingEnabled,
                        onThinkingEnabledChange = settingsRepository::setAgentLiteRtThinkingEnabled
                    )
                }
                
                if (showAgentConfiguration) {
                AgentSettingsGroupHeader(
                    title = stringResource(R.string.agent_settings_section_roles)
                )
                AgentSettingsGroupHeader(
                    title = stringResource(R.string.agent_settings_subsection_coordination)
                )
// Orchestrator (always enabled, cannot be disabled)
                val orchestratorThinking by settingsRepository.agentOrchestratorThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "🎯",
                    roleName = stringResource(R.string.agent_orchestrator_name),
                    description = stringResource(R.string.agent_orchestrator_desc),
                    selectedModel = orchestratorModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = { settingsRepository.setAgentOrchestratorModel(it) },
                    prompt = orchestratorPrompt,
                    onPromptChange = { settingsRepository.setAgentOrchestratorPrompt(it) },
                    onResetPrompt = { settingsRepository.resetAgentPromptToDefault("ORCHESTRATOR") },
                    contextSize = orchestratorCtx,
                    onContextSizeChange = { settingsRepository.setAgentOrchestratorCtx(it) },
                    maxOutputTokens = orchestratorMaxOutputTokens,
                    onMaxOutputTokensChange = settingsRepository::setAgentOrchestratorMaxOutputTokens,
                    thinkingEnabled = orchestratorThinking,
                    onThinkingChange = { settingsRepository.setAgentOrchestratorThinkingEnabled(it) },
                    visionEnabled = orchestratorVisionEnabled,
                    onVisionChange = { settingsRepository.setAgentOrchestratorVisionEnabled(it) },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.ORCHESTRATOR),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                AgentTuningCard(
                    emoji = "🗺️",
                    roleName = stringResource(R.string.agent_codebase_scout_name),
                    description = stringResource(R.string.agent_codebase_scout_desc),
                    selectedModel = codebaseScoutModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = settingsRepository::setAgentCodebaseScoutModel,
                    contextSize = codebaseScoutCtx,
                    onContextSizeChange = settingsRepository::setAgentCodebaseScoutCtx,
                    maxOutputTokens = codebaseScoutMaxOutputTokens,
                    onMaxOutputTokensChange =
                        settingsRepository::setAgentCodebaseScoutMaxOutputTokens,
                    thinkingEnabled = codebaseScoutThinking,
                    onThinkingChange =
                        settingsRepository::setAgentCodebaseScoutThinkingEnabled,
                    isEnabled = "CODEBASE_SCOUT" !in disabledAgents,
                    onEnabledChange = { enabled ->
                        AgentService.setBuiltInAgentEnabled(
                            "CODEBASE_SCOUT",
                            enabled
                        )
                    },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.CODEBASE_SCOUT),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                AgentTuningCard(
                    emoji = "🧭",
                    roleName = stringResource(R.string.agent_planner_name),
                    description = stringResource(R.string.agent_planner_desc),
                    selectedModel = plannerModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = settingsRepository::setAgentPlannerModel,
                    contextSize = plannerCtx,
                    onContextSizeChange = settingsRepository::setAgentPlannerCtx,
                    maxOutputTokens = plannerMaxOutputTokens,
                    onMaxOutputTokensChange =
                        settingsRepository::setAgentPlannerMaxOutputTokens,
                    thinkingEnabled = plannerThinking,
                    onThinkingChange =
                        settingsRepository::setAgentPlannerThinkingEnabled,
                    isEnabled = "PLANNER" !in disabledAgents,
                    onEnabledChange = { enabled ->
                        AgentService.setBuiltInAgentEnabled(
                            "PLANNER",
                            enabled
                        )
                    },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.PLANNER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                AgentTuningCard(
                    emoji = "🌐",
                    roleName = stringResource(R.string.agent_researcher_name),
                    description = stringResource(R.string.agent_researcher_desc),
                    selectedModel = researcherModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = settingsRepository::setAgentResearcherModel,
                    contextSize = researcherCtx,
                    onContextSizeChange = settingsRepository::setAgentResearcherCtx,
                    maxOutputTokens = researcherMaxOutputTokens,
                    onMaxOutputTokensChange =
                        settingsRepository::setAgentResearcherMaxOutputTokens,
                    thinkingEnabled = researcherThinking,
                    onThinkingChange =
                        settingsRepository::setAgentResearcherThinkingEnabled,
                    isEnabled = "RESEARCHER" !in disabledAgents,
                    onEnabledChange = { enabled ->
                        AgentService.setBuiltInAgentEnabled(
                            "RESEARCHER",
                            enabled
                        )
                    },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.RESEARCHER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )
                
                AgentSettingsGroupHeader(
                    title = stringResource(R.string.agent_settings_subsection_implementation)
                )
                val coderThinking by settingsRepository.agentCoderThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "👷",
                    roleName = stringResource(R.string.agent_coder_name),
                    description = stringResource(R.string.agent_coder_desc),
                    selectedModel = coderModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = { settingsRepository.setAgentCoderModel(it) },
                    prompt = coderPrompt,
                    onPromptChange = { settingsRepository.setAgentCoderPrompt(it) },
                    onResetPrompt = { settingsRepository.resetAgentPromptToDefault("CODER") },
                    contextSize = coderCtx,
                    onContextSizeChange = { settingsRepository.setAgentCoderCtx(it) },
                    maxOutputTokens = coderMaxOutputTokens,
                    onMaxOutputTokensChange = settingsRepository::setAgentCoderMaxOutputTokens,
                    thinkingEnabled = coderThinking,
                    onThinkingChange = { settingsRepository.setAgentCoderThinkingEnabled(it) },
                    visionEnabled = coderVisionEnabled,
                    onVisionChange = { settingsRepository.setAgentCoderVisionEnabled(it) },
                    isEnabled = "CODER" !in disabledAgents,
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("CODER", it) },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.CODER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )
                
                // Executor
                val executorThinking by settingsRepository.agentExecutorThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "⚡",
                    roleName = stringResource(R.string.agent_executor_name),
                    description = stringResource(R.string.agent_executor_desc),
                    selectedModel = executorModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = { settingsRepository.setAgentExecutorModel(it) },
                    prompt = executorPrompt,
                    onPromptChange = { settingsRepository.setAgentExecutorPrompt(it) },
                    onResetPrompt = { settingsRepository.resetAgentPromptToDefault("EXECUTOR") },
                    contextSize = executorCtx,
                    onContextSizeChange = { settingsRepository.setAgentExecutorCtx(it) },
                    maxOutputTokens = executorMaxOutputTokens,
                    onMaxOutputTokensChange = settingsRepository::setAgentExecutorMaxOutputTokens,
                    thinkingEnabled = executorThinking,
                    onThinkingChange = { settingsRepository.setAgentExecutorThinkingEnabled(it) },
                    visionEnabled = executorVisionEnabled,
                    onVisionChange = { settingsRepository.setAgentExecutorVisionEnabled(it) },
                    isEnabled = "EXECUTOR" !in disabledAgents,
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("EXECUTOR", it) },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.EXECUTOR),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                AgentSettingsGroupHeader(
                    title = stringResource(R.string.agent_settings_subsection_validation)
                )
                val reviewerThinking by settingsRepository.agentReviewerThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "🔍",
                    roleName = stringResource(R.string.agent_reviewer_name),
                    description = stringResource(R.string.agent_reviewer_desc),
                    selectedModel = reviewerModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = { settingsRepository.setAgentReviewerModel(it) },
                    prompt = reviewerPrompt,
                    onPromptChange = { settingsRepository.setAgentReviewerPrompt(it) },
                    onResetPrompt = { settingsRepository.resetAgentPromptToDefault("REVIEWER") },
                    contextSize = reviewerCtx,
                    onContextSizeChange = { settingsRepository.setAgentReviewerCtx(it) },
                    maxOutputTokens = reviewerMaxOutputTokens,
                    onMaxOutputTokensChange = settingsRepository::setAgentReviewerMaxOutputTokens,
                    thinkingEnabled = reviewerThinking,
                    onThinkingChange = { settingsRepository.setAgentReviewerThinkingEnabled(it) },
                    visionEnabled = reviewerVisionEnabled,
                    onVisionChange = { settingsRepository.setAgentReviewerVisionEnabled(it) },
                    isEnabled = "REVIEWER" !in disabledAgents,
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("REVIEWER", it) },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.REVIEWER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                AgentTuningCard(
                    emoji = "👁️",
                    roleName = stringResource(R.string.agent_visual_tester_name),
                    description = stringResource(R.string.agent_visual_tester_desc),
                    selectedModel = visualTesterModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = settingsRepository::setAgentVisualTesterModel,
                    contextSize = visualTesterCtx,
                    onContextSizeChange = settingsRepository::setAgentVisualTesterCtx,
                    maxOutputTokens = visualTesterMaxOutputTokens,
                    onMaxOutputTokensChange =
                        settingsRepository::setAgentVisualTesterMaxOutputTokens,
                    thinkingEnabled = visualTesterThinking,
                    onThinkingChange =
                        settingsRepository::setAgentVisualTesterThinkingEnabled,
                    visionEnabled = visualTesterVisionEnabled,
                    onVisionChange =
                        settingsRepository::setAgentVisualTesterVisionEnabled,
                    isEnabled =
                        visualTestingEnabled &&
                            "VISUAL_TESTER" !in disabledAgents,
                    onEnabledChange = { enabled ->
                        settingsRepository.setAgentVisualTestingEnabled(enabled)
                        AgentService.setBuiltInAgentEnabled(
                            "VISUAL_TESTER",
                            enabled
                        )
                    },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.VISUAL_TESTER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )

                // Summarizer
                val summarizerModel by settingsRepository.agentSummarizerModel.collectAsState()
                val summarizerPrompt by settingsRepository.agentSummarizerPrompt.collectAsState()
                val summarizerCtx by settingsRepository.agentSummarizerCtx.collectAsState()
                val summarizerThinking by settingsRepository.agentSummarizerThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "📝",
                    roleName = stringResource(R.string.agent_summarizer_name),
                    description = stringResource(R.string.agent_summarizer_desc),
                    selectedModel = summarizerModel,
                    availableModels = availableModels,
                    llamaSwapModels = llamaSwapModels,
                    backend = agentBackend,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    onModelChange = { settingsRepository.setAgentSummarizerModel(it) },
                    prompt = summarizerPrompt,
                    onPromptChange = { settingsRepository.setAgentSummarizerPrompt(it) },
                    onResetPrompt = { settingsRepository.resetAgentPromptToDefault("SUMMARIZER") },
                    contextSize = summarizerCtx,
                    onContextSizeChange = { settingsRepository.setAgentSummarizerCtx(it) },
                    maxOutputTokens = summarizerMaxOutputTokens,
                    onMaxOutputTokensChange = settingsRepository::setAgentSummarizerMaxOutputTokens,
                    thinkingEnabled = summarizerThinking,
                    onThinkingChange = { settingsRepository.setAgentSummarizerThinkingEnabled(it) },
                    visionEnabled = summarizerVisionEnabled,
                    onVisionChange = { settingsRepository.setAgentSummarizerVisionEnabled(it) },
                    isEnabled = "SUMMARIZER" !in disabledAgents,
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("SUMMARIZER", it) },
                    runtimeProfile = storedRuntimeProfile(AgentRuntimeProfileKeys.SUMMARIZER),
                    managedLlamaServers = managedLlamaServers,
                    runtimeLiteRtModels = runtimeLiteRtOptions,
                    endpointConfigs = runtimeEndpointConfigs,
                    onSaveEndpointConfig = ::saveRuntimeEndpointConfig,
                    onDeleteEndpointConfig = ::deleteRuntimeEndpointConfig,
                    onRuntimeProfileChange = { saveRuntimeProfile(it) },
                    onRuntimeContinue = onRuntimeContinue
                )
                }
                if (showToolConfiguration) {
Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.agent_image_generation_settings_title),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.agent_image_generation_settings_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsRepository.setAgentImageGenerationToolEnabled(!imageGenerationToolEnabled)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.agent_image_generation_tool_enabled),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.agent_image_generation_tool_enabled_desc),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = imageGenerationToolEnabled,
                                onCheckedChange = settingsRepository::setAgentImageGenerationToolEnabled,
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        AnimatedVisibility(visible = imageGenerationToolEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AgentStringDropdown(
                                    label = stringResource(R.string.image_tool_engine_label),
                                    selected = imageGenerationEngine,
                                    values = listOf("ONNX", "SD"),
                                    labelFor = { engine ->
                                        when (engine.uppercase()) {
                                            "SD" -> stringResource(R.string.image_tool_engine_sd)
                                            else -> stringResource(R.string.image_tool_engine_onnx)
                                        }
                                    },
                                    onSelected = settingsRepository::setAgentImageGenerationEngine
                                )

                                if (imageGenerationEngine.equals("SD", ignoreCase = true)) {
                                    val selectedSdModel = availableSdImageMainModels.firstOrNull {
                                        it.filename == sdImageGenerationModel || it.path == sdImageGenerationModel
                                    } ?: availableSdImageMainModels.firstOrNull()
                                    val selectedSdSpec = selectedSdModel?.resolvedSdFamily()
                                        ?.let { (family, variant) -> family?.let { resolveSdFamilySpec(it, variant) } }
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
                                    val componentRoles = ((selectedSdSpec?.requiredRoles.orEmpty() + selectedSdSpec?.optionalRoles.orEmpty()) intersect allowedRoles)
                                        .toList()

                                    AgentStringDropdown(
                                        label = stringResource(R.string.agent_sd_image_generation_model_label),
                                        selected = sdImageGenerationModel.orEmpty(),
                                        values = availableSdImageMainModels.map { it.filename }.distinct(),
                                        onSelected = settingsRepository::setAgentSdImageGenerationModel
                                    )

                                    componentRoles.forEach { role ->
                                        AgentSdComponentDropdown(
                                            label = stringResource(agentSdComponentLabelRes(role)) +
                                                if (role in selectedSdSpec?.requiredRoles.orEmpty()) " *" else "",
                                            selected = when (role) {
                                                SdComponentRole.VAE -> sdImageGenerationVae.orEmpty()
                                                SdComponentRole.TAE -> sdImageGenerationTae.orEmpty()
                                                SdComponentRole.CLIP_L -> sdImageGenerationClipL.orEmpty()
                                                SdComponentRole.CLIP_G -> sdImageGenerationClipG.orEmpty()
                                                SdComponentRole.T5XXL -> sdImageGenerationT5xxl.orEmpty()
                                                SdComponentRole.LLM -> sdImageGenerationLlm.orEmpty()
                                                SdComponentRole.LLM_VISION -> sdImageGenerationLlmVision.orEmpty()
                                                SdComponentRole.PHOTOMAKER -> sdImageGenerationPhotoMaker.orEmpty()
                                                else -> ""
                                            },
                                            values = agentSdComponentOptions(availableSdImageSupportModels, selectedSdModel, role),
                                            allowNone = role !in selectedSdSpec?.requiredRoles.orEmpty(),
                                            onSelected = { value ->
                                                when (role) {
                                                    SdComponentRole.VAE -> settingsRepository.setAgentSdImageGenerationVae(value)
                                                    SdComponentRole.TAE -> settingsRepository.setAgentSdImageGenerationTae(value)
                                                    SdComponentRole.CLIP_L -> settingsRepository.setAgentSdImageGenerationClipL(value)
                                                    SdComponentRole.CLIP_G -> settingsRepository.setAgentSdImageGenerationClipG(value)
                                                    SdComponentRole.T5XXL -> settingsRepository.setAgentSdImageGenerationT5xxl(value)
                                                    SdComponentRole.LLM -> settingsRepository.setAgentSdImageGenerationLlm(value)
                                                    SdComponentRole.LLM_VISION -> settingsRepository.setAgentSdImageGenerationLlmVision(value)
                                                    SdComponentRole.PHOTOMAKER -> settingsRepository.setAgentSdImageGenerationPhotoMaker(value)
                                                    else -> Unit
                                                }
                                            }
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DraftIntTextField(
                                            value = sdImageGenerationWidth,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationWidth,
                                            label = { Text(stringResource(R.string.onnx_image_gen_width_label)) },
                                            modifier = Modifier.weight(1f),
                                            blankValue = sdImageGenerationWidth
                                        )
                                        DraftIntTextField(
                                            value = sdImageGenerationHeight,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationHeight,
                                            label = { Text(stringResource(R.string.onnx_image_gen_height_label)) },
                                            modifier = Modifier.weight(1f),
                                            blankValue = sdImageGenerationHeight
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DraftIntTextField(
                                            value = sdImageGenerationSteps,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationSteps,
                                            label = { Text(stringResource(R.string.agent_image_generation_steps_label)) },
                                            modifier = Modifier.weight(1f),
                                            blankValue = sdImageGenerationSteps
                                        )
                                        DraftFloatTextField(
                                            value = sdImageGenerationCfg,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationCfg,
                                            label = { Text(stringResource(R.string.agent_image_generation_cfg_label)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    AgentStringDropdown(
                                        label = stringResource(R.string.imagegen_sampler_label),
                                        selected = sdImageGenerationSampler,
                                        values = SamplingMethod.entries.map { it.name },
                                        labelFor = { name ->
                                            SamplingMethod.entries.firstOrNull { it.name == name }?.cliName ?: name
                                        },
                                        onSelected = settingsRepository::setAgentSdImageGenerationSampler
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DraftIntTextField(
                                            value = sdImageGenerationThreads,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationThreads,
                                            label = { Text(stringResource(R.string.imagegen_threads_label)) },
                                            modifier = Modifier.weight(1f),
                                            blankValue = sdImageGenerationThreads
                                        )
                                        OutlinedTextField(
                                            value = sdImageGenerationSeed,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationSeed,
                                            label = { Text(stringResource(R.string.onnx_image_gen_seed_label)) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                    }

                                    OutlinedTextField(
                                        value = sdImageGenerationNegativePrompt,
                                        onValueChange = settingsRepository::setAgentSdImageGenerationNegativePrompt,
                                        label = { Text(stringResource(R.string.native_chat_image_generation_negative_prompt_label)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4
                                    )

                                    if (selectedSdSpec?.supportsFlowShift == true) {
                                        OutlinedTextField(
                                            value = sdImageGenerationFlowShift,
                                            onValueChange = settingsRepository::setAgentSdImageGenerationFlowShift,
                                            label = { Text(stringResource(R.string.imagegen_flow_shift_label)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                    if (selectedSdSpec?.supportsDiffusionFa == true) {
                                        AgentSwitchRow(
                                            title = stringResource(R.string.video_gen_diffusion_fa_label),
                                            checked = sdImageGenerationDiffusionFa,
                                            onCheckedChange = settingsRepository::setAgentSdImageGenerationDiffusionFa
                                        )
                                    }
                                    if (selectedSdSpec?.supportsMmap == true) {
                                        AgentSwitchRow(
                                            title = stringResource(R.string.imagegen_mmap_label),
                                            checked = sdImageGenerationMmap,
                                            onCheckedChange = settingsRepository::setAgentSdImageGenerationMmap
                                        )
                                    }
                                    if (selectedSdSpec?.supportsVaeConvDirect == true) {
                                        AgentSwitchRow(
                                            title = stringResource(R.string.imagegen_vae_conv_direct_label),
                                            checked = sdImageGenerationVaeConvDirect,
                                            onCheckedChange = settingsRepository::setAgentSdImageGenerationVaeConvDirect
                                        )
                                    }
                                    if (selectedSdSpec?.supportsQwenImageZeroCondT == true) {
                                        AgentSwitchRow(
                                            title = stringResource(R.string.imagegen_qwen_zero_cond_t_label),
                                            checked = sdImageGenerationQwenZeroCondT,
                                            onCheckedChange = settingsRepository::setAgentSdImageGenerationQwenZeroCondT
                                        )
                                    }
                                    if (selectedSdSpec?.supportsChromaDisableDitMask == true) {
                                        AgentSwitchRow(
                                            title = stringResource(R.string.imagegen_chroma_disable_dit_mask_label),
                                            checked = sdImageGenerationChromaDisableDitMask,
                                            onCheckedChange = settingsRepository::setAgentSdImageGenerationChromaDisableDitMask
                                        )
                                    }
                                } else {
                                    AgentStringDropdown(
                                        label = stringResource(R.string.agent_image_generation_model_label),
                                        selected = imageGenerationModel.orEmpty(),
                                        values = availableImageGenerationModels,
                                        onSelected = settingsRepository::setAgentImageGenerationModel
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DraftIntTextField(
                                            value = imageGenerationSteps,
                                            onValueChange = settingsRepository::setAgentImageGenerationSteps,
                                            label = { Text(stringResource(R.string.agent_image_generation_steps_label)) },
                                            modifier = Modifier.weight(1f),
                                            blankValue = imageGenerationSteps
                                        )
                                        DraftFloatTextField(
                                            value = imageGenerationCfg,
                                            onValueChange = settingsRepository::setAgentImageGenerationCfg,
                                            label = { Text(stringResource(R.string.agent_image_generation_cfg_label)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    AgentStringDropdown(
                                        label = stringResource(R.string.agent_image_generation_resolution_label),
                                        selected = imageGenerationResolution,
                                        values = listOf("128x128", "256x256", "384x384", "512x512", "640x640", "768x768", "896x896", "1024x1024"),
                                        onSelected = settingsRepository::setAgentImageGenerationResolution
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.agent_bgr_settings_title),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.agent_bgr_settings_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsRepository.setAgentBackgroundRemovalToolEnabled(!backgroundRemovalToolEnabled)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.agent_bgr_tool_enabled),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.agent_bgr_tool_enabled_desc),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = backgroundRemovalToolEnabled,
                                onCheckedChange = settingsRepository::setAgentBackgroundRemovalToolEnabled,
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        AnimatedVisibility(visible = backgroundRemovalToolEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                var bgrModelExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = bgrModelExpanded,
                                    onExpandedChange = { bgrModelExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = backgroundRemovalModel.orEmpty(),
                                        onValueChange = { settingsRepository.setAgentBackgroundRemovalModel(it) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(),
                                        label = { Text(stringResource(R.string.agent_image_generation_model_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bgrModelExpanded) },
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bgrModelExpanded,
                                        onDismissRequest = { bgrModelExpanded = false }
                                    ) {
                                        availableBackgroundRemovalModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    settingsRepository.setAgentBackgroundRemovalModel(model)
                                                    bgrModelExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    var bgrBackendExpanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = bgrBackendExpanded,
                                        onExpandedChange = { bgrBackendExpanded = it },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = backgroundRemovalBackend,
                                            onValueChange = {},
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(),
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.onnx_image_gen_backend_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bgrBackendExpanded) },
                                            singleLine = true
                                        )
                                        ExposedDropdownMenu(
                                            expanded = bgrBackendExpanded,
                                            onDismissRequest = { bgrBackendExpanded = false }
                                        ) {
                                            listOf("CPU", "NNAPI").forEach { backend ->
                                                DropdownMenuItem(
                                                    text = { Text(backend) },
                                                    onClick = {
                                                        settingsRepository.setAgentBackgroundRemovalBackend(backend)
                                                        bgrBackendExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    DraftIntTextField(
                                        value = backgroundRemovalRuntimeThreads,
                                        onValueChange = settingsRepository::setAgentBackgroundRemovalRuntimeThreads,
                                        label = { Text(stringResource(R.string.agent_bgr_runtime_threads_label)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 0
                                    )
                                }

                                var bgrGraphExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = bgrGraphExpanded,
                                    onExpandedChange = { bgrGraphExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = backgroundRemovalGraphOptimization,
                                        onValueChange = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(),
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.onnx_image_gen_graph_opt_title)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bgrGraphExpanded) },
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bgrGraphExpanded,
                                        onDismissRequest = { bgrGraphExpanded = false }
                                    ) {
                                        listOf("DISABLED", "BASIC", "EXTENDED", "ALL").forEach { level ->
                                            DropdownMenuItem(
                                                text = { Text(level) },
                                                onClick = {
                                                    settingsRepository.setAgentBackgroundRemovalGraphOptimization(level)
                                                    bgrGraphExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DraftFloatTextField(
                                        value = backgroundRemovalAlphaThreshold,
                                        onValueChange = settingsRepository::setAgentBackgroundRemovalAlphaThreshold,
                                        label = { Text(stringResource(R.string.agent_bgr_alpha_threshold_label)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DraftIntTextField(
                                        value = backgroundRemovalFeatherRadius,
                                        onValueChange = settingsRepository::setAgentBackgroundRemovalFeatherRadius,
                                        label = { Text(stringResource(R.string.agent_bgr_feather_label)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 1
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DraftFloatTextField(
                                        value = backgroundRemovalMaskSoftness,
                                        onValueChange = settingsRepository::setAgentBackgroundRemovalMaskSoftness,
                                        label = { Text(stringResource(R.string.agent_bgr_mask_softness_label)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DraftFloatTextField(
                                        value = backgroundRemovalMaskContrast,
                                        onValueChange = settingsRepository::setAgentBackgroundRemovalMaskContrast,
                                        label = { Text(stringResource(R.string.agent_bgr_mask_contrast_label)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_bgr_resize_label), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.agent_bgr_resize_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = backgroundRemovalResizeBeforeProcessing,
                                        onCheckedChange = settingsRepository::setAgentBackgroundRemovalResizeBeforeProcessing,
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }

                                if (backgroundRemovalResizeBeforeProcessing) {
                                    var bgrResizeExpanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = bgrResizeExpanded,
                                        onExpandedChange = { bgrResizeExpanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = backgroundRemovalResizeMaxEdge.toString(),
                                            onValueChange = {},
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(),
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.agent_bgr_resize_max_edge_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bgrResizeExpanded) },
                                            singleLine = true
                                        )
                                        ExposedDropdownMenu(
                                            expanded = bgrResizeExpanded,
                                            onDismissRequest = { bgrResizeExpanded = false }
                                        ) {
                                            listOf(512, 768, 1024, 1536, 2048).forEach { edge ->
                                                DropdownMenuItem(
                                                    text = { Text(edge.toString()) },
                                                    onClick = {
                                                        settingsRepository.setAgentBackgroundRemovalResizeMaxEdge(edge)
                                                        bgrResizeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.agent_bgr_export_mask_label),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = backgroundRemovalExportMask,
                                        onCheckedChange = settingsRepository::setAgentBackgroundRemovalExportMask,
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                }
                if (showToolConfiguration) {
// Web Search Settings
                val webSearchEnabled by settingsRepository.agentWebSearchEnabled.collectAsState()
                val webSearchModel by settingsRepository.agentWebSearchModel.collectAsState()
                val webSearchMaxResults by settingsRepository.agentWebSearchMaxResults.collectAsState()
                val webSearchMaxChars by settingsRepository.agentWebSearchMaxChars.collectAsState()
                val webSearchNumCtx by settingsRepository.agentWebSearchNumCtx.collectAsState()
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Header and Toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { settingsRepository.setAgentWebSearchEnabled(!webSearchEnabled) }
                        ) {
                            Text("🌐", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.agent_websearch_name), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.agent_websearch_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = webSearchEnabled,
                                onCheckedChange = { settingsRepository.setAgentWebSearchEnabled(it) }
                            )
                        }
                        
                        AnimatedVisibility(visible = webSearchEnabled) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Model dropdown
                                var wsExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = wsExpanded,
                                    onExpandedChange = { wsExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = webSearchModel,
                                        onValueChange = { settingsRepository.setAgentWebSearchModel(it) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                                        label = { Text(stringResource(R.string.agent_model_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wsExpanded) },
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = wsExpanded,
                                        onDismissRequest = { wsExpanded = false }
                                    ) {
                                        availableModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    settingsRepository.setAgentWebSearchModel(model)
                                                    wsExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Max results
                                    DraftIntTextField(
                                        value = webSearchMaxResults,
                                        onValueChange = settingsRepository::setAgentWebSearchMaxResults,
                                        label = { Text(stringResource(R.string.agent_websearch_max_results)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 0
                                    )
                                    
                                    // Max chars
                                    DraftIntTextField(
                                        value = webSearchMaxChars,
                                        onValueChange = settingsRepository::setAgentWebSearchMaxChars,
                                        label = { Text(stringResource(R.string.agent_websearch_max_chars)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 0
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Context size
                                DraftIntTextField(
                                    value = webSearchNumCtx,
                                    onValueChange = settingsRepository::setAgentWebSearchNumCtx,
                                    label = { Text(stringResource(R.string.agent_websearch_context)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    blankValue = 0
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Thinking toggle
                                val wsThinking by settingsRepository.agentWebSearchThinkingEnabled.collectAsState()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { settingsRepository.setAgentWebSearchThinkingEnabled(!wsThinking) }
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_thinking_enabled), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.agent_thinking_enabled_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = wsThinking,
                                        onCheckedChange = { settingsRepository.setAgentWebSearchThinkingEnabled(it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
                // Kiwix Search Settings
                val kiwixEnabled by settingsRepository.agentKiwixEnabled.collectAsState()
                val kiwixUrl by settingsRepository.agentKiwixUrl.collectAsState()
                val kiwixModel by settingsRepository.agentKiwixModel.collectAsState()
                val kiwixMaxResults by settingsRepository.agentKiwixMaxResults.collectAsState()
                val kiwixMaxChars by settingsRepository.agentKiwixMaxChars.collectAsState()
                val kiwixNumCtx by settingsRepository.agentKiwixNumCtx.collectAsState()
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Header and Toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { settingsRepository.setAgentKiwixEnabled(!kiwixEnabled) }
                        ) {
                            Text("📚", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.agent_kiwix_enabled), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.agent_kiwix_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = kiwixEnabled,
                                onCheckedChange = { settingsRepository.setAgentKiwixEnabled(it) }
                            )
                        }
                        
                        AnimatedVisibility(visible = kiwixEnabled) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Server URL
                                OutlinedTextField(
                                    value = kiwixUrl ?: "",
                                    onValueChange = { settingsRepository.setAgentKiwixUrl(it) },
                                    label = { Text(stringResource(R.string.agent_kiwix_url)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Model dropdown
                                var kiwixExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = kiwixExpanded,
                                    onExpandedChange = { kiwixExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = kiwixModel,
                                        onValueChange = { settingsRepository.setAgentKiwixModel(it) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                                        label = { Text(stringResource(R.string.agent_kiwix_model)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kiwixExpanded) },
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = kiwixExpanded,
                                        onDismissRequest = { kiwixExpanded = false }
                                    ) {
                                        availableModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    settingsRepository.setAgentKiwixModel(model)
                                                    kiwixExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Max results
                                    DraftIntTextField(
                                        value = kiwixMaxResults,
                                        onValueChange = settingsRepository::setAgentKiwixMaxResults,
                                        label = { Text(stringResource(R.string.agent_kiwix_max_results)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 0
                                    )
                                    
                                    // Max chars
                                    DraftIntTextField(
                                        value = kiwixMaxChars,
                                        onValueChange = settingsRepository::setAgentKiwixMaxChars,
                                        label = { Text(stringResource(R.string.agent_kiwix_max_chars)) },
                                        modifier = Modifier.weight(1f),
                                        blankValue = 0
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Context size
                                DraftIntTextField(
                                    value = kiwixNumCtx,
                                    onValueChange = settingsRepository::setAgentKiwixNumCtx,
                                    label = { Text(stringResource(R.string.agent_kiwix_context)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    blankValue = 0
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Thinking toggle
                                val kiwixThinking by settingsRepository.agentKiwixThinkingEnabled.collectAsState()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { settingsRepository.setAgentKiwixThinkingEnabled(!kiwixThinking) }
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_thinking_enabled), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.agent_thinking_enabled_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = kiwixThinking,
                                        onCheckedChange = { settingsRepository.setAgentKiwixThinkingEnabled(it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

@Composable
private fun AgentSettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * Shared runtime defaults for all configured roles.
 *
 * This card only edits the adapter state. The owner persists it and applies it
 * when dispatching a role; the enabled switch is intentionally part of that
 * state so a disabled card has no effect on per-role preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentGlobalOverrideCard(
    state: AgentGlobalOverrideState,
    availableModels: List<String>,
    llamaSwapModels: List<String> = emptyList(),
    endpointConfigs: List<AgentRuntimeEndpointConfig> = emptyList(),
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    liteRtModels: List<AgentLiteRtProfileOption> = emptyList(),
    globalConnectionDescription: String? = null,
    onChange: (AgentGlobalOverrideState) -> Unit
) {
    val backendOptions = AgentRuntimeBackend.entries.map { it.id }
    val backend = state.normalizedBackend
    val endpointOptions = endpointConfigs
        .filter { it.normalizedBackend == backend }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val serverOptions = managedLlamaServers
        .filter { normalizeAgentRuntimeBackend(it.backend) == backend.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    val connectionSelection = when {
        state.endpointConfigId != null -> state.endpointConfigId.toString()
        state.managedLlamaServerId != null -> MANAGED_OVERRIDE_CONNECTION_KEY
        else -> GLOBAL_OVERRIDE_CONNECTION_KEY
    }
    val connectionOptions = buildList {
        add(GLOBAL_OVERRIDE_CONNECTION_KEY)
        if (backend == AgentRuntimeBackend.LLAMA_SERVER || backend == AgentRuntimeBackend.LLAMA_SWAP) {
            add(MANAGED_OVERRIDE_CONNECTION_KEY)
        }
        addAll(endpointOptions.map { it.id.toString() })
    }
    val modelOptions = buildList {
        addAll(
            if (backend == AgentRuntimeBackend.LLAMA_SWAP && llamaSwapModels.isNotEmpty()) {
                llamaSwapModels
            } else {
                availableModels
            }
        )
        state.model?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }.distinct()
    val liteRtModelOptions = liteRtModels.map { it.id.toString() }

    fun selectConnection(selection: String) {
        when (selection) {
            GLOBAL_OVERRIDE_CONNECTION_KEY -> onChange(
                state.copy(endpointConfigId = null, managedLlamaServerId = null)
            )
            MANAGED_OVERRIDE_CONNECTION_KEY -> onChange(
                state.copy(
                    endpointConfigId = null,
                    managedLlamaServerId = serverOptions.firstOrNull()?.id ?: 0L
                )
            )
            else -> endpointConfigs.firstOrNull { it.id.toString() == selection }?.let { endpoint ->
                onChange(
                    state.copy(
                        backend = endpoint.normalizedBackend.id,
                        endpointConfigId = endpoint.id,
                        managedLlamaServerId = null
                    )
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_global_override_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.agent_global_override_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { onChange(state.copy(enabled = it)) },
                    modifier = Modifier.scale(0.82f)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (state.enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                }
            ) {
                Text(
                    text = stringResource(
                        if (state.enabled) {
                            R.string.agent_global_override_enabled_note
                        } else {
                            R.string.agent_global_override_disabled_note
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            AgentStringDropdown(
                label = stringResource(R.string.agent_global_override_backend_label),
                selected = state.backend,
                values = backendOptions,
                labelFor = { backend ->
                    when (AgentRuntimeBackend.from(backend)) {
                        AgentRuntimeBackend.OLLAMA -> stringResource(R.string.agent_runtime_engine_ollama)
                        AgentRuntimeBackend.LLAMA_SERVER -> stringResource(R.string.agent_runtime_engine_llama_server)
                        AgentRuntimeBackend.LLAMA_SWAP -> stringResource(R.string.agent_runtime_engine_llama_swap)
                        AgentRuntimeBackend.LITERT -> stringResource(R.string.agent_runtime_engine_litert)
                    }
                },
                onSelected = {
                    onChange(
                        state.copy(
                            backend = it,
                            endpointConfigId = null,
                            managedLlamaServerId = null,
                            model = state.model
                        )
                    )
                }
            )

            if (backend != AgentRuntimeBackend.LITERT) {
                AgentStringDropdown(
                    label = stringResource(R.string.agent_global_override_connection_label),
                    selected = connectionSelection,
                    values = connectionOptions,
                    labelFor = { selection ->
                        when (selection) {
                            GLOBAL_OVERRIDE_CONNECTION_KEY -> stringResource(
                                R.string.agent_runtime_connection_global
                            )
                            MANAGED_OVERRIDE_CONNECTION_KEY -> stringResource(
                                R.string.agent_runtime_connection_managed
                            )
                            else -> endpointConfigs.firstOrNull {
                                it.id.toString() == selection
                            }?.name ?: selection
                        }
                    },
                    descriptionFor = { selection ->
                        when (selection) {
                            GLOBAL_OVERRIDE_CONNECTION_KEY -> globalConnectionDescription
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.agent_runtime_connection_not_configured)
                            MANAGED_OVERRIDE_CONNECTION_KEY -> serverOptions.firstOrNull()?.let {
                                "${it.host}:${it.port}"
                            } ?: stringResource(R.string.agent_runtime_connection_choose_managed)
                            else -> endpointConfigs.firstOrNull {
                                it.id.toString() == selection
                            }?.baseUrl.orEmpty()
                        }
                    },
                    supportingText = stringResource(R.string.agent_global_override_connection_hint),
                    onSelected = ::selectConnection
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            stringResource(R.string.agent_global_override_connection_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.agent_global_override_connection_litert))
                    }
                }
            }

            if (
                backend == AgentRuntimeBackend.LLAMA_SERVER ||
                    backend == AgentRuntimeBackend.LLAMA_SWAP
            ) {
                if (state.managedLlamaServerId != null) {
                    AgentStringDropdown(
                        label = stringResource(R.string.agent_runtime_managed_server_label),
                        selected = state.managedLlamaServerId.toString(),
                        values = serverOptions.map { it.id.toString() },
                        labelFor = { id ->
                            serverOptions.firstOrNull { it.id.toString() == id }?.displayName
                                ?: id
                        },
                        descriptionFor = { id ->
                            serverOptions.firstOrNull { it.id.toString() == id }?.let {
                                "${it.host}:${it.port}"
                            }
                        },
                        onSelected = { id ->
                            onChange(state.copy(managedLlamaServerId = id.toLongOrNull()))
                        }
                    )
                }
            }

            if (backend == AgentRuntimeBackend.LITERT) {
                AgentStringDropdown(
                    label = stringResource(R.string.agent_runtime_litert_model_label),
                    selected = state.liteRtModelId?.toString().orEmpty(),
                    values = liteRtModelOptions,
                    labelFor = { id ->
                        liteRtModels.firstOrNull { it.id.toString() == id }?.displayName ?: id
                    },
                    onSelected = {
                        onChange(state.copy(liteRtModelId = it.toLongOrNull(), model = null))
                    }
                )
            } else {
                if (modelOptions.isNotEmpty()) {
                    AgentStringDropdown(
                        label = stringResource(R.string.agent_global_override_model_label),
                        selected = state.model.orEmpty(),
                        values = modelOptions,
                        onSelected = { onChange(state.copy(model = it)) }
                    )
                } else {
                    OutlinedTextField(
                        value = state.model.orEmpty(),
                        onValueChange = { onChange(state.copy(model = it)) },
                        label = { Text(stringResource(R.string.agent_global_override_model_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            if (backend == AgentRuntimeBackend.LITERT) {
                Text(
                    text = stringResource(R.string.litert_gallery_accelerator),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        LITERT_BACKEND_AUTO to R.string.general_acceleration_mode_auto,
                        LITERT_BACKEND_CPU to R.string.general_acceleration_mode_cpu,
                        LITERT_BACKEND_GPU to R.string.litert_backend_gpu
                    ).forEach { (mode, labelRes) ->
                        FilterChip(
                            selected = normalizeLiteRtBackend(state.liteRtBackend) == mode,
                            onClick = { onChange(state.copy(liteRtBackend = mode)) },
                            modifier = Modifier.defaultMinSize(minWidth = 104.dp),
                            label = { Text(stringResource(labelRes), maxLines = 1) }
                        )
                    }
                }
                AgentGlobalOverrideSwitchRow(
                    title = stringResource(R.string.litert_gallery_mtp_title),
                    description = stringResource(R.string.litert_gallery_mtp_desc),
                    checked = state.liteRtMtpEnabled,
                    onCheckedChange = { onChange(state.copy(liteRtMtpEnabled = it)) }
                )
            }

            DraftIntTextField(
                value = state.contextSize,
                onValueChange = { onChange(state.copy(contextSize = it)) },
                label = { Text(stringResource(R.string.agent_global_override_context_label)) },
                modifier = Modifier.fillMaxWidth(),
                blankValue = 0
            )
            DraftIntTextField(
                value = state.maxOutputTokens,
                onValueChange = { onChange(state.copy(maxOutputTokens = it)) },
                valueRange = 1..1_048_576,
                label = { Text(stringResource(R.string.agent_global_override_max_output_label)) },
                modifier = Modifier.fillMaxWidth(),
                blankValue = 8096
            )

            AgentGlobalOverrideSwitchRow(
                title = stringResource(R.string.agent_thinking_enabled),
                description = stringResource(R.string.agent_thinking_enabled_desc),
                checked = state.thinkingEnabled,
                onCheckedChange = { onChange(state.copy(thinkingEnabled = it)) }
            )
            AgentGlobalOverrideSwitchRow(
                title = stringResource(R.string.agent_vision_enabled),
                description = stringResource(R.string.agent_vision_enabled_desc),
                checked = state.visionEnabled,
                onCheckedChange = { onChange(state.copy(visionEnabled = it)) }
            )
        }
    }
}

@Composable
private fun AgentGlobalOverrideSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f)
        )
    }
}

private data class AgentToolSettingGroup(
    val titleRes: Int,
    val tools: List<String>
)

private fun agentToolSettingGroups(): List<AgentToolSettingGroup> = listOf(
    AgentToolSettingGroup(
        R.string.agent_tool_category_workflow,
        listOf(
            "question",
            "project_state_read",
            "project_order_read",
            "plan_read",
            "agent_report_read",
            "todo_read",
            "todo_write",
            "todo_reconcile",
            "todo_transition",
            "call_agent",
            "propose_plan",
            "report_progress",
            "reflection",
            "finish_task"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_inspection,
        listOf(
            "read_file",
            "read_file_lines",
            "file_line_count",
            "list_directory",
            "search_code",
            "view_image"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_mutation,
        listOf("write_file", "edit_lines", "apply_patch", "create_folder")
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_execution,
        listOf(
            "run_command",
            "check_command",
            "wait_command",
            "command_list",
            "cancel_command",
            "send_command_input",
            "run_project",
            "check_project_run",
            "stop_project_run",
            "force_stop_project_run",
            "install_python_dependency"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_memory,
        listOf(
            "read_memory",
            "list_memory",
            "write_memory",
            "rewrite_memory",
            "delete_memory"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_research,
        listOf(
            "web_search",
            "fetch_url",
            "kiwix_search",
            "kb_search",
            "kb_read_chunk",
            "kb_list_sources"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_media,
        listOf(
            "generate_image",
            "remove_image_background",
            "observe_preview",
            "interact_preview"
        )
    ),
    AgentToolSettingGroup(
        R.string.agent_tool_category_advanced,
        listOf(
            "run_tools_sequential",
            "skill",
            "read_skill_resource",
            "run_skill_script",
            "get_datetime"
        )
    )
)

private fun agentToolDisplayName(toolName: String): String =
    toolName
        .split('_')
        .joinToString(" ") { token ->
            token.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        }

@Composable
private fun AgentToolSettingsCard(
    disabledStandardTools: Set<String>,
    autoReflectionEnabled: Boolean,
    requirePlanReadOnlyDelegationApproval: Boolean,
    onAutoReflectionChanged: (Boolean) -> Unit,
    onPlanDelegationApprovalChanged: (Boolean) -> Unit,
    onToolEnabledChanged: (String, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_tool_controls_title),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.agent_tool_controls_desc),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.agent_tool_search_label)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )
            AgentToolToggleRow(
                label = stringResource(R.string.agent_auto_reflection_toggle),
                description = stringResource(R.string.agent_auto_reflection_toggle_desc),
                checked = autoReflectionEnabled,
                onCheckedChange = onAutoReflectionChanged
            )
            AgentToolToggleRow(
                label = stringResource(
                    R.string.agent_plan_read_only_delegation_approval
                ),
                description = stringResource(
                    R.string.agent_plan_read_only_delegation_approval_desc
                ),
                checked = requirePlanReadOnlyDelegationApproval,
                onCheckedChange = onPlanDelegationApprovalChanged
            )
            agentToolSettingGroups().forEach { group ->
                val visibleTools = group.tools.filter { tool ->
                    normalizedQuery.isBlank() ||
                        tool.lowercase().contains(normalizedQuery) ||
                        agentToolDisplayName(tool)
                            .lowercase()
                            .contains(normalizedQuery)
                }
                if (visibleTools.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(group.titleRes),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    visibleTools.forEach { toolName ->
                        val critical = isCriticalAgentProtocolTool(toolName)
                        AgentToolToggleRow(
                            label = agentToolDisplayName(toolName),
                            rawName = toolName,
                            description = stringResource(
                                if (critical) {
                                    R.string.agent_critical_tool_desc
                                } else {
                                    R.string.agent_tool_toggle_desc
                                }
                            ),
                            checked = critical || toolName !in disabledStandardTools,
                            enabled = !critical,
                            onCheckedChange = { enabled ->
                                onToolEnabledChanged(toolName, enabled)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentWorkflowReadinessCard(
    disabledAgents: Set<String>,
    researchBackendAvailable: Boolean
) {
    val planReady =
        "CODEBASE_SCOUT" !in disabledAgents &&
            "PLANNER" !in disabledAgents
    val buildReady = "CODER" !in disabledAgents
    val reviewReady =
        "REVIEWER" !in disabledAgents &&
            "EXECUTOR" !in disabledAgents
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.agent_workflow_readiness_title),
                fontWeight = FontWeight.Bold
            )
            AgentReadinessRow(
                label = stringResource(R.string.agent_workflow_plan_label),
                ready = planReady,
                detail = if (planReady) {
                    stringResource(R.string.agent_workflow_ready)
                } else {
                    stringResource(R.string.agent_workflow_plan_limited)
                }
            )
            AgentReadinessRow(
                label = stringResource(R.string.agent_workflow_build_label),
                ready = buildReady,
                detail = if (buildReady) {
                    stringResource(R.string.agent_workflow_ready)
                } else {
                    stringResource(R.string.agent_workflow_build_limited)
                }
            )
            AgentReadinessRow(
                label = stringResource(R.string.agent_workflow_quality_label),
                ready = reviewReady,
                detail = if (reviewReady) {
                    stringResource(R.string.agent_workflow_ready)
                } else {
                    stringResource(R.string.agent_workflow_quality_limited)
                }
            )
            if (!researchBackendAvailable) {
                Text(
                    stringResource(R.string.agent_workflow_research_backend_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun AgentReadinessRow(
    label: String,
    ready: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTuningCard(
    emoji: String,
    roleName: String,
    description: String,
    selectedModel: String,
    availableModels: List<String>,
    llamaSwapModels: List<String> = emptyList(),
    backend: String,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    onModelChange: (String) -> Unit,
    contextSize: Int,
    onContextSizeChange: (Int) -> Unit,
    maxOutputTokens: Int,
    onMaxOutputTokensChange: (Int) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    visionEnabled: Boolean? = null,
    onVisionChange: ((Boolean) -> Unit)? = null,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    runtimeProfile: AgentRuntimeProfile? = null,
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    runtimeLiteRtModels: List<AgentLiteRtProfileOption> = emptyList(),
    endpointConfigs: List<AgentRuntimeEndpointConfig> = emptyList(),
    onSaveEndpointConfig: (AgentRuntimeEndpointConfig) -> Unit = {},
    onDeleteEndpointConfig: (Long) -> Unit = {},
    onRuntimeProfileChange: (AgentRuntimeProfile) -> Unit = {},
    onRuntimeContinue: ((AgentRuntimeContinueAction) -> Unit)? = null
) {
    var modelExpanded by remember { mutableStateOf(false) }
    val effectiveBackend = runtimeProfile?.normalizedBackend?.id ?: backend
    val isLiteRt = SettingsRepository.isLiteRtBackend(effectiveBackend)
    val isServer =
        SettingsRepository.isLlamaServerBackend(effectiveBackend) ||
            SettingsRepository.isLlamaSwapBackend(effectiveBackend)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (isEnabled) 0.5f else 0.2f
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    emoji,
                    fontSize = 24.sp,
                    modifier = Modifier.alpha(if (isEnabled) 1f else 0.4f)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        roleName + if (!isEnabled) {
                            " (${stringResource(R.string.agent_disabled_label)})"
                        } else {
                            ""
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.scale(0.7f)
                )
            }
            AnimatedVisibility(visible = isEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runtimeProfile?.let { profile ->
                        AgentRuntimeProfileControls(
                            profile = profile,
                            ollamaModels = availableModels,
                            llamaSwapModels = llamaSwapModels,
                            managedLlamaServers = managedLlamaServers,
                            liteRtModels = runtimeLiteRtModels,
                            endpointConfigs = endpointConfigs,
                            onSaveEndpointConfig = onSaveEndpointConfig,
                            onDeleteEndpointConfig = onDeleteEndpointConfig,
                            onProfileChange = onRuntimeProfileChange,
                            onContinue = onRuntimeContinue
                        )
                    }
                    when {
                        runtimeProfile != null -> Unit
                        isLiteRt -> Text(
                            stringResource(R.string.agent_litert_role_model_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        isServer -> {
                            Text(
                                stringResource(R.string.pdf_llama_server_model_label),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                friendlyBackendModelLabel(llamaServerModelLabel)
                                    ?: stringResource(
                                        R.string.agent_llama_server_value_unavailable
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            llamaServerContextLabel?.let { contextLabel ->
                                Text(
                                    stringResource(
                                        R.string.agent_server_effective_context,
                                        contextLabel
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedModel,
                                onValueChange = onModelChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                label = { Text(stringResource(R.string.agent_model_label)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = modelExpanded
                                    )
                                },
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onModelChange(model)
                                            modelExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (!isLiteRt && !isServer) {
                        DraftIntTextField(
                            value = contextSize,
                            onValueChange = onContextSizeChange,
                            label = { Text(stringResource(R.string.agent_context_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            blankValue = 0
                        )
                        DraftIntTextField(
                            value = maxOutputTokens,
                            onValueChange = onMaxOutputTokensChange,
                            valueRange = 1..1_048_576,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.agent_max_output_tokens_label
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            blankValue = 8096
                        )
                    }
                    if (!isLiteRt) {
                        AgentSwitchRow(
                            title = stringResource(R.string.agent_thinking_enabled),
                            checked = thinkingEnabled,
                            onCheckedChange = onThinkingChange
                        )
                    }
                    if (visionEnabled != null && onVisionChange != null) {
                        AgentSwitchRow(
                            title = stringResource(R.string.agent_vision_enabled),
                            checked = visionEnabled,
                            onCheckedChange = onVisionChange
                        )
                    }
                    Text(
                        stringResource(R.string.agent_runtime_contract_managed_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentStringDropdown(
    label: String,
    selected: String,
    values: List<String>,
    labelFor: @Composable (String) -> String = { it },
    descriptionFor: @Composable (String) -> String? = { null },
    supportingText: String? = null,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
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
                        text = if (selected.isBlank()) {
                            stringResource(R.string.image_tool_component_none)
                        } else {
                            labelFor(selected)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    descriptionFor(selected)?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(imageVector = Icons.Default.ExpandMore, contentDescription = label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(labelFor(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                descriptionFor(option)?.takeIf { it.isNotBlank() }?.let { description ->
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
        supportingText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AgentSdComponentDropdown(
    label: String,
    selected: String,
    values: List<String>,
    allowNone: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
                Text(
                    text = selected.ifBlank { stringResource(R.string.image_tool_component_none) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
private fun AgentSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f)
        )
    }
}

private fun agentSdComponentOptions(
    models: List<ModelEntity>,
    selectedModel: ModelEntity?,
    role: SdComponentRole
): List<String> {
    val (family, variant) = selectedModel?.resolvedSdFamily() ?: return emptyList()
    val modelType = role.toAgentModelType() ?: return emptyList()
    val resolvedFamily = family ?: return emptyList()
    return models
        .filter { model -> model.type == modelType && model.matchesSdFamily(resolvedFamily, variant) }
        .map { it.filename }
        .distinct()
}

private fun SdComponentRole.toAgentModelType(): ModelType? = when (this) {
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

private fun agentSdComponentLabelRes(role: SdComponentRole): Int = when (role) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentLiteRtBackendCard(
    liteRtModels: List<LiteRtModelEntity>,
    selectedModel: LiteRtModelEntity?,
    selectedModelId: Long,
    onModelSelected: (Long?) -> Unit,
    showModelPicker: Boolean = true,
    selectedBackend: String,
    onBackendSelected: (String) -> Unit,
    savedContextTokens: Int,
    onContextTokensChange: (Int?) -> Unit,
    savedMaxOutputTokens: Int,
    onMaxOutputTokensChange: (Int?) -> Unit,
    mtpEnabled: Boolean,
    onMtpEnabledChange: (Boolean) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingEnabledChange: (Boolean) -> Unit
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val contextCap = resolveAgentLiteRtContextTokens(
        savedContextTokens = Int.MAX_VALUE,
        model = selectedModel
    )
    val defaultContext = resolveAgentLiteRtContextTokens(
        savedContextTokens = -1,
        model = selectedModel
    )
    val resolvedContext = resolveAgentLiteRtContextTokens(
        savedContextTokens = savedContextTokens,
        model = selectedModel
    )
    val resolvedMaxOutput = resolveAgentLiteRtMaxOutputTokens(
        savedMaxOutputTokens = savedMaxOutputTokens,
        resolvedContextTokens = resolvedContext,
        model = selectedModel
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("⚡", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_litert_settings_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.agent_litert_settings_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showModelPicker) {
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { if (liteRtModels.isNotEmpty()) modelMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedModel?.displayName
                            ?: stringResource(R.string.agent_litert_no_models),
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        enabled = liteRtModels.isNotEmpty(),
                        label = { Text(stringResource(R.string.litert_model_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        liteRtModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            text = model.filename,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = {
                                    onModelSelected(model.id)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.litert_gallery_accelerator),
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    LITERT_BACKEND_AUTO to R.string.general_acceleration_mode_auto,
                    LITERT_BACKEND_CPU to R.string.general_acceleration_mode_cpu,
                    LITERT_BACKEND_GPU to R.string.litert_backend_gpu
                ).forEach { (mode, labelRes) ->
                    FilterChip(
                        selected = normalizeLiteRtBackend(selectedBackend) == mode,
                        onClick = { onBackendSelected(mode) },
                        modifier = Modifier.defaultMinSize(minWidth = 104.dp),
                        label = { Text(stringResource(labelRes), maxLines = 1) }
                    )
                }
            }

            DraftIntTextField(
                value = resolvedContext,
                onValueChange = { value -> onContextTokensChange(value.takeIf { it > 0 }) },
                label = { Text(stringResource(R.string.agent_litert_context_label)) },
                modifier = Modifier.fillMaxWidth(),
                blankValue = 0
            )
            Text(
                text = stringResource(R.string.agent_litert_context_hint, defaultContext, contextCap),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DraftIntTextField(
                value = resolvedMaxOutput,
                onValueChange = { value -> onMaxOutputTokensChange(value.takeIf { it > 0 }) },
                label = { Text(stringResource(R.string.agent_litert_max_output_label)) },
                modifier = Modifier.fillMaxWidth(),
                blankValue = 0
            )
            Text(
                text = stringResource(R.string.agent_litert_max_output_hint, resolvedMaxOutput),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThinkingEnabledChange(!thinkingEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_thinking_enabled), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.agent_thinking_enabled_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = onThinkingEnabledChange,
                    modifier = Modifier.scale(0.8f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMtpEnabledChange(!mtpEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.litert_gallery_mtp_title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.litert_gallery_mtp_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = mtpEnabled,
                    onCheckedChange = onMtpEnabledChange,
                    modifier = Modifier.scale(0.8f)
                )
            }

            if (selectedModelId <= 0L && liteRtModels.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.agent_litert_autoselect_note),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigCard(
    emoji: String,
    roleName: String,
    description: String,
    selectedModel: String,
    availableModels: List<String>,
    llamaSwapModels: List<String> = emptyList(),
    backend: String,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    onModelChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    contextSize: Int,
    onContextSizeChange: (Int) -> Unit,
    maxOutputTokens: Int,
    onMaxOutputTokensChange: (Int) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    visionEnabled: Boolean,
    onVisionChange: (Boolean) -> Unit,
    isEnabled: Boolean = true,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    runtimeProfile: AgentRuntimeProfile? = null,
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    runtimeLiteRtModels: List<AgentLiteRtProfileOption> = emptyList(),
    endpointConfigs: List<AgentRuntimeEndpointConfig> = emptyList(),
    onSaveEndpointConfig: (AgentRuntimeEndpointConfig) -> Unit = {},
    onDeleteEndpointConfig: (Long) -> Unit = {},
    onRuntimeProfileChange: (AgentRuntimeProfile) -> Unit = {},
    onRuntimeContinue: ((AgentRuntimeContinueAction) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    val effectiveBackend = runtimeProfile?.normalizedBackend?.id ?: backend
    val isLiteRtBackend = SettingsRepository.isLiteRtBackend(effectiveBackend)
    val isRemoteServerBackend =
        SettingsRepository.isLlamaServerBackend(effectiveBackend) ||
            SettingsRepository.isLlamaSwapBackend(effectiveBackend)
    val friendlyLlamaServerModel = friendlyBackendModelLabel(llamaServerModelLabel)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isEnabled) 0.5f else 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with optional enable/disable toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(emoji, fontSize = 24.sp, modifier = Modifier.alpha(if (isEnabled) 1f else 0.4f))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        roleName + if (!isEnabled) " (${stringResource(R.string.agent_disabled_label)})" else "",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onEnabledChange != null) {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onEnabledChange,
                        modifier = Modifier.scale(0.7f)
                    )
                }
            }
            
            // Only show settings when enabled
            AnimatedVisibility(visible = isEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    runtimeProfile?.let { profile ->
                        AgentRuntimeProfileControls(
                            profile = profile,
                            ollamaModels = availableModels,
                            llamaSwapModels = llamaSwapModels,
                            managedLlamaServers = managedLlamaServers,
                            liteRtModels = runtimeLiteRtModels,
                            endpointConfigs = endpointConfigs,
                            onSaveEndpointConfig = onSaveEndpointConfig,
                            onDeleteEndpointConfig = onDeleteEndpointConfig,
                            onProfileChange = onRuntimeProfileChange,
                            onContinue = onRuntimeContinue
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (runtimeProfile == null && isLiteRtBackend) {
                        Text(
                            text = stringResource(R.string.pdf_backend_litert),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.agent_litert_role_model_note),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (runtimeProfile == null && isRemoteServerBackend) {
                        Text(
                            text = stringResource(R.string.pdf_llama_server_model_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = friendlyLlamaServerModel ?: stringResource(R.string.agent_llama_server_value_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.agent_llama_server_role_model_note,
                                friendlyLlamaServerModel ?: stringResource(R.string.agent_llama_server_value_unavailable)
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        llamaServerContextLabel?.let { contextLabel ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.pdf_llama_server_context_label),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = contextLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (runtimeProfile == null) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedModel,
                                onValueChange = { onModelChange(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                label = { Text(stringResource(R.string.agent_model_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                singleLine = true
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onModelChange(model)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (!isLiteRtBackend && !isRemoteServerBackend) {
                        Spacer(modifier = Modifier.height(8.dp))

                        DraftIntTextField(
                            value = contextSize,
                            onValueChange = onContextSizeChange,
                            label = { Text(stringResource(R.string.agent_context_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            blankValue = 0
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        DraftIntTextField(
                            value = maxOutputTokens,
                            onValueChange = onMaxOutputTokensChange,
                            valueRange = 1..1_048_576,
                            label = { Text(stringResource(R.string.agent_max_output_tokens_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            blankValue = 8096
                        )
                        Text(
                            text = stringResource(
                                R.string.agent_max_output_tokens_hint,
                                maxOutputTokens,
                                contextSize
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (!isLiteRtBackend) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThinkingChange(!thinkingEnabled) }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.agent_thinking_enabled),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.agent_thinking_enabled_desc),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = thinkingEnabled,
                                onCheckedChange = onThinkingChange,
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVisionChange(!visionEnabled) }
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.agent_vision_enabled),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.agent_vision_enabled_desc),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = visionEnabled,
                            onCheckedChange = onVisionChange,
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPrompt = !showPrompt },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.agent_system_prompt),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (showPrompt) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            contentDescription = if (showPrompt) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(visible = showPrompt) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = onPromptChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 200.dp),
                                label = { Text(stringResource(R.string.agent_prompt_label)) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 10
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = onResetPrompt,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.agent_reset_default), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentToolToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    rawName: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            rawName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.scale(0.8f)
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentModelSelector(
    emoji: String,
    roleName: String,
    description: String,
    selectedModel: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(roleName, fontWeight = FontWeight.Bold)
                    Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = { onModelChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    label = { Text(stringResource(R.string.agent_model_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    singleLine = true
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onModelChange(model)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
