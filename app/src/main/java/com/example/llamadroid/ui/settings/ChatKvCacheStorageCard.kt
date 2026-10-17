package com.example.llamadroid.ui.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.KvCacheNamespace
import com.example.llamadroid.service.KvCompressProgress
import com.example.llamadroid.service.KvCompressResult
import com.example.llamadroid.service.KvUnpackSpeedStore
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.NativeChatKvCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings card showing the disk used by per-chat KV caches, with a confirmed "Delete all". */
@Composable
fun ChatKvCacheStorageCard(
    @StringRes titleRes: Int = R.string.native_chat_kv_cache_storage_title,
    @StringRes deleteAllLabelRes: Int = R.string.native_chat_kv_cache_delete_all,
    @StringRes deleteAllTitleRes: Int = R.string.native_chat_kv_cache_delete_all_title,
    @StringRes deleteAllMessageRes: Int = R.string.native_chat_kv_cache_delete_all_message
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NativeChatKvCacheStore(context.applicationContext) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var savedBytes by remember { mutableLongStateOf(0L) }
    var breakdown by remember { mutableStateOf<Map<KvCacheNamespace, Long>>(emptyMap()) }
    var confirmNamespace by remember { mutableStateOf<KvCacheNamespace?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var compressProgress by remember { mutableStateOf<KvCompressProgress?>(null) }
    var compressResult by remember { mutableStateOf<KvCompressResult?>(null) }
    var unpackMbPerSecond by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(refreshTick) {
        withContext(Dispatchers.IO) {
            unpackMbPerSecond = KvUnpackSpeedStore(context.applicationContext).measuredBytesPerMs()?.let { it / 1000.0 }
            totalBytes = store.totalBytes()
            savedBytes = store.compressionSavedBytes()
            breakdown = store.breakdown()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(titleRes),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.native_chat_kv_cache_storage_total,
                        Formatter.formatShortFileSize(context, totalBytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                OutlinedButton(enabled = totalBytes > 0, onClick = { confirm = true }) {
                    Text(stringResource(deleteAllLabelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = totalBytes > 0 && compressProgress == null,
                    onClick = {
                        compressResult = null
                        compressProgress = KvCompressProgress(0, 0)
                        scope.launch {
                            val coordinator = NativeChatKvCacheCoordinator(context.applicationContext)
                            compressResult = runCatching {
                                coordinator.compressAllNow { compressProgress = it }
                            }.getOrNull() ?: KvCompressResult(0, 0L)
                            compressProgress = null
                            refreshTick++
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.native_chat_kv_cache_compress_now),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val progress = compressProgress
                val result = compressResult
                val statusText = when {
                    progress != null -> stringResource(
                        R.string.native_chat_kv_cache_compressing, progress.done, progress.total
                    )
                    result != null && result.compressed > 0 -> stringResource(
                        R.string.native_chat_kv_cache_compress_saved,
                        Formatter.formatShortFileSize(context, result.savedBytes)
                    )
                    result != null -> stringResource(R.string.native_chat_kv_cache_compress_nothing)
                    else -> null
                }
                if (statusText != null) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            unpackMbPerSecond?.let { speed ->
                Text(
                    stringResource(R.string.native_chat_kv_cache_unpack_speed, speed.toInt().toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (savedBytes > 0L) {
                Text(
                    stringResource(
                        R.string.native_chat_kv_cache_saved_by_compression,
                        Formatter.formatShortFileSize(context, savedBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            KvCacheNamespace.values().forEach { namespace ->
                val bytes = breakdown[namespace] ?: 0L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.native_chat_delegation_storage_namespace_line,
                            stringResource(namespaceLabelRes(namespace)),
                            Formatter.formatShortFileSize(context, bytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    OutlinedButton(enabled = bytes > 0, onClick = { confirmNamespace = namespace }) {
                        Text(stringResource(R.string.native_chat_delegation_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(deleteAllTitleRes)) },
            text = {
                Text(
                    stringResource(deleteAllMessageRes),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.clearAll() }
                        refreshTick++
                    }
                }) { Text(stringResource(deleteAllLabelRes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    confirmNamespace?.let { namespace ->
        AlertDialog(
            onDismissRequest = { confirmNamespace = null },
            title = { Text(stringResource(R.string.native_chat_delegation_clear_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.native_chat_delegation_storage_clear_message,
                        stringResource(namespaceLabelRes(namespace))
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNamespace = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteNamespace(namespace) }
                        refreshTick++
                    }
                }) { Text(stringResource(R.string.native_chat_delegation_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNamespace = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

private fun namespaceLabelRes(namespace: KvCacheNamespace): Int = when (namespace) {
    KvCacheNamespace.CHAT -> R.string.native_chat_delegation_storage_chats
    KvCacheNamespace.DELEGATE -> R.string.native_chat_delegation_storage_subagents
    KvCacheNamespace.HARNESS -> R.string.native_chat_delegation_storage_harness
    KvCacheNamespace.BASE -> R.string.native_chat_kv_cache_namespace_base
}
