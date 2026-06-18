package com.example.llamadroid.tama.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteBackendResilience
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.service.RemoteSummaryRequest
import com.example.llamadroid.service.RemoteSummaryResponse
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaArtworkKind
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class DailyDreamQueueResult(
    val albumId: String,
    val dreamDate: String,
    val sourceActivity: String
)

@Serializable
data class DailyDreamPlan(
    val story: String,
    val closing: String = "",
    val moments: List<DailyDreamMoment>
)

@Serializable
data class DailyDreamMoment(
    val title: String,
    val prompt: String
)

object TamaDailyDreamManager {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private const val NORMAL_SLEEP_START_HOUR = 23
    private const val DEEP_SLEEP_START_HOUR = 21
    private const val DEEP_SLEEP_END_HOUR = 3
    private const val DEEP_SLEEP_MINUTES = 4 * 60
    private const val DEEP_DREAM_LOCAL_LLAMA_TIMEOUT_MS = 5L * 60L * 1000L
    private const val DEEP_DREAM_METADATA_TIMEOUT_MS = 5L * 60L * 1000L
    private const val DEEP_DREAM_STEP_TIMEOUT_MS = 10L * 60L * 1000L
    private const val DEEP_DREAM_METADATA_TIMEOUT_MINUTES = 5
    private const val DEEP_DREAM_STEP_TIMEOUT_MINUTES = 10
    private const val DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS = 10
    private const val DEEP_DREAM_BACKEND_CONNECT_RETRY_DELAY_MS = 2_000L
    private const val DEEP_DREAM_LOCAL_LLAMA_RESTART_WAIT_MS = 3L * 60L * 1000L
    private const val DEEP_DREAM_LOCAL_LLAMA_RETRY_DELAY_MS =
        DEEP_DREAM_LOCAL_LLAMA_RESTART_WAIT_MS / (DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS - 1)
    private val llamaServerChatService = LlamaServerChatService()

    private class DeepDreamBackendConnectionException(
        message: String,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    suspend fun queueIfEligible(
        context: Context,
        dao: TamaDao,
        settingsRepo: SettingsRepository,
        pet: TamaPet,
        now: Long = System.currentTimeMillis()
    ): Result<DailyDreamQueueResult?> {
        if (!pet.isSleeping) return Result.success(null)
        if (!settingsRepo.tamaNormalDreamingEnabled.value) return Result.success(null)
        val dream = TamaArtworkManager.queueDream(
            context = context,
            pet = pet,
            settingsRepository = settingsRepo
        ).getOrElse { error ->
            return Result.failure(error)
        }
        return Result.success(
            DailyDreamQueueResult(
                albumId = dream.id,
                dreamDate = dateFormat.format(Date(pet.sleepStartTime ?: now)),
                sourceActivity = dream.sourceActivity ?: "sleeping"
            )
        )
    }

    suspend fun queueDeepIfEligible(
        context: Context,
        dao: TamaDao,
        settingsRepo: SettingsRepository,
        pet: TamaPet,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
        progressTaskIdOverride: Int? = null,
        stageReporter: suspend (String) -> Unit = {},
        llamaOwnershipReporter: suspend (Boolean) -> Unit = {}
    ): Result<DailyDreamQueueResult?> {
        return queueDreamIfEligible(
            context = context,
            dao = dao,
            settingsRepo = settingsRepo,
            pet = pet,
            now = now,
            allowDeep = true,
            force = force,
            progressTaskIdOverride = progressTaskIdOverride,
            stageReporter = stageReporter,
            llamaOwnershipReporter = llamaOwnershipReporter
        )
    }

    private suspend fun queueDreamIfEligible(
        context: Context,
        dao: TamaDao,
        settingsRepo: SettingsRepository,
        pet: TamaPet,
        now: Long,
        allowDeep: Boolean,
        force: Boolean = false,
        progressTaskIdOverride: Int? = null,
        stageReporter: suspend (String) -> Unit = {},
        llamaOwnershipReporter: suspend (Boolean) -> Unit = {}
    ): Result<DailyDreamQueueResult?> {
        if (allowDeep && !settingsRepo.tamaDeepDreamingEnabled.value && !force) return Result.success(null)
        val sourceActivity = if (allowDeep) {
            val deepDreamDate = if (force) {
                val sleepStart = pet.sleepStartTime ?: now
                dateFormat.format(Date(sleepStart))
            } else {
                eligibleDeepDreamDate(pet, now) ?: return Result.success(null)
            }
            deepDreamDate
        } else {
            return queueIfEligible(context, dao, settingsRepo, pet, now)
        }

        val managesTaskLifecycle = allowDeep && progressTaskIdOverride == null
        val progressTaskId = progressTaskIdOverride ?: if (allowDeep) {
            UnifiedNotificationManager.startTask(
                UnifiedNotificationManager.TaskType.LLAMA_SERVER,
                context.getString(R.string.tama_deep_dream_progress_title)
            )
        } else {
            null
        }

        fun updateDeepProgress(progress: Float, text: String) {
            if (progressTaskId != null) {
                UnifiedNotificationManager.updateProgress(progressTaskId, progress, text)
            }
        }

        var ownsLocalLlama = false
        try {
            if (allowDeep) {
                stageReporter(TamaDeepDreamRunCoordinator.STAGE_LOCAL_LLAMA)
                updateDeepProgress(0.05f, context.getString(R.string.tama_deep_dream_status_preparing))
                val localLlamaResult = runCatching {
                    withTimeout(DEEP_DREAM_LOCAL_LLAMA_TIMEOUT_MS) {
                        ensureLocalLlamaServerIfNeeded(context, settingsRepo, pet.id).getOrThrow()
                    }
                }.recoverCatching {
                    if (it is TimeoutCancellationException) {
                        throw IllegalStateException(
                            context.getString(
                                R.string.tama_deep_dream_error_request_timeout,
                                DEEP_DREAM_METADATA_TIMEOUT_MINUTES
                            )
                        )
                    }
                    throw it
                }.mapCatching { autostarted ->
                    ownsLocalLlama = autostarted
                    llamaOwnershipReporter(autostarted)
                }
                localLlamaResult.getOrElse { error ->
                    if (managesTaskLifecycle) {
                        progressTaskId?.let { UnifiedNotificationManager.failTask(it, error.message ?: context.getString(R.string.error_generic)) }
                    }
                    return Result.failure(error)
                }
            }

            stageReporter(TamaDeepDreamRunCoordinator.STAGE_RESOLVE_MODEL)
            updateDeepProgress(0.25f, context.getString(R.string.tama_deep_dream_status_generating_summary))
            TamaArtworkManager.resolvePicGenModel(context, settingsRepo).getOrElse { error ->
                if (managesTaskLifecycle) {
                    progressTaskId?.let { UnifiedNotificationManager.failTask(it, error.message ?: context.getString(R.string.error_generic)) }
                }
                return Result.failure(error)
            }

            stageReporter(TamaDeepDreamRunCoordinator.STAGE_FETCH_METADATA)
            updateDeepProgress(0.40f, context.getString(R.string.tama_deep_dream_status_fetching_metadata))
            val metadata = runCatching {
                withTimeout(DEEP_DREAM_METADATA_TIMEOUT_MS) {
                    fetchBackendMetadataWithConnectionLimit(
                        context = context,
                        settingsRepo = settingsRepo,
                        petId = pet.id,
                        localLlamaAutostartReporter = { autostarted ->
                            if (autostarted && !ownsLocalLlama) {
                                ownsLocalLlama = true
                                llamaOwnershipReporter(true)
                            }
                        }
                    ).getOrThrow()
                }
            }.recoverCatching {
                if (it is TimeoutCancellationException) {
                    throw IllegalStateException(
                        context.getString(
                            R.string.tama_deep_dream_error_request_timeout,
                            DEEP_DREAM_METADATA_TIMEOUT_MINUTES
                        )
                    )
                }
                throw it
            }.getOrElse { error ->
                if (managesTaskLifecycle) {
                    progressTaskId?.let { UnifiedNotificationManager.failTask(it, error.message ?: context.getString(R.string.error_generic)) }
                }
                return Result.failure(error)
            }
            persistMetadata(settingsRepo, metadata)

            stageReporter(TamaDeepDreamRunCoordinator.STAGE_SUMMARY)
            updateDeepProgress(0.55f, context.getString(R.string.tama_deep_dream_status_building_plan))
            val plan = buildDreamPlan(
                context = context,
                dao = dao,
                settingsRepo = settingsRepo,
                pet = pet,
                dreamDate = sourceActivity,
                progressTaskId = progressTaskId,
                stageReporter = stageReporter,
                localLlamaAutostartReporter = { autostarted ->
                    if (autostarted && !ownsLocalLlama) {
                        ownsLocalLlama = true
                        llamaOwnershipReporter(true)
                    }
                }
            )
            if (plan.moments.size != 4) {
                val error = IllegalStateException(context.getString(R.string.tama_daily_dream_invalid_response))
                if (managesTaskLifecycle) {
                    progressTaskId?.let { UnifiedNotificationManager.failTask(it, error.message ?: context.getString(R.string.error_generic)) }
                }
                return Result.failure(error)
            }

            val albumId = UUID.randomUUID().toString()
            stageReporter(TamaDeepDreamRunCoordinator.STAGE_QUEUEING_ALBUM)
            updateDeepProgress(0.75f, context.getString(R.string.tama_deep_dream_status_queueing_slides))
            val queued = TamaArtworkManager.queueDailyDreamAlbum(
                context = context,
                pet = pet,
                settingsRepository = settingsRepo,
                albumId = albumId,
                dreamDate = sourceActivity,
                story = plan.story,
                closing = plan.closing,
                moments = plan.moments,
                sourceActivity = if (allowDeep) "deep_sleeping" else "sleeping"
            )
            updateDeepProgress(0.95f, context.getString(R.string.tama_deep_dream_status_slides_queued))
            if (managesTaskLifecycle) {
                progressTaskId?.let {
                    UnifiedNotificationManager.completeTask(
                        it,
                        context.getString(R.string.tama_deep_dream_status_slides_queued)
                    )
                }
            }
            return queued.map {
                DailyDreamQueueResult(
                    albumId = albumId,
                    dreamDate = sourceActivity,
                    sourceActivity = if (allowDeep) "deep_sleeping" else "sleeping"
                )
            }
        } catch (e: Exception) {
            if (managesTaskLifecycle) {
                progressTaskId?.let { UnifiedNotificationManager.failTask(it, e.message ?: context.getString(R.string.error_generic)) }
            }
            return Result.failure(e)
        } finally {
            runCatching {
                releaseOwnedLocalLlamaServerIfNeeded(context, pet.id, ownsLocalLlama)
                if (ownsLocalLlama) {
                    llamaOwnershipReporter(false)
                }
            }
        }
    }

    fun formatDreamDate(timeMillis: Long): String = dateFormat.format(Date(timeMillis))

    fun eligibleDreamDate(pet: TamaPet, now: Long): String? {
        val sleepStart = pet.sleepStartTime ?: return null
        if (!pet.isSleeping || sleepStart > now) return null
        val startCursor = Calendar.getInstance().apply {
            timeInMillis = sleepStart
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endDay = Calendar.getInstance().apply { timeInMillis = now }
        while (!startCursor.after(endDay)) {
            val dayKey = dateFormat.format(startCursor.time)
            val bedtimeBoundary = (startCursor.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, NORMAL_SLEEP_START_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (bedtimeBoundary.timeInMillis in sleepStart..now &&
                dayKey != pet.lastDailyDreamDate
            ) {
                return dayKey
            }
            startCursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun eligibleDeepDreamDate(pet: TamaPet, now: Long): String? {
        val sleepStart = pet.sleepStartTime ?: return null
        if (!pet.isSleeping || sleepStart > now) return null

        val sleepStartCalendar = Calendar.getInstance().apply { timeInMillis = sleepStart }
        val sleepHour = sleepStartCalendar.get(Calendar.HOUR_OF_DAY)
        val sleepMinute = sleepStartCalendar.get(Calendar.MINUTE)
        val sleptMinutes = ((now - sleepStart) / 60_000L).toInt()
        val inDeepSleepWindow = when {
            sleepHour > DEEP_SLEEP_START_HOUR && sleepHour < 24 -> true
            sleepHour < DEEP_SLEEP_END_HOUR -> true
            sleepHour == DEEP_SLEEP_START_HOUR -> true
            sleepHour == DEEP_SLEEP_END_HOUR -> sleepMinute == 0
            else -> false
        }
        if (!inDeepSleepWindow || sleptMinutes < DEEP_SLEEP_MINUTES) return null

        return dateFormat.format(Date(sleepStart))
    }

    private suspend fun buildDreamPlan(
        context: Context,
        dao: TamaDao,
        settingsRepo: SettingsRepository,
        pet: TamaPet,
        dreamDate: String,
        progressTaskId: Int?,
        stageReporter: suspend (String) -> Unit,
        localLlamaAutostartReporter: suspend (Boolean) -> Unit
    ): DailyDreamPlan {
        val retryCount = settingsRepo.tamaDeepDreamRetryCount.value.coerceAtLeast(1)
        val speciesLine = PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
        val events = eventsForDreamDate(dao, pet.id, dreamDate)
        val eventLines = events.joinToString("\n") { event ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.timestamp))
            "[$time] ${event.details}"
        }.ifBlank { context.getString(R.string.tama_daily_dream_no_events) }
        val inventorySummary = pet.inventory
            .groupBy { it.name }
            .entries
            .sortedBy { it.key.lowercase() }
            .joinToString(", ") { "${it.key} x${it.value.sumOf { item -> item.quantity }}" }
            .ifBlank { context.getString(R.string.tama_status_inventory_empty) }
        val desiredLanguage = settingsRepo.tamaDeepDreamDesiredLanguage.value.trim().ifBlank { "English" }
        val backendConfig = RemoteSummaryBackendConfig(
            backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value),
            baseUrl = activeBaseUrl(settingsRepo),
            model = activeModel(settingsRepo),
            timeoutMinutes = DEEP_DREAM_STEP_TIMEOUT_MINUTES,
            context = context,
            liteRtModelId = settingsRepo.tamaLiteRtModelId.value.takeIf { it > 0L },
            liteRtBackend = settingsRepo.tamaLiteRtBackend.value,
            liteRtMtpEnabled = settingsRepo.tamaLiteRtMtpEnabled.value
        )
        val client = RemoteSummaryClientFactory.fromConfig(backendConfig)
        val sharedFacts = buildString {
            appendLine("Pet name: ${pet.name}")
            appendLine("Species flavor: ${speciesLine.promptLabel}")
            appendLine("Stage: ${pet.stage.displayName}")
            appendLine("Personality: ${pet.personality.name.lowercase(Locale.getDefault())}")
            appendLine("Dream date: $dreamDate")
            appendLine("Inventory summary: $inventorySummary")
            appendLine("Recent day events:")
            appendLine(eventLines)
        }.trim()

        fun updateStepProgress(progress: Float, text: String) {
            if (progressTaskId != null) {
                UnifiedNotificationManager.updateProgress(progressTaskId, progress, text)
            }
        }

        suspend fun summarizeWithLocalLlamaRecovery(request: RemoteSummaryRequest): RemoteSummaryResponse {
            return try {
                client.summarize(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!shouldRecoverLocalLlamaBackend(settingsRepo, error)) {
                    throw error
                }
                recordDreamBreadcrumb(
                    event = "local_llama_recovery_start",
                    petId = pet.id,
                    details = RemoteBackendResilience.summarize(error)
                )
                val autostarted = ensureLocalLlamaServerIfNeeded(context, settingsRepo, pet.id).getOrElse { recoveryError ->
                    throw recoveryError
                }
                if (autostarted) {
                    localLlamaAutostartReporter(true)
                }
                recordDreamBreadcrumb("local_llama_recovery_retry", petId = pet.id, details = activeBaseUrl(settingsRepo))
                val retryClient = RemoteSummaryClientFactory.fromConfig(
                    backendConfig.copy(
                        baseUrl = activeBaseUrl(settingsRepo),
                        model = activeModel(settingsRepo)
                    )
                )
                try {
                    retryClient.summarize(request)
                } catch (retryError: CancellationException) {
                    throw retryError
                } catch (retryError: Exception) {
                    if (RemoteBackendResilience.isRecoverable(retryError)) {
                        throw backendConnectionLimitException(context, retryError)
                    }
                    throw retryError
                }
            }
        }

        suspend fun runStep(
            stageKey: String,
            progress: Float,
            retryProgress: Float,
            title: String,
            promptFactory: (attempt: Int, previousError: Throwable?) -> Pair<String, String>,
            parser: (String) -> String
        ): String {
            var lastError: Throwable? = null
            repeat(retryCount) { attempt ->
                recordDreamBreadcrumb(
                    event = "planner_stage_started",
                    petId = pet.id,
                    details = "$stageKey attempt=${attempt + 1}"
                )
                stageReporter(stageKey)
                updateStepProgress(
                    if (attempt == 0) progress else retryProgress,
                    if (attempt == 0) {
                        title
                    } else {
                        context.getString(R.string.tama_deep_dream_status_retrying_plan, attempt + 1, retryCount)
                    }
                )
                val (systemPrompt, userPrompt) = promptFactory(attempt, lastError)
                val response = try {
                    val summaryRequest = RemoteSummaryRequest(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        contextSize = settingsRepo.tamaOllamaNumCtx.value,
                        maxTokens = 1024,
                        temperature = 0.7f,
                        thinkingEnabled = settingsRepo.tamaThinkingEnabled.value
                    )
                    withTimeout(DEEP_DREAM_STEP_TIMEOUT_MS) {
                        summarizeWithLocalLlamaRecovery(summaryRequest)
                    }
                } catch (_: TimeoutCancellationException) {
                    recordDreamBreadcrumb(
                        event = "planner_stage_timeout",
                        petId = pet.id,
                        details = "$stageKey attempt=${attempt + 1}"
                    )
                    lastError = IllegalStateException(
                        context.getString(
                            R.string.tama_deep_dream_error_request_timeout,
                            DEEP_DREAM_STEP_TIMEOUT_MINUTES
                        )
                    )
                    return@repeat
                } catch (error: DeepDreamBackendConnectionException) {
                    recordDreamBreadcrumb(
                        event = "planner_stage_connection_failed",
                        petId = pet.id,
                        details = "$stageKey attempt=${attempt + 1} ${error.message}"
                    )
                    throw error
                }
                val payload = sanitizeTamaModelOutput(response.output)
                runCatching { parser(payload) }
                    .onSuccess {
                        recordDreamBreadcrumb(
                            event = "planner_stage_finished",
                            petId = pet.id,
                            details = "$stageKey attempt=${attempt + 1}"
                        )
                        return it
                    }
                    .onFailure {
                        recordDreamBreadcrumb(
                            event = "planner_stage_parse_failed",
                            petId = pet.id,
                            details = "$stageKey attempt=${attempt + 1} ${it.javaClass.simpleName}: ${it.message}"
                        )
                        lastError = it
                    }
            }
            throw IllegalStateException(lastError?.message ?: context.getString(R.string.tama_daily_dream_invalid_response))
        }

        val summary = runStep(
            stageKey = TamaDeepDreamRunCoordinator.STAGE_SUMMARY,
            progress = 0.58f,
            retryProgress = 0.585f,
            title = context.getString(R.string.tama_deep_dream_status_generating_summary),
            promptFactory = { attempt, previousError ->
                dreamSummaryPrompts(
                    desiredLanguage = desiredLanguage,
                    sharedFacts = sharedFacts,
                    attempt = attempt + 1,
                    retryCount = retryCount,
                    previousError = previousError?.message
                )
            },
            parser = { parsePlainTextStep(it, requirePrefix = null) }
        )

        val builtMoments = mutableListOf<DailyDreamMoment>()
        repeat(4) { index ->
            val stepNumber = index + 1
            val momentText = runStep(
                stageKey = "MOMENT_TEXT_$stepNumber",
                progress = 0.60f + (index * 0.035f),
                retryProgress = 0.61f + (index * 0.035f),
                title = context.getString(R.string.tama_deep_dream_status_generating_slide, stepNumber, 4),
                promptFactory = { attempt, previousError ->
                    dreamMomentTextPrompts(
                        desiredLanguage = desiredLanguage,
                        sharedFacts = sharedFacts,
                        summary = summary,
                        previousMoments = builtMoments,
                        momentNumber = stepNumber,
                        attempt = attempt + 1,
                        retryCount = retryCount,
                        previousError = previousError?.message
                    )
                },
                parser = { parsePlainTextStep(it, requirePrefix = null) }
            )
            val momentPrompt = runStep(
                stageKey = "MOMENT_PROMPT_$stepNumber",
                progress = 0.617f + (index * 0.035f),
                retryProgress = 0.627f + (index * 0.035f),
                title = context.getString(R.string.tama_deep_dream_status_generating_slide, stepNumber, 4),
                promptFactory = { attempt, previousError ->
                    dreamMomentImagePromptPrompts(
                        sharedFacts = sharedFacts,
                        summary = summary,
                        previousMoments = builtMoments,
                        momentNumber = stepNumber,
                        momentText = momentText,
                        attempt = attempt + 1,
                        retryCount = retryCount,
                        previousError = previousError?.message
                    )
                },
                parser = { parsePlainTextStep(it, requirePrefix = null) }
            )
            builtMoments += DailyDreamMoment(
                title = momentText,
                prompt = momentPrompt
            )
        }

        val closing = runStep(
            stageKey = TamaDeepDreamRunCoordinator.STAGE_CLOSING,
            progress = 0.74f,
            retryProgress = 0.745f,
            title = context.getString(R.string.tama_deep_dream_status_refreshing_memory),
            promptFactory = { attempt, previousError ->
                dreamClosingPrompts(
                    desiredLanguage = desiredLanguage,
                    sharedFacts = sharedFacts,
                    summary = summary,
                    previousMoments = builtMoments,
                    attempt = attempt + 1,
                    retryCount = retryCount,
                    previousError = previousError?.message
                )
            },
            parser = { parsePlainTextStep(it, requirePrefix = null) }
        )

        return DailyDreamPlan(
            story = summary,
            closing = closing,
            moments = builtMoments
        )
    }

    private suspend fun ensureLocalLlamaServerIfNeeded(
        context: Context,
        settingsRepo: SettingsRepository,
        petId: String
    ): Result<Boolean> {
        if (!SettingsRepository.isLlamaServerBackend(settingsRepo.tamaBackend.value)) {
            return Result.success(false)
        }

        val baseUrl = activeBaseUrl(settingsRepo)
        if (!isLocalLlamaServer(baseUrl)) {
            return Result.success(false)
        }

        if (llamaServerChatService.checkConnection(baseUrl)) {
            recordDreamBreadcrumb("local_llama_already_running", petId = petId, details = baseUrl)
            return Result.success(false)
        }

        val modelPath = settingsRepo.selectedModelPath.value
        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException(context.getString(R.string.tama_deep_dream_local_model_missing)))
        }

        val wasAlreadyRunning = LlamaService.state.value !is ServerState.Stopped
        startLocalLlamaServer(context, settingsRepo, modelPath)
        val ownsRuntime = !wasAlreadyRunning
        if (ownsRuntime) {
            recordDreamBreadcrumb("local_llama_autostarted", petId = petId, details = baseUrl)
        } else {
            recordDreamBreadcrumb("local_llama_reused_running_service", petId = petId, details = baseUrl)
        }

        if (waitForLocalLlamaServerReady(baseUrl, petId)) {
            return Result.success(ownsRuntime)
        }

        recordDreamBreadcrumb(
            "local_llama_unavailable",
            petId = petId,
            details = "$baseUrl attempts=$DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS"
        )
        return Result.failure(
            backendConnectionLimitException(context)
        )
    }

    private suspend fun waitForLocalLlamaServerReady(
        baseUrl: String,
        petId: String
    ): Boolean {
        repeat(DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS) { attempt ->
            if (llamaServerChatService.checkConnection(baseUrl)) {
                recordDreamBreadcrumb("local_llama_ready", petId = petId, details = "attempt=${attempt + 1}")
                return true
            }
            if (attempt < DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS - 1) {
                delay(DEEP_DREAM_LOCAL_LLAMA_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun startLocalLlamaServer(
        context: Context,
        settingsRepo: SettingsRepository,
        modelPath: String
    ) {
        val intent = Intent(context, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            if (settingsRepo.speculativeEnabled.value) {
                val speculativeMode = settingsRepo.speculativeMode.value
                val shouldPassDraftModel =
                    speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE ||
                        (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
                if (shouldPassDraftModel) {
                    putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                }
                putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
            }
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        }
        context.startForegroundService(intent)
    }

    internal fun releaseOwnedLocalLlamaServerIfNeeded(context: Context, petId: String, ownsRuntime: Boolean) {
        if (!ownsRuntime) return
        recordDreamBreadcrumb("local_llama_stopping_owned_runtime", petId = petId)
        val stopIntent = Intent(context, LlamaService::class.java).apply {
            action = LlamaService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    private fun recordDreamBreadcrumb(event: String, petId: String, details: String? = null) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "tama_deep_dream",
                sessionId = petId,
                mode = "deep_dream",
                event = event,
                details = details
            )
        }
    }

    private fun isLocalLlamaServer(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase(Locale.getDefault()).orEmpty()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private fun shouldRecoverLocalLlamaBackend(
        settingsRepo: SettingsRepository,
        error: Throwable
    ): Boolean {
        return SettingsRepository.isLlamaServerBackend(settingsRepo.tamaBackend.value) &&
            isLocalLlamaServer(activeBaseUrl(settingsRepo)) &&
            RemoteBackendResilience.isRecoverable(error)
    }

    private fun backendConnectionLimitException(
        context: Context,
        cause: Throwable? = null
    ): DeepDreamBackendConnectionException {
        return DeepDreamBackendConnectionException(
            context.getString(
                R.string.tama_deep_dream_backend_connect_retry_limit,
                DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS
            ),
            cause
        )
    }

    private suspend fun eventsForDreamDate(
        dao: TamaDao,
        petId: String,
        dreamDate: String
    ) = dao.getEventsSince(petId, startOfDayMillis(dreamDate))
        .filter { it.timestamp <= endOfDayMillis(dreamDate) }

    private fun startOfDayMillis(dayKey: String): Long {
        val calendar = Calendar.getInstance()
        calendar.time = dateFormat.parse(dayKey) ?: Date()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun endOfDayMillis(dayKey: String): Long {
        val calendar = Calendar.getInstance()
        calendar.time = dateFormat.parse(dayKey) ?: Date()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    private fun activeBaseUrl(settingsRepo: SettingsRepository): String {
        return when (SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> settingsRepo.tamaLlamaServerUrl.value.trim().trimEnd('/')
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> settingsRepo.tamaLlamaSwapUrl.value.trim().trimEnd('/')
            SettingsRepository.PDF_BACKEND_LITERT -> "local"
            else -> settingsRepo.tamaOllamaUrl.value.trim().trimEnd('/')
        }
    }

    private fun activeModel(settingsRepo: SettingsRepository): String? {
        return when (SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER ->
                settingsRepo.tamaLlamaServerModelLabel.value?.trim()?.ifBlank { null }
            SettingsRepository.PDF_BACKEND_LITERT ->
                settingsRepo.tamaLiteRtModelId.value.takeIf { it > 0L }?.let { "litert:$it" }
            else ->
                settingsRepo.tamaSummarizerModel.value.trim().ifBlank { null }
        }
    }

    private suspend fun fetchBackendMetadata(
        context: Context,
        settingsRepo: SettingsRepository
    ): Result<RemoteSummaryMetadata> {
        return RemoteSummaryClientFactory.fromConfig(
            RemoteSummaryBackendConfig(
                backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value),
                baseUrl = activeBaseUrl(settingsRepo),
                model = activeModel(settingsRepo),
                timeoutMinutes = DEEP_DREAM_METADATA_TIMEOUT_MINUTES,
                context = context,
                liteRtModelId = settingsRepo.tamaLiteRtModelId.value.takeIf { it > 0L },
                liteRtBackend = settingsRepo.tamaLiteRtBackend.value,
                liteRtMtpEnabled = settingsRepo.tamaLiteRtMtpEnabled.value
            )
        ).fetchMetadata().recoverCatching { error ->
            if (!SettingsRepository.isLlamaServerBackend(settingsRepo.tamaBackend.value)) {
                throw error
            }
            val cachedModel = settingsRepo.tamaLlamaServerModelLabel.value?.trim().orEmpty()
            val cachedContextTokens = settingsRepo.tamaLlamaServerContextTokens.value
            val cachedContextLabel = settingsRepo.tamaLlamaServerContextLabel.value?.trim().orEmpty()
            if (cachedModel.isBlank() && cachedContextTokens == null && cachedContextLabel.isBlank()) {
                throw error
            }
            RemoteSummaryMetadata(
                backend = SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                baseUrl = activeBaseUrl(settingsRepo),
                selectedModel = cachedModel.ifBlank { null },
                serverModelLabel = cachedModel.ifBlank { null },
                serverContextTokens = cachedContextTokens,
                serverContextLabel = cachedContextLabel.ifBlank { null }
            )
        }
    }

    private suspend fun fetchBackendMetadataWithConnectionLimit(
        context: Context,
        settingsRepo: SettingsRepository,
        petId: String,
        localLlamaAutostartReporter: suspend (Boolean) -> Unit
    ): Result<RemoteSummaryMetadata> {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepo.tamaBackend.value)
        val baseUrl = activeBaseUrl(settingsRepo)
        if (backend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER && isLocalLlamaServer(baseUrl)) {
            if (!llamaServerChatService.checkConnection(baseUrl)) {
                val autostarted = ensureLocalLlamaServerIfNeeded(context, settingsRepo, petId).getOrElse { error ->
                    return Result.failure(error)
                }
                if (autostarted) {
                    localLlamaAutostartReporter(true)
                }
            }
            return fetchBackendMetadata(context, settingsRepo).recoverCatching { error ->
                if (!shouldRecoverLocalLlamaBackend(settingsRepo, error)) {
                    throw error
                }
                recordDreamBreadcrumb(
                    "backend_connection_recovery_start",
                    petId = petId,
                    details = RemoteBackendResilience.summarize(error)
                )
                val autostarted = ensureLocalLlamaServerIfNeeded(context, settingsRepo, petId).getOrThrow()
                if (autostarted) {
                    localLlamaAutostartReporter(true)
                }
                fetchBackendMetadata(context, settingsRepo).getOrElse { retryError ->
                    if (RemoteBackendResilience.isRecoverable(retryError)) {
                        throw backendConnectionLimitException(context, retryError)
                    }
                    throw retryError
                }
            }
        }

        if (backend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER) {
            repeat(DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS) { attempt ->
                if (llamaServerChatService.checkConnection(baseUrl)) {
                    recordDreamBreadcrumb("backend_connection_ready", petId = petId, details = "$backend attempt=${attempt + 1}")
                    return fetchBackendMetadata(context, settingsRepo)
                }
                recordDreamBreadcrumb("backend_connection_retry", petId = petId, details = "$backend attempt=${attempt + 1}")
                if (attempt < DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS - 1) {
                    delay(DEEP_DREAM_BACKEND_CONNECT_RETRY_DELAY_MS)
                }
            }
            return Result.failure(
                backendConnectionLimitException(context)
            )
        }

        var lastError: Throwable? = null
        repeat(DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS) { attempt ->
            val metadata = fetchBackendMetadata(context, settingsRepo)
            metadata.onSuccess {
                recordDreamBreadcrumb("backend_connection_ready", petId = petId, details = "$backend attempt=${attempt + 1}")
                return metadata
            }.onFailure { error ->
                lastError = error
                recordDreamBreadcrumb(
                    "backend_connection_retry",
                    petId = petId,
                    details = "$backend attempt=${attempt + 1} ${RemoteBackendResilience.summarize(error)}"
                )
                if (!RemoteBackendResilience.isRecoverable(error)) {
                    return Result.failure(error)
                }
            }
            if (attempt < DEEP_DREAM_BACKEND_CONNECT_MAX_ATTEMPTS - 1) {
                delay(DEEP_DREAM_BACKEND_CONNECT_RETRY_DELAY_MS)
            }
        }
        return Result.failure(
            backendConnectionLimitException(context, lastError)
        )
    }

    private fun persistMetadata(settingsRepo: SettingsRepository, metadata: RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setTamaLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setTamaLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setTamaLlamaServerContextLabel(metadata.serverContextLabel)
        } else if (SettingsRepository.requiresSelectedRemoteModel(metadata.backend) && !metadata.selectedModel.isNullOrBlank()) {
            settingsRepo.setTamaSummarizerModel(metadata.selectedModel)
        }
    }

    internal fun encodeAlbumSummary(
        story: String,
        closing: String,
        language: String,
        momentTexts: List<String> = emptyList()
    ): String {
        return JSONObject()
            .put("story", story)
            .put("closing", closing)
            .put("language", language)
            .put("momentTexts", org.json.JSONArray(momentTexts))
            .toString()
    }

    internal data class DailyDreamAlbumSummary(
        val story: String,
        val closing: String,
        val language: String?,
        val momentTexts: List<String> = emptyList()
    )

    internal fun decodeAlbumSummary(raw: String?): DailyDreamAlbumSummary {
        val trimmed = extractAssistantContentEnvelope(raw?.trim().orEmpty())
        if (trimmed.isBlank()) {
            return DailyDreamAlbumSummary("", "", null)
        }
        return runCatching {
            val json = JSONObject(trimmed)
            DailyDreamAlbumSummary(
                story = json.optString("story").trim(),
                closing = json.optString("closing").trim(),
                language = json.optString("language").trim().ifBlank { null },
                momentTexts = buildList {
                    val moments = json.optJSONArray("momentTexts")
                    if (moments != null) {
                        for (index in 0 until moments.length()) {
                            moments.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
            )
        }.getOrElse {
            DailyDreamAlbumSummary(trimmed, trimmed, null)
        }
    }

    private fun dreamSummaryPrompts(
        desiredLanguage: String,
        sharedFacts: String,
        attempt: Int,
        retryCount: Int,
        previousError: String?
    ): Pair<String, String> {
        val systemPrompt = """
            You are writing the opening narration for a pet's deep dream slideshow.
            Write only the opening slide text in $desiredLanguage.
            Keep it warm, vivid, and concrete.
            Mention multiple moments from the day as a friendly retelling.
            Make it sound like the pet is sharing the best parts of the day before drifting deeper into sleep.
            Cover distinct anchors from the day such as a place, an action, a reward, or a feeling.
            Do not use JSON.
            Do not include labels, markdown, bullet lists, or explanations.
            If thinking is enabled internally, never reveal the thinking.
            Example good tone:
            Today felt bright and full. We wandered through the farm, picked ripe vegetables, laughed under the sky, and ended the day sleepy but proud.
        """.trimIndent()
        val userPrompt = """
            $sharedFacts

            Write the opening slide text for a 6-slide dream album.
            This opening must summarize the day in $desiredLanguage and set up four dream moments without repeating the exact same sentence structure.
            Attempt $attempt of $retryCount.
            Previous error to avoid: ${previousError ?: "none"}
        """.trimIndent()
        return systemPrompt to userPrompt
    }

    private fun dreamMomentTextPrompts(
        desiredLanguage: String,
        sharedFacts: String,
        summary: String,
        previousMoments: List<DailyDreamMoment>,
        momentNumber: Int,
        attempt: Int,
        retryCount: Int,
        previousError: String?
    ): Pair<String, String> {
        val previousBlock = if (previousMoments.isEmpty()) {
            "No previous moments yet."
        } else {
            previousMoments.mapIndexed { index, moment ->
                "Moment ${index + 1}: ${moment.title}"
            }.joinToString("\n\n")
        }
        val systemPrompt = """
            You are writing one dream-slide paragraph for a retro Tamagotchi-style deep dream.
            Return only one paragraph in $desiredLanguage.
            Do not use JSON.
            Do not use labels, markdown, bullet lists, or explanations.
            Focus on one memorable scene, feeling, place, reward, or action from the day.
            Make the scene concrete enough to illustrate, but do not write the image prompt.
            Do not repeat the same anchor, reward, or emotion already used by previous moments.
            If thinking is enabled internally, never reveal the thinking.

            Example good output:
            We wandered through golden fields and felt proud of the baskets we had filled together.
        """.trimIndent()
        val userPrompt = """
            $sharedFacts

            Opening summary already chosen:
            $summary

            Previous dream moments already used:
            $previousBlock

            Write moment $momentNumber of 4.
            It must feel different from all prior moments and stay faithful to the day.
            Avoid reusing the same place, reward, or emotional beat if it already appeared in a previous moment unless the day truly had no other strong anchors.
            Attempt $attempt of $retryCount.
            Previous error to avoid: ${previousError ?: "none"}
        """.trimIndent()
        return systemPrompt to userPrompt
    }

    private fun dreamMomentImagePromptPrompts(
        sharedFacts: String,
        summary: String,
        previousMoments: List<DailyDreamMoment>,
        momentNumber: Int,
        momentText: String,
        attempt: Int,
        retryCount: Int,
        previousError: String?
    ): Pair<String, String> {
        val previousBlock = if (previousMoments.isEmpty()) {
            "No previous prompts yet."
        } else {
            previousMoments.mapIndexed { index, moment ->
                "Moment ${index + 1} text: ${moment.title}\nMoment ${index + 1} image prompt: ${moment.prompt}"
            }.joinToString("\n\n")
        }
        val systemPrompt = """
            You are writing one English image-generation prompt for a retro Tamagotchi-style dream slide.
            Return only the prompt text in English.
            Do not use JSON.
            Do not use labels, markdown, bullet lists, or explanations.
            Start with: retro handheld game background illustration, charming 1990s Tamagotchi-style ...
            Keep the scene explicitly retro handheld and pixel-friendly, with flat colorful 2d art, bold outlines, cute toy-like shapes, and a simple readable composition.
            Describe left-to-right composition, main objects, background, mood, and open center space when needed.
            Match the supplied slide text closely.
            Use concrete nouns from the slide text instead of vague fantasy wording.
            Keep the prompt readable, charming, and specific to the day instead of generic dream symbolism.
            Do not reuse the same core composition as an earlier moment prompt.
            If thinking is enabled internally, never reveal the thinking.

            Example good output:
            retro handheld game background illustration, charming 1990s Tamagotchi-style room interior scene, flat colorful 2d art, large TV on the left, rounded sofa on the right, dining table in the center, shelf at the back, wall clock, rug on the floor, warm cozy mood, cute toy-like shapes, bold outlines, simple readable composition, clear open center space for pet tiles, no text, no UI, no people, no foreground objects
        """.trimIndent()
        val userPrompt = """
            $sharedFacts

            Opening summary already chosen:
            $summary

            Previous dream prompts already used:
            $previousBlock

            Current slide text for moment $momentNumber:
            $momentText

            Write only the English image prompt for this slide.
            It must stay visually faithful to the slide text and avoid duplicating the visual composition of previous prompts.
            Attempt $attempt of $retryCount.
            Previous error to avoid: ${previousError ?: "none"}
        """.trimIndent()
        return systemPrompt to userPrompt
    }

    private fun dreamClosingPrompts(
        desiredLanguage: String,
        sharedFacts: String,
        summary: String,
        previousMoments: List<DailyDreamMoment>,
        attempt: Int,
        retryCount: Int,
        previousError: String?
    ): Pair<String, String> {
        val priorText = previousMoments.joinToString("\n") { "- ${it.title}" }
        val systemPrompt = """
            You are writing the closing slide for a pet's deep dream slideshow.
            Write only the closing text in $desiredLanguage.
            It should feel like a soft final thought before waking up.
            Make it feel reflective and gentle, like the pet is holding onto the nicest feeling from the dream.
            Do not use JSON.
            Do not use labels, markdown, or bullet lists.
            If thinking is enabled internally, never reveal the thinking.
        """.trimIndent()
        val userPrompt = """
            $sharedFacts

            Opening summary:
            $summary

            Moment texts already used:
            $priorText

            Write the closing recap slide in $desiredLanguage.
            It should end the dream gently without repeating the opening summary verbatim.
            Attempt $attempt of $retryCount.
            Previous error to avoid: ${previousError ?: "none"}
        """.trimIndent()
        return systemPrompt to userPrompt
    }

    private fun parsePlainTextStep(raw: String, requirePrefix: String?): String {
        val cleaned = sanitizeTamaModelOutput(extractAssistantContentEnvelope(raw))
        val extracted = stripLeakedReasoningFromDreamText(
            extractDreamTextValue(
                cleaned = cleaned,
                preferredKeys = when {
                    requirePrefix != null -> listOf("content", "text", "story", "summary", "prompt")
                    else -> listOf("story", "closing", "content", "text", "summary", "prompt")
                }
            )
        )
        val normalized = extracted
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Dream step returned empty text.")
        }
        if (requirePrefix == null) {
            return normalized
                .removeSurrounding("\"")
                .removePrefix("content:")
                .trim()
        }
        val line = normalized.lineSequence().firstOrNull().orEmpty()
        if (!line.startsWith(requirePrefix, ignoreCase = true)) {
            throw IllegalArgumentException("Dream step is missing the required prefix $requirePrefix.")
        }
        return line.substringAfter(':').trim()
    }

    private fun extractDreamTextValue(cleaned: String, preferredKeys: List<String>): String {
        val trimmed = cleaned.trim()
        if (trimmed.isBlank()) return trimmed

        val jsonObject = runCatching { JSONObject(trimmed) }.getOrNull()
        if (jsonObject != null) {
            preferredKeys.forEach { key ->
                jsonObject.optString(key)
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            val arrays = preferredKeys.asSequence()
                .mapNotNull { key -> jsonObject.optJSONArray(key)?.takeIf { it.length() > 0 } }
                .firstOrNull()
            if (arrays != null) {
                val joined = buildString {
                    for (index in 0 until arrays.length()) {
                        val item = arrays.optString(index).trim()
                        if (item.isNotBlank()) {
                            if (isNotBlank()) append('\n')
                            append(item)
                        }
                    }
                }.trim()
                if (joined.isNotBlank()) return joined
            }
        }

        val quoted = preferredKeys.firstNotNullOfOrNull { key ->
            Regex("\"$key\"\\s*:\\s*\"(.*?)\"", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        if (quoted != null) return quoted

        return trimmed
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
    }

    private fun stripLeakedReasoningFromDreamText(raw: String): String {
        return raw
            .replace(Regex("(?is)<(think|thinking|reasoning)>.*?</\\1>"), " ")
            .replace(Regex("(?is)\\[Start thinking].*?\\[End thinking]"), " ")
            .replace(
                Regex(
                    "(?ims)^\\s*(Thinking Process|Reasoning|Thoughts|Analysis)\\s*:\\s*.*?(?=^\\s*(Final answer|Answer|Dream|Story|Content|Summary|Prompt|Closing)\\s*:|\\z)"
                ),
                ""
            )
            .lineSequence()
            .filterNot { line ->
                val normalized = line.trim().lowercase()
                normalized.startsWith("reasoning_content") ||
                    normalized.startsWith("reasoning:") ||
                    normalized.startsWith("thinking:") ||
                    normalized.startsWith("thoughts:") ||
                    normalized.startsWith("analysis:")
            }
            .joinToString("\n")
            .trim()
    }

    private fun extractAssistantContentEnvelope(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val json = JSONObject(trimmed)
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: json.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: trimmed
        }.getOrDefault(trimmed)
    }

}
