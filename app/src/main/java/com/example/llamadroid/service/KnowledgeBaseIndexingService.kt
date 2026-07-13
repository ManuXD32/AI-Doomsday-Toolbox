package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.KnowledgeBaseIndexProgress
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class KnowledgeBaseIndexingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskTitle = getString(R.string.kb_notification_title)
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.KNOWLEDGE_BASE,
            taskTitle
        )
        startForeground(taskId, notification)
        UnifiedNotificationManager.updateProgress(taskId, 0f, getString(R.string.kb_notification_starting))
        // Register the start before launching work so back-to-back queued intents cannot
        // briefly look idle and stop the shared embedding server between jobs.
        if (activeIndexingJobs.getAndIncrement() == 0) {
            completedIndexingJobs.set(0)
        }
        val notificationProgress = IndexingNotificationProgress(taskId)

        serviceScope.launch {
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = KnowledgeBaseRepository(
                applicationContext,
                database,
                progressReporter = notificationProgress::onSourceProgress
            )

            try {
                runCatching {
                    indexingMutex.withLock {
                        when (intent?.action) {
                            ACTION_IMPORT_FILE -> {
                                val baseId = intent.getLongExtra(EXTRA_BASE_ID, 0L)
                                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                                    ?: error(getString(R.string.kb_error_missing_file))
                                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                                KnowledgeBaseDiagnostics.log("Queued file import '$title' for knowledge base $baseId.")
                                notificationProgress.beginSource(title, getString(R.string.kb_notification_extracting, title))
                                repository.importFile(baseId, uri, title)
                            }
                            ACTION_IMPORT_QUEUED_FILE -> {
                                val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
                                val source = database.knowledgeBaseDao().getSource(sourceId)
                                if (source == null) {
                                    KnowledgeBaseDiagnostics.log(getString(R.string.kb_log_queued_source_missing, sourceId))
                                } else {
                                    KnowledgeBaseDiagnostics.log(getString(R.string.kb_log_starting_queued_file_import, source.title, source.knowledgeBaseId))
                                    notificationProgress.beginSource(source.title, getString(R.string.kb_notification_extracting, source.title))
                                    repository.importQueuedFile(sourceId)
                                }
                            }
                            ACTION_IMPORT_NOTE -> {
                                val baseId = intent.getLongExtra(EXTRA_BASE_ID, 0L)
                                val noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
                                val note = database.noteDao().getNoteById(noteId)
                                    ?: error(getString(R.string.kb_error_missing_note))
                                KnowledgeBaseDiagnostics.log("Queued note import '${note.title}' for knowledge base $baseId.")
                                notificationProgress.beginSource(note.title, getString(R.string.kb_notification_extracting, note.title))
                                repository.importNote(baseId, note)
                            }
                            ACTION_IMPORT_WEB -> {
                                val baseId = intent.getLongExtra(EXTRA_BASE_ID, 0L)
                                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                                require(url.isNotBlank()) { getString(R.string.kb_error_missing_url) }
                                KnowledgeBaseDiagnostics.log("Queued web import '$url' for knowledge base $baseId.")
                                notificationProgress.beginSource(title ?: url.take(80), getString(R.string.kb_notification_fetching_url, url.take(80)))
                                repository.importWebUrl(baseId, url, title)
                            }
                            ACTION_REINDEX_SOURCE -> {
                                val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
                                KnowledgeBaseDiagnostics.log("Queued source reindex for source $sourceId.")
                                val source = database.knowledgeBaseDao().getSource(sourceId)
                                notificationProgress.beginSource(
                                    source?.title.orEmpty(),
                                    source?.title?.let { getString(R.string.kb_notification_reindexing_source, it) }
                                        ?: getString(R.string.kb_notification_reindexing)
                                )
                                repository.reindexSource(sourceId)
                            }
                            ACTION_RESUME_SOURCE -> {
                                val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
                                KnowledgeBaseDiagnostics.log("Queued embedding continuation for source $sourceId.")
                                val source = database.knowledgeBaseDao().getSource(sourceId)
                                notificationProgress.beginSource(
                                    source?.title.orEmpty(),
                                    source?.title?.let { getString(R.string.kb_notification_continuing_source, it) }
                                        ?: getString(R.string.kb_notification_continuing)
                                )
                                repository.resumeSourceEmbeddings(sourceId)
                            }
                            ACTION_REINDEX_BASE -> {
                                val baseId = intent.getLongExtra(EXTRA_BASE_ID, 0L)
                                val sources = database.knowledgeBaseDao().getSourcesOnce(baseId)
                                KnowledgeBaseDiagnostics.log("Queued folder reindex for knowledge base $baseId (${sources.size} sources).")
                                sources.forEachIndexed { index, source ->
                                    notificationProgress.beginQueuedSource(
                                        index = index + 1,
                                        total = sources.size,
                                        title = source.title,
                                        phaseText = getString(R.string.kb_notification_reindexing_source, source.title)
                                    )
                                    repository.reindexSource(source.id)
                                }
                            }
                            ACTION_RESUME_BASE -> {
                                val baseId = intent.getLongExtra(EXTRA_BASE_ID, 0L)
                                val sources = database.knowledgeBaseDao().getSourcesOnce(baseId)
                                KnowledgeBaseDiagnostics.log("Queued folder embedding continuation for knowledge base $baseId (${sources.size} sources).")
                                sources.forEachIndexed { index, source ->
                                    notificationProgress.beginQueuedSource(
                                        index = index + 1,
                                        total = sources.size,
                                        title = source.title,
                                        phaseText = getString(R.string.kb_notification_continuing_source, source.title)
                                    )
                                    repository.resumeSourceEmbeddings(source.id)
                                }
                            }
                            else -> error(getString(R.string.kb_action_failed))
                        }
                        repository.repairAllSourceProgress()
                    }
                }.onSuccess {
                    KnowledgeBaseDiagnostics.log("Knowledge-base indexing job finished.")
                    UnifiedNotificationManager.completeTask(taskId, getString(R.string.kb_notification_complete))
                }.onFailure { error ->
                    val detail = error.knowledgeBaseFailureDetail()
                    KnowledgeBaseDiagnostics.log("Knowledge-base indexing job failed: $detail.")
                    DebugLog.log("[KnowledgeBaseIndexingService] Failed: $detail")
                    UnifiedNotificationManager.failTask(taskId, detail)
                }
            } finally {
                completedIndexingJobs.incrementAndGet()
                if (activeIndexingJobs.decrementAndGet() == 0) {
                    runCatching {
                        indexingMutex.withLock {
                            if (activeIndexingJobs.get() == 0) {
                                repository.stopManagedEmbeddingServer("indexing-idle")
                            }
                        }
                    }.onFailure { error ->
                        DebugLog.log("[KnowledgeBaseIndexingService] Server stop failed: ${error.message}")
                    }
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private inner class IndexingNotificationProgress(private val taskId: Int) {
        private var queueIndex: Int = 1
        private var queueTotal: Int = 1
        private var sourceTitle: String = ""
        private var phaseText: String = getString(R.string.kb_notification_starting)
        private var sourceDone: Int = 0
        private var sourceTotal: Int = 0

        fun beginSource(title: String, phase: String) {
            val total = (activeIndexingJobs.get() + completedIndexingJobs.get()).coerceAtLeast(1)
            beginQueuedSource(
                index = (completedIndexingJobs.get() + 1).coerceIn(1, total),
                total = total,
                title = title,
                phaseText = phase
            )
        }

        fun beginQueuedSource(index: Int, total: Int, title: String, phaseText: String) {
            queueIndex = index.coerceAtLeast(1)
            queueTotal = total.coerceAtLeast(queueIndex).coerceAtLeast(1)
            sourceTitle = title
            this.phaseText = phaseText
            sourceDone = 0
            sourceTotal = 0
            publish()
        }

        fun onSourceProgress(progress: KnowledgeBaseIndexProgress) {
            if (progress.sourceTitle.isNotBlank()) {
                sourceTitle = progress.sourceTitle
            }
            sourceDone = progress.done
            sourceTotal = progress.total
            publish()
        }

        private fun publish() {
            val sourceFraction = if (sourceTotal > 0) {
                sourceDone.toFloat() / sourceTotal.toFloat()
            } else {
                0f
            }.coerceIn(0f, 1f)
            val totalFraction = ((queueIndex - 1).toFloat() + sourceFraction) / queueTotal.toFloat()
            val sourcePercent = (sourceFraction * 100f).toInt().coerceIn(0, 100)
            val queuePercent = (totalFraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
            val displayTitle = sourceTitle.ifBlank { phaseText }
            UnifiedNotificationManager.updateProgressWithDetails(
                taskId,
                totalFraction.coerceIn(0f, 1f),
                phaseText,
                listOf(
                    getString(
                        R.string.kb_notification_current_source_progress,
                        displayTitle,
                        sourcePercent,
                        sourceDone.coerceAtLeast(0),
                        sourceTotal.coerceAtLeast(0)
                    ),
                    getString(
                        R.string.kb_notification_total_queue_progress,
                        queuePercent,
                        queueIndex,
                        queueTotal
                    )
                )
            )
        }
    }

    companion object {
        private const val ACTION_IMPORT_FILE = "com.example.llamadroid.KB_IMPORT_FILE"
        private const val ACTION_IMPORT_QUEUED_FILE = "com.example.llamadroid.KB_IMPORT_QUEUED_FILE"
        private const val ACTION_IMPORT_NOTE = "com.example.llamadroid.KB_IMPORT_NOTE"
        private const val ACTION_IMPORT_WEB = "com.example.llamadroid.KB_IMPORT_WEB"
        private const val ACTION_REINDEX_SOURCE = "com.example.llamadroid.KB_REINDEX_SOURCE"
        private const val ACTION_REINDEX_BASE = "com.example.llamadroid.KB_REINDEX_BASE"
        private const val ACTION_RESUME_SOURCE = "com.example.llamadroid.KB_RESUME_SOURCE"
        private const val ACTION_RESUME_BASE = "com.example.llamadroid.KB_RESUME_BASE"
        private const val EXTRA_BASE_ID = "base_id"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_NOTE_ID = "note_id"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private val indexingMutex = Mutex()
        private val activeIndexingJobs = AtomicInteger(0)
        private val completedIndexingJobs = AtomicInteger(0)

        fun enqueueFile(context: Context, knowledgeBaseId: Long, uri: Uri, title: String) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_IMPORT_FILE
                    putExtra(EXTRA_BASE_ID, knowledgeBaseId)
                    putExtra(EXTRA_URI, uri.toString())
                    putExtra(EXTRA_TITLE, title)
                }
            )
        }

        fun enqueueQueuedFile(context: Context, sourceId: Long) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_IMPORT_QUEUED_FILE
                    putExtra(EXTRA_SOURCE_ID, sourceId)
                }
            )
        }

        fun enqueueNote(context: Context, knowledgeBaseId: Long, noteId: Int) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_IMPORT_NOTE
                    putExtra(EXTRA_BASE_ID, knowledgeBaseId)
                    putExtra(EXTRA_NOTE_ID, noteId)
                }
            )
        }

        fun enqueueWeb(context: Context, knowledgeBaseId: Long, url: String, title: String? = null) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_IMPORT_WEB
                    putExtra(EXTRA_BASE_ID, knowledgeBaseId)
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_TITLE, title.orEmpty())
                }
            )
        }

        fun enqueueReindexSource(context: Context, sourceId: Long) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_REINDEX_SOURCE
                    putExtra(EXTRA_SOURCE_ID, sourceId)
                }
            )
        }

        fun enqueueResumeSource(context: Context, sourceId: Long) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_RESUME_SOURCE
                    putExtra(EXTRA_SOURCE_ID, sourceId)
                }
            )
        }

        fun enqueueReindexBase(context: Context, knowledgeBaseId: Long) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_REINDEX_BASE
                    putExtra(EXTRA_BASE_ID, knowledgeBaseId)
                }
            )
        }

        fun enqueueResumeBase(context: Context, knowledgeBaseId: Long) {
            start(
                context,
                Intent(context, KnowledgeBaseIndexingService::class.java).apply {
                    action = ACTION_RESUME_BASE
                    putExtra(EXTRA_BASE_ID, knowledgeBaseId)
                }
            )
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

private fun Throwable.knowledgeBaseFailureDetail(): String {
    var current: Throwable = this
    while (current is java.lang.reflect.InvocationTargetException) {
        current = current.targetException ?: current.cause ?: break
    }
    return current.message?.takeIf { it.isNotBlank() }
        ?: current.cause?.message?.takeIf { it.isNotBlank() }
        ?: current::class.java.simpleName
}
