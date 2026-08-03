package com.example.llamadroid.ui.notes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.data.db.OrganizerLlmSettingsEntity
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.ui.ai.llama.LlamaChatExportPayload
import com.example.llamadroid.ui.ai.llama.MarkdownText
import com.example.llamadroid.ui.ai.llama.NoteExportPayload
import com.example.llamadroid.ui.ai.llama.NotesExportPayload
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.markdownToPreview
import com.example.llamadroid.service.formatNativeTodoItems
import com.example.llamadroid.service.OrganizerAlarmScheduler
import com.example.llamadroid.service.parseNativeTodoItems
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import com.google.gson.Gson
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Notes Manager Screen - Unified view for transcriptions, PDF summaries, and manual notes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val clipboardManager = LocalClipboardManager.current

    // State
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<NoteType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var fullScreenNote by remember { mutableStateOf<NoteEntity?>(null) }  // Full screen view
    var selectedImageReference by remember { mutableStateOf<NoteImageReference?>(null) }
    var selectedNoteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pinnedNoteId by remember {
        mutableIntStateOf(com.example.llamadroid.wear.PinnedOrganizerNoteStore.get(context) ?: -1)
    }
    var notesPendingExport by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var selectedOrganizerTab by remember { mutableIntStateOf(0) }
    var selectedCalendarDate by remember { mutableStateOf(LocalDate.now()) }
    var visibleCalendarMonth by remember { mutableStateOf(YearMonth.now()) }
    var showEventDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<OrganizerEventEntity?>(null) }
    var showAlarmDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<OrganizerAlarmEntity?>(null) }
    val gson = remember { Gson() }

    // Load notes
    val allNotes by db.noteDao().getAllNotes().collectAsState(initial = emptyList())
    val organizerEvents by db.organizerDao().getAllEvents().collectAsState(initial = emptyList())
    val organizerAlarms by db.organizerDao().getAllAlarms().collectAsState(initial = emptyList())
    val organizerSettings by db.organizerDao().getLlmSettings().collectAsState(initial = null)
    val effectiveOrganizerSettings = organizerSettings ?: OrganizerLlmSettingsEntity()

    // Filter notes based on search and type filter
    val filteredNotes = remember(allNotes, searchQuery, selectedFilter) {
        allNotes.filter { note ->
            val matchesSearch = searchQuery.isEmpty() ||
                note.title.contains(searchQuery, ignoreCase = true) ||
                note.content.contains(searchQuery, ignoreCase = true)
            val matchesType = selectedFilter == null || note.type == selectedFilter
            matchesSearch && matchesType
        }
    }

    LaunchedEffect(allNotes) {
        val existingIds = allNotes.map { it.id }.toSet()
        selectedNoteIds = selectedNoteIds.intersect(existingIds)
    }

    fun toggleNoteSelection(noteId: Int) {
        selectedNoteIds = if (noteId in selectedNoteIds) {
            selectedNoteIds - noteId
        } else {
            selectedNoteIds + noteId
        }
    }

    val notesExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val notesToExport = notesPendingExport
            scope.launch {
                try {
                    val payload: Any = if (notesToExport.size == 1) {
                        notesToExport.first().toExportPayload()
                    } else {
                        NotesExportPayload(notesToExport.map { it.toExportPayload() })
                    }
                    val json = gson.toJson(payload)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(context.getString(R.string.notes_export_failed_open_output))
                    Toast.makeText(
                        context,
                        context.getString(R.string.notes_export_success, notesToExport.size),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.notes_export_failed,
                            e.message ?: context.getString(R.string.error_generic)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    notesPendingExport = emptyList()
                }
            }
        } else {
            notesPendingExport = emptyList()
        }
    }

    val notesImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val importedNotes = parseNotesImportPayload(
                        json = json,
                        gson = gson,
                        chatSourceLabel = context.getString(R.string.notes_import_source_native_chat),
                        defaultNoteTitle = context.getString(R.string.notes_import_default_single_title),
                        unknownFormatMessage = context.getString(R.string.notes_import_error_unknown_format),
                        systemLabel = context.getString(R.string.llama_note_transcript_system),
                        imageLabel = context.getString(R.string.llama_note_transcript_image),
                        audioLabel = context.getString(R.string.llama_note_transcript_audio)
                    )
                    importedNotes.forEach { db.noteDao().insert(it) }
                    NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                    Toast.makeText(
                        context,
                        context.getString(R.string.notes_import_success, importedNotes.size),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.notes_import_failed,
                            e.message ?: context.getString(R.string.error_generic)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    AppScreenScaffold(
        title = if (selectedOrganizerTab == 0 && selectedNoteIds.isNotEmpty()) {
            stringResource(R.string.notes_selected_count, selectedNoteIds.size)
        } else {
            stringResource(R.string.organizer_title)
        },
        subtitle = if (selectedOrganizerTab == 0 && selectedNoteIds.isNotEmpty()) {
            null
        } else {
            when (selectedOrganizerTab) {
                1 -> stringResource(R.string.organizer_calendar_subtitle)
                2 -> stringResource(R.string.organizer_alarms_subtitle)
                else -> stringResource(R.string.notes_empty_desc)
            }
        },
        onBack = {
            if (selectedOrganizerTab == 0 && selectedNoteIds.isNotEmpty()) {
                selectedNoteIds = emptySet()
            } else {
                navController.popBackStack()
            }
        },
        actions = {
            if (selectedOrganizerTab == 0 && selectedNoteIds.isNotEmpty()) {
                IconButton(
                    onClick = {
                        selectedNoteIds = if (selectedNoteIds.size == filteredNotes.size) {
                            emptySet()
                        } else {
                            filteredNotes.map { it.id }.toSet()
                        }
                    }
                ) {
                    Icon(Icons.Default.DoneAll, stringResource(R.string.notes_select_all))
                }
                IconButton(
                    onClick = {
                        val selected = allNotes.filter { it.id in selectedNoteIds }
                        if (selected.isNotEmpty()) {
                            notesPendingExport = selected
                            notesExportLauncher.launch(notesExportFileName(selected))
                            selectedNoteIds = emptySet()
                        }
                    }
                ) {
                    Icon(Icons.Default.Download, stringResource(R.string.notes_export_selected))
                }
                IconButton(onClick = { showBatchDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.notes_delete_selected), tint = MaterialTheme.colorScheme.error)
                }
                IconButton(
                    onClick = {
                        val idsToUpdate = selectedNoteIds.toList()
                        scope.launch {
                            db.noteDao().setLlmWhitelisted(idsToUpdate, true)
                            selectedNoteIds = emptySet()
                        }
                    }
                ) {
                    Icon(Icons.Default.CheckCircle, stringResource(R.string.notes_llm_allow_selected))
                }
                IconButton(
                    onClick = {
                        val idsToUpdate = selectedNoteIds.toList()
                        scope.launch {
                            db.noteDao().setLlmWhitelisted(idsToUpdate, false)
                            selectedNoteIds = emptySet()
                        }
                    }
                ) {
                    Icon(Icons.Default.RemoveCircle, stringResource(R.string.notes_llm_block_selected))
                }
                IconButton(onClick = { selectedNoteIds = emptySet() }) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
                }
            } else if (selectedOrganizerTab == 0) {
                IconButton(
                    onClick = {
                        notesImportLauncher.launch(arrayOf("application/json"))
                    }
                ) {
                    Icon(Icons.Default.Upload, stringResource(R.string.notes_import_note))
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.notes_new))
                }
            } else if (selectedOrganizerTab == 1) {
                IconButton(onClick = {
                    eventToEdit = null
                    showEventDialog = true
                }) {
                    Icon(Icons.Default.Add, stringResource(R.string.organizer_event_new))
                }
            } else {
                IconButton(onClick = {
                    alarmToEdit = null
                    showAlarmDialog = true
                }) {
                    Icon(Icons.Default.Add, stringResource(R.string.organizer_alarm_new))
                }
            }
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            TabRow(selectedTabIndex = selectedOrganizerTab) {
                listOf(
                    R.string.organizer_tab_notes,
                    R.string.organizer_tab_calendar,
                    R.string.organizer_tab_alarms
                ).forEachIndexed { index, labelRes ->
                    Tab(
                        selected = selectedOrganizerTab == index,
                        onClick = {
                            selectedOrganizerTab = index
                            selectedNoteIds = emptySet()
                        },
                        text = { Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedOrganizerTab == 0) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.notes_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.notes_clear_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filter chips - horizontal scroll for more filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text(stringResource(R.string.notes_all)) }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.TRANSCRIPTION,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.TRANSCRIPTION) null else NoteType.TRANSCRIPTION },
                    label = { Text("🎤") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.PDF_SUMMARY,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.PDF_SUMMARY) null else NoteType.PDF_SUMMARY },
                    label = { Text("📄") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.VIDEO_SUMMARY,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.VIDEO_SUMMARY) null else NoteType.VIDEO_SUMMARY },
                    label = { Text("🎬") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.WORKFLOW,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.WORKFLOW) null else NoteType.WORKFLOW },
                    label = { Text("⚙️") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.TODO_LIST,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.TODO_LIST) null else NoteType.TODO_LIST },
                    label = { Text("☑️") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.MANUAL,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.MANUAL) null else NoteType.MANUAL },
                    label = { Text("📝") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notes list
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.notes_no_notes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            stringResource(R.string.notes_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            selected = note.id in selectedNoteIds,
                            selectionMode = selectedNoteIds.isNotEmpty(),
                            onToggleSelection = { toggleNoteSelection(note.id) },
                            onClick = {
                                if (selectedNoteIds.isNotEmpty()) {
                                    toggleNoteSelection(note.id)
                                } else {
                                    fullScreenNote = note
                                }
                            },
                            onEdit = { selectedNote = note },
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(note.content))
                            },
                            onExport = {
                                notesPendingExport = listOf(note)
                                notesExportLauncher.launch(notesExportFileName(listOf(note)))
                            },
                            isPinned = note.id == pinnedNoteId,
                            onPin = {
                                pinnedNoteId = if (pinnedNoteId == note.id) -1 else note.id
                                com.example.llamadroid.wear.PinnedOrganizerNoteStore.set(
                                    context,
                                    pinnedNoteId.takeIf { it > 0 }
                                )
                            },
                            onDelete = {
                                noteToDelete = note
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
            } else if (selectedOrganizerTab == 1) {
                OrganizerCalendarTab(
                    events = organizerEvents,
                    alarms = organizerAlarms,
                    settings = effectiveOrganizerSettings,
                    selectedDate = selectedCalendarDate,
                    visibleMonth = visibleCalendarMonth,
                    onSelectedDateChange = { selectedCalendarDate = it },
                    onVisibleMonthChange = { visibleCalendarMonth = it },
                    onSettingsChange = { settings ->
                        scope.launch { db.organizerDao().upsertLlmSettings(settings) }
                    },
                    onCreateEvent = {
                        eventToEdit = null
                        showEventDialog = true
                    },
                    onEditEvent = { event ->
                        eventToEdit = event
                        showEventDialog = true
                    },
                    onDeleteEvent = { event ->
                        scope.launch {
                            db.organizerDao().getAlarmsForEventOnce(event.id).forEach { alarm ->
                                OrganizerAlarmScheduler.cancelAlarm(context, alarm.id)
                            }
                            db.organizerDao().deleteEvent(event)
                            OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                        }
                    }
                )
            } else {
                OrganizerAlarmsTab(
                    alarms = organizerAlarms,
                    events = organizerEvents,
                    settings = effectiveOrganizerSettings,
                    onSettingsChange = { settings ->
                        scope.launch { db.organizerDao().upsertLlmSettings(settings) }
                    },
                    onCreateAlarm = {
                        alarmToEdit = null
                        showAlarmDialog = true
                    },
                    onEditAlarm = { alarm ->
                        alarmToEdit = alarm
                        showAlarmDialog = true
                    },
                    onToggleAlarm = { alarm, enabled ->
                        scope.launch {
                            val updated = alarm.copy(
                                enabled = enabled,
                                deliveredAt = if (enabled) null else alarm.deliveredAt,
                                updatedAt = System.currentTimeMillis()
                            )
                            db.organizerDao().updateAlarm(updated)
                            if (enabled) {
                                OrganizerAlarmScheduler.scheduleAlarm(context, updated)
                            } else {
                                OrganizerAlarmScheduler.cancelAlarm(context, updated.id)
                            }
                            OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                        }
                    },
                    onDeleteAlarm = { alarm ->
                        scope.launch {
                            OrganizerAlarmScheduler.cancelAlarm(context, alarm.id)
                            db.organizerDao().deleteAlarm(alarm)
                            OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                        }
                    }
                )
            }
        }
    }

    // Add/Edit note dialog
    if (showAddDialog || selectedNote != null) {
        NoteEditDialog(
            note = selectedNote,
            onDismiss = {
                showAddDialog = false
                selectedNote = null
            },
            onSave = { title, content ->
                scope.launch {
                    if (selectedNote != null) {
                        db.noteDao().update(
                            selectedNote!!.copy(
                                title = title,
                                content = content,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    } else {
                        db.noteDao().insert(
                            NoteEntity(
                                title = title,
                                content = content,
                                type = NoteType.MANUAL
                            )
                        )
                    }
                    NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                    showAddDialog = false
                    selectedNote = null
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                noteToDelete = null
            },
            title = { Text(stringResource(R.string.notes_delete_title)) },
            text = { Text(stringResource(R.string.notes_delete_confirm, noteToDelete?.title ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            noteToDelete?.let {
                                db.noteDao().delete(it)
                                selectedNoteIds = selectedNoteIds - it.id
                                NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                            }
                            showDeleteDialog = false
                            noteToDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    noteToDelete = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showBatchDeleteDialog && selectedNoteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.notes_delete_selected_title)) },
            text = { Text(stringResource(R.string.notes_delete_selected_confirm, selectedNoteIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedNoteIds.toList()
                        scope.launch {
                            db.noteDao().deleteByIds(idsToDelete)
                            NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                            selectedNoteIds = emptySet()
                            showBatchDeleteDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showEventDialog) {
        OrganizerEventEditDialog(
            event = eventToEdit,
            initialDate = selectedCalendarDate,
            onDismiss = {
                showEventDialog = false
                eventToEdit = null
            },
            onSave = { title, description, location, startText, endText, allDay, colorText, alarmText ->
                scope.launch {
                    runCatching {
                        val start = parseOrganizerUiDateTime(
                            startText,
                            context.getString(R.string.organizer_error_datetime_required),
                            context.getString(R.string.organizer_error_datetime_format)
                        )
                        val end = endText.trim().takeIf { it.isNotBlank() }?.let {
                            parseOrganizerUiDateTime(
                                it,
                                context.getString(R.string.organizer_error_datetime_required),
                                context.getString(R.string.organizer_error_datetime_format)
                            )
                        }
                        require(end == null || !end.toInstant().isBefore(start.toInstant())) {
                            context.getString(R.string.organizer_event_error_end_before_start)
                        }
                        val now = System.currentTimeMillis()
                        val color = parseOrganizerUiColor(colorText, context.getString(R.string.organizer_error_color_format))
                        val savedEventId = if (eventToEdit != null) {
                            val existing = eventToEdit ?: error(context.getString(R.string.error_generic))
                            db.organizerDao().updateEvent(
                                existing.copy(
                                    title = title.trim(),
                                    description = description.trim(),
                                    location = location.trim(),
                                    startAtMillis = start.toInstant().toEpochMilli(),
                                    endAtMillis = end?.toInstant()?.toEpochMilli(),
                                    allDay = allDay,
                                    timezoneId = start.zone.id,
                                    colorArgb = color,
                                    updatedAt = now
                                )
                            )
                            existing.id
                        } else {
                            db.organizerDao().insertEvent(
                                OrganizerEventEntity(
                                    title = title.trim(),
                                    description = description.trim(),
                                    location = location.trim(),
                                    startAtMillis = start.toInstant().toEpochMilli(),
                                    endAtMillis = end?.toInstant()?.toEpochMilli(),
                                    allDay = allDay,
                                    timezoneId = start.zone.id,
                                    colorArgb = color,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        alarmText.trim().takeIf { it.isNotBlank() }?.let { rawAlarm ->
                            val alarmAt = parseOrganizerUiDateTime(
                                rawAlarm,
                                context.getString(R.string.organizer_error_datetime_required),
                                context.getString(R.string.organizer_error_datetime_format)
                            )
                            val alarm = OrganizerAlarmEntity(
                                eventId = savedEventId,
                                title = title.trim(),
                                message = description.trim(),
                                triggerAtMillis = alarmAt.toInstant().toEpochMilli(),
                                timezoneId = alarmAt.zone.id,
                                createdAt = now,
                                updatedAt = now
                            )
                            val alarmId = db.organizerDao().insertAlarm(alarm)
                            OrganizerAlarmScheduler.scheduleAlarm(context, alarm.copy(id = alarmId))
                        }
                        OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                    }.onSuccess {
                        showEventDialog = false
                        eventToEdit = null
                    }.onFailure { error ->
                        Toast.makeText(context, error.message ?: context.getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showAlarmDialog) {
        OrganizerAlarmEditDialog(
            alarm = alarmToEdit,
            onDismiss = {
                showAlarmDialog = false
                alarmToEdit = null
            },
            onSave = { title, message, triggerText, soundEnabled, enabled ->
                scope.launch {
                    runCatching {
                        val trigger = parseOrganizerUiDateTime(
                            triggerText,
                            context.getString(R.string.organizer_error_datetime_required),
                            context.getString(R.string.organizer_error_datetime_format)
                        )
                        val now = System.currentTimeMillis()
                        val savedAlarm = if (alarmToEdit != null) {
                            val existing = alarmToEdit ?: error(context.getString(R.string.error_generic))
                            existing.copy(
                                title = title.trim(),
                                message = message.trim(),
                                triggerAtMillis = trigger.toInstant().toEpochMilli(),
                                timezoneId = trigger.zone.id,
                                soundEnabled = soundEnabled,
                                enabled = enabled,
                                deliveredAt = if (enabled) null else existing.deliveredAt,
                                updatedAt = now
                            ).also { db.organizerDao().updateAlarm(it) }
                        } else {
                            val alarm = OrganizerAlarmEntity(
                                title = title.trim(),
                                message = message.trim(),
                                triggerAtMillis = trigger.toInstant().toEpochMilli(),
                                timezoneId = trigger.zone.id,
                                soundEnabled = soundEnabled,
                                enabled = enabled,
                                createdAt = now,
                                updatedAt = now
                            )
                            val alarmId = db.organizerDao().insertAlarm(alarm)
                            alarm.copy(id = alarmId)
                        }
                        if (savedAlarm.enabled) {
                            OrganizerAlarmScheduler.scheduleAlarm(context, savedAlarm)
                        } else {
                            OrganizerAlarmScheduler.cancelAlarm(context, savedAlarm.id)
                        }
                        OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                    }.onSuccess {
                        showAlarmDialog = false
                        alarmToEdit = null
                    }.onFailure { error ->
                        Toast.makeText(context, error.message ?: context.getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // Full screen note view
    fullScreenNote?.let { note ->
        val latestNote = allNotes.firstOrNull { it.id == note.id } ?: note
        NoteFullScreenDialog(
            note = latestNote,
            onDismiss = { fullScreenNote = null },
            onEdit = {
                fullScreenNote = null
                selectedNote = latestNote
            },
            onCopy = {
                clipboardManager.setText(AnnotatedString(latestNote.content))
            },
            onDelete = {
                fullScreenNote = null
                noteToDelete = latestNote
                showDeleteDialog = true
            },
            onImageClick = { imageReference ->
                selectedImageReference = imageReference.copy(noteId = latestNote.id)
            },
            onTodoItemChecked = { itemIndex, checked ->
                scope.launch {
                    val current = db.noteDao().getNoteById(latestNote.id) ?: return@launch
                    val updatedContent = updateTodoCheckedStateInMarkdown(current.content, itemIndex, checked)
                    if (updatedContent != current.content) {
                        db.noteDao().update(
                            current.copy(
                                content = updatedContent,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                    }
                }
            }
        )
    }

    selectedImageReference?.let { imageReference ->
        NoteImageDialog(
            imageReference = imageReference,
            onDismiss = { selectedImageReference = null },
            onShare = {
                shareNoteImage(
                    context = context,
                    imageReference = imageReference,
                    onFailure = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onDelete = {
                scope.launch {
                    val current = db.noteDao().getNoteById(imageReference.noteId)
                    if (current != null) {
                        db.noteDao().update(
                            current.copy(
                                content = removeImageReferenceFromMarkdown(current.content, imageReference),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                    }
                    deleteNoteImageFileIfLocal(imageReference.path)
                    selectedImageReference = null
                }
            }
        )
    }
}

@Composable
private fun OrganizerCalendarTab(
    events: List<OrganizerEventEntity>,
    alarms: List<OrganizerAlarmEntity>,
    settings: OrganizerLlmSettingsEntity,
    selectedDate: LocalDate,
    visibleMonth: YearMonth,
    onSelectedDateChange: (LocalDate) -> Unit,
    onVisibleMonthChange: (YearMonth) -> Unit,
    onSettingsChange: (OrganizerLlmSettingsEntity) -> Unit,
    onCreateEvent: () -> Unit,
    onEditEvent: (OrganizerEventEntity) -> Unit,
    onDeleteEvent: (OrganizerEventEntity) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val selectedEvents = remember(events, selectedDate, zone) {
        events.filter { it.occursOn(selectedDate, zone) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            OrganizerLlmAccessCard(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
        }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onVisibleMonthChange(visibleMonth.minusMonths(1)) }) {
                            Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.organizer_calendar_previous_month))
                        }
                        Text(
                            visibleMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { onVisibleMonthChange(visibleMonth.plusMonths(1)) }) {
                            Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.organizer_calendar_next_month))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        organizerWeekdayLabels().forEach { label ->
                            Text(
                                label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    organizerMonthCells(visibleMonth).chunked(7).forEach { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            week.forEach { date ->
                                val hasEvents = date != null && events.any { it.occursOn(date, zone) }
                                val isSelected = date == selectedDate
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = date != null) {
                                            date?.let {
                                                onSelectedDateChange(it)
                                                if (YearMonth.from(it) != visibleMonth) {
                                                    onVisibleMonthChange(YearMonth.from(it))
                                                }
                                            }
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                                        date == LocalDate.now() -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = date?.dayOfMonth?.toString().orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (hasEvents) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onCreateEvent) {
                    Text(stringResource(R.string.organizer_event_new))
                }
            }
        }
        if (selectedEvents.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.organizer_calendar_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            lazyListItems(selectedEvents, key = { it.id }) { event ->
                OrganizerEventCard(
                    event = event,
                    alarms = alarms.filter { it.eventId == event.id },
                    onEdit = { onEditEvent(event) },
                    onDelete = { onDeleteEvent(event) }
                )
            }
        }
    }
}

@Composable
private fun OrganizerAlarmsTab(
    alarms: List<OrganizerAlarmEntity>,
    events: List<OrganizerEventEntity>,
    settings: OrganizerLlmSettingsEntity,
    onSettingsChange: (OrganizerLlmSettingsEntity) -> Unit,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (OrganizerAlarmEntity) -> Unit,
    onToggleAlarm: (OrganizerAlarmEntity, Boolean) -> Unit,
    onDeleteAlarm: (OrganizerAlarmEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            OrganizerLlmAccessCard(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.organizer_alarms_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onCreateAlarm) {
                    Text(stringResource(R.string.organizer_alarm_new))
                }
            }
        }
        if (alarms.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.organizer_alarms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            lazyListItems(alarms.sortedWith(compareBy<OrganizerAlarmEntity> { it.triggerAtMillis }.thenBy { it.id }), key = { it.id }) { alarm ->
                OrganizerAlarmCard(
                    alarm = alarm,
                    linkedEvent = events.firstOrNull { it.id == alarm.eventId },
                    onEdit = { onEditAlarm(alarm) },
                    onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                    onDelete = { onDeleteAlarm(alarm) }
                )
            }
        }
    }
}

@Composable
private fun OrganizerLlmAccessCard(
    settings: OrganizerLlmSettingsEntity,
    onSettingsChange: (OrganizerLlmSettingsEntity) -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.organizer_llm_access_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.organizer_llm_access_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OrganizerSwitchRow(
                title = stringResource(R.string.organizer_llm_calendar_toggle),
                checked = settings.calendarToolsAllowed,
                onCheckedChange = {
                    onSettingsChange(
                        settings.copy(
                            calendarToolsAllowed = it,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            )
            OrganizerSwitchRow(
                title = stringResource(R.string.organizer_llm_alarm_toggle),
                checked = settings.alarmToolsAllowed,
                onCheckedChange = {
                    onSettingsChange(
                        settings.copy(
                            alarmToolsAllowed = it,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun OrganizerSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OrganizerEventCard(
    event: OrganizerEventEntity,
    alarms: List<OrganizerAlarmEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(shape = RoundedCornerShape(10.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (event.allDay) stringResource(R.string.organizer_all_day) else organizerEventTimeLabel(event),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
            event.location.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            event.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (alarms.isNotEmpty()) {
                Text(
                    stringResource(R.string.organizer_event_alarm_count, alarms.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun OrganizerAlarmCard(
    alarm: OrganizerAlarmEntity,
    linkedEvent: OrganizerEventEntity?,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(alarm.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    organizerAlarmTimeLabel(alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                linkedEvent?.let {
                    Text(
                        stringResource(R.string.organizer_alarm_linked_event, it.title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                alarm.message.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    isPinned: Boolean,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    var showActionsMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            ),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type badge
                val (emoji, badgeColor) = when (note.type) {
                    NoteType.TRANSCRIPTION -> "🎤" to MaterialTheme.colorScheme.primaryContainer
                    NoteType.PDF_SUMMARY -> "📄" to MaterialTheme.colorScheme.secondaryContainer
                    NoteType.VIDEO_SUMMARY -> "🎬" to MaterialTheme.colorScheme.tertiaryContainer
                    NoteType.WORKFLOW -> "⚙️" to MaterialTheme.colorScheme.surfaceVariant
                    NoteType.TODO_LIST -> "☑️" to MaterialTheme.colorScheme.primaryContainer
                    NoteType.MANUAL -> "📝" to MaterialTheme.colorScheme.surfaceContainerHigh
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (selectionMode) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleSelection() },
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(emoji, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Audio indicator
                    if (note.audioPath != null) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.notes_has_audio), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (note.isLlmWhitelisted) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.notes_llm_allowed_badge),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (!selectionMode) {
                    Box {
                        IconButton(onClick = { showActionsMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.notes_note_actions), modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isPinned) R.string.notes_unpin_from_watch
                                            else R.string.notes_pin_to_watch
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = {
                                    showActionsMenu = false
                                    onPin()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    showActionsMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copy)) },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showActionsMenu = false
                                    onCopy()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.notes_export_note)) },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = {
                                    showActionsMenu = false
                                    onExport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showActionsMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                markdownToPreview(note.content),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            note.sourceFile?.let {
                Text(
                    stringResource(R.string.notes_source_prefix, it.substringAfterLast("/")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        dateFormat.format(Date(note.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditDialog(
    note: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit
) {
    var title by remember(note) { mutableStateOf(note?.title ?: "") }
    var content by remember(note) { mutableStateOf(note?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(0.95f),
        title = { Text(if (note == null) stringResource(R.string.notes_new_title) else stringResource(R.string.notes_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.notes_field_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.notes_field_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    maxLines = 50
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, content) },
                enabled = title.isNotBlank()
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

@Composable
private fun OrganizerEventEditDialog(
    event: OrganizerEventEntity?,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        description: String,
        location: String,
        startText: String,
        endText: String,
        allDay: Boolean,
        colorText: String,
        alarmText: String
    ) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialStart = remember(event, initialDate) {
        event?.startAtMillis?.let { millis ->
            Instant.ofEpochMilli(millis)
                .atZone(zone)
                .withSecond(0)
                .withNano(0)
        } ?: ZonedDateTime.of(
            initialDate,
            LocalTime.now(zone).withSecond(0).withNano(0),
            zone
        )
    }
    val initialEnd = remember(event) {
        event?.endAtMillis?.let { millis ->
            Instant.ofEpochMilli(millis)
                .atZone(zone)
                .withSecond(0)
                .withNano(0)
        }
    }
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var description by remember(event) { mutableStateOf(event?.description.orEmpty()) }
    var location by remember(event) { mutableStateOf(event?.location.orEmpty()) }
    var startDate by remember(event, initialDate) { mutableStateOf(initialStart.toLocalDate()) }
    var startTime by remember(event, initialDate) { mutableStateOf(initialStart.toLocalTime()) }
    var endDate by remember(event) { mutableStateOf(initialEnd?.toLocalDate()) }
    var endTime by remember(event) { mutableStateOf(initialEnd?.toLocalTime()) }
    var allDay by remember(event) { mutableStateOf(event?.allDay ?: false) }
    var colorText by remember(event) { mutableStateOf(event?.colorArgb?.let { "#${it.toString(16).uppercase(Locale.US).padStart(8, '0')}" }.orEmpty()) }
    var alarmDate by remember(event) { mutableStateOf<LocalDate?>(null) }
    var alarmTime by remember(event) { mutableStateOf<LocalTime?>(null) }
    val defaultEndDateTime = remember(startDate, startTime) {
        LocalDateTime.of(startDate, startTime).plusHours(1)
    }
    val defaultAlarmDateTime = remember(startDate, startTime) {
        LocalDateTime.of(startDate, startTime)
    }
    val startText = remember(startDate, startTime) {
        organizerUiDateTimeText(startDate, startTime)
    }
    val endText = remember(endDate, endTime) {
        val date = endDate
        val time = endTime
        if (date != null && time != null) organizerUiDateTimeText(date, time) else ""
    }
    val alarmText = remember(alarmDate, alarmTime) {
        val date = alarmDate
        val time = alarmTime
        if (date != null && time != null) organizerUiDateTimeText(date, time) else ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(0.95f),
        title = { Text(if (event == null) stringResource(R.string.organizer_event_new) else stringResource(R.string.organizer_event_edit)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.organizer_field_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OrganizerDateTimeSelector(
                    label = stringResource(R.string.organizer_field_start),
                    date = startDate,
                    time = startTime,
                    onDateChange = { startDate = it },
                    onTimeChange = { startTime = it }
                )
                OrganizerOptionalDateTimeSelector(
                    label = { Text(stringResource(R.string.organizer_field_end_optional)) },
                    addLabel = stringResource(R.string.organizer_event_add_end),
                    clearContentDescription = stringResource(R.string.organizer_event_clear_end),
                    date = endDate,
                    time = endTime,
                    defaultDate = defaultEndDateTime.toLocalDate(),
                    defaultTime = defaultEndDateTime.toLocalTime(),
                    onDateTimeChange = { date, time ->
                        endDate = date
                        endTime = time
                    }
                )
                OrganizerSwitchRow(
                    title = stringResource(R.string.organizer_field_all_day),
                    checked = allDay,
                    onCheckedChange = { allDay = it }
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.organizer_field_location)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OrganizerEventColorSelector(
                    colorText = colorText,
                    onColorTextChange = { colorText = it }
                )
                OrganizerOptionalDateTimeSelector(
                    label = { Text(stringResource(R.string.organizer_field_alarm_optional)) },
                    addLabel = stringResource(R.string.organizer_event_add_alarm),
                    clearContentDescription = stringResource(R.string.organizer_event_clear_alarm),
                    date = alarmDate,
                    time = alarmTime,
                    defaultDate = defaultAlarmDateTime.toLocalDate(),
                    defaultTime = defaultAlarmDateTime.toLocalTime(),
                    onDateTimeChange = { date, time ->
                        alarmDate = date
                        alarmTime = time
                    }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.organizer_field_description)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, description, location, startText, endText, allDay, colorText, alarmText) },
                enabled = title.isNotBlank()
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

@Composable
private fun OrganizerDateTimeSelector(
    label: String,
    date: LocalDate,
    time: LocalTime,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OrganizerDateTimeButtons(
            date = date,
            time = time,
            onDateChange = onDateChange,
            onTimeChange = onTimeChange
        )
    }
}

@Composable
private fun OrganizerEventColorSelector(
    colorText: String,
    onColorTextChange: (String) -> Unit
) {
    var showColorDialog by remember { mutableStateOf(false) }
    val selectedColor = remember(colorText) { parseOrganizerUiColorOrNull(colorText) }
    val colorLabel = selectedColor?.let(::formatOrganizerUiColorText)
        ?: stringResource(R.string.organizer_color_none)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.organizer_field_color_optional),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { showColorDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            OrganizerColorSwatch(
                colorArgb = selectedColor,
                selected = false,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.organizer_color_choose, colorLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showColorDialog) {
        OrganizerEventColorPickerDialog(
            selectedColorText = colorText,
            onDismiss = { showColorDialog = false },
            onColorSelected = {
                onColorTextChange(it)
                showColorDialog = false
            }
        )
    }
}

@Composable
private fun OrganizerEventColorPickerDialog(
    selectedColorText: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    var customHex by remember(selectedColorText) { mutableStateOf(selectedColorText) }
    val selectedColor = remember(selectedColorText) { parseOrganizerUiColorOrNull(selectedColorText) }
    val customColor = remember(customHex) { parseOrganizerUiColorOrNull(customHex) }
    val customIsValid = customHex.isBlank() || customColor != null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(0.9f),
        title = { Text(stringResource(R.string.organizer_color_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                organizerDefaultEventColors().chunked(2).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowColors.forEach { option ->
                            OrganizerColorOptionButton(
                                option = option,
                                selected = selectedColor == option.colorArgb,
                                onClick = { onColorSelected(option.hex) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowColors.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                OutlinedTextField(
                    value = customHex,
                    onValueChange = { customHex = it },
                    label = { Text(stringResource(R.string.organizer_color_custom_hex)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !customIsValid,
                    supportingText = {
                        if (!customIsValid) {
                            Text(stringResource(R.string.organizer_error_color_format))
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    customColor?.let {
                        onColorSelected(formatOrganizerUiColorText(it))
                    } ?: onColorSelected("")
                },
                enabled = customIsValid
            ) {
                Text(stringResource(R.string.organizer_color_use_custom))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onColorSelected("") }) {
                    Text(stringResource(R.string.organizer_color_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
private fun OrganizerColorOptionButton(
    option: OrganizerEventColorOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        OrganizerColorSwatch(
            colorArgb = option.colorArgb,
            selected = selected,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(option.labelRes),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OrganizerColorSwatch(
    colorArgb: Long?,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val color = colorArgb?.let { Color(it.toInt()) } ?: Color.Transparent
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color,
        border = BorderStroke(
            width = if (selected) 3.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
    ) {}
}

private data class OrganizerEventColorOption(
    val labelRes: Int,
    val hex: String
) {
    val colorArgb: Long = parseOrganizerUiColor(hex, "").requireNoNullsForColorOption()
}

private fun organizerDefaultEventColors(): List<OrganizerEventColorOption> = listOf(
    OrganizerEventColorOption(R.string.organizer_color_red, "#D32F2F"),
    OrganizerEventColorOption(R.string.organizer_color_orange, "#F57C00"),
    OrganizerEventColorOption(R.string.organizer_color_yellow, "#FBC02D"),
    OrganizerEventColorOption(R.string.organizer_color_green, "#388E3C"),
    OrganizerEventColorOption(R.string.organizer_color_teal, "#00897B"),
    OrganizerEventColorOption(R.string.organizer_color_blue, "#1976D2"),
    OrganizerEventColorOption(R.string.organizer_color_indigo, "#3F51B5"),
    OrganizerEventColorOption(R.string.organizer_color_purple, "#7B1FA2"),
    OrganizerEventColorOption(R.string.organizer_color_pink, "#C2185B"),
    OrganizerEventColorOption(R.string.organizer_color_gray, "#607D8B")
)

private fun Long?.requireNoNullsForColorOption(): Long =
    requireNotNull(this) { "Default Organizer event colors must be valid." }

private fun parseOrganizerUiColorOrNull(value: String): Long? =
    runCatching { parseOrganizerUiColor(value, "") }.getOrNull()

private fun formatOrganizerUiColorText(colorArgb: Long): String {
    val alpha = (colorArgb ushr 24) and 0xFF
    val color = if (alpha == 0xFFL) colorArgb and 0x00FFFFFF else colorArgb
    val digits = if (alpha == 0xFFL) 6 else 8
    return "#${color.toString(16).uppercase(Locale.US).padStart(digits, '0')}"
}

@Composable
private fun OrganizerOptionalDateTimeSelector(
    label: @Composable () -> Unit,
    addLabel: String,
    clearContentDescription: String,
    date: LocalDate?,
    time: LocalTime?,
    defaultDate: LocalDate,
    defaultTime: LocalTime,
    onDateTimeChange: (LocalDate?, LocalTime?) -> Unit
) {
    val selectedDate = date
    val selectedTime = time
    if (selectedDate == null || selectedTime == null) {
        OutlinedButton(
            onClick = { onDateTimeChange(defaultDate, defaultTime) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(addLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProvideTextStyle(
                    value = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    label()
                }
                IconButton(
                    onClick = { onDateTimeChange(null, null) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = clearContentDescription,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            OrganizerDateTimeButtons(
                date = selectedDate,
                time = selectedTime,
                onDateChange = { onDateTimeChange(it, selectedTime) },
                onTimeChange = { onDateTimeChange(selectedDate, it) }
            )
        }
    }
}

@Composable
private fun OrganizerDateTimeButtons(
    date: LocalDate,
    time: LocalTime,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        onDateChange(LocalDate.of(year, month + 1, dayOfMonth))
                    },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth
                ).show()
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = organizerUiDateLabel(date),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        onTimeChange(LocalTime.of(hourOfDay, minute))
                    },
                    time.hour,
                    time.minute,
                    true
                ).show()
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(organizerUiTimeLabel(time))
        }
    }
}

@Composable
private fun OrganizerAlarmEditDialog(
    alarm: OrganizerAlarmEntity?,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        message: String,
        triggerText: String,
        soundEnabled: Boolean,
        enabled: Boolean
) -> Unit
) {
    val initialTrigger = remember(alarm) {
        Instant.ofEpochMilli(alarm?.triggerAtMillis ?: (System.currentTimeMillis() + 3_600_000L))
            .atZone(ZoneId.systemDefault())
            .withSecond(0)
            .withNano(0)
    }
    var title by remember(alarm) { mutableStateOf(alarm?.title.orEmpty()) }
    var message by remember(alarm) { mutableStateOf(alarm?.message.orEmpty()) }
    var triggerDate by remember(alarm) { mutableStateOf(initialTrigger.toLocalDate()) }
    var triggerTime by remember(alarm) { mutableStateOf(initialTrigger.toLocalTime()) }
    var soundEnabled by remember(alarm) { mutableStateOf(alarm?.soundEnabled ?: true) }
    var enabled by remember(alarm) { mutableStateOf(alarm?.enabled ?: true) }
    val triggerText = remember(triggerDate, triggerTime) {
        organizerUiDateTimeText(triggerDate, triggerTime)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(0.95f),
        title = { Text(if (alarm == null) stringResource(R.string.organizer_alarm_new) else stringResource(R.string.organizer_alarm_edit)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.organizer_field_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OrganizerDateTimeSelector(
                    label = stringResource(R.string.organizer_field_alarm_time),
                    date = triggerDate,
                    time = triggerTime,
                    onDateChange = { triggerDate = it },
                    onTimeChange = { triggerTime = it }
                )
                OrganizerSwitchRow(
                    title = stringResource(R.string.organizer_field_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
                OrganizerSwitchRow(
                    title = stringResource(R.string.organizer_field_sound),
                    checked = soundEnabled,
                    onCheckedChange = { soundEnabled = it }
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.organizer_field_message)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, message, triggerText, soundEnabled, enabled) },
                enabled = title.isNotBlank() && triggerText.isNotBlank()
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

/**
 * Full screen dialog for viewing long notes with audio playback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteFullScreenDialog(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onImageClick: (NoteImageReference) -> Unit,
    onTodoItemChecked: (Int, Boolean) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    // Audio playback state
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Cleanup media player
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    // Type info
    val (emoji, typeName) = when (note.type) {
        NoteType.TRANSCRIPTION -> "🎤" to stringResource(R.string.notes_type_transcription)
        NoteType.PDF_SUMMARY -> "📄" to stringResource(R.string.notes_type_pdf_summary)
        NoteType.VIDEO_SUMMARY -> "🎬" to stringResource(R.string.notes_type_video_summary)
        NoteType.WORKFLOW -> "⚙️" to stringResource(R.string.notes_type_workflow)
        NoteType.TODO_LIST -> "☑️" to stringResource(R.string.notes_type_todo_list)
        NoteType.MANUAL -> "📝" to stringResource(R.string.notes_type_note)
    }

    AlertDialog(
        onDismissRequest = {
            mediaPlayer?.release()
            onDismiss()
        },
        modifier = Modifier.fillMaxSize(0.95f)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with actions only
                TopAppBar(
                    title = { Text(stringResource(R.string.notes_details_title)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            mediaPlayer?.release()
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, stringResource(R.string.notes_details_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.Share, stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = {
                            mediaPlayer?.release()
                            onDelete()
                        }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )

                // Visible title card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$emoji $typeName • ${dateFormat.format(Date(note.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Audio player (if available)
                note.audioPath?.let { audioPath ->
                    if (File(audioPath).exists()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    if (isPlaying) {
                                        mediaPlayer?.pause()
                                        isPlaying = false
                                    } else {
                                        if (mediaPlayer == null) {
                                            mediaPlayer = MediaPlayer().apply {
                                                setDataSource(audioPath)
                                                prepare()
                                                setOnCompletionListener { isPlaying = false }
                                            }
                                        }
                                        mediaPlayer?.start()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                    if (isPlaying) stringResource(R.string.notes_audio_stop) else stringResource(R.string.notes_audio_play),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "🎵 " + stringResource(R.string.notes_audio_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Source file info
                    note.sourceFile?.let {
                        Text(
                            stringResource(R.string.notes_source_prefix, it.substringAfterLast("/")),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (note.type == NoteType.TODO_LIST) {
                        TodoListNoteContent(
                            content = note.content,
                            noteId = note.id,
                            onImageClick = onImageClick,
                            onItemCheckedChange = onTodoItemChecked
                        )
                    } else {
                        NoteMarkdownContent(
                            content = note.content,
                            noteId = note.id,
                            onImageClick = onImageClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoListNoteContent(
    content: String,
    noteId: Int,
    onImageClick: (NoteImageReference) -> Unit,
    onItemCheckedChange: (Int, Boolean) -> Unit
) {
    var todoIndex = 0
    val textBuffer = remember { StringBuilder() }
    fun flushTextBuffer(flushed: MutableList<@Composable () -> Unit>) {
        val chunk = textBuffer.toString().trim()
        if (chunk.isNotBlank()) {
            flushed += {
                MarkdownText(
                    text = chunk,
                    textColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        textBuffer.clear()
    }
    val rows = mutableListOf<@Composable () -> Unit>()
    content.lines().forEach { line ->
        val trimmed = line.trim()
        val todoMatch = TODO_NOTE_PATTERN.matchEntire(trimmed)
        val imageReference = parseNoteImageReference(trimmed, noteId)
        when {
            todoMatch != null -> {
                flushTextBuffer(rows)
                todoIndex += 1
                val itemIndex = todoIndex
                val checked = todoMatch.groupValues[1].equals("x", ignoreCase = true)
                val itemText = todoMatch.groupValues[2].trim()
                rows += {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { nextChecked -> onItemCheckedChange(itemIndex, nextChecked) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MarkdownText(
                            text = itemText,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            imageReference != null -> {
                flushTextBuffer(rows)
                rows += {
                    NoteImageBlock(
                        imageReference = imageReference,
                        onClick = { onImageClick(imageReference) }
                    )
                }
            }
            else -> {
                textBuffer.appendLine(line)
            }
        }
    }
    flushTextBuffer(rows)
    if (rows.isEmpty()) {
        NoteMarkdownContent(content = content, noteId = noteId, onImageClick = onImageClick)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { it() }
    }
}

private data class NoteImageReference(
    val noteId: Int,
    val altText: String,
    val path: String,
    val markdownLine: String
)

private val NOTE_IMAGE_PATTERN = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
private val TODO_NOTE_PATTERN = Regex("""^[-*]\s+\[([ xX])]\s+(.+)$""")

@Composable
private fun NoteMarkdownContent(
    content: String,
    noteId: Int,
    onImageClick: (NoteImageReference) -> Unit
) {
    val blocks = remember(content, noteId) { splitNoteContentBlocks(content, noteId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteContentBlock.Text -> MarkdownText(
                    text = block.text,
                    textColor = MaterialTheme.colorScheme.onSurface
                )
                is NoteContentBlock.Image -> NoteImageBlock(
                    imageReference = block.image,
                    onClick = { onImageClick(block.image) }
                )
            }
        }
    }
}

@Composable
private fun NoteImageBlock(
    imageReference: NoteImageReference,
    onClick: () -> Unit
) {
    val file = remember(imageReference.path) { File(imageReference.path.removePrefix("file://")) }
    val bitmap = remember(imageReference.path, file.lastModified()) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 360.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = imageReference.altText.ifBlank { stringResource(R.string.notes_image_content_description) },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.notes_image_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteImageDialog(
    imageReference: NoteImageReference,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val file = remember(imageReference.path) { File(imageReference.path.removePrefix("file://")) }
    val bitmap = remember(imageReference.path, file.lastModified()) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            imageReference.altText.ifBlank { stringResource(R.string.notes_image_title) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                }

                Card(shape = RoundedCornerShape(14.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = imageReference.altText,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Text(
                                stringResource(R.string.notes_image_missing),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class NoteContentBlock {
    data class Text(val text: String) : NoteContentBlock()
    data class Image(val image: NoteImageReference) : NoteContentBlock()
}

private fun splitNoteContentBlocks(content: String, noteId: Int): List<NoteContentBlock> {
    val blocks = mutableListOf<NoteContentBlock>()
    val buffer = StringBuilder()
    fun flushText() {
        val text = buffer.toString().trim()
        if (text.isNotBlank()) blocks += NoteContentBlock.Text(text)
        buffer.clear()
    }
    content.lines().forEach { line ->
        val image = parseNoteImageReference(line.trim(), noteId)
        if (image != null) {
            flushText()
            blocks += NoteContentBlock.Image(image)
        } else {
            buffer.appendLine(line)
        }
    }
    flushText()
    if (blocks.isEmpty() && content.isNotBlank()) {
        blocks += NoteContentBlock.Text(content)
    }
    return blocks
}

private fun parseNoteImageReference(line: String, noteId: Int): NoteImageReference? {
    val match = NOTE_IMAGE_PATTERN.find(line) ?: return null
    val path = match.groupValues[2].trim()
    if (path.isBlank()) return null
    return NoteImageReference(
        noteId = noteId,
        altText = match.groupValues[1].trim(),
        path = path,
        markdownLine = match.value
    )
}

private fun removeImageReferenceFromMarkdown(content: String, imageReference: NoteImageReference): String {
    return content.lines()
        .filterNot { line ->
            parseNoteImageReference(line.trim(), imageReference.noteId)?.path == imageReference.path
        }
        .joinToString("\n")
        .trim()
}

private fun updateTodoCheckedStateInMarkdown(content: String, itemIndex: Int, checked: Boolean): String {
    if (itemIndex <= 0) return content
    var seen = 0
    return content.lines().joinToString("\n") { line ->
        val match = TODO_NOTE_PATTERN.matchEntire(line.trim())
        if (match == null) {
            line
        } else {
            seen += 1
            if (seen == itemIndex) {
                val indent = line.takeWhile { it.isWhitespace() }
                "$indent- [${if (checked) "x" else " "}] ${match.groupValues[2].trim()}"
            } else {
                line
            }
        }
    }
}

private fun NoteEntity.toExportPayload(): NoteExportPayload = NoteExportPayload(
    title = title,
    content = content,
    type = type.name,
    sourceFile = sourceFile,
    language = language,
    audioPath = audioPath,
    isLlmWhitelisted = isLlmWhitelisted
)

private fun parseNotesImportPayload(
    json: String,
    gson: Gson,
    chatSourceLabel: String,
    defaultNoteTitle: String,
    unknownFormatMessage: String,
    systemLabel: String,
    imageLabel: String,
    audioLabel: String
): List<NoteEntity> {
    val obj = JSONObject(json)
    val now = System.currentTimeMillis()
    return when {
        obj.has("messages") -> {
            val payload = gson.fromJson(json, LlamaChatExportPayload::class.java)
            listOf(
                NoteEntity(
                    title = payload.title.ifBlank { defaultNoteTitle },
                    content = chatPayloadToNoteMarkdown(payload, systemLabel, imageLabel, audioLabel),
                    type = NoteType.MANUAL,
                    sourceFile = chatSourceLabel,
                    createdAt = now,
                    updatedAt = now,
                    isLlmWhitelisted = false
                )
            )
        }
        obj.has("notes") -> {
            gson.fromJson(json, NotesExportPayload::class.java)
                .notes
                .map { it.toNoteEntity(now, defaultNoteTitle) }
        }
        obj.has("content") -> {
            listOf(gson.fromJson(json, NoteExportPayload::class.java).toNoteEntity(now, defaultNoteTitle))
        }
        else -> error(unknownFormatMessage)
    }
}

private fun NoteExportPayload.toNoteEntity(now: Long, defaultTitle: String): NoteEntity {
    val noteType = runCatching {
        NoteType.valueOf(type.uppercase(Locale.US))
    }.getOrDefault(NoteType.MANUAL)
    return NoteEntity(
        title = title.ifBlank { defaultTitle },
        content = content,
        type = noteType,
        sourceFile = sourceFile,
        language = language,
        audioPath = audioPath,
        createdAt = now,
        updatedAt = now,
        isLlmWhitelisted = isLlmWhitelisted
    )
}

private fun chatPayloadToNoteMarkdown(
    payload: LlamaChatExportPayload,
    systemLabel: String,
    imageLabel: String,
    audioLabel: String
): String {
    return buildString {
        payload.systemPrompt?.takeIf { it.isNotBlank() }?.let {
            append("## ")
            append(systemLabel)
            append("\n\n")
            append(it.trim())
            append("\n\n")
        }
        payload.messages.forEach { message ->
            append("## ")
            append(message.role.replaceFirstChar { it.titlecase(Locale.getDefault()) })
            append("\n\n")
            append(message.content.trim())
            message.imagePath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(imageLabel).append(": ").append(it) }
            message.audioPath?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(audioLabel).append(": ").append(it) }
            append("\n\n")
        }
    }.trim()
}

private fun notesExportFileName(notes: List<NoteEntity>): String {
    val baseName = if (notes.size == 1) notes.first().title else "notes_${notes.size}"
    return safeNoteTransferFileName(baseName) + ".json"
}

private fun safeNoteTransferFileName(name: String): String {
    return name
        .ifBlank { "note" }
        .replace(Regex("""[\\/:*?"<>|]+"""), "_")
        .take(60)
}

private fun shareNoteImage(
    context: android.content.Context,
    imageReference: NoteImageReference,
    onFailure: (String) -> Unit
) {
    runCatching {
        val file = File(imageReference.path.removePrefix("file://"))
        require(file.isFile) { context.getString(R.string.notes_image_missing) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.imagegen_share_chooser)))
    }.onFailure { error ->
        onFailure(context.getString(R.string.notes_image_share_failed, error.message ?: context.getString(R.string.error_generic)))
    }
}

private fun deleteNoteImageFileIfLocal(path: String) {
    val file = File(path.removePrefix("file://"))
    if (!file.isFile) return
    if (file.absolutePath.contains("/onnx_image_output/")) {
        OnnxStorage.deleteImageWithMetadata(file)
    }
}

private fun OrganizerEventEntity.occursOn(date: LocalDate, zone: ZoneId): Boolean {
    val start = Instant.ofEpochMilli(startAtMillis).atZone(zone).toLocalDate()
    val end = (endAtMillis ?: startAtMillis).let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    return !date.isBefore(start) && !date.isAfter(end)
}

private fun organizerMonthCells(month: YearMonth): List<LocalDate?> {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val cells = MutableList<LocalDate?>(leading) { null }
    for (day in 1..month.lengthOfMonth()) {
        cells += month.atDay(day)
    }
    while (cells.size % 7 != 0) cells += null
    return cells
}

private fun organizerWeekdayLabels(): List<String> {
    val formatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    val monday = LocalDate.of(2026, 4, 27)
    return (0..6).map { monday.plusDays(it.toLong()).format(formatter) }
}

private fun organizerEventTimeLabel(event: OrganizerEventEntity): String {
    val start = formatOrganizerUiDateTime(event.startAtMillis)
    val end = event.endAtMillis?.let(::formatOrganizerUiDateTime)
    return if (end == null) start else "$start - $end"
}

private fun organizerAlarmTimeLabel(alarm: OrganizerAlarmEntity): String =
    formatOrganizerUiDateTime(alarm.triggerAtMillis)

private fun formatOrganizerUiDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US))

private fun organizerUiDateTimeText(date: LocalDate, time: LocalTime): String =
    LocalDateTime.of(date, time.withSecond(0).withNano(0))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US))

private fun organizerUiDateLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))

private fun organizerUiTimeLabel(time: LocalTime): String =
    time.withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

private fun parseOrganizerUiDateTime(
    value: String,
    requiredMessage: String,
    formatMessage: String
): ZonedDateTime {
    val trimmed = value.trim()
    require(trimmed.isNotBlank()) { requiredMessage }
    val zone = ZoneId.systemDefault()
    return runCatching { ZonedDateTime.parse(trimmed) }.getOrNull()
        ?: runCatching { Instant.parse(trimmed).atZone(zone) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(trimmed).atZone(zone) }.getOrNull()
        ?: runCatching { LocalDate.parse(trimmed).atStartOfDay(zone) }.getOrNull()
        ?: throw IllegalArgumentException(formatMessage)
}

private fun parseOrganizerUiColor(value: String, formatMessage: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val hex = trimmed.removePrefix("#")
    require(hex.length == 6 || hex.length == 8) { formatMessage }
    return hex.toLong(16).let { raw ->
        if (hex.length == 6) 0xFF000000L or raw else raw
    }
}
