package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessTransferSettingsTest {
    @Test fun importedModelIdsAndCapabilityOverridesUseTheDestinationMapping() {
        val context = HarnessRuntimeScope.context(RuntimeEnvironment.getApplication(), "settings-destination")
        val settings = document()
        assertEquals(setOf("litert:7", "llama:9"), HarnessTransferSettings.modelReferences(settings))
        HarnessTransferSettings.restore(context, settings, mapOf("litert:7" to "litert:22", "llama:9" to "unresolved:llama:9"))
        val agent = context.getSharedPreferences("llamadroid_settings", 0)
        assertEquals(22L, agent.getLong("agent_litert_model_id", -1))
        assertEquals(8192L, agent.getLong("agent_max_tokens", -1))
        val overrides = JSONObject(context.getSharedPreferences("harness_local_model_capabilities", 0).getString("overrides", "{}")!!)
        assertTrue(overrides.has("litert:22"))
        assertFalse(overrides.has("litert:7"))
        assertFalse(overrides.has("llama:9"))
    }

    @Test fun unmappedNumericModelCannotSelectAnUnrelatedDestinationModel() {
        val context = HarnessRuntimeScope.context(RuntimeEnvironment.getApplication(), "settings-unmapped")
        HarnessTransferSettings.restore(context, document(), emptyMap())
        assertEquals(-1L, context.getSharedPreferences("llamadroid_settings", 0).getLong("agent_litert_model_id", 0))
        assertEquals("{}", context.getSharedPreferences("harness_local_model_capabilities", 0).getString("overrides", null))
    }

    private fun document(): JSONObject = JSONObject().put("version", 1).put("groups", JSONObject()
        .put("agent", JSONObject()
            .put("agent_litert_model_id", JSONObject().put("type", "long").put("value", 7L))
            .put("agent_max_tokens", JSONObject().put("type", "long").put("value", 8192L)))
        .put("capabilities", JSONObject().put("overrides", JSONObject().put("type", "string").put("value",
            JSONObject().put("litert:7", JSONObject().put("contextTokens", 16384))
                .put("llama:9", JSONObject().put("maxOutputTokens", 4096)).toString()))))
}
