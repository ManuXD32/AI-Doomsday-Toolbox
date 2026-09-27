package com.example.llamadroid.ui.ai.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.repository.LlamaServerCardRepository
import com.example.llamadroid.data.repository.RoomGeneralSavedCommandProvider
import com.example.llamadroid.data.repository.EasyLlamaChatCoordinator
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionLogStore
import com.example.llamadroid.service.LlamaServerSessionSnapshot
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.settings.SavedLlamaCommandEditor
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RunningLlamaChatServerUi(
    val sessionId: String,
    val name: String,
    val port: Int
)

private enum class CardErrorAction {
    START,
    CHAT
}

/**
 * Reads the independently managed server sessions used by the AI Hub Chat tile.
 *
 * This is intentionally state-only: the hub owns the tile and picker UI, while the dedicated
 * server screen owns cards and logs.
 */
@Composable
fun rememberRunningLlamaChatServers(): List<RunningLlamaChatServerUi> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val cardRepository = remember(database) {
        LlamaServerCardRepository(
            database.llamaServerCardDao(),
            RoomGeneralSavedCommandProvider(database.savedCommandDao())
        )
    }
    val cards by cardRepository.cards.collectAsState(initial = emptyList())
    val stateStore = remember(context) { LlamaServerSessionStateStore(context) }
    var snapshots by remember { mutableStateOf<Map<String, LlamaServerSessionSnapshot>>(emptyMap()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            snapshots = stateStore.readAll().associateBy { it.sessionId }
            delay(750L)
        }
    }

    val runningServers = remember(snapshots, cards) {
        snapshots.values
            .filter { it.status == LlamaServerSessionStatus.RUNNING && it.port != null }
            .mapNotNull { session ->
                val card = cards.firstOrNull { it.sessionId == session.sessionId }
                    ?: return@mapNotNull null
                RunningLlamaChatServerUi(
                    sessionId = session.sessionId,
                    name = card.name,
                    port = requireNotNull(session.port)
                )
            }
            .distinctBy { it.sessionId }
    }

    return runningServers
}

/**
 * Persistent manager for independent local llama.cpp servers. Cards, linked native servers, and
 * saved launch profiles all remain owned by the Room-backed repositories.
 */
@Composable
fun LlamaServerCardsSection(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenChat: (chatId: Long, serverId: Long) -> Unit = { _, _ -> },
    onManageModels: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val easyChatCoordinator = remember(context, database) {
        EasyLlamaChatCoordinator(context, database)
    }
    val managedServerCoordinator = remember(context, database) {
        ManagedLlamaServerCoordinator(context, database)
    }
    val presets by remember(database) {
        database.savedCommandDao().getCommandsByScope(SavedCommandScopes.GENERAL)
    }.collectAsState(initial = emptyList())
    val cardRepository = remember(database) {
        LlamaServerCardRepository(
            database.llamaServerCardDao(),
            RoomGeneralSavedCommandProvider(database.savedCommandDao())
        )
    }
    val cards by cardRepository.cards.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val startingChatText = stringResource(R.string.easy_chat_starting_server)
    val logs = remember(context) { LlamaServerSessionLogStore(context) }
    val stateStore = remember(context) { LlamaServerSessionStateStore(context) }
    var runtimeSnapshots by remember { mutableStateOf<Map<String, LlamaServerSessionSnapshot>>(emptyMap()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEasyAddDialog by remember { mutableStateOf(false) }
    var presetToEdit by remember { mutableStateOf<SavedCommand?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var cardToEdit by remember { mutableStateOf<LlamaServerCardEntity?>(null) }
    var cardToDelete by remember { mutableStateOf<LlamaServerCardEntity?>(null) }
    var expandedLogs by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var logVersion by remember { mutableStateOf(0) }
    var startingCardId by remember { mutableStateOf<Long?>(null) }
    var chatCardId by remember { mutableStateOf<Long?>(null) }
    var chatStatus by remember { mutableStateOf<String?>(null) }
    var chatError by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var chatErrorAction by remember { mutableStateOf<Pair<Long, CardErrorAction>?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            runtimeSnapshots = stateStore.readAll().associateBy { it.sessionId }
            delay(750L)
        }
    }
    LaunchedEffect(expandedLogs) {
        while (isActive && expandedLogs.isNotEmpty()) {
            logVersion++
            delay(750L)
        }
    }

    fun startCard(card: LlamaServerCardEntity) {
        if (startingCardId != null || chatCardId != null) return
        startingCardId = card.id
        chatError = null
        chatErrorAction = null
        actionJob = scope.launch {
            try {
                managedServerCoordinator.prepare(card.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                if (currentCoroutineContext().isActive) {
                    chatError = card.id to localizedEasyLlamaFailure(
                        context,
                        throwable,
                        R.string.managed_llama_start_failed
                    )
                    chatErrorAction = card.id to CardErrorAction.START
                }
            } finally {
                startingCardId = null
            }
        }
    }

    fun openCardChat(card: LlamaServerCardEntity) {
        if (startingCardId != null || chatCardId != null) return
        chatCardId = card.id
        chatStatus = startingChatText
        chatError = null
        chatErrorAction = null
        actionJob = scope.launch {
            val requestJob = currentCoroutineContext()[Job]
            try {
                val session = easyChatCoordinator.openChat(card.id) { message ->
                    scope.launch { if (requestJob?.isActive == true) chatStatus = message }
                }
                onOpenChat(session.chatId, session.serverId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                if (currentCoroutineContext().isActive) {
                    chatError = card.id to localizedEasyLlamaFailure(
                        context,
                        throwable,
                        R.string.easy_chat_open_failed
                    )
                    chatErrorAction = card.id to CardErrorAction.CHAT
                }
            } finally {
                chatCardId = null
                chatStatus = null
            }
        }
    }

    AppSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.llama_cards_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.llama_cards_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showEasyAddDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.llama_cards_add)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { showEasyAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.llama_cards_add), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LlamaServerSetupGuide(onOpenSettings)
        EasyLlamaChatEntryCard(
            onChatOpened = onOpenChat,
            onManageModels = onManageModels,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (cards.isEmpty()) {
            Text(
                text = stringResource(R.string.llama_cards_empty),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.llama_cards_add_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { card ->
                    val preset = presets.firstOrNull { it.id == card.savedCommandId }
                    val snapshot = runtimeSnapshots[card.sessionId]
                    val isExpanded = card.id in expandedLogs
                    LlamaServerCard(
                        card = card,
                        preset = preset,
                        snapshot = snapshot,
                        isLogsExpanded = isExpanded,
                        logs = if (isExpanded) logs.read(card.sessionId) else emptyList(),
                        logVersion = logVersion,
                        onStart = { startCard(card) },
                        onStop = {
                            if (chatCardId == card.id || startingCardId == card.id) actionJob?.cancel()
                            LlamaServerLauncher.stopSession(context, card.sessionId)
                        },
                        onToggleLogs = {
                            expandedLogs = if (isExpanded) expandedLogs - card.id else expandedLogs + card.id
                            logVersion++
                        },
                        onCopyLogs = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("llama.cpp ${card.name}", logs.read(card.sessionId).joinToString("\n"))
                            )
                        },
                        onClearLogs = {
                            LlamaServerLauncher.clearSessionLogs(context, card.sessionId)
                            logVersion++
                        },
                        onEdit = { cardToEdit = card },
                        onArguments = { presetToEdit = preset },
                        onCancel = { actionJob?.cancel() },
                        onDelete = { cardToDelete = card },
                        onChat = { openCardChat(card) },
                        onRetry = {
                            when (chatErrorAction?.takeIf { it.first == card.id }?.second) {
                                CardErrorAction.START -> startCard(card)
                                CardErrorAction.CHAT -> openCardChat(card)
                                null -> startCard(card)
                            }
                        },
                        onManageModels = onManageModels,
                        isStarting = startingCardId == card.id,
                        isAnyActionBusy = startingCardId != null || chatCardId != null,
                        isChatOpening = chatCardId == card.id,
                        chatStatus = chatStatus.takeIf { chatCardId == card.id },
                        chatError = chatError?.takeIf { it.first == card.id }?.second,
                        onToggleWearStart = {
                            scope.launch { cardRepository.setWearStartCard(card.id) }
                        }
                    )
                }
            }
        }


    }

    if (showAddDialog) {
        AddLlamaServerCardDialog(
            presets = presets,
            existingCards = cards,
            initialCard = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, preset, port ->
                scope.launch {
                    cardRepository.save(
                        LlamaServerCardEntity(
                            name = name,
                            savedCommandId = preset.id,
                            presetNameSnapshot = preset.name,
                            port = port
                        )
                    )
                    showAddDialog = false
                }
            }
        )
    }

    if (showEasyAddDialog) {
        EasyLlamaServerDialog(
            onDismiss = { showEasyAddDialog = false },
            onOpenAdvanced = {
                showEasyAddDialog = false
                showAddDialog = true
            },
            onSaved = { showEasyAddDialog = false },
            onChatOpened = { chatId, serverId ->
                showEasyAddDialog = false
                onOpenChat(chatId, serverId)
            },
            onManageModels = {
                showEasyAddDialog = false
                onManageModels()
            }
        )
    }

    presetToEdit?.let { preset ->
        SavedLlamaCommandEditor(command = preset, onDismiss = { presetToEdit = null }) { name, profile ->
            scope.launch {
                database.savedCommandDao().insertCommand(savedCommandFromLaunchProfile(name, profile, id = preset.id))
                presetToEdit = null
            }
        }
    }

    cardToEdit?.let { card ->
        AddLlamaServerCardDialog(
            presets = presets,
            existingCards = cards,
            initialCard = card,
            onDismiss = { cardToEdit = null },
            onSave = { name, preset, port ->
                scope.launch {
                    cardRepository.save(
                        card.copy(
                            name = name,
                            savedCommandId = preset.id,
                            presetNameSnapshot = preset.name,
                            port = port
                        )
                    )
                    cardToEdit = null
                }
            }
        )
    }

    cardToDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardToDelete = null },
            title = { Text(stringResource(R.string.llama_card_delete)) },
            text = { Text(stringResource(R.string.llama_card_delete_confirm, card.name)) },
            confirmButton = {
                TextButton(onClick = {
                    cardToDelete = null
                    scope.launch {
                        // Delete the card first; only then remove its session/log ownership.
                        cardRepository.delete(card)
                        LlamaServerLauncher.removeSession(context, card.sessionId)
                        expandedLogs = expandedLogs - card.id
                    }
                }) { Text(stringResource(R.string.llama_card_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { cardToDelete = null }) {
                    Text(stringResource(R.string.llama_card_cancel))
                }
            }
        )
    }
}

@Composable
private fun LlamaServerCard(
    card: LlamaServerCardEntity,
    preset: SavedCommand?,
    snapshot: LlamaServerSessionSnapshot?,
    isLogsExpanded: Boolean,
    logs: List<String>,
    logVersion: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onEdit: () -> Unit,
    onArguments: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onChat: () -> Unit,
    onRetry: () -> Unit,
    onManageModels: () -> Unit,
    isStarting: Boolean,
    isAnyActionBusy: Boolean,
    isChatOpening: Boolean,
    chatStatus: String?,
    chatError: String?,
    onToggleWearStart: () -> Unit
) {
    val status = snapshot?.status ?: LlamaServerSessionStatus.STOPPED
    val canStart = preset != null && status !in setOf(
        LlamaServerSessionStatus.STARTING,
        LlamaServerSessionStatus.LOADING,
        LlamaServerSessionStatus.RUNNING
    )
    val statusLabel = when (status) {
        LlamaServerSessionStatus.STOPPED -> if (snapshot?.stopReason == "idle") {
            stringResource(R.string.managed_llama_idle_stopped)
        } else {
            stringResource(R.string.llama_card_status_stopped)
        }
        LlamaServerSessionStatus.STARTING -> stringResource(R.string.llama_card_status_starting)
        LlamaServerSessionStatus.LOADING -> stringResource(R.string.llama_card_status_loading)
        LlamaServerSessionStatus.RUNNING -> stringResource(R.string.llama_card_status_running, snapshot?.port ?: card.port)
        LlamaServerSessionStatus.ERROR -> stringResource(R.string.managed_llama_start_failed)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                LlamaServerSessionStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                LlamaServerSessionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    modifier = Modifier.padding(top = 4.dp).size(10.dp),
                    color = when (status) {
                        LlamaServerSessionStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        LlamaServerSessionStatus.ERROR -> MaterialTheme.colorScheme.error
                        LlamaServerSessionStatus.STARTING,
                        LlamaServerSessionStatus.LOADING -> MaterialTheme.colorScheme.tertiary
                        LlamaServerSessionStatus.STOPPED -> MaterialTheme.colorScheme.outline
                    },
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {}
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = preset?.name?.takeIf { it.isNotBlank() }?.let { "$it · ${card.port}" }
                            ?: stringResource(R.string.llama_card_missing_preset, card.presetNameSnapshot.ifBlank { card.savedCommandId.toString() }),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (preset == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (preset == null) {
                Text(
                    text = stringResource(R.string.llama_card_no_presets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Only one card may be watch-startable; the repository enforces that, so
            // enabling this one silently clears whichever card held it before.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llama_card_allow_wear_start),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.llama_card_allow_wear_start_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = card.allowWearStart,
                    onCheckedChange = { onToggleWearStart() },
                    enabled = preset != null
                )
            }

            if (isStarting) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.easy_chat_starting), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (canStart) {
                Button(
                    onClick = onStart,
                    enabled = !isAnyActionBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.llama_card_start), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (status in setOf(LlamaServerSessionStatus.STARTING, LlamaServerSessionStatus.LOADING, LlamaServerSessionStatus.RUNNING)) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.llama_card_stop), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            Button(
                onClick = onChat,
                enabled = !isAnyActionBusy && preset != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = if (isChatOpening) stringResource(R.string.easy_chat_starting)
                    else stringResource(R.string.easy_card_chat),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isStarting || isChatOpening) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            chatStatus?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            chatError?.let { message ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.llama_card_status_error, message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onRetry, enabled = !isAnyActionBusy) {
                            Text(stringResource(R.string.easy_chat_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = onManageModels, enabled = !isAnyActionBusy) {
                            Text(stringResource(R.string.easy_chat_manage_models), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (status == LlamaServerSessionStatus.ERROR && chatError == null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRetry, enabled = !isAnyActionBusy) {
                        Text(stringResource(R.string.easy_chat_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onManageModels, enabled = !isAnyActionBusy) {
                        Text(stringResource(R.string.easy_chat_manage_models), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleLogs, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (isLogsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        if (isLogsExpanded) stringResource(R.string.llama_card_hide_logs)
                        else stringResource(R.string.llama_card_logs),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onArguments, enabled = preset != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.easy_server_arguments))
                }
                TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.llama_card_edit), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.llama_card_delete), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isLogsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            stringResource(R.string.llama_card_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp, max = 220.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(logs, key = { index, line -> "$index-$line" }) { _, line ->
                                Text(
                                    text = line,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCopyLogs, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(stringResource(R.string.llama_card_copy_logs), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = onClearLogs, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(stringResource(R.string.llama_card_clear_logs), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddLlamaServerCardDialog(
    presets: List<SavedCommand>,
    existingCards: List<LlamaServerCardEntity>,
    initialCard: LlamaServerCardEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, preset: SavedCommand, port: Int) -> Unit
) {
    var name by remember(initialCard?.id) { mutableStateOf(initialCard?.name.orEmpty()) }
    var portText by remember(initialCard?.id, presets) {
        mutableStateOf(
            initialCard?.port?.toString()
                ?: presets.firstOrNull()?.launchProfile()?.serverPort?.toString()
                ?: "8080"
        )
    }
    var selectedPreset by remember(initialCard?.id, presets) {
        mutableStateOf(
            initialCard?.let { card -> presets.firstOrNull { it.id == card.savedCommandId } }
                ?: presets.firstOrNull()
        )
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val port = portText.toIntOrNull()
    val portInUse = port != null && existingCards.any { card ->
        card.id != initialCard?.id && card.port == port
    }
    val valid = name.isNotBlank() && selectedPreset != null &&
        port?.let { it in 1..65535 } == true && !portInUse
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initialCard == null) R.string.llama_cards_add else R.string.llama_card_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llama_card_name)) },
                    placeholder = { Text(stringResource(R.string.llama_card_name_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llama_card_port)) },
                    isError = portInUse,
                    supportingText = if (portInUse && port != null) {
                        {
                            Text(
                                text = stringResource(R.string.llama_card_port_collision, port),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true
                )
                Column {
                    Text(stringResource(R.string.llama_card_preset), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = presets.isNotEmpty()
                    ) {
                        Text(
                            selectedPreset?.name ?: stringResource(R.string.llama_card_choose_preset),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    selectedPreset = preset
                                    if (initialCard == null) {
                                        portText = preset.launchProfile().serverPort.toString()
                                    }
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                    if (presets.isEmpty()) {
                        Text(
                            stringResource(R.string.llama_card_no_presets),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), requireNotNull(selectedPreset), requireNotNull(port)) },
                enabled = valid
            ) { Text(stringResource(R.string.llama_card_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.llama_card_cancel)) } }
    )
}
