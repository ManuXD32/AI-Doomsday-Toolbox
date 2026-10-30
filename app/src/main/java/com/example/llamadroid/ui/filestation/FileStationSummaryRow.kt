package com.example.llamadroid.ui.filestation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.R
import com.example.llamadroid.service.filestation.FileStationRuntime
import com.example.llamadroid.ui.components.AppHubRow

/** Servers hub entry: opens the File Station screen and shows the running address. */
@Composable
fun FileStationSummaryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val running by FileStationRuntime.running.collectAsState()
    val urls by FileStationRuntime.urls.collectAsState()
    val unprotected by FileStationRuntime.unprotected.collectAsState()
    val summary = when {
        !running -> stringResource(R.string.fs_summary_stopped)
        urls.isNotEmpty() -> stringResource(R.string.fs_summary_running, urls.first().second)
        else -> stringResource(R.string.fs_status_running)
    }
    AppHubRow(
        title = stringResource(R.string.fs_title),
        subtitle = stringResource(R.string.soft_studio_home_file_server_desc),
        onClick = onOpen,
        modifier = modifier,
        icon = Icons.Default.Folder,
        extra = {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (running && unprotected) {
                Text(
                    stringResource(R.string.fs_summary_unprotected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1
                )
            }
        }
    )
}
