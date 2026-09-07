package com.example.llamadroid.ui.ai.llama

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val LLAMA_FOLDER_FILTER_ALL = Long.MIN_VALUE
private const val LLAMA_FOLDER_FILTER_UNFILED = 0L
private const val WEAR_QUICK_CHAT_PREFS = "adt_wear_quick_chat_v1"
private const val WEAR_QUICK_CHAT_SELECTED_SERVER_ID = "selected_server_id"
private const val WEAR_QUICK_CHAT_SYSTEM_PROMPT = "system_prompt"
private const val WEAR_QUICK_CHAT_ALLOWED_TOOLS = "allowed_tools"
private const val WEAR_QUICK_CHAT_AUTO_START = "auto_start_server"
private const val WEAR_QUICK_CHAT_AUTO_TTS = "auto_play_tts"
private const val WEAR_TOOL_CALENDAR = "calendar"
private const val WEAR_TOOL_ALARMS = "alarms"
private const val WEAR_TOOL_NOTES = "notes"
private const val WEAR_TOOL_PINNED_NOTE = "pinned_note"
private const val WEAR_TOOL_WEB = "web_search"
private const val WEAR_TOOL_IMAGES = "image_generation"

private fun formatLlamaChatListString(template: String, vararg args: Any?): String =
    String.format(Locale.getDefault(), template, *args)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatListScreen(
    navController: NavController,
    initialFolderId: Long? = null
) {
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
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))
    val chats by viewModel.chats.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val promptProfiles by viewModel.promptProfiles.collectAsState()
    val servers by repository.allServers.collectAsState(initial = emptyList())

    var showNewChatDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showManageFoldersDialog by remember { mutableStateOf(false) }
    var showPromptProfilesDialog by remember { mutableStateOf(false) }
    var showWearQuickChatSettings by remember { mutableStateOf(false) }
    var chatToEdit by remember { mutableStateOf<LlamaChatEntity?>(null) }
    var chatToMove by remember { mutableStateOf<LlamaChatEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<LlamaChatFolderEntity?>(null) }
    var showFabMenu by remember { mutableStateOf(false) }
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
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val gson = Gson()
                    val obj = JSONObject(json)
                    val (title, systemPrompt, messages) = when {
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

    // Ideally we should pass the selected server ID from previous screen
    // But since paths in navigation are strings, we'd need to parse arguments.
    // For simplicity, let's pick the LAST USED server automatically in the Chat Screen, 
    // or ask user to select if ambiguous. 
    // Wait, the route says `llama_chat/{chatId}/{serverId}`.
    // So we need a server ID to navigate to Chat Screen. 
    // We can get the last used server from Repo.
    
    // Let's Fetch last used server ID
    var lastUsedServerId by remember { mutableLongStateOf(-1L) }
    
    LaunchedEffect(Unit) {
        // A bit of a hack to get it in Composable, ideally ViewModel provides this
        kotlinx.coroutines.Dispatchers.IO.let {
             // In a real app, use a proper scope/VM
        }
        // Actually, let's just use the server list VM or add a method to Chat VM
        // For now, let's assume we pass -1 and Chat Screen handles "Active Server" resolution 
        // OR we just use the most recent one.
    }

    Scaffold(
        topBar = {
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
                    Box {
                        IconButton(onClick = { showFabMenu = !showFabMenu }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.llama_new_chat))
                        }
                        DropdownMenu(
                            expanded = showFabMenu,
                            onDismissRequest = { showFabMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_new_chat)) },
                                onClick = {
                                    showFabMenu = false
                                    showNewChatDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_folder_create)) },
                                onClick = {
                                    showFabMenu = false
                                    showNewFolderDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_folder_manage)) },
                                onClick = {
                                    showFabMenu = false
                                    showManageFoldersDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_prompt_profiles_manage)) },
                                onClick = {
                                    showFabMenu = false
                                    showPromptProfilesDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.wear_quick_chat_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    showFabMenu = false
                                    showWearQuickChatSettings = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_import_chat)) },
                                onClick = {
                                    showFabMenu = false
                                    importLauncher.launch(arrayOf("application/json"))
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val rootIsEmpty = false
            val folderIsEmpty = selectedFolderFilter != LLAMA_FOLDER_FILTER_ALL && displayedChats.isEmpty()
            if (rootIsEmpty || folderIsEmpty) {
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedFolderFilter == LLAMA_FOLDER_FILTER_ALL) {
                        item(key = "wear_quick_chat_settings") {
                            WearQuickChatSettingsEntryCard(
                                servers = servers,
                                onClick = { showWearQuickChatSettings = true }
                            )
                        }
                        item(key = "scheduler_folder") {
                            LlamaSchedulerFolderCard(
                                onClick = { navController.navigate(Screen.LlamaScheduler.route) }
                            )
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
                            onClick = {
                                // If we pass -1, LlamaChatScreen can look up "Last Used Server"
                                walkthroughTargets?.recordEvent("llama.history")
                                navController.navigate(Screen.LlamaChat.createRoute(chat.id, -1))
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

    if (showWearQuickChatSettings) {
        WearQuickChatSettingsDialog(
            servers = servers,
            onDismiss = { showWearQuickChatSettings = false }
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

    // New Chat Dialog
    if (showNewChatDialog) {
        var title by remember { mutableStateOf("") }
        var systemPrompt by remember { mutableStateOf("") }
        var newChatFolderId by remember(showNewChatDialog, selectedFolderFilter) {
            mutableStateOf(selectedFolderFilter.takeIf { it > 0 })
        }

        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text(stringResource(R.string.llama_new_chat)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
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
                        placeholder = { Text(stringResource(R.string.llama_system_prompt_placeholder)) },
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
                    LlamaFolderPickerField(
                        folders = folders,
                        selectedFolderId = newChatFolderId,
                        onSelected = { newChatFolderId = it }
                    )
                    Text(
                        text = stringResource(R.string.llama_context_legacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            viewModel.createChat(
                                title = title,
                                contextSize = 0,
                                systemPrompt = systemPrompt.ifBlank { null },
                                apiParams = servers.firstOrNull()?.defaultApiParams?.takeIf { it.isNotBlank() },
                                folderId = newChatFolderId
                            ) { newId ->
                                showNewChatDialog = false
                                navController.navigate(Screen.LlamaChat.createRoute(newId, -1))
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun WearQuickChatSettingsDialog(
    servers: List<LlamaServerEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(WEAR_QUICK_CHAT_PREFS, Context.MODE_PRIVATE)
    }
    val defaultPrompt = stringResource(R.string.wear_quick_chat_default_prompt)
    var selectedServerId by remember {
        mutableLongStateOf(prefs.getLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, -1L))
    }
    var systemPrompt by remember {
        mutableStateOf(prefs.getString(WEAR_QUICK_CHAT_SYSTEM_PROMPT, defaultPrompt) ?: defaultPrompt)
    }
    var autoStartServer by remember {
        mutableStateOf(prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_START, true))
    }
    var autoPlayTts by remember {
        mutableStateOf(prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_TTS, false))
    }
    var allowedTools by remember {
        mutableStateOf(
            prefs.getStringSet(
                WEAR_QUICK_CHAT_ALLOWED_TOOLS,
                setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES)
            )?.toSet() ?: setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wear_quick_chat_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.llama_server_label),
                    style = MaterialTheme.typography.labelLarge
                )
                if (servers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.llama_no_servers),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        servers.take(8).forEach { server ->
                            FilterChip(
                                selected = selectedServerId == server.id,
                                onClick = {
                                    selectedServerId = server.id
                                    prefs.edit().putLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, server.id).apply()
                                },
                                label = {
                                    Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        systemPrompt = it
                        prefs.edit().putString(WEAR_QUICK_CHAT_SYSTEM_PROMPT, it).apply()
                    },
                    label = { Text(stringResource(R.string.llama_system_prompt)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    minLines = 3
                )
                WearQuickChatSettingsSwitchRow(
                    label = stringResource(R.string.wear_quick_chat_auto_start),
                    checked = autoStartServer,
                    onCheckedChange = {
                        autoStartServer = it
                        prefs.edit().putBoolean(WEAR_QUICK_CHAT_AUTO_START, it).apply()
                    }
                )
                WearQuickChatSettingsSwitchRow(
                    label = stringResource(R.string.llama_tool_assistant_tts),
                    checked = autoPlayTts,
                    onCheckedChange = {
                        autoPlayTts = it
                        prefs.edit().putBoolean(WEAR_QUICK_CHAT_AUTO_TTS, it).apply()
                    }
                )
                Text(
                    text = stringResource(R.string.llama_tools_title),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        WEAR_TOOL_CALENDAR to stringResource(R.string.llama_tool_calendar),
                        WEAR_TOOL_ALARMS to stringResource(R.string.llama_tool_alarms),
                        WEAR_TOOL_NOTES to stringResource(R.string.llama_tool_notes),
                        WEAR_TOOL_PINNED_NOTE to stringResource(R.string.wear_quick_chat_modify_pinned_note),
                        WEAR_TOOL_WEB to stringResource(R.string.llama_tool_web_search),
                        WEAR_TOOL_IMAGES to stringResource(R.string.llama_tool_image_generation)
                    ).forEach { (tool, label) ->
                        FilterChip(
                            selected = tool in allowedTools,
                            onClick = {
                                val enabled = tool !in allowedTools
                                allowedTools = if (enabled) allowedTools + tool else allowedTools - tool
                                prefs.edit().putStringSet(WEAR_QUICK_CHAT_ALLOWED_TOOLS, allowedTools).apply()
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun WearQuickChatSettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LlamaChatCard(
    chat: LlamaChatEntity,
    folderName: String?,
    showFolderName: Boolean = true,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onShortcut: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    var menuExpanded by remember { mutableStateOf(false) }

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
private fun LlamaSchedulerFolderCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
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
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.llama_scheduler_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.llama_scheduler_folder_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.llama_scheduler_open_desc),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun WearQuickChatSettingsEntryCard(
    servers: List<LlamaServerEntity>,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val selectedServerId = context
        .getSharedPreferences(WEAR_QUICK_CHAT_PREFS, Context.MODE_PRIVATE)
        .getLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, -1L)
    val selectedServer = servers.firstOrNull { it.id == selectedServerId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
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
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wear_quick_chat_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.wear_quick_chat_settings_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (selectedServer == null) {
                        stringResource(R.string.wear_quick_chat_settings_no_server)
                    } else {
                        stringResource(R.string.wear_quick_chat_settings_server, selectedServer.name)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.wear_quick_chat_settings),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
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

private data class LlamaPromptProfileChoice(
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
private fun LlamaPromptProfilePickerField(
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
