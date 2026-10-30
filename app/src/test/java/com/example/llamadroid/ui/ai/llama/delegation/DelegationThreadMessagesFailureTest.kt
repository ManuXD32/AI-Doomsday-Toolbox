package com.example.llamadroid.ui.ai.llama.delegation

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegationThreadMessagesFailureTest {
    @Test fun joinsCodeAndMessage() {
        assertEquals("BAD_REQUEST: throughSeq required", DelegationThreadMessages.failureDetail("BAD_REQUEST", "throughSeq required"))
    }

    @Test fun skipsBlankPartsAndBoundsLength() {
        assertEquals("CODE", DelegationThreadMessages.failureDetail("CODE", "  "))
        assertEquals(200, DelegationThreadMessages.failureDetail("CODE", "x".repeat(500)).length)
    }
}
