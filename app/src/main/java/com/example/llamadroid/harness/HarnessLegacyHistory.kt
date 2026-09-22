package com.example.llamadroid.harness

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.db.HarnessLegacyMessagePreview
import com.example.llamadroid.data.db.HarnessLegacyMessageChunk
import kotlinx.coroutines.CancellationException
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.launch

/** Read-only archive backed by bounded Room pages. It never hydrates the old agent runtime. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HarnessLegacyHistory(
    database: AppDatabase,
    initialConversationId: Long? = null,
    deletion: HarnessLegacyDeletion? = null,
    onOpenFiles: ((Long) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val conversations by database.harnessDao().observeLegacyConversations().collectAsState(initial = emptyList())
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var cleanupRevision by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf(emptyList<HarnessPendingDeletion>()) }
    LaunchedEffect(cleanupRevision) { pending = deletion?.pending().orEmpty() }
    var selected by remember { mutableStateOf(initialConversationId) }
    var messages by remember { mutableStateOf(emptyList<HarnessLegacyMessagePreview>()) }
    var canLoadOlder by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<HarnessLegacyMessagePreview?>(null) }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    suspend fun loadPage(id: Long, beforeSequence: Int = Int.MAX_VALUE, beforeId: Long = Long.MAX_VALUE) {
        try {
            val page = database.harnessDao().legacyMessagePreviews(id, beforeSequence, beforeId, PAGE_SIZE + 1)
            if (selected == id) {
                canLoadOlder = page.size > PAGE_SIZE
                messages = page.take(PAGE_SIZE).reversed()
                failed = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (selected == id) failed = true }
    }
    LaunchedEffect(selected) { selected?.let { loadPage(it) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.harness_history_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.harness_history_read_only), style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth()) {
                    if (selected != null) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.harness_history_back)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.harness_runtime_close)) }
                }
                if (failed) {
                    Text(stringResource(R.string.harness_history_read_failed))
                    TextButton(onClick = { selected?.let { id -> scope.launch { loadPage(id) } } }) { Text(stringResource(R.string.harness_runtime_retry)) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selected == null) {
                        items(pending, key = { "cleanup-${it.conversationId}" }) { receipt ->
                            Column {
                                Text(stringResource(R.string.harness_legacy_cleanup_pending, receipt.projectFolder))
                                TextButton(onClick = { deleteId = receipt.conversationId }) { Text(stringResource(R.string.harness_runtime_retry)) }
                            }
                        }
                        items(conversations, key = { it.id }) { conversation ->
                            Surface(Modifier.fillMaxWidth().clickable { selected = conversation.id }, shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                                    Text(conversation.projectFolder, style = MaterialTheme.typography.bodySmall)
                                    FlowRow {
                                        onOpenFiles?.let { open -> TextButton(onClick = { open(conversation.id) }) { Text(stringResource(R.string.harness_legacy_open_files)) } }
                                        if (deletion != null) TextButton(onClick = { deleteId = conversation.id }) { Text(stringResource(R.string.harness_legacy_delete)) }
                                    }
                                }
                            }
                        }
                    } else {
                        if (canLoadOlder) item {
                            TextButton(onClick = { scope.launch {
                                val oldest = messages.firstOrNull() ?: return@launch
                                val id = selected ?: return@launch
                                loadPage(id, oldest.sequenceNumber, oldest.id)
                            } }) { Text(stringResource(R.string.harness_history_load_older)) }
                        }
                        items(messages, key = { it.id }) { message ->
                            Surface(Modifier.fillMaxWidth().clickable { expanded = message }, shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(message.toolName ?: stringResource(when (message.role) {
                                        "user" -> R.string.harness_history_role_user
                                        "assistant" -> R.string.harness_history_role_assistant
                                        "tool" -> R.string.harness_history_role_tool
                                        else -> R.string.harness_history_role_system
                                    }), style = MaterialTheme.typography.labelMedium)
                                    SelectionContainer { Text(message.preview, style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteId?.let { id -> deletion?.let { manager ->
        HarnessLegacyDeleteDialog(manager, id, pending.any { it.conversationId == id }, onDeleted = {
            if (selected == id) selected = null
            deleteId = null
            cleanupRevision++
        }, onClose = { deleteId = null; cleanupRevision++ })
    } }
    expanded?.let { message -> LegacyMessageViewer(database, message) { expanded = null } }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LegacyMessageViewer(database: AppDatabase, message: HarnessLegacyMessagePreview, onClose: () -> Unit) {
    var part by remember(message.id) { mutableStateOf("content") }
    var offset by remember(message.id) { mutableStateOf(0) }
    var chunk by remember(message.id) { mutableStateOf<HarnessLegacyMessageChunk?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(message.id, part, offset, retry) {
        chunk = null
        try {
            chunk = database.harnessDao().legacyMessageChunk(message.conversationId, message.id, part, offset + 1)
            failed = chunk == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.harness_runtime_close)) }
                    listOf("content" to R.string.harness_history_content, "thinking" to R.string.harness_history_thinking,
                        "toolOutput" to R.string.harness_history_tool_output, "terminalOutput" to R.string.harness_history_terminal_output).forEach { (key, label) ->
                        TextButton(onClick = { part = key; offset = 0 }, enabled = part != key) { Text(stringResource(label)) }
                    }
                }
                FlowRow {
                    TextButton(onClick = { offset = (offset - MESSAGE_PAGE).coerceAtLeast(0) }, enabled = offset > 0) { Text(stringResource(R.string.harness_history_previous_page)) }
                    TextButton(onClick = { offset += MESSAGE_PAGE }, enabled = chunk?.let { offset + MESSAGE_PAGE < it.totalLength } == true) { Text(stringResource(R.string.harness_history_next_page)) }
                }
                if (failed) {
                    Text(stringResource(R.string.harness_history_read_failed))
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.harness_runtime_retry)) }
                }
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(chunk?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private const val PAGE_SIZE = 80
private const val MESSAGE_PAGE = 24_000
