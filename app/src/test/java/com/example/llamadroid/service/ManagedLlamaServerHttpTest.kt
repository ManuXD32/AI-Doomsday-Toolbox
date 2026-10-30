package com.example.llamadroid.service

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManagedLlamaServerHttpTest {
    private lateinit var server: NanoHTTPD
    private lateinit var url: String
    @Volatile private var ready = false
    @Volatile private var slots = """[{"id":0,"n_ctx":4096,"is_processing":false,"n_past":20}]"""
    @Volatile private var metrics = "llamacpp:tokens_predicted_total 10\n"

    @Before fun start() {
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val body = when (session.uri) {
                    "/slots" -> slots
                    "/props" -> """{"default_generation_settings":{"n_ctx":8192},"n_ctx_train":131072}"""
                    "/metrics" -> metrics
                    else -> "{}"
                }
                val status = if (session.uri == "/health" && !ready)
                    Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK
                return newFixedLengthResponse(status, "application/json", body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        url = "http://127.0.0.1:${server.listeningPort}"
    }

    @After fun stop() { server.stop() }

    @Test fun `loading health is not readiness`() {
        assertFalse(ManagedLlamaServerHttp.healthy(url))
        ready = true
        assertTrue(ManagedLlamaServerHttp.healthy(url))
    }

    @Test fun `runtime slot context overrides profile and training size`() {
        assertEquals(4096, ManagedLlamaServerHttp.contextTokens(url, 32768))
    }

    @Test fun `busy and completed external inference are observable without recording content`() {
        val before = requireNotNull(ManagedLlamaServerHttp.idleObservation(url))
        assertFalse(before.busy)
        slots = """[{"id":0,"is_processing":true,"n_past":30}]"""
        assertTrue(requireNotNull(ManagedLlamaServerHttp.idleObservation(url)).busy)
        slots = """[{"id":0,"is_processing":false,"n_past":40}]"""
        metrics = "llamacpp:tokens_predicted_total 20\n"
        val after = requireNotNull(ManagedLlamaServerHttp.idleObservation(url))
        assertFalse(after.busy)
        assertNotEquals(before.fingerprint, after.fingerprint)
        slots = "{broken"
        assertNull(ManagedLlamaServerHttp.idleObservation(url))
    }

    @Test fun `partial or unrecognized slot activity cannot establish idleness`() {
        slots = """[{"is_processing":false},null]"""
        assertNull(ManagedLlamaServerHttp.idleObservation(url))
        slots = """[{"is_processing":"unknown"}]"""
        assertNull(ManagedLlamaServerHttp.idleObservation(url))
        slots = (List(256) { """{"is_processing":false}""" } + """{"is_processing":true}""")
            .joinToString(prefix = "[", postfix = "]")
        assertNull(ManagedLlamaServerHttp.idleObservation(url))
    }
}
