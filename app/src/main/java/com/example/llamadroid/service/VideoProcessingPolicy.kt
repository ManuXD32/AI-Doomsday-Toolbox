package com.example.llamadroid.service

/**
 * The single processing policy used by Video Summary and Native Chat.
 *
 * The policy deliberately lives outside a server launch profile. A server profile describes how
 * a llama-server process is started; this object describes how an attached video is prepared and
 * which evidence paths are allowed for the current request.
 */
enum class VideoProcessingMode {
    /** Prefer direct multimodal video/audio input, then use Whisper as a recovery path. */
    AUTO_MULTIMODAL,

    /** Always collect visual evidence and one independent Whisper transcript when available. */
    VISUAL_WHISPER,

    /** Route the request through the existing Whisper/transcript service. */
    LEGACY_TRANSCRIPT;

    companion object {
        fun fromStorage(value: String?): VideoProcessingMode = value
            ?.trim()
            ?.uppercase()
            ?.let { candidate -> entries.firstOrNull { it.name == candidate } }
            ?: AUTO_MULTIMODAL
    }
}

data class VideoProcessingPolicy(
    val mode: VideoProcessingMode = VideoProcessingMode.AUTO_MULTIMODAL,
    val segmentSeconds: Int = VideoRecognitionLimits.DEFAULT_SEGMENT_SECONDS,
    val maxFrames: Int = VideoRecognitionLimits.DEFAULT_MAX_FRAMES,
    val maxFps: Float = VideoRecognitionLimits.DEFAULT_MAX_FPS,
    val directAudioEnabled: Boolean = VideoRecognitionLimits.DEFAULT_DIRECT_AUDIO_ENABLED,
    /** Native Chat's independent Whisper job is intentionally opt-in. */
    val nativeChatParallelWhisperEnabled: Boolean = false
) {
    val isLegacyTranscript: Boolean
        get() = mode == VideoProcessingMode.LEGACY_TRANSCRIPT

    val shouldCollectIndependentTranscript: Boolean
        get() = mode != VideoProcessingMode.AUTO_MULTIMODAL || nativeChatParallelWhisperEnabled

    fun normalized(): VideoProcessingPolicy = copy(
        mode = mode,
        segmentSeconds = VideoRecognitionLimits.normalizeSegmentSeconds(segmentSeconds),
        maxFrames = VideoRecognitionLimits.normalizeMaxFrames(maxFrames),
        maxFps = VideoRecognitionLimits.normalizeMaxFps(maxFps)
    )

    companion object {
        fun from(settings: VideoRecognitionSettingsSnapshot): VideoProcessingPolicy = VideoProcessingPolicy(
            mode = settings.processingMode,
            segmentSeconds = settings.segmentSeconds,
            maxFrames = settings.maxFrames,
            maxFps = settings.maxFps,
            directAudioEnabled = settings.audioEnabled,
            nativeChatParallelWhisperEnabled = settings.parallelWhisperEnabled
        ).normalized()
    }
}
