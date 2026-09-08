package com.example.llamadroid.audio

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class AudioProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

internal object AudioProcessRunner {
    suspend fun run(
        context: Context,
        command: List<String>,
        isCancelled: () -> Boolean = { false },
        environmentOverrides: Map<String, String> = emptyMap()
    ): AudioProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "Audio process command is empty" }
        val repository = BinaryRepository(context)
        val process = ProcessBuilder(command)
            .directory(context.cacheDir)
            .redirectErrorStream(false)
            .apply {
                repository.getLibraryDir().takeIf { it.isNotBlank() }?.let {
                    environment()["LD_LIBRARY_PATH"] = it
                }
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environmentOverrides.forEach { (key, value) -> environment()[key] = value }
            }
            .start()
        val stdoutJob = async { process.inputStream.readTail() }
        val stderrJob = async { process.errorStream.readTail() }
        val deadline = System.nanoTime() + MAX_PROCESS_RUNTIME_MS * 1_000_000L
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (isCancelled()) throw CancellationException("Audio process cancelled")
                if (System.nanoTime() >= deadline) {
                    throw IllegalStateException("Audio process timed out")
                }
            }
            AudioProcessResult(
                exitCode = process.exitValue(),
                stdout = stdoutJob.await(),
                stderr = stderrJob.await()
            )
        } catch (cancelled: CancellationException) {
            stdoutJob.cancel()
            stderrJob.cancel()
            process.destroy()
            process.waitFor(500, TimeUnit.MILLISECONDS)
            if (process.isAlive) process.destroyForcibly()
            throw cancelled
        } catch (error: Throwable) {
            stdoutJob.cancel()
            stderrJob.cancel()
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            throw error
        } finally {
            // Closing the streams is required after a forced child shutdown;
            // otherwise a blocked drain coroutine can retain the process.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private const val MAX_CAPTURE_BYTES = 64 * 1024
    private const val MAX_PROCESS_RUNTIME_MS = 30L * 60L * 1000L

    private fun InputStream.readTail(maxBytes: Int = MAX_CAPTURE_BYTES): String {
        val tail = ByteArrayOutputStream(maxBytes)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = read(buffer)
            if (count <= 0) break
            if (count >= maxBytes) {
                tail.reset()
                tail.write(buffer, count - maxBytes, maxBytes)
            } else if (tail.size() + count <= maxBytes) {
                tail.write(buffer, 0, count)
            } else {
                val existing = tail.toByteArray()
                tail.reset()
                val keep = (maxBytes - count).coerceAtLeast(0)
                if (keep > 0) tail.write(existing, existing.size - keep, keep)
                tail.write(buffer, 0, count)
            }
        }
        return tail.toString(Charsets.UTF_8.name())
    }
}

internal object AudioFfmpeg {
    suspend fun convertToMp3(context: Context, input: File, output: File, isCancelled: () -> Boolean = { false }) {
        val ffmpeg = BinaryRepository(context).getFFmpegBinary()
            ?: error("FFmpeg is not installed")
        output.parentFile?.mkdirs()
        val result = AudioProcessRunner.run(
            context,
            listOf(
                ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.absolutePath,
                "-vn", "-codec:a", "libmp3lame", "-b:a", "128k",
                output.absolutePath
            ),
            isCancelled
        )
        require(result.exitCode == 0 && output.isFile && output.length() > 0L) {
            "FFmpeg MP3 conversion failed (${result.exitCode})"
        }
    }

    suspend fun processVoice(
        context: Context,
        input: File,
        output: File,
        trimStartMs: Long,
        trimEndMs: Long?,
        normalize: Boolean,
        denoise: Boolean,
        sampleRate: Int = 24_000,
        isCancelled: () -> Boolean = { false }
    ) {
        val ffmpeg = BinaryRepository(context).getFFmpegBinary()
            ?: error("FFmpeg is not installed")
        val filter = buildList {
            if (denoise) add("afftdn=nf=-25")
            if (normalize) add("loudnorm=I=-16:TP=-1.5:LRA=11")
        }.joinToString(",")
        val args = mutableListOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-loglevel", "error", "-y"
        )
        if (trimStartMs > 0L) {
            args += listOf("-ss", (trimStartMs / 1000.0).formatSeconds())
        }
        args += listOf("-i", input.absolutePath)
        trimEndMs?.let { end ->
            val duration = (end - trimStartMs).coerceAtLeast(1L)
            args += listOf("-t", (duration / 1000.0).formatSeconds())
        }
        args += listOf("-vn", "-ac", "1", "-ar", sampleRate.toString(), "-codec:a", "pcm_s16le")
        if (filter.isNotBlank()) args += listOf("-af", filter)
        args += output.absolutePath
        output.parentFile?.mkdirs()
        val result = AudioProcessRunner.run(context, args, isCancelled)
        require(result.exitCode == 0 && output.isFile && output.length() > 44L) {
            "FFmpeg voice processing failed (${result.exitCode})"
        }
    }

    suspend fun postProcessOutput(
        context: Context,
        input: File,
        output: File,
        sampleRate: Int,
        speed: Float,
        isCancelled: () -> Boolean = { false }
    ) {
        val ffmpeg = BinaryRepository(context).getFFmpegBinary()
            ?: error("FFmpeg is not installed")
        val filters = buildList {
            if (kotlin.math.abs(speed - 1.0f) > 0.001f) add(atempoFilter(speed))
        }.joinToString(",")
        val args = mutableListOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-loglevel", "error", "-y",
            "-i", input.absolutePath,
            "-vn", "-ar", sampleRate.toString()
        )
        if (filters.isNotBlank()) args += listOf("-af", filters)
        args += output.absolutePath
        output.parentFile?.mkdirs()
        val result = AudioProcessRunner.run(context, args, isCancelled)
        require(result.exitCode == 0 && output.isFile && output.length() > 44L) {
            "FFmpeg output processing failed (${result.exitCode})"
        }
    }

    private fun atempoFilter(speed: Float): String {
        var value = speed.coerceIn(0.25f, 4.0f)
        val parts = mutableListOf<String>()
        while (value < 0.5f) {
            parts += "atempo=0.5"
            value /= 0.5f
        }
        while (value > 2.0f) {
            parts += "atempo=2.0"
            value /= 2.0f
        }
        parts += "atempo=${String.format(Locale.US, "%.4f", value)}"
        return parts.joinToString(",")
    }

    private fun Double.formatSeconds(): String = String.format(Locale.US, "%.3f", this)
}

internal fun logAudioProcessFailure(tag: String, result: AudioProcessResult) {
    if (result.exitCode != 0) {
        DebugLog.log("[AUDIO] $tag failed with exit=${result.exitCode}")
    }
}
