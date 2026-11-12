package com.example.llamadroid.ui.distributed.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/*
 * Controls shared by the main-device Advanced groups and the remote-control Settings tab, so the
 * same flags are never implemented twice. Values are plain text/booleans; each caller decides
 * where they are stored (local DistributedService state or the remote device).
 */

/** Flash attention switch with its one-line explanation. */
@Composable
internal fun FlashAttentionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.dist_flash_attention), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.dist_flash_attention_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** "Parallel requests" and "Cache RAM" side by side. */
@Composable
internal fun ParallelCacheRamRow(
    parallel: String,
    cacheRam: String,
    onParallelChange: (String) -> Unit,
    onCacheRamChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = parallel,
            onValueChange = onParallelChange,
            label = { Text(stringResource(R.string.dist_advanced_parallel), maxLines = 1) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("1") }
        )
        OutlinedTextField(
            value = cacheRam,
            onValueChange = onCacheRamChange,
            label = { Text(stringResource(R.string.dist_advanced_cache_ram), maxLines = 1) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("0") }
        )
    }
}

/** Extra command-line flags field. */
@Composable
internal fun CustomFlagsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.dist_advanced_custom_flags)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = false,
        placeholder = { Text("--load-mode mlock") }
    )
}
