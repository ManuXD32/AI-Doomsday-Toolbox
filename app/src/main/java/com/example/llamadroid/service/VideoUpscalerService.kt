package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.UpscalerAssetPackSupport
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service for video upscaling using realsr-ncnn/realcugan-ncnn
 * Workflow: Extract frames → Upscale → Rebuild with audio
 */
class VideoUpscalerService : Service() {
    
    private val binder = VideoUpscalerBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _state = MutableStateFlow<VideoUpscalerState>(VideoUpscalerState.Idle)
    val state = _state.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()
    
    private val _eta = MutableStateFlow("")
    val eta = _eta.asStateFlow()
    
    private var currentProcess: Process? = null
    private var isCancelled = false
    private var notificationTaskId: Int? = null
    
    private lateinit var modelsDir: File
    
    inner class VideoUpscalerBinder : Binder() {
        fun getService(): VideoUpscalerService = this@VideoUpscalerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        modelsDir = File(filesDir, "upscaler_models")
        ffmpegLibDir = File(filesDir, "ffmpeg_libs")
        setupFFmpegLibrarySymlinks()
        // Extract models from assets on first run
        scope.launch {
            extractModelsFromAssets()
        }
    }
    
    private lateinit var ffmpegLibDir: File
    
    /**
     * Create symlinks for versioned library names that FFmpeg expects
     */
    private fun setupFFmpegLibrarySymlinks() {
        ffmpegLibDir.mkdirs()
        
        // Only need libx264 versioned symlink with new FFmpeg build
        val versionedLibs = mapOf(
            "libx264.so.164" to "libx264.so.164.so"
        )
        
        val nativeLibDir = applicationInfo.nativeLibraryDir
        
        versionedLibs.forEach { (versionedName, actualName) ->
            val targetFile = File(nativeLibDir, actualName)
            val linkFile = File(ffmpegLibDir, versionedName)
            
            if (targetFile.exists() && !linkFile.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", targetFile.absolutePath, linkFile.absolutePath)).waitFor()
                    DebugLog.log("[UPSCALER] Created symlink: $versionedName -> $actualName")
                } catch (e: Exception) {
                    DebugLog.log("[UPSCALER] Failed to create symlink for $versionedName: ${e.message}")
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_START_UPSCALE -> {
                val config = intent.toUpscalerConfig()
                if (config == null) {
                    _state.value = VideoUpscalerState.Error("Missing video upscale configuration")
                } else {
                    scope.launch {
                        upscale(config)
                    }
                }
            }
            ACTION_CANCEL_UPSCALE -> cancel()
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        currentProcess?.destroy()
        WakeLockManager.release("VideoUpscalerService")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    
    private suspend fun extractModelsFromAssets() = withContext(Dispatchers.IO) {
        val result = UpscalerAssetPackSupport.ensureLocalModelsAvailable(applicationContext)
        result.onSuccess {
            modelsDir = it
            DebugLog.log("[UPSCALER] Models ready in ${it.absolutePath}")
        }.onFailure { error ->
            DebugLog.log("[UPSCALER] Failed to prepare models: ${error.message}")
        }
    }
    
    private fun updateNotification(text: String, progress: Int = 0) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, progress / 100f, text)
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
        VideoUpscalerStateHolder.setIsProcessing(processing)
        VideoUpscalerStateHolder.setProgress(progress.coerceIn(0f, 1f))
        VideoUpscalerStateHolder.setCurrentFrame(currentFrame)
        VideoUpscalerStateHolder.setTotalFrames(totalFrames)
        VideoUpscalerStateHolder.setStatus(status)
        VideoUpscalerStateHolder.setResultPath(resultPath)
        VideoUpscalerStateHolder.setError(error)
    }
    
    private fun startForegroundWithNotification() {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.VIDEO_UPSCALE,
            "Video Upscaling"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        WakeLockManager.acquire(applicationContext, "VideoUpscalerService")
    }
    
    /**
     * Get video information using ffprobe
     */
    suspend fun getVideoInfo(videoPath: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val binaryRepo = BinaryRepository(applicationContext)
            val ffprobeBinary = binaryRepo.getFFprobeBinary()
            if (ffprobeBinary == null || !ffprobeBinary.exists()) {
                DebugLog.log("[UPSCALER] ffprobe binary not found")
                return@withContext Result.failure(Exception("ffprobe binary not found"))
            }
            
            DebugLog.log("[UPSCALER] Video path: $videoPath")
            DebugLog.log("[UPSCALER] File exists: ${File(videoPath).exists()}")
            
            val args = listOf(
                ffprobeBinary.absolutePath,
                "-v", "error",  // Only output errors, not warnings
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                videoPath
            )
            
            DebugLog.log("[UPSCALER] Running ffprobe: ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(args)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            // Separate stdout and stderr
            processBuilder.redirectErrorStream(false)
            
            val process = processBuilder.start()
            
            // Read stdout (JSON output)
            val stdout = process.inputStream.bufferedReader().readText()
            // Read stderr (errors)
            val stderr = process.errorStream.bufferedReader().readText()
            
            val exitCode = process.waitFor()
            
            DebugLog.log("[UPSCALER] ffprobe exit code: $exitCode")
            DebugLog.log("[UPSCALER] ffprobe stdout length: ${stdout.length}")
            if (stderr.isNotEmpty()) {
                DebugLog.log("[UPSCALER] ffprobe stderr: $stderr")
            }
            
            if (exitCode != 0 || stdout.isBlank()) {
                val errorMsg = if (stderr.isNotEmpty()) stderr else "ffprobe returned no output"
                DebugLog.log("[UPSCALER] ffprobe failed: $errorMsg")
                return@withContext Result.failure(Exception("ffprobe failed: $errorMsg"))
            }
            
            // Try to parse JSON
            val json = try {
                JSONObject(stdout)
            } catch (e: Exception) {
                DebugLog.log("[UPSCALER] JSON parse error: ${e.message}")
                DebugLog.log("[UPSCALER] Raw output: ${stdout.take(500)}")
                return@withContext Result.failure(Exception("Could not parse video info: ${e.message}"))
            }
            
            val streams = json.getJSONArray("streams")
            val format = json.getJSONObject("format")
            
            var width = 0
            var height = 0
            var fps = 0.0
            
            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                if (stream.getString("codec_type") == "video") {
                    width = stream.getInt("width")
                    height = stream.getInt("height")
                    // Parse fps from "30/1" or "23.976" format
                    val fpsStr = stream.optString("r_frame_rate", "30/1")
                    fps = if (fpsStr.contains("/")) {
                        val parts = fpsStr.split("/")
                        parts[0].toDouble() / parts[1].toDouble()
                    } else {
                        fpsStr.toDoubleOrNull() ?: 30.0
                    }
                    break
                }
            }
            
            val duration = format.optDouble("duration", 0.0)
            val size = format.optLong("size", 0L)
            
            Result.success(VideoInfo(
                width = width,
                height = height,
                fps = fps,
                durationSeconds = duration,
                sizeBytes = size
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Upscale video
     * Workflow: Extract frames → Upscale each frame → Rebuild video with original audio
     */
    suspend fun upscale(config: VideoUpscalerConfig): Result<String> = withContext(Dispatchers.IO) {
        isCancelled = false
        VideoUpscalerStateHolder.reset()
        VideoUpscalerStateHolder.setInputPath(config.inputPath)
        publishProgress("Preparing video upscale", 0f)
        
        val tmpDir = File(cacheDir, "upscaler_tmp").apply { mkdirs() }
        val outDir = File(cacheDir, "upscaler_out").apply { mkdirs() }
        
        try {
            // Step 1: Get video info
            _state.value = VideoUpscalerState.Analyzing
            updateNotification("Analyzing video...", 0)
            publishProgress("Analyzing video", 0.03f)
            
            val videoInfoResult = getVideoInfo(config.inputPath)
            if (videoInfoResult.isFailure) {
                throw videoInfoResult.exceptionOrNull()!!
            }
            val videoInfo = videoInfoResult.getOrThrow()
            
            // Step 2: Extract frames
            _state.value = VideoUpscalerState.ExtractingFrames
            updateNotification("Extracting frames...", 5)
            publishProgress("Extracting frames", 0.08f)
            
            val extractResult = extractFrames(config.inputPath, tmpDir)
            if (extractResult.isFailure) {
                throw extractResult.exceptionOrNull()!!
            }
            
            if (isCancelled) {
                cleanup(tmpDir, outDir)
                return@withContext Result.failure(Exception("Cancelled"))
            }
            
            // Step 3: Count frames
            val frameCount = tmpDir.listFiles()?.count { it.extension == "jpg" } ?: 0
            if (frameCount == 0) {
                throw Exception("No frames extracted")
            }
            
            // Step 4: Upscale frames
            _state.value = VideoUpscalerState.Upscaling(0, frameCount)
            updateNotification("Upscaling 0/$frameCount frames...", 10)
            publishProgress("Upscaling 0/$frameCount frames", 0.1f, currentFrame = 0, totalFrames = frameCount)
            
            val upscaleResult = upscaleFrames(config, tmpDir, outDir, frameCount)
            if (upscaleResult.isFailure) {
                throw upscaleResult.exceptionOrNull()!!
            }
            
            if (isCancelled) {
                cleanup(tmpDir, outDir)
                return@withContext Result.failure(Exception("Cancelled"))
            }
            
            // Step 5: Rebuild video with audio
            _state.value = VideoUpscalerState.Rebuilding
            updateNotification("Rebuilding video with audio...", 95)
            publishProgress("Rebuilding video with audio", 0.95f, totalFrames = frameCount)
            
            val rebuildResult = rebuildVideoWithAudio(
                config.inputPath,
                outDir,
                config.outputPath,
                videoInfo.fps
            )
            if (rebuildResult.isFailure) {
                throw rebuildResult.exceptionOrNull()!!
            }
            
            // Cleanup
            cleanup(tmpDir, outDir)
            
            _state.value = VideoUpscalerState.Completed
            updateNotification("Upscaling complete!", 100)
            publishProgress("Upscaling complete", 1f, resultPath = config.outputPath, processing = false)
            
            Result.success(config.outputPath)
        } catch (e: Exception) {
            cleanup(tmpDir, outDir)
            _state.value = VideoUpscalerState.Error(e.message ?: "Unknown error")
            updateNotification("Error: ${e.message}", 0)
            publishProgress(
                status = e.message ?: "Video upscale failed",
                progress = 0f,
                error = e.message ?: "Unknown error",
                processing = false
            )
            Result.failure(e)
        }
    }
    
    private suspend fun extractFrames(inputPath: String, outputDir: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val binaryRepo = BinaryRepository(applicationContext)
                val ffmpegBinary = binaryRepo.getFFmpegBinary()
                if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                    return@withContext Result.failure(Exception("ffmpeg binary not found"))
                }
                
                val args = listOf(
                    ffmpegBinary.absolutePath,
                    "-y",
                    "-i", inputPath,
                    "-qscale:v", "2",  // JPG quality
                    "-vsync", "0",
                    "${outputDir.absolutePath}/frame%08d.jpg"  // Use JPG instead of PNG (zlib not available)
                )
                
                DebugLog.log("[UPSCALER] Extracting: ${args.joinToString(" ")}")
                
                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
                // Separate stdout/stderr for better debugging
                processBuilder.redirectErrorStream(false)
                
                val process = processBuilder.start()
                
                // Capture output
                process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                
                val exitCode = process.waitFor()
                
                DebugLog.log("[UPSCALER] Frame extraction exit code: $exitCode")
                if (stderr.isNotEmpty()) {
                    DebugLog.log("[UPSCALER] Frame extraction stderr: ${stderr.take(500)}")
                }
                
                // Count extracted frames
                val frameCount = outputDir.listFiles()?.size ?: 0
                DebugLog.log("[UPSCALER] Extracted $frameCount frames")
                
                if (exitCode != 0) {
                    return@withContext Result.failure(Exception("Frame extraction failed: $stderr"))
                }
                
                if (frameCount == 0) {
                    return@withContext Result.failure(Exception("No frames extracted"))
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                DebugLog.log("[UPSCALER] Frame extraction exception: ${e.message}")
                Result.failure(e)
            }
        }
    
    private suspend fun upscaleFrames(
        config: VideoUpscalerConfig, 
        inputDir: File, 
        outputDir: File,
        totalFrames: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val binaryName = config.engine.binaryName.removePrefix("lib").removeSuffix(".so")
            val binary = BinaryRepository(applicationContext).getTieredBinary(binaryName) 
                ?: File(applicationInfo.nativeLibraryDir, config.engine.binaryName)
            
            if (!binary.exists()) {
                return@withContext Result.failure(Exception("${config.engine.name} binary not found"))
            }
            
            // Use the class-level modelsDir (extracted from assets)
            val args = mutableListOf(
                binary.absolutePath,
                "-i", inputDir.absolutePath,
                "-o", outputDir.absolutePath,
                "-m", File(modelsDir, config.model).absolutePath,
                "-s", config.scale.toString(),
                "-f", "jpg"
            )
            
            // Add denoise for RealCUGAN
            if (config.engine == UpscalerEngine.REALCUGAN && config.denoise >= 0) {
                args.addAll(listOf("-n", config.denoise.toString()))
            }
            
            // Add thread configuration (load:proc:save)
            args.addAll(listOf("-j", "${config.loadThreads}:${config.procThreads}:${config.saveThreads}"))
            
            DebugLog.log("[UPSCALER] Upscaling: ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(args)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${BinaryRepository(applicationContext).getLibraryDir()}"
            processBuilder.redirectErrorStream(true)
            
            currentProcess = processBuilder.start()
            
            // Monitor output and progress
            val startTime = System.currentTimeMillis()
            
            // Read output in a separate thread to prevent blocking
            val outputJob = scope.launch(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                var line: String?
                while (currentProcess?.isAlive == true || reader.ready()) {
                    try {
                        line = reader.readLine()
                        if (line != null) {
                            DebugLog.log("[UPSCALER_BIN] $line")
                            // Parse progress if available (realsr-ncnn usually prints percentage)
                            // But we are also counting files, so we can just log it for now
                            
                            // If it's an error message
                            if (line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true)) {
                                DebugLog.log("[UPSCALER_ERR] Potential error: $line")
                            }
                        } else {
                            delay(100)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            // Monitor file progress
            scope.launch {
                while (currentProcess?.isAlive == true && !isCancelled) {
                    val outputCount = outputDir.listFiles()?.count { it.extension == "jpg" } ?: 0
                    val progressPercent = (outputCount * 100 / totalFrames).coerceIn(0, 100)
                    
                    _progress.value = progressPercent / 100f
                    _state.value = VideoUpscalerState.Upscaling(outputCount, totalFrames)
                    
                    // Calculate ETA
                    if (outputCount > 0) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val rate = outputCount.toDouble() / elapsed
                        val remaining = totalFrames - outputCount
                        val etaMs = (remaining / rate).toLong()
                        val etaMins = etaMs / 60000
                        val etaSecs = (etaMs % 60000) / 1000
                        _eta.value = "${etaMins}m ${etaSecs}s"
                        
                        updateNotification(
                            "Upscaling $outputCount/$totalFrames | ETA: ${_eta.value}",
                            10 + (progressPercent * 0.85).toInt()
                        )
                    }
                    val status = if (_eta.value.isBlank()) {
                        "Upscaling $outputCount/$totalFrames frames"
                    } else {
                        "Upscaling $outputCount/$totalFrames | ETA: ${_eta.value}"
                    }
                    publishProgress(
                        status = status,
                        progress = 0.1f + (_progress.value * 0.85f),
                        currentFrame = outputCount,
                        totalFrames = totalFrames
                    )
                    
                    delay(1000)
                }
            }
            
            val exitCode = currentProcess!!.waitFor()
            outputJob.cancelAndJoin()
            currentProcess = null
            
            DebugLog.log("[UPSCALER] Binary exit code: $exitCode")

            if (exitCode != 0 && !isCancelled) {
                return@withContext Result.failure(Exception("Upscaling failed with exit code $exitCode"))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun rebuildVideoWithAudio(
        originalVideo: String,
        framesDir: File,
        outputPath: String,
        fps: Double
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val binaryRepo = BinaryRepository(applicationContext)
            val ffmpegBinary = binaryRepo.getFFmpegBinary()
            if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                return@withContext Result.failure(Exception("ffmpeg binary not found"))
            }
            
            // Rebuild video and copy audio from original
            val args = listOf(
                ffmpegBinary.absolutePath,
                "-y",
                "-r", fps.toString(),
                "-i", "${framesDir.absolutePath}/frame%08d.jpg",
                "-i", originalVideo,
                "-map", "0:v:0", // Video from upscaled frames
                "-map", "1:a:0?", // Audio from original (optional)
                "-c:a", "copy", // Copy audio without re-encoding
                "-c:v", "libx264",
                "-r", fps.toString(),
                "-pix_fmt", "yuv420p",
                outputPath
            )
            
            DebugLog.log("[UPSCALER] Rebuilding: ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(args)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${ffmpegLibDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (reader.readLine() != null) { /* consume */ }
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                return@withContext Result.failure(Exception("Video rebuild failed"))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun cleanup(vararg dirs: File) {
        dirs.forEach { dir ->
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
    
    fun cancel() {
        isCancelled = true
        currentProcess?.destroy()
        currentProcess = null
        _state.value = VideoUpscalerState.Idle
        updateNotification("Upscaling cancelled", 0)
        publishProgress("Upscaling cancelled", 0f, processing = false)
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
    }

    private fun Intent.toUpscalerConfig(): VideoUpscalerConfig? {
        val inputPath = getStringExtra(EXTRA_INPUT_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val outputPath = getStringExtra(EXTRA_OUTPUT_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val engine = getStringExtra(EXTRA_ENGINE)
            ?.let { value -> UpscalerEngine.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
            ?: return null
        val model = getStringExtra(EXTRA_MODEL)?.takeIf { it.isNotBlank() } ?: return null
        return VideoUpscalerConfig(
            inputPath = inputPath,
            outputPath = outputPath,
            engine = engine,
            model = model,
            scale = getIntExtra(EXTRA_SCALE, 2),
            denoise = getIntExtra(EXTRA_DENOISE, -1),
            loadThreads = getIntExtra(EXTRA_LOAD_THREADS, 1),
            procThreads = getIntExtra(EXTRA_PROC_THREADS, 1),
            saveThreads = getIntExtra(EXTRA_SAVE_THREADS, 1)
        )
    }
    
    companion object {
        private const val ACTION_START_UPSCALE = "com.example.llamadroid.action.START_VIDEO_UPSCALE"
        private const val ACTION_CANCEL_UPSCALE = "com.example.llamadroid.action.CANCEL_VIDEO_UPSCALE"
        private const val EXTRA_INPUT_PATH = "extra_video_upscale_input"
        private const val EXTRA_OUTPUT_PATH = "extra_video_upscale_output"
        private const val EXTRA_ENGINE = "extra_video_upscale_engine"
        private const val EXTRA_MODEL = "extra_video_upscale_model"
        private const val EXTRA_SCALE = "extra_video_upscale_scale"
        private const val EXTRA_DENOISE = "extra_video_upscale_denoise"
        private const val EXTRA_LOAD_THREADS = "extra_video_upscale_load_threads"
        private const val EXTRA_PROC_THREADS = "extra_video_upscale_proc_threads"
        private const val EXTRA_SAVE_THREADS = "extra_video_upscale_save_threads"

        fun createStartIntent(context: Context, config: VideoUpscalerConfig): Intent =
            Intent(context, VideoUpscalerService::class.java).apply {
                action = ACTION_START_UPSCALE
                putExtra(EXTRA_INPUT_PATH, config.inputPath)
                putExtra(EXTRA_OUTPUT_PATH, config.outputPath)
                putExtra(EXTRA_ENGINE, config.engine.name)
                putExtra(EXTRA_MODEL, config.model)
                putExtra(EXTRA_SCALE, config.scale)
                putExtra(EXTRA_DENOISE, config.denoise)
                putExtra(EXTRA_LOAD_THREADS, config.loadThreads)
                putExtra(EXTRA_PROC_THREADS, config.procThreads)
                putExtra(EXTRA_SAVE_THREADS, config.saveThreads)
            }

        fun createCancelIntent(context: Context): Intent =
            Intent(context, VideoUpscalerService::class.java).apply {
                action = ACTION_CANCEL_UPSCALE
            }
    }
}

sealed class VideoUpscalerState {
    object Idle : VideoUpscalerState()
    object Analyzing : VideoUpscalerState()
    object ExtractingFrames : VideoUpscalerState()
    data class Upscaling(val current: Int, val total: Int) : VideoUpscalerState()
    object Rebuilding : VideoUpscalerState()
    object Completed : VideoUpscalerState()
    data class Error(val message: String) : VideoUpscalerState()
}

data class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationSeconds: Double,
    val sizeBytes: Long
) {
    val durationFormatted: String
        get() {
            val mins = (durationSeconds / 60).toInt()
            val secs = (durationSeconds % 60).toInt()
            return "${mins}:${secs.toString().padStart(2, '0')}"
        }
    
    val sizeFormatted: String
        get() = com.example.llamadroid.util.FormatUtils.Technical.formatBytes(sizeBytes)
    
    val resolution: String get() = "${width}x${height}"
}
