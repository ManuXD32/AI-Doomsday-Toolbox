package com.example.llamadroid.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings that belong only to visual video recognition.
 *
 * The legacy Whisper/transcript settings intentionally remain in
 * [com.example.llamadroid.data.SettingsRepository]. Keeping this repository
 * separate lets the visual flow evolve without changing the legacy summary
 * preference group while another settings migration is in progress.
 */
class VideoRecognitionSettingsRepository(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _savedTargetId = MutableStateFlow(readTargetId())
    val savedTargetId: StateFlow<String?> = _savedTargetId.asStateFlow()

    private val _remoteEnabled = MutableStateFlow(readBoolean(KEY_REMOTE_ENABLED, false))
    val remoteEnabled: StateFlow<Boolean> = _remoteEnabled.asStateFlow()

    private val _remoteEndpoint = MutableStateFlow(readString(KEY_REMOTE_ENDPOINT))
    val remoteEndpoint: StateFlow<String> = _remoteEndpoint.asStateFlow()

    private val _remoteModel = MutableStateFlow(readString(KEY_REMOTE_MODEL))
    val remoteModel: StateFlow<String> = _remoteModel.asStateFlow()

    private val _remoteVideoEnabled = MutableStateFlow(readBoolean(KEY_REMOTE_VIDEO_ENABLED, false))
    val remoteVideoEnabled: StateFlow<Boolean> = _remoteVideoEnabled.asStateFlow()

    /** Attach a bounded PCM WAV only when the selected target advertises direct audio input. */
    private val _audioEnabled = MutableStateFlow(readBoolean(KEY_AUDIO_ENABLED, false))
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    /** Run one coordinator-owned Whisper process alongside sequential visual work. */
    private val _parallelWhisperEnabled = MutableStateFlow(
        readBoolean(KEY_PARALLEL_WHISPER_ENABLED, false)
    )
    val parallelWhisperEnabled: StateFlow<Boolean> = _parallelWhisperEnabled.asStateFlow()

    private val _segmentSeconds = MutableStateFlow(
        VideoRecognitionLimits.normalizeSegmentSeconds(
            preferences.getInt(KEY_SEGMENT_SECONDS, VideoRecognitionLimits.DEFAULT_SEGMENT_SECONDS)
        )
    )
    val segmentSeconds: StateFlow<Int> = _segmentSeconds.asStateFlow()

    private val _maxFrames = MutableStateFlow(
        VideoRecognitionLimits.normalizeMaxFrames(
            preferences.getInt(KEY_MAX_FRAMES, VideoRecognitionLimits.DEFAULT_MAX_FRAMES)
        )
    )
    val maxFrames: StateFlow<Int> = _maxFrames.asStateFlow()

    private val _maxFps = MutableStateFlow(
        VideoRecognitionLimits.normalizeMaxFps(
            preferences.getFloat(KEY_MAX_FPS, VideoRecognitionLimits.DEFAULT_MAX_FPS)
        )
    )
    val maxFps: StateFlow<Float> = _maxFps.asStateFlow()

    private val _targetLanguage = MutableStateFlow(
        readString(KEY_TARGET_LANGUAGE).ifBlank { VideoRecognitionLimits.DEFAULT_TARGET_LANGUAGE }
    )
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _prompt = MutableStateFlow(
        readString(KEY_PROMPT).ifBlank { VideoRecognitionLimits.DEFAULT_PROMPT }
    )
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _contextSize = MutableStateFlow(
        VideoRecognitionLimits.normalizeContextSize(
            preferences.getInt(KEY_CONTEXT_SIZE, VideoRecognitionLimits.DEFAULT_CONTEXT_SIZE)
        )
    )
    val contextSize: StateFlow<Int> = _contextSize.asStateFlow()

    private val _maxTokens = MutableStateFlow(
        VideoRecognitionLimits.normalizeMaxTokens(
            preferences.getInt(KEY_MAX_TOKENS, VideoRecognitionLimits.DEFAULT_MAX_TOKENS)
        )
    )
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _temperature = MutableStateFlow(
        VideoRecognitionLimits.normalizeTemperature(
            preferences.getFloat(KEY_TEMPERATURE, VideoRecognitionLimits.DEFAULT_TEMPERATURE)
        )
    )
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _timeoutMinutes = MutableStateFlow(
        VideoRecognitionLimits.normalizeTimeoutMinutes(
            preferences.getInt(KEY_TIMEOUT_MINUTES, VideoRecognitionLimits.DEFAULT_TIMEOUT_MINUTES)
        )
    )
    val timeoutMinutes: StateFlow<Int> = _timeoutMinutes.asStateFlow()

    fun setSavedTargetId(value: String?) {
        val normalized = value?.trim()?.ifBlank { null }
        updateString(KEY_SAVED_TARGET_ID, normalized)
        _savedTargetId.value = normalized
    }

    /** Persist the first automatic local choice without overwriting a concurrent explicit choice. */
    fun setSavedTargetIdIfAbsent(value: String): Boolean {
        val normalized = value.trim().ifBlank { return false }
        return synchronized(lock) {
            if (readTargetId() != null) return@synchronized false
            preferences.edit().putString(KEY_SAVED_TARGET_ID, normalized).apply()
            _savedTargetId.value = normalized
            true
        }
    }

    /** Refresh the target flow after another entry point persisted an automatic choice. */
    fun refreshSavedTargetId() {
        _savedTargetId.value = synchronized(lock) { readTargetId() }
    }

    fun setRemoteEnabled(value: Boolean) {
        updateBoolean(KEY_REMOTE_ENABLED, value)
        _remoteEnabled.value = value
    }

    fun setRemoteEndpoint(value: String) {
        val normalized = value.trim()
        updateString(KEY_REMOTE_ENDPOINT, normalized)
        _remoteEndpoint.value = normalized
    }

    fun setRemoteModel(value: String) {
        val normalized = value.trim()
        updateString(KEY_REMOTE_MODEL, normalized)
        _remoteModel.value = normalized
    }

    fun setRemoteVideoEnabled(value: Boolean) {
        updateBoolean(KEY_REMOTE_VIDEO_ENABLED, value)
        _remoteVideoEnabled.value = value
    }

    fun setAudioEnabled(value: Boolean) {
        updateBoolean(KEY_AUDIO_ENABLED, value)
        _audioEnabled.value = value
    }

    fun setParallelWhisperEnabled(value: Boolean) {
        updateBoolean(KEY_PARALLEL_WHISPER_ENABLED, value)
        _parallelWhisperEnabled.value = value
    }

    fun setSegmentSeconds(value: Int) {
        val normalized = VideoRecognitionLimits.normalizeSegmentSeconds(value)
        updateInt(KEY_SEGMENT_SECONDS, normalized)
        _segmentSeconds.value = normalized
    }

    fun setMaxFrames(value: Int) {
        val normalized = VideoRecognitionLimits.normalizeMaxFrames(value)
        updateInt(KEY_MAX_FRAMES, normalized)
        _maxFrames.value = normalized
    }

    fun setMaxFps(value: Float) {
        val normalized = VideoRecognitionLimits.normalizeMaxFps(value)
        updateFloat(KEY_MAX_FPS, normalized)
        _maxFps.value = normalized
    }

    fun setTargetLanguage(value: String) {
        val normalized = value.trim().ifBlank { VideoRecognitionLimits.DEFAULT_TARGET_LANGUAGE }
        updateString(KEY_TARGET_LANGUAGE, normalized)
        _targetLanguage.value = normalized
    }

    fun setPrompt(value: String) {
        val normalized = value.trim().ifBlank { VideoRecognitionLimits.DEFAULT_PROMPT }
        updateString(KEY_PROMPT, normalized)
        _prompt.value = normalized
    }

    fun setContextSize(value: Int) {
        val normalized = VideoRecognitionLimits.normalizeContextSize(value)
        updateInt(KEY_CONTEXT_SIZE, normalized)
        _contextSize.value = normalized
    }

    fun setMaxTokens(value: Int) {
        val normalized = VideoRecognitionLimits.normalizeMaxTokens(value)
        updateInt(KEY_MAX_TOKENS, normalized)
        _maxTokens.value = normalized
    }

    fun setTemperature(value: Float) {
        val normalized = VideoRecognitionLimits.normalizeTemperature(value)
        updateFloat(KEY_TEMPERATURE, normalized)
        _temperature.value = normalized
    }

    fun setTimeoutMinutes(value: Int) {
        val normalized = VideoRecognitionLimits.normalizeTimeoutMinutes(value)
        updateInt(KEY_TIMEOUT_MINUTES, normalized)
        _timeoutMinutes.value = normalized
    }

    fun snapshot(): VideoRecognitionSettingsSnapshot = synchronized(lock) {
        VideoRecognitionSettingsSnapshot(
            savedTargetId = _savedTargetId.value,
            remoteEnabled = _remoteEnabled.value,
            remoteEndpoint = _remoteEndpoint.value,
            remoteModel = _remoteModel.value,
            remoteVideoEnabled = _remoteVideoEnabled.value,
            audioEnabled = _audioEnabled.value,
            parallelWhisperEnabled = _parallelWhisperEnabled.value,
            segmentSeconds = _segmentSeconds.value,
            maxFrames = _maxFrames.value,
            maxFps = _maxFps.value,
            targetLanguage = _targetLanguage.value,
            prompt = _prompt.value,
            contextSize = _contextSize.value,
            maxTokens = _maxTokens.value,
            temperature = _temperature.value,
            timeoutMinutes = _timeoutMinutes.value
        )
    }

    private fun readTargetId(): String? = readString(KEY_SAVED_TARGET_ID).ifBlank { null }

    private fun readString(key: String): String = preferences.getString(key, "").orEmpty()

    private fun readBoolean(key: String, fallback: Boolean): Boolean =
        preferences.getBoolean(key, fallback)

    private fun updateString(key: String, value: String?) = synchronized(lock) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private fun updateBoolean(key: String, value: Boolean) = synchronized(lock) {
        preferences.edit().putBoolean(key, value).apply()
    }

    private fun updateInt(key: String, value: Int) = synchronized(lock) {
        preferences.edit().putInt(key, value).apply()
    }

    private fun updateFloat(key: String, value: Float) = synchronized(lock) {
        preferences.edit().putFloat(key, value).apply()
    }

    private companion object {
        // Screen and coordinator use separate repository instances over the same preferences.
        val lock = Any()

        const val PREFERENCES_NAME = "video_recognition_settings"
        const val KEY_SAVED_TARGET_ID = "saved_target_id"
        const val KEY_REMOTE_ENABLED = "remote_enabled"
        const val KEY_REMOTE_ENDPOINT = "remote_endpoint"
        const val KEY_REMOTE_MODEL = "remote_model"
        const val KEY_REMOTE_VIDEO_ENABLED = "remote_video_enabled"
        const val KEY_AUDIO_ENABLED = "audio_enabled"
        const val KEY_PARALLEL_WHISPER_ENABLED = "parallel_whisper_enabled"
        const val KEY_SEGMENT_SECONDS = "segment_seconds"
        const val KEY_MAX_FRAMES = "max_frames"
        const val KEY_MAX_FPS = "max_fps"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_PROMPT = "prompt"
        const val KEY_CONTEXT_SIZE = "context_size"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TIMEOUT_MINUTES = "timeout_minutes"
    }
}

data class VideoRecognitionSettingsSnapshot(
    val savedTargetId: String?,
    val remoteEnabled: Boolean,
    val remoteEndpoint: String,
    val remoteModel: String,
    val remoteVideoEnabled: Boolean,
    val segmentSeconds: Int,
    val maxFrames: Int,
    val maxFps: Float,
    val targetLanguage: String,
    val prompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val timeoutMinutes: Int,
    val audioEnabled: Boolean = false,
    val parallelWhisperEnabled: Boolean = false
) {
    val remoteConfigured: Boolean
        get() = remoteEnabled && remoteVideoEnabled &&
            remoteEndpoint.isNotBlank() && remoteModel.isNotBlank()
}

/** Central validation for the native video flags and the UI controls. */
object VideoRecognitionLimits {
    const val DEFAULT_SEGMENT_SECONDS = 30
    const val DEFAULT_MAX_FRAMES = 24
    const val DEFAULT_MAX_FPS = 2f
    const val DEFAULT_CONTEXT_SIZE = 8192
    const val DEFAULT_MAX_TOKENS = 768
    const val DEFAULT_TEMPERATURE = 0.2f
    const val DEFAULT_TIMEOUT_MINUTES = 20
    const val DEFAULT_TARGET_LANGUAGE = "English"
    const val DEFAULT_PROMPT =
        "Produce four sections: Overview; Timeline with absolute timestamps; Key details; " +
            "Visible text, translated to the target language. Describe only observable visual " +
            "content, mention people, objects, actions, and changes, and do not invent details " +
            "or claim anything about speech or audio."

    fun normalizeSegmentSeconds(value: Int): Int = value.coerceIn(5, DEFAULT_SEGMENT_SECONDS)
    fun normalizeMaxFrames(value: Int): Int = value.coerceIn(1, DEFAULT_MAX_FRAMES)
    fun normalizeMaxFps(value: Float): Float = value.takeIf { it.isFinite() }?.coerceIn(0.25f, DEFAULT_MAX_FPS)
        ?: DEFAULT_MAX_FPS
    fun normalizeContextSize(value: Int): Int = value.coerceIn(1024, 131_072)
    fun normalizeMaxTokens(value: Int): Int = value.coerceIn(64, 8_192)
    fun normalizeTemperature(value: Float): Float = value.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: DEFAULT_TEMPERATURE
    fun normalizeTimeoutMinutes(value: Int): Int = value.coerceIn(1, 120)
}
