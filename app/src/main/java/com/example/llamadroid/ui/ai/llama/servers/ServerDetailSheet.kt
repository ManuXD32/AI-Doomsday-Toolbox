package com.example.llamadroid.ui.ai.llama.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLlamaServerEngine
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOG_TAIL_LINES = 300
private const val LOG_POLL_MS = 1_000L
private const val MAX_LISTED_CHATS = 20
private const val MAX_TITLES_IN_DELETE = 6

/**
 * Detail sheet for one server, looked up live by [serverKey] so status and progress keep updating.
 * The sheet dismisses itself when the server disappears (deleted, converted).
 *
 * @param onEdit open the editor for this server (the host owns the editor sheet)
 * @param onOpenChat a chat bound to this server was tapped
 * @param onOpenWebUi optional; shown for running local servers
 * @param onUpgraded called after a legacy row was converted to a managed server
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailSheet(
    serverKey: String,
    viewModel: ServerRegistryViewModel,
    onDismiss: () -> Unit,
    onEdit: (UnifiedServer) -> Unit,
    onOpenChat: (chatId: Long, rowId: Long) -> Unit,
    onOpenWebUi: ((port: Int) -> Unit)? = null,
    onUpgraded: () -> Unit = {}
) {
    val servers by viewModel.servers.collectAsState()
    val actions by viewModel.actions.collectAsState()
    val server = servers.firstOrNull { it.key == serverKey }
    var seen by remember(serverKey) { mutableStateOf(false) }
    LaunchedEffect(server != null) {
        if (server != null) seen = true else if (seen) onDismiss()
    }
    if (server == null) return
    val action = actions[server.key]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boundChats by produceState(emptyList<LlamaChatEntity>(), server.rowId, server.boundChatCount) {
        value = viewModel.boundChats(server)
    }
    var logsExpanded by remember(serverKey) { mutableStateOf(false) }
    var showDelete by remember(serverKey) { mutableStateOf(false) }
    var showUpgrade by remember(serverKey) { mutableStateOf(false) }
    var showPrepareCaches by remember(serverKey) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ServerSheetHeader(server)
            ServerStatusBlock(server, action, viewModel)

            if (server.status == UnifiedServerStatus.RUNNING && server.kind != UnifiedServerKind.REMOTE &&
                onOpenWebUi != null
            ) {
                OutlinedButton(onClick = { onOpenWebUi(server.port) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Language, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.chats_open_web_ui), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (server.status == UnifiedServerStatus.RUNNING && server.kind != UnifiedServerKind.REMOTE &&
                normalizeLlamaServerEngine(server.engine) == LlamaServerEntity.ENGINE_LLAMA_SERVER
            ) {
                OutlinedButton(onClick = { showPrepareCaches = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.kv_prepare_caches_button), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (server.kind != UnifiedServerKind.REMOTE &&
                normalizeLlamaServerEngine(server.engine) == LlamaServerEntity.ENGINE_LLAMA_SERVER
            ) {
                LockedScreenSpeedBlock(port = server.port)
            }

            if (server.kind == UnifiedServerKind.LOCAL_MANAGED && server.cardId != null) {
                ServerLogViewer(
                    expanded = logsExpanded,
                    onToggle = { logsExpanded = !logsExpanded },
                    server = server,
                    viewModel = viewModel,
                    context = context
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.llama_card_allow_wear_start),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.llama_card_allow_wear_start_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = server.allowWearStart,
                        onCheckedChange = { viewModel.setAllowWearStart(server, it) }
                    )
                }
            }

            BoundChatsSection(boundChats, server, onOpenChat = { chatId ->
                server.rowId?.let { onOpenChat(chatId, it) }
            })

            HorizontalDivider()
            OutlinedButton(onClick = { onEdit(server) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.srv_edit), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (server.kind == UnifiedServerKind.LOCAL_LEGACY) {
                OutlinedButton(onClick = { showUpgrade = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Upgrade, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.srv_upgrade), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.srv_delete),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showPrepareCaches) {
        PrepareCachesSheet(server = server, onDismiss = { showPrepareCaches = false })
    }

    if (showUpgrade) {
        AlertDialog(
            onDismissRequest = { showUpgrade = false },
            title = { Text(stringResource(R.string.srv_upgrade_title)) },
            text = {
                Text(
                    stringResource(R.string.srv_upgrade_body),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpgrade = false
                    scope.launch { if (viewModel.convert(server)) onUpgraded() }
                }) { Text(stringResource(R.string.srv_upgrade_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showUpgrade = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.srv_delete_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.srv_delete_body, server.name))
                    if (boundChats.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.srv_delete_chats_warning, boundChats.size, boundChats.size
                            ),
                            fontWeight = FontWeight.Medium
                        )
                        boundChats.take(MAX_TITLES_IN_DELETE).forEach { chat ->
                            Text(
                                "• ${chat.title}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (boundChats.size > MAX_TITLES_IN_DELETE) {
                            Text(
                                (boundChats.size - MAX_TITLES_IN_DELETE).let { more -> pluralStringResource(R.plurals.srv_more_chats, more, more) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch { viewModel.delete(server) }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun ServerSheetHeader(server: UnifiedServer) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = server.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        server.modelName?.takeIf { it.isNotBlank() }?.let { model ->
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "${stringResource(serverKindLabelRes(server.kind))} · ${server.endpointLabel()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Status line, transient progress, error text and the Start/Stop/Restart controls. */
@Composable
private fun ServerStatusBlock(
    server: UnifiedServer,
    action: ServerActionState?,
    viewModel: ServerRegistryViewModel
) {
    val status = effectiveServerStatus(server, action)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerStatusDot(status, size = 12.dp)
                Text(
                    text = serverStatusLabel(server, action),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            action?.takeIf { it.busy }?.progress?.let { progress ->
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            serverErrorText(server, action)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (server.kind != UnifiedServerKind.REMOTE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ServerStartStopButton(
                        server = server,
                        action = action,
                        onStart = { viewModel.start(server) },
                        onStop = { viewModel.stop(server) },
                        modifier = Modifier.weight(1f)
                    )
                    if (server.status == UnifiedServerStatus.RUNNING && action?.busy != true) {
                        OutlinedButton(
                            onClick = { viewModel.restart(server) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(R.string.srv_restart),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bounded session-log tail with copy and clear. Polls the existing log store only while expanded,
 * and renders at most [LOG_TAIL_LINES] lines in a height-limited, independently scrollable box.
 */
@Composable
private fun ServerLogViewer(
    expanded: Boolean,
    onToggle: () -> Unit,
    server: UnifiedServer,
    viewModel: ServerRegistryViewModel,
    context: Context
) {
    var lines by remember(server.key) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(expanded, server.key) {
        while (expanded) {
            lines = viewModel.readLogs(server).takeLast(LOG_TAIL_LINES)
            delay(LOG_POLL_MS)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(if (expanded) R.string.llama_card_hide_logs else R.string.llama_card_logs),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small
            ) {
                val logScroll = rememberScrollState()
                // Follow the tail unless the user scrolled up to read.
                LaunchedEffect(lines.size) { if (logScroll.value >= logScroll.maxValue - 80) logScroll.scrollTo(logScroll.maxValue) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 220.dp)
                        .verticalScroll(logScroll)
                        .padding(8.dp)
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            stringResource(R.string.llama_card_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("llama.cpp ${server.name}", lines.joinToString("\n")))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.llama_card_copy_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(
                    onClick = {
                        viewModel.clearLogs(server)
                        lines = emptyList()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.llama_card_clear_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BoundChatsSection(
    chats: List<LlamaChatEntity>,
    server: UnifiedServer,
    onOpenChat: (chatId: Long) -> Unit
) {
    if (server.rowId == null) return
    HorizontalDivider()
    Text(
        text = stringResource(R.string.srv_bound_chats_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    if (chats.isEmpty()) {
        Text(
            stringResource(R.string.srv_no_bound_chats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    chats.take(MAX_LISTED_CHATS).forEach { chat ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenChat(chat.id) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = chat.title.ifBlank { stringResource(R.string.srv_untitled_chat) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (chats.size > MAX_LISTED_CHATS) {
        Text(
            (chats.size - MAX_LISTED_CHATS).let { more -> pluralStringResource(R.plurals.srv_more_chats, more, more) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
