package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDelegationStopBarTest {
    @Test fun visibleOnlyForActiveStates() {
        assertFalse(delegationStopBarVisible(null))
        assertFalse(delegationStopBarVisible(NativeChatDelegateTaskState.COMPLETED))
        assertFalse(delegationStopBarVisible(NativeChatDelegateTaskState.CANCELLED))
        assertTrue(delegationStopBarVisible(NativeChatDelegateTaskState.RUNNING))
        assertTrue(delegationStopBarVisible(NativeChatDelegateTaskState.NEEDS_DIRECTION))
    }

    @Test fun spinnerOnlyWhileLive() {
        assertTrue(delegationStopBarSpinning(NativeChatDelegateTaskState.WAITING_HARNESS))
        assertFalse(delegationStopBarSpinning(NativeChatDelegateTaskState.INTERRUPTED))
    }
}
