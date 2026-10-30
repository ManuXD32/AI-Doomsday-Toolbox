package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.service.delegation.DelegationServerChoice
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDelegationLogicTest {
    @Test
    fun numberValidationAcceptsOnlyBoundedIntegers() {
        assertEquals(8192, validateDelegationContext(" 8192 "))
        assertNull(validateDelegationContext(""))
        assertNull(validateDelegationContext("abc"))
        assertNull(validateDelegationContext((NativeChatDelegationConfig.MIN_CONTEXT_TOKENS - 1).toString()))
        assertNull(validateDelegationContext((NativeChatDelegationConfig.MAX_CONTEXT_TOKENS + 1).toString()))
        assertEquals(256, validateDelegationMaxOutput("256"))
        assertNull(validateDelegationMaxOutput("255"))
        assertNull(validateDelegationMaxOutput((NativeChatDelegationConfig.MAX_OUTPUT_TOKENS + 1).toString()))
    }

    @Test
    fun subagentRequiresOpenAiCompatibleEngineButHarnessAcceptsAll() {
        assertTrue(isDelegationServerEligible("llama-server", forSubagent = true))
        assertTrue(isDelegationServerEligible("llama-swap", forSubagent = true))
        assertFalse(isDelegationServerEligible("litert-lm", forSubagent = true))
        assertFalse(isDelegationServerEligible("ollama", forSubagent = true))
        assertTrue(isDelegationServerEligible("litert-lm", forSubagent = false))
        assertTrue(isDelegationServerEligible("ollama", forSubagent = false))
    }

    @Test
    fun limitFieldsOnlyForRemoteOrLiteRt() {
        assertTrue(delegationNeedsLimitFields("llama-server", isRemote = true))
        assertTrue(delegationNeedsLimitFields("litert-lm", isRemote = false))
        assertFalse(delegationNeedsLimitFields("llama-server", isRemote = false))
    }

    @Test
    fun enableBlockExplainsMissingRuntimeAndLiteRtSameAsChat() {
        assertEquals(
            DelegationEnableBlock.NO_RUNTIME,
            delegationEnableBlock(false, false, DelegationServerChoice.SameAsChat)
        )
        assertEquals(
            DelegationEnableBlock.LITERT_SAME_AS_CHAT,
            delegationEnableBlock(true, true, DelegationServerChoice.SameAsChat)
        )
        assertEquals(
            DelegationEnableBlock.NONE,
            delegationEnableBlock(true, true, DelegationServerChoice.Server(7))
        )
        assertEquals(DelegationEnableBlock.NONE, delegationEnableBlock(true, false, DelegationServerChoice.SameAsChat))
    }

    @Test
    fun participantsGroupBySharedServer() {
        val shared = delegationParticipantsByServer(5L, NativeChatDelegationConfig())
        assertEquals(setOf(5L), shared.keys)
        assertEquals(3, shared.getValue(5L).size)

        val split = delegationParticipantsByServer(
            5L,
            NativeChatDelegationConfig(
                subagentServer = DelegationServerChoice.Server(9),
                harnessServer = DelegationServerChoice.Server(9)
            )
        )
        assertEquals(setOf(DelegationParticipant.MAIN_CHAT), split.getValue(5L))
        assertEquals(2, split.getValue(9L).size)
        assertTrue(delegationParticipantsByServer(null, NativeChatDelegationConfig()).isEmpty())
    }

    @Test
    fun slotAdviceWarnsOnlyWhenParticipantsOutnumberSlots() {
        assertTrue(delegationNeedsMoreSlots(participants = 3, slots = 1, parallelRequests = true))
        assertTrue(delegationNeedsMoreSlots(participants = 3, slots = 2, parallelRequests = true))
        assertFalse(delegationNeedsMoreSlots(participants = 3, slots = 3, parallelRequests = true))
        assertFalse(delegationNeedsMoreSlots(participants = 1, slots = 1, parallelRequests = true))
        assertFalse(delegationNeedsMoreSlots(participants = 3, slots = null, parallelRequests = true))
        assertFalse(delegationNeedsMoreSlots(participants = 3, slots = 1, parallelRequests = false))
        assertEquals(NativeChatDelegationConfig.RECOMMENDED_PARALLEL_SLOTS, delegationRecommendedSlots(3))
        assertEquals(2, delegationRecommendedSlots(2))
    }

    @Test
    fun paramMapParsingIsTolerant() {
        assertTrue(parseParamMap(null).isEmpty())
        assertTrue(parseParamMap("not json").isEmpty())
        val config = NativeChatDelegationConfig.fromParams(
            parseParamMap("""{"tool_deepseek_enabled":true,"deepseek_subagent_server_row":12.0,"deepseek_subagent_ctx":16384.0}""")
        )
        assertTrue(config.enabled)
        assertEquals(DelegationServerChoice.Server(12, 16384, null), config.subagentServer)
    }

    @Test
    fun harnessSlotShortfallDependsOnUnifiedKv() {
        // 3 slots splitting 24k each get 8k: advice needed; unified KV gives the full 24k.
        assertEquals(8192, delegationHarnessSlotShortfall(24576, 3, false))
        assertNull(delegationHarnessSlotShortfall(24576, 3, true))
        assertNull(delegationHarnessSlotShortfall(65536, 3, false))
        assertEquals(8192, delegationHarnessSlotShortfall(8192, 1, false))
        assertNull(delegationHarnessSlotShortfall(null, 3, false))
        assertNull(delegationHarnessSlotShortfall(24576, null, false))
        assertEquals(24576, delegationContextPerSlot(24576, 3, true))
    }
}
