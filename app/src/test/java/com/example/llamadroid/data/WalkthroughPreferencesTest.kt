package com.example.llamadroid.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WalkthroughPreferencesTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun clearSettings() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
    }

    @Test
    fun `existing welcome completion makes walkthrough manual only`() {
        prefs.edit().putBoolean("has_completed_welcome", true).commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertFalse(settings.walkthrough.automaticEligible)
        assertFalse(settings.walkthrough.claimAutomaticPresentation())
    }

    @Test
    fun `new first run seeds eligibility before welcome completion and keeps it`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertTrue(settings.walkthrough.automaticEligible)
        settings.setHasCompletedWelcome(true)

        val reopened = SettingsRepository(RuntimeEnvironment.getApplication())
        assertTrue(reopened.walkthrough.automaticEligible)
    }

    @Test
    fun `claim is durable and only succeeds once across repository instances`() {
        val first = SettingsRepository(RuntimeEnvironment.getApplication())
        val second = SettingsRepository(RuntimeEnvironment.getApplication())

        assertTrue(first.walkthrough.claimAutomaticPresentation())
        assertEquals("home", first.walkthrough.progress("core"))
        assertFalse(first.walkthrough.automaticEligible)
        assertFalse(second.walkthrough.claimAutomaticPresentation())
        assertFalse(SettingsRepository(RuntimeEnvironment.getApplication()).walkthrough.automaticEligible)
    }

    @Test
    fun `claim seeds core home progress atomically without replacing existing progress`() {
        val walkthrough = WalkthroughPreferences(prefs)
        assertTrue(walkthrough.claimAutomaticPresentation())
        assertEquals("home", walkthrough.progress("core"))

        prefs.edit().clear().commit()
        val resumed = WalkthroughPreferences(prefs)
        resumed.saveProgress("core", "tools")

        assertTrue(resumed.claimAutomaticPresentation())
        assertEquals("tools", resumed.progress("core"))
    }

    @Test
    fun `defer re arms a claimed presentation durably and preserves progress`() {
        val application = RuntimeEnvironment.getApplication()
        val first = WalkthroughPreferences(prefs)
        assertTrue(first.claimAutomaticPresentation())
        first.saveProgress("core", "tools")

        assertTrue(first.deferAutomaticPresentation())

        val reopened = WalkthroughPreferences(
            application.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        )
        assertTrue(reopened.automaticEligible)
        assertEquals("tools", reopened.progress("core"))
        assertTrue(reopened.claimAutomaticPresentation())
        assertFalse(reopened.automaticEligible)
        assertEquals("tools", reopened.progress("core"))
    }

    @Test
    fun `manual presentation consumes eligibility and preserves chapter state`() {
        prefs.edit()
            .putBoolean("walkthrough_automatic_eligible", true)
            .putString("walkthrough_progress:core", "tools")
            .putBoolean("walkthrough_completed:core", true)
            .putString("walkthrough_progress:setup", "folder")
            .putBoolean("walkthrough_completed:setup", false)
            .commit()

        val first = WalkthroughPreferences(prefs)

        assertTrue(first.consumeManualPresentation())
        assertFalse(first.automaticEligible)
        assertFalse(first.consumeManualPresentation())

        val reopened = WalkthroughPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        )
        assertFalse(reopened.automaticEligible)
        assertEquals("tools", reopened.progress("core"))
        assertTrue(reopened.isCompleted("core"))
        assertEquals("folder", reopened.progress("setup"))
        assertFalse(reopened.isCompleted("setup"))
    }

    @Test
    fun `manual presentation never seeds core progress and remains consumed after restart`() {
        prefs.edit().putBoolean("has_completed_welcome", false).commit()

        val first = WalkthroughPreferences(prefs)
        assertTrue(first.automaticEligible)
        assertTrue(first.consumeManualPresentation())
        assertFalse(first.automaticEligible)
        assertNull(first.progress("core"))
        assertFalse(first.isCompleted("core"))

        val reopened = WalkthroughPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        )
        assertFalse(reopened.automaticEligible)
        assertNull(reopened.progress("core"))
        assertFalse(reopened.isCompleted("core"))
    }

    @Test
    fun `simultaneous claims from separate repositories only succeed once`() {
        val first = SettingsRepository(RuntimeEnvironment.getApplication())
        val second = SettingsRepository(RuntimeEnvironment.getApplication())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                listOf(
                    Callable { first.walkthrough.claimAutomaticPresentation() },
                    Callable { second.walkthrough.claimAutomaticPresentation() }
                )
            ).map { it.get() }

            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `progress survives restart and completion is stored per chapter`() {
        val application = RuntimeEnvironment.getApplication()
        val first = SettingsRepository(application)
        first.walkthrough.claimAutomaticPresentation()
        first.walkthrough.saveProgress("setup", "folder")

        val reopened = SettingsRepository(application)
        assertEquals("folder", reopened.walkthrough.progress("setup"))
        assertFalse(reopened.walkthrough.isCompleted("setup"))

        reopened.walkthrough.complete("setup")
        assertTrue(reopened.walkthrough.isCompleted("setup"))
        assertEquals("folder", reopened.walkthrough.progress("setup"))
    }

    @Test
    fun `chapters keep independent progress and completion state`() {
        val walkthrough = WalkthroughPreferences(prefs)

        walkthrough.saveProgress("setup", "step-1")
        walkthrough.saveProgress("runtime", "step-2")
        walkthrough.complete("setup")

        assertEquals("step-1", walkthrough.progress("setup"))
        assertEquals("step-2", walkthrough.progress("runtime"))
        assertTrue(walkthrough.isCompleted("setup"))
        assertFalse(walkthrough.isCompleted("runtime"))
    }

    @Test
    fun `reset clears only the selected chapter`() {
        val walkthrough = WalkthroughPreferences(prefs)
        walkthrough.saveProgress("setup", "step-1")
        walkthrough.complete("setup")
        walkthrough.saveProgress("runtime", "step-2")
        walkthrough.complete("runtime")

        walkthrough.reset("setup")

        assertNull(walkthrough.progress("setup"))
        assertFalse(walkthrough.isCompleted("setup"))
        assertEquals("step-2", walkthrough.progress("runtime"))
        assertTrue(walkthrough.isCompleted("runtime"))
    }

    @Test
    fun `saving skipped progress does not make it eligible for another automatic presentation`() {
        val application = RuntimeEnvironment.getApplication()
        val first = SettingsRepository(application)
        assertTrue(first.walkthrough.claimAutomaticPresentation())
        first.walkthrough.saveProgress("setup", "step-2")

        val reopened = SettingsRepository(application)
        assertFalse(reopened.walkthrough.automaticEligible)
        assertEquals("step-2", reopened.walkthrough.progress("setup"))
        assertFalse(reopened.walkthrough.isCompleted("setup"))
    }
}
