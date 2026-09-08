package com.example.llamadroid.ui.audio.library

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.audio.library.AudioLibraryBatchResult
import com.example.llamadroid.audio.library.AudioLibraryFailureCode
import com.example.llamadroid.audio.library.AudioLibraryFolder
import com.example.llamadroid.audio.library.AudioLibraryFolderDeleteMode
import com.example.llamadroid.audio.library.AudioLibraryItem
import com.example.llamadroid.audio.library.AudioLibraryPreferences
import com.example.llamadroid.audio.library.AudioLibraryQuery
import com.example.llamadroid.audio.library.AudioLibraryRenamePreview
import com.example.llamadroid.audio.library.AudioLibraryRepository
import com.example.llamadroid.audio.library.AudioLibrarySelection
import com.example.llamadroid.audio.library.AudioLibrarySort
import com.example.llamadroid.audio.library.AudioLibraryOperationException
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A paged, Audio-only library. Playback and file actions are deliberately local to this screen;
 * [onUseAsInput] is the single bridge into the music/SFX draft owned by the workspace.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioLibraryScreen(
    navController: NavController? = null,
    repository: AudioLibraryRepository? = null,
    onUseAsInput: (path: String, kind: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = repository ?: remember { AudioLibraryRepository(context) }

    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(AudioLibrarySort.NEWEST.name) }
    var kindFilter by rememberSaveable { mutableStateOf("") }
    var preferencesReady by remember { mutableStateOf(false) }
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    var selection by remember { mutableStateOf(AudioLibrarySelection()) }
    var sortExpanded by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<AudioLibraryFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<AudioLibraryFolder?>(null) }
    var deleteFolderMode by remember { mutableStateOf(AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT) }
    var moveFolderTarget by remember { mutableStateOf<AudioLibraryFolder?>(null) }
    var renameItemTarget by remember { mutableStateOf<AudioLibraryItem?>(null) }
    var deleteItemTarget by remember { mutableStateOf<AudioLibraryItem?>(null) }
    var batchRenameOpen by remember { mutableStateOf(false) }
    var moveItemsOpen by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<AudioLibraryItem?>(null) }
    var moveItemTarget by remember { mutableStateOf<AudioLibraryItem?>(null) }
    var moveDestinationId by remember { mutableStateOf<String?>(null) }
    var movingFolderDestinationId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var storageError by remember { mutableStateOf(false) }
    val libraryListState = rememberLazyListState()
    var readRetry by remember { mutableIntStateOf(0) }

    val preferencesFlow = remember(library, readRetry) { library.observePreferences()
        .catch {
            storageError = true
            emit(AudioLibraryPreferences())
        } }
    val preferences by preferencesFlow.collectAsState(initial = null)
    val foldersFlow = remember(library, readRetry) { library.observeFolders()
        .catch {
            storageError = true
            emit(emptyList())
        } }
    val folders by foldersFlow.collectAsState(initial = emptyList())
    val latestPlayer by rememberUpdatedState(player)

    val sort = AudioLibrarySort.fromStored(sortName)
    val request = remember(query, sort, kindFilter, currentFolderId, pageIndex) {
        AudioLibraryQuery(
            query = query,
            folderId = currentFolderId,
            kind = kindFilter.takeIf { it.isNotBlank() },
            sort = sort,
            page = pageIndex
        )
    }
    val pageFlow = remember(library, request, readRetry) { library.observePage(request)
        .catch {
            storageError = true
            emit(
                com.example.llamadroid.audio.library.AudioLibraryPage(
                    items = emptyList(),
                    page = pageIndex,
                    pageSize = AudioLibraryQuery.DEFAULT_PAGE_SIZE,
                    totalCount = 0
                )
            )
        } }
    val page by pageFlow.collectAsState(
        initial = com.example.llamadroid.audio.library.AudioLibraryPage(
            items = emptyList(),
            page = -1,
            pageSize = AudioLibraryQuery.DEFAULT_PAGE_SIZE,
            totalCount = 0
        )
        )
    val childFolders = remember(folders, currentFolderId) {
        folders.filter { it.parentId == currentFolderId }
    }
    val currentFolder = remember(folders, currentFolderId) { folders.firstOrNull { it.id == currentFolderId } }

    LaunchedEffect(preferences) {
        val stored = preferences ?: return@LaunchedEffect
        if (!preferencesReady) {
            query = stored.query
            sortName = stored.sort.name
            kindFilter = stored.kind.orEmpty()
            currentFolderId = stored.folderId
            preferencesReady = true
        }
    }
    LaunchedEffect(query, sortName, kindFilter, currentFolderId, preferencesReady) {
        if (preferencesReady) {
            delay(250)
            try {
                library.savePreferences(
                    AudioLibraryPreferences(
                        query = query,
                        sort = AudioLibrarySort.fromStored(sortName),
                        folderId = currentFolderId,
                        kind = kindFilter.takeIf { it.isNotBlank() }
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                storageError = true
            }
        }
    }
    LaunchedEffect(query, sortName, kindFilter, currentFolderId) {
        pageIndex = 0
        selection = selection.clear()
    }
    LaunchedEffect(page.totalCount, page.pageSize, page.page, pageIndex, storageError) {
        val lastPage = ((page.totalCount - 1) / page.pageSize).coerceAtLeast(0)
        if (!storageError && page.page == pageIndex && pageIndex > lastPage) pageIndex = lastPage
    }
    LaunchedEffect(pageIndex) {
        libraryListState.scrollToItem(0)
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(4_000)
            feedback = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestPlayer?.runCatching { release() }
        }
    }

    fun stopPlayback() {
        player?.runCatching { release() }
        player = null
        playingId = null
    }

    fun play(item: AudioLibraryItem) {
        if (playingId == item.id) {
            stopPlayback()
            return
        }
        val file = File(item.audioPath)
        if (!file.isFile) {
            feedback = R.string.audio_library_missing_file
            return
        }
        stopPlayback()
        val next = MediaPlayer()
        try {
            next.setDataSource(file.absolutePath)
            next.prepare()
            next.setOnCompletionListener {
                it.release()
                player = null
                playingId = null
            }
            next.start()
            player = next
            playingId = item.id
        } catch (_: Throwable) {
            next.runCatching { release() }
            feedback = R.string.audio_library_operation_failed
        }
    }

    fun share(item: AudioLibraryItem) {
        val file = File(item.audioPath)
        if (!file.isFile) {
            feedback = R.string.audio_library_missing_file
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.onFailure { feedback = R.string.audio_library_operation_failed }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = exportTarget
        exportTarget = null
        val destination = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null && destination != null) {
            scope.launch {
                val exportResult = library.exportItem(target.id, destination)
                if (exportResult.isFailure) feedback = R.string.audio_library_operation_failed
            }
        }
    }

    fun export(item: AudioLibraryItem) {
        exportTarget = item
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = item.mimeType
                putExtra(Intent.EXTRA_TITLE, "${safeExportName(item.title)}.${item.extension}")
            }
        )
    }

    fun report(result: AudioLibraryBatchResult) {
        selection = selection.clear()
        if (result.failures.isNotEmpty()) {
            feedback = when (result.failures.first().code) {
                AudioLibraryFailureCode.ACTIVE_JOB -> R.string.audio_library_active_job
                AudioLibraryFailureCode.FILE_MISSING -> R.string.audio_library_missing_file
                AudioLibraryFailureCode.UNSAFE_PATH -> R.string.audio_library_unsafe_path
                AudioLibraryFailureCode.FOLDER_NOT_EMPTY -> R.string.audio_library_folder_not_empty
                AudioLibraryFailureCode.CONFLICT -> R.string.audio_library_folder_conflict
                AudioLibraryFailureCode.INVALID_FOLDER -> R.string.audio_library_invalid_folder
                AudioLibraryFailureCode.INVALID_NAME -> R.string.audio_library_invalid_name
                AudioLibraryFailureCode.NOT_FOUND -> R.string.audio_library_not_found
                else -> R.string.audio_library_failure
            }
        }
    }

    fun runBatch(action: suspend (List<String>) -> AudioLibraryBatchResult) {
        if (isBusy) return
        isBusy = true
        scope.launch {
            try {
                val ids = if (selection.allMatching) {
                    library.listMatchingIds(request).filterNot { it in selection.excludedIds }
                } else selection.selectedIds.toList()
                report(action(ids))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                feedback = R.string.audio_library_operation_failed
            } finally {
                isBusy = false
            }
        }
    }

    val libraryContent: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            state = libraryListState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (storageError) {
                item(key = "storage-error") {
                    ResponsiveActionGroup {
                        Text(
                            stringResource(R.string.audio_library_storage_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { storageError = false; readRetry++ }) {
                            Text(stringResource(R.string.audio_library_retry))
                        }
                    }
                }
            }
            item(key = "search") {
                AppSectionCard {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.audio_library_clear_selection))
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.audio_library_search_hint)) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(sortLabel(sort)), style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { sortExpanded = true }) {
                                Text(stringResource(R.string.audio_library_sort))
                            }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                AudioLibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(sortLabel(option))) },
                                        onClick = {
                                            sortName = option.name
                                            sortExpanded = false
                                        },
                                        leadingIcon = if (option == sort) ({ Icon(Icons.Default.Check, null) }) else null
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.audio_library_filter_kind),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "" to R.string.audio_library_filter_all,
                            "speech" to R.string.audio_library_kind_speech,
                            "music" to R.string.audio_library_kind_music,
                            "sfx" to R.string.audio_library_kind_sfx
                        ).forEach { (value, label) ->
                            if (value == kindFilter) {
                                Button(onClick = { kindFilter = value }) { Text(stringResource(label)) }
                            } else {
                                OutlinedButton(onClick = { kindFilter = value }) { Text(stringResource(label)) }
                            }
                        }
                    }
                }
            }
            item(key = "folders") {
                AppSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (currentFolderId != null) {
                            IconButton(onClick = { currentFolderId = currentFolder?.parentId }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.audio_library_up))
                            }
                        }
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            currentFolder?.name ?: stringResource(R.string.audio_library_root),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { createFolderOpen = true }) {
                            Text(stringResource(R.string.audio_library_new_folder))
                        }
                    }
                    if (selection.allMatching || selection.selectedIds.isNotEmpty()) {
                        ResponsiveActionGroup {
                            OutlinedButton(onClick = {
                                selection = if (selection.allMatching) selection.clear() else selection.copy(allMatching = true)
                            }) {
                                Text(stringResource(if (selection.allMatching) R.string.audio_library_clear_selection else R.string.audio_library_select_all))
                            }
                            Text(
                                stringResource(
                                    R.string.audio_library_selected_count,
                                    if (selection.allMatching) (page.totalCount - selection.excludedIds.size).coerceAtLeast(0) else selection.selectedIds.size
                                ),
                                modifier = Modifier.align(Alignment.CenterVertically),
                                style = MaterialTheme.typography.labelLarge
                            )
                            OutlinedButton(onClick = { batchRenameOpen = true }) { Text(stringResource(R.string.audio_library_rename)) }
                            OutlinedButton(onClick = {
                                moveItemTarget = null
                                moveDestinationId = null
                                moveItemsOpen = true
                            }) { Text(stringResource(R.string.audio_library_move)) }
                            OutlinedButton(onClick = { runBatch { ids -> library.deleteItems(ids) } }) { Text(stringResource(R.string.audio_library_delete)) }
                        }
                    }
                    childFolders.forEach { folder ->
                        FolderRow(
                            folder = folder,
                            onOpen = { currentFolderId = folder.id },
                            onRename = { renameFolderTarget = folder },
                            onMove = {
                                movingFolderDestinationId = folder.parentId
                                moveFolderTarget = folder
                            },
                            onDelete = {
                                deleteFolderMode = AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT
                                deleteFolderTarget = folder
                            }
                        )
                    }
                }
            }
            if (page.items.isEmpty() && childFolders.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (query.isBlank()) R.string.audio_library_empty else R.string.audio_library_no_results),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 28.dp)
                    )
                    if (query.isBlank()) {
                        Text(stringResource(R.string.audio_library_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(page.items, key = { it.id }) { item ->
                AudioLibraryItemRow(
                    item = item,
                    selected = selection.contains(item.id),
                    playing = playingId == item.id,
                    onSelected = { selection = selection.toggle(item.id) },
                    onPlay = { play(item) },
                    onShare = { share(item) },
                    onExport = { export(item) },
                    onUseAsInput = {
                        if (File(item.audioPath).isFile) onUseAsInput(item.audioPath, item.kind)
                        else feedback = R.string.audio_library_missing_file
                    },
                    onRename = { renameItemTarget = item },
                    onMove = {
                        moveItemTarget = item
                        moveDestinationId = item.folderId
                        moveItemsOpen = true
                    },
                    onDelete = { deleteItemTarget = item }
                )
            }
            if (page.totalCount > 0) {
                item(key = "paging") {
                    ResponsiveActionGroup {
                        Text(
                            text = "${page.page + 1} / ${((page.totalCount - 1) / page.pageSize) + 1} · ${page.totalCount}",
                            modifier = Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { pageIndex -= 1 },
                            enabled = pageIndex > 0 && !isBusy
                        ) { Text(stringResource(R.string.action_previous)) }
                        TextButton(
                            onClick = { pageIndex += 1 },
                            enabled = page.hasNext && !isBusy
                        ) { Text(stringResource(R.string.action_next)) }
                    }
                }
            }
            if (isBusy) item(key = "busy") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            feedback?.let { messageRes ->
                item(key = "feedback") {
                    Text(
                        text = stringResource(messageRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (navController == null) {
        libraryContent()
    } else {
        AppScreenScaffold(
            title = stringResource(R.string.audio_library_title),
            onBack = { navController.popBackStack() },
            actions = {
                IconButton(onClick = { createFolderOpen = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.audio_library_new_folder))
                }
            }
        ) { libraryContent() }
    }

    if (createFolderOpen) {
        AudioLibraryTextDialog(
            title = stringResource(R.string.audio_library_new_folder),
            label = stringResource(R.string.audio_library_folder_name),
            confirmLabel = stringResource(R.string.audio_library_create),
            onDismiss = { createFolderOpen = false },
            onConfirm = { name ->
                scope.launch {
                    try {
                        library.createFolder(name, currentFolderId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        feedback = libraryErrorMessage(error)
                    }
                    createFolderOpen = false
                }
            }
        )
    }
    renameFolderTarget?.let { folder ->
        AudioLibraryTextDialog(
            title = stringResource(R.string.audio_library_rename),
            label = stringResource(R.string.audio_library_folder_name),
            initialValue = folder.name,
            confirmLabel = stringResource(R.string.audio_library_rename),
            onDismiss = { renameFolderTarget = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        library.renameFolder(folder.id, name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        feedback = libraryErrorMessage(error)
                    }
                    renameFolderTarget = null
                }
            }
        )
    }
    deleteFolderTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text(stringResource(R.string.audio_library_delete_folder_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.audio_library_delete_folder_message))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                deleteFolderMode = AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = deleteFolderMode == AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT,
                            onClick = {
                                deleteFolderMode = AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.audio_library_folder_delete_move))
                            Text(
                                stringResource(R.string.audio_library_folder_delete_move_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                deleteFolderMode = AudioLibraryFolderDeleteMode.DELETE_CONTAINED_AUDIO
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = deleteFolderMode == AudioLibraryFolderDeleteMode.DELETE_CONTAINED_AUDIO,
                            onClick = {
                                deleteFolderMode = AudioLibraryFolderDeleteMode.DELETE_CONTAINED_AUDIO
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.audio_library_folder_delete_audio))
                            Text(
                                stringResource(R.string.audio_library_folder_delete_audio_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val result = library.deleteFolderContents(folder.id, deleteFolderMode)
                            report(result)
                            if (result.isSuccess && currentFolderId == folder.id) {
                                currentFolderId = folder.parentId
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            feedback = libraryErrorMessage(error)
                        }
                        deleteFolderTarget = null
                    }
                }) { Text(stringResource(R.string.audio_library_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text(stringResource(R.string.audio_library_cancel)) } }
        )
    }
    renameItemTarget?.let { item ->
        AudioLibraryTextDialog(
            title = stringResource(R.string.audio_library_rename_title),
            label = stringResource(R.string.audio_library_name),
            initialValue = item.title,
            confirmLabel = stringResource(R.string.audio_library_rename),
            onDismiss = { renameItemTarget = null },
            onConfirm = { name ->
                scope.launch {
                    val result = library.renameItem(item.id, name)
                    report(result)
                    renameItemTarget = null
                }
            }
        )
    }
    deleteItemTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItemTarget = null },
            title = { Text(stringResource(R.string.audio_library_delete_title)) },
            text = { Text(stringResource(R.string.audio_library_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        report(library.deleteItem(item.id))
                        deleteItemTarget = null
                    }
                }) { Text(stringResource(R.string.audio_library_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteItemTarget = null }) { Text(stringResource(R.string.audio_library_cancel)) } }
        )
    }
    if (batchRenameOpen) {
        AudioLibraryBatchRenameDialog(
            title = stringResource(R.string.audio_library_batch_rename_title),
            repository = library,
            selectedIds = selection.selectedIds.toList(),
            selection = selection,
            request = request,
            onDismiss = { batchRenameOpen = false },
            onConfirm = { name ->
                batchRenameOpen = false
                runBatch { ids -> library.renameItems(ids, name) }
            }
        )
    }
    if (moveItemsOpen) {
        AudioLibraryFolderPickerDialog(
            title = stringResource(R.string.audio_library_move_title),
            folders = folders,
            selectedId = moveDestinationId,
            currentFolderId = moveItemTarget?.folderId ?: currentFolderId,
            onSelected = { moveDestinationId = it },
            onDismiss = {
                moveItemsOpen = false
                moveItemTarget = null
            },
            onConfirm = {
                moveItemsOpen = false
                val destination = moveDestinationId
                val itemTarget = moveItemTarget
                moveItemTarget = null
                if (itemTarget != null) {
                    scope.launch {
                        try {
                            report(library.moveItems(listOf(itemTarget.id), destination))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            feedback = R.string.audio_library_operation_failed
                        }
                    }
                } else {
                    runBatch { ids -> library.moveItems(ids, destination) }
                }
            }
        )
    }
    moveFolderTarget?.let { folder ->
        AudioLibraryFolderPickerDialog(
            title = stringResource(R.string.audio_library_move_title),
            folders = folders.filter { it.id != folder.id },
            selectedId = movingFolderDestinationId,
            currentFolderId = folder.parentId,
            onSelected = { movingFolderDestinationId = it },
            onDismiss = { moveFolderTarget = null },
            onConfirm = {
                val destination = movingFolderDestinationId
                scope.launch {
                    try {
                        library.moveFolder(folder.id, destination)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        feedback = libraryErrorMessage(error)
                    }
                    moveFolderTarget = null
                }
            }
        )
    }
}

@Composable
private fun FolderRow(
    folder: AudioLibraryFolder,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(folder.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_library_rename)) },
                    onClick = { menuExpanded = false; onRename() },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_library_move)) },
                    onClick = { menuExpanded = false; onMove() },
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_library_delete)) },
                    onClick = { menuExpanded = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun AudioLibraryItemRow(
    item: AudioLibraryItem,
    selected: Boolean,
    playing: Boolean,
    onSelected: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onUseAsInput: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(item.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = selected, onCheckedChange = { onSelected() })
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = listOf(
                            stringResource(kindLabel(item.kind)),
                            formatDuration(item.durationMs),
                            formatLibraryDate(item.createdAt)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val modelAndVoice = buildList {
                        if (item.modelName.isNotBlank()) {
                            add(stringResource(R.string.audio_library_model_line, item.modelName))
                        }
                        item.voiceName?.takeIf { it.isNotBlank() }?.let {
                            add(stringResource(R.string.audio_library_voice_line, it))
                        }
                    }
                    if (modelAndVoice.isNotEmpty()) {
                        Text(modelAndVoice.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = stringResource(if (playing) R.string.audio_library_pause else R.string.audio_library_play))
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_rename)) },
                            onClick = { menuExpanded = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_move)) },
                            onClick = { menuExpanded = false; onMove() },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_delete)) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_share)) },
                            onClick = { menuExpanded = false; onShare() },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_export)) },
                            onClick = { menuExpanded = false; onExport() },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_library_use_as_input)) },
                            onClick = { menuExpanded = false; onUseAsInput() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLibraryTextDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.trim().isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_library_cancel)) } }
    )
}

@Composable
private fun AudioLibraryBatchRenameDialog(
    title: String,
    repository: AudioLibraryRepository,
    selectedIds: List<String>,
    selection: AudioLibrarySelection,
    request: AudioLibraryQuery,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<AudioLibraryRenamePreview>>(emptyList()) }
    var previewFailed by remember { mutableStateOf(false) }
    LaunchedEffect(value, selection, request) {
        val clean = value.trim()
        if (clean.isBlank()) {
            preview = emptyList()
            previewFailed = false
            return@LaunchedEffect
        }
        try {
            val ids = if (selection.allMatching) {
                repository.listMatchingIds(request).filterNot { it in selection.excludedIds }
            } else selectedIds
            // A preview is intentionally bounded; the confirmed action still applies to the
            // complete selected/all-matching set.
            preview = repository.previewRename(ids.take(PREVIEW_LIMIT), clean)
            previewFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            preview = emptyList()
            previewFailed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.audio_library_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.audio_library_preview), style = MaterialTheme.typography.labelLarge)
                if (previewFailed) {
                    Text(stringResource(R.string.audio_library_operation_failed), color = MaterialTheme.colorScheme.error)
                } else if (preview.isEmpty()) {
                    Text(stringResource(R.string.audio_library_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    preview.forEach { item ->
                        Text(
                            text = "${item.currentTitle} → ${item.nextTitle}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (preview.size == PREVIEW_LIMIT) {
                        Text(stringResource(R.string.audio_library_preview_more), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.trim().isNotBlank() && !previewFailed) {
                Text(stringResource(R.string.audio_library_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_library_cancel)) } }
    )
}

@Composable
private fun AudioLibraryFolderPickerDialog(
    title: String,
    folders: List<AudioLibraryFolder>,
    selectedId: String?,
    currentFolderId: String?,
    onSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FolderChoiceRow(
                    name = stringResource(R.string.audio_library_root),
                    selected = selectedId == null,
                    onClick = { onSelected(null) }
                )
                folders.filter { it.id != currentFolderId }.forEach { folder ->
                    FolderChoiceRow(folder.name, selectedId == folder.id) { onSelected(folder.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.audio_library_move_here)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_library_cancel)) } }
    )
}

@Composable
private fun FolderChoiceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun safeExportName(raw: String): String = raw
    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    .trim('.', '_', '-')
    .take(70)
    .ifBlank { "audio" }

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val minutes = seconds / 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
}

private fun formatLibraryDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        .format(Date(timestamp))

private fun sortLabel(sort: AudioLibrarySort): Int = when (sort) {
    AudioLibrarySort.NEWEST -> R.string.audio_library_sort_newest
    AudioLibrarySort.OLDEST -> R.string.audio_library_sort_oldest
    AudioLibrarySort.NAME_ASC -> R.string.audio_library_sort_name_asc
    AudioLibrarySort.NAME_DESC -> R.string.audio_library_sort_name_desc
    AudioLibrarySort.LONGEST -> R.string.audio_library_sort_longest
    AudioLibrarySort.SHORTEST -> R.string.audio_library_sort_shortest
    AudioLibrarySort.SIZE_ASC -> R.string.audio_library_sort_size_asc
    AudioLibrarySort.SIZE_DESC -> R.string.audio_library_sort_size_desc
}

private fun kindLabel(kind: String): Int = when (kind) {
    "music" -> R.string.audio_library_kind_music
    "sfx" -> R.string.audio_library_kind_sfx
    else -> R.string.audio_library_kind_speech
}

private const val PREVIEW_LIMIT = 6

private fun libraryErrorMessage(error: Throwable): Int = when (
    (error as? AudioLibraryOperationException)?.code
) {
    AudioLibraryFailureCode.INVALID_NAME -> R.string.audio_library_invalid_name
    AudioLibraryFailureCode.CONFLICT -> R.string.audio_library_folder_conflict
    AudioLibraryFailureCode.CYCLE -> R.string.audio_library_folder_cycle
    AudioLibraryFailureCode.INVALID_FOLDER -> R.string.audio_library_invalid_folder
    AudioLibraryFailureCode.FOLDER_NOT_EMPTY -> R.string.audio_library_folder_not_empty
    else -> R.string.audio_library_operation_failed
}
