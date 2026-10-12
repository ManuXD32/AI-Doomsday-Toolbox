package com.example.llamadroid.ui.settings

import android.text.format.Formatter
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
import com.example.llamadroid.service.NativeChatKvCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings card showing the disk used by per-chat KV caches, with a confirmed "Delete all". */
@Composable
fun ChatKvCacheStorageCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NativeChatKvCacheStore(context.applicationContext) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        totalBytes = withContext(Dispatchers.IO) { store.totalBytes() }
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
                stringResource(R.string.native_chat_kv_cache_storage_title),
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
                    Text(stringResource(R.string.native_chat_kv_cache_delete_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.native_chat_kv_cache_delete_all_title)) },
            text = {
                Text(
                    stringResource(R.string.native_chat_kv_cache_delete_all_message),
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
                }) { Text(stringResource(R.string.native_chat_kv_cache_delete_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}
