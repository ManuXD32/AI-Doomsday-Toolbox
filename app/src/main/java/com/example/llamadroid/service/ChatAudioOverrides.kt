package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerEntity

/**
 * Applies a chat's audio-input options on top of the server card. The server only describes what it
 * can do (vision/audio/video capability); whether audio is sent directly or transcribed with Whisper
 * is a per-chat choice. Pure so it can be unit tested.
 */
fun LlamaServerEntity.withChatAudioOverrides(
    config: NativeChatToolConfig,
    directRejected: Boolean = false
): LlamaServerEntity {
    val preferWhisper = NativeChatMediaRouting.audioUsesWhisper(
        config.audioInputMode, preferWhisperAudioTranscription, directRejected
    )
    // "Send audio directly" overrides an unreliable capability flag for engines that can carry audio.
    val forceDirect = NativeChatMediaRouting.forcesDirectAudio(config.audioInputMode, directRejected) &&
        (usesOpenAiCompatibleEngine() || isLiteRtEngine())
    val audioFlag = supportsAudio || forceDirect
    val modelPath = config.whisperModelPath?.trim()?.takeIf { it.isNotBlank() } ?: whisperModelPath
    val language = config.whisperLanguage?.trim()?.takeIf { it.isNotBlank() } ?: whisperLanguage
    if (preferWhisper == preferWhisperAudioTranscription && audioFlag == supportsAudio &&
        modelPath == whisperModelPath && language == whisperLanguage
    ) {
        return this
    }
    return copy(
        supportsAudio = audioFlag,
        preferWhisperAudioTranscription = preferWhisper,
        whisperModelPath = modelPath,
        whisperLanguage = language
    )
}

/** Applies the video options: native mode is tried even when the server is not flagged for video. */
fun LlamaServerEntity.withChatVideoOverrides(effectiveVideoMode: String?): LlamaServerEntity =
    if (NativeChatMediaRouting.forcesNativeVideo(effectiveVideoMode) && !supportsVideo &&
        (isLlamaServerEngine() || isLlamaSwapEngine())
    ) copy(supportsVideo = true) else this

/** Convenience for call sites that only have the persisted chat API params. */
fun LlamaServerEntity.withChatAudioOverrides(apiParams: String?, directRejected: Boolean = false): LlamaServerEntity =
    withChatAudioOverrides(NativeChatToolConfig.fromApiParams(apiParams), directRejected)

/**
 * Per-chat audio params copied from the server card when a chat is created. Only values that
 * differ from the neutral defaults are returned, so servers without Whisper settings add nothing.
 */
fun LlamaServerEntity.chatAudioSeedParams(): Map<String, Any> = buildMap {
    if (preferWhisperAudioTranscription && hasDirectAudioInputCapability()) {
        put(NativeChatToolConfig.KEY_AUDIO_INPUT_MODE, NativeChatToolConfig.AUDIO_INPUT_WHISPER)
    }
    whisperModelPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(NativeChatToolConfig.KEY_WHISPER_MODEL_PATH, it) }
    whisperLanguage.trim()
        .takeIf { it.isNotBlank() && it != LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE }
        ?.let { put(NativeChatToolConfig.KEY_WHISPER_LANGUAGE, it) }
}
