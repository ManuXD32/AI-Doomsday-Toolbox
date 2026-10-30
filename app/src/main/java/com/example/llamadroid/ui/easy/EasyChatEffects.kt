package com.example.llamadroid.ui.easy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.data.easy.EasyModelNames
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.ui.ai.llama.chat.ChatSettingsState

/** Friendly name of the chat's model: curated bundle title, else the file name without `.gguf`. */
@Composable
internal fun rememberEasyModelName(modelFilename: String?): String =
    EasyModelNames.friendlyName(modelFilename, EasyModelNames.curatedTitleRes(modelFilename)?.let { stringResource(it) })

/**
 * Easy chat audio policy. Models with a native audio encoder (e.g. Gemma 4 E2B) take audio
 * directly; Whisper (the Easy mic model) is forced only when the model has no direct audio.
 */
@Composable
internal fun EasyChatAudioPolicyEffect(
    easyChat: Boolean,
    settings: ChatSettingsState,
    easyWhisperPath: String,
    directAudioCapable: Boolean
) {
    LaunchedEffect(easyChat, settings.loaded, easyWhisperPath, directAudioCapable) {
        if (!easyChat || !settings.loaded) return@LaunchedEffect
        if (directAudioCapable) {
            if (settings.audioInputMode == NativeChatToolConfig.AUDIO_INPUT_WHISPER) {
                settings.audioInputMode = NativeChatToolConfig.AUDIO_INPUT_DIRECT
            }
        } else if (easyWhisperPath.isNotBlank() &&
            (settings.whisperModelPath != easyWhisperPath ||
                settings.audioInputMode != NativeChatToolConfig.AUDIO_INPUT_WHISPER)
        ) {
            settings.audioInputMode = NativeChatToolConfig.AUDIO_INPUT_WHISPER
            settings.whisperModelPath = easyWhisperPath
        }
    }
}
