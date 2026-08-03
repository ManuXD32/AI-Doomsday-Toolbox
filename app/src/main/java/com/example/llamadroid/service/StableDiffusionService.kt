package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.util.AccelerationWorkload
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.WakeLockManager
import com.example.llamadroid.util.getParcelableExtraCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Service to run stable-diffusion.cpp image generation.
 *
 * Generation is intentionally started by explicit service intents so the full
 * lifecycle is owned by the foreground service rather than the screen binder.
 */
class StableDiffusionService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val modeJobs = mutableMapOf<SdWorkLane, Job>()
    private val modeProcesses = mutableMapOf<SdWorkLane, Process>()
    private val modeLifecycleLock = Any()
    private val modeDiagnostics = mutableMapOf<SDMode, ActivityDiagnostics>()
    private val modeSessionIds = mutableMapOf<SdWorkLane, String>()
    private val foregroundTimeoutGate = ForegroundTimeoutGate()
    private val timedOutLanes = mutableSetOf<SdWorkLane>()
    private var workflowJob: Job? = null
    @Volatile
    private var workflowTimedOut = false
    private var notificationTaskId: Int? = null
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var stallMonitorJob: Job? = null
    @Volatile
    private var latestStageTimings = SdStageTimings()

    private val _generationState = kotlinx.coroutines.flow.MutableStateFlow<SDGenerationState>(SDGenerationState.Idle)
    val generationState: kotlinx.coroutines.flow.StateFlow<SDGenerationState> = _generationState

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val progress: kotlinx.coroutines.flow.StateFlow<Float> = _progress

    inner class LocalBinder : Binder() {
        fun getService(): StableDiffusionService = this@StableDiffusionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun laneFor(mode: SDMode, useDistributedStateHolder: Boolean): SdWorkLane =
        SdWorkLane(mode, useDistributedStateHolder)

    private fun storeModeJob(mode: SDMode, useDistributedStateHolder: Boolean, job: Job) {
        synchronized(modeLifecycleLock) {
            modeJobs[laneFor(mode, useDistributedStateHolder)] = job
        }
    }

    private fun removeModeJob(mode: SDMode, useDistributedStateHolder: Boolean): Job? = synchronized(modeLifecycleLock) {
        modeJobs.remove(laneFor(mode, useDistributedStateHolder))
    }

    private fun storeModeProcess(mode: SDMode, useDistributedStateHolder: Boolean, process: Process) {
        synchronized(modeLifecycleLock) {
            modeProcesses[laneFor(mode, useDistributedStateHolder)] = process
        }
    }

    private fun removeModeProcess(mode: SDMode, useDistributedStateHolder: Boolean): Process? = synchronized(modeLifecycleLock) {
        modeProcesses.remove(laneFor(mode, useDistributedStateHolder))
    }

    private fun removeModeRuntime(mode: SDMode, useDistributedStateHolder: Boolean): Pair<Job?, Process?> = synchronized(modeLifecycleLock) {
        val lane = laneFor(mode, useDistributedStateHolder)
        modeJobs.remove(lane) to modeProcesses.remove(lane)
    }

    private fun snapshotModeKeys(): List<SdWorkLane> = synchronized(modeLifecycleLock) {
        modeJobs.keys.toList()
    }

    private fun snapshotModeProcesses(): List<Process> = synchronized(modeLifecycleLock) {
        modeProcesses.values.toList()
    }

    private fun clearAllModeProcesses(): List<Process> = synchronized(modeLifecycleLock) {
        modeProcesses.values.toList().also { modeProcesses.clear() }
    }

    private fun clearAllModeJobs(): List<Job> = synchronized(modeLifecycleLock) {
        modeJobs.values.toList().also { modeJobs.clear() }
    }

    private fun hasActiveModeJobs(): Boolean = synchronized(modeLifecycleLock) {
        modeJobs.values.any { it.isActive }
    }

    private fun hasActiveModeJob(mode: SDMode, useDistributedStateHolder: Boolean): Boolean = synchronized(modeLifecycleLock) {
        modeJobs[laneFor(mode, useDistributedStateHolder)]?.isActive == true
    }

    override fun onCreate() {
        super.onCreate()
        GenerationDiagnosticsStore.init(applicationContext)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AI-Doomsday:StableDiffusion"
        ).apply {
            setReferenceCounted(false)
        }
        recordServiceBreadcrumb("service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordServiceBreadcrumb("start_command", intent?.action)
        when (intent?.action) {
            ACTION_START_GENERATION -> {
                val config = intent.getParcelableExtraCompat<SDConfig>(EXTRA_CONFIG)
                val useWorkflowStateHolder = intent.getBooleanExtra(EXTRA_USE_WORKFLOW_STATE_HOLDER, false)
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                if (config == null) {
                    surfaceStartFailure(getString(R.string.imagegen_error_missing_config))
                } else if (hasActiveModeJob(config.mode, useDistributedStateHolder)) {
                    val message = getString(R.string.imagegen_error_already_running)
                    getModeStateHolder(config.mode, useWorkflowStateHolder, useDistributedStateHolder)
                        .updateState(SDGenerationState.Error(message))
                    _generationState.value = SDGenerationState.Error(message)
                } else {
                    startGenerationSafely(config, useWorkflowStateHolder, useDistributedStateHolder)
                }
            }
            ACTION_START_UPSCALE -> {
                val config = intent.getParcelableExtraCompat<SDUpscaleConfig>(EXTRA_UPSCALE_CONFIG)
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                if (config == null) {
                    surfaceStartFailure(getString(R.string.imagegen_error_missing_config))
                } else if (hasActiveModeJob(SDMode.UPSCALE, useDistributedStateHolder)) {
                    val message = getString(R.string.imagegen_error_already_running)
                    getModeStateHolder(SDMode.UPSCALE, false, useDistributedStateHolder)
                        .updateState(SDGenerationState.Error(message))
                    _generationState.value = SDGenerationState.Error(message)
                } else {
                    startUpscaleSafely(config, useDistributedStateHolder)
                }
            }
            ACTION_START_WORKFLOW -> {
                val workflowConfig = intent.getParcelableExtraCompat<SDWorkflowConfig>(EXTRA_WORKFLOW_CONFIG)
                if (workflowConfig == null) {
                    surfaceStartFailure(getString(R.string.imagegen_error_missing_config))
                } else if (hasActiveWork()) {
                    val message = getString(R.string.imagegen_error_already_running)
                    SDModeStateHolder.workflowTxt2img.updateState(SDGenerationState.Error(message))
                } else {
                    startWorkflowSafely(workflowConfig)
                }
            }
            ACTION_CANCEL_MODE -> {
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { SDMode.valueOf(it) }.getOrNull() }
                    ?.let { cancelMode(it, useDistributedStateHolder) }
            }
            ACTION_CANCEL_WORKFLOW -> cancelWorkflow()
            ACTION_CANCEL_ALL -> cancel()
        }
        return START_NOT_STICKY
    }

    private fun startGenerationSafely(
        config: SDConfig,
        useWorkflowStateHolder: Boolean,
        useDistributedStateHolder: Boolean
    ) {
        val launchDetails = buildSdLaunchBreadcrumbDetails(config)
        recordLaunchBreadcrumb(config.mode, "service_start_requested", launchDetails)

        val binaryPath = BinaryRepository(applicationContext).getSdBinary()?.absolutePath
        val launchIssue = validateSdLaunchInputs(
            mode = config.mode,
            modelPath = config.modelPath,
            inputImagePath = config.initImage,
            sdBinaryPath = binaryPath
        )
        if (launchIssue != null) {
            val message = sdLaunchIssueMessage(this, config.mode, launchIssue)
            recordLaunchBreadcrumb(
                config.mode,
                "service_preflight_failed",
                "$launchDetails issue=${launchIssue.name}"
            )
            surfaceStartFailure(message, config.mode, useWorkflowStateHolder, useDistributedStateHolder)
            return
        }

        runCatching {
            recordLaunchBreadcrumb(config.mode, "service_foreground_starting", launchDetails)
            ensureForegroundTask()
            recordLaunchBreadcrumb(config.mode, "service_generation_handoff", launchDetails)
            startGeneration(config, useWorkflowStateHolder, useDistributedStateHolder)
        }.onFailure { throwable ->
            val message = throwable.message ?: getString(R.string.error_generic)
            DebugLog.log("[StableDiffusionService] Failed to start ${config.mode}: $message")
            recordLaunchBreadcrumb(
                config.mode,
                "service_start_failed",
                "$launchDetails error=${throwable.javaClass.simpleName}: $message"
            )
            surfaceStartFailure(message, config.mode, useWorkflowStateHolder, useDistributedStateHolder)
            failForegroundTask(message)
            cleanupAfterWork()
        }
    }

    private fun startWorkflowSafely(workflowConfig: SDWorkflowConfig) {
        val workflowDetails = buildString {
            append("txt2img{")
            append(buildSdLaunchBreadcrumbDetails(workflowConfig.txt2imgConfig))
            append("} upscale{")
            append(buildSdLaunchBreadcrumbDetails(workflowConfig.upscaleConfig))
            append("}")
        }
        recordServiceBreadcrumb("workflow_start_requested", workflowDetails)
        runCatching {
            recordServiceBreadcrumb("workflow_foreground_starting", workflowDetails)
            ensureForegroundTask()
            recordServiceBreadcrumb("workflow_generation_handoff", workflowDetails)
            startWorkflow(workflowConfig)
        }.onFailure { throwable ->
            val message = throwable.message ?: getString(R.string.error_generic)
            DebugLog.log("[StableDiffusionService] Failed to start workflow: $message")
            recordServiceBreadcrumb(
                "workflow_start_failed",
                "$workflowDetails error=${throwable.javaClass.simpleName}: $message"
            )
            SDModeStateHolder.workflowTxt2img.updateState(SDGenerationState.Error(message))
            SDModeStateHolder.workflowUpscale.updateState(SDGenerationState.Error(message))
            _generationState.value = SDGenerationState.Error(message)
            failForegroundTask(message)
            cleanupAfterWork()
        }
    }

    private fun startUpscaleSafely(config: SDUpscaleConfig, useDistributedStateHolder: Boolean) {
        val launchDetails = buildSdLaunchBreadcrumbDetails(config)
        recordLaunchBreadcrumb(SDMode.UPSCALE, "service_start_requested", launchDetails)

        val binaryPath = BinaryRepository(applicationContext).getSdBinary()?.absolutePath
        val launchIssue = validateSdLaunchInputs(
            mode = SDMode.UPSCALE,
            modelPath = config.modelPath,
            inputImagePath = config.inputImagePath,
            sdBinaryPath = binaryPath
        )
        if (launchIssue != null) {
            val message = sdLaunchIssueMessage(this, SDMode.UPSCALE, launchIssue)
            recordLaunchBreadcrumb(
                SDMode.UPSCALE,
                "service_preflight_failed",
                "$launchDetails issue=${launchIssue.name}"
            )
            surfaceStartFailure(message, SDMode.UPSCALE, false, useDistributedStateHolder)
            return
        }

        runCatching {
            recordLaunchBreadcrumb(SDMode.UPSCALE, "service_foreground_starting", launchDetails)
            ensureForegroundTask()
            recordLaunchBreadcrumb(SDMode.UPSCALE, "service_generation_handoff", launchDetails)
            startUpscale(config, useDistributedStateHolder)
        }.onFailure { throwable ->
            val message = throwable.message ?: getString(R.string.error_generic)
            DebugLog.log("[StableDiffusionService] Failed to start upscale: $message")
            recordLaunchBreadcrumb(
                SDMode.UPSCALE,
                "service_start_failed",
                "$launchDetails error=${throwable.javaClass.simpleName}: $message"
            )
            surfaceStartFailure(message, SDMode.UPSCALE, false, useDistributedStateHolder)
            failForegroundTask(message)
            cleanupAfterWork()
        }
    }

    private fun startGeneration(
        config: SDConfig,
        useWorkflowStateHolder: Boolean,
        useDistributedStateHolder: Boolean
    ) {
        val modeStateHolder = getModeStateHolder(config.mode, useWorkflowStateHolder, useDistributedStateHolder)
        modeStateHolder.updatePrompt(config.prompt)
        ensureWakeLockHeld()
        modeSessionIds[laneFor(config.mode, useDistributedStateHolder)] = GenerationDiagnosticsStore.startSession(
            source = DIAGNOSTIC_SOURCE,
            mode = config.mode.name,
            details = buildSessionDetails(config, useWorkflowStateHolder),
            phase = "starting",
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
        markActivity(config.mode, "starting")
        ensureStallMonitorRunning()

        val lane = laneFor(config.mode, useDistributedStateHolder)
        storeModeJob(config.mode, useDistributedStateHolder, serviceScope.launch {
            try {
                val result = executeGeneration(
                    config = config,
                    modeStateHolder = modeStateHolder,
                    exportSubfolder = subfolderForMode(config.mode),
                    useDistributedStateHolder = useDistributedStateHolder
                )
                markActivity(config.mode, "complete")
                _generationState.value = SDGenerationState.Complete(result)
                _progress.value = 1f
                modeStateHolder.updateState(SDGenerationState.Complete(result))
                finishModeSession(config.mode, useDistributedStateHolder, "complete", File(config.modelPath).name)
                completeForegroundTask(getString(R.string.imagegen_notification_complete))
            } catch (cancelled: CancellationException) {
                if (isTimedOutLane(lane)) {
                    val message = timeoutMessage(cancelled)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_cancelled")
                    DebugLog.log("[StableDiffusionService] ${config.mode} stopped after foreground timeout")
                } else {
                    markActivity(config.mode, "cancelled")
                    finishModeSession(config.mode, useDistributedStateHolder, "cancelled")
                    DebugLog.log("[StableDiffusionService] ${config.mode} cancelled")
                }
            } catch (e: Exception) {
                if (isTimedOutLane(lane)) {
                    val message = getString(R.string.imagegen_error_media_processing_timeout)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_failed")
                    DebugLog.log(
                        "[StableDiffusionService] ${config.mode} native process exited during foreground timeout: ${e.message}"
                    )
                } else {
                    val message = e.message ?: getString(R.string.error_generic)
                    markActivity(config.mode, "failed")
                    DebugLog.log("[StableDiffusionService] ${config.mode} failed: $message")
                    _generationState.value = SDGenerationState.Error(message)
                    modeStateHolder.updateState(SDGenerationState.Error(message))
                    finishModeSession(config.mode, useDistributedStateHolder, "failed", message)
                    failForegroundTask(message)
                }
            } finally {
                removeModeProcess(config.mode, useDistributedStateHolder)
                removeModeJob(config.mode, useDistributedStateHolder)
                clearTimedOutLane(lane)
                clearDiagnostics(config.mode)
                cleanupAfterWork()
            }
        })
    }

    private fun startUpscale(config: SDUpscaleConfig, useDistributedStateHolder: Boolean) {
        val modeStateHolder = getModeStateHolder(SDMode.UPSCALE, false, useDistributedStateHolder)
        ensureWakeLockHeld()
        modeSessionIds[laneFor(SDMode.UPSCALE, useDistributedStateHolder)] = GenerationDiagnosticsStore.startSession(
            source = DIAGNOSTIC_SOURCE,
            mode = SDMode.UPSCALE.name,
            details = buildUpscaleSessionDetails(config),
            phase = "starting",
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
        markActivity(SDMode.UPSCALE, "starting")
        ensureStallMonitorRunning()

        val lane = laneFor(SDMode.UPSCALE, useDistributedStateHolder)
        storeModeJob(SDMode.UPSCALE, useDistributedStateHolder, serviceScope.launch {
            try {
                val result = executeUpscaleGeneration(
                    config = config,
                    modeStateHolder = modeStateHolder,
                    useDistributedStateHolder = useDistributedStateHolder
                )
                markActivity(SDMode.UPSCALE, "complete")
                _generationState.value = SDGenerationState.Complete(result)
                _progress.value = 1f
                modeStateHolder.updateState(SDGenerationState.Complete(result))
                finishModeSession(SDMode.UPSCALE, useDistributedStateHolder, "complete", File(config.modelPath).name)
                completeForegroundTask(getString(R.string.imagegen_notification_complete))
            } catch (cancelled: CancellationException) {
                if (isTimedOutLane(lane)) {
                    val message = timeoutMessage(cancelled)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_cancelled")
                    DebugLog.log("[StableDiffusionService] ${SDMode.UPSCALE} stopped after foreground timeout")
                } else {
                    markActivity(SDMode.UPSCALE, "cancelled")
                    finishModeSession(SDMode.UPSCALE, useDistributedStateHolder, "cancelled")
                    DebugLog.log("[StableDiffusionService] ${SDMode.UPSCALE} cancelled")
                }
            } catch (e: Exception) {
                if (isTimedOutLane(lane)) {
                    val message = getString(R.string.imagegen_error_media_processing_timeout)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_failed")
                    DebugLog.log(
                        "[StableDiffusionService] ${SDMode.UPSCALE} native process exited during foreground timeout: ${e.message}"
                    )
                } else {
                    val message = e.message ?: getString(R.string.error_generic)
                    markActivity(SDMode.UPSCALE, "failed")
                    DebugLog.log("[StableDiffusionService] ${SDMode.UPSCALE} failed: $message")
                    _generationState.value = SDGenerationState.Error(message)
                    modeStateHolder.updateState(SDGenerationState.Error(message))
                    finishModeSession(SDMode.UPSCALE, useDistributedStateHolder, "failed", message)
                    failForegroundTask(message)
                }
            } finally {
                removeModeProcess(SDMode.UPSCALE, useDistributedStateHolder)
                removeModeJob(SDMode.UPSCALE, useDistributedStateHolder)
                clearTimedOutLane(lane)
                clearDiagnostics(SDMode.UPSCALE)
                cleanupAfterWork()
            }
        })
    }

    private fun startWorkflow(workflowConfig: SDWorkflowConfig) {
        val txt2imgHolder = SDModeStateHolder.workflowTxt2img
        val upscaleHolder = SDModeStateHolder.workflowUpscale
        txt2imgHolder.reset()
        upscaleHolder.reset()
        txt2imgHolder.updatePrompt(workflowConfig.txt2imgConfig.prompt)
        ensureWakeLockHeld()
        modeSessionIds[laneFor(SDMode.TXT2IMG, false)] = GenerationDiagnosticsStore.startSession(
            source = DIAGNOSTIC_SOURCE,
            mode = SDMode.TXT2IMG.name,
            details = buildSessionDetails(workflowConfig.txt2imgConfig, true),
            phase = "workflow-starting",
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
        markActivity(SDMode.TXT2IMG, "workflow-starting")
        ensureStallMonitorRunning()

        workflowJob = serviceScope.launch {
            try {
                val txt2imgOutput = executeGeneration(
                    config = workflowConfig.txt2imgConfig,
                    modeStateHolder = txt2imgHolder,
                    exportSubfolder = null,
                    useDistributedStateHolder = false
                )
                txt2imgHolder.updateState(SDGenerationState.Complete(txt2imgOutput))
                finishModeSession(SDMode.TXT2IMG, false, "complete", File(workflowConfig.txt2imgConfig.modelPath).name)

                modeSessionIds[laneFor(SDMode.UPSCALE, false)] = GenerationDiagnosticsStore.startSession(
                    source = DIAGNOSTIC_SOURCE,
                    mode = SDMode.UPSCALE.name,
                    details = buildSessionDetails(workflowConfig.upscaleConfig, true),
                    phase = "starting",
                    wakeLockHeld = wakeLock?.isHeld == true,
                    notificationActive = notificationTaskId != null,
                    batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
                    interactive = powerManager.isInteractive,
                    powerSaveMode = powerManager.isPowerSaveMode
                )

                val finalOutput = executeGeneration(
                    config = workflowConfig.upscaleConfig,
                    modeStateHolder = upscaleHolder,
                    exportSubfolder = WORKFLOW_OUTPUT_SUBFOLDER,
                    useDistributedStateHolder = false
                )

                markActivity(SDMode.UPSCALE, "complete")
                upscaleHolder.updateState(SDGenerationState.Complete(finalOutput))
                finishModeSession(SDMode.UPSCALE, false, "complete", File(workflowConfig.upscaleConfig.modelPath).name)
                completeForegroundTask(getString(R.string.workflow_complete))
            } catch (cancelled: CancellationException) {
                if (workflowTimedOut) {
                    val message = timeoutMessage(cancelled)
                    publishWorkflowTimeout(message, event = "foreground_timeout_cancelled")
                    DebugLog.log("[StableDiffusionService] Workflow stopped after foreground timeout")
                } else {
                    markActivity(SDMode.TXT2IMG, "workflow-cancelled")
                    markActivity(SDMode.UPSCALE, "workflow-cancelled")
                    finishModeSession(SDMode.TXT2IMG, false, "cancelled")
                    finishModeSession(SDMode.UPSCALE, false, "cancelled")
                    DebugLog.log("[StableDiffusionService] Workflow cancelled")
                }
            } catch (e: Exception) {
                if (workflowTimedOut) {
                    val message = getString(R.string.imagegen_error_media_processing_timeout)
                    publishWorkflowTimeout(message, event = "foreground_timeout_failed")
                    DebugLog.log(
                        "[StableDiffusionService] Workflow native process exited during foreground timeout: ${e.message}"
                    )
                } else {
                    val message = e.message ?: getString(R.string.error_generic)
                    markActivity(SDMode.TXT2IMG, "workflow-failed")
                    markActivity(SDMode.UPSCALE, "workflow-failed")
                    finishModeSession(SDMode.TXT2IMG, false, "failed", message)
                    finishModeSession(SDMode.UPSCALE, false, "failed", message)
                    DebugLog.log("[StableDiffusionService] Workflow failed: $message")
                    val activeHolder = when {
                        upscaleHolder.state.value is SDGenerationState.Generating -> upscaleHolder
                        else -> txt2imgHolder
                    }
                    activeHolder.updateState(SDGenerationState.Error(message))
                    failForegroundTask(message)
                }
            } finally {
                workflowJob = null
                workflowTimedOut = false
                clearDiagnostics(SDMode.TXT2IMG)
                clearDiagnostics(SDMode.UPSCALE)
                cleanupAfterWork()
            }
        }
    }

    private suspend fun executeGeneration(
        config: SDConfig,
        modeStateHolder: SDModeStateHolder,
        exportSubfolder: String?,
        useDistributedStateHolder: Boolean
    ): String = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        latestStageTimings = SdStageTimings()
        val startingSnapshot = buildStartingSnapshot(config)
        _generationState.value = SDGenerationState.Generating(startingSnapshot)
        _progress.value = 0f
        modeStateHolder.updateState(SDGenerationState.Generating(startingSnapshot))
        modeStateHolder.updateTotalSteps(startingSnapshot.totalSteps)
        updateNotification(buildImageNotificationText(config.mode, startingSnapshot), startingSnapshot.progress)
        markActivity(config.mode, "starting")

        val result = runGeneration(config, modeStateHolder, useDistributedStateHolder)
        val outputFile = File(result)
        markActivity(config.mode, "post-processing")
        postProcessOutputIfNeeded(config, outputFile)
        markActivity(config.mode, "exporting")
        exportImageIfConfigured(outputFile, exportSubfolder)
        writeImageMetadata(config, outputFile, SystemClock.elapsedRealtime() - startedAtMs, latestStageTimings)
        markActivity(config.mode, "generated")
        clearDiagnostics(config.mode)
        result
    }

    private suspend fun executeUpscaleGeneration(
        config: SDUpscaleConfig,
        modeStateHolder: SDModeStateHolder,
        useDistributedStateHolder: Boolean
    ): String = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val startingSnapshot = buildStartingSnapshot(config.upscaleRepeats.coerceAtLeast(1))
        _generationState.value = SDGenerationState.Generating(startingSnapshot)
        _progress.value = 0f
        modeStateHolder.updateState(SDGenerationState.Generating(startingSnapshot))
        modeStateHolder.updateTotalSteps(startingSnapshot.totalSteps)
        updateNotification(buildImageNotificationText(SDMode.UPSCALE, startingSnapshot), startingSnapshot.progress)
        markActivity(SDMode.UPSCALE, "starting")

        val result = runUpscaleGeneration(config, modeStateHolder, useDistributedStateHolder)
        val outputFile = File(result)
        markActivity(SDMode.UPSCALE, "exporting")
        exportImageIfConfigured(outputFile, subfolderForMode(SDMode.UPSCALE))
        writeUpscaleImageMetadata(config, outputFile, SystemClock.elapsedRealtime() - startedAtMs)
        markActivity(SDMode.UPSCALE, "generated")
        clearDiagnostics(SDMode.UPSCALE)
        result
    }

    private suspend fun runGeneration(
        config: SDConfig,
        modeStateHolder: SDModeStateHolder,
        useDistributedStateHolder: Boolean
    ): String = withContext(Dispatchers.IO) {
        SdGenerationProcessLock.withLock {
        val binaryRepo = BinaryRepository(applicationContext)
        val sdBinary = binaryRepo.getSdBinary()

        if (sdBinary == null || !sdBinary.exists()) {
            throw IllegalStateException(getString(R.string.video_gen_error_sd_binary_missing))
        }
        runCatching {
            runGenerationWithBinary(config, modeStateHolder, binaryRepo, sdBinary, useDistributedStateHolder)
        }.getOrElse { error ->
            val cpuBinary = binaryRepo.getCpuSdBinary()
            if (shouldRetrySdGenerationOnCpu(sdBinary, cpuBinary, config.distributedRuntime, error)) {
                DebugLog.log("[StableDiffusionService] Accelerator SD binary failed, retrying CPU fallback: ${error.message}")
                runGenerationWithBinary(config, modeStateHolder, binaryRepo, requireNotNull(cpuBinary), useDistributedStateHolder)
            } else {
                throw error
            }
        }
        }
    }

    private suspend fun runGenerationWithBinary(
        config: SDConfig,
        modeStateHolder: SDModeStateHolder,
        binaryRepo: BinaryRepository,
        sdBinary: File,
        useDistributedStateHolder: Boolean
    ): String = coroutineScope {
        validateOptionalComponentFile(
            config.loraPath,
            getString(R.string.sd_type_lora)
        )
        validateOptionalComponentFile(
            config.textualInversionPath,
            getString(R.string.sd_type_textual_inversion)
        )
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.STABLE_DIFFUSION, sdBinary)

        val binaryCapabilities = probeSdBinaryCapabilities(applicationContext, sdBinary, binaryRepo)
        val args = mutableListOf(sdBinary.absolutePath)
        val effectiveConfig = config.copy(
            maxVramCpuGiB = effectiveSdMaxVramCpuGiBForBinary(sdBinary, config.maxVramCpuGiB),
            sdRuntimeBackendMode = effectiveSdRuntimeBackendModeForBinary(sdBinary, config.sdRuntimeBackendMode)
        )
        try {
            args.addAll(
                buildSdCommandArgs(
                    config = effectiveConfig,
                    binaryCapabilities = binaryCapabilities
                )
            )
        } catch (e: SdMissingComponentsException) {
            throw IllegalStateException(
                getString(
                    R.string.imagegen_error_missing_required_components,
                    e.roles.joinToString(", ") { componentRoleLabel(it) }
                )
            )
        } catch (e: SdUnsupportedFlagsException) {
            throw IllegalStateException(
                getString(
                    R.string.imagegen_error_binary_missing_flags,
                    e.flags.joinToString(", ")
                )
            )
        } catch (e: SdDisallowedDistributedFlagException) {
            throw IllegalStateException(getString(R.string.sd_dist_error_row_split_not_supported))
        }
        DebugLog.log("[StableDiffusionService] Running command: ${args.joinToString(" ")}")
        if (config.mode == SDMode.UPSCALE) {
            recordModeBreadcrumb(
                mode = config.mode,
                event = "command_prepared",
                phase = "launching",
                details = args.joinToString(" ").take(COMMAND_BREADCRUMB_LIMIT)
            )
        }

        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(sdBinary.parentFile)

        val libDir = File(applicationContext.filesDir, "lib").apply { mkdirs() }
        setupSdLibrarySymlinks(sdBinary.parentFile, libDir, sdBinary.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = sdProcessLibraryPath(binaryRepo, sdBinary, libDir)

        val process = pb.start()
        storeModeProcess(config.mode, useDistributedStateHolder, process)
        val progressTracker = SdProgressTracker(
            totalStepsHint = when (config.mode) {
                SDMode.UPSCALE -> config.upscaleRepeats.coerceAtLeast(1)
                else -> config.steps.coerceAtLeast(1)
            },
            startedAtMs = SystemClock.elapsedRealtime()
        )
        val etaTickerJob = launch {
            while (isActive) {
                delay(1000)
                progressTracker.tick(SystemClock.elapsedRealtime())?.let { liveSnapshot ->
                    val snapshot = liveSnapshot.copy(
                        statusText = withStageTimings(buildImageProgressStatus(liveSnapshot), latestStageTimings)
                    )
                    _progress.value = snapshot.progress
                    _generationState.value = SDGenerationState.Generating(snapshot)
                    modeStateHolder.updateState(SDGenerationState.Generating(snapshot))
                    modeStateHolder.updateTotalSteps(snapshot.totalSteps)
                    updateNotification(
                        buildImageNotificationText(config.mode, snapshot),
                        snapshot.progress
                    )
                }
            }
        }

        try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    markActivity(config.mode, "generating")
                    DebugLog.log("SD: $line")
                    latestStageTimings = latestStageTimings.withLine(line)
                    progressTracker.update(line, SystemClock.elapsedRealtime())?.let { parsedSnapshot ->
                        val snapshot = parsedSnapshot.copy(
                            statusText = withStageTimings(buildImageProgressStatus(parsedSnapshot), latestStageTimings)
                        )
                        _progress.value = snapshot.progress
                        _generationState.value = SDGenerationState.Generating(snapshot)
                        modeStateHolder.updateState(SDGenerationState.Generating(snapshot))
                        modeStateHolder.updateTotalSteps(snapshot.totalSteps)
                        updateNotification(
                            buildImageNotificationText(config.mode, snapshot),
                            snapshot.progress
                        )
                    }

                    line = reader.readLine()
                }
            }
        } finally {
            etaTickerJob.cancel()
        }

        val exitCode = process.waitFor()
        DebugLog.log("[StableDiffusionService] Process exited with code $exitCode")
        if (exitCode != 0) {
            if (DeviceAcceleration.isAcceleratorBinary(sdBinary)) {
                val detail = "Stable Diffusion accelerator ${sdBinary.name} failed with exit code $exitCode."
                DebugLog.log("[StableDiffusionService] $detail")
                DeviceAcceleration.reportRuntimeFailure(AccelerationWorkload.STABLE_DIFFUSION, detail)
            }
            throw RuntimeException(getString(R.string.imagegen_error_generation_failed, exitCode))
        }

        config.outputPath
    }

    private suspend fun runUpscaleGeneration(
        config: SDUpscaleConfig,
        modeStateHolder: SDModeStateHolder,
        useDistributedStateHolder: Boolean
    ): String = withContext(Dispatchers.IO) {
        SdGenerationProcessLock.withLock {
        val binaryRepo = BinaryRepository(applicationContext)
        val sdBinary = binaryRepo.getSdBinary()

        if (sdBinary == null || !sdBinary.exists()) {
            throw IllegalStateException(getString(R.string.video_gen_error_sd_binary_missing))
        }
        runCatching {
            runUpscaleGenerationWithBinary(config, modeStateHolder, binaryRepo, sdBinary, useDistributedStateHolder)
        }.getOrElse { error ->
            val cpuBinary = binaryRepo.getCpuSdBinary()
            if (shouldRetrySdGenerationOnCpu(sdBinary, cpuBinary, config.distributedRuntime, error)) {
                DebugLog.log("[StableDiffusionService] Accelerator SD upscale binary failed, retrying CPU fallback: ${error.message}")
                runUpscaleGenerationWithBinary(config, modeStateHolder, binaryRepo, requireNotNull(cpuBinary), useDistributedStateHolder)
            } else {
                throw error
            }
        }
        }
    }

    private fun validateOptionalComponentFile(path: String?, label: String) {
        val selectedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val selectedFile = File(selectedPath)
        if (!selectedFile.isFile) {
            throw IllegalStateException(
                getString(
                    R.string.imagegen_error_component_file_missing,
                    label,
                    selectedPath
                )
            )
        }
    }

    private suspend fun runUpscaleGenerationWithBinary(
        config: SDUpscaleConfig,
        modeStateHolder: SDModeStateHolder,
        binaryRepo: BinaryRepository,
        sdBinary: File,
        useDistributedStateHolder: Boolean
    ): String = coroutineScope {
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.STABLE_DIFFUSION, sdBinary)

        val binaryCapabilities = probeSdBinaryCapabilities(applicationContext, sdBinary, binaryRepo)
        val args = mutableListOf(sdBinary.absolutePath)
        val effectiveConfig = config.copy(
            maxVramCpuGiB = effectiveSdMaxVramCpuGiBForBinary(sdBinary, config.maxVramCpuGiB)
        )
        try {
            args.addAll(buildSdUpscaleCommandArgs(effectiveConfig, binaryCapabilities))
        } catch (e: SdUnsupportedFlagsException) {
            throw IllegalStateException(
                getString(
                    R.string.imagegen_error_binary_missing_flags,
                    e.flags.joinToString(", ")
                )
            )
        } catch (e: SdDisallowedDistributedFlagException) {
            throw IllegalStateException(getString(R.string.sd_dist_error_row_split_not_supported))
        }

        DebugLog.log("[StableDiffusionService] Running upscale command: ${args.joinToString(" ")}")
        recordModeBreadcrumb(
            mode = SDMode.UPSCALE,
            event = "command_prepared",
            phase = "launching",
            details = args.joinToString(" ").take(COMMAND_BREADCRUMB_LIMIT)
        )

        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(sdBinary.parentFile)

        val libDir = File(applicationContext.filesDir, "lib").apply { mkdirs() }
        setupSdLibrarySymlinks(sdBinary.parentFile, libDir, sdBinary.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = sdProcessLibraryPath(binaryRepo, sdBinary, libDir)

        val process = pb.start()
        storeModeProcess(SDMode.UPSCALE, useDistributedStateHolder, process)
        val progressTracker = SdProgressTracker(
            totalStepsHint = config.upscaleRepeats.coerceAtLeast(1),
            startedAtMs = SystemClock.elapsedRealtime()
        )
        val etaTickerJob = launch {
            while (isActive) {
                delay(1000)
                progressTracker.tick(SystemClock.elapsedRealtime())?.let { liveSnapshot ->
                    val snapshot = liveSnapshot.copy(
                        statusText = buildImageProgressStatus(liveSnapshot)
                    )
                    _progress.value = snapshot.progress
                    _generationState.value = SDGenerationState.Generating(snapshot)
                    modeStateHolder.updateState(SDGenerationState.Generating(snapshot))
                    modeStateHolder.updateTotalSteps(snapshot.totalSteps)
                    updateNotification(
                        buildImageNotificationText(SDMode.UPSCALE, snapshot),
                        snapshot.progress
                    )
                }
            }
        }

        try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    markActivity(SDMode.UPSCALE, "generating")
                    DebugLog.log("SD: $line")
                    progressTracker.update(line, SystemClock.elapsedRealtime())?.let { parsedSnapshot ->
                        val snapshot = parsedSnapshot.copy(
                            statusText = buildImageProgressStatus(parsedSnapshot)
                        )
                        _progress.value = snapshot.progress
                        _generationState.value = SDGenerationState.Generating(snapshot)
                        modeStateHolder.updateState(SDGenerationState.Generating(snapshot))
                        modeStateHolder.updateTotalSteps(snapshot.totalSteps)
                        updateNotification(
                            buildImageNotificationText(SDMode.UPSCALE, snapshot),
                            snapshot.progress
                        )
                    }

                    line = reader.readLine()
                }
            }
        } finally {
            etaTickerJob.cancel()
        }

        val exitCode = process.waitFor()
        DebugLog.log("[StableDiffusionService] Upscale process exited with code $exitCode")
        if (exitCode != 0) {
            if (DeviceAcceleration.isAcceleratorBinary(sdBinary)) {
                val detail = "Stable Diffusion accelerator ${sdBinary.name} failed during upscale with exit code $exitCode."
                DebugLog.log("[StableDiffusionService] $detail")
                DeviceAcceleration.reportRuntimeFailure(AccelerationWorkload.STABLE_DIFFUSION, detail)
            }
            throw RuntimeException(getString(R.string.imagegen_error_generation_failed, exitCode))
        }

        config.outputPath
    }

    fun cancelMode(mode: SDMode, useDistributedStateHolder: Boolean = false) {
        markActivity(mode, "cancel-requested")
        recordModeBreadcrumb(mode, "cancel_requested")
        val (job, process) = removeModeRuntime(mode, useDistributedStateHolder)
        job?.cancel(CancellationException(getString(R.string.action_cancelled)))
        process?.destroy()
        clearDiagnostics(mode)

        getModeStateHolder(mode, false, useDistributedStateHolder).updateState(SDGenerationState.Idle)
        _generationState.value = SDGenerationState.Idle
        _progress.value = 0f

        if (!hasActiveWork()) {
            dismissForegroundTask()
            cleanupAfterWork()
        }
    }

    fun cancelWorkflow() {
        markActivity(SDMode.TXT2IMG, "workflow-cancel-requested")
        markActivity(SDMode.UPSCALE, "workflow-cancel-requested")
        recordModeBreadcrumb(SDMode.TXT2IMG, "workflow_cancel_requested")
        recordModeBreadcrumb(SDMode.UPSCALE, "workflow_cancel_requested")
        workflowJob?.cancel(CancellationException(getString(R.string.action_cancelled)))
        workflowJob = null
        clearAllModeProcesses().forEach { it.destroy() }
        clearDiagnostics(SDMode.TXT2IMG)
        clearDiagnostics(SDMode.UPSCALE)
        SDModeStateHolder.workflowTxt2img.reset()
        SDModeStateHolder.workflowUpscale.reset()
        dismissForegroundTask()
        cleanupAfterWork()
    }

    fun cancel() {
        snapshotModeKeys().forEach { lane -> cancelMode(lane.mode, lane.useDistributedStateHolder) }
        cancelWorkflow()
    }

    private fun ensureForegroundTask() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.IMAGE_GEN,
            getString(R.string.imagegen_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        recordServiceBreadcrumb("foreground_started", "taskId=$taskId")
    }

    private fun updateNotification(text: String, progress: Float = 0f) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, progress.coerceIn(0f, 1f), text)
        }
    }

    private fun buildStartingSnapshot(config: SDConfig): SdProgressSnapshot {
        val totalSteps = when (config.mode) {
            SDMode.UPSCALE -> config.upscaleRepeats.coerceAtLeast(1)
            else -> config.steps.coerceAtLeast(1)
        }
        return buildStartingSnapshot(totalSteps)
    }

    private fun buildStartingSnapshot(totalSteps: Int): SdProgressSnapshot {
        return SdProgressTracker.buildStartingSnapshot(
            totalSteps = totalSteps,
            statusText = getString(R.string.gen_status_calculating_eta)
        )
    }

    private fun buildImageProgressStatus(snapshot: SdProgressSnapshot): String {
        return if (snapshot.etaSeconds == null) {
            getString(R.string.gen_status_calculating_eta)
        } else {
            getString(
                if (snapshot.phase == SdProgressPhase.VAE) {
                    R.string.gen_status_vae_progress_with_eta
                } else {
                    R.string.gen_status_diffusion_progress_with_eta
                },
                SdProgressTracker.progressPercent(snapshot),
                formatEtaShort(snapshot.etaSeconds)
            )
        }
    }

    private fun buildImageNotificationText(mode: SDMode, snapshot: SdProgressSnapshot): String {
        return getString(
            R.string.imagegen_notification_status,
            modeLabel(mode),
            snapshot.statusText.ifBlank { getString(R.string.gen_status_calculating_eta) }
        )
    }

    private fun formatEtaShort(etaSeconds: Double): String {
        val roundedSeconds = etaSeconds.coerceAtLeast(0.0).roundToInt()
        return when {
            roundedSeconds < 60 -> getString(R.string.gen_eta_seconds_short, roundedSeconds)
            roundedSeconds < 3600 -> getString(
                R.string.gen_eta_minutes_seconds_short,
                roundedSeconds / 60,
                roundedSeconds % 60
            )
            else -> getString(
                R.string.gen_eta_hours_minutes_short,
                roundedSeconds / 3600,
                (roundedSeconds % 3600) / 60
            )
        }
    }

    private fun completeForegroundTask(message: String) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.completeTask(taskId, message)
            stopForegroundSafely("complete")
            notificationTaskId = null
            recordServiceBreadcrumb("foreground_completed", message)
        }
    }

    private fun failForegroundTask(message: String) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.failTask(taskId, message)
            stopForegroundSafely("fail")
            notificationTaskId = null
            recordServiceBreadcrumb("foreground_failed", message)
        }
    }

    private fun dismissForegroundTask() {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
            stopForegroundSafely("dismiss")
            notificationTaskId = null
            recordServiceBreadcrumb("foreground_dismissed", "taskId=$taskId")
        }
    }

    private fun stopForegroundSafely(reason: String) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { error ->
                DebugLog.log("[StableDiffusionService] stopForeground failed during $reason: ${error.message}")
            }
    }

    private fun surfaceStartFailure(
        message: String,
        mode: SDMode? = null,
        useWorkflowStateHolder: Boolean = false,
        useDistributedStateHolder: Boolean = false
    ) {
        DebugLog.log("[StableDiffusionService] Start failure: $message")
        mode?.let {
            getModeStateHolder(it, useWorkflowStateHolder, useDistributedStateHolder)
                .updateState(SDGenerationState.Error(message))
        }
        _generationState.value = SDGenerationState.Error(message)
        _progress.value = 0f
        recordServiceBreadcrumb("start_failure", message)
    }

    private fun ensureWakeLockHeld() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
            DebugLog.log("[StableDiffusionService] WakeLock acquired")
            recordServiceBreadcrumb("wake_lock_acquired")
        }
        WakeLockManager.acquireWifiLock(applicationContext, "StableDiffusionService")
    }

    private fun releaseWakeLockIfIdle() {
        if (!hasActiveWork()) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[StableDiffusionService] WakeLock released")
                recordServiceBreadcrumb("wake_lock_released")
            }
            WakeLockManager.releaseWifiLock("StableDiffusionService")
        }
    }

    private fun ensureStallMonitorRunning() {
        if (stallMonitorJob?.isActive == true) return
        stallMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(STALL_MONITOR_INTERVAL_MS)
                if (!hasActiveWork()) break
                val now = SystemClock.elapsedRealtime()
                modeDiagnostics.toMap().forEach { (mode, diagnostics) ->
                    val gapMs = now - diagnostics.lastActivityElapsedMs
                    val bucket = stallBucketForGap(gapMs)
                    if (bucket > diagnostics.lastLoggedStallBucket) {
                        diagnostics.lastLoggedStallBucket = bucket
                        val message =
                            "gap=${formatDuration(gapMs)} wakeLockHeld=${wakeLock?.isHeld == true} " +
                                "workflowActive=${workflowJob?.isActive == true} " +
                                "batteryExempt=${powerManager.isIgnoringBatteryOptimizations(packageName)} " +
                                "interactive=${powerManager.isInteractive} powerSave=${powerManager.isPowerSaveMode}"
                        DebugLog.log(
                            "[StableDiffusionService] Stall detected: mode=${mode.name} phase=${diagnostics.phase} $message"
                        )
                        recordModeBreadcrumb(
                            mode = mode,
                            event = "stall_detected",
                            phase = diagnostics.phase,
                            details = message
                        )
                    }
                }
            }
        }
    }

    private fun stopStallMonitorIfIdle() {
        if (!hasActiveWork()) {
            stallMonitorJob?.cancel()
            stallMonitorJob = null
        }
    }

    private fun cleanupAfterWork() {
        releaseWakeLockIfIdle()
        stopStallMonitorIfIdle()
        if (!hasActiveWork()) {
            modeDiagnostics.clear()
            DeviceAcceleration.reportActiveBinary(AccelerationWorkload.STABLE_DIFFUSION, null)
        }
    }

    private fun hasActiveWork(): Boolean =
        workflowJob?.isActive == true || hasActiveModeJobs()

    private fun markActivity(mode: SDMode, phase: String) {
        val now = SystemClock.elapsedRealtime()
        val diagnostics = modeDiagnostics[mode]
        if (diagnostics == null) {
            modeDiagnostics[mode] = ActivityDiagnostics(
                phase = phase,
                lastActivityElapsedMs = now
            )
            recordModeBreadcrumb(mode, "phase_changed", phase = phase)
            return
        }

        val gapMs = now - diagnostics.lastActivityElapsedMs
        if (diagnostics.lastLoggedStallBucket > 0 && gapMs >= STALL_THRESHOLD_MS_1) {
            val message =
                "afterGap=${formatDuration(gapMs)} wakeLockHeld=${wakeLock?.isHeld == true} " +
                    "workflowActive=${workflowJob?.isActive == true} " +
                    "batteryExempt=${powerManager.isIgnoringBatteryOptimizations(packageName)} " +
                    "interactive=${powerManager.isInteractive} powerSave=${powerManager.isPowerSaveMode}"
            DebugLog.log(
                "[StableDiffusionService] Activity resumed: mode=${mode.name} phase=$phase $message"
            )
            recordModeBreadcrumb(mode, "activity_resumed", phase = phase, details = message)
        }

        val phaseChanged = diagnostics.phase != phase
        diagnostics.phase = phase
        diagnostics.lastActivityElapsedMs = now
        diagnostics.lastLoggedStallBucket = 0
        if (phaseChanged) {
            recordModeBreadcrumb(mode, "phase_changed", phase = phase)
        }
    }

    private fun clearDiagnostics(mode: SDMode) {
        modeDiagnostics.remove(mode)
    }

    private fun stallBucketForGap(gapMs: Long): Int = when {
        gapMs >= STALL_THRESHOLD_MS_3 -> 3
        gapMs >= STALL_THRESHOLD_MS_2 -> 2
        gapMs >= STALL_THRESHOLD_MS_1 -> 1
        else -> 0
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m${seconds.toString().padStart(2, '0')}s"
        } else {
            "${totalSeconds}s"
        }
    }

    private fun getModeStateHolder(
        mode: SDMode,
        useWorkflowStateHolder: Boolean,
        useDistributedStateHolder: Boolean = false
    ): SDModeStateHolder =
        if (useDistributedStateHolder) {
            SDModeStateHolder.getDistributedForMode(mode)
        } else if (useWorkflowStateHolder) {
            when (mode) {
                SDMode.TXT2IMG -> SDModeStateHolder.workflowTxt2img
                SDMode.UPSCALE -> SDModeStateHolder.workflowUpscale
                SDMode.IMG2IMG -> SDModeStateHolder.getForMode(mode)
            }
        } else {
            SDModeStateHolder.getForMode(mode)
        }

    private fun subfolderForMode(mode: SDMode): String = when (mode) {
        SDMode.TXT2IMG -> "txt2img"
        SDMode.IMG2IMG -> "img2img"
        SDMode.UPSCALE -> "upscaled"
    }

    private fun modeLabel(mode: SDMode): String = when (mode) {
        SDMode.TXT2IMG -> getString(R.string.imagegen_mode_txt2img)
        SDMode.IMG2IMG -> getString(R.string.imagegen_mode_img2img)
        SDMode.UPSCALE -> getString(R.string.imagegen_mode_upscale)
    }

    private fun postProcessOutputIfNeeded(config: SDConfig, outputFile: File) {
        if (!outputFile.exists()) return
        val initImagePath = config.initImage ?: return
        if (config.mode != SDMode.IMG2IMG) return

        try {
            val (origWidth, origHeight) = decodeImageBounds(initImagePath) ?: return
            val (outWidth, outHeight) = decodeImageBounds(outputFile.absolutePath) ?: return
            resolveSdAutoCropSkipReason(config.mode, outWidth, outHeight)?.let { reason ->
                DebugLog.log("[StableDiffusionService] Auto-crop skipped: $reason")
                recordModeBreadcrumb(
                    mode = config.mode,
                    event = "postprocess_crop_skipped",
                    phase = "post-processing",
                    details = reason
                )
                return
            }

            val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath) ?: return
            val originalAspect = origWidth.toFloat() / origHeight.toFloat()

            val (targetWidth, targetHeight) = if (outWidth > outHeight) {
                if (originalAspect >= 1f) {
                    Pair(outWidth, (outWidth / originalAspect).toInt())
                } else {
                    Pair((outHeight * originalAspect).toInt(), outHeight)
                }
            } else {
                if (originalAspect >= 1f) {
                    Pair(outWidth, (outWidth / originalAspect).toInt())
                } else {
                    Pair((outHeight * originalAspect).toInt(), outHeight)
                }
            }

            if (outWidth == targetWidth && outHeight == targetHeight) {
                outputBitmap.recycle()
                return
            }

            val cropLeft = (outWidth - targetWidth) / 2
            val cropTop = (outHeight - targetHeight) / 2
            if (cropLeft <= 0 && cropTop <= 0) {
                outputBitmap.recycle()
                return
            }

            val finalWidth = targetWidth.coerceIn(1, outWidth - cropLeft.coerceAtLeast(0))
            val finalHeight = targetHeight.coerceIn(1, outHeight - cropTop.coerceAtLeast(0))

            val croppedBitmap = Bitmap.createBitmap(
                outputBitmap,
                cropLeft.coerceAtLeast(0),
                cropTop.coerceAtLeast(0),
                finalWidth,
                finalHeight
            )
            FileOutputStream(outputFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            croppedBitmap.recycle()
            outputBitmap.recycle()
        } catch (oom: OutOfMemoryError) {
            DebugLog.log("[StableDiffusionService] Auto-crop skipped after OOM")
            recordModeBreadcrumb(
                mode = config.mode,
                event = "postprocess_crop_failed",
                phase = "post-processing",
                details = "oom"
            )
        } catch (e: Exception) {
            DebugLog.log("[StableDiffusionService] Auto-crop failed: ${e.message}")
            recordModeBreadcrumb(
                mode = config.mode,
                event = "postprocess_crop_failed",
                phase = "post-processing",
                details = e.message
            )
        }
    }

    private fun decodeImageBounds(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun exportImageIfConfigured(outputFile: File, subfolder: String?) {
        if (subfolder == null || !outputFile.exists()) return

        val settingsRepo = SettingsRepository(this)
        val customFolderUri = settingsRepo.outputFolderUri.value ?: return

        try {
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(customFolderUri)) ?: return
            var imagesDoc = rootDoc.findFile("images")
            if (imagesDoc == null) {
                imagesDoc = rootDoc.createDirectory("images")
            }
            var subfolderDoc = imagesDoc?.findFile(subfolder)
            if (subfolderDoc == null) {
                subfolderDoc = imagesDoc?.createDirectory(subfolder)
            }
            val newFile = subfolderDoc?.createFile("image/png", outputFile.name) ?: return
            contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
                outputFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[StableDiffusionService] Failed to copy to custom folder: ${e.message}")
        }
    }

    private fun writeImageMetadata(
        config: SDConfig,
        outputFile: File,
        generationDurationMs: Long,
        stageTimings: SdStageTimings
    ) {
        if (!outputFile.exists()) return
        runCatching {
            SdGeneratedImageMetadata.fromConfig(config, outputFile, generationDurationMs, stageTimings)
                .writeToFile()
        }.onFailure { error ->
            DebugLog.log("[StableDiffusionService] Failed to write image metadata: ${error.message}")
        }
    }

    private fun withStageTimings(status: String, timings: SdStageTimings): String {
        val values = listOfNotNull(
            timings.conditioningMs?.let { getString(R.string.sd_stage_conditioning, formatStageDuration(it)) },
            timings.samplingMs?.let { getString(R.string.sd_stage_sampling, formatStageDuration(it)) },
            timings.decodingMs?.let { getString(R.string.sd_stage_decoding, formatStageDuration(it)) }
        )
        return if (values.isEmpty()) status else "$status • ${values.joinToString(" • ")}"
    }

    private fun formatStageDuration(durationMs: Long): String = "${durationMs / 1_000.0}s"

    private fun writeUpscaleImageMetadata(config: SDUpscaleConfig, outputFile: File, generationDurationMs: Long) {
        if (!outputFile.exists()) return
        runCatching {
            SdGeneratedImageMetadata.fromUpscaleConfig(config, outputFile, generationDurationMs)
                .writeToFile()
        }.onFailure { error ->
            DebugLog.log("[StableDiffusionService] Failed to write upscale metadata: ${error.message}")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (!isMediaProcessingForegroundTimeout(
                fgsType,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        ) {
            super.onTimeout(startId, fgsType)
            return
        }
        handleMediaProcessingForegroundTimeout(startId, fgsType)
    }

    private fun handleMediaProcessingForegroundTimeout(startId: Int, fgsType: Int) {
        if (!foregroundTimeoutGate.tryEnter()) {
            recordServiceBreadcrumb(
                "foreground_timeout_duplicate",
                "startId=$startId fgsType=$fgsType"
            )
            stopSelf()
            return
        }

        val message = getString(R.string.imagegen_error_media_processing_timeout)
        DebugLog.log(
            "[StableDiffusionService] Foreground media-processing timeout: startId=$startId fgsType=$fgsType"
        )
        recordServiceBreadcrumb("foreground_timeout", "startId=$startId fgsType=$fgsType")

        val workflowJobSnapshot = workflowJob
        val workflowActive = workflowJobSnapshot?.isActive == true
        val activeLanes = snapshotTimeoutLanes()
        markTimedOut(activeLanes, workflowActive)

        if (workflowActive) {
            publishWorkflowTimeout(message, event = "foreground_timeout")
        } else {
            activeLanes.forEach { lane ->
                publishTimeoutForLane(lane, message, event = "foreground_timeout")
            }
        }

        val jobs = clearAllModeJobs()
        val processes = clearAllModeProcesses()
        workflowJob = null
        jobs.forEach { job ->
            job.cancel(MediaProcessingForegroundTimeoutCancellation(message))
        }
        workflowJobSnapshot?.cancel(MediaProcessingForegroundTimeoutCancellation(message))
        forceStopProcesses(processes, "foreground_timeout")

        stallMonitorJob?.cancel()
        stallMonitorJob = null
        modeDiagnostics.clear()
        failForegroundTask(message)
        releaseWakeLocksForTimeout()
        stopSelf()
    }

    private fun snapshotTimeoutLanes(): List<SdWorkLane> = synchronized(modeLifecycleLock) {
        (modeJobs.keys + modeProcesses.keys + modeSessionIds.keys).distinct()
    }

    private fun markTimedOut(lanes: List<SdWorkLane>, workflow: Boolean) {
        synchronized(modeLifecycleLock) {
            timedOutLanes.addAll(lanes)
            if (workflow) workflowTimedOut = true
        }
    }

    private fun isTimedOutLane(lane: SdWorkLane): Boolean = synchronized(modeLifecycleLock) {
        lane in timedOutLanes
    }

    private fun clearTimedOutLane(lane: SdWorkLane) {
        synchronized(modeLifecycleLock) {
            timedOutLanes.remove(lane)
        }
    }

    private fun publishTimeoutForLane(
        lane: SdWorkLane,
        message: String,
        event: String
    ) {
        markActivity(lane.mode, "timeout")
        recordModeBreadcrumb(
            mode = lane.mode,
            event = event,
            phase = "timeout",
            details = message
        )
        _generationState.value = SDGenerationState.Error(message)
        _progress.value = 0f
        getModeStateHolder(
            mode = lane.mode,
            useWorkflowStateHolder = false,
            useDistributedStateHolder = lane.useDistributedStateHolder
        ).updateState(SDGenerationState.Error(message))
        finishModeSession(
            mode = lane.mode,
            useDistributedStateHolder = lane.useDistributedStateHolder,
            outcome = "timeout",
            details = message
        )
    }

    private fun publishWorkflowTimeout(message: String, event: String) {
        markActivity(SDMode.TXT2IMG, "workflow-timeout")
        markActivity(SDMode.UPSCALE, "workflow-timeout")
        recordModeBreadcrumb(
            mode = SDMode.TXT2IMG,
            event = event,
            phase = "timeout",
            details = message
        )
        recordModeBreadcrumb(
            mode = SDMode.UPSCALE,
            event = event,
            phase = "timeout",
            details = message
        )
        _generationState.value = SDGenerationState.Error(message)
        _progress.value = 0f
        SDModeStateHolder.workflowTxt2img.updateState(SDGenerationState.Error(message))
        SDModeStateHolder.workflowUpscale.updateState(SDGenerationState.Error(message))
        finishModeSession(SDMode.TXT2IMG, false, "timeout", message)
        finishModeSession(SDMode.UPSCALE, false, "timeout", message)
    }

    private fun forceStopProcesses(processes: Iterable<Process>, reason: String) {
        processes.forEach { process ->
            runCatching {
                if (process.isAlive) {
                    DebugLog.log("[StableDiffusionService] Force-stopping native process for $reason")
                    process.destroyForcibly()
                }
            }.onFailure { error ->
                DebugLog.log("[StableDiffusionService] Failed to force-stop native process: ${error.message}")
            }
        }
    }

    private fun releaseWakeLocksForTimeout() {
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
                .onSuccess {
                    DebugLog.log("[StableDiffusionService] WakeLock released after foreground timeout")
                    recordServiceBreadcrumb("wake_lock_released", "foreground_timeout")
                }
        }
        WakeLockManager.releaseWifiLock("StableDiffusionService")
    }

    private fun timeoutMessage(cancelled: CancellationException): String =
        (cancelled as? MediaProcessingForegroundTimeoutCancellation)?.userMessage
            ?: getString(R.string.imagegen_error_media_processing_timeout)

    override fun onDestroy() {
        recordServiceBreadcrumb("service_destroyed")
        clearAllModeProcesses().forEach { it.destroy() }
        clearAllModeJobs().forEach { it.cancel() }
        modeSessionIds.clear()
        workflowJob?.cancel()
        workflowJob = null
        modeDiagnostics.clear()
        stallMonitorJob?.cancel()
        stallMonitorJob = null
        serviceScope.cancel()
        dismissForegroundTask()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        WakeLockManager.releaseWifiLock("StableDiffusionService")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        recordServiceBreadcrumb("task_removed", rootIntent?.action)
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        recordServiceBreadcrumb("trim_memory", "level=$level")
        super.onTrimMemory(level)
    }

    private fun finishModeSession(
        mode: SDMode,
        useDistributedStateHolder: Boolean,
        outcome: String,
        details: String? = null
    ) {
        val sessionId = modeSessionIds.remove(laneFor(mode, useDistributedStateHolder)) ?: return
        GenerationDiagnosticsStore.finishSession(
            sessionId = sessionId,
            source = DIAGNOSTIC_SOURCE,
            mode = mode.name,
            outcome = outcome,
            details = details,
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
    }

    private fun recordModeBreadcrumb(
        mode: SDMode,
        event: String,
        phase: String? = modeDiagnostics[mode]?.phase,
        details: String? = null
    ) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            sessionId = modeSessionIds[laneFor(mode, false)] ?: modeSessionIds[laneFor(mode, true)],
            mode = mode.name,
            event = event,
            phase = phase,
            details = details,
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
    }

    private fun recordLaunchBreadcrumb(mode: SDMode, event: String, details: String? = null) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            mode = mode.name,
            event = event,
            details = details,
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = if (::powerManager.isInitialized) {
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                null
            },
            interactive = if (::powerManager.isInitialized) powerManager.isInteractive else null,
            powerSaveMode = if (::powerManager.isInitialized) powerManager.isPowerSaveMode else null
        )
    }

    private fun recordServiceBreadcrumb(event: String, details: String? = null) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            event = event,
            details = details,
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = if (::powerManager.isInitialized) {
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                null
            },
            interactive = if (::powerManager.isInitialized) powerManager.isInteractive else null,
            powerSaveMode = if (::powerManager.isInitialized) powerManager.isPowerSaveMode else null
        )
    }

    private fun buildSessionDetails(config: SDConfig, workflow: Boolean): String {
        val (family, variant) = inferSdFamilyForConfig(config)
        return buildList {
            add("model=${File(config.modelPath).name}")
            add("size=${config.width}x${config.height}")
            add("steps=${config.steps}")
            add("sampler=${config.samplingMethod.cliName}")
            add("mode=${config.mode.name}")
            add("initImage=${config.initImage != null}")
            add("family=${family.storedValue}")
            variant?.let { add("variant=$it") }
            add("paramsBackend=${config.sdParamsBackendMode}")
            add("runtimeBackend=${config.sdRuntimeBackendMode}")
            if (config.maxVramCpuGiB.isNotBlank()) {
                add("maxVramCpuGiB=${config.maxVramCpuGiB}")
            }
            add("workflow=$workflow")
        }.joinToString(" ")
    }

    private fun buildUpscaleSessionDetails(config: SDUpscaleConfig): String = buildList {
        add("model=${File(config.modelPath).name}")
        add("input=${File(config.inputImagePath).name}")
        add("repeats=${config.upscaleRepeats}")
        add("threads=${config.threads}")
        add("paramsBackend=${config.sdParamsBackendMode}")
        add("runtimeBackend=${config.sdRuntimeBackendMode}")
        if (config.maxVramCpuGiB.isNotBlank()) {
            add("maxVramCpuGiB=${config.maxVramCpuGiB}")
        }
    }.joinToString(" ")

    private fun componentRoleLabel(role: SdComponentRole): String = when (role) {
        SdComponentRole.VAE -> getString(R.string.imagegen_component_vae)
        SdComponentRole.TAE -> getString(R.string.imagegen_component_tae)
        SdComponentRole.CLIP_L -> getString(R.string.imagegen_component_clip_l)
        SdComponentRole.CLIP_G -> getString(R.string.imagegen_component_clip_g)
        SdComponentRole.T5XXL -> getString(R.string.imagegen_component_t5xxl)
        SdComponentRole.LLM -> getString(R.string.imagegen_component_llm)
        SdComponentRole.LLM_VISION -> getString(R.string.imagegen_component_llm_vision)
        SdComponentRole.CONTROLNET -> getString(R.string.imagegen_component_controlnet)
        SdComponentRole.LORA -> getString(R.string.imagegen_component_lora)
        SdComponentRole.PHOTOMAKER -> getString(R.string.imagegen_component_photomaker)
        SdComponentRole.UPSCALER -> getString(R.string.imagegen_component_upscaler)
        SdComponentRole.MAIN_MODEL -> getString(R.string.imagegen_component_main_model)
    }

    private suspend fun probeSdBinaryCapabilities(
        sdBinary: File,
        binaryRepo: BinaryRepository
    ): SdBinaryCapabilities? = withContext(Dispatchers.IO) {
        if (cachedSdCapabilityBinaryPath == sdBinary.absolutePath && cachedSdCapabilities != null) {
            return@withContext cachedSdCapabilities
        }

        val libDir = File(applicationContext.filesDir, "lib").apply { mkdirs() }
        setupLibrarySymlinks(sdBinary.parentFile, libDir, sdBinary.absolutePath)
        val envPath = sdProcessLibraryPath(binaryRepo, sdBinary, libDir)

        val helpCapabilities = listOf("--help", "-h").mapNotNull { flag ->
            runCatching {
                val process = ProcessBuilder(sdBinary.absolutePath, flag)
                    .redirectErrorStream(true)
                    .directory(sdBinary.parentFile)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = envPath
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.takeIf { it.isNotBlank() }?.let(::parseSdBinaryCapabilities)
            }.getOrNull()
        }
        val capabilities = helpCapabilities.maxByOrNull { it.supportedFlags.size } ?: return@withContext null

        capabilities.also {
            cachedSdCapabilityBinaryPath = sdBinary.absolutePath
            cachedSdCapabilities = capabilities
        }
    }

    /**
     * Create symlinks for versioned library names (.so.0 -> .so)
     */
    private fun setupLibrarySymlinks(sourceDir: File?, targetDir: File, binaryPath: String) {
        if (sourceDir == null) return

        val binaryName = File(binaryPath).name
        val tier = when {
            binaryName.contains("_snapdragon_vulkan") -> "_snapdragon_vulkan"
            binaryName.contains("_armv9") -> "_armv9"
            binaryName.contains("_dotprod") -> "_dotprod"
            binaryName.contains("_baseline") -> "_baseline"
            else -> ""
        }

        DebugLog.log("StableDiffusionService: Inferred tier '$tier' from $binaryName")

        val librariesToLink = listOf(
            "libmtmd.so" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            "libmtmd.so.0" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            "libllama.so" to listOf("libllama.so", "libllama.so.0.so"),
            "libllama.so.0" to listOf("libllama.so.0", "libllama.so", "libllama.so.0.so"),
            "libggml.so" to listOf("libggml.so", "libggml.so.0.so"),
            "libggml.so.0" to listOf("libggml.so.0", "libggml.so", "libggml.so.0.so"),
            "libggml-cpu.so" to listOf("libggml-cpu.so", "libggml-cpu.so.0.so"),
            "libggml-cpu.so.0" to listOf("libggml-cpu.so.0", "libggml-cpu.so", "libggml-cpu.so.0.so"),
            "libggml-base.so" to listOf("libggml-base.so", "libggml-base.so.0.so"),
            "libggml-base.so.0" to listOf("libggml-base.so.0", "libggml-base.so", "libggml-base.so.0.so")
        )

        for ((linkName, sourceCandidates) in librariesToLink) {
            var sourceFile: File? = null
            for (candidateName in sourceCandidates) {
                val candidate = File(sourceDir, candidateName)
                if (candidate.exists()) {
                    sourceFile = candidate
                    break
                }
            }

            val linkFile = File(targetDir, linkName)
            if (sourceFile != null) {
                try {
                    if (linkFile.exists()) {
                        linkFile.delete()
                    }
                    val result = Runtime.getRuntime()
                        .exec(arrayOf("ln", "-sf", sourceFile.absolutePath, linkFile.absolutePath))
                        .waitFor()
                    if (result != 0 || !linkFile.exists()) {
                        sourceFile.copyTo(linkFile, overwrite = true)
                    }
                } catch (e: Exception) {
                    DebugLog.log("StableDiffusionService: Error creating link/copy for $linkName: ${e.message}")
                    try {
                        sourceFile.copyTo(linkFile, overwrite = true)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_START_GENERATION = "com.example.llamadroid.action.START_SD_GENERATION"
        private const val ACTION_START_UPSCALE = "com.example.llamadroid.action.START_SD_UPSCALE"
        private const val ACTION_START_WORKFLOW = "com.example.llamadroid.action.START_SD_WORKFLOW"
        private const val ACTION_CANCEL_MODE = "com.example.llamadroid.action.CANCEL_SD_MODE"
        private const val ACTION_CANCEL_WORKFLOW = "com.example.llamadroid.action.CANCEL_SD_WORKFLOW"
        private const val ACTION_CANCEL_ALL = "com.example.llamadroid.action.CANCEL_ALL_SD"
        private const val EXTRA_CONFIG = "extra_sd_config"
        private const val EXTRA_UPSCALE_CONFIG = "extra_sd_upscale_config"
        private const val EXTRA_WORKFLOW_CONFIG = "extra_sd_workflow_config"
        private const val EXTRA_MODE = "extra_sd_mode"
        private const val EXTRA_USE_WORKFLOW_STATE_HOLDER = "extra_sd_use_workflow_holder"
        private const val EXTRA_USE_DISTRIBUTED_STATE_HOLDER = "extra_sd_use_distributed_holder"
        private const val WORKFLOW_OUTPUT_SUBFOLDER = "workflow"
        private const val STALL_MONITOR_INTERVAL_MS = 15_000L
        private const val STALL_THRESHOLD_MS_1 = 60_000L
        private const val STALL_THRESHOLD_MS_2 = 120_000L
        private const val STALL_THRESHOLD_MS_3 = 300_000L
        private const val DIAGNOSTIC_SOURCE = "image_generation"
        private const val COMMAND_BREADCRUMB_LIMIT = 768
        private var cachedSdCapabilityBinaryPath: String? = null
        private var cachedSdCapabilities: SdBinaryCapabilities? = null

        fun createStartIntent(
            context: Context,
            config: SDConfig,
            useWorkflowStateHolder: Boolean = false,
            useDistributedStateHolder: Boolean = false
        ): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_START_GENERATION
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_USE_WORKFLOW_STATE_HOLDER, useWorkflowStateHolder)
                putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
            }

        fun createStartUpscaleIntent(
            context: Context,
            config: SDUpscaleConfig,
            useDistributedStateHolder: Boolean = false
        ): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_START_UPSCALE
                putExtra(EXTRA_UPSCALE_CONFIG, config)
                putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
            }

        fun createStartWorkflowIntent(context: Context, workflowConfig: SDWorkflowConfig): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_START_WORKFLOW
                putExtra(EXTRA_WORKFLOW_CONFIG, workflowConfig)
            }

        fun createCancelModeIntent(
            context: Context,
            mode: SDMode,
            useDistributedStateHolder: Boolean = false
        ): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_CANCEL_MODE
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
            }

        fun createCancelWorkflowIntent(context: Context): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_CANCEL_WORKFLOW
            }

        fun createCancelAllIntent(context: Context): Intent =
            Intent(context, StableDiffusionService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
    }

    private data class SdWorkLane(
        val mode: SDMode,
        val useDistributedStateHolder: Boolean
    )

    private data class ActivityDiagnostics(
        var phase: String,
        var lastActivityElapsedMs: Long,
        var lastLoggedStallBucket: Int = 0
    )
}

/**
 * State of image generation
 */
sealed class SDGenerationState {
    object Idle : SDGenerationState()
    data class Generating(val snapshot: SdProgressSnapshot) : SDGenerationState()
    data class Complete(val outputPath: String) : SDGenerationState()
    data class Error(val message: String) : SDGenerationState()
}

/**
 * Enum for SD generation modes
 */
enum class SDMode {
    TXT2IMG,
    IMG2IMG,
    UPSCALE
}

/**
 * State holder for a specific SD generation mode
 * Each mode has its own instance to allow concurrent operations
 */
class SDModeStateHolder(val mode: SDMode) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<SDGenerationState>(SDGenerationState.Idle)
    val state: kotlinx.coroutines.flow.StateFlow<SDGenerationState> = _state

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val progress: kotlinx.coroutines.flow.StateFlow<Float> = _progress

    private val _totalSteps = kotlinx.coroutines.flow.MutableStateFlow(20)
    val totalSteps: kotlinx.coroutines.flow.StateFlow<Int> = _totalSteps

    private val _currentStep = kotlinx.coroutines.flow.MutableStateFlow(0)
    val currentStep: kotlinx.coroutines.flow.StateFlow<Int> = _currentStep

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("")
    val status: kotlinx.coroutines.flow.StateFlow<String> = _status

    private val _etaSeconds = kotlinx.coroutines.flow.MutableStateFlow<Double?>(null)
    val etaSeconds: kotlinx.coroutines.flow.StateFlow<Double?> = _etaSeconds

    private val _currentPrompt = kotlinx.coroutines.flow.MutableStateFlow("")
    val currentPrompt: kotlinx.coroutines.flow.StateFlow<String> = _currentPrompt

    private val _generatedImages = kotlinx.coroutines.flow.MutableStateFlow<List<File>>(emptyList())
    val generatedImages: kotlinx.coroutines.flow.StateFlow<List<File>> = _generatedImages

    fun updateState(newState: SDGenerationState) {
        _state.value = newState
        when (newState) {
            is SDGenerationState.Generating -> {
                _progress.value = newState.snapshot.progress
                _totalSteps.value = newState.snapshot.totalSteps
                _currentStep.value = newState.snapshot.currentStep
                _status.value = newState.snapshot.statusText
                _etaSeconds.value = newState.snapshot.etaSeconds
            }
            is SDGenerationState.Complete -> {
                _progress.value = 1f
                _currentStep.value = _totalSteps.value
                _status.value = ""
                _etaSeconds.value = null
                val outputFile = File(newState.outputPath)
                if (outputFile.exists()) {
                    _generatedImages.value = _generatedImages.value + outputFile
                }
            }
            is SDGenerationState.Error -> {
                _progress.value = 0f
                _currentStep.value = 0
                _status.value = newState.message
                _etaSeconds.value = null
            }
            is SDGenerationState.Idle -> {
                _progress.value = 0f
                _currentStep.value = 0
                _status.value = ""
                _etaSeconds.value = null
            }
        }
    }

    fun updateTotalSteps(total: Int) {
        _totalSteps.value = total
    }

    fun updatePrompt(prompt: String) {
        _currentPrompt.value = prompt
    }

    fun reset() {
        _state.value = SDGenerationState.Idle
        _progress.value = 0f
        _currentStep.value = 0
        _status.value = ""
        _etaSeconds.value = null
        _currentPrompt.value = ""
    }

    fun clearGallery() {
        _generatedImages.value = emptyList()
    }

    fun addImage(file: File) {
        if (file.exists()) {
            _generatedImages.value = _generatedImages.value + file
        }
    }

    fun removeImage(file: File) {
        _generatedImages.value = _generatedImages.value.filter { it.absolutePath != file.absolutePath }
    }

    companion object {
        val txt2img = SDModeStateHolder(SDMode.TXT2IMG)
        val img2img = SDModeStateHolder(SDMode.IMG2IMG)
        val upscale = SDModeStateHolder(SDMode.UPSCALE)

        val workflowTxt2img = SDModeStateHolder(SDMode.TXT2IMG)
        val workflowUpscale = SDModeStateHolder(SDMode.UPSCALE)

        val distributedTxt2img = SDModeStateHolder(SDMode.TXT2IMG)
        val distributedImg2img = SDModeStateHolder(SDMode.IMG2IMG)
        val distributedUpscale = SDModeStateHolder(SDMode.UPSCALE)

        fun getForMode(mode: SDMode): SDModeStateHolder = when (mode) {
            SDMode.TXT2IMG -> txt2img
            SDMode.IMG2IMG -> img2img
            SDMode.UPSCALE -> upscale
        }

        fun getDistributedForMode(mode: SDMode): SDModeStateHolder = when (mode) {
            SDMode.TXT2IMG -> distributedTxt2img
            SDMode.IMG2IMG -> distributedImg2img
            SDMode.UPSCALE -> distributedUpscale
        }

        fun getForModeIndex(index: Int): SDModeStateHolder = when (index) {
            0 -> txt2img
            1 -> img2img
            2 -> upscale
            else -> txt2img
        }
    }
}

/**
 * Backward compatibility - delegate to txt2img by default
 */
object SDGenerationStateHolder {
    val state get() = SDModeStateHolder.txt2img.state
    val progress get() = SDModeStateHolder.txt2img.progress
    val totalSteps get() = SDModeStateHolder.txt2img.totalSteps
    val currentStep get() = SDModeStateHolder.txt2img.currentStep
    val status get() = SDModeStateHolder.txt2img.status
    val etaSeconds get() = SDModeStateHolder.txt2img.etaSeconds
    val currentPrompt get() = SDModeStateHolder.txt2img.currentPrompt
    val generatedImages get() = SDModeStateHolder.txt2img.generatedImages

    fun updateState(newState: SDGenerationState) = SDModeStateHolder.txt2img.updateState(newState)
    fun updateTotalSteps(total: Int) = SDModeStateHolder.txt2img.updateTotalSteps(total)
    fun updatePrompt(prompt: String) = SDModeStateHolder.txt2img.updatePrompt(prompt)
    fun reset() = SDModeStateHolder.txt2img.reset()
    fun clearGallery() = SDModeStateHolder.txt2img.clearGallery()
}
