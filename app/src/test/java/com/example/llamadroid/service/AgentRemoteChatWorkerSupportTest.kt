package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRemoteChatWorkerSupportTest {
    @Test
    fun `remote worker stays alive while an open root session owns it`() {
        assertFalse(shouldStopAgentRemoteWorker(openSessionCount = 1, activeJobs = 0))
        assertFalse(shouldStopAgentRemoteWorker(openSessionCount = 1, activeJobs = 2))
    }

    @Test
    fun `remote worker stops only after close session and job cleanup`() {
        assertFalse(shouldStopAgentRemoteWorker(openSessionCount = 0, activeJobs = 1))
        assertTrue(shouldStopAgentRemoteWorker(openSessionCount = 0, activeJobs = 0))
    }

    @Test
    fun `atomic snapshot follower publishes only newer revisions`() {
        assertTrue(shouldPublishRemoteSnapshot(previousRevision = -1, candidateRevision = 0))
        assertTrue(shouldPublishRemoteSnapshot(previousRevision = 4, candidateRevision = 5))
        assertFalse(shouldPublishRemoteSnapshot(previousRevision = 5, candidateRevision = 5))
        assertFalse(shouldPublishRemoteSnapshot(previousRevision = 5, candidateRevision = 4))
    }
}
