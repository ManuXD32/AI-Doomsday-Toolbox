package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.example.llamadroid.data.binary.BinaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class StagedVideoMedia(
    val file: File,
    val mediaDirectory: File,
    val relativeFileUrl: String
)

data class VideoSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

data class VideoFrame(
    val file: File,
    val timestampMs: Long
)

data class ProcessedVideoSegment(
    val segment: VideoSegment,
    val frames: List<VideoFrame>,
    val usedTimestampFallback: Boolean
)

data class ProcessedVideo(
    val source: File,
    val durationMs: Long,
    val segments: List<ProcessedVideoSegment>
) {
    val frames: List<VideoFrame> get() = segments.flatMap { it.frames }
}

/** A small, bounded process result used by ffprobe and ffmpeg helpers. */
data class BoundedVideoProcessResult(
    val exitCode: Int,
    val output: String
)

class VideoProcessTimeoutException(message: String) : IOException(message)

/**
 * Extracts bounded frame sets from private staged media. Segments are processed
 * sequentially so a long input cannot queue multiple ffmpeg processes or hold
 * all decoded frames in memory at once.
 */
class VideoMediaProcessor(
    private val context: Context,
    private val binaryRepository: BinaryRepository = BinaryRepository(context),
    private val policy: NativeLlamaVideoPolicy = NativeLlamaVideoPolicy(),
    private val processRunner: BoundedVideoProcessRunner = BoundedVideoProcessRunner()
) {
    suspend fun stageVideo(
        source: Uri,
        mediaDirectory: File,
        preferredName: String? = null
    ): StagedVideoMedia = withContext(Dispatchers.IO) {
        val name = NativeLlamaVideoSupport.sanitizeMediaFileName(
            preferredName?.takeIf { it.isNotBlank() }
                ?: source.lastPathSegment?.substringAfterLast('/')
                ?: "video.mp4"
        ).let { candidate ->
            if (candidate.contains('.')) candidate else "$candidate.mp4"
        }
        val target = uniqueTarget(mediaDirectory, name)
        require(mediaDirectory.mkdirs() || mediaDirectory.isDirectory) {
            "Unable to create private video media directory"
        }
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Unable to open selected video")
        input.use { stream -> copyBounded(stream, target) }
        stagedMedia(target, mediaDirectory)
    }

    suspend fun stageVideo(
        source: File,
        mediaDirectory: File,
        preferredName: String? = null
    ): StagedVideoMedia = withContext(Dispatchers.IO) {
        require(source.isFile && source.canRead()) { "Video source is not readable" }
        require(source.length() <= MAX_STAGED_VIDEO_BYTES) {
            "Video source exceeds the ${MAX_STAGED_VIDEO_BYTES / (1024L * 1024L)} MiB staging limit"
        }
        val name = NativeLlamaVideoSupport.sanitizeMediaFileName(
            preferredName?.takeIf { it.isNotBlank() } ?: source.name
        )
        val target = uniqueTarget(mediaDirectory, name)
        require(mediaDirectory.mkdirs() || mediaDirectory.isDirectory) {
            "Unable to create private video media directory"
        }
        source.inputStream().use { input -> copyBounded(input, target) }
        stagedMedia(target, mediaDirectory)
    }

    /**
     * Stage a retained chat attachment into the currently running native
     * server's private media root. The retained source is never moved or
     * deleted; only the temporary runtime copy is owned by this processor.
     */
    suspend fun stageVideoForActiveRuntime(
        source: File,
        preferredName: String? = null,
        serverPort: Int? = null
    ): StagedVideoMedia = withContext(Dispatchers.IO) {
        val mediaDirectory = if (serverPort != null) {
            NativeLlamaVideoSupport.activeMediaDirectory(context, serverPort)
        } else {
            NativeLlamaVideoSupport.activeMediaDirectory(context)
        } ?: throw IllegalStateException("No active native video runtime is available")
        stageVideo(source, mediaDirectory, preferredName)
    }

    suspend fun processVideo(
        source: File,
        outputDirectory: File,
        durationMsHint: Long? = null
    ): ProcessedVideo {
        require(source.isFile && source.canRead()) { "Video source is not readable" }
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create video frame output directory"
        }
        val durationMs = resolveDurationMs(source, durationMsHint)
        val segments = buildVideoSegmentPlan(durationMs, policy)
        val processed = mutableListOf<ProcessedVideoSegment>()
        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            val segmentDirectory = File(outputDirectory, "segment_${segment.index.toString().padStart(4, '0')}")
            segmentDirectory.mkdirs()
            try {
                val ffmpegFrames = extractWithFfmpeg(source, segment, segmentDirectory)
                val frames = if (ffmpegFrames.isNotEmpty()) {
                    ffmpegFrames
                } else {
                    extractWithTimestampFallback(source, segment, segmentDirectory)
                }
                processed += ProcessedVideoSegment(
                    segment = segment,
                    frames = frames.take(policy.maxFramesPerSegment),
                    usedTimestampFallback = ffmpegFrames.isEmpty()
                )
            } catch (error: Throwable) {
                // A partial segment must not survive cancellation or an extraction failure.
                segmentDirectory.deleteRecursively()
                throw error
            }
        }
        return ProcessedVideo(source, durationMs, processed)
    }

    /**
     * Process one bounded segment at a time and delete its frame files as soon
     * as [onSegment] returns. The callback must finish any native or remote
     * request while the supplied frame files are still readable.
     */
    suspend fun processVideoSegments(
        source: File,
        outputDirectory: File,
        durationMsHint: Long? = null,
        onSegment: suspend (ProcessedVideoSegment) -> Unit
    ): Long {
        require(source.isFile && source.canRead()) { "Video source is not readable" }
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create video frame output directory"
        }
        val durationMs = resolveDurationMs(source, durationMsHint)
        for (segment in buildVideoSegmentPlan(durationMs, policy)) {
            currentCoroutineContext().ensureActive()
            val segmentDirectory = File(
                outputDirectory,
                "segment_${segment.index.toString().padStart(4, '0')}"
            )
            segmentDirectory.mkdirs()
            try {
                val ffmpegFrames = extractWithFfmpeg(source, segment, segmentDirectory)
                val frames = if (ffmpegFrames.isNotEmpty()) {
                    ffmpegFrames
                } else {
                    extractWithTimestampFallback(source, segment, segmentDirectory)
                }
                onSegment(
                    ProcessedVideoSegment(
                        segment = segment,
                        frames = frames.take(policy.maxFramesPerSegment),
                        usedTimestampFallback = ffmpegFrames.isEmpty()
                    )
                )
            } finally {
                // Also runs when request serialization is cancelled or fails.
                segmentDirectory.deleteRecursively()
            }
        }
        return durationMs
    }

    /** Readable alias for callers that consume segments with a suspending callback. */
    suspend fun forEachSegment(
        source: File,
        outputDirectory: File,
        durationMsHint: Long? = null,
        onSegment: suspend (ProcessedVideoSegment) -> Unit
    ): Long = processVideoSegments(source, outputDirectory, durationMsHint, onSegment)

    /** Alias retained for callers that describe this operation as frame processing. */
    suspend fun process(
        source: File,
        outputDirectory: File,
        durationMsHint: Long? = null
    ): ProcessedVideo = processVideo(source, outputDirectory, durationMsHint)

    fun segmentPlan(durationMs: Long): List<VideoSegment> =
        buildVideoSegmentPlan(durationMs, policy)

    /** Probe only; callers that stream native video need duration without decoding every frame. */
    suspend fun probeDurationSeconds(source: File): Double =
        resolveDurationMs(source, durationMsHint = null) / 1_000.0

    /** Process exactly one bounded segment; the caller owns and may delete the output directory. */
    suspend fun processSegment(
        source: File,
        segment: VideoSegment,
        outputDirectory: File,
        requestedFps: Float = policy.defaultFps,
        maxFrames: Int = policy.maxFramesPerSegment
    ): ProcessedVideoSegment {
        require(source.isFile && source.canRead()) { "Video source is not readable" }
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create video frame output directory"
        }
        val boundedPolicy = NativeLlamaVideoPolicy(
            maxSegmentDurationMs = policy.maxSegmentDurationMs,
            maxFramesPerSegment = maxFrames.coerceIn(1, policy.maxFramesPerSegment),
            maxFps = requestedFps.coerceIn(0.1f, policy.maxFps),
            defaultFps = requestedFps.coerceIn(0.1f, policy.maxFps)
        )
        val segmentDirectory = File(
            outputDirectory,
            "segment_${segment.index.toString().padStart(4, '0')}"
        ).apply { mkdirs() }
        return try {
            val ffmpegFrames = extractWithFfmpeg(source, segment, segmentDirectory, boundedPolicy)
            val frames = if (ffmpegFrames.isNotEmpty()) {
                ffmpegFrames
            } else {
                extractWithTimestampFallback(source, segment, segmentDirectory, boundedPolicy)
            }
            ProcessedVideoSegment(
                segment = segment,
                frames = frames.take(boundedPolicy.maxFramesPerSegment),
                usedTimestampFallback = ffmpegFrames.isEmpty()
            )
        } catch (error: Throwable) {
            segmentDirectory.deleteRecursively()
            throw error
        }
    }

    private suspend fun resolveDurationMs(source: File, durationMsHint: Long?): Long {
        durationMsHint?.let {
            require(it > 0L) { "Video duration hint must be positive" }
            return it
        }
        return probeDurationMs(source)
            ?: readRetrieverDurationMs(source)
            ?: throw IOException("Unable to determine video duration")
    }

    private suspend fun probeDurationMs(source: File): Long? {
        val ffprobe = binaryRepository.getFFprobeBinary()?.takeIf { it.isFile } ?: return null
        val result = try {
            processRunner.run(
                command = listOf(
                    ffprobe.absolutePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    source.absolutePath
                ),
                workingDirectory = source.parentFile ?: context.cacheDir,
                timeoutMs = PROBE_TIMEOUT_MS
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        if (result.exitCode != 0) return null
        val seconds = result.output
            .lineSequence()
            .mapNotNull { it.trim().toDoubleOrNull() }
            .firstOrNull { it.isFinite() && it > 0.0 }
            ?: return null
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(1L)
    }

    private fun readRetrieverDurationMs(source: File): Long? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } finally {
            retriever.release()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun extractWithFfmpeg(
        source: File,
        segment: VideoSegment,
        segmentDirectory: File,
        extractionPolicy: NativeLlamaVideoPolicy = policy
    ): List<VideoFrame> {
        val ffmpeg = binaryRepository.getFFmpegBinary()?.takeIf { it.isFile } ?: return emptyList()
        val rawPattern = File(segmentDirectory, "raw_%06d.jpg")
        val fps = effectiveVideoFpsForSegment(
            segment = segment,
            requestedFps = extractionPolicy.defaultFps,
            maxFrames = extractionPolicy.maxFramesPerSegment,
            policy = extractionPolicy
        )
        val result = try {
            processRunner.run(
                command = buildVideoFrameExtractionCommand(
                    ffmpegPath = ffmpeg.absolutePath,
                    sourcePath = source.absolutePath,
                    segment = segment,
                    fps = fps,
                    maxFrames = extractionPolicy.maxFramesPerSegment,
                    outputPattern = rawPattern.absolutePath
                ),
                workingDirectory = segmentDirectory,
                timeoutMs = FFMPEG_TIMEOUT_MS
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return emptyList()
        }
        if (result.exitCode != 0) return emptyList()

        val rawFrames = segmentDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("raw_") && it.extension.equals("jpg", true) }
            .sortedBy { it.name }
            .take(extractionPolicy.maxFramesPerSegment)
        if (rawFrames.isEmpty()) return emptyList()
        val intervalMs = (1_000.0 / fps).roundToLong().coerceAtLeast(1L)
        return rawFrames.mapIndexed { index, raw ->
            val timestamp = (segment.startMs + index * intervalMs)
                .coerceAtMost((segment.endMs - 1L).coerceAtLeast(segment.startMs))
            val named = File(segmentDirectory, timestampedFrameName(timestamp, index))
            val output = if (raw.renameTo(named)) named else raw
            VideoFrame(output, timestamp)
        }
    }

    private suspend fun extractWithTimestampFallback(
        source: File,
        segment: VideoSegment,
        segmentDirectory: File,
        extractionPolicy: NativeLlamaVideoPolicy = policy
    ): List<VideoFrame> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<VideoFrame>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val fps = effectiveVideoFpsForSegment(
                segment = segment,
                requestedFps = extractionPolicy.defaultFps,
                maxFrames = extractionPolicy.maxFramesPerSegment,
                policy = extractionPolicy
            )
            val intervalMs = (1_000.0 / fps).roundToLong().coerceAtLeast(1L)
            var timestamp = segment.startMs
            var index = 0
            while (index < extractionPolicy.maxFramesPerSegment && timestamp < segment.endMs) {
                currentCoroutineContext().ensureActive()
                val bitmap = retriever.frame(timestamp * 1_000L) ?: break
                val file = File(segmentDirectory, timestampedFrameName(timestamp, index))
                try {
                    val boundedBitmap = bitmap.boundTo(FALLBACK_MAX_DIMENSION)
                    try {
                        FileOutputStream(file).use { output ->
                            check(boundedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                                "Unable to encode fallback video frame"
                            }
                        }
                    } finally {
                        if (boundedBitmap !== bitmap && !boundedBitmap.isRecycled) {
                            boundedBitmap.recycle()
                        }
                    }
                    frames += VideoFrame(file, timestamp)
                    index += 1
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                timestamp += intervalMs
            }
        } finally {
            retriever.release()
        }
        frames
    }

    private fun stagedMedia(file: File, mediaDirectory: File): StagedVideoMedia =
        StagedVideoMedia(
            file = file,
            mediaDirectory = mediaDirectory,
            relativeFileUrl = NativeLlamaVideoSupport.relativeFileUrl(file.name)
        )

    private fun uniqueTarget(directory: File, name: String): File {
        val safeName = NativeLlamaVideoSupport.sanitizeMediaFileName(name)
        val initial = File(directory, safeName)
        if (!initial.exists()) return initial
        val stem = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").takeIf { it != safeName }
        var index = 1
        while (true) {
            val candidateName = if (extension == null) "$stem-$index" else "$stem-$index.$extension"
            val candidate = File(directory, NativeLlamaVideoSupport.sanitizeMediaFileName(candidateName))
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private suspend fun copyBounded(input: java.io.InputStream, target: File) {
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copiedBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copiedBytes += read.toLong()
                    if (copiedBytes > MAX_STAGED_VIDEO_BYTES) {
                        throw IOException(
                            "Video source exceeds the ${MAX_STAGED_VIDEO_BYTES / (1024L * 1024L)} MiB staging limit"
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_STAGED_VIDEO_BYTES = 4L * 1024L * 1024L * 1024L
        private const val JPEG_QUALITY = 86
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val FFMPEG_TIMEOUT_MS = 120_000L
        private const val FALLBACK_MAX_DIMENSION = MAX_VIDEO_FRAME_DIMENSION
    }
}

/** Pure segment planning policy used by both local extraction and request builders. */
fun buildVideoSegmentPlan(
    durationMs: Long,
    policy: NativeLlamaVideoPolicy = NativeLlamaVideoPolicy()
): List<VideoSegment> {
    val boundedDuration = durationMs.coerceAtLeast(1L)
    val segmentDuration = policy.maxSegmentDurationMs.coerceAtMost(MAX_SEGMENT_DURATION_MS)
    require(boundedDuration <= MAX_VIDEO_PLAN_DURATION_MS) {
        "Video duration exceeds the bounded ${MAX_VIDEO_PLAN_DURATION_MS / 60_000L}-minute policy"
    }
    val segmentCountLong = ((boundedDuration - 1L) / segmentDuration) + 1L
    require(segmentCountLong <= MAX_VIDEO_SEGMENTS_PER_RUN) {
        "Video duration requires more than $MAX_VIDEO_SEGMENTS_PER_RUN bounded segments"
    }
    val segmentCount = segmentCountLong.toInt()
    return (0 until segmentCount).map { index ->
        val start = index.toLong() * segmentDuration
        VideoSegment(
            index = index,
            startMs = start,
            endMs = minOf(start + segmentDuration, boundedDuration)
        )
    }
}

fun buildVideoFrameExtractionCommand(
    ffmpegPath: String,
    sourcePath: String,
    segment: VideoSegment,
    fps: Float,
    maxFrames: Int,
    outputPattern: String,
    policy: NativeLlamaVideoPolicy = NativeLlamaVideoPolicy()
): List<String> {
    require(ffmpegPath.isNotBlank()) { "ffmpeg path must not be blank" }
    require(sourcePath.isNotBlank()) { "video source path must not be blank" }
    require(outputPattern.isNotBlank()) { "frame output pattern must not be blank" }
    val boundedFrames = maxFrames.coerceIn(1, policy.maxFramesPerSegment)
    val boundedFps = effectiveVideoFpsForSegment(segment, fps, boundedFrames, policy)
    return listOf(
        ffmpegPath,
        "-hide_banner",
        "-loglevel", "error",
        "-nostdin",
        "-y",
        "-ss", formatSeconds(segment.startMs),
        "-i", sourcePath,
        "-t", formatSeconds(segment.durationMs),
        "-vf",
        "fps=${formatExtractionFps(boundedFps)}," +
            "scale=${MAX_VIDEO_FRAME_DIMENSION}:${MAX_VIDEO_FRAME_DIMENSION}:" +
            "force_original_aspect_ratio=decrease:force_divisible_by=2",
        "-frames:v", boundedFrames.toString(),
        "-vsync", "vfr",
        "-q:v", "3",
        outputPattern
    )
}

/**
 * Select the lower of the requested rate and the rate that covers the complete
 * segment without exceeding its frame budget. With the default 30-second and
 * 24-frame policy, this is 0.8 fps rather than 2 fps.
 */
fun effectiveVideoFpsForSegment(
    segment: VideoSegment,
    requestedFps: Float,
    maxFrames: Int,
    policy: NativeLlamaVideoPolicy = NativeLlamaVideoPolicy()
): Float {
    require(segment.durationMs > 0L) { "Video segment duration must be positive" }
    val boundedFrames = maxFrames.coerceIn(1, policy.maxFramesPerSegment)
    val coverageFps = boundedFrames * 1_000f / segment.durationMs.toFloat()
    // The policy's public/native setting has a 0.1 fps floor, but extraction may
    // need a lower internal rate to spread one or two frames across a full
    // 30-second segment. Keep that coverage rate intact; callers that cannot
    // represent sub-0.1 native fps must switch to timestamped frame fallback.
    val requested = if (requestedFps.isFinite() && requestedFps > 0f) {
        requestedFps.coerceAtMost(policy.maxFps)
    } else {
        policy.defaultFps
    }
    return minOf(requested, coverageFps).coerceAtLeast(Float.MIN_VALUE)
}

private fun formatExtractionFps(value: Float): String =
    String.format(Locale.US, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')
        .ifBlank { "0.1" }

private fun timestampedFrameName(timestampMs: Long, index: Int): String =
    "frame_${timestampMs.toString().padStart(13, '0')}_${index.toString().padStart(2, '0')}.jpg"

private fun formatSeconds(milliseconds: Long): String =
    String.format(Locale.US, "%.3f", milliseconds.coerceAtLeast(0L) / 1_000.0)

private fun MediaMetadataRetriever.frame(timestampUs: Long): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        getScaledFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
            768,
            768
        )
    } else {
        getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

private fun Bitmap.boundTo(maxDimension: Int): Bitmap {
    val largest = maxOf(width, height)
    if (largest <= maxDimension) return this
    val scale = maxDimension.toFloat() / largest.toFloat()
    val scaled = Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToLong().toInt().coerceAtLeast(1),
        (height * scale).roundToLong().toInt().coerceAtLeast(1),
        true
    )
    recycle()
    return scaled
}

/**
 * A cancellation-aware process runner.  ffmpeg is always terminated in the
 * finally block, including when the parent coroutine is cancelled or a bounded
 * timeout is reached.
 */
class BoundedVideoProcessRunner(
    private val maxOutputChars: Int = 16 * 1024
) {
    suspend fun run(
        command: List<String>,
        workingDirectory: File,
        timeoutMs: Long
    ): BoundedVideoProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "Video process command must not be empty" }
        require(timeoutMs > 0L) { "Video process timeout must be positive" }
        workingDirectory.mkdirs()
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
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
                    throw VideoProcessTimeoutException(
                        "Video process timed out after ${timeoutMs}ms: ${command.first()}"
                    )
                }
            }
            drainAvailable(process.inputStream, output, buffer)
            BoundedVideoProcessResult(process.exitValue(), output.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            terminate(process)
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
            if (output.length < maxOutputChars) {
                val remaining = maxOutputChars - output.length
                output.append(String(buffer, 0, minOf(read, remaining), Charsets.UTF_8))
            }
        }
    }

    private fun terminate(process: Process) {
        runCatching { process.inputStream.close() }
        if (process.isAlive) {
            runCatching { process.destroy() }
            runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }
        }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private companion object {
        const val MAX_DRAIN_BYTES = 64 * 1024
    }
}

private const val MAX_VIDEO_PLAN_DURATION_MS = 60 * 60 * 1_000L
