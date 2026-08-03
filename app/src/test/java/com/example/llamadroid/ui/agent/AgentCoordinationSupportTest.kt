package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.service.AiRuntimeJobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoordinationSupportTest {
    @Test
    fun `newest conversation fallback skips excluded conversation`() {
        val fallback = newestConversationIdExcluding(listOf(42L, 31L, 12L), excludedId = 42L)

        assertEquals(31L, fallback)
    }

    @Test
    fun `workspace root requires both active conversation and project folder`() {
        assertNull(resolveWorkspaceProjectRoot(conversationAnchorId = null, currentProjectFolder = "alpha"))
        assertNull(resolveWorkspaceProjectRoot(conversationAnchorId = 7L, currentProjectFolder = ""))
        assertEquals("/workspace/alpha", resolveWorkspaceProjectRoot(conversationAnchorId = 7L, currentProjectFolder = "alpha"))
    }

    @Test
    fun `workspace anchor prefers the selected or runtime hint over transient active conversation nulls`() {
        assertEquals(9L, resolveWorkspaceConversationAnchor(preferredConversationId = 9L, activeConversationId = null))
        assertEquals(7L, resolveWorkspaceConversationAnchor(preferredConversationId = null, activeConversationId = 7L))
        assertNull(resolveWorkspaceConversationAnchor(preferredConversationId = null, activeConversationId = null))
    }

    @Test
    fun `workspace parent path stays clamped to project root`() {
        assertEquals(
            "/workspace/demo",
            clampWorkspaceParentPath("/workspace/demo/src", "/workspace/demo")
        )
        assertEquals(
            "/workspace/demo",
            clampWorkspaceParentPath("/workspace/demo", "/workspace/demo")
        )
        assertEquals(
            "/workspace/demo",
            clampWorkspaceParentPath("/workspace", "/workspace/demo")
        )
    }

    @Test
    fun `autosave requires stable matching runtime and active conversations`() {
        assertTrue(
            shouldAutosaveConversationSnapshot(
                runtimeConversationId = 9L,
                activeConversationId = 9L,
                isConversationRestoring = false,
                messagesEmpty = false,
                hasStreamingMessages = false,
                targetConversationId = 9L
            )
        )
        assertFalse(
            shouldAutosaveConversationSnapshot(
                runtimeConversationId = 9L,
                activeConversationId = 3L,
                isConversationRestoring = false,
                messagesEmpty = false,
                hasStreamingMessages = false,
                targetConversationId = 9L
            )
        )
        assertFalse(
            shouldAutosaveConversationSnapshot(
                runtimeConversationId = 9L,
                activeConversationId = 9L,
                isConversationRestoring = true,
                messagesEmpty = false,
                hasStreamingMessages = false,
                targetConversationId = 9L
            )
        )
    }

    @Test
    fun `live runtime messages win for the matching selected conversation`() {
        assertTrue(
            shouldPreferLiveRuntimeMessages(
                selectedConversationId = 9L,
                runtimeConversationId = 9L,
                activeConversationId = 9L,
                showConversationLoading = false,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldPreferLiveRuntimeMessages(
                selectedConversationId = 9L,
                runtimeConversationId = 3L,
                activeConversationId = 3L,
                showConversationLoading = false,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldPreferLiveRuntimeMessages(
                selectedConversationId = 9L,
                runtimeConversationId = 9L,
                activeConversationId = 9L,
                showConversationLoading = true,
                liveMessagesEmpty = false
            )
        )
    }

    @Test
    fun `screen re-entry attaches only to the matching live running conversation`() {
        assertTrue(
            shouldAttachToLiveConversationRuntime(
                targetConversationId = 9L,
                activeConversationId = 9L,
                isLoading = true,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldAttachToLiveConversationRuntime(
                targetConversationId = 9L,
                activeConversationId = 8L,
                isLoading = true,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldAttachToLiveConversationRuntime(
                targetConversationId = 9L,
                activeConversationId = 9L,
                isLoading = false,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldAttachToLiveConversationRuntime(
                targetConversationId = 9L,
                activeConversationId = 9L,
                isLoading = true,
                liveMessagesEmpty = true
            )
        )
    }

    @Test
    fun `selected conversation preview is skipped when runtime conversation already matches`() {
        assertFalse(
            shouldUseSelectedConversationPreview(
                selectedConversationId = 12L,
                runtimeConversationId = 12L,
                activeConversationId = null,
                showConversationLoading = false
            )
        )
        assertTrue(
            shouldUseSelectedConversationPreview(
                selectedConversationId = 12L,
                runtimeConversationId = 7L,
                activeConversationId = 7L,
                showConversationLoading = false
            )
        )
    }

    @Test
    fun `unrelated active job is ignored when a different conversation is already selected`() {
        val unrelatedJob = AiRuntimeJobEntity(
            jobId = "job-9",
            jobKey = "agent|9|demo",
            type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
            status = AiRuntimeJobStore.STATUS_RUNNING,
            conversationId = 9L,
            payloadJson = "{}",
            checkpointJson = "{}",
            progressText = "working",
            createdAt = 1L,
            updatedAt = 1L
        )

        val resolved = resolveRelevantAgentRuntimeJob(
            activeRuntimeJobs = listOf(unrelatedJob),
            runtimeActiveConversationId = null,
            runtimeConversationId = 42L,
            selectedConversationId = 42L
        )

        assertNull(resolved)
    }

    @Test
    fun `runtime job fallback still works when no conversation is selected`() {
        val recoverableJob = AiRuntimeJobEntity(
            jobId = "job-9",
            jobKey = "agent|9|demo",
            type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
            status = AiRuntimeJobStore.STATUS_RUNNING,
            conversationId = 9L,
            payloadJson = "{}",
            checkpointJson = "{}",
            progressText = "working",
            createdAt = 1L,
            updatedAt = 1L
        )

        val resolved = resolveRelevantAgentRuntimeJob(
            activeRuntimeJobs = listOf(recoverableJob),
            runtimeActiveConversationId = null,
            runtimeConversationId = null,
            selectedConversationId = null
        )

        assertNotNull(resolved)
        assertEquals(9L, resolved?.conversationId)
    }

    @Test
    fun `stale live runtime conversation does not override a newly selected conversation`() {
        val oldLiveJob = AiRuntimeJobEntity(
            jobId = "job-9",
            jobKey = "agent|9|demo",
            type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
            status = AiRuntimeJobStore.STATUS_RUNNING,
            conversationId = 9L,
            payloadJson = "{}",
            checkpointJson = "{}",
            progressText = "working",
            createdAt = 1L,
            updatedAt = 1L
        )

        val resolved = resolveRelevantAgentRuntimeJob(
            activeRuntimeJobs = listOf(oldLiveJob),
            runtimeActiveConversationId = 9L,
            runtimeConversationId = 42L,
            selectedConversationId = 42L
        )

        assertNull(resolved)
    }

    @Test
    fun `live runtime is only adopted when it matches the selected conversation or no selection exists`() {
        assertTrue(
            shouldAdoptLiveRuntimeConversation(
                selectedConversationId = null,
                liveConversationId = 9L,
                knownConversationIds = listOf(9L, 4L)
            )
        )
        assertTrue(
            shouldAdoptLiveRuntimeConversation(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                knownConversationIds = listOf(9L, 4L)
            )
        )
        assertFalse(
            shouldAdoptLiveRuntimeConversation(
                selectedConversationId = 4L,
                liveConversationId = 9L,
                knownConversationIds = listOf(9L, 4L)
            )
        )
    }
}
