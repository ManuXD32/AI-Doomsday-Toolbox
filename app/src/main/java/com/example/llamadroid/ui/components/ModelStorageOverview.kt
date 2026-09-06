package com.example.llamadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llamadroid.R
import com.example.llamadroid.data.model.ModelStorageInventory
import com.example.llamadroid.data.model.ModelStorageRepository
import com.example.llamadroid.data.model.StorageUsage
import com.example.llamadroid.util.FormatUtils

@Composable
fun rememberModelStorageInventory(): ModelStorageInventory {
    val context = LocalContext.current
    val repository = remember(context) { ModelStorageRepository.get(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, repository) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) repository.refresh() }
        owner.lifecycle.addObserver(observer)
        repository.refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return repository.inventory.collectAsStateWithLifecycle().value
}

@Composable
fun ModelStorageCount(usage: StorageUsage, modifier: Modifier = Modifier, loaded: Boolean = true) {
    val text = if (loaded) stringResource(R.string.model_storage_count_bytes, usage.count,
        FormatUtils.Display.formatBytes(LocalContext.current, usage.bytes)) else stringResource(R.string.model_storage_checking)
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelStorageOverviewCard(snapshot: ModelStorageInventory, family: String? = null) {
    val context = LocalContext.current
    val checking = stringResource(R.string.model_storage_checking)
    fun bytes(value: Long) = if (snapshot.loaded) FormatUtils.Display.formatBytes(context, value) else checking
    val total = bytes(snapshot.totalBytes)
    val free = bytes(snapshot.freeBytes)
    val models = bytes(snapshot.modelsBytes)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.models_storage_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageValue(stringResource(R.string.models_storage_total), total)
                StorageValue(stringResource(R.string.models_storage_free), free)
                StorageValue(stringResource(R.string.models_storage_models), models)
            }
            val totalBytes = snapshot.totalBytes.coerceAtLeast(1L).toDouble()
            val modelFraction = (snapshot.modelsBytes / totalBytes).toFloat().coerceIn(0f, 1f)
            val usedFraction = ((snapshot.totalBytes - snapshot.freeBytes) / totalBytes).toFloat().coerceIn(modelFraction, 1f)
            val description = stringResource(R.string.models_storage_bar_desc, total, free, models)
            Row(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = description }) {
                if (modelFraction > 0f) Spacer(Modifier.weight(modelFraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                if (usedFraction > modelFraction) Spacer(Modifier.weight(usedFraction - modelFraction).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
                if (usedFraction < 1f) Spacer(Modifier.weight(1f - usedFraction))
            }
            family?.let { ModelStorageCount(snapshot.usage(it), loaded = snapshot.loaded) }
            if (snapshot.pendingBytes > 0L) Text(stringResource(R.string.model_storage_unknown_bytes, bytes(snapshot.pendingBytes)), style = MaterialTheme.typography.bodySmall)
            if (snapshot.downloadBytes > 0L) Text(stringResource(R.string.model_storage_partial_bytes, bytes(snapshot.downloadBytes)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StorageValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
