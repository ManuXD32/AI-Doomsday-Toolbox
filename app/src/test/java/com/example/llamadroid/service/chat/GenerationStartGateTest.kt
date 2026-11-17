package com.example.llamadroid.service.chat

import com.example.llamadroid.service.chat.GenerationStartGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationStartGateTest {
    @Test fun noSessionStarts() {
        assertEquals(Decision.START, GenerationStartGate.decide(hasSession = false, stopRequested = false))
        assertEquals(Decision.START, GenerationStartGate.decide(hasSession = false, stopRequested = true))
    }

    @Test fun stoppedSessionWaits() {
        assertEquals(Decision.WAIT_THEN_START, GenerationStartGate.decide(hasSession = true, stopRequested = true))
    }

    @Test fun liveSessionIsBusy() {
        assertEquals(Decision.REJECT_BUSY, GenerationStartGate.decide(hasSession = true, stopRequested = false))
    }
}
