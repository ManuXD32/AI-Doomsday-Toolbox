package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Conversation scrolling follows streamed content until the reader scrolls away. */
@Composable
internal fun HarnessChatTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRequests: () -> Unit = {},
    onOpenPlan: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val transcript = remember(state.transcript) { boundedHarnessTranscript(state.transcript) }
    var followLatest by rememberSaveable(state.selectedSessionId) { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    val nearTail by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (last != null &&
                last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset + 24)
        }
    }
    LaunchedEffect(listState, state.selectedSessionId) {
        snapshotFlow { Triple(listState.isScrollInProgress, nearTail, autoScrolling) }
            .collect { (scrolling, atEnd, automatic) ->
                if (scrolling && !automatic) followLatest = atEnd
            }
    }
    val lastStructured = state.structuredTranscript.lastOrNull { !it.isMetadataOnly }
    LaunchedEffect(state.selectedSessionId, transcript.lastOrNull(), lastStructured) {
        if (followLatest) {
            autoScrolling = true
            try {
                withFrameNanos { }
                listState.scrollHarnessToEnd()
            } finally { autoScrolling = false }
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("harness_chat_timeline"),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        state.notice?.let { notice ->
            item(key = "notice") {
                HarnessNoticeBanner(notice, onAction)
            }
        }
        if (state.questions.isNotEmpty() || state.approvals.isNotEmpty() || state.plans.isNotEmpty()) {
            item(key = "pending-requests") {
                HarnessPendingRequestsCard(
                    questions = state.questions.size,
                    approvals = state.approvals.size,
                    plans = state.plans.size,
                    onOpenRequests = onOpenRequests,
                    onOpenPlan = onOpenPlan,
                )
            }
        }
        if (transcript.isEmpty() && state.structuredTranscript.isEmpty()) {
            item(key = "empty") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_empty_title),
                    message = stringResource(
                        if (state.selectedSessionId == null) {
                            R.string.harness_empty_session_message
                        } else {
                            R.string.harness_empty_message
                        }
                    ),
                    actionLabel = if (state.selectedSessionId == null) {
                        stringResource(R.string.harness_new_session)
                    } else {
                        null
                    },
                    onAction = if (state.selectedSessionId == null) {
                        { onAction(NativeHarnessUiAction.CreateSession) }
                    } else {
                        null
                    }
                )
            }
        } else {
            item(key = "transcript-title") {
                HarnessSectionHeading(
                    title = stringResource(R.string.harness_transcript_title),
                    icon = Icons.Default.History
                )
            }
            harnessTranscriptTimeline(
                sessionKey = state.selectedSessionId,
                transcript = transcript,
                structured = state.structuredTranscript,
                detail = state.structuredDetail,
                feedback = state.messageFeedback,
                onAction = onAction,
                actionHooks = NativeHarnessTranscriptActionHooks(
                    onCopyTurn = { target -> target.sessionId?.let { sessionId ->
                        onAction(NativeHarnessUiAction.CopyTranscriptTurn(
                            sessionId, target.turn.toLong(), target.tailSequence, target.branchSequence,
                        ))
                    } },
                    onBranchTurn = { target -> target.sessionId?.let { sessionId ->
                        target.branchSequence?.let { sequence ->
                            onAction(NativeHarnessUiAction.ForkTranscriptTurn(sessionId, sequence))
                        }
                    } },
                    onCopyMessage = { target -> target.sessionId?.let { sessionId ->
                        onAction(NativeHarnessUiAction.CopyTranscriptMessage(sessionId, target.turn?.toLong(), target.messageSequence))
                    } },
                    isCopying = state.isCopyingTranscript,
                    onCancelCopy = { onAction(NativeHarnessUiAction.CancelTranscriptCopy) },
                ),
            )
            if (state.canLoadOlderMessages) {
                item(key = "load-older-messages") {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.LoadOlderMessages) },
                        enabled = !state.isLoadingOlderMessages,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (state.isLoadingOlderMessages) {
                                    R.string.harness_loading_older_messages
                                } else {
                                    R.string.harness_load_older_messages
                                }
                            )
                        )
                    }
                }
            }
        }
        }
        if (!nearTail) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                shape = AppChromeDefaults.InnerCardShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 3.dp,
            ) {
                TextButton(onClick = {
                    if (listState.layoutInfo.totalItemsCount > 0) {
                        scrollScope.launch {
                            followLatest = true
                            autoScrolling = true
                            try { listState.scrollHarnessToEnd() }
                            finally { autoScrolling = false }
                        }
                    }
                }) {
                    Text(stringResource(R.string.harness_jump_to_latest))
                }
            }
        }
}

}

@Composable
private fun HarnessPendingRequestsCard(
    questions: Int,
    approvals: Int,
    plans: Int,
    onOpenRequests: () -> Unit,
    onOpenPlan: () -> Unit,
) {
    AppSectionCard(
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.PendingActions, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.harness_pending_requests_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.harness_pending_requests_summary, questions + approvals + plans),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (questions > 0 || approvals > 0) {
                OutlinedButton(onClick = onOpenRequests) {
                    Text(stringResource(R.string.harness_pending_requests_open))
                }
            }
            if (plans > 0) {
                OutlinedButton(onClick = onOpenPlan) {
                    Text(stringResource(R.string.harness_pending_plan_open))
                }
            }
        }
    }
}
private suspend fun LazyListState.scrollHarnessToEnd() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    scrollToItem(count - 1)
    val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    // The final item itself may be taller than the viewport.
    val remaining = (tail.offset + tail.size - layoutInfo.viewportEndOffset).coerceAtLeast(0)
    if (remaining > 0) scrollBy(remaining.toFloat())
}
