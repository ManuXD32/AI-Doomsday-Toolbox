package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LLAMA_AUDIO_LOG_TAG = "[LLAMA-AUDIO]"
private val NATIVE_LLAMA_PASSTHROUGH_AUDIO_EXTENSIONS = setOf("wav", "mp3")

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
