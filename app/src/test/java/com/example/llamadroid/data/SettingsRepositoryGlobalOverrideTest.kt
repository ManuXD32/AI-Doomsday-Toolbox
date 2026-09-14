package com.example.llamadroid.data

import android.content.Context
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.runtime.AgentRuntimeGlobalOverride
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
class SettingsRepositoryGlobalOverrideTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun clearSettings() {
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun `override is disabled by default and Direct defaults ignore legacy role settings`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        settings.setAgentCoderModel("coder-local")
        settings.setAgentCoderCtx(12_345)
        settings.setAgentCoderMaxOutputTokens(2_345)
        settings.setAgentCoderThinkingEnabled(false)
        settings.setAgentDirectRuntimeDefaults(
            AgentRuntimeGlobalOverride(
                enabled = true,
                backend = AgentRuntimeBackend.LLAMA_SWAP.id,
                model = "direct-model",
                managedLlamaServerId = 5L,
                contextSize = 16_384,
                maxOutputTokens = 4_096,
                thinkingEnabled = false
            )
        )

        val resolved = settings.resolveAgentSettingsForDispatch(
            role = "CODER",
            customModel = "custom-model",
            customVisionEnabled = true,
            runtimeProfile = AgentRuntimeProfile(
                agentKey = "CODER",
                backend = AgentRuntimeBackend.LLAMA_SWAP.id,
                model = "profile-model",
                managedLlamaServerId = 7L
            )
        )

        assertFalse(settings.agentGlobalOverrideEnabled.value)
        assertEquals(AgentRuntimeBackend.LLAMA_SWAP.id, resolved.backend)
        assertEquals("direct-model", resolved.model)
        assertEquals(5L, resolved.managedLlamaServerId)
        assertEquals(16_384, resolved.contextSize)
        assertEquals(4_096, resolved.maxOutputTokens)
        assertFalse(resolved.thinkingEnabled)
        assertEquals("coder-local", settings.agentCoderModel.value)
        assertEquals(12_345, settings.agentCoderCtx.value)
        assertEquals(2_345, settings.agentCoderMaxOutputTokens.value)
    }

    @Test
    fun `enabled override wins for built in and custom agents without changing their settings`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        settings.setAgentCoderModel("coder-local")
        settings.setAgentCoderCtx(12_345)
        settings.setAgentCoderMaxOutputTokens(2_345)
        settings.setAgentCoderThinkingEnabled(false)
        settings.setAgentGlobalRuntimeOverride(
            AgentRuntimeGlobalOverride(
                enabled = true,
                backend = AgentRuntimeBackend.LLAMA_SERVER.id,
                model = "general-model",
                managedLlamaServerId = 11L,
                contextSize = 65_536,
                maxOutputTokens = 4_096,
                thinkingEnabled = true,
                thinkingBudgetTokens = 768,
                visionEnabled = false
            )
        )

        val builtIn = settings.resolveAgentSettingsForDispatch(
            role = "CODER",
            runtimeProfile = AgentRuntimeProfile(
                agentKey = "CODER",
                backend = AgentRuntimeBackend.OLLAMA.id,
                model = "profile-model",
                endpointConfigId = 9L,
                managedLlamaServerId = 8L
            )
        )
        val custom = settings.resolveAgentSettingsForDispatch(
            role = "CUSTOM:DEBUGGER",
            customModel = "custom-model",
            customVisionEnabled = true,
            runtimeProfile = AgentRuntimeProfile(
                agentKey = "CUSTOM:DEBUGGER",
                backend = AgentRuntimeBackend.LITERT.id,
                model = "custom-profile-model",
                liteRtModelId = 3L
            )
        )

        listOf(builtIn, custom).forEach { resolved ->
            assertEquals(AgentRuntimeBackend.LLAMA_SERVER.id, resolved.backend)
            assertEquals("general-model", resolved.model)
            assertNull(resolved.endpointConfigId)
            assertEquals(11L, resolved.managedLlamaServerId)
            assertEquals(65_536, resolved.contextSize)
            assertEquals(4_096, resolved.maxOutputTokens)
            assertTrue(resolved.thinkingEnabled)
            assertEquals(768, resolved.thinkingBudgetTokens)
            assertFalse(resolved.visionEnabled)
        }

        assertEquals("coder-local", settings.agentCoderModel.value)
        assertEquals(12_345, settings.agentCoderCtx.value)
        assertEquals(2_345, settings.agentCoderMaxOutputTokens.value)
        assertFalse(settings.agentCoderThinkingEnabled.value)
    }

    @Test
    fun `global override persists independently and can be disabled without losing its values`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app)
        settings.setAgentGlobalRuntimeOverride(
            AgentRuntimeGlobalOverride(
                enabled = true,
                backend = AgentRuntimeBackend.LITERT.id,
                model = "ignored-for-litert",
                endpointConfigId = 19L,
                contextSize = 1_500,
                maxOutputTokens = 999,
                thinkingEnabled = false,
                thinkingBudgetTokens = 321,
                visionEnabled = true
            )
        )
        settings.setAgentGlobalOverrideEnabled(false)

        val reopened = SettingsRepository(app)
        assertFalse(reopened.agentGlobalOverrideEnabled.value)
        assertEquals(AgentRuntimeBackend.LITERT.id, reopened.agentGlobalOverrideBackend.value)
        assertEquals("ignored-for-litert", reopened.agentGlobalOverrideModel.value)
        assertEquals(19L, reopened.agentGlobalOverrideEndpointConfigId.value)
        assertEquals(1_500, reopened.agentGlobalOverrideContextSize.value)
        assertEquals(999, reopened.agentGlobalOverrideMaxOutputTokens.value)
        assertFalse(reopened.agentGlobalOverrideThinkingEnabled.value)
        assertEquals(321, reopened.agentGlobalOverrideThinkingBudgetTokens.value)
        assertTrue(reopened.agentGlobalOverrideVisionEnabled.value)
        assertEquals(
            reopened.getAgentGlobalRuntimeOverride(),
            reopened.agentGlobalRuntimeOverride.value
        )
    }

    @Test
    fun `fresh direct override values use documented recommendations`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(16_384, settings.agentGlobalOverrideContextSize.value)
        assertEquals(4_096, settings.agentGlobalOverrideMaxOutputTokens.value)
        assertFalse(settings.agentGlobalOverrideThinkingEnabled.value)
        assertNull(settings.agentGlobalOverrideThinkingBudgetTokens.value)
        assertEquals(16_384, settings.agentDirectRuntimeDefaults.value.contextSize)
        assertEquals(4_096, settings.agentDirectRuntimeDefaults.value.maxOutputTokens)
        assertFalse(settings.agentDirectRuntimeDefaults.value.thinkingEnabled)
    }

    @Test
    fun `Direct defaults are captured independently of later edits`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        settings.setAgentDirectRuntimeDefaults(
            settings.getAgentDirectRuntimeDefaults().copy(model = "captured", contextSize = 32_768)
        )
        val snapshot = settings.getAgentDirectRuntimeDefaults()
        settings.setAgentDirectRuntimeDefaults(snapshot.copy(model = "later", contextSize = 65_536))

        val resolved = settings.resolveAgentSettingsForDispatch(
            role = "ORCHESTRATOR",
            directDefaultsSnapshot = snapshot
        )

        assertEquals("captured", resolved.model)
        assertEquals(32_768, resolved.contextSize)
    }

    @Test
    fun `dispatch uses the immutable override snapshot captured for the turn`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        settings.setAgentGlobalRuntimeOverride(
            AgentRuntimeGlobalOverride(
                enabled = true,
                backend = AgentRuntimeBackend.LLAMA_SWAP.id,
                model = "snapshot-model",
                contextSize = 65_536,
                maxOutputTokens = 12_288,
                thinkingEnabled = true,
                thinkingBudgetTokens = 1_024
            )
        )
        val snapshot = settings.getAgentGlobalRuntimeOverride()
        settings.setAgentGlobalOverrideEnabled(false)

        val resolved = settings.resolveAgentSettingsForDispatch(
            role = "ORCHESTRATOR",
            globalOverrideSnapshot = snapshot
        )

        assertEquals(AgentRuntimeBackend.LLAMA_SWAP.id, resolved.backend)
        assertEquals("snapshot-model", resolved.model)
        assertEquals(65_536, resolved.contextSize)
        assertEquals(12_288, resolved.maxOutputTokens)
        assertTrue(resolved.thinkingEnabled)
        assertEquals(1_024, resolved.thinkingBudgetTokens)
    }
}
