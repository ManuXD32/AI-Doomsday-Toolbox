package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class ModelClassificationPolicyTest {
    @Test
    fun `selected classification wins while retaining disagreement warning`() {
        val resolution = ModelClassificationPolicy.resolve(
            detected = ModelClassification(ModelFamily.LLM, ModelType.LLM, "base"),
            selected = ModelClassification(ModelFamily.LLM, ModelType.LORA, "adapter")
        )

        assertEquals(ModelType.LORA, resolution.selected?.type)
        assertTrue(resolution.hasSemanticDisagreement)
        assertEquals(ModelClassificationSource.USER_OVERRIDE, resolution.source)
    }

    @Test
    fun `evidence is bounded and malformed evidence is discarded`() {
        val evidence = ModelClassificationPolicy.evidenceJson(
            detected = ModelClassification(ModelFamily.SD, ModelType.SD_CHECKPOINT, "checkpoint"),
            rawJson = "{" + "\"message\":\"${"x".repeat(20_000)}\"}"
        )
        assertTrue(requireNotNull(evidence).length <= ModelClassificationPolicy.MAX_DETECTED_EVIDENCE_LENGTH)
        assertTrue(JSONObject(evidence).getBoolean("evidenceTruncated"))
        assertEquals("{}", ModelClassificationPolicy.boundedEvidence("not-json"))
    }

    @Test
    fun `normalized classification is merged into inspector evidence`() {
        val evidence = ModelClassificationPolicy.evidenceJson(
            detected = ModelClassification(ModelFamily.SD, ModelType.SD_DIFFUSION, "diffusion"),
            rawJson = "{\"inspectorVersion\":3}"
        )

        val parsed = JSONObject(requireNotNull(evidence))
        assertEquals(3, parsed.getInt("inspectorVersion"))
        assertEquals(ModelFamily.SD.storedValue, parsed.getString("family"))
        assertEquals(ModelType.SD_DIFFUSION.name, parsed.getString("type"))
        assertEquals("diffusion", parsed.getString("role"))
    }

    @Test
    fun `legacy rows with evidence become automatic`() {
        assertEquals(
            ModelClassificationSource.AUTO,
            ModelClassificationPolicy.sourceOrLegacy("unknown", hasDetectedEvidence = true)
        )
        assertEquals(
            ModelClassificationSource.LEGACY,
            ModelClassificationPolicy.sourceOrLegacy("unknown", hasDetectedEvidence = false)
        )
    }
}
