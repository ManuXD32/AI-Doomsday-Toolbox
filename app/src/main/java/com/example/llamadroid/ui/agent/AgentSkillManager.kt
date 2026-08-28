package com.example.llamadroid.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentSkillAssignmentEntity
import com.example.llamadroid.data.db.AgentSkillEntity
import com.example.llamadroid.service.AgentSkillRepository
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.SkillCatalogEntry
import com.example.llamadroid.service.SkillPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSkillManagerDialog(
    repository: AgentSkillRepository,
    conversationId: Long?,
    agentKey: String,
    onImportZip: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val installed by repository.observeInstalled().collectAsState(initial = emptyList())
    val assignments by repository.observeAssignments().collectAsState(initial = emptyList())
    var catalog by remember { mutableStateOf<List<SkillCatalogEntry>>(emptyList()) }
    var catalogLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var activeOperation by remember { mutableStateOf<String?>(null) }
    var importsExpanded by rememberSaveable { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<AgentSkillEntity?>(null) }
    val installedFeedback = stringResource(R.string.agent_skill_install_complete)
    val permissionFeedback = stringResource(R.string.agent_skill_permission_saved)
    val uninstallFeedback = stringResource(R.string.agent_skill_uninstall_complete)

    LaunchedEffect(Unit) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_skill_manager",
            event = "opened",
            details = "conversation=${conversationId ?: "none"}"
        )
        catalog = runCatching { repository.catalogEntries() }
            .onFailure {
                statusIsError = true
                status = it.message ?: it.javaClass.simpleName
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_skill_manager",
                    event = "catalog_failed",
                    details = "error=${it.javaClass.simpleName}"
                )
            }
            .getOrDefault(emptyList())
        catalogLoading = false
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_skill_manager",
            event = "catalog_loaded",
            details = "catalog=${catalog.size} installed=${installed.size}"
        )
    }

    fun launchOperation(
        operation: String,
        successMessage: String? = null,
        block: suspend () -> Unit
    ) {
        if (activeOperation != null) return
        scope.launch {
            status = null
            statusIsError = false
            activeOperation = operation
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { status = successMessage }
                .onFailure {
                    statusIsError = true
                    status = it.message ?: it.javaClass.simpleName
                }
            activeOperation = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.agent_skills_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.agent_skills_catalog_tab)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.agent_skills_installed_tab)) }
                    )
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_skills_search)) },
                    singleLine = true
                )
                if (selectedTab == 0) {
                    Card(
                        onClick = { importsExpanded = !importsExpanded },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.agent_skill_imports_title),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                if (importsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                        AnimatedVisibility(visible = importsExpanded) {
                            Column(
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = importUrl,
                                    onValueChange = { importUrl = it.take(2_000) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.agent_skill_import_url_hint)) },
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        val url = importUrl.trim()
                                        launchOperation(
                                            operation = "https",
                                            successMessage = installedFeedback
                                        ) {
                                            repository.installFromHttps(url)
                                            withContext(Dispatchers.Main) { importUrl = "" }
                                        }
                                    },
                                    enabled = activeOperation == null && importUrl.trim().startsWith("https://"),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (activeOperation == "https") {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(stringResource(R.string.agent_skill_import_https))
                                    }
                                }
                                OutlinedButton(
                                    onClick = onImportZip,
                                    enabled = activeOperation == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.agent_skill_import_zip))
                                }
                                Text(
                                    stringResource(R.string.agent_skill_scripts_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                status?.let {
                    Text(
                        it,
                        color = if (statusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                val installedByName = installed.associateBy { it.name.lowercase() }
                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            catalog.filter {
                                search.isBlank() ||
                                    it.name.contains(search, true) ||
                                    it.description.contains(search, true)
                            },
                            key = { it.id }
                        ) { entry ->
                            val operation = "catalog:${entry.id}"
                            SkillCatalogCard(
                                entry = entry,
                                installed = installedByName[entry.name.lowercase()],
                                operationInProgress = activeOperation == operation,
                                operationsEnabled = activeOperation == null,
                                onInstall = {
                                    launchOperation(
                                        operation = operation,
                                        successMessage = installedFeedback
                                    ) { repository.installCatalogSkill(entry) }
                                }
                            )
                        }
                        if (catalogLoading) {
                            item(key = "catalog-loading") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            installed.filter {
                                search.isBlank() ||
                                    it.name.contains(search, true) ||
                                    it.description.contains(search, true)
                            },
                            key = { it.id }
                        ) { skill ->
                            val selectedPermission = resolveDisplayedPermission(
                                skill = skill,
                                assignments = assignments,
                                conversationId = conversationId,
                                agentKey = agentKey
                            )
                            InstalledSkillCard(
                                skill = skill,
                                selectedPermission = selectedPermission,
                                operationInProgress = activeOperation?.startsWith("permission:${skill.id}:") == true,
                                operationsEnabled = activeOperation == null,
                                onPermission = { permission ->
                                    launchOperation(
                                        operation = "permission:${skill.id}:${permission.name}",
                                        successMessage = permissionFeedback
                                    ) {
                                        repository.setAssignment(
                                            skillId = skill.id,
                                            permission = permission,
                                            conversationId = conversationId,
                                            agentKey = agentKey
                                        )
                                    }
                                },
                                onUninstall = { uninstallTarget = skill }
                            )
                        }
                    }
                }
            }
        }
    }

    uninstallTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text(stringResource(R.string.agent_skill_uninstall)) },
            text = { Text(skill.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uninstallTarget = null
                        launchOperation(
                            operation = "uninstall:${skill.id}",
                            successMessage = uninstallFeedback
                        ) { repository.uninstall(skill.id) }
                    }
                ) { Text(stringResource(R.string.agent_skill_uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SkillCatalogCard(
    entry: SkillCatalogEntry,
    installed: AgentSkillEntity?,
    operationInProgress: Boolean,
    operationsEnabled: Boolean,
    onInstall: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(entry.name, fontWeight = FontWeight.Bold)
            Text(entry.description, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.agent_skill_version_license,
                    entry.version,
                    entry.license ?: stringResource(R.string.agent_skill_license_missing)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onInstall,
                enabled = operationsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (operationInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        stringResource(
                            if (installed == null) R.string.agent_skill_install else R.string.agent_skill_update
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledSkillCard(
    skill: AgentSkillEntity,
    selectedPermission: SkillPermission,
    operationInProgress: Boolean,
    operationsEnabled: Boolean,
    onPermission: (SkillPermission) -> Unit,
    onUninstall: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(skill.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            Text(
                stringResource(
                    R.string.agent_skill_provenance,
                    skill.sourceType,
                    skill.version ?: stringResource(R.string.agent_skill_version_unknown),
                    skill.license ?: stringResource(R.string.agent_skill_license_missing)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SkillPermission.entries.forEach { permission ->
                    val label = stringResource(
                        when (permission) {
                            SkillPermission.ALLOW -> R.string.agent_skill_permission_allow
                            SkillPermission.ASK -> R.string.agent_skill_permission_ask
                            SkillPermission.DENY -> R.string.agent_skill_permission_deny
                        }
                    )
                    if (permission == selectedPermission) {
                        Button(
                            onClick = { onPermission(permission) },
                            enabled = operationsEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(label, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPermission(permission) },
                            enabled = operationsEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            if (operationInProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            TextButton(
                onClick = onUninstall,
                enabled = operationsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.agent_skill_uninstall))
            }
        }
    }
}

private fun resolveDisplayedPermission(
    skill: AgentSkillEntity,
    assignments: List<AgentSkillAssignmentEntity>,
    conversationId: Long?,
    agentKey: String
): SkillPermission {
    val assignment = assignments
        .asSequence()
        .filter { it.skillId == skill.id }
        .filter { it.conversationId == conversationId || it.conversationId == null }
        .filter { it.agentKey == agentKey || it.agentKey == "*" }
        .sortedWith(
            compareBy<AgentSkillAssignmentEntity>(
                { if (it.conversationId == conversationId) 0 else 1 },
                { if (it.agentKey == agentKey) 0 else 1 },
                { -it.updatedAt }
            )
        )
        .firstOrNull()
    return runCatching {
        SkillPermission.valueOf(
            assignment?.permission ?: if (skill.sourceType == "CURATED") "ALLOW" else "ASK"
        )
    }.getOrDefault(SkillPermission.ASK)
}
