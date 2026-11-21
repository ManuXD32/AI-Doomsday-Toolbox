package com.example.llamadroid.service

import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SdRunKind { IMAGE, VIDEO }

/** Ordered phases of one stable-diffusion.cpp run. [VIDEO_FRAMES] only applies to video runs. */
enum class SdRunPhase {
    LOADING,
    TEXT_ENCODING,
    DIFFUSION,
    VAE_DECODING,
    VIDEO_FRAMES,
    SAVING
}

/** What the sd.cpp output has shown so far. [phase] is the furthest phase reached (null before any marker). */
data class SdRunProgress(
    val phase: SdRunPhase? = null,
    val step: Int = 0,
    val totalSteps: Int = 0,
    /** Smoothed seconds per sampling step of the current batch item. */
    val secondsPerIt: Double? = null,
    val batchIndex: Int = 1,
    val batchCount: Int = 1,
    /** Video frame count read from `generate_video WxHxF`. */
    val frames: Int? = null,
    val finished: Boolean = false
)

/**
 * Folds stable-diffusion.cpp output lines into an [SdRunProgress]. Strings come from the sd.cpp sources:
 * `pretty_progress` (`| 3/20 - 2.31s/it - ETA 00:39`), `get_learned_condition completed`, `generating image: 1/2`,
 * `sampling completed`, `decoding N latents`, `generate_video WxHxF`, `generate_video completed`,
 * `save result image/video` and `N/M images saved`. Phases never move backwards, so a late tiling bar
 * (VAE tiles use the same bar format) cannot be mistaken for sampling.
 */
object SdRunLineParser {
    private const val RATE_WINDOW = 5
    private const val DECODE_FRACTION = 0.15

    private val generatingImage = Regex("""generating image:\s*(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    private val generateVideo = Regex("""generate_video\s+(\d+)x(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val decodingLatents = Regex("""\bdecoding\s+\d+(?:/\d+)?\s+latents?\b""", RegexOption.IGNORE_CASE)
    private val latentDecoded = Regex("""\blatent\s+\d+\s+decoded\b""", RegexOption.IGNORE_CASE)
    private val imagesSaved = Regex("""\b\d+/\d+\s+images?\s+saved\b""", RegexOption.IGNORE_CASE)
    private val loadingLine = Regex("""\bloading\s""")
    private val saveResult = Regex("""\bsave result (?:image|video|audio)\b""", RegexOption.IGNORE_CASE)

    /** Strips the app's log prefix (`SD: `, `[VIDEO-GEN] `) so callers can pass raw DebugLog messages. */
    fun stripPrefix(message: String): String = when {
        message.startsWith("SD: ") -> message.removePrefix("SD: ")
        message.startsWith("[VIDEO-GEN] ") -> message.removePrefix("[VIDEO-GEN] ")
        else -> message
    }

    fun parse(lines: List<String>): SdRunProgress {
        var phase: SdRunPhase? = null
        var step = 0
        var total = 0
        var batchIndex = 1
        var batchCount = 1
        var frames: Int? = null
        var finished = false
        val rates = ArrayDeque<Double>()

        fun advance(to: SdRunPhase) {
            if (phase == null || to.ordinal > phase!!.ordinal) phase = to
        }

        for (raw in lines) {
            val line = stripPrefix(raw)
            val lower = line.lowercase()
            val image = generatingImage.find(line)
            if (image != null) {
                batchIndex = image.groupValues[1].toIntOrNull() ?: batchIndex
                batchCount = image.groupValues[2].toIntOrNull() ?: batchCount
                if ((phase?.ordinal ?: -1) <= SdRunPhase.DIFFUSION.ordinal) {
                    advance(SdRunPhase.DIFFUSION)
                    step = 0
                    rates.clear()
                }
                continue
            }
            val video = generateVideo.find(line)
            if (video != null) {
                frames = video.groupValues[3].toIntOrNull()
                continue
            }
            if ((phase?.ordinal ?: -1) <= SdRunPhase.DIFFUSION.ordinal) {
                val sampling = SdProgressTracker.parseSamplingProgress(line)
                if (sampling != null) {
                    advance(SdRunPhase.DIFFUSION)
                    if (sampling.first < step) rates.clear() // a new batch item or hires pass restarted the bar
                    step = sampling.first
                    total = sampling.second
                    SdProgressTracker.extractIterationSeconds(line)?.let { seconds ->
                        rates.addLast(seconds)
                        while (rates.size > RATE_WINDOW) rates.removeFirst()
                    }
                    continue
                }
            }
            when {
                imagesSaved.containsMatchIn(line) -> {
                    advance(SdRunPhase.SAVING)
                    finished = true
                }
                saveResult.containsMatchIn(line) -> advance(SdRunPhase.SAVING)
                "generate_video completed" in lower -> advance(SdRunPhase.VIDEO_FRAMES)
                "latent images completed" in lower ||
                    "generating latent video completed" in lower ||
                    "decode_first_stage" in lower ||
                    decodingLatents.containsMatchIn(line) ||
                    latentDecoded.containsMatchIn(line) -> advance(SdRunPhase.VAE_DECODING)
                "loading tensors completed" in lower ||
                    "get_learned_condition" in lower ||
                    "condition graph" in lower ||
                    "encode_first_stage" in lower -> advance(SdRunPhase.TEXT_ENCODING)
                loadingLine.containsMatchIn(lower) || "memory-mapped" in lower -> advance(SdRunPhase.LOADING)
            }
        }
        return SdRunProgress(
            phase = phase,
            step = step,
            totalSteps = total,
            secondsPerIt = rates.takeIf { it.isNotEmpty() }?.average(),
            batchIndex = batchIndex,
            batchCount = batchCount,
            frames = frames,
            finished = finished
        )
    }

    /**
     * Remaining seconds: s/it times the remaining steps (this batch item and the following ones), plus a decode
     * estimate of [DECODE_FRACTION] of one sampling pass per batch item. Null until a rate is known or once
     * sampling is over.
     */
    fun etaSeconds(progress: SdRunProgress): Int? {
        if (progress.finished) return 0
        val rate = progress.secondsPerIt ?: return null
        val phase = progress.phase ?: return null
        if (phase.ordinal > SdRunPhase.DIFFUSION.ordinal || progress.totalSteps <= 0) return null
        val remainingSteps = (progress.totalSteps - progress.step).coerceAtLeast(0) +
            (progress.batchCount - progress.batchIndex).coerceAtLeast(0) * progress.totalSteps
        val decode = progress.totalSteps * rate * DECODE_FRACTION * progress.batchCount.coerceAtLeast(1)
        return ceil(remainingSteps * rate + decode - 1e-6).toInt()
    }
}

/** Expected weight bytes per Media helper, derived from the placement plan and the selected model files. */
object SdTransferExpectation {
    private val loadOrder = listOf(
        SdDistributedModules.DIFFUSION,
        SdDistributedModules.TEXT_ENCODER,
        SdDistributedModules.VAE,
        SdDistributedModules.CONTROLNET,
        SdDistributedModules.UPSCALER
    )

    /** Summed on-disk size per module; blank or missing paths count as zero. */
    fun moduleBytes(paths: Map<String, List<String?>>): Map<String, Long> =
        paths.mapValues { (_, files) ->
            files.filterNot { it.isNullOrBlank() }.sumOf { runCatching { File(it!!).length() }.getOrDefault(0L) }
        }

    /**
     * Bytes each remote helper should receive, as `host:port` to bytes. A split module contributes its layer share,
     * a whole module its full size. Helpers are ordered by the earliest module they host (sd.cpp loads the diffusion
     * model first) so cumulative TX can be mapped onto them in order, like the LLM master does.
     */
    fun expected(
        workers: List<SdDistributedWorkerRuntime>,
        moduleBytes: Map<String, Long>
    ): List<Pair<String, Long>> {
        val rows = workers.filterNot { it.isLocalMaster }.mapIndexed { index, worker ->
            val bytes = worker.plannedAssignments.sumOf { assignment ->
                val size = moduleBytes[assignment.module] ?: 0L
                if (assignment.isSplit) size * assignment.estimatedLayerShare.coerceIn(0, 100) / 100 else size
            }
            val firstModule = worker.plannedAssignments
                .minOfOrNull { loadOrder.indexOf(it.module).let { i -> if (i < 0) loadOrder.size else i } }
                ?: loadOrder.size
            Triple("${worker.host}:${worker.port}", bytes, firstModule * 1000 + index)
        }
        return rows.sortedBy { it.third }.map { it.first to it.second }
    }
}

/**
 * Cross-screen holder for the active Media run: the master screen calls [begin] when Start succeeds, the
 * Red multimedia screen drives [tick] while it is visible and reads [transfers]. TX is the app uid's counter
 * delta since [begin], the same measurement the LLM master uses.
 */
object SdDistributedRunMonitor {
    data class Session(val kind: SdRunKind, val mode: String, val startedAtMs: Long, val startTxBytes: Long)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _transfers = MutableStateFlow<List<HelperTransfer>>(emptyList())
    val transfers: StateFlow<List<HelperTransfer>> = _transfers.asStateFlow()

    private var sampler: DistributedTransferSampler? = null
    private var transferClosed = false

    @Synchronized
    fun begin(kind: SdRunKind, mode: String, nowMs: Long, txBytes: Long, expected: List<Pair<String, Long>>) {
        resetTerminalState(kind, mode)
        _session.value = Session(kind, mode, nowMs, txBytes)
        transferClosed = false
        sampler = expected.takeIf { list -> list.any { it.second > 0L } }?.let(::DistributedTransferSampler)
        _transfers.value = emptyList()
    }

    /**
     * A new run starts from a clean slate: an old Error/Complete left in the mode's distributed state holder would
     * otherwise be shown as this run's outcome until the service publishes Generating. Unknown mode names are ignored.
     */
    private fun resetTerminalState(kind: SdRunKind, mode: String) {
        if (kind == SdRunKind.IMAGE) {
            val sdMode = SDMode.entries.firstOrNull { it.name == mode } ?: return
            val holder = SDModeStateHolder.getDistributedForMode(sdMode)
            val state = holder.state.value
            if (state is SDGenerationState.Error || state is SDGenerationState.Complete) {
                holder.updateState(SDGenerationState.Idle)
            }
        } else {
            val videoMode = VideoGenerationMode.entries.firstOrNull { it.name == mode } ?: return
            val holder = VideoGenerationStateHolder.getForMode(videoMode, useDistributedStateHolder = true)
            val state = holder.state.value
            if (state is VideoGenerationState.Error || state is VideoGenerationState.Complete) {
                holder.updateState(VideoGenerationState.Idle)
            }
        }
    }

    /** Samples TX; once [weightsLoaded] the final per-helper state (including cache hits) is published once. */
    @Synchronized
    fun tick(nowMs: Long, txBytes: Long, weightsLoaded: Boolean) {
        val active = sampler ?: return
        if (_session.value == null || transferClosed) return
        // The sampler baselines on its first sample, so seed it with the TX recorded at begin().
        val base = _session.value!!.startTxBytes
        if (_transfers.value.isEmpty()) active.sample(base, _session.value!!.startedAtMs)
        if (weightsLoaded) {
            active.sample(txBytes, nowMs)
            _transfers.value = active.finish()
            transferClosed = true
        } else {
            _transfers.value = active.sample(txBytes, nowMs)
        }
    }

    @Synchronized
    fun clear() {
        _session.value = null
        _transfers.value = emptyList()
        sampler = null
        transferClosed = false
    }
}

/** What the run panel shows, derived from the state of the run's own mode only. */
internal data class SdRunView(
    val error: String?,
    val complete: Boolean,
    val saving: Boolean,
    val running: Boolean
)

internal fun sdRunView(
    kind: SdRunKind,
    imageState: SDGenerationState?,
    videoState: VideoGenerationState?
): SdRunView {
    val error = if (kind == SdRunKind.IMAGE) {
        (imageState as? SDGenerationState.Error)?.message
    } else {
        (videoState as? VideoGenerationState.Error)?.message
    }
    val complete = if (kind == SdRunKind.IMAGE) {
        imageState is SDGenerationState.Complete
    } else {
        videoState is VideoGenerationState.Complete
    }
    val saving = kind == SdRunKind.VIDEO &&
        (videoState is VideoGenerationState.Converting || videoState is VideoGenerationState.Copying)
    val generating = if (kind == SdRunKind.IMAGE) {
        imageState is SDGenerationState.Generating
    } else {
        videoState is VideoGenerationState.Generating
    }
    return SdRunView(error, complete, saving, running = error == null && !complete && (generating || saving))
}
