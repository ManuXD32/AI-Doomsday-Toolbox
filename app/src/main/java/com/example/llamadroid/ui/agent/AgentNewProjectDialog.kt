package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.ui.components.AppTaskActionFooter

/** Workspace choices and the create action remain reachable with large text and the IME. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentNewProjectDialog(
    name: String,
    onNameChange: (String) -> Unit,
    backend: AgentWorkspaceBackendType,
    onBackendChange: (AgentWorkspaceBackendType) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            topBar = { TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },title = { Text(stringResource(R.string.agent_new_project_title)) }) },
            bottomBar = {
                AppTaskActionFooter {
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_create))
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(value = name, onValueChange = onNameChange, singleLine = true,
                        label = { Text(stringResource(R.string.agent_project_name_label)) },
                        modifier = Modifier.fillMaxWidth())
                }
                item { Text(stringResource(R.string.agent_project_backend_label), style = MaterialTheme.typography.titleMedium) }
                item {
                    FilterChip(selected = backend == AgentWorkspaceBackendType.REMOTE_SSH,
                        onClick = { onBackendChange(AgentWorkspaceBackendType.REMOTE_SSH) },
                        label = { Text(stringResource(R.string.agent_project_backend_remote)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
                item {
                    FilterChip(selected = backend == AgentWorkspaceBackendType.LOCAL_SANDBOX,
                        onClick = { onBackendChange(AgentWorkspaceBackendType.LOCAL_SANDBOX) },
                        label = { Text(stringResource(R.string.agent_project_backend_local)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
                item {
                    Text(stringResource(if (backend == AgentWorkspaceBackendType.LOCAL_SANDBOX)
                        R.string.agent_project_backend_local_desc else R.string.agent_project_backend_remote_desc),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
