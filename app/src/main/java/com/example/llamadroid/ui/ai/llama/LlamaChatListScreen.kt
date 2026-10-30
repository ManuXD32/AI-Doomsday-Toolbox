package com.example.llamadroid.ui.ai.llama

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.ui.ai.llama.chatlist.ChatListServerSheetState
import com.example.llamadroid.ui.ai.llama.chatlist.ChatListServerSheets
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.ui.ai.llama.chatlist.ChatRowServerChip
import com.example.llamadroid.ui.ai.llama.chatlist.ChatsServerStrip
import com.example.llamadroid.ui.ai.llama.chatlist.NewChatSheet
import com.example.llamadroid.ui.ai.llama.chatlist.WearQuickChatSettingsDialog
import com.example.llamadroid.ui.ai.llama.servers.ServerActionState
import com.example.llamadroid.ui.ai.llama.servers.LocalServerProfileEditorOpener
import com.example.llamadroid.ui.ai.llama.servers.PendingServerChatRequest
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LLAMA_FOLDER_FILTER_ALL = Long.MIN_VALUE
private const val LLAMA_FOLDER_FILTER_UNFILED = 0L
private fun formatLlamaChatListString(template: String, vararg args: Any?): String =
    String.format(Locale.getDefault(), template, *args)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatListScreen(
    navController: NavController,
    initialFolderId: Long? = null
) {
    LlamaChatListContent(navController = navController, folderId = initialFolderId)
}

/**
 * Native chat history body (list, folders, search-free pinned ordering, dialogs). With
 * [embedded] = false it owns its own Scaffold/top bar (legacy routes); with [embedded] = true it
 * renders only the body plus a compact action row so the Chats root can host it under its own
 * header. Incrementing [newChatSignal] opens the new-chat sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatListContent(
    navController: NavController,
    folderId: Long?,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    newChatSignal: Int = 0
) {
    val initialFolderId = folderId
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val importedChatTitleText = stringResource(R.string.llama_imported_chat_title)
    val importedNotesChatTitleText = stringResource(R.string.llama_imported_notes_chat_title)
    val notesImportDefaultTitleFormat = stringResource(R.string.notes_import_default_title)
    val notesImportSourceLabelText = stringResource(R.string.notes_import_source_label)
    val importedNoteChatTitleText = stringResource(R.string.llama_imported_note_chat_title)
    val importErrorUnknownFormatText = stringResource(R.string.llama_import_error_unknown_format)
    val importSuccessText = stringResource(R.string.llama_import_success)
    val importErrorText = stringResource(R.string.llama_import_error)
    val promptProfileSaveErrorText = stringResource(R.string.llama_prompt_profile_save_error)
    val folderCreateErrorText = stringResource(R.string.llama_folder_create_error)
    val database = AppDatabase.getDatabase(context)
    val repository = remember { 
         LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao(),
            database.llamaChatPromptProfileDao()
        ) 
    }
    val serversViewModel: ServerRegistryViewModel = viewModel()
    val openLocalServerEditor = LocalServerProfileEditorOpener.current
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))
    val chats by viewModel.chats.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val promptProfiles by viewModel.promptProfiles.collectAsState()
    val servers by serversViewModel.servers.collectAsState()
    val serverActions by serversViewModel.actions.collectAsState()
    val serverSheets = remember { ChatListServerSheetState() }
    val serversByRowId = remember(servers) { servers.filter { it.rowId != null }.associateBy { it.rowId } }

    var showNewChatSheet by remember { mutableStateOf(false) }
    var showQuickSetupSheet by remember { mutableStateOf(false) }
    var showTitleGenerationSheet by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showManageFoldersDialog by remember { mutableStateOf(false) }
    var showPromptProfilesDialog by remember { mutableStateOf(false) }
    var showWearQuickChatSettings by remember { mutableStateOf(false) }
    var chatToEdit by remember { mutableStateOf<LlamaChatEntity?>(null) }
    var chatToMove by remember { mutableStateOf<LlamaChatEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<LlamaChatFolderEntity?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedFolderFilter by remember(initialFolderId) {
        mutableLongStateOf(initialFolderId?.takeIf { it > 0 } ?: LLAMA_FOLDER_FILTER_ALL)
    }
    val scope = rememberCoroutineScope()
    val filteredChats = remember(chats, selectedFolderFilter) {
        when (selectedFolderFilter) {
            LLAMA_FOLDER_FILTER_ALL -> chats
            LLAMA_FOLDER_FILTER_UNFILED -> chats.filter { it.folderId == null }
            else -> chats.filter { it.folderId == selectedFolderFilter }
        }
    }
    val rootChats = remember(chats) { chats.filter { it.folderId == null } }
    val displayedChats = remember(rootChats, filteredChats, selectedFolderFilter) {
        if (selectedFolderFilter == LLAMA_FOLDER_FILTER_ALL) rootChats else filteredChats
    }
    val folderNamesById = remember(folders) { folders.associate { it.id to it.name } }
    val currentFolder = remember(folders, selectedFolderFilter) {
        folders.firstOrNull { it.id == selectedFolderFilter }
    }

    LaunchedEffect(folders, selectedFolderFilter) {
        if (folders.isNotEmpty() && selectedFolderFilter > 0 && folders.none { it.id == selectedFolderFilter }) {
            selectedFolderFilter = LLAMA_FOLDER_FILTER_ALL
        }
    }
    
    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val (title, systemPrompt, messages) = withContext(Dispatchers.IO) {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val gson = Gson()
                    val obj = JSONObject(json)
                    when {
                        obj.has("messages") -> {
                            val payload = gson.fromJson(json, LlamaChatExportPayload::class.java)
                            Triple(
                                payload.title.ifBlank { importedChatTitleText },
                                payload.systemPrompt?.ifBlank { null },
                                payload.messages
                            )
                        }
                        obj.has("notes") -> {
                            val payload = gson.fromJson(json, NotesExportPayload::class.java)
                            Triple(
                                importedNotesChatTitleText,
                                null,
                                payload.notes.mapIndexed { index, note ->
                                    note.toLlamaSerializedMessage(
                                        fallbackTitle = formatLlamaChatListString(
                                            notesImportDefaultTitleFormat,
                                            index + 1
                                        ),
                                        sourceLabel = notesImportSourceLabelText
                                    )
                                }
                            )
                        }
                        obj.has("content") -> {
                            val payload = gson.fromJson(json, NoteExportPayload::class.java)
                            Triple(
                                payload.title.ifBlank { importedNoteChatTitleText },
                                null,
                                listOf(
                                    payload.toLlamaSerializedMessage(
                                        fallbackTitle = importedNoteChatTitleText,
                                        sourceLabel = notesImportSourceLabelText
                                    )
                                )
                            )
                        }
                        else -> error(importErrorUnknownFormatText)
                    }
                    }
                    viewModel.importChat(title, systemPrompt, messages) { newId ->
                        navController.navigate(Screen.LlamaChat.createRoute(newId, -1))
                    }
                    Toast.makeText(context, importSuccessText, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, importErrorText + ": ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // The signal is saved by Chats home, so keep a saved high-water mark: returning from a chat
    // recomposes this list with the same signal and must not reopen the sheet.
    var handledNewChatSignal by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(newChatSignal) {
        if (newChatSignal > handledNewChatSignal) {
            handledNewChatSignal = newChatSignal
            showNewChatSheet = true
        }
    }

    val chatActionsMenu: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ui_chats_more))
            }
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llama_folder_create)) },
                    onClick = {
                        showMoreMenu = false
                        showNewFolderDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llama_folder_manage)) },
                    onClick = {
                        showMoreMenu = false
                        showManageFoldersDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llama_prompt_profiles_manage)) },
                    onClick = {
                        showMoreMenu = false
                        showPromptProfilesDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llama_import_chat)) },
                    onClick = {
                        showMoreMenu = false
                        importLauncher.launch(arrayOf("application/json"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.wear_quick_chat_settings)) },
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    onClick = {
                        showMoreMenu = false
                        showWearQuickChatSettings = true
                    }
                )
                if (!embedded) {
                    // The embedded Chats root keeps Servers/Scheduler in its own header menu.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_chats_scheduled_prompts)) },
                        onClick = {
                            showMoreMenu = false
                            navController.navigate(Screen.LlamaScheduler.route)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.title_generation_menu_item)) },
                    onClick = {
                        showMoreMenu = false
                        showTitleGenerationSheet = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quick_setup_button)) },
                    onClick = {
                        showMoreMenu = false
                        showQuickSetupSheet = true
                    }
                )
            }
        }
    }

    val chatListTopBar: @Composable () -> Unit = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentFolder?.name ?: stringResource(R.string.llama_chats_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentFolder != null) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.llama_folder_chat_count,
                                    displayedChats.size,
                                    displayedChats.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedFolderFilter > 0) {
                                selectedFolderFilter = LLAMA_FOLDER_FILTER_ALL
                            } else {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = if (selectedFolderFilter > 0) {
                                stringResource(R.string.llama_folder_back_to_root)
                            } else {
                                stringResource(R.string.action_back)
                            }
                        )
                    }
                },
                actions = {
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                    if (currentFolder != null) {
                        IconButton(
                            onClick = {
                                LlamaChatShortcutHelper.requestPinFolderShortcut(context, currentFolder)
                            }
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = stringResource(
                                    R.string.llama_folder_shortcut_desc,
                                    currentFolder.name
                                )
                            )
                        }
                        IconButton(onClick = { folderToDelete = currentFolder }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.llama_folder_delete_desc, currentFolder.name),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    chatActionsMenu()
                }
            )
    }

    val chatListBody: @Composable (PaddingValues) -> Unit = { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val folderIsEmpty = selectedFolderFilter != LLAMA_FOLDER_FILTER_ALL && displayedChats.isEmpty()
            if (folderIsEmpty) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (chats.isEmpty()) {
                            stringResource(R.string.llama_no_chats)
                        } else {
                            stringResource(R.string.llama_no_chats_in_folder)
                        },
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .walkthroughTarget("llama.history"),
                    // Embedded in the Chats root, leave room for its floating New button.
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = if (embedded) 88.dp else 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedFolderFilter == LLAMA_FOLDER_FILTER_ALL) {
                        item(key = "servers_strip") {
                            ChatsServerStrip(
                                servers = servers,
                                actions = serverActions,
                                onOpen = { server -> serverSheets.detailKey = server.key },
                                onStart = serversViewModel::start,
                                onStop = serversViewModel::stop,
                                onAddServer = { openLocalServerEditor(null, null) },
                                onAddRemote = { serverSheets.openEditor() },
                                onGetModel = { navController.navigate(Screen.LLMModels.route) },
                                onOpenWebUi = { server -> navController.navigate("${Screen.Chat.route}?port=${server.port}") }
                            )
                        }
                        if (chats.isEmpty()) {
                            item(key = "no_chats") {
                                Text(
                                    text = stringResource(R.string.llama_no_chats),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        if (folders.isNotEmpty()) {
                            item(key = "folders_header") {
                                Text(
                                    text = stringResource(R.string.llama_folders_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                            items(folders, key = { "folder_${it.id}" }) { folder ->
                                LlamaFolderCard(
                                    folder = folder,
                                    chatCount = chats.count { it.folderId == folder.id },
                                    onClick = { selectedFolderFilter = folder.id },
                                    onShortcut = {
                                        LlamaChatShortcutHelper.requestPinFolderShortcut(context, folder)
                                    },
                                    onDelete = { folderToDelete = folder }
                                )
                            }
                        }
                        if (rootChats.isNotEmpty()) {
                            item(key = "unfiled_header") {
                                Text(
                                    text = stringResource(R.string.llama_folder_none),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                    items(displayedChats, key = { "chat_${it.id}" }) { chat ->
                        LlamaChatCard(
                            chat = chat,
                            folderName = chat.folderId?.let { folderNamesById[it] },
                            showFolderName = selectedFolderFilter == LLAMA_FOLDER_FILTER_ALL,
                            server = serversByRowId[chat.serverId],
                            serverAction = serversByRowId[chat.serverId]?.let { serverActions[it.key] },
                            onClick = {
                                // Unbound legacy chats pass -1 and fall back to the last-used server.
                                walkthroughTargets?.recordEvent("llama.history")
                                navController.navigate(Screen.LlamaChat.createRoute(chat.id, chat.serverId ?: -1L))
                            },
                            onEdit = { chatToEdit = chat },
                            onMove = { chatToMove = chat },
                            onShortcut = { LlamaChatShortcutHelper.requestPinShortcut(context, chat) },
                            onDelete = { viewModel.deleteChat(chat) }
                        )
                    }
                }
            }
        }
    }

    if (embedded) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedFolderFilter > 0) {
                    IconButton(onClick = { selectedFolderFilter = LLAMA_FOLDER_FILTER_ALL }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.llama_folder_back_to_root)
                        )
                    }
                }
                Text(
                    text = currentFolder?.name ?: stringResource(R.string.llama_chats_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                chatActionsMenu()
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                chatListBody(PaddingValues(0.dp))
            }
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = chatListTopBar,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showNewChatSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(R.string.chats_rework_fab_new_chat),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            },
            content = chatListBody
        )
    }

    if (showWearQuickChatSettings) {
        WearQuickChatSettingsDialog(
            serversViewModel = serversViewModel,
            onDismiss = { showWearQuickChatSettings = false }
        )
    }

    ChatListServerSheets(state = serverSheets, viewModel = serversViewModel, navController = navController)

    if (showTitleGenerationSheet) {
        com.example.llamadroid.ui.settings.TitleGenerationSheet(onDismiss = { showTitleGenerationSheet = false })
    }

    if (showQuickSetupSheet) {
        com.example.llamadroid.ui.quicksetup.QuickSetupSheet(onDismiss = { showQuickSetupSheet = false })
    }

    if (showNewChatSheet) {
        NewChatSheet(
            serversViewModel = serversViewModel,
            repository = repository,
            promptProfiles = promptProfiles,
            folderId = selectedFolderFilter.takeIf { it > 0 },
            onDismiss = { showNewChatSheet = false },
            onChatReady = { chatId, serverId ->
                showNewChatSheet = false
                navController.navigate(Screen.LlamaChat.createRoute(chatId, serverId))
            },
            onAddServer = {
                showNewChatSheet = false
                openLocalServerEditor(null, null)
            },
            onManageModels = {
                showNewChatSheet = false
                navController.navigate(Screen.LLMModels.route)
            },
            onNewLocalFromModel = { model ->
                showNewChatSheet = false
                PendingServerChatRequest.request()
                openLocalServerEditor(null, model.path)
            }
        )
    }

    // Edit Chat Dialog
    if (chatToEdit != null) {
        val chat = chatToEdit!!
        var title by remember { mutableStateOf(chat.title) }
        var systemPrompt by remember { mutableStateOf(chat.systemPrompt ?: "") }

        AlertDialog(
            onDismissRequest = { chatToEdit = null },
            title = { Text(stringResource(R.string.llama_edit_chat)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.llama_chat_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.llama_system_prompt)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LlamaPromptProfilePickerField(
                        customProfiles = promptProfiles,
                        onSelected = { selectedProfile ->
                            systemPrompt = selectedProfile.content
                        }
                    )
                    LlamaPromptProfileActionsRow(
                        currentPrompt = systemPrompt,
                        onManageProfiles = { showPromptProfilesDialog = true },
                        onSaveProfile = { name, content ->
                            viewModel.createPromptProfile(name, content) { success ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        promptProfileSaveErrorText,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            viewModel.updateChat(chat, title, chat.contextSize, systemPrompt.ifBlank { null })
                            chatToEdit = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToEdit = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.llama_folder_create)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.llama_folder_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanName = name.trim()
                        if (cleanName.isNotBlank()) {
                            viewModel.createFolder(cleanName) { success ->
                                if (!success) {
                                    Toast.makeText(context, folderCreateErrorText, Toast.LENGTH_SHORT).show()
                                }
                            }
                            showNewFolderDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showManageFoldersDialog) {
        LlamaFolderManagerDialog(
            folders = folders,
            chats = chats,
            onDismiss = { showManageFoldersDialog = false },
            onCreateFolder = {
                showManageFoldersDialog = false
                showNewFolderDialog = true
            },
            onDeleteFolder = { folder ->
                showManageFoldersDialog = false
                folderToDelete = folder
            }
        )
    }

    if (showPromptProfilesDialog) {
        LlamaPromptProfilesManagerDialog(
            customProfiles = promptProfiles,
            onDismiss = { showPromptProfilesDialog = false },
            onCreateProfile = { name, content ->
                viewModel.createPromptProfile(name, content) { success ->
                    if (!success) {
                        Toast.makeText(
                            context,
                            promptProfileSaveErrorText,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onUpdateProfile = { profile, name, content ->
                viewModel.updatePromptProfile(profile, name, content) { success ->
                    if (!success) {
                        Toast.makeText(
                            context,
                            promptProfileSaveErrorText,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDeleteProfile = { profile ->
                viewModel.deletePromptProfile(profile)
            }
        )
    }

    chatToMove?.let { chat ->
        var selectedFolderId by remember(chat.id) { mutableStateOf(chat.folderId) }
        AlertDialog(
            onDismissRequest = { chatToMove = null },
            title = { Text(stringResource(R.string.llama_folder_move_to)) },
            text = {
                LlamaFolderPickerList(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelected = { selectedFolderId = it }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.moveChatToFolder(chat, selectedFolderId)
                        chatToMove = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToMove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    folderToDelete?.let { folder ->
        val chatCount = chats.count { it.folderId == folder.id }
        val chatCountText = pluralStringResource(R.plurals.llama_folder_chat_count, chatCount, chatCount)
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.llama_folder_delete_title)) },
            text = { Text(stringResource(R.string.llama_folder_delete_message_with_count, folder.name, chatCountText)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folder)
                        if (selectedFolderFilter == folder.id) {
                            selectedFolderFilter = LLAMA_FOLDER_FILTER_ALL
                        }
                        folderToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun LlamaChatCard(
    chat: LlamaChatEntity,
    folderName: String?,
    showFolderName: Boolean = true,
    server: UnifiedServer? = null,
    serverAction: ServerActionState? = null,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onShortcut: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    var menuExpanded by remember { mutableStateOf(false) }
    val generationState by remember(chat.id) { NativeChatGenerationStates.flowFor(chat.id) }
        .collectAsState(initial = NativeChatGenerationStates.stateFor(chat.id))

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showFolderName) {
                    Text(
                        text = folderName ?: stringResource(R.string.llama_folder_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (server != null) {
                    ChatRowServerChip(
                        server = server,
                        action = serverAction,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                val busyLabel = when (generationState) {
                    is LlamaClientService.GenerationState.Generating -> R.string.chat_row_generating
                    is LlamaClientService.GenerationState.Queued -> R.string.chat_row_queued
                    else -> null
                }
                if (busyLabel != null) {
                    Text(
                        text = stringResource(busyLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = dateFormat.format(Date(chat.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.llama_chat_actions))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_folder_move_to)) },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_add_shortcut)) },
                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShortcut()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LlamaFolderCard(
    folder: LlamaChatFolderEntity,
    chatCount: Int,
    onClick: () -> Unit,
    onShortcut: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.llama_folder_chat_count, chatCount, chatCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.llama_folder_open_desc, folder.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.llama_folder_actions))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_add_shortcut)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            expanded = false
                            onShortcut()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_folder_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            expanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LlamaFolderManagerDialog(
    folders: List<LlamaChatFolderEntity>,
    chats: List<LlamaChatEntity>,
    onDismiss: () -> Unit,
    onCreateFolder: () -> Unit,
    onDeleteFolder: (LlamaChatFolderEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_folder_manage)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (folders.isEmpty()) {
                    Text(
                        text = stringResource(R.string.llama_folder_manage_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    folders.forEach { folder ->
                        val chatCount = chats.count { it.folderId == folder.id }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.llama_folder_chat_count,
                                        chatCount,
                                        chatCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteFolder(folder) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.llama_folder_delete_desc, folder.name),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onCreateFolder) {
                Text(stringResource(R.string.llama_folder_create))
            }
        }
    )
}

internal data class LlamaPromptProfileChoice(
    val key: String,
    val name: String,
    val content: String,
    val isBuiltIn: Boolean,
    val customProfile: LlamaChatPromptProfileEntity? = null
)

@Composable
private fun llamaPromptProfileChoices(
    customProfiles: List<LlamaChatPromptProfileEntity>
): List<LlamaPromptProfileChoice> {
    val builtIns = LlamaBuiltInPromptProfiles.all.map { profile ->
        LlamaPromptProfileChoice(
            key = "builtin_${profile.key}",
            name = stringResource(profile.nameRes),
            content = stringResource(profile.contentRes),
            isBuiltIn = true
        )
    }
    val custom = customProfiles.map { profile ->
        LlamaPromptProfileChoice(
            key = "custom_${profile.id}",
            name = profile.name,
            content = profile.content,
            isBuiltIn = false,
            customProfile = profile
        )
    }
    return builtIns + custom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LlamaPromptProfilePickerField(
    customProfiles: List<LlamaChatPromptProfileEntity>,
    onSelected: (LlamaPromptProfileChoice) -> Unit
) {
    val profiles = llamaPromptProfileChoices(customProfiles)
    var expanded by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf("") }
    val placeholder = stringResource(R.string.llama_prompt_profile_picker_placeholder)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel.ifBlank { placeholder },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.llama_prompt_profile_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (profile.isBuiltIn) {
                                    stringResource(R.string.llama_prompt_profile_builtin)
                                } else {
                                    stringResource(R.string.llama_prompt_profile_custom)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        selectedLabel = profile.name
                        expanded = false
                        onSelected(profile)
                    }
                )
            }
        }
    }
}

@Composable
private fun LlamaPromptProfileActionsRow(
    currentPrompt: String,
    onManageProfiles: () -> Unit,
    onSaveProfile: (String, String) -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onManageProfiles,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.llama_prompt_profiles_manage_short),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = { showSaveDialog = true },
            enabled = currentPrompt.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.llama_prompt_profile_save_current),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showSaveDialog) {
        LlamaPromptProfileEditDialog(
            profile = null,
            initialName = "",
            initialContent = currentPrompt,
            contentEditable = false,
            onDismiss = { showSaveDialog = false },
            onSave = { name, content ->
                onSaveProfile(name, content)
                showSaveDialog = false
            }
        )
    }
}

@Composable
private fun LlamaPromptProfilesManagerDialog(
    customProfiles: List<LlamaChatPromptProfileEntity>,
    onDismiss: () -> Unit,
    onCreateProfile: (String, String) -> Unit,
    onUpdateProfile: (LlamaChatPromptProfileEntity, String, String) -> Unit,
    onDeleteProfile: (LlamaChatPromptProfileEntity) -> Unit
) {
    val profiles = llamaPromptProfileChoices(customProfiles)
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<LlamaChatPromptProfileEntity?>(null) }
    var deletingProfile by remember { mutableStateOf<LlamaChatPromptProfileEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_prompt_profiles_manage)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = profile.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (profile.isBuiltIn) {
                                    stringResource(R.string.llama_prompt_profile_builtin_readonly)
                                } else {
                                    stringResource(R.string.llama_prompt_profile_custom)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        profile.customProfile?.let { customProfile ->
                            IconButton(onClick = { editingProfile = customProfile }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                            }
                            IconButton(onClick = { deletingProfile = customProfile }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = { showCreateDialog = true }) {
                Text(stringResource(R.string.llama_prompt_profile_new))
            }
        }
    )

    if (showCreateDialog) {
        LlamaPromptProfileEditDialog(
            profile = null,
            initialName = "",
            initialContent = "",
            contentEditable = true,
            onDismiss = { showCreateDialog = false },
            onSave = { name, content ->
                onCreateProfile(name, content)
                showCreateDialog = false
            }
        )
    }

    editingProfile?.let { profile ->
        LlamaPromptProfileEditDialog(
            profile = profile,
            initialName = profile.name,
            initialContent = profile.content,
            contentEditable = true,
            onDismiss = { editingProfile = null },
            onSave = { name, content ->
                onUpdateProfile(profile, name, content)
                editingProfile = null
            }
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.llama_prompt_profile_delete_title)) },
            text = { Text(stringResource(R.string.llama_prompt_profile_delete_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile)
                        deletingProfile = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LlamaPromptProfileEditDialog(
    profile: LlamaChatPromptProfileEntity?,
    initialName: String,
    initialContent: String,
    contentEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(profile?.id, initialName) { mutableStateOf(initialName) }
    var content by remember(profile?.id, initialContent) { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (profile == null) {
                    stringResource(R.string.llama_prompt_profile_new)
                } else {
                    stringResource(R.string.llama_prompt_profile_edit)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.llama_prompt_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.llama_system_prompt)) },
                    minLines = 5,
                    maxLines = 10,
                    readOnly = !contentEditable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, content) },
                enabled = name.isNotBlank() && content.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaFolderPickerField(
    folders: List<LlamaChatFolderEntity>,
    selectedFolderId: Long?,
    onSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = folders.firstOrNull { it.id == selectedFolderId }?.name ?: stringResource(R.string.llama_folder_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.llama_folder_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.llama_folder_none)) },
                onClick = {
                    onSelected(null)
                    expanded = false
                }
            )
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    onClick = {
                        onSelected(folder.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LlamaFolderPickerList(
    folders: List<LlamaChatFolderEntity>,
    selectedFolderId: Long?,
    onSelected: (Long?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LlamaFolderChoiceRow(
            label = stringResource(R.string.llama_folder_none),
            selected = selectedFolderId == null,
            onClick = { onSelected(null) }
        )
        folders.forEach { folder ->
            LlamaFolderChoiceRow(
                label = folder.name,
                selected = selectedFolderId == folder.id,
                onClick = { onSelected(folder.id) }
            )
        }
    }
}

@Composable
private fun LlamaFolderChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun NoteExportPayload.toLlamaSerializedMessage(
    fallbackTitle: String,
    sourceLabel: String
): LlamaChatSerializedMessage {
    val cleanTitle = title.ifBlank { fallbackTitle }
    val sourceLine = sourceFile?.takeIf { it.isNotBlank() }?.let { "\n$sourceLabel: $it" }.orEmpty()
    val body = buildString {
        append("# ")
        append(cleanTitle)
        append(sourceLine)
        append("\n\n")
        append(content)
    }
    return LlamaChatSerializedMessage(
        role = "user",
        content = body,
        audioPath = audioPath
    )
}
