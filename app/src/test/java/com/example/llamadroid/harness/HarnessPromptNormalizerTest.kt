package com.example.llamadroid.harness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessPromptNormalizerTest {
    private fun web(port: Int) =
        "You are interacting with the user through the DeepSeek Harness Web GUI at http://127.0.0.1:$port. Do not start a replacement server."

    private fun systemText(port: Int, cwd: String) =
        "You are a coding agent.\n\nUse tools carefully.\n\n${web(port)}\n\nYour working directory is $cwd.  "

    private fun messages(system: String, user: Any = "do it") = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))
        .put(JSONObject().put("role", "assistant").put("content", "ok"))

    @Test fun systemIsIdenticalAcrossPortAndCwd() {
        val a = HarnessPromptNormalizer.normalize(messages(systemText(41000, "/work/a")))
        val b = HarnessPromptNormalizer.normalize(messages(systemText(52111, "/work/b/sub")))
        val sysA = a.getJSONObject(0).getString("content")
        assertEquals(sysA, b.getJSONObject(0).getString("content"))
        assertEquals("You are a coding agent.\n\nUse tools carefully.", sysA)
    }

    @Test fun valuesMoveIntoFirstUserMessageAndRolesAreUnchanged() {
        val out = HarnessPromptNormalizer.normalize(messages(systemText(41000, "/work/a")))
        val user = out.getJSONObject(1).getString("content")
        assertTrue(user.startsWith("[Runtime context]\n"))
        assertTrue(user.contains("http://127.0.0.1:41000"))
        assertTrue(user.contains("Your working directory is /work/a."))
        assertTrue(user.endsWith("\n\ndo it"))
        assertEquals(listOf("system", "user", "assistant"), (0 until out.length()).map { out.getJSONObject(it).getString("role") })
    }

    @Test fun contentPartArrayGetsLeadingTextPart() {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "hello"))
        val out = HarnessPromptNormalizer.normalize(messages(systemText(1, "/w"), parts))
        val content = out.getJSONObject(1).getJSONArray("content")
        assertEquals(2, content.length())
        assertTrue(content.getJSONObject(0).getString("text").startsWith("[Runtime context]"))
        assertEquals("hello", content.getJSONObject(1).getString("text"))
    }

    @Test fun noopWhenLinesAbsentOrNoUserMessage() {
        val plain = messages("You are a coding agent.")
        assertSame(plain, HarnessPromptNormalizer.normalize(plain))
        val noUser = JSONArray().put(JSONObject().put("role", "system").put("content", systemText(1, "/w")))
        assertSame(noUser, HarnessPromptNormalizer.normalize(noUser))
        val assistantFirst = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemText(1, "/w")))
            .put(JSONObject().put("role", "assistant").put("content", "x"))
        assertSame(assistantFirst, HarnessPromptNormalizer.normalize(assistantFirst))
    }

    @Test fun idempotent() {
        val once = HarnessPromptNormalizer.normalize(messages(systemText(41000, "/work/a")))
        val twice = HarnessPromptNormalizer.normalize(once)
        assertEquals(once.toString(), twice.toString())
    }

    @Test fun normalizeRequestKeepsOtherFields() {
        val request = JSONObject().put("model", "m").put("messages", messages(systemText(9, "/w")))
        val out = HarnessPromptNormalizer.normalizeRequest(request)
        assertEquals("m", out.getString("model"))
        assertTrue(out.getJSONArray("messages").getJSONObject(1).getString("content").contains("/w"))
    }
}
