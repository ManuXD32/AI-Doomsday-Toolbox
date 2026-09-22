package com.example.llamadroid.harness

import org.json.JSONArray
import org.json.JSONObject

/** Bounded OpenAI stream aggregation for provider clients requesting stream=false. */
internal class HarnessCompletionAccumulator(private val maxCharacters: Int = 2 * 1024 * 1024) {
    private var characters = 0
    private var id = ""
    private var model = ""
    private var created = 0L
    private var finishReason = "stop"
    private var usage: JSONObject? = null
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = sortedMapOf<Int, JSONObject>()

    fun add(chunk: JSONObject) {
        check(!chunk.has("error")) { "PROVIDER_INTERRUPTED" }
        if (chunk.has("id")) id = chunk.getString("id")
        if (chunk.has("model")) model = chunk.getString("model")
        if (chunk.has("created")) created = chunk.getLong("created")
        chunk.optJSONObject("usage")?.let { usage = it }
        val choices = chunk.optJSONArray("choices") ?: return
        for (index in 0 until choices.length()) {
            val choice = choices.getJSONObject(index)
            require(choice.optInt("index", 0) == 0) { "MULTIPLE_COMPLETIONS_UNSUPPORTED" }
            if (!choice.isNull("finish_reason")) finishReason = choice.getString("finish_reason")
            val delta = choice.optJSONObject("delta") ?: continue
            if (!delta.isNull("content")) append(content, delta.getString("content"))
            if (!delta.isNull("reasoning_content")) append(reasoning, delta.getString("reasoning_content"))
            val calls = delta.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                val part = calls.getJSONObject(callIndex)
                val call = toolCalls.getOrPut(part.getInt("index")) {
                    require(toolCalls.size < 256) { "PROVIDER_OUTPUT_LIMIT" }
                    JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", ""))
                }
                if (part.has("id")) call.put("id", part.getString("id"))
                val function = part.optJSONObject("function") ?: continue
                val target = call.getJSONObject("function")
                listOf("name", "arguments").forEach { key -> if (!function.isNull(key)) {
                    val fragment = function.getString(key)
                    count(fragment)
                    target.put(key, target.getString(key) + fragment)
                } }
            }
        }
    }

    fun finish(): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
        if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls.values))
        return JSONObject().put("id", id).put("object", "chat.completion").put("model", model).put("created", created)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", finishReason)))
            .also { result -> usage?.let { result.put("usage", it) } }
    }

    private fun append(target: StringBuilder, value: String) { count(value); target.append(value) }
    private fun count(value: String) {
        require(value.length <= maxCharacters - characters) { "PROVIDER_OUTPUT_LIMIT" }
        characters += value.length
    }
}
