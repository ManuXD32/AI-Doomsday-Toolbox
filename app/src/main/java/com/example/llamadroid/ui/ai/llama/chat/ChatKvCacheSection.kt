package com.example.llamadroid.ui.ai.llama.chat

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.KvCacheEligibility
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.UnifiedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the KV-cache switch can be changed, and which reason (if any) to show under it. */
internal data class KvCacheToggleState(val enabled: Boolean, @StringRes val reasonRes: Int?)

/**
 * Pure rule for the switch: eligible/disabled servers can switch on; otherwise the reason is shown
 * and the switch stays locked, except that a restart-pending server still lets users turn it off.
 */
internal fun kvCacheToggleState(eligibility: KvCacheEligibility, currentlyOn: Boolean): KvCacheToggleState =
    when (eligibility) {
        KvCacheEligibility.ELIGIBLE, KvCacheEligibility.DISABLED -> KvCacheToggleState(true, null)
        // Turning an enabled cache off is always allowed, e.g. after moving the chat to a remote server.
        else -> KvCacheToggleState(currentlyOn, eligibility.reasonRes)
    }

/** Memory-section controls for the per-chat KV cache kept on disk. */
@Composable
fun ChatKvCacheSection(
    chat: LlamaChatEntity,
    server: LlamaServerEntity?,
    unifiedServer: UnifiedServer? = null,
    isGenerating: Boolean = false,
    onRestartServer: (UnifiedServer) -> Unit = {},
    delegationEnabled: Boolean = false
) {
    val context = LocalContext.current
    val restartingMessage = stringResource(R.string.native_chat_kv_cache_restarting)
    val scope = rememberCoroutineScope()
    val store = remember { NativeChatKvCacheStore(context.applicationContext) }
    val repository = remember {
        val db = AppDatabase.getDatabase(context)
        LlamaRepository(db.llamaServerDao(), db.llamaChatDao(), db.llamaChatFolderDao(), db.llamaMessageDao())
    }
    var refreshTick by remember { mutableIntStateOf(0) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var showEnableDialog by remember { mutableStateOf(false) }

    LaunchedEffect(chat.id, chat.persistKvCache, refreshTick) {
        withContext(Dispatchers.IO) {
            val size = store.sizeBytes(chat.id)
            val at = if (size > 0) store.readMeta(chat.id)?.savedAt else null
            sizeBytes = size
            savedAt = at
        }
    }

    val eligibility = if (server == null) KvCacheEligibility.REMOTE_SERVER
    else NativeChatKvCacheCoordinator(context).eligibility(server, chat)
    val toggle = kvCacheToggleState(eligibility, chat.persistKvCache)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.native_chat_kv_cache_title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Switch(
                checked = chat.persistKvCache,
                enabled = toggle.enabled && !isGenerating,
                onCheckedChange = { on ->
                    if (on) showEnableDialog = true
                    else scope.launch {
                        repository.setPersistKvCache(chat.id, false)
                        refreshTick++
                    }
                }
            )
        }
        Text(
            stringResource(R.string.native_chat_kv_cache_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.native_chat_kv_cache_local_only_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (delegationEnabled) {
            Text(
                stringResource(R.string.native_chat_delegation_kv_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isGenerating) {
            Text(
                stringResource(R.string.native_chat_kv_cache_generating_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        toggle.reasonRes?.let { res ->
            Text(
                stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val sizeText = Formatter.formatShortFileSize(context, sizeBytes)
            val summary = if (sizeBytes > 0) {
                val at = savedAt
                if (at != null) {
                    val whenText = DateUtils.getRelativeTimeSpanString(
                        at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                    stringResource(R.string.native_chat_kv_cache_size_saved, sizeText, whenText)
                } else stringResource(R.string.native_chat_kv_cache_size, sizeText)
            } else stringResource(R.string.native_chat_kv_cache_empty)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            OutlinedButton(
                enabled = sizeBytes > 0,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(chat.id) }
                        refreshTick++
                    }
                }
            ) {
                Text(stringResource(R.string.native_chat_kv_cache_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (showEnableDialog) {
        val needsRestart = unifiedServer != null &&
            kvCacheEnableNeedsRestart(unifiedServer.kind, unifiedServer.engine, unifiedServer.status)
        ChatKvCacheEnableDialog(
            needsRestart = needsRestart,
            onConfirm = {
                showEnableDialog = false
                scope.launch {
                    repository.setPersistKvCache(chat.id, true)
                    refreshTick++
                    if (needsRestart && unifiedServer != null) {
                        server?.let { NativeChatKvCacheCoordinator.onServerRestarted(it.baseUrl()) }
                        onRestartServer(unifiedServer)
                        Toast.makeText(
                            context, restartingMessage, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onDismiss = { showEnableDialog = false }
        )
    }
}
