package com.example.llamadroid.ui.ai.llama.delegation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.ui.ai.llama.chat.ChatAttachmentChips
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Main LLM <-> subagent messages as two-sided bubbles, plus the final result card. */
@Composable
internal fun DelegationConversationTab(
    task: NativeChatDelegateTaskEntity,
    messages: List<NativeChatDelegateMessageEntity>
) {
    val conversation = remember(messages) {
        messages.filter {
            it.role == NativeChatDelegateMessageRole.MAIN_TO_SUB || it.role == NativeChatDelegateMessageRole.SUB_TO_MAIN
        }
    }
    val resultFiles = remember(task.resultFilesJson) { parseResultFiles(task.resultFilesJson) }
    val hasResult = task.resultStatus != null || !task.resultSummary.isNullOrBlank() || resultFiles.isNotEmpty()
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    val itemCount = conversation.size + if (hasResult) 1 else 0
    AutoScrollToBottomEffect(listState, itemCount)

    if (itemCount == 0) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            AppEmptyState(
                title = stringResource(R.string.native_chat_delegation_viewer_tab_conversation),
                subtitle = stringResource(R.string.native_chat_delegation_viewer_conversation_empty)
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(conversation, key = { it.id }) { message ->
            val fromMain = message.role == NativeChatDelegateMessageRole.MAIN_TO_SUB
            val label = stringResource(
                if (fromMain) R.string.native_chat_delegation_viewer_role_main
                else R.string.native_chat_delegation_viewer_role_subagent
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = if (fromMain) Alignment.CenterStart else Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (fromMain) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .clickable(enabled = isPreviewTruncated(message.content)) { details = label to message.content }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(boundedPreview(message.content), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (hasResult) {
            item(key = "result") {
                DelegationResultCard(task, resultFiles) { title, text -> details = title to text }
            }
        }
    }
    details?.let { (title, text) -> AppTextDetailsDialog(title, text) { details = null } }
}

@Composable
private fun DelegationResultCard(
    task: NativeChatDelegateTaskEntity,
    files: List<DelegationResultFile>,
    onShowFull: (String, String) -> Unit
) {
    val context = LocalContext.current
    val ids = remember(files) { files.mapNotNull { it.attachmentId } }
    val attachments by produceState(emptyList<LlamaMessageAttachmentEntity>(), ids) {
        value = if (ids.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            runCatching { AppDatabase.getDatabase(context).llamaMessageAttachmentDao().getByIds(ids) }
                .getOrDefault(emptyList())
        }
    }
    val title = stringResource(R.string.native_chat_delegation_viewer_result_title)
    AppSectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        task.resultStatus?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.native_chat_delegation_viewer_result_status, it),
                style = MaterialTheme.typography.labelLarge
            )
        }
        task.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                boundedPreview(summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(enabled = isPreviewTruncated(summary)) { onShowFull(title, summary) }
            )
        }
        if (files.isNotEmpty()) {
            Text(
                stringResource(R.string.native_chat_delegation_viewer_result_files),
                style = MaterialTheme.typography.labelLarge
            )
            ChatAttachmentChips(attachments = attachments)
            val loadedIds = attachments.map { it.id }.toSet()
            files.filter { it.attachmentId == null || it.attachmentId !in loadedIds }.take(20).forEach { file ->
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_result_file_missing, file.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
