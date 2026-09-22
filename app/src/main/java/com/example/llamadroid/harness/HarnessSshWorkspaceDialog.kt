package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.ui.components.SshConnectionFields
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Connection belongs to this workspace; editing another project cannot redirect its session. */
@Composable
fun HarnessSshWorkspaceDialog(runtime: HarnessAppRuntime, workspace: HarnessWorkspaceEntity, onReady: () -> Unit, onDismiss: () -> Unit) {
    val summary = remember(workspace.id) { runtime.workspaces.sshSummary(workspace.id) }
    var host by remember(workspace.id) { mutableStateOf(summary.getString("host")) }
    var port by remember(workspace.id) { mutableStateOf(summary.getInt("port").toString()) }
    var username by remember(workspace.id) { mutableStateOf(summary.getString("username")) }
    var password by remember(workspace.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.ssh_connection_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(workspace.title)
            Text("/workspace/${workspace.projectFolder}")
            SshConnectionFields(host, port, username, password, { host = it }, { port = it }, { username = it }, { password = it })
            if (summary.optBoolean("configured")) Text(stringResource(R.string.harness_ssh_keep_password))
            if (failed) Text(stringResource(R.string.harness_ssh_failed))
        } },
        confirmButton = { TextButton(enabled = !saving && host.isNotBlank() && username.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535,
            onClick = { scope.launch {
                saving = true; failed = false
                try {
                    runtime.workspaces.configureSsh(workspace.id, JSONObject().put("host", host.trim()).put("port", port.toInt())
                        .put("username", username.trim()).put("password", password))
                    runtime.files.prepareSshWorkspace(workspace)
                    onReady()
                } catch (_: Exception) { failed = true } finally { saving = false }
            } }) { Text(stringResource(R.string.harness_ssh_save_connect)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
