package com.example.llamadroid.ui.search

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.ui.ai.AIToolDefinition
import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.navigation.navigateToRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PREFS = "global_search"
private const val RECENT_KEY = "recent"
private const val COLLAPSED_COUNT = 5
private const val MAX_PER_GROUP = 50
private const val DEBOUNCE_MS = 200L
private const val NOTE_SEARCH_CHARS = 2000
private val ROOT_ROUTES = setOf(Screen.Chats.route, Screen.NotesManager.route, Screen.Dashboard.route)

private enum class SearchGroup(val titleRes: Int) {
    TOOLS(R.string.studio_nav_tools),
    SETTINGS(R.string.settings_title),
    CHATS(R.string.global_search_group_chats),
    PROJECTS(R.string.global_search_group_projects),
    MODELS(R.string.global_search_group_models),
    NOTES(R.string.global_search_group_notes)
}

private data class SearchHit(
    val key: String,
    val title: String,
    val subtitle: String?,
    val icon: ImageVector,
    val route: String
)

/** Searchable fields of one catalog entry, in English and Spanish whatever the app language. */
private data class CatalogEntry(val tool: AIToolDefinition, val fields: SearchFields)

internal fun resourcesFor(context: Context, locale: Locale) =
    context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources

private fun modelRoute(type: ModelType): String = when {
    type == ModelType.WHISPER -> Screen.WhisperModels.route
    type.name.startsWith("ONNX_") -> Screen.OnnxModels.route
    type.name.startsWith("SD_") -> Screen.SDModels.route
    type == ModelType.LLM || type == ModelType.LORA || type == ModelType.EMBEDDING || type == ModelType.VISION ||
        type == ModelType.VISION_PROJECTOR || type == ModelType.MMPROJ -> Screen.LLMModels.route
    else -> Screen.ModelHub.route
}

private fun loadRecent(context: Context): List<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT_KEY, "").orEmpty()
        .split('\n').filter { it.isNotBlank() }

/** Full-screen global search over tools, settings, chats, Agent projects, models and notes. */
@Composable
fun GlobalSearchScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = remember { AppDatabase.getDatabase(context) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf(loadRecent(context)) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var results by remember { mutableStateOf(emptyMap<SearchGroup, List<SearchHit>>()) }
    val untitledChat = stringResource(R.string.ai_pinned_chat_label)

    val catalogEntries = remember(context) {
        val en = resourcesFor(context, Locale.ENGLISH)
        val es = resourcesFor(context, Locale.forLanguageTag("es"))
        (ToolCatalog.searchableTools + ToolCatalog.settingsPages).map { tool ->
            CatalogEntry(
                tool,
                SearchFields(
                    titles = listOf(en.getString(tool.titleRes), es.getString(tool.titleRes)),
                    keywords = tool.keywords + tool.keywordsEs + en.getString(tool.category.titleRes) + es.getString(tool.category.titleRes),
                    text = listOf(en.getString(tool.descriptionRes), es.getString(tool.descriptionRes))
                )
            )
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyMap()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        val found = withContext(Dispatchers.Default) {
            val groups = linkedMapOf<SearchGroup, List<SearchHit>>()
            val ranked = GlobalSearchMatcher.rank(query, catalogEntries) { it.fields }
            fun toHit(entry: CatalogEntry) = SearchHit(
                key = entry.tool.id,
                title = resources.getString(entry.tool.titleRes),
                subtitle = resources.getString(entry.tool.descriptionRes),
                icon = entry.tool.icon,
                route = entry.tool.route
            )
            groups[SearchGroup.TOOLS] = ranked.filter { !it.tool.isSettingsPage }.take(MAX_PER_GROUP).map(::toHit)
            groups[SearchGroup.SETTINGS] = ranked.filter { it.tool.isSettingsPage }.take(MAX_PER_GROUP).map(::toHit)
            groups[SearchGroup.CHATS] = GlobalSearchMatcher
                .rank(query, database.llamaChatDao().getAllChats().first()) { SearchFields(listOf(it.title)) }
                .take(MAX_PER_GROUP).map { chat ->
                    val serverId = chat.serverId ?: chat.pinnedServerId
                    SearchHit(
                        key = "chat:${chat.id}",
                        title = chat.title.ifBlank { untitledChat },
                        subtitle = null,
                        icon = Icons.AutoMirrored.Filled.Chat,
                        route = if (serverId != null) Screen.LlamaChat.createRoute(chat.id, serverId) else Screen.Chats.route
                    )
                }
            groups[SearchGroup.PROJECTS] = GlobalSearchMatcher
                .rank(query, database.agentChatDao().getAllConversations().first()) { SearchFields(listOf(it.title, it.projectFolder)) }
                .take(MAX_PER_GROUP).map { project ->
                    SearchHit(
                        key = "project:${project.id}",
                        title = project.title.ifBlank { project.projectFolder },
                        subtitle = project.projectFolder,
                        icon = Icons.Default.AccountTree,
                        route = Screen.Agent.createRoute(project.id)
                    )
                }
            groups[SearchGroup.MODELS] = GlobalSearchMatcher
                .rank(query, database.modelDao().getAllModels().first().filter { it.isDownloaded }) { SearchFields(listOf(it.filename), listOf(it.repoId)) }
                .take(MAX_PER_GROUP).map { model ->
                    SearchHit(
                        key = "model:${model.filename}",
                        title = model.filename,
                        subtitle = model.repoId.takeIf { it.isNotBlank() },
                        icon = Icons.Default.Storage,
                        route = modelRoute(model.type)
                    )
                }
            groups[SearchGroup.NOTES] = GlobalSearchMatcher
                .rank(query, database.noteDao().getAllNotesOnce()) { SearchFields(listOf(it.title), text = listOf(it.content.take(NOTE_SEARCH_CHARS))) }
                .take(MAX_PER_GROUP).map { note ->
                    SearchHit(
                        key = "note:${note.id}",
                        title = note.title.ifBlank { note.content.take(40) },
                        subtitle = note.content.take(90).replace('\n', ' ').takeIf { it.isNotBlank() },
                        icon = Icons.Default.EditNote,
                        route = Screen.NotesManager.route
                    )
                }
            groups.filterValues { it.isNotEmpty() }
        }
        results = found
    }

    fun open(hit: SearchHit) {
        recent = GlobalSearchMatcher.addRecent(recent, query)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(RECENT_KEY, recent.joinToString("\n")) }
        focusManager.clearFocus()
        if (hit.route in ROOT_ROUTES) navController.navigateToRoot(hit.route) else navController.navigate(hit.route)
    }

    AppScreenScaffold(
        title = stringResource(R.string.studio_nav_search),
        onBack = { navController.popBackStack() }
    ) { padding ->
        AppPageBackground {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = AppChromeDefaults.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("global_search_field"),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.global_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (query.isBlank()) {
                        if (recent.isEmpty()) {
                            item { HintText(stringResource(R.string.global_search_prompt)) }
                        } else {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.global_search_recent_title),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = {
                                        recent = emptyList()
                                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(RECENT_KEY) }
                                    }) {
                                        Text(stringResource(R.string.global_search_clear_recent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            items(recent.size, key = { "recent-${recent[it]}" }) { index ->
                                ResultRow(Icons.Default.History, recent[index], null) { query = recent[index] }
                            }
                        }
                    } else if (results.isEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.global_search_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                HintText(stringResource(R.string.global_search_empty_desc))
                            }
                        }
                    } else {
                        results.forEach { (group, hits) ->
                            val isExpanded = group.name in expanded
                            item(key = "header-${group.name}") {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(
                                        stringResource(group.titleRes),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (hits.size > COLLAPSED_COUNT) {
                                        TextButton(onClick = {
                                            expanded = if (isExpanded) expanded - group.name else expanded + group.name
                                        }) {
                                            Text(
                                                stringResource(if (isExpanded) R.string.global_search_show_fewer else R.string.global_search_show_all),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            val shown = if (isExpanded) hits else hits.take(COLLAPSED_COUNT)
                            items(shown.size, key = { "${group.name}-${shown[it].key}" }) { index ->
                                val hit = shown[index]
                                ResultRow(hit.icon, hit.title, hit.subtitle) { open(hit) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, supporting: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppChromeDefaults.InnerCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        supporting,
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
