package com.example.llamadroid.ui.ai.llama.delegation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.ui.ai.llama.chat.ChatNoticeLine
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppTextDetailsDialog

/**
 * Keeps the list pinned to the newest row, but only while the user has not scrolled away from
 * the bottom. The stick flag is refreshed when a user-driven scroll settles.
 */
@Composable
internal fun AutoScrollToBottomEffect(listState: LazyListState, itemCount: Int) {
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atBottom) ->
            if (wasScrolling && !scrolling) stick = atBottom
            if (scrolling) stick = atBottom
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(itemCount) {
        if (stick && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
}

/** The subagent's internal loop; every row is a bounded preview with full text in a dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DelegationSubagentTab(messages: List<NativeChatDelegateMessageEntity>) {
    val rows = remember(messages) {
        messages.filter {
            it.role != NativeChatDelegateMessageRole.MAIN_TO_SUB && it.role != NativeChatDelegateMessageRole.SUB_TO_MAIN
        }
    }
    val expanded = remember { mutableStateListOf<String>() }
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    AutoScrollToBottomEffect(listState, rows.size)

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            AppEmptyState(
                title = stringResource(R.string.native_chat_delegation_viewer_tab_subagent),
                subtitle = stringResource(R.string.native_chat_delegation_viewer_subagent_empty)
            )
        }
        return
    }
    val summaryTitle = stringResource(R.string.native_chat_delegation_viewer_row_summary)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.id }) { message ->
            val dim = if (message.compactedOut) Modifier.alpha(0.5f) else Modifier
            Column(dim.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    message.role == NativeChatDelegateMessageRole.NOTICE -> ChatNoticeLine(
                        text = boundedPreview(message.content, 160),
                        onClick = if (isPreviewTruncated(message.content, 160)) {
                            { details = summaryTitle to message.content }
                        } else null
                    )
                    message.isSummary -> ChatNoticeLine(
                        text = summaryTitle,
                        onClick = { details = summaryTitle to message.content }
                    )
                    else -> RowCard(
                        message = message,
                        expanded = expanded,
                        onShowFull = { title, text -> details = title to text }
                    )
                }
                if (message.compactedOut) {
                    Text(
                        stringResource(R.string.native_chat_delegation_viewer_row_compacted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    details?.let { (title, text) -> AppTextDetailsDialog(title, text) { details = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowCard(
    message: NativeChatDelegateMessageEntity,
    expanded: MutableList<String>,
    onShowFull: (String, String) -> Unit
) {
    val role = message.role
    val title = when (role) {
        NativeChatDelegateMessageRole.SYSTEM -> stringResource(R.string.native_chat_delegation_viewer_row_system)
        NativeChatDelegateMessageRole.USER -> stringResource(R.string.native_chat_delegation_viewer_row_user)
        NativeChatDelegateMessageRole.TOOL ->
            stringResource(R.string.native_chat_delegation_viewer_row_tool, message.toolName ?: "?")
        else -> stringResource(R.string.native_chat_delegation_viewer_row_assistant)
    }
    val systemKey = "s${message.id}"
    val thinkingKey = "t${message.id}"
    val collapsed = role == NativeChatDelegateMessageRole.SYSTEM && systemKey !in expanded
    val toolNames = remember(message.toolCallsJson) { parseToolCallNames(message.toolCallsJson) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when (role) {
            NativeChatDelegateMessageRole.ASSISTANT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            NativeChatDelegateMessageRole.TOOL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    role == NativeChatDelegateMessageRole.SYSTEM ->
                        if (systemKey in expanded) expanded.remove(systemKey) else expanded.add(systemKey)
                    role == NativeChatDelegateMessageRole.TOOL -> onShowFull(title, message.content)
                    isPreviewTruncated(message.content) -> onShowFull(title, message.content)
                }
            }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!collapsed && message.content.isNotBlank()) {
                Text(boundedPreview(message.content), style = MaterialTheme.typography.bodyMedium)
            }
            if (toolNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    toolNames.take(12).forEach { name ->
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                name,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            val thinking = message.thinking
            if (role == NativeChatDelegateMessageRole.ASSISTANT && !thinking.isNullOrBlank()) {
                TextButton(onClick = {
                    if (thinkingKey in expanded) expanded.remove(thinkingKey) else expanded.add(thinkingKey)
                }) {
                    Text(stringResource(R.string.native_chat_delegation_viewer_row_thinking), maxLines = 1)
                }
                if (thinkingKey in expanded) {
                    Text(
                        boundedPreview(thinking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isPreviewTruncated(thinking)) {
                        TextButton(onClick = {
                            onShowFull(
                                title,
                                thinking
                            )
                        }) { Text(stringResource(R.string.native_chat_delegation_viewer_row_show_full), maxLines = 1) }
                    }
                }
            }
            if (!collapsed && role != NativeChatDelegateMessageRole.TOOL && isPreviewTruncated(message.content)) {
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_row_show_full),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
