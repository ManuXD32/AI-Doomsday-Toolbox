package com.example.llamadroid.ui.ai.llama.delegation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.llamadroid.service.delegation.DelegationThreadLive
import com.example.llamadroid.service.delegation.DelegationThreadLives
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import com.example.llamadroid.ui.components.ContextUsageRing
import java.util.Locale

/** One harness thread: title, running chip, context ring and (while processing) prompt progress. */
@Composable
internal fun DelegationThreadCard(thread: DelegationThreadLive, onClick: () -> Unit) {
    val percent = DelegationThreadLives.contextPercent(thread.contextUsed, thread.contextWindow)
    val promptPercent = delegationPromptPercent(thread.promptProcessed, thread.promptTotal)
    AppSectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (thread.running) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        stringResource(
                            if (thread.running) R.string.native_chat_delegation_viewer_thread_running
                            else R.string.native_chat_delegation_viewer_thread_idle
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1
                    )
                }
            }
            // No context data (window unknown): hide the ring instead of showing "--".
            if (percent != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ContextUsageRing(percent, size = 32.dp)
                    Text("$percent%", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        if (promptPercent != null) {
            Text(
                stringResource(
                    R.string.native_chat_delegation_viewer_thread_processing, promptPercent,
                    count(thread.promptProcessed), count(thread.promptTotal)
                ),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(progress = { promptPercent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun count(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

/** Loads (IO) and shows the thread's last two messages; long texts open in [AppTextDetailsDialog]. */
@Composable
internal fun DelegationThreadMessagesDialog(runtimeId: String, thread: DelegationThreadLive, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<DelegationThreadMessagesResult?>(null, runtimeId, thread.sessionId) {
        value = DelegationThreadMessages.load(context, runtimeId, thread.sessionId, thread.lastSeq)
    }
    var detail by remember { mutableStateOf<DelegationThreadMessage?>(null) }
    detail?.let { message ->
        AppTextDetailsDialog(
            title = roleLabel(message.role), text = message.text, onDismiss = { detail = null }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(thread.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (val r = result) {
                    null -> CircularProgressIndicator()
                    DelegationThreadMessagesResult.RuntimeNotRunning -> Text(
                        stringResource(R.string.native_chat_delegation_viewer_thread_runtime_stopped),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is DelegationThreadMessagesResult.Failed -> Text(
                        if (r.detail.isEmpty()) stringResource(R.string.native_chat_delegation_viewer_thread_load_failed)
                        else stringResource(R.string.native_chat_delegation_viewer_thread_load_failed_detail, r.detail),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is DelegationThreadMessagesResult.Loaded -> if (r.messages.isEmpty()) {
                        Text(
                            stringResource(R.string.native_chat_delegation_viewer_thread_no_messages),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else r.messages.forEach { message ->
                        Text(
                            roleLabel(message.role),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(message.preview, style = MaterialTheme.typography.bodySmall)
                        if (message.truncated) {
                            TextButton(onClick = { detail = message }) {
                                Text(
                                    stringResource(R.string.native_chat_delegation_viewer_row_show_full),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_chat_delegation_viewer_thread_close)) }
        }
    )
}

@Composable
private fun roleLabel(role: String): String = stringResource(
    if (role == "USER") R.string.native_chat_delegation_viewer_thread_role_user
    else R.string.native_chat_delegation_viewer_thread_role_assistant
)
