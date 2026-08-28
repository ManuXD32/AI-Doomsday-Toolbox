package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class VideoInterpolationService : Service() {
    private val binder = VideoInterpolationBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<VideoInterpolationState>(VideoInterpolationState.Idle)
    val state = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _eta = MutableStateFlow("")
    val eta = _eta.asStateFlow()

    private var currentProcess: Process? = null
    private var isCancelled = false
    private var notificationTaskId: Int? = null
    private lateinit var ffmpegLibDir: File

    inner class VideoInterpolationBinder : Binder() {
        fun getService(): VideoInterpolationService = this@VideoInterpolationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ffmpegLibDir = File(filesDir, "ffmpeg_libs")
        setupFFmpegLibrarySymlinks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_START_INTERPOLATION -> {
                val config = intent.toInterpolationConfig()
                if (config == null) {
                    _state.value = VideoInterpolationState.Error(getString(R.string.interpolation_error_missing_config))
                } else {
                    scope.launch { interpolate(config) }
                }
            }
            ACTION_START_INTERPOLATE_UPSCALE -> {
                val config = intent.toInterpolateUpscaleConfig()
                if (config == null) {
                    val message = getString(R.string.interpolation_error_missing_config)
                    VideoInterpolateUpscaleStateHolder.setError(message)
                    _state.value = VideoInterpolationState.Error(message)
                } else {
                    scope.launch { interpolateThenUpscale(config) }
                }
            }
            ACTION_CANCEL_INTERPOLATION -> cancel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        currentProcess?.destroy()
        WakeLockManager.release("VideoInterpolationService")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    suspend fun getVideoInfo(videoPath: String): Result<VideoInterpolationInfo> = withContext(Dispatchers.IO) {
        try {
            val binaryRepo = BinaryRepository(applicationContext)
            val ffprobeBinary = binaryRepo.getFFprobeBinary()
                ?: return@withContext Result.failure(Exception(getString(R.string.interpolation_error_ffprobe_missing)))
            if (!ffprobeBinary.exists()) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_error_ffprobe_missing)))
            }

            val args = listOf(
                ffprobeBinary.absolutePath,
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                videoPath
            )
            DebugLog.log("[INTERPOLATION] ffprobe: ${args.joinToString(" ")}")
            val processBuilder = ProcessBuilder(args)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            processBuilder.redirectErrorStream(false)
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0 || stdout.isBlank()) {
                return@withContext Result.failure(Exception(stderr.ifBlank { getString(R.string.interpolation_error_video_info) }))
            }

            val json = JSONObject(stdout)
            val streams = json.getJSONArray("streams")
            val format = json.getJSONObject("format")
            var width = 0
            var height = 0
            var rFps = 0.0
            var avgFps = 0.0
            var nbFrames = 0

            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                if (stream.optString("codec_type") == "video") {
                    width = stream.optInt("width", 0)
                    height = stream.optInt("height", 0)
                    rFps = parseFps(stream.optString("r_frame_rate", "0/1"))
                    avgFps = parseFps(stream.optString("avg_frame_rate", "0/1")).takeIf { it > 0.0 } ?: rFps
                    nbFrames = stream.optString("nb_frames").toIntOrNull() ?: 0
                    break
                }
            }
            if (width <= 0 || height <= 0 || avgFps <= 0.0) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_error_no_video_stream)))
            }
            val duration = format.optDouble("duration", 0.0)
            val estimatedFrames = if (nbFrames > 0) nbFrames else ceil(duration * avgFps).toInt().coerceAtLeast(1)
            val vfr = rFps > 0.0 && abs(rFps - avgFps) > 0.01

            Result.success(
                VideoInterpolationInfo(
                    width = width,
                    height = height,
                    fps = avgFps,
                    avgFps = avgFps,
                    durationSeconds = duration,
                    sizeBytes = format.optLong("size", 0L),
                    frameCount = estimatedFrames,
                    isLikelyVariableFrameRate = vfr
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun interpolate(config: VideoInterpolationConfig): Result<String> = withContext(Dispatchers.IO) {
        isCancelled = false
        VideoInterpolationStateHolder.reset()
        VideoInterpolationStateHolder.setInputPath(config.inputPath)
        publishProgress(getString(R.string.interpolation_status_preparing), 0f)

        val jobId = "interpolation_${System.currentTimeMillis()}"
        val jobDir = File(cacheDir, "media_jobs/$jobId")
        val inputFramesDir = File(jobDir, "input_frames")
        val interpolatedFramesDir = File(jobDir, "output_frames")
        val logsDir = File(jobDir, "logs")
        val resultDir = File(jobDir, "result")
        listOf(inputFramesDir, interpolatedFramesDir, logsDir, resultDir).forEach { it.mkdirs() }

        try {
            _state.value = VideoInterpolationState.PreparingModel
            updateNotification(getString(R.string.interpolation_notification_preparing, 0), 0)
            val asset = MediaModelRegistry.rifeById(config.modelId)
                ?: throw IllegalArgumentException(getString(R.string.interpolation_error_unknown_model, config.modelId))
            val modelValidation = MediaModelManager.validate(applicationContext, asset)
            val modelDir = when (modelValidation) {
                is MediaModelValidationResult.Installed -> modelValidation.modelDir
                is MediaModelValidationResult.ChecksumMismatch -> {
                    throw IllegalStateException(getString(R.string.interpolation_error_model_checksum, modelValidation.relativePath))
                }
                is MediaModelValidationResult.Incomplete -> {
                    throw IllegalStateException(getString(R.string.interpolation_error_model_incomplete))
                }
                is MediaModelValidationResult.Missing -> {
                    throw IllegalStateException(getString(R.string.interpolation_error_model_missing))
                }
            }

            _state.value = VideoInterpolationState.ReadingVideo
            publishProgress(getString(R.string.interpolation_status_reading), 0.03f)
            val info = getVideoInfo(config.inputPath).getOrThrow()
            val outputFps = VideoInterpolationMath.outputFps(info.fps, config.multiplier)
            val estimatedOutputFrames = VideoInterpolationMath.outputFrameCount(info.frameCount, config.multiplier)
            checkFreeSpace(jobDir, info, estimatedOutputFrames)
            DebugLog.log(
                "[INTERPOLATION] model=${asset.id} requestedBackend=${config.backend} inputFps=${info.fps} " +
                    "outputFps=$outputFps inputFrames=${info.frameCount} outputFrames=$estimatedOutputFrames"
            )

            _state.value = VideoInterpolationState.ExtractingFrames
            publishProgress(getString(R.string.interpolation_status_extracting), 0.08f)
            updateNotification(getString(R.string.interpolation_notification_extracting, 8), 8)
            extractFrames(config.inputPath, inputFramesDir, info.fps).getOrThrow()
            throwIfCancelled()

            val sourceFrameCount = countPngFrames(inputFramesDir)
            if (sourceFrameCount <= 1) {
                throw IllegalStateException(getString(R.string.interpolation_error_too_few_frames))
            }
            val outputFrameCount = VideoInterpolationMath.outputFrameCount(sourceFrameCount, config.multiplier)
            val sceneCuts = if (config.sceneCutProtection) {
                _state.value = VideoInterpolationState.DetectingSceneCuts
                publishProgress(getString(R.string.interpolation_status_detecting_cuts), 0.12f, 0, outputFrameCount)
                detectSceneCuts(inputFramesDir, sourceFrameCount)
            } else {
                emptySet()
            }
            throwIfCancelled()

            val backendUsed = runInterpolationWithFallback(
                config = config,
                modelDir = modelDir,
                inputFramesDir = inputFramesDir,
                outputFramesDir = interpolatedFramesDir,
                outputFrameCount = outputFrameCount
            ).getOrThrow()
            VideoInterpolationStateHolder.setBackendUsed(backendUsed)
            throwIfCancelled()

            if (sceneCuts.isNotEmpty()) {
                applySceneCutProtection(
                    outputFramesDir = interpolatedFramesDir,
                    sourceFrameCount = sourceFrameCount,
                    multiplier = config.multiplier,
                    sceneCuts = sceneCuts
                )
            }

            _state.value = VideoInterpolationState.EncodingVideo
            publishProgress(getString(R.string.interpolation_status_encoding), 0.92f, outputFrameCount, outputFrameCount)
            updateNotification(getString(R.string.interpolation_notification_encoding, 92), 92)
            encodeVideoWithAudio(
                originalVideo = config.inputPath,
                framesDir = interpolatedFramesDir,
                outputPath = config.outputPath,
                fps = outputFps,
                codec = config.codec,
                crf = config.crf,
                preserveAudio = config.preserveAudio
            ).getOrThrow()

            _state.value = VideoInterpolationState.Finalizing
            cleanup(jobDir)
            _state.value = VideoInterpolationState.Completed(config.outputPath, backendUsed)
            publishProgress(
                status = getString(R.string.interpolation_status_completed_backend, backendUsed.name),
                progress = 1f,
                currentFrame = outputFrameCount,
                totalFrames = outputFrameCount,
                resultPath = config.outputPath,
                processing = false
            )
            updateNotification(getString(R.string.interpolation_notification_complete, 100), 100)
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(
                    it,
                    getString(R.string.interpolation_status_completed_backend, backendUsed.name)
                )
            }
            Result.success(config.outputPath)
        } catch (e: Exception) {
            cleanup(jobDir)
            val message = e.message ?: getString(R.string.interpolation_error_unknown)
            if (isCancelled || message == getString(R.string.interpolation_status_cancelled)) {
                File(config.outputPath).delete()
                _state.value = VideoInterpolationState.Idle
                publishProgress(
                    status = getString(R.string.interpolation_status_cancelled),
                    progress = 0f,
                    processing = false
                )
                updateNotification(getString(R.string.interpolation_notification_cancelled, 0), 0)
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                notificationTaskId = null
                return@withContext Result.failure(e)
            }
            _state.value = VideoInterpolationState.Error(message)
            publishProgress(message, 0f, error = message, processing = false)
            updateNotification(getString(R.string.interpolation_notification_error, message), 0)
            notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
            Result.failure(e)
        }
    }

    suspend fun interpolateThenUpscale(config: VideoInterpolateUpscaleConfig): Result<String> = withContext(Dispatchers.IO) {
        isCancelled = false
        VideoInterpolateUpscaleStateHolder.reset()
        VideoUpscalerStateHolder.reset()
        val jobId = "interpolate_upscale_${System.currentTimeMillis()}"
        val jobDir = File(cacheDir, "media_jobs/$jobId").apply { mkdirs() }
        val intermediate = File(jobDir, "interpolated_intermediate.mp4")
        val finalTemp = File(jobDir, "upscaled_final.mp4")
        var backendUsed: VideoInterpolationBackend? = null
        var sourceInfo: VideoInterpolationInfo? = null

        try {
            sourceInfo = getVideoInfo(config.interpolationConfig.inputPath).getOrNull()
            val interpolationConfig = config.interpolationConfig.copy(outputPath = intermediate.absolutePath)
            VideoInterpolateUpscaleStateHolder.setRunning(
                VideoInterpolateUpscaleState.Interpolating,
                getString(R.string.interpolation_running_title),
                0f
            )
            DebugLog.log(
                "[INTERPOLATE_UPSCALE] start input=${File(interpolationConfig.inputPath).name} " +
                    "model=${interpolationConfig.modelId} upscale=${config.upscaleConfig.engine}/${config.upscaleConfig.model} " +
                    "scale=${config.upscaleConfig.scale} denoise=${config.upscaleConfig.denoise}"
            )

            val interpolationMonitor = launch {
                while (isActive && VideoInterpolateUpscaleStateHolder.state.value is VideoInterpolateUpscaleState.Interpolating) {
                    val status = VideoInterpolationStateHolder.status.value.ifBlank {
                        getString(R.string.interpolation_running_title)
                    }
                    VideoInterpolateUpscaleStateHolder.setProgress(status, VideoInterpolationStateHolder.progress.value * 0.5f)
                    delay(300)
                }
            }
            val interpolatedPath = interpolate(interpolationConfig).getOrThrow()
            interpolationMonitor.cancel()
            backendUsed = VideoInterpolationStateHolder.backendUsed.value
            validateVideoOutput(interpolatedPath).getOrThrow()
            throwIfCancelled()

            VideoInterpolateUpscaleStateHolder.setRunning(
                VideoInterpolateUpscaleState.Upscaling,
                getString(R.string.interpolation_upscale_stage),
                0.5f
            )
            val upscaleConfig = config.upscaleConfig.copy(
                inputPath = interpolatedPath,
                outputPath = finalTemp.absolutePath
            )
            val upscalerMonitor = launch {
                while (isActive && VideoInterpolateUpscaleStateHolder.state.value is VideoInterpolateUpscaleState.Upscaling) {
                    val status = VideoUpscalerStateHolder.status.value.ifBlank {
                        getString(R.string.interpolation_upscale_stage)
                    }
                    VideoInterpolateUpscaleStateHolder.setProgress(status, 0.5f + (VideoUpscalerStateHolder.progress.value * 0.5f))
                    delay(300)
                }
            }
            startForegroundService(VideoUpscalerService.createStartIntent(applicationContext, upscaleConfig))
            val finalPath = waitForUpscaleResult(finalTemp).getOrThrow()
            upscalerMonitor.cancel()
            throwIfCancelled()

            VideoInterpolateUpscaleStateHolder.setRunning(
                VideoInterpolateUpscaleState.Finalizing,
                getString(R.string.llama_scheduler_status_finalizing),
                0.98f
            )
            val finalInfo = validateVideoOutput(finalPath).getOrThrow()
            val galleryItem = VideoInterpolationGalleryStore.save(
                context = applicationContext,
                source = File(finalPath),
                config = interpolationConfig,
                info = sourceInfo,
                backendUsed = backendUsed,
                workflow = "INTERPOLATE_UPSCALE",
                upscaleModel = config.upscaleConfig.model,
                upscaleScale = config.upscaleConfig.scale
            )
            DebugLog.log(
                "[INTERPOLATE_UPSCALE] complete output=${galleryItem.videoFile.name} " +
                    "resolution=${finalInfo.resolution} fps=${finalInfo.fps} bytes=${galleryItem.videoFile.length()}"
            )
            VideoInterpolateUpscaleStateHolder.setCompleted(
                outputPath = galleryItem.videoFile.absolutePath,
                galleryId = galleryItem.id,
                status = getString(R.string.upscaler_complete)
            )
            runCatching { File(interpolatedPath).delete() }
            runCatching { File(finalPath).delete() }
            cleanup(jobDir)
            Result.success(galleryItem.videoFile.absolutePath)
        } catch (e: Exception) {
            val message = e.message ?: getString(R.string.interpolation_error_unknown)
            DebugLog.log("[INTERPOLATE_UPSCALE] failed: $message")
            VideoInterpolateUpscaleStateHolder.setError(message)
            runCatching { applicationContext.startService(VideoUpscalerService.createCancelIntent(applicationContext)) }
            runCatching { intermediate.delete() }
            runCatching { finalTemp.delete() }
            cleanup(jobDir)
            Result.failure(e)
        }
    }

    fun cancel() {
        isCancelled = true
        currentProcess?.destroy()
        runCatching { currentProcess?.destroyForcibly() }
        currentProcess = null
        _state.value = VideoInterpolationState.Idle
        publishProgress(getString(R.string.interpolation_status_cancelled), 0f, processing = false)
        updateNotification(getString(R.string.interpolation_notification_cancelled, 0), 0)
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        VideoInterpolateUpscaleStateHolder.setError(getString(R.string.interpolation_status_cancelled))
        runCatching { applicationContext.startService(VideoUpscalerService.createCancelIntent(applicationContext)) }
    }

    private suspend fun waitForUpscaleResult(expectedOutput: File): Result<String> {
        while (!isCancelled) {
            VideoUpscalerStateHolder.error.value?.takeIf { it.isNotBlank() }?.let {
                return Result.failure(IllegalStateException(it))
            }
            val resultPath = VideoUpscalerStateHolder.resultPath.value
            if (!VideoUpscalerStateHolder.isProcessing.value && !resultPath.isNullOrBlank()) {
                return Result.success(resultPath)
            }
            if (!VideoUpscalerStateHolder.isProcessing.value && expectedOutput.isFile && expectedOutput.length() > 0L) {
                return Result.success(expectedOutput.absolutePath)
            }
            delay(500)
        }
        return Result.failure(IllegalStateException(getString(R.string.interpolation_status_cancelled)))
    }

    private suspend fun validateVideoOutput(path: String): Result<VideoInterpolationInfo> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            return@withContext Result.failure(IllegalStateException(getString(R.string.upscaler_error_video_info)))
        }
        val info = getVideoInfo(path).getOrElse {
            return@withContext Result.failure(IllegalStateException(getString(R.string.upscaler_error_video_info)))
        }
        if (info.width <= 0 || info.height <= 0 || info.durationSeconds <= 0.0 || info.frameCount <= 0) {
            return@withContext Result.failure(IllegalStateException(getString(R.string.upscaler_error_video_info)))
        }
        Result.success(info)
    }

    private suspend fun runInterpolationWithFallback(
        config: VideoInterpolationConfig,
        modelDir: File,
        inputFramesDir: File,
        outputFramesDir: File,
        outputFrameCount: Int
    ): Result<VideoInterpolationBackend> {
        val attemptBackends = when (config.backend) {
            VideoInterpolationBackend.AUTO -> listOf(VideoInterpolationBackend.VULKAN, VideoInterpolationBackend.CPU)
            else -> listOf(config.backend)
        }
        var lastError: Throwable? = null
        for (backend in attemptBackends) {
            val result = runRife(
                config = config,
                modelDir = modelDir,
                inputFramesDir = inputFramesDir,
                outputFramesDir = outputFramesDir,
                outputFrameCount = outputFrameCount,
                backend = backend
            )
            if (result.isSuccess) {
                DebugLog.log("[INTERPOLATION] Backend used: $backend")
                return Result.success(backend)
            }
            lastError = result.exceptionOrNull()
            DebugLog.log("[INTERPOLATION] Backend $backend failed: ${lastError?.message}")
            if (config.backend == VideoInterpolationBackend.AUTO && backend == VideoInterpolationBackend.VULKAN) {
                publishProgress(getString(R.string.interpolation_status_vulkan_fallback), 0.16f)
            }
        }
        return Result.failure(lastError ?: IllegalStateException(getString(R.string.interpolation_error_native_failed)))
    }

    private suspend fun runRife(
        config: VideoInterpolationConfig,
        modelDir: File,
        inputFramesDir: File,
        outputFramesDir: File,
        outputFrameCount: Int,
        backend: VideoInterpolationBackend
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            outputFramesDir.deleteRecursively()
            outputFramesDir.mkdirs()
            val binaryRepo = BinaryRepository(applicationContext)
            val binary = binaryRepo.getTieredBinary("rife-ncnn")
                ?: File(applicationInfo.nativeLibraryDir, "librife-ncnn.so")
            if (!binary.exists()) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_error_binary_missing)))
            }

            val args = mutableListOf(
                binary.absolutePath,
                "-i", inputFramesDir.absolutePath,
                "-o", outputFramesDir.absolutePath,
                "-m", modelDir.absolutePath,
                "-n", outputFrameCount.toString(),
                "-j", config.threadSpec,
                "-f", "%08d.png"
            )
            when (backend) {
                VideoInterpolationBackend.CPU -> args.addAll(listOf("-g", "-1"))
                VideoInterpolationBackend.VULKAN -> args.addAll(listOf("-g", "0"))
                VideoInterpolationBackend.AUTO -> Unit
            }
            if (config.spatialTta || config.temporalTta) args.add("-x")
            if (config.uhdMode) args.add("-u")
            DebugLog.log("[INTERPOLATION] RIFE: ${args.joinToString(" ")}")

            val processBuilder = ProcessBuilder(args)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            processBuilder.redirectErrorStream(true)
            currentProcess = processBuilder.start()
            val startTime = System.currentTimeMillis()

            val logJob = scope.launch(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                while (isActive && (currentProcess?.isAlive == true || reader.ready())) {
                    val line = runCatching { reader.readLine() }.getOrNull()
                    if (line != null) {
                        DebugLog.log("[RIFE] $line")
                    } else {
                        delay(100)
                    }
                }
            }

            val progressJob = scope.launch {
                while (currentProcess?.isAlive == true && !isCancelled) {
                    val done = countPngFrames(outputFramesDir)
                    val frameProgress = (done.toFloat() / outputFrameCount.toFloat()).coerceIn(0f, 1f)
                    val totalProgress = 0.16f + (frameProgress * 0.74f)
                    _progress.value = totalProgress
                    _state.value = VideoInterpolationState.Interpolating(done, outputFrameCount, backend)
                    val etaText = etaText(done, outputFrameCount, startTime)
                    _eta.value = etaText
                    val percent = (totalProgress * 100).toInt().coerceIn(0, 99)
                    val status = getString(
                        R.string.interpolation_status_frames_backend,
                        done,
                        outputFrameCount,
                        percent,
                        backend.name
                    )
                    publishProgress(status, totalProgress, done, outputFrameCount)
                    updateNotification(
                        getString(R.string.interpolation_notification_frames, done, outputFrameCount, percent, etaText),
                        percent
                    )
                    delay(1000)
                }
            }

            val exitCode = currentProcess!!.waitFor()
            progressJob.cancel()
            logJob.cancel()
            currentProcess = null
            if (exitCode != 0 && !isCancelled) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_error_native_exit, exitCode)))
            }
            if (isCancelled) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_status_cancelled)))
            }
            val produced = countPngFrames(outputFramesDir)
            if (produced <= 0) {
                return@withContext Result.failure(Exception(getString(R.string.interpolation_error_no_output_frames)))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun extractFrames(inputPath: String, outputDir: File, fps: Double): Result<Unit> =
        withContext(Dispatchers.IO) {
            runFfmpeg(
                listOf(
                    "-y",
                    "-i", inputPath,
                    "-vf", "fps=${formatFps(fps)}",
                    "-start_number", "0",
                    "${outputDir.absolutePath}/%08d.png"
                ),
                getString(R.string.interpolation_error_extract_failed)
            )
        }

    private suspend fun encodeVideoWithAudio(
        originalVideo: String,
        framesDir: File,
        outputPath: String,
        fps: Double,
        codec: VideoInterpolationCodec,
        crf: Int,
        preserveAudio: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val commonArgs = listOf(
            "-y",
            "-framerate", formatFps(fps),
            "-start_number", "0",
            "-i", "${framesDir.absolutePath}/%08d.png",
            "-i", originalVideo,
            "-map", "0:v:0",
            "-map", "1:a:0?",
            "-c:v", codec.ffmpegCodec,
            "-crf", crf.coerceIn(14, 32).toString(),
            "-pix_fmt", "yuv420p",
            "-r", formatFps(fps)
        )
        val audioCopyArgs = commonArgs + if (preserveAudio) {
            listOf("-c:a", "copy", "-shortest", outputPath)
        } else {
            listOf("-an", outputPath)
        }
        val first = runFfmpeg(audioCopyArgs, getString(R.string.interpolation_error_encode_failed))
        if (first.isSuccess || !preserveAudio) return@withContext first

        _state.value = VideoInterpolationState.RestoringAudio
        val audioFallbackArgs = commonArgs + listOf("-c:a", "aac", "-b:a", "160k", "-shortest", outputPath)
        runFfmpeg(audioFallbackArgs, getString(R.string.interpolation_error_audio_remux_failed))
    }

    private suspend fun runFfmpeg(argsWithoutBinary: List<String>, errorPrefix: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val binaryRepo = BinaryRepository(applicationContext)
                val ffmpegBinary = binaryRepo.getFFmpegBinary()
                    ?: return@withContext Result.failure(Exception(getString(R.string.interpolation_error_ffmpeg_missing)))
                if (!ffmpegBinary.exists()) {
                    return@withContext Result.failure(Exception(getString(R.string.interpolation_error_ffmpeg_missing)))
                }
                val args = listOf(ffmpegBinary.absolutePath) + argsWithoutBinary
                DebugLog.log("[INTERPOLATION] ffmpeg: ${args.joinToString(" ")}")
                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
                processBuilder.redirectErrorStream(true)
                currentProcess = processBuilder.start()
                val stderr = currentProcess!!.inputStream.bufferedReader().readText()
                val exitCode = currentProcess!!.waitFor()
                currentProcess = null
                if (exitCode != 0 && !isCancelled) {
                    return@withContext Result.failure(Exception("$errorPrefix: ${stderr.takeLast(500)}"))
                }
                if (isCancelled) {
                    return@withContext Result.failure(Exception(getString(R.string.interpolation_status_cancelled)))
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun detectSceneCuts(framesDir: File, sourceFrameCount: Int): Set<Int> {
        val cuts = mutableSetOf<Int>()
        var previous: Bitmap? = null
        for (index in 0 until sourceFrameCount) {
            if (isCancelled) break
            val bitmap = decodeSampledBitmap(File(framesDir, "%08d.png".format(index)))
            if (previous != null && bitmap != null) {
                val diff = averageFrameDifference(previous, bitmap)
                if (diff >= SCENE_CUT_THRESHOLD) {
                    cuts += index - 1
                }
            }
            previous?.recycle()
            previous = bitmap
        }
        previous?.recycle()
        DebugLog.log("[INTERPOLATION] Scene cuts detected: ${cuts.size}")
        return cuts
    }

    private fun applySceneCutProtection(
        outputFramesDir: File,
        sourceFrameCount: Int,
        multiplier: Int,
        sceneCuts: Set<Int>
    ) {
        sceneCuts.forEach { sourceIndex ->
            if (sourceIndex !in 0 until sourceFrameCount - 1) return@forEach
            val holdFile = File(outputFramesDir, "%08d.png".format(sourceIndex * multiplier))
            if (!holdFile.isFile) return@forEach
            for (offset in 1 until multiplier) {
                val target = File(outputFramesDir, "%08d.png".format(sourceIndex * multiplier + offset))
                holdFile.copyTo(target, overwrite = true)
            }
        }
    }

    private fun averageFrameDifference(first: Bitmap, second: Bitmap): Double {
        val width = min(first.width, second.width)
        val height = min(first.height, second.height)
        if (width <= 0 || height <= 0) return 0.0
        var diff = 0L
        var samples = 0
        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                diff += abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
                diff += abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
                diff += abs((a and 0xff) - (b and 0xff))
                samples += 3
                x += stepX
            }
            y += stepY
        }
        return diff.toDouble() / (samples.toDouble() * 255.0)
    }

    private fun decodeSampledBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun checkFreeSpace(jobDir: File, info: VideoInterpolationInfo, outputFrames: Int) {
        val bytesPerFrame = (info.width.toLong() * info.height.toLong() * 4L).coerceAtLeast(1L)
        val estimated = bytesPerFrame * (info.frameCount.toLong() + outputFrames.toLong())
        val available = jobDir.usableSpace
        if (available < estimated) {
            throw IllegalStateException(
                getString(
                    R.string.interpolation_error_storage,
                    com.example.llamadroid.util.FormatUtils.Technical.formatBytes(estimated),
                    com.example.llamadroid.util.FormatUtils.Technical.formatBytes(available)
                )
            )
        }
    }

    private fun publishProgress(
        status: String,
        progress: Float,
        currentFrame: Int = 0,
        totalFrames: Int = 0,
        resultPath: String? = null,
        error: String? = null,
        processing: Boolean = true
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        _progress.value = clamped
        VideoInterpolationStateHolder.setIsProcessing(processing)
        VideoInterpolationStateHolder.setProgress(clamped)
        VideoInterpolationStateHolder.setCurrentFrame(currentFrame)
        VideoInterpolationStateHolder.setTotalFrames(totalFrames)
        VideoInterpolationStateHolder.setStatus(status)
        VideoInterpolationStateHolder.setResultPath(resultPath)
        VideoInterpolationStateHolder.setError(error)
    }

    private fun updateNotification(text: String, progress: Int) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, progress / 100f, text)
        }
    }

    private fun startForegroundWithNotification() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.VIDEO_INTERPOLATION,
            getString(R.string.interpolation_notification_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        WakeLockManager.acquire(applicationContext, "VideoInterpolationService")
    }

    private fun setupFFmpegLibrarySymlinks() {
        ffmpegLibDir.mkdirs()
        val targetFile = File(applicationInfo.nativeLibraryDir, "libx264.so.164.so")
        val linkFile = File(ffmpegLibDir, "libx264.so.164")
        if (targetFile.exists() && !linkFile.exists()) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", targetFile.absolutePath, linkFile.absolutePath)).waitFor()
            }
        }
    }

    private fun cleanup(vararg files: File) {
        files.forEach { file ->
            if (file.exists()) file.deleteRecursively()
        }
    }

    private fun countPngFrames(dir: File): Int = dir.listFiles()?.count { it.extension.equals("png", ignoreCase = true) } ?: 0

    private fun throwIfCancelled() {
        if (isCancelled) throw IllegalStateException(getString(R.string.interpolation_status_cancelled))
    }

    private fun etaText(done: Int, total: Int, startTime: Long): String {
        if (done <= 0 || total <= done) return "--"
        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
        val framesPerMs = done.toDouble() / elapsed.toDouble()
        val etaMs = ((total - done) / framesPerMs).toLong()
        val mins = etaMs / 60000
        val secs = (etaMs % 60000) / 1000
        return "${mins}m ${secs}s"
    }

    private fun parseFps(value: String): Double {
        val trimmed = value.trim()
        if (trimmed.contains("/")) {
            val parts = trimmed.split("/")
            val numerator = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
            val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: return 0.0
            return if (denominator == 0.0) 0.0 else numerator / denominator
        }
        return trimmed.toDoubleOrNull() ?: 0.0
    }

    private fun formatFps(fps: Double): String = "%.3f".format(java.util.Locale.US, fps)

    private fun Intent.toInterpolationConfig(): VideoInterpolationConfig? {
        val inputPath = getStringExtra(EXTRA_INPUT_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val outputPath = getStringExtra(EXTRA_OUTPUT_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val backend = getStringExtra(EXTRA_BACKEND)
            ?.let { value -> VideoInterpolationBackend.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
            ?: VideoInterpolationBackend.AUTO
        val codec = getStringExtra(EXTRA_CODEC)
            ?.let { value -> VideoInterpolationCodec.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
            ?: VideoInterpolationCodec.H264
        return VideoInterpolationConfig(
            inputPath = inputPath,
            outputPath = outputPath,
            modelId = getStringExtra(EXTRA_MODEL_ID) ?: MediaModelRegistry.defaultRifeModel.id,
            multiplier = getIntExtra(EXTRA_MULTIPLIER, 2),
            backend = backend,
            preserveAudio = getBooleanExtra(EXTRA_PRESERVE_AUDIO, true),
            sceneCutProtection = getBooleanExtra(EXTRA_SCENE_CUT, true),
            spatialTta = getBooleanExtra(EXTRA_SPATIAL_TTA, false),
            temporalTta = getBooleanExtra(EXTRA_TEMPORAL_TTA, false),
            uhdMode = getBooleanExtra(EXTRA_UHD_MODE, false),
            threadSpec = getStringExtra(EXTRA_THREAD_SPEC) ?: "1:2:2",
            codec = codec,
            crf = getIntExtra(EXTRA_CRF, 20)
        )
    }

    private fun Intent.toInterpolateUpscaleConfig(): VideoInterpolateUpscaleConfig? {
        val interpolation = toInterpolationConfig() ?: return null
        val engine = getStringExtra(EXTRA_UPSCALE_ENGINE)
            ?.let { value -> UpscalerEngine.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
            ?: return null
        val model = getStringExtra(EXTRA_UPSCALE_MODEL)?.takeIf { it.isNotBlank() } ?: return null
        val upscaleConfig = VideoUpscalerConfig(
            inputPath = interpolation.outputPath,
            outputPath = getStringExtra(EXTRA_UPSCALE_OUTPUT_PATH)?.takeIf { it.isNotBlank() } ?: interpolation.outputPath,
            engine = engine,
            model = model,
            scale = getIntExtra(EXTRA_UPSCALE_SCALE, 2),
            denoise = getIntExtra(EXTRA_UPSCALE_DENOISE, -1),
            loadThreads = getIntExtra(EXTRA_UPSCALE_LOAD_THREADS, 1),
            procThreads = getIntExtra(EXTRA_UPSCALE_PROC_THREADS, 1),
            saveThreads = getIntExtra(EXTRA_UPSCALE_SAVE_THREADS, 1)
        )
        return VideoInterpolateUpscaleConfig(interpolation, upscaleConfig)
    }

    companion object {
        private const val ACTION_START_INTERPOLATION = "com.example.llamadroid.action.START_VIDEO_INTERPOLATION"
        private const val ACTION_START_INTERPOLATE_UPSCALE = "com.example.llamadroid.action.START_VIDEO_INTERPOLATE_UPSCALE"
        private const val ACTION_CANCEL_INTERPOLATION = "com.example.llamadroid.action.CANCEL_VIDEO_INTERPOLATION"
        private const val EXTRA_INPUT_PATH = "extra_video_interpolation_input"
        private const val EXTRA_OUTPUT_PATH = "extra_video_interpolation_output"
        private const val EXTRA_MODEL_ID = "extra_video_interpolation_model_id"
        private const val EXTRA_MULTIPLIER = "extra_video_interpolation_multiplier"
        private const val EXTRA_BACKEND = "extra_video_interpolation_backend"
        private const val EXTRA_PRESERVE_AUDIO = "extra_video_interpolation_preserve_audio"
        private const val EXTRA_SCENE_CUT = "extra_video_interpolation_scene_cut"
        private const val EXTRA_SPATIAL_TTA = "extra_video_interpolation_spatial_tta"
        private const val EXTRA_TEMPORAL_TTA = "extra_video_interpolation_temporal_tta"
        private const val EXTRA_UHD_MODE = "extra_video_interpolation_uhd_mode"
        private const val EXTRA_THREAD_SPEC = "extra_video_interpolation_thread_spec"
        private const val EXTRA_CODEC = "extra_video_interpolation_codec"
        private const val EXTRA_CRF = "extra_video_interpolation_crf"
        private const val EXTRA_UPSCALE_OUTPUT_PATH = "extra_video_interpolation_upscale_output"
        private const val EXTRA_UPSCALE_ENGINE = "extra_video_interpolation_upscale_engine"
        private const val EXTRA_UPSCALE_MODEL = "extra_video_interpolation_upscale_model"
        private const val EXTRA_UPSCALE_SCALE = "extra_video_interpolation_upscale_scale"
        private const val EXTRA_UPSCALE_DENOISE = "extra_video_interpolation_upscale_denoise"
        private const val EXTRA_UPSCALE_LOAD_THREADS = "extra_video_interpolation_upscale_load_threads"
        private const val EXTRA_UPSCALE_PROC_THREADS = "extra_video_interpolation_upscale_proc_threads"
        private const val EXTRA_UPSCALE_SAVE_THREADS = "extra_video_interpolation_upscale_save_threads"
        private const val SCENE_CUT_THRESHOLD = 0.35

        fun createStartIntent(context: Context, config: VideoInterpolationConfig): Intent =
            Intent(context, VideoInterpolationService::class.java).apply {
                action = ACTION_START_INTERPOLATION
                putExtra(EXTRA_INPUT_PATH, config.inputPath)
                putExtra(EXTRA_OUTPUT_PATH, config.outputPath)
                putExtra(EXTRA_MODEL_ID, config.modelId)
                putExtra(EXTRA_MULTIPLIER, config.multiplier)
                putExtra(EXTRA_BACKEND, config.backend.name)
                putExtra(EXTRA_PRESERVE_AUDIO, config.preserveAudio)
                putExtra(EXTRA_SCENE_CUT, config.sceneCutProtection)
                putExtra(EXTRA_SPATIAL_TTA, config.spatialTta)
                putExtra(EXTRA_TEMPORAL_TTA, config.temporalTta)
                putExtra(EXTRA_UHD_MODE, config.uhdMode)
                putExtra(EXTRA_THREAD_SPEC, config.threadSpec)
                putExtra(EXTRA_CODEC, config.codec.name)
                putExtra(EXTRA_CRF, config.crf)
            }

        fun createStartInterpolateUpscaleIntent(context: Context, config: VideoInterpolateUpscaleConfig): Intent =
            createStartIntent(context, config.interpolationConfig).apply {
                action = ACTION_START_INTERPOLATE_UPSCALE
                putExtra(EXTRA_UPSCALE_OUTPUT_PATH, config.upscaleConfig.outputPath)
                putExtra(EXTRA_UPSCALE_ENGINE, config.upscaleConfig.engine.name)
                putExtra(EXTRA_UPSCALE_MODEL, config.upscaleConfig.model)
                putExtra(EXTRA_UPSCALE_SCALE, config.upscaleConfig.scale)
                putExtra(EXTRA_UPSCALE_DENOISE, config.upscaleConfig.denoise)
                putExtra(EXTRA_UPSCALE_LOAD_THREADS, config.upscaleConfig.loadThreads)
                putExtra(EXTRA_UPSCALE_PROC_THREADS, config.upscaleConfig.procThreads)
                putExtra(EXTRA_UPSCALE_SAVE_THREADS, config.upscaleConfig.saveThreads)
            }

        fun createCancelIntent(context: Context): Intent =
            Intent(context, VideoInterpolationService::class.java).apply {
                action = ACTION_CANCEL_INTERPOLATION
            }
    }
}
