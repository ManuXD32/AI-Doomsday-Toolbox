package com.example.llamadroid.harness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessCompletionAccumulatorTest {
    @Test fun preservesFragmentedToolCallsReasoningAndUsage() {
        val result = HarnessCompletionAccumulator()
        result.add(JSONObject("""{"id":"c1","model":"litert:7","created":12,"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"think ","content":"hello ","tool_calls":[{"index":0,"id":"t1","function":{"name":"read_","arguments":"{\"path\":"}}]}}]}"""))
        result.add(JSONObject("""{"choices":[{"index":0,"delta":{"reasoning_content":"done","content":"world","tool_calls":[{"index":0,"function":{"name":"file","arguments":"\"a\"}"}}]},"finish_reason":"tool_calls"}]}"""))
        result.add(JSONObject("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""))
        val completion = result.finish()
        val choice = completion.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        assertEquals("chat.completion", completion.getString("object"))
        assertEquals("hello world", message.getString("content"))
        assertEquals("think done", message.getString("reasoning_content"))
        assertEquals("tool_calls", choice.getString("finish_reason"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("t1", call.getString("id"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))
        assertEquals("a", JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"))
        assertEquals(13, completion.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test fun oversizedOutputFailsInsteadOfGrowingUnbounded() {
        val accumulator = HarnessCompletionAccumulator(4)
        val failure = runCatching { accumulator.add(JSONObject("""{"choices":[{"delta":{"content":"12345"}}]}""")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("PROVIDER_OUTPUT_LIMIT", failure?.message)
    }
}
