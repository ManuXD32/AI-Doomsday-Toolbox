package com.example.llamadroid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProbeStateTest {
    @Test
    fun `completed missing binary is unavailable rather than pending`() {
        val availability = videoUiAvailability(
            binaryCapabilities = null,
            binaryProbePending = false,
            binaryProbeUnavailable = true
        )

        assertFalse(availability.binaryProbePending)
        assertTrue(availability.binaryProbeUnavailable)
        assertFalse(availability.workflowEnabled)
        // Controls stay visible/editable so the user can recover saved values.
        assertTrue(availability.isFlagEnabled("--guidance"))
    }

    @Test
    fun `pending binary keeps controls editable until probe completes`() {
        val availability = videoUiAvailability(
            binaryCapabilities = null,
            binaryProbePending = true,
            binaryProbeUnavailable = false
        )

        assertTrue(availability.binaryProbePending)
        assertFalse(availability.binaryProbeUnavailable)
        assertTrue(availability.workflowEnabled)
        assertTrue(availability.isFlagEnabled("--guidance"))
    }
}
