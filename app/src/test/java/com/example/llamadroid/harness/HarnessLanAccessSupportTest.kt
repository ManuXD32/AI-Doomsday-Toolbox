package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessLanAccessSupportTest {
    @Test
    fun queryTokenAuthorizesAndIsRemovedBeforeForwarding() {
        val target = "/?adt_lan_token=lan-secret&route=chat"

        assertTrue(HarnessLanAccessSupport.isAuthorized(target, null, "lan-secret"))
        assertEquals("/?route=chat", HarnessLanAccessSupport.stripBootstrapToken(target))
    }

    @Test
    fun cookieTokenAuthorizesWithoutForwardingBootstrapQuery() {
        val target = "/assets/app.js"

        assertTrue(HarnessLanAccessSupport.isAuthorized(target, "theme=dark; ADT_HARNESS_LAN=lan-secret", "lan-secret"))
        assertFalse(HarnessLanAccessSupport.isAuthorized(target, "ADT_HARNESS_LAN=other", "lan-secret"))
        assertFalse(HarnessLanAccessSupport.isAuthorized(target, null, "lan-secret"))
    }

    @Test
    fun bootstrapUrlContainsOnlyLanToken() {
        assertEquals(
            "http://192.168.1.7:43127/?adt_lan_token=lan-secret",
            HarnessLanAccessSupport.bootstrapUrl("192.168.1.7", 43127, "lan-secret")
        )
        assertEquals(
            "http://[::1]:43127/?adt_lan_token=lan-secret",
            HarnessLanAccessSupport.bootstrapUrl("::1", 43127, "lan-secret")
        )
    }

    @Test
    fun indexPatchAddsTrustedGatewayMarkerOnlyToAuthorizedResponse() {
        val html = "<html><head></head><body>crypto.randomUUID()</body></html>"
        val patched = HarnessLanAccessSupport.patchIndexForLan(html)

        assertTrue(patched.contains("globalThis.crypto"))
        assertTrue(patched.contains("__ADT_AUTHENTICATED_GATEWAY__=true"))
        assertTrue(patched.contains("</head>"))
        val plain = HarnessLanAccessSupport.patchIndexForLan("<html><head></head><body>plain</body></html>")
        assertTrue(plain.contains("__ADT_AUTHENTICATED_GATEWAY__=true"))
        assertEquals(plain, HarnessLanAccessSupport.patchIndexForLan(plain))
    }

    @Test
    fun sameOriginTrustHeadersAreRewrittenAndCrossOriginIsRejected() {
        assertEquals(
            "http://127.0.0.1:43127",
            HarnessLanAccessSupport.rewriteTrustedHeader(
                "Origin", "http://192.168.1.7:43128", "192.168.1.7:43128", "http://127.0.0.1:43127"
            )
        )
        assertEquals(
            "http://127.0.0.1:43127/index.html",
            HarnessLanAccessSupport.rewriteTrustedHeader(
                "Referer", "http://192.168.1.7:43128/index.html?adt_lan_token=secret", "192.168.1.7:43128", "http://127.0.0.1:43127"
            )
        )
        assertEquals(
            null,
            HarnessLanAccessSupport.rewriteTrustedHeader(
                "Origin", "http://evil.example:43128", "192.168.1.7:43128", "http://127.0.0.1:43127"
            )
        )
    }
}
