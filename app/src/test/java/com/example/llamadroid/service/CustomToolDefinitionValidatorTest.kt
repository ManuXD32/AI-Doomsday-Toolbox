package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CustomToolDefinitionValidatorTest {
    @Test
    fun acceptsCurlSchemaAndRejectsMissingParameter() {
        assertTrue(
            CustomToolDefinitionValidator.validate(
                "weather_api", "Fetch weather", "curl https://example.com/{city}",
                "{\"city\":\"City name\"}", "[\"city\"]", "weather_api(city=London)"
            ).valid
        )
        assertTrue(
            CustomToolDefinitionValidator.validate(
                "weather_schema", "Fetch weather", "curl https://example.com/{city}",
                """{"type":"object","properties":{"city":{"type":"string","description":"City"}}}""",
                "[\"city\"]", "weather_schema(city=London)"
            ).valid
        )
        assertFalse(
            CustomToolDefinitionValidator.validate(
                "weather_api", "Fetch weather", "curl https://example.com/{city}",
                "{}", "[]", "weather_api(city=London)"
            ).valid
        )
    }

    @Test
    fun preservesTypedJsonSchemaForProviderToolDefinitions() {
        val schema = CustomToolDefinitionValidator.canonicalSchemaJson(
            """{"type":"object","properties":{"count":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}}}""",
            "[\"count\"]"
        )
        val json = JSONObject(schema!!)
        assertEquals("integer", json.getJSONObject("properties").getJSONObject("count").getString("type"))
        assertEquals("array", json.getJSONObject("properties").getJSONObject("tags").getString("type"))
        assertEquals("count", json.getJSONArray("required").getString(0))
    }
}
