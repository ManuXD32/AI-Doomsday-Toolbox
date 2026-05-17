package com.example.llamadroid.service

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import com.example.llamadroid.R

/**
 * Service to summarize video content using:
 * 1. FFmpeg to extract audio
 * 2. Whisper to transcribe
 * 3. LLM to summarize transcript
 */
object VideoSumupService {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _state = MutableStateFlow<VideoSumupState>(VideoSumupState.Idle)
    val state: StateFlow<VideoSumupState> = _state
    
    private val _result = MutableStateFlow<Result<VideoSumupResult>?>(null)
    val result: StateFlow<Result<VideoSumupResult>?> = _result
    
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress
    
    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var currentRemoteClient: RemoteSummaryClient? = null
    private var isCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationTaskId: Int? = null

    private fun stageProgressFraction(stage: VideoSumupState): Float {
        return when (stage) {
            VideoSumupState.Idle -> 0f
            VideoSumupState.ExtractingAudio -> 0.15f
            VideoSumupState.Transcribing -> 0.55f
            VideoSumupState.Summarizing -> 0.85f
            is VideoSumupState.Error -> 0f
        }
    }

    private fun summaryProgressFraction(progress: RemoteSummaryProgress): Float {
        return when (progress.phase) {
            RemoteSummaryPhase.SINGLE_SUMMARY -> 0.95f
            RemoteSummaryPhase.CHUNK_SUMMARY -> {
                if (progress.totalChunks <= 0) 0.9f
                else 0.85f + (progress.currentChunk.coerceAtLeast(1).toFloat() / progress.totalChunks.toFloat()) * 0.1f
            }
            RemoteSummaryPhase.MERGE_SUMMARY -> {
                if (progress.mergeBatchCount <= 0) 0.97f
                else 0.95f + (progress.mergeBatch.coerceAtLeast(1).toFloat() / progress.mergeBatchCount.toFloat()) * 0.04f
            }
        }.coerceIn(0f, 1f)
    }

    private fun updateToolPhase(
        noteType: NoteType,
        label: String,
        progressFraction: Float
    ) {
        if (noteType == NoteType.WORKFLOW) {
            WorkflowStateHolder.setStep(label)
            WorkflowStateHolder.setProgress(progressFraction.coerceIn(0f, 1f))
        } else {
            VideoSummaryStateHolder.setProgress(label)
            VideoSummaryStateHolder.setProgressFraction(progressFraction)
        }
    }

    private fun summaryProgressLabel(context: Context, progress: RemoteSummaryProgress): String {
        return when (progress.phase) {
            RemoteSummaryPhase.SINGLE_SUMMARY -> context.getString(R.string.summary_progress_single)
            RemoteSummaryPhase.CHUNK_SUMMARY -> context.getString(
                R.string.summary_progress_chunk,
                progress.currentChunk,
                progress.totalChunks
            )
            RemoteSummaryPhase.MERGE_SUMMARY -> {
                if (progress.mergeBatch > 0 && progress.mergeBatchCount > 0) {
                    context.getString(
                        R.string.summary_progress_merge_batch,
                        progress.mergeBatch,
                        progress.mergeBatchCount
                    )
                } else {
                    context.getString(R.string.summary_progress_merge)
                }
            }
        }
    }
    
    fun startSummarization(
        context: Context,
        videoPath: String,
        videoFileName: String,
        whisperModelPath: String,
        language: String = "auto",
        threads: Int = 4,
        saveToNotes: Boolean = true,
        noteType: NoteType = NoteType.VIDEO_SUMMARY,  // WORKFLOW for workflow calls
        audioSourcePath: String? = null  // Original audio path for workflow notes
    ) {
        currentJob?.cancel()
        _result.value = null
        isCancelled = false
        if (noteType == NoteType.WORKFLOW) {
            WorkflowStateHolder.setCancelled(false)
            WorkflowStateHolder.setError(null)
            WorkflowStateHolder.setSummaryText("")
            WorkflowStateHolder.setPartialSummaries(emptyList())
            WorkflowStateHolder.setCurrentChunk(0)
            WorkflowStateHolder.setTotalChunks(0)
            WorkflowStateHolder.setProjectedChunkCount(0)
            WorkflowStateHolder.setStep(context.getString(R.string.workflow_step_starting))
            WorkflowStateHolder.setProgress(0.05f)
            WorkflowStateHolder.setIsRunning(true)
        } else {
            VideoSummaryStateHolder.setCancelled(false)
            VideoSummaryStateHolder.setError(null)
            VideoSummaryStateHolder.setSummary("")
            VideoSummaryStateHolder.setTranscript("")
            VideoSummaryStateHolder.setPartialSummaries(emptyList())
            VideoSummaryStateHolder.setCurrentChunk(0)
            VideoSummaryStateHolder.setTotalChunks(0)
            VideoSummaryStateHolder.setSelectedSourceName(videoFileName)
            VideoSummaryStateHolder.setProgress(context.getString(R.string.workflow_step_starting))
            VideoSummaryStateHolder.setProgressFraction(0.05f)
            VideoSummaryStateHolder.setProjectedChunkCount(0)
            VideoSummaryStateHolder.setIsRunning(true)
        }
        
        // Start notification for workflow
        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            context.getString(R.string.video_sumup_notification_title, videoFileName)
        )
        RemoteSummaryProtection.acquire(context)
        
        // Acquire wake lock
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:VideoSumup")
            wakeLock?.acquire(60 * 60 * 1000L)
            DebugLog.log("[VIDEO-SUMUP] Wake lock acquired")
        } catch (e: Exception) {
            DebugLog.log("[VIDEO-SUMUP] Failed to acquire wake lock: ${e.message}")
        }
        
        currentJob = serviceScope.launch {
            try {
                val result = summarizeVideo(context, videoPath, videoFileName, whisperModelPath, language, threads, saveToNotes, noteType, audioSourcePath)
                _result.value = result
                
                // Complete notification on success
                result.fold(
                    onSuccess = {
                        notificationTaskId?.let { id ->
                            UnifiedNotificationManager.completeTask(id, context.getString(R.string.video_sumup_notification_complete))
                        }
                    },
                    onFailure = { e ->
                        notificationTaskId?.let { id ->
                            UnifiedNotificationManager.failTask(id, e.message ?: context.getString(R.string.error_generic))
                        }
                    }
                )
            } catch (e: Exception) {
                notificationTaskId?.let { id ->
                    UnifiedNotificationManager.failTask(id, e.message ?: context.getString(R.string.error_generic))
                }
            } finally {
                currentRemoteClient = null
                releaseWakeLock()
                RemoteSummaryProtection.release()
                notificationTaskId = null
            }
        }
    }
    
    private suspend fun summarizeVideo(
        context: Context,
        videoPath: String,
        videoFileName: String,
        whisperModelPath: String,
        language: String,
        threads: Int,
        saveToNotes: Boolean,
        noteType: NoteType,
        audioSourcePath: String?
    ): Result<VideoSumupResult> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Extract audio
            _state.value = VideoSumupState.ExtractingAudio
            _progress.value = context.getString(R.string.video_sumup_extracting_audio)
            updateToolPhase(
                noteType = noteType,
                label = context.getString(R.string.video_sumup_extracting_audio),
                progressFraction = stageProgressFraction(VideoSumupState.ExtractingAudio)
            )
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.1f, context.getString(R.string.video_sumup_extracting_audio)) }
            DebugLog.log("[VIDEO-SUMUP] Step 1: Extracting audio")
            
            val audioFile = File(context.cacheDir, "video_audio.wav")
            val extractResult = extractAudio(context, videoPath, audioFile.absolutePath)
            if (extractResult.isFailure) {
                _state.value = VideoSumupState.Error(extractResult.exceptionOrNull()?.message ?: "Extract failed")
                return@withContext Result.failure(extractResult.exceptionOrNull()!!)
            }
            if (isCancelled) return@withContext Result.failure(Exception("Cancelled"))
            
            // Step 2: Transcribe
            _state.value = VideoSumupState.Transcribing
            _progress.value = context.getString(R.string.video_sumup_transcribing_whisper)
            updateToolPhase(
                noteType = noteType,
                label = context.getString(R.string.video_sumup_transcribing_whisper),
                progressFraction = stageProgressFraction(VideoSumupState.Transcribing)
            )
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.5f, context.getString(R.string.video_sumup_transcribing)) }
            DebugLog.log("[VIDEO-SUMUP] Step 2: Transcribing")
            
            val transcriptResult = transcribe(context, audioFile.absolutePath, whisperModelPath, language, threads)
            if (transcriptResult.isFailure) {
                _state.value = VideoSumupState.Error(transcriptResult.exceptionOrNull()?.message ?: "Transcribe failed")
                return@withContext Result.failure(transcriptResult.exceptionOrNull()!!)
            }
            val transcript = transcriptResult.getOrThrow()
            if (noteType == NoteType.WORKFLOW) {
                WorkflowStateHolder.setTranscriptionText(transcript)
            } else {
                VideoSummaryStateHolder.setTranscript(transcript)
            }
            if (isCancelled) return@withContext Result.failure(Exception("Cancelled"))
            
            // Step 3: Summarize
            _state.value = VideoSumupState.Summarizing
            _progress.value = context.getString(R.string.video_sumup_summarizing_ai)
            updateToolPhase(
                noteType = noteType,
                label = context.getString(R.string.video_sumup_summarizing_ai),
                progressFraction = stageProgressFraction(VideoSumupState.Summarizing)
            )
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.9f, context.getString(R.string.video_sumup_summarizing)) }
            DebugLog.log("[VIDEO-SUMUP] Step 3: Summarizing (${transcript.length} chars)")
            
            val summaryResult = summarizeRemotely(
                context = context,
                transcript = transcript,
                noteType = noteType
            )
            if (summaryResult.isFailure) {
                _state.value = VideoSumupState.Error(summaryResult.exceptionOrNull()?.message ?: "Summary failed")
                return@withContext Result.failure(summaryResult.exceptionOrNull()!!)
            }
            val summary = summaryResult.getOrThrow()
            if (noteType == NoteType.WORKFLOW) {
                WorkflowStateHolder.setSummaryText(summary)
                WorkflowStateHolder.setIsRunning(false)
                WorkflowStateHolder.setProgress(1f)
                WorkflowStateHolder.setStep(context.getString(R.string.video_sumup_complete))
            } else {
                VideoSummaryStateHolder.setSummary(summary)
                VideoSummaryStateHolder.setIsRunning(false)
                VideoSummaryStateHolder.setProgress(context.getString(R.string.video_sumup_complete))
                VideoSummaryStateHolder.setProgressFraction(1f)
            }
            
            // Cleanup and save
            audioFile.delete()
            
            // Save to notes - always save, use noteType parameter (VIDEO_SUMMARY or WORKFLOW)
            if (saveToNotes) {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val settingsRepo = SettingsRepository(context)
                    
                    // Copy audio to permanent Recordings folder
                    var permanentAudioPath: String? = null
                    val sourceAudio = audioSourcePath ?: videoPath
                    if (sourceAudio.isNotEmpty() && java.io.File(sourceAudio).exists()) {
                        try {
                            val recordingsDir = java.io.File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            val extension = sourceAudio.substringAfterLast(".", "m4a")
                            val savedFile = java.io.File(recordingsDir, "audio_${timestamp}.$extension")
                            java.io.File(sourceAudio).copyTo(savedFile, overwrite = true)
                            permanentAudioPath = savedFile.absolutePath
                            DebugLog.log("[VIDEO-SUMUP] Audio saved to app storage: $permanentAudioPath")
                            
                            // Also copy to user's SAF output folder if configured
                            val outputFolderUri = settingsRepo.outputFolderUri.value
                            if (!outputFolderUri.isNullOrEmpty()) {
                                try {
                                    val treeUri = android.net.Uri.parse(outputFolderUri)
                                    val rootFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                                    
                                    // Create/get Recordings subfolder
                                    var recordingsDoc = rootFolder?.findFile("Recordings")
                                    if (recordingsDoc == null) {
                                        recordingsDoc = rootFolder?.createDirectory("Recordings")
                                    }
                                    
                                    if (recordingsDoc != null) {
                                        val mimeType = when (extension) {
                                            "mp3" -> "audio/mpeg"
                                            "m4a" -> "audio/mp4"
                                            "wav" -> "audio/wav"
                                            "ogg" -> "audio/ogg"
                                            else -> "audio/*"
                                        }
                                        val destFile = recordingsDoc.createFile(mimeType, "audio_${timestamp}.$extension")
                                        destFile?.uri?.let { destUri ->
                                            context.contentResolver.openOutputStream(destUri)?.use { output ->
                                                savedFile.inputStream().use { input ->
                                                    input.copyTo(output)
                                                }
                                            }
                                            DebugLog.log("[VIDEO-SUMUP] Audio copied to output folder: ${destFile.name}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    DebugLog.log("[VIDEO-SUMUP] Failed to copy to output folder: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            DebugLog.log("[VIDEO-SUMUP] Failed to save audio: ${e.message}")
                        }
                    }
                    
                    db.noteDao().insert(NoteEntity(
                        title = if (noteType == NoteType.WORKFLOW) {
                            context.getString(R.string.workflow_note_title, videoFileName)
                        } else {
                            context.getString(R.string.video_sumup_note_title, videoFileName)
                        },
                        content = context.getString(
                            R.string.summary_note_content,
                            context.getString(R.string.summary_section_title),
                            summary,
                            context.getString(R.string.transcript_section_title),
                            transcript
                        ),
                        type = noteType,
                        sourceFile = videoFileName,
                        language = language,
                        audioPath = permanentAudioPath
                    ))
                    DebugLog.log("[VIDEO-SUMUP] Saved to Notes with audio: $permanentAudioPath")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.video_sumup_summary_saved), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    DebugLog.log("[VIDEO-SUMUP] Save failed: ${e.message}")
                }
            }
            
            _state.value = VideoSumupState.Idle
            _progress.value = context.getString(R.string.video_sumup_complete)
            Result.success(VideoSumupResult(transcript, summary))
        } catch (e: Exception) {
            DebugLog.log("[VIDEO-SUMUP] Error: ${e.message}")
            _state.value = VideoSumupState.Error(e.message ?: "Error")
            if (noteType == NoteType.WORKFLOW) {
                WorkflowStateHolder.setIsRunning(false)
                WorkflowStateHolder.setError(e.message)
                WorkflowStateHolder.setProgress(0f)
            } else {
                VideoSummaryStateHolder.setIsRunning(false)
                VideoSummaryStateHolder.setError(e.message)
                VideoSummaryStateHolder.setProgressFraction(0f)
            }
            Result.failure(e)
        }
    }
    
    private fun extractAudio(context: Context, videoPath: String, outputPath: String): Result<Unit> {
        return try {
            val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
            val ffmpeg = binaryRepo.getFFmpegBinary()
            if (ffmpeg == null || !ffmpeg.exists()) return Result.failure(Exception("FFmpeg not found"))
            
            val args = listOf(ffmpeg.absolutePath, "-y", "-i", videoPath, "-vn", 
                "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", outputPath)
            DebugLog.log("[VIDEO-SUMUP] FFmpeg: ${args.joinToString(" ")}")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val symlinkDir = File(context.cacheDir, "ffmpeg_libs")
            symlinkDir.mkdirs()
            pb.environment()["LD_LIBRARY_PATH"] = "${binaryRepo.getLibraryDir()}:${symlinkDir.absolutePath}"
            
            val process = pb.start()
            currentProcess = process
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) Result.failure(Exception("FFmpeg exit $exitCode"))
            else Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private fun transcribe(context: Context, audioPath: String, modelPath: String, language: String, threads: Int): Result<String> {
        return try {
            val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
            val whisper = binaryRepo.getWhisperCliBinary()
            if (whisper == null || !whisper.exists()) return Result.failure(Exception("Whisper not found"))
            
            val outputBase = File(context.cacheDir, "whisper_sumup")
            val args = listOf(whisper.absolutePath, "-m", modelPath, "-f", audioPath, 
                "-l", language, "-t", threads.toString(), "--no-gpu", "-otxt", "-of", outputBase.absolutePath)
            DebugLog.log("[VIDEO-SUMUP] Whisper: ${args.joinToString(" ")}")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val symlinkDir = File(context.cacheDir, "ffmpeg_libs")
            pb.environment()["LD_LIBRARY_PATH"] = "${binaryRepo.getLibraryDir()}:${symlinkDir.absolutePath}"
            
            val process = pb.start()
            currentProcess = process
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) return Result.failure(Exception("Whisper exit $exitCode"))
            
            val transcriptFile = File("${outputBase.absolutePath}.txt")
            if (!transcriptFile.exists()) return Result.failure(Exception("No transcript"))
            
            val transcript = transcriptFile.readText().trim()
            transcriptFile.delete()
            Result.success(transcript)
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private suspend fun summarizeRemotely(
        context: Context,
        transcript: String,
        noteType: NoteType
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val settingsRepo = SettingsRepository(context)
            val snapshot = if (noteType == NoteType.WORKFLOW) {
                settingsRepo.workflowSummarySettings.snapshot()
            } else {
                settingsRepo.videoSummarySettings.snapshot()
            }
            val client = RemoteSummaryClientFactory.fromSnapshot(snapshot)
            currentRemoteClient = client
            val orchestrator = RemoteSummaryOrchestrator(client)

            val selectedModel = when (SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)) {
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapModel
                SettingsRepository.PDF_BACKEND_OLLAMA -> snapshot.ollamaModel
                else -> snapshot.llamaServerModelLabel
            }
            if (SettingsRepository.requiresSelectedRemoteModel(snapshot.backend) && selectedModel.isNullOrBlank()) {
                val message = if (SettingsRepository.isLlamaSwapBackend(snapshot.backend)) {
                    context.getString(R.string.pdf_error_missing_llama_swap_model)
                } else {
                    context.getString(R.string.pdf_error_missing_ollama_model)
                }
                throw IllegalStateException(message)
            }

            val summaryPrompt = snapshot.summaryPrompt ?: SettingsRepository.DEFAULT_TRANSCRIPT_SUMMARY_PROMPT
            val mergePrompt = snapshot.mergePrompt ?: SettingsRepository.DEFAULT_TRANSCRIPT_MERGE_PROMPT

            val estimate = orchestrator.estimateChunkPlan(
                summaryPrompt = summaryPrompt,
                text = transcript,
                chunkContext = snapshot.chunkContext,
                chunkMaxTokens = snapshot.chunkMaxTokens,
                targetLanguage = snapshot.targetLanguage
            )
            if (noteType == NoteType.WORKFLOW) {
                WorkflowStateHolder.setProjectedChunkCount(estimate.chunkCount)
            } else {
                VideoSummaryStateHolder.setProjectedChunkCount(estimate.chunkCount)
            }

            val execution = orchestrator.summarize(
                sourceText = transcript,
                summaryPrompt = summaryPrompt,
                mergePrompt = mergePrompt,
                chunkContext = snapshot.chunkContext,
                chunkMaxTokens = snapshot.chunkMaxTokens,
                mergeContext = snapshot.mergeContext,
                mergeMaxTokens = snapshot.mergeMaxTokens,
                temperature = snapshot.temperature,
                thinkingEnabled = snapshot.thinkingEnabled,
                targetLanguage = snapshot.targetLanguage,
                isCancelled = { isCancelled },
                onProgress = { progress ->
                    val label = summaryProgressLabel(context, progress)
                _progress.value = label
                if (noteType == NoteType.WORKFLOW) {
                    WorkflowStateHolder.setStep(label)
                    WorkflowStateHolder.setProgress(summaryProgressFraction(progress))
                    WorkflowStateHolder.setCurrentChunk(progress.currentChunk)
                    WorkflowStateHolder.setTotalChunks(progress.totalChunks)
                    WorkflowStateHolder.setPartialSummaries(progress.partialSummaries)
                } else {
                    VideoSummaryStateHolder.setProgress(label)
                    VideoSummaryStateHolder.setProgressFraction(summaryProgressFraction(progress))
                    VideoSummaryStateHolder.setCurrentChunk(progress.currentChunk)
                    VideoSummaryStateHolder.setTotalChunks(progress.totalChunks)
                    VideoSummaryStateHolder.setPartialSummaries(progress.partialSummaries)
                }
            }
            )

            if (execution.cancelled) {
                throw CancellationException("Cancelled")
            }
            execution.summary ?: throw IllegalStateException(execution.errorMessage ?: "Summary failed")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
            wakeLock = null
            DebugLog.log("[VIDEO-SUMUP] Wake lock released")
        } catch (_: Exception) {}
    }
    
    fun cancel() {
        isCancelled = true
        currentRemoteClient?.cancelActiveCall()
        currentProcess?.destroyForcibly()
        currentJob?.cancel()
        _state.value = VideoSumupState.Idle
        _progress.value = ""
        VideoSummaryStateHolder.setIsRunning(false)
        VideoSummaryStateHolder.setProgressFraction(0f)
        VideoSummaryStateHolder.setCancelled(true)
        WorkflowStateHolder.setIsRunning(false)
        WorkflowStateHolder.setProgress(0f)
        WorkflowStateHolder.setCancelled(true)
        releaseWakeLock()
        RemoteSummaryProtection.release()
    }
    
    fun clearResult() { _result.value = null }
}

sealed class VideoSumupState {
    object Idle : VideoSumupState()
    object ExtractingAudio : VideoSumupState()
    object Transcribing : VideoSumupState()
    object Summarizing : VideoSumupState()
    data class Error(val message: String) : VideoSumupState()
}

data class VideoSumupResult(val transcript: String, val summary: String)
