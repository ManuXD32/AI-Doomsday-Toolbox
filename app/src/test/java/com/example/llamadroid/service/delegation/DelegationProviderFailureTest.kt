package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationProviderFailureTest {
    @Test fun contextOverflowReasonMentionsFixAndSlotTokens() {
        val text = DelegationProviderFailure.needsDirectionReason("CONTEXT_WINDOW_EXCEEDED", 4096)
        assertTrue(text.contains("4096 tokens per slot"))
        assertTrue(text.contains("unified KV cache"))
        assertTrue(text.length <= 400)
        assertTrue(!DelegationProviderFailure.needsDirectionReason("CONTEXT_WINDOW_EXCEEDED", null).contains("per slot"))
    }

    @Test fun firstFailureHintNamesCode() {
        assertTrue(DelegationProviderFailure.firstFailureHint("LLAMA_SERVER_REJECTED").contains("LLAMA_SERVER_REJECTED"))
        assertTrue(DelegationProviderFailure.firstFailureHint("CONTEXT_WINDOW_EXCEEDED").contains("context window"))
    }

    @Test fun parsesContextTokens() {
        assertEquals(8192, DelegationProviderFailure.contextTokensOf("""{"contextTokens":8192}"""))
        assertNull(DelegationProviderFailure.contextTokensOf("""{"contextTokens":null}"""))
        assertNull(DelegationProviderFailure.contextTokensOf(null))
    }
}
