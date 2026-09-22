package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The retained explorer opens the same terminal identities seen by the original Web UI. */
@Composable
fun HarnessTerminalDialog(conversationId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { HarnessAppRuntime.get(context) }
    val repository = runtime.terminals
    val scope = rememberCoroutineScope()
    val all by repository.states.collectAsState()
    var sessionId by remember(conversationId) { mutableStateOf<String?>(null) }
    var selected by remember(conversationId) { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showShells by remember { mutableStateOf(false) }
    var shells by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val sessions = sessionId?.let(all::get).orEmpty()
    val current = sessions.firstOrNull { it.sessionId == selected } ?: sessions.lastOrNull()
    fun perform(block: suspend () -> Unit) { scope.launch {
        try { failed = false; block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failed = true; runtime.diagnostics.event(sessionId, "terminal_action", "FAILED", errorCode = error.javaClass.simpleName) }
    } }
    val nativeTransport = remember(sessionId, current?.sessionId) {
        HarnessTermuxTransportAdapter(
            initialScreen = current?.transcript.orEmpty(),
            onWrite = { data ->
                val owner = sessionId
                val terminal = current
                if (owner != null && terminal != null) {
                    repository.send(owner, terminal.sessionId, data, newline = false)
                } else {
                    error("TERMINAL_NOT_SELECTED")
                }
            },
            onResize = { columns, rows ->
                val owner = sessionId
                val terminal = current
                if (owner != null && terminal != null) {
                    perform { repository.resize(owner, terminal.sessionId, columns, rows) }
                }
            },
            onFailure = { error ->
                scope.launch {
                    failed = true
                    runtime.diagnostics.event(
                        sessionId,
                        "terminal_transport",
                        "FAILED",
                        errorCode = error.javaClass.simpleName,
                    )
                }
            },
        )
    }
    nativeTransport.setOnResnapshotRequested {
        val owner = sessionId
        val terminal = current
        if (owner != null && terminal != null) {
            repository.attach(owner, terminal.sessionId, reconnect = true, transport = nativeTransport)
        }
    }
    LaunchedEffect(conversationId) {
        try {
            sessionId = requireNotNull(runtime.database.harnessDao().sessionForConversation(conversationId)).harnessSessionId
            while (isActive) {
                try { repository.refresh(requireNotNull(sessionId)); failed = false }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failed = true }
                delay(3_000)
            }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { failed = true }
    }
    LaunchedEffect(current?.sessionId, sessionId) {
        current?.let { terminal -> sessionId?.let { repository.attach(it, terminal.sessionId, transport = nativeTransport) } }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Card(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            HarnessTerminalScreen(
                workspaceRoot = current?.workspaceRoot.orEmpty(),
                state = current,
                sessions = sessions,
                allowMultipleSessions = true,
                transport = nativeTransport.takeIf { current != null },
                onSend = { input -> current?.let { perform { repository.send(requireNotNull(sessionId), it.sessionId, input) } } },
                onSpecialKey = { input -> current?.let { perform { repository.send(requireNotNull(sessionId), it.sessionId, input, false) } } },
                onInterrupt = { current?.let { perform { repository.send(requireNotNull(sessionId), it.sessionId, "\u0003", false) } } },
                onClear = { current?.let { repository.clear(requireNotNull(sessionId), it.sessionId) } },
                onStop = { current?.let { perform { repository.close(requireNotNull(sessionId), it.sessionId) } } },
                onReconnect = { perform {
                    val id = requireNotNull(sessionId)
                    repository.refresh(id)
                    current?.let { repository.attach(id, it.sessionId, reconnect = true, transport = nativeTransport) }
                } },
                onNewSession = { perform { shells = repository.shells(requireNotNull(sessionId)); showShells = true } },
                onSelectSession = { selected = it },
                managementContent = {
                    TextButton(onClick = { showOptions = true }, enabled = current != null) {
                        Text(stringResource(R.string.harness_terminal_options))
                    }
                    if (failed) Text(stringResource(R.string.harness_terminal_failed))
                },
            )
        }
    }
    if (showShells) AlertDialog(onDismissRequest = { showShells = false }, title = { Text(stringResource(R.string.harness_terminal_choose_shell)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            shells.forEach { shell -> TextButton(onClick = { perform {
                selected = repository.create(requireNotNull(sessionId), shell.getValue("path").jsonPrimitive.content)
                showShells = false
            } }, modifier = Modifier.fillMaxWidth()) { Text(shell.getValue("name").jsonPrimitive.content) } }
        }
    }, confirmButton = { TextButton(onClick = { showShells = false }) { Text(stringResource(R.string.harness_runtime_close)) } })
    if (showOptions && current != null) {
        val terminal = current
        var title by remember(terminal.sessionId) { mutableStateOf(terminal.displayName) }
        var cols by remember(terminal.sessionId) { mutableStateOf("80") }
        var rows by remember(terminal.sessionId) { mutableStateOf("24") }
        AlertDialog(onDismissRequest = { showOptions = false }, title = { Text(stringResource(R.string.harness_terminal_options)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text(stringResource(R.string.harness_terminal_name)) })
                OutlinedTextField(cols, { cols = it }, label = { Text(stringResource(R.string.harness_terminal_columns)) })
                OutlinedTextField(rows, { rows = it }, label = { Text(stringResource(R.string.harness_terminal_rows)) })
            }
        }, confirmButton = { TextButton(onClick = { perform {
            repository.rename(requireNotNull(sessionId), terminal.sessionId, title)
            repository.resize(requireNotNull(sessionId), terminal.sessionId, requireNotNull(cols.toIntOrNull()), requireNotNull(rows.toIntOrNull()))
            showOptions = false
        } }, enabled = title.isNotBlank() && cols.toIntOrNull()?.let { it in 2..500 } == true && rows.toIntOrNull()?.let { it in 1..200 } == true) { Text(stringResource(R.string.action_done)) } },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

/**
 * Route-owned terminal slot for NativeHarnessScreen's Terminal tab. It keeps the native
 * terminal identities in the same repository as the dialog and does not create a second PTY
 * implementation. A null session selects the first workspace returned by the shared guest.
 */
@Composable
fun HarnessTerminalRoute(
    runtime: HarnessAppRuntime,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    val repository = runtime.terminals
    val all by repository.states.collectAsState()
    // A null selection means the project has no session. Never borrow another project's PTY.
    val activeSessionId = sessionId
    val scope = rememberCoroutineScope()
    var selected by remember(activeSessionId) { mutableStateOf<String?>(null) }
    var failed by remember(activeSessionId) { mutableStateOf(false) }
    val sessions = activeSessionId?.let(all::get).orEmpty()
    val current = sessions.firstOrNull { it.sessionId == selected } ?: sessions.lastOrNull()

    fun perform(block: suspend () -> Unit) {
        scope.launch {
            try {
                failed = false
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failed = true
                runtime.diagnostics.event(activeSessionId, "terminal_action", "FAILED", errorCode = error.javaClass.simpleName)
            }
        }
    }

    val nativeTransport = remember(activeSessionId, current?.sessionId) {
        HarnessTermuxTransportAdapter(
            initialScreen = current?.transcript.orEmpty(),
            onWrite = { data ->
                val owner = activeSessionId
                val terminal = current
                if (owner != null && terminal != null) {
                    repository.send(owner, terminal.sessionId, data, newline = false)
                } else {
                    error("TERMINAL_NOT_SELECTED")
                }
            },
            onResize = { columns, rows ->
                val owner = activeSessionId
                val terminal = current
                if (owner != null && terminal != null) {
                    perform { repository.resize(owner, terminal.sessionId, columns, rows) }
                }
            },
            onFailure = { error ->
                scope.launch {
                    failed = true
                    runtime.diagnostics.event(
                        activeSessionId,
                        "terminal_transport",
                        "FAILED",
                        errorCode = error.javaClass.simpleName,
                    )
                }
            },
        )
    }
    nativeTransport.setOnResnapshotRequested {
        val owner = activeSessionId
        val terminal = current
        if (owner != null && terminal != null) {
            repository.attach(owner, terminal.sessionId, reconnect = true, transport = nativeTransport)
        }
    }

    LaunchedEffect(activeSessionId) {
        val id = activeSessionId ?: return@LaunchedEffect
        while (isActive) {
            try {
                repository.refresh(id)
                failed = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
            delay(3_000)
        }
    }
    LaunchedEffect(activeSessionId, current?.sessionId) {
        val id = activeSessionId
        current?.let { terminal -> if (id != null) repository.attach(id, terminal.sessionId, transport = nativeTransport) }
    }

    HarnessTerminalScreen(
        workspaceRoot = current?.workspaceRoot.orEmpty(),
        state = current,
        sessions = sessions,
        transport = nativeTransport.takeIf { current != null },
        canCreateSession = activeSessionId != null,
        modifier = modifier,
        onSend = { input -> current?.let { terminal -> perform { repository.send(requireNotNull(activeSessionId), terminal.sessionId, input) } } },
        onSpecialKey = { input -> current?.let { terminal -> perform { repository.send(requireNotNull(activeSessionId), terminal.sessionId, input, false) } } },
        onInterrupt = { current?.let { terminal -> perform { repository.send(requireNotNull(activeSessionId), terminal.sessionId, "\u0003", false) } } },
        onClear = { current?.let { repository.clear(requireNotNull(activeSessionId), it.sessionId) } },
        onStop = { current?.let { terminal -> perform { repository.close(requireNotNull(activeSessionId), terminal.sessionId) } } },
        onReconnect = { perform {
            val id = requireNotNull(activeSessionId)
            repository.refresh(id)
            current?.let { repository.attach(id, it.sessionId, reconnect = true, transport = nativeTransport) }
        } },
        onNewSession = { activeSessionId?.let { id -> perform { selected = repository.create(id) } } },
        onSelectSession = { selected = it },
        onResize = { columns, rows ->
            current?.let { terminal ->
                perform { repository.resize(requireNotNull(activeSessionId), terminal.sessionId, columns, rows) }
            }
        },
        managementContent = {
            if (activeSessionId == null) Text(stringResource(R.string.harness_terminal_choose_session))
            if (failed) Text(stringResource(R.string.harness_terminal_failed))
        },
    )
}
