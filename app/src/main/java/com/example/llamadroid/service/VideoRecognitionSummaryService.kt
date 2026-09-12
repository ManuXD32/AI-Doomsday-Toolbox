package com.example.llamadroid.service

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.VideoRecognitionBundleCatalog
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A local curated bundle or a remote endpoint explicitly confirmed to accept video. */
data class VideoRecognitionTarget(
    val id: String,
    val label: String,
    val kind: Kind,
    val modelPath: String? = null,
    val mmprojPath: String? = null,
    val remoteEndpoint: String? = null,
    val remoteModel: String? = null,
    val remoteVideoEnabled: Boolean = false,
    val remoteServerId: Long? = null,
    val remoteEngine: String = LlamaServerEntity.ENGINE_LLAMA_SERVER,
    val preferredContextSize: Int = VideoRecognitionLimits.DEFAULT_CONTEXT_SIZE,
    val runtimeKey: String = "video-recognition:$id",
    /** True only when the bundle/server explicitly declares direct audio input support. */
    val supportsAudio: Boolean = false,
    /** Decoded server-owned launch policy, when this target came from a managed server row. */
    val launchProfile: LlamaServerLaunchProfile? = null
) {
    enum class Kind { LOCAL_BUNDLE, REMOTE_SERVER }

    val isLocal: Boolean get() = kind == Kind.LOCAL_BUNDLE
    val isRemote: Boolean get() = kind == Kind.REMOTE_SERVER

    /** Local input_video requires both the model and multimodal projector. */
    fun isConfigured(): Boolean = when (kind) {
        Kind.LOCAL_BUNDLE -> {
            !modelPath.isNullOrBlank() && File(modelPath).isFile &&
                !mmprojPath.isNullOrBlank() && File(mmprojPath).isFile
        }
        Kind.REMOTE_SERVER -> remoteVideoEnabled &&
            !remoteEndpoint.isNullOrBlank() && !remoteModel.isNullOrBlank()
    }

    companion object {
        fun fromCuratedBundle(
            bundle: CuratedModelBundle,
            installedModels: List<ModelEntity>
        ): VideoRecognitionTarget? {
            val policy = bundle.videoPolicy ?: return null
            val model = installedModels.firstOrNull { it.type == ModelType.LLM }
                ?: return null
            val projector = installedModels.firstOrNull {
                it.type == ModelType.VISION_PROJECTOR || it.type == ModelType.MMPROJ
            } ?: return null
            return VideoRecognitionTarget(
                id = bundle.id,
                label = bundle.id,
                kind = Kind.LOCAL_BUNDLE,
                modelPath = model.path,
                mmprojPath = projector.path,
                preferredContextSize = policy.recommendedContext,
                runtimeKey = "video-recognition:local:${bundle.id}",
                supportsAudio = policy.supportsVideoAudio
            )
        }

        fun remote(
            endpoint: String,
            model: String,
            videoEnabled: Boolean,
            label: String = model,
            serverId: Long? = null,
            engine: String = LlamaServerEntity.ENGINE_LLAMA_SERVER,
            supportsAudio: Boolean = false
        ): VideoRecognitionTarget = VideoRecognitionTarget(
            id = serverId?.let { "remote-server:$it" }
                ?: "remote:${endpoint.trim()}|${model.trim()}",
            label = label,
            kind = Kind.REMOTE_SERVER,
            remoteEndpoint = endpoint.trim().trimEnd('/'),
            remoteModel = model.trim(),
            remoteVideoEnabled = videoEnabled,
            remoteServerId = serverId,
            remoteEngine = engine,
            runtimeKey = "video-recognition:remote:${endpoint.trim().trimEnd('/')}",
            supportsAudio = supportsAudio
        )

        fun fromServer(server: LlamaServerEntity): VideoRecognitionTarget? {
            if (!server.supportsVideo || !server.usesOpenAiCompatibleEngine()) return null
            val endpoint = server.baseUrl().trimEnd('/')
            val profile = LlamaServerLaunchProfile.decode(server.localLaunchProfileJson)
            return remote(
                endpoint = endpoint,
                model = server.modelName?.trim().orEmpty().ifBlank { "local-model" },
                videoEnabled = profile?.videoEnabled != false,
                label = server.name,
                serverId = server.id,
                engine = server.normalizedEngine(),
                // Capability comes only from the server's explicit audio declaration and its
                // current direct-input preference. The global policy decides whether to attach it.
                supportsAudio = server.supportsDirectAudioInput()
            ).copy(
                preferredContextSize = profile?.contextSize?.takeIf { it > 0 }
                    ?: VideoRecognitionLimits.DEFAULT_CONTEXT_SIZE,
                launchProfile = profile
            )
        }
    }
}

data class VideoRecognitionTargetSelection(
    val target: VideoRecognitionTarget?,
    val savedTargetMissing: Boolean
)

/** Selection follows a saved target, then the only installed target, then the recommended bundle. */
object VideoRecognitionTargetSelector {
    fun select(
        savedTargetId: String?,
        installedTargets: List<VideoRecognitionTarget>
    ): VideoRecognitionTarget? = selectLocal(savedTargetId, installedTargets).target

    fun selectLocal(
        savedTargetId: String?,
        installedTargets: List<VideoRecognitionTarget>
    ): VideoRecognitionTargetSelection {
        val usable = installedTargets.filter {
            it.kind == VideoRecognitionTarget.Kind.LOCAL_BUNDLE && it.isConfigured()
        }
        if (savedTargetId != null) {
            if (savedTargetId.startsWith("remote-server:")) {
                return VideoRecognitionTargetSelection(target = null, savedTargetMissing = true)
            }
            usable.firstOrNull { it.id == savedTargetId }?.let {
                return VideoRecognitionTargetSelection(it, savedTargetMissing = false)
            }
            return VideoRecognitionTargetSelection(target = null, savedTargetMissing = true)
        }
        if (usable.size == 1) return VideoRecognitionTargetSelection(usable.single(), false)
        val recommended = usable.firstOrNull { target ->
            VideoRecognitionBundleCatalog.bundles.any { it.id == target.id && it.videoPolicy?.recommended == true }
        }
        val deterministic = listOf(
            "video-qwen25vl-3b",
            "video-smolvlm2-500m",
            "video-qwen25vl-7b"
        ).asSequence()
            .mapNotNull { id -> usable.firstOrNull { it.id == id } }
            .firstOrNull()
            ?: usable.sortedBy { it.id }.firstOrNull()
        return VideoRecognitionTargetSelection(
            target = recommended ?: deterministic,
            savedTargetMissing = false
        )
    }

    fun selectRemote(
        savedTargetId: String?,
        remoteTargets: List<VideoRecognitionTarget>
    ): VideoRecognitionTargetSelection {
        val usable = remoteTargets.filter {
            it.kind == VideoRecognitionTarget.Kind.REMOTE_SERVER && it.isConfigured()
        }
        if (savedTargetId != null) {
            usable.firstOrNull { it.id == savedTargetId }?.let {
                return VideoRecognitionTargetSelection(it, savedTargetMissing = false)
            }
            if (savedTargetId.startsWith("remote-server:")) {
                return VideoRecognitionTargetSelection(target = null, savedTargetMissing = true)
            }
        }
        return VideoRecognitionTargetSelection(
            target = null,
            savedTargetMissing = false
        )
    }

    /**
     * Resolve the persisted target across both target kinds. A missing saved target is an error;
     * only an empty selection may fall back to the deterministic local bundle choice. Remote rows
     * are never auto-selected merely because one is available.
     */
    fun selectConfigured(
        savedTargetId: String?,
        localTargets: List<VideoRecognitionTarget>,
        remoteTargets: List<VideoRecognitionTarget>
    ): VideoRecognitionTargetSelection {
        if (savedTargetId?.startsWith("remote-server:") == true) {
            return selectRemote(savedTargetId, remoteTargets)
        }
        return selectLocal(savedTargetId, localTargets)
    }
}

/** Build a runtime target from the same verified rows used by the canonical bundle catalog. */
fun videoRecognitionTargetFromBundle(
    bundle: CuratedModelBundle,
    installedModels: List<ModelEntity>
): VideoRecognitionTarget? = VideoRecognitionTarget.fromCuratedBundle(bundle, installedModels)

/**
 * Keep multimodal image tokens and generated text inside the selected context window. A 768px
 * Qwen frame can consume roughly 700 visual tokens, so reserving 1K tokens per frame leaves room
 * for the prompt and output while still honoring the global 24-frame ceiling.
 */
fun boundedVideoFrameCount(contextSize: Int, maxTokens: Int, requestedFrames: Int): Int {
    val usableContext = contextSize.coerceAtLeast(1_024)
    val outputReserve = maxTokens.coerceAtLeast(64)
    val frameBudget = ((usableContext - outputReserve - 1_024) / 1_024).coerceAtLeast(1)
    return min(
        requestedFrames.coerceIn(1, NativeLlamaVideoSupport.MAX_FRAMES_PER_SEGMENT),
        frameBudget
    ).coerceAtLeast(1)
}

data class VideoRecognitionAudioFallbackRequest(
    val whisperModelPath: String?,
    val language: String,
    val threads: Int,
    val vadConfig: WhisperVadConfig?,
    val settingsOverride: RemoteSummarySettingsSnapshot?,
    val saveToNotes: Boolean = true,
    val noteType: NoteType = NoteType.VIDEO_SUMMARY
) {
    val isConfigured: Boolean
        get() = !whisperModelPath.isNullOrBlank()
}

data class VideoRecognitionSummaryRequest(
    val sourceUri: Uri? = null,
    val sourcePath: String? = null,
    val sourceName: String,
    val localTargets: List<VideoRecognitionTarget>,
    val remoteTargets: List<VideoRecognitionTarget> = emptyList(),
    val settings: VideoRecognitionSettingsSnapshot,
    val audioFallback: VideoRecognitionAudioFallbackRequest?,
    val forceAudioFallback: Boolean = false,
    val saveToNotes: Boolean = true,
    val noteType: NoteType = NoteType.VIDEO_SUMMARY
)

data class VideoRecognitionSegment(
    val index: Int,
    val total: Int,
    val startSeconds: Int,
    val durationSeconds: Int
)

data class VideoRecognitionSegmentRequest(
    val sessionKey: String,
    val target: VideoRecognitionTarget,
    val sourceFile: File,
    val sourceName: String,
    val segment: VideoRecognitionSegment,
    val maxFrames: Int,
    val maxFps: Float,
    val targetLanguage: String,
    val prompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val timeoutMinutes: Int,
    /** Audio is transportable only after coordinator capability gating. */
    val includeAudio: Boolean = false
)

data class VideoRecognitionMultimodalMergeRequest(
    val sessionKey: String,
    val target: VideoRecognitionTarget,
    val sourceName: String,
    val visualSummary: String,
    val transcript: String,
    val targetLanguage: String,
    val prompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val timeoutMinutes: Int
)

data class VideoRecognitionMergeRequest(
    val sessionKey: String,
    val target: VideoRecognitionTarget,
    val sourceName: String,
    val summaries: List<String>,
    val round: Int,
    val batchIndex: Int,
    val targetLanguage: String,
    val prompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val timeoutMinutes: Int,
    /** Absolute source ranges aligned with [summaries], retained through hierarchical merges. */
    val segmentRanges: List<VideoRecognitionSegment> = emptyList()
)

/**
 * Transport/runtime seam for the coordinator. The runtime implementation owns FFmpeg staging,
 * input_video requests, remote HTTP, and keyed local llama sessions. It must never mutate global
 * chat settings or stop an unrelated server session.
 */
interface VideoRecognitionRuntime {
    suspend fun probeDurationSeconds(sourceFile: File): Double

    suspend fun summarizeSegment(
        request: VideoRecognitionSegmentRequest,
        onProgress: (label: String, fraction: Float) -> Unit
    ): String

    /** Retry a failed video request with extracted frames and the same target/model. */
    suspend fun summarizeFramesFallback(
        request: VideoRecognitionSegmentRequest,
        onProgress: (label: String, fraction: Float) -> Unit
    ): String

    suspend fun mergeSummaries(
        request: VideoRecognitionMergeRequest,
        onProgress: (label: String, fraction: Float) -> Unit
    ): String

    /** Merge independently bounded visual and audio evidence through the selected model. */
    suspend fun mergeMultimodalSummary(
        request: VideoRecognitionMultimodalMergeRequest,
        onProgress: (label: String, fraction: Float) -> Unit
    ): String = mergeSummaries(
        VideoRecognitionMergeRequest(
            sessionKey = request.sessionKey,
            target = request.target,
            sourceName = request.sourceName,
            summaries = listOf(request.visualSummary, request.transcript),
            round = 0,
            batchIndex = 0,
            targetLanguage = request.targetLanguage,
            prompt = request.prompt,
            contextSize = request.contextSize,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            timeoutMinutes = request.timeoutMinutes
        ),
        onProgress
    )

    fun cancelActiveOperation() = Unit

    /** Releases only sessions and resources owned by this runtime instance. */
    fun shutdown() = Unit
}

/** Installs an override for tests or specialized transports; normal use is lazy and contextual. */
object VideoRecognitionRuntimeRegistry {
    @Volatile
    private var installedRuntime: VideoRecognitionRuntime? = null

    fun install(value: VideoRecognitionRuntime) {
        installedRuntime = value
    }

    fun clear() {
        installedRuntime = null
    }

    fun current(context: Context): VideoRecognitionRuntime {
        return installedRuntime ?: NativeVideoRecognitionRuntime(context.applicationContext)
    }

}

enum class VideoRecognitionSummaryStatus {
    IDLE,
    PREPARING,
    VISUAL,
    FRAME_FALLBACK,
    MERGING,
    AUDIO_FALLBACK,
    SUCCESS,
    ERROR,
    CANCELLED
}

data class VideoRecognitionSummaryState(
    val status: VideoRecognitionSummaryStatus = VideoRecognitionSummaryStatus.IDLE,
    /** Stable coordinator generation used by tool callers to scope cancellation and polling. */
    val runId: Long = 0L,
    val sourceName: String? = null,
    val message: String = "",
    val progress: Float = 0f,
    val currentSegment: Int = 0,
    val totalSegments: Int = 0,
    val partialSummaries: List<String> = emptyList(),
    val summary: String = "",
    val error: String? = null,
    val canRetry: Boolean = false,
    val canUseAudioFallback: Boolean = false,
    val usedFrameFallback: Boolean = false,
    /** Transcript retained by the visual coordinator when parallel Whisper succeeds. */
    val transcript: String = "",
    /** Source duration and media progress are separate from model/merge workflow progress. */
    val sourceDurationSeconds: Double = 0.0,
    val processedSeconds: Double = 0.0,
    val processedFraction: Float = 0f,
    val audioProgress: Float = 0f,
    val visualProgress: Float = 0f,
    /** Bounded phase labels for the visual and audio branches. */
    val visualPhase: String = "",
    val audioPhase: String = ""
) {
    val isRunning: Boolean
        get() = status in setOf(
            VideoRecognitionSummaryStatus.PREPARING,
            VideoRecognitionSummaryStatus.VISUAL,
            VideoRecognitionSummaryStatus.FRAME_FALLBACK,
            VideoRecognitionSummaryStatus.MERGING,
            VideoRecognitionSummaryStatus.AUDIO_FALLBACK
        )
}

/**
 * Orchestrates sequential visual segments and hierarchical same-model merges. The old
 * [VideoSumupService] remains the audio/transcript fallback and is called without changing its
 * behavior when no visual target is available or when the user explicitly selects audio fallback.
 */
object VideoRecognitionSummaryService {
    private const val MERGE_FAN_IN = 4
    private const val MAX_SEGMENTS_PER_RUN = 120
    private const val WHOLE_RUN_TIMEOUT_MS = 60L * 60L * 1_000L
    private const val PHASE_LABEL_MAX_CHARS = 120

    /**
     * Real app builds resolve every message from the localized resource table. The fallback keeps
     * service state usable in stripped host-test contexts where Robolectric exposes an R id but
     * does not load the matching packaged string.
     */
    private fun localizedString(context: Context, @StringRes id: Int, vararg args: Any): String =
        runCatching { context.getString(id, *args) }.getOrElse {
            when (id) {
                R.string.video_recognition_progress_preparing -> "Preparing video recognition…"
                R.string.video_recognition_cancelled -> "Video processing cancelled."
                R.string.video_recognition_notification_title ->
                    "Visual video summary: ${args.firstOrNull()?.toString().orEmpty()}"
                else -> "Video processing"
            }
        }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runCounter = AtomicLong(0L)
    private val _state = MutableStateFlow(VideoRecognitionSummaryState())
    private var currentJob: Job? = null
    private var currentAudioJob: Deferred<String>? = null
    private var legacyFallbackJob: Job? = null
    private var currentRuntime: VideoRecognitionRuntime? = null
    private var legacySource: PreparedSource? = null
    private var legacyFallbackRunId: Long? = null
    private var notificationTaskId: Int? = null
    private var notificationRunId: Long? = null

    val state: StateFlow<VideoRecognitionSummaryState> = _state.asStateFlow()

    /** Returns the generation currently represented by [state], or zero before the first run. */
    @Synchronized
    fun currentRunId(): Long = _state.value.runId

    /** Cancel only the run observed by a caller; a late tool request cannot cancel a newer run. */
    @Synchronized
    fun cancelIfCurrent(expectedRunId: Long): Boolean {
        if (expectedRunId <= 0L || _state.value.runId != expectedRunId || !_state.value.isRunning) {
            return false
        }
        cancel()
        return true
    }

    /**
     * Public entry point for the video tool API. It resolves the canonical verified bundle rows
     * and supportsVideo server rows at call time, so callers cannot accidentally pass an
     * arbitrary model or silently switch away from a saved target. The return value scopes later
     * tool polling and cancellation to this exact run.
     */
    suspend fun startConfigured(
        context: Context,
        sourceUri: Uri? = null,
        sourcePath: String? = null,
        sourceName: String,
        audioFallback: VideoRecognitionAudioFallbackRequest?,
        forceAudioFallback: Boolean = false,
        saveToNotes: Boolean = true,
        noteType: NoteType = NoteType.VIDEO_SUMMARY
    ): Long {
        val appContext = context.applicationContext
        val database = AppDatabase.getDatabase(appContext)
        val models = database.modelDao().getAllModels().first()
        val localTargets = VideoRecognitionBundleCatalog.installedBundles(models).mapNotNull { bundle ->
            videoRecognitionTargetFromBundle(
                bundle,
                VideoRecognitionBundleCatalog.installedModels(bundle, models)
            )
        }
        val remoteTargets = database.llamaServerDao().getAllServers().first()
            .mapNotNull(VideoRecognitionTarget::fromServer)
        val request = VideoRecognitionSummaryRequest(
            sourceUri = sourceUri,
            sourcePath = sourcePath,
            sourceName = sourceName,
            localTargets = localTargets,
            remoteTargets = remoteTargets,
            settings = VideoRecognitionSettingsRepository(appContext).snapshot(),
            audioFallback = audioFallback,
            forceAudioFallback = forceAudioFallback,
            saveToNotes = saveToNotes,
            noteType = noteType
        )
        return start(appContext, request)
    }

    @Synchronized
    fun start(
        context: Context,
        request: VideoRecognitionSummaryRequest
    ): Long {
        val previousJob = currentJob
        val previousFallbackJob = legacyFallbackJob
        cancel()
        val runId = runCounter.incrementAndGet()
        _state.value = VideoRecognitionSummaryState(
            status = VideoRecognitionSummaryStatus.PREPARING,
            runId = runId,
            sourceName = request.sourceName,
            message = localizedString(context, R.string.video_recognition_progress_preparing),
            canUseAudioFallback = request.audioFallback?.isConfigured == true
        )
        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            localizedString(context, R.string.video_recognition_notification_title, request.sourceName)
        )
        notificationRunId = runId
        currentJob = serviceScope.launch {
            try {
                previousJob?.join()
                previousFallbackJob?.join()
                if (!isCurrent(runId)) return@launch
                withTimeout(WHOLE_RUN_TIMEOUT_MS) {
                    run(context.applicationContext, request, runId)
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrent(runId)) {
                    fail(
                        request,
                        localizedString(context, R.string.video_recognition_run_timed_out),
                        runId
                    )
                }
            } catch (_: CancellationException) {
                if (isCurrent(runId)) {
                    _state.value = _state.value.copy(
                        status = VideoRecognitionSummaryStatus.CANCELLED,
                        message = localizedString(context, R.string.video_recognition_cancelled),
                        progress = 0f,
                        canRetry = false
                    )
                }
            } catch (error: Throwable) {
                if (isCurrent(runId)) {
                    fail(request, error.message ?: error.javaClass.simpleName, runId)
                }
            } finally {
                if (isCurrent(runId)) {
                    currentAudioJob?.cancel()
                    currentAudioJob = null
                    currentJob = null
                    currentRuntime = null
                    if (notificationRunId == runId) {
                        notificationTaskId = null
                        notificationRunId = null
                    }
                }
            }
        }
        return runId
    }

    @Synchronized
    fun startAudioFallback(
        context: Context,
        request: VideoRecognitionSummaryRequest
    ) = start(context, request.copy(forceAudioFallback = true))

    @Synchronized
    fun cancel() {
        val oldCurrentJob = currentJob
        val oldAudioJob = currentAudioJob
        val oldFallbackJob = legacyFallbackJob
        runCounter.incrementAndGet()
        currentRuntime?.cancelActiveOperation()
        oldAudioJob?.cancel()
        if (_state.value.status == VideoRecognitionSummaryStatus.AUDIO_FALLBACK &&
            legacyFallbackRunId == _state.value.runId
        ) {
            VideoSumupService.cancel()
            legacyFallbackRunId = null
        }
        oldCurrentJob?.cancel()
        oldFallbackJob?.cancel()
        // The legacy service may still be reading this staged source after its monitor is
        // cancelled. Its monitor/current-job finally blocks own cleanup; only clean a stale
        // source when there is no job left that could still reference it.
        if (oldCurrentJob == null && oldFallbackJob == null) {
            legacySource?.cleanup()
            legacySource = null
        }
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        notificationRunId = null
        currentJob = null
        currentAudioJob = null
        legacyFallbackJob = null
        currentRuntime = null
        val sourceName = _state.value.sourceName
        val cancelledRunId = _state.value.runId
        _state.value = VideoRecognitionSummaryState(
            status = VideoRecognitionSummaryStatus.CANCELLED,
            runId = cancelledRunId,
            sourceName = sourceName,
            message = "",
            canRetry = false
        )
    }

    @Synchronized
    fun clear() {
        if (_state.value.isRunning) cancel()
        _state.value = VideoRecognitionSummaryState()
    }

    private suspend fun run(
        context: Context,
        request: VideoRecognitionSummaryRequest,
        runId: Long
    ) {
        val source = materializeSource(context, request)
        val fallback = request.audioFallback
        val policy = request.settings.processingPolicy
        val target = if (request.forceAudioFallback || policy.isLegacyTranscript) {
            VideoRecognitionTargetSelection(target = null, savedTargetMissing = false)
        } else {
            VideoRecognitionTargetSelector.selectConfigured(
                savedTargetId = request.settings.savedTargetId,
                localTargets = request.localTargets,
                remoteTargets = request.remoteTargets
            )
        }

        val selectedTarget = target.target
        if (selectedTarget == null || !selectedTarget.isConfigured()) {
            // A selected model can disappear after selection (for example during deletion).
            // Preserve the configured-target error instead of silently changing to audio.
            if (target.savedTargetMissing || selectedTarget != null) {
                source.cleanup()
                fail(
                    request,
                    localizedString(context, R.string.video_recognition_saved_target_unavailable),
                    runId
                )
                return
            }
            if (fallback?.isConfigured == true &&
                (request.forceAudioFallback || policy.mode != VideoProcessingMode.VISUAL_WHISPER)
            ) {
                startLegacyFallback(context, request, source, runId)
            } else {
                source.cleanup()
                val message = if (request.settings.remoteEnabled) {
                    localizedString(context, R.string.video_recognition_remote_not_configured)
                } else {
                    localizedString(context, R.string.video_recognition_no_model_audio_unavailable)
                }
                fail(request, message, runId)
            }
            return
        }

        // Freeze the first automatic local choice so a later bundle install cannot silently
        // change the model. A concurrently explicit UI choice wins through the repository's
        // compare-and-set helper.
        if (!request.forceAudioFallback && request.settings.savedTargetId == null && selectedTarget.isLocal) {
            VideoRecognitionSettingsRepository(context).setSavedTargetIdIfAbsent(selectedTarget.id)
        }

        val runtime = VideoRecognitionRuntimeRegistry.current(context)
        currentRuntime = runtime
        RemoteSummaryProtection.acquire(context)
        try {
            // Provision the canonical native LLM/media modules before probing. Missing media is
            // allowed to continue because the same model can use Android-decoded frame fallback.
            // Test/specialized transports are already provisioned by their owner. The concrete
            // default runtime performs the canonical native-module check lazily here.
            if (runtime is NativeVideoRecognitionRuntime) {
                NativeVideoDependencies.ensure(context, requireLlm = selectedTarget.isLocal)
            }
            publish(runId) {
                copy(
                    status = VideoRecognitionSummaryStatus.PREPARING,
                    message = localizedString(context, R.string.video_recognition_progress_analyzing),
                    progress = 0.02f,
                    canUseAudioFallback = fallback?.isConfigured == true
                )
            }
            val duration = runtime.probeDurationSeconds(source.file)
                .takeIf { it.isFinite() && it > 0.0 }
                ?: throw IllegalStateException(localizedString(context, R.string.video_recognition_duration_failed))
            // Processing policy is global. A server launch profile may describe how the
            // endpoint was started, but it cannot override segmentation or evidence choices.
            val effectiveSegmentSeconds = policy.segmentSeconds
            val effectiveMaxFrames = policy.maxFrames
            val effectiveMaxFps = policy.maxFps
            val effectiveContextSize = request.settings.contextSize.takeIf { it > 0 }
                ?: selectedTarget.preferredContextSize
            val effectiveTemperature = request.settings.temperature
            val segments = planSegments(context, duration, effectiveSegmentSeconds)
            publish(runId) {
                copy(
                    sourceDurationSeconds = duration,
                    processedSeconds = 0.0,
                    processedFraction = 0f,
                    visualProgress = 0f,
                    audioProgress = 0f,
                    visualPhase = localizedString(context, R.string.video_recognition_progress_preparing),
                    audioPhase = ""
                )
            }
            val directAudioNeedsFallback = selectedTarget.supportsAudio && segments.any {
                it.durationSeconds * 1_000L > NativeLlamaVideoSupport.MAX_DIRECT_VIDEO_DURATION_MS
            }
            val canRunParallelAudio = fallback?.isConfigured == true && when (policy.mode) {
                VideoProcessingMode.VISUAL_WHISPER -> true
                // AUTO uses Whisper as the summary fallback whenever the selected target cannot
                // accept direct audio or the user-selected segment exceeds the native direct
                // clip limit. Native Chat's explicit parallel toggle is separate.
                VideoProcessingMode.AUTO_MULTIMODAL -> !selectedTarget.supportsAudio || directAudioNeedsFallback
                VideoProcessingMode.LEGACY_TRANSCRIPT -> false
            }
            val audioJob: Deferred<String>? = if (canRunParallelAudio) {
                serviceScope.async {
                    VideoRecognitionWhisperTranscriber(context, runId).transcribe(
                        sourceFile = source.file,
                        request = requireNotNull(fallback)
                    ) { label, fraction ->
                        publish(runId) {
                            copy(
                                audioProgress = fraction.coerceIn(0f, 1f),
                                audioPhase = label.take(PHASE_LABEL_MAX_CHARS)
                            )
                        }
                    }
                }.also { currentAudioJob = it }
            } else {
                null
            }

            try {
                val summaries = mutableListOf<String>()
                var usedFrameFallback = false

                segments.forEach { segment ->
                ensureCurrent(runId)
                val processedBefore = processedSecondsForSegments(
                    segments = segments,
                    completedCount = segment.index - 1,
                    sourceDurationSeconds = duration
                )
                val segmentRequest = VideoRecognitionSegmentRequest(
                    sessionKey = selectedTarget.runtimeKey,
                    target = selectedTarget,
                    sourceFile = source.file,
                    sourceName = request.sourceName,
                    segment = segment,
                    maxFrames = boundedVideoFrameCount(
                        contextSize = effectiveContextSize,
                        maxTokens = request.settings.maxTokens,
                        requestedFrames = effectiveMaxFrames
                    ),
                    maxFps = effectiveMaxFps,
                    targetLanguage = request.settings.targetLanguage,
                    prompt = request.settings.prompt,
                    contextSize = effectiveContextSize,
                    maxTokens = request.settings.maxTokens,
                    temperature = effectiveTemperature,
                    timeoutMinutes = request.settings.timeoutMinutes,
                    includeAudio = policy.mode == VideoProcessingMode.AUTO_MULTIMODAL &&
                        policy.directAudioEnabled && selectedTarget.supportsAudio
                )
                publish(runId) {
                    copy(
                        status = VideoRecognitionSummaryStatus.VISUAL,
                        message = localizedString(context,
                            R.string.video_recognition_progress_segment,
                            segment.index,
                            segment.total
                        ),
                        currentSegment = segment.index,
                        totalSegments = segment.total,
                        progress = ((segment.index - 1).toFloat() / segment.total).coerceIn(0f, 0.94f),
                        partialSummaries = summaries.toList(),
                        usedFrameFallback = usedFrameFallback,
                        processedSeconds = processedBefore,
                        processedFraction = durationWeightedProgress(duration, processedBefore),
                        visualProgress = segmentProgress(segment, 0f)
                    )
                }
                val segmentSummary = try {
                    runtime.summarizeSegment(segmentRequest) { label, fraction ->
                        publish(runId) {
                            copy(
                                status = VideoRecognitionSummaryStatus.VISUAL,
                                message = localizedString(context,
                                    R.string.video_recognition_progress_segment,
                                    segment.index,
                                    segment.total
                                ),
                                progress = segmentProgress(segment, fraction),
                                currentSegment = segment.index,
                                totalSegments = segment.total,
                                partialSummaries = summaries.toList(),
                                usedFrameFallback = usedFrameFallback,
                                processedSeconds = processedBefore,
                                processedFraction = durationWeightedProgress(duration, processedBefore),
                                visualProgress = segmentProgress(segment, fraction),
                                visualPhase = label.take(PHASE_LABEL_MAX_CHARS)
                            )
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    publish(runId) {
                        copy(
                            status = VideoRecognitionSummaryStatus.FRAME_FALLBACK,
                            message = localizedString(context, R.string.video_recognition_progress_frame_fallback),
                            progress = segmentProgress(segment, 0.5f),
                            currentSegment = segment.index,
                            totalSegments = segment.total,
                            partialSummaries = summaries.toList(),
                            usedFrameFallback = true,
                            processedSeconds = processedBefore,
                            processedFraction = durationWeightedProgress(duration, processedBefore),
                            visualProgress = segmentProgress(segment, 0.5f)
                        )
                    }
                    usedFrameFallback = true
                    runtime.summarizeFramesFallback(segmentRequest) { label, fraction ->
                        publish(runId) {
                            copy(
                                status = VideoRecognitionSummaryStatus.FRAME_FALLBACK,
                                message = localizedString(context, R.string.video_recognition_progress_frame_fallback),
                                progress = segmentProgress(segment, fraction),
                                currentSegment = segment.index,
                                totalSegments = segment.total,
                                partialSummaries = summaries.toList(),
                                usedFrameFallback = true,
                                processedSeconds = processedBefore,
                                processedFraction = durationWeightedProgress(duration, processedBefore),
                                visualProgress = segmentProgress(segment, fraction),
                                visualPhase = label.take(PHASE_LABEL_MAX_CHARS)
                            )
                        }
                    }
                }
                val cleanSummary = segmentSummary.trim()
                if (cleanSummary.isBlank()) {
                    throw IllegalStateException(localizedString(context, R.string.video_recognition_empty_segment))
                }
                summaries += cleanSummary
                val processedAfter = processedSecondsForSegments(
                    segments = segments,
                    completedCount = segment.index,
                    sourceDurationSeconds = duration
                )
                publish(runId) {
                    copy(
                        partialSummaries = summaries.toList(),
                        usedFrameFallback = usedFrameFallback,
                        progress = (segment.index.toFloat() / segment.total).coerceIn(0f, 0.94f),
                        processedSeconds = processedAfter,
                        processedFraction = durationWeightedProgress(duration, processedAfter),
                        visualProgress = segmentProgress(segment, 1f)
                    )
                }
            }

                var level = summaries.mapIndexed { index, summary ->
                SummaryObservation(summary, segments[index])
            }
                var round = 0
                while (level.size > 1) {
                ensureCurrent(runId)
                round += 1
                val batches = level.chunked(MERGE_FAN_IN)
                val nextLevel = mutableListOf<SummaryObservation>()
                batches.forEachIndexed { batchIndex, batch ->
                    ensureCurrent(runId)
                    if (batch.size == 1) {
                        nextLevel += batch.single()
                    } else {
                        publish(runId) {
                            copy(
                                status = VideoRecognitionSummaryStatus.MERGING,
                                message = localizedString(context,
                                    R.string.video_recognition_progress_merge,
                                    round,
                                    batchIndex + 1,
                                    batches.size
                                ),
                                progress = (0.94f + ((batchIndex.toFloat() / batches.size) * 0.05f)).coerceIn(0f, 0.99f),
                                currentSegment = segments.size,
                                totalSegments = segments.size,
                                partialSummaries = level.map { it.text },
                                usedFrameFallback = usedFrameFallback
                            )
                        }
                        val merged = runtime.mergeSummaries(
                            VideoRecognitionMergeRequest(
                                sessionKey = selectedTarget.runtimeKey,
                                target = selectedTarget,
                                sourceName = request.sourceName,
                                summaries = batch.map { it.text },
                                round = round,
                                batchIndex = batchIndex,
                                targetLanguage = request.settings.targetLanguage,
                                prompt = request.settings.prompt,
                                contextSize = effectiveContextSize,
                                maxTokens = request.settings.maxTokens,
                                temperature = effectiveTemperature,
                                timeoutMinutes = request.settings.timeoutMinutes,
                                segmentRanges = batch.map { it.segment }
                            )
                        ) { _, fraction ->
                            publish(runId) {
                                copy(
                                    status = VideoRecognitionSummaryStatus.MERGING,
                                    message = localizedString(context,
                                        R.string.video_recognition_progress_merge,
                                        round,
                                        batchIndex + 1,
                                        batches.size
                                    ),
                                    progress = (0.94f + (((batchIndex + fraction.coerceIn(0f, 1f)) / batches.size) * 0.05f)).coerceIn(0f, 0.99f),
                                    currentSegment = segments.size,
                                    totalSegments = segments.size,
                                    partialSummaries = level.map { it.text },
                                    usedFrameFallback = usedFrameFallback
                                )
                            }
                        }.trim().also { merged ->
                            if (merged.isBlank()) {
                                throw IllegalStateException(localizedString(context, R.string.video_recognition_empty_merge))
                            }
                        }
                        val first = batch.first().segment
                        val last = batch.last().segment
                        nextLevel += SummaryObservation(
                            text = merged,
                            segment = VideoRecognitionSegment(
                                index = first.index,
                                total = first.total,
                                startSeconds = first.startSeconds,
                                durationSeconds = (
                                    last.startSeconds + last.durationSeconds - first.startSeconds
                                ).coerceAtLeast(1)
                            )
                        )
                    }
                }
                level = nextLevel
            }

                val visualSummary = level.singleOrNull()?.text?.trim()
                    ?: throw IllegalStateException(localizedString(context, R.string.video_recognition_empty_summary))
                val transcript = audioJob?.let { job ->
                    awaitAudioOrNull(job)
                }
                val summary = if (transcript != null) {
                    publish(runId) {
                        copy(
                            status = VideoRecognitionSummaryStatus.MERGING,
                            message = localizedString(context, R.string.video_recognition_progress_multimodal_merge),
                            progress = 0.99f,
                            transcript = transcript,
                            audioProgress = 1f,
                            audioPhase = localizedString(context, R.string.video_recognition_audio_complete)
                        )
                    }
                    runtime.mergeMultimodalSummary(
                        VideoRecognitionMultimodalMergeRequest(
                            sessionKey = selectedTarget.runtimeKey,
                            target = selectedTarget,
                            sourceName = request.sourceName,
                            visualSummary = visualSummary,
                            transcript = transcript,
                            targetLanguage = request.settings.targetLanguage,
                            prompt = request.settings.prompt,
                            contextSize = effectiveContextSize,
                            maxTokens = request.settings.maxTokens,
                            temperature = effectiveTemperature,
                            timeoutMinutes = request.settings.timeoutMinutes
                        )
                        ) { label, fraction ->
                        publish(runId) {
                            copy(
                                status = VideoRecognitionSummaryStatus.MERGING,
                                message = localizedString(context, R.string.video_recognition_progress_multimodal_merge),
                                progress = (0.99f + fraction.coerceIn(0f, 1f) * 0.01f).coerceIn(0f, 1f),
                                transcript = transcript,
                                audioProgress = 1f,
                                audioPhase = localizedString(context, R.string.video_recognition_audio_complete),
                                visualPhase = label.take(PHASE_LABEL_MAX_CHARS)
                            )
                        }
                    }.trim().ifBlank { visualSummary }
                } else {
                    visualSummary
                }
                if (request.saveToNotes) {
                    saveVisualNote(context, request, summary)
                }
                publish(runId) {
                    copy(
                        status = VideoRecognitionSummaryStatus.SUCCESS,
                        message = if (audioJob != null && transcript == null) {
                            localizedString(context, R.string.video_recognition_visual_complete_audio_unavailable)
                        } else {
                            localizedString(context, R.string.video_recognition_progress_complete)
                        },
                        progress = 1f,
                        currentSegment = segments.size,
                        totalSegments = segments.size,
                        partialSummaries = summaries.toList(),
                        summary = summary,
                        error = null,
                        canRetry = false,
                        canUseAudioFallback = false,
                        usedFrameFallback = usedFrameFallback,
                        transcript = transcript.orEmpty(),
                        processedSeconds = duration,
                        processedFraction = durationWeightedProgress(duration, duration),
                        sourceDurationSeconds = duration,
                        visualProgress = 1f,
                        audioProgress = if (audioJob != null) 1f else 0f,
                        visualPhase = localizedString(context, R.string.video_recognition_progress_complete),
                        audioPhase = if (audioJob != null) {
                            localizedString(context, R.string.video_recognition_audio_complete)
                        } else {
                            ""
                        }
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val audioOnlyTranscript = audioJob?.let { job ->
                    awaitAudioOrNull(job)
                }
                if (audioOnlyTranscript != null && isCurrent(runId)) {
                    publish(runId) {
                        copy(
                            status = VideoRecognitionSummaryStatus.SUCCESS,
                            message = localizedString(context, R.string.video_recognition_audio_only_complete),
                            progress = 1f,
                            summary = "",
                            transcript = audioOnlyTranscript,
                            error = null,
                            canRetry = false,
                            canUseAudioFallback = false,
                            processedSeconds = duration,
                            processedFraction = durationWeightedProgress(duration, duration),
                            sourceDurationSeconds = duration,
                            audioProgress = 1f,
                            audioPhase = localizedString(context, R.string.video_recognition_audio_complete)
                        )
                    }
                } else {
                    throw error
                }
            } finally {
                audioJob?.cancel()
                withContext(NonCancellable) {
                    try {
                        audioJob?.join()
                    } catch (_: Throwable) {
                        // Cancellation already invalidated the generation; cleanup is best effort.
                    }
                }
                if (currentAudioJob === audioJob) currentAudioJob = null
            }
        } finally {
            RemoteSummaryProtection.release()
            runtime.shutdown()
            if (currentRuntime === runtime) currentRuntime = null
            source.cleanup()
        }
    }

    private suspend fun startLegacyFallback(
        context: Context,
        request: VideoRecognitionSummaryRequest,
        source: PreparedSource,
        runId: Long
    ) {
        val fallback = request.audioFallback
            ?: throw IllegalStateException(localizedString(context, R.string.video_recognition_no_model_audio_unavailable))
        if (!fallback.isConfigured) {
            fail(request, localizedString(context, R.string.video_recognition_no_model_audio_unavailable), runId)
            return
        }
        publish(runId) {
            copy(
                status = VideoRecognitionSummaryStatus.AUDIO_FALLBACK,
                message = localizedString(context, R.string.video_recognition_audio_fallback_active),
                progress = 0.05f,
                canUseAudioFallback = false,
                canRetry = false
            )
        }
        // The legacy service owns its own progress notification. Transfer ownership before
        // launching it so the visual coordinator does not leave a duplicate task behind.
        if (notificationRunId == runId) {
            notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
            notificationTaskId = null
            notificationRunId = null
        }
        legacySource = source
        legacyFallbackRunId = runId
        try {
            VideoSumupService.startSummarization(
                context = context,
                videoPath = source.file.absolutePath,
                videoFileName = request.sourceName,
                whisperModelPath = requireNotNull(fallback.whisperModelPath),
                language = fallback.language,
                threads = fallback.threads,
                vadConfig = fallback.vadConfig,
                // The coordinator saves a labeled note after the legacy service reaches a
                // terminal state. This preserves the old transcription pipeline without its
                // unlabeled direct note side effect.
                saveToNotes = false,
                noteType = fallback.noteType,
                settingsOverride = fallback.settingsOverride
            )
        } catch (error: Throwable) {
            if (legacyFallbackRunId == runId) legacyFallbackRunId = null
            source.cleanup()
            throw error
        }
        val monitor = serviceScope.launch {
            VideoSummaryStateHolder.isRunning.first { running ->
                !running || !isCurrent(runId)
            }
            if (!isCurrent(runId)) return@launch
            val error = VideoSummaryStateHolder.error.value
            if (error.isNullOrBlank() && !VideoSummaryStateHolder.cancelled.value) {
                val summary = VideoSummaryStateHolder.summary.value.trim()
                if (summary.isNotBlank() && request.saveToNotes && fallback.saveToNotes) {
                    saveAudioFallbackNote(context, request, fallback, summary)
                }
                publish(runId) {
                    copy(
                        status = VideoRecognitionSummaryStatus.SUCCESS,
                        message = localizedString(context, R.string.video_recognition_progress_complete),
                        progress = 1f,
                        // The legacy holder renders the Whisper result below; keep the
                        // coordinator state terminal without duplicating that card.
                        summary = "",
                        error = null,
                        canRetry = false,
                        canUseAudioFallback = false
                    )
                }
            } else if (VideoSummaryStateHolder.cancelled.value) {
                publish(runId) {
                    copy(
                        status = VideoRecognitionSummaryStatus.CANCELLED,
                        message = localizedString(context, R.string.video_recognition_cancelled),
                        progress = 0f,
                        canRetry = false,
                        canUseAudioFallback = false
                    )
                }
            } else {
                fail(request, error ?: localizedString(context, R.string.error_generic), runId)
            }
        }
        legacyFallbackJob = monitor
        try {
            monitor.join()
        } finally {
            if (legacyFallbackRunId == runId) legacyFallbackRunId = null
            if (legacyFallbackJob === monitor) legacyFallbackJob = null
            if (legacySource?.file == source.file) {
                legacySource?.cleanup()
                legacySource = null
            }
        }
    }

    private suspend fun materializeSource(
        context: Context,
        request: VideoRecognitionSummaryRequest
    ): PreparedSource = withContext(Dispatchers.IO) {
        val existing = request.sourcePath?.trim()?.takeIf { it.isNotBlank() }?.let(::File)
        if (existing?.isFile == true) {
            if (existing.length() > VideoMediaProcessor.MAX_STAGED_VIDEO_BYTES) {
                throw IllegalStateException(localizedString(context, R.string.video_recognition_source_too_large))
            }
            return@withContext PreparedSource(existing, owned = false)
        }
        val uri = request.sourceUri
            ?: throw IllegalArgumentException(localizedString(context, R.string.video_recognition_source_missing))
        val extension = request.sourceName.substringAfterLast('.', "mp4")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
            .takeIf { it.isNotBlank() } ?: "mp4"
        val mediaDirectory = File(context.cacheDir, "video_recognition_sources")
        val staged = try {
            VideoMediaProcessor(context).stageVideo(uri, mediaDirectory, "source.$extension")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IllegalStateException(
                localizedString(context, R.string.video_recognition_source_unreadable),
                error
            )
        }
        PreparedSource(staged.file, owned = true)
    }

    private suspend fun saveVisualNote(
        context: Context,
        request: VideoRecognitionSummaryRequest,
        summary: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.getDatabase(context).noteDao().insert(
                NoteEntity(
                    title = localizedString(context, R.string.video_recognition_note_title, request.sourceName),
                    content = localizedString(context,
                        R.string.video_recognition_note_content,
                        summary
                    ),
                    type = request.noteType,
                    sourceFile = request.sourceName,
                    language = request.settings.targetLanguage
                )
            )
        }.onFailure { error ->
            DebugLog.log("[VIDEO-RECOGNITION] Failed to save visual note: ${error.message}")
        }
    }

    private suspend fun saveAudioFallbackNote(
        context: Context,
        request: VideoRecognitionSummaryRequest,
        fallback: VideoRecognitionAudioFallbackRequest,
        summary: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.getDatabase(context).noteDao().insert(
                NoteEntity(
                    title = localizedString(context,
                        R.string.video_recognition_audio_note_title,
                        request.sourceName
                    ),
                    content = localizedString(context,
                        R.string.video_recognition_audio_note_content,
                        summary
                    ),
                    type = fallback.noteType,
                    sourceFile = request.sourceName,
                    language = fallback.language
                )
            )
        }.onFailure { error ->
            DebugLog.log("[VIDEO-RECOGNITION] Failed to save audio fallback note: ${error.message}")
        }
    }

    private fun fail(
        request: VideoRecognitionSummaryRequest,
        message: String,
        runId: Long
    ) {
        publish(runId) {
            copy(
                status = VideoRecognitionSummaryStatus.ERROR,
                message = message,
                error = message,
                progress = 0f,
                canRetry = true,
                canUseAudioFallback = request.audioFallback?.isConfigured == true
            )
        }
    }

    @Synchronized
    private fun publish(runId: Long, transform: VideoRecognitionSummaryState.() -> VideoRecognitionSummaryState) {
        if (!isCurrent(runId)) return
        val next = transform(_state.value)
        _state.value = next
        if (notificationRunId == runId) {
            notificationTaskId?.let { taskId ->
                when (next.status) {
                    VideoRecognitionSummaryStatus.SUCCESS -> UnifiedNotificationManager.completeTask(
                        taskId,
                        next.message.ifBlank { "Complete" }
                    )
                    VideoRecognitionSummaryStatus.ERROR -> UnifiedNotificationManager.failTask(
                        taskId,
                        next.error ?: next.message
                    )
                    VideoRecognitionSummaryStatus.CANCELLED -> UnifiedNotificationManager.failTask(
                        taskId,
                        next.message.ifBlank { "Cancelled" }
                    )
                    else -> UnifiedNotificationManager.updateProgress(
                        taskId,
                        next.progress.coerceIn(0f, 1f),
                        next.message
                    )
                }
            }
        }
    }

    private fun ensureCurrent(runId: Long) {
        serviceScope.coroutineContext.ensureActive()
        if (!isCurrent(runId)) throw CancellationException("Video recognition run replaced")
    }

    private fun isCurrent(runId: Long): Boolean = runCounter.get() == runId

    private suspend fun awaitAudioOrNull(job: Deferred<String>): String? = try {
        job.await().trim().takeIf { it.isNotBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun processedSecondsForSegments(
        segments: List<VideoRecognitionSegment>,
        completedCount: Int,
        sourceDurationSeconds: Double
    ): Double = segments
        .take(completedCount.coerceIn(0, segments.size))
        .sumOf { it.durationSeconds.toDouble() }
        .coerceIn(0.0, sourceDurationSeconds.coerceAtLeast(0.0))

    /** Weight media progress by source seconds, avoiding a false jump on short tail segments. */
    fun durationWeightedProgress(sourceDurationSeconds: Double, processedSeconds: Double): Float {
        if (!sourceDurationSeconds.isFinite() || sourceDurationSeconds <= 0.0) return 0f
        return (processedSeconds.coerceIn(0.0, sourceDurationSeconds) / sourceDurationSeconds)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun segmentProgress(segment: VideoRecognitionSegment, fraction: Float): Float =
        (((segment.index - 1).toFloat() + fraction.coerceIn(0f, 1f)) / segment.total)
            .coerceIn(0f, 0.94f)

    private fun planSegments(
        context: Context,
        durationSeconds: Double,
        segmentSeconds: Int
    ): List<VideoRecognitionSegment> {
        if (!durationSeconds.isFinite()) {
            throw IllegalStateException(localizedString(context, R.string.video_recognition_duration_failed))
        }
        val safeDuration = max(1.0, durationSeconds)
        val segmentLength = VideoRecognitionLimits.normalizeSegmentSeconds(segmentSeconds)
        val totalAsDouble = ceil(safeDuration / segmentLength)
        if (!totalAsDouble.isFinite() || totalAsDouble > MAX_SEGMENTS_PER_RUN) {
            throw IllegalStateException(
                localizedString(context,
                    R.string.video_recognition_duration_too_long,
                    MAX_SEGMENTS_PER_RUN * segmentLength / 60
                )
            )
        }
        val total = totalAsDouble.toInt().coerceAtLeast(1)
        return (0 until total).map { index ->
            val start = index * segmentLength
            val remaining = (safeDuration - start).coerceAtLeast(1.0)
            VideoRecognitionSegment(
                index = index + 1,
                total = total,
                startSeconds = start,
                durationSeconds = ceil(remaining.coerceAtMost(segmentLength.toDouble())).toInt().coerceAtLeast(1)
            )
        }
    }

    private data class PreparedSource(
        val file: File,
        val owned: Boolean
    ) {
        fun cleanup() {
            if (owned) file.delete()
        }
    }

    private data class SummaryObservation(
        val text: String,
        val segment: VideoRecognitionSegment
    )
}
