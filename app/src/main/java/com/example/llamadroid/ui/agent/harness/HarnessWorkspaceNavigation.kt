package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel

/** Stable project identity supplied by the workspace owner. */
data class HarnessProjectUi(
    val id: String,
    val title: String,
    val projectFolder: String,
    val backendLabel: String,
    val threadIds: Set<String> = emptySet(),
)

/** A destination in the native Harness history. Project identity is kept with every tab. */
internal data class HarnessNavigationLocation(
    val projectId: String?,
    val tab: HarnessSurfaceTab,
)

internal data class HarnessNavigationBackResult(
    val history: List<String>,
    val destination: HarnessNavigationLocation?,
)

/** Back follows the workspace hierarchy, regardless of how many panels were visited. */
internal fun harnessNavigationParent(
    current: HarnessNavigationLocation,
    projectNavigationEnabled: Boolean,
): HarnessNavigationLocation? = when {
    current.tab != HarnessSurfaceTab.CONVERSATION -> current.copy(tab = HarnessSurfaceTab.CONVERSATION)
    projectNavigationEnabled && current.projectId != null -> HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
    else -> null
}

private const val HARNESS_NAVIGATION_SEPARATOR = '\u001f'

/** String keys keep the small navigation stack saveable across configuration changes. */
internal fun harnessNavigationLocationKey(location: HarnessNavigationLocation): String =
    buildString {
        location.projectId?.let(::append)
        append(HARNESS_NAVIGATION_SEPARATOR)
        append(location.tab.name)
    }

internal fun harnessNavigationLocationFromKey(key: String): HarnessNavigationLocation? {
    val separator = key.lastIndexOf(HARNESS_NAVIGATION_SEPARATOR)
    if (separator < 0) return null
    val projectId = key.substring(0, separator).takeIf(String::isNotEmpty)
    val tab = runCatching { HarnessSurfaceTab.valueOf(key.substring(separator + 1)) }.getOrNull()
        ?: return null
    return HarnessNavigationLocation(projectId, tab)
}

internal fun harnessNavigationPush(
    history: List<String>,
    destination: HarnessNavigationLocation,
): List<String> {
    val key = harnessNavigationLocationKey(destination)
    return if (history.lastOrNull() == key) history else history + key
}

internal fun harnessNavigationBack(history: List<String>): HarnessNavigationBackResult {
    if (history.size <= 1) return HarnessNavigationBackResult(history, null)
    val previousHistory = history.dropLast(1)
    return HarnessNavigationBackResult(
        history = previousHistory,
        destination = previousHistory.lastOrNull()?.let(::harnessNavigationLocationFromKey),
    )
}

internal fun harnessNavigationPruneProjects(
    history: List<String>,
    projectIds: Set<String>,
): List<String> {
    val pruned = history.filter { key ->
        harnessNavigationLocationFromKey(key)?.let { location ->
            location.projectId == null || location.projectId in projectIds
        } == true
    }
    return pruned.ifEmpty {
        listOf(
            harnessNavigationLocationKey(
                HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
            )
        )
    }
}

/** Runtime controls remain in the menu; this shortcut is reserved for actionable failures. */
internal fun harnessRuntimeNeedsAttention(runtime: HarnessRuntimeUiState): Boolean {
    if (runtime.status == HarnessRuntimeStatus.ERROR || runtime.status == HarnessRuntimeStatus.INTERRUPTED) {
        return true
    }
    val code = runtime.errorCode.orEmpty().uppercase()
    return code.contains("TIMEOUT") || code.contains("TIMED_OUT") || code.contains("TIMEDOUT")
}

internal enum class HarnessSurfaceTab {
    CONVERSATION,
    PLAN,
    REQUESTS,
    CHANGES,
    TOOLS,
    FILES,
    TERMINAL,
    RUNTIME,
    SESSIONS,
    SETTINGS,
    EXTENSIONS,
    WEB,
}

internal fun HarnessProjectUi.containsThread(session: HarnessSessionUiState): Boolean =
    session.id in threadIds

/**
 * Project selection is identity based.  A deleted workspace may leave a saved selection in the
 * Compose state while Room is reloading; treating that stale id as a real project would expose
 * the controller's unfiltered session list and make a newly created project appear to inherit
 * the old chat.  Display names are deliberately absent from this check.
 */
internal fun isHarnessProjectSelectionValid(
    selectedProjectId: String?,
    projects: List<HarnessProjectUi>,
): Boolean = selectedProjectId != null && projects.any { it.id == selectedProjectId }

/**
 * Projects own the session transcript. During project selection, Room reload, or project
 * recreation the controller can still expose the previous session payload for one frame. Keep
 * the session list visible for routing, but clear content until a session belonging to the
 * selected project is canonical again.
 */
internal fun projectScopedHarnessState(
    state: NativeHarnessUiState,
    visibleSessions: List<HarnessSessionUiState>,
    visibleSessionIds: Set<String>,
    projectWorkspace: HarnessWorkspaceUiState,
    projectNavigationEnabled: Boolean,
    selectedProjectPresent: Boolean,
): NativeHarnessUiState {
    val selectedSessionIsCanonical = state.selectedSessionId != null &&
        state.selectedSessionId in visibleSessionIds
    val clearProjection = (state.selectedSessionId != null && !selectedSessionIsCanonical) ||
        (projectNavigationEnabled && (!selectedProjectPresent || !selectedSessionIsCanonical))
    return if (!clearProjection) {
        state.copy(sessions = visibleSessions, workspace = projectWorkspace)
    } else {
        state.copy(
            sessions = visibleSessions,
            workspace = projectWorkspace,
            selectedSessionId = null,
            transcript = emptyList(),
            structuredTranscript = emptyList(),
            attachments = emptyList(),
            questions = emptyList(),
            approvals = emptyList(),
            plans = emptyList(),
            queue = emptyList(),
            goal = null,
        )
    }
}

@Composable
internal fun HarnessProjectsLanding(
    projects: List<HarnessProjectUi>,
    onOpenProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    modifier: Modifier = Modifier,
    onRenameProject: (HarnessProjectUi) -> Unit = {},
    onRemoveProject: (HarnessProjectUi) -> Unit = {},
    canBatchRemove: Boolean = false,
    onBatchRemove: (List<HarnessProjectUi>) -> Unit = {},
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    androidx.compose.runtime.LaunchedEffect(projects, canBatchRemove) {
        val projectIds = projects.mapTo(mutableSetOf()) { it.id }
        selectedProjectIds = selectedProjectIds.intersect(projectIds)
        if (!canBatchRemove) selectionMode = false
    }

    fun toggleSelection(projectId: String) {
        selectedProjectIds = if (projectId in selectedProjectIds) {
            selectedProjectIds - projectId
        } else {
            selectedProjectIds + projectId
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_projects_landing"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "projects-header") {
            Card(
                shape = AppChromeDefaults.InnerCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = AppChromeDefaults.CompactShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.harness_projects_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.harness_projects_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.harness_projects_count, projects.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = onCreateProject,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("harness_project_create"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.harness_project_create))
                    }
                    if (canBatchRemove) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectionMode = !selectionMode
                                    selectedProjectIds = emptySet()
                                },
                                modifier = Modifier.weight(1f).testTag("harness_project_batch_select"),
                            ) {
                                Text(
                                    stringResource(
                                        if (selectionMode) R.string.harness_project_batch_cancel_selection
                                        else R.string.harness_project_batch_select,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selectionMode) {
                                TextButton(
                                    onClick = { selectedProjectIds = projects.mapTo(mutableSetOf()) { it.id } },
                                    modifier = Modifier.weight(1f).testTag("harness_project_batch_select_all"),
                                ) {
                                    Text(
                                        stringResource(R.string.harness_project_batch_select_all),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (selectionMode) {
                            Text(
                                stringResource(
                                    R.string.harness_project_batch_selected_count,
                                    selectedProjectIds.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    onBatchRemove(projects.filter { it.id in selectedProjectIds })
                                    selectionMode = false
                                    selectedProjectIds = emptySet()
                                },
                                enabled = selectedProjectIds.size >= 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("harness_project_batch_remove"),
                            ) {
                                Text(stringResource(R.string.harness_project_batch_remove))
                            }
                        }
                    }
                }
            }
        }
        if (projects.isEmpty()) {
            item(key = "projects-empty") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_projects_empty_title),
                    message = stringResource(R.string.harness_projects_empty_description),
                    actionLabel = stringResource(R.string.harness_project_create),
                    onAction = onCreateProject,
                )
            }
        } else {
            items(projects, key = { "project-${it.id}" }) { project ->
                var actionsExpanded by remember(project.id) { mutableStateOf(false) }
                val openDescription = stringResource(R.string.harness_project_open, project.title)
                val actionsDescription = stringResource(R.string.harness_project_actions, project.title)
                Card(
                    shape = AppChromeDefaults.InnerCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    if (selectionMode) toggleSelection(project.id)
                                    else onOpenProject(project.id)
                                },
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = openDescription
                            }
                            .testTag("harness_project_${project.id}")
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = project.id in selectedProjectIds,
                                onCheckedChange = { toggleSelection(project.id) },
                                modifier = Modifier.testTag("harness_project_batch_check_${project.id}"),
                            )
                        } else {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                project.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                project.projectFolder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = AppChromeDefaults.CompactShape,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                ) {
                                    Text(
                                        project.backendLabel,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Surface(
                                    shape = AppChromeDefaults.CompactShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        stringResource(R.string.harness_project_threads, project.threadIds.size),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { actionsExpanded = true },
                                modifier = Modifier.testTag("harness_project_actions_${project.id}"),
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = actionsDescription)
                            }
                            DropdownMenu(
                                expanded = actionsExpanded,
                                onDismissRequest = { actionsExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_rename)) },
                                    onClick = {
                                        actionsExpanded = false
                                        onRenameProject(project)
                                    },
                                    modifier = Modifier.testTag("harness_project_rename_${project.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_remove)) },
                                    onClick = {
                                        actionsExpanded = false
                                        onRemoveProject(project)
                                    },
                                    modifier = Modifier.testTag("harness_project_remove_${project.id}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HarnessWorkTabs(
    selectedTab: HarnessSurfaceTab,
    onSelected: (HarnessSurfaceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        HarnessSurfaceTab.CONVERSATION to stringResource(R.string.harness_tab_conversation),
        HarnessSurfaceTab.PLAN to stringResource(R.string.harness_tab_plan),
        HarnessSurfaceTab.REQUESTS to stringResource(R.string.harness_tab_requests),
        HarnessSurfaceTab.CHANGES to stringResource(R.string.harness_tab_changes),
        HarnessSurfaceTab.TOOLS to stringResource(R.string.harness_tab_tools),
        HarnessSurfaceTab.FILES to stringResource(R.string.harness_tab_files),
        HarnessSurfaceTab.TERMINAL to stringResource(R.string.harness_tab_terminal),
    )
    AppScrollableTabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0),
        edgePadding = 0.dp,
        modifier = modifier.testTag("harness_work_tabs"),
    ) {
        tabs.forEach { (tab, label) ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("harness_work_tab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Composable
internal fun HarnessSecondaryDestinationMenu(
    onSelected: (HarnessSurfaceTab) -> Unit,
    modifier: Modifier = Modifier,
    onProjects: (() -> Unit)? = null,
    onRecoveryTerminal: (() -> Unit)? = null,
    onRecoveryFileServer: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = modifier.testTag("harness_secondary_menu"),
    ) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.harness_more_destinations))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (onProjects != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.harness_projects_title)) },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onProjects()
                },
            )
        }
        listOf(
            HarnessSurfaceTab.RUNTIME to R.string.harness_tab_runtime,
            HarnessSurfaceTab.SESSIONS to R.string.harness_tab_sessions,
            HarnessSurfaceTab.SETTINGS to R.string.harness_tab_settings,
            HarnessSurfaceTab.EXTENSIONS to R.string.harness_tab_extensions,
            HarnessSurfaceTab.WEB to R.string.harness_tab_web,
        ).forEach { (tab, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = {
                    Icon(
                        when (tab) {
                            HarnessSurfaceTab.RUNTIME -> Icons.Default.Build
                            HarnessSurfaceTab.SESSIONS -> Icons.Default.Chat
                            HarnessSurfaceTab.SETTINGS -> Icons.Default.Settings
                            HarnessSurfaceTab.EXTENSIONS -> Icons.Default.Extension
                            HarnessSurfaceTab.WEB -> Icons.Default.Web
                            else -> Icons.Default.MoreVert
                        },
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onSelected(tab)
                },
            )
        }
        if (onRecoveryTerminal != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.harness_recovery_terminal_menu)) },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRecoveryTerminal()
                },
            )
        }
        if (onRecoveryFileServer != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.harness_recovery_debian_files_menu)) },
                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRecoveryFileServer()
                },
            )
        }
    }
}

@Composable
internal fun HarnessPlanTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGoalEditor by rememberSaveable(state.selectedSessionId) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_plan_tab"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "plan-goal") {
            HarnessGoalDisclosure(state.goal, showGoalEditor) { showGoalEditor = it }
        }
        if (showGoalEditor) item(key = "plan-goal-editor") {
            HarnessGoalPanel(goal = state.goal, onAction = onAction)
        }
        if (state.plans.isEmpty()) {
            item(key = "plan-empty") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_plan_empty_title),
                    message = stringResource(R.string.harness_plan_empty_description),
                )
            }
        } else {
            items(state.plans, key = { "plan-${it.id}" }) { plan ->
                HarnessPlanCard(plan = plan, onAction = onAction)
            }
        }
    }
}

@Composable
internal fun HarnessRequestsTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_requests_tab"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.notice?.let { notice -> item(key = "requests-notice") { HarnessNoticeBanner(notice, onAction) } }
        if (state.questions.isNotEmpty()) {
            item(key = "requests-questions-title") {
                HarnessSectionHeading(stringResource(R.string.harness_questions_title), Icons.Default.PendingActions)
            }
            items(state.questions, key = { "request-question-${it.id}" }) { HarnessQuestionCard(it, onAction) }
        }
        if (state.approvals.isNotEmpty()) {
            item(key = "requests-approvals-title") {
                HarnessSectionHeading(stringResource(R.string.harness_approvals_title), Icons.Default.Settings)
            }
            items(state.approvals, key = { "request-approval-${it.id}" }) { HarnessApprovalCard(it, onAction) }
        }
        if (state.queue.isNotEmpty()) {
            item(key = "requests-queue-title") {
                HarnessSectionHeading(stringResource(R.string.harness_queue_title), Icons.Default.PendingActions)
            }
            items(state.queue, key = { "request-queue-${it.id}" }) { HarnessQueueCard(it, onAction) }
        }
        if (state.questions.isEmpty() && state.approvals.isEmpty() && state.queue.isEmpty()) {
            item(key = "requests-empty") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_requests_empty_title),
                    message = stringResource(R.string.harness_requests_empty_description),
                )
            }
        }
    }
}

@Composable
internal fun HarnessFilesTab(
    state: NativeHarnessUiState,
    workspaceId: String,
    onOpenProjectFiles: (String) -> Unit,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_files_tab"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "files-project") {
            AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.harness_files_project_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.harness_files_project_description), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onOpenProjectFiles(workspaceId) },
                    modifier = Modifier.fillMaxWidth().testTag("harness_open_project_files"),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.harness_open_project_files))
                }
            }
        }
        if (state.attachments.isNotEmpty()) {
            item(key = "files-thread-title") {
                HarnessSectionHeading(stringResource(R.string.harness_files_thread_title), Icons.Default.Description)
            }
            items(state.attachments, key = { "attachment-${it.id}" }) { attachment ->
                AppSectionCard(shape = AppChromeDefaults.CompactShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(attachment.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(attachment.mediaType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (attachment.isUploading) {
                            Text(stringResource(R.string.harness_files_uploading), style = MaterialTheme.typography.labelSmall)
                        } else {
                            OutlinedButton(onClick = { onAction(NativeHarnessUiAction.OpenAttachment(attachment.id)) }) {
                                Text(stringResource(R.string.harness_files_attached))
                            }
                        }
                    }
                }
            }
        }
        if (state.attachments.isEmpty()) {
            item(key = "files-thread-empty") {
                Text(
                    stringResource(R.string.harness_files_thread_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun HarnessChangesFallback(
    state: NativeHarnessUiState,
    modifier: Modifier = Modifier,
) {
    val changedFiles = remember(state.structuredTranscript) {
        state.structuredTranscript.flatMap { item ->
            item.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.File>().map { it.attachment }
        }.distinctBy { it.attachmentId }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_changes_tab"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "changes-heading") {
            AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                Text(stringResource(R.string.harness_changes_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.harness_changes_description), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (changedFiles.isEmpty()) item(key = "changes-empty") {
            AppStatePanel(
                kind = AppStateKind.Empty,
                title = stringResource(R.string.harness_changes_empty_title),
                message = stringResource(R.string.harness_changes_empty_description),
            )
        } else items(changedFiles, key = { "changed-file-${it.attachmentId}" }) { file ->
            AppSectionCard(shape = AppChromeDefaults.CompactShape) {
                Text(file.name ?: file.attachmentId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.mediaType?.takeIf(String::isNotBlank)?.let { mediaType ->
                    Text(mediaType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
