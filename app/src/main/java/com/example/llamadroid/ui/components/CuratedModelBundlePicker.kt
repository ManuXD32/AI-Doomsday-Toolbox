package com.example.llamadroid.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_DISCARDED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_STALE
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.BundleProgressSnapshot
import com.example.llamadroid.data.model.CuratedBundleFile
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.StableAudioModelSupport
import com.example.llamadroid.data.model.buildDownloadTaskId
import com.example.llamadroid.data.model.calculateBundleProgressSnapshot

private fun pickerComponentRole(file: CuratedBundleFile): String? =
    StableAudioModelSupport.canonicalRole(file.componentRole)
        ?: file.componentRole?.trim()?.takeIf { it.isNotEmpty() }

@Composable
internal fun CuratedSharedComponentsProgress(snapshot: BundleProgressSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.audio_bundle_shared_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.audio_bundle_shared_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.hasIndeterminateLiveTask) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { snapshot.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                stringResource(
                    R.string.audio_bundle_shared_status,
                    snapshot.completedFileCount,
                    snapshot.fileCount,
                    snapshot.percentage
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Contextual picker backed by the same curated registry and installed model
 * rows used by the canonical bundle section. A ready bundle can be applied;
 * an incomplete bundle only offers an explicit download action.
 */
@Composable
fun CuratedModelBundlePicker(
    bundles: List<CuratedModelBundle>,
    family: String,
    selectedComponents: Map<String, String>,
    onUseBundle: (CuratedModelBundle, Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val installedModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    val persistedTasks by db.downloadTaskDao().observeAll().collectAsState(initial = emptyList())
    val taskByProgressKey = remember(persistedTasks) {
        persistedTasks.asSequence()
            .flatMap { task -> sequenceOf(task.id to task, task.progressKey to task) }
            .toMap()
    }
    LaunchedEffect(persistedTasks) {
        persistedTasks
            .filter { task ->
                task.status in setOf(
                    DOWNLOAD_TASK_STATUS_COMPLETED,
                    DOWNLOAD_TASK_STATUS_CANCELLED,
                    DOWNLOAD_TASK_STATUS_FAILED,
                    DOWNLOAD_TASK_STATUS_DISCARDED,
                    DOWNLOAD_TASK_STATUS_STALE
                )
            }
            .forEach { task ->
                CuratedBundleDownloadOwnership.clear(context, task.id)
                CuratedBundleDownloadOwnership.clear(context, task.progressKey)
            }
    }
    val matchingBundles = remember(bundles, family) {
        bundles.filter { bundle ->
            bundle.files.any { file ->
                file.audioFamily == family && file.type == ModelType.LITERT_AUDIO_DIT
            }
        }
    }
    val progressHistory = remember { mutableMapOf<String, BundleProgressSnapshot>() }
    val sharedFiles = remember(matchingBundles) {
        matchingBundles.flatMap { it.files }
            .filter(::isSharedCuratedFile)
            .distinctBy { file ->
                buildDownloadTaskId(file.repoId, file.installedFilename(""), file.type)
            }
    }
    val sharedResolvedFiles = remember(matchingBundles, installedModels, persistedTasks, progressMap) {
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
    val sharedPrevious = progressHistory["__shared__"]
    val sharedProgress = remember(sharedResolvedFiles, sharedPrevious) {
        val entries = sharedResolvedFiles.map { it.toProgressEntry() }
        val hasActive = entries.any { it.active && !it.cancelled && !it.installed && !it.completed }
        calculateBundleProgressSnapshot(
            entries = entries,
            previousSnapshot = sharedPrevious.takeIf { hasActive },
            resetToPersisted = !hasActive && sharedPrevious?.hasActiveDownloads == true &&
                entries.any { !it.installed }
        )
    }
    SideEffect {
        if (sharedResolvedFiles.isNotEmpty()) progressHistory["__shared__"] = sharedProgress
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (sharedResolvedFiles.isNotEmpty() && sharedProgress.fileCount > 0 &&
            sharedProgress.completedFileCount < sharedProgress.fileCount &&
            (sharedProgress.hasActiveDownloads || sharedProgress.downloadedBytes > 0L)
        ) {
            CuratedSharedComponentsProgress(sharedProgress)
        }
        if (matchingBundles.isEmpty()) {
            Text(
                stringResource(R.string.audio_bundle_picker_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        matchingBundles.forEach { bundle ->
            val resolvedFiles = resolveCuratedFiles(
                bundle = bundle,
                prefix = bundle.defaultPrefix,
                installedModels = installedModels,
                taskByProgressKey = taskByProgressKey,
                progressMap = progressMap
            )
            val progressEntries = resolvedFiles.map { it.toProgressEntry() }
            val previousSnapshot = progressHistory[bundle.id]
            val hasActive = progressEntries.any { it.active && !it.cancelled && !it.installed && !it.completed }
            val snapshot = calculateBundleProgressSnapshot(
                entries = progressEntries,
                previousSnapshot = previousSnapshot.takeIf { hasActive },
                resetToPersisted = !hasActive && previousSnapshot?.hasActiveDownloads == true &&
                    resolvedFiles.any { !it.installed }
            )
            SideEffect { progressHistory[bundle.id] = snapshot }
            val componentPaths = resolvedFiles.mapNotNull { resolved ->
                pickerComponentRole(resolved.file)?.let { role ->
                    resolved.installedModel?.path?.let { path -> role to path }
                }
            }.toMap()
            val complete = resolvedFiles.all { it.installed } && componentPaths.size == resolvedFiles.size
            val selected = complete && componentPaths.all { (role, path) -> selectedComponents[role] == path }
            val directActive = resolvedFiles.any { it.active && !isSharedCuratedFile(it.file) }
            val sharedOwnedActive = resolvedFiles.any {
                it.active && isSharedCuratedFile(it.file) && it.isOwnedBy(context, bundle.id)
            }
            val conflicts = resolvedFiles.any { resolved ->
                installedModels.any { model ->
                    model.filename == resolved.expectedName &&
                        !resolved.file.matchesVerifiedInstalledModel(resolved.expectedName, model)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(bundle.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(bundle.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.audio_bundle_picker_selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.audio_bundle_picker_components,
                            resolvedFiles.count { it.installed },
                            resolvedFiles.size
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (conflicts) {
                        Text(
                            stringResource(R.string.curated_bundle_conflict),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (directActive || sharedOwnedActive) {
                        if (snapshot.hasIndeterminateLiveTask) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { snapshot.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            stringResource(R.string.audio_bundle_picker_download_progress, snapshot.percentage),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (complete) {
                        OutlinedButton(
                            onClick = { onUseBundle(bundle, componentPaths) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.audio_bundle_picker_use))
                        }
                    } else {
                        Button(
                            onClick = {
                                val result = requestCuratedBundleDownload(
                                    context = context,
                                    bundle = bundle,
                                    prefix = bundle.defaultPrefix,
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
                            },
                            enabled = !directActive && !sharedOwnedActive && !conflicts,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (directActive || sharedOwnedActive) {
                                    stringResource(R.string.audio_bundle_picker_downloading)
                                } else {
                                    stringResource(R.string.audio_bundle_picker_download)
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
