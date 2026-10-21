package com.example.llamadroid.service

import com.example.llamadroid.service.delegation.DelegateSubagentMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsJsonTest {
    private fun strict(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test fun validCompactIsVerbatim() {
        val raw = """{"b":1,"a":"x"}"""
        assertEquals(raw, ToolArgumentsJson.canonical(raw))
    }

    @Test fun literalNewlineIsEscaped() {
        val out = ToolArgumentsJson.canonical("{\"message\":\"line1\nline2\"}")
        assertEquals("line1\nline2", strict(out)["message"]!!.jsonPrimitive.content)
        assertTrue(out.none { it < ' ' })
    }

    @Test fun trailingGarbageIsDropped() {
        assertEquals("""{"a":1}""", ToolArgumentsJson.canonical("""{"a":1}}"""))
    }

    @Test fun singleQuotesAndUnquotedKeysRecovered() {
        val out = ToolArgumentsJson.canonical("{'a':'x', b:2}")
        val obj = strict(out)
        assertEquals("x", obj["a"]!!.jsonPrimitive.content)
        assertEquals(2, obj["b"]!!.jsonPrimitive.int)
    }

    @Test fun truncatedFallsBack() {
        assertEquals("{}", ToolArgumentsJson.canonical("""{"message":"abc"""))
        val out = ToolArgumentsJson.canonical("""{"message":"abc""", mapOf("message" to "abc"))
        assertEquals("abc", strict(out)["message"]!!.jsonPrimitive.content)
        assertEquals("{}", ToolArgumentsJson.canonical(null))
    }

    @Test fun nestedValuesPreserved() {
        val out = ToolArgumentsJson.canonical("{\"o\":{\"k\":[1,2,\n3]},\"n\":1.5,\"t\":true}")
        val obj = strict(out)
        assertEquals(3, obj["o"]!!.jsonObject["k"]!!.jsonArray.size)
        assertEquals("1.5", obj["n"]!!.jsonPrimitive.content)
        assertEquals("true", obj["t"]!!.jsonPrimitive.content)
    }

    @Test fun delegateToolCallsJsonIsStrict() {
        val call = OllamaService.ToolCall(
            name = "message_main", arguments = mapOf("message" to "a\nb"), id = "c1",
            rawArgumentsJson = "{\"message\":\"a\nb\"}"
        )
        val array = JSONArray(DelegateSubagentMessages.toolCallsJson(listOf(call)))
        val args = array.getJSONObject(0).getJSONObject("function").getString("arguments")
        assertEquals("a\nb", strict(args)["message"]!!.jsonPrimitive.content)
    }
}
