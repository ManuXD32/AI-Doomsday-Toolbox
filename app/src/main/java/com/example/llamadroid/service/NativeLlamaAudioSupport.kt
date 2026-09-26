package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LLAMA_AUDIO_LOG_TAG = "[LLAMA-AUDIO]"
private val NATIVE_LLAMA_PASSTHROUGH_AUDIO_EXTENSIONS = setOf("wav", "mp3")

/**
 * Temporary compatibility policy for the video-audio setting.  The launch
 * profile will gain a typed [videoAudioEnabled] field in the main settings
 * change.  Until then, callers pass the existing video-enabled state as the
 * fallback and an explicit JSON value always wins.
 */
internal const val NATIVE_LLAMA_VIDEO_AUDIO_PROFILE_KEY = "videoAudioEnabled"

/** Keep direct video audio small enough for low-memory phones and JSON payloads. */
internal const val NATIVE_LLAMA_VIDEO_AUDIO_SAMPLE_RATE = 16_000
internal const val NATIVE_LLAMA_VIDEO_AUDIO_CHANNELS = 1
internal const val NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS = 12_000L
internal const val NATIVE_LLAMA_VIDEO_AUDIO_MAX_WAV_BYTES =
    44L + (NATIVE_LLAMA_VIDEO_AUDIO_SAMPLE_RATE * 2L *
        NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS / 1_000L)

/**
 * Resolve the compatibility policy without asking a model to interpret the
 * setting.  A malformed non-empty profile fails closed; absent profiles use
 * the caller's compatibility default so remote rows can opt in from their
 * explicit server capability.
 */
internal fun nativeLlamaVideoAudioEnabled(
    profileJson: String?,
    compatibilityDefault: Boolean
): Boolean {
    val raw = profileJson?.trim().orEmpty()
    if (raw.isBlank()) return compatibilityDefault
    return runCatching {
        val profile = JSONObject(raw)
        if (profile.has(NATIVE_LLAMA_VIDEO_AUDIO_PROFILE_KEY)) {
            profile.optBoolean(NATIVE_LLAMA_VIDEO_AUDIO_PROFILE_KEY, false)
        } else {
            compatibilityDefault
        }
    }.getOrDefault(false)
}

/** Return a bounded duration suitable for the transient video-audio extraction. */
internal fun nativeLlamaVideoAudioDurationMs(durationMs: Long): Long? =
    durationMs.takeIf { it in 1L..NATIVE_LLAMA_VIDEO_AUDIO_MAX_DURATION_MS }

/**
 * Extract only the first bounded portion of a video's audio as PCM WAV.  A
 * successful `null` result means that the source has no audio stream; callers
 * should then send the visual input alone.  The generated file belongs to the
 * caller's transient directory and must never be persisted as chat history.
 */
internal suspend fun extractNativeLlamaVideoAudio(
    context: Context,
    videoPath: String,
    outputDirectory: File,
    durationMs: Long,
    preferredName: String = "video"
): Result<String?> = withContext(Dispatchers.IO) {
    val duration = nativeLlamaVideoAudioDurationMs(durationMs)
        ?: return@withContext Result.failure(
            IllegalArgumentException("Video audio duration is outside the bounded range")
        )
    val source = File(videoPath)
    if (!source.isFile || !source.canRead()) {
        return@withContext Result.failure(IOException("Video source is not readable"))
    }

    try {
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create transient video-audio directory"
        }
        val binaries = BinaryRepository(context.applicationContext)
        val ffmpeg = binaries.getFFmpegBinary()?.takeIf { it.isFile }
            ?: return@withContext Result.failure(IOException("Packaged ffmpeg binary is unavailable"))
        setupNativeLlamaFfmpegLibrarySymlinks(context.applicationContext)

        // ffprobe lets a video without an audio stream take the documented
        // video-only path instead of turning an optional ffmpeg map into an
        // apparent request failure.
        val ffprobe = binaries.getFFprobeBinary()?.takeIf { it.isFile }
        if (ffprobe != null) {
            val probe = BoundedVideoProcessRunner().run(
                command = listOf(
                    ffprobe.absolutePath,
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=index",
                    "-of", "csv=p=0",
                    source.absolutePath
                ),
                workingDirectory = outputDirectory,
                timeoutMs = VIDEO_AUDIO_PROBE_TIMEOUT_MS
            )
            if (probe.exitCode == 0 && probe.output.trim().isEmpty()) {
                return@withContext Result.success(null)
            }
        }

        val safeStem = NativeLlamaVideoSupport.sanitizeMediaFileName(preferredName)
            .substringBeforeLast('.', "video")
            .ifBlank { "video" }
        val output = File(
            outputDirectory,
            "${safeStem}_audio_${System.nanoTime().toString(16)}.wav"
        )
        val durationSeconds = duration / 1_000.0
        val result = try {
            BoundedVideoProcessRunner().run(
                command = listOf(
                    ffmpeg.absolutePath,
                    "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", source.absolutePath,
                    "-map", "0:a:0?",
                    "-t", String.format(Locale.US, "%.3f", durationSeconds),
                    "-vn", "-sn", "-dn",
                    "-ar", NATIVE_LLAMA_VIDEO_AUDIO_SAMPLE_RATE.toString(),
                    "-ac", NATIVE_LLAMA_VIDEO_AUDIO_CHANNELS.toString(),
                    "-c:a", "pcm_s16le",
                    output.absolutePath
                ),
                workingDirectory = outputDirectory,
                timeoutMs = VIDEO_AUDIO_EXTRACTION_TIMEOUT_MS
            )
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        }

        if (result.exitCode != 0 || !output.isFile || output.length() <= 44L) {
            output.delete()
            val reason = result.output.trim().takeLast(240).ifBlank {
                "ffmpeg exit code ${result.exitCode}"
            }
            return@withContext Result.failure(IOException("Video audio extraction failed: $reason"))
        }
        if (output.length() > NATIVE_LLAMA_VIDEO_AUDIO_MAX_WAV_BYTES) {
            output.delete()
            return@withContext Result.failure(IOException("Video audio extraction exceeded the bounded WAV size"))
        }

        Result.success(output.absolutePath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

private const val VIDEO_AUDIO_PROBE_TIMEOUT_MS = 10_000L
private const val VIDEO_AUDIO_EXTRACTION_TIMEOUT_MS = 60_000L

internal fun requiresNativeLlamaAudioConversion(filePath: String): Boolean =
    File(filePath).extension.lowercase(Locale.ROOT) !in NATIVE_LLAMA_PASSTHROUGH_AUDIO_EXTENSIONS

internal suspend fun prepareAudioPathForNativeLlama(
    context: Context,
    audioPath: String,
    forcePcmWav: Boolean = false
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val inputFile = File(audioPath)
        if (!inputFile.exists()) {
            return@withContext Result.failure(Exception(context.getString(R.string.llama_audio_file_missing)))
        }

        if (!forcePcmWav && !requiresNativeLlamaAudioConversion(audioPath)) {
            return@withContext Result.success(audioPath)
        }
        if (forcePcmWav && isPreparedNativeLlamaAudio(inputFile, context)) {
            return@withContext Result.success(audioPath)
        }

        setupNativeLlamaFfmpegLibrarySymlinks(context)

        val binaryRepo = BinaryRepository(context)
        val ffmpegBinary = binaryRepo.getFFmpegBinary()
        if (ffmpegBinary == null || !ffmpegBinary.exists()) {
            DebugLog.log("$LLAMA_AUDIO_LOG_TAG ffmpeg binary not found")
            return@withContext Result.failure(
                Exception(context.getString(R.string.llama_audio_conversion_missing_ffmpeg))
            )
        }

        val outputDir = File(context.filesDir, "llama_chat_audio").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val outputFile = File(outputDir, "${inputFile.nameWithoutExtension}_llama_$timestamp.wav")

        val args = listOf(
            ffmpegBinary.absolutePath,
            "-y",
            "-i", inputFile.absolutePath,
            "-vn",
            "-ar", "16000",
            "-ac", "1",
            "-c:a", "pcm_s16le",
            outputFile.absolutePath
        )

        DebugLog.log("$LLAMA_AUDIO_LOG_TAG Converting audio for native llama: ${args.joinToString(" ")}")

        val processBuilder = ProcessBuilder(args)
        val libDir = File(context.filesDir, "ffmpeg_libs")
        processBuilder.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}"
        processBuilder.environment()["HOME"] = context.filesDir.absolutePath
        processBuilder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        processBuilder.redirectErrorStream(false)

        val process = processBuilder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (stdout.isNotBlank()) {
            DebugLog.log("$LLAMA_AUDIO_LOG_TAG ffmpeg stdout: ${stdout.take(500)}")
        }
        if (stderr.isNotBlank()) {
            DebugLog.log("$LLAMA_AUDIO_LOG_TAG ffmpeg stderr: ${stderr.lines().takeLast(10).joinToString("\n")}")
        }

        if (exitCode != 0 || !outputFile.exists() || outputFile.length() <= 0L) {
            outputFile.delete()
            val reason = summarizeNativeLlamaAudioFailure(stderr, exitCode)
            DebugLog.log("$LLAMA_AUDIO_LOG_TAG conversion failed: $reason")
            return@withContext Result.failure(
                Exception(context.getString(R.string.llama_audio_conversion_failed, reason))
            )
        }

        DebugLog.log(
            "$LLAMA_AUDIO_LOG_TAG Prepared audio for native llama: ${inputFile.absolutePath} -> ${outputFile.absolutePath}"
        )
        Result.success(outputFile.absolutePath)
    } catch (e: Exception) {
        DebugLog.log("$LLAMA_AUDIO_LOG_TAG exception during conversion: ${e.message}")
        Result.failure(
            Exception(
                context.getString(
                    R.string.llama_audio_conversion_failed,
                    e.message ?: context.getString(R.string.error_generic)
                )
            )
        )
    }
}

private fun isPreparedNativeLlamaAudio(file: File, context: Context): Boolean {
    val audioDir = File(context.filesDir, "llama_chat_audio")
    return file.extension.equals("wav", ignoreCase = true) &&
        file.parentFile?.canonicalPath == audioDir.canonicalPath &&
        file.nameWithoutExtension.contains("_llama_")
}

private fun setupNativeLlamaFfmpegLibrarySymlinks(context: Context) {
    val libDir = File(context.filesDir, "ffmpeg_libs")
    libDir.mkdirs()

    val versionedLibs = mapOf(
        "libx264.so.164" to "libx264.so.164.so",
        "libwhisper.so.1" to "libwhisper.so.1.so",
        "libggml.so.0" to "libggml.so.0.so",
        "libggml-base.so.0" to "libggml-base.so.0.so",
        "libggml-cpu.so.0" to "libggml-cpu.so.0.so"
    )

    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    versionedLibs.forEach { (versionedName, actualName) ->
        val targetFile = File(nativeLibDir, actualName)
        val linkFile = File(libDir, versionedName)
        if (targetFile.exists() && !linkFile.exists()) {
            try {
                Runtime.getRuntime().exec(
                    arrayOf("ln", "-sf", targetFile.absolutePath, linkFile.absolutePath)
                ).waitFor()
                DebugLog.log("$LLAMA_AUDIO_LOG_TAG Created symlink: $versionedName -> $actualName")
            } catch (e: Exception) {
                DebugLog.log("$LLAMA_AUDIO_LOG_TAG Failed to create symlink for $versionedName: ${e.message}")
            }
        }
    }
}

private fun summarizeNativeLlamaAudioFailure(stderr: String, exitCode: Int): String {
    val interestingLines = stderr
        .lines()
        .filter { line ->
            line.contains("error", ignoreCase = true) ||
                line.contains("invalid", ignoreCase = true) ||
                line.contains("failed", ignoreCase = true) ||
                line.contains("unsupported", ignoreCase = true)
        }
        .takeLast(3)
        .joinToString("; ")
        .ifBlank { "ffmpeg exit code $exitCode" }

    return interestingLines.take(240)
}
