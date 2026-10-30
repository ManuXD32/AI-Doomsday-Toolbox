package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLlamaServerEngine
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus

/** Switching only needs a warning when it actually moves the chat to a different server. */
internal fun shouldWarnOnServerSwitch(currentRowId: Long?, pickedRowId: Long): Boolean =
    currentRowId != null && currentRowId != pickedRowId

/** Whether the switch dialog should mention the chat's saved disk cache being deleted. */
internal fun serverSwitchDeletesCache(persistKvCache: Boolean): Boolean = persistKvCache

/**
 * Enabling the disk cache needs the chat's own local llama.cpp server relaunched, but only when it
 * is up or starting; a stopped server simply picks the option up on its next start.
 */
internal fun kvCacheEnableNeedsRestart(
    kind: UnifiedServerKind?,
    engine: String?,
    status: UnifiedServerStatus?
): Boolean = kind != null && kind != UnifiedServerKind.REMOTE &&
    normalizeLlamaServerEngine(engine) == LlamaServerEntity.ENGINE_LLAMA_SERVER &&
    (status == UnifiedServerStatus.RUNNING || status == UnifiedServerStatus.STARTING)

/** Confirmation before binding the chat to a different server. */
@Composable
internal fun ChatSwitchServerDialog(
    deletesCache: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_chat_change_server_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (deletesCache) R.string.native_chat_change_server_message_cache
                        else R.string.native_chat_change_server_message
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.native_chat_change_server_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Confirmation before turning the per-chat disk KV cache on (which restarts the local server). */
@Composable
internal fun ChatKvCacheEnableDialog(
    needsRestart: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_chat_kv_cache_enable_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(
                        if (needsRestart) R.string.native_chat_kv_cache_enable_message
                        else R.string.native_chat_kv_cache_enable_message_no_restart
                    )
                )
                Text(
                    stringResource(R.string.native_chat_kv_cache_local_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (needsRestart) R.string.native_chat_kv_cache_enable_confirm
                        else R.string.native_chat_kv_cache_enable_confirm_plain
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
