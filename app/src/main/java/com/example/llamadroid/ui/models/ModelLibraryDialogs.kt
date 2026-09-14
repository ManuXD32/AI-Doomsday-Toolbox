package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.api.HfTreeItemDto
import com.example.llamadroid.data.model.library.HfFolderListing
import com.example.llamadroid.data.model.library.ModelSourceKind
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog

@Composable
internal fun HfFolderDialog(
    listing: HfFolderListing,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onLoadMore: () -> Unit = {},
    onSelectFile: (HfTreeItemDto) -> Unit
) {
    WalkthroughAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_hf_browser_title, listing.repositoryId)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_path, listing.folderPath.ifBlank { "/" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_pages, listing.pagesFetched),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canGoUp) {
                    item {
                        OutlinedButton(onClick = onGoUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_parent))
                        }
                    }
                }
                if (listing.nextCursor != null) {
                    item {
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_load_more))
                        }
                    }
                } else if (listing.truncated) {
                    item {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_truncated),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (listing.items.isEmpty()) {
                    item { Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_empty)) }
                } else {
                    items(listing.items, key = { it.path }) { item ->
                        HfTreeItemRow(item, onOpenFolder, onSelectFile)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_close)) }
        }
    )
}

@Composable
private fun HfTreeItemRow(
    item: HfTreeItemDto,
    onOpenFolder: (String) -> Unit,
    onSelectFile: (HfTreeItemDto) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (item.type == "directory") Icons.Default.FolderOpen else Icons.Default.Link, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            item.path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.type == "directory") {
            TextButton(onClick = { onOpenFolder(item.path) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_open))
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                if (item.size > 0L) {
                    Text(formatBytes(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onSelectFile(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_use_file))
                }
            }
        }
    }
}

@Composable
internal fun EmptyLibraryCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun modelSourceKindLabel(kind: ModelSourceKind?): String = when (kind) {
    ModelSourceKind.HUGGING_FACE_REPOSITORY -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_hf_repository)
    ModelSourceKind.HUGGING_FACE_FILE -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_hf_file)
    ModelSourceKind.HTTPS, null -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_https)
}

@Composable
internal fun pendingStatusLabel(status: com.example.llamadroid.data.model.library.PendingArtifactStatus?): String = when (status) {
    com.example.llamadroid.data.model.library.PendingArtifactStatus.STAGED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_staged)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.INSPECTING -> androidx.compose.ui.res.stringResource(R.string.model_library_status_inspecting)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.NEEDS_MANUAL_PROMOTION -> androidx.compose.ui.res.stringResource(R.string.model_library_status_manual)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.VALIDATED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_validated)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.PROMOTED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_promoted)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.REJECTED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_rejected)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.CANCELLED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_cancelled)
    com.example.llamadroid.data.model.library.PendingArtifactStatus.FAILED, null -> androidx.compose.ui.res.stringResource(R.string.model_library_status_failed)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
