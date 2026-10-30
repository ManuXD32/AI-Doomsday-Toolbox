package com.example.llamadroid.ui.ai.llama.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.ui.ai.llama.EasyLlamaModelPickerDialog
import com.example.llamadroid.ui.ai.llama.LlamaPromptProfilePickerField
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.ServerStatusDot
import com.example.llamadroid.ui.ai.llama.servers.effectiveServerStatus
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.ai.llama.servers.serverStatusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single "New chat" entry point: choose the server (last used preselected), or create a new
 * local one from a downloaded model through the Easy chat path, with optional title and prompt.
 * Every chat created here is bound to its server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    serversViewModel: ServerRegistryViewModel,
    repository: LlamaRepository,
    promptProfiles: List<LlamaChatPromptProfileEntity>,
    folderId: Long?,
    onDismiss: () -> Unit,
    onChatReady: (chatId: Long, serverId: Long) -> Unit,
    onAddServer: () -> Unit,
    onManageModels: () -> Unit,
    onNewLocalFromModel: (ModelEntity) -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val servers by serversViewModel.servers.collectAsState()
    val actions by serversViewModel.actions.collectAsState()
    val lastUsedRowId by produceState<Long?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { database.llamaServerDao().getLastUsedServer()?.id }
    }

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var userChose by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(servers, lastUsedRowId) {
        if (!userChose || servers.none { it.key == selectedKey }) {
            selectedKey = choosePreselectedServer(servers, lastUsedRowId)?.key
        }
    }
    val selected = servers.firstOrNull { it.key == selectedKey }

    var title by rememberSaveable { mutableStateOf("") }
    var systemPrompt by rememberSaveable { mutableStateOf("") }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val defaultTitle = stringResource(R.string.llama_new_chat)
    val failedText = stringResource(R.string.chats_rework_create_failed)

    fun createWithServer(server: UnifiedServer) {
        if (busy) return
        busy = true
        error = null
        job = scope.launch {
            try {
                val rowId = serversViewModel.ensureRowId(server)
                if (rowId == null) {
                    error = failedText
                    return@launch
                }
                val entity = withContext(Dispatchers.IO) { database.llamaServerDao().getServerById(rowId) }
                val chatId = repository.createChat(
                    title = title.trim().ifBlank { defaultTitle },
                    contextSize = 0,
                    systemPrompt = systemPrompt.ifBlank { null },
                    apiParams = newChatApiParamsForServer(entity),
                    folderId = folderId,
                    serverId = rowId,
                    persistKvCache = defaultPersistKvCacheForServer(entity)
                )
                withContext(Dispatchers.IO) { database.llamaServerDao().updateLastUsed(rowId) }
                onChatReady(chatId, rowId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                error = failedText
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { job?.cancel(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.chats_rework_new_chat_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.chats_rework_choose_server),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (servers.isEmpty()) {
                Text(
                    text = stringResource(R.string.chats_rework_sheet_no_servers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            servers.forEach { server ->
                ServerRadioRow(
                    server = server,
                    statusLabel = serverStatusLabel(server, actions[server.key]),
                    status = effectiveServerStatus(server, actions[server.key]),
                    selected = server.key == selectedKey,
                    enabled = !busy,
                    onClick = {
                        userChose = true
                        selectedKey = server.key
                    }
                )
            }
            OutlinedButton(
                onClick = { showModelPicker = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.chats_rework_new_local),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.chats_rework_new_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (servers.isEmpty()) {
                TextButton(onClick = onAddServer, enabled = !busy) {
                    Text(stringResource(R.string.chats_rework_add_server), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { moreOpen = !moreOpen }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ui_chats_more),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    if (moreOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (moreOpen) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.llama_chat_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.llama_system_prompt)) },
                    placeholder = { Text(stringResource(R.string.llama_system_prompt_placeholder)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                LlamaPromptProfilePickerField(
                    customProfiles = promptProfiles,
                    onSelected = { systemPrompt = it.content }
                )
            }

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { selected?.let(::createWithServer) },
                enabled = selected != null && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.chats_rework_create), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) {
                TextButton(
                    onClick = { job?.cancel(); busy = false; status = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }

    if (showModelPicker) {
        EasyLlamaModelPickerDialog(
            onDismiss = { showModelPicker = false },
            onManageModels = {
                showModelPicker = false
                onManageModels()
            },
            onSelected = { model ->
                showModelPicker = false
                onNewLocalFromModel(model)
            }
        )
    }
}

@Composable
private fun ServerRadioRow(
    server: UnifiedServer,
    statusLabel: String,
    status: com.example.llamadroid.data.repository.UnifiedServerStatus,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        ServerStatusDot(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondary = listOfNotNull(server.modelName?.takeIf { it.isNotBlank() }, statusLabel).joinToString(" · ")
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
