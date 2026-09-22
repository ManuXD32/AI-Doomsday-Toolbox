package com.example.llamadroid.harness

import com.example.llamadroid.data.db.HarnessRuntimeEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProjectRemovalPolicyTest {
    @Test fun startupAndPartlyCleanedOwnersBlockRemoval() {
        assertFalse(canRemoveHarnessProject(null, starting = true, connected = false))
        assertFalse(canRemoveHarnessProject(null, starting = false, connected = true))
        for (state in listOf("STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING", "INTERRUPTED")) {
            assertFalse(canRemoveHarnessProject(HarnessRuntimeEntity(state = state), false, false))
        }
        assertFalse(canRemoveHarnessProject(HarnessRuntimeEntity(state = "FAILED", brokerPid = 42), false, false))
    }

    @Test fun neverStartedAndConfirmedStoppedProjectsCanBeRemoved() {
        assertTrue(canRemoveHarnessProject(null, false, false))
        assertTrue(canRemoveHarnessProject(HarnessRuntimeEntity(state = "FAILED"), false, false))
        assertTrue(canRemoveHarnessProject(HarnessRuntimeEntity(state = "STOPPED", brokerPid = 42), false, false))
    }
}
