package com.example.llamadroid.ui.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.IBinder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AiServerConfigEntity
import com.example.llamadroid.data.db.AiServerPermissionEntity
import com.example.llamadroid.data.db.AiServerUserEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.AiServerAccessMode
import com.example.llamadroid.service.AiServerAuth
import com.example.llamadroid.service.AiServerLogStore
import com.example.llamadroid.service.AiServerNetwork
import com.example.llamadroid.service.AiServerRuntimeState
import com.example.llamadroid.service.AiServerType
import com.example.llamadroid.service.AiToolServerService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHeroCard
import com.example.llamadroid.ui.components.AppInfoRow
import com.example.llamadroid.ui.components.AppInsetDivider
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AiServersHubScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val configs by db.aiServerDao().observeConfigs().collectAsState(initial = emptyList())
    val users by db.aiServerDao().observeUsers().collectAsState(initial = emptyList())
    val permissions by db.aiServerDao().observePermissions().collectAsState(initial = emptyList())
    val runtimeStates by AiToolServerService.runtimeStates.collectAsState()
    val logsByServer by AiServerLogStore.logs.collectAsState()
    var boundService by remember { mutableStateOf<AiToolServerService?>(null) }
    var addUserDialogOpen by remember { mutableStateOf(false) }
    var resetPasswordUser by remember { mutableStateOf<AiServerUserEntity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val portDrafts = remember { mutableStateMapOf<String, String>() }
    val logsExpanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        AiToolServerService.ensureDefaultConfigs(context)
    }

    DisposableEffect(context) {
        AiToolServerService.start(context)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? AiToolServerService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        context.bindService(
            Intent(context, AiToolServerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            runCatching { context.unbindService(connection) }
        }
    }

    LaunchedEffect(configs) {
        configs.forEach { config ->
            portDrafts.putIfAbsent(config.serverType, config.port.toString())
        }
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        statusMessage = resources.getString(R.string.ai_servers_copied)
    }

    fun saveConfig(config: AiServerConfigEntity, startAfterSave: Boolean = false) {
        val draft = portDrafts[config.serverType].orEmpty()
        val port = draft.toIntOrNull()
        val portError = port == null ||
            !AiServerNetwork.isValidServerPort(port) ||
            AiServerNetwork.portConflict(configs, config.serverType, port)
        if (portError) {
            statusMessage = resources.getString(R.string.ai_servers_invalid_port)
            return
        }
        val safePort = port ?: return
        val updated = config.copy(port = safePort, updatedAt = System.currentTimeMillis())
        scope.launch {
            db.aiServerDao().upsertConfig(updated)
            if (startAfterSave) {
                val result = boundService?.startServer(updated)
                statusMessage = result?.exceptionOrNull()?.message
                    ?: resources.getString(R.string.ai_servers_server_started)
            } else {
                statusMessage = resources.getString(R.string.ai_servers_saved)
            }
        }
    }

    fun setAllAccessModes(accessMode: String) {
        scope.launch(Dispatchers.IO) {
            configs.forEach {
                db.aiServerDao().upsertConfig(it.copy(accessMode = accessMode, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    AppScreenScaffold(
        title = stringResource(R.string.ai_servers_hub_title),
        subtitle = stringResource(R.string.ai_servers_hub_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(
                onClick = {
                    AiServerType.entries.forEach { boundService?.stopServer(it.id) }
                    statusMessage = resources.getString(R.string.ai_servers_all_stopped)
                }
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.ai_servers_stop_all))
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppContentColumn(bottomPadding = 0.dp) {
                    AppHeroCard(
                        title = stringResource(R.string.ai_servers_hub_hero_title),
                        subtitle = stringResource(R.string.ai_servers_hub_hero_subtitle),
                        badge = stringResource(
                            R.string.ai_servers_running_badge,
                            runtimeStates.count { it.running },
                            configs.size
                        ),
                        gradientColors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ),
                        icon = Icons.Default.Cloud,
                        iconContentDescription = stringResource(R.string.ai_servers_hub_title)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = { setAllAccessModes(AiServerAccessMode.PUBLIC) },
                                label = { Text(stringResource(R.string.ai_servers_make_all_public)) },
                                leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) }
                            )
                            AssistChip(
                                onClick = { setAllAccessModes(AiServerAccessMode.USERS) },
                                label = { Text(stringResource(R.string.ai_servers_use_users_all)) },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) }
                            )
                        }
                    }
                    statusMessage?.let {
                        StatusBanner(message = it, onDismiss = { statusMessage = null })
                    }
                    UsersAccessCard(
                        users = users,
                        permissions = permissions,
                        onAddUser = { addUserDialogOpen = true },
                        onDeleteUser = { user ->
                            scope.launch(Dispatchers.IO) { db.aiServerDao().deleteUser(user.id) }
                        },
                        onResetPassword = { resetPasswordUser = it },
                        onPermissionChange = { userId, serverType, enabled ->
                            scope.launch(Dispatchers.IO) {
                                db.aiServerDao().upsertPermission(
                                    AiServerPermissionEntity(
                                        userId = userId,
                                        serverType = serverType,
                                        canAccess = enabled,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    )
                }
            }

            items(configs, key = { it.serverType }) { config ->
                val type = AiServerType.fromId(config.serverType)
                val state = runtimeStates.firstOrNull { it.serverType == config.serverType }
                val serverLogs = logsByServer[config.serverType].orEmpty()
                val portDraft = portDrafts[config.serverType] ?: config.port.toString()
                val portNumber = portDraft.toIntOrNull()
                val portError = portNumber == null ||
                    !AiServerNetwork.isValidServerPort(portNumber) ||
                    AiServerNetwork.portConflict(configs, config.serverType, portNumber)
                AppContentColumn(topPadding = 0.dp, bottomPadding = 0.dp) {
                    AiServerConfigCard(
                        config = config,
                        displayName = localizedServerName(config.serverType),
                        type = type,
                        runtimeState = state,
                        logs = serverLogs,
                        logsExpanded = logsExpanded[config.serverType] == true,
                        portDraft = portDraft,
                        portError = portError,
                        serviceReady = boundService != null,
                        onPortChange = { portDrafts[config.serverType] = it.filter(Char::isDigit).take(5) },
                        onLanChange = { lanVisible ->
                            scope.launch(Dispatchers.IO) {
                                db.aiServerDao().upsertConfig(
                                    config.copy(lanVisible = lanVisible, updatedAt = System.currentTimeMillis())
                                )
                            }
                        },
                        onAccessChange = { accessMode ->
                            scope.launch(Dispatchers.IO) {
                                db.aiServerDao().upsertConfig(
                                    config.copy(accessMode = accessMode, updatedAt = System.currentTimeMillis())
                                )
                            }
                        },
                        onSave = { saveConfig(config, startAfterSave = false) },
                        onStart = { saveConfig(config, startAfterSave = true) },
                        onStop = {
                            boundService?.stopServer(config.serverType)
                            statusMessage = resources.getString(R.string.ai_servers_server_stopped)
                        },
                        onCopy = ::copy,
                        onToggleLogs = {
                            logsExpanded[config.serverType] = logsExpanded[config.serverType] != true
                        },
                        onClearLogs = { AiServerLogStore.clear(config.serverType) }
                    )
                }
            }
        }
    }

    if (addUserDialogOpen) {
        AiServerUserDialog(
            title = stringResource(R.string.ai_servers_add_user),
            confirmLabel = stringResource(R.string.ai_servers_add_user),
            onDismiss = { addUserDialogOpen = false },
            onSave = { username, password ->
                addUserDialogOpen = false
                scope.launch(Dispatchers.IO) {
                    val salt = AiServerAuth.createSalt()
                    val userId = db.aiServerDao().upsertUser(
                        AiServerUserEntity(
                            username = username.trim(),
                            displayName = username.trim(),
                            passwordSalt = salt,
                            passwordHash = AiServerAuth.hashPassword(password, salt)
                        )
                    )
                    db.aiServerDao().upsertPermissions(
                        AiServerType.entries.map { type ->
                            AiServerPermissionEntity(userId = userId, serverType = type.id, canAccess = true)
                        }
                    )
                    withContext(Dispatchers.Main) {
                        statusMessage = resources.getString(R.string.ai_servers_user_added)
                    }
                }
            }
        )
    }

    resetPasswordUser?.let { user ->
        AiServerUserDialog(
            title = stringResource(R.string.ai_servers_reset_password_for, user.username),
            confirmLabel = stringResource(R.string.ai_servers_reset_password),
            usernameLocked = user.username,
            onDismiss = { resetPasswordUser = null },
            onSave = { _, password ->
                resetPasswordUser = null
                scope.launch(Dispatchers.IO) {
                    val salt = AiServerAuth.createSalt()
                    db.aiServerDao().upsertUser(
                        user.copy(
                            passwordSalt = salt,
                            passwordHash = AiServerAuth.hashPassword(password, salt),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    withContext(Dispatchers.Main) {
                        statusMessage = resources.getString(R.string.ai_servers_password_updated)
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiServerConfigCard(
    config: AiServerConfigEntity,
    displayName: String,
    type: AiServerType?,
    runtimeState: AiServerRuntimeState?,
    logs: List<com.example.llamadroid.util.LogEntry>,
    logsExpanded: Boolean,
    portDraft: String,
    portError: Boolean,
    serviceReady: Boolean,
    onPortChange: (String) -> Unit,
    onLanChange: (Boolean) -> Unit,
    onAccessChange: (String) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onToggleLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val running = runtimeState?.running == true
    AppSectionCard(
        tonalAccent = if (running) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = type?.emoji ?: "AI",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = serverDescription(config.serverType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ServerStatusChip(running = running, error = runtimeState?.error)
        }

        BoxWithConstraints {
            val compact = maxWidth < 760.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ServerSettingsFields(config, portDraft, portError, onPortChange, onLanChange, onAccessChange)
                    ServerActions(running, serviceReady, portError, onSave, onStart, onStop)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        ServerSettingsFields(config, portDraft, portError, onPortChange, onLanChange, onAccessChange)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ServerActions(running, serviceReady, portError, onSave, onStart, onStop)
                    }
                }
            }
        }

        if (running) {
            QrAndUrlPanel(
                urls = runtimeState?.urls.orEmpty(),
                onCopy = onCopy
            )
        }

        AppInsetDivider()
        LogsPanel(
            logs = logs,
            expanded = logsExpanded,
            onToggle = onToggleLogs,
            onCopy = {
                onCopy(logs.joinToString("\n") { "${it.timestamp} ${it.message}" })
            },
            onClear = onClearLogs
        )
    }
}

@Composable
private fun ServerSettingsFields(
    config: AiServerConfigEntity,
    portDraft: String,
    portError: Boolean,
    onPortChange: (String) -> Unit,
    onLanChange: (Boolean) -> Unit,
    onAccessChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = portDraft,
            onValueChange = onPortChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_servers_port)) },
            supportingText = {
                Text(
                    if (portError) {
                        stringResource(R.string.ai_servers_port_error)
                    } else {
                        stringResource(R.string.ai_servers_port_hint)
                    }
                )
            },
            isError = portError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        SettingSwitchRow(
            title = stringResource(R.string.ai_servers_lan_visible),
            subtitle = stringResource(R.string.ai_servers_lan_visible_desc),
            checked = config.lanVisible,
            onCheckedChange = onLanChange
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.ai_servers_access_mode),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.accessMode == AiServerAccessMode.PUBLIC,
                    onClick = { onAccessChange(AiServerAccessMode.PUBLIC) },
                    label = { Text(stringResource(R.string.ai_servers_access_public)) },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = config.accessMode == AiServerAccessMode.USERS,
                    onClick = { onAccessChange(AiServerAccessMode.USERS) },
                    label = { Text(stringResource(R.string.ai_servers_access_users)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }
    }
}

@Composable
private fun ServerActions(
    running: Boolean,
    serviceReady: Boolean,
    portError: Boolean,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppInfoRow(
            label = stringResource(R.string.ai_servers_service),
            value = if (serviceReady) stringResource(R.string.ai_servers_service_ready) else stringResource(R.string.ai_servers_service_starting),
            highlight = serviceReady
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSave,
                enabled = !portError,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.ai_servers_save))
            }
            Button(
                onClick = if (running) onStop else onStart,
                enabled = serviceReady && !portError,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (running) stringResource(R.string.ai_servers_stop) else stringResource(R.string.ai_servers_start))
            }
        }
    }
}

@Composable
private fun QrAndUrlPanel(
    urls: List<Pair<String, String>>,
    onCopy: (String) -> Unit
) {
    if (urls.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppSectionTitle(
            title = stringResource(R.string.ai_servers_qr_title),
            supporting = stringResource(R.string.ai_servers_qr_desc)
        )
        urls.forEach { (label, url) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = remember(url) { generateAiServerQrCode(url, 164).asImageBitmap() },
                        contentDescription = stringResource(R.string.ai_servers_qr_for, label),
                        modifier = Modifier.size(118.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { onCopy(url) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ai_servers_copy_url))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsPanel(
    logs: List<com.example.llamadroid.util.LogEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val displayLogs = remember(logs) { logs.takeLast(140) }
    val listState = rememberLazyListState()
    val shouldAutoScroll by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= totalItems - 3
            }
        }
    }

    LaunchedEffect(displayLogs.size, expanded, shouldAutoScroll) {
        if (expanded && shouldAutoScroll && displayLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayLogs.lastIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ai_servers_logs_title, logs.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy, enabled = logs.isNotEmpty()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_servers_copy_logs))
                }
                OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_servers_clear_logs))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .background(
                        MaterialTheme.colorScheme.inverseSurface,
                        RoundedCornerShape(14.dp)
                    ),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (displayLogs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.ai_servers_no_logs),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                } else {
                    items(displayLogs) { entry ->
                        Text(
                            text = "${entry.timestamp}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsersAccessCard(
    users: List<AiServerUserEntity>,
    permissions: List<AiServerPermissionEntity>,
    onAddUser: () -> Unit,
    onDeleteUser: (AiServerUserEntity) -> Unit,
    onResetPassword: (AiServerUserEntity) -> Unit,
    onPermissionChange: (Long, String, Boolean) -> Unit
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSectionTitle(
                title = stringResource(R.string.ai_servers_users_title),
                supporting = stringResource(R.string.ai_servers_users_desc),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onAddUser) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_servers_add_user))
            }
        }
        if (users.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_servers_no_users),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            users.forEach { user ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.username, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(
                                    stringResource(R.string.ai_servers_user_permissions_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteUser(user) }) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiServerType.entries.forEach { type ->
                                val allowed = permissions
                                    .firstOrNull { it.userId == user.id && it.serverType == type.id }
                                    ?.canAccess ?: true
                                FilterChip(
                                    selected = allowed,
                                    onClick = { onPermissionChange(user.id, type.id, !allowed) },
                                    label = { Text(localizedServerName(type.id)) }
                                )
                            }
                        }
                        OutlinedButton(onClick = { onResetPassword(user) }) {
                            Text(stringResource(R.string.ai_servers_reset_password))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiServerUserDialog(
    title: String,
    confirmLabel: String,
    usernameLocked: String? = null,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(usernameLocked.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val valid = username.trim().isNotBlank() && password.length >= 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { if (usernameLocked == null) username = it },
                    label = { Text(stringResource(R.string.ai_servers_username)) },
                    enabled = usernameLocked == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ai_servers_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.ai_servers_password_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(username, password) },
                enabled = valid
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ServerStatusChip(running: Boolean, error: String?) {
    val color = when {
        error != null -> MaterialTheme.colorScheme.errorContainer
        running -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    ElevatedAssistChip(
        onClick = {},
        label = {
            Text(
                when {
                    error != null -> stringResource(R.string.ai_servers_error)
                    running -> stringResource(R.string.ai_servers_running)
                    else -> stringResource(R.string.ai_servers_stopped)
                }
            )
        },
        colors = androidx.compose.material3.AssistChipDefaults.elevatedAssistChipColors(containerColor = color)
    )
}

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun localizedServerName(serverType: String): String = when (serverType) {
    AiServerType.IMAGE.id -> stringResource(R.string.ai_servers_type_image)
    AiServerType.VIDEO.id -> stringResource(R.string.ai_servers_type_video)
    AiServerType.WORKFLOWS.id -> stringResource(R.string.ai_servers_type_workflows)
    AiServerType.TTS.id -> stringResource(R.string.ai_servers_type_tts)
    AiServerType.VIDEO_UPSCALE.id -> stringResource(R.string.ai_servers_type_video_upscale)
    AiServerType.DOCS_DATASETS.id -> stringResource(R.string.ai_servers_type_docs_datasets)
    AiServerType.LLAMA_CHAT.id -> stringResource(R.string.ai_servers_type_llama_chat)
    AiServerType.AI_HUB.id -> stringResource(R.string.ai_servers_type_ai_hub)
    else -> serverType
}

@Composable
private fun serverDescription(serverType: String): String = when (serverType) {
    AiServerType.IMAGE.id -> stringResource(R.string.ai_servers_type_image_desc)
    AiServerType.VIDEO.id -> stringResource(R.string.ai_servers_type_video_desc)
    AiServerType.WORKFLOWS.id -> stringResource(R.string.ai_servers_type_workflows_desc)
    AiServerType.TTS.id -> stringResource(R.string.ai_servers_type_tts_desc)
    AiServerType.VIDEO_UPSCALE.id -> stringResource(R.string.ai_servers_type_video_upscale_desc)
    AiServerType.DOCS_DATASETS.id -> stringResource(R.string.ai_servers_type_docs_datasets_desc)
    AiServerType.LLAMA_CHAT.id -> stringResource(R.string.ai_servers_type_llama_chat_desc)
    AiServerType.AI_HUB.id -> stringResource(R.string.ai_servers_type_ai_hub_desc)
    else -> ""
}

private fun generateAiServerQrCode(content: String, size: Int): Bitmap {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bitmap
}
