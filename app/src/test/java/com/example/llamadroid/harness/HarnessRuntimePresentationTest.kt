package com.example.llamadroid.harness

import com.example.llamadroid.R
import com.example.llamadroid.data.db.HarnessRuntimeEntity
import com.example.llamadroid.harness.runtime.HarnessRuntimeException
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeStatus
import org.junit.Assert.*
import org.junit.Test

class HarnessRuntimePresentationTest {
    @Test fun authenticatedEndpointPublishesWebUiWithoutASeparateSetupForm() {
        val endpoint = com.example.llamadroid.harness.runtime.HarnessEndpoint("http://127.0.0.1:43127", "dsh-session=private-cookie")
        val web = endpoint.toWebUiPresentation()
        assertTrue(web.available)
        assertTrue(web.authenticated)
        assertEquals(endpoint.origin, web.endpointLabel)
        assertFalse(web.toString().contains("private-cookie"))
        assertFalse((null as com.example.llamadroid.harness.runtime.HarnessEndpoint?).toWebUiPresentation().available)
    }

    @Test fun runningProcessCanShowAnInterruptedChatConnection() {
        val rows = listOf(HarnessRuntimeDiagnostic(1, 1, "websocket", outcome = "failure", httpStatus = 403))
        val result = runtimePresentation(HarnessRuntimeEntity(state = "RUNNING"), null, rows, Int::toString)
        assertEquals(HarnessRuntimeStatus.RUNNING, result.status)
        assertEquals(R.string.harness_connection_interrupted.toString(), result.connectionLabel)
        assertTrue(result.diagnostics.single().connectionMetadata.orEmpty().contains("HTTP 403"))
    }
    @Test fun startupErrorKeepsItsCodeThroughWrappedExceptions() {
        val failure = IllegalStateException("private output", HarnessRuntimeException("HARNESS_NOT_READY", "private URL"))
        assertEquals("HARNESS_NOT_READY", harnessLifecycleErrorCode(failure))
        assertEquals("HARNESS_ILLEGALSTATEEXCEPTION", harnessLifecycleErrorCode(IllegalStateException("private output")))
    }

    @Test fun preOwnerStartupFailureStillHasAnActionableState() {
        val result = runtimePresentation(null, "HARNESS_NATIVE_UNAVAILABLE", emptyList(), Int::toString)
        assertEquals(HarnessRuntimeStatus.ERROR, result.status)
        assertTrue(result.canStart)
        assertEquals("HARNESS_NATIVE_UNAVAILABLE", result.errorCode)
        assertEquals(R.string.harness_start_payload_failed.toString(), result.detail)
    }

    @Test fun durableStartEventCannotMakeARecoveredAppLookBusy() {
        val rows = listOf(HarnessRuntimeDiagnostic(1, 1, "app_start_requested"))
        val recovered = runtimePresentation(null, null, rows, Int::toString)
        assertEquals(HarnessRuntimeStatus.STOPPED, recovered.status)
        assertTrue(recovered.canStart)
        val starting = runtimePresentation(null, null, rows, Int::toString, startingInApp = true)
        assertEquals(HarnessRuntimeStatus.STARTING, starting.status)
        assertFalse(starting.canStart)
    }

    @Test fun authenticationTimeoutIsNotPresentedAsStopTimeout() {
        val result = runtimePresentation(HarnessRuntimeEntity(state = "FAILED", errorCode = "HARNESS_NOT_READY"), null, emptyList(), Int::toString)
        assertEquals(R.string.harness_start_readiness_failed.toString(), result.detail)
        val stopping = runtimePresentation(HarnessRuntimeEntity(state = "STOP_REQUESTED", errorCode = "HARNESS_STOP_TIMEOUT"), null, emptyList(), Int::toString)
        assertEquals(R.string.harness_runtime_stop_timeout.toString(), stopping.detail)
        assertTrue(stopping.canForceStop)
        assertFalse(stopping.canStart)
    }

    @Test fun startupPhasesAndErrorsRemainAvailableWithoutAnAuthenticatedSession() {
        val rows = listOf(
            HarnessRuntimeDiagnostic(1, 1, "phase", phase = "payload_preparing", generation = "generation"),
            HarnessRuntimeDiagnostic(2, 2, "start_failed", errorCode = "HARNESS_EXECUTABLE_MISSING", exitCode = 127),
        )
        val result = runtimePresentation(HarnessRuntimeEntity(state = "FAILED", generation = "generation"), null, rows, Int::toString)
        assertEquals(R.string.harness_event_payload_preparing.toString(), result.phaseLabel)
        assertEquals(2, result.diagnostics.size)
        assertEquals(127, result.diagnostics.last().exitCode)
        assertTrue(runtimeDiagnosticsText(rows).contains("error=HARNESS_EXECUTABLE_MISSING exitCode=127"))
    }
}
