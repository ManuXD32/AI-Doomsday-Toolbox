package com.example.llamadroid.harness

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.service.WorkspaceTerminalUiState

/** Keys supported by the shared guest terminal key bar. */
internal enum class HarnessTerminalKey(
    val id: String,
    @StringRes val labelRes: Int,
) {
    TAB("tab", R.string.harness_terminal_key_tab),
    ESCAPE("escape", R.string.harness_terminal_key_escape),
    UP("up", R.string.harness_terminal_key_up),
    DOWN("down", R.string.harness_terminal_key_down),
    LEFT("left", R.string.harness_terminal_key_left),
    RIGHT("right", R.string.harness_terminal_key_right),
}

enum class HarnessTerminalModifierMode {
    OFF,
    STICKY,
    LOCKED,
}

enum class HarnessTerminalModifier {
    CTRL,
    ALT,
    SHIFT,
}

data class HarnessTerminalModifierState(
    val ctrl: HarnessTerminalModifierMode = HarnessTerminalModifierMode.OFF,
    val alt: HarnessTerminalModifierMode = HarnessTerminalModifierMode.OFF,
    val shift: HarnessTerminalModifierMode = HarnessTerminalModifierMode.OFF,
)

internal val HARNESS_TERMINAL_DEFAULT_KEYS = listOf(
    "ctrl", "alt", "shift", "tab", "escape", "up", "down", "left", "right"
)

internal fun HarnessTerminalModifierMode.next(): HarnessTerminalModifierMode = when (this) {
    HarnessTerminalModifierMode.OFF -> HarnessTerminalModifierMode.STICKY
    HarnessTerminalModifierMode.STICKY -> HarnessTerminalModifierMode.LOCKED
    HarnessTerminalModifierMode.LOCKED -> HarnessTerminalModifierMode.OFF
}

private fun modifierMode(value: String): HarnessTerminalModifierMode =
    runCatching { HarnessTerminalModifierMode.valueOf(value) }
        .getOrDefault(HarnessTerminalModifierMode.OFF)

/**
 * Encodes the same terminal control sequences used by a hardware key bar. The function is pure
 * so the key contract can be tested without a WebView, emulator, or a running Harness process.
 */
internal fun composeHarnessTerminalKeySequence(
    key: HarnessTerminalKey,
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
): String {
    if (key == HarnessTerminalKey.ESCAPE) return "\u001b"
    if (key == HarnessTerminalKey.TAB && !ctrl && !alt && !shift) return "\t"
    if (key == HarnessTerminalKey.TAB && shift && !ctrl && !alt) return "\u001b[Z"
    val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    if (key == HarnessTerminalKey.TAB) return "\u001b[27;${modifier};9~"
    val suffix = when (key) {
        HarnessTerminalKey.UP -> "A"
        HarnessTerminalKey.DOWN -> "B"
        HarnessTerminalKey.RIGHT -> "C"
        HarnessTerminalKey.LEFT -> "D"
        HarnessTerminalKey.ESCAPE, HarnessTerminalKey.TAB -> error("Handled above")
    }
    return if (modifier == 1) "\u001b[$suffix" else "\u001b[1;${modifier}${suffix}"
}

internal fun harnessTerminalKeyById(id: String): HarnessTerminalKey? =
    HarnessTerminalKey.entries.firstOrNull { it.id == id }

internal fun composeHarnessTerminalText(value: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String =
    buildString {
        value.forEach { original ->
            val char = if (shift) original.uppercaseChar() else original
            if (alt) append('\u001b')
            append(if (ctrl) when (char.uppercaseChar()) {
                in 'A'..'Z' -> (char.uppercaseChar().code - 'A'.code + 1).toChar()
                ' ', '@' -> '\u0000'
                '[' -> '\u001b'
                '\\' -> '\u001c'
                ']' -> '\u001d'
                '^' -> '\u001e'
                '_' -> '\u001f'
                '?' -> '\u007f'
                else -> char
            } else char)
        }
    }

/**
 * Dedicated shared-guest terminal surface. The parent can place it in a tab or dialog and keep
 * all process, approval, and policy decisions in [HarnessTerminals].
 */
@Composable
fun HarnessTerminalScreen(
    workspaceRoot: String,
    state: WorkspaceTerminalUiState?,
    modifier: Modifier = Modifier,
    sessions: List<WorkspaceTerminalUiState> = listOfNotNull(state),
    allowMultipleSessions: Boolean = true,
    canCreateSession: Boolean = true,
    /** Optional native Termux surface backed by the DSH follow/write transport. */
    transport: HarnessTermuxTransportAdapter? = null,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onSpecialKey: (String) -> Unit = {},
    onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    managementContent: @Composable () -> Unit = {},
) {
    val terminalStateKey = state?.sessionId ?: workspaceRoot
    var input by remember(terminalStateKey) { mutableStateOf("") }
    var selectedKeyIds by rememberSaveable(workspaceRoot) {
        mutableStateOf(HARNESS_TERMINAL_DEFAULT_KEYS.joinToString(","))
    }
    var ctrlModeName by rememberSaveable(workspaceRoot) { mutableStateOf(HarnessTerminalModifierMode.OFF.name) }
    var altModeName by rememberSaveable(workspaceRoot) { mutableStateOf(HarnessTerminalModifierMode.OFF.name) }
    var shiftModeName by rememberSaveable(workspaceRoot) { mutableStateOf(HarnessTerminalModifierMode.OFF.name) }
    var showKeyConfiguration by remember { mutableStateOf(false) }
    var fontSize by rememberSaveable(workspaceRoot) {
        mutableStateOf(HARNESS_TERMINAL_DEFAULT_FONT_SIZE_SP)
    }
    var columns by rememberSaveable(terminalStateKey) { mutableStateOf(80) }
    var rows by rememberSaveable(terminalStateKey) { mutableStateOf(24) }
    val ctrlMode = modifierMode(ctrlModeName)
    val altMode = modifierMode(altModeName)
    val shiftMode = modifierMode(shiftModeName)
    val transcriptScroll = rememberScrollState()
    val transcriptHorizontal = rememberScrollState()
    val contextScroll = rememberScrollState()
    val controlsScroll = rememberScrollState()
    val transcript = state?.transcript.orEmpty()
    val selectedKeys = selectedKeyIds.split(',').filter { it.isNotBlank() }
    val nativeConnected = transport != null && state?.isConnected == true
    val nativeModifierState = HarnessTerminalModifierState(ctrlMode, altMode, shiftMode)
    // AppScreenScaffold owns the actual IME inset. The native transport also observes the
    // platform visibility directly because edge-to-edge Android 15 windows can consume the
    // Compose IME value before this surface reads it.
    val transportImeVisible = transport?.imeVisible?.collectAsState()?.value ?: false
    val keyboardVisible = transportImeVisible || WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun sendInput() {
        val value = input
        if (value.isNotEmpty()) {
            val modified = composeHarnessTerminalText(value,
                ctrlMode != HarnessTerminalModifierMode.OFF,
                altMode != HarnessTerminalModifierMode.OFF,
                shiftMode != HarnessTerminalModifierMode.OFF)
            if (ctrlMode != HarnessTerminalModifierMode.OFF || altMode != HarnessTerminalModifierMode.OFF) {
                onSpecialKey(modified)
            } else onSend(modified)
            if (ctrlMode == HarnessTerminalModifierMode.STICKY) ctrlModeName = HarnessTerminalModifierMode.OFF.name
            if (altMode == HarnessTerminalModifierMode.STICKY) altModeName = HarnessTerminalModifierMode.OFF.name
            if (shiftMode == HarnessTerminalModifierMode.STICKY) shiftModeName = HarnessTerminalModifierMode.OFF.name
            input = ""
        }
    }

    fun sendKey(key: HarnessTerminalKey) {
        onSpecialKey(composeHarnessTerminalKeySequence(
            key = key,
            ctrl = ctrlMode != HarnessTerminalModifierMode.OFF,
            alt = altMode != HarnessTerminalModifierMode.OFF,
            shift = shiftMode != HarnessTerminalModifierMode.OFF,
        ))
        if (ctrlMode == HarnessTerminalModifierMode.STICKY) ctrlModeName = HarnessTerminalModifierMode.OFF.name
        if (altMode == HarnessTerminalModifierMode.STICKY) altModeName = HarnessTerminalModifierMode.OFF.name
        if (shiftMode == HarnessTerminalModifierMode.STICKY) shiftModeName = HarnessTerminalModifierMode.OFF.name
    }

    fun resize(deltaColumns: Int, deltaRows: Int) {
        columns = (columns + deltaColumns).coerceIn(2, 500)
        rows = (rows + deltaRows).coerceIn(1, 200)
        onResize(columns, rows)
    }

    // AppScreenScaffold owns the single IME inset for the project tab. Keep this surface
    // explicitly shrinkable so the native PTY cannot retain an unconstrained height when the
    // keyboard opens; the standalone dialog supplies its own bounded window.
    Surface(modifier = modifier.fillMaxSize().heightIn(min = 0.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 0.dp)
                .padding(if (transport != null) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (transport != null) 4.dp else 8.dp),
        ) {
            // Keep project/session metadata available without allowing it to consume the
            // terminal viewport. When the IME is open this region becomes a compact scroll owner;
            // the terminal and its keyboard input keep the rest of the resized page.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (keyboardVisible) 56.dp else 168.dp)
                    .verticalScroll(contextScroll)
                    .testTag("harness_terminal_context"),
                verticalArrangement = Arrangement.spacedBy(if (transport != null) 4.dp else 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.agent_proot_terminal_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state?.workspaceRoot ?: workspaceRoot,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (!nativeConnected) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(stringResource(R.string.harness_terminal_shared_guest), maxLines = 1)
                            },
                        )
                    }
                }

                if (allowMultipleSessions && !(keyboardVisible && transport != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        sessions.forEachIndexed { index, session ->
                            FilterChip(
                                selected = session.sessionId == state?.sessionId,
                                onClick = { onSelectSession(session.sessionId) },
                                label = {
                                    Text(
                                        session.displayName.ifBlank {
                                            stringResource(R.string.agent_proot_terminal_session_number, index + 1)
                                        },
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                        OutlinedButton(onClick = onNewSession, enabled = canCreateSession) {
                            Text(stringResource(R.string.agent_proot_terminal_new_session))
                        }
                    }
                }

                if (!(keyboardVisible && transport != null)) managementContent()

                if (!nativeConnected || !state?.errorMessage.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val status = when {
                            state?.isConnecting == true -> R.string.agent_workspace_terminal_status_connecting
                            state?.isConnected == true -> R.string.agent_workspace_terminal_status_connected
                            else -> R.string.agent_workspace_terminal_status_disconnected
                        }
                        AssistChip(onClick = {}, label = { Text(stringResource(status)) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state?.errorMessage ?: stringResource(R.string.agent_workspace_terminal_empty),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }

            if (transport != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .heightIn(min = 0.dp)
                        .testTag("harness_terminal_viewport"),
                ) {
                    HarnessTermuxTransportSurface(
                        transport = transport,
                        fontSizeSp = fontSize,
                        modifierState = nativeModifierState,
                        onStickyModifierConsumed = { modifier ->
                            when (modifier) {
                                HarnessTerminalModifier.CTRL -> {
                                    if (ctrlMode == HarnessTerminalModifierMode.STICKY) {
                                        ctrlModeName = HarnessTerminalModifierMode.OFF.name
                                    }
                                }
                                HarnessTerminalModifier.ALT -> {
                                    if (altMode == HarnessTerminalModifierMode.STICKY) {
                                        altModeName = HarnessTerminalModifierMode.OFF.name
                                    }
                                }
                                HarnessTerminalModifier.SHIFT -> {
                                    if (shiftMode == HarnessTerminalModifierMode.STICKY) {
                                        shiftModeName = HarnessTerminalModifierMode.OFF.name
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .heightIn(min = 0.dp)
                        .horizontalScroll(transcriptHorizontal)
                        .verticalScroll(transcriptScroll)
                        .padding(8.dp),
                ) {
                    Text(
                        text = transcript.ifEmpty { stringResource(R.string.agent_workspace_terminal_empty) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                LaunchedEffect(transcript) {
                    transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
                }
            }

            // Controls get their own bounded scroll owner. This keeps the native terminal at the
            // top of the resized viewport when the IME is open while retaining every action.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        max = if (keyboardVisible && transport != null) 92.dp else 196.dp
                    )
                    .verticalScroll(controlsScroll)
                    .testTag("harness_terminal_controls"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!(keyboardVisible && transport != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onReconnect, enabled = state != null) {
                            Text(stringResource(R.string.agent_workspace_terminal_reconnect))
                        }
                        OutlinedButton(onClick = onInterrupt, enabled = state?.isConnected == true) {
                            Text(stringResource(R.string.agent_workspace_terminal_interrupt))
                        }
                        if (transport == null) {
                            OutlinedButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                        }
                        IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) }) { Text("A−") }
                        Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(24f) }) { Text("A+") }
                        if (transport == null) {
                            Text(stringResource(R.string.harness_terminal_resize), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { resize(-10, -2) }) { Text("−") }
                            Text(stringResource(R.string.harness_terminal_dimensions, columns, rows), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { resize(10, 2) }) { Text("+") }
                        }
                        Button(
                            onClick = onStop,
                            enabled = state != null,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.action_stop)) }
                    }
                }

                Text(
                    stringResource(R.string.harness_terminal_keybar),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selectedKeys.forEach { id ->
                        val key = harnessTerminalKeyById(id)
                        val isCtrl = id == "ctrl"
                        val isAlt = id == "alt"
                        val isShift = id == "shift"
                        if (isCtrl || isAlt || isShift) {
                            val mode = when {
                                isCtrl -> ctrlMode
                                isAlt -> altMode
                                else -> shiftMode
                            }
                            val labelRes = when {
                                isCtrl -> R.string.harness_terminal_key_ctrl
                                isAlt -> R.string.harness_terminal_key_alt
                                else -> R.string.harness_terminal_key_shift
                            }
                            FilterChip(
                                selected = mode != HarnessTerminalModifierMode.OFF,
                                onClick = {
                                    when {
                                        isCtrl -> ctrlModeName = mode.next().name
                                        isAlt -> altModeName = mode.next().name
                                        else -> shiftModeName = mode.next().name
                                    }
                                },
                                label = {
                                    Text(stringResource(labelRes) + when (mode) {
                                        HarnessTerminalModifierMode.OFF -> ""
                                        HarnessTerminalModifierMode.STICKY -> "*"
                                        HarnessTerminalModifierMode.LOCKED -> "!"
                                    })
                                },
                            )
                        } else {
                            key?.let {
                                FilterChip(
                                    selected = false,
                                    onClick = { sendKey(it) },
                                    label = { Text(stringResource(it.labelRes)) },
                                )
                            }
                        }
                    }
                    if (selectedKeys.isEmpty()) {
                        Text(stringResource(R.string.harness_terminal_keybar_empty))
                    }
                    OutlinedButton(onClick = { showKeyConfiguration = true }) {
                        Text(stringResource(R.string.harness_terminal_configure_keys))
                    }
                }

                if (transport == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(64 * 1024) },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.agent_workspace_terminal_input_label)) },
                            placeholder = { Text(stringResource(R.string.agent_workspace_terminal_input_placeholder)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { sendInput() }),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { sendInput() }, enabled = state?.isConnected == true) {
                            Text(stringResource(R.string.harness_terminal_send))
                        }
                    }
                }
            }
        }
    }

    if (showKeyConfiguration) {
        var draftIds by remember(showKeyConfiguration, selectedKeyIds) {
            mutableStateOf(selectedKeyIds.split(',').filter { it.isNotBlank() }.toSet())
        }
        AlertDialog(
            onDismissRequest = { showKeyConfiguration = false },
            title = { Text(stringResource(R.string.harness_terminal_configure_keys_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    HARNESS_TERMINAL_DEFAULT_KEYS.forEach { id ->
                        val key = harnessTerminalKeyById(id)
                        val label = when (id) {
                            "ctrl" -> stringResource(R.string.harness_terminal_key_ctrl)
                            "alt" -> stringResource(R.string.harness_terminal_key_alt)
                            "shift" -> stringResource(R.string.harness_terminal_key_shift)
                            else -> key?.let { stringResource(it.labelRes) }.orEmpty()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id in draftIds,
                                onCheckedChange = { checked ->
                                    draftIds = if (checked) draftIds + id else draftIds - id
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedKeyIds = HARNESS_TERMINAL_DEFAULT_KEYS.filter { it in draftIds }.joinToString(",")
                    showKeyConfiguration = false
                }) { Text(stringResource(R.string.harness_terminal_save_keys)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyConfiguration = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
