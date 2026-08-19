package com.example.llamadroid.data.db

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaServerLaunchProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCommandProfileTest {
    @Test
    fun `new general command retains canonical profile without lossy column mapping`() {
        val profile = LlamaServerLaunchProfile(
            modelPath = "/models/main.gguf",
            host = "0.0.0.0",
            mtpUseDraftModel = true,
            draftModelPath = "/models/mtp-draft.gguf",
            contextCheckpoints = 4,
            cacheIdleSlots = false,
            nativeBinarySelection = SettingsRepository.NATIVE_BINARY_CPU_I8MM,
            nativeToolsEnabled = true
        )

        val command = savedCommandFromLaunchProfile("MTP", profile, id = 8)

        assertEquals(8, command.id)
        assertEquals(profile, command.launchProfile())
        assertTrue(command.launchProfileJson!!.contains("mtpUseDraftModel"))
        assertTrue(command.launchProfileJson!!.contains("nativeBinarySelection"))
    }

    @Test
    fun `legacy general command converts when it has no profile json`() {
        val legacy = SavedCommand(
            name = "old",
            modelPath = "/models/old.gguf",
            lowMemoryMode = true,
            flashAttention = true,
            speculativeEnabled = true,
            draftModelPath = "/models/draft.gguf",
            host = "0.0.0.0"
        )

        val converted = legacy.launchProfile()

        assertEquals("/models/old.gguf", converted.modelPath)
        assertTrue(converted.noMmap)
        assertTrue(converted.flashAttention)
        assertTrue(converted.speculativeEnabled)
        assertEquals("0.0.0.0", converted.host)
        assertFalse(converted.mtpUseDraftModel)
    }
}
