package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HarnessRuntimeTab(
    runtime: HarnessRuntimeUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
    onReinstallRuntime: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_runtime_tab"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "runtime-lifecycle") {
            HarnessRuntimeHeader(runtime = runtime, onAction = onAction)
        }
        item(key = "runtime-lan-access") {
            HarnessLanAccessPanel(runtime = runtime)
        }
        if (onReinstallRuntime != null) item(key = "runtime-reinstall") {
            HarnessRuntimeReinstallPanel(onReinstallRuntime)
        }
        item(key = "runtime-diagnostics") {
            HarnessRuntimeDiagnosticsPanel(runtime = runtime, onAction = onAction)
        }
    }
}

@Composable
private fun HarnessRuntimeReinstallPanel(onReinstall: () -> Unit) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(
            text = stringResource(R.string.harness_runtime_reinstall_action),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.harness_runtime_reinstall_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        OutlinedButton(
            onClick = onReinstall,
            modifier = Modifier.fillMaxWidth().testTag("harness_runtime_reinstall"),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.harness_runtime_reinstall_action))
        }
    }
}

@Composable
private fun HarnessLanAccessPanel(runtime: HarnessRuntimeUiState) {
    val context = LocalContext.current
    val manager = remember(context) { com.example.llamadroid.harness.HarnessLanAccessManager.get(context) }
    val access by manager.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val runtimeReady = runtime.status == HarnessRuntimeStatus.RUNNING
    AppSectionCard(
        modifier = Modifier.testTag("harness_lan_access"),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.harness_lan_access_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.harness_lan_access_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = access.enabled,
                enabled = true,
                onCheckedChange = manager::setEnabled,
                modifier = Modifier.testTag("harness_lan_access_toggle")
            )
        }
        when {
            access.running && access.urls.isNotEmpty() -> {
                Text(
                    text = stringResource(R.string.harness_lan_access_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        access.urls.forEach { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("harness_lan_access_url")
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(access.urls.first())) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.harness_lan_access_copy_url))
                }
            }
            access.enabled && !runtimeReady -> {
                Text(
                    text = stringResource(R.string.harness_lan_access_waiting_runtime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            access.enabled && access.errorCode != null -> {
                Text(
                    text = stringResource(R.string.harness_lan_access_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.harness_lan_access_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
internal fun HarnessRuntimeShortcut(
    runtime: HarnessRuntimeUiState,
    onOpenRuntime: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusLabel = harnessRuntimeStatusLabel(runtime.status)
    val statusColor = harnessRuntimeStatusColor(runtime.status)
    AppSectionCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenRuntime)
            .testTag("harness_runtime_shortcut"),
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = harnessRuntimeStatusIcon(runtime.status),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.harness_runtime_shortcut, statusLabel),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                runtime.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(R.string.harness_runtime_open_tab),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HarnessRuntimeHeader(
    runtime: HarnessRuntimeUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val statusLabel = harnessRuntimeStatusLabel(runtime.status)
    val statusColor = harnessRuntimeStatusColor(runtime.status)
    AppSectionCard(
        modifier = Modifier.testTag("harness_runtime_header"),
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.15f)
            ) {
                if (runtime.status == HarnessRuntimeStatus.STARTING ||
                    runtime.status == HarnessRuntimeStatus.STOPPING
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.padding(12.dp),
                        color = statusColor
                    )
                } else {
                    Icon(
                        imageVector = harnessRuntimeStatusIcon(runtime.status),
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = statusColor
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = runtime.instanceLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = runtime.endpointLabel?.let {
                        stringResource(R.string.harness_runtime_endpoint, statusLabel, it)
                    } ?: statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                listOfNotNull(
                    runtime.connectionLabel,
                    runtime.detail?.takeIf { it.isNotBlank() },
                    runtime.phaseLabel?.takeIf { it.isNotBlank() },
                    runtime.errorCode?.takeIf { it.isNotBlank() }?.let {
                        stringResource(R.string.harness_runtime_error_code, it)
                    }
                ).forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onAction(NativeHarnessUiAction.StartRuntime) },
                enabled = runtime.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("harness_start")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.harness_start))
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.StopRuntime) },
                enabled = runtime.canStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("harness_stop")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.harness_stop))
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.ForceStopRuntime) },
                enabled = runtime.canForceStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("harness_force_stop")
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.harness_force_stop))
            }
        }
    }
}

@Composable
private fun HarnessRuntimeDiagnosticsPanel(
    runtime: HarnessRuntimeUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val rows = runtime.diagnostics.takeLast(MAX_RUNTIME_DIAGNOSTICS).asReversed()
    AppSectionCard(
        modifier = Modifier.testTag("harness_runtime_diagnostics"),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.harness_runtime_diagnostics_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.harness_runtime_diagnostics_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { onAction(NativeHarnessUiAction.RefreshRuntimeDiagnostics) },
                enabled = !runtime.diagnosticsLoading,
                modifier = Modifier.testTag("harness_runtime_refresh_diagnostics")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.harness_runtime_refresh_diagnostics)
                )
            }
        }
        if (runtime.diagnosticsLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        runtime.diagnosticsLastUpdatedMs?.let { updatedAt ->
            Text(
                text = stringResource(
                    R.string.harness_runtime_diagnostics_last_updated,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updatedAt))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (rows.isEmpty() && !runtime.diagnosticsLoading) {
            Text(
                text = stringResource(R.string.harness_runtime_diagnostics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else if (rows.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { row -> HarnessRuntimeDiagnosticRow(row) }
                    }
                }
            }
        }
        TextButton(onClick = { onAction(NativeHarnessUiAction.OpenRuntimeJournal) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.harness_journal_open))
        }
        TextButton(
            onClick = { onAction(NativeHarnessUiAction.CopyRuntimeDiagnostics) },
            enabled = rows.isNotEmpty() && !runtime.diagnosticsLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("harness_runtime_copy_diagnostics")
        ) {
            Text(stringResource(R.string.harness_runtime_copy_diagnostics))
        }
    }
}

@Composable
private fun HarnessRuntimeDiagnosticRow(row: HarnessRuntimeDiagnosticUi) {
    val timestamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.timestampMs))
    val state = row.stateLabel?.takeIf { it.isNotBlank() }
    val error = row.errorCode?.takeIf { it.isNotBlank() }
    val timing = row.durationMs?.let { stringResource(R.string.harness_runtime_duration, it) }
    val exit = row.exitCode?.let { stringResource(R.string.harness_runtime_exit_code, it) }
    val metadata = listOfNotNull(state, row.connectionMetadata, error, timing, exit).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("harness_runtime_diagnostic_${row.id}")
    ) {
        Text(
            text = "$timestamp · ${row.eventLabel}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (metadata.isNotBlank()) {
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val MAX_RUNTIME_DIAGNOSTICS = 100

@Composable
private fun harnessRuntimeStatusLabel(status: HarnessRuntimeStatus): String = when (status) {
    HarnessRuntimeStatus.STOPPED -> stringResource(R.string.harness_status_stopped)
    HarnessRuntimeStatus.STARTING -> stringResource(R.string.harness_status_starting)
    HarnessRuntimeStatus.RUNNING -> stringResource(R.string.harness_status_running)
    HarnessRuntimeStatus.STOPPING -> stringResource(R.string.harness_status_stopping)
    HarnessRuntimeStatus.INTERRUPTED -> stringResource(R.string.harness_status_interrupted)
    HarnessRuntimeStatus.ERROR -> stringResource(R.string.harness_status_error)
}

@Composable
private fun harnessRuntimeStatusColor(status: HarnessRuntimeStatus): Color = when (status) {
    HarnessRuntimeStatus.RUNNING -> MaterialTheme.colorScheme.primary
    HarnessRuntimeStatus.STARTING,
    HarnessRuntimeStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
    HarnessRuntimeStatus.ERROR,
    HarnessRuntimeStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
    HarnessRuntimeStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun harnessRuntimeStatusIcon(status: HarnessRuntimeStatus) = when (status) {
    HarnessRuntimeStatus.RUNNING -> Icons.Default.CheckCircle
    HarnessRuntimeStatus.ERROR,
    HarnessRuntimeStatus.INTERRUPTED -> Icons.Default.Close
    else -> Icons.Default.Cloud
}
