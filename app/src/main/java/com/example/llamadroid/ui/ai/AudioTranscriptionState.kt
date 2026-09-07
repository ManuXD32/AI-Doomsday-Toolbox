package com.example.llamadroid.ui.ai

/**
 * State shown by the transcription input/result surface.
 *
 * Keeping input, status and error in one state object makes it impossible for a successful
 * selection or transcription to leave an obsolete validation message attached to the result.
 */
data class TranscriptionUiState(
    val selectedAudioPath: String? = null,
    val isRunning: Boolean = false,
    val transcriptionResult: String? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null
) {
    fun onAudioSelected(path: String, statusMessage: String? = null): TranscriptionUiState = copy(
        selectedAudioPath = path,
        isRunning = false,
        transcriptionResult = null,
        errorMessage = null,
        statusMessage = statusMessage
    )

    fun onInputSelectionStarted(): TranscriptionUiState = copy(
        selectedAudioPath = null,
        isRunning = false,
        transcriptionResult = null,
        errorMessage = null,
        statusMessage = null
    )

    fun onTranscriptionStarted(): TranscriptionUiState = copy(
        isRunning = true,
        transcriptionResult = null,
        errorMessage = null,
        statusMessage = null
    )

    fun onTranscriptionSucceeded(text: String): TranscriptionUiState = copy(
        isRunning = false,
        transcriptionResult = text,
        errorMessage = null,
        statusMessage = null
    )

    fun onTranscriptionFailed(message: String): TranscriptionUiState = copy(
        isRunning = false,
        transcriptionResult = null,
        errorMessage = message,
        statusMessage = null
    )

    fun onTranscriptionCancelled(): TranscriptionUiState = copy(
        isRunning = false,
        errorMessage = null,
        statusMessage = null
    )

    fun clearFeedback(): TranscriptionUiState = copy(errorMessage = null, statusMessage = null)

    fun clearError(message: String): TranscriptionUiState =
        if (errorMessage == message) copy(errorMessage = null) else this
}

enum class MicrophonePermissionState {
    Granted,
    Requestable,
    RationaleRequired,
    PermanentlyDenied
}

internal fun classifyMicrophonePermission(
    isGranted: Boolean,
    hasRequestedPermission: Boolean,
    shouldShowRationale: Boolean
): MicrophonePermissionState = when {
    isGranted -> MicrophonePermissionState.Granted
    shouldShowRationale -> MicrophonePermissionState.RationaleRequired
    hasRequestedPermission -> MicrophonePermissionState.PermanentlyDenied
    else -> MicrophonePermissionState.Requestable
}
