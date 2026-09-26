package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AiRuntimeJobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoordinationSupportTest {
    @Test
    fun `canonical conversation backend overrides a stale restored snapshot`() {
        assertEquals(
            AgentWorkspaceBackendType.LOCAL_SANDBOX,
            resolveRestoredWorkspaceBackend(
                canonicalConversationBackend = "LOCAL_SANDBOX",
                snapshotBackend = AgentWorkspaceBackendType.REMOTE_SSH
            )
        )
        assertEquals(
            AgentWorkspaceBackendType.REMOTE_SSH,
            resolveRestoredWorkspaceBackend(
                canonicalConversationBackend = "REMOTE_SSH",
                snapshotBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX
            )
        )
    }

    @Test
    fun `invalid canonical backend preserves the restored snapshot value`() {
        assertEquals(
            AgentWorkspaceBackendType.LOCAL_SANDBOX,
            resolveRestoredWorkspaceBackend(
                canonicalConversationBackend = "",
                snapshotBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX
            )
        )
    }

    @Test
    fun `saved messages only short circuit persisted restore during a live generation`() {
        assertTrue(
            shouldSkipPersistedRuntimeRestore(
                isLoading = true,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldSkipPersistedRuntimeRestore(
                isLoading = false,
                liveMessagesEmpty = false
            )
        )
        assertFalse(
            shouldSkipPersistedRuntimeRestore(
                isLoading = true,
                liveMessagesEmpty = true
            )
        )
    }

    @Test
    fun `cold restore surfaces stale idle conversation with an enqueued continuation`() {
        assertTrue(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "generation_finished",
                latestBoundaryStatus = "tool_calls"
            )
        )
    }

    @Test
    fun `cold restore ignores an old open context after a terminal boundary`() {
        assertFalse(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "generation_finished",
                latestBoundaryStatus = "stop",
                latestBoundaryTimestamp = 200L,
                pendingContinuationTimestamp = 100L
            )
        )
    }

    @Test
    fun `cold restore ignores stale continuation after a terminal session boundary`() {
        assertFalse(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "agent_session_end",
                latestBoundaryStatus = "SUCCESS",
                latestBoundaryTimestamp = 200L,
                pendingContinuationTimestamp = 100L
            )
        )
    }

    @Test
    fun `cold restore keeps an unfinished tool result recoverable`() {
        assertTrue(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = false,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "tool_result",
                latestBoundaryStatus = "ERROR",
                latestBoundaryTimestamp = 200L,
                pendingContinuationTimestamp = 150L
            )
        )
    }

    @Test
    fun `cold restore ignores stale continuation after successful finish task`() {
        assertFalse(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "tool_output_prepared",
                latestBoundaryStatus = "OK",
                latestBoundaryToolName = "finish_task",
                latestBoundaryTimestamp = 200L,
                pendingContinuationTimestamp = 100L
            )
        )
    }

    @Test
    fun `cold restore recognizes a tool call boundary still awaiting execution`() {
        assertTrue(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = false,
                latestBoundaryEventType = "tool_call",
                latestBoundaryStatus = "OK"
            )
        )
    }

    @Test
    fun `cold restore keeps a newer continuation after a terminal model boundary`() {
        assertTrue(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "generation_finished",
                latestBoundaryStatus = "stop",
                latestBoundaryTimestamp = 100L,
                pendingContinuationTimestamp = 200L
            )
        )
    }

    @Test
    fun `cold restore recognizes an assistant generation with no terminal boundary`() {
        assertTrue(
            shouldSurfaceColdRecovery(
                hasLiveOwner = false,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = false,
                latestBoundaryEventType = "chat_message_assistant",
                latestBoundaryStatus = "RUNNING"
            )
        )
    }

    @Test
    fun `cold restore never interrupts a conversation still owned by the service`() {
        assertFalse(
            shouldSurfaceColdRecovery(
                hasLiveOwner = true,
                hasOpenTurnContext = true,
                hasPendingToolContinuation = true,
                latestBoundaryEventType = "chat_message_assistant",
                latestBoundaryStatus = "RUNNING"
            )
        )
    }

    @Test
    fun `selected interrupted conversation exposes explicit recovery only when idle`() {
        assertTrue(
            shouldShowSelectedConversationRecovery(
                selectedConversationId = 9L,
                conversationId = 9L,
                resumeState = "INTERRUPTED",
                isWorking = false,
                hasLiveOwner = false
            )
        )
        assertFalse(
            shouldShowSelectedConversationRecovery(
                selectedConversationId = 9L,
                conversationId = 9L,
                resumeState = AgentService.RESUME_STATE_IDLE,
                isWorking = false,
                hasLiveOwner = false
            )
        )
        assertFalse(
            shouldShowSelectedConversationRecovery(
                selectedConversationId = 9L,
                conversationId = 9L,
                resumeState = "INTERRUPTED",
                isWorking = true,
                hasLiveOwner = false
            )
        )
        assertFalse(
            shouldShowSelectedConversationRecovery(
                selectedConversationId = 9L,
                conversationId = 9L,
                resumeState = "INTERRUPTED",
                isWorking = false,
                hasLiveOwner = true
            )
        )
    }

    @Test
    fun `explicit failure pause exposes Continue after ownership is released`() {
        assertTrue(shouldShowSelectedConversationRecovery(
            selectedConversationId = 10L,
            conversationId = 10L,
            resumeState = AgentService.RESUME_STATE_NEEDS_DIRECTION,
            isWorking = false,
            hasLiveOwner = false
        ))
        assertFalse(shouldShowSelectedConversationRecovery(
            selectedConversationId = 10L,
            conversationId = 10L,
            resumeState = AgentService.RESUME_STATE_NEEDS_DIRECTION,
            isWorking = true,
            hasLiveOwner = false
        ))
    }

    @Test
    fun `pending user answers do not expose a generic continuation shortcut`() {
        assertFalse(shouldShowSelectedConversationRecovery(
            selectedConversationId = 9L,
            conversationId = 9L,
            resumeState = AgentService.RESUME_STATE_WAITING_FOR_USER,
            isWorking = false,
            hasLiveOwner = false
        ))
    }

    @Test
    fun `knowledge header stacks before accessibility text reaches two hundred percent`() {
        assertFalse(shouldStackAgentKnowledgeHeader(1.0f))
        assertFalse(shouldStackAgentKnowledgeHeader(1.49f))
        assertTrue(shouldStackAgentKnowledgeHeader(1.5f))
        assertTrue(shouldStackAgentKnowledgeHeader(2.0f))
    }

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
    fun `same process checkpoint does not replace matching live conversation`() {
        assertTrue(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-a",
                checkpointConversationId = 9L
            )
        )
    }

    @Test
    fun `different process checkpoint remains eligible for restore`() {
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-b"
            )
        )
    }

    @Test
    fun `missing or blank process generation remains eligible for restore`() {
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = null,
                currentProcessGeneration = "process-a"
            )
        )
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = " ",
                currentProcessGeneration = "process-a"
            )
        )
    }

    @Test
    fun `empty live messages remain eligible for restore`() {
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = true,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-a"
            )
        )
    }

    @Test
    fun `mismatched conversation identities remain eligible for restore`() {
        val base = shouldSkipSameProcessRuntimeRestore(
            selectedConversationId = 9L,
            liveConversationId = 9L,
            jobConversationId = 9L,
            liveMessagesEmpty = false,
            checkpointProcessGeneration = "process-a",
            currentProcessGeneration = "process-a"
        )
        assertTrue(base)
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 8L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-a"
            )
        )
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 8L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-a"
            )
        )
        assertFalse(
            shouldSkipSameProcessRuntimeRestore(
                selectedConversationId = 9L,
                liveConversationId = 9L,
                jobConversationId = 9L,
                liveMessagesEmpty = false,
                checkpointProcessGeneration = "process-a",
                currentProcessGeneration = "process-a",
                checkpointConversationId = 8L
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
