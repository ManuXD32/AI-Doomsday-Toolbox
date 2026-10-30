package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProviderErrorCodesTest {
    @Test fun contextOverflowVariantsMap() {
        listOf(
            "llama-server error: {\"error\":{\"type\":\"exceed_context_size_error\"}}",
            "request (9000 tokens) exceeds the available context size (4096 tokens)",
            "Prompt would Exceed the context",
        ).forEach {
            assertEquals(it, HarnessProviderErrorCodes.CONTEXT_WINDOW_EXCEEDED, HarnessProviderErrorCodes.classify(Exception(it)))
        }
    }

    @Test fun overflowFoundInCauseChain() {
        val error = RuntimeException("wrapper", Exception("llama-server error: exceed_context_size_error"))
        assertEquals("CONTEXT_WINDOW_EXCEEDED", HarnessProviderErrorCodes.classify(error))
    }

    @Test fun otherLlamaServerErrorsAreRejected() {
        assertEquals("LLAMA_SERVER_REJECTED", HarnessProviderErrorCodes.classify(Exception("llama-server error: bad request")))
    }

    @Test fun connectionFailuresAreUnreachable() {
        assertEquals("LLAMA_SERVER_UNREACHABLE", HarnessProviderErrorCodes.classify(java.net.ConnectException("x")))
        assertEquals("LLAMA_SERVER_UNREACHABLE", HarnessProviderErrorCodes.classify(java.net.SocketTimeoutException()))
        assertEquals("LLAMA_SERVER_UNREACHABLE", HarnessProviderErrorCodes.classify(Exception("Connection refused")))
    }

    @Test fun unknownIsNull() {
        assertNull(HarnessProviderErrorCodes.classify(Exception("boom")))
    }

    @Test fun diagnosticBoundsSnippet() {
        val text = HarnessProviderErrorCodes.diagnostic("X", Exception("llama-server error: " + "a".repeat(500)))
        assertTrue(text.length < 320)
        assertTrue(text.contains("class=Exception"))
    }

    @Test fun midStreamDisconnectsAreConnectionLost() {
        assertEquals("LLAMA_SERVER_CONNECTION_LOST", HarnessProviderErrorCodes.classify(java.io.EOFException()))
        assertEquals("LLAMA_SERVER_CONNECTION_LOST", HarnessProviderErrorCodes.classify(java.net.SocketException("Connection reset")))
        assertEquals("LLAMA_SERVER_CONNECTION_LOST", HarnessProviderErrorCodes.classify(java.io.IOException("unexpected end of stream")))
    }

    @Test fun parserFailuresGetTheirOwnCode() {
        assertEquals("ADAPTER_STREAM_ERROR", HarnessProviderErrorCodes.classify(RuntimeException("x", org.json.JSONException("bad"))))
    }

    @Test fun bodySnippetIsBounded() {
        val snippet = HarnessProviderErrorCodes.bodySnippet(Exception("llama-server error: " + "a".repeat(500)))
        assertEquals(200, snippet?.length)
        assertNull(HarnessProviderErrorCodes.bodySnippet(Exception("boom")))
    }
}
