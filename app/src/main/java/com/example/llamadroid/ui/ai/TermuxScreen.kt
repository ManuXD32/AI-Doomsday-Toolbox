package com.example.llamadroid.ui.ai

import androidx.compose.ui.graphics.Color

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.data.model.TermuxTool
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.data.model.ToolInfoCards
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.service.SSHConfig
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Termux Doomsday Tools Screen
 * Install, uninstall, and run AI tools through a direct Ubuntu SSH server
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val sshService = remember { SSHService(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // Connection state
    val isConnected by SSHService.isConnected.collectAsState()
    val sshConfig by SSHService.config.collectAsState()

    var host by remember { mutableStateOf<String>(sshConfig.host) }
    var port by remember { mutableStateOf<String>(sshConfig.port.toString()) }
    var username by remember { mutableStateOf<String>(sshConfig.user) }
    var password by remember { mutableStateOf<String>(sshConfig.password) }

    var showPassword by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    // Command execution state (global - persists across navigation)
    val output by SSHService.output.collectAsState()
    val isExecuting by SSHService.isExecuting.collectAsState()
    val currentCommand by SSHService.currentCommand.collectAsState()

    // Per-tool output state
    val toolOutputs by SSHService.toolOutputs.collectAsState()
    val toolExecuting by SSHService.toolExecuting.collectAsState()

    // Confirmation dialog state
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf("") }
    var pendingCommandDescription by remember { mutableStateOf("") }
    var pendingToolId by remember { mutableStateOf<String?>(null) }  // Track which tool requested the command
    var pendingToolLogFile by remember { mutableStateOf<String?>(null) }
    var pendingToolClearOutput by remember { mutableStateOf(false) }
    var pendingToolRefreshState by remember { mutableStateOf(false) }
    var pendingToolRefreshInstallState by remember { mutableStateOf(false) }
    var pendingToolExpectedRunningState by remember { mutableStateOf<Boolean?>(null) }

    // Run All dialog state
    var showRunAllDialog by remember { mutableStateOf(false) }
    var pendingCommands by remember { mutableStateOf(listOf<String>()) }
    var pendingToolName by remember { mutableStateOf("") }
    var pendingRunAllWarning by remember { mutableStateOf<String?>(null) }

    // UI state
    var showSetupGuide by remember { mutableStateOf(false) }
    var expandedCard by remember { mutableStateOf<String?>(null) }
    var expandedToolOutput by remember { mutableStateOf<Set<String>>(emptySet()) }  // Track expanded tool outputs
    var detectedLanHost by remember { mutableStateOf<String?>(null) }
    var optimisticRunningUntil by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var currentTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Tool running states (GLOBAL persistence)
    val runningTools by SSHService.runningTools.collectAsState()
    val installedTools by SSHService.installedTools.collectAsState()

    // Trigger for immediate poll (increment to force a poll)
    var pollTrigger by remember { mutableStateOf(0) }

    suspend fun refreshRunningToolsNow() {
        if (!isConnected) {
            SSHService.setRunningTools(emptySet())
            optimisticRunningUntil = emptyMap()
            return
        }

        val newRunning = mutableSetOf<String>()
        TermuxTools.allTools.forEach { tool ->
            if (tool.port > 0) {
                try {
                    val result = SSHService.executeQuiet(TermuxTools.getStatusCommand(tool.id))
                    if (result?.trim() == "running") {
                        newRunning.add(tool.id)
                    }
                } catch (_: Exception) {
                    // Ignore errors during status check.
                }
            }
        }
        SSHService.setRunningTools(newRunning)
        optimisticRunningUntil = optimisticRunningUntil.filterValues { expiry -> expiry > System.currentTimeMillis() }
    }

    suspend fun refreshInstalledToolsNow() {
        if (!isConnected) {
            SSHService.setInstalledTools(emptySet())
            return
        }
        sshService.refreshInstalledToolsNow()
    }

    fun startupGraceMs(toolId: String): Long = when (toolId) {
        "a1111" -> 45_000L
        "fastsdcpu", "fastsdcpu_mcp", "open_webui", "oobabooga" -> 15_000L
        "big_agi" -> 10_000L
        else -> 8_000L
    }

    fun refreshDelayMs(toolId: String): Long = when (toolId) {
        "a1111" -> 8_000L
        "fastsdcpu", "fastsdcpu_mcp", "open_webui", "oobabooga" -> 4_000L
        "big_agi" -> 3_000L
        else -> 2_500L
    }

    fun stopVerificationTimeoutMs(toolId: String): Long = when (toolId) {
        "a1111" -> 20_000L
        "fastsdcpu", "fastsdcpu_mcp", "open_webui", "oobabooga" -> 12_000L
        "big_agi" -> 10_000L
        else -> 8_000L
    }

    suspend fun verifyToolStopped(toolId: String): Boolean {
        val deadline = System.currentTimeMillis() + stopVerificationTimeoutMs(toolId)
        while (System.currentTimeMillis() < deadline) {
            refreshRunningToolsNow()
            if (!SSHService.runningTools.value.contains(toolId)) {
                optimisticRunningUntil = optimisticRunningUntil - toolId
                return true
            }
            kotlinx.coroutines.delay(1000)
        }
        refreshRunningToolsNow()
        val stopped = !SSHService.runningTools.value.contains(toolId)
        if (stopped) {
            optimisticRunningUntil = optimisticRunningUntil - toolId
        }
        return stopped
    }

    // Periodic connection health check (every 3 seconds)
    LaunchedEffect(Unit) {
        while (true) {
            SSHService.checkConnection()
            kotlinx.coroutines.delay(3000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimestamp = System.currentTimeMillis()
            optimisticRunningUntil = optimisticRunningUntil.filterValues { expiry -> expiry > currentTimestamp }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Periodic server status polling (every 10 seconds or on trigger)
    LaunchedEffect(isConnected, pollTrigger) {
        if (isConnected) {
            while (true) {
                refreshRunningToolsNow()
                kotlinx.coroutines.delay(10000)
            }
        } else {
            SSHService.setRunningTools(emptySet())
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            refreshInstalledToolsNow()
        } else {
            SSHService.setInstalledTools(emptySet())
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) {
                detectedLanHost = detectDeviceLanAddress()
                    ?: sshService.detectLanAddress()
                kotlinx.coroutines.delay(15000)
            }
        } else {
            detectedLanHost = null
            optimisticRunningUntil = emptyMap()
        }
    }
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.clipboard_label_command), text))
        Toast.makeText(context, resources.getString(R.string.termux_copy_toast), Toast.LENGTH_SHORT).show()
    }

    fun requestExecute(
        command: String,
        description: String = "Execute command",
        toolId: String? = null,
        followLogFile: String? = null,
        clearExistingOutput: Boolean = false,
        refreshToolState: Boolean = false,
        refreshInstalledState: Boolean = false,
        expectedRunningState: Boolean? = null
    ) {
        pendingCommand = command
        pendingCommandDescription = description
        pendingToolId = toolId
        pendingToolLogFile = followLogFile
        pendingToolClearOutput = clearExistingOutput
        pendingToolRefreshState = refreshToolState
        pendingToolRefreshInstallState = refreshInstalledState
        pendingToolExpectedRunningState = expectedRunningState
        showConfirmDialog = true
    }

    fun requestRunAll(toolName: String, commands: List<String>, warningText: String? = null) {
        pendingToolName = toolName
        pendingCommands = commands
        pendingRunAllWarning = warningText
        showRunAllDialog = true
    }

    fun executeCommand(command: String, refreshInstalledState: Boolean = false) {
        if (!isConnected) return
        scope.launch {
            val result = sshService.executeCommandStreaming(command)
            if (result.isSuccess && refreshInstalledState) {
                kotlinx.coroutines.delay(500)
                refreshInstalledToolsNow()
            }
        }
    }

    fun executeCommandForTool(
        toolId: String,
        command: String,
        followLogFile: String? = null,
        clearExistingOutput: Boolean = false,
        refreshToolState: Boolean = false,
        expectedRunningState: Boolean? = null
    ) {
        if (!isConnected) return
        // Auto-expand output for this tool
        expandedToolOutput = expandedToolOutput + toolId
        scope.launch {
            val result = sshService.executeCommandForTool(
                toolId = toolId,
                command = command,
                followLogFile = followLogFile,
                clearExistingOutput = clearExistingOutput
            )
            if (result.isSuccess && expectedRunningState != null) {
                if (expectedRunningState) {
                    val optimisticRunning = SSHService.runningTools.value.toMutableSet()
                    optimisticRunning.add(toolId)
                    optimisticRunningUntil = optimisticRunningUntil + (
                        toolId to (System.currentTimeMillis() + startupGraceMs(toolId))
                    )
                    SSHService.setRunningTools(optimisticRunning)
                } else {
                    optimisticRunningUntil = optimisticRunningUntil.filterKeys { it != toolId }
                }
            }
            if (refreshToolState) {
                if (result.isSuccess && expectedRunningState == true) {
                    kotlinx.coroutines.delay(refreshDelayMs(toolId))
                    refreshRunningToolsNow()
                } else if (result.isSuccess && expectedRunningState == false) {
                    val stopped = verifyToolStopped(toolId)
                    if (!stopped) {
                        val toolLabel = TermuxTools.getTool(toolId)?.name ?: toolId
                        SSHService.appendToolOutput(
                            toolId,
                            "\n[${resources.getString(R.string.termux_stop_verification_failed, toolLabel)}]\n"
                        )
                    }
                } else {
                    refreshRunningToolsNow()
                    if (result.isSuccess) {
                        kotlinx.coroutines.delay(1500)
                        refreshRunningToolsNow()
                    }
                }
            }
        }
    }

    fun connect() {
        val p = port.toIntOrNull() ?: TermuxTools.DEFAULT_SSH_PORT
        isConnecting = true
        connectionError = null

        // Save to persistent config
        SSHService.setConfig(host, p, username, password)

        scope.launch {
            sshService.connect(SSHConfig(host, p, username, password)).onSuccess {
                isConnecting = false
            }.onFailure { e ->
                isConnecting = false
                connectionError = e.message
            }
        }
    }

    fun executeAllCommands(commands: List<String>, toolName: String) {
        if (!isConnected) return
        // Use persistent scope so installation continues even if navigating away
        sshService.launchSequentialCommandsPersistent(commands, toolName)
    }

    // Confirmation Dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDialog = false
                pendingToolRefreshInstallState = false
                pendingToolExpectedRunningState = null
            },
            title = { Text(stringResource(R.string.termux_confirm_command)) },
            text = {
                Column {
                    Text(pendingCommandDescription, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)
                    ) {
                        ScrollableCommandText(
                            command = pendingCommand,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val toolId = pendingToolId
                        if (toolId != null) {
                            executeCommandForTool(
                                toolId = toolId,
                                command = pendingCommand,
                                followLogFile = pendingToolLogFile,
                                clearExistingOutput = pendingToolClearOutput,
                                refreshToolState = pendingToolRefreshState,
                                expectedRunningState = pendingToolExpectedRunningState
                            )
                        } else {
                            executeCommand(
                                command = pendingCommand,
                                refreshInstalledState = pendingToolRefreshInstallState
                            )
                        }
                        pendingToolId = null
                        pendingToolLogFile = null
                        pendingToolClearOutput = false
                        pendingToolRefreshState = false
                        pendingToolRefreshInstallState = false
                        pendingToolExpectedRunningState = null
                    }
                ) {
                    Text(stringResource(R.string.termux_execute))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showConfirmDialog = false
                    pendingToolRefreshInstallState = false
                    pendingToolExpectedRunningState = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Run All Confirmation Dialog with Edit option
    var isEditingCommands by remember { mutableStateOf(false) }
    var editedCommandsText by remember { mutableStateOf("") }

    if (showRunAllDialog) {
        AlertDialog(
            onDismissRequest = {
                showRunAllDialog = false
                isEditingCommands = false
                pendingRunAllWarning = null
            },
            title = { Text(if (isEditingCommands) stringResource(R.string.termux_edit_commands) else stringResource(R.string.termux_install_tool, pendingToolName)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isEditingCommands) {
                        Text(stringResource(R.string.termux_edit_commands_hint), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedCommandsText,
                            onValueChange = { editedCommandsText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        )
                    } else {
                        pendingRunAllWarning?.let { warning ->
                            Text(
                                warning,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(stringResource(R.string.termux_run_all_summary, pendingCommands.size), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        pendingCommands.forEachIndexed { index, cmd ->
                            CommandPreviewCard(
                                label = "${index + 1}.",
                                command = cmd,
                                onCopy = { copyToClipboard(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (isEditingCommands) {
                    Button(
                        onClick = {
                            val editedCommands = editedCommandsText.lines().filter { it.isNotBlank() }
                            showRunAllDialog = false
                            isEditingCommands = false
                            pendingRunAllWarning = null
                            executeAllCommands(editedCommands, pendingToolName)
                        }
                    ) {
                        Text(stringResource(R.string.termux_run_edited))
                    }
                } else {
                    Row {
                        OutlinedButton(
                            onClick = {
                                editedCommandsText = pendingCommands.joinToString("\n")
                                isEditingCommands = true
                            }
                        ) {
                            Text(stringResource(R.string.action_edit))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                showRunAllDialog = false
                                pendingRunAllWarning = null
                                executeAllCommands(pendingCommands, pendingToolName)
                            }
                        ) {
                            Text(stringResource(R.string.termux_run_all))
                        }
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showRunAllDialog = false
                    isEditingCommands = false
                    pendingRunAllWarning = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    AppScreenScaffold(
        title = stringResource(R.string.termux_title),
        onBack = {
            // Keep the SSH session alive across navigation.
            navController.popBackStack()
        },
        actions = {
            IconButton(onClick = { showSetupGuide = !showSetupGuide }) {
                Icon(Icons.Default.Info, stringResource(R.string.termux_setup_guide))
            }
        }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Setup Guide
            if (showSetupGuide) {
                item {
                    SetupGuideCard(onCopy = { copyToClipboard(it) })
                }
            }

            // WebView behavior info
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.termux_webui_info),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Connection Card
            item {
                ConnectionCard(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    showPassword = showPassword,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    connectionError = connectionError,
                    onHostChange = { host = it },
                    onPortChange = { port = it },
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onTogglePassword = { showPassword = !showPassword },
                    onConnect = {
                        connect()
                    },
                    onDisconnect = {
                        sshService.disconnect()
                        SSHService.clearOutput()
                    }
                )
            }

            // Only show tools when connected
            if (isConnected) {
                // Miniconda Prerequisites Card (install once)
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_miniconda_title),
                        subtitle = stringResource(R.string.termux_miniconda_subtitle),
                        isExpanded = expandedCard == "miniconda",
                        onToggle = { expandedCard = if (expandedCard == "miniconda") null else "miniconda" },
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.termux_miniconda_warning),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            val minicondaName = stringResource(R.string.termux_miniconda_name)

                            // Run All Miniconda commands button
                            Button(
                                onClick = { requestRunAll(minicondaName, TermuxTools.MINICONDA_INSTALL_COMMANDS) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.termux_install_miniconda, TermuxTools.MINICONDA_INSTALL_COMMANDS.size))
                            }

                            val setupDesc = stringResource(R.string.termux_setup_description)
                            TermuxTools.MINICONDA_INSTALL_COMMANDS.forEach { cmd ->
                                CommandRow(
                                    command = cmd,
                                    onCopy = { copyToClipboard(it) },
                                    onExecute = { requestExecute(it, setupDesc) }
                                )
                            }
                        }
                    }
                }

                // Install Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_install_tools_title),
                        subtitle = stringResource(R.string.termux_install_tools_subtitle),
                        isExpanded = expandedCard == "install",
                        onToggle = {
                            val newState = if (expandedCard == "install") null else "install"
                            expandedCard = newState
                            if (newState == "install") {
                                scope.launch { refreshInstalledToolsNow() }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val installAllName = stringResource(R.string.termux_install_all_name)
                            val installAllWarning = stringResource(R.string.termux_install_all_warning)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        installAllWarning,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            requestRunAll(
                                                toolName = installAllName,
                                                commands = TermuxTools.installAllCommands,
                                                warningText = installAllWarning
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.termux_install_all_button))
                                    }
                                }
                            }

                            // Individual tools (with Run All button)
                            TermuxTools.installableTools.forEach { tool ->
                                val installDesc = stringResource(R.string.termux_install_tool, tool.name)
                                ToolInstallItem(
                                    tool = tool,
                                    isInstalled = installedTools.contains(tool.id),
                                    onCopy = { copyToClipboard(it) },
                                    onExecute = { cmd ->
                                        requestExecute(
                                            command = cmd,
                                            description = installDesc,
                                            refreshInstalledState = true
                                        )
                                    },
                                    onRunAll = { requestRunAll(tool.name, tool.installCommands) }
                                )
                            }
                        }
                    }
                }

                // Uninstall Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_remove_tools_title),
                        subtitle = stringResource(R.string.termux_remove_tools_subtitle),
                        isExpanded = expandedCard == "uninstall",
                        onToggle = { expandedCard = if (expandedCard == "uninstall") null else "uninstall" },
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Uninstall all
                            Text(stringResource(R.string.termux_remove_all), fontWeight = FontWeight.Medium)
                            val removeAllConfirm = stringResource(R.string.termux_remove_all_confirm)
                            CommandRow(
                                command = TermuxTools.UNINSTALL_ALL,
                                onCopy = { copyToClipboard(it) },
                                onExecute = {
                                    requestExecute(
                                        command = it,
                                        description = removeAllConfirm,
                                        refreshInstalledState = true
                                    )
                                },
                                isDanger = true
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(stringResource(R.string.termux_remove_individual), fontWeight = FontWeight.Medium)

                            // Individual uninstalls
                            TermuxTools.allTools.forEach { tool ->
                                val removeToolDesc = stringResource(R.string.termux_remove_tool_description, tool.name)
                                ToolUninstallItem(
                                    tool = tool,
                                    onCopy = { copyToClipboard(TermuxTools.getUninstallCommand(tool)) },
                                    onExecute = {
                                        requestExecute(
                                            command = TermuxTools.getUninstallCommand(tool),
                                            description = removeToolDesc,
                                            refreshInstalledState = true
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Run Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_run_servers_title),
                        subtitle = stringResource(R.string.termux_run_servers_subtitle),
                        isExpanded = expandedCard == "run",
                        onToggle = {
                            val newState = if (expandedCard == "run") null else "run"
                            expandedCard = newState
                            if (newState == "run") {
                                pollTrigger++  // Trigger immediate poll when opening
                                scope.launch { refreshInstalledToolsNow() }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TermuxTools.allTools.forEach { tool ->
                                val isRunning = runningTools.contains(tool.id) ||
                                    (optimisticRunningUntil[tool.id] ?: 0L) > currentTimestamp
                                val toolOutput = toolOutputs[tool.id] ?: ""
                                val isToolExecuting = toolExecuting.contains(tool.id)
                                val isOutputExpanded = expandedToolOutput.contains(tool.id)

                                // Use key() to ensure proper state per tool, with local state for immediate UI updates
                                key(tool.id) {
                                    var isNetworkVisible by remember {
                                        mutableStateOf(settingsRepository.getToolNetworkVisible(tool.id))
                                    }

                                    val startToolDesc = stringResource(R.string.termux_start_tool_description, tool.name)
                                    val stopToolDesc = stringResource(R.string.termux_stop_tool_description, tool.name)

                                    Column {
                                        ToolRunItem(
                                            tool = tool,
                                            isRunning = isRunning,
                                            isInstalled = installedTools.contains(tool.id),
                                            isNetworkVisible = isNetworkVisible,
                                            lanConnectionHost = detectedLanHost
                                                ?: sshConfig.host.ifBlank { host.ifBlank { "127.0.0.1" } },
                                            onVisibilityChange = { enabled ->
                                                // Update local state first for immediate UI response
                                                isNetworkVisible = enabled
                                                // Persist to settings
                                                settingsRepository.setToolNetworkVisible(tool.id, enabled)
                                            },
                                            onStart = {
                                                // Get visibility-aware run command
                                                val runCmd = TermuxTools.getRunCommand(tool.id, isNetworkVisible)
                                                requestExecute(
                                                    command = runCmd,
                                                    description = startToolDesc,
                                                    toolId = tool.id,
                                                    followLogFile = TermuxTools.getLogFile(tool.id),
                                                    clearExistingOutput = true,
                                                    refreshToolState = true,
                                                    expectedRunningState = true
                                                )
                                            },
                                            onStop = {
                                                requestExecute(
                                                    command = TermuxTools.getStopCommand(tool.id),
                                                    description = stopToolDesc,
                                                    toolId = tool.id,
                                                    refreshToolState = true,
                                                    expectedRunningState = false
                                                )
                                            },
                                            onOpenWebView = {
                                                val url = "http://127.0.0.1:${tool.port}"
                                                navController.navigate(Screen.TermuxWebView.createRoute(url, tool.name, tool.id))
                                            },
                                            onOpenGallery = {
                                                navController.navigate(Screen.FastsdGallery.route)
                                            }
                                        )

                                        // Collapsible output card for this tool
                                        if (toolOutput.isNotBlank() || isToolExecuting) {
                                            ToolOutputCard(
                                                toolName = tool.name,
                                                output = toolOutput,
                                                isExecuting = isToolExecuting,
                                                isExpanded = isOutputExpanded,
                                                onToggle = {
                                                    expandedToolOutput = if (isOutputExpanded) {
                                                        expandedToolOutput - tool.id
                                                    } else {
                                                        expandedToolOutput + tool.id
                                                    }
                                                },
                                                onClear = { SSHService.clearToolOutput(tool.id) },
                                                onCopy = { copyToClipboard(toolOutput) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Model Manager Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Screen.TermuxFileManager.route) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📂", fontSize = 32.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.termux_file_manager_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.termux_file_manager_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.KeyboardArrowRight, "Open")
                        }
                    }
                }

                // Output
                if (output.isNotBlank() || isExecuting) {
                    item {
                        OutputCard(
                            output = output,
                            isExecuting = isExecuting,
                            currentCommand = currentCommand,
                            onClear = { SSHService.clearOutput() },
                            onCopyAll = { copyToClipboard(it) }
                        )
                    }
                }
            }

            // Manual Command (always available when connected)
            if (isConnected) {
                item {
                    ManualCommandCard(
                        onExecute = { cmd -> executeCommand(cmd) },
                        isExecuting = isExecuting
                    )
                }
            }
        }
    }
}

@Composable
fun SetupGuideCard(onCopy: (String) -> Unit) {
    val sshPort = TermuxTools.DEFAULT_SSH_PORT
    val ubuntuSshStartCommand = "proot-distro login ubuntu -- /usr/sbin/sshd -D -e -p $sshPort &"
    val oneLiner = "pkg install proot-distro -y && proot-distro install ubuntu && proot-distro login ubuntu -- bash -lc \"apt update && apt install openssh-server -y && mkdir -p /run/sshd && chmod 755 /run/sshd && sed -i '/^#\\?PermitRootLogin/d' /etc/ssh/sshd_config && sed -i '/^#\\?PasswordAuthentication/d' /etc/ssh/sshd_config && sed -i '/^#\\?Port /d' /etc/ssh/sshd_config && printf 'Port $sshPort\\nPermitRootLogin yes\\nPasswordAuthentication yes\\n' >> /etc/ssh/sshd_config && echo root:termux | chpasswd\""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.termux_setup_debian_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            val steps = listOf(
                stringResource(R.string.termux_setup_step_1) to null,
                stringResource(R.string.termux_setup_step_2) to "pkg install proot-distro -y\nproot-distro install ubuntu",
                stringResource(R.string.termux_setup_step_3) to "proot-distro login ubuntu",
                stringResource(R.string.termux_setup_step_4) to "apt update && apt upgrade -y\napt install openssh-server -y",
                stringResource(R.string.termux_setup_step_5) to "mkdir -p /run/sshd && chmod 755 /run/sshd",
                stringResource(R.string.termux_setup_step_6) to "sed -i '/^#\\?PermitRootLogin/d' /etc/ssh/sshd_config\nsed -i '/^#\\?PasswordAuthentication/d' /etc/ssh/sshd_config\nsed -i '/^#\\?Port /d' /etc/ssh/sshd_config\nprintf 'Port $sshPort\\nPermitRootLogin yes\\nPasswordAuthentication yes\\n' >> /etc/ssh/sshd_config",
                stringResource(R.string.termux_setup_step_7) to "passwd",
                stringResource(R.string.termux_setup_step_8) to "exit",
                stringResource(R.string.termux_setup_step_9) to ubuntuSshStartCommand
            )

            steps.forEach { (label, command) ->
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (command != null) {
                    CommandBlock(command = command, onCopy = onCopy)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                stringResource(R.string.termux_setup_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(stringResource(R.string.termux_one_liner_setup), fontWeight = FontWeight.Medium)
            CommandBlock(command = oneLiner, onCopy = onCopy)
            Text(
                stringResource(R.string.termux_default_password),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.termux_start_ssh_hint), style = MaterialTheme.typography.bodySmall)
            CommandBlock(command = ubuntuSshStartCommand, onCopy = onCopy)
        }
    }
}

@Composable
fun CommandBlock(command: String, onCopy: (String) -> Unit) {
    CommandPreviewCard(
        command = command,
        onCopy = onCopy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        fontSize = 12.sp
    )
}

@Composable
private fun CommandPreviewCard(
    command: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top
            ) {
                label?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                ScrollableCommandText(
                    command = command,
                    modifier = Modifier.weight(1f),
                    fontSize = fontSize
                )
            }
            IconButton(
                onClick = { onCopy(command) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ScrollableCommandText(
    command: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    SelectionContainer {
        Box(
            modifier = modifier
                .heightIn(max = 220.dp)
                .verticalScroll(verticalScroll)
        ) {
            Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = fontSize
                )
            }
        }
    }
}

@Composable
fun CommandRow(
    command: String,
    onCopy: (String) -> Unit,
    onExecute: (String) -> Unit,
    isDanger: Boolean = false
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editedCommand by remember { mutableStateOf(command) }

    // Edit dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.termux_edit_command)) },
            text = {
                OutlinedTextField(
                    value = editedCommand,
                    onValueChange = { editedCommand = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    showEditDialog = false
                    onExecute(editedCommand)
                }) {
                    Text(stringResource(R.string.termux_run_edited))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEditDialog = false
                    editedCommand = command  // Reset
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isDanger) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.inverseSurface
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                command.take(50) + if (command.length > 50) "..." else "",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontSize = 11.sp,
                maxLines = 1
            )
            Row {
                IconButton(onClick = { onCopy(command) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Share,
                        stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = {
                    editedCommand = command
                    showEditDialog = true
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.action_edit),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = { onExecute(command) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        stringResource(R.string.termux_run_command),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(
    host: String,
    port: String,
    username: String,
    password: String,
    showPassword: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    connectionError: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.termux_connection_title), fontWeight = FontWeight.Bold)
                if (isConnected) {
                    Text(stringResource(R.string.termux_connected), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                stringResource(R.string.termux_connection_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text(stringResource(R.string.termux_host)) },
                    modifier = Modifier.weight(2f),
                    enabled = !isConnected,
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.termux_port)) },
                    modifier = Modifier.weight(1f),
                    enabled = !isConnected,
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.termux_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.termux_password)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected,
                singleLine = true,
                visualTransformation = if (showPassword)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(Icons.Default.Lock, stringResource(R.string.termux_toggle_password))
                    }
                }
            )

            connectionError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.termux_disconnect))
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = username.isNotBlank() && password.isNotBlank() && !isConnecting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.PlayArrow, null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isConnecting) stringResource(R.string.termux_connecting) else stringResource(R.string.termux_connect))
                }
            }
        }
    }
}

@Composable
fun ToolsCard(
    title: String,
    subtitle: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    containerColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    "Expand"
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
private fun InstalledBadge() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
    ) {
        Text(
            text = stringResource(R.string.termux_installed_badge),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ToolInstallItem(
    tool: TermuxTool,
    isInstalled: Boolean,
    onCopy: (String) -> Unit,
    onExecute: (String) -> Unit,
    onRunAll: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(tool.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, fontWeight = FontWeight.Medium)
                            if (isInstalled) {
                                Spacer(modifier = Modifier.width(8.dp))
                                InstalledBadge()
                            }
                            if (tool.requiresMiniconda) {
                                Text(" 🐍", fontSize = 14.sp)  // Indicates needs Miniconda
                            }
                        }
                        Text(stringResource(tool.descriptionResId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.requiresMiniconda) {
                            Text(stringResource(R.string.termux_miniconda_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.action_port, tool.port), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.dataset_steps, tool.installCommands.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                // Run All button prominently at top
                Button(
                    onClick = onRunAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.termux_run_all_steps, tool.installCommands.size))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.termux_or_run_individually), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tool.installCommands.forEach { cmd ->
                    CommandRow(command = cmd, onCopy = onCopy, onExecute = onExecute)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun ToolUninstallItem(tool: TermuxTool, onCopy: () -> Unit, onExecute: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.emoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name)
            }
            Row {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, "Copy", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onExecute, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolRunItem(
    tool: TermuxTool,
    isRunning: Boolean,
    isInstalled: Boolean,
    isNetworkVisible: Boolean = false,
    lanConnectionHost: String,
    onVisibilityChange: (Boolean) -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenWebView: () -> Unit,
    onOpenGallery: () -> Unit = {}
) {
    var showInfo by remember { mutableStateOf(false) }
    val toolInfo = ToolInfoCards.getInfo(tool.id)
    val localTarget = "127.0.0.1:${tool.port}"
    val resolvedLanHost = lanConnectionHost.ifBlank { "127.0.0.1" }
    val lanTarget = "$resolvedLanHost:${tool.port}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tool.emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tool.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isInstalled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            InstalledBadge()
                        }
                    }
                    Text(
                        if (isRunning) stringResource(R.string.termux_status_running_label) else stringResource(R.string.termux_status_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showInfo = !showInfo },
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (showInfo) Icons.Default.KeyboardArrowUp else Icons.Default.Info,
                        stringResource(R.string.action_info),
                        tint = if (showInfo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.action_port, tool.port),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (showInfo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (tool.id == "fastsdcpu") {
                    FilledTonalButton(
                        onClick = onOpenGallery,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountBox,
                            stringResource(R.string.fastsd_gallery_title),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.fastsd_gallery_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isRunning && tool.port > 0 && tool.hasWebUI) {
                    FilledTonalButton(
                        onClick = onOpenWebView,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.Home,
                            stringResource(R.string.termux_open_webui),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.termux_open_webui),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_stop), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.action_start), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Network visibility toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isNetworkVisible) stringResource(R.string.termux_lan) else stringResource(R.string.termux_local),
                        fontSize = 12.sp,
                        color = if (isNetworkVisible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isNetworkVisible) stringResource(R.string.termux_lan_desc) else stringResource(R.string.termux_local_desc),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isNetworkVisible) {
                            stringResource(R.string.termux_lan_target_value, lanTarget)
                        } else {
                            stringResource(R.string.termux_local_target_value, localTarget)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isNetworkVisible,
                    onCheckedChange = onVisibilityChange,
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                        checkedTrackColor = MaterialTheme.colorScheme.secondary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Info card section (expandable)
            if (showInfo && toolInfo != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // RAM requirement
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💾", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.termux_ram_requirement), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(toolInfo.ramRequirement, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Low-RAM tips
                Text(stringResource(R.string.termux_low_ram_tips), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                toolInfo.lowRamTips.forEach { tip ->
                    Row(modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                        Text("•", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Integration
                Text(stringResource(R.string.termux_integration), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    toolInfo.integrationResId?.let { stringResource(it) } ?: toolInfo.integration,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Features row
                Text(stringResource(R.string.termux_features), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    toolInfo.features.forEach { feature ->
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
            }
        }
    }
}

@Composable
fun OutputCard(output: String, isExecuting: Boolean, currentCommand: String, onClear: () -> Unit, onCopyAll: (String) -> Unit = {}) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.termux_output_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                    if (currentCommand.isNotBlank()) {
                        Text(
                            "$ ${currentCommand.take(40)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                    if (output.isNotBlank()) {
                        IconButton(onClick = { onCopyAll(output) }) {
                            Icon(
                                Icons.Default.Share,
                                stringResource(R.string.action_copy_all),
                                tint = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Clear,
                                stringResource(R.string.action_clear),
                                tint = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SelectionContainer {
                Text(
                    text = if (output.isBlank() && isExecuting) "Running..." else output.ifBlank { "No output" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                )
            }
        }
    }
}

@Composable
fun ToolOutputCard(
    toolName: String,
    output: String,
    isExecuting: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        if (isExpanded) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (isExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.termux_output_title) + " " + toolName,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
                    )
                    if (isExecuting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
                Row {
                    if (output.isNotBlank()) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Share,
                                stringResource(R.string.action_copy),
                                tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Clear,
                                stringResource(R.string.action_clear),
                                tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Expandable content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = if (output.isBlank() && isExecuting) stringResource(R.string.termux_output_running) else output.ifBlank { stringResource(R.string.termux_output_none) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                                .verticalScroll(scrollState)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManualCommandCard(onExecute: (String) -> Unit, isExecuting: Boolean) {
    var command by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.termux_manual_command_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.action_command)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (command.isNotBlank()) {
                            onExecute(command)
                            command = ""  // Clear field after execution
                        }
                    },
                    enabled = command.isNotBlank() && !isExecuting
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.termux_execute))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.termux_quick_commands_hint), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ls", "pwd", "whoami", "df -h").forEach { cmd ->
                    FilterChip(
                        selected = false,
                        onClick = { command = cmd },
                        label = { Text(cmd, fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

private fun detectDeviceLanAddress(): String? {
    val preferredInterfaces = listOf("wlan0", "swlan0", "eth0", "ap0", "rmnet_data0")

    val interfaces = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    } catch (_: Exception) {
        emptyList()
    }

    return interfaces
        .sortedBy { networkInterface ->
            preferredInterfaces.indexOf(networkInterface.name).let { index ->
                if (index >= 0) index else Int.MAX_VALUE
            }
        }
        .asSequence()
        .filter { networkInterface ->
            try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (_: Exception) {
                false
            }
        }
        .flatMap { networkInterface ->
            Collections.list(networkInterface.inetAddresses).asSequence()
        }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { address -> address.hostAddress }
        .firstOrNull { hostAddress ->
            !hostAddress.startsWith("127.") && !hostAddress.startsWith("169.254.")
        }
}
