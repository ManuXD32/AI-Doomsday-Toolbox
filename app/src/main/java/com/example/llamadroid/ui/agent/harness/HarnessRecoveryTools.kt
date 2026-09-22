package com.example.llamadroid.ui.agent.harness

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessRecoveryFileServer
import com.example.llamadroid.harness.HarnessRecoveryFileServerPhase
import com.example.llamadroid.harness.HarnessRecoveryFileServerState
import com.example.llamadroid.harness.HarnessRecoveryFileServerStatus
import com.example.llamadroid.harness.HARNESS_TERMINAL_DEFAULT_KEYS
import com.example.llamadroid.harness.HarnessTerminalModifier
import com.example.llamadroid.harness.HarnessTerminalModifierMode
import com.example.llamadroid.harness.HarnessTerminalModifierState
import com.example.llamadroid.harness.composeHarnessTerminalKeySequence
import com.example.llamadroid.harness.harnessTerminalKeyById
import com.example.llamadroid.service.HarnessRecoveryTerminalSessionManager
import com.example.llamadroid.service.HarnessRecoveryTerminalStatus
import com.example.llamadroid.ui.agent.AgentProotTerminalHost
import kotlinx.coroutines.launch

private val RecoveryTerminalBackground = Color(0xFF0D100E)
private val RecoveryTerminalPanel = Color(0xFF151A16)
private val RecoveryTerminalText = Color(0xFFD7FFE0)

/** Full-screen interactive terminal used for recovery without starting the Harness runtime. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HarnessRecoveryTerminalScreen(
    onBack: () -> Unit,
    manager: HarnessRecoveryTerminalSessionManager = HarnessRecoveryTerminalSessionManager.get(LocalContext.current)
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(state.sessionId, state.status) { manager.terminalHost() }
    val latestHost by rememberUpdatedState(host)
    val modifierState by manager.modifierState.collectAsState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(manager) {
        manager.open()
    }
    DisposableEffect(manager) {
        manager.setScreenListener { latestHost?.onScreenUpdated() }
        onDispose {
            manager.setScreenListener(null)
            manager.closeAsync()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(RecoveryTerminalBackground),
        containerColor = RecoveryTerminalBackground,
        // Scaffold owns the safe-drawing/IME insets for this route. The viewport consumes only
        // the PaddingValues supplied here, matching the AppScreenScaffold contract.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RecoveryTerminalBackground,
                    titleContentColor = RecoveryTerminalText,
                    navigationIconContentColor = RecoveryTerminalText,
                    actionIconContentColor = RecoveryTerminalText,
                ),
                title = {
                    Column {
                        Text(stringResource(R.string.harness_recovery_terminal_title))
                        Text(
                            stringResource(R.string.harness_recovery_terminal_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = RecoveryTerminalText.copy(alpha = 0.75f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.harness_recovery_back)
                        )
                    }
                },
                actions = {
                    if (state.isConnected) {
                        IconButton(onClick = { host?.showKeyboard() }) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = stringResource(R.string.harness_recovery_show_keyboard)
                            )
                        }
                        IconButton(onClick = { scope.launch { manager.close() } }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.harness_recovery_stop_terminal)
                            )
                        }
                    } else if (!state.isOpening) {
                        IconButton(onClick = { scope.launch { manager.open() } }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.harness_recovery_retry)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        HarnessRecoveryTerminalViewport(
            padding = padding,
            state = state,
            manager = manager,
            host = host,
            context = context,
            keyboardVisible = keyboardVisible,
            modifierState = modifierState,
        )
    }
}

/** Production recovery viewport used by deterministic IME and small-screen Compose coverage. */
@Composable
fun HarnessRecoveryTerminalViewport(
    padding: PaddingValues,
    state: com.example.llamadroid.service.HarnessRecoveryTerminalState,
    manager: HarnessRecoveryTerminalSessionManager,
    host: AgentProotTerminalHost?,
    context: Context,
    keyboardVisible: Boolean,
    modifierState: HarnessTerminalModifierState = HarnessTerminalModifierState(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(RecoveryTerminalBackground)
            .testTag("harness_recovery_terminal_viewport")
    ) {
        when {
            state.isOpening -> RecoveryTerminalStatusPanel(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.harness_recovery_terminal_starting),
                progress = true
            )

            state.status == HarnessRecoveryTerminalStatus.FAILED ||
                state.status == HarnessRecoveryTerminalStatus.DISCONNECTED -> {
                RecoveryTerminalStatusPanel(
                    modifier = Modifier.fillMaxWidth(),
                    text = recoveryTerminalErrorText(state.errorCode),
                    progress = false
                )
            }
        }
        val currentHost = host
        if (currentHost != null && state.isConnected) {
            // The PTY is replaced after Stop → Retry while this route remains composed. Keying
            // by session prevents Compose from reusing an Android TerminalView attached to the
            // stopped session, and update/factory refreshes cover output received around attach.
            key(state.sessionId ?: "recovery-terminal-connected") {
                AndroidView(
                    factory = {
                        currentHost.view.also { currentHost.refreshAfterAttachment() }
                    },
                    update = { currentHost.onScreenUpdated() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(RecoveryTerminalPanel)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(RecoveryTerminalPanel),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.harness_recovery_terminal_empty),
                    color = RecoveryTerminalText.copy(alpha = 0.8f)
                )
            }
        }
        RecoveryTerminalControls(
            manager = manager,
            host = host,
            state = state,
            context = context,
            keyboardVisible = keyboardVisible,
            modifierState = modifierState,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun RecoveryTerminalStatusPanel(
    modifier: Modifier,
    text: String,
    progress: Boolean
) {
    Card(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = RecoveryTerminalPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text, color = RecoveryTerminalText)
            if (progress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecoveryTerminalControls(
    manager: HarnessRecoveryTerminalSessionManager,
    host: AgentProotTerminalHost?,
    state: com.example.llamadroid.service.HarnessRecoveryTerminalState,
    context: Context,
    keyboardVisible: Boolean,
    modifierState: HarnessTerminalModifierState,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val copyHint = stringResource(R.string.harness_recovery_terminal_copy_hint)
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!keyboardVisible) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag("harness_recovery_terminal_actions"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_start_proot),
                    enabled = !state.isConnected && !state.isOpening,
                ) {
                    scope.launch { manager.open() }
                }
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_stop_proot),
                    enabled = state.isConnected,
                ) {
                    scope.launch { manager.close() }
                }
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_force_stop_proot),
                    enabled = state.isConnected || state.isOpening,
                ) {
                    scope.launch { manager.forceStop() }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_clear),
                    enabled = state.isConnected,
                ) { manager.clear() }
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_interrupt),
                    enabled = state.isConnected,
                ) { scope.launch { manager.send("\u0003", appendNewline = false) } }
                RecoveryTerminalButton(
                    label = stringResource(R.string.harness_recovery_keyboard),
                    enabled = host != null,
                ) { host?.showKeyboard() }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            "Debian recovery terminal",
                            copyHint
                        )
                    )
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.harness_recovery_copy_hint))
                }
            }
        }
        Text(
            stringResource(R.string.harness_recovery_special_keys),
            style = MaterialTheme.typography.labelMedium,
            color = RecoveryTerminalText.copy(alpha = 0.82f),
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("harness_recovery_terminal_special_keys"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HARNESS_TERMINAL_DEFAULT_KEYS.forEach { id ->
                val modifier = when (id) {
                    "ctrl" -> HarnessTerminalModifier.CTRL
                    "alt" -> HarnessTerminalModifier.ALT
                    "shift" -> HarnessTerminalModifier.SHIFT
                    else -> null
                }
                if (modifier != null) {
                    val mode = when (modifier) {
                        HarnessTerminalModifier.CTRL -> modifierState.ctrl
                        HarnessTerminalModifier.ALT -> modifierState.alt
                        HarnessTerminalModifier.SHIFT -> modifierState.shift
                    }
                    val labelRes = when (modifier) {
                        HarnessTerminalModifier.CTRL -> R.string.harness_terminal_key_ctrl
                        HarnessTerminalModifier.ALT -> R.string.harness_terminal_key_alt
                        HarnessTerminalModifier.SHIFT -> R.string.harness_terminal_key_shift
                    }
                    RecoveryTerminalButton(
                        label = stringResource(labelRes) + when (mode) {
                            HarnessTerminalModifierMode.OFF -> ""
                            HarnessTerminalModifierMode.STICKY -> "*"
                            HarnessTerminalModifierMode.LOCKED -> "!"
                        },
                        enabled = state.isConnected,
                    ) { host?.cycleModifier(modifier) }
                } else {
                    val key = harnessTerminalKeyById(id) ?: return@forEach
                    RecoveryTerminalButton(
                        label = stringResource(key.labelRes),
                        enabled = state.isConnected,
                    ) {
                        val sequence = composeHarnessTerminalKeySequence(
                            key = key,
                            ctrl = modifierState.ctrl != HarnessTerminalModifierMode.OFF,
                            alt = modifierState.alt != HarnessTerminalModifierMode.OFF,
                            shift = modifierState.shift != HarnessTerminalModifierMode.OFF,
                        )
                        scope.launch {
                            manager.send(sequence, appendNewline = false)
                            host?.consumeStickyModifiers()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun recoveryTerminalErrorText(errorCode: String?): String = when (errorCode) {
    "RECOVERY_TERMINAL_EXITED" -> stringResource(R.string.harness_recovery_terminal_exited)
    "RECOVERY_TERMINAL_START_FAILED",
    "RECOVERY_TERMINAL_ENVIRONMENT_UNAVAILABLE",
    "RECOVERY_TERMINAL_LAUNCH_FAILED",
    "RECOVERY_TERMINAL_PTY_FAILED",
    "RECOVERY_TERMINAL_PROCESS_NOT_READY",
    "RECOVERY_TERMINAL_GUEST_SHELL_NOT_READY" ->
        stringResource(R.string.harness_recovery_terminal_start_failed)
    "RECOVERY_TERMINAL_VIEW_FAILED" -> stringResource(R.string.harness_recovery_terminal_view_failed)
    else -> stringResource(R.string.harness_recovery_terminal_disconnected)
}

@Composable
private fun RecoveryTerminalButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Reusable controls for the opt-in SFTP export. The service remains disabled until the switch is
 * enabled and credentials are generated only after the user starts it.
 */
@Composable
fun HarnessRecoveryFileServerPanel(
    modifier: Modifier = Modifier,
    server: HarnessRecoveryFileServer = HarnessRecoveryFileServer.get(LocalContext.current)
) {
    val state by server.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.harness_recovery_file_server_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.harness_recovery_file_server_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = state.isRunning,
                    enabled = !busy && !state.isBusy,
                    onCheckedChange = { enabled ->
                        busy = true
                        scope.launch {
                            if (enabled) server.start() else server.stop()
                            busy = false
                        }
                    }
                )
            }
            TextButton(onClick = { showBrowser = true }) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.harness_rootfs_browse_files))
            }
            if (busy || state.isBusy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    recoveryFileServerPhaseText(state.phase),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.status == HarnessRecoveryFileServerStatus.FAILED) {
                Text(
                    recoveryFileServerErrorText(state.errorCode),
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            server.start()
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.harness_recovery_retry_sftp))
                }
            }
            if (state.isRunning) RecoveryFileServerDetails(state, context, server)
        }
    }
    if (showBrowser) Dialog(
        onDismissRequest = { showBrowser = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            HarnessRootfsFileBrowser(onClose = { showBrowser = false })
        }
    }
}

@Composable
private fun recoveryFileServerPhaseText(phase: HarnessRecoveryFileServerPhase): String = when (phase) {
    HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT ->
        stringResource(R.string.harness_recovery_sftp_phase_preparing)
    HarnessRecoveryFileServerPhase.VALIDATING_ROOTFS ->
        stringResource(R.string.harness_recovery_sftp_phase_validating)
    HarnessRecoveryFileServerPhase.GENERATING_HOST_KEY ->
        stringResource(R.string.harness_recovery_sftp_phase_host_key)
    HarnessRecoveryFileServerPhase.INITIALIZING_SERVICE ->
        stringResource(R.string.harness_recovery_sftp_phase_initializing)
    HarnessRecoveryFileServerPhase.BINDING_SOCKET ->
        stringResource(R.string.harness_recovery_sftp_phase_binding)
    HarnessRecoveryFileServerPhase.VERIFYING_READINESS ->
        stringResource(R.string.harness_recovery_sftp_phase_readiness)
    HarnessRecoveryFileServerPhase.STOPPING ->
        stringResource(R.string.harness_recovery_sftp_phase_stopping)
    HarnessRecoveryFileServerPhase.READY ->
        stringResource(R.string.harness_recovery_sftp_phase_ready)
    HarnessRecoveryFileServerPhase.IDLE ->
        stringResource(R.string.harness_recovery_sftp_phase_idle)
}

@Composable
private fun recoveryFileServerErrorText(errorCode: String?): String = when (errorCode) {
    "RECOVERY_SFTP_ENVIRONMENT_UNAVAILABLE" ->
        stringResource(R.string.harness_recovery_sftp_error_environment)
    "RECOVERY_SFTP_ROOTFS_MISSING", "RECOVERY_SFTP_ROOTFS_UNAVAILABLE" ->
        stringResource(R.string.harness_recovery_sftp_error_rootfs)
    "RECOVERY_SFTP_KEY_FAILED" ->
        stringResource(R.string.harness_recovery_sftp_error_host_key)
    "RECOVERY_SFTP_SERVICE_INIT_FAILED" ->
        stringResource(R.string.harness_recovery_sftp_error_service_init)
    "RECOVERY_SFTP_PORT_UNAVAILABLE", "RECOVERY_SFTP_BIND_FAILED" ->
        stringResource(R.string.harness_recovery_sftp_error_port)
    "RECOVERY_SFTP_READINESS_FAILED" ->
        stringResource(R.string.harness_recovery_sftp_error_readiness)
    "RECOVERY_SFTP_ACCESS_DENIED" ->
        stringResource(R.string.harness_recovery_sftp_error_access)
    "RECOVERY_SFTP_STOP_FAILED" ->
        stringResource(R.string.harness_recovery_sftp_error_stop)
    else -> stringResource(
        R.string.harness_recovery_file_server_failed_with_code,
        errorCode ?: "RECOVERY_SFTP_OPERATION_FAILED",
    )
}

@Composable
private fun RecoveryFileServerDetails(
    state: HarnessRecoveryFileServerState,
    context: Context,
    server: HarnessRecoveryFileServer
) {
    val scope = rememberCoroutineScope()
    val address = state.addresses.firstOrNull()
    Text(stringResource(R.string.harness_recovery_file_server_running))
    Text(
        stringResource(R.string.harness_recovery_file_server_root, state.rootLabel),
        style = MaterialTheme.typography.bodySmall
    )
    RecoveryCredentialRow(
        label = stringResource(R.string.harness_recovery_username),
        value = state.username.orEmpty(),
        context = context
    )
    RecoveryCredentialRow(
        label = stringResource(R.string.harness_recovery_password),
        value = state.password.orEmpty(),
        context = context
    )
    Text(
        stringResource(R.string.harness_recovery_file_server_endpoints),
        style = MaterialTheme.typography.labelLarge
    )
    state.endpoints.forEach { endpoint ->
        Text(endpoint, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (address != null && state.port != null && state.username != null) {
        RecoveryCredentialRow(
            label = stringResource(R.string.harness_recovery_sftp_command),
            value = "sftp -P ${state.port} ${state.username}@$address:/",
            context = context,
        )
        RecoveryCredentialRow(
            label = stringResource(R.string.harness_recovery_scp_command),
            value = "scp -P ${state.port} -r ${state.username}@$address:/ ./proot-rootfs",
            context = context,
        )
    }
    Text(
        stringResource(R.string.harness_recovery_file_server_legacy_scp_note),
        style = MaterialTheme.typography.bodySmall
    )
    TextButton(onClick = { scope.launch { server.refreshAddresses() } }) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.harness_recovery_refresh_addresses))
    }
}

@Composable
private fun RecoveryCredentialRow(label: String, value: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.harness_recovery_copy))
        }
    }
}
