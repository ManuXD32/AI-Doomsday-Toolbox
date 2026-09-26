package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Coordinator-scoped Whisper fallback for visual runs.
 *
 * The legacy video service owns global process/state holders and therefore cannot safely run beside
 * a visual segment. This helper uses a unique directory and the cancellation-aware process runner;
 * one instance represents one fallback process for one coordinator generation.
 */
class VideoRecognitionWhisperTranscriber(
    context: Context,
    private val runId: Long
) {
    private val appContext = context.applicationContext
    private val binaries = BinaryRepository(appContext)
    private val processRunner = BoundedVideoProcessRunner(maxOutputChars = MAX_PROCESS_OUTPUT_CHARS)

    suspend fun transcribe(
        sourceFile: File,
        request: VideoRecognitionAudioFallbackRequest,
        onProgress: (label: String, fraction: Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(sourceFile.isFile && sourceFile.canRead()) { "Video source is not readable" }
        val directory = File(
            appContext.cacheDir,
            "video_recognition_audio/${sanitize(runId.toString())}_${System.nanoTime()}"
        ).apply { mkdirs() }
        val audioFile = File(directory, "source_audio.wav")
        val outputBase = File(directory, "transcript")
        val transcriptFile = File("${outputBase.absolutePath}.txt")
        try {
            val ffmpeg = binaries.getFFmpegBinary()?.takeIf { it.isFile }
                ?: throw IOException(appContext.getString(R.string.whisper_error_ffmpeg_not_found))
            onProgress(
                appContext.getString(R.string.video_recognition_audio_extracting),
                0.12f
            )
            val extraction = processRunner.run(
                command = listOf(
                    ffmpeg.absolutePath,
                    "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", sourceFile.absolutePath,
                    "-t", MAX_AUDIO_DURATION_SECONDS.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    audioFile.absolutePath
                ),
                workingDirectory = directory,
                timeoutMs = MAX_PROCESS_TIMEOUT_MS
            )
            if (extraction.exitCode != 0 || !audioFile.isFile || audioFile.length() == 0L) {
                throw IOException(appContext.getString(R.string.video_recognition_audio_extract_failed))
            }

            currentCoroutineContext().ensureActive()
            val whisper = binaries.getWhisperCliBinary()?.takeIf { it.isFile }
                ?: throw IllegalStateException(
                    appContext.getString(R.string.whisper_error_binary_not_found)
                )
            val modelPath = WhisperModelPathResolver.resolve(
                appContext,
                request.whisperModelPath
            ) ?: throw IllegalStateException(appContext.getString(R.string.whisper_error_no_model))
            val requestedVad = request.vadConfig ?: WhisperVadConfig()
            val effectiveVad = try {
                WhisperVadAssetStore.effectiveConfig(
                    context = appContext,
                    config = requestedVad,
                    purpose = WhisperInvocationPurpose.VIDEO_SUMMARY
                )
            } catch (error: WhisperVadUnavailableException) {
                throw IllegalStateException(
                    appContext.getString(R.string.whisper_error_vad_model_missing),
                    error
                )
            }
            val libraryDir = binaries.getLibraryDir()
            val environment = whisperCpuEnvironment(
                libraryPath = libraryDir,
                homeDirectory = appContext.filesDir,
                temporaryDirectory = appContext.cacheDir
            )
            val capabilities = if (effectiveVad.enabled) {
                WhisperBinaryCapabilityCache.capabilitiesFor(
                    binary = whisper,
                    workingDirectory = appContext.filesDir,
                    environment = environment
                )
            } else {
                null
            }
            val args = try {
                buildWhisperInvocationArgs(
                    request = WhisperInvocationRequest(
                        binaryPath = whisper.absolutePath,
                        modelPath = modelPath,
                        audioPath = audioFile.absolutePath,
                        language = request.language,
                        threads = request.threads.coerceIn(1, 16),
                        translate = false,
                        outputFormats = setOf(WhisperOutputFormat.TXT),
                        outputBasePath = outputBase.absolutePath,
                        purpose = WhisperInvocationPurpose.VIDEO_SUMMARY,
                        vad = effectiveVad
                    ),
                    binaryCapabilities = capabilities
                )
            } catch (error: WhisperUnsupportedFlagsException) {
                throw IllegalStateException(
                    appContext.getString(
                        R.string.whisper_error_vad_unsupported,
                        error.flags.joinToString(", ")
                    ),
                    error
                )
            }
            onProgress(
                appContext.getString(R.string.video_recognition_audio_transcribing),
                0.52f
            )
            // Use the shared bounded runner so cancellation terminates this process promptly.
            val transcription = runWhisperProcess(
                command = args,
                workingDirectory = appContext.filesDir,
                timeoutMs = MAX_PROCESS_TIMEOUT_MS,
                processEnvironment = environment
            )
            currentCoroutineContext().ensureActive()
            if (transcription.exitCode != 0) {
                throw IOException(
                    if (whisperExitCodeIndicatesMissingModel(transcription.exitCode)) {
                        appContext.getString(R.string.whisper_error_no_model)
                    } else {
                        appContext.getString(
                            R.string.video_recognition_whisper_failed,
                            transcription.exitCode
                        )
                    }
                )
            }
            if (!transcriptFile.isFile) {
                throw IOException(appContext.getString(R.string.whisper_error_no_transcript))
            }
            val transcript = transcriptFile.readText().trim().take(MAX_TRANSCRIPT_CHARS)
            if (transcript.isBlank()) {
                throw IOException(appContext.getString(R.string.whisper_error_no_transcript))
            }
            onProgress(
                appContext.getString(R.string.video_recognition_audio_complete),
                1f
            )
            transcript
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "run" }

    private suspend fun runWhisperProcess(
        command: List<String>,
        workingDirectory: File,
        timeoutMs: Long,
        processEnvironment: Map<String, String>
    ): WhisperProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply { environment().putAll(processEnvironment) }
            .start()
        val output = StringBuilder()
        val buffer = ByteArray(4 * 1024)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                drainAvailable(process.inputStream, output, buffer)
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) {
                    throw IOException("Whisper transcription timed out")
                }
            }
            drainAvailable(process.inputStream, output, buffer)
            WhisperProcessResult(process.exitValue(), output.toString())
        } finally {
            runCatching { process.inputStream.close() }
            if (process.isAlive) runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    private fun drainAvailable(
        input: java.io.InputStream,
        output: StringBuilder,
        buffer: ByteArray
    ) {
        var drained = 0
        while (drained < MAX_DRAIN_BYTES && input.available() > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_DRAIN_BYTES - drained))
            if (read <= 0) break
            drained += read
            if (output.length < MAX_PROCESS_OUTPUT_CHARS) {
                val remaining = MAX_PROCESS_OUTPUT_CHARS - output.length
                output.append(String(buffer, 0, minOf(read, remaining), Charsets.UTF_8))
            }
        }
    }

    private data class WhisperProcessResult(
        val exitCode: Int,
        val output: String
    )

    private companion object {
        const val MAX_AUDIO_DURATION_SECONDS = 60 * 60
        const val MAX_PROCESS_TIMEOUT_MS = 60L * 60L * 1_000L
        const val MAX_TRANSCRIPT_CHARS = 1_048_576
        const val MAX_PROCESS_OUTPUT_CHARS = 16 * 1024
        const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
