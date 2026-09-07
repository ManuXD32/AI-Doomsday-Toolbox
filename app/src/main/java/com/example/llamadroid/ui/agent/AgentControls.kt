package com.example.llamadroid.ui.agent

import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import com.example.llamadroid.data.db.AgentProjectEventEntity
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.PromptContextSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun remainingAgentImeBottomPx(
    fullWindowHeightPx: Int,
    composerBottomInWindowPx: Int,
    imeBottomPx: Int
): Int {
    if (composerBottomInWindowPx <= 0) return 0
    val alreadyReservedBottomPx = (fullWindowHeightPx - composerBottomInWindowPx).coerceAtLeast(0)
    return (imeBottomPx - alreadyReservedBottomPx).coerceAtLeast(0)
}

/**
 * One keyboard-inset owner for both Agent composers.
 *
 * Some edge-to-edge devices resize the Compose root for the IME and some leave the
 * full inset for Compose to consume. Measuring the window space already reserved
 * lets this host add only the remainder, avoiding both the hidden composer and the
 * former double-height black band.
 */
@Composable
fun AgentComposerHost(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var composerBottomInWindowPx by remember { mutableIntStateOf(0) }
    val fullWindowHeightPx = maxOf(
        view.rootView.height,
        view.resources.displayMetrics.heightPixels
    )
    val remainingImePadding = with(density) {
        remainingAgentImeBottomPx(
            fullWindowHeightPx = fullWindowHeightPx,
            composerBottomInWindowPx = composerBottomInWindowPx,
            imeBottomPx = WindowInsets.ime.getBottom(this)
        ).toDp()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                composerBottomInWindowPx = (
                    coordinates.positionInWindow().y + coordinates.size.height
                ).roundToInt()
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = verticalArrangement
        ) {
            content()
            Spacer(modifier = Modifier.height(remainingImePadding + 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTopBar(
    onNavigateBack: () -> Unit,
    onShowAgentSettings: () -> Unit,
    onShowToolSettings: () -> Unit,
    onShowSettings: () -> Unit,
    onShowSetupInfo: () -> Unit,
    onShowProjectManagement: () -> Unit,
    onShowCustomTools: () -> Unit,
    onShowCustomAgents: () -> Unit,
    onShowSkills: () -> Unit,
    onShowCommands: () -> Unit,
    showAllOutput: Boolean,
    onToggleAllOutput: () -> Unit,
    showDebugPanel: Boolean,
    onToggleDebugPanel: () -> Unit,
    onStopAll: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                stringResource(R.string.agent_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack, modifier = Modifier.walkthroughTarget("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
            Box {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_workspace_title)) },
                        onClick = { showMenu = false; onNavigateToWorkspace() },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_stop_all)) },
                        onClick = { showMenu = false; onStopAll() },
                        leadingIcon = { Icon(Icons.Default.StopCircle, null, tint = MaterialTheme.colorScheme.error) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_settings_title)) },
                        onClick = { showMenu = false; onShowAgentSettings() },
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_tool_settings_title)) },
                        onClick = { showMenu = false; onShowToolSettings() },
                        leadingIcon = { Icon(Icons.Default.Tune, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_custom_tools_title)) },
                        onClick = { showMenu = false; onShowCustomTools() },
                        leadingIcon = { Icon(Icons.Default.Build, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_custom_agents_title)) },
                        onClick = { showMenu = false; onShowCustomAgents() },
                        leadingIcon = { Icon(Icons.Default.SmartToy, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_skills_title)) },
                        onClick = { showMenu = false; onShowSkills() },
                        leadingIcon = { Icon(Icons.Default.Extension, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_commands_title)) },
                        onClick = { showMenu = false; onShowCommands() },
                        leadingIcon = { Icon(Icons.Default.Terminal, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_project_mgmt_title)) },
                        onClick = { showMenu = false; onShowProjectManagement() },
                        leadingIcon = { Icon(Icons.Default.Inventory, null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(if (showAllOutput) stringResource(R.string.agent_hide_output) else stringResource(R.string.agent_show_output)) },
                        onClick = { showMenu = false; onToggleAllOutput() },
                        leadingIcon = { Icon(if (showAllOutput) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (showDebugPanel) stringResource(R.string.agent_hide_debug) else stringResource(R.string.agent_show_debug)) },
                        onClick = { showMenu = false; onToggleDebugPanel() },
                        leadingIcon = { Icon(Icons.Default.Code, null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_settings)) },
                        onClick = { showMenu = false; onShowSettings() },
                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_setup_title)) },
                        onClick = { showMenu = false; onShowSetupInfo() },
                        leadingIcon = { Icon(Icons.Default.Help, null) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun AgentWorkspaceConsoleHeader(
    projectTitle: String?,
    projectPath: String?,
    backendLabel: String,
    modelLabel: String,
    connectionLabel: String,
    isConnected: Boolean,
    isRunning: Boolean,
    statusText: String,
    contextSnapshot: PromptContextSnapshot?,
    lastSavedAt: Long?,
    planningModeEnabled: Boolean,
    onShowDashboard: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onStopAll: () -> Unit,
    onPlanningModeChanged: (Boolean) -> Unit,
    onShowAgentSettings: () -> Unit,
    onShowKnowledgeBases: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasProject = !projectPath.isNullOrBlank()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val horizontalScroll = rememberScrollState()
    val detailScrollState = rememberScrollState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val resolvedProject = projectTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.agent_console_no_project)
    val resolvedPath = projectPath?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.agent_workspace_no_project_path)
    val runtimeLabel = if (isRunning) {
        statusText.takeIf { it.isNotBlank() } ?: stringResource(R.string.agent_console_running)
    } else {
        stringResource(R.string.agent_console_ready)
    }
    val contextLabel = contextSnapshot?.let { snapshot ->
        val promptTokens = snapshot.actualPromptTokens ?: snapshot.calibratedRequestTokens ?: snapshot.packedEstimatedTokens
        val percentUsed = snapshot.actualPercentUsed ?: snapshot.percentUsed
        stringResource(
            R.string.agent_console_context_value,
            percentUsed,
            promptTokens,
            snapshot.contextSize
        )
    } ?: stringResource(R.string.agent_console_context_unknown)
    val savedLabel = lastSavedAt?.let { timestamp ->
        stringResource(R.string.agent_console_last_saved_value, timeFormatter.format(Date(timestamp)))
    } ?: stringResource(R.string.agent_console_last_saved_unknown)
    val displayedPromptTokens = contextSnapshot?.actualPromptTokens ?: contextSnapshot?.calibratedRequestTokens ?: contextSnapshot?.packedEstimatedTokens
    val displayedPercentUsed = contextSnapshot?.actualPercentUsed ?: contextSnapshot?.percentUsed
    val contextProgress = displayedPercentUsed?.div(100f)?.coerceIn(0f, 1f)
    val contextTone = when {
        contextSnapshot != null && displayedPercentUsed != null && displayedPercentUsed >= contextSnapshot.thresholdPercent ->
            MaterialTheme.colorScheme.error
        contextSnapshot != null && displayedPercentUsed != null && displayedPercentUsed >= 75 ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resolvedProject,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasProject) {
                        Text(
                            text = resolvedPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = runtimeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onPlanningModeChanged(!planningModeEnabled) },
                        shape = RoundedCornerShape(50),
                        color = if (planningModeEnabled) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (planningModeEnabled) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (planningModeEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(
                                    if (planningModeEnabled) R.string.agent_mode_plan else R.string.agent_mode_build
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(onClick = onShowDashboard) {
                        Icon(Icons.Default.Dashboard, stringResource(R.string.agent_dashboard_return))
                    }
                    if (hasProject) {
                        IconButton(onClick = onNavigateToWorkspace) {
                            Icon(Icons.Default.Folder, stringResource(R.string.agent_workspace_title))
                        }
                    }
                    if (isRunning) {
                        IconButton(onClick = onStopAll) {
                            Icon(
                                Icons.Default.StopCircle,
                                stringResource(R.string.agent_stop_all),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (hasProject) {
                        ContextUsageCircle(
                            percentUsed = displayedPercentUsed,
                            progress = contextProgress,
                            color = contextTone,
                            isWorking = isRunning,
                            onClick = { expanded = !expanded }
                        )
                    }
                }
            }

            if (expanded && hasProject) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgentConsoleChip(
                        label = stringResource(R.string.agent_console_connection),
                        value = connectionLabel,
                        containerColor = if (isConnected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColor = if (isConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    AgentConsoleChip(
                        label = stringResource(R.string.agent_console_backend),
                        value = backendLabel
                    )
                    AgentConsoleChip(
                        label = stringResource(R.string.agent_console_model),
                        value = modelLabel
                    )
                    AgentConsoleChip(
                        label = stringResource(R.string.agent_console_last_saved),
                        value = savedLabel
                    )
                    FilterChip(
                        selected = planningModeEnabled,
                        onClick = { onPlanningModeChanged(true) },
                        label = { Text(stringResource(R.string.agent_mode_plan), maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    FilterChip(
                        selected = !planningModeEnabled,
                        onClick = { onPlanningModeChanged(false) },
                        label = { Text(stringResource(R.string.agent_mode_build), maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(18.dp))
                        }
                    )
                    AssistChip(
                        onClick = onShowAgentSettings,
                        label = { Text(stringResource(R.string.agent_settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = onShowKnowledgeBases,
                        label = { Text(stringResource(R.string.agent_console_knowledge), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Inventory, null, modifier = Modifier.size(18.dp)) }
                    )
                }
                if (contextSnapshot != null && displayedPromptTokens != null && displayedPercentUsed != null) {
                    AgentContextDetails(
                        snapshot = contextSnapshot,
                        displayedPromptTokens = displayedPromptTokens,
                        displayedPercentUsed = displayedPercentUsed,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        detailScrollState = detailScrollState
                    )
                } else {
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextUsageCircle(
    percentUsed: Int?,
    progress: Float?,
    color: Color,
    isWorking: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (progress == null && isWorking) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 4.dp,
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            CircularProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 4.dp,
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text(
            text = percentUsed?.let { "$it%" } ?: if (isWorking) "…" else "--",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun AgentConsoleChip(
    label: String,
    value: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 120.dp, max = 220.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AgentContextDetails(
    snapshot: PromptContextSnapshot,
    displayedPromptTokens: Int,
    displayedPercentUsed: Int,
    contentColor: Color,
    detailScrollState: androidx.compose.foundation.ScrollState
) {
    val timeFormatter = remember {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    val progress = (displayedPromptTokens.toFloat() / snapshot.contextSize.toFloat()).coerceIn(0f, 1f)
    val detailText = when {
        snapshot.isUsingHardCompactedBasis ->
            stringResource(
                R.string.agent_context_usage_hard_compacted_detail,
                snapshot.rawEstimatedTokens,
                snapshot.packedEstimatedTokens,
                snapshot.thresholdPercent
            )
        snapshot.didCompactHistory || snapshot.omittedCount > 0 ->
            stringResource(
                R.string.agent_context_usage_compacted_detail,
                snapshot.rawEstimatedTokens,
                snapshot.packedEstimatedTokens,
                snapshot.omittedCount
            )
        else ->
            stringResource(
                R.string.agent_context_usage_normalized_detail,
                snapshot.rawEstimatedTokens,
                snapshot.thresholdPercent
            )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
            .verticalScroll(detailScrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(
                R.string.agent_context_usage_label,
                displayedPercentUsed,
                displayedPromptTokens,
                snapshot.contextSize
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.18f)
        )
        Text(
            text = detailText,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.88f)
        )
        snapshot.actualPromptTokens?.let { actualPromptTokens ->
            Text(
                text = stringResource(
                    R.string.agent_context_usage_actual_detail,
                    actualPromptTokens,
                    snapshot.actualCompletionTokens?.toString() ?: "?",
                    snapshot.actualTotalTokens?.toString() ?: "?"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.88f)
            )
        }
        if (
            snapshot.maximumInputTokens != null &&
            snapshot.safetyReserveTokens != null &&
            snapshot.effectiveOutputTokens != null
        ) {
            Text(
                text = stringResource(
                    R.string.agent_context_budget_breakdown,
                    snapshot.packedEstimatedTokens,
                    snapshot.rawToolSchemaTokens,
                    snapshot.actualPromptTokens
                        ?: snapshot.calibratedRequestTokens
                        ?: snapshot.packedEstimatedTokens,
                    snapshot.maximumInputTokens,
                    snapshot.effectiveOutputTokens,
                    snapshot.safetyReserveTokens,
                    snapshot.countSource ?: "estimate"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.88f)
            )
        }
        if (snapshot.recentCompactions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.agent_context_usage_history_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            snapshot.recentCompactions.take(3).forEach { event ->
                Text(
                    text = stringResource(
                        R.string.agent_context_usage_history_item_verbose,
                        timeFormatter.format(Date(event.timestamp)),
                        event.rawEstimatedTokens,
                        event.packedEstimatedTokens,
                        event.omittedCount,
                        event.compactionPasses
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.88f)
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(
    isBackendConnected: Boolean,
    backendIsRecovering: Boolean,
    backendHasChecked: Boolean,
    backendOfflineMessage: String,
    backendReconnectingMessage: String,
    agentConnectionStatus: AgentService.Companion.ConnectionStatus,
    retryMessage: String?,
    onRetry: () -> Unit
) {
    // Don't show bar until we've actually checked AND confirmed a problem
    val agentHasIssue = agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED ||
        agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING ||
        agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING
    val backendHasIssue = backendIsRecovering || (backendHasChecked && !isBackendConnected)

    if (backendHasIssue || agentHasIssue) {
        val message = when {
            backendIsRecovering -> backendReconnectingMessage
            backendHasIssue -> backendOfflineMessage
            agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING -> retryMessage ?: stringResource(R.string.agent_reconnecting)
            agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING -> stringResource(R.string.agent_connecting)
            agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED ->
                retryMessage ?: stringResource(R.string.agent_disconnected)
            else -> retryMessage ?: stringResource(R.string.agent_disconnected)
        }

        Surface(
            color = if (backendIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (backendIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = message,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (backendIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Show retry button only if disconnected and NOT retrying or if ollama is down
                if (!backendIsRecovering && (agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED || !isBackendConnected)) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            stringResource(R.string.action_retry),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SshConnectionWarningCard(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compactTitle: String? = null
) {
    if (compactTitle != null) {
        var showDetails by rememberSaveable { mutableStateOf(false) }
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(compactTitle, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { showDetails = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Info, stringResource(R.string.soft_studio_view_details))
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, stringResource(R.string.action_settings))
                    }
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.action_retry))
                }
            }
        }
        if (showDetails) {
            AppTextDetailsDialog(title = title, text = message, onDismiss = { showDetails = false })
        }
        return
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.action_settings))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
fun DebugPanel(
    events: List<AgentProjectEventEntity>,
    onClear: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val debugJournalExportLabel = stringResource(R.string.agent_debug_journal_export)
    val clipboardManager = LocalClipboardManager.current
    var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
    val filters = listOf(
        "ALL" to stringResource(R.string.agent_journal_filter_all),
        "LLM" to stringResource(R.string.agent_journal_filter_llm),
        "TOOLS" to stringResource(R.string.agent_journal_filter_tools),
        "CONNECTION" to stringResource(R.string.agent_journal_filter_connection),
        "UI" to stringResource(R.string.agent_journal_filter_ui),
        "ERRORS" to stringResource(R.string.agent_journal_filter_errors)
    )
    val filteredEvents = remember(events, selectedFilter) {
        when (selectedFilter) {
            "ALL" -> events
            "ERRORS" -> events.filter { it.category == "ERROR" || it.status == "ERROR" }
            else -> events.filter { it.category == selectedFilter }
        }
    }
    val exportText = remember(filteredEvents) {
        buildString {
            appendLine("AI Agent project debug journal")
            appendLine("Content redaction: message text, prompts, tool output, file contents, and private arguments are not stored.")
            appendLine("Events exported: ${filteredEvents.size}")
            filteredEvents.asReversed().forEach { event ->
                appendLine(formatAgentProjectEvent(event))
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_debug_journal_title), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        stringResource(R.string.agent_debug_journal_retention, 10000),
                        fontSize = 9.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    TextButton(onClick = { clipboardManager.setText(AnnotatedString(exportText)) }) {
                        Text(stringResource(R.string.action_copy), fontSize = 10.sp)
                    }
                    TextButton(onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, exportText)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, debugJournalExportLabel))
                    }) {
                        Text(stringResource(R.string.action_share), fontSize = 10.sp)
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.action_clear), fontSize = 10.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filters.forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = { Text(label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
            if (filteredEvents.isEmpty()) {
                Text(stringResource(R.string.agent_no_journal_events), fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                Box(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredEvents.forEach { event ->
                            Text(
                                formatAgentProjectEvent(event),
                                fontSize = 10.sp,
                                color = if (event.category == "ERROR" || event.status == "ERROR") Color(0xFFFF8A80) else Color(0xFF8BE28B),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatAgentProjectEvent(event: AgentProjectEventEntity): String {
    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))
    val counts = buildList {
        event.contentChars?.let { add("contentChars=$it") }
        event.contentLines?.let { add("contentLines=$it") }
        event.toolOutputChars?.let { add("toolOutputChars=$it") }
        event.toolOutputLines?.let { add("toolOutputLines=$it") }
        event.contextPercent?.let { add("ctx=$it%") }
        event.activeJobCount?.let { add("jobs=$it") }
    }.joinToString(" ")
    return buildString {
        append("[$timestamp] ")
        append(event.category)
        append(" ")
        append(event.eventType)
        event.toolName?.takeIf { it.isNotBlank() }?.let { append(" tool=").append(it) }
        event.toolCallId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it.take(12)) }
        event.status?.takeIf { it.isNotBlank() }?.let { append(" status=").append(it) }
        event.phase?.takeIf { it.isNotBlank() }?.let { append(" phase=").append(it) }
        if (counts.isNotBlank()) append(" ").append(counts)
        event.errorClass?.takeIf { it.isNotBlank() }?.let { append(" error=").append(it) }
        event.errorMessage?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        event.summary.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
    }
}

@Composable
fun AgentActivityBanner(
    statusText: String,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isVisible || statusText.isBlank()) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AgentContextWindowBanner(
    snapshot: PromptContextSnapshot?,
    modifier: Modifier = Modifier
) {
    if (snapshot == null || snapshot.agentRole != "ORCHESTRATOR") return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val detailScrollState = rememberScrollState()
    val timeFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    val displayedPromptTokens = snapshot.actualPromptTokens ?: snapshot.calibratedRequestTokens ?: snapshot.packedEstimatedTokens
    val displayedPercentUsed = snapshot.actualPercentUsed ?: snapshot.percentUsed
    val progress = (displayedPromptTokens.toFloat() / snapshot.contextSize.toFloat()).coerceIn(0f, 1f)
    val containerColor = when {
        displayedPercentUsed >= snapshot.thresholdPercent -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
        displayedPercentUsed >= 75 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    }
    val contentColor = when {
        displayedPercentUsed >= snapshot.thresholdPercent -> MaterialTheme.colorScheme.onErrorContainer
        displayedPercentUsed >= 75 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detailText = when {
        snapshot.isUsingHardCompactedBasis ->
            stringResource(
                R.string.agent_context_usage_hard_compacted_detail,
                snapshot.rawEstimatedTokens,
                snapshot.packedEstimatedTokens,
                snapshot.thresholdPercent
            )
        snapshot.didCompactHistory || snapshot.omittedCount > 0 ->
            stringResource(
                R.string.agent_context_usage_compacted_detail,
                snapshot.rawEstimatedTokens,
                snapshot.packedEstimatedTokens,
                snapshot.omittedCount
            )
        else ->
            stringResource(
                R.string.agent_context_usage_normalized_detail,
                snapshot.rawEstimatedTokens,
                snapshot.thresholdPercent
            )
    }

    Surface(
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.agent_context_usage_label,
                        displayedPercentUsed,
                        displayedPromptTokens,
                        snapshot.contextSize
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.agent_context_usage_hide_details else R.string.agent_context_usage_show_details
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (expanded) R.string.agent_context_usage_hide_details else R.string.agent_context_usage_show_details
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!expanded) {
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.22f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .verticalScroll(detailScrollState)
            ) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.88f)
                )
                snapshot.actualPromptTokens?.let { actualPromptTokens ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.agent_context_usage_actual_detail,
                            actualPromptTokens,
                            snapshot.actualCompletionTokens?.toString() ?: "?",
                            snapshot.actualTotalTokens?.toString() ?: "?"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.88f)
                    )
                }
                if (
                    snapshot.maximumInputTokens != null &&
                    snapshot.safetyReserveTokens != null &&
                    snapshot.effectiveOutputTokens != null
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.agent_context_budget_breakdown,
                            snapshot.packedEstimatedTokens,
                            snapshot.rawToolSchemaTokens,
                            snapshot.actualPromptTokens
                                ?: snapshot.calibratedRequestTokens
                                ?: snapshot.packedEstimatedTokens,
                            snapshot.maximumInputTokens,
                            snapshot.effectiveOutputTokens,
                            snapshot.safetyReserveTokens,
                            snapshot.countSource ?: "estimate"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.88f)
                    )
                }
                if (snapshot.backend != null && snapshot.model != null && snapshot.calibrationFactor != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.agent_context_usage_calibration_detail,
                            snapshot.backend,
                            snapshot.model,
                            String.format(Locale.US, "%.2f", snapshot.calibrationFactor)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.88f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.agent_context_usage_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (snapshot.recentCompactions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_context_usage_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.82f)
                    )
                } else {
                    snapshot.recentCompactions.forEach { event ->
                        Text(
                            text = stringResource(
                                R.string.agent_context_usage_history_item_verbose,
                                timeFormatter.format(Date(event.timestamp)),
                                event.rawEstimatedTokens,
                                event.packedEstimatedTokens,
                                event.omittedCount,
                                event.compactionPasses
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.88f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    canSend: Boolean,
    canAttachImage: Boolean = false,
    hasImageAttachment: Boolean = false,
    keyboardPadding: Dp = 0.dp,
    onAttachImage: (() -> Unit)? = null,
    walkthroughTargetId: String? = null,
    walkthroughEventId: String? = null
) {
    val walkthroughTargets = LocalWalkthroughTargets.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = keyboardPadding)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onAttachImage != null) {
                FilledIconButton(
                    onClick = onAttachImage,
                    modifier = Modifier.size(48.dp),
                    enabled = canAttachImage && !isLoading,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (hasImageAttachment) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (hasImageAttachment) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Image, stringResource(R.string.agent_attach_image), modifier = Modifier.size(20.dp))
                }

                Spacer(modifier = Modifier.width(10.dp))
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                modifier = Modifier
                    .weight(1f)
                    .then(walkthroughTargetId?.let { Modifier.walkthroughTarget(it) } ?: Modifier),
                placeholder = {
                    Text(
                        if (canSend) stringResource(R.string.agent_type_msg) else stringResource(R.string.agent_thinking),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                enabled = canSend || isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.width(12.dp))

            if (isLoading) {
                FilledIconButton(
                    onClick = {
                        onSend()
                        walkthroughEventId?.let { eventId -> walkthroughTargets?.recordEvent(eventId) }
                    },
                    modifier = Modifier.size(48.dp),
                    enabled = inputText.isNotBlank() && canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.agent_send_guidance), modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Stop, stringResource(R.string.action_stop), modifier = Modifier.size(24.dp))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        onSend()
                        walkthroughEventId?.let { eventId -> walkthroughTargets?.recordEvent(eventId) }
                    },
                    modifier = Modifier.size(48.dp),
                    enabled = canSend && inputText.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun AgentImageAttachmentChip(
    imagePath: String,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = stringResource(R.string.agent_image_attachment_title),
                modifier = Modifier
                    .size(52.dp)
                    .clickable(onClick = onPreview),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_image_attachment_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = File(imagePath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.llama_remove_attachment))
            }
        }
    }
}
