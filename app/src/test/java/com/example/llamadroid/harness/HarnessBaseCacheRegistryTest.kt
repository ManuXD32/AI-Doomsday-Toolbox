package com.example.llamadroid.harness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HarnessBaseCacheRegistryTest {
    private fun messages(system: String, user: String = "hi") = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))

    private fun tools(a: String, b: String) = JSONArray().put(
        JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "read").put("description", a).put("parameters", JSONObject().put(a, b)))
    )

    @Test fun hashIgnoresUserMessagesAndKeyOrder() {
        val first = HarnessBaseCacheRegistry.prefixHash(messages("sys", "one"), tools("a", "b"))
        val second = HarnessBaseCacheRegistry.prefixHash(messages("sys", "something else"), tools("a", "b"))
        assertNotNull(first)
        assertEquals(first, second)
        val reordered = JSONArray().put(JSONObject().put("function",
            JSONObject().put("parameters", JSONObject().put("a", "b")).put("description", "a").put("name", "read"))
            .put("type", "function"))
        assertEquals(first, HarnessBaseCacheRegistry.prefixHash(messages("sys"), reordered))
    }

    @Test fun hashChangesWithSystemOrTools() {
        val base = HarnessBaseCacheRegistry.prefixHash(messages("sys"), tools("a", "b"))
        assertNotEquals(base, HarnessBaseCacheRegistry.prefixHash(messages("sys2"), tools("a", "b")))
        assertNotEquals(base, HarnessBaseCacheRegistry.prefixHash(messages("sys"), tools("a", "c")))
    }

    @Test fun noLeadingSystemMessageMeansNoBase() {
        val onlyUser = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        assertNull(HarnessBaseCacheRegistry.prefixHash(onlyUser, null))
        assertNull(HarnessBaseCacheRegistry.hint(1, "m", null))
    }

    @Test fun keyIsStableAndScopedToHarness() {
        val key = HarnessBaseCacheRegistry.baseKey(4, "gemma.gguf", "abc")
        assertEquals("harness", key.scope)
        assertEquals("4|gemma.gguf|abc", key.prefixHash)
        assertEquals(key.fileStem, HarnessBaseCacheRegistry.baseKey(4, "gemma.gguf", "abc").fileStem)
    }
}
