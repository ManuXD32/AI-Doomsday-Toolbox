package com.example.llamadroid.ui.ai.llama.delegation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.harness.HarnessModelFailureLog
import com.example.llamadroid.service.delegation.DelegationThreadLive
import com.example.llamadroid.service.delegation.DelegationThreadLives
import com.example.llamadroid.service.delegation.NativeChatDelegationApi
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen

@StringRes
private fun digestSectionTitleRes(kind: DigestSectionKind): Int = when (kind) {
    DigestSectionKind.STATE -> R.string.native_chat_delegation_viewer_harness_section_state
    DigestSectionKind.PENDING -> R.string.native_chat_delegation_viewer_harness_section_pending
    DigestSectionKind.ERRORS -> R.string.native_chat_delegation_viewer_harness_section_errors
    DigestSectionKind.LAST_ASSISTANT -> R.string.native_chat_delegation_viewer_harness_section_last_assistant
    DigestSectionKind.CHANGED -> R.string.native_chat_delegation_viewer_harness_section_changed
    DigestSectionKind.DELIVERABLES -> R.string.native_chat_delegation_viewer_harness_section_deliverables
    DigestSectionKind.OTHER -> R.string.native_chat_delegation_viewer_harness_section_other
}

/** Latest Harness digest as bounded cards, plus a link to the canonical live transcript. */
@Composable
internal fun DelegationHarnessTab(
    navController: NavController,
    api: NativeChatDelegationApi,
    task: NativeChatDelegateTaskEntity
) {
    val digest = remember(task.digestJson) { parseDigest(task.digestJson) }
    val conversationId = task.harnessConversationId
    val liveMap by api.liveStatus.collectAsState()
    val liveThreads = liveMap[task.id]?.threads.orEmpty()
    val threads = remember(task.harnessThreadsJson, task.harnessSessionId, task.title, liveThreads) {
        DelegationThreadLives.forViewer(task.harnessThreadsJson, task.harnessSessionId, task.title, liveThreads)
    }
    val appContext = LocalContext.current
    val failureLog = remember(task.runtimeId) { HarnessModelFailureLog.get(appContext, task.runtimeId) }
    val failures by failureLog.records.collectAsState()
    val lastFailure = failures.firstOrNull()
    var openThread by remember { mutableStateOf<DelegationThreadLive?>(null) }
    openThread?.let { thread ->
        DelegationThreadMessagesDialog(task.runtimeId, thread, onDismiss = { openThread = null })
    }
    if (digest.sections.isEmpty() && digest.raw == null && conversationId == null && threads.isEmpty() &&
        lastFailure == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            AppEmptyState(
                title = stringResource(R.string.native_chat_delegation_viewer_tab_harness),
                subtitle = stringResource(R.string.native_chat_delegation_viewer_harness_empty)
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (conversationId != null && conversationId > 0L) {
            item(key = "open_live") {
                Button(
                    onClick = { navController.navigate(Screen.Agent.createRoute(conversationId, returnToCaller = true)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.native_chat_delegation_viewer_harness_open_live),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        lastFailure?.let { failure ->
            item(key = "model_error") { DelegationModelErrorCard(task.runtimeId, failure) }
        }
        items(threads, key = { "thread_" + it.sessionId }) { thread ->
            DelegationThreadCard(thread, onClick = { openThread = thread })
        }
        digest.raw?.let { raw ->
            item(key = "raw") {
                AppSectionCard {
                    Text(
                        stringResource(R.string.native_chat_delegation_viewer_harness_raw_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(raw, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(digest.sections) { section ->
            AppSectionCard {
                Text(
                    section.title ?: stringResource(digestSectionTitleRes(section.kind)),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                section.lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
                if (section.hiddenCount > 0) {
                    Text(
                        stringResource(R.string.native_chat_delegation_viewer_harness_more, section.hiddenCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
