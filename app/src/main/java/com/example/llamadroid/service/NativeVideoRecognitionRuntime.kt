package com.example.llamadroid.service

import com.example.llamadroid.R
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.data.binary.BinaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Concrete visual runtime used by the video coordinator. Local sessions are launched through
 * LlamaServerSessionService with a stable, feature-owned id; remote requests use the same
 * OpenAI-compatible llama-server route and require a server row marked supportsVideo.
 */
class NativeVideoRecognitionRuntime(
    context: android.content.Context
) : VideoRecognitionRuntime {
    private val appContext = context.applicationContext
    private val binaries = BinaryRepository(appContext)
    private val processRunner = BoundedVideoProcessRunner()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(20, TimeUnit.MINUTES)
        .callTimeout(25, TimeUnit.MINUTES)
        .build()
    private val ownedSessions = ConcurrentHashMap<String, OwnedSession>()
    @Volatile private var activeCall: Call? = null

    override suspend fun probeDurationSeconds(sourceFile: File): Double =
        VideoMediaProcessor(appContext, binaries).probeDurationSeconds(sourceFile)

    override suspend fun summarizeSegment(
        request: VideoRecognitionSegmentRequest,
        onProgress: (String, Float) -> Unit
    ): String {
        currentCoroutineContext().ensureActive()
        onProgress("Preparing video segment ${request.segment.index}/${request.segment.total}", 0.08f)
        return when (request.target.kind) {
            VideoRecognitionTarget.Kind.LOCAL_BUNDLE -> {
                // Keep direct input_video requests within llama.cpp's twelve-second decoder
                // limit. Longer user-selected segments still work through the same bounded
                // client-side frame path and never send an oversized clip to MTMD.
                if (request.segment.durationSeconds * 1_000L >
                    NativeLlamaVideoSupport.MAX_DIRECT_VIDEO_DURATION_MS
                ) {
                    return summarizeFramesFallback(request, onProgress)
                }
                // A context budget that permits only one frame cannot satisfy the native
                // decoder's practical 0.1fps floor across a long segment. Use the shared
                // timestamped-frame path so the <=maxFrames contract remains exact.
                val effectiveFps = request.maxFrames.toFloat() /
                    request.segment.durationSeconds.coerceAtLeast(1).toFloat()
                if (effectiveFps < 0.1f) {
                    return summarizeFramesFallback(request, onProgress)
                }
                val session = ensureLocalSession(request, requireVideo = true)
                val mediaDirectory = session.directories?.mediaDirectory
                    ?: throw IOException("Native video media runtime is unavailable")
                val mediaFile = trimSegment(request, mediaDirectory)
                val audioFile = prepareAudioIfSupported(request, mediaDirectory, onProgress)
                try {
                    onProgress("Sending video to the local model", 0.35f)
                    completeVideoRequest(
                        target = request.target,
                        endpoint = "http://127.0.0.1:${session.port}",
                        mediaFile = mediaFile,
                        audioFile = audioFile,
                        request = request
                    )
                } finally {
                    mediaFile.delete()
                    audioFile?.delete()
                }
            }
            VideoRecognitionTarget.Kind.REMOTE_SERVER -> {
                // Remote llama-server rows do not expose their decoder fps. Sending a clip
                // leaves the remote --video-fps policy in control and can duplicate sparse
                // frames. Transport the bounded, timestamped frame set instead.
                val directory = operationDirectory(request).apply { mkdirs() }
                try {
                    val frames = extractFrames(request, directory)
                    if (frames.isEmpty()) throw IOException("No video frames could be extracted")
                    val audioFile = prepareAudioIfSupported(request, directory, onProgress)
                    onProgress("Sending timestamped frames to the remote model", 0.35f)
                    completeFrameRequest(
                        target = request.target,
                        endpoint = requireNotNull(request.target.remoteEndpoint),
                        frames = frames,
                        request = request,
                        audioFile = audioFile
                    )
                } finally {
                    directory.deleteRecursively()
                }
            }
        }.also { onProgress("Segment complete", 1f) }
    }

    override suspend fun summarizeFramesFallback(
        request: VideoRecognitionSegmentRequest,
        onProgress: (String, Float) -> Unit
    ): String {
        currentCoroutineContext().ensureActive()
        val directory = operationDirectory(request).apply { mkdirs() }
        val frames = try {
            onProgress("Extracting fallback frames", 0.1f)
            extractFrames(request, directory)
                .also { onProgress("Sending ${it.size} fallback frames", 0.4f) }
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
        try {
            if (frames.isEmpty()) throw IOException("No video frames could be extracted")
            val audioFile = prepareAudioIfSupported(request, directory, onProgress)
            val endpoint = when (request.target.kind) {
                VideoRecognitionTarget.Kind.LOCAL_BUNDLE -> {
                    val session = ensureLocalSession(request, requireVideo = false)
                    "http://127.0.0.1:${session.port}"
                }
                VideoRecognitionTarget.Kind.REMOTE_SERVER ->
                    requireNotNull(request.target.remoteEndpoint)
            }
            return completeFrameRequest(
                target = request.target,
                endpoint = endpoint,
                frames = frames,
                request = request,
                audioFile = audioFile
            ).also { onProgress("Frame fallback complete", 1f) }
        } finally {
            directory.deleteRecursively()
        }
    }

    override suspend fun mergeSummaries(
        request: VideoRecognitionMergeRequest,
        onProgress: (String, Float) -> Unit
    ): String {
        currentCoroutineContext().ensureActive()
        val endpoint = when (request.target.kind) {
            VideoRecognitionTarget.Kind.LOCAL_BUNDLE -> {
                val session = ensureLocalSession(
                    request = VideoRecognitionSegmentRequest(
                        sessionKey = request.sessionKey,
                        target = request.target,
                        sourceFile = File("."),
                        sourceName = request.sourceName,
                        segment = VideoRecognitionSegment(1, 1, 0, 1),
                        maxFrames = VideoRecognitionLimits.DEFAULT_MAX_FRAMES,
                        maxFps = VideoRecognitionLimits.DEFAULT_MAX_FPS,
                        targetLanguage = request.targetLanguage,
                        prompt = request.prompt,
                        contextSize = request.contextSize,
                        maxTokens = request.maxTokens,
                        temperature = request.temperature,
                        timeoutMinutes = request.timeoutMinutes
                    ),
                    requireVideo = false
                )
                "http://127.0.0.1:${session.port}"
            }
            VideoRecognitionTarget.Kind.REMOTE_SERVER ->
                requireNotNull(request.target.remoteEndpoint)
        }
        onProgress("Merging visual segment summaries", 0.2f)
        val merged = completeTextRequest(
            target = request.target,
            endpoint = endpoint,
            prompt = mergePrompt(request),
            contextSize = request.contextSize,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            timeoutMinutes = request.timeoutMinutes
        )
        onProgress("Merge complete", 1f)
        return merged
    }

    override suspend fun mergeMultimodalSummary(
        request: VideoRecognitionMultimodalMergeRequest,
        onProgress: (String, Float) -> Unit
    ): String {
        currentCoroutineContext().ensureActive()
        val endpoint = when (request.target.kind) {
            VideoRecognitionTarget.Kind.LOCAL_BUNDLE -> {
                val session = ensureLocalSession(
                    request = VideoRecognitionSegmentRequest(
                        sessionKey = request.sessionKey,
                        target = request.target,
                        sourceFile = File("."),
                        sourceName = request.sourceName,
                        segment = VideoRecognitionSegment(1, 1, 0, 1),
                        maxFrames = VideoRecognitionLimits.DEFAULT_MAX_FRAMES,
                        maxFps = VideoRecognitionLimits.DEFAULT_MAX_FPS,
                        targetLanguage = request.targetLanguage,
                        prompt = request.prompt,
                        contextSize = request.contextSize,
                        maxTokens = request.maxTokens,
                        temperature = request.temperature,
                        timeoutMinutes = request.timeoutMinutes
                    ),
                    requireVideo = false
                )
                "http://127.0.0.1:${session.port}"
            }
            VideoRecognitionTarget.Kind.REMOTE_SERVER ->
                requireNotNull(request.target.remoteEndpoint)
        }
        onProgress("Merging visual and audio evidence", 0.2f)
        val merged = completeTextRequest(
            target = request.target,
            endpoint = endpoint,
            prompt = multimodalMergePrompt(request),
            contextSize = request.contextSize,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            timeoutMinutes = request.timeoutMinutes
        )
        onProgress("Multimodal merge complete", 1f)
        return merged
    }

    override fun cancelActiveOperation() {
        activeCall?.cancel()
        stopOwnedSessions()
    }

    override fun shutdown() {
        activeCall?.cancel()
        stopOwnedSessions()
    }

    private suspend fun ensureLocalSession(
        request: VideoRecognitionSegmentRequest,
        requireVideo: Boolean
    ): OwnedSession {
        val key = request.sessionKey
        ownedSessions[key]?.let { existing ->
            if (isSessionRunning(existing.sessionId) && (!requireVideo || existing.directories != null)) {
                return existing
            }
            stopSession(existing)
        }

        val sessionId = stableSessionId(request.target)
        val directories = try {
            NativeLlamaVideoSupport.createRuntimeDirectories(
                context = appContext,
                binaryRepository = binaries,
                sessionId = sessionId
            )
        } catch (error: Throwable) {
            if (requireVideo) throw error
            null
        }
        return startLocalSession(request, sessionId, directories, requireVideo)
            .also { ownedSessions[key] = it }
    }

    private suspend fun startLocalSession(
        request: VideoRecognitionSegmentRequest,
        sessionId: String,
        directories: LlamaVideoRuntimeDirectories?,
        requireVideo: Boolean
    ): OwnedSession {
        val port = freeLoopbackPort()
        val ownerStore = LlamaServerSessionOwnerStore(appContext)
        val previousOwnerPid = ownerStore.get(sessionId)?.pid
        val profile = LlamaServerLaunchProfile(
            modelPath = requireNotNull(request.target.modelPath),
            mmprojPath = request.target.mmprojPath,
            visionEnabled = true,
            videoEnabled = requireVideo && directories != null,
            videoFps = boundedSegmentFps(request),
            videoTimestampIntervalMs = NativeLlamaVideoSupport.DEFAULT_TIMESTAMP_INTERVAL_MS,
            mediaPath = directories?.mediaPath,
            videoFfmpegDir = directories?.ffmpegDirPath,
            host = "127.0.0.1",
            serverPort = port,
            threads = 4,
            contextSize = request.contextSize,
            temperature = request.temperature
        )
        LlamaServerLauncher.startSession(appContext, sessionId, profile, port)
            .getOrThrow()
        val stateStore = LlamaServerSessionStateStore(appContext)
        val deadline = System.currentTimeMillis() + request.timeoutMinutes.coerceIn(1, 30) * 60_000L
        try {
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val snapshot = stateStore.readAll().firstOrNull { it.sessionId == sessionId }
                val owner = ownerStore.get(sessionId)
                when {
                    snapshot?.status == LlamaServerSessionStatus.ERROR -> {
                        throw IllegalStateException(snapshot.error ?: "Local video model failed to start")
                    }
                    snapshot?.isRunning == true && owner != null &&
                        (previousOwnerPid == null || owner.pid != previousOwnerPid) -> {
                        return OwnedSession(sessionId, snapshot.port ?: port, directories)
                    }
                }
                delay(250L)
            }
            throw IOException("Timed out while starting the local video model")
        } catch (cancelled: CancellationException) {
            runCatching { LlamaServerLauncher.stopSession(appContext, sessionId) }
            directories?.cleanup()
            throw cancelled
        } catch (error: Throwable) {
            runCatching { LlamaServerLauncher.stopSession(appContext, sessionId) }
            directories?.cleanup()
            throw error
        }
    }

    private suspend fun trimSegment(
        request: VideoRecognitionSegmentRequest,
        mediaDirectory: File,
        destinationOverride: File? = null
    ): File {
        val destination = destinationOverride
            ?: File(mediaDirectory, "segment_${request.segment.index}.mp4")
        mediaDirectory.mkdirs()
        val ffmpeg = binaries.getFFmpegBinary()?.takeIf { it.isFile }
            ?: throw IOException("Packaged ffmpeg binary is unavailable")
        val result = processRunner.run(
            command = listOf(
                ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-ss", formatSeconds(request.segment.startSeconds * 1_000L),
                "-i", request.sourceFile.absolutePath,
                "-t", formatSeconds(request.segment.durationSeconds * 1_000L),
                "-vf",
                "fps=${formatFps(boundedSegmentFps(request))}," +
                    "scale=${MAX_VIDEO_FRAME_DIMENSION}:${MAX_VIDEO_FRAME_DIMENSION}:" +
                    "force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-frames:v", request.maxFrames.coerceIn(1, NativeLlamaVideoSupport.MAX_FRAMES_PER_SEGMENT).toString(),
                "-an", "-c:v", "mpeg4", "-q:v", "4", destination.absolutePath
            ),
            workingDirectory = mediaDirectory,
            timeoutMs = request.timeoutMinutes.coerceIn(1, 30) * 60_000L
        )
        if (result.exitCode != 0 || !destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IOException("Unable to prepare video segment: ${result.output.takeLast(240)}")
        }
        return destination
    }

    /** Extract at most one mono 16 kHz PCM segment; failed extraction leaves visual work usable. */
    private suspend fun prepareAudioIfSupported(
        request: VideoRecognitionSegmentRequest,
        directory: File,
        onProgress: (String, Float) -> Unit
    ): File? {
        if (!request.includeAudio || !request.target.supportsAudio) return null
        if (request.segment.durationSeconds * 1_000L >
            NativeLlamaVideoSupport.MAX_DIRECT_VIDEO_DURATION_MS
        ) {
            onProgress(
                appContext.getString(R.string.video_recognition_audio_skipped_direct_limit),
                0.2f
            )
            return null
        }
        val destination = File(directory, "segment_${request.segment.index}.wav")
        return try {
            onProgress("Preparing bounded audio", 0.16f)
            extractAudioSegment(request, directory, destination)
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (_: Throwable) {
            destination.delete()
            onProgress("Audio unavailable; continuing with visual input", 0.2f)
            null
        }
    }

    private suspend fun extractAudioSegment(
        request: VideoRecognitionSegmentRequest,
        directory: File,
        destination: File
    ): File {
        directory.mkdirs()
        val ffmpeg = binaries.getFFmpegBinary()?.takeIf { it.isFile }
            ?: throw IOException("Packaged ffmpeg binary is unavailable")
        val result = processRunner.run(
            command = listOf(
                ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-ss", formatSeconds(request.segment.startSeconds * 1_000L),
                "-i", request.sourceFile.absolutePath,
                "-t", formatSeconds(request.segment.durationSeconds * 1_000L),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                destination.absolutePath
            ),
            workingDirectory = directory,
            timeoutMs = request.timeoutMinutes.coerceIn(1, 30) * 60_000L
        )
        if (result.exitCode != 0 || !destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IOException("Unable to prepare audio segment: ${result.output.takeLast(240)}")
        }
        return destination
    }

    private suspend fun extractFrames(
        request: VideoRecognitionSegmentRequest,
        directory: File
    ): List<VideoFrame> {
        val segment = VideoSegment(
            index = request.segment.index,
            startMs = request.segment.startSeconds * 1_000L,
            endMs = (request.segment.startSeconds + request.segment.durationSeconds) * 1_000L
        )
        val processed = VideoMediaProcessor(
            context = appContext,
            binaryRepository = binaries,
            policy = NativeLlamaVideoPolicy(
                maxFramesPerSegment = request.maxFrames.coerceIn(1, NativeLlamaVideoSupport.MAX_FRAMES_PER_SEGMENT),
                maxFps = request.maxFps.coerceIn(0.1f, NativeLlamaVideoSupport.MAX_VIDEO_FPS),
                defaultFps = request.maxFps.coerceIn(0.1f, NativeLlamaVideoSupport.MAX_VIDEO_FPS)
            ),
            processRunner = processRunner
        ).processSegment(
            source = request.sourceFile,
            segment = segment,
            outputDirectory = directory,
            requestedFps = request.maxFps,
            maxFrames = request.maxFrames
        )
        return processed.frames
    }

    private suspend fun completeVideoRequest(
        target: VideoRecognitionTarget,
        endpoint: String,
        mediaFile: File,
        audioFile: File?,
        request: VideoRecognitionSegmentRequest
    ): String {
        val prompt = segmentPrompt(request, frameTimestampsAbsolute = false)
        val json = chatEnvelope(
            model = target.remoteModel ?: "local-model",
            prompt = prompt,
            contextSize = request.contextSize,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                JSONObject().put("type", "input_video").put(
                        "input_video", JSONObject().put(
                            "data", NativeLlamaVideoSupport.relativeFileUrl(mediaFile.name)
                        )
                    )
                )
                .let { content ->
                    audioFile?.let { audio ->
                        content.put(
                            JSONObject().put("type", "input_audio").put(
                                "input_audio", JSONObject()
                                    .put("data", fileToBase64(audio.absolutePath))
                                    .put("format", inferLlamaInputAudioFormat(audio.absolutePath))
                            )
                        )
                    }
                    content
                }
        )
        return executeCompletion(
            endpoint,
            json.toString().toRequestBody(JSON_MEDIA_TYPE),
            request.timeoutMinutes
        )
    }

    private suspend fun completeFrameRequest(
        target: VideoRecognitionTarget,
        endpoint: String,
        frames: List<VideoFrame>,
        request: VideoRecognitionSegmentRequest,
        audioFile: File? = null
    ): String {
        val prompt = segmentPrompt(request, frameTimestampsAbsolute = true)
        val framePaths = frames.map { it.file.absolutePath }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
        frames.forEachIndexed { index, frame ->
            content.put(
                JSONObject().put("type", "text").put(
                    "text",
                    "Frame at absolute source time ${formatSeconds(frame.timestampMs)} " +
                        "(within ${formatSeconds(request.segment.startSeconds * 1_000L)}–" +
                        "${formatSeconds((request.segment.startSeconds + request.segment.durationSeconds) * 1_000L)})."
                )
            )
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put(
                        "url", llamaVideoStreamMarker(index)
                    )
                )
            )
        }
        audioFile?.let { audio ->
            content.put(
                JSONObject().put("type", "input_audio").put(
                    "input_audio", JSONObject()
                        .put("data", fileToBase64(audio.absolutePath))
                        .put("format", inferLlamaInputAudioFormat(audio.absolutePath))
                )
            )
        }
        val json = chatEnvelope(
            model = target.remoteModel ?: "local-model",
            prompt = prompt,
            contextSize = request.contextSize,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            content = content
        ).toString()
        return executeCompletion(
            endpoint = endpoint,
            body = buildStreamingJsonRequestBody(
                json = json,
                files = framePaths,
                valuePrefix = "data:image/jpeg;base64,"
            ),
            timeoutMinutes = request.timeoutMinutes
        )
    }

    private suspend fun completeTextRequest(
        target: VideoRecognitionTarget,
        endpoint: String,
        prompt: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float,
        timeoutMinutes: Int
    ): String = executeCompletion(
        endpoint = endpoint,
        body = chatEnvelope(
            model = target.remoteModel ?: "local-model",
            prompt = prompt,
            contextSize = contextSize,
            maxTokens = maxTokens,
            temperature = temperature,
            content = prompt
        ).toString().toRequestBody(JSON_MEDIA_TYPE),
        timeoutMinutes = timeoutMinutes
    )

    private suspend fun executeCompletion(
        endpoint: String,
        body: RequestBody,
        timeoutMinutes: Int
    ): String {
        val url = HttpEndpointUrlSupport.appendPath(endpoint, "v1/chat/completions")
            ?: throw IOException("Invalid video recognition endpoint")
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .build()
        val call = http.newBuilder()
            .callTimeout(timeoutMinutes.coerceIn(1, 30).toLong(), TimeUnit.MINUTES)
            .build()
            .newCall(request)
        activeCall = call
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                if (activeCall === call) activeCall = null
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (activeCall === call) activeCall = null
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val payload = it.body?.readLimited(MAX_RESPONSE_CHARS).orEmpty()
                            if (!it.isSuccessful) {
                                throw IOException("Video model HTTP ${it.code}: ${payload.takeLast(400)}")
                            }
                            val result = parseCompletion(payload)
                            if (!continuation.isCancelled) continuation.resume(result) {}
                        }
                    } catch (error: Throwable) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    } finally {
                        if (activeCall === call) activeCall = null
                    }
                }
            })
        }
    }

    private fun chatEnvelope(
        model: String,
        prompt: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float,
        content: Any
    ): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(contextSize)))
            .put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("temperature", temperature)
            .put("max_tokens", boundedOutputTokens(contextSize, maxTokens))
            .put("messages", messages)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    }

    private fun boundedOutputTokens(contextSize: Int, requested: Int): Int {
        val contextBudget = (contextSize.coerceAtLeast(1_024) - 512).coerceAtLeast(64)
        return requested.coerceAtLeast(64).coerceAtMost(minOf(2_048, contextBudget))
    }

    private fun parseCompletion(payload: String): String {
        val json = runCatching { JSONObject(payload) }
            .getOrElse { throw IOException("Video model returned invalid JSON") }
        val message = json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw IOException(json.optString("error").ifBlank { "Video model returned no answer" })
        val raw = when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index)
                    if (part != null) append(part.optString("text"))
                    else append(content.optString(index))
                }
            }
            else -> message.optString("text")
        }
        return raw.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { throw IOException("Video model returned an empty answer") }
            .take(MAX_MODEL_TEXT_CHARS)
    }

    private fun okhttp3.ResponseBody.readLimited(limit: Int): String {
        val reader = charStream()
        val buffer = CharArray(limit + 1)
        var total = 0
        while (total < buffer.size) {
            val read = reader.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        if (total > limit) throw IOException("Video model response exceeded ${limit} characters")
        return String(buffer, 0, total)
    }

    private fun segmentPrompt(
        request: VideoRecognitionSegmentRequest,
        frameTimestampsAbsolute: Boolean
    ): String = buildString {
        append(request.prompt.ifBlank { "Describe the important visual events in this video segment." })
        append("\nWrite the answer in ")
        append(request.targetLanguage.ifBlank { "English" })
        append(". This is segment ${request.segment.index} of ${request.segment.total}, covering absolute source time ")
        append(formatSeconds(request.segment.startSeconds * 1_000L))
        append(" to ")
        append(formatSeconds((request.segment.startSeconds + request.segment.durationSeconds) * 1_000L))
        if (frameTimestampsAbsolute) {
            append(". Frame timestamps are absolute source times; cover observable events, people, objects, text, and changes in order.")
        } else {
            append(". The supplied clip starts at this segment's beginning, so any decoder timestamps are clip-relative. Convert them to the absolute source range above; cover observable events, people, objects, text, and changes in order.")
        }
    }

    private fun mergePrompt(request: VideoRecognitionMergeRequest): String = buildString {
        append(request.prompt.ifBlank { "Create a coherent visual summary of the video." })
        append("\nCombine these chronological segment observations into one concise, faithful summary in ")
        append(request.targetLanguage.ifBlank { "English" })
        append(". Preserve the absolute source-time ranges and chronology; do not mention the merge process or invent details.\n\n")
        val perObservationChars = mergeObservationCharBudget(request)
        request.summaries.forEachIndexed { index, summary ->
            val range = request.segmentRanges.getOrNull(index)
            if (range != null) {
                append("Observation ${index + 1} [absolute source time ")
                append(formatSeconds(range.startSeconds * 1_000L))
                append(" to ")
                append(formatSeconds((range.startSeconds + range.durationSeconds) * 1_000L))
                append("]:\n")
            } else {
                append("Observation ${index + 1}:\n")
            }
            append(summary.take(perObservationChars))
            append("\n\n")
        }
    }

    private fun multimodalMergePrompt(request: VideoRecognitionMultimodalMergeRequest): String = buildString {
        append(request.prompt.ifBlank { "Create a coherent summary of the supplied video evidence." })
        append("\nCombine the visual observations and the speech transcript into one faithful summary in ")
        append(request.targetLanguage.ifBlank { "English" })
        append(". Treat the transcript as speech evidence, keep uncertainty explicit, and do not invent details.\n\n")
        val perSourceChars = ((request.contextSize.coerceAtLeast(1_024) -
            boundedOutputTokens(request.contextSize, request.maxTokens) - 512).coerceAtLeast(128) * 3 / 2)
            .coerceIn(256, MAX_MERGE_OBSERVATION_CHARS)
        append("Visual observations:\n")
        append(request.visualSummary.take(perSourceChars))
        append("\n\nSpeech transcript:\n")
        append(request.transcript.take(perSourceChars))
    }

    private fun mergeObservationCharBudget(request: VideoRecognitionMergeRequest): Int {
        val outputReserve = boundedOutputTokens(request.contextSize, request.maxTokens)
        val inputBudgetTokens = (request.contextSize.coerceAtLeast(1_024) - outputReserve - 512)
            .coerceAtLeast(128)
        return ((inputBudgetTokens * 3) / request.summaries.size.coerceAtLeast(1))
            .coerceIn(256, MAX_MERGE_OBSERVATION_CHARS)
    }

    private fun systemPrompt(contextSize: Int): String =
        "You are a visual video recognition model. Use only the supplied media and return a direct answer. Context budget: $contextSize tokens."

    private fun operationDirectory(request: VideoRecognitionSegmentRequest): File = File(
        appContext.cacheDir,
        "video_recognition_runtime/${sanitize(request.sessionKey)}/segment_${request.segment.index}"
    )

    private fun isSessionRunning(sessionId: String): Boolean =
        LlamaServerSessionStateStore(appContext).readAll()
            .firstOrNull { it.sessionId == sessionId }
            ?.isRunning == true

    private fun stopOwnedSessions() {
        ownedSessions.values.toList().forEach(::stopSession)
        ownedSessions.clear()
    }

    private fun stopSession(session: OwnedSession) {
        runCatching { LlamaServerLauncher.stopSession(appContext, session.sessionId) }
        session.directories?.cleanup()
    }

    private fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

    private fun stableSessionId(target: VideoRecognitionTarget): String =
        "video-recognition:${target.id.hashCode().toUInt().toString(16)}"

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "video" }

    private fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.3f", milliseconds.coerceAtLeast(0L) / 1_000.0)

    private fun boundedSegmentFps(request: VideoRecognitionSegmentRequest): Float =
        minOf(
            request.maxFps.coerceIn(0.1f, NativeLlamaVideoSupport.MAX_VIDEO_FPS),
            request.maxFrames.coerceIn(1, NativeLlamaVideoSupport.MAX_FRAMES_PER_SEGMENT).toFloat() /
                request.segment.durationSeconds.coerceAtLeast(1).toFloat()
        ).coerceIn(0.1f, NativeLlamaVideoSupport.MAX_VIDEO_FPS)

    private fun formatFps(value: Float): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private data class OwnedSession(
        val sessionId: String,
        val port: Int,
        val directories: LlamaVideoRuntimeDirectories?
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_CHARS = 1_048_576
        const val MAX_MODEL_TEXT_CHARS = 12_000
        const val MAX_MERGE_OBSERVATION_CHARS = 3_500
    }
}
