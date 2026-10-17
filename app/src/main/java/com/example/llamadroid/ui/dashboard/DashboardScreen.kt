package com.example.llamadroid.ui.dashboard

import com.example.llamadroid.ui.navigation.navigateToRoot
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.service.GenerationQueueRepository
import com.example.llamadroid.ui.ai.ChatServerPickerDialog
import com.example.llamadroid.ui.ai.AIToolDefinition
import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.ai.ToolDiscovery
import com.example.llamadroid.ui.ai.ToolUsageStore
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import com.example.llamadroid.ui.ai.llama.EasyLlamaChatEntryCard
import com.example.llamadroid.ui.ai.llama.rememberRunningLlamaChatServers
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.util.SystemMonitor

private const val CHATS_ROUTE = "chats"
private const val MAX_CONTINUE_ITEMS = 5
private const val MAX_NEW_ITEMS = 3
private const val MILLIS_PER_DAY = 86_400_000L
private const val GLOBAL_SEARCH_ROUTE = "global_search"

/** A Start-row shortcut: pinned tools first, then unpinned defaults. */
private data class HomeStartAction(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val sprite: AppSpriteKey? = null,
    val onClick: () -> Unit
)

/** One entry of the merged Continue list, ordered by [timestamp] (newest first). */
private data class HomeContinueItem(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val supporting: String?,
    val timestamp: Long,
    val onClick: () -> Unit
)

/**
 * Soft Studio Home: Easy chat, a Start row of shortcuts, a merged Continue list and one compact
 * Status row. Infrastructure (file server, Kiwix, distributed) lives in Tools.
 */
@Composable
fun DashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val systemMonitor = remember(context) { SystemMonitor(context) }
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(systemMonitor)
    )
    val queueRepository = remember(context) { GenerationQueueRepository(context) }

    val pinnedChats by database.llamaChatDao().getPinnedAiHubChats()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val allChats: List<LlamaChatEntity>? by database.llamaChatDao().getAllChats()
        .collectAsStateWithLifecycle(initialValue = null)
    val servers by database.llamaServerDao().getAllServers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by database.agentChatDao().getAllConversations()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingJobs by queueRepository.pendingCountFlow
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

    // Start row: pinned tools first, then defaults that are not already pinned.
    val pinnedTools = ToolCatalog.tools.filter { it.id in pinnedToolIds }
    val startActions = buildList {
        pinnedTools.forEach { tool ->
            add(HomeStartAction("tool:${tool.id}", tool.icon, stringResource(tool.titleRes), tool.sprite) {
                if (tool.id == "chat") {
                    when (runningChatServers.size) {
                        0 -> navController.navigate(Screen.Chats.route) { launchSingleTop = true }
                        1 -> navController.navigate("chat?port=${runningChatServers.single().port}")
                        else -> showChatServerPicker = true
                    }
                } else navController.navigate(tool.route)
            })
        }
        fun addDefault(toolId: String?, route: String, icon: ImageVector, label: String, sprite: AppSpriteKey? = null) {
            if (toolId != null && toolId in pinnedToolIds) return
            add(HomeStartAction("default:$route", icon, label, sprite) {
                if (route == CHATS_ROUTE) navController.navigateToRoot(route) else navController.navigate(route)
            })
        }
        addDefault(null, CHATS_ROUTE, Icons.AutoMirrored.Filled.Chat, stringResource(R.string.soft_studio_home_new_chat), AppSpriteKey.CHATS)
        addDefault("agent", Screen.Agent.route, Icons.Default.AccountTree, stringResource(R.string.ui_home_agent_short), AppSpriteKey.AGENT_ROBOT)
        addDefault("image_generation", Screen.ImageGen.route, Icons.Default.Image, stringResource(R.string.ai_image_gen), AppSpriteKey.MODEL_IMAGE)
        addDefault("organizer", Screen.NotesManager.route, Icons.Default.CalendarMonth, stringResource(R.string.soft_studio_home_organizer), AppSpriteKey.ORGANIZER)
    }

    // Continue: pinned native chats and Agent projects merged by recency.
    val untitledChat = stringResource(R.string.ai_pinned_chat_label)
    val missingServer = stringResource(R.string.ai_pinned_chat_server_missing)
    val continueItems = buildList {
        pinnedChats.forEach { chat ->
            val server = chat.pinnedServerId?.let { id -> servers.firstOrNull { it.id == id } }
            add(HomeContinueItem(
                key = "chat:${chat.id}",
                icon = Icons.AutoMirrored.Filled.Chat,
                title = chat.title.ifBlank { untitledChat },
                supporting = server?.name ?: missingServer,
                timestamp = maxOf(chat.pinnedAt ?: 0L, chat.lastModified)
            ) {
                val serverId = chat.pinnedServerId
                if (serverId == null || servers.none { it.id == serverId }) {
                    navController.navigate(Screen.Chats.route) { launchSingleTop = true }
                } else {
                    navController.navigate(Screen.LlamaChat.createRoute(chat.id, serverId))
                }
            })
        }
        projects.forEach { project ->
            add(HomeContinueItem(
                key = "project:${project.id}",
                icon = Icons.Default.AccountTree,
                title = project.title.ifBlank { project.projectFolder },
                supporting = project.projectFolder,
                timestamp = project.updatedAt
            ) { navController.navigate(Screen.Agent.createRoute(project.id)) })
        }
    }.sortedByDescending { it.timestamp }.take(MAX_CONTINUE_ITEMS)

    // Discovery: grid tools never opened (rotating daily) and entries added in this version.
    val openedToolIds = remember { ToolUsageStore.lastOpened(context).keys }
    val daySeed = remember { System.currentTimeMillis() / MILLIS_PER_DAY }
    val notTriedTools = remember(pinnedToolIds, openedToolIds, daySeed) {
        ToolDiscovery.notTried(ToolCatalog.gridTools, openedToolIds, daySeed, excludeIds = pinnedToolIds)
    }
    val newTools = remember { ToolCatalog.addedIn(BuildConfig.VERSION_NAME).take(MAX_NEW_ITEMS) }
    fun openTool(tool: AIToolDefinition) {
        if (tool.route == CHATS_ROUTE) navController.navigateToRoot(tool.route) else navController.navigate(tool.route)
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
                title = stringResource(R.string.soft_studio_home_title),
                modifier = Modifier.walkthroughTarget("home.summary")
            )

            DashboardSearchField(onClick = { navController.navigate(GLOBAL_SEARCH_ROUTE) })

            if (allChats?.isEmpty() == true) {
                EasyLlamaChatEntryCard(
                    showQuickSetup = true,
                    onChatOpened = { chatId, serverId ->
                        navController.navigate(Screen.LlamaChat.createRoute(chatId, serverId))
                    },
                    onManageModels = {
                        navController.navigate(Screen.LLMModels.route)
                    }
                )
            }

            DashboardContinueCard(
                items = continueItems,
                pendingJobs = pendingJobs,
                onQueue = { navController.navigate(Screen.GenerationQueue.route) },
                onSeeAll = { navController.navigateToRoot(CHATS_ROUTE) }
            )

            DashboardStartRow(startActions)

            if (notTriedTools.isNotEmpty()) {
                DashboardToolSuggestions(
                    title = stringResource(R.string.home_not_tried_title),
                    tools = notTriedTools,
                    actionLabel = stringResource(R.string.home_not_tried_action),
                    tag = "home_not_tried",
                    onOpen = ::openTool
                )
            }

            if (newTools.isNotEmpty()) {
                DashboardToolSuggestions(
                    title = stringResource(R.string.home_new_in_version_title),
                    tools = newTools,
                    actionLabel = stringResource(R.string.home_not_tried_action),
                    tag = "home_new_in_version",
                    onOpen = ::openTool
                )
            }

            DashboardStatusRow(
                viewModel = viewModel,
                runningServers = runningChatServers.size,
                onMemory = { navController.navigate(Screen.Stats.route) },
                onServers = { navController.navigate(Screen.Chats.route) { launchSingleTop = true } }
            )
        }
    }
}

/** Tap target that looks like a search field and opens the global search page. */
@Composable
private fun DashboardSearchField(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .testTag("home_search"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.global_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A short list of catalog tools (never opened, or new in this version) with a Try action. */
@Composable
private fun DashboardToolSuggestions(
    title: String,
    tools: List<AIToolDefinition>,
    actionLabel: String,
    tag: String,
    onOpen: (AIToolDefinition) -> Unit
) {
    AppSectionCard(modifier = Modifier.testTag(tag)) {
        AppSectionTitle(title = title)
        tools.forEach { tool ->
            AppHubRow(
                title = stringResource(tool.titleRes),
                subtitle = stringResource(tool.descriptionRes),
                icon = tool.icon,
                onClick = { onOpen(tool) },
                trailing = {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardStartRow(actions: List<HomeStartAction>) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.ui_home_start_title))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEach { action ->
                Surface(
                    modifier = Modifier.clickable(onClick = action.onClick),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppIcon(action.icon, null, modifier = Modifier.size(if (LocalAppVisualStyle.current.isPixel) 24.dp else 18.dp), tint = MaterialTheme.colorScheme.primary, sprite = action.sprite)
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardContinueCard(
    items: List<HomeContinueItem>,
    pendingJobs: Int,
    onQueue: () -> Unit,
    onSeeAll: () -> Unit
) {
    AppSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AppSectionTitle(title = stringResource(R.string.ui_home_continue_title))
            }
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.ui_home_see_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_home_continue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items.forEach { item ->
            AppHubRow(title = item.title, subtitle = item.supporting, icon = item.icon, onClick = item.onClick)
        }
        if (pendingJobs > 0) {
            AppHubRow(
                title = pluralStringResource(R.plurals.ui_home_queue_pending, pendingJobs, pendingJobs),
                icon = Icons.Default.Queue,
                onClick = onQueue
            )
        }
    }
}

@Composable
private fun DashboardStatusRow(
    viewModel: DashboardViewModel,
    runningServers: Int,
    onMemory: () -> Unit,
    onServers: () -> Unit
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.ui_home_status_title))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppChromeDefaults.InnerCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onMemory)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = if (stats.totalRamGb > 0f) stringResource(
                            R.string.ui_home_status_memory,
                            numberFormat.format((stats.totalRamGb - stats.freeRamGb).coerceAtLeast(0f)),
                            numberFormat.format(stats.totalRamGb)
                        ) else stringResource(R.string.dashboard_memory),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onServers)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.ui_home_status_servers, runningServers, runningServers),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

