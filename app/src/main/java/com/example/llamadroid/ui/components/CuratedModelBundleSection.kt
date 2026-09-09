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
import androidx.compose.runtime.LaunchedEffect
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
import android.widget.Toast
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_DISCARDED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_STALE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.BundleProgressEntry
import com.example.llamadroid.data.model.BundleProgressSnapshot
import com.example.llamadroid.data.model.CuratedBundleFile
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.buildDownloadTaskId
import com.example.llamadroid.data.model.calculateBundleProgressSnapshot
import com.example.llamadroid.data.model.curatedVisionRepairTargets
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.data.model.runtimeIsVision
import com.example.llamadroid.data.model.sanitizeCuratedBundlePrefix
import com.example.llamadroid.data.model.toPendingDownload
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.FormatUtils

private val CURATED_TERMINAL_DOWNLOAD_STATUSES = setOf(
    DOWNLOAD_TASK_STATUS_COMPLETED,
    DOWNLOAD_TASK_STATUS_CANCELLED,
    DOWNLOAD_TASK_STATUS_FAILED,
    DOWNLOAD_TASK_STATUS_DISCARDED,
    DOWNLOAD_TASK_STATUS_STALE
)

internal data class CuratedResolvedFile(
    val file: CuratedBundleFile,
    val expectedName: String,
    val taskId: String,
    val installedModel: ModelEntity?,
    val task: DownloadTaskEntity?,
    val liveValue: Float?
) {
    val installed: Boolean get() = installedModel != null
    val cancelled: Boolean
        get() = task?.status == DOWNLOAD_TASK_STATUS_CANCELLED ||
            liveValue != null && liveValue < 0f && liveValue != DownloadProgressHolder.INDETERMINATE
    val completed: Boolean
        get() = task?.status == DOWNLOAD_TASK_STATUS_COMPLETED || liveValue == 1f
    val active: Boolean
        get() = !installed && !completed && !cancelled &&
            task?.status !in CURATED_TERMINAL_DOWNLOAD_STATUSES && (
            task?.status == DOWNLOAD_TASK_STATUS_ACTIVE ||
                liveValue == DownloadProgressHolder.INDETERMINATE ||
                liveValue != null && liveValue in 0f..0.999f
            )
}

internal fun resolveCuratedFiles(
    bundle: CuratedModelBundle,
    prefix: String,
    installedModels: List<ModelEntity>,
    taskByProgressKey: Map<String, DownloadTaskEntity>,
    progressMap: Map<String, Float>
): List<CuratedResolvedFile> = bundle.files.map { file ->
    val expectedName = file.installedFilename(prefix)
    val taskId = buildDownloadTaskId(file.repoId, expectedName, file.type)
    CuratedResolvedFile(
        file = file,
        expectedName = expectedName,
        taskId = taskId,
        installedModel = installedModels.firstOrNull { model ->
            file.matchesVerifiedInstalledModel(expectedName, model)
        },
        task = taskByProgressKey[taskId],
        liveValue = progressMap[taskId]
    )
}

internal fun CuratedResolvedFile.toProgressEntry(): BundleProgressEntry = BundleProgressEntry(
    key = taskId,
    declaredBytes = file.sizeBytes,
    installed = installed,
    completed = completed,
    cancelled = cancelled,
    active = active,
    persistedTaskBytes = task?.bytesDownloaded,
    partBytes = task?.partFile()?.length(),
    liveFraction = liveValue
)

internal fun isSharedCuratedFile(file: CuratedBundleFile): Boolean =
    !file.sharedArtifactKey.isNullOrBlank()

internal fun CuratedResolvedFile.isOwnedBy(
    context: android.content.Context,
    bundleId: String
): Boolean = CuratedBundleDownloadOwnership.owners(context, taskId).contains(bundleId)

/**
 * Starts every missing file in a curated bundle, registering explicit owners
 * before starting the worker. Existing active tasks are reused by task ID,
 * which preserves deduplication for shared audio components.
 */
internal fun requestCuratedBundleDownload(
    context: android.content.Context,
    bundle: CuratedModelBundle,
    prefix: String = bundle.defaultPrefix,
    installedModels: List<ModelEntity>,
    persistedTasks: List<DownloadTaskEntity> = emptyList(),
    progressMap: Map<String, Float> = emptyMap()
): CuratedBundleDownloadResult {
    val repository = ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
    val taskByProgressKey = persistedTasks
        .asSequence()
        .flatMap { task -> sequenceOf(task.id to task, task.progressKey to task) }
        .toMap()
    var requested = 0
    var failed = 0
    resolveCuratedFiles(bundle, prefix, installedModels, taskByProgressKey, progressMap).forEach { resolved ->
        if (resolved.installed) return@forEach
        requested += 1
        CuratedBundleDownloadOwnership.addOwner(context, resolved.taskId, bundle.id)
        val taskIsActive = resolved.task?.status !in CURATED_TERMINAL_DOWNLOAD_STATUSES && (
            resolved.task?.status == DOWNLOAD_TASK_STATUS_ACTIVE ||
            resolved.liveValue == DownloadProgressHolder.INDETERMINATE ||
            resolved.liveValue != null && resolved.liveValue in 0f..0.999f
            )
        if (taskIsActive) return@forEach
        runCatching {
            if (resolved.task?.status == DOWNLOAD_TASK_STATUS_CANCELLED) {
                val recovered = resolved.task.toPendingDownload()
                PendingDownloadHolder.putPending(
                    downloadId = resolved.task.id,
                    pending = recovered.copy(
                        type = resolved.file.type,
                        isVision = bundle.runtimeIsVision(resolved.file),
                        artifactFamily = resolved.file.audioFamily ?: recovered.artifactFamily,
                        artifactRole = resolved.file.componentRole ?: recovered.artifactRole
                    )
                )
                DownloadService.resumeDownload(
                    context = context,
                    downloadId = resolved.task.id,
                    explicitRetry = true
                )
            } else {
                repository.startDownloadAsync(
                    repoId = resolved.file.repoId,
                    filename = resolved.file.remotePath,
                    type = resolved.file.type,
                    isVision = bundle.runtimeIsVision(resolved.file),
                    downloadUrlOverride = resolved.file.downloadUrl,
                    localFilenameOverride = resolved.expectedName,
                    artifactFamily = resolved.file.audioFamily,
                    artifactRole = resolved.file.componentRole
                )
            }
        }.onFailure {
            failed += 1
            CuratedBundleDownloadOwnership.releaseOwner(context, resolved.taskId, bundle.id)
        }
    }
    return CuratedBundleDownloadResult(requested = requested, failed = failed)
}

internal data class CuratedBundleDownloadResult(
    val requested: Int,
    val failed: Int
)

private fun cancelCuratedBundleDownloads(
    context: android.content.Context,
    bundle: CuratedModelBundle,
    resolvedFiles: List<CuratedResolvedFile>
) {
    resolvedFiles
        .filter { it.active }
        .forEach { resolved ->
            val shouldCancel = if (isSharedCuratedFile(resolved.file)) {
                CuratedBundleDownloadOwnership.releaseOwner(context, resolved.taskId, bundle.id)
            } else {
                true
            }
            if (shouldCancel) {
                DownloadService.cancelDownload(context, resolved.expectedName, resolved.taskId)
            }
        }
}

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
    prefixForBundle: (CuratedModelBundle) -> String = { it.defaultPrefix },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
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
    val visionRepairPrefixes = bundles.associate { bundle ->
        bundle.id to resolveCuratedBundlePrefix(bundle, prefixForBundle).value
    }
    var pendingBundle by remember { mutableStateOf<CuratedModelBundle?>(null) }
    val sharedFiles = remember(bundles) {
        bundles
            .flatMap { it.files }
            .filter(::isSharedCuratedFile)
            .distinctBy { file ->
                buildDownloadTaskId(file.repoId, file.installedFilename(""), file.type)
            }
    }
    val sharedResolvedFiles = remember(
        bundles,
        installedModels,
        persistedTasks,
        progressMap
    ) {
        sharedFiles.map { file ->
            val expectedName = file.installedFilename("")
            val taskId = buildDownloadTaskId(file.repoId, expectedName, file.type)
            CuratedResolvedFile(
                file = file,
                expectedName = expectedName,
                taskId = taskId,
                installedModel = installedModels.firstOrNull { model ->
                    file.matchesVerifiedInstalledModel(expectedName, model)
                },
                task = taskByProgressKey[taskId],
                liveValue = progressMap[taskId]
            )
        }
    }
    val sharedProgressHistory = remember { mutableMapOf<String, BundleProgressSnapshot>() }
    val sharedProgress = remember(sharedResolvedFiles, sharedProgressHistory["__shared__"]) {
        val entries = sharedResolvedFiles.map { it.toProgressEntry() }
        val hasActive = entries.any { it.active && !it.cancelled && !it.installed && !it.completed }
        val previous = sharedProgressHistory["__shared__"]
        calculateBundleProgressSnapshot(
            entries = entries,
            previousSnapshot = previous.takeIf { hasActive },
            resetToPersisted = !hasActive && previous?.hasActiveDownloads == true &&
                entries.any { !it.installed }
        )
    }
    SideEffect {
        if (sharedResolvedFiles.isNotEmpty()) sharedProgressHistory["__shared__"] = sharedProgress
    }
    LaunchedEffect(persistedTasks) {
        persistedTasks
            .filter { task ->
                task.status in CURATED_TERMINAL_DOWNLOAD_STATUSES
            }
            .forEach { task ->
                CuratedBundleDownloadOwnership.clear(context, task.id)
                CuratedBundleDownloadOwnership.clear(context, task.progressKey)
            }
    }
    LaunchedEffect(visionRepairPrefixes, installedModels) {
        bundles.forEach { bundle ->
            curatedVisionRepairTargets(
                bundle = bundle,
                prefix = visionRepairPrefixes[bundle.id].orEmpty(),
                installedModels = installedModels
            ).forEach { model ->
                db.modelDao().updateVisionSupport(model.filename, true)
            }
        }
    }

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

        if (sharedResolvedFiles.isNotEmpty() && sharedProgress.fileCount > 0 &&
            sharedProgress.completedFileCount < sharedProgress.fileCount &&
            (sharedProgress.hasActiveDownloads || sharedProgress.downloadedBytes > 0L)
        ) {
            CuratedSharedComponentsProgress(sharedProgress)
        }

        bundles.forEach { bundle ->
            val prefixState = resolveCuratedBundlePrefix(bundle, prefixForBundle)
            val prefix = prefixState.value
            val resolvedFiles = resolveCuratedFiles(
                bundle = bundle,
                prefix = prefix,
                installedModels = installedModels,
                taskByProgressKey = taskByProgressKey,
                progressMap = progressMap
            )
            val installedFiles = resolvedFiles.mapNotNull { it.installedModel }
            val missingFiles = resolvedFiles.filterNot { it.installed }.map { it.file }
            val conflictingFiles = resolvedFiles.filter { resolved ->
                installedModels.any { model ->
                    model.filename == resolved.expectedName &&
                        !resolved.file.matchesVerifiedInstalledModel(resolved.expectedName, model)
                }
            }
            val progressEntries = resolvedFiles.map { it.toProgressEntry() }
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
            val directActive = resolvedFiles.any { it.active && !isSharedCuratedFile(it.file) }
            val sharedOwnedActive = resolvedFiles.any {
                it.active && isSharedCuratedFile(it.file) && it.isOwnedBy(context, bundle.id)
            }

            CuratedModelBundleCard(
                bundle = bundle,
                installedCount = installedFiles.size,
                missingBytes = progressSnapshot.remainingBytes,
                isDownloading = directActive || sharedOwnedActive,
                aggregateProgress = progressSnapshot.progress,
                canUse = missingFiles.isEmpty() && !prefixState.invalid && onUseBundle != null,
                hasConflicts = conflictingFiles.isNotEmpty(),
                hasPrefixError = prefixState.invalid,
                onReview = { pendingBundle = bundle },
                onCancel = {
                    cancelCuratedBundleDownloads(context, bundle, resolvedFiles)
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
        val prefixState = resolveCuratedBundlePrefix(bundle, prefixForBundle)
        val prefix = prefixState.value
        val expectedNames = bundle.files.map { it.installedFilename(prefix) }
        val installedFiles = bundle.files.mapIndexedNotNull { index, file ->
            installedModels.firstOrNull { model ->
                file.matchesVerifiedInstalledModel(expectedNames[index], model)
            }
        }
        val installedNames = installedFiles.map { it.filename }.toSet()
        val missingFiles = bundle.files.filterIndexed { index, _ ->
            expectedNames[index] !in installedNames
        }
        val conflictingFiles = bundle.files.filterIndexed { index, _ ->
            installedModels.any { model ->
                model.filename == expectedNames[index] &&
                    !bundle.files[index].matchesVerifiedInstalledModel(expectedNames[index], model)
            }
        }
        CuratedModelBundleDialog(
            bundle = bundle,
            expectedNames = expectedNames,
            missingFiles = missingFiles,
            conflictingFiles = conflictingFiles,
            prefix = prefix,
            prefixInvalid = prefixState.invalid,
            onDismiss = { pendingBundle = null },
            onDownload = {
                if (!prefixState.invalid && conflictingFiles.isEmpty()) {
                    val result = requestCuratedBundleDownload(
                        context = context,
                        bundle = bundle,
                        prefix = prefix,
                        installedModels = installedModels,
                        persistedTasks = persistedTasks,
                        progressMap = progressMap
                    )
                    if (result.failed > 0) {
                        Toast.makeText(
                            context,
                            R.string.audio_bundle_download_start_error,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    pendingBundle = null
                }
            }
        )
    }
}

private data class CuratedPrefixState(
    val value: String,
    val invalid: Boolean
)

private fun resolveCuratedBundlePrefix(
    bundle: CuratedModelBundle,
    prefixForBundle: (CuratedModelBundle) -> String
): CuratedPrefixState {
    val raw = runCatching { prefixForBundle(bundle) }.getOrNull()
    val sanitized = raw?.let { runCatching { sanitizeCuratedBundlePrefix(it) }.getOrNull() }
    val invalid = raw == null || (raw.trim().isNotEmpty() && sanitized.isNullOrBlank())
    if (!invalid) return CuratedPrefixState(sanitized.orEmpty(), false)
    val fallback = runCatching { sanitizeCuratedBundlePrefix(bundle.defaultPrefix) }.getOrDefault("")
    return CuratedPrefixState(fallback, true)
}

@Composable
private fun CuratedModelBundleCard(
    bundle: CuratedModelBundle,
    installedCount: Int,
    missingBytes: Long,
    isDownloading: Boolean,
    aggregateProgress: Float?,
    canUse: Boolean,
    hasConflicts: Boolean,
    hasPrefixError: Boolean,
    onReview: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit
) {
    val context = LocalContext.current
    val allInstalled = installedCount == bundle.files.size && !hasPrefixError
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
            if (hasConflicts) {
                Text(
                    stringResource(R.string.curated_bundle_conflict),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (hasPrefixError) {
                Text(
                    stringResource(R.string.audio_models_prefix_invalid),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
    conflictingFiles: List<CuratedBundleFile>,
    prefix: String,
    prefixInvalid: Boolean,
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
                    stringResource(R.string.sd_bundle_prefix_note, prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (prefixInvalid) {
                    Text(
                        stringResource(R.string.audio_models_prefix_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    stringResource(R.string.sd_bundle_total, FormatUtils.Display.formatBytes(context, bundle.totalBytes)),
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                bundle.files.forEachIndexed { index, file ->
                    CuratedBundleFileRow(
                        file = file,
                        installedFilename = expectedNames[index],
                        isMissing = file in missingFiles,
                        isConflict = file in conflictingFiles
                    )
                }
                if (missingFiles.isEmpty()) {
                    Text(stringResource(R.string.sd_bundle_already_installed), color = MaterialTheme.colorScheme.primary)
                }
                if (conflictingFiles.isNotEmpty()) {
                    Text(stringResource(R.string.curated_bundle_conflict), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                enabled = missingFiles.isNotEmpty() && conflictingFiles.isEmpty() && !prefixInvalid,
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
    isMissing: Boolean,
    isConflict: Boolean
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
                if (isConflict) {
                    Text(
                        stringResource(R.string.curated_bundle_conflict_file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
    ModelType.LLAMA_TTS,
    ModelType.LLAMA_TTS_COMPANION -> stringResource(R.string.model_promote_audio_tts)
    else -> type.name
}
