package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.llamadroid.util.DebugLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chat service for llama-server (llama.cpp HTTP server).
 * Uses the OpenAI-compatible /v1/chat/completions endpoint.
 *
 * Key differences from Ollama:
 * - Model is fixed at server launch (not changeable per-request)
 * - Threads are fixed at server launch
 * - Thinking is handled via <think> tags in content (same parsing as Ollama fallback)
 * - Streaming uses SSE format (data: {json}\n\n) instead of JSON lines
 * - Tool calls arrive incrementally across SSE chunks
 */
class LlamaServerChatService {

    companion object {
        private const val TAG = "LlamaServerChat"
    }

    @Volatile
    var shouldStop = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun stopGeneration() {
        shouldStop = true
        runCatching { activeConnection?.disconnect() }
    }

    /**
     * Send a chat completion request to llama-server with tool support.
     * Returns the same ChatResponse type as OllamaService for seamless integration.
     *
     * @param baseUrl llama-server base URL (e.g., "http://localhost:8080")
     * @param messages conversation history
     * @param tools available tools for function calling
     * @param thinkingEnabled if false, strip <think> tags from output
     * @param numCtx context window size (passed as max_tokens hint)
     * @param onChunk streaming callback: (contentDelta, thinkingDelta)
     */
    internal suspend fun chatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        modelLabel: String? = null,
        thinkingEnabled: Boolean = true,
        numCtx: Int = 16384,
        samplingParams: LlamaServerSamplingParams = LlamaServerSamplingParams(),
        onChunk: (String?, String?) -> Unit = { _, _ -> }
    ): Result<OllamaService.ChatResponse> = withContext(Dispatchers.IO) {
        shouldStop = false
        var sawStreamOutput = false
        val guardedOnChunk: (String?, String?) -> Unit = { chunk, thinkingChunk ->
            if (!chunk.isNullOrBlank() || !thinkingChunk.isNullOrBlank()) {
                sawStreamOutput = true
            }
            onChunk(chunk, thinkingChunk)
        }

        try {
            val chatResponse = RemoteAgentProtection.withProtection(baseUrl, "Running remote llama-server agent…") {
                RemoteBackendResilience.runWithSingleRetry(
                    onRetry = { firstError ->
                        DebugLog.log("[$TAG] Recoverable llama-server chat failure, retrying: ${RemoteBackendResilience.summarize(firstError)}")
                    },
                    shouldRetry = { !sawStreamOutput }
                ) {
                    performChatWithToolsStreaming(baseUrl, messages, tools, modelLabel, thinkingEnabled, numCtx, samplingParams, guardedOnChunk)
                }
            }
            Result.success(chatResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun performChatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        modelLabel: String?,
        thinkingEnabled: Boolean,
        numCtx: Int,
        samplingParams: LlamaServerSamplingParams,
        onChunk: (String?, String?) -> Unit
    ): OllamaService.ChatResponse {
        val url = URL("${baseUrl.trimEnd('/')}/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        activeConnection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 1800000 // 30 minutes for long reasoning

        val requestJson = buildJsonObject(
            buildLlamaServerChatRequestPayload(
                messages = messages,
                tools = tools,
                model = modelLabel,
                thinkingEnabled = thinkingEnabled,
                maxTokens = numCtx,
                samplingParams = samplingParams
            )
        )

        try {
            OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()); it.flush() }

            if (conn.responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (_: Exception) {
                    "HTTP ${conn.responseCode}"
                }
                throw Exception("llama-server error: $errorBody")
            }

            val fullContent = StringBuilder()
            val fullThinking = StringBuilder()
            var insideThinkTag = false
            var usage: OllamaService.ChatUsage? = null
            val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (shouldStop) {
                        DebugLog.log("[$TAG] stop requested, breaking SSE stream")
                        conn.disconnect()
                        throw Exception("Stopped by user")
                    }

                    val data = line ?: continue
                    if (!data.startsWith("data: ")) continue
                    val jsonStr = data.removePrefix("data: ").trim()
                    if (jsonStr == "[DONE]") break

                    try {
                        val chunk = JSONObject(jsonStr)
                        parseLlamaServerUsage(chunk)?.let { usage = it }
                        val choices = chunk.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue

                        val content = delta.optString("content", "").takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
                        if (content.isNotEmpty()) {
                            var remaining = content
                            while (remaining.isNotEmpty()) {
                                if (!insideThinkTag) {
                                    if (remaining.contains("<think>")) {
                                        val parts = remaining.split("<think>", limit = 2)
                                        if (parts[0].isNotEmpty()) {
                                            fullContent.append(parts[0])
                                            onChunk(parts[0], null)
                                        }
                                        insideThinkTag = true
                                        remaining = if (parts.size > 1) parts[1] else ""
                                    } else {
                                        fullContent.append(remaining)
                                        onChunk(remaining, null)
                                        remaining = ""
                                    }
                                } else {
                                    if (remaining.contains("</think>")) {
                                        val parts = remaining.split("</think>", limit = 2)
                                        if (parts[0].isNotEmpty()) {
                                            fullThinking.append(parts[0])
                                            if (thinkingEnabled) onChunk(null, parts[0])
                                        }
                                        insideThinkTag = false
                                        remaining = if (parts.size > 1) parts[1] else ""
                                    } else {
                                        fullThinking.append(remaining)
                                        if (thinkingEnabled) onChunk(null, remaining)
                                        remaining = ""
                                    }
                                }
                            }
                        }

                        val reasoningContent = delta.optString("reasoning_content", "")
                            .takeUnless { it.equals("null", ignoreCase = true) }
                            .orEmpty()
                        if (reasoningContent.isNotEmpty()) {
                            fullThinking.append(reasoningContent)
                            if (thinkingEnabled) onChunk(null, reasoningContent)
                        }

                        val tcArray = delta.optJSONArray("tool_calls")
                        if (tcArray != null) {
                            for (i in 0 until tcArray.length()) {
                                val tcObj = tcArray.getJSONObject(i)
                                val index = tcObj.optInt("index", i)
                                val id = tcObj.optString("id", "")
                                val funcObj = tcObj.optJSONObject("function")

                                val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                                if (id.isNotEmpty()) builder.id = id
                                if (funcObj != null) {
                                    val name = funcObj.optString("name", "")
                                    val args = funcObj.optString("arguments", "")
                                    if (name.isNotEmpty()) builder.name = name
                                    builder.arguments.append(args)
                                }
                            }
                        }

                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason == "stop" || finishReason == "tool_calls") break
                    } catch (e: Exception) {
                        DebugLog.log("[$TAG] SSE parse error: ${e.message} for line: $jsonStr")
                    }
                }
            }

            val toolCalls = if (toolCallBuilders.isNotEmpty()) {
                toolCallBuilders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
                    if (builder.name.isNotEmpty()) {
                        try {
                            val args = AgentRuntimeSupport.normalizeToolArguments(builder.arguments.toString())
                            DebugLog.log("[$TAG] Assembled tool call: ${builder.name} (id: ${builder.id})")
                            OllamaService.ToolCall(builder.name, args, builder.id)
                        } catch (e: Exception) {
                            DebugLog.log("[$TAG] Failed to parse tool call args: ${e.message}")
                            null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            DebugLog.log("[$TAG] Stream finished. ${toolCalls?.size ?: 0} tool calls detected.")

            return OllamaService.ChatResponse(
                message = OllamaService.ChatMessage(
                    role = "assistant",
                    content = fullContent.toString(),
                    toolCalls = toolCalls,
                    thinking = fullThinking.toString().ifEmpty { null }
                ),
                done = true,
                toolCalls = toolCalls,
                usage = usage
            )
        } finally {
            if (activeConnection === conn) {
                activeConnection = null
            }
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Check connection to llama-server by hitting /health endpoint
     */
    suspend fun checkConnection(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            RemoteAgentProtection.withProtection(baseUrl, "Checking remote llama-server connection…") {
                RemoteBackendResilience.runWithSingleRetry(
                    onRetry = { firstError ->
                        DebugLog.log("[$TAG] Recoverable llama-server health failure, retrying: ${RemoteBackendResilience.summarize(firstError)}")
                    }
                ) {
                    val url = URL("${baseUrl.trimEnd('/')}/health")
                    val conn = url.openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.responseCode == 200
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Helper class to assemble tool calls from incremental SSE deltas.
     * llama-server sends tool calls piece by piece:
     * - First chunk: id + function name
     * - Subsequent chunks: argument fragments
     */
    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}

internal fun buildLlamaServerChatRequestPayload(
    messages: List<OllamaService.ChatMessage>,
    tools: List<AgentTool>,
    model: String? = null,
    thinkingEnabled: Boolean,
    maxTokens: Int,
    samplingParams: LlamaServerSamplingParams = LlamaServerSamplingParams()
): Map<String, Any?> {
    val normalizedMessages = normalizeLlamaServerMessageSequence(
        messages = normalizeLlamaServerSystemMessages(messages),
        thinkingEnabled = thinkingEnabled
    )
    val payload = linkedMapOf<String, Any?>(
        "stream" to true,
        "model" to (model?.ifBlank { null } ?: "local-model"),
        "stream_options" to mapOf("include_usage" to true),
        "messages" to normalizedMessages.map { msg ->
            linkedMapOf<String, Any?>(
                "role" to msg.role,
                "content" to if (!msg.imagePath.isNullOrBlank() || !msg.audioPath.isNullOrBlank()) {
                    buildNativeLlamaUserContent(
                        userMessage = msg.content,
                        imagePath = msg.imagePath,
                        audioPath = msg.audioPath
                    )
                } else {
                    msg.content
                }
            ).apply {
                msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                    put(
                        "tool_calls",
                        calls.map { tc ->
                            mapOf(
                                "id" to (tc.id ?: "call_${System.nanoTime()}"),
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to tc.name,
                                    "arguments" to JSONObject(tc.arguments as Map<*, *>).toString()
                                )
                            )
                        }
                    )
                }
                if (msg.role == "tool" && msg.toolCallId != null) {
                    put("tool_call_id", msg.toolCallId)
                }
            }
        },
        "chat_template_kwargs" to mapOf("enable_thinking" to thinkingEnabled)
    )

    if (maxTokens > 0) {
        payload["max_tokens"] = maxTokens
    }

    samplingParams.temperature?.let { payload["temperature"] = it }
    samplingParams.topP?.let { payload["top_p"] = it }
    samplingParams.topK?.let { payload["top_k"] = it }
    samplingParams.minP?.let { payload["min_p"] = it }
    samplingParams.seed?.let { payload["seed"] = it }
    samplingParams.repeatPenalty?.let { payload["repeat_penalty"] = it }
    samplingParams.frequencyPenalty?.let { payload["frequency_penalty"] = it }
    samplingParams.presencePenalty?.let { payload["presence_penalty"] = it }

    if (tools.isNotEmpty()) {
        payload["tools"] = tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to tool.parameters.mapValues { (_, paramDesc) ->
                            mapOf(
                                "type" to "string",
                                "description" to paramDesc
                            )
                        },
                        "required" to tool.requiredParams
                    )
                )
            )
        }
        payload["tool_choice"] = "auto"
    }

    if (!thinkingEnabled) {
        payload["reasoning_effort"] = "none"
        payload["reasoning"] = mapOf("effort" to "none")
    }

    return payload
}

internal fun normalizeLlamaServerSystemMessages(
    messages: List<OllamaService.ChatMessage>
): List<OllamaService.ChatMessage> {
    val systemMessages = messages.filter { it.role == "system" }
    if (systemMessages.isEmpty()) return messages

    val mergedSystemContent = systemMessages
        .mapNotNull { it.content.takeIf(String::isNotBlank) }
        .joinToString("\n\n")

    val nonSystemMessages = messages.filterNot { it.role == "system" }
    if (mergedSystemContent.isBlank()) return nonSystemMessages

    return listOf(
        systemMessages.first().copy(
            content = mergedSystemContent,
            toolCalls = null,
            toolCallId = null,
            imagePath = null,
            audioPath = null
        )
    ) + nonSystemMessages
}

internal fun normalizeLlamaServerMessageSequence(
    messages: List<OllamaService.ChatMessage>,
    thinkingEnabled: Boolean = false
): List<OllamaService.ChatMessage> {
    val trimmed = messages.dropLastWhile {
        it.role == "assistant" && it.content.isBlank() && it.toolCalls.isNullOrEmpty()
    }
    if (trimmed.size < 2) {
        return if (thinkingEnabled) trimmed.dropLastWhile { it.role == "assistant" } else trimmed
    }

    var firstTrailingAssistant = trimmed.size
    while (firstTrailingAssistant > 0 && trimmed[firstTrailingAssistant - 1].role == "assistant") {
        firstTrailingAssistant--
    }
    if (trimmed.size - firstTrailingAssistant < 2) {
        return if (thinkingEnabled) trimmed.dropLastWhile { it.role == "assistant" } else trimmed
    }

    val prefix = trimmed.take(firstTrailingAssistant)
    val trailing = trimmed.drop(firstTrailingAssistant)
    val mergedContent = trailing
        .mapNotNull { it.content.takeIf(String::isNotBlank) }
        .joinToString("\n\n")
    val mergedThinking = trailing
        .mapNotNull { it.thinking?.takeIf(String::isNotBlank) }
        .joinToString("\n\n")
        .takeIf(String::isNotBlank)
    val last = trailing.last()
    val merged = prefix + last.copy(
        content = mergedContent,
        thinking = mergedThinking,
        toolCalls = null,
        toolCallId = null
    )
    return if (thinkingEnabled) merged.dropLastWhile { it.role == "assistant" } else merged
}

data class LlamaServerSamplingParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val seed: Int? = null,
    val repeatPenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
) {
    companion object {
        fun fromParams(params: Map<String, Any>): LlamaServerSamplingParams = LlamaServerSamplingParams(
            temperature = (params["temperature"] as? Number)?.toFloat(),
            topP = (params["top_p"] as? Number)?.toFloat(),
            topK = (params["top_k"] as? Number)?.toInt(),
            minP = (params["min_p"] as? Number)?.toFloat(),
            seed = (params["seed"] as? Number)?.toInt(),
            repeatPenalty = (params["repeat_penalty"] as? Number)?.toFloat(),
            frequencyPenalty = (params["frequency_penalty"] as? Number)?.toFloat(),
            presencePenalty = (params["presence_penalty"] as? Number)?.toFloat()
        )
    }
}

internal fun parseLlamaServerUsage(chunk: JSONObject): OllamaService.ChatUsage? {
    val usage = chunk.optJSONObject("usage") ?: return null
    val promptTokens = usage.optInt("prompt_tokens").takeIf { it > 0 }
    val completionTokens = usage.optInt("completion_tokens").takeIf { it > 0 }
    val totalTokens = usage.optInt("total_tokens").takeIf { it > 0 }
    if (promptTokens == null && completionTokens == null && totalTokens == null) return null
    return OllamaService.ChatUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        backend = "llama-server"
    )
}
