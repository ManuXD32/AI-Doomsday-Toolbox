package com.example.llamadroid.data

import android.content.Context
import com.example.llamadroid.service.AgentHarnessPolicy
import com.example.llamadroid.service.AgentHarnessStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryHarnessTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun clearSettings() {
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun `thinking override is absent by default and explicit false survives reopening`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app)

        assertNull(settings.agentHarnessThinkingOverride.value)
        assertFalse(settings.hasExplicitAgentHarnessThinkingOverride)

        settings.setAgentHarnessThinkingOverride(false)

        val reopened = SettingsRepository(app)
        assertEquals(false, reopened.agentHarnessThinkingOverride.value)
        assertTrue(reopened.hasExplicitAgentHarnessThinkingOverride)
        assertEquals(false, reopened.resolveAgentHarnessPolicy(AgentHarnessPolicy.OPTIMIZED).thinkingOverride)
    }

    @Test
    fun `clearing thinking override removes preference and keeps role explicit metadata`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app)
        settings.setAgentHarnessThinkingOverride(true)
        settings.setAgentHarnessThinkingOverride(null)

        assertNull(settings.agentHarnessThinkingOverride.value)
        assertFalse(settings.hasExplicitAgentHarnessThinkingOverride)

        settings.setAgentCoderThinkingEnabled(false)
        assertEquals(false, settings.getExplicitAgentThinkingEnabledForRole("CODER"))
        assertEquals(null, settings.getExplicitAgentThinkingEnabledForRole("CUSTOM:writer"))
    }

    @Test
    fun `optimized resolution uses recommendations only for roles without saved limits`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app)

        assertFalse(settings.hasExplicitAgentContextForRole("ORCHESTRATOR"))
        assertFalse(settings.hasExplicitAgentMaxOutputTokensForRole("ORCHESTRATOR"))
        assertEquals(
            AgentHarnessPolicy.DEFAULT_CONTEXT_TOKENS,
            settings.resolveAgentHarnessContext(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "ORCHESTRATOR"
            )
        )
        assertEquals(
            AgentHarnessPolicy.CONTROL_MAX_OUTPUT_TOKENS,
            settings.resolveAgentHarnessOutputTokens(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "ORCHESTRATOR",
                stage = AgentHarnessStage.CONTROL,
                configuredMaxOutputTokens = 8_096
            )
        )

        settings.setAgentOrchestratorCtx(32_768)
        settings.setAgentOrchestratorMaxOutputTokens(12_345)

        assertTrue(settings.hasExplicitAgentContextForRole("ORCHESTRATOR"))
        assertTrue(settings.hasExplicitAgentMaxOutputTokensForRole("ORCHESTRATOR"))
        assertEquals(
            AgentHarnessPolicy.MAX_CONTEXT_TOKENS,
            settings.resolveAgentHarnessContext(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "ORCHESTRATOR"
            )
        )
        assertEquals(
            AgentHarnessPolicy.BUILD_MAX_OUTPUT_TOKENS,
            settings.resolveAgentHarnessOutputTokens(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "ORCHESTRATOR",
                stage = AgentHarnessStage.BUILD,
                configuredMaxOutputTokens = 12_345
            )
        )
    }

    @Test
    fun `custom roles inherit explicit orchestrator limits for optimized resolution`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app)
        settings.setAgentOrchestratorCtx(32_768)
        settings.setAgentOrchestratorMaxOutputTokens(12_345)

        assertEquals(32_768, settings.getAgentContextForRole("CUSTOM:writer"))
        assertEquals(12_345, settings.getAgentMaxOutputTokensForRole("CUSTOM:writer"))
        assertTrue(settings.hasExplicitAgentContextForRole("CUSTOM:writer"))
        assertTrue(settings.hasExplicitAgentMaxOutputTokensForRole("CUSTOM:writer"))
        assertEquals(
            AgentHarnessPolicy.MAX_CONTEXT_TOKENS,
            settings.resolveAgentHarnessContext(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "CUSTOM:writer"
            )
        )
        assertEquals(
            AgentHarnessPolicy.BUILD_MAX_OUTPUT_TOKENS,
            settings.resolveAgentHarnessOutputTokens(
                profileId = AgentHarnessPolicy.OPTIMIZED,
                role = "CUSTOM:writer",
                stage = AgentHarnessStage.BUILD,
                configuredMaxOutputTokens = settings.getAgentMaxOutputTokensForRole("CUSTOM:writer")
            )
        )
    }
}
