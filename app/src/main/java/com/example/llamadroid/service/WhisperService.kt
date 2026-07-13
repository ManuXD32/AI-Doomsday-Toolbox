package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service for WhisperCPP audio transcription
 */
class WhisperService : Service() {
    
    private val binder = WhisperBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _state = MutableStateFlow<WhisperState>(WhisperState.Idle)
    val state = _state.asStateFlow()
    
    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()
    
    private var currentProcess: Process? = null
    private var notificationTaskId: Int? = null
    
    inner class WhisperBinder : Binder() {
        fun getService(): WhisperService = this@WhisperService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        setupFFmpegLibrarySymlinks()
    }
    
    /**
     * Create symlinks for versioned library names that FFmpeg expects
     * Android only loads lib*.so files, but FFmpeg was linked against versioned names
     */
    private fun setupFFmpegLibrarySymlinks() {
        val libDir = File(filesDir, "ffmpeg_libs")
        libDir.mkdirs()
        
        // Map of versioned name -> actual library name in jniLibs (with .so suffix)
        val versionedLibs = mapOf(
            "libx264.so.164" to "libx264.so.164.so",
            "libwhisper.so.1" to "libwhisper.so.1.so",
            "libggml.so.0" to "libggml.so.0.so",
            "libggml-base.so.0" to "libggml-base.so.0.so",
            "libggml-cpu.so.0" to "libggml-cpu.so.0.so"
        )
        
        val nativeLibDir = applicationInfo.nativeLibraryDir
        
        versionedLibs.forEach { (versionedName, actualName) ->
            val targetFile = File(nativeLibDir, actualName)
            val linkFile = File(libDir, versionedName)
            
            if (targetFile.exists() && !linkFile.exists()) {
                try {
                    // Create symlink using ln -s
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", targetFile.absolutePath, linkFile.absolutePath)).waitFor()
                    DebugLog.log("[WHISPER] Created symlink: $versionedName -> $actualName")
                } catch (e: Exception) {
                    DebugLog.log("[WHISPER] Failed to create symlink for $versionedName: ${e.message}")
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            "Whisper Transcription"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        WakeLockManager.acquire(applicationContext, "WhisperService")
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        currentProcess?.destroy()
        WakeLockManager.release("WhisperService")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }
    
    private fun updateNotification(text: String, progress: Float = 0f) {
        notificationTaskId?.let { 
            UnifiedNotificationManager.updateProgress(it, progress, text)
        }
    }

    private fun finishForegroundTask() {
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        notificationTaskId = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
    
    /**
     * Transcribe audio file using WhisperCPP
     */
    suspend fun transcribe(config: WhisperConfig): Result<WhisperResult> = withContext(Dispatchers.IO) {
        var wavFile: File? = null
        var process: Process? = null
        try {
            _state.value = WhisperState.Converting
            updateNotification(getString(R.string.whisper_status_converting))
            
            // Step 1: Convert audio to 16-bit WAV using ffmpeg
            wavFile = File(cacheDir, "whisper_input.wav")
            val convertResult = convertAudioToWav(config.audioPath, wavFile.absolutePath)
            if (convertResult.isFailure) {
                _state.value = WhisperState.Error(
                    convertResult.exceptionOrNull()?.message
                        ?: getString(R.string.whisper_error_audio_conversion_failed)
                )
                return@withContext Result.failure(convertResult.exceptionOrNull()!!)
            }
            
            _state.value = WhisperState.Transcribing
            updateNotification(getString(R.string.whisper_status_transcribing))
            
            // Step 2: Run whisper-cli using BinaryRepository
            val binaryRepo = BinaryRepository(applicationContext)
            val whisperBinary = binaryRepo.getWhisperCliBinary()
            if (whisperBinary == null || !whisperBinary.exists()) {
                val error = getString(R.string.whisper_error_binary_not_found)
                _state.value = WhisperState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            val resolvedModelPath = WhisperModelPathResolver.resolve(applicationContext, config.modelPath)
            if (resolvedModelPath == null) {
                val error = getString(R.string.whisper_error_no_model)
                _state.value = WhisperState.Error(error)
                return@withContext Result.failure(Exception(error))
            }
            
            // Build command
            val args = mutableListOf<String>()
            args.add(whisperBinary.absolutePath)
            args.addAll(listOf("-m", resolvedModelPath))
            args.addAll(listOf("-f", wavFile.absolutePath))
            args.addAll(listOf("-l", config.language))
            args.addAll(listOf("-t", config.threads.toString()))
            
            if (config.translate) {
                args.add("-tr")
            }
            
            // Disable GPU to prevent ggml from scanning system directories for GPU backends
            // This fixes the "/init permission denied" crash on Android
            args.add("--no-gpu")
            
            // Add output format flags
            config.outputFormats.forEach { format ->
                args.add(format.cliFlag)
            }
            
            // Output file base name
            val outputBase = config.outputDir?.let { File(it, "whisper_output") } 
                ?: File(cacheDir, "whisper_output")
            args.addAll(listOf("-of", outputBase.absolutePath))
            
            DebugLog.log("[WHISPER] Running: ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(args)
            val libDir = File(filesDir, "ffmpeg_libs")
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            // Set GGML_BACKEND_PATH to /dev/null to completely skip GPU backend loading
            // This prevents std::filesystem from accessing protected paths like /init
            processBuilder.environment()["GGML_BACKEND_PATH"] = "/dev/null"
            // Set HOME and working directory to app's files dir to prevent filesystem access to /init
            processBuilder.environment()["HOME"] = filesDir.absolutePath
            processBuilder.environment()["TMPDIR"] = cacheDir.absolutePath
            processBuilder.directory(filesDir)
            processBuilder.redirectErrorStream(true)
            
            process = processBuilder.start()
            currentProcess = process
            
            // Read output
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                _progress.value = line ?: ""
                DebugLog.log("[WHISPER] ${line ?: ""}")
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val error = getString(R.string.whisper_error_failed_with_exit_code, exitCode)
                _state.value = WhisperState.Error(error)
                return@withContext Result.failure(Exception(error))
            }
            
            // Read output files
            val results = mutableMapOf<WhisperOutputFormat, String>()
            val outputFiles = mutableListOf<File>()
            config.outputFormats.forEach { format ->
                val outputFile = File("${outputBase.absolutePath}.${format.extension}")
                if (outputFile.exists()) {
                    results[format] = outputFile.readText()
                    outputFiles.add(outputFile)
                }
            }
            
            // Copy output files to user's selected folder if set
            val settingsRepo = SettingsRepository(this@WhisperService)
            // Use whisper-specific folder, or fall back to shared output folder
            val outputFolderUri = settingsRepo.whisperOutputFolder.value 
                ?: settingsRepo.outputFolderUri.value
            if (!outputFolderUri.isNullOrEmpty()) {
                try {
                    val treeUri = Uri.parse(outputFolderUri)
                    val rootFolder = DocumentFile.fromTreeUri(this@WhisperService, treeUri)
                    
                    // Create/get transcriptions/ subfolder
                    var transcriptionsDoc = rootFolder?.findFile("transcriptions")
                    if (transcriptionsDoc == null) {
                        transcriptionsDoc = rootFolder?.createDirectory("transcriptions")
                    }
                    
                    if (transcriptionsDoc != null) {
                        val timestamp = System.currentTimeMillis()
                        
                        outputFiles.forEach { sourceFile ->
                            val mimeType = when (sourceFile.extension) {
                                "txt" -> "text/plain"
                                "srt" -> "application/x-subrip"
                                "vtt" -> "text/vtt"
                                "json" -> "application/json"
                                else -> "text/plain"
                            }
                            val destName = "whisper_${timestamp}.${sourceFile.extension}"
                            val newFile = transcriptionsDoc.createFile(mimeType, destName)
                            newFile?.uri?.let { destUri ->
                                contentResolver.openOutputStream(destUri)?.use { output ->
                                    sourceFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                                DebugLog.log("[WHISPER] Copied ${sourceFile.name} to transcriptions/")
                            }
                        }
                    } else {
                        DebugLog.log("[WHISPER] Failed to create transcriptions folder")
                    }
                } catch (e: Exception) {
                    DebugLog.log("[WHISPER] Failed to copy to output folder: ${e.message}")
                }
            } else {
                DebugLog.log("[WHISPER] No output folder configured, files remain in cache")
            }
            
            // Clean up temp files
            _state.value = WhisperState.Completed
            updateNotification(getString(R.string.whisper_status_complete))
            
            // Get result text - use TXT if available, otherwise use first available format
            val resultText = results[WhisperOutputFormat.TXT] 
                ?: results.values.firstOrNull() 
                ?: output.toString()
            
            // Auto-save to Notes database - use NonCancellable to ensure this completes
            // even if the calling scope is cancelled (e.g., user navigating away)
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val db = AppDatabase.getDatabase(this@WhisperService)
                    val noteTitle = "Transcription: ${config.audioPath.substringAfterLast("/").substringBeforeLast(".")}"
                    db.noteDao().insert(
                        NoteEntity(
                            title = noteTitle,
                            content = resultText,
                            type = NoteType.TRANSCRIPTION,
                            sourceFile = config.audioPath,
                            language = extractDetectedLanguage(output.toString()),
                            audioPath = config.audioPath  // Link to audio for playback
                        )
                    )
                    DebugLog.log("[WHISPER] Transcription saved to Notes with audio: ${config.audioPath}")
                } catch (e: Exception) {
                    DebugLog.log("[WHISPER] Failed to save note: ${e.message}")
                }
            }
            
            Result.success(WhisperResult(
                text = resultText,
                outputs = results,
                detectedLanguage = extractDetectedLanguage(output.toString())
            ))
        } catch (e: Exception) {
            _state.value = WhisperState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        } finally {
            runCatching {
                if (process?.isAlive == true) {
                    process.destroy()
                }
            }
            if (currentProcess === process) {
                currentProcess = null
            }
            wavFile?.takeIf { it.exists() }?.delete()
            finishForegroundTask()
        }
    }
    
    private suspend fun convertAudioToWav(inputPath: String, outputPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val binaryRepo = BinaryRepository(applicationContext)
            val ffmpegBinary = binaryRepo.getFFmpegBinary()
            if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                val error = getString(R.string.whisper_error_ffmpeg_not_found)
                DebugLog.log("[WHISPER] $error")
                return@withContext Result.failure(Exception(error))
            }
            
            DebugLog.log("[WHISPER] Input file: $inputPath")
            DebugLog.log("[WHISPER] Input file exists: ${File(inputPath).exists()}")
            DebugLog.log("[WHISPER] Input file size: ${File(inputPath).length()}")
            
            val args = listOf(
                ffmpegBinary.absolutePath,
                "-y", // Overwrite output
                "-i", inputPath,
                "-ar", "16000", // 16kHz sample rate
                "-ac", "1", // Mono
                "-c:a", "pcm_s16le", // 16-bit PCM
                outputPath
            )
            
            DebugLog.log("[WHISPER] Converting audio: ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(args)
            val libDir = File(filesDir, "ffmpeg_libs")
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            // Separate stdout and stderr for better error capture
            processBuilder.redirectErrorStream(false)
            
            val process = processBuilder.start()
            
            // ffmpeg outputs to stderr, not stdout
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            
            val exitCode = process.waitFor()
            
            DebugLog.log("[WHISPER] ffmpeg exit code: $exitCode")
            if (stdout.isNotEmpty()) {
                DebugLog.log("[WHISPER] ffmpeg stdout: ${stdout.take(500)}")
            }
            if (stderr.isNotEmpty()) {
                // Log last few lines of stderr (most important)
                val lastLines = stderr.lines().takeLast(10).joinToString("\n")
                DebugLog.log("[WHISPER] ffmpeg stderr: $lastLines")
            }
            
            if (exitCode != 0) {
                val errorLines = stderr.lines().filter { 
                    it.contains("Error", ignoreCase = true) || 
                    it.contains("Invalid", ignoreCase = true) ||
                    it.contains("No such", ignoreCase = true)
                }.joinToString("; ")
                val errorMsg = errorLines.ifEmpty {
                    getString(R.string.whisper_error_ffmpeg_exit_code, exitCode)
                }
                DebugLog.log("[WHISPER] ffmpeg conversion failed: $errorMsg")
                return@withContext Result.failure(
                    Exception(getString(R.string.whisper_error_audio_conversion_detail, errorMsg))
                )
            }
            
            val outputFile = File(outputPath)
            DebugLog.log("[WHISPER] Output file exists: ${outputFile.exists()}, size: ${outputFile.length()}")
            
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("[WHISPER] Exception during conversion: ${e.message}")
            Result.failure(e)
        }
    }
    
    private fun extractDetectedLanguage(output: String): String? {
        // Parse "auto-detected language: xx" from whisper output
        val regex = Regex("auto-detected language:\\s*(\\w+)")
        return regex.find(output)?.groupValues?.getOrNull(1)
    }
    
    fun cancel() {
        currentProcess?.destroy()
        currentProcess = null
        _state.value = WhisperState.Idle
        updateNotification(getString(R.string.whisper_status_cancelled))
    }
    
    companion object {
        // Notification handled by UnifiedNotificationManager
    }
}

sealed class WhisperState {
    object Idle : WhisperState()
    object Converting : WhisperState()
    object Transcribing : WhisperState()
    object Completed : WhisperState()
    data class Error(val message: String) : WhisperState()
}

data class WhisperResult(
    val text: String,
    val outputs: Map<WhisperOutputFormat, String>,
    val detectedLanguage: String? = null
)
