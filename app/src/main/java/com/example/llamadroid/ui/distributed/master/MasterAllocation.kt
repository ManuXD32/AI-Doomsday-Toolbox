package com.example.llamadroid.ui.distributed.master

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectAsState
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandEntity
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedDeviceKind
import com.example.llamadroid.service.DistributedDeviceRef
import com.example.llamadroid.service.DistributedKvPlacement
import com.example.llamadroid.service.DistributedKvPlacementMode
import com.example.llamadroid.service.DistributedLaunchResolution
import com.example.llamadroid.service.DistributedLayerAllocation
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedManualSplit
import com.example.llamadroid.service.DistributedLayerPlanning
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedPerfSettings
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.DistributedSpeculativePlacement
import com.example.llamadroid.service.DistributedWorkerLaunchSpec
import com.example.llamadroid.service.DraftModelPlacement
import com.example.llamadroid.service.DraftModelPlacementMode
import com.example.llamadroid.service.LlamaModelMetadataProbe
import com.example.llamadroid.service.LlamaModelProbeNoLayersException
import com.example.llamadroid.service.LlamaModelProbeResult
import com.example.llamadroid.service.LlamaModelProbeTimeoutException
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.MainModelPlacement
import com.example.llamadroid.service.MainModelPlacementMode
import com.example.llamadroid.service.MmprojPlacement
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.service.RemoteMasterServer
import com.example.llamadroid.service.ResolvedDistributedLaunch
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.service.stableKey
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun DistributedKvChoice(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    description: String
) {
    val shape = RoundedCornerShape(12.dp)
    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun DistributedKvTypeSelector(
    title: String,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("f16" to "F16", "q8_0" to "Q8_0", "q4_0" to "Q4_0").forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun DistributedPlacementStepCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        )
    }
}

@Composable
internal fun DistributedAllocationStrip(
    workers: List<SavedWorker>,
    residentWorkerId: Long?,
    includeMaster: Boolean,
    masterRamMiB: Int,
    selectedWorkerIds: Set<Long>?,
    offloadableLayers: Int?
) {
    val activeWorkers = when {
        residentWorkerId != null -> workers.filter { it.id == residentWorkerId }
        selectedWorkerIds != null -> workers.filter { it.id in selectedWorkerIds }
        else -> workers
    }
    val refs = buildList {
        if (includeMaster) add(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU))
        activeWorkers.forEach {
            add(DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}"))
        }
    }
    if (refs.isEmpty()) {
        Text(stringResource(R.string.dist_main_no_devices), color = MaterialTheme.colorScheme.error)
        return
    }
    val ramByKey = buildMap {
        put(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU).stableKey(), masterRamMiB)
        activeWorkers.forEach { worker ->
            put(DistributedDeviceRef(DistributedDeviceKind.WORKER, worker.id, "${worker.ip}:${worker.port}").stableKey(), worker.ramMB)
        }
    }
    val allocations = offloadableLayers?.let { layers ->
        runCatching {
            DistributedLlamaLaunchResolver.allocateLayersByRam(layers, refs) { ramByKey[it.stableKey()] ?: 0 }
        }.getOrNull()
    }
    val weights = refs.map { (ramByKey[it.stableKey()] ?: 0).toFloat().coerceAtLeast(0.01f) }
    val total = weights.sum().coerceAtLeast(0.01f)
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp))
    ) {
        refs.forEachIndexed { index, _ ->
            Box(
                modifier = Modifier.weight(weights[index] / total).fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .then(Modifier)
            ) {
                Canvas(Modifier.fillMaxSize()) { drawRect(colors[index % colors.size]) }
            }
        }
    }
    refs.forEachIndexed { index, ref ->
        val percent = (weights[index] / total * 100f).toInt()
        val label = if (ref.kind == DistributedDeviceKind.MASTER_CPU) {
            stringResource(R.string.dist_master_cpu)
        } else {
            activeWorkers.firstOrNull { it.id == ref.workerId }?.deviceName.orEmpty()
        }
        val layerText = allocations?.getOrNull(index)?.assignedLayers?.let { layers ->
            stringResource(R.string.dist_allocation_layers, layers)
        }.orEmpty()
        Text(
            stringResource(
                R.string.dist_allocation_device_summary,
                label,
                percent,
                ramByKey[ref.stableKey()] ?: 0,
                layerText
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** Endpoint key of the master in the persisted manual split values. */
internal const val MASTER_SPLIT_ENDPOINT = "local"

/** One input row of the PERCENT / LAYERS split. [endpoint] is "local" or "ip:port"; [stableKey] matches the launch profile. */
internal data class ManualSplitDevice(val endpoint: String, val stableKey: String, val name: String)

internal fun manualSplitKey(mode: DistributedLayerPlanning, endpoint: String): String = "${mode.name}:$endpoint"

/** Master first (when it takes part), then the helpers in their selection order: the same order the launch profile uses. */
internal fun manualSplitDevicesFor(
    distributed: Boolean,
    includeMaster: Boolean,
    workers: List<SavedWorker>,
    masterName: String
): List<ManualSplitDevice> {
    if (!distributed) return emptyList()
    return buildList {
        if (includeMaster) {
            add(
                ManualSplitDevice(
                    MASTER_SPLIT_ENDPOINT,
                    DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU).stableKey(),
                    masterName
                )
            )
        }
        workers.forEach {
            val endpoint = "${it.ip}:${it.port}"
            add(
                ManualSplitDevice(
                    endpoint,
                    DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, endpoint).stableKey(),
                    it.deviceName
                )
            )
        }
    }
}

/** Per-device number fields with a live total line and a validation error, shown for PERCENT / LAYERS. */
@Composable
internal fun ManualSplitInputs(s: MasterScreenState) {
    val devices = s.manualSplitDevices
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.dist_r3_split_needs_distributed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val percent = s.layerPlanning == DistributedLayerPlanning.PERCENT
    devices.forEach { device ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                device.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedTextField(
                value = s.manualSplitValue(device)?.toString().orEmpty(),
                onValueChange = { text ->
                    s.updateManualSplitValue(device, text.filter(Char::isDigit).take(5).toIntOrNull())
                },
                modifier = Modifier.width(120.dp),
                singleLine = true,
                suffix = { Text(if (percent) "%" else stringResource(R.string.dist_r3_split_layers_suffix)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !s.isServerRunning
            )
        }
    }
    val result = s.manualSplitResult
    val enteredTotal = devices.sumOf { s.manualSplitValue(it) ?: 0 }
    val layers = s.effectiveLayerResult?.offloadableLayers
    val invalid = result as? DistributedManualSplit.Result.Invalid
    Text(
        text = when {
            percent -> stringResource(R.string.dist_r3_split_total_percent, enteredTotal)
            layers != null -> stringResource(R.string.dist_r3_split_total_layers, enteredTotal, layers)
            else -> stringResource(R.string.dist_r3_split_total_layers_unknown, enteredTotal)
        },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (invalid != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
    if (invalid != null) {
        Text(
            text = when (invalid.reason) {
                DistributedManualSplit.Reason.INVALID_VALUE -> stringResource(R.string.dist_r3_split_err_value)
                DistributedManualSplit.Reason.TOTAL_MISMATCH -> if (percent) {
                    stringResource(R.string.dist_r3_split_err_total_percent, invalid.total)
                } else {
                    stringResource(R.string.dist_r3_split_err_total_layers, layers ?: 0, invalid.total)
                }
                DistributedManualSplit.Reason.ZERO_LAYERS -> stringResource(
                    R.string.dist_r3_split_err_zero_layers,
                    devices.getOrNull(invalid.deviceIndex ?: -1)?.name.orEmpty()
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
