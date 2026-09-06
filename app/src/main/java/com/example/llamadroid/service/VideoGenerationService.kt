package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.sd.SdLoraConfigurationException
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoInputException
import com.example.llamadroid.sd.SdVideoPrerequisiteException
import com.example.llamadroid.sd.SdVideoWorkflowException
import com.example.llamadroid.sd.SdVideoWorkflowErrorCode
import com.example.llamadroid.sd.activeInOrder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Dedicated foreground service for stable-diffusion.cpp video generation.
 * This is intentionally separate from image generation.
 */
class VideoGenerationService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val modeJobs = mutableMapOf<VideoWorkLane, Job>()
    private val modeProcesses = mutableMapOf<VideoWorkLane, Process>()
    private val modeDiagnostics = mutableMapOf<VideoGenerationMode, ActivityDiagnostics>()
    private val modeSessionIds = mutableMapOf<VideoWorkLane, String>()
    private val foregroundTimeoutGate = ForegroundTimeoutGate()
    private val timedOutLanes = mutableSetOf<VideoWorkLane>()

    private var notificationTaskId: Int? = null
    private lateinit var ffmpegLibDir: File
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var stallMonitorJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): VideoGenerationService = this@VideoGenerationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun laneFor(mode: VideoGenerationMode, useDistributedStateHolder: Boolean): VideoWorkLane =
        VideoWorkLane(mode, useDistributedStateHolder)

    override fun onCreate() {
        super.onCreate()
        GenerationDiagnosticsStore.init(applicationContext)
        ffmpegLibDir = File(filesDir, "ffmpeg_libs")
        setupFFmpegLibrarySymlinks()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AI-Doomsday:VideoGeneration"
        ).apply {
            setReferenceCounted(false)
        }
        recordServiceBreadcrumb("service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordServiceBreadcrumb("start_command", intent?.action)
        when (intent?.action) {
            ACTION_START_GENERATION -> {
                val config = intent.getParcelableExtraCompat<VideoGenerationConfig>(EXTRA_CONFIG)
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                if (config == null) {
                    DebugLog.log("[VIDEO-GEN] Missing config in start intent")
                } else if (hasActiveModeJob(config.mode, useDistributedStateHolder)) {
                    VideoGenerationStateHolder.getForMode(config.mode, useDistributedStateHolder).updateState(
                        VideoGenerationState.Error(getString(R.string.video_gen_error_already_running))
                    )
                } else {
                    ensureForegroundTask()
                    startGeneration(config, useDistributedStateHolder)
                }
            }
            ACTION_CANCEL_MODE -> {
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { VideoGenerationMode.valueOf(it) }.getOrNull() }
                    ?.let { cancelMode(it, useDistributedStateHolder) }
            }
            ACTION_RETRY_CONVERSION -> {
                val useDistributedStateHolder = intent.getBooleanExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, false)
                val metadata = intent.getStringExtra(EXTRA_METADATA_PATH)
                    ?.let { GeneratedVideoMetadata.fromFile(File(it)) }
                if (metadata == null) {
                    DebugLog.log("[VIDEO-GEN] Missing or unreadable metadata for conversion retry")
                } else if (hasActiveModeJob(metadata.modeEnum, useDistributedStateHolder)) {
                    VideoGenerationStateHolder.getForMode(metadata.modeEnum, useDistributedStateHolder).updateState(
                        VideoGenerationState.Error(getString(R.string.video_gen_error_already_running))
                    )
                } else {
                    ensureForegroundTask()
                    startConversionRetry(metadata, useDistributedStateHolder)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startGeneration(config: VideoGenerationConfig, useDistributedStateHolder: Boolean) {
        val holder = VideoGenerationStateHolder.getForMode(config.mode, useDistributedStateHolder)
        val lane = laneFor(config.mode, useDistributedStateHolder)
        holder.updatePrompt(config.prompt)
        ensureWakeLockHeld()
        modeSessionIds[lane] = GenerationDiagnosticsStore.startSession(
            source = DIAGNOSTIC_SOURCE,
            mode = config.mode.name,
            details = buildSessionDetails(config),
            phase = "starting",
            wakeLockHeld = wakeLock?.isHeld == true,
            notificationActive = notificationTaskId != null,
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            interactive = powerManager.isInteractive,
            powerSaveMode = powerManager.isPowerSaveMode
        )
        markActivity(config.mode, "starting")
        ensureStallMonitorRunning()

        modeJobs[lane] = serviceScope.launch {
            try {
                val (metadata, warningMessage) = runGeneration(config, holder, useDistributedStateHolder)
                markActivity(config.mode, "complete")
                holder.updateState(VideoGenerationState.Complete(metadata, warningMessage))
                finishModeSession(config.mode, useDistributedStateHolder, "complete", metadata.diffusionModelName)
                completeForegroundTask(getString(R.string.video_gen_notification_complete))
            } catch (cancelled: CancellationException) {
                if (isTimedOutLane(lane)) {
                    val message = timeoutMessage(cancelled)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_cancelled")
                    DebugLog.log("[VIDEO-GEN] ${config.mode} stopped after foreground timeout")
                } else {
                    markActivity(config.mode, "cancelled")
                    holder.reset()
                    finishModeSession(config.mode, useDistributedStateHolder, "cancelled")
                    DebugLog.log("[VIDEO-GEN] ${config.mode} cancelled")
                }
            } catch (e: Exception) {
                if (isTimedOutLane(lane)) {
                    val message = getString(R.string.video_gen_error_media_processing_timeout)
                    publishTimeoutForLane(lane, message, event = "foreground_timeout_failed")
                    DebugLog.log("[VIDEO-GEN] ${config.mode} native process exited during foreground timeout: ${e.message}")
                } else {
                    val message = localizeVideoRuntimeError(e)
                    markActivity(config.mode, "failed")
                    DebugLog.log("[VIDEO-GEN] Failed: $message")
                    holder.updateState(VideoGenerationState.Error(message))
                    finishModeSession(config.mode, useDistributedStateHolder, "failed", message)
                    failForegroundTask(message)
                }
            } finally {
                modeProcesses.remove(lane)
                modeJobs.remove(lane)
                clearTimedOutLane(lane)
                clearDiagnostics(config.mode)
                cleanupAfterWork()
            }
        }
    }

    /** Retry only the portable conversion for a completed native artifact. */
    private fun startConversionRetry(
        metadata: GeneratedVideoMetadata,
        useDistributedStateHolder: Boolean
    ) {
        val mode = metadata.modeEnum
        val lane = laneFor(mode, useDistributedStateHolder)
        val holder = VideoGenerationStateHolder.getForMode(mode, useDistributedStateHolder)
        ensureWakeLockHeld()
        markActivity(mode, "conversion-retry")
        ensureStallMonitorRunning()
        modeJobs[lane] = serviceScope.launch {
            try {
                val nativeFile = File(metadata.nativeOutputPath ?: metadata.aviPath)
                if (!nativeFile.exists()) {
                    throw IllegalStateException(getString(R.string.video_runtime_native_output_missing))
                }
                val metadataFile = File(metadata.metadataPath)
                val outputFile = File(metadata.mp4Path).apply {
                    parentFile?.mkdirs()
                }
                val nativeFormat = com.example.llamadroid.sd.SdVideoNativeOutputFormat.entries
                    .firstOrNull { it.name.equals(metadata.nativeOutputFormat, ignoreCase = true) }
                    ?: com.example.llamadroid.sd.SdVideoNativeOutputFormat.AVI
                val outputFormat = com.example.llamadroid.sd.SdVideoOutputFormat.entries
                    .firstOrNull { it.name.equals(metadata.outputFormat, ignoreCase = true) }
                    ?: com.example.llamadroid.sd.SdVideoOutputFormat.MP4
                val audioCodec = metadata.audioCodec?.let { raw ->
                    com.example.llamadroid.sd.SdVideoAudioCodec.entries
                        .firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                val status = getString(R.string.video_runtime_status_converting, outputFormat.name)
                holder.updateState(VideoGenerationState.Converting(0.8f, status))
                updateNotification(status, 0.8f)
                val conversion = convertNativeOutput(
                    inputNative = nativeFile,
                    audioSidecar = metadata.audioSidecarPath?.let(::File)?.takeIf { it.isFile }
                        ?: findVideoAudioSidecar(nativeFile),
                    output = outputFile,
                    nativeFormat = nativeFormat,
                    outputFormat = outputFormat,
                    audioCodec = audioCodec,
                    mode = mode,
                    holder = holder,
                    useDistributedStateHolder = useDistributedStateHolder,
                    recoveryEnabled = true
                )
                var updated = metadata.copy(
                    nativeOutputPath = nativeFile.absolutePath,
                    conversionAttempted = conversion.attempted,
                    conversionRecoveredNative = conversion.recoveredNative,
                    conversionWarning = conversion.warning
                )
                updated.writeToFile(metadataFile)
                val exported = exportArtifacts(updated, metadataFile)
                updated = exported.first
                updated.writeToFile(metadataFile)
                val warning = listOfNotNull(conversion.warning, exported.second)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { null }
                holder.updateState(VideoGenerationState.Complete(updated, warning))
                completeForegroundTask(getString(R.string.video_gen_notification_complete))
            } catch (cancelled: CancellationException) {
                DebugLog.log("[VIDEO-GEN] Conversion retry cancelled: ${cancelled.message}")
                holder.reset()
                dismissForegroundTask()
            } catch (error: Exception) {
                val message = localizeVideoRuntimeError(error)
                DebugLog.log("[VIDEO-GEN] Conversion retry failed: $message")
                holder.updateState(VideoGenerationState.Error(message))
                failForegroundTask(message)
            } finally {
                modeJobs.remove(lane)
                clearDiagnostics(mode)
                cleanupAfterWork()
            }
        }
    }

    fun cancelMode(mode: VideoGenerationMode, useDistributedStateHolder: Boolean = false) {
        markActivity(mode, "cancel-requested")
        recordModeBreadcrumb(mode, "cancel_requested")
        val lane = laneFor(mode, useDistributedStateHolder)
        modeJobs[lane]?.cancel(CancellationException(getString(R.string.video_gen_status_cancelled)))
        modeProcesses[lane]?.destroy()
        modeJobs.remove(lane)
        modeProcesses.remove(lane)
        clearDiagnostics(mode)
        VideoGenerationStateHolder.getForMode(mode, useDistributedStateHolder).reset()

        if (!hasActiveWork()) {
            dismissForegroundTask()
            cleanupAfterWork()
        }
    }

    private suspend fun runGeneration(
        config: VideoGenerationConfig,
        holder: VideoGenerationStateHolder,
        useDistributedStateHolder: Boolean
    ): Pair<GeneratedVideoMetadata, String?> = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        var stageTimings = SdStageTimings()
        val binaryRepo = BinaryRepository(applicationContext)
        var sdBinary = binaryRepo.getSdBinary()
            ?: throw IllegalStateException(getString(R.string.video_gen_error_sd_binary_missing))
        var binaryCapabilities = probeSdBinaryCapabilities(applicationContext, sdBinary, binaryRepo)
        val missingDistributedFlags = missingSdDistributedFlags(config.distributedRuntime, binaryCapabilities)
        if (missingDistributedFlags.isNotEmpty()) {
            val unsupportedFlags = SdUnsupportedFlagsException(missingDistributedFlags)
            val cpuBinary = binaryRepo.getCpuSdBinary()
            if (shouldRetrySdGenerationOnCpu(sdBinary, cpuBinary, config.distributedRuntime, unsupportedFlags)) {
                DebugLog.log(
                    "[VIDEO-GEN] Accelerator SD binary lacks distributed flags, retrying CPU fallback: " +
                        missingDistributedFlags.joinToString(", ")
                )
                sdBinary = requireNotNull(cpuBinary)
                binaryCapabilities = probeSdBinaryCapabilities(applicationContext, sdBinary, binaryRepo)
            } else {
                throw IllegalStateException(
                    getString(R.string.imagegen_error_binary_missing_flags, missingDistributedFlags.joinToString(", "))
                )
            }
        }

        val outputAvi = File(config.resolvedNativeOutputPath()).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val outputMp4 = File(config.resolvedPortableOutputPath()).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val metadataFile = File(config.metadataPath).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }

        val activeVideoLoras = config.resolvedLoras().activeInOrder()
        com.example.llamadroid.sd.validateSdLoras(
            activeVideoLoras,
            requireReadableFiles = true
        )
        // The pinned native parser accepts absolute prompt paths. This plan
        // keeps every adapter in its original directory and still gives the
        // builder one singular --lora-model-dir value.
        val loraStaging = VideoLoraStagingPlan.nativeAbsolute(activeVideoLoras)
        try {

        holder.updateState(
            VideoGenerationState.Generating(
                0f,
                getString(R.string.gen_status_calculating_eta),
                currentStep = 0,
                totalSteps = config.steps.coerceAtLeast(1)
            )
        )
        updateNotification(getString(R.string.gen_status_calculating_eta), 0f)
        markActivity(config.mode, "starting")

        suspend fun runSamplingWithBinary(
            candidateBinary: File,
            candidateCapabilities: SdBinaryCapabilities?
        ): File {
            if (outputAvi.exists()) outputAvi.delete()
            if (config.mode == VideoGenerationMode.IMG2VID && config.resolvedVideoInputs().initImagePath.isNullOrBlank()) {
                throw IllegalArgumentException(getString(R.string.video_gen_error_input_image_required))
            }
            val args = try {
                buildVideoCommandArgs(
                    config = config.copy(nativeOutputPath = outputAvi.absolutePath),
                    executablePath = candidateBinary.absolutePath,
                    binaryCapabilities = candidateCapabilities,
                    loraStaging = loraStaging
                )
            } catch (error: SdDisallowedDistributedFlagException) {
                throw IllegalStateException(getString(R.string.sd_dist_error_row_split_not_supported))
            }

            DebugLog.log("[VIDEO-GEN] Running command: ${args.joinToString(" ")}")

            val libDir = File(filesDir, "lib").apply { mkdirs() }
            setupSdLibrarySymlinks(candidateBinary.parentFile, libDir, candidateBinary.absolutePath)

            val processBuilder = ProcessBuilder(args)
                .directory(candidateBinary.parentFile)
            processBuilder.environment()["LD_LIBRARY_PATH"] = sdProcessLibraryPath(binaryRepo, candidateBinary, libDir)

            val lane = laneFor(config.mode, useDistributedStateHolder)
            modeProcesses[lane] = processBuilder.start()
            val sdProcess = modeProcesses[lane]!!
            val processLogFile = File(
                outputAvi.parentFile,
                "${outputAvi.nameWithoutExtension}.stable-diffusion.log"
            ).apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            val progressTracker = SdProgressTracker(
                totalStepsHint = config.steps.coerceAtLeast(1),
                startedAtMs = SystemClock.elapsedRealtime()
            )
            val progressPublishMutex = Mutex()
            var pendingSamplingSnapshot: SdProgressSnapshot? = null
            var lastSamplingProgressPublishMs = 0L
            var lastPublishedSamplingStep = -1

            fun publishSamplingSnapshot(snapshot: SdProgressSnapshot) {
                val weighted = snapshot.progress * 0.72f
                val stageLabels = listOfNotNull(
                    stageTimings.conditioningMs?.let { getString(R.string.sd_stage_conditioning, formatStageDuration(it)) },
                    stageTimings.samplingMs?.let { getString(R.string.sd_stage_sampling, formatStageDuration(it)) },
                    stageTimings.decodingMs?.let { getString(R.string.sd_stage_decoding, formatStageDuration(it)) }
                )
                val status = buildVideoSamplingStatus(snapshot).let { base ->
                    if (stageLabels.isEmpty()) base else "$base • ${stageLabels.joinToString(" • ")}"
                }
                holder.updateState(
                    VideoGenerationState.Generating(
                        progress = weighted,
                        status = status,
                        currentStep = snapshot.currentStep,
                        totalSteps = snapshot.totalSteps,
                        etaSeconds = snapshot.etaSeconds
                    )
                )
                updateNotification(status, weighted)
            }

            suspend fun publishPendingSamplingSnapshot(force: Boolean = false) {
                progressPublishMutex.withLock {
                    val snapshot = pendingSamplingSnapshot ?: return@withLock
                    val now = SystemClock.elapsedRealtime()
                    val stepChanged = snapshot.currentStep != lastPublishedSamplingStep
                    if (!force &&
                        !stepChanged &&
                        now - lastSamplingProgressPublishMs < VIDEO_PROGRESS_PUBLISH_INTERVAL_MS
                    ) {
                        return@withLock
                    }
                    lastSamplingProgressPublishMs = now
                    lastPublishedSamplingStep = snapshot.currentStep
                    pendingSamplingSnapshot = null
                    publishSamplingSnapshot(snapshot)
                }
            }

            suspend fun handleSamplingOutput(text: String, appendToLog: Boolean) {
                markActivity(config.mode, "generating")
                stageTimings = stageTimings.withLine(text)
                if (appendToLog) {
                    DebugLog.log("[VIDEO-GEN] $text")
                }
                progressTracker.update(text, SystemClock.elapsedRealtime())?.let { snapshot ->
                    progressPublishMutex.withLock {
                        pendingSamplingSnapshot = snapshot
                    }
                    publishPendingSamplingSnapshot()
                }
            }
            val etaTickerJob = launch {
                while (isActive) {
                    delay(1000)
                    progressTracker.tick(SystemClock.elapsedRealtime())?.let { snapshot ->
                        publishSamplingSnapshot(snapshot)
                    }
                }
            }

            try {
                BufferedOutputStream(FileOutputStream(processLogFile, false)).use { processLog ->
                    val stdoutJob = launch(Dispatchers.IO) {
                        consumeBoundedProcessOutput(
                            input = sdProcess.inputStream,
                            rawLogOutput = processLog,
                            onLogLine = { line -> handleSamplingOutput(line, appendToLog = true) },
                            onProgress = { progress -> handleSamplingOutput(progress, appendToLog = false) }
                        )
                    }
                    val stderrJob = launch(Dispatchers.IO) {
                        consumeBoundedProcessOutput(
                            input = sdProcess.errorStream,
                            rawLogOutput = processLog,
                            onLogLine = { line -> handleSamplingOutput(line, appendToLog = true) },
                            onProgress = { progress -> handleSamplingOutput(progress, appendToLog = false) }
                        )
                    }
                    val sdExitCode = sdProcess.waitFor()
                    stdoutJob.join()
                    stderrJob.join()
                    publishPendingSamplingSnapshot(force = true)
                    modeProcesses.remove(lane)
                    if (sdExitCode != 0) {
                        throw IllegalStateException(
                            getString(R.string.video_gen_error_generation_failed, sdExitCode)
                        )
                    }
                }
            } finally {
                etaTickerJob.cancel()
            }

            return resolveGeneratedNativeOutput(outputAvi)
        }

        val generatedAvi = runCatching {
            runSamplingWithBinary(sdBinary, binaryCapabilities)
        }.getOrElse { error ->
            val cpuBinary = binaryRepo.getCpuSdBinary()
            if (DeviceAcceleration.isAcceleratorBinary(sdBinary) &&
                shouldRetrySdGenerationOnCpu(sdBinary, cpuBinary, config.distributedRuntime, error)
            ) {
                val detail = "Stable Diffusion video accelerator ${sdBinary.name} failed: ${error.message.orEmpty().take(180)}"
                DebugLog.log("[VIDEO-GEN] $detail")
                DeviceAcceleration.reportRuntimeFailure(AccelerationWorkload.STABLE_DIFFUSION, detail)
                DebugLog.log("[VIDEO-GEN] Retrying video generation with CPU SD binary.")
                sdBinary = requireNotNull(cpuBinary)
                binaryCapabilities = probeSdBinaryCapabilities(applicationContext, sdBinary, binaryRepo)
                runSamplingWithBinary(sdBinary, binaryCapabilities)
            } else {
                throw error
            }
        }
        markActivity(config.mode, "converting")
        val conversionStatus = getString(
            R.string.video_runtime_status_converting,
            config.outputFormat.name
        )
        holder.updateState(VideoGenerationState.Converting(0.8f, conversionStatus))
        updateNotification(conversionStatus, 0.8f)

        val audioSidecar = findVideoAudioSidecar(generatedAvi)
        val conversion = convertNativeOutput(
            audioSidecar = audioSidecar,
            inputNative = generatedAvi,
            output = outputMp4,
            nativeFormat = config.nativeOutputFormat,
            outputFormat = config.outputFormat,
            audioCodec = config.audioCodec,
            mode = config.mode,
            holder = holder,
            useDistributedStateHolder = useDistributedStateHolder,
            recoveryEnabled = config.conversionRecoveryEnabled
        )

        val resolvedVideoComponents = config.resolvedVideoComponents()
        var metadata = GeneratedVideoMetadata(
            mode = config.mode.folderName,
            prompt = config.prompt,
            negativePrompt = config.negativePrompt,
            diffusionModelPath = config.diffusionModelPath,
            diffusionModelName = File(config.diffusionModelPath).name,
            vaeEnabled = resolvedVideoComponents.vaePath != null,
            vaePath = resolvedVideoComponents.vaePath,
            vaeName = resolvedVideoComponents.vaePath?.let { File(it).name },
            t5xxlEnabled = resolvedVideoComponents.t5xxlPath != null,
            t5xxlPath = resolvedVideoComponents.t5xxlPath,
            t5xxlName = resolvedVideoComponents.t5xxlPath?.let { File(it).name },
            initImagePath = config.resolvedVideoInputs().initImagePath,
            videoFrames = config.videoFrames,
            fps = config.fps,
            width = config.width,
            height = config.height,
            steps = config.steps,
            cfgScale = config.cfgScale,
            flowShift = config.flowShift,
            samplingMethod = config.samplingMethod,
            scheduler = config.scheduler,
            cacheMode = config.cacheMode,
            cacheOption = config.cacheOption,
            scmMask = config.scmMask,
            scmPolicy = config.scmPolicy,
            threads = config.threads,
            vaeTiling = config.vaeTiling,
            vaeTileSize = config.vaeTileSize.takeIf { config.vaeTiling && it.isNotBlank() },
            diffusionFa = config.diffusionFa,
            diffusionConvDirect = config.diffusionConvDirect,
            vaeConvDirect = config.vaeConvDirect,
            mmap = config.mmap,
            sdParamsBackendMode = config.sdParamsBackendMode,
            sdParamsBackendSpec = config.sdParamsBackendSpec,
            sdRuntimeBackendMode = config.sdRuntimeBackendMode,
            maxVramCpuGiB = config.maxVramCpuGiB,
            distributedRuntime = config.distributedRuntime,
            loras = config.loras,
            highNoiseLoras = config.highNoiseLoras,
            loraApplyMode = config.loraApplyMode?.cliName,
            createdAt = System.currentTimeMillis(),
            aviPath = generatedAvi.absolutePath,
            mp4Path = outputMp4.absolutePath,
            metadataPath = metadataFile.absolutePath,
            conditioningDurationMs = stageTimings.conditioningMs,
            samplingDurationMs = stageTimings.samplingMs,
            decodingDurationMs = stageTimings.decodingMs,
            videoFamily = config.videoFamily?.storedValue,
            videoVariant = config.videoVariant,
            workflow = config.resolvedVideoWorkflow().storedValue,
            videoComponents = resolvedVideoComponents,
            videoInputs = config.resolvedVideoInputs(),
            useTae = config.useTae || resolvedVideoComponents.taePath != null && resolvedVideoComponents.vaePath == null,
            taePath = resolvedVideoComponents.taePath,
            taeName = resolvedVideoComponents.taePath?.let { File(it).name },
            seed = config.seed,
            highNoiseSteps = config.highNoiseSteps,
            highNoiseCfgScale = config.highNoiseCfgScale,
            highNoiseSamplingMethod = config.highNoiseSamplingMethod,
            controlStrength = config.controlStrength,
            vaeTileOverlap = config.vaeTileOverlap,
            vaeRelativeTileSize = config.vaeRelativeTileSize.takeIf { it.isNotBlank() },
            hires = config.hires,
            outputFormat = config.outputFormat.name,
            nativeOutputFormat = config.nativeOutputFormat.name,
            nativeOutputPath = generatedAvi.absolutePath,
            audioCodec = config.audioCodec?.name,
            conversionAttempted = conversion.attempted,
            conversionRecoveredNative = conversion.recoveredNative,
            conversionWarning = conversion.warning,
            audioSidecarPath = audioSidecar?.absolutePath
        )
        metadata.writeToFile(metadataFile)

        markActivity(config.mode, "copying")
        holder.updateState(VideoGenerationState.Copying(0.94f, getString(R.string.video_gen_status_copying)))
        updateNotification(getString(R.string.video_gen_status_copying), 0.94f)

        val exportResult = exportArtifacts(metadata, metadataFile)
        markActivity(config.mode, "exported")
        metadata = exportResult.first
            .copy(generationDurationMs = SystemClock.elapsedRealtime() - startedAtMs)
        metadata.writeToFile(metadataFile)

        val warning = listOfNotNull(conversion.warning, exportResult.second)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
        if (!warning.isNullOrBlank()) {
            DebugLog.log("[VIDEO-GEN] Export warning: $warning")
        }

        metadata to warning
        } finally {
            loraStaging.close()
        }
    }

    private fun resolveGeneratedNativeOutput(expectedFile: File): File {
        if (expectedFile.isFile && expectedFile.length() > 0L) return expectedFile
        // Native may normalize the requested extension. Only inspect this run's basename;
        // picking the newest file from the gallery could report an earlier run as success.
        val candidates = listOf("avi", "webm", "webp").flatMap { extension ->
            listOf(File(expectedFile.parentFile, "${expectedFile.nameWithoutExtension}.$extension"),
                File(expectedFile.parentFile, "${expectedFile.name}.$extension"))
        }
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
            ?: throw IllegalStateException(getString(R.string.video_runtime_native_output_missing))
    }

    private fun buildVideoSamplingStatus(snapshot: SdProgressSnapshot): String {
        return if (snapshot.etaSeconds == null) {
            getString(R.string.gen_status_calculating_eta)
        } else {
            getString(
                if (snapshot.phase.isVae) {
                    R.string.video_gen_status_vae_eta
                } else {
                    R.string.video_gen_status_diffusion_eta
                },
                SdProgressTracker.progressPercent(snapshot),
                formatEtaShort(snapshot.etaSeconds)
            )
        }
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

    private fun formatStageDuration(durationMs: Long): String = "${durationMs / 1_000.0}s"

    private fun localizeVideoRuntimeError(error: Throwable): String = when (error) {
        is SdUnsupportedFlagsException -> getString(
            R.string.imagegen_error_binary_missing_flags,
            error.flags.joinToString(", ")
        )
        is SdUnsupportedModesException -> getString(
            R.string.video_runtime_unsupported_mode,
            error.modes.joinToString(", ")
        )
        is SdVideoPrerequisiteException -> buildList {
            error.result.missingComponents.forEach { role ->
                add(getString(R.string.video_runtime_missing_component, videoComponentLabel(role)))
            }
            error.result.missingInputs.forEach { role ->
                add(getString(R.string.video_runtime_missing_input, videoInputLabel(role)))
            }
        }.joinToString(" ").ifBlank { getString(R.string.error_generic) }
        is SdVideoWorkflowException -> when (error.code) {
            SdVideoWorkflowErrorCode.NATIVE_WORKFLOW_UNSUPPORTED ->
                getString(R.string.video_runtime_native_workflow_unsupported)
            else -> getString(R.string.video_runtime_invalid_workflow)
        }
        is SdVideoInputException -> when (error.code) {
            SdVideoInputException.Code.INVALID_NUMERIC_VALUE ->
                getString(R.string.video_gen_error_invalid_number, error.detail.orEmpty())
            SdVideoInputException.Code.FRAMES_MUST_BE_VIDEO ->
                getString(R.string.video_output_requires_two_frames)
            SdVideoInputException.Code.MODE_REQUIRES_INIT_IMAGE ->
                getString(R.string.video_runtime_input_image_required)
            SdVideoInputException.Code.REFERENCE_AUDIO_MISMATCH ->
                getString(R.string.video_runtime_reference_audio_mismatch)
            SdVideoInputException.Code.MINIMAX_REFERENCES_CONFLICT_WITH_KEYFRAMES ->
                getString(R.string.video_runtime_minimax_reference_keyframe_conflict)
            SdVideoInputException.Code.MINIMAX_CONTROL_VIDEO_UNSUPPORTED ->
                getString(R.string.video_runtime_minimax_control_video_unsupported)
        }
        is SdLoraConfigurationException -> getString(
            R.string.video_runtime_invalid_lora,
            error.issues.joinToString(", ") { issue -> issue.code.name.lowercase(Locale.US) }
        )
        else -> error.message ?: getString(R.string.error_generic)
    }

    private fun videoInputLabel(role: com.example.llamadroid.sd.SdVideoInputRole): String = getString(when (role) {
        com.example.llamadroid.sd.SdVideoInputRole.INIT_IMAGE -> R.string.video_input_first_image
        com.example.llamadroid.sd.SdVideoInputRole.END_IMAGE -> R.string.video_input_last_image
        com.example.llamadroid.sd.SdVideoInputRole.CONTROL_IMAGE -> R.string.video_input_control_image
        com.example.llamadroid.sd.SdVideoInputRole.CONTROL_VIDEO -> R.string.video_input_control_video
        com.example.llamadroid.sd.SdVideoInputRole.REFERENCE_IMAGE -> R.string.video_input_reference_images
        com.example.llamadroid.sd.SdVideoInputRole.REFERENCE_VIDEO -> R.string.video_input_reference_videos
        com.example.llamadroid.sd.SdVideoInputRole.REFERENCE_VIDEO_AUDIO -> R.string.video_input_reference_video_audio
        com.example.llamadroid.sd.SdVideoInputRole.REFERENCE_AUDIO -> R.string.video_input_reference_audio
    })

    private fun videoComponentLabel(role: SdVideoComponentRole): String = when (role) {
        SdVideoComponentRole.DIFFUSION_MODEL -> getString(R.string.video_runtime_component_diffusion_model)
        SdVideoComponentRole.FULL_MODEL -> getString(R.string.video_runtime_component_full_model)
        SdVideoComponentRole.HIGH_NOISE_DIFFUSION_MODEL -> getString(R.string.video_runtime_component_high_noise_diffusion_model)
        SdVideoComponentRole.VAE -> getString(R.string.video_runtime_component_vae)
        SdVideoComponentRole.TAE -> getString(R.string.video_runtime_component_tae)
        SdVideoComponentRole.T5XXL -> getString(R.string.video_runtime_component_t5xxl)
        SdVideoComponentRole.LLM -> getString(R.string.video_runtime_component_llm)
        SdVideoComponentRole.LLM_VISION -> getString(R.string.video_runtime_component_llm_vision)
        SdVideoComponentRole.AUDIO_VAE -> getString(R.string.video_runtime_component_audio_vae)
        SdVideoComponentRole.EMBEDDINGS_CONNECTORS -> getString(R.string.video_runtime_component_embeddings_connectors)
        SdVideoComponentRole.MOTION_MODULE -> getString(R.string.video_runtime_component_motion_module)
        SdVideoComponentRole.CLIP_VISION -> getString(R.string.video_runtime_component_clip_vision)
        SdVideoComponentRole.CONTROL_NET -> getString(R.string.video_runtime_component_control_net)
        SdVideoComponentRole.LORA -> "LoRA"
        SdVideoComponentRole.HIRES_UPSCALER -> getString(R.string.video_runtime_component_hires_upscaler)
    }

    private data class VideoConversionResult(
        val attempted: Boolean,
        val recoveredNative: Boolean,
        val warning: String? = null
    )

    private suspend fun convertNativeOutput(
        inputNative: File,
        output: File,
        audioSidecar: File? = null,
        nativeFormat: com.example.llamadroid.sd.SdVideoNativeOutputFormat,
        outputFormat: com.example.llamadroid.sd.SdVideoOutputFormat,
        audioCodec: com.example.llamadroid.sd.SdVideoAudioCodec?,
        mode: VideoGenerationMode,
        holder: VideoGenerationStateHolder,
        useDistributedStateHolder: Boolean,
        recoveryEnabled: Boolean
    ): VideoConversionResult = withContext(Dispatchers.IO) {
        // A same-extension request still needs ffmpeg when the user selected
        // an audio policy (including explicit NONE), otherwise a direct copy
        // would silently retain or discard the wrong soundtrack. A null
        // policy intentionally leaves the native artifact untouched.
        if (nativeFormat.extension == outputFormat.extension && audioCodec == null && audioSidecar == null) {
            if (inputNative.canonicalFile != output.canonicalFile) {
                inputNative.copyTo(output, overwrite = true)
            }
            return@withContext VideoConversionResult(attempted = false, recoveredNative = false)
        }

        val binaryRepo = BinaryRepository(applicationContext)
        val ffmpegBinary = binaryRepo.getFFmpegBinary()
            ?: if (recoveryEnabled) {
                return@withContext VideoConversionResult(
                    attempted = true,
                    recoveredNative = true,
                    warning = getString(R.string.video_runtime_conversion_recovered)
                )
            } else {
                throw IllegalStateException(getString(R.string.video_gen_error_ffmpeg_missing))
            }

        val conversionOutput = if (inputNative.canonicalFile == output.canonicalFile) {
            File(output.parentFile, ".${output.name}.conversion-${System.nanoTime()}.tmp")
        } else {
            output
        }
        if (conversionOutput.exists()) conversionOutput.delete()
        val args = buildVideoConversionArgs(ffmpegBinary.absolutePath, inputNative,
            conversionOutput, outputFormat, audioCodec, audioSidecar)

        val processBuilder = ProcessBuilder(args)
            .directory(ffmpegBinary.parentFile)
        processBuilder.environment()["LD_LIBRARY_PATH"] =
            "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"

        val lane = laneFor(mode, useDistributedStateHolder)
        val ffmpegProcess = try {
            processBuilder.start()
        } catch (error: Exception) {
            if (recoveryEnabled && inputNative.exists() && error !is CancellationException) {
                return@withContext VideoConversionResult(
                    attempted = true,
                    recoveredNative = true,
                    warning = getString(R.string.video_runtime_conversion_recovered)
                )
            }
            throw error
        }
        modeProcesses[lane] = ffmpegProcess
        val processLogFile = File(
            output.parentFile,
            "${output.nameWithoutExtension}.ffmpeg.log"
        ).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val conversionMutex = Mutex()
        var tick = 0

        suspend fun handleConversionOutput(text: String, appendToLog: Boolean) {
            conversionMutex.withLock {
                markActivity(mode, "converting")
                if (appendToLog) {
                    DebugLog.log("[VIDEO-GEN][FFMPEG] $text")
                }
                tick += 1
                val progress = (0.80f + (tick.coerceAtMost(12) * 0.01f)).coerceAtMost(0.92f)
                val status = getString(R.string.video_runtime_status_converting, outputFormat.name)
                holder.updateState(
                    VideoGenerationState.Converting(progress, status)
                )
                updateNotification(status, progress)
            }
        }

        var processCompleted = false
        val exitCode = try {
            BufferedOutputStream(FileOutputStream(processLogFile, false)).use { processLog ->
                val stdoutJob = launch(Dispatchers.IO) {
                    consumeBoundedProcessOutput(
                        input = ffmpegProcess.inputStream,
                        rawLogOutput = processLog,
                        onLogLine = { line -> handleConversionOutput(line, appendToLog = true) },
                        onProgress = { progress -> handleConversionOutput(progress, appendToLog = false) }
                    )
                }
                val stderrJob = launch(Dispatchers.IO) {
                    consumeBoundedProcessOutput(
                        input = ffmpegProcess.errorStream,
                        rawLogOutput = processLog,
                        onLogLine = { line -> handleConversionOutput(line, appendToLog = true) },
                        onProgress = { progress -> handleConversionOutput(progress, appendToLog = false) }
                    )
                }
                val processExitCode = ffmpegProcess.waitFor()
                stdoutJob.join()
                stderrJob.join()
                processCompleted = true
                processExitCode
            }
        } finally {
            modeProcesses.remove(lane)
            if (!processCompleted && conversionOutput != output && conversionOutput.exists()) {
                conversionOutput.delete()
            }
        }
        if (exitCode != 0 || !conversionOutput.exists()) {
            if (conversionOutput != output && conversionOutput.exists()) conversionOutput.delete()
            if (recoveryEnabled && inputNative.exists()) {
                if (output.canonicalFile != inputNative.canonicalFile && output.exists()) output.delete()
                return@withContext VideoConversionResult(
                    attempted = true,
                    recoveredNative = true,
                    warning = getString(R.string.video_runtime_conversion_recovered)
                )
            }
            throw IllegalStateException(getString(R.string.video_gen_error_conversion_failed, exitCode))
        }
        if (conversionOutput != output) {
            conversionOutput.copyTo(output, overwrite = true)
            conversionOutput.delete()
        }
        VideoConversionResult(attempted = true, recoveredNative = false)
    }

    private fun exportArtifacts(
        metadata: GeneratedVideoMetadata,
        metadataFile: File
    ): Pair<GeneratedVideoMetadata, String?> {
        val settingsRepo = SettingsRepository(this)
        val outputFolderUri = settingsRepo.outputFolderUri.value
        if (outputFolderUri.isNullOrBlank()) {
            return metadata to getString(R.string.video_gen_warning_output_not_configured)
        }

        return try {
            val rootFolder = DocumentFile.fromTreeUri(this, Uri.parse(outputFolderUri))
                ?: return metadata to getString(R.string.video_gen_warning_output_unavailable)

            val generatedVideosFolder = rootFolder.findFile(VIDEO_OUTPUT_FOLDER_NAME)
                ?: rootFolder.createDirectory(VIDEO_OUTPUT_FOLDER_NAME)
            val modeFolder = generatedVideosFolder?.findFile(metadata.mode)
                ?: generatedVideosFolder?.createDirectory(metadata.mode)

            if (modeFolder == null) {
                return metadata to getString(R.string.video_gen_warning_output_unavailable)
            }

            val nativePath = metadata.nativeOutputPath ?: metadata.aviPath
            val nativeFile = File(nativePath)
            val outputFile = File(metadata.mp4Path)
            val nativeDoc = nativeFile.takeIf { it.exists() }?.let {
                createOrReplaceDocument(modeFolder, it.absolutePath, videoMimeType(it))
            }
            val outputDoc = outputFile.takeIf { it.exists() }
                ?.takeUnless { it.canonicalFile == nativeFile.canonicalFile }
                ?.let { createOrReplaceDocument(modeFolder, it.absolutePath, videoMimeType(it)) }
            val audioFile = metadata.audioSidecarPath?.let(::File)?.takeIf { it.isFile }
            val audioDoc = audioFile?.let { createOrReplaceDocument(modeFolder, it.absolutePath, "audio/wav") }
            val metadataDoc = createOrReplaceDocument(modeFolder, metadataFile.absolutePath, "application/json")

            val updatedMetadata = metadata.copy(
                exportedAviUri = nativeDoc?.uri?.toString()
                    ?.takeIf { nativeFile.extension.equals("avi", ignoreCase = true) },
                exportedMp4Uri = outputDoc?.uri?.toString()
                    ?: nativeDoc?.uri?.toString()?.takeIf { outputFile.exists() && outputFile.canonicalFile == nativeFile.canonicalFile },
                exportedMetadataUri = metadataDoc?.uri?.toString(),
                exportedNativeUri = nativeDoc?.uri?.toString(),
                exportedAudioUri = audioDoc?.uri?.toString()
            )

            nativeDoc?.uri?.let { copyFileToUri(nativeFile, it) }
            outputDoc?.uri?.let { copyFileToUri(outputFile, it) }
            audioDoc?.uri?.let { audioFile?.let { file -> copyFileToUri(file, it) } }
            metadataDoc?.uri?.let { writeTextToUri(updatedMetadata.toJson().toString(2), it) }

            updatedMetadata to null
        } catch (e: Exception) {
            DebugLog.log("[VIDEO-GEN] Failed to export artifacts: ${e.message}")
            metadata to getString(R.string.video_gen_warning_export_failed, e.message ?: "")
        }
    }

    private fun createOrReplaceDocument(parent: DocumentFile, sourcePath: String, mimeType: String): DocumentFile? {
        val name = File(sourcePath).name
        parent.findFile(name)?.delete()
        return parent.createFile(mimeType, name)
    }

    private fun videoMimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "webm" -> "video/webm"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        else -> "video/x-msvideo"
    }

    private fun copyFileToUri(sourceFile: File, uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun writeTextToUri(content: String, uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { output ->
            output.bufferedWriter().use { writer ->
                writer.write(content)
            }
        }
    }

    private fun ensureForegroundTask() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.VIDEO_GEN,
            getString(R.string.video_gen_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        recordServiceBreadcrumb("foreground_started", "taskId=$taskId")
    }

    private fun updateNotification(text: String, progress: Float) {
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, progress, text) }
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
                DebugLog.log("[VIDEO-GEN] stopForeground failed during $reason: ${error.message}")
            }
    }

    private fun ensureWakeLockHeld() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(GENERATION_WAKE_LOCK_TIMEOUT_MS)
            DebugLog.log("[VIDEO-GEN] WakeLock acquired")
            recordServiceBreadcrumb("wake_lock_acquired")
        }
        WakeLockManager.acquireWifiLock(applicationContext, "VideoGenerationService")
    }

    private fun releaseWakeLockIfIdle() {
        if (!hasActiveWork()) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[VIDEO-GEN] WakeLock released")
                recordServiceBreadcrumb("wake_lock_released")
            }
            WakeLockManager.releaseWifiLock("VideoGenerationService")
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
                                "batteryExempt=${powerManager.isIgnoringBatteryOptimizations(packageName)} " +
                                "interactive=${powerManager.isInteractive} powerSave=${powerManager.isPowerSaveMode}"
                        DebugLog.log(
                            "[VIDEO-GEN] Stall detected: mode=${mode.name} phase=${diagnostics.phase} $message"
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
        }
    }

    private fun hasActiveWork(): Boolean = modeJobs.values.any { it.isActive }

    private fun hasActiveModeJob(
        mode: VideoGenerationMode,
        useDistributedStateHolder: Boolean
    ): Boolean = modeJobs[laneFor(mode, useDistributedStateHolder)]?.isActive == true

    private fun markActivity(mode: VideoGenerationMode, phase: String) {
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
                    "batteryExempt=${powerManager.isIgnoringBatteryOptimizations(packageName)} " +
                    "interactive=${powerManager.isInteractive} powerSave=${powerManager.isPowerSaveMode}"
            DebugLog.log(
                "[VIDEO-GEN] Activity resumed: mode=${mode.name} phase=$phase $message"
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

    private fun clearDiagnostics(mode: VideoGenerationMode) {
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

    private fun setupFFmpegLibrarySymlinks() {
        ffmpegLibDir.mkdirs()
        val versionedLibs = mapOf(
            "libx264.so.164" to "libx264.so.164.so"
        )
        val nativeLibDir = applicationInfo.nativeLibraryDir
        versionedLibs.forEach { (versionedName, actualName) ->
            val targetFile = File(nativeLibDir, actualName)
            val linkFile = File(ffmpegLibDir, versionedName)
            if (targetFile.exists() && !linkFile.exists()) {
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("ln", "-sf", targetFile.absolutePath, linkFile.absolutePath))
                        .waitFor()
                } catch (e: Exception) {
                    DebugLog.log("[VIDEO-GEN] Failed to create FFmpeg symlink $versionedName: ${e.message}")
                }
            }
        }
    }

    private fun setupSdLibrarySymlinks(sourceDir: File?, targetDir: File, binaryPath: String) {
        if (sourceDir == null) return

        val binaryName = File(binaryPath).name
        val tier = when {
            binaryName.contains("_snapdragon_vulkan") -> "_snapdragon_vulkan"
            binaryName.contains("_armv9") -> "_armv9"
            binaryName.contains("_dotprod") -> "_dotprod"
            binaryName.contains("_baseline") -> "_baseline"
            else -> ""
        }

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
            val sourceFile = sourceCandidates
                .firstNotNullOfOrNull { candidateName ->
                    File(sourceDir, candidateName).takeIf { it.exists() }
                } ?: continue
            val linkFile = File(targetDir, linkName)
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
                DebugLog.log("[VIDEO-GEN] Failed to create SD symlink $linkName: ${e.message}")
                try {
                    sourceFile.copyTo(linkFile, overwrite = true)
                } catch (_: Exception) {
                }
            }
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

        val message = getString(R.string.video_gen_error_media_processing_timeout)
        DebugLog.log("[VIDEO-GEN] Foreground media-processing timeout: startId=$startId fgsType=$fgsType")
        recordServiceBreadcrumb("foreground_timeout", "startId=$startId fgsType=$fgsType")

        val activeLanes = (
            modeJobs.keys +
                modeProcesses.keys +
                modeSessionIds.keys
            ).distinct()
        markTimedOut(activeLanes)

        activeLanes.forEach { lane ->
            publishTimeoutForLane(lane, message, event = "foreground_timeout")
        }

        val jobs = modeJobs.values.toList()
        val processes = modeProcesses.values.toList()
        modeJobs.clear()
        modeProcesses.clear()
        jobs.forEach { job ->
            job.cancel(MediaProcessingForegroundTimeoutCancellation(message))
        }
        forceStopProcesses(processes, "foreground_timeout")

        stallMonitorJob?.cancel()
        stallMonitorJob = null
        modeDiagnostics.clear()
        failForegroundTask(message)
        releaseWakeLocksForTimeout()
        stopSelf()
    }

    private fun publishTimeoutForLane(
        lane: VideoWorkLane,
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
        VideoGenerationStateHolder.getForMode(
            lane.mode,
            lane.useDistributedStateHolder
        ).updateState(VideoGenerationState.Error(message))
        finishModeSession(
            mode = lane.mode,
            useDistributedStateHolder = lane.useDistributedStateHolder,
            outcome = "timeout",
            details = message
        )
    }

    private fun forceStopProcesses(processes: Iterable<Process>, reason: String) {
        processes.forEach { process ->
            runCatching {
                if (process.isAlive) {
                    DebugLog.log("[VIDEO-GEN] Force-stopping native process for $reason")
                    process.destroyForcibly()
                }
            }.onFailure { error ->
                DebugLog.log("[VIDEO-GEN] Failed to force-stop native process: ${error.message}")
            }
        }
    }

    private fun releaseWakeLocksForTimeout() {
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
                .onSuccess {
                    DebugLog.log("[VIDEO-GEN] WakeLock released after foreground timeout")
                    recordServiceBreadcrumb("wake_lock_released", "foreground_timeout")
                }
        }
        WakeLockManager.releaseWifiLock("VideoGenerationService")
    }

    private fun markTimedOut(lanes: List<VideoWorkLane>) {
        synchronized(timedOutLanes) {
            timedOutLanes.addAll(lanes)
        }
    }

    private fun isTimedOutLane(lane: VideoWorkLane): Boolean = synchronized(timedOutLanes) {
        lane in timedOutLanes
    }

    private fun clearTimedOutLane(lane: VideoWorkLane) {
        synchronized(timedOutLanes) {
            timedOutLanes.remove(lane)
        }
    }

    private fun timeoutMessage(cancelled: CancellationException): String =
        (cancelled as? MediaProcessingForegroundTimeoutCancellation)?.userMessage
            ?: getString(R.string.video_gen_error_media_processing_timeout)

    override fun onDestroy() {
        recordServiceBreadcrumb("service_destroyed")
        modeProcesses.values.forEach { it.destroy() }
        modeProcesses.clear()
        modeJobs.values.forEach { it.cancel() }
        modeJobs.clear()
        modeSessionIds.clear()
        modeDiagnostics.clear()
        stallMonitorJob?.cancel()
        stallMonitorJob = null
        serviceScope.cancel()
        dismissForegroundTask()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        WakeLockManager.releaseWifiLock("VideoGenerationService")
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
        mode: VideoGenerationMode,
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
        mode: VideoGenerationMode,
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

    private fun buildSessionDetails(config: VideoGenerationConfig): String {
        return buildList {
            add("model=${File(config.diffusionModelPath).name}")
            add("size=${config.width}x${config.height}")
            add("frames=${config.videoFrames}")
            add("fps=${config.fps}")
            add("steps=${config.steps}")
            add("sampler=${config.samplingMethod.cliName}")
            add("vae=${config.useVae && !config.vaePath.isNullOrBlank()}")
            add("t5=${config.useT5xxl && !config.t5xxlPath.isNullOrBlank()}")
            add("initImage=${config.initImagePath != null}")
        }.joinToString(" ")
    }

    companion object {
        const val VIDEO_OUTPUT_FOLDER_NAME = "Generated videos"
        private const val STALL_MONITOR_INTERVAL_MS = 15_000L
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1_000L
        private const val STALL_THRESHOLD_MS_1 = 60_000L
        private const val STALL_THRESHOLD_MS_2 = 120_000L
        private const val STALL_THRESHOLD_MS_3 = 300_000L
        private const val VIDEO_PROGRESS_PUBLISH_INTERVAL_MS = 1_000L

        private const val ACTION_START_GENERATION = "com.example.llamadroid.action.START_VIDEO_GENERATION"
        private const val ACTION_CANCEL_MODE = "com.example.llamadroid.action.CANCEL_VIDEO_GENERATION"
        private const val ACTION_RETRY_CONVERSION = "com.example.llamadroid.action.RETRY_VIDEO_CONVERSION"
        private const val EXTRA_CONFIG = "extra_video_generation_config"
        private const val EXTRA_MODE = "extra_video_generation_mode"
        private const val EXTRA_METADATA_PATH = "extra_video_metadata_path"
        private const val EXTRA_USE_DISTRIBUTED_STATE_HOLDER = "extra_video_generation_use_distributed_holder"
        private const val DIAGNOSTIC_SOURCE = "video_generation"

        fun createStartIntent(
            context: Context,
            config: VideoGenerationConfig,
            useDistributedStateHolder: Boolean = false
        ): Intent =
            Intent(context, VideoGenerationService::class.java).apply {
                action = ACTION_START_GENERATION
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
            }

        fun createCancelIntent(
            context: Context,
            mode: VideoGenerationMode,
            useDistributedStateHolder: Boolean = false
        ): Intent =
            Intent(context, VideoGenerationService::class.java).apply {
                action = ACTION_CANCEL_MODE
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
            }

        fun createRetryConversionIntent(
            context: Context,
            metadata: GeneratedVideoMetadata,
            useDistributedStateHolder: Boolean = false
        ): Intent = Intent(context, VideoGenerationService::class.java).apply {
            action = ACTION_RETRY_CONVERSION
            putExtra(EXTRA_METADATA_PATH, metadata.metadataPath)
            putExtra(EXTRA_USE_DISTRIBUTED_STATE_HOLDER, useDistributedStateHolder)
        }
    }

    private data class VideoWorkLane(
        val mode: VideoGenerationMode,
        val useDistributedStateHolder: Boolean
    )

    private data class ActivityDiagnostics(
        var phase: String,
        var lastActivityElapsedMs: Long,
        var lastLoggedStallBucket: Int = 0
    )
}
