package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaArtworkManager
import com.example.llamadroid.tama.game.TamaDailyDreamManager
import com.example.llamadroid.tama.game.TamaDeepDreamRunClaimAction
import com.example.llamadroid.tama.game.TamaDeepDreamRunCoordinator
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class TamaDeepDreamService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processingJob: Job? = null
    private var notificationTaskId: Int? = null
    private var keepTaskNotificationOnExit = false
    @Volatile
    private var activeRunId: String? = null
    @Volatile
    private var activePetId: String? = null

    companion object {
        private const val ACTION_PROCESS_DEEP_DREAM = "com.example.llamadroid.action.PROCESS_TAMA_DEEP_DREAM"
        private const val EXTRA_PET_ID = "pet_id"
        private const val EXTRA_SIGNATURE = "signature"
        private const val EXTRA_FORCE = "force"
        private const val DEEP_DREAM_WATCHDOG_MS = 60L * 60L * 1000L

        fun createIntent(context: Context, petId: String, signature: String? = null, force: Boolean = false): Intent =
            Intent(context, TamaDeepDreamService::class.java).apply {
                action = ACTION_PROCESS_DEEP_DREAM
                putExtra(EXTRA_PET_ID, petId)
                putExtra(EXTRA_FORCE, force)
                if (!signature.isNullOrBlank()) {
                    putExtra(EXTRA_SIGNATURE, signature)
                }
            }

        fun start(context: Context, petId: String, signature: String? = null, force: Boolean = false) {
            ContextCompat.startForegroundService(context, createIntent(context, petId, signature, force))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROCESS_DEEP_DREAM) {
            val petId = intent.getStringExtra(EXTRA_PET_ID)
            val signature = intent.getStringExtra(EXTRA_SIGNATURE)
            val force = intent.getBooleanExtra(EXTRA_FORCE, false)
            if (petId.isNullOrBlank()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (processingJob?.isActive == true) {
                recordBreadcrumb("service_deduped", petId = petId, details = "activePetId=$activePetId")
                return START_NOT_STICKY
            }
            activePetId = petId
            processingJob = serviceScope.launch {
                recordBreadcrumb("service_started", petId = petId)
                try {
                    runDeepDream(petId, signature, force)
                } finally {
                    cleanupForeground(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationTaskId?.takeIf { !keepTaskNotificationOnExit }?.let { UnifiedNotificationManager.dismissTask(it) }
        activePetId = null
        activeRunId = null
    }

    private suspend fun runDeepDream(petId: String, signature: String?, force: Boolean) {
        val tamaDatabase = TamaDatabase.getInstance(applicationContext)
        val tamaDao = tamaDatabase.tamaDao()
        val petEntity = tamaDao.getPet(petId) ?: return
        val pet = PetMapper.toDomain(petEntity)
        val settingsRepository = SettingsRepository(applicationContext)
        val now = System.currentTimeMillis()
        val effectiveSignature = signature ?: buildForceSignature(pet, now)
        val dreamDate = dreamDateFromSignature(effectiveSignature, pet.sleepStartTime ?: now)
        val claim = TamaDeepDreamRunCoordinator.claimPlanningRun(
            database = tamaDatabase,
            petId = petId,
            signature = effectiveSignature,
            dreamDate = dreamDate,
            now = now
        )
        activeRunId = claim.run.id

        when (claim.action) {
            TamaDeepDreamRunClaimAction.DEDUPED_ACTIVE -> {
                recordBreadcrumb("run_deduped", petId = petId, details = "signature=$effectiveSignature status=${claim.run.status}")
                if (claim.run.status == TamaDeepDreamRunCoordinator.STATUS_QUEUED ||
                    claim.run.status == TamaDeepDreamRunCoordinator.STATUS_ARTWORK_RUNNING) {
                    TamaDeepDreamRunCoordinator.reconcileAlbumState(
                        context = applicationContext,
                        database = tamaDatabase,
                        run = claim.run,
                        startArtworkIfNeeded = true
                    )
                }
                return
            }
            TamaDeepDreamRunClaimAction.ALREADY_COMPLETED -> {
                recordBreadcrumb("run_already_completed", petId = petId, details = "signature=$effectiveSignature")
                return
            }
            TamaDeepDreamRunClaimAction.RESTARTED_STALE -> {
                recordBreadcrumb("run_stale_taken_over", petId = petId, details = "signature=$effectiveSignature")
            }
            TamaDeepDreamRunClaimAction.RESUMED_WAITING_APP_OPEN -> {
                recordBreadcrumb("run_resumed_waiting_app_open", petId = petId, details = "signature=$effectiveSignature")
            }
            TamaDeepDreamRunClaimAction.STARTED_NEW -> {
                recordBreadcrumb("run_claimed", petId = petId, details = "signature=$effectiveSignature")
            }
        }

        if (!startForegroundOnce(getString(R.string.tama_deep_dream_progress_title), petId)) {
            if (!force) {
                TamaDeepDreamRunCoordinator.markWaitingAppOpen(
                    database = tamaDatabase,
                    runId = claim.run.id,
                    errorMessage = getString(R.string.tama_notification_deep_dream_retry_on_open_body, pet.name)
                )
                if (claim.action != TamaDeepDreamRunClaimAction.RESUMED_WAITING_APP_OPEN) {
                    UnifiedNotificationManager.showTamaDeepDreamRetryOnOpenNotification(petId, pet.name)
                }
                recordBreadcrumb("run_waiting_app_open", petId = petId, details = "signature=$effectiveSignature")
            } else {
                TamaDeepDreamRunCoordinator.markFailed(
                    database = tamaDatabase,
                    runId = claim.run.id,
                    errorMessage = getString(R.string.error_generic)
                )
            }
            return
        }
        if (!force) {
            TamaNotificationScheduler.markDelivered(
                applicationContext,
                TamaNotificationScheduler.AlertKind.DEEP_DREAM,
                petId,
                effectiveSignature
            )
        }

        val result = runCatching {
            withTimeout(DEEP_DREAM_WATCHDOG_MS) {
                TamaDailyDreamManager.queueDeepIfEligible(
                    context = applicationContext,
                    dao = tamaDao,
                    settingsRepo = settingsRepository,
                    pet = pet,
                    force = force,
                    progressTaskIdOverride = notificationTaskId,
                    stageReporter = { stage ->
                        TamaDeepDreamRunCoordinator.updatePlanningStage(
                            database = tamaDatabase,
                            runId = claim.run.id,
                            stage = stage
                        )
                        recordBreadcrumb("stage_started", petId = petId, details = stage)
                    },
                    llamaOwnershipReporter = { ownsLocalLlama ->
                        TamaDeepDreamRunCoordinator.updateOwnsLocalLlama(
                            database = tamaDatabase,
                            runId = claim.run.id,
                            ownsLocalLlama = ownsLocalLlama
                        )
                        recordBreadcrumb(
                            if (ownsLocalLlama) "owned_local_llama_enabled" else "owned_local_llama_disabled",
                            petId = petId
                        )
                    }
                ).getOrThrow()
            }
        }.getOrElse { error ->
            val normalizedError = if (error is TimeoutCancellationException) {
                IllegalStateException(
                    getString(
                        R.string.tama_deep_dream_error_watchdog_timeout,
                        DEEP_DREAM_WATCHDOG_MS / 60_000L / 60L
                    )
                )
            } else {
                error
            }
            recordBreadcrumb("service_failed", petId = petId, details = normalizedError.message)
            TamaDeepDreamRunCoordinator.markFailed(
                database = tamaDatabase,
                runId = claim.run.id,
                errorMessage = normalizedError.message
            )
            if (!force) {
                TamaNotificationScheduler.clearDelivered(
                    applicationContext,
                    TamaNotificationScheduler.AlertKind.DEEP_DREAM,
                    petId
                )
            }
            notificationTaskId?.let {
                UnifiedNotificationManager.failTask(it, normalizedError.message ?: getString(R.string.error_generic))
            }
            keepTaskNotificationOnExit = true
            return
        } ?: run {
            recordBreadcrumb("service_skipped", petId = petId)
            TamaDeepDreamRunCoordinator.markCancelled(
                database = tamaDatabase,
                runId = claim.run.id,
                errorMessage = getString(R.string.tama_deep_dream_status_skipped)
            )
            if (!force) {
                TamaNotificationScheduler.clearDelivered(
                    applicationContext,
                    TamaNotificationScheduler.AlertKind.DEEP_DREAM,
                    petId
                )
            }
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.tama_deep_dream_status_skipped))
            }
            return
        }

        applyQueuedAlbumToPet(tamaDao, petId, pet.sleepStartTime ?: System.currentTimeMillis(), result.albumId, result.dreamDate)
        TamaDeepDreamRunCoordinator.markQueued(
            database = tamaDatabase,
            runId = claim.run.id,
            albumId = result.albumId
        )
        notificationTaskId?.let {
            UnifiedNotificationManager.completeTask(it, getString(R.string.tama_deep_dream_status_slides_queued))
        }
        recordBreadcrumb("service_completed", petId = petId, details = "albumId=${result.albumId}")
    }

    private suspend fun applyQueuedAlbumToPet(
        tamaDao: com.example.llamadroid.tama.db.TamaDao,
        petId: String,
        sleepStartTime: Long,
        newAlbumId: String,
        dreamDate: String
    ) {
        val currentEntity = tamaDao.getPet(petId) ?: return
        val currentPet = PetMapper.toDomain(currentEntity)
        val existingAlbumId = currentPet.pendingDreamAlbumId
        if (!existingAlbumId.isNullOrBlank() && existingAlbumId != newAlbumId) {
            deleteAlbum(tamaDao, petId, existingAlbumId)
        }
        deleteLatestSleepDreamIfNeeded(tamaDao, petId, sleepStartTime)
        val updatedPet = currentPet.copy(
            lastDailyDreamDate = dreamDate,
            pendingDreamAlbumId = newAlbumId
        )
        tamaDao.savePet(PetMapper.toEntity(updatedPet))
    }

    private suspend fun deleteAlbum(
        tamaDao: com.example.llamadroid.tama.db.TamaDao,
        petId: String,
        albumId: String
    ) {
        tamaDao.getArtworks(petId)
            .filter { it.albumId == albumId }
            .forEach { artwork ->
                TamaArtworkManager.deleteArtworkFile(artwork)
                tamaDao.deleteArtwork(artwork.id)
            }
    }

    private suspend fun deleteLatestSleepDreamIfNeeded(
        tamaDao: com.example.llamadroid.tama.db.TamaDao,
        petId: String,
        sinceMillis: Long
    ) {
        tamaDao.getArtworks(petId)
            .asSequence()
            .filter { it.kind == com.example.llamadroid.tama.data.TamaArtworkKind.DREAM.name }
            .filter { it.sourceActivity == "sleeping" }
            .filter { it.createdAt >= sinceMillis }
            .maxByOrNull(TamaArtworkEntity::createdAt)
            ?.let { artwork ->
                TamaArtworkManager.deleteArtworkFile(artwork)
                tamaDao.deleteArtwork(artwork.id)
            }
    }

    private fun startForegroundOnce(title: String, petId: String): Boolean {
        if (notificationTaskId != null) return true
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SERVER,
            title
        )
        return try {
            notificationTaskId = taskId
            keepTaskNotificationOnExit = false
            startForeground(taskId, notification)
            recordBreadcrumb("foreground_started", petId = petId, details = "taskId=$taskId")
            true
        } catch (error: RuntimeException) {
            val event = if (isForegroundServiceStartNotAllowed(error)) {
                "foreground_start_denied"
            } else if (error.javaClass.name.contains("RemoteServiceException")) {
                "foreground_start_failed_remote"
            } else {
                "foreground_start_failed"
            }
            recordBreadcrumb(
                event,
                petId = petId,
                details = "${error.javaClass.simpleName}:${error.message}"
            )
            UnifiedNotificationManager.failTask(taskId, error.message ?: getString(R.string.error_generic))
            keepTaskNotificationOnExit = true
            notificationTaskId = null
            false
        }
    }

    private fun cleanupForeground(startId: Int) {
        recordBreadcrumb("service_cleanup", petId = activePetId)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        notificationTaskId?.takeIf { !keepTaskNotificationOnExit }?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        keepTaskNotificationOnExit = false
        activePetId = null
        activeRunId = null
        stopSelf(startId)
    }

    private fun buildForceSignature(pet: com.example.llamadroid.tama.data.TamaPet, now: Long): String {
        val dreamDate = TamaDailyDreamManager.formatDreamDate(pet.sleepStartTime ?: now)
        return "deep:$dreamDate:$now:force"
    }

    private fun dreamDateFromSignature(signature: String, fallbackTime: Long): String {
        val parts = signature.split(":")
        return parts.getOrNull(1)?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
            ?: TamaDailyDreamManager.formatDreamDate(fallbackTime)
    }

    private fun recordBreadcrumb(event: String, petId: String? = null, details: String? = null) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "tama_deep_dream_service",
                sessionId = petId,
                mode = "tama_deep_dream",
                event = event,
                details = details
            )
        }
    }

}
