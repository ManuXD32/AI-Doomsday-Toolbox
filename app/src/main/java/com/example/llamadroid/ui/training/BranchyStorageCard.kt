package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyRunStorage
import com.example.llamadroid.branchy.BranchyRunRepository
import com.example.llamadroid.branchy.BranchyRunStorageScan
import com.example.llamadroid.branchy.BranchyStoragePart
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Training hub summary of the space Branchy runs use; "Manage" opens the Branchy runs list. Hidden without runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BranchyStorageCard(onManage: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember(context) { BranchyRunRepository(context.applicationContext) }
    val runs = repo.observeAll().collectAsState(initial = emptyList()).value
    val summary = produceState<BranchyRunStorage?>(null, runs) {
        value = withContext(Dispatchers.IO) {
            BranchyRunStorageScan.summarize(runs.filter { it.runDir.isNotBlank() }.map { File(it.runDir) })
        }
    }.value
    if (runs.isNotEmpty()) {
        val checking = stringResource(R.string.model_storage_checking)
        fun bytes(part: BranchyStoragePart?): String = when {
            summary == null -> checking
            part == null -> FormatUtils.Display.formatBytes(context, summary.total)
            else -> FormatUtils.Display.formatBytes(context, summary.of(part))
        }
        Card(
            modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.branchy_r6_storage_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StorageValue(stringResource(R.string.branchy_r6_storage_card_total), bytes(null))
                    StorageValue(stringResource(R.string.branchy_r6_storage_part_tokenized), bytes(BranchyStoragePart.TOKENIZED))
                    StorageValue(stringResource(R.string.branchy_r6_storage_part_checkpoints), bytes(BranchyStoragePart.CHECKPOINTS))
                    StorageValue(stringResource(R.string.branchy_r6_storage_part_exports), bytes(BranchyStoragePart.EXPORTS))
                }
                TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.branchy_r6_storage_card_manage), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StorageValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}
