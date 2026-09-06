package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.settings.ImageGenSettingsContent
import com.example.llamadroid.ui.settings.PDFSettingsContent
import com.example.llamadroid.ui.settings.VideoUpscalerSettingsContent
import com.example.llamadroid.ui.ai.llama.RunningLlamaChatServerUi
import com.example.llamadroid.ui.ai.llama.rememberRunningLlamaChatServers
import com.example.llamadroid.ui.settings.WhisperSettingsContent
import kotlinx.coroutines.launch

private const val PINNED_TOOLS_PREFS = "ai_tool_pins"
private const val PINNED_TOOLS_KEY = "pinned_tool_ids"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIHubScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val database = remember { AppDatabase.getDatabase(context) }
    val pinnedChats by remember(database) {
        database.llamaChatDao().getPinnedAiHubChats()
    }.collectAsState(initial = emptyList())
    val servers by remember(database) {
        database.llamaServerDao().getAllServers()
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val pinPrefs = remember {
        context.getSharedPreferences(PINNED_TOOLS_PREFS, android.content.Context.MODE_PRIVATE)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var pinnedToolIds by remember {
        mutableStateOf(pinPrefs.getStringSet(PINNED_TOOLS_KEY, emptySet()).orEmpty())
    }
    var selectedSettingsTool by remember { mutableStateOf<AIToolDefinition?>(null) }
    var showChatServerPicker by rememberSaveable { mutableStateOf(false) }
    val runningChatServers = rememberRunningLlamaChatServers()

    val categoryTitles = ToolCategory.entries.associateWith { stringResource(it.titleRes) }
    val toolUiItems = ToolCatalog.tools.map { tool ->
        ToolUiItem(
            definition = tool,
            title = stringResource(tool.titleRes),
            description = stringResource(tool.descriptionRes),
            categoryTitle = categoryTitles.getValue(tool.category)
        )
    }
    val filteredTools = remember(query, toolUiItems) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            toolUiItems
        } else {
            toolUiItems.filter { item ->
                item.searchText.contains(normalizedQuery)
            }
        }
    }
    val groupedTools = ToolCategory.entries.mapNotNull { category ->
        val tools = filteredTools.filter {
            it.definition.category == category && it.definition.id !in pinnedToolIds
        }
        if (tools.isEmpty()) null else category to tools
    }
    val pinnedTools = filteredTools.filter { it.definition.id in pinnedToolIds }
    val pinnedChatItems = remember(query, pinnedChats, servers) {
        val serversById = servers.associateBy { it.id }
        val normalizedQuery = query.trim().lowercase()
        pinnedChats.map { chat ->
            val server = chat.pinnedServerId?.let(serversById::get)
            PinnedChatUiItem(chat, server)
        }.filter { item ->
            normalizedQuery.isBlank() || item.searchText.contains(normalizedQuery)
        }
    }

    fun setToolPinned(toolId: String, pinned: Boolean) {
        val next = if (pinned) {
            pinnedToolIds + toolId
        } else {
            pinnedToolIds - toolId
        }
        pinnedToolIds = next
        pinPrefs.edit().putStringSet(PINNED_TOOLS_KEY, next.toSet()).apply()
    }

    fun openChat(server: RunningLlamaChatServerUi) {
        navController.navigate("${Screen.Chat.route}?port=${server.port}")
    }

    fun openChatOrChoose() {
        when (runningChatServers.size) {
            0 -> navController.navigate(Screen.LlamaServers.route)
            1 -> openChat(runningChatServers.single())
            else -> showChatServerPicker = true
        }
    }

    fun openTool(item: ToolUiItem) {
        if (item.definition.id == "chat") {
            openChatOrChoose()
        } else {
            navController.navigate(item.definition.route)
        }
    }

    selectedSettingsTool?.let { tool ->
        ModalBottomSheet(
            onDismissRequest = { selectedSettingsTool = null },
            sheetState = sheetState
        ) {
            ToolSettingsSheetContent(
                tool = tool,
                settingsRepo = settingsRepo,
                onNavigate = { route ->
                    selectedSettingsTool = null
                    navController.navigate(route)
                }
            )
        }
    }

    if (showChatServerPicker) {
        ChatServerPickerDialog(
            servers = runningChatServers,
            onDismiss = { showChatServerPicker = false },
            onServerSelected = { server ->
                showChatServerPicker = false
                openChat(server)
            }
        )
    }

    val tourTargets = LocalWalkthroughTargets.current
    val toolListState = rememberLazyListState()
    val tourKeys = buildList {
        add("tools.search")
        if (pinnedChatItems.isEmpty() && pinnedTools.isEmpty() && groupedTools.isEmpty()) add("empty")
        if (pinnedChatItems.isNotEmpty()) { add("pinned.chat.header"); pinnedChatItems.forEach { add("chat.${it.chat.id}") } }
        if (pinnedTools.isNotEmpty()) { add("pinned.tool.header"); pinnedTools.forEach { add("tool.${it.definition.id}") } }
        groupedTools.forEach { (category, tools) -> add("category.$category"); tools.forEach { add("tool.${it.definition.id}") } }
    }
    LaunchedEffect(tourTargets?.requestedId, tourTargets?.retryKey, tourKeys) {
        val index = tourKeys.indexOf(tourTargets?.requestedId)
        if (index >= 0 && toolListState.layoutInfo.visibleItemsInfo.none { it.index == index }) toolListState.scrollToItem(index)
    }
    AppPageBackground {
        LazyColumn(
            state = toolListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppPageHeader(
                    title = stringResource(R.string.studio_nav_tools),
                    subtitle = stringResource(R.string.ai_hub_subtitle)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("studio_tool_search").walkthroughTarget("tools.search"),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_clear)
                                )
                            }
                        }
                    },
                    label = {
                        Text(stringResource(R.string.ai_tools_search_label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                )
                            }
            }
                if (pinnedChatItems.isEmpty() && pinnedTools.isEmpty() && groupedTools.isEmpty()) {
                    item {
                        AppSectionCard {
                            Text(
                                text = stringResource(R.string.soft_studio_tools_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.soft_studio_tools_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (pinnedChatItems.isNotEmpty()) {
                    item {
                        ToolCategoryHeader(
                            title = stringResource(R.string.ai_tools_category_pinned_chats),
                            count = pinnedChatItems.size
                        )
                    }
                    items(pinnedChatItems, key = { "pinned-chat-${it.chat.id}" }) { item ->
                        PinnedChatHubCard(
                            chat = item.chat,
                            server = item.server,
                            onOpen = {
                                val serverId = item.chat.pinnedServerId ?: return@PinnedChatHubCard
                                navController.navigate(Screen.LlamaChat.createRoute(item.chat.id, serverId))
                            },
                            onUnpin = {
                                scope.launch {
                                    database.llamaChatDao().updateAiHubPin(item.chat.id, false, null, null)
                                }
                            }
                        )
                    }
                }

                if (pinnedTools.isNotEmpty()) {
                    item {
                        ToolCategoryHeader(
                            title = stringResource(R.string.soft_studio_tools_pinned),
                            count = pinnedTools.size
                        )
                    }
                    items(pinnedTools, key = { "pinned-${it.definition.id}" }) { item ->
                        ToolHubCard(
                            id = item.definition.id,
                            icon = item.definition.icon,
                            category = item.definition.category,
                            title = item.title,
                            description = item.description,
                            hasSettings = item.definition.settingsAction !is ToolSettingsAction.None,
                            isPinned = true,
                            onOpen = { openTool(item) },
                            onSettings = { selectedSettingsTool = item.definition },
                            onTogglePinned = { setToolPinned(item.definition.id, false) }
                        )
                    }
                }

                groupedTools.forEach { (category, tools) ->
                    item {
                        ToolCategoryHeader(
                            title = categoryTitles.getValue(category),
                            count = tools.size
                        )
                    }
                    items(tools, key = { it.definition.id }) { item ->
                        ToolHubCard(
                            id = item.definition.id,
                            icon = item.definition.icon,
                            category = item.definition.category,
                            title = item.title,
                            description = item.description,
                            hasSettings = item.definition.settingsAction !is ToolSettingsAction.None,
                            isPinned = false,
                            onOpen = { openTool(item) },
                            onSettings = { selectedSettingsTool = item.definition },
                            onTogglePinned = { setToolPinned(item.definition.id, true) }
                        )
                    }
                }
        }
    }
}

@Composable
internal fun ChatServerPickerDialog(
    servers: List<RunningLlamaChatServerUi>,
    onDismiss: () -> Unit,
    onServerSelected: (RunningLlamaChatServerUi) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ai_chat_choose_server),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_chat_servers_many),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                servers.forEach { server ->
                    Button(
                        onClick = { onServerSelected(server) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = server.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.ai_chat_server_port, server.port),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

private data class PinnedChatUiItem(
    val chat: LlamaChatEntity,
    val server: LlamaServerEntity?
) {
    val searchText: String = buildString {
        append(chat.title.lowercase())
        append(' ')
        append(server?.name.orEmpty().lowercase())
        append(' ')
        append(server?.modelName.orEmpty().lowercase())
    }
}

private data class ToolUiItem(
    val definition: AIToolDefinition,
    val title: String,
    val description: String,
    val categoryTitle: String
) {
    val searchText: String = buildString {
        append(title.lowercase())
        append(' ')
        append(description.lowercase())
        append(' ')
        append(categoryTitle.lowercase())
        append(' ')
        append(definition.keywords.joinToString(" ").lowercase())
    }
}

@Composable
private fun ToolCategoryHeader(
    title: String,
    count: Int
) {
    val countDescription = pluralStringResource(R.plurals.soft_studio_tools_category_count, count, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = countDescription },
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ToolHubCard(
    id: String,
    icon: ImageVector,
    category: ToolCategory,
    title: String,
    description: String,
    hasSettings: Boolean,
    isPinned: Boolean,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onTogglePinned: () -> Unit
) {
    val accent = when (category) {
        ToolCategory.CONVERSATION -> MaterialTheme.colorScheme.primary
        ToolCategory.CREATE -> MaterialTheme.colorScheme.tertiary
        ToolCategory.VOICE -> MaterialTheme.colorScheme.secondary
        ToolCategory.DOCS -> MaterialTheme.colorScheme.primary
        ToolCategory.ORGANIZER -> MaterialTheme.colorScheme.tertiary
        ToolCategory.INFRASTRUCTURE -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_tool_$id").walkthroughTarget("tool.$id")
            .clickable(onClick = onOpen),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppChromeDefaults.CardElevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (LocalDensity.current.fontScale < 1.3f) Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(14.dp),
                color = accent.copy(alpha = 0.14f)
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp), tint = accent)
            }
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onTogglePinned,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(
                        if (isPinned) R.string.ai_tools_unpin_cd else R.string.ai_tools_pin_cd,
                        title
                    ),
                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasSettings) {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.ai_tools_open_settings_cd, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun PinnedChatHubCard(
    chat: LlamaChatEntity,
    server: LlamaServerEntity?,
    onOpen: () -> Unit,
    onUnpin: () -> Unit
) {
    val serverAvailable = server != null
    val subtitle = server?.let {
        buildList {
            add(it.name)
            it.modelName?.takeIf { modelName -> modelName.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
    } ?: stringResource(R.string.ai_pinned_chat_server_missing)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = serverAvailable, onClick = onOpen),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (serverAvailable) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AppChromeDefaults.CardElevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onUnpin,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.ai_tools_unpin_cd, chat.title),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_pinned_chat_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (serverAvailable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ToolSettingsSheetContent(
    tool: AIToolDefinition,
    settingsRepo: SettingsRepository,
    onNavigate: (String) -> Unit
) {
    val title = stringResource(tool.titleRes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_tools_tool_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.ai_tools_tool_settings_subtitle, title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (val action = tool.settingsAction) {
            ToolSettingsAction.None -> {
                AppSectionCard(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_tools_no_tool_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.ai_tools_no_tool_settings_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is ToolSettingsAction.Navigate -> {
                AppSectionCard(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_tools_open_related_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.ai_tools_open_related_settings_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { onNavigate(action.route) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_tools_open_related_settings_action))
                    }
                }
            }
            is ToolSettingsAction.Sheet -> {
                when (action.sheet) {
                    ToolSettingsSheet.IMAGE_GENERATION -> ImageGenSettingsContent(
                        settingsRepo = settingsRepo,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    ToolSettingsSheet.WHISPER -> WhisperSettingsContent(
                        settingsRepo = settingsRepo,
                        onOpenModels = { onNavigate(Screen.WhisperModels.route) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    ToolSettingsSheet.VIDEO_UPSCALER -> VideoUpscalerSettingsContent(
                        settingsRepo = settingsRepo,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    ToolSettingsSheet.PDF_SUMMARY -> PDFSettingsContent(
                        settingsRepo = settingsRepo,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
