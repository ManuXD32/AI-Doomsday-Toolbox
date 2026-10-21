package com.example.llamadroid.service

import android.media.MediaMetadataRetriever
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.util.DebugLog
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

// Native MTMD defaults to 2 fps. Keeping direct input to twelve seconds caps the
// decoded frame budget at 24 even when a remote server has no app-managed fps
// profile. Longer videos go through the bounded, sequential recognition path.
internal const val NATIVE_CHAT_MAX_DIRECT_VIDEO_DURATION_MS = MAX_DIRECT_VIDEO_DURATION_MS
internal const val NATIVE_CHAT_VIDEO_MAX_TOKENS = 512
internal const val NATIVE_CHAT_VIDEO_TIMEOUT_MINUTES = 10
internal const val NATIVE_CHAT_VIDEO_WHOLE_TIMEOUT_MS = 60L * 60L * 1_000L
internal const val NATIVE_CHAT_VIDEO_MERGE_OBSERVATION_CHARS = 3_500
internal const val NATIVE_CHAT_VIDEO_QUESTION_MAX_CHARS = 2_000
internal const val NATIVE_CHAT_VIDEO_TRANSCRIPT_MAX_CHARS = 12_000
internal const val NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS = 120

internal data class NativeChatVideoPayload(
    val streamVideo: Boolean = false,
    val localVideoData: String? = null,
    val sampledFramePaths: List<String> = emptyList(),
    val samplingDisclosure: String? = null,
    val videoObservation: String? = null,
    /** Transient, extracted audio; never written back to the chat message. */
    val videoAudioPath: String? = null
)

internal data class NativeChatVideoPreparation(
    val payloads: List<NativeChatVideoPayload>,
    val streamedMediaPaths: List<String>,
    val cleanupRoot: File?,
    val stagedRuntimeFiles: List<File>,
    val transientAudioRoot: File? = null
) {
    val hasDirectVideo: Boolean
        get() = payloads.any {
            !it.localVideoData.isNullOrBlank() || it.streamVideo
        }

    fun cleanup() {
        cleanupRoot?.deleteRecursively()
        transientAudioRoot?.deleteRecursively()
        stagedRuntimeFiles.forEach { file -> file.delete() }
    }
}

internal data class NativeChatVideoObservation(
    val text: String,
    val range: VideoRecognitionSegment
)


/**
 * Prepare the representation sent to Native Chat while retaining every original
 * attachment path in the conversation. Managed local runtimes can send a short clip
 * directly through their private media directory. Remote-compatible endpoints receive a
 * separately normalized short clip through a streaming body, while longer clips are
 * recognized in sequential, bounded segments against the selected server and the resulting
 * observations are injected as text into this request. This keeps remote requests free of
 * local paths and prevents an arbitrary remote fps setting from decoding a whole long source
 * in one chat request.
 */
internal suspend fun LlamaClientService.prepareNativeChatVideoPayloads(
    history: List<LlamaMessageEntity>,
    chatId: Long,
    server: LlamaServerEntity,
    contextSize: Int,
    params: Map<String, Any>,
    taskId: Int,
    progress: StreamingProgress,
    forceBoundedVideo: Boolean = false
): NativeChatVideoPreparation {
    if (history.none { !it.videoPath.isNullOrBlank() }) {
        return NativeChatVideoPreparation(
            payloads = List(history.size) { NativeChatVideoPayload() },
            streamedMediaPaths = emptyList(),
            cleanupRoot = null,
            transientAudioRoot = null,
            stagedRuntimeFiles = emptyList()
        )
    }
    val policy = videoSettingsRepo.snapshot().processingPolicy
    val wantsIndependentWhisper = when (policy.mode) {
        VideoProcessingMode.VISUAL_WHISPER,
        VideoProcessingMode.LEGACY_TRANSCRIPT -> true
        VideoProcessingMode.AUTO_MULTIMODAL -> policy.nativeChatParallelWhisperEnabled
    }
    val payloads = mutableListOf<NativeChatVideoPayload>()
    val streamedMediaPaths = mutableListOf<String>()
    val stagedRuntimeFiles = mutableListOf<File>()
    var transientAudioRoot: File? = null
    val canUseManagedLocalRuntime = server.isLlamaServerEngine() &&
        isNativeChatLoopbackHost(server.host)
    var recognitionRuntime: NativeVideoRecognitionRuntime? = null
    var mediaDependenciesChecked = false
    var mediaDependenciesAvailable = false
    var streamingRoot: File? = null
    val canUseSafeDirectStream = policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
        server.allowsSafeNativeChatVideoStream() &&
        NativeLlamaVideoSupport.activeRuntimeDirectories(
            context = applicationContext,
            serverPort = server.port
        ) != null
    val canAttachVideoAudio = policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
        policy.directAudioEnabled &&
        server.allowsNativeChatVideoAudio()

    suspend fun prepareVideoAudio(source: File, durationMs: Long, messageId: Long): String? {
        if (!canAttachVideoAudio ||
            nativeLlamaVideoAudioDurationMs(durationMs) == null
        ) return null
        currentCoroutineContext().ensureActive()
        val outputDirectory = transientAudioRoot ?: File(
            applicationContext.cacheDir,
            "native_chat_video_audio/${chatId}_${System.nanoTime()}"
        ).also {
            require(it.mkdirs() || it.isDirectory) {
                "Unable to create transient video audio directory"
            }
            transientAudioRoot = it
        }
        return extractNativeLlamaVideoAudio(
            context = applicationContext,
            videoPath = source.absolutePath,
            outputDirectory = outputDirectory,
            durationMs = durationMs,
            preferredName = "chat_${messageId}_${source.name}"
        ).fold(
            onSuccess = { path -> path },
            onFailure = { error ->
                // Audio is an optional companion to visual input. Keep a
                // valid video request when extraction is unavailable or
                // the source has no audio stream.
                DebugLog.log(
                    "LlamaClientService: video audio unavailable; continuing video-only: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                null
            }
        )
    }
    var visualFraction = 0f
    var audioFraction = 0f
    var parallelWhisperJob: Deferred<String?>? = null
    var parallelWhisperPayloadIndex: Int? = null
    var parallelWhisperMerged = false
    fun publishVideoProgress(
        visual: Float = visualFraction,
        audio: Float = audioFraction,
        visualPhase: String? = null,
        audioPhase: String? = null
    ) {
        visualFraction = visual.coerceIn(0f, 1f)
        audioFraction = audio.coerceIn(0f, 1f)
        publishNativeChatVideoProgress(
            chatId = chatId,
            taskId = taskId,
            progress = progress,
            visualFraction = visualFraction,
            audioFraction = audioFraction,
            visualPhase = visualPhase,
            audioPhase = audioPhase,
            audioExpected = wantsIndependentWhisper
        )
    }

    if (policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
        policy.directAudioEnabled &&
        !canAttachVideoAudio
    ) {
        publishVideoProgress(
            audioPhase = getString(R.string.video_recognition_audio_unavailable)
        )
    }

    suspend fun startParallelWhisper(source: File): Deferred<String?>? {
        if (!wantsIndependentWhisper) return null
        val modelPath = resolveWhisperModelPath(server)
        if (modelPath.isNullOrBlank()) {
            publishVideoProgress(
                audio = 0f,
                audioPhase = getString(R.string.video_recognition_audio_unavailable)
            )
            return null
        }
        return serviceScope.async {
            runCatching {
                VideoRecognitionWhisperTranscriber(
                    context = applicationContext,
                    runId = System.nanoTime()
                ).transcribe(
                    sourceFile = source,
                    request = VideoRecognitionAudioFallbackRequest(
                        whisperModelPath = modelPath,
                        language = server.whisperLanguage.ifBlank {
                            LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE
                        },
                        threads = settingsRepo.whisperThreads.value,
                        vadConfig = settingsRepo.whisperVadConfigSnapshot(),
                        settingsOverride = null,
                        saveToNotes = false
                    )
                ) { label, fraction ->
                    publishVideoProgress(audio = fraction, audioPhase = label)
                }
            }.onFailure { error ->
                DebugLog.log(
                    "LlamaClientService: Native Chat Whisper video branch failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                publishVideoProgress(
                    audio = 0f,
                    audioPhase = getString(R.string.video_recognition_audio_unavailable)
                )
            }.getOrNull()
        }
    }

    val latestUserQuestion = history
        .asReversed()
        .firstOrNull { it.role == "user" }
        ?.content
        ?.trim()
        ?.take(NATIVE_CHAT_VIDEO_QUESTION_MAX_CHARS)
    val target = VideoRecognitionTarget.fromServer(server)
    if (!policy.usesLegacyTranscriptRoute() && target == null) {
        throw IllegalStateException(getString(R.string.llama_video_unsupported))
    }
    try {
        history.forEach { message ->
            val sourcePath = message.videoPath?.takeIf { it.isNotBlank() }
            if (sourcePath == null) {
                payloads += NativeChatVideoPayload()
                return@forEach
            }

            val source = File(sourcePath)
            val durationMs = readNativeChatVideoDurationMs(source)
                ?: throw IllegalStateException(getString(R.string.llama_video_duration_unavailable))
            if (parallelWhisperJob == null) {
                parallelWhisperJob = startParallelWhisper(source)
                if (parallelWhisperJob != null) {
                    parallelWhisperPayloadIndex = payloads.size
                }
            }

            if (policy.usesLegacyTranscriptRoute()) {
                val transcript = parallelWhisperJob?.await()?.trim()
                    ?.take(NATIVE_CHAT_VIDEO_TRANSCRIPT_MAX_CHARS)
                if (transcript.isNullOrBlank()) {
                    throw IllegalStateException(getString(R.string.llama_video_whisper_unavailable))
                }
                payloads += NativeChatVideoPayload(
                    videoObservation = getString(
                        R.string.llama_video_transcript_disclosure,
                        transcript
                    )
                )
                parallelWhisperMerged = true
                return@forEach
            }

            if (!forceBoundedVideo &&
                policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
                durationMs <= NATIVE_CHAT_MAX_DIRECT_VIDEO_DURATION_MS &&
                canUseManagedLocalRuntime
            ) {
                if (!mediaDependenciesChecked) {
                    val availability = NativeVideoDependencies.ensure(
                        context = applicationContext,
                        requireLlm = false
                    )
                    mediaDependenciesAvailable = availability.mediaAvailable
                    mediaDependenciesChecked = true
                }
                if (mediaDependenciesAvailable) {
                    val staged = try {
                        val mediaDirectory = NativeLlamaVideoSupport.activeMediaDirectory(
                            context = applicationContext,
                            serverPort = server.port
                        ) ?: throw IllegalStateException(
                            "No active native video runtime is available"
                        )
                        stageBoundedLocalChatVideoForRuntime(
                            source = source,
                            mediaDirectory = mediaDirectory,
                            durationMs = durationMs,
                            preferredName = "chat_${message.id}_${source.name}",
                            maxFrames = policy.maxFrames,
                            maxFps = policy.maxFps
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        DebugLog.log(
                            "LlamaClientService: local video staging unavailable; " +
                                "falling back to bounded recognition: ${error.message}"
                        )
                        null
                    }
                    if (staged != null) {
                        stagedRuntimeFiles += staged.file
                        val videoAudioPath = prepareVideoAudio(source, durationMs, message.id)
                        if (policy.directAudioEnabled && canAttachVideoAudio && videoAudioPath == null) {
                            publishVideoProgress(
                                audioPhase = getString(R.string.video_recognition_audio_unavailable)
                            )
                        }
                        payloads += NativeChatVideoPayload(
                            localVideoData = staged.relativeFileUrl,
                            videoAudioPath = videoAudioPath
                        )
                        publishVideoProgress(
                            visual = 1f,
                            visualPhase = getString(R.string.video_recognition_progress_complete)
                        )
                        return@forEach
                    }
                }
            }

            // Remote rows do not expose their native decoder FPS, so they skip inline
            // input_video and use the bounded timestamped-frame runtime path below. The
            // streaming branch is reserved for an app-managed loopback profile whose FPS
            // is verified.
            if (!forceBoundedVideo &&
                policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
                canUseSafeDirectStream &&
                durationMs <= NATIVE_CHAT_MAX_DIRECT_VIDEO_DURATION_MS
            ) {
                if (!mediaDependenciesChecked) {
                    val availability = NativeVideoDependencies.ensure(
                        context = applicationContext,
                        requireLlm = false
                    )
                    mediaDependenciesAvailable = availability.mediaAvailable
                    mediaDependenciesChecked = true
                }
                if (mediaDependenciesAvailable) {
                    val staged = try {
                        val root = streamingRoot ?: File(
                            applicationContext.cacheDir,
                            "native_chat_video_stream/${chatId}_${System.nanoTime()}"
                        ).also {
                            require(it.mkdirs() || it.isDirectory) {
                                "Unable to create Native Chat streaming directory"
                            }
                            streamingRoot = it
                        }
                        val mediaDirectory = File(root, "message_${message.id}")
                        stageBoundedLocalChatVideoForRuntime(
                            source = source,
                            mediaDirectory = mediaDirectory,
                            durationMs = durationMs,
                            preferredName = "chat_${message.id}_${source.name}",
                            maxFrames = policy.maxFrames,
                            maxFps = policy.maxFps
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        DebugLog.log(
                            "LlamaClientService: normalized remote video staging unavailable; " +
                                "falling back to bounded recognition: ${error.message}"
                        )
                        null
                    }
                    if (staged != null) {
                        stagedRuntimeFiles += staged.file
                        streamedMediaPaths += staged.file.absolutePath
                        val videoAudioPath = prepareVideoAudio(source, durationMs, message.id)
                        if (policy.directAudioEnabled && canAttachVideoAudio && videoAudioPath == null) {
                            publishVideoProgress(
                                audioPhase = getString(R.string.video_recognition_audio_unavailable)
                            )
                        }
                        payloads += NativeChatVideoPayload(
                            streamVideo = true,
                            videoAudioPath = videoAudioPath
                        )
                        publishVideoProgress(
                            visual = 1f,
                            visualPhase = getString(R.string.video_recognition_progress_complete)
                        )
                        return@forEach
                    }
                }
            }

            if (!mediaDependenciesChecked) {
                val availability = NativeVideoDependencies.ensure(
                    context = applicationContext,
                    requireLlm = false
                )
                mediaDependenciesAvailable = availability.mediaAvailable
                DebugLog.log(
                    "LlamaClientService: video media dependencies available=" +
                        availability.mediaAvailable
                )
                mediaDependenciesChecked = true
            }
            val runtime = recognitionRuntime ?: NativeVideoRecognitionRuntime(
                applicationContext
            ).also { recognitionRuntime = it }
            val observations = withTimeout(NATIVE_CHAT_VIDEO_WHOLE_TIMEOUT_MS) {
                summarizeNativeChatVideo(
                    runtime = runtime,
                    target = requireNotNull(target),
                    source = source,
                    chatId = chatId,
                    messageId = message.id,
                    durationMs = durationMs,
                    contextSize = contextSize,
                    params = params,
                    latestUserQuestion = latestUserQuestion,
                    policy = policy,
                    parallelWhisperJob = parallelWhisperJob,
                    onProgress = { visual, phase ->
                        publishVideoProgress(visual = visual, visualPhase = phase)
                    }
                )
            }
            parallelWhisperMerged = parallelWhisperJob != null
            payloads += NativeChatVideoPayload(
                videoObservation = getString(
                    R.string.llama_video_observation_disclosure,
                    observations
                )
            )
        }
        if (parallelWhisperJob != null && !parallelWhisperMerged) {
            val transcript = parallelWhisperJob?.await()
                ?.trim()
                ?.take(NATIVE_CHAT_VIDEO_TRANSCRIPT_MAX_CHARS)
            if (!transcript.isNullOrBlank()) {
                val payloadIndex = parallelWhisperPayloadIndex
                if (payloadIndex != null && payloadIndex in payloads.indices) {
                    val payload = payloads[payloadIndex]
                    payloads[payloadIndex] = payload.copy(
                        videoObservation = listOf(
                            payload.videoObservation,
                            getString(
                                R.string.llama_video_transcript_disclosure,
                                transcript
                            )
                        ).filter { !it.isNullOrBlank() }.joinToString("\n\n")
                    )
                }
                parallelWhisperMerged = true
                publishVideoProgress(
                    audio = 1f,
                    audioPhase = getString(R.string.video_recognition_audio_complete),
                    visual = visualFraction
                )
            }
        }
        publishVideoProgress(
            visual = 1f,
            audio = if (parallelWhisperJob != null) audioFraction else 0f,
            visualPhase = getString(R.string.video_recognition_progress_complete)
        )
        return NativeChatVideoPreparation(
            payloads = payloads,
            streamedMediaPaths = streamedMediaPaths,
            cleanupRoot = streamingRoot,
            transientAudioRoot = transientAudioRoot,
            stagedRuntimeFiles = stagedRuntimeFiles.toList()
        )
    } catch (error: Throwable) {
        parallelWhisperJob?.cancel()
        stagedRuntimeFiles.forEach { file -> file.delete() }
        streamingRoot?.deleteRecursively()
        transientAudioRoot?.deleteRecursively()
        throw error
    } finally {
        recognitionRuntime?.shutdown()
    }
}

/**
 * Recognize each globally configured segment (capped to twelve seconds for Native Chat) in
 * order using the exact selected server endpoint,
 * then merge observations hierarchically with that same model. The runtime receives a
 * REMOTE_SERVER target even for a managed localhost server, so this path never launches
 * a second local model session for a Native Chat turn.
 */
internal suspend fun LlamaClientService.summarizeNativeChatVideo(
    runtime: NativeVideoRecognitionRuntime,
    target: VideoRecognitionTarget,
    source: File,
    chatId: Long,
    messageId: Long,
    durationMs: Long,
    contextSize: Int,
    params: Map<String, Any>,
    latestUserQuestion: String?,
    policy: VideoProcessingPolicy,
    parallelWhisperJob: Deferred<String?>? = null,
    onProgress: (visualFraction: Float, phase: String) -> Unit = { _, _ -> }
): String {
    // Native MTMD accepts at most a twelve-second direct clip. Longer videos are segmented
    // client-side even when the global policy allows larger Video Summary windows.
    val segmentSeconds = policy.segmentSeconds.coerceAtMost(
        NATIVE_CHAT_MAX_DIRECT_VIDEO_DURATION_MS.toInt() / 1_000
    )
    val segmentMaxFramesSetting = policy.maxFrames
    val segmentMaxFps = policy.maxFps
    val includeSegmentAudio = policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
        policy.directAudioEnabled && target.supportsAudio
    val mediaProcessor = VideoMediaProcessor(
        context = applicationContext,
        policy = NativeLlamaVideoPolicy(
            maxSegmentDurationMs = LlamaVideoProfileLimits
                .normalizeSegmentSeconds(segmentSeconds) * 1_000L,
            maxFramesPerSegment = LlamaVideoProfileLimits
                .normalizeMaxFrames(segmentMaxFramesSetting),
            maxFps = LlamaVideoProfileLimits.normalizeMaxFps(segmentMaxFps),
            defaultFps = LlamaVideoProfileLimits.normalizeMaxFps(segmentMaxFps)
        )
    )
    val segments = mediaProcessor.segmentPlan(durationMs)
    if (segments.isEmpty()) {
        throw IllegalStateException(getString(R.string.llama_video_sampling_failed))
    }
    val targetLanguage = if (Locale.getDefault().language.equals("es", ignoreCase = true)) {
        "Spanish"
    } else {
        "English"
    }
    val prompt = buildString {
        append(getString(R.string.llama_video_segment_prompt))
        latestUserQuestion
            ?.takeIf { it.isNotBlank() }
            ?.let { question ->
                append("\nCurrent/latest user question: ")
                append(question)
                append("\nPrioritize observations that help answer that question.")
            }
    }
    val maxTokens = nativeChatMaxOutputTokens(params)
        ?.coerceIn(128, NATIVE_CHAT_VIDEO_MAX_TOKENS)
        ?: NATIVE_CHAT_VIDEO_MAX_TOKENS
    val segmentMaxFrames = boundedVideoFrameCount(
        contextSize = contextSize,
        maxTokens = maxTokens,
        requestedFrames = segmentMaxFramesSetting
    )
    val temperature = (params["temperature"] as? Number)
        ?.toFloat()
        ?.coerceIn(0f, 1f)
        ?: 0.2f
    val summaries = mutableListOf<NativeChatVideoObservation>()
    segments.forEach { segment ->
        currentCoroutineContext().ensureActive()
        val startSeconds = (segment.startMs / 1_000L).toInt()
        val durationSeconds = ((segment.durationMs + 999L) / 1_000L)
            .toInt()
            .coerceIn(1, NativeLlamaVideoSupport.MAX_SEGMENT_DURATION_MS.toInt() / 1_000)
        val segmentRange = VideoRecognitionSegment(
            // VideoMediaProcessor uses zero-based indexes; the runtime transport and
            // progress contract use one-based segment numbers.
            index = segment.index + 1,
            total = segments.size,
            startSeconds = startSeconds,
            durationSeconds = durationSeconds
        )
        val segmentRequest = VideoRecognitionSegmentRequest(
            sessionKey = "native-chat-video:$chatId:$messageId:${target.id}",
            target = target,
            sourceFile = source,
            sourceName = source.name,
            segment = segmentRange,
            maxFrames = segmentMaxFrames,
            maxFps = segmentMaxFps,
            targetLanguage = targetLanguage,
            prompt = prompt,
            contextSize = contextSize.takeIf { it > 0 } ?: target.preferredContextSize,
            maxTokens = maxTokens,
            temperature = temperature,
            timeoutMinutes = NATIVE_CHAT_VIDEO_TIMEOUT_MINUTES,
            includeAudio = includeSegmentAudio
        )
        // The selected server is represented as a REMOTE_SERVER target even when it is
        // localhost. The shared runtime therefore extracts timestamped JPEGs and bounds
        // the remote request before it is sent; it does not expose the Android source path
        // or let an arbitrary server-side fps setting decode the whole clip.
        val summary = runtime.summarizeSegment(segmentRequest) { label, fraction ->
            val completed = ((segment.index + fraction.coerceIn(0f, 1f)) / segments.size)
                .coerceIn(0f, 1f)
            onProgress(completed, label.take(NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS))
        }.trim()
        if (summary.isBlank()) {
            throw IllegalStateException(getString(R.string.llama_video_sampling_failed))
        }
        summaries += NativeChatVideoObservation(
            text = summary.take(NATIVE_CHAT_VIDEO_MERGE_OBSERVATION_CHARS),
            range = segmentRange
        )
    }

    var level = summaries.toList()
    var round = 0
    while (level.size > 1) {
        currentCoroutineContext().ensureActive()
        round += 1
        val batches = level.chunked(4)
        level = batches.mapIndexed { batchIndex, batch ->
            if (batch.size == 1) {
                batch.single()
            } else {
                val mergeObservationLimit = nativeChatMergeObservationLimit(
                    contextSize = contextSize,
                    maxTokens = maxTokens,
                    observationCount = batch.size
                )
                val merged = runtime.mergeSummaries(
                    VideoRecognitionMergeRequest(
                        sessionKey = "native-chat-video:$chatId:$messageId:${target.id}",
                        target = target,
                        sourceName = source.name,
                        summaries = batch.map {
                            it.text.take(mergeObservationLimit)
                        },
                        round = round,
                        batchIndex = batchIndex,
                        targetLanguage = targetLanguage,
                        prompt = prompt,
                        contextSize = contextSize.takeIf { it > 0 } ?: target.preferredContextSize,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        timeoutMinutes = NATIVE_CHAT_VIDEO_TIMEOUT_MINUTES,
                        segmentRanges = batch.map { it.range }
                    )
                ) { label, fraction ->
                    onProgress(
                        (0.9f + ((batchIndex + fraction.coerceIn(0f, 1f)) / batches.size) * 0.1f)
                            .coerceIn(0f, 1f),
                        label.take(NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS)
                    )
                }.trim()
                if (merged.isBlank()) {
                    throw IllegalStateException(getString(R.string.llama_video_sampling_failed))
                }
                NativeChatVideoObservation(
                    text = merged.take(NATIVE_CHAT_VIDEO_MERGE_OBSERVATION_CHARS),
                    range = mergeNativeChatVideoRanges(batch.map { it.range })
                )
            }
        }
    }
    val visualSummary = level.single().text
    val transcript = parallelWhisperJob?.await()
        ?.trim()
        ?.take(NATIVE_CHAT_VIDEO_TRANSCRIPT_MAX_CHARS)
    if (transcript.isNullOrBlank()) return visualSummary

    onProgress(0.98f, getString(R.string.video_recognition_progress_multimodal_merge))
    val merged = runtime.mergeMultimodalSummary(
        VideoRecognitionMultimodalMergeRequest(
            sessionKey = "native-chat-video:$chatId:$messageId:${target.id}",
            target = target,
            sourceName = source.name,
            visualSummary = visualSummary,
            transcript = transcript,
            targetLanguage = targetLanguage,
            prompt = prompt,
            contextSize = contextSize.takeIf { it > 0 } ?: target.preferredContextSize,
            maxTokens = maxTokens,
            temperature = temperature,
            timeoutMinutes = NATIVE_CHAT_VIDEO_TIMEOUT_MINUTES
        )
    ) { label, fraction ->
        onProgress(
            (0.98f + fraction.coerceIn(0f, 1f) * 0.02f).coerceIn(0f, 1f),
            label.take(NATIVE_CHAT_VIDEO_PHASE_MAX_CHARS)
        )
    }.trim()
    return merged.ifBlank { visualSummary }
}

/** Keep merge input inside the selected context after reserving output/system tokens. */
internal fun LlamaClientService.nativeChatMergeObservationLimit(
    contextSize: Int,
    maxTokens: Int,
    observationCount: Int
): Int {
    val availableInputTokens = (
        contextSize.coerceAtLeast(1_024) - maxTokens.coerceAtLeast(64) - 512
        ).coerceAtLeast(128)
    return ((availableInputTokens * 4) / observationCount.coerceAtLeast(1))
        .coerceIn(256, NATIVE_CHAT_VIDEO_MERGE_OBSERVATION_CHARS)
}

internal fun LlamaClientService.mergeNativeChatVideoRanges(
    ranges: List<VideoRecognitionSegment>
): VideoRecognitionSegment {
    require(ranges.isNotEmpty()) { "At least one video range is required" }
    val first = ranges.first()
    val last = ranges.last()
    val endSeconds = (last.startSeconds + last.durationSeconds).coerceAtLeast(
        first.startSeconds + first.durationSeconds
    )
    return first.copy(
        durationSeconds = (endSeconds - first.startSeconds).coerceAtLeast(1)
    )
}

internal suspend fun LlamaClientService.readNativeChatVideoDurationMs(source: File): Long? =
    withContext(Dispatchers.IO) {
        if (!source.isFile || !source.canRead()) return@withContext null
        try {
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
    }

/**
 * Normalize a short local clip before placing it in llama-server's private media root.
 * A duration cap alone is insufficient for a 4K source; this encoder bounds both the
 * decoded frame count and the largest frame edge before native MTMD sees the file.
 */
internal suspend fun LlamaClientService.stageBoundedLocalChatVideoForRuntime(
    source: File,
    mediaDirectory: File,
    durationMs: Long,
    preferredName: String,
    maxFrames: Int,
    maxFps: Float
): StagedVideoMedia = withContext(Dispatchers.IO) {
    require(durationMs in 1L..NATIVE_CHAT_MAX_DIRECT_VIDEO_DURATION_MS) {
        "Direct Native Chat video duration is outside the bounded range"
    }
    val ffmpeg = BinaryRepository(applicationContext).getFFmpegBinary()
        ?.takeIf { it.isFile }
        ?: throw IOException("Packaged ffmpeg binary is unavailable")
    require(mediaDirectory.mkdirs() || mediaDirectory.isDirectory) {
        "Unable to create native video media directory"
    }
    val durationSeconds = durationMs / 1_000.0
    val normalizedMaxFrames = VideoRecognitionLimits.normalizeMaxFrames(maxFrames)
    val normalizedMaxFps = VideoRecognitionLimits.normalizeMaxFps(maxFps)
    val frameLimit = ceil(durationSeconds * normalizedMaxFps)
        .toInt()
        .coerceIn(1, normalizedMaxFrames)
    val boundedFps = minOf(
        normalizedMaxFps,
        frameLimit / durationSeconds.toFloat()
    ).coerceIn(0.1f, normalizedMaxFps)
    val safeStem = NativeLlamaVideoSupport.sanitizeMediaFileName(preferredName)
        .substringBeforeLast('.', preferredName)
    val output = File(
        mediaDirectory,
        "${safeStem}_${System.nanoTime().toString(16)}.mp4"
    )
    val command = listOf(
        ffmpeg.absolutePath,
        "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-ss", "0",
        "-i", source.absolutePath,
        "-t", String.format(Locale.US, "%.3f", durationSeconds),
        "-vf",
        "scale=${NativeLlamaVideoSupport.MAX_VIDEO_FRAME_DIMENSION}:" +
            "${NativeLlamaVideoSupport.MAX_VIDEO_FRAME_DIMENSION}:" +
            "force_original_aspect_ratio=decrease:force_divisible_by=2," +
            "fps=${NativeLlamaVideoSupport.formatFps(boundedFps)}",
        "-frames:v", frameLimit.toString(),
        "-fps_mode", "vfr",
        "-an", "-c:v", "mpeg4", "-q:v", "4",
        output.absolutePath
    )
    try {
        val result = BoundedVideoProcessRunner().run(
            command = command,
            workingDirectory = mediaDirectory,
            timeoutMs = 120_000L
        )
        if (result.exitCode != 0 || !output.isFile || output.length() <= 0L) {
            throw IOException("Unable to normalize video for Native Chat: ${result.output.takeLast(240)}")
        }
        StagedVideoMedia(
            file = output,
            mediaDirectory = mediaDirectory,
            relativeFileUrl = NativeLlamaVideoSupport.relativeFileUrl(output.name)
        )
    } catch (cancelled: CancellationException) {
        output.delete()
        throw cancelled
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}
