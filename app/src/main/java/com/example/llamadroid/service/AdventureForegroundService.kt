package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.adventure.AdventureService
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdventureGenerationState(
    val petId: String,
    val dungeonTypeName: String,
    val status: String,
    val progress: Float,
    val isBuildingWorld: Boolean
)

/** Last failed generation for a pet/dungeon, shown by the UI with a Retry action. */
data class AdventureGenerationFailure(
    val petId: String,
    val dungeonTypeName: String,
    val message: String,
    val wasWorldBuild: Boolean
)

class AdventureForegroundService : Service() {

    companion object {
        private const val TAG = "AdventureForegroundService"

        const val ACTION_BUILD_WORLD = "build_adventure_world"
        const val ACTION_SUBMIT_CHOICE = "submit_adventure_choice"
        const val ACTION_STOP = "stop_adventure"
        const val ACTION_UPDATE_STATUS = "update_status"

        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_PET_ID = "pet_id"
        const val EXTRA_DUNGEON_TYPE = "dungeon_type"
        const val EXTRA_PLAYER_CHOICE = "player_choice"

        @Volatile
        private var isRunning = false

        private val _generationState = MutableStateFlow<AdventureGenerationState?>(null)
        val generationState: StateFlow<AdventureGenerationState?> = _generationState.asStateFlow()

        private val _generationFailure = MutableStateFlow<AdventureGenerationFailure?>(null)
        val generationFailure: StateFlow<AdventureGenerationFailure?> = _generationFailure.asStateFlow()

        /** Returns false when a generation is already running (nothing is started). */
        fun startWorldBuild(context: Context, petId: String, dungeonTypeName: String): Boolean {
            if (!reserveRun(petId, dungeonTypeName, context.getString(R.string.adventure_status_building_world), true)) return false
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_BUILD_WORLD
                putExtra(EXTRA_PET_ID, petId)
                putExtra(EXTRA_DUNGEON_TYPE, dungeonTypeName)
            }
            return startOrRelease(context, intent, "world build")
        }

        /**
         * Returns false when a generation is already running so the UI can keep the player's text.
         * A blank [playerChoice] retries the choice already saved on the last stage.
         */
        fun submitChoice(
            context: Context,
            petId: String,
            dungeonTypeName: String,
            playerChoice: String
        ): Boolean {
            if (!reserveRun(petId, dungeonTypeName, context.getString(R.string.adventure_status_processing_choice), false)) return false
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_SUBMIT_CHOICE
                putExtra(EXTRA_PET_ID, petId)
                putExtra(EXTRA_DUNGEON_TYPE, dungeonTypeName)
                putExtra(EXTRA_PLAYER_CHOICE, playerChoice)
            }
            return startOrRelease(context, intent, "choice")
        }

        /** Publishes a placeholder state synchronously so a second tap cannot slip in before the service starts. */
        @Synchronized
        private fun reserveRun(petId: String, dungeonTypeName: String, status: String, isBuildingWorld: Boolean): Boolean {
            if (isRunning || _generationState.value != null) return false
            _generationFailure.value = null
            _generationState.value = AdventureGenerationState(petId, dungeonTypeName, status, 0.02f, isBuildingWorld)
            return true
        }

        private fun startOrRelease(context: Context, intent: Intent, label: String): Boolean {
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Failed to start $label service: ${e.message}")
                _generationState.value = null
                false
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, AdventureForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun updateStatus(context: Context, status: String, progress: Float = 0f) {
            if (!isRunning) return
            context.startService(
                Intent(context, AdventureForegroundService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS, status)
                    putExtra(EXTRA_PROGRESS, progress)
                }
            )
        }

        fun isServiceRunning(): Boolean = isRunning
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processingJob: Job? = null
    private var notificationTaskId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var diagnosticSessionId: String? = null
    private var diagnosticMode: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BUILD_WORLD -> {
                val petId = intent.getStringExtra(EXTRA_PET_ID)
                val dungeonTypeName = intent.getStringExtra(EXTRA_DUNGEON_TYPE)
                // Always satisfy the startForegroundService() contract before anything else.
                startForegroundMode(getString(R.string.adventure_status_building_world))
                if (processingJob?.isActive == true) return START_NOT_STICKY
                if (petId.isNullOrBlank() || dungeonTypeName.isNullOrBlank()) {
                    publishState(null)
                    stopForegroundMode()
                    return START_NOT_STICKY
                }
                processingJob = serviceScope.launch {
                    runWorldBuild(petId, dungeonTypeName)
                }
            }

            ACTION_SUBMIT_CHOICE -> {
                val petId = intent.getStringExtra(EXTRA_PET_ID)
                val dungeonTypeName = intent.getStringExtra(EXTRA_DUNGEON_TYPE)
                val playerChoice = intent.getStringExtra(EXTRA_PLAYER_CHOICE)
                startForegroundMode(getString(R.string.adventure_status_processing_choice))
                if (processingJob?.isActive == true) return START_NOT_STICKY
                if (petId.isNullOrBlank() || dungeonTypeName.isNullOrBlank() || playerChoice == null) {
                    publishState(null)
                    stopForegroundMode()
                    return START_NOT_STICKY
                }
                processingJob = serviceScope.launch {
                    runChoiceGeneration(petId, dungeonTypeName, playerChoice)
                }
            }

            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: getString(R.string.adventure_status_generating)
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                updateNotificationStatus(status, progress)
            }

            ACTION_STOP -> {
                finishDiagnosticSession("cancelled", "stop action")
                diagnosticSessionId = null
                diagnosticMode = null
                stopForegroundMode()
            }

            else -> {
                // Null intent (process restart) or unknown action: nothing to resume, no stale idle service.
                if (processingJob?.isActive != true) stopSelf(startId)
            }
        }
        // Generations are user-initiated and re-runnable; never restart an idle service.
        return START_NOT_STICKY
    }

    private suspend fun runWorldBuild(petId: String, dungeonTypeName: String) {
        val dungeonType = runCatching { DungeonType.valueOf(dungeonTypeName) }.getOrDefault(DungeonType.CHAOS_REALM)
        publishState(
            AdventureGenerationState(
                petId = petId,
                dungeonTypeName = dungeonTypeName,
                status = getString(R.string.adventure_status_building_world),
                progress = 0.05f,
                isBuildingWorld = true
            )
        )
        startDiagnosticSession(
            mode = "adventure_world_build",
            details = "petId=$petId dungeon=$dungeonTypeName"
        )
        val database = TamaDatabase.getInstance(applicationContext)
        val service = AdventureService(database, SettingsRepository(applicationContext), applicationContext)
        val result = runCatching {
            service.initializeOrContinue(
                petId = petId,
                dungeonType = dungeonType,
                onProgress = { progress, status ->
                    publishState(
                        AdventureGenerationState(
                            petId = petId,
                            dungeonTypeName = dungeonTypeName,
                            status = status,
                            progress = progress,
                            isBuildingWorld = true
                        )
                    )
                    recordDiagnosticBreadcrumb(
                        event = "progress",
                        phase = status,
                        details = "progress=$progress"
                    )
                    updateNotificationStatus(status, progress)
                }
            )
        }.getOrElse { Result.failure(it) }

        if (result.isSuccess) {
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.adventure_world_ready_notification_body))
            }
            UnifiedNotificationManager.showAdventureReadyNotification(
                title = getString(R.string.adventure_world_ready_notification_title),
                body = getString(R.string.adventure_world_ready_notification_body),
                dungeonTypeName = dungeonTypeName
            )
            finishDiagnosticSession("success", "world build complete")
        } else {
            val message = result.exceptionOrNull()?.message ?: getString(R.string.error_generic)
            notificationTaskId?.let {
                UnifiedNotificationManager.failTask(
                    it,
                    message
                )
            }
            finishDiagnosticSession("failed", message)
            _generationFailure.value = AdventureGenerationFailure(petId, dungeonTypeName, message, wasWorldBuild = true)
        }
        finishRun()
    }

    private suspend fun runChoiceGeneration(petId: String, dungeonTypeName: String, playerChoice: String) {
        val dungeonType = runCatching { DungeonType.valueOf(dungeonTypeName) }.getOrDefault(DungeonType.CHAOS_REALM)
        publishState(
            AdventureGenerationState(
                petId = petId,
                dungeonTypeName = dungeonTypeName,
                status = getString(R.string.adventure_status_processing_choice),
                progress = 0.05f,
                isBuildingWorld = false
            )
        )
        startDiagnosticSession(
            mode = "adventure_choice",
            details = "petId=$petId dungeon=$dungeonTypeName choiceLength=${playerChoice.length}"
        )
        val database = TamaDatabase.getInstance(applicationContext)
        val service = AdventureService(database, SettingsRepository(applicationContext), applicationContext)
        val result = runCatching {
            service.submitChoice(
                petId = petId,
                dungeonType = dungeonType,
                playerChoice = playerChoice,
                onProgress = { progress, status ->
                    publishState(
                        AdventureGenerationState(
                            petId = petId,
                            dungeonTypeName = dungeonTypeName,
                            status = status,
                            progress = progress,
                            isBuildingWorld = false
                        )
                    )
                    recordDiagnosticBreadcrumb(
                        event = "progress",
                        phase = status,
                        details = "progress=$progress"
                    )
                    updateNotificationStatus(status, progress)
                }
            )
        }.getOrElse { Result.failure(it) }

        if (result.isSuccess) {
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.adventure_next_scene_notification_body))
            }
            UnifiedNotificationManager.showAdventureReadyNotification(
                title = getString(R.string.adventure_next_scene_notification_title),
                body = getString(R.string.adventure_next_scene_notification_body),
                dungeonTypeName = dungeonTypeName
            )
            finishDiagnosticSession("success", "choice generation complete")
        } else {
            val message = result.exceptionOrNull()?.message ?: getString(R.string.error_generic)
            notificationTaskId?.let {
                UnifiedNotificationManager.failTask(
                    it,
                    message
                )
            }
            finishDiagnosticSession("failed", message)
            _generationFailure.value = AdventureGenerationFailure(petId, dungeonTypeName, message, wasWorldBuild = false)
        }
        finishRun()
    }

    private fun startForegroundMode(initialStatus: String) {
        if (isRunning) {
            updateNotificationStatus(initialStatus, 0f)
            return
        }
        isRunning = true
        acquireWakeLock()
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ADVENTURE,
            getString(R.string.adventure_service_title)
        )
        notificationTaskId = taskId
        try {
            val startResult = ForegroundStart.start(
                this,
                taskId,
                notification,
                ForegroundServiceTypes.DATA_SYNC
            )
            if (startResult is ForegroundStart.Result.Failed) {
                val message = ForegroundStart.failureMessage(
                    this,
                    startResult.reason,
                    getString(R.string.adventure_service_title)
                )
                runCatching { UnifiedNotificationManager.failTask(taskId, message) }
                throw IllegalStateException(message, startResult.cause)
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to start foreground: ${e.message}")
            isRunning = false
            // Keep the failure notice visible instead of overwriting it with progress updates.
            notificationTaskId = null
        }
        updateNotificationStatus(initialStatus, 0f)
    }

    private fun updateNotificationStatus(status: String, progress: Float) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, progress.coerceIn(0f, 1f), status)
        }
    }

    private fun publishState(state: AdventureGenerationState?) {
        _generationState.value = state
    }

    private fun finishRun() {
        publishState(null)
        processingJob = null
        diagnosticSessionId = null
        diagnosticMode = null
        stopForegroundMode()
    }

    private fun stopForegroundMode() {
        isRunning = false
        releaseWakeLock()
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AI-Doomsday:AdventureForegroundService"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L)
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun startDiagnosticSession(mode: String, details: String) {
        runCatching {
            GenerationDiagnosticsStore.init(applicationContext)
            diagnosticMode = mode
            diagnosticSessionId = GenerationDiagnosticsStore.startSession(
                source = TAG,
                mode = mode,
                details = details,
                phase = "starting",
                wakeLockHeld = wakeLock?.isHeld,
                notificationActive = notificationTaskId != null,
                batteryExempt = null,
                interactive = null,
                powerSaveMode = null
            )
        }.onFailure { error ->
            DebugLog.log("[$TAG] Failed to start diagnostics: ${error.message}")
        }
    }

    private fun recordDiagnosticBreadcrumb(
        event: String,
        phase: String?,
        details: String? = null
    ) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = TAG,
                sessionId = diagnosticSessionId,
                mode = diagnosticMode,
                event = event,
                phase = phase,
                details = details,
                wakeLockHeld = wakeLock?.isHeld,
                notificationActive = notificationTaskId != null,
                batteryExempt = null,
                interactive = null,
                powerSaveMode = null
            )
        }.onFailure { error ->
            DebugLog.log("[$TAG] Failed to record diagnostics: ${error.message}")
        }
    }

    private fun finishDiagnosticSession(outcome: String, details: String?) {
        runCatching {
            GenerationDiagnosticsStore.finishSession(
                sessionId = diagnosticSessionId,
                source = TAG,
                mode = diagnosticMode,
                outcome = outcome,
                details = details,
                wakeLockHeld = wakeLock?.isHeld,
                notificationActive = notificationTaskId != null,
                batteryExempt = null,
                interactive = null,
                powerSaveMode = null
            )
        }.onFailure { error ->
            DebugLog.log("[$TAG] Failed to finish diagnostics: ${error.message}")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        super.onDestroy()
        finishDiagnosticSession("destroyed", "service destroyed")
        processingJob?.cancel()
        serviceScope.cancel()
        publishState(null)
        isRunning = false
        releaseWakeLock()
    }
}
