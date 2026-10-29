package com.example.llamadroid.ui.easy

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.easy.EasyModelNames
import com.example.llamadroid.data.easy.easyModeCardModelId
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.repository.EasyLlamaChatCoordinator
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun EasyChatTab(
    navController: NavController,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val settings = remember(context) { EasyModeSettings(context) }
    val coordinator = remember(context, database) { EasyLlamaChatCoordinator(context, database) }
    val scope = rememberCoroutineScope()
    val chats by remember(database) { database.llamaChatDao().getEasyChats().map { list -> list.filter { !it.isEphemeral } } }.collectAsState(initial = emptyList())
    var isOpening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var deleteTarget by remember { mutableStateOf<LlamaChatEntity?>(null) }
    val startingText = stringResource(R.string.easy_chat_starting_server)
    val pickModelText = stringResource(R.string.easy_chat_orphan_pick_model)
    val modelMissingText = stringResource(R.string.easy_chat_model_missing_choose)
    val failedText = stringResource(R.string.easy_chat_open_failed)
    val chatModelPath by settings.chatModelPathFlow.collectAsState()
    val modelFile = chatModelPath.takeIf { it.isNotBlank() }?.let { File(it).name }
    val curatedTitle = EasyModelNames.curatedTitleRes(modelFile)?.let { stringResource(it) }
    val modelName = modelFile?.let { EasyModelNames.friendlyName(it, curatedTitle) }
    val cardId by produceState<Long?>(null, modelFile, isOpening) {
        value = modelFile?.let { file ->
            withContext(Dispatchers.IO) { database.llamaServerCardDao().getEasyCard(easyModeCardModelId(file))?.id }
        }
    }

    fun newChat() {
        if (isOpening) return
        val path = chatModelPath
        if (path.isBlank()) { onOpenModels(); return }
        isOpening = true; error = null; status = startingText
        job = scope.launch {
            try {
                val model = withContext(Dispatchers.IO) { database.modelDao().getModelByPath(path) }
                if (model == null || !File(model.path).isFile) { onOpenModels(); return@launch }
                // The coordinator applies the per-model context override or the RAM recommendation.
                val target = coordinator.createOrReuseEasy(model.filename)
                val session = coordinator.openEasyChat(target.card.id) { message -> status = message }
                navController.navigate(Screen.LlamaChat.createRoute(session.chatId, session.serverId))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                error = (throwable as? ManagedLlamaServerException)?.message?.takeIf { it.isNotBlank() } ?: failedText
            } finally { isOpening = false; status = null }
        }
    }

    fun openChat(chat: LlamaChatEntity) {
        if (isOpening) return
        when (val action = easyChatOpenAction(chat.serverId, modelFile)) {
            is EasyChatOpenAction.Open ->
                navController.navigate(Screen.LlamaChat.createRoute(chat.id, action.serverId))
            EasyChatOpenAction.PickModel -> { error = pickModelText; onOpenModels() }
            is EasyChatOpenAction.Rebind -> {
                isOpening = true; error = null; status = startingText
                job = scope.launch {
                    try {
                        val target = coordinator.createOrReuseEasy(action.modelFilename)
                        withContext(Dispatchers.IO) {
                            // The saved KV cache may belong to another model.
                            runCatching { NativeChatKvCacheStore(context.applicationContext).delete(chat.id) }
                            LlamaRepository(database.llamaServerDao(), database.llamaChatDao(),
                                database.llamaChatFolderDao(), database.llamaMessageDao())
                                .bindChatServer(chat.id, target.server.id)
                        }
                        navController.navigate(Screen.LlamaChat.createRoute(chat.id, target.server.id))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        val missing = (throwable as? ManagedLlamaServerException)?.code == "MANAGED_MODEL_MISSING"
                        error = if (missing) modelMissingText else failedText
                        if (missing) onOpenModels()
                    } finally { isOpening = false; status = null }
                }
            }
        }
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.easy_chat_opts_delete_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(chat.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.easy_chat_opts_delete_body), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    // Removes delegation artifacts, the row and the KV cache files.
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            LlamaRepository(database.llamaServerDao(), database.llamaChatDao(),
                                database.llamaChatFolderDao(), database.llamaMessageDao()).deleteChat(chat)
                        }
                    }
                }) { Text(stringResource(R.string.easy_chat_opts_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("easy_chat_tab"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.easy_mode_shell_chat_with),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(modelName ?: stringResource(R.string.easy_mode_shell_chat_no_model),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpenModels, modifier = Modifier.testTag("easy_chat_change_model")) {
                    Text(stringResource(R.string.easy_mode_shell_change), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            EasyServerStatusRow(cardId)
            Button(onClick = ::newChat, enabled = !isOpening, modifier = Modifier.fillMaxWidth().testTag("easy_new_chat")) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(if (isOpening) R.string.easy_mode_shell_chat_starting else R.string.easy_mode_shell_new_chat),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isOpening) {
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        Text(stringResource(R.string.easy_mode_shell_recent_chats), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chats.isEmpty()) {
            Text(stringResource(R.string.easy_mode_shell_no_chats), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            chats.forEach { chat -> EasyChatRow(chat, onClick = { openChat(chat) }, onLongClick = { deleteTarget = chat }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EasyChatRow(chat: LlamaChatEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    AppSectionCard(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("easy_chat_row_${chat.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateUtils.getRelativeTimeSpanString(chat.lastModified, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
