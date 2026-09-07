package com.example.llamadroid.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val NOTE_WIDGET_PREFS_NAME = "note_display_widgets"
private const val PREF_NOTE_PREFIX = "note_"
private const val ACTION_NOTE_TOGGLE_TODO = "com.example.llamadroid.widget.NOTE_TOGGLE_TODO"
private const val EXTRA_NOTE_ID = "extra_note_id"
private const val EXTRA_NOTE_TODO_INDEX = "extra_note_todo_index"
private const val EXTRA_NOTE_TODO_CHECKED = "extra_note_todo_checked"

private object NoteWidgetIntents {
    private const val REQUEST_OPEN_BASE = 500_000
    private const val REQUEST_CONFIG_BASE = 600_000
    private const val REQUEST_TOGGLE_BASE = 700_000

    fun openOrganizer(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.NotesManager.route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_BASE + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun configure(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, NoteDisplayWidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONFIG_BASE + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun toggleTemplate(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, NoteDisplayWidgetProvider::class.java).apply {
            action = ACTION_NOTE_TOGGLE_TODO
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_TOGGLE_BASE + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}

private object NoteDisplayWidgetPrefs {
    fun setNoteId(context: Context, appWidgetId: Int, noteId: Int) {
        context.getSharedPreferences(NOTE_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("$PREF_NOTE_PREFIX$appWidgetId", noteId)
            .apply()
    }

    fun getNoteId(context: Context, appWidgetId: Int): Int? {
        val prefs = context.getSharedPreferences(NOTE_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$PREF_NOTE_PREFIX$appWidgetId"
        return if (prefs.contains(key)) prefs.getInt(key, -1).takeIf { it > 0 } else null
    }

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(NOTE_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$PREF_NOTE_PREFIX$appWidgetId")
            .apply()
    }
}

class NoteDisplayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context.applicationContext, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NOTE_TOGGLE_TODO) {
            val pendingResult = goAsync()
            handleTodoToggle(context.applicationContext, intent, pendingResult)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { NoteDisplayWidgetPrefs.remove(context, it) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NoteDisplayWidgetProvider::class.java))
            ids.forEach { updateWidget(context.applicationContext, manager, it) }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            scope.launch {
                val noteId = NoteDisplayWidgetPrefs.getNoteId(context, appWidgetId)
                val note = noteId?.let { AppDatabase.getDatabase(context).noteDao().getNoteById(it) }
                val views = RemoteViews(context.packageName, R.layout.widget_note_display)
                val hasConfiguredNote = noteId != null
                val hasLiveNote = note != null

                views.setTextViewText(
                    R.id.widget_note_title,
                    note?.title ?: context.getString(R.string.widget_note_title_fallback)
                )
                views.setTextViewText(
                    R.id.widget_note_empty,
                    when {
                        !hasConfiguredNote -> context.getString(R.string.widget_note_unconfigured_body)
                        !hasLiveNote -> context.getString(R.string.widget_note_missing_body)
                        note?.content?.isBlank() == true -> context.getString(R.string.widget_note_empty_body)
                        else -> context.getString(R.string.widget_note_empty_body)
                    }
                )
                views.setOnClickPendingIntent(
                    R.id.widget_note_title,
                    if (hasConfiguredNote) {
                        NoteWidgetIntents.openOrganizer(context, appWidgetId)
                    } else {
                        NoteWidgetIntents.configure(context, appWidgetId)
                    }
                )
                views.setOnClickPendingIntent(
                    R.id.widget_note_empty,
                    if (hasConfiguredNote) {
                        NoteWidgetIntents.openOrganizer(context, appWidgetId)
                    } else {
                        NoteWidgetIntents.configure(context, appWidgetId)
                    }
                )
                val adapterIntent = Intent(context, NoteDisplayWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse("llamadroid://note-widget/$appWidgetId/${noteId ?: 0}")
                }
                views.setRemoteAdapter(R.id.widget_note_list, adapterIntent)
                views.setEmptyView(R.id.widget_note_list, R.id.widget_note_empty)
                views.setPendingIntentTemplate(
                    R.id.widget_note_list,
                    NoteWidgetIntents.toggleTemplate(context, appWidgetId)
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_note_list)
            }
        }

        private fun handleTodoToggle(
            context: Context,
            intent: Intent,
            pendingResult: BroadcastReceiver.PendingResult
        ) {
            scope.launch {
                try {
                    val noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
                    val itemIndex = intent.getIntExtra(EXTRA_NOTE_TODO_INDEX, -1)
                    val checked = intent.getBooleanExtra(EXTRA_NOTE_TODO_CHECKED, false)
                    if (noteId > 0 && itemIndex > 0) {
                        val dao = AppDatabase.getDatabase(context).noteDao()
                        val note = dao.getNoteById(noteId)
                        if (note != null) {
                            val updatedContent = updateWidgetTodoCheckedState(
                                content = note.content,
                                itemIndex = itemIndex,
                                checked = checked
                            )
                            if (updatedContent != note.content) {
                                dao.update(
                                    note.copy(
                                        content = updatedContent,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    refreshAll(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

class NoteDisplayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NoteDisplayRemoteViewsFactory(applicationContext, intent)
}

private class NoteDisplayRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {
    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var noteId: Int? = null
    private var rows: List<NoteDisplayWidgetRow> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        noteId = NoteDisplayWidgetPrefs.getNoteId(context, appWidgetId)
        rows = runBlocking {
            noteId?.let { AppDatabase.getDatabase(context).noteDao().getNoteById(it) }
                ?.let { noteDisplayRowsForContent(it.content) }
                .orEmpty()
        }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: NoteDisplayWidgetRow(text = "")
        return if (row.todoIndex == null) {
            if (row.imagePath == null) {
                RemoteViews(context.packageName, R.layout.widget_note_text_item).apply {
                    setTextViewText(R.id.widget_note_text, row.text)
                }
            } else {
                RemoteViews(context.packageName, R.layout.widget_note_image_item).apply {
                    val imageLabel = row.text.ifBlank {
                        context.getString(R.string.widget_note_image_fallback)
                    }
                    val bitmap = decodeNoteWidgetImage(row.imagePath)
                    if (bitmap == null) {
                        setViewVisibility(R.id.widget_note_image, View.GONE)
                        setTextViewText(
                            R.id.widget_note_image_caption,
                            context.getString(R.string.widget_note_image_missing, imageLabel)
                        )
                    } else {
                        setViewVisibility(R.id.widget_note_image, View.VISIBLE)
                        setImageViewBitmap(R.id.widget_note_image, bitmap)
                        setTextViewText(
                            R.id.widget_note_image_caption,
                            context.getString(R.string.widget_note_image_label, imageLabel)
                        )
                    }
                }
            }
        } else {
            RemoteViews(context.packageName, R.layout.widget_note_todo_item).apply {
                setTextViewText(R.id.widget_note_todo_text, row.text)
                setTextViewText(
                    R.id.widget_note_checkbox,
                    context.getString(
                        if (row.checked) {
                            R.string.widget_note_todo_checked_marker
                        } else {
                            R.string.widget_note_todo_unchecked_marker
                        }
                    )
                )
                setViewVisibility(R.id.widget_note_checkbox, View.VISIBLE)
                setInt(
                    R.id.widget_note_row_root,
                    "setBackgroundResource",
                    if (row.checked) {
                        R.drawable.widget_note_todo_checked_bubble
                    } else {
                        R.drawable.widget_note_todo_bubble
                    }
                )
                val fillInIntent = Intent().apply {
                    putExtra(EXTRA_NOTE_ID, noteId ?: -1)
                    putExtra(EXTRA_NOTE_TODO_INDEX, row.todoIndex)
                    putExtra(EXTRA_NOTE_TODO_CHECKED, !row.checked)
                }
                setOnClickFillInIntent(R.id.widget_note_row_root, fillInIntent)
                setOnClickFillInIntent(R.id.widget_note_checkbox, fillInIntent)
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 3
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}

class NoteDisplayWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LlamaApplication.updateLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val database = AppDatabase.getDatabase(applicationContext)
        setContent {
            val appearanceSettings = remember { SettingsRepository(applicationContext) }
            val themeMode = appearanceSettings.themeMode.collectAsState().value
            val dynamicColor = appearanceSettings.dynamicColor.collectAsState().value
            LlamaDroidTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                val notes by database.noteDao().getAllNotes().collectAsState(initial = emptyList())
                NoteDisplayWidgetConfigScreen(
                    notes = notes,
                    onCancel = { finish() },
                    onSelectNote = { note ->
                        NoteDisplayWidgetPrefs.setNoteId(applicationContext, appWidgetId, note.id)
                        NoteDisplayWidgetProvider.updateWidget(
                            applicationContext,
                            AppWidgetManager.getInstance(applicationContext),
                            appWidgetId
                        )
                        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun NoteDisplayWidgetConfigScreen(
    notes: List<NoteEntity>,
    onCancel: () -> Unit,
    onSelectNote: (NoteEntity) -> Unit
) {
    AppScreenScaffold(
        title = androidx.compose.ui.res.stringResource(R.string.widget_note_config_title),
        subtitle = androidx.compose.ui.res.stringResource(R.string.widget_note_config_desc),
        onBack = onCancel
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (notes.isEmpty()) {
                item {
                    AppSectionCard {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.widget_note_config_empty),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedButton(onClick = onCancel) {
                            Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                        }
                    }
                }
            } else {
                items(notes, key = { it.id }) { note ->
                    NoteDisplayWidgetConfigNoteRow(
                        note = note,
                        onSelect = { onSelectNote(note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteDisplayWidgetConfigNoteRow(
    note: NoteEntity,
    onSelect: () -> Unit
) {
    AppSectionCard(
        modifier = Modifier.clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = noteWidgetPreview(note.content)
                        .ifBlank { androidx.compose.ui.res.stringResource(R.string.widget_note_empty_body) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onSelect) {
                Text(androidx.compose.ui.res.stringResource(R.string.widget_note_config_select))
            }
        }
    }
}

private data class NoteDisplayWidgetRow(
    val text: String,
    val imagePath: String? = null,
    val todoIndex: Int? = null,
    val checked: Boolean = false
)

private fun noteDisplayRowsForContent(content: String): List<NoteDisplayWidgetRow> {
    var todoIndex = 0
    return content.lines().flatMap { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) {
            emptyList()
        } else {
            val todoMatch = NOTE_WIDGET_TODO_PATTERN.matchEntire(line)
            val imageMatch = NOTE_WIDGET_IMAGE_PATTERN.find(line)
            if (todoMatch != null) {
                todoIndex += 1
                listOf(
                    NoteDisplayWidgetRow(
                        text = todoMatch.groupValues[2].trim(),
                        todoIndex = todoIndex,
                        checked = todoMatch.groupValues[1].equals("x", ignoreCase = true)
                    )
                )
            } else if (imageMatch != null) {
                val imagePath = imageMatch.groupValues[2].trim()
                val label = imageMatch.groupValues[1].trim()
                    .ifBlank { File(imagePath.removePrefix("file://")).name }
                    .ifBlank { imagePath }
                listOf(NoteDisplayWidgetRow(text = label, imagePath = imagePath))
            } else {
                splitNoteWidgetLine(line).map { NoteDisplayWidgetRow(text = it) }
            }
        }
    }
}

private fun splitNoteWidgetLine(line: String): List<String> {
    if (line.length <= 320) return listOf(line)
    return line.chunked(320)
}

private fun updateWidgetTodoCheckedState(content: String, itemIndex: Int, checked: Boolean): String {
    if (itemIndex <= 0) return content
    var seen = 0
    return content.lines().joinToString("\n") { line ->
        val match = NOTE_WIDGET_TODO_PATTERN.matchEntire(line.trim())
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

private fun noteWidgetPreview(content: String): String =
    content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" ")
        .let { if (it.length > 180) it.take(177).trimEnd() + "..." else it }

private fun decodeNoteWidgetImage(path: String): android.graphics.Bitmap? {
    val file = File(path.removePrefix("file://"))
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 640 || bounds.outHeight / sampleSize > 640) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}

private val NOTE_WIDGET_IMAGE_PATTERN = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
private val NOTE_WIDGET_TODO_PATTERN = Regex("""^[-*]\s+\[([ xX])]\s+(.+)$""")
