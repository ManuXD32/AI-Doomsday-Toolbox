package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessModelEditorTest {
    @Test
    fun `capacity update preserves exact wire id and unknown properties`() {
        val source = """[{"id":"/models/alpha.gguf","name":"Alpha","vendorFlag":true}]"""

        val result = updateNativeHarnessModelRows(
            existingText = source,
            wireId = "/models/alpha.gguf",
            contextText = "32768",
            outputText = "4096",
        )

        val row = Json.parseToJsonElement(requireNotNull(result)).jsonArray.single().jsonObject
        assertEquals("/models/alpha.gguf", row.getValue("id").jsonPrimitive.content)
        assertEquals("Alpha", row.getValue("name").jsonPrimitive.content)
        assertTrue(row.getValue("vendorFlag").jsonPrimitive.content.toBoolean())
        assertEquals(32768L, row.getValue("contextWindow").jsonPrimitive.content.toLong())
        assertEquals(4096L, row.getValue("maxTokens").jsonPrimitive.content.toLong())
    }

    @Test
    fun `blank capacities clear only the capacity properties`() {
        val source = """[{"id":"model-a","contextWindow":250000,"maxTokens":8192,"kept":"yes"}]"""

        val result = updateNativeHarnessModelRows(source, "model-a", "", "")

        val row = Json.parseToJsonElement(requireNotNull(result)).jsonArray.single().jsonObject
        assertFalse(row.containsKey("contextWindow"))
        assertFalse(row.containsKey("maxTokens"))
        assertEquals("yes", row.getValue("kept").jsonPrimitive.content)
    }

    @Test
    fun `missing model does not produce a settings mutation`() {
        assertNull(updateNativeHarnessModelRows("[{\"id\":\"model-a\"}]", "model-b", "1", "1"))
    }
}
