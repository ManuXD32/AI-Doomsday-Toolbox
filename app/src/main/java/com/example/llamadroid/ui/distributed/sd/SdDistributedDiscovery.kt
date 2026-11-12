@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.service.DistributedPerfText
import com.example.llamadroid.service.SdDiscoveredWorker
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdWorkerAdvert
import com.example.llamadroid.ui.distributed.common.DistributedHelperListItem
import com.example.llamadroid.ui.distributed.common.DistributedSearchingState

/**
 * Media helpers found nearby over NSD, shown like the LLM master's "Found nearby" list with a
 * one-tap add. Runs discovery while it is on screen; already saved helpers are hidden.
 */
@Composable
internal fun SdDiscoveredWorkersSection(
    saved: List<SdDistributedWorkerEntity>,
    onAdd: (SdDiscoveredWorker) -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        SdDistributedService.startDiscovery(context)
        onDispose { SdDistributedService.stopDiscovery() }
    }
    val discovered by SdDistributedService.discoveredWorkers.collectAsState()
    val nearby = SdWorkerAdvert.unsaved(discovered, saved)
    if (nearby.isEmpty()) {
        DistributedSearchingState()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dist_ui_found_nearby),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        nearby.forEach { worker ->
            DistributedHelperListItem(
                name = worker.name,
                address = worker.address,
                modifier = Modifier,
                advertLabel = DistributedPerfText.advertLabel(context, worker.ramMb, worker.cores),
                highlighted = true,
                trailing = {
                    FilledTonalIconButton(onClick = { onAdd(worker) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.dist_add_worker_btn)
                        )
                    }
                }
            )
        }
    }
}
