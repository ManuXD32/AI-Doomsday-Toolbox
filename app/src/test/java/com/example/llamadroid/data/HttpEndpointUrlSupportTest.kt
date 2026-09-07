package com.example.llamadroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpEndpointUrlSupportTest {
    @Test
    fun `scheme-less endpoint defaults to http`() {
        assertEquals(
            "http://127.0.0.1:8084",
            HttpEndpointUrlSupport.normalizeBaseUrl(" 127.0.0.1:8084/ ")
        )
    }

    @Test
    fun `managed card port is authoritative for URL-valued host`() {
        assertEquals(
            "https://server.example:8084",
            HttpEndpointUrlSupport.fromHostPort("https://server.example:9999/", 8084)
        )
    }

    @Test
    fun `managed IPv6 host is bracketed`() {
        assertEquals(
            "http://[::1]:8084",
            HttpEndpointUrlSupport.fromHostPort("::1", 8084)
        )
        assertEquals(
            "https://[2001:db8::1]:8084",
            HttpEndpointUrlSupport.fromHostPort("https://2001:db8::1", 8084)
        )
    }

    @Test
    fun `API joining keeps reverse proxy prefix and avoids duplicate v1`() {
        assertEquals(
            "https://server.example/proxy/v1/models",
            HttpEndpointUrlSupport.appendPath(
                "https://server.example/proxy/v1",
                "/v1/models"
            )
        )
        assertEquals(
            "http://127.0.0.1:8084/v1/chat/completions",
            HttpEndpointUrlSupport.appendPath(
                "127.0.0.1:8084/v1",
                "/v1/chat/completions"
            )
        )
    }

    @Test
    fun `invalid protocols and ports fail closed`() {
        assertNull(HttpEndpointUrlSupport.normalizeBaseUrl("file:///tmp/server"))
        assertNull(HttpEndpointUrlSupport.fromHostPort("127.0.0.1", 0))
        assertNull(HttpEndpointUrlSupport.normalizeBaseUrl("https://server.example:99999"))
    }
}
