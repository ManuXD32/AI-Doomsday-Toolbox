package com.example.llamadroid.ui.ai

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedDownloadHandle
import com.example.llamadroid.data.model.curatedLabel
import com.example.llamadroid.data.model.expectedSdCuratedDownloadHandle
import com.example.llamadroid.data.model.isInstalledForBundle
import com.example.llamadroid.data.model.startSdCuratedBundleFileDownload
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.FormatUtils

/** Curated, complete model sets shown instead of the old recommendation cards. */
@Composable
fun SdCuratedBundlesSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val installedModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    val startedHandles = remember { mutableStateMapOf<String, List<SdCuratedDownloadHandle>>() }
    var pendingBundle by remember { mutableStateOf<SdCuratedBundle?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.sd_bundles_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.sd_bundles_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SdCuratedBundleCatalog.bundles.forEach { bundle ->
            val installedCount = bundle.files.count { file ->
                file.isInstalledForBundle(bundle, installedModels)
            }
            val missingFiles = bundle.files.filterNot { file ->
                file.isInstalledForBundle(bundle, installedModels)
            }
            val expectedHandles = bundle.files.map { file ->
                expectedSdCuratedDownloadHandle(bundle, file)
            }
            val handles = (startedHandles[bundle.id].orEmpty() + expectedHandles)
                .distinctBy { it.progressKey }
            val activeHandles = handles.filter { handle ->
                val value = progressMap[handle.progressKey]
                value == DownloadProgressHolder.INDETERMINATE || value != null && value in 0f..0.999f
            }
            val determinate = activeHandles.mapNotNull { handle ->
                progressMap[handle.progressKey]
                    ?.takeIf { it >= 0f }
                    ?.coerceIn(0f, 1f)
            }
            val aggregateProgress = determinate
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
            val isDownloading = activeHandles.isNotEmpty()

            SdCuratedBundleCard(
                bundle = bundle,
                installedCount = installedCount,
                missingBytes = missingFiles.sumOf { it.sizeBytes },
                isDownloading = isDownloading,
                aggregateProgress = aggregateProgress,
                onClick = {
                    if (!isDownloading) pendingBundle = bundle
                },
                onCancel = {
                    activeHandles.forEach { handle ->
                        DownloadService.cancelDownload(
                            context = context,
                            filename = handle.filename,
                            downloadId = handle.progressKey
                        )
                    }
                }
            )
        }

        Text(
            stringResource(R.string.sd_bundles_license_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    pendingBundle?.let { bundle ->
        val missingFiles = bundle.files.filterNot { file ->
            file.isInstalledForBundle(bundle, installedModels)
        }
        AlertDialog(
            onDismissRequest = { pendingBundle = null },
            title = {
                Text(
                    stringResource(R.string.sd_bundle_dialog_title, stringResource(bundle.titleRes))
                )
            },
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
                        stringResource(
                            R.string.sd_bundle_prefix_note,
                            bundle.installPrefix
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(
                            if (bundle.totalSizeIsApproximate) {
                                R.string.sd_bundle_total_approx
                            } else {
                                R.string.sd_bundle_total
                            },
                            FormatUtils.Display.formatBytes(context, bundle.totalSizeBytes)
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider()
                    bundle.files.forEach { file ->
                        val installed = file.isInstalledForBundle(bundle, installedModels)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.localFilename(bundle.installPrefix),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(
                                            R.string.sd_bundle_file_meta,
                                            FormatUtils.Display.formatBytes(context, file.sizeBytes),
                                            file.modelType.curatedLabel()
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(
                                            R.string.sd_bundle_source_repo,
                                            file.repoId
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(
                                            R.string.sd_bundle_license,
                                            file.licenseLabel
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (installed) {
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
                    if (missingFiles.isEmpty()) {
                        Text(
                            stringResource(R.string.sd_bundle_already_installed),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val handles = missingFiles.map { file ->
                            startSdCuratedBundleFileDownload(
                                context = context,
                                repository = repository,
                                bundle = bundle,
                                file = file
                            )
                        }
                        if (handles.isNotEmpty()) {
                            startedHandles[bundle.id] = handles
                        }
                        pendingBundle = null
                    },
                    enabled = missingFiles.isNotEmpty()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (missingFiles.isEmpty()) {
                            stringResource(R.string.sd_bundle_installed)
                        } else {
                            stringResource(
                                R.string.sd_bundle_download_missing,
                                missingFiles.size
                            )
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBundle = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SdCuratedBundleCard(
    bundle: SdCuratedBundle,
    installedCount: Int,
    missingBytes: Long,
    isDownloading: Boolean,
    aggregateProgress: Float?,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val allInstalled = installedCount == bundle.files.size
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        ),
        border = BorderStroke(
            1.dp,
            if (allInstalled) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        Icons.Default.Collections,
                        contentDescription = null,
                        tint = if (allInstalled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
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

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                if (bundle.totalSizeIsApproximate) {
                                    R.string.sd_bundle_total_approx
                                } else {
                                    R.string.sd_bundle_total
                                },
                                FormatUtils.Display.formatBytes(context, bundle.totalSizeBytes)
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                R.string.sd_bundle_file_count,
                                bundle.files.size
                            )
                        )
                    }
                )
            }

            Text(
                stringResource(
                    R.string.sd_bundle_installed_progress,
                    installedCount,
                    bundle.files.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isDownloading) {
                LinearProgressIndicator(
                    progress = { installedCount.toFloat() / bundle.files.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (missingBytes > 0L) {
                Text(
                    stringResource(
                        R.string.sd_bundle_missing_storage,
                        FormatUtils.Display.formatBytes(context, missingBytes)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isDownloading) {
                if (aggregateProgress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { aggregateProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(
                            R.string.sd_bundle_download_progress,
                            (aggregateProgress * 100).toInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (installedCount < bundle.files.size) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_bundle_review_download))
                }
            }
        }
    }
}
