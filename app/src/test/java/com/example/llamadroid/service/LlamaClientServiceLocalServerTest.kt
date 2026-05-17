package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaClientServiceLocalServerTest {
    @Test
    fun loopbackHostDetection_acceptsLocalLlamaServerHosts() {
        assertTrue(isNativeChatLoopbackHost("localhost"))
        assertTrue(isNativeChatLoopbackHost("127.0.0.1"))
        assertTrue(isNativeChatLoopbackHost("http://127.0.0.1"))
        assertTrue(isNativeChatLoopbackHost("::1"))
        assertTrue(isNativeChatLoopbackHost("[::1]"))
    }

    @Test
    fun loopbackHostDetection_rejectsRemoteHosts() {
        assertFalse(isNativeChatLoopbackHost("192.168.1.20"))
        assertFalse(isNativeChatLoopbackHost("example.com"))
    }

    @Test
    fun nativeChatLocalHostForServer_preservesIpFamily() {
        assertEquals("127.0.0.1", nativeChatLocalHostForServer("localhost"))
        assertEquals("127.0.0.1", nativeChatLocalHostForServer("http://127.0.0.1"))
        assertEquals("::1", nativeChatLocalHostForServer("[::1]"))
    }
}
