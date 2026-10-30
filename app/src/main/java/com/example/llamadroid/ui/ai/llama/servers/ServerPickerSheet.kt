package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerStatus
import kotlinx.coroutines.launch

/** An extra, non-server entry at the top of the picker (e.g. the scheduler's "Last-used server"). */
data class ServerPickerExtraOption(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * The one server picker (replaces both `ChatServerPickerDialog` copies and the scheduler
 * dropdown). Lists every [UnifiedServer] with its status dot and model, preselecting
 * [selectedRowId]. Managed servers without a connection row get one through
 * [ServerRegistryViewModel.ensureRowId] before [onPick] fires.
 *
 * @param onPick receives the connection row id of the chosen server
 * @param onPickServer optional richer callback with the whole server (e.g. when the port is needed);
 *        invoked after [onPick]
 * @param onCreateNew shows "+ New local server from a model" when non-null
 * @param onManageServers shows "Manage servers" when non-null
 * @param runningOnly list only running servers (web-UI chat target)
 * @param extraOption optional leading entry that is not a server
 * @param selectedPort alternative preselection for callers that only know a port
 * @param filter optional predicate limiting which servers are listed (e.g. engine-specific pickers)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(
    viewModel: ServerRegistryViewModel,
    selectedRowId: Long?,
    onDismiss: () -> Unit,
    onPick: (rowId: Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    onPickServer: ((UnifiedServer) -> Unit)? = null,
    onCreateNew: (() -> Unit)? = null,
    onManageServers: (() -> Unit)? = null,
    runningOnly: Boolean = false,
    extraOption: ServerPickerExtraOption? = null,
    selectedPort: Int? = null,
    filter: ((UnifiedServer) -> Boolean)? = null
) {
    val servers by viewModel.servers.collectAsState()
    val actions by viewModel.actions.collectAsState()
    val scope = rememberCoroutineScope()
    val eligible = if (filter != null) servers.filter(filter) else servers
    val visible = if (runningOnly) eligible.filter { it.status == UnifiedServerStatus.RUNNING } else eligible

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title ?: stringResource(R.string.srv_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            extraOption?.let { extra ->
                PickerRow(extra.label, null, null, null, extra.selected) { extra.onClick() }
            }
            if (visible.isEmpty()) {
                Text(
                    stringResource(if (runningOnly) R.string.chat_servers_no_running else R.string.srv_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visible.forEach { server ->
                val selected = (selectedRowId != null && server.rowId == selectedRowId) ||
                    (selectedRowId == null && selectedPort != null && server.port == selectedPort)
                PickerRow(
                    name = server.name,
                    model = server.modelName,
                    status = effectiveServerStatus(server, actions[server.key]),
                    statusLabel = serverStatusLabel(server, actions[server.key]),
                    selected = selected
                ) {
                    scope.launch {
                        val rowId = viewModel.ensureRowId(server) ?: return@launch
                        onPick(rowId)
                        onPickServer?.invoke(server.copy(rowId = rowId))
                    }
                }
            }
            if (onCreateNew != null) {
                OutlinedButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.srv_picker_new_local), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onManageServers != null) {
                OutlinedButton(onClick = onManageServers, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.chat_servers_manage), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    name: String,
    model: String?,
    status: UnifiedServerStatus?,
    statusLabel: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (status != null) ServerStatusDot(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondary = listOfNotNull(model?.takeIf { it.isNotBlank() }, statusLabel).joinToString(" · ")
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.chat_servers_selected))
        }
    }
}
