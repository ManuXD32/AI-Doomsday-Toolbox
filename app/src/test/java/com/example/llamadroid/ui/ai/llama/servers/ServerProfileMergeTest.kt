package com.example.llamadroid.ui.ai.llama.servers

import com.example.llamadroid.service.LlamaServerLaunchProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfileMergeTest {
    private val stored = LlamaServerLaunchProfile(
        modelPath = "/m/model.gguf",
        mmprojPath = "/m/mmproj.gguf",
        visionEnabled = true,
        contextSize = 32768,
        temperature = 0.6f,
        idleStopSeconds = 600
    )

    @Test fun untouchedFieldsKeepStoredValuesEvenIfTheDraftWasReset() {
        // The draft came up with defaults (8K, no projector) before the baseline was taken; the user only changed temperature.
        val resetDraft = LlamaServerLaunchProfile(modelPath = "/m/model.gguf")
        val baseline = profileFingerprint(resetDraft)
        val edited = resetDraft.copy(temperature = 0.9f)
        val merged = mergeEditedProfile(stored, baseline, edited)
        assertEquals(32768, merged.contextSize)
        assertEquals("/m/mmproj.gguf", merged.mmprojPath)
        assertEquals(true, merged.visionEnabled)
        assertEquals(0.9f, merged.temperature)
    }

    @Test fun fieldsTheEditorDoesNotCaptureSurvive() {
        val draft = stored.copy(idleStopSeconds = null)
        val merged = mergeEditedProfile(stored, profileFingerprint(draft), draft.copy(contextSize = 16384))
        assertEquals(600, merged.idleStopSeconds)
        assertEquals(16384, merged.contextSize)
    }

    @Test fun clearingAFieldIsKept() {
        val merged = mergeEditedProfile(stored, profileFingerprint(stored), stored.copy(mmprojPath = null, visionEnabled = false))
        assertNull(merged.mmprojPath)
        assertEquals(false, merged.visionEnabled)
    }

    @Test fun unreadableBaselineFallsBackToTheEditedProfile() {
        val edited = stored.copy(contextSize = 4096)
        assertEquals(edited, mergeEditedProfile(stored, "", edited))
    }
}
