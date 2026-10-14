package com.example.llamadroid.ui.ai.llama.delegation

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.harness.HarnessProjectFilesNavigator
import com.example.llamadroid.service.delegation.DelegationLiveStatus
import com.example.llamadroid.service.delegation.NativeChatDelegation
import com.example.llamadroid.service.delegation.NativeChatDelegationApi
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ContextUsageRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Distinguishes "still loading" (null holder) from "loaded, no task" (holder with null). */
private class LoadedTask(val task: NativeChatDelegateTaskEntity?)

/** DeepSeek delegation viewer: header + Conversation / Subagent / Harness tabs. */
@Composable
fun NativeChatDelegationScreen(navController: NavController, chatId: Long) {
    val context = LocalContext.current
    val api = remember(context) { NativeChatDelegation.get(context) }
    val loaded by remember(chatId, api) { api.observeLatestTask(chatId).map { LoadedTask(it) } }
        .collectAsState(initial = null)
    val task = loaded?.task

    AppScreenScaffold(
        title = stringResource(R.string.native_chat_delegation_viewer_title),
        subtitle = task?.title,
        onBack = { navController.popBackStack() }
    ) { _ ->
        when {
            loaded == null -> Box(Modifier.fillMaxSize())
            task == null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                AppEmptyState(
                    title = stringResource(R.string.native_chat_delegation_viewer_empty_title),
                    subtitle = stringResource(R.string.native_chat_delegation_viewer_empty_body)
                )
            }
            else -> DelegationContent(navController, api, chatId, task)
        }
    }
}

@Composable
private fun DelegationContent(
    navController: NavController,
    api: NativeChatDelegationApi,
    chatId: Long,
    task: NativeChatDelegateTaskEntity
) {
    val messages by remember(task.id, api) { api.observeTaskMessages(task.id) }
        .collectAsState(initial = emptyList<NativeChatDelegateMessageEntity>())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        // The header can be taller than a small phone allows; keep it bounded and scrollable.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            DelegationHeader(navController, api, chatId, task)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(
                R.string.native_chat_delegation_viewer_tab_conversation,
                R.string.native_chat_delegation_viewer_tab_subagent,
                R.string.native_chat_delegation_viewer_tab_harness
            ).forEachIndexed { index, res ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(res), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> DelegationConversationTab(task, messages)
                1 -> DelegationSubagentTab(messages)
                else -> DelegationHarnessTab(navController, api, task)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelegationHeader(
    navController: NavController,
    api: NativeChatDelegationApi,
    chatId: Long,
    task: NativeChatDelegateTaskEntity
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveMap by api.liveStatus.collectAsState()
    val live: DelegationLiveStatus? = liveMap[task.id]
    val isLive = task.state in NativeChatDelegateTaskState.LIVE
    val now by produceState(System.currentTimeMillis(), isLive) {
        value = System.currentTimeMillis()
        if (isLive) while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val elapsed = (task.finishedAt ?: now) - task.createdAt
    var cacheTick by remember { mutableIntStateOf(0) }
    val cacheBytes by produceState(0L, task.id, task.state, cacheTick) {
        value = withContext(Dispatchers.IO) { runCatching { api.subagentCacheBytes(task.id) }.getOrDefault(0L) }
    }
    val liveWindow = live?.subagentContextWindow ?: 0
    val used = if (liveWindow > 0) live?.subagentContextUsed ?: 0 else task.ctxUsedTokens
    val window = if (liveWindow > 0) liveWindow else task.ctxWindowTokens
    val percent = delegationContextPercent(used, window)
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    AppSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                StateChip(task.state)
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_elapsed, formatDelegationElapsed(elapsed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ContextUsageRing(
                percentUsed = percent,
                isWorking = isLive,
                contentDescription = percent?.let {
                    stringResource(R.string.native_chat_delegation_viewer_context_cd, it)
                }
            )
        }
        live?.phase?.takeIf { it.isNotBlank() && isLive }?.let { phase ->
            val phaseText = delegationPhaseLabelRes(phase)?.let { stringResource(it) } ?: phase
            Text(
                stringResource(R.string.native_chat_delegation_viewer_phase_label, phaseText),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isLive) DelegationProgressLines(live)
        (live?.progressNote ?: task.progressNote)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = if (percent != null) {
                stringResource(R.string.native_chat_delegation_viewer_context_label) + ": " +
                    stringResource(
                        R.string.native_chat_delegation_viewer_context_percent, percent,
                        com.example.llamadroid.ui.ai.llama.chat.formatTokenCount(window)
                    )
            } else stringResource(R.string.native_chat_delegation_viewer_context_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (task.compactionCount > 0) {
            Text(
                stringResource(R.string.native_chat_delegation_viewer_compactions, task.compactionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        task.lastErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            val text = delegationErrorLabelRes(code)?.let { stringResource(it) }
                ?: stringResource(R.string.native_chat_delegation_viewer_error_unknown, code)
            Text(
                stringResource(R.string.native_chat_delegation_viewer_error_label, text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.native_chat_delegation_viewer_cache_size,
                    Formatter.formatShortFileSize(context, cacheBytes)
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { confirmClear = true }, enabled = cacheBytes > 0) {
                Text(stringResource(R.string.native_chat_delegation_viewer_cache_clear), maxLines = 1)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isLive) {
                OutlinedButton(onClick = { confirmCancel = true }) {
                    Text(stringResource(R.string.native_chat_delegation_viewer_cancel), maxLines = 1)
                }
            }
            if (task.state == NativeChatDelegateTaskState.INTERRUPTED ||
                task.state == NativeChatDelegateTaskState.NEEDS_DIRECTION
            ) {
                Button(onClick = {
                    scope.launch {
                        val ok = runCatching { api.continueTask(chatId) }.getOrDefault(false)
                        if (!ok) Toast.makeText(
                            context, R.string.native_chat_delegation_viewer_continue_failed, Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.native_chat_delegation_viewer_continue_task), maxLines = 1) }
            }
            val workspaceId = task.workspaceId
            if (workspaceId != null) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = runCatching {
                            HarnessProjectFilesNavigator.openProjectFiles(context, navController, task.runtimeId, workspaceId)
                        }.getOrDefault(false)
                        if (!ok) Toast.makeText(
                            context, R.string.native_chat_delegation_viewer_open_files_failed, Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.native_chat_delegation_viewer_open_files), maxLines = 1) }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.native_chat_delegation_viewer_cancel_title)) },
            text = {
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_cancel_body),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    scope.launch { runCatching { api.cancelTask(chatId, null, false) } }
                }) { Text(stringResource(R.string.native_chat_delegation_viewer_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.native_chat_delegation_viewer_cancel_keep))
                }
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.native_chat_delegation_viewer_cache_clear_title)) },
            text = {
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_cache_clear_body),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        runCatching { api.clearSubagentCache(task.id) }
                        cacheTick++
                        Toast.makeText(
                            context, R.string.native_chat_delegation_viewer_cache_cleared, Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.native_chat_delegation_viewer_cache_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun StateChip(state: String) {
    val label = delegationStateLabelRes(state)?.let { stringResource(it) }
        ?: stringResource(R.string.native_chat_delegation_viewer_state_unknown, state)
    val colors = MaterialTheme.colorScheme
    val container = when (state) {
        NativeChatDelegateTaskState.FAILED -> colors.errorContainer
        NativeChatDelegateTaskState.NEEDS_DIRECTION, NativeChatDelegateTaskState.INTERRUPTED -> colors.tertiaryContainer
        NativeChatDelegateTaskState.COMPLETED -> colors.primaryContainer
        in NativeChatDelegateTaskState.LIVE -> colors.secondaryContainer
        else -> colors.surfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
