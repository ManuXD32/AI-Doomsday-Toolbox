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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    
    private val transcriptionMutex = Mutex()
    private var currentProcess: Process? = null
    @Volatile private var cancellationRequested = false
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
        cancellationRequested = true
        currentProcess?.destroyForcibly()
        currentProcess = null
        scope.cancel()
        WakeLockManager.release("WhisperService")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        super.onDestroy()
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
    suspend fun transcribe(config: WhisperConfig): Result<WhisperResult> =
        transcriptionMutex.withLock {
            withContext(Dispatchers.IO) {
                val runId = "${System.currentTimeMillis()}_${System.nanoTime()}"
                val wavFile = File(cacheDir, "whisper_input_$runId.wav")
                val generatedOutputFiles = mutableListOf<File>()
                var process: Process? = null
                cancellationRequested = false
                try {
                    _progress.value = ""
                    _state.value = WhisperState.Converting
                    updateNotification(getString(R.string.whisper_status_converting))

                    val convertResult = convertAudioToWav(config.audioPath, wavFile.absolutePath)
                    if (convertResult.isFailure) {
                        val error = convertResult.exceptionOrNull()
                            ?: IllegalStateException(
                                getString(R.string.whisper_error_audio_conversion_failed)
                            )
                        if (cancellationRequested || error is CancellationException) {
                            throw CancellationException("Whisper transcription cancelled")
                        }
                        throw error
                    }
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }

                    _state.value = WhisperState.Transcribing
                    updateNotification(getString(R.string.whisper_status_transcribing))

                    val binaryRepo = BinaryRepository(applicationContext)
                    val whisperBinary = binaryRepo.getWhisperCliBinary()
                    if (whisperBinary == null || !whisperBinary.exists()) {
                        throw IllegalStateException(
                            getString(R.string.whisper_error_binary_not_found)
                        )
                    }
                    val resolvedModelPath = WhisperModelPathResolver.resolve(
                        applicationContext,
                        config.modelPath
                    ) ?: throw IllegalStateException(getString(R.string.whisper_error_no_model))

                    val settingsRepo = SettingsRepository(this@WhisperService)
                    val requestedVad = config.vad ?: settingsRepo.whisperVadConfigSnapshot()
                    val effectiveVad = try {
                        WhisperVadAssetStore.effectiveConfig(
                            context = this@WhisperService,
                            config = requestedVad,
                            purpose = config.purpose
                        )
                    } catch (error: WhisperVadUnavailableException) {
                        throw IllegalStateException(
                            getString(R.string.whisper_error_vad_model_missing),
                            error
                        )
                    }

                    val outputBase = config.outputDir
                        ?.let { directoryPath ->
                            val directory = File(directoryPath).apply { mkdirs() }
                            File(directory, "whisper_output_$runId")
                        }
                        ?: File(cacheDir, "whisper_output_$runId")
                    outputBase.parentFile?.mkdirs()

                    val libDir = File(filesDir, "ffmpeg_libs")
                    val environment = mapOf(
                        "LD_LIBRARY_PATH" to
                            "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}",
                        "GGML_BACKEND_PATH" to "/dev/null",
                        "HOME" to filesDir.absolutePath,
                        "TMPDIR" to cacheDir.absolutePath
                    )
                    val capabilities = if (effectiveVad.enabled) {
                        WhisperBinaryCapabilityCache.capabilitiesFor(
                            binary = whisperBinary,
                            workingDirectory = filesDir,
                            environment = environment
                        )
                    } else {
                        null
                    }
                    val args = try {
                        buildWhisperInvocationArgs(
                            request = WhisperInvocationRequest(
                                binaryPath = whisperBinary.absolutePath,
                                modelPath = resolvedModelPath,
                                audioPath = wavFile.absolutePath,
                                language = config.language,
                                threads = config.threads,
                                translate = config.translate,
                                outputFormats = config.outputFormats,
                                outputBasePath = outputBase.absolutePath,
                                purpose = config.purpose,
                                vad = effectiveVad
                            ),
                            binaryCapabilities = capabilities
                        )
                    } catch (error: WhisperUnsupportedFlagsException) {
                        throw IllegalStateException(
                            getString(
                                R.string.whisper_error_vad_unsupported,
                                error.flags.joinToString(", ")
                            ),
                            error
                        )
                    }
                    DebugLog.log("[WHISPER] Running: ${args.joinToString(" ")}")

                    val processBuilder = ProcessBuilder(args)
                        .directory(filesDir)
                        .redirectErrorStream(true)
                    processBuilder.environment().putAll(environment)
                    process = processBuilder.start()
                    currentProcess = process

                    val output = StringBuilder()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.appendLine(line)
                            _progress.value = line
                            DebugLog.log("[WHISPER] $line")
                        }
                    }
                    val exitCode = process.waitFor()
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }
                    if (exitCode != 0) {
                        throw IllegalStateException(
                            getString(R.string.whisper_error_failed_with_exit_code, exitCode)
                        )
                    }

                    val normalizedFormats = config.outputFormats.ifEmpty {
                        setOf(WhisperOutputFormat.TXT)
                    }
                    val results = linkedMapOf<WhisperOutputFormat, String>()
                    normalizedFormats.sortedBy { it.ordinal }.forEach { format ->
                        val outputFile = File("${outputBase.absolutePath}.${format.extension}")
                        if (outputFile.isFile) {
                            generatedOutputFiles += outputFile
                            results[format] = outputFile.readText()
                        }
                    }
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }
                    if (results.isEmpty()) {
                        throw IllegalStateException(
                            getString(R.string.whisper_error_no_output)
                        )
                    }

                    val outputFolderUri = settingsRepo.whisperOutputFolder.value
                        ?: settingsRepo.outputFolderUri.value
                    if (!outputFolderUri.isNullOrBlank()) {
                        runCatching {
                            val rootFolder = DocumentFile.fromTreeUri(
                                this@WhisperService,
                                Uri.parse(outputFolderUri)
                            )
                            val transcriptionsFolder = rootFolder?.findFile("transcriptions")
                                ?: rootFolder?.createDirectory("transcriptions")
                            if (transcriptionsFolder != null) {
                                val timestamp = System.currentTimeMillis()
                                generatedOutputFiles.forEach { sourceFile ->
                                    if (cancellationRequested) {
                                        throw CancellationException(
                                            "Whisper transcription cancelled"
                                        )
                                    }
                                    val mimeType = when (sourceFile.extension.lowercase()) {
                                        "txt" -> "text/plain"
                                        "srt" -> "application/x-subrip"
                                        "vtt" -> "text/vtt"
                                        "json" -> "application/json"
                                        else -> "text/plain"
                                    }
                                    transcriptionsFolder
                                        .createFile(
                                            mimeType,
                                            "whisper_${timestamp}.${sourceFile.extension}"
                                        )
                                        ?.uri
                                        ?.let { destination ->
                                            contentResolver.openOutputStream(destination)?.use {
                                                outputStream ->
                                                sourceFile.inputStream().use { input ->
                                                    input.copyTo(outputStream)
                                                }
                                            }
                                        }
                                }
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            DebugLog.log(
                                "[WHISPER] Failed to copy output files: ${error.message}"
                            )
                        }
                    }
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }

                    val outputText = output.toString()
                    val resultText = results[WhisperOutputFormat.TXT]
                        ?: results.values.firstOrNull()
                        ?: outputText
                    val detectedLanguage = extractDetectedLanguage(outputText)

                    // Preserve existing behavior, but never persist a completed note after cancellation.
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        val db = AppDatabase.getDatabase(this@WhisperService)
                        val sourceName = config.audioPath
                            .substringAfterLast('/')
                            .substringBeforeLast('.')
                        db.noteDao().insert(
                            NoteEntity(
                                title = "Transcription: $sourceName",
                                content = resultText,
                                type = NoteType.TRANSCRIPTION,
                                sourceFile = config.audioPath,
                                language = detectedLanguage,
                                audioPath = config.audioPath
                            )
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLog.log("[WHISPER] Failed to save note: ${error.message}")
                    }
                    if (cancellationRequested) {
                        throw CancellationException("Whisper transcription cancelled")
                    }

                    _state.value = WhisperState.Completed
                    _progress.value = getString(R.string.whisper_status_complete)
                    updateNotification(getString(R.string.whisper_status_complete), 1f)
                    Result.success(
                        WhisperResult(
                            text = resultText,
                            outputs = results,
                            detectedLanguage = detectedLanguage
                        )
                    )
                } catch (cancelled: CancellationException) {
                    _state.value = WhisperState.Cancelled
                    _progress.value = getString(R.string.whisper_status_cancelled)
                    Result.failure(cancelled)
                } catch (error: Exception) {
                    val message = error.message ?: getString(R.string.error_generic)
                    _state.value = WhisperState.Error(message)
                    _progress.value = message
                    Result.failure(error)
                } finally {
                    if (process?.isAlive == true) process.destroyForcibly()
                    if (currentProcess === process) currentProcess = null
                    if (wavFile.exists()) wavFile.delete()
                    generatedOutputFiles
                        .filter { file ->
                            file.absolutePath.startsWith(cacheDir.absolutePath)
                        }
                        .forEach { it.delete() }
                    finishForegroundTask()
                }
            }
        }
    
    private suspend fun convertAudioToWav(
        inputPath: String,
        outputPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            if (cancellationRequested) {
                throw CancellationException("Whisper transcription cancelled")
            }
            val binaryRepo = BinaryRepository(applicationContext)
            val ffmpegBinary = binaryRepo.getFFmpegBinary()
            if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                val error = getString(R.string.whisper_error_ffmpeg_not_found)
                DebugLog.log("[WHISPER] $error")
                return@withContext Result.failure(Exception(error))
            }

            val inputFile = File(inputPath)
            if (!inputFile.isFile || !inputFile.canRead()) {
                return@withContext Result.failure(
                    IllegalArgumentException(getString(R.string.whisper_error_no_audio))
                )
            }

            val args = listOf(
                ffmpegBinary.absolutePath,
                "-y",
                "-i", inputPath,
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                outputPath
            )
            DebugLog.log("[WHISPER] Converting audio: ${args.joinToString(" ")}")

            val processBuilder = ProcessBuilder(args)
            val libDir = File(filesDir, "ffmpeg_libs")
            processBuilder.environment()["LD_LIBRARY_PATH"] =
                "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}"
            processBuilder.redirectErrorStream(true)

            process = processBuilder.start()
            currentProcess = process
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (cancellationRequested) {
                throw CancellationException("Whisper transcription cancelled")
            }
            if (exitCode != 0) {
                val diagnostic = output
                    .lineSequence()
                    .filter { line ->
                        line.contains("error", ignoreCase = true) ||
                            line.contains("invalid", ignoreCase = true) ||
                            line.contains("no such", ignoreCase = true)
                    }
                    .toList()
                    .takeLast(4)
                    .joinToString("; ")
                    .ifBlank { getString(R.string.whisper_error_ffmpeg_exit_code, exitCode) }
                return@withContext Result.failure(
                    Exception(
                        getString(
                            R.string.whisper_error_audio_conversion_detail,
                            diagnostic
                        )
                    )
                )
            }

            val outputFile = File(outputPath)
            if (!outputFile.isFile || outputFile.length() <= 44L) {
                return@withContext Result.failure(
                    Exception(getString(R.string.whisper_error_audio_conversion_failed))
                )
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            Result.failure(cancelled)
        } catch (error: Exception) {
            DebugLog.log("[WHISPER] Audio conversion failed: ${error.message}")
            Result.failure(error)
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            if (currentProcess === process) currentProcess = null
        }
    }
    
    private fun extractDetectedLanguage(output: String): String? {
        // Parse "auto-detected language: xx" from whisper output
        val regex = Regex("auto-detected language:\\s*(\\w+)")
        return regex.find(output)?.groupValues?.getOrNull(1)
    }
    
    fun cancel() {
        cancellationRequested = true
        currentProcess?.destroy()
        if (currentProcess?.isAlive == true) currentProcess?.destroyForcibly()
        currentProcess = null
        _state.value = WhisperState.Cancelled
        _progress.value = getString(R.string.whisper_status_cancelled)
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
    object Cancelled : WhisperState()
    data class Error(val message: String) : WhisperState()
}

data class WhisperResult(
    val text: String,
    val outputs: Map<WhisperOutputFormat, String>,
    val detectedLanguage: String? = null
)
