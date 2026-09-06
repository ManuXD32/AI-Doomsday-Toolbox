package com.example.llamadroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.SmartToy
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.BundleProgressEntry
import com.example.llamadroid.data.model.BundleProgressSnapshot
import com.example.llamadroid.data.model.CuratedBundleFile
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.buildDownloadTaskId
import com.example.llamadroid.data.model.calculateBundleProgressSnapshot
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.data.model.sanitizeCuratedBundlePrefix
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.FormatUtils

/**
 * Curated downloads use the same review-first card treatment as Stable Diffusion bundles.
 * Their fixed catalog prefixes keep related files identifiable and make installed-state
 * detection deterministic.
 */
@Composable
fun CuratedModelBundleSection(
    title: String,
    description: String,
    bundles: List<CuratedModelBundle>,
    onUseBundle: ((CuratedModelBundle, List<ModelEntity>, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val installedModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    val persistedTasks by db.downloadTaskDao().observeAll().collectAsState(initial = emptyList())
    val bundleProgressHistory = remember { mutableMapOf<String, BundleProgressSnapshot>() }
    val taskByProgressKey = remember(persistedTasks) {
        persistedTasks
            .asSequence()
            .flatMap { task -> sequenceOf(task.id to task, task.progressKey to task) }
            .toMap()
    }
    var pendingBundle by remember { mutableStateOf<CuratedModelBundle?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        bundles.forEach { bundle ->
            val prefix = sanitizeCuratedBundlePrefix(bundle.defaultPrefix)
            val expectedNames = bundle.files.map { it.installedFilename(prefix) }
            val installedFiles = installedModels.filter { it.filename in expectedNames }
            val installedNames = installedFiles.map { it.filename }.toSet()
            val missingFiles = bundle.files.filterIndexed { index, _ ->
                expectedNames[index] !in installedNames
            }
            val expectedTaskIds = bundle.files.mapIndexed { index, file ->
                expectedNames[index] to buildDownloadTaskId(file.repoId, expectedNames[index], file.type)
            }
            val progressEntries = bundle.files.mapIndexed { index, file ->
                val (expectedName, taskId) = expectedTaskIds[index]
                val task = taskByProgressKey[taskId]
                val liveValue = progressMap[taskId]
                BundleProgressEntry(
                    key = taskId,
                    declaredBytes = file.sizeBytes,
                    installed = expectedName in installedNames,
                    completed = task?.status == DOWNLOAD_TASK_STATUS_COMPLETED || liveValue == 1f,
                    cancelled = task?.status == DOWNLOAD_TASK_STATUS_CANCELLED ||
                        liveValue != null && liveValue < 0f && liveValue != DownloadProgressHolder.INDETERMINATE,
                    active = task?.status == DOWNLOAD_TASK_STATUS_ACTIVE ||
                        liveValue == DownloadProgressHolder.INDETERMINATE || liveValue != null && liveValue in 0f..0.999f,
                    persistedTaskBytes = task?.bytesDownloaded,
                    partBytes = task?.partFile()?.length(),
                    liveFraction = liveValue
                )
            }
            val previousSnapshot = bundleProgressHistory[bundle.id]
            val hasCurrentActive = progressEntries.any {
                it.active && !it.cancelled && !it.installed && !it.completed
            }
            val resetAfterCancellation = !hasCurrentActive &&
                previousSnapshot?.hasActiveDownloads == true &&
                missingFiles.isNotEmpty()
            val progressSnapshot = calculateBundleProgressSnapshot(
                entries = progressEntries,
                // Monotonicity belongs to an active download session. Once the bundle is idle,
                // recompute from durable state so deleting an installed file cannot leave a stale
                // 100% snapshot behind.
                previousSnapshot = previousSnapshot.takeIf { hasCurrentActive },
                resetToPersisted = resetAfterCancellation
            )
            // Only advance the monotonic history after this composition is successfully applied.
            // This avoids mutating retained calculation state from an abandoned composition.
            SideEffect {
                bundleProgressHistory[bundle.id] = progressSnapshot
            }
            val activeKeys = progressEntries
                .filter { it.active && !it.cancelled && !it.installed && !it.completed }
                .map { it.key }
                .toSet()
            val activeTasks = expectedTaskIds
                .filter { (_, taskId) -> taskId in activeKeys }

            CuratedModelBundleCard(
                bundle = bundle,
                installedCount = installedFiles.size,
                missingBytes = progressSnapshot.remainingBytes,
                isDownloading = progressSnapshot.hasActiveDownloads,
                aggregateProgress = progressSnapshot.progress,
                canUse = missingFiles.isEmpty() && onUseBundle != null,
                onReview = { pendingBundle = bundle },
                onCancel = {
                    activeTasks.forEach { (filename, taskId) ->
                        DownloadService.cancelDownload(context, filename, taskId)
                    }
                },
                onUse = {
                    onUseBundle?.invoke(bundle, installedFiles, prefix)
                }
            )
        }

        Text(
            stringResource(R.string.curated_bundle_license_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    pendingBundle?.let { bundle ->
        val prefix = sanitizeCuratedBundlePrefix(bundle.defaultPrefix)
        val expectedNames = bundle.files.map { it.installedFilename(prefix) }
        val installedFiles = installedModels.filter { it.filename in expectedNames }
        val missingFiles = bundle.files.filterIndexed { index, _ ->
            installedFiles.none { it.filename == expectedNames[index] }
        }
        CuratedModelBundleDialog(
            bundle = bundle,
            expectedNames = expectedNames,
            missingFiles = missingFiles,
            onDismiss = { pendingBundle = null },
            onDownload = {
                bundle.files.forEachIndexed { index, file ->
                    val localFilename = expectedNames[index]
                    if (installedFiles.none { it.filename == localFilename }) {
                        repository.startDownloadAsync(
                            repoId = file.repoId,
                            filename = file.remotePath,
                            type = file.type,
                            isVision = file.type == ModelType.VISION_PROJECTOR,
                            downloadUrlOverride = file.downloadUrl,
                            localFilenameOverride = localFilename
                        )
                    }
                }
                pendingBundle = null
            }
        )
    }
}

@Composable
private fun CuratedModelBundleCard(
    bundle: CuratedModelBundle,
    installedCount: Int,
    missingBytes: Long,
    isDownloading: Boolean,
    aggregateProgress: Float?,
    canUse: Boolean,
    onReview: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit
) {
    val context = LocalContext.current
    val allInstalled = installedCount == bundle.files.size
    val catalogIcon = if (bundle.files.any { it.type == ModelType.LLM || it.type == ModelType.LLM_DRAFT }) {
        Icons.Default.SmartToy
    } else {
        Icons.Default.Collections
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading, onClick = onReview),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            if (allInstalled) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (allInstalled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        catalogIcon,
                        contentDescription = null,
                        tint = if (allInstalled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(bundle.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(bundle.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    isDownloading -> IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.sd_bundle_cancel),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    allInstalled -> Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.curated_bundle_status_ready), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (bundle.capabilityRes.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bundle.capabilityRes.forEach { capabilityRes ->
                        AssistChip(onClick = {}, label = { Text(stringResource(capabilityRes)) })
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.sd_bundle_total, FormatUtils.Display.formatBytes(context, bundle.totalBytes))) },
                    leadingIcon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.sd_bundle_file_count, bundle.files.size)) }
                )
            }
            Text(
                stringResource(R.string.sd_bundle_installed_progress, installedCount, bundle.files.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isDownloading) {
                LinearProgressIndicator(
                    progress = {
                        aggregateProgress ?: installedCount.toFloat() / bundle.files.size.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (missingBytes > 0L) {
                Text(
                    stringResource(R.string.sd_bundle_missing_storage, FormatUtils.Display.formatBytes(context, missingBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                isDownloading && aggregateProgress == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                isDownloading -> {
                    LinearProgressIndicator(
                        progress = { aggregateProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.sd_bundle_download_progress, ((aggregateProgress ?: 0f) * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                installedCount < bundle.files.size -> Button(
                    onClick = onReview,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_bundle_review_download))
                }
                canUse -> OutlinedButton(
                    onClick = onUse,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.phase_c_bundle_use))
                }
            }
        }
    }
}

@Composable
private fun CuratedModelBundleDialog(
    bundle: CuratedModelBundle,
    expectedNames: List<String>,
    missingFiles: List<CuratedBundleFile>,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sd_bundle_dialog_title, stringResource(bundle.titleRes))) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(bundle.descriptionRes))
                Text(
                    stringResource(R.string.sd_bundle_prefix_note, sanitizeCuratedBundlePrefix(bundle.defaultPrefix)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.sd_bundle_total, FormatUtils.Display.formatBytes(context, bundle.totalBytes)),
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                bundle.files.forEachIndexed { index, file ->
                    CuratedBundleFileRow(
                        file = file,
                        installedFilename = expectedNames[index],
                        isMissing = file in missingFiles
                    )
                }
                if (missingFiles.isEmpty()) {
                    Text(stringResource(R.string.sd_bundle_already_installed), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                enabled = missingFiles.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (missingFiles.isEmpty()) stringResource(R.string.sd_bundle_installed)
                    else stringResource(R.string.sd_bundle_download_missing, missingFiles.size)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun CuratedBundleFileRow(
    file: CuratedBundleFile,
    installedFilename: String,
    isMissing: Boolean
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    installedFilename,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.sd_bundle_file_meta,
                        FormatUtils.Display.formatBytes(context, file.sizeBytes),
                        curatedBundleModelTypeLabel(file.type)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.sd_bundle_source_repo, file.repoId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.sd_bundle_license, file.license),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isMissing) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.desc_downloaded),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun curatedBundleModelTypeLabel(type: ModelType): String = when (type) {
    ModelType.LLM -> stringResource(R.string.models_type_llm)
    ModelType.LLM_DRAFT -> stringResource(R.string.curated_bundle_type_draft_model)
    ModelType.VISION_PROJECTOR -> stringResource(R.string.models_type_vision_projector)
    ModelType.SD_ADETAILER -> stringResource(R.string.curated_bundle_type_adetailer_detector)
    else -> type.name
}
