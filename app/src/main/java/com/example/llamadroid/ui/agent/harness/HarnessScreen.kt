package com.example.llamadroid.ui.agent.harness
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
/** Native Harness surface; route-owned slots keep Web UI, files, and terminal separate. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NativeHarnessScreen(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    originalWebUiContent: (@Composable () -> Unit)? = null,
    onLeavingOriginalWebUi: (() -> Unit)? = null,
    /** Optional route-owned slot for the authenticated Changes transport. */
    projectReviewContent: (@Composable () -> Unit)? = null,
    /** Stable workspace identity supplied by the project owner. */
    workspaceId: String? = null,
    projects: List<HarnessProjectUi> = emptyList(),
    projectNavigationEnabled: Boolean = false,
    /** False while the owning Room query is still producing its first snapshot. */
    projectsLoaded: Boolean = true,
    initialProjectId: String? = null,
    /** Optional notification deep-link destination, applied once after the project is selected. */
    initialAttentionTab: String? = null,
    onOpenProject: (String) -> Unit = {},
    onCloseProject: () -> Unit = {},
    onCreateProject: () -> Unit = {},
    onRenameProject: (HarnessProjectUi) -> Unit = {},
    onRemoveProject: (HarnessProjectUi) -> Unit = {},
    canBatchRemove: Boolean = false,
    onBatchRemove: (List<HarnessProjectUi>) -> Unit = {},
    onOpenProjectFiles: (String) -> Unit = {},
    /** Session-filtered file/session references from the canonical resolver. */
    referenceCatalog: List<HarnessComposerReferenceUi> = emptyList(),
    /** Called with the active @ query, excluding the trigger; null closes the resolver. */
    onReferenceQuery: (String?) -> Unit = {},
    /** Handles client-only slash commands that have no DSH `commands/execute` wire call. */
    onClientCommand: (String) -> Unit = {},
    terminalContent: (@Composable (String?) -> Unit)? = null,
    runtimeDiagnosticsContent: (@Composable () -> Unit)? = null,
    onOpenRecoveryTerminal: (() -> Unit)? = null,
    onOpenRecoveryFileServer: (() -> Unit)? = null,
    onReinstallRuntime: (() -> Unit)? = null,
    onOpenAppToolsSettings: (() -> Unit)? = null,
) {
    // The navigation stack belongs to this Harness entry, not to a mutable
    // workspace label that changes as projects and sessions are selected.
    val navigationStateKey = workspaceId ?: "harness-navigation-root"
    var selectedProjectId by rememberSaveable(navigationStateKey) { mutableStateOf(initialProjectId) }
    var navigationKeys by rememberSaveable(navigationStateKey) {
        mutableStateOf(
            listOf(
                harnessNavigationLocationKey(
                    HarnessNavigationLocation(initialProjectId, HarnessSurfaceTab.CONVERSATION)
                )
            )
        )
    }
    var routeNavigationPending by remember(navigationStateKey) { mutableStateOf(false) }
    var pendingRouteProjectId by remember(navigationStateKey) { mutableStateOf<String?>(null) }
    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    // Room can briefly restore a deleted project's id while the project list is loading.  Never
    // let that stale selection fall through to the unfiltered controller session list: the
    // project picker is the only valid owner for a project-scoped conversation.
    val projectSelectionValid = isHarnessProjectSelectionValid(selectedProjectId, projects)
    val projectLandingEligible = projectNavigationEnabled && projectsLoaded && !projectSelectionValid
    val visibleSessions = remember(state.sessions, selectedProject, projectNavigationEnabled) {
        when {
            selectedProject != null -> state.sessions.filter(selectedProject::containsThread)
            // A project-scoped route with no matching canonical project is a transient loading
            // or deletion state. Never expose the controller's global session list here: doing so
            // makes a newly recreated same-name project briefly display the previous chat.
            projectNavigationEnabled -> emptyList()
            else -> state.sessions
        }
    }
    val visibleSessionIds = remember(visibleSessions) { visibleSessions.mapTo(mutableSetOf()) { it.id } }
    LaunchedEffect(selectedProjectId, visibleSessionIds, state.selectedSessionId) {
        if (!projectLandingEligible && selectedProjectId != null && state.selectedSessionId !in visibleSessionIds) {
            visibleSessions.firstOrNull()?.let { onAction(NativeHarnessUiAction.SelectSession(it.id)) }
        }
    }
    val projectWorkspace = selectedProject?.let { project ->
        state.workspace.copy(
            projectFolder = project.projectFolder,
            backendLabel = project.backendLabel,
        )
    } ?: state.workspace
    val projectState = projectScopedHarnessState(
        state = state,
        visibleSessions = visibleSessions,
        visibleSessionIds = visibleSessionIds,
        projectWorkspace = projectWorkspace,
        projectNavigationEnabled = projectNavigationEnabled,
        selectedProjectPresent = selectedProject != null,
    )
    val workspaceKey = selectedProject?.id ?: workspaceId ?: state.workspace.projectFolder
    var selectedTabName by rememberSaveable(navigationStateKey) {
        mutableStateOf(HarnessSurfaceTab.CONVERSATION.name)
    }
    var initialAttentionApplied by rememberSaveable(navigationStateKey, initialAttentionTab) {
        mutableStateOf(false)
    }
    LaunchedEffect(initialProjectId) {
        val currentProjectId = navigationKeys.lastOrNull()
            ?.let(::harnessNavigationLocationFromKey)
            ?.projectId
        if (routeNavigationPending && pendingRouteProjectId == initialProjectId) {
            routeNavigationPending = false
            pendingRouteProjectId = null
        } else {
            routeNavigationPending = false
            pendingRouteProjectId = null
            if (currentProjectId != initialProjectId) {
                selectedProjectId = initialProjectId
                selectedTabName = HarnessSurfaceTab.CONVERSATION.name
                navigationKeys = harnessNavigationPush(
                    navigationKeys,
                    HarnessNavigationLocation(initialProjectId, HarnessSurfaceTab.CONVERSATION),
                )
            } else {
                selectedProjectId = initialProjectId
            }
        }
    }
    val requestedTab = runCatching { HarnessSurfaceTab.valueOf(selectedTabName) }
        .getOrDefault(HarnessSurfaceTab.CONVERSATION)
    val selectedTab = requestedTab
    val currentNavigationLocation = HarnessNavigationLocation(selectedProjectId, selectedTab)

    LaunchedEffect(projectNavigationEnabled, projectsLoaded, selectedProjectId, projects.map { it.id }) {
        // An empty list is also the initial value of the Room flow. Keep a deep-linked project
        // selected until that first snapshot arrives; otherwise the transient empty list turns a
        // project conversation into the global landing route and loses its intended destination.
        if (projectNavigationEnabled && projectsLoaded && !projectSelectionValid) {
            selectedProjectId = null
            selectedTabName = HarnessSurfaceTab.CONVERSATION.name
            navigationKeys = listOf(
                harnessNavigationLocationKey(
                    HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION),
                ),
            )
        }
    }

    fun applyNavigationLocation(destination: HarnessNavigationLocation) {
        val projectChanged = selectedProjectId != destination.projectId
        if (projectChanged) {
            routeNavigationPending = true
            pendingRouteProjectId = destination.projectId
            if (destination.projectId == null) onCloseProject()
            else onOpenProject(destination.projectId)
        }
        if (selectedTab == HarnessSurfaceTab.WEB && destination.tab != HarnessSurfaceTab.WEB) {
            onLeavingOriginalWebUi?.invoke()
        }
        selectedProjectId = destination.projectId
        selectedTabName = destination.tab.name
    }

    fun navigateTo(destination: HarnessNavigationLocation) {
        if (destination == currentNavigationLocation) return
        val destinationKey = harnessNavigationLocationKey(destination)
        // A tab/project location is a logical destination, so selecting it again
        // should not make Back walk through stale copies of the same screen.
        navigationKeys = navigationKeys.filterNot { it == destinationKey } + destinationKey
        applyNavigationLocation(destination)
    }

    LaunchedEffect(initialAttentionTab, initialProjectId, projects.map { it.id }) {
        val requestedAttentionTab = when (initialAttentionTab?.lowercase()) {
            "requests" -> HarnessSurfaceTab.REQUESTS
            "plan" -> HarnessSurfaceTab.PLAN
            "conversation" -> HarnessSurfaceTab.CONVERSATION
            else -> null
        }
        if (initialAttentionApplied || requestedAttentionTab == null) return@LaunchedEffect
        if (projectNavigationEnabled && initialProjectId == null && requestedAttentionTab != HarnessSurfaceTab.CONVERSATION) {
            // The route may still be resolving the notification's project. Keep the deep link
            // pending until the owner supplies that project instead of marking it handled on the
            // landing screen.
            return@LaunchedEffect
        }
        if (projectNavigationEnabled && initialProjectId != null && projects.none { it.id == initialProjectId }) {
            return@LaunchedEffect
        }
        initialAttentionApplied = true
        if (requestedAttentionTab != HarnessSurfaceTab.CONVERSATION) {
            navigateTo(HarnessNavigationLocation(initialProjectId, requestedAttentionTab))
        }
    }

    fun navigateBack() {
        // Keep Back hierarchical: a secondary panel returns to the active
        // conversation, then the project picker, then the owning dashboard.
        // Replace the small history rather than appending another route so a
        // deep link cannot create a loop of duplicate entries.
        val destination = harnessNavigationParent(currentNavigationLocation, projectNavigationEnabled)
        if (destination != null) {
            navigationKeys = listOf(harnessNavigationLocationKey(destination))
            applyNavigationLocation(destination)
            return
        }
        if (onBack == null) {
            val result = harnessNavigationBack(navigationKeys)
            val previousDestination = result.destination
            if (previousDestination != null) {
                navigationKeys = result.history
                applyNavigationLocation(previousDestination)
                return
            }
        }
        if (onBack == null) {
            // There is no external route to pop and no prior native location.
            return
        } else {
            onBack.invoke()
        }
    }

    fun navigateToProjects() {
        val destination = HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
        navigationKeys = listOf(harnessNavigationLocationKey(destination))
        applyNavigationLocation(destination)
    }

    LaunchedEffect(projectNavigationEnabled, projectsLoaded, initialProjectId, projects.map { it.id }) {
        // A newly created workspace can reach the route before the Room list emission.
        // Keep that destination until its project row arrives instead of popping it away.
        if (!projectNavigationEnabled || !projectsLoaded ||
            (initialProjectId != null && projects.none { it.id == initialProjectId })) {
            return@LaunchedEffect
        }
        val prunedHistory = harnessNavigationPruneProjects(
            history = navigationKeys,
            projectIds = projects.mapTo(mutableSetOf()) { it.id },
        )
        if (prunedHistory != navigationKeys) {
            navigationKeys = prunedHistory
            val destination = prunedHistory.lastOrNull()?.let(::harnessNavigationLocationFromKey)
                ?: HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
            applyNavigationLocation(destination)
        } else if (selectedProjectId != null && projects.none { it.id == selectedProjectId }) {
            selectedProjectId = null
        }
    }

    val projectLanding = projectLandingEligible && selectedTab == HarnessSurfaceTab.CONVERSATION
    val showHarnessBack = selectedTab != HarnessSurfaceTab.CONVERSATION ||
        selectedProjectId != null || navigationKeys.size > 1 || onBack != null
    BackHandler(enabled = showHarnessBack) { navigateBack() }
    val selectedManagementLoad = when (selectedTab) {
        HarnessSurfaceTab.SETTINGS -> projectState.managementLoads[HarnessManagementArea.SETTINGS]
        HarnessSurfaceTab.EXTENSIONS -> projectState.managementLoads[HarnessManagementArea.EXTENSIONS]
        HarnessSurfaceTab.SESSIONS -> projectState.managementLoads[HarnessManagementArea.SESSIONS]
        else -> null
    }
    LaunchedEffect(
        selectedTab,
        projectState.originalWebUi.available,
        selectedManagementLoad?.isLoading,
        selectedManagementLoad?.loaded,
        selectedManagementLoad?.errorCode,
    ) {
        when (selectedTab) {
            HarnessSurfaceTab.SETTINGS -> if (selectedManagementLoad?.isLoading != true &&
                selectedManagementLoad?.loaded != true && selectedManagementLoad?.errorCode == null
            ) onAction(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SETTINGS))
            HarnessSurfaceTab.EXTENSIONS -> if (selectedManagementLoad?.isLoading != true &&
                selectedManagementLoad?.loaded != true && selectedManagementLoad?.errorCode == null
            ) onAction(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.EXTENSIONS))
            HarnessSurfaceTab.SESSIONS -> if (selectedManagementLoad?.isLoading != true &&
                selectedManagementLoad?.loaded != true && selectedManagementLoad?.errorCode == null
            ) onAction(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.SESSIONS))
            else -> Unit
        }
    }
    val imeVisible = selectedTab == HarnessSurfaceTab.CONVERSATION &&
        WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val topContextVisible = !projectLanding &&
        selectedTab !in setOf(
            HarnessSurfaceTab.RUNTIME,
            HarnessSurfaceTab.SETTINGS,
            HarnessSurfaceTab.SESSIONS,
        ) && !imeVisible

    // The project terminal owns the whole Harness work surface. Keeping it inside the normal
    // body leaves the session strip and work tabs above the PTY, and makes the terminal compete
    // with those rows when the IME resizes the window. AppScreenScaffold is the single inset
    // owner here; HarnessTerminalScreen deliberately receives an already inset viewport.
    if (!projectLanding && selectedTab == HarnessSurfaceTab.TERMINAL) {
        AppScreenScaffold(
            title = stringResource(R.string.harness_tab_terminal),
            subtitle = stringResource(
                R.string.harness_surface_subtitle,
                selectedProject?.title ?: projectState.workspace.projectFolder
            ),
            onBack = ::navigateBack,
            modifier = modifier.fillMaxSize().testTag("harness_terminal_fullscreen"),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("harness_terminal_fullscreen_viewport")
            ) {
                terminalContent?.invoke(projectState.selectedSessionId)
                    ?: AppStatePanel(
                        kind = AppStateKind.Empty,
                        title = stringResource(R.string.harness_terminal_unavailable_title),
                        message = stringResource(R.string.harness_terminal_unavailable_description),
                    )
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        AppScreenScaffold(
            title = stringResource(R.string.harness_surface_title),
            subtitle = stringResource(
                R.string.harness_surface_subtitle,
                selectedProject?.title ?: projectState.workspace.projectFolder
            ),
            onBack = if (showHarnessBack) ::navigateBack else null,
            modifier = Modifier.fillMaxSize(),
            actions = {
                IconButton(
                    onClick = { onAction(NativeHarnessUiAction.OpenOriginalWebUi) },
                    enabled = projectState.originalWebUi.available,
                    modifier = Modifier.testTag("harness_open_web_ui")
                ) {
                    Icon(
                        imageVector = Icons.Default.Web,
                        contentDescription = stringResource(R.string.harness_open_web_ui)
                    )
                }
                onOpenAppToolsSettings?.let { openAppTools ->
                    IconButton(
                        onClick = openAppTools,
                        modifier = Modifier.testTag(HarnessAppToolsNavigationContract.testTag),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.harness_app_tools_open),
                        )
                    }
                }
                if (projectNavigationEnabled && selectedProject != null) {
                    IconButton(
                        onClick = ::navigateToProjects,
                        modifier = Modifier.testTag("harness_back_to_projects"),
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = stringResource(R.string.harness_back_to_projects),
                        )
                    }
                }
                HarnessSecondaryDestinationMenu(
                    onSelected = { navigateTo(HarnessNavigationLocation(selectedProjectId, it)) },
                    onProjects = if (projectNavigationEnabled) ::navigateToProjects else null,
                    onRecoveryTerminal = onOpenRecoveryTerminal,
                    onRecoveryFileServer = onOpenRecoveryFileServer,
                )
            },
            bottomBar = {
                if (selectedTab == HarnessSurfaceTab.CONVERSATION && !projectLanding) {
                    HarnessComposerSlot(
                        state = projectState,
                        onAction = onAction,
                        references = referenceCatalog,
                        onReferenceQuery = onReferenceQuery,
                        // Client commands are executed by the selected Harness session.  In
                        // particular, `/plan` is a session command that produces a plan-review
                        // event in Conversation; the Plan tab is only a browser for persisted
                        // plan artifacts and must not steal the command route.
                        onClientCommand = onClientCommand,
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppChromeDefaults.ScreenPadding)
                    .testTag("harness_body")
            ) {
                if (projectLanding) {
                    HarnessProjectsLanding(
                        projects = projects,
                        onOpenProject = { projectId ->
                            navigateTo(HarnessNavigationLocation(projectId, HarnessSurfaceTab.CONVERSATION))
                        },
                        onCreateProject = onCreateProject,
                        onRenameProject = onRenameProject,
                        onRemoveProject = onRemoveProject,
                        canBatchRemove = canBatchRemove,
                        onBatchRemove = onBatchRemove,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (topContextVisible && harnessRuntimeNeedsAttention(projectState.runtime)) {
                    HarnessRuntimeShortcut(
                        runtime = projectState.runtime,
                        onOpenRuntime = {
                            navigateTo(HarnessNavigationLocation(selectedProjectId, HarnessSurfaceTab.RUNTIME))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (topContextVisible) {
                    HarnessSessionStrip(
                        sessions = visibleSessions,
                        onAction = onAction
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!projectLanding && selectedTab in setOf(
                        HarnessSurfaceTab.CONVERSATION,
                        HarnessSurfaceTab.PLAN,
                        HarnessSurfaceTab.REQUESTS,
                        HarnessSurfaceTab.CHANGES,
                        HarnessSurfaceTab.TOOLS,
                        HarnessSurfaceTab.FILES,
                        HarnessSurfaceTab.COMMANDS,
                        HarnessSurfaceTab.TERMINAL,
                    )
                ) {
                    HarnessWorkTabs(
                        selectedTab = selectedTab,
                        onSelected = { navigateTo(HarnessNavigationLocation(selectedProjectId, it)) },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (!projectLanding) when (selectedTab) {
                HarnessSurfaceTab.CONVERSATION -> HarnessChatTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpenRequests = {
                        navigateTo(HarnessNavigationLocation(selectedProjectId, HarnessSurfaceTab.REQUESTS))
                    },
                    onOpenPlan = {
                        navigateTo(HarnessNavigationLocation(selectedProjectId, HarnessSurfaceTab.PLAN))
                    },
                )
                HarnessSurfaceTab.PLAN -> HarnessPlanTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.REQUESTS -> HarnessRequestsTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.CHANGES -> if (projectReviewContent != null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { projectReviewContent.invoke() }
                } else {
                    HarnessChangesFallback(projectState, Modifier.weight(1f).fillMaxWidth())
                }
                HarnessSurfaceTab.TOOLS -> HarnessToolsTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.FILES -> HarnessFilesTab(
                    state = projectState,
                    workspaceId = workspaceKey,
                    onOpenProjectFiles = onOpenProjectFiles,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.COMMANDS -> HarnessCommandsTab(
                    history = projectState.commandHistory,
                    hasSession = projectState.selectedSessionId != null,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HarnessSurfaceTab.TERMINAL -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    terminalContent?.invoke(projectState.selectedSessionId)
                        ?: AppStatePanel(
                            kind = AppStateKind.Empty,
                            title = stringResource(R.string.harness_terminal_unavailable_title),
                            message = stringResource(R.string.harness_terminal_unavailable_description),
                        )
                }
                HarnessSurfaceTab.RUNTIME -> if (runtimeDiagnosticsContent != null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { runtimeDiagnosticsContent.invoke() }
                } else {
                    HarnessRuntimeTab(
                        runtime = projectState.runtime,
                        onAction = onAction,
                        onReinstallRuntime = onReinstallRuntime,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
                HarnessSurfaceTab.SESSIONS -> HarnessSessionsTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.SETTINGS -> HarnessSettingsTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.EXTENSIONS -> HarnessExtensionsTab(
                    state = projectState,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HarnessSurfaceTab.WEB -> HarnessWebUiTab(
                    state = projectState,
                    onAction = onAction,
                    originalWebUiContent = originalWebUiContent,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                }
                }
                }
            }
        }
@Composable
internal fun HarnessTranscriptCard(
    item: HarnessTranscriptItem,
    feedback: HarnessMessageFeedbackUi?,
    onToggle: () -> Unit,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var showDetail by rememberSaveable(item.id) { mutableStateOf(false) }
    val (containerColor, labelColor, icon) = when (item.role) {
        HarnessTranscriptRole.USER -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.Code
        )
        HarnessTranscriptRole.ASSISTANT -> Triple(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.primary,
            Icons.Default.Cloud
        )
        HarnessTranscriptRole.SYSTEM -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Default.Tune
        )
        HarnessTranscriptRole.TOOL,
        HarnessTranscriptRole.THINKING -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Terminal
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.isExpandable) Modifier.clickable {
                onToggle()
                showDetail = true
            } else Modifier),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (item.role == HarnessTranscriptRole.TOOL ||
            item.role == HarnessTranscriptRole.THINKING
        ) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(18.dp))
                Text(
                    text = item.label ?: when (item.role) {
                        HarnessTranscriptRole.USER -> stringResource(R.string.harness_role_user)
                        HarnessTranscriptRole.ASSISTANT -> stringResource(R.string.harness_role_assistant)
                        HarnessTranscriptRole.SYSTEM -> stringResource(R.string.harness_role_system)
                        HarnessTranscriptRole.TOOL -> stringResource(R.string.harness_role_tool)
                        HarnessTranscriptRole.THINKING -> stringResource(R.string.harness_role_thinking)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor
                )
                if (item.isStreaming) {
                    Text(
                        text = stringResource(R.string.harness_streaming),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor.copy(alpha = 0.78f)
                    )
                }
            }
            SelectionContainer {
                val preview = if (item.isExpandable) {
                    boundedHarnessMessage(item.text.take(2_000) + "…")
                } else {
                    boundedHarnessMessage(item.text)
                }
                if (item.role == HarnessTranscriptRole.TOOL || item.role == HarnessTranscriptRole.THINKING) {
                    HarnessInlineText(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        userReferences = false,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    HarnessMarkdownText(
                        text = preview,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        userReferences = item.role == HarnessTranscriptRole.USER,
                    )
                }
            }
            item.usage?.let { HarnessTurnUsageRow(it) }
            if (item.role == HarnessTranscriptRole.ASSISTANT && item.messageId != null) {
                HarnessMessageFeedbackActions(
                    messageId = item.messageId,
                    feedback = feedback,
                    onAction = onAction
                )
            }
            if (item.isExpandable) {
                Text(
                    text = stringResource(
                        if (item.isExpanded) R.string.harness_collapse_detail
                        else R.string.harness_expand_detail
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
            }
        }
    }
    if (showDetail && item.isExpandable) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(stringResource(R.string.harness_transcript_detail_title)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        if (item.role == HarnessTranscriptRole.TOOL || item.role == HarnessTranscriptRole.THINKING) {
                            HarnessInlineText(
                                boundedHarnessMessage(item.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            HarnessMarkdownText(
                                boundedHarnessMessage(item.text),
                                textColor = MaterialTheme.colorScheme.onSurface,
                                userReferences = item.role == HarnessTranscriptRole.USER,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) {
                    Text(stringResource(R.string.harness_close))
                }
            }
        )
    }
}
@Composable
internal fun HarnessMessageFeedbackActions(
    messageId: String,
    feedback: HarnessMessageFeedbackUi?,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var dialogRatingName by rememberSaveable(messageId) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(messageId) { mutableStateOf("") }
    var category by rememberSaveable(messageId) { mutableStateOf<String?>(null) }
    val dialogRating = dialogRatingName?.let { name -> runCatching { HarnessFeedbackRating.valueOf(name) }.getOrNull() }
    val categories = listOf(
        "task-result" to stringResource(R.string.harness_feedback_category_task_result),
        "instruction-following" to stringResource(R.string.harness_feedback_category_instruction_following),
        "product-interaction" to stringResource(R.string.harness_feedback_category_product_interaction),
        "service-stability" to stringResource(R.string.harness_feedback_category_service_stability),
        "resource-cost" to stringResource(R.string.harness_feedback_category_resource_cost),
        "security-privacy-permission" to stringResource(R.string.harness_feedback_category_security_privacy_permission),
        "other" to stringResource(R.string.harness_feedback_category_other)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            HarnessFeedbackRating.POSITIVE to if (feedback?.rating == HarnessFeedbackRating.POSITIVE) {
                stringResource(R.string.harness_feedback_remove)
            } else {
                stringResource(R.string.harness_feedback_like)
            },
            HarnessFeedbackRating.NEGATIVE to if (feedback?.rating == HarnessFeedbackRating.NEGATIVE) {
                stringResource(R.string.harness_feedback_remove)
            } else {
                stringResource(R.string.harness_feedback_dislike)
            }
        ).forEach { (rating, label) ->
            OutlinedButton(
                onClick = {
                    if (feedback?.rating == rating) {
                        onAction(NativeHarnessUiAction.RetractMessageFeedback(messageId, rating))
                    } else {
                        dialogRatingName = rating.name
                        note = ""
                        category = null
                    }
                }
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (dialogRating != null) {
        AlertDialog(
            onDismissRequest = { dialogRatingName = null },
            title = { Text(stringResource(R.string.harness_feedback_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (dialogRating == HarnessFeedbackRating.POSITIVE) {
                            stringResource(R.string.harness_feedback_like)
                        } else {
                            stringResource(R.string.harness_feedback_dislike)
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(stringResource(R.string.harness_feedback_category), style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { (value, label) ->
                            FilterChip(
                                selected = category == value,
                                onClick = { category = if (category == value) null else value },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.harness_feedback_details)) },
                        placeholder = { Text(stringResource(R.string.harness_feedback_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(
                        NativeHarnessUiAction.SubmitMessageFeedback(
                            messageId = messageId,
                            rating = dialogRating,
                            note = note.takeIf { it.isNotBlank() },
                            category = category
                        )
                    )
                    dialogRatingName = null
                }) { Text(stringResource(R.string.harness_feedback_submit)) }
            },
            dismissButton = {
                TextButton(onClick = { dialogRatingName = null }) {
                    Text(stringResource(R.string.harness_cancel))
                }
            }
        )
    }
}
@Composable
internal fun HarnessQuestionCard(
    question: HarnessQuestionUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var answer by rememberSaveable(question.id) { mutableStateOf("") }
    var selectedOptions by remember(question.id) { mutableStateOf(emptySet<String>()) }
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(
            question.title.ifBlank { stringResource(R.string.harness_question_default_title) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(question.prompt, style = MaterialTheme.typography.bodyMedium)
        if (question.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    if (question.multiSelect) {
                        FilterChip(
                            selected = option in selectedOptions,
                            onClick = {
                                selectedOptions = if (option in selectedOptions) {
                                    selectedOptions - option
                                } else {
                                    selectedOptions + option
                                }
                            },
                            label = {
                                Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                onAction(
                                    NativeHarnessUiAction.AnswerQuestion(
                                        questionId = question.id,
                                        answer = option,
                                        selected = listOf(option)
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (question.allowsFreeText) {
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_answer_label)) },
                minLines = 2,
                maxLines = 5
            )
            Button(
                onClick = {
                    onAction(
                        NativeHarnessUiAction.AnswerQuestion(
                            questionId = question.id,
                            answer = answer.trim(),
                            custom = true,
                            selected = selectedOptions.toList()
                        )
                    )
                    answer = ""
                    selectedOptions = emptySet()
                },
                enabled = answer.isNotBlank() || selectedOptions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_submit_answer))
            }
            TextButton(
                onClick = {
                    onAction(
                        NativeHarnessUiAction.AnswerQuestion(
                            questionId = question.id,
                            answer = "",
                            custom = true
                        )
                    )
                    answer = ""
                    selectedOptions = emptySet()
                }
            ) {
                Text(stringResource(R.string.harness_skip_question))
            }
        }
    }
}
@Composable
internal fun HarnessApprovalCard(
    approval: HarnessApprovalUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.56f)
    ) {
        Text(
            approval.title.ifBlank { stringResource(R.string.harness_approval_default_title) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            approval.summary.ifBlank { stringResource(R.string.harness_approval_default_summary) },
            style = MaterialTheme.typography.bodyMedium
        )
        approval.riskLabel?.let {
            Text(
                stringResource(R.string.harness_risk_label, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        approval.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            SelectionContainer {
                Text(
                    boundedHarnessMessage(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onAction(NativeHarnessUiAction.Approve(approval.id)) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.harness_approve))
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.Reject(approval.id)) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.harness_reject))
            }
        }
    }
}
@Composable
internal fun HarnessPlanCard(
    plan: HarnessPlanUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(
            plan.title.ifBlank { stringResource(R.string.harness_plan_default_title) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (plan.summary.isNotBlank()) Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
        if (plan.steps.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                plan.steps.forEachIndexed { index, step ->
                    Text(
                        text = stringResource(R.string.harness_plan_step, index + 1, step),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onAction(NativeHarnessUiAction.ApprovePlan(plan.id)) },
                enabled = plan.canApprove && !plan.isApproved,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (plan.isApproved) R.string.harness_approved else R.string.harness_approve_plan))
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.RejectPlan(plan.id)) },
                enabled = plan.canReject && !plan.isApproved,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.harness_reject_plan))
            }
        }
    }
}
@Composable
private fun HarnessSessionsTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                AppPageHeader(
                    title = stringResource(R.string.harness_sessions_title),
                    subtitle = stringResource(R.string.harness_sessions_description),
                    trailing = {
                        IconButton(onClick = { onAction(NativeHarnessUiAction.CreateSession) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.harness_new_session))
                        }
                    }
                )
                OutlinedTextField(
                    value = state.sessionSearchQuery,
                    onValueChange = { onAction(NativeHarnessUiAction.SearchSessions(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("harness_session_search"),
                    label = { Text(stringResource(R.string.harness_search_sessions)) },
                    singleLine = true
                )
                if (state.sessionSearchResults.isNotEmpty()) {
                    Text(
                        stringResource(R.string.harness_search_results),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    state.sessionSearchResults.forEach { result ->
                        TextButton(
                            onClick = { onAction(NativeHarnessUiAction.SelectSession(result.sessionId)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(result.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        items(state.sessions, key = { it.id }) { session ->
            HarnessSessionCard(
                session = session,
                deleting = session.id in state.deletingSessionIds,
                deletionFailed = session.id in state.failedSessionDeletionIds,
                onAction = onAction,
            )
        }
        item {
            HarnessWorkspaceCard(state = state, onAction = onAction)
        }
        item {
            HarnessWorkspaceManagerCard(
                state = state.workspaceManager,
                sessions = state.sessions,
                onAction = onAction
            )
        }
        if (state.legacyHistoryAvailable) {
            item {
                AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                    Text(
                        stringResource(R.string.harness_legacy_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(stringResource(R.string.harness_legacy_history_description))
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.OpenLegacyHistory) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.harness_open_legacy_history))
                    }
                }
            }
        }
    }
}
@Composable
private fun HarnessSessionCard(
    session: HarnessSessionUiState,
    deleting: Boolean,
    deletionFailed: Boolean,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var renaming by rememberSaveable(session.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(session.id) { mutableStateOf(session.title) }
    AppSectionCard(
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = if (session.isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (renaming) {
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.harness_session_title_label)) },
                        singleLine = true
                    )
                } else {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    session.projectFolder,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.harness_session_backend, session.backendLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                session.lastActivityLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (session.unreadCount > 0) {
                Text(
                    stringResource(R.string.harness_unread_count, session.unreadCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HarnessSessionOverflowCardActions(
                session = session,
                deleting = deleting,
                deletionFailed = deletionFailed,
                onAction = onAction,
            )
            if (!renaming) {
                OutlinedButton(
                    onClick = { onAction(NativeHarnessUiAction.SelectSession(session.id)) },
                    enabled = !deleting,
                ) {
                    Text(stringResource(if (session.isSelected) R.string.harness_selected else R.string.harness_open))
                }
            }
        }
        if (deleting) {
            Text(
                stringResource(R.string.harness_session_delete_pending),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (deletionFailed) {
            Text(
                stringResource(R.string.harness_session_delete_retry),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (renaming) {
                Button(
                    onClick = {
                        onAction(NativeHarnessUiAction.RenameSession(session.id, titleDraft.trim()))
                        renaming = false
                    },
                    enabled = titleDraft.isNotBlank() && !deleting
                ) {
                    Text(stringResource(R.string.harness_save))
                }
                OutlinedButton(onClick = { renaming = false }, enabled = !deleting) {
                    Text(stringResource(R.string.harness_cancel))
                }
            } else if (session.canRename && !deleting) {
                OutlinedButton(onClick = { renaming = true }) {
                    Text(stringResource(R.string.harness_rename))
                }
            }
            if (session.canFork && !deleting) {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ForkSession(session.id)) }) {
                    Text(stringResource(R.string.harness_fork))
                }
            }
            if (session.canArchive && !deleting) {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ArchiveSession(session.id)) }) {
                    Text(stringResource(R.string.harness_archive))
                }
            }
            if (session.canUnarchive && !deleting) {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.UnarchiveSession(session.id)) }) {
                    Text(stringResource(R.string.harness_unarchive))
                }
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.ExportSessionLog(session.id)) },
                enabled = !deleting,
            ) {
                Text(stringResource(R.string.harness_session_export))
            }
        }
    }
}
@Composable
internal fun HarnessWorkspaceCard(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(
            title = stringResource(R.string.harness_workspace_title),
            icon = Icons.Default.Folder
        )
        HarnessValueLine(stringResource(R.string.harness_workspace_project), state.workspace.projectFolder)
        HarnessValueLine(stringResource(R.string.harness_workspace_backend), state.workspace.backendLabel)
        state.workspace.rootLabel?.let { HarnessValueLine(stringResource(R.string.harness_workspace_root), it) }
        state.workspace.runStatusLabel?.let { HarnessValueLine(stringResource(R.string.harness_workspace_run), it) }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.OpenWorkspace) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_open_workspace))
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.OpenProjectIntegration) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_app_tools))
        }
    }
}
@Composable
private fun HarnessSettingsTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    state.settingsDocument?.let { document ->
        HarnessSettingsDocumentDialog(document = document, onAction = onAction)
    }
    HarnessSettingsNavigator(
        state = state,
        onAction = onAction,
        modifier = modifier
    )
}

internal fun harnessProviderOptionLabel(
    option: HarnessProviderOption,
    allOptions: List<HarnessProviderOption>,
    officialLabelTemplate: String,
): String {
    val displayName = if (option.id == "deepseek-official" &&
        !option.name.contains("official", ignoreCase = true)
    ) {
        officialLabelTemplate.replace("%1\$s", option.name)
    } else {
        option.name
    }
    return if (allOptions.count { it.name.equals(option.name, ignoreCase = true) } > 1 &&
        option.id != "deepseek-official"
    ) {
        "$displayName (${option.id})"
    } else {
        displayName
    }
}
@Composable
internal fun HarnessProviderCard(
    provider: HarnessProviderUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    includeProviderConfigurations: Boolean = true
) {
    var providerMenuOpen by remember { mutableStateOf(false) }
    val selectedProvider = provider.providers.firstOrNull { it.id == provider.selectedProviderId }
    val providerOptions = remember(provider.providers) {
        provider.providers.sortedWith(
            compareBy<HarnessProviderOption> { it.id != "deepseek-official" }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id }
        )
    }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val officialLabelTemplate = stringResource(R.string.harness_provider_official_label)
    val providerLabels = providerOptions.map {
        harnessProviderOptionLabel(it, providerOptions, officialLabelTemplate)
    }
    // A model without a known context window cannot be selected safely. The
    // provider editor is the place to enter an explicit per-model override.
    val modelIds = selectedProvider?.models.orEmpty().filter {
        selectedProvider?.let { providerOption -> harnessModelContextKnown(providerOption, it) } == true
    }
    val unknownModelCount = (selectedProvider?.models.orEmpty().size - modelIds.size).coerceAtLeast(0)
    val modelOptions = selectedProvider?.let { provider ->
        modelIds.map { modelId -> harnessModelOptionLabel(provider, modelId) }
    }.orEmpty()
    val selectedCatalogFailure = selectedProvider?.let { selected ->
        provider.catalogFailures.firstOrNull { it.providerId == selected.id }
    }
    val modelCatalogStatus = when {
        provider.isCatalogLoading -> stringResource(R.string.harness_refreshing_models)
        provider.catalogRefreshFailed -> stringResource(R.string.harness_model_refresh_failed)
        selectedCatalogFailure != null -> stringResource(
            R.string.harness_model_catalog_failure,
            selectedCatalogFailure.providerName,
            selectedCatalogFailure.message,
        )
        else -> null
    }
    var effortMenuOpen by remember { mutableStateOf(false) }
    val effortOptions = selectedProvider
        ?.let { selected -> provider.selectedModel?.let(selected.reasoningEfforts::get) }
        .orEmpty()
    val providerDefaultEffortLabel = stringResource(R.string.harness_reasoning_provider_default)
    val selectedEffortLabel = effortOptions.firstOrNull { it.id == provider.selectedReasoningEffort }?.name
        ?: providerDefaultEffortLabel
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_provider_title), icon = Icons.Default.Cloud)
        Text(stringResource(R.string.harness_provider_description), style = MaterialTheme.typography.bodySmall)
        HarnessChoiceField(
            label = stringResource(R.string.harness_provider_label),
            value = selectedProvider?.let {
                harnessProviderOptionLabel(it, providerOptions, officialLabelTemplate)
            }
                ?: stringResource(R.string.harness_choose_provider),
            expanded = providerMenuOpen,
            onExpandedChange = { providerMenuOpen = it },
            options = providerLabels,
            onOptionSelected = { name ->
                providerOptions.getOrNull(providerLabels.indexOf(name))?.let {
                    onAction(NativeHarnessUiAction.SelectProvider(it.id))
                }
                providerMenuOpen = false
            }
        )
        HarnessChoiceField(
            label = stringResource(R.string.harness_model_label),
            value = provider.selectedModel?.let { selectedProvider?.let { p -> harnessModelOptionLabel(p, it) } }
                ?: stringResource(R.string.harness_choose_model),
            expanded = modelMenuOpen,
            onExpandedChange = { modelMenuOpen = it },
            options = modelOptions,
            onOptionSelected = { label ->
                val index = modelOptions.indexOf(label)
                modelIds.getOrNull(index)?.let { onAction(NativeHarnessUiAction.SelectModel(it)) }
                modelMenuOpen = false
            },
            enabled = true,
            actionText = stringResource(R.string.harness_refresh_models),
            actionEnabled = !provider.isCatalogLoading,
            onAction = { onAction(NativeHarnessUiAction.RefreshModelCatalog) },
            statusText = modelCatalogStatus,
            statusIsError = !provider.isCatalogLoading &&
                (provider.catalogRefreshFailed || selectedCatalogFailure != null),
        )
        if (unknownModelCount > 0) {
            Text(
                stringResource(R.string.harness_model_context_unknown_count, unknownModelCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (provider.supportsThinking && effortOptions.isNotEmpty()) {
            HarnessChoiceField(
                label = stringResource(R.string.harness_reasoning_effort_label),
                value = selectedEffortLabel,
                expanded = effortMenuOpen,
                onExpandedChange = { effortMenuOpen = it },
                options = listOf(providerDefaultEffortLabel) + effortOptions.map { it.name },
                onOptionSelected = { name ->
                    val effort = effortOptions.firstOrNull { it.name == name }?.id
                    onAction(NativeHarnessUiAction.SelectReasoningEffort(effort))
                    effortMenuOpen = false
                }
            )
        }
        provider.catalogFailures.forEach { failure ->
            Text(
                stringResource(R.string.harness_model_catalog_failure, failure.providerName, failure.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        provider.endpointLabel?.let { HarnessValueLine(stringResource(R.string.harness_endpoint_label), it) }
        if (provider.supportsThinking) {
            HarnessSwitchRow(
                title = stringResource(R.string.harness_thinking_label),
                description = stringResource(R.string.harness_thinking_description),
                checked = provider.thinkingEnabled,
                onCheckedChange = { onAction(NativeHarnessUiAction.SetThinkingEnabled(it)) }
            )
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.OpenModelManager) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_open_model_manager))
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshModelCatalog) },
            enabled = !provider.isCatalogLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    if (provider.isCatalogLoading) R.string.harness_refreshing_models
                    else R.string.harness_refresh_models
                )
            )
        }
        if (includeProviderConfigurations) {
            NativeHarnessCustomProviderPanel(
                existingRoutes = (provider.providers.map { it.id } + provider.configs.map { it.id }).toSet(),
                protocols = provider.customProviderProtocols.ifEmpty { NATIVE_CUSTOM_PROVIDER_PROTOCOLS },
                enabled = provider.canCreateProvider,
                onCreate = { onAction(NativeHarnessUiAction.CreateCustomProvider(it)) },
                discovery = provider.customProviderDiscovery,
                onDiscover = { onAction(NativeHarnessUiAction.DiscoverCustomProviderModels(it)) },
                onDismissDiscovery = { onAction(NativeHarnessUiAction.DismissCustomProviderModels) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.harness_provider_credentials_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.harness_provider_credentials_description),
                style = MaterialTheme.typography.bodySmall
            )
            provider.configs.forEach { config ->
                HarnessProviderConfigCard(config = config, onAction = onAction)
            }
        }
    }
    if (provider.modelEditorOpen) {
        HarnessModelEditorDialog(provider = provider, onAction = onAction)
    }
}
@Composable
internal fun HarnessProviderConfigCard(
    config: HarnessProviderConfigUi,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var credentialDraft by remember(config.id) { mutableStateOf("") }
    val connectionFields = config.fields.filter {
        (it.path.lastOrNull() ?: it.key.substringAfterLast('.')).lowercase() in
            setOf("name", "displayname", "baseurl")
    }
    val advancedFields = config.fields.filterNot { it in connectionFields }
    val configuredModels = remember(config.fields) {
        config.fields.firstOrNull(::isNativeHarnessProviderModelsField)?.value
            ?.let(::parseHarnessJsonValue)?.jsonArrayOrNull()?.size
    }
    val credentialReference = config.credentialReference
        ?: config.id.takeIf { config.credentialOptional }
            ?.let(::nativeCustomProviderCredentialReference)
    LaunchedEffect(config.id, config.credentialConfigured) {
        if (config.credentialConfigured) credentialDraft = ""
    }
    AppSectionCard(
        modifier = modifier,
        shape = AppChromeDefaults.CompactShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Text(config.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.harness_provider_connection_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        connectionFields.forEach { field ->
            val label = if ((field.path.lastOrNull() ?: field.key.substringAfterLast('.')).equals("baseUrl", true)) {
                stringResource(R.string.harness_custom_provider_base_url)
            } else stringResource(R.string.harness_custom_provider_display_name)
            HarnessProviderFieldControl(field.copy(label = label), config.id, onAction)
        }
        HarnessProviderAuthPanel(config, onAction)
        credentialReference?.let { reference ->
            OutlinedTextField(
                value = credentialDraft,
                onValueChange = { credentialDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("harness_provider_credential_${config.id}"),
                enabled = config.credentialWritable,
                label = {
                    Text(
                        stringResource(
                            if (config.credentialOptional) R.string.harness_api_key_optional_label
                            else R.string.harness_api_key_label
                        )
                    )
                },
                supportingText = {
                    Text(
                        if (config.credentialOptional && !config.credentialConfigured) {
                            stringResource(R.string.harness_api_key_optional_supporting)
                        } else if (config.credentialConfigured) {
                            stringResource(R.string.harness_api_key_configured)
                        } else {
                            stringResource(R.string.harness_api_key_missing)
                        }
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Text(
                reference,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onAction(NativeHarnessUiAction.SetProviderCredential(config.id, credentialDraft))
                    },
                    enabled = config.credentialWritable && credentialDraft.isNotBlank()
                ) {
                    Text(stringResource(R.string.harness_save_api_key))
                }
                if (config.credentialConfigured) {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.UnsetProviderCredential(config.id)) },
                        enabled = config.credentialWritable
                    ) {
                        Text(stringResource(R.string.harness_clear_api_key))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.harness_custom_provider_models),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        configuredModels?.let { count ->
            Text(
                stringResource(R.string.harness_provider_configured_models, count),
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.DiscoverProviderModels(config.id)) },
            enabled = config.canSave && !config.discovery.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.harness_discover_models))
        }
        NativeHarnessProviderDiscoveryPanel(config.id, config.discovery, onAction)
        AppAdvancedSection(
            title = stringResource(R.string.harness_provider_advanced),
            modifier = Modifier.testTag("harness_provider_advanced_${config.id}"),
            initiallyExpanded = false
        ) {
            advancedFields.forEach { field ->
                HarnessProviderFieldControl(field = field, providerId = config.id, onAction = onAction)
            }
            Button(
                onClick = { onAction(NativeHarnessUiAction.SaveProvider(config.id)) },
                enabled = config.canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_save_provider))
            }
        }
        if (config.canDelete) {
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.DeleteProvider(config.id)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_delete_provider), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
@Composable
private fun HarnessProviderFieldControl(
    field: HarnessSchemaField,
    providerId: String,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    when (field.type) {
        HarnessSchemaFieldType.TOGGLE -> HarnessSwitchRow(
            title = field.label,
            description = field.description,
            checked = field.value.equals("true", ignoreCase = true),
            enabled = field.enabled,
            onCheckedChange = {
                onAction(NativeHarnessUiAction.UpdateProviderField(providerId, field.key, it.toString()))
            }
        )
        HarnessSchemaFieldType.TEXT,
        HarnessSchemaFieldType.INTEGER,
        HarnessSchemaFieldType.DECIMAL,
        HarnessSchemaFieldType.JSON -> {
            var draft by rememberSaveable(providerId, field.key) { mutableStateOf(field.value) }
            var upstream by rememberSaveable(providerId, field.key, "upstream") {
                mutableStateOf(field.value)
            }
            LaunchedEffect(providerId, field.key, field.value) {
                // Adopt refreshed values only while the local draft is clean.
                // This keeps a failed or pending save visible to the user.
                if (draft == upstream) draft = field.value
                upstream = field.value
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = field.enabled,
                label = { Text(field.label) },
                supportingText = field.description?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        HarnessSchemaFieldType.INTEGER -> KeyboardType.Number
                        HarnessSchemaFieldType.DECIMAL -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    }
                ),
                singleLine = field.type != HarnessSchemaFieldType.JSON,
                minLines = if (field.type == HarnessSchemaFieldType.JSON) 3 else 1
            )
            if (draft != field.value) {
                OutlinedButton(
                    onClick = {
                        onAction(NativeHarnessUiAction.UpdateProviderField(providerId, field.key, draft))
                    },
                    enabled = field.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.harness_save))
                }
            }
        }
        HarnessSchemaFieldType.CHOICE -> {
            var expanded by remember(providerId, field.key) { mutableStateOf(false) }
            HarnessChoiceField(
                label = field.label,
                value = field.value,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                options = field.options,
                enabled = field.enabled,
                onOptionSelected = {
                    onAction(NativeHarnessUiAction.UpdateProviderField(providerId, field.key, it))
                    expanded = false
                }
            )
        }
    }
}
@Composable
internal fun HarnessSchemaSectionCard(
    section: HarnessSchemaSection,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        section.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        section.fields.forEach { field ->
            HarnessSchemaFieldControl(field = field, onAction = onAction)
        }
    }
}
@Composable
private fun HarnessSchemaFieldControl(
    field: HarnessSchemaField,
    onAction: (NativeHarnessUiAction) -> Unit,
    onFieldChange: ((String, String) -> Unit)? = null
) {
    val update = { value: String ->
        onFieldChange?.invoke(field.key, value)
            ?: onAction(NativeHarnessUiAction.UpdateSchemaField(field.key, value))
    }
    when (field.type) {
        HarnessSchemaFieldType.TOGGLE -> HarnessSwitchRow(
            title = field.label,
            description = field.description,
            checked = field.value.equals("true", ignoreCase = true),
            enabled = field.enabled,
            onCheckedChange = { update(it.toString()) }
        )
        HarnessSchemaFieldType.CHOICE -> {
            var expanded by remember(field.key) { mutableStateOf(false) }
            HarnessChoiceField(
                label = field.label,
                value = field.value.ifBlank { stringResource(R.string.harness_choose_value) },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                options = field.options,
                enabled = field.enabled,
                onOptionSelected = {
                    update(it)
                    expanded = false
                }
            )
            field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        HarnessSchemaFieldType.TEXT,
        HarnessSchemaFieldType.INTEGER,
        HarnessSchemaFieldType.DECIMAL,
        HarnessSchemaFieldType.JSON -> {
            if (onFieldChange == null) {
                var draft by rememberSaveable(field.key) { mutableStateOf(field.value) }
                var upstream by rememberSaveable(field.key, "upstream") {
                    mutableStateOf(field.value)
                }
                LaunchedEffect(field.key, field.value) {
                    if (draft == upstream) draft = field.value
                    upstream = field.value
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = field.enabled,
                    label = { Text(field.label) },
                    supportingText = field.description?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            HarnessSchemaFieldType.INTEGER -> KeyboardType.Number
                            HarnessSchemaFieldType.DECIMAL -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }
                    ),
                    singleLine = field.type != HarnessSchemaFieldType.JSON,
                    minLines = if (field.type == HarnessSchemaFieldType.JSON) 3 else 1
                )
                if (draft != field.value) {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.UpdateSchemaField(field.key, draft)) },
                        enabled = field.enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.harness_save))
                    }
                }
            } else {
                OutlinedTextField(
                    value = field.value,
                    onValueChange = update,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = field.enabled,
                    label = { Text(field.label) },
                    supportingText = field.description?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            HarnessSchemaFieldType.INTEGER -> KeyboardType.Number
                            HarnessSchemaFieldType.DECIMAL -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }
                    ),
                    singleLine = field.type != HarnessSchemaFieldType.JSON,
                    minLines = if (field.type == HarnessSchemaFieldType.JSON) 3 else 1
                )
            }
        }
    }
    HarnessSchemaFieldReset(field = field, enabled = onFieldChange == null, onAction = onAction)
}
@Composable
internal fun HarnessCapabilitySectionCard(
    section: HarnessCapabilitySectionUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = section.title, icon = Icons.Default.Tune)
        section.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        section.actions.forEach { capability ->
            HarnessCapabilityActionCard(capability = capability, onAction = onAction)
        }
    }
}
@Composable
private fun HarnessCapabilityActionCard(
    capability: HarnessCapabilityActionUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val values = remember(capability.id) {
        mutableStateMapOf<String, String>().also { map ->
            capability.fields.forEach { field -> map[field.key] = field.value }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(capability.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(capability.summary, style = MaterialTheme.typography.bodySmall)
        capability.fields.forEach { field ->
            HarnessSchemaFieldControl(
                field = field.copy(value = values[field.key] ?: field.value),
                onAction = onAction,
                onFieldChange = { key, value -> values[key] = value }
            )
        }
        Button(
            onClick = {
                onAction(NativeHarnessUiAction.InvokeCapability(capability.id, values.toMap()))
            },
            enabled = capability.enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (capability.requiresApproval) R.string.harness_run_capability_approval
                    else R.string.harness_run_capability
                )
            )
        }
    }
}
@Composable
internal fun HarnessChoiceField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    enabled: Boolean = true,
    actionText: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    statusText: String? = null,
    statusIsError: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled && (options.isNotEmpty() || onAction != null),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            if (onAction != null && actionText != null) {
                DropdownMenuItem(
                    text = { Text(actionText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = onAction,
                    enabled = actionEnabled,
                )
            }
            statusText?.takeIf(String::isNotBlank)?.let { status ->
                DropdownMenuItem(
                    text = {
                        Text(
                            status,
                            color = if (statusIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { onOptionSelected(option) }
                )
            }
        }
    }
}
