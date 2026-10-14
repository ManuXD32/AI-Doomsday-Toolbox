package com.example.llamadroid.ui.ai.llama.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.compaction.NativeChatCompactionHooks
import com.example.llamadroid.service.compaction.NativeChatContextFallback
import com.example.llamadroid.service.compaction.NativeChatContextUsageStore
import com.example.llamadroid.ui.components.ContextUsageRing
import kotlinx.coroutines.launch

/**
 * Always-visible context ring for the chat top bar. Tapping it opens a small scrollable popup with
 * "N of M tokens (P%)", measured/estimated, the last compaction saving, the auto-compact switch
 * and "Compact now" (disabled while a reply is generating).
 */
@Composable
internal fun ChatContextGauge(
    chatId: Long,
    autoCompactEnabled: Boolean,
    onAutoCompactChange: (Boolean) -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { NativeChatContextUsageStore.load(context) }
    val usage by remember(chatId) { NativeChatContextUsageStore.observe(chatId) }.collectAsState()
    // No recorded value yet (older chat, Ollama/LiteRT): derive one from history so the ring is never blank.
    LaunchedEffect(chatId, usage == null, isGenerating) {
        if (!isGenerating && usage?.windowTokens?.let { it > 0 } != true) {
            NativeChatContextFallback.seedIfMissing(context, chatId)
        }
    }
    var open by remember { mutableStateOf(false) }
    var compacting by remember { mutableStateOf(false) }
    val known = usage?.takeIf { it.windowTokens > 0 }
    val percent = known?.percent
    val percentLabel = percent?.let { "$it%" } ?: stringResource(R.string.native_chat_compaction_ring_unknown)

    Box(modifier = modifier) {
        ContextUsageRing(
            percentUsed = percent,
            size = 30.dp,
            isWorking = compacting,
            contentDescription = stringResource(R.string.native_chat_compaction_ring_description, percentLabel),
            onClick = { open = true },
            modifier = Modifier.testTag("native_chat_context_ring")
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 300.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.native_chat_compaction_gauge_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (known != null) {
                    Text(
                        text = stringResource(
                            R.string.native_chat_compaction_gauge_usage,
                            formatTokenCount(known.usedTokens),
                            formatTokenCount(known.windowTokens),
                            known.percent
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            if (known.measured) R.string.native_chat_compaction_gauge_measured
                            else R.string.native_chat_compaction_gauge_estimated
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.native_chat_compaction_gauge_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                usage?.lastSavedTokens?.takeIf { it > 0 }?.let { saved ->
                    Text(
                        text = stringResource(
                            if (usage?.lastSavedMeasured == true) R.string.native_chat_compaction_gauge_last_saved
                            else R.string.native_chat_compaction_gauge_last_saved_approx,
                            formatTokenCount(saved)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.native_chat_compaction_auto),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.native_chat_compaction_auto_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(checked = autoCompactEnabled, onCheckedChange = onAutoCompactChange)
                }
                Button(
                    onClick = {
                        compacting = true
                        scope.launch {
                            val result = NativeChatCompactionHooks.compactNow(context.applicationContext, chatId)
                            compacting = false
                            val app = context.applicationContext
                            val message = result.fold(
                                onSuccess = { saved ->
                                    app.getString(R.string.native_chat_compaction_done, formatTokenCount(saved))
                                },
                                onFailure = { error ->
                                    app.getString(
                                        R.string.native_chat_compaction_failed,
                                        error.message ?: error.javaClass.simpleName
                                    )
                                }
                            )
                            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                        }
                        open = false
                    },
                    enabled = !isGenerating && !compacting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (compacting) R.string.native_chat_compaction_running else R.string.native_chat_compaction_now
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isGenerating) {
                    Text(
                        text = stringResource(R.string.native_chat_compaction_busy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
