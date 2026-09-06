package com.example.llamadroid.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.ai.ChatServerPickerDialog
import com.example.llamadroid.ui.ai.llama.rememberRunningLlamaChatServers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.SystemMonitor

/**
 * Soft Studio Home. The page is intentionally a small set of live hand-offs:
 * durable pins, recent project state, runtime status, and links to the owners
 * of detailed model, knowledge, offline, and output workflows.
 */
@Composable
fun DashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val knowledgeRepository = remember { KnowledgeBaseRepository(context, database) }
    val systemMonitor = remember(context) { SystemMonitor(context) }
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(systemMonitor)
    )

    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val pinnedChats by database.llamaChatDao().getPinnedAiHubChats()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val servers by database.llamaServerDao().getAllServers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by database.agentChatDao().getAllConversations()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val knowledgeBases by knowledgeRepository.observeKnowledgeBases()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val knowledgeSources by knowledgeRepository.observeSourceCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val knowledgeChunks by knowledgeRepository.observeChunkCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val organizerEvents by database.organizerDao().getAllEvents()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val noteCount by database.noteDao().getNoteCount()
        .collectAsStateWithLifecycle(initialValue = 0)

    val pinPrefs = remember(context) { context.getSharedPreferences("ai_tool_pins", android.content.Context.MODE_PRIVATE) }
    var pinnedToolIds by remember { mutableStateOf(pinPrefs.getStringSet("pinned_tool_ids", emptySet()).orEmpty().toSet()) }
    DisposableEffect(pinPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "pinned_tool_ids") pinnedToolIds = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
        }
        pinPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pinPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val runningChatServers = rememberRunningLlamaChatServers()
    var showChatServerPicker by rememberSaveable { mutableStateOf(false) }
    if (showChatServerPicker) {
        ChatServerPickerDialog(runningChatServers, onDismiss = { showChatServerPicker = false }, onServerSelected = {
            showChatServerPicker = false
            navController.navigate("chat?port=${it.port}")
        })
    }
    val scrollState = rememberScrollState()
    AppPageBackground {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppPageHeader(
                title = stringResource(R.string.soft_studio_home_title)
            )

            val pinnedTools = ToolCatalog.tools.filter { it.id in pinnedToolIds }
            if (pinnedTools.isNotEmpty()) {
                AppSectionCard {
                    AppSectionTitle(title = stringResource(R.string.soft_studio_tools_pinned))
                    pinnedTools.forEach { tool ->
                        DashboardLinkRow(tool.icon, stringResource(tool.titleRes), stringResource(tool.descriptionRes)) {
                            if (tool.id == "chat") {
                                when (runningChatServers.size) {
                                    0 -> navController.navigate(Screen.LlamaServers.route)
                                    1 -> navController.navigate("chat?port=${runningChatServers.single().port}")
                                    else -> showChatServerPicker = true
                                }
                            } else navController.navigate(tool.route)
                        }
                    }
                }
            }

            if (pinnedTools.isEmpty()) DashboardQuickActions(navController)

            if (pinnedChats.isNotEmpty()) DashboardPinnedChats(
                chats = pinnedChats,
                servers = servers,
                onOpen = { chat ->
                    val serverId = chat.pinnedServerId
                    if (serverId == null || servers.none { it.id == serverId }) {
                        navController.navigate(Screen.LlamaServers.route)
                    } else {
                        navController.navigate(Screen.LlamaChat.createRoute(chat.id, serverId))
                    }
                }
            )

            if (projects.isNotEmpty()) DashboardRecentProjects(
                projects = projects,
                onOpen = { projectId -> navController.navigate(Screen.Agent.createRoute(projectId)) }
            )

            DashboardDeviceStatus(viewModel, onOpen = { navController.navigate(Screen.Stats.route) })
            DashboardServerCard(
                state = serverState,
                onOpen = { navController.navigate(Screen.LlamaServers.route) }
            )

            DashboardDomainState(
                knowledgeBaseCount = knowledgeBases.size,
                sourceCount = knowledgeSources,
                chunkCount = knowledgeChunks,
                organizerEventCount = organizerEvents.size,
                noteCount = noteCount,
                onKnowledge = { navController.navigate(Screen.KnowledgeBase.route) },
                onOrganizer = { navController.navigate(Screen.NotesManager.route) }
            )

            AppAdvancedSection(title = stringResource(R.string.soft_studio_home_infrastructure)) {
                DashboardFileServerCard()
                DashboardInfrastructureLinks(navController)
                DashboardKiwixCard(navController)
            }
        }
    }
}

@Composable
private fun DashboardDeviceStatus(viewModel: DashboardViewModel, onOpen: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.secondary)
            Text(if (stats.totalRamGb > 0f) stringResource(R.string.studio_home_memory_available,
                numberFormat.format(stats.freeRamGb), numberFormat.format(stats.totalRamGb))
                else stringResource(R.string.dashboard_memory),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardServerCard(
    state: ServerState,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val statusText = when (state) {
        ServerState.Stopped -> stringResource(R.string.soft_studio_home_status_stopped)
        ServerState.Starting -> stringResource(R.string.soft_studio_home_status_starting)
        is ServerState.Loading -> stringResource(R.string.soft_studio_home_status_loading)
        is ServerState.Running -> stringResource(R.string.soft_studio_home_status_running, state.port)
        is ServerState.Error -> stringResource(R.string.soft_studio_home_status_error)
    }
    val statusColor = when (state) {
        ServerState.Stopped -> colors.onSurfaceVariant
        ServerState.Starting, is ServerState.Loading -> colors.primary
        is ServerState.Running -> colors.tertiary
        is ServerState.Error -> colors.error
    }

    AppSectionCard(
        modifier = Modifier.clickable(onClick = onOpen),
        containerColor = colors.primaryContainer.copy(alpha = 0.52f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = colors.primary
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.soft_studio_home_server_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DashboardPinnedChats(
    chats: List<LlamaChatEntity>,
    servers: List<LlamaServerEntity>,
    onOpen: (LlamaChatEntity) -> Unit
) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.soft_studio_home_pinned_title))
        if (chats.isEmpty()) {
            Text(
                text = stringResource(R.string.soft_studio_home_pinned_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            chats.take(3).forEach { chat ->
                val server = chat.pinnedServerId?.let { id -> servers.firstOrNull { it.id == id } }
                val chatTitle = if (chat.title.isBlank()) {
                    stringResource(R.string.ai_pinned_chat_label)
                } else {
                    chat.title
                }
                DashboardLinkRow(
                    icon = Icons.Default.Chat,
                    title = chatTitle,
                    supporting = server?.name ?: stringResource(R.string.ai_pinned_chat_server_missing),
                    onClick = { onOpen(chat) }
                )
            }
        }
    }
}

@Composable
private fun DashboardRecentProjects(
    projects: List<com.example.llamadroid.data.db.AgentConversationEntity>,
    onOpen: (Long) -> Unit
) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.soft_studio_home_recent_projects))
        if (projects.isEmpty()) {
            Text(
                text = stringResource(R.string.soft_studio_home_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            projects.take(3).forEach { project ->
                DashboardLinkRow(
                    icon = Icons.Default.AccountTree,
                    title = project.title.ifBlank { project.projectFolder },
                    supporting = project.projectFolder,
                    onClick = { onOpen(project.id) }
                )
            }
        }
    }
}

@Composable
private fun DashboardDomainState(
    knowledgeBaseCount: Int,
    sourceCount: Int,
    chunkCount: Int,
    organizerEventCount: Int,
    noteCount: Int,
    onKnowledge: () -> Unit,
    onOrganizer: () -> Unit
) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.soft_studio_home_knowledge_state))
        DashboardLinkRow(
            icon = Icons.Default.Search,
            title = stringResource(R.string.soft_studio_home_knowledge),
            supporting = stringResource(
                R.string.soft_studio_home_knowledge_state_desc,
                knowledgeBaseCount,
                sourceCount,
                chunkCount
            ),
            onClick = onKnowledge
        )
        DashboardLinkRow(
            icon = Icons.Default.CalendarMonth,
            title = stringResource(R.string.soft_studio_home_organizer_state),
            supporting = stringResource(
                R.string.soft_studio_home_organizer_state_desc,
                organizerEventCount,
                noteCount
            ),
            onClick = onOrganizer
        )
    }
}

@Composable
private fun DashboardQuickActions(navController: NavController) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.soft_studio_home_quick_actions))
        DashboardLinkRow(
            icon = Icons.Default.Chat,
            title = stringResource(R.string.soft_studio_home_new_chat),
            onClick = { navController.navigate(Screen.LlamaChatList.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Image,
            title = stringResource(R.string.ai_image_gen),
            onClick = { navController.navigate(Screen.ImageGen.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.AccountTree,
            title = stringResource(R.string.soft_studio_home_agent),
            onClick = { navController.navigate(Screen.Agent.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.CalendarMonth,
            title = stringResource(R.string.soft_studio_home_organizer),
            supporting = stringResource(R.string.soft_studio_home_notes),
            onClick = { navController.navigate(Screen.NotesManager.route) }
        )
    }
}

@Composable
private fun DashboardLibraryLinks(navController: NavController) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.soft_studio_home_library))
        DashboardLinkRow(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.soft_studio_home_models),
            onClick = { navController.navigate(Screen.ModelHub.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Search,
            title = stringResource(R.string.soft_studio_home_knowledge),
            onClick = { navController.navigate(Screen.KnowledgeBase.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.soft_studio_home_offline),
            onClick = { navController.navigate(Screen.ZimManager.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Image,
            title = stringResource(R.string.soft_studio_home_outputs),
            supporting = stringResource(R.string.soft_studio_library_open_images),
            onClick = { navController.navigate(Screen.ImageGen.createRoute(startMode = 0, tab = "gallery")) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.soft_studio_library_open_videos),
            onClick = { navController.navigate(Screen.VideoGen.createRoute(tab = "gallery")) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Description,
            title = stringResource(R.string.soft_studio_library_open_notes),
            onClick = { navController.navigate(Screen.NotesManager.route) }
        )
    }
}

@Composable
private fun DashboardInfrastructureLinks(navController: NavController) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.soft_studio_home_infrastructure),
            supporting = stringResource(R.string.soft_studio_home_infrastructure_desc)
        )
        DashboardLinkRow(
            icon = Icons.Default.SmartToy,
            title = stringResource(R.string.soft_studio_home_tools),
            onClick = { navController.navigate(Screen.AIHub.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Share,
            title = stringResource(R.string.soft_studio_home_distributed_llm),
            supporting = stringResource(R.string.dashboard_setup_distributed_desc),
            onClick = { navController.navigate(Screen.DistributedHub.route) }
        )
        DashboardLinkRow(
            icon = Icons.Default.Hub,
            title = stringResource(R.string.soft_studio_home_distributed_media),
            supporting = stringResource(R.string.dashboard_sd_distributed_desc),
            onClick = { navController.navigate(Screen.SdDistributedHub.route) }
        )
    }
}

@Composable
private fun DashboardLinkRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppChromeDefaults.InnerCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
