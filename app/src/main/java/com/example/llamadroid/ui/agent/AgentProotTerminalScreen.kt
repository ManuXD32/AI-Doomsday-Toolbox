package com.example.llamadroid.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentWorkspaceBackendType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ProotTerminalBackground = Color(0xFF0D100E)
private val ProotTerminalPanel = Color(0xFF151A16)
private val ProotTerminalOutline = Color(0xFF334038)
private val ProotTerminalText = Color(0xFFD7FFE0)
private val ProotTerminalAccent = Color(0xFF72F58A)
private val ProotTerminalMuted = Color(0xFF9AA99E)

/** Full-screen, user-owned PTY terminal; Agent command output remains in the workspace Run tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProotTerminalScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val agentService = remember { AgentForegroundService.getAgentService(context) }
    val currentProjectFolder by AgentService.currentProjectFolder.collectAsState()
    val activeConversationId by AgentService.activeConversationId.collectAsState()
    val preferredConversationId by AgentService.preferredConversationId.collectAsState()
    val workspaceBackend by AgentService.currentWorkspaceBackend.collectAsState()
    val terminalStates by agentService.prootTerminalStates.collectAsState()
    val conversationId = remember(preferredConversationId, activeConversationId) {
        resolveWorkspaceConversationAnchor(preferredConversationId, activeConversationId)
    }
    val sessions = conversationId?.let { terminalStates[it] }.orEmpty()
    var selectedSessionId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var isOpening by rememberSaveable(conversationId) { mutableStateOf(false) }
    var startupAttempted by rememberSaveable(conversationId) { mutableStateOf(false) }
    var screenError by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var controlEnabled by rememberSaveable(selectedSessionId) { mutableStateOf(false) }
    var altEnabled by rememberSaveable(selectedSessionId) { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val selectedSession = sessions.firstOrNull { it.sessionId == selectedSessionId }
        ?: sessions.lastOrNull()
    val terminalSession = selectedSession?.takeIf { it.isConnected }?.sessionId
        ?.let(agentService::getProotTerminalSession)
    val terminalHost = remember(selectedSession?.sessionId, terminalSession) {
        val sessionId = selectedSession?.sessionId
        terminalSession?.let { terminal ->
            runCatching {
                AgentProotTerminalHost(
                    context = context,
                    terminalSession = terminal,
                    onInputActivity = { sessionId?.let(agentService::touchProotTerminalSession) },
                    onModifierStateChanged = { control, alt ->
                        controlEnabled = control
                        altEnabled = alt
                    }
                )
            }.onFailure { error ->
                screenError = resources.getString(
                    R.string.agent_proot_terminal_view_failed,
                    error.javaClass.simpleName
                )
            }.getOrNull()
        }
    }

    fun openNewSession() {
        val anchor = conversationId ?: return
        val folder = currentProjectFolder.orEmpty()
        if (folder.isBlank() || isOpening) return
        isOpening = true
        screenError = null
        scope.launch {
            agentService.openProotWorkspaceTerminal(anchor, folder)
                .onSuccess { selectedSessionId = it.sessionId }
                .onFailure { _ ->
                    screenError = resources.getString(
                        R.string.agent_proot_terminal_start_failed,
                        "TERMINAL_START_FAILED"
                    )
                }
            isOpening = false
        }
    }

    fun sendSequence(sequence: String) {
        val sessionId = selectedSession?.sessionId ?: return
        scope.launch {
            agentService.sendProotWorkspaceTerminalInput(sessionId, sequence, appendNewline = false)
                .onFailure { _ ->
                    screenError = resources.getString(
                        R.string.agent_proot_terminal_input_failed,
                        "TERMINAL_INPUT_FAILED"
                    )
                }
        }
    }

    LaunchedEffect(conversationId, currentProjectFolder, workspaceBackend) {
        if (!startupAttempted &&
            workspaceBackend == AgentWorkspaceBackendType.LOCAL_PROOT &&
            conversationId != null &&
            !currentProjectFolder.isNullOrBlank()
        ) {
            startupAttempted = true
            if (sessions.isEmpty()) openNewSession()
        }
    }

    LaunchedEffect(sessions.map { it.sessionId }, selectedSessionId) {
        if (sessions.none { it.sessionId == selectedSessionId }) {
            selectedSessionId = sessions.lastOrNull()?.sessionId
        }
    }

    DisposableEffect(selectedSession?.sessionId, terminalHost) {
        val sessionId = selectedSession?.sessionId
        if (sessionId != null && terminalHost != null) {
            agentService.setProotTerminalScreenListener(sessionId, terminalHost::onScreenUpdated)
            terminalHost.setVisible(true)
        }
        onDispose {
            if (sessionId != null) agentService.setProotTerminalScreenListener(sessionId, null)
            terminalHost?.setVisible(false)
        }
    }

    LaunchedEffect(terminalHost) {
        if (terminalHost != null) {
            delay(180)
            terminalHost.showKeyboard()
        }
    }

    Scaffold(
        containerColor = ProotTerminalBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ProotTerminalBackground,
                    titleContentColor = ProotTerminalText,
                    navigationIconContentColor = ProotTerminalText,
                    actionIconContentColor = ProotTerminalAccent
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(
                            stringResource(R.string.agent_proot_terminal_title),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.agent_proot_terminal_screen_subtitle),
                            color = ProotTerminalMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { terminalHost?.showKeyboard() },
                        enabled = terminalHost != null
                    ) {
                        Icon(Icons.Default.Keyboard, stringResource(R.string.agent_proot_terminal_keyboard))
                    }
                    if (isOpening) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 14.dp).size(22.dp),
                            color = ProotTerminalAccent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = ::openNewSession,
                            enabled = workspaceBackend == AgentWorkspaceBackendType.LOCAL_PROOT
                        ) {
                            Icon(Icons.Default.Add, stringResource(R.string.agent_proot_terminal_new_session))
                        }
                    }
                    IconButton(
                        onClick = {
                            selectedSession?.sessionId?.let { sessionId ->
                                scope.launch {
                                    agentService.closeProotWorkspaceTerminal(sessionId)
                                    selectedSessionId = null
                                }
                            }
                        },
                        enabled = selectedSession != null
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.agent_proot_terminal_close_session))
                    }
                }
            )
        }
    ) { innerPadding ->
        if (workspaceBackend != AgentWorkspaceBackendType.LOCAL_PROOT ||
            conversationId == null || currentProjectFolder.isNullOrBlank()
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.agent_proot_terminal_no_project),
                    color = ProotTerminalText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Scaffold
        }

        AgentProotTerminalViewport(
            innerPadding = innerPadding,
            keyboardVisible = keyboardVisible,
            sessions = sessions,
            selectedSession = selectedSession,
            isOpening = isOpening,
            terminalHost = terminalHost,
            screenError = screenError,
            controlEnabled = controlEnabled,
            altEnabled = altEnabled,
            onSelectSession = { selectedSessionId = it },
            onNewSession = ::openNewSession,
            onControl = { controlEnabled = terminalHost?.toggleControl() == true },
            onAlt = { altEnabled = terminalHost?.toggleAlt() == true },
            onSequence = ::sendSequence
        )
    }
}

/** Production terminal viewport shared with bounded IME/layout regression coverage. */
@Composable
fun AgentProotTerminalViewport(
    innerPadding: PaddingValues,
    keyboardVisible: Boolean,
    sessions: List<com.example.llamadroid.service.WorkspaceTerminalUiState>,
    selectedSession: com.example.llamadroid.service.WorkspaceTerminalUiState?,
    isOpening: Boolean,
    terminalHost: AgentProotTerminalHost?,
    screenError: String?,
    controlEnabled: Boolean,
    altEnabled: Boolean,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onControl: () -> Unit,
    onAlt: () -> Unit,
    onSequence: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .imePadding()
            .background(ProotTerminalBackground)
    ) {
        // The IME owns the bottom inset here. Secondary chrome collapses while it is open so the
        // weighted PTY viewport remains usable above the keyboard on small displays.
        if (!keyboardVisible) {
            SessionStrip(
                sessions = sessions,
                selectedSessionId = selectedSession?.sessionId,
                isOpening = isOpening,
                onSelect = onSelectSession,
                onNew = onNewSession
            )
            Text(
                text = selectedSession?.let {
                    stringResource(
                        if (it.isConnected) R.string.agent_workspace_terminal_status_connected
                        else R.string.agent_workspace_terminal_status_disconnected
                    ) + " · /workspace"
                } ?: stringResource(R.string.agent_proot_terminal_background_note),
                color = if (selectedSession?.isConnected == true) ProotTerminalAccent else ProotTerminalMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
            )
            HorizontalDivider(color = ProotTerminalOutline)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .testTag("agent_proot_terminal_viewport")
        ) {
            if (terminalHost != null) {
                AndroidView(
                    factory = { terminalHost.view },
                    update = { it.onScreenUpdated() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = when {
                        isOpening -> stringResource(R.string.agent_proot_terminal_connecting_body)
                        selectedSession?.errorMessage != null -> selectedSession.errorMessage.orEmpty()
                        else -> stringResource(R.string.agent_workspace_terminal_empty)
                    },
                    color = ProotTerminalMuted,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center).padding(20.dp)
                )
            }
        }

        if (!keyboardVisible) {
            screenError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().background(ProotTerminalPanel).padding(8.dp)
                )
            }
        }

        TerminalExtraKeysRow(
            enabled = terminalHost != null && selectedSession?.isConnected == true,
            controlEnabled = controlEnabled,
            altEnabled = altEnabled,
            onControl = onControl,
            onAlt = onAlt,
            onSequence = onSequence
        )
    }
}

@Composable
private fun SessionStrip(
    sessions: List<com.example.llamadroid.service.WorkspaceTerminalUiState>,
    selectedSessionId: String?,
    isOpening: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEachIndexed { index, session ->
            val selected = session.sessionId == selectedSessionId
            Surface(
                color = if (selected) ProotTerminalOutline else ProotTerminalPanel,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 48.dp).clickable { onSelect(session.sessionId) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (session.isConnected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (session.isConnected) ProotTerminalAccent else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.agent_proot_terminal_session_number, index + 1),
                        color = ProotTerminalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
        Surface(
            color = ProotTerminalPanel,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = !isOpening, onClick = onNew)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = ProotTerminalAccent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.agent_proot_terminal_new_session),
                    color = ProotTerminalText,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TerminalExtraKeysRow(
    enabled: Boolean,
    controlEnabled: Boolean,
    altEnabled: Boolean,
    onControl: () -> Unit,
    onAlt: () -> Unit,
    onSequence: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(ProotTerminalPanel).padding(horizontal = 6.dp, vertical = 5.dp)
            .testTag("agent_proot_terminal_extra_keys"),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TerminalKey("CTRL", enabled, controlEnabled, onControl)
        TerminalKey("ALT", enabled, altEnabled, onAlt)
        prootTerminalExtraKeys().forEach { key ->
            TerminalKey(key.label, enabled, false) { onSequence(key.sequence) }
        }
    }
}

@Composable
private fun TerminalKey(label: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) ProotTerminalAccent else ProotTerminalOutline,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else ProotTerminalText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
        )
    }
}
