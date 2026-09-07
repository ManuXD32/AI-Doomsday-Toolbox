package com.example.llamadroid.ui.agent

import com.example.llamadroid.service.AgentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatRenderProjectionTest {
    @Test
    fun messageActionsReserveDistinctAccessibleTouchTargets() {
        assertTrue(AGENT_MESSAGE_ACTION_TOUCH_TARGET_DP >= 48)
    }

    @Test
    fun composerAddsOnlyImeSpaceNotAlreadyReservedByWindowResize() {
        assertEquals(
            600,
            remainingAgentImeBottomPx(
                fullWindowHeightPx = 2_400,
                composerBottomInWindowPx = 2_400,
                imeBottomPx = 600
            )
        )
        assertEquals(
            0,
            remainingAgentImeBottomPx(
                fullWindowHeightPx = 2_400,
                composerBottomInWindowPx = 1_800,
                imeBottomPx = 600
            )
        )
        assertEquals(
            200,
            remainingAgentImeBottomPx(
                fullWindowHeightPx = 2_400,
                composerBottomInWindowPx = 2_000,
                imeBottomPx = 600
            )
        )
    }

    @Test
    fun longHistoriesRenderOnlyRecentProjection() {
        val messages = (0 until 900).map { index ->
            AgentService.Companion.ChatMessage(
                role = if (index % 2 == 0) "assistant" else "user",
                content = "message-$index"
            )
        }

        val projection = buildAgentChatRenderProjection(messages, maxMessages = 600)

        assertEquals(600, projection.size)
        assertEquals("message-300", projection.first().content)
        assertEquals("message-899", projection.last().content)
    }

    @Test
    fun shortHistoriesRemainIntact() {
        val messages = (0 until 12).map { index ->
            AgentService.Companion.ChatMessage(role = "assistant", content = "message-$index")
        }

        val projection = buildAgentChatRenderProjection(messages, maxMessages = 600)

        assertEquals(messages, projection)
        assertTrue(projection.any { it.content == "message-0" })
    }

    @Test
    fun deliveredGuidanceShowsOnlyUserTextAndDeliveryState() {
        val message = AgentService.Companion.ChatMessage(
            role = "user",
            content = "[[AGENT_RUNTIME_QUEUED_GUIDANCE]]\nruntime instruction\n[[USER_GUIDANCE_CONTENT]]\nPlease check the failing test"
        )

        val visible = buildAgentChatRenderProjection(listOf(message)).single()

        assertEquals("Please check the failing test", visible.content)
        assertEquals("DELIVERED", visible.guidanceDeliveryState)
    }

    @Test
    fun longStreamingAndReasoningTextUsesBoundedPreview() {
        val longLine = "x".repeat(3_000)
        val preview = boundedAgentStreamingPreview(List(20) { longLine }.joinToString("\n"))

        assertTrue(preview.length <= 24_020)
        assertTrue(preview.lineSequence().all { it.length <= 2_001 })
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun restoredInvocationTimelineHidesEmptyRequestPlaceholderAndPairedToolTransport() {
        val callId = "tool-1"
        val call = AgentService.Companion.ChatMessage(
            role = "assistant",
            content = "",
            toolName = "list_directory",
            toolCallId = callId
        )
        val transportResult = AgentService.Companion.ChatMessage(
            role = "tool",
            content = "directories",
            toolCallId = callId,
            toolOutput = "directories"
        )
        val emptyRequestPlaceholder = AgentService.Companion.ChatMessage(
            role = "assistant",
            content = ""
        )

        val visible = buildVisibleAgentTimelineMessages(
            listOf(emptyRequestPlaceholder, call, transportResult),
            showAllOutput = true
        )

        assertEquals(listOf(call), visible)
    }

    @Test
    fun liveInvocationRowsReplacePersistedRowsWithoutCrossingInvocationBoundaries() {
        val persisted = AgentService.Companion.ChatMessage(
            id = "message-1",
            role = "assistant",
            content = "checkpoint",
            invocationId = "invocation-a",
            sequenceNumber = 4
        )
        val liveReplacement = persisted.copy(content = "streaming update", isStreaming = true)
        val unrelatedLive = AgentService.Companion.ChatMessage(
            id = "message-2",
            role = "assistant",
            content = "other agent",
            invocationId = "invocation-b",
            sequenceNumber = 5
        )

        val merged = mergeInvocationTimelineMessages(
            persistedMessages = listOf(persisted),
            liveMessages = listOf(liveReplacement, unrelatedLive),
            invocationId = "invocation-a"
        )

        assertEquals(1, merged.size)
        assertEquals("streaming update", merged.single().content)
        assertTrue(merged.single().isStreaming)
    }

    @Test
    fun pendingCallAgentApprovalRemainsAnActionableChatMessage() {
        val pendingApproval = AgentService.Companion.ChatMessage(
            role = "assistant",
            content = "Approve delegation",
            toolName = "call_agent",
            needsApproval = true
        )
        val approvedCall = pendingApproval.copy(needsApproval = false, isApproved = true)

        assertFalse(shouldRenderCallAgentAsDelegation(pendingApproval))
        assertTrue(shouldRenderCallAgentAsDelegation(approvedCall))
    }

    @Test
    fun retryableLoopPauseIsVisibleAndActionableWhenExtraOutputIsOff() {
        val pause = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Paused because the agent may be looping: repeated recovery",
            toolName = AgentService.Companion.RETRYABLE_NEEDS_DIRECTION_TOOL
        )

        assertTrue(AgentService.isRetryableNeedsDirectionMessage(pause))
        assertEquals(
            listOf(pause),
            buildVisibleAgentTimelineMessages(listOf(pause), showAllOutput = false)
        )
    }

    @Test
    fun genericRuntimePauseDoesNotUseLoopRetryMarker() {
        val pause = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Agent paused and needs your attention: server is offline"
        )

        assertFalse(AgentService.isRetryableNeedsDirectionMessage(pause))
        assertTrue(
            buildVisibleAgentTimelineMessages(listOf(pause), showAllOutput = false).isEmpty()
        )
    }

    @Test
    fun legacyLoopPauseRemainsRetryableAfterRestoringOlderMessage() {
        val pause = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Pausado porque el agente podria estar en bucle: recuperacion repetida"
        )

        assertTrue(AgentService.isRetryableNeedsDirectionMessage(pause))
    }

    @Test
    fun retryMarkerSurvivesRuntimeJsonPersistence() {
        val pause = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Paused because the agent may be looping: repeated recovery",
            toolName = AgentService.Companion.RETRYABLE_NEEDS_DIRECTION_TOOL
        )

        val restored = AgentService.chatMessageFromJson(AgentService.chatMessageToJson(pause))

        assertEquals(AgentService.Companion.RETRYABLE_NEEDS_DIRECTION_TOOL, restored.toolName)
        assertTrue(AgentService.isRetryableNeedsDirectionMessage(restored))
    }

    @Test
    fun explicitRetryFilterRemovesStaleLoopCardButKeepsNormalInterruption() {
        val loopPause = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Paused because the agent may be looping: repeated recovery",
            toolName = AgentService.Companion.RETRYABLE_NEEDS_DIRECTION_TOOL
        )
        val interruption = AgentService.Companion.ChatMessage(
            role = "system",
            content = "Agent paused and needs your attention: server is offline"
        )

        assertEquals(
            listOf(interruption),
            AgentService.withoutRetryableNeedsDirectionMessages(listOf(loopPause, interruption))
        )
    }
}
