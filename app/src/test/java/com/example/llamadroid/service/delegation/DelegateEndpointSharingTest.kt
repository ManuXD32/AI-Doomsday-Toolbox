package com.example.llamadroid.service.delegation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateEndpointSharingTest {
    @Test
    fun sameRowShares() {
        assertTrue(DelegateEndpointSharing.sameEndpoint(1L, null, 1L, null))
    }

    @Test
    fun sameCardOnDifferentRowsShares() {
        assertTrue(DelegateEndpointSharing.sameEndpoint(1L, 7L, 2L, 7L))
    }

    @Test
    fun differentServersDoNotShare() {
        assertFalse(DelegateEndpointSharing.sameEndpoint(1L, 7L, 2L, 8L))
        assertFalse(DelegateEndpointSharing.sameEndpoint(null, null, 2L, null))
    }
}
