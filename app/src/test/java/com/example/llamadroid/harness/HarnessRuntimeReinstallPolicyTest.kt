package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessRuntimeReinstallPolicyTest {
    @Test fun legacyDeletionNeverOwnsTheInstallationsCatalogOrOtherRuntimes() {
        val context = RuntimeEnvironment.getApplication()
        val legacyRoots = HarnessInstallationFiles.ownedRoots(context)
        val other = HarnessRuntimeScope.context(context, "experiment-one")
        val otherRoot = HarnessRuntimeScope.dataRoot(other).toPath()
        assertTrue(legacyRoots.none { otherRoot.startsWith(it.toPath()) })
        assertTrue(legacyRoots.none { it.name == "agent_harness" })
        assertTrue(legacyRoots.any { it.name == "agent_local_workspaces" })
    }

    @Test fun capturedContextsKeepFilesAndPreferencesIndependent() {
        val host = RuntimeEnvironment.getApplication()
        val first = HarnessRuntimeScope.context(host, "experiment-one")
        val second = HarnessRuntimeScope.context(host, "experiment-two")
        assertNotEquals(HarnessRuntimePaths.harnessHome(first), HarnessRuntimePaths.harnessHome(second))
        assertNotEquals(HarnessRuntimePaths.projects(first), HarnessRuntimePaths.projects(second))
        assertSame(first, first.applicationContext)
        first.getSharedPreferences("harness_credentials", 0).edit().putString("test", "one").commit()
        assertNull(second.getSharedPreferences("harness_credentials", 0).getString("test", null))
        first.getSharedPreferences("llamadroid_settings", 0).edit().putBoolean("agent_test", true).commit()
        assertFalse(second.getSharedPreferences("llamadroid_settings", 0).getBoolean("agent_test", false))
        assertEquals(host.filesDir, first.filesDir)
    }

    @Test fun runtimeNamesCannotBecomeFilesystemPaths() {
        val host = RuntimeEnvironment.getApplication()
        assertTrue(runCatching { HarnessRuntimeScope.context(host, "../other") }.isFailure)
        assertTrue(runCatching { HarnessRuntimeScope.dataFile(host, "agent_harness/../other") }.isFailure)
        assertEquals("Experiment / A", HarnessInstallationManager.normalizeName(" Experiment / A "))
    }
}
