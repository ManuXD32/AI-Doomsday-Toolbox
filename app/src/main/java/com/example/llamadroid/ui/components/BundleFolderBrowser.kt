package com.example.llamadroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Folder navigation shares the enclosing screen's scroll owner; never nests an unbounded grid. */
data class BundleFolder(val id: String, @StringRes val titleRes: Int, val count: Int)

@Composable
fun BundleFolderBrowser(
    folders: List<BundleFolder>,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = folders.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selected != null) { selectedId = null }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected == null) {
            folders.filter { it.count > 0 }.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { folder ->
                        Card(
                            onClick = { selectedId = folder.id },
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(folder.titleRes), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.bundle_folder_count, folder.count),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            TextButton(onClick = { selectedId = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(stringResource(R.string.bundle_folder_back), modifier = Modifier.padding(start = 8.dp))
            }
            content(selected.id)
        }
    }
}
