package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTranscriptionStateTest {

    @Test
    fun `valid selection clears previous validation and result`() {
        val state = TranscriptionUiState(
            transcriptionResult = "old result",
            errorMessage = "Please select an audio file"
        ).onAudioSelected("/cache/input.wav")

        assertEquals("/cache/input.wav", state.selectedAudioPath)
        assertNull(state.transcriptionResult)
        assertNull(state.errorMessage)
        assertNull(state.statusMessage)
        assertEquals(false, state.isRunning)
    }

    @Test
    fun `successful transcription cannot retain an error`() {
        val running = TranscriptionUiState(errorMessage = "old error")
            .onTranscriptionStarted()
        assertEquals(true, running.isRunning)

        val state = running
            .onTranscriptionSucceeded("The orbit mark is blue.")

        assertEquals("The orbit mark is blue.", state.transcriptionResult)
        assertNull(state.errorMessage)
        assertNull(state.statusMessage)
        assertEquals(false, state.isRunning)
    }

    @Test
    fun `failed transcription replaces informational status`() {
        val state = TranscriptionUiState(statusMessage = "Video loaded")
            .onTranscriptionFailed("Whisper failed")

        assertEquals("Whisper failed", state.errorMessage)
        assertNull(state.statusMessage)
    }

    @Test
    fun `permission classification distinguishes every recovery state`() {
        assertEquals(
            MicrophonePermissionState.Granted,
            classifyMicrophonePermission(true, true, false)
        )
        assertEquals(
            MicrophonePermissionState.Requestable,
            classifyMicrophonePermission(false, false, false)
        )
        assertEquals(
            MicrophonePermissionState.RationaleRequired,
            classifyMicrophonePermission(false, true, true)
        )
        assertEquals(
            MicrophonePermissionState.PermanentlyDenied,
            classifyMicrophonePermission(false, true, false)
        )
    }
}
