package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.KnowledgeBaseIndexProgress
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DeepResearchJobResult(
    val query: String,
    val knowledgeBaseId: Long,
    val importedSources: Int,
    val sourceLimit: Int,
    val usedExistingKnowledgeBase: Boolean = false
)

private data class DeepResearchScoredSource(
    val source: DeepResearchFetchedSource,
    val score: DeepResearchScore
)

class DeepResearchService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerLockGuard = Any()
    private var powerLocksHeld = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.KNOWLEDGE_BASE,
            getString(R.string.deep_research_notification_title)
        )
        startForeground(taskId, notification)
        acquirePowerLocks()
        UnifiedNotificationManager.updateProgress(taskId, 0f, getString(R.string.deep_research_progress_starting))

        serviceScope.launch {
            val jobId = intent?.getStringExtra(EXTRA_JOB_ID).orEmpty()
            val chatId = intent?.getLongExtra(EXTRA_CHAT_ID, 0L) ?: 0L
            val query = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
            val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
            val focus = intent?.getStringExtra(EXTRA_FOCUS).orEmpty()
            val sourceLimit = DeepResearchSupport.normalizeSourceLimit(
                intent?.getIntExtra(EXTRA_SOURCE_LIMIT, DeepResearchSupport.DEFAULT_SOURCE_LIMIT)
            )
            val targetKnowledgeBaseId = intent?.getLongExtra(EXTRA_TARGET_KNOWLEDGE_BASE_ID, 0L)
                ?.takeIf { it > 0L }

            val progress = DeepResearchNotificationProgress(taskId, sourceLimit)
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = KnowledgeBaseRepository(
                applicationContext,
                database,
                progressReporter = progress::onSourceProgress
            )
            var createdKnowledgeBaseId: Long? = null
            var importedSourceCount = 0
            runCatching {
                require(chatId > 0L) { "A chat id is required for Deep Research." }
                require(query.isNotBlank()) { "A research query is required." }
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                val kbId = targetKnowledgeBaseId ?: createResearchKnowledgeBase(repository, query, title, chatId)
                if (targetKnowledgeBaseId == null) {
                    createdKnowledgeBaseId = kbId
                } else {
                    KnowledgeBaseDiagnostics.log("Deep Research will import new sources into selected knowledge base #$kbId for chat $chatId.")
                }
                val imported = runResearch(repository, kbId, query, focus, sourceLimit, taskId, progress)
                importedSourceCount = imported
                if (imported <= 0) {
                    if (targetKnowledgeBaseId == null) {
                        repository.deleteKnowledgeBase(kbId)
                        createdKnowledgeBaseId = null
                    }
                    error(getString(R.string.deep_research_error_no_sources_imported))
                }
                selectKnowledgeBaseForChat(database, chatId, kbId)
                KnowledgeBaseDiagnostics.log(
                    "Deep Research finished for chat $chatId: imported $imported sources (maximum $sourceLimit) into " +
                        if (targetKnowledgeBaseId == null) "knowledge base $kbId." else "selected knowledge base $kbId."
                )
                repository.repairAllSourceProgress()
                UnifiedNotificationManager.completeTask(
                    taskId,
                    getString(R.string.deep_research_notification_complete, imported)
                )
                completeAwaitingJob(
                    jobId,
                    Result.success(
                        DeepResearchJobResult(
                            query = query,
                            knowledgeBaseId = kbId,
                            importedSources = imported,
                            sourceLimit = sourceLimit,
                            usedExistingKnowledgeBase = targetKnowledgeBaseId != null
                        )
                    )
                )
            }.onFailure { error ->
                if (importedSourceCount == 0) {
                    createdKnowledgeBaseId?.let { kbId ->
                        runCatching {
                            repository.deleteKnowledgeBase(kbId)
                            KnowledgeBaseDiagnostics.log("Deep Research removed empty knowledge base #$kbId after failure.")
                        }
                    }
                }
                KnowledgeBaseDiagnostics.log("Deep Research failed: ${error.message ?: error::class.java.simpleName}.")
                DebugLog.log("[DeepResearchService] Failed: ${error.message}")
                UnifiedNotificationManager.failTask(taskId, error.message ?: getString(R.string.deep_research_error_failed))
                completeAwaitingJob(jobId, Result.failure(error))
            }
            runCatching { repository.stopManagedEmbeddingServer("deep-research-idle") }
            releasePowerLocks()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePowerLocks()
        super.onDestroy()
    }

    private fun acquirePowerLocks() {
        synchronized(powerLockGuard) {
            if (powerLocksHeld) return
            WakeLockManager.acquire(applicationContext, "DeepResearchService")
            WakeLockManager.acquireWifiLock(applicationContext, "DeepResearchService")
            powerLocksHeld = true
        }
    }

    private fun releasePowerLocks() {
        synchronized(powerLockGuard) {
            if (!powerLocksHeld) return
            WakeLockManager.release("DeepResearchService")
            WakeLockManager.releaseWifiLock("DeepResearchService")
            powerLocksHeld = false
        }
    }

    private suspend fun createResearchKnowledgeBase(
        repository: KnowledgeBaseRepository,
        query: String,
        title: String,
        chatId: Long
    ): Long {
        val topic = title.ifBlank { query }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(54)
            .ifBlank { "Research" }
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val name = uniqueKnowledgeBaseName(repository, "Deep Research - $topic - $date")
        val description = "Created by Deep Research from chat $chatId.\nQuery: ${query.trim()}"
        val kbId = repository.createKnowledgeBase(name, description)
        KnowledgeBaseDiagnostics.log("Deep Research created knowledge base '$name' (#$kbId).")
        return kbId
    }

    private suspend fun uniqueKnowledgeBaseName(repository: KnowledgeBaseRepository, baseName: String): String {
        val existing = repository.getKnowledgeBasesOnce().map { it.name }.toSet()
        if (baseName !in existing) return baseName
        var suffix = 2
        while ("$baseName ($suffix)" in existing) suffix += 1
        return "$baseName ($suffix)"
    }

    private suspend fun runResearch(
        repository: KnowledgeBaseRepository,
        knowledgeBaseId: Long,
        query: String,
        focus: String,
        sourceLimit: Int,
        taskId: Int,
        progress: DeepResearchNotificationProgress
    ): Int {
        val client = DeepResearchSupport.defaultClient()
        val seenUrls = mutableSetOf<String>()
        val scoredSources = mutableListOf<DeepResearchScoredSource>()
        var roundsWithoutUsefulCandidates = 0
        val variants = DeepResearchSupport.buildQueryVariants(query, focus.takeIf { it.isNotBlank() }, sourceLimit)
        val minimumRoundsBeforeStaleStop = (sourceLimit / 5).coerceAtLeast(6)
        val directResults = DeepResearchSupport.extractHttpUrls("$query $focus")
            .map { url ->
                DeepResearchSearchResult(
                    title = DeepResearchSupport.titleFromUrl(url),
                    url = url,
                    query = query
                )
            }
        if (directResults.isNotEmpty()) {
            KnowledgeBaseDiagnostics.log("Deep Research found ${directResults.size} direct URLs in the request.")
            scoredSources += collectScoredCandidates(
                query = query,
                candidates = directResults,
                seenUrls = seenUrls,
                client = client,
                taskId = taskId,
                progressBase = 0.03f,
                desiredCandidatePool = sourceLimit
            )
        }
        for ((roundIndex, variant) in variants.withIndex()) {
            if (scoredSources.count { DeepResearchSupport.shouldImportScore(it.score) } >= sourceLimit) break
            if (roundsWithoutUsefulCandidates >= 3 && roundIndex >= minimumRoundsBeforeStaleStop) break
            val progressBase = (roundIndex.toFloat() / variants.size.toFloat()).coerceIn(0.05f, 0.85f)
            UnifiedNotificationManager.updateProgress(
                taskId,
                progressBase,
                getString(R.string.deep_research_progress_query_round, roundIndex + 1, variants.size, variant.take(80))
            )
            KnowledgeBaseDiagnostics.log("Deep Research query round ${roundIndex + 1}/${variants.size}: $variant")
            val results = DeepResearchSupport.searchWeb(
                client = client,
                query = variant,
                maxResults = DeepResearchSupport.maxResultsPerQuery(sourceLimit)
            )
            KnowledgeBaseDiagnostics.log("Deep Research found ${results.size} candidates for '$variant'.")
            val collectedThisRound = collectScoredCandidates(
                query = query,
                candidates = results,
                seenUrls = seenUrls,
                client = client,
                taskId = taskId,
                progressBase = progressBase,
                desiredCandidatePool = (sourceLimit * 2).coerceAtLeast(sourceLimit)
            )
            val usefulThisRound = collectedThisRound.count { DeepResearchSupport.shouldImportScore(it.score) }
            scoredSources += collectedThisRound
            roundsWithoutUsefulCandidates = if (usefulThisRound == 0) roundsWithoutUsefulCandidates + 1 else 0
        }
        val importable = scoredSources
            .filter { DeepResearchSupport.shouldImportScore(it.score) }
            .distinctBy { DeepResearchSupport.normalizeUrl(it.source.finalUrl) }
            .sortedByDescending { it.score.score }
            .take(sourceLimit)
        val selectedSources = if (importable.isNotEmpty()) {
            importable
        } else {
            scoredSources
                .filter { DeepResearchSupport.shouldFallbackImportScore(it.score) }
                .distinctBy { DeepResearchSupport.normalizeUrl(it.source.finalUrl) }
                .sortedByDescending { it.score.score }
                .take(1)
        }
        if (selectedSources.isEmpty()) {
            KnowledgeBaseDiagnostics.log("Deep Research found no candidates above the import score floor after visiting ${seenUrls.size} unique URLs.")
            return 0
        }
        return importScoredSources(
            repository = repository,
            knowledgeBaseId = knowledgeBaseId,
            sources = selectedSources,
            sourceLimit = sourceLimit,
            progress = progress
        )
    }

    private suspend fun collectScoredCandidates(
        query: String,
        candidates: List<DeepResearchSearchResult>,
        seenUrls: MutableSet<String>,
        client: okhttp3.OkHttpClient,
        taskId: Int,
        progressBase: Float,
        desiredCandidatePool: Int
    ): List<DeepResearchScoredSource> {
        val scored = mutableListOf<DeepResearchScoredSource>()
        for ((candidateIndex, candidate) in candidates.withIndex()) {
            if (scored.count { DeepResearchSupport.shouldImportScore(it.score) } >= desiredCandidatePool) break
            val normalized = DeepResearchSupport.normalizeUrl(candidate.url)
            if (!seenUrls.add(normalized)) continue
            UnifiedNotificationManager.updateProgress(
                taskId,
                progressBase,
                getString(
                    R.string.deep_research_progress_fetching,
                    candidateIndex + 1,
                    candidates.size,
                    candidate.title.take(70)
                )
            )
            KnowledgeBaseDiagnostics.log("Deep Research reading candidate ${candidateIndex + 1}/${candidates.size}: ${candidate.title} <$normalized>.")
            val fetched = runCatching {
                DeepResearchSupport.fetchReadableSource(
                    client = client,
                    url = normalized,
                    maxChars = DeepResearchSupport.MAX_SOURCE_TEXT_CHARS,
                    pdfTextExtractor = { bytes, maxChars -> extractNativePdfTextFromBytes(bytes, maxChars) }
                )
            }.onFailure { error ->
                KnowledgeBaseDiagnostics.log("Deep Research skipped '${candidate.url}': ${error.message ?: error::class.java.simpleName}.")
            }.getOrNull() ?: continue
            val finalUrl = DeepResearchSupport.normalizeUrl(fetched.finalUrl)
            if (finalUrl != normalized && !seenUrls.add(finalUrl)) {
                KnowledgeBaseDiagnostics.log("Deep Research skipped duplicate final URL '$finalUrl'.")
                continue
            }
            val score = DeepResearchSupport.scoreCandidate(
                query = query,
                title = fetched.title.ifBlank { candidate.title },
                url = finalUrl,
                readableText = fetched.text,
                contentType = fetched.contentType
            )
            if (!score.skip) {
                val titledSource = fetched.copy(
                    finalUrl = finalUrl,
                    title = fetched.title.ifBlank { candidate.title }
                )
                scored += DeepResearchScoredSource(titledSource, score)
                if (DeepResearchSupport.shouldImportScore(score)) {
                    KnowledgeBaseDiagnostics.log("Deep Research accepted candidate '${titledSource.title}' (${score.score}, ${score.reason}) from $finalUrl.")
                } else {
                    KnowledgeBaseDiagnostics.log("Deep Research kept low-score fallback candidate '${titledSource.title}' (${score.score}, below import floor ${DeepResearchSupport.MIN_IMPORT_SCORE}) from $finalUrl.")
                }
            } else {
                KnowledgeBaseDiagnostics.log("Deep Research skipped '$finalUrl' (${score.score}): ${score.reason}.")
            }
        }
        return scored
    }

    private suspend fun importScoredSources(
        repository: KnowledgeBaseRepository,
        knowledgeBaseId: Long,
        sources: List<DeepResearchScoredSource>,
        sourceLimit: Int,
        progress: DeepResearchNotificationProgress
    ): Int {
        var importedCount = 0
        sources.forEach { (source, score) ->
            progress.beginImport(
                index = importedCount + 1,
                title = source.title,
                phaseText = getString(R.string.deep_research_progress_importing, importedCount + 1, sourceLimit, source.title.take(70))
            )
            KnowledgeBaseDiagnostics.log("Deep Research importing '${source.title}' (${score.score}, ${score.reason}) from ${source.finalUrl}.")
            repository.importWebSource(
                knowledgeBaseId = knowledgeBaseId,
                finalUrl = source.finalUrl,
                title = source.title,
                text = source.text
            )
            importedCount += 1
        }
        return importedCount
    }

    private inner class DeepResearchNotificationProgress(
        private val taskId: Int,
        private val totalSources: Int
    ) {
        private var sourceIndex: Int = 1
        private var sourceTitle: String = ""
        private var phaseText: String = getString(R.string.deep_research_progress_starting)
        private var sourceDone: Int = 0
        private var sourceTotal: Int = 0

        fun beginImport(index: Int, title: String, phaseText: String) {
            sourceIndex = index.coerceIn(1, totalSources.coerceAtLeast(1))
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
            val safeTotalSources = totalSources.coerceAtLeast(1)
            val sourceFraction = if (sourceTotal > 0) {
                sourceDone.toFloat() / sourceTotal.toFloat()
            } else {
                0f
            }.coerceIn(0f, 1f)
            val totalFraction = ((sourceIndex - 1).toFloat() + sourceFraction) / safeTotalSources.toFloat()
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
                        sourceIndex,
                        safeTotalSources
                    )
                )
            )
        }
    }

    private suspend fun selectKnowledgeBaseForChat(database: AppDatabase, chatId: Long, knowledgeBaseId: Long) {
        val chat = database.llamaChatDao().getChatById(chatId) ?: return
        val config = NativeChatToolConfig.fromApiParams(chat.apiParams)
        val nextConfig = config.copy(
            toolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = true,
            selectedKnowledgeBaseIds = (config.selectedKnowledgeBaseIds + knowledgeBaseId).distinct(),
            chatDocumentKnowledgeBaseId = config.chatDocumentKnowledgeBaseId
        )
        database.llamaChatDao().updateApiParams(chatId, Gson().toJson(nextConfig.toParamMap()))
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FOCUS = "focus"
        private const val EXTRA_SOURCE_LIMIT = "source_limit"
        private const val EXTRA_TARGET_KNOWLEDGE_BASE_ID = "target_knowledge_base_id"
        private const val EXTRA_JOB_ID = "job_id"
        private val awaitingJobs = ConcurrentHashMap<String, CompletableDeferred<Result<DeepResearchJobResult>>>()

        fun enqueue(
            context: Context,
            chatId: Long,
            query: String,
            title: String? = null,
            focus: String? = null,
            sourceLimit: Int = DeepResearchSupport.DEFAULT_SOURCE_LIMIT,
            targetKnowledgeBaseId: Long? = null
        ) {
            enqueueInternal(
                context = context,
                chatId = chatId,
                query = query,
                title = title,
                focus = focus,
                sourceLimit = sourceLimit,
                targetKnowledgeBaseId = targetKnowledgeBaseId,
                jobId = null
            )
        }

        suspend fun runAndAwait(
            context: Context,
            chatId: Long,
            query: String,
            title: String? = null,
            focus: String? = null,
            sourceLimit: Int = DeepResearchSupport.DEFAULT_SOURCE_LIMIT,
            targetKnowledgeBaseId: Long? = null
        ): DeepResearchJobResult {
            val jobId = UUID.randomUUID().toString()
            val completion = CompletableDeferred<Result<DeepResearchJobResult>>()
            awaitingJobs[jobId] = completion
            try {
                enqueueInternal(
                    context = context,
                    chatId = chatId,
                    query = query,
                    title = title,
                    focus = focus,
                    sourceLimit = sourceLimit,
                    targetKnowledgeBaseId = targetKnowledgeBaseId,
                    jobId = jobId
                )
                return completion.await().getOrThrow()
            } finally {
                awaitingJobs.remove(jobId)
            }
        }

        private fun enqueueInternal(
            context: Context,
            chatId: Long,
            query: String,
            title: String? = null,
            focus: String? = null,
            sourceLimit: Int = DeepResearchSupport.DEFAULT_SOURCE_LIMIT,
            targetKnowledgeBaseId: Long? = null,
            jobId: String? = null
        ) {
            val intent = Intent(context, DeepResearchService::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_QUERY, query)
                putExtra(EXTRA_TITLE, title.orEmpty())
                putExtra(EXTRA_FOCUS, focus.orEmpty())
                putExtra(EXTRA_SOURCE_LIMIT, DeepResearchSupport.normalizeSourceLimit(sourceLimit))
                putExtra(EXTRA_TARGET_KNOWLEDGE_BASE_ID, targetKnowledgeBaseId?.takeIf { it > 0L } ?: 0L)
                putExtra(EXTRA_JOB_ID, jobId.orEmpty())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        private fun completeAwaitingJob(jobId: String, result: Result<DeepResearchJobResult>) {
            if (jobId.isBlank()) return
            awaitingJobs.remove(jobId)?.complete(result)
        }
    }
}
