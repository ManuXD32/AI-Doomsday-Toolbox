package com.example.llamadroid.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure routing decisions for per-chat audio/video input. Capability flags on server cards are
 * unreliable, so a chat may force "direct"/"native" and the service falls back once to Whisper /
 * the transcript route when the server rejects the media. Everything here is unit tested.
 */
object NativeChatMediaRouting {
    enum class VideoRoute { NATIVE, TRANSCRIPT, UNSUPPORTED }

    /** True when the turn should skip direct audio and use Whisper for [mode]. */
    fun audioUsesWhisper(
        mode: String?,
        serverPrefersWhisper: Boolean,
        directRejected: Boolean
    ): Boolean = when (NativeChatToolConfig.normalizeAudioInputMode(mode)) {
        NativeChatToolConfig.AUDIO_INPUT_WHISPER -> true
        NativeChatToolConfig.AUDIO_INPUT_DIRECT -> directRejected
        else -> serverPrefersWhisper
    }

    /** "Direct" overrides the capability flag, unless the server already rejected audio. */
    fun forcesDirectAudio(mode: String?, directRejected: Boolean): Boolean =
        !directRejected &&
            NativeChatToolConfig.normalizeAudioInputMode(mode) == NativeChatToolConfig.AUDIO_INPUT_DIRECT

    /** Effective video mode after a remembered native failure: native degrades to transcript. */
    fun effectiveVideoMode(mode: String?, nativeRejected: Boolean): String {
        val normalized = NativeChatToolConfig.normalizeVideoInputMode(mode)
        return if (normalized == NativeChatToolConfig.VIDEO_INPUT_NATIVE && nativeRejected) {
            NativeChatToolConfig.VIDEO_INPUT_TRANSCRIPT
        } else {
            normalized
        }
    }

    fun forcesNativeVideo(effectiveMode: String?): Boolean =
        NativeChatToolConfig.normalizeVideoInputMode(effectiveMode) == NativeChatToolConfig.VIDEO_INPUT_NATIVE

    fun videoRoute(effectiveMode: String?, serverNativeCapable: Boolean, policyUsesTranscript: Boolean): VideoRoute =
        when (NativeChatToolConfig.normalizeVideoInputMode(effectiveMode)) {
            NativeChatToolConfig.VIDEO_INPUT_TRANSCRIPT -> VideoRoute.TRANSCRIPT
            NativeChatToolConfig.VIDEO_INPUT_NATIVE -> VideoRoute.NATIVE
            else -> when {
                policyUsesTranscript -> VideoRoute.TRANSCRIPT
                serverNativeCapable -> VideoRoute.NATIVE
                else -> VideoRoute.UNSUPPORTED
            }
        }

    private val rejectionHints = listOf(
        "audio input", "input_audio", "mmproj", "multimodal", "multi-modal", "does not support",
        "not supported", "unsupported", "video", "image input", "invalid", "bad request"
    )
    private val httpErrorRegex = Regex("""server error\s+[45]\d\d""", RegexOption.IGNORE_CASE)

    /** Heuristic for llama-server errors caused by media the server cannot take (HTTP 4xx/5xx or known phrases). */
    fun looksLikeMediaRejection(message: String?): Boolean {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (httpErrorRegex.containsMatchIn(text)) return true
        val lower = text.lowercase()
        return rejectionHints.any { lower.contains(it) }
    }

    /** Fallback applies once, only to a clean failure (nothing streamed) with the chosen media in play. */
    fun shouldFallBack(
        forcedMode: Boolean,
        mediaInTurn: Boolean,
        alreadyFellBack: Boolean,
        streamedAnything: Boolean,
        message: String?
    ): Boolean = forcedMode && mediaInTurn && !alreadyFellBack && !streamedAnything && looksLikeMediaRejection(message)
}

/** Process-lifetime memory of chats whose server rejected direct audio / native video. */
object NativeChatMediaFailures {
    private val audio = ConcurrentHashMap.newKeySet<Long>()
    private val video = ConcurrentHashMap.newKeySet<Long>()

    fun audioRejected(chatId: Long): Boolean = audio.contains(chatId)
    fun videoRejected(chatId: Long): Boolean = video.contains(chatId)
    fun markAudioRejected(chatId: Long) { audio.add(chatId) }
    fun markVideoRejected(chatId: Long) { video.add(chatId) }
    fun clear(chatId: Long) { audio.remove(chatId); video.remove(chatId) }
}
