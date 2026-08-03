package com.example.llamadroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoInterpolationConfig(
    val inputPath: String,
    val outputPath: String,
    val modelId: String = MediaModelRegistry.defaultRifeModel.id,
    val multiplier: Int = 2,
    val backend: VideoInterpolationBackend = VideoInterpolationBackend.AUTO,
    val preserveAudio: Boolean = true,
    val sceneCutProtection: Boolean = true,
    val spatialTta: Boolean = false,
    val temporalTta: Boolean = false,
    val uhdMode: Boolean = false,
    val threadSpec: String = "1:2:2",
    val codec: VideoInterpolationCodec = VideoInterpolationCodec.H264,
    val crf: Int = 20
)

data class VideoInterpolateUpscaleConfig(
    val interpolationConfig: VideoInterpolationConfig,
    val upscaleConfig: VideoUpscalerConfig
)

enum class VideoInterpolationBackend {
    AUTO,
    VULKAN,
    CPU
}

enum class VideoInterpolationCodec(val ffmpegCodec: String) {
    H264("libx264"),
    HEVC("libx265")
}

data class VideoInterpolationInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val avgFps: Double,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val frameCount: Int,
    val isLikelyVariableFrameRate: Boolean
) {
    val durationFormatted: String
        get() {
            val mins = (durationSeconds / 60).toInt()
            val secs = (durationSeconds % 60).toInt()
            return "${mins}:${secs.toString().padStart(2, '0')}"
        }

    val sizeFormatted: String
        get() = com.example.llamadroid.util.FormatUtils.Technical.formatBytes(sizeBytes)

    val resolution: String get() = "${width}x${height}"
}

sealed class VideoInterpolationState {
    data object Idle : VideoInterpolationState()
    data object PreparingModel : VideoInterpolationState()
    data object ReadingVideo : VideoInterpolationState()
    data object ExtractingFrames : VideoInterpolationState()
    data object DetectingSceneCuts : VideoInterpolationState()
    data class Interpolating(
        val current: Int,
        val total: Int,
        val backendUsed: VideoInterpolationBackend?
    ) : VideoInterpolationState()
    data object EncodingVideo : VideoInterpolationState()
    data object RestoringAudio : VideoInterpolationState()
    data object Finalizing : VideoInterpolationState()
    data class Completed(val outputPath: String, val backendUsed: VideoInterpolationBackend?) : VideoInterpolationState()
    data class Error(val message: String) : VideoInterpolationState()
}

sealed class VideoInterpolateUpscaleState {
    data object Idle : VideoInterpolateUpscaleState()
    data object Interpolating : VideoInterpolateUpscaleState()
    data object Upscaling : VideoInterpolateUpscaleState()
    data object Finalizing : VideoInterpolateUpscaleState()
    data class Completed(val outputPath: String, val galleryId: String) : VideoInterpolateUpscaleState()
    data class Error(val message: String) : VideoInterpolateUpscaleState()
}

object VideoInterpolationMath {
    fun outputFrameCount(inputFrames: Int, multiplier: Int): Int {
        if (inputFrames <= 0) return 0
        require(multiplier >= 2) { "Multiplier must be at least 2" }
        return (inputFrames - 1) * multiplier + 1
    }

    fun outputFps(sourceFps: Double, multiplier: Int): Double {
        require(sourceFps > 0.0) { "Source FPS must be positive" }
        require(multiplier >= 2) { "Multiplier must be at least 2" }
        return sourceFps * multiplier
    }
}

object VideoInterpolationStateHolder {
    private val _inputPath = MutableStateFlow<String?>(null)
    val inputPath: StateFlow<String?> = _inputPath.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentFrame = MutableStateFlow(0)
    val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()

    private val _totalFrames = MutableStateFlow(0)
    val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _resultPath = MutableStateFlow<String?>(null)
    val resultPath: StateFlow<String?> = _resultPath.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _backendUsed = MutableStateFlow<VideoInterpolationBackend?>(null)
    val backendUsed: StateFlow<VideoInterpolationBackend?> = _backendUsed.asStateFlow()

    fun setInputPath(path: String?) { _inputPath.value = path }
    fun setIsProcessing(processing: Boolean) { _isProcessing.value = processing }
    fun setProgress(progress: Float) { _progress.value = progress.coerceIn(0f, 1f) }
    fun setCurrentFrame(frame: Int) { _currentFrame.value = frame }
    fun setTotalFrames(total: Int) { _totalFrames.value = total }
    fun setStatus(status: String) { _status.value = status }
    fun setResultPath(path: String?) { _resultPath.value = path }
    fun setError(error: String?) { _error.value = error }
    fun setBackendUsed(backend: VideoInterpolationBackend?) { _backendUsed.value = backend }

    fun reset() {
        _inputPath.value = null
        _isProcessing.value = false
        _progress.value = 0f
        _currentFrame.value = 0
        _totalFrames.value = 0
        _status.value = ""
        _resultPath.value = null
        _error.value = null
        _backendUsed.value = null
    }
}

object VideoInterpolateUpscaleStateHolder {
    private val _state = MutableStateFlow<VideoInterpolateUpscaleState>(VideoInterpolateUpscaleState.Idle)
    val state: StateFlow<VideoInterpolateUpscaleState> = _state.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _resultPath = MutableStateFlow<String?>(null)
    val resultPath: StateFlow<String?> = _resultPath.asStateFlow()

    private val _galleryId = MutableStateFlow<String?>(null)
    val galleryId: StateFlow<String?> = _galleryId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setRunning(state: VideoInterpolateUpscaleState, status: String, progress: Float) {
        _state.value = state
        _status.value = status
        _progress.value = progress.coerceIn(0f, 1f)
        _isProcessing.value = true
        _error.value = null
    }

    fun setProgress(status: String, progress: Float) {
        _status.value = status
        _progress.value = progress.coerceIn(0f, 1f)
    }

    fun setCompleted(outputPath: String, galleryId: String, status: String) {
        _state.value = VideoInterpolateUpscaleState.Completed(outputPath, galleryId)
        _resultPath.value = outputPath
        _galleryId.value = galleryId
        _status.value = status
        _progress.value = 1f
        _isProcessing.value = false
        _error.value = null
    }

    fun setError(message: String) {
        _state.value = VideoInterpolateUpscaleState.Error(message)
        _status.value = message
        _progress.value = 0f
        _isProcessing.value = false
        _error.value = message
    }

    fun reset() {
        _state.value = VideoInterpolateUpscaleState.Idle
        _isProcessing.value = false
        _progress.value = 0f
        _status.value = ""
        _resultPath.value = null
        _galleryId.value = null
        _error.value = null
    }
}
