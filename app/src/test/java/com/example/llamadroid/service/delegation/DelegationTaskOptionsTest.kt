package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationTaskOptionsTest {
    @Test
    fun roundTripKeepsNewFields() {
        val o = DelegationTaskOptions(projectId = "p", threadId = "t", pendingAttachmentRefs = listOf("f1"), newProject = true, freshThread = true)
        assertEquals(o, DelegationTaskOptions.decode(o.encode()))
    }

    @Test
    fun oldJsonWithoutNewFieldsDecodesToFalse() {
        val o = DelegationTaskOptions.decode("{\"projectId\":\"p\",\"pendingAttachmentRefs\":[\"f1\"]}")
        assertEquals("p", o.projectId)
        assertFalse(o.newProject)
        assertFalse(o.freshThread)
    }

    @Test
    fun consumedClearsOnlyTheOneShotFlags() {
        val c = DelegateLoopBind.consumed(DelegationTaskOptions(projectId = "p", newProject = true, freshThread = true))
        assertFalse(c.newProject || c.freshThread)
        assertEquals("p", c.projectId)
        assertTrue(DelegationTaskOptions.decode(c.encode()) == c)
    }
}
