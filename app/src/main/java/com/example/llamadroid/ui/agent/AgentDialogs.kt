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
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.friendlyBackendModelLabel
import com.example.llamadroid.service.resolveAgentLiteRtContextTokens
import com.example.llamadroid.service.resolveAgentLiteRtMaxOutputTokens
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField

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
    
    // One-line install command - configures SSH on port 8023 (separate from Termux tools port 8025)
    val oneLineInstall = """pkg install proot-distro -y && proot-distro install ubuntu --override-alias ai-agent && proot-distro login ai-agent --isolated -- bash -c "apt update && apt install -y openssh-server git ripgrep python3 nodejs npm curl wget && mkdir -p /run/sshd && sed -i 's/#Port 22/Port 8023/' /etc/ssh/sshd_config && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config && echo 'root:agent' | chpasswd && mkdir -p /workspace""""
    
    // Start command - uses port 8023
    val startCommand = "proot-distro login ai-agent --isolated -- /usr/sbin/sshd -p 8023 -D &"
    
    fun copyToClipboard(text: String, label: String) {
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.agent_copy_success, label), Toast.LENGTH_SHORT).show()
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
                                            onClick = { copyToClipboard(tool.name, "Tool Name") },
                                            modifier = Modifier.size(20.dp)
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
                                            "Params: ${tool.requiredParams.joinToString(", ")}",
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
                            onClick = { copyToClipboard(oneLineInstall, context.getString(R.string.agent_install_cmd_label)) },
                            modifier = Modifier.size(32.dp)
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
                            onClick = { copyToClipboard(startCommand, context.getString(R.string.agent_start_cmd_label)) },
                            modifier = Modifier.size(32.dp)
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
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var editedOllamaUrl by remember { mutableStateOf(ollamaUrl) }
    val context = LocalContext.current
    val settingsRepo = settingsRepository
    val agentBackend by settingsRepo.agentBackend.collectAsState()
    val isAgentOllama = agentBackend == SettingsRepository.PDF_BACKEND_OLLAMA
    val isAgentLlamaServer = SettingsRepository.isLlamaServerBackend(agentBackend)
    val isAgentLlamaSwap = SettingsRepository.isLlamaSwapBackend(agentBackend)
    val isAgentLiteRt = SettingsRepository.isLiteRtBackend(agentBackend)
    val liteRtModels by remember(context) {
        AppDatabase.getDatabase(context.applicationContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val agentLiteRtModelId by settingsRepo.agentLiteRtModelId.collectAsState()
    val agentLiteRtBackend by settingsRepo.agentLiteRtBackend.collectAsState()
    val agentLiteRtContextTokens by settingsRepo.agentLiteRtContextTokens.collectAsState()
    val agentLiteRtMaxOutputTokens by settingsRepo.agentLiteRtMaxOutputTokens.collectAsState()
    val agentLiteRtMtpEnabled by settingsRepo.agentLiteRtMtpEnabled.collectAsState()
    val agentLiteRtThinkingEnabled by settingsRepo.agentLiteRtThinkingEnabled.collectAsState()
    var showLiteRtModelMenu by remember { mutableStateOf(false) }
    
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
                
                // Backend Selector (Ollama / llama-server / llama-swap / LiteRT)
                val llamaServerUrl by settingsRepo.llamaServerUrl.collectAsState()
                val llamaSwapUrl by settingsRepo.agentLlamaSwapUrl.collectAsState()
                var showBackendDropdown by remember { mutableStateOf(false) }
                
                Text(stringResource(R.string.agent_backend_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.agent_backend_desc), fontSize = 10.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                
                Box {
                    OutlinedButton(onClick = { showBackendDropdown = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                isAgentLlamaServer -> stringResource(R.string.pdf_backend_llama_server)
                                isAgentLlamaSwap -> stringResource(R.string.pdf_backend_llama_swap)
                                isAgentLiteRt -> stringResource(R.string.pdf_backend_litert)
                                else -> stringResource(R.string.pdf_backend_ollama)
                            }
                        )
                    }
                    DropdownMenu(expanded = showBackendDropdown, onDismissRequest = { showBackendDropdown = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_backend_ollama)) }, onClick = {
                            settingsRepo.setAgentBackend(SettingsRepository.PDF_BACKEND_OLLAMA)
                            showBackendDropdown = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_backend_llama_server)) }, onClick = {
                            settingsRepo.setAgentBackend(SettingsRepository.PDF_BACKEND_LLAMA_SERVER)
                            showBackendDropdown = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_backend_llama_swap)) }, onClick = {
                            settingsRepo.setAgentBackend(SettingsRepository.PDF_BACKEND_LLAMA_SWAP)
                            showBackendDropdown = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_backend_litert)) }, onClick = {
                            settingsRepo.setAgentBackend(SettingsRepository.PDF_BACKEND_LITERT)
                            showBackendDropdown = false
                        })
                    }
                }

                if (isAgentOllama) {
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
                
                if (isAgentLlamaServer || isAgentLlamaSwap) {
                    Spacer(modifier = Modifier.height(4.dp))
                    var editedLlamaUrl by remember(isAgentLlamaSwap) {
                        mutableStateOf(if (isAgentLlamaSwap) llamaSwapUrl else llamaServerUrl)
                    }
                    OutlinedTextField(
                        value = editedLlamaUrl,
                        onValueChange = { editedLlamaUrl = it },
                        label = {
                            Text(
                                if (isAgentLlamaSwap) {
                                    stringResource(R.string.agent_llama_swap_url)
                                } else {
                                    stringResource(R.string.agent_llama_server_url)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    LaunchedEffect(editedLlamaUrl, isAgentLlamaSwap) {
                        if (isAgentLlamaSwap) {
                            if (editedLlamaUrl != llamaSwapUrl) {
                                settingsRepo.setAgentLlamaSwapUrl(editedLlamaUrl)
                            }
                        } else if (editedLlamaUrl != llamaServerUrl) {
                            settingsRepo.setLlamaServerUrl(editedLlamaUrl)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (isAgentLlamaSwap) {
                            stringResource(R.string.agent_llama_swap_note)
                        } else {
                            stringResource(R.string.agent_llama_server_note)
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }

                if (isAgentLiteRt) {
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
                    ExposedDropdownMenuBox(
                        expanded = showLiteRtModelMenu,
                        onExpandedChange = { showLiteRtModelMenu = it }
                    ) {
                        OutlinedTextField(
                            value = selectedLiteRtModel?.displayName.orEmpty(),
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            readOnly = true,
                            enabled = liteRtModels.isNotEmpty(),
                            label = { Text(stringResource(R.string.litert_model_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showLiteRtModelMenu) },
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = showLiteRtModelMenu,
                            onDismissRequest = { showLiteRtModelMenu = false }
                        ) {
                            liteRtModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        settingsRepo.setAgentLiteRtModelId(model.id)
                                        showLiteRtModelMenu = false
                                    }
                                )
                            }
                        }
                    }
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
                            if (isAgentOllama) {
                                onOllamaUrlChange(editedOllamaUrl)
                            }
                            onConnect()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_connect))
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
    knowledgeBases: List<KnowledgeBaseEntity>,
    selectedKnowledgeBaseIds: List<Long>,
    availableImageGenerationModels: List<String>,
    availableSdImageMainModels: List<ModelEntity>,
    availableSdImageSupportModels: List<ModelEntity>,
    availableBackgroundRemovalModels: List<String>,
    onKnowledgeBaseSelectionChange: (List<Long>) -> Unit,
    onManageKnowledgeBases: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val agentBackend by settingsRepository.agentBackend.collectAsState()
    val isAgentLiteRt = SettingsRepository.isLiteRtBackend(agentBackend)
    val liteRtModels by remember(context) {
        AppDatabase.getDatabase(context.applicationContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val llamaServerModelLabel by settingsRepository.agentLlamaServerModelLabel.collectAsState()
    val llamaServerContextLabel by settingsRepository.agentLlamaServerContextLabel.collectAsState()
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

    LaunchedEffect(isAgentLiteRt, selectedAgentLiteRtModel?.id, agentLiteRtModelId) {
        if (isAgentLiteRt && selectedAgentLiteRtModel != null && agentLiteRtModelId != selectedAgentLiteRtModel.id) {
            settingsRepository.setAgentLiteRtModelId(selectedAgentLiteRtModel.id)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_settings_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.agent_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AgentKnowledgeBaseSelector(
                    knowledgeBases = knowledgeBases,
                    selectedIds = selectedKnowledgeBaseIds,
                    onSelectionChange = onKnowledgeBaseSelectionChange,
                    onManage = onManageKnowledgeBases
                )
                
                // Load disabled agents state
                val disabledAgents by AgentService.disabledBuiltInAgents.collectAsState()
                val disabledStandardTools by AgentService.disabledStandardAgentTools.collectAsState()
                val autoReflectionEnabled by AgentService.autoReflectionEnabled.collectAsState()
                
                LaunchedEffect(Unit) {
                    AgentService.loadDisabledAgents()
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        AgentToolToggleRow(
                            label = stringResource(R.string.agent_auto_reflection_toggle),
                            description = stringResource(R.string.agent_auto_reflection_toggle_desc),
                            checked = autoReflectionEnabled,
                            onCheckedChange = AgentService::setAutoReflectionEnabled
                        )
                        listOf(
                            "read_file",
                            "list_directory",
                            "search_code",
                            "read_memory",
                            "write_memory",
                            "list_memory",
                            "get_datetime",
                            "file_line_count",
                            "read_file_lines",
                            "kb_search",
                            "kb_read_chunk",
                            "kb_list_sources",
                            "view_image",
                            "run_command",
                            "check_command",
                            "wait_command",
                            "command_list",
                            "cancel_command",
                            "send_command_input",
                            "write_file",
                            "edit_lines",
                            "apply_patch",
                            "create_folder",
                            "run_tools_sequential",
                            "call_agent",
                            "reflection",
                            "propose_plan",
                            "finish_task",
                            "run_project",
                            "check_project_run",
                            "stop_project_run",
                            "force_stop_project_run",
                            "install_python_dependency",
                            "web_search",
                            "fetch_url",
                            "kiwix_search",
                            "generate_image",
                            "remove_image_background"
                        ).forEach { toolName ->
                            AgentToolToggleRow(
                                label = toolName,
                                description = stringResource(R.string.agent_tool_toggle_desc),
                                checked = toolName !in disabledStandardTools,
                                onCheckedChange = { enabled ->
                                    AgentService.setStandardAgentToolEnabled(toolName, enabled)
                                }
                            )
                        }
                    }
                }

                if (isAgentLiteRt) {
                    AgentLiteRtBackendCard(
                        liteRtModels = liteRtModels,
                        selectedModel = selectedAgentLiteRtModel,
                        selectedModelId = agentLiteRtModelId,
                        onModelSelected = settingsRepository::setAgentLiteRtModelId,
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
                
                // Orchestrator (always enabled, cannot be disabled)
                val orchestratorThinking by settingsRepository.agentOrchestratorThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "🎯",
                    roleName = stringResource(R.string.agent_orchestrator_name),
                    description = stringResource(R.string.agent_orchestrator_desc),
                    selectedModel = orchestratorModel,
                    availableModels = availableModels,
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
                    onVisionChange = { settingsRepository.setAgentOrchestratorVisionEnabled(it) }
                )
                
                val coderThinking by settingsRepository.agentCoderThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "👷",
                    roleName = stringResource(R.string.agent_coder_name),
                    description = stringResource(R.string.agent_coder_desc),
                    selectedModel = coderModel,
                    availableModels = availableModels,
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
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("CODER", it) }
                )
                
                val reviewerThinking by settingsRepository.agentReviewerThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "🔍",
                    roleName = stringResource(R.string.agent_reviewer_name),
                    description = stringResource(R.string.agent_reviewer_desc),
                    selectedModel = reviewerModel,
                    availableModels = availableModels,
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
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("REVIEWER", it) }
                )
                
                // Executor
                val executorThinking by settingsRepository.agentExecutorThinkingEnabled.collectAsState()
                AgentConfigCard(
                    emoji = "⚡",
                    roleName = stringResource(R.string.agent_executor_name),
                    description = stringResource(R.string.agent_executor_desc),
                    selectedModel = executorModel,
                    availableModels = availableModels,
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
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("EXECUTOR", it) }
                )

                var visualTesterExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (visualTestingEnabled) 0.5f else 0.2f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("👁️", fontSize = 24.sp, modifier = Modifier.alpha(if (visualTestingEnabled) 1f else 0.4f))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.agent_visual_tester_name),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.agent_visual_tester_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = visualTestingEnabled,
                                onCheckedChange = settingsRepository::setAgentVisualTestingEnabled,
                                modifier = Modifier.scale(0.7f)
                            )
                        }

                        AnimatedVisibility(visible = visualTestingEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsRepository.setAgentVisualTesterVisionEnabled(!visualTesterVisionEnabled) }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_visual_testing_vision_toggle), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(R.string.agent_visual_testing_vision_desc),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = visualTesterVisionEnabled,
                                        onCheckedChange = settingsRepository::setAgentVisualTesterVisionEnabled,
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }

                                if (SettingsRepository.isLiteRtBackend(agentBackend)) {
                                    Text(
                                        text = stringResource(R.string.agent_visual_testing_model_route_global),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (SettingsRepository.isLlamaServerBackend(agentBackend)) {
                                    Text(
                                        text = stringResource(
                                            R.string.agent_visual_testing_model_route_server,
                                            friendlyBackendModelLabel(llamaServerModelLabel)
                                                ?: stringResource(R.string.agent_llama_server_value_unavailable)
                                        ),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    ExposedDropdownMenuBox(
                                        expanded = visualTesterExpanded,
                                        onExpandedChange = { visualTesterExpanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = visualTesterModel,
                                            onValueChange = settingsRepository::setAgentVisualTesterModel,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(),
                                            label = { Text(stringResource(R.string.agent_visual_testing_model_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = visualTesterExpanded) },
                                            singleLine = true
                                        )
                                        ExposedDropdownMenu(
                                            expanded = visualTesterExpanded,
                                            onDismissRequest = { visualTesterExpanded = false }
                                        ) {
                                            availableModels.forEach { model ->
                                                DropdownMenuItem(
                                                    text = { Text(model) },
                                                    onClick = {
                                                        settingsRepository.setAgentVisualTesterModel(model)
                                                        visualTesterExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.agent_visual_testing_future_native),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
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
                    onEnabledChange = { AgentService.setBuiltInAgentEnabled("SUMMARIZER", it) }
                )

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
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

@Composable
private fun AgentStringDropdown(
    label: String,
    selected: String,
    values: List<String>,
    labelFor: @Composable (String) -> String = { it },
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
            }
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
    onEnabledChange: ((Boolean) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    val isLiteRtBackend = SettingsRepository.isLiteRtBackend(backend)
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
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                    if (!isLiteRtBackend) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onThinkingChange(!thinkingEnabled) }.padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.agent_thinking_enabled), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.agent_thinking_enabled_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = thinkingEnabled,
                                onCheckedChange = onThinkingChange,
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onVisionChange(!visionEnabled) }.padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.agent_vision_enabled), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.agent_vision_enabled_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = visionEnabled,
                            onCheckedChange = onVisionChange,
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLiteRtBackend) {
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
                    } else if (SettingsRepository.isLlamaServerBackend(backend)) {
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
                    } else {
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

                    if (!isLiteRtBackend) {
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
                                modifier = Modifier.height(28.dp)
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
    onCheckedChange: (Boolean) -> Unit
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
