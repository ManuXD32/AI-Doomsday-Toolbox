package com.example.llamadroid.harness.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTransferConfigurationTest {
    @Test
    fun safeSettingsRewriteMapsReferencesAndStripsSecretsWithoutTouchingOpaquePrompt() {
        val yaml = """
            provider: source-provider
            model: source-model
            prompt: "model: source-model provider: source-provider token: keep-this-text"
            credentials:
              token: remove-me
            nested:
              providerId: source-provider
        """.trimIndent() + "\n"

        val sanitized = HarnessTransferConfiguration.sanitize(yaml)
        assertFalse(sanitized.contains("remove-me"))
        assertTrue(sanitized.contains("keep-this-text"))

        val rewritten = HarnessTransferConfiguration.rewrite(sanitized) { key, value ->
            when (key) {
                "provider", "providerId" -> "destination-provider"
                "model" -> "destination-model"
                else -> value
            }
        }
        assertTrue(rewritten.contains("destination-provider"))
        assertTrue(rewritten.contains("destination-model"))
        assertTrue(rewritten.contains("model: source-model provider: source-provider"))
    }

    @Test
    fun safeLoaderRejectsCustomTagsAndCollectsProviderModelDefinitions() {
        var rejected = false
        try {
            HarnessTransferConfiguration.references("!!java/object {value: x}\n")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        val references = HarnessTransferConfiguration.references(
            """
            providers:
              source-provider:
                endpoint: https://example.invalid
            models:
              source-model:
                provider: source-provider
            """.trimIndent(),
        )
        assertEquals(setOf("source-provider"), references["providerDefinition"])
        assertEquals(setOf("source-model"), references["modelDefinition"])
        assertEquals(setOf("source-provider"), references["provider"])
    }

    @Test
    fun settingsJsonKeepsJsonSyntaxWhenStructureChanges() {
        val rewritten = HarnessTransferConfiguration.rewriteSettings(
            "{\"provider\":\"source-provider\",\"prompt\":\"model: source-model\"}",
            { key, value -> if (key == "provider") "destination-provider" else value },
            jsonOutput = true,
        )
        assertTrue(rewritten.trimStart().startsWith("{"))
        assertTrue(rewritten.contains("\"destination-provider\""))
        assertTrue(rewritten.contains("model: source-model"))
    }
}
