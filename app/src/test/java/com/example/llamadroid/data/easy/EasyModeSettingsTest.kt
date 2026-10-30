package com.example.llamadroid.data.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EasyModeSettingsTest {
    @Test
    fun `intro shows until marked seen`() {
        val s = EasyModeSettings(RuntimeEnvironment.getApplication())
        s.introSeen = false
        assertTrue(s.shouldShowIntro())
        s.markIntroSeen()
        assertFalse(s.shouldShowIntro())
    }

    @Test
    fun `enabled flow is shared between instances`() {
        val app = RuntimeEnvironment.getApplication()
        val a = EasyModeSettings(app)
        val b = EasyModeSettings(app)
        a.enabled = true
        assertTrue(b.enabledFlow.value)
        a.enabled = false
        assertFalse(b.enabledFlow.value)
    }

    @Test
    fun `context override is stored per model and 0 clears it`() {
        val s = EasyModeSettings(RuntimeEnvironment.getApplication())
        s.setContextOverrideFor("/m/a.gguf", 16384)
        assertEquals(16384, s.contextOverrideFor("/m/a.gguf"))
        assertEquals(0, s.contextOverrideFor("/m/b.gguf"))
        s.setContextOverrideFor("/m/a.gguf", 0)
        assertEquals(0, s.contextOverrideFor("/m/a.gguf"))
    }
}
