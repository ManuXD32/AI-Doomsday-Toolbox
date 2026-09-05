package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.util.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

const val NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED = "max_output_tokens_enabled"
const val NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS = "max_output_tokens"

enum class LlamaSlotAffinityMode(val value: String) {
    AUTOMATIC("automatic"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromValue(value: String?): LlamaSlotAffinityMode =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: AUTOMATIC
    }
}

data class LlamaServerRequestOptions(
    val cachePrompt: Boolean = true,
    val slotId: Int? = null,
    val returnPromptProgress: Boolean = true
)

data class LlamaPromptProcessingProgress(
    val total: Int,
    val cached: Int,
    val processed: Int,
    val timeMs: Long
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

data class LlamaServerCapabilities(
    val supportsSlotSelection: Boolean,
    val slotCount: Int?,
    val supportsPromptCaching: Boolean = true,
    val serverSleeping: Boolean = false
)

data class LlamaPromptCacheDiagnostics(
    val systemPromptHash: String,
    val toolDefinitionsHash: String,
    val stablePrefixHash: String,
    val messageCount: Int,
    val toolCount: Int
)

enum class LlamaInputTokenCountStatus {
    SUPPORTED,
    UNSUPPORTED,
    TRANSIENT_FAILURE
}

data class LlamaInputTokenCountResult(
    val status: LlamaInputTokenCountStatus,
    val inputTokens: Int? = null,
    val latencyMs: Long = 0L,
    val httpCode: Int? = null,
    val errorMessage: String? = null
)

internal enum class SseProcessingFailureKind {
    CANCELLATION,
    MALFORMED_JSON,
    PROCESSING
}

internal fun classifySseProcessingFailure(error: Exception): SseProcessingFailureKind = when (error) {
    is CancellationException -> SseProcessingFailureKind.CANCELLATION
    is org.json.JSONException -> SseProcessingFailureKind.MALFORMED_JSON
    else -> SseProcessingFailureKind.PROCESSING
}

internal fun llamaServerHealthResponseReady(responseCode: Int): Boolean = responseCode == 200

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
        private const val INPUT_TOKEN_COUNT_TIMEOUT_MS = 5_000
        private const val INPUT_TOKEN_UNSUPPORTED_TTL_MS = 10L * 60L * 1000L
        private const val HEALTH_CHECK_ATTEMPTS = 3
        private const val HEALTH_CHECK_RETRY_DELAY_MS = 500L
        private val unsupportedInputTokenEndpoints =
            java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    /**
     * Cancellation belongs to one request, not to the service singleton. A retry can start
     * while the previous HTTP connection is still unwinding; a global stop flag would be
     * cleared by that retry and let the old stream continue writing into the new turn.
     */
    private class GenerationRequest {
        val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var baseUrl: String? = null
        @Volatile var slotId: Int? = null
    }

    @Volatile
    private var activeGeneration: GenerationRequest? = null

    @Volatile
    var shouldStop = false

    fun stopGeneration() {
        shouldStop = true
        activeGeneration?.let(::cancelGeneration)
    }

    /** Cancel one request without touching a newer retry that now owns the singleton. */
    private fun cancelGeneration(generation: GenerationRequest) {
        if (!generation.cancelled.compareAndSet(false, true)) return
        val cancelBaseUrl = generation.baseUrl
        val cancelSlotId = generation.slotId
        runCatching { generation.connection?.disconnect() }
        if (!cancelBaseUrl.isNullOrBlank()) {
            Thread {
                sendBestEffortLlamaServerCancel(cancelBaseUrl, cancelSlotId)
            }.apply {
                name = "llama-server-cancel"
                isDaemon = true
                start()
            }
        }
    }

    internal suspend fun countChatInputTokens(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        modelLabel: String? = null,
        thinkingEnabled: Boolean = true
    ): LlamaInputTokenCountResult = withContext(Dispatchers.IO) {
        val normalizedBase = normalizeLlamaServerBaseUrlForHealth(baseUrl)
            ?: return@withContext LlamaInputTokenCountResult(
                status = LlamaInputTokenCountStatus.TRANSIENT_FAILURE,
                errorMessage = "Invalid llama-server URL"
            )
        val capabilityKey = "$normalizedBase|${modelLabel.orEmpty()}"
        val now = System.currentTimeMillis()
        val unsupportedUntil = unsupportedInputTokenEndpoints[capabilityKey]
        if (unsupportedUntil != null && unsupportedUntil > now) {
            return@withContext LlamaInputTokenCountResult(
                status = LlamaInputTokenCountStatus.UNSUPPORTED,
                errorMessage = "input_tokens endpoint is temporarily cached as unsupported"
            )
        }
        unsupportedInputTokenEndpoints.remove(capabilityKey, unsupportedUntil)

        val startedAt = android.os.SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val payload = buildLlamaServerChatRequestPayload(
                messages = messages,
                tools = tools,
                model = modelLabel,
                thinkingEnabled = thinkingEnabled,
                maxTokens = null,
                requestOptions = LlamaServerRequestOptions(
                    cachePrompt = false,
                    slotId = null,
                    returnPromptProgress = false
                )
            ).toMutableMap().apply {
                put("stream", false)
                remove("stream_options")
                remove("return_progress")
                remove("sse_ping_interval")
                remove("cache_prompt")
                remove("id_slot")
            }
            val requestUrl = HttpEndpointUrlSupport.appendPath(
                normalizedBase,
                "/v1/chat/completions/input_tokens"
            ) ?: return@withContext LlamaInputTokenCountResult(
                status = LlamaInputTokenCountStatus.TRANSIENT_FAILURE,
                errorMessage = "Invalid llama-server URL"
            )
            conn = URL(requestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = INPUT_TOKEN_COUNT_TIMEOUT_MS
            conn.readTimeout = INPUT_TOKEN_COUNT_TIMEOUT_MS
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(buildJsonObject(payload).toString())
                writer.flush()
            }

            val code = conn.responseCode
            val responseBody = runCatching {
                val stream = if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val latencyMs = (
                android.os.SystemClock.elapsedRealtime() - startedAt
            ).coerceAtLeast(0L)

            when {
                code in 200..299 -> {
                    val inputTokens = parseLlamaInputTokenCountBody(responseBody)
                    if (inputTokens != null && inputTokens >= 0) {
                        unsupportedInputTokenEndpoints.remove(capabilityKey)
                        LlamaInputTokenCountResult(
                            status = LlamaInputTokenCountStatus.SUPPORTED,
                            inputTokens = inputTokens,
                            latencyMs = latencyMs,
                            httpCode = code
                        )
                    } else {
                        LlamaInputTokenCountResult(
                            status = LlamaInputTokenCountStatus.TRANSIENT_FAILURE,
                            latencyMs = latencyMs,
                            httpCode = code,
                            errorMessage = "Missing input token count in response"
                        )
                    }
                }
                code == 404 || code == 405 || code == 501 -> {
                    unsupportedInputTokenEndpoints[capabilityKey] =
                        now + INPUT_TOKEN_UNSUPPORTED_TTL_MS
                    LlamaInputTokenCountResult(
                        status = LlamaInputTokenCountStatus.UNSUPPORTED,
                        latencyMs = latencyMs,
                        httpCode = code,
                        errorMessage = responseBody.take(240)
                    )
                }
                else -> LlamaInputTokenCountResult(
                    status = LlamaInputTokenCountStatus.TRANSIENT_FAILURE,
                    latencyMs = latencyMs,
                    httpCode = code,
                    errorMessage = responseBody.take(240)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LlamaInputTokenCountResult(
                status = LlamaInputTokenCountStatus.TRANSIENT_FAILURE,
                latencyMs = (
                    android.os.SystemClock.elapsedRealtime() - startedAt
                ).coerceAtLeast(0L),
                errorMessage = error.message?.take(240)
                    ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Send a chat completion request to llama-server with tool support.
     * Returns the same ChatResponse type as OllamaService for seamless integration.
     *
     * @param baseUrl llama-server base URL (e.g., "http://localhost:8080")
     * @param messages conversation history
     * @param tools available tools for function calling
     * @param thinkingEnabled if false, strip <think> tags from output
     * @param maxTokens optional maximum output tokens hint
     * @param onChunk streaming callback: (contentDelta, thinkingDelta)
     */
    internal suspend fun chatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        modelLabel: String? = null,
        thinkingEnabled: Boolean = true,
        maxTokens: Int? = null,
        samplingParams: LlamaServerSamplingParams = LlamaServerSamplingParams(),
        requestOptions: LlamaServerRequestOptions = LlamaServerRequestOptions(),
        slotOwner: LlamaSlotOwnerKey? = null,
        slotAffinityMode: LlamaSlotAffinityMode = LlamaSlotAffinityMode.AUTOMATIC,
        onPromptProgress: (LlamaPromptProcessingProgress) -> Unit = {},
        onChunk: (String?, String?) -> Unit = { _, _ -> }
    ): Result<OllamaService.ChatResponse> = withContext(Dispatchers.IO) {
        val generation = GenerationRequest()
        activeGeneration = generation
        shouldStop = false
        var sawStreamOutput = false
        val guardedOnChunk: (String?, String?) -> Unit = { chunk, thinkingChunk ->
            if (!chunk.isNullOrBlank() || !thinkingChunk.isNullOrBlank()) {
                sawStreamOutput = true
            }
            onChunk(chunk, thinkingChunk)
        }

        try {
            val capabilities = if (slotOwner != null && slotAffinityMode != LlamaSlotAffinityMode.DISABLED) {
                discoverCapabilities(baseUrl)
            } else {
                null
            }
            val diagnostics = buildLlamaPromptCacheDiagnostics(messages, tools, thinkingEnabled)
            val chatResponse = LlamaSlotManager.withAssignedSlot(
                owner = slotOwner,
                slotCount = capabilities?.slotCount,
                affinityMode = slotAffinityMode,
                promptFingerprint = diagnostics.stablePrefixHash
            ) { assignedSlot ->
                suspend fun execute(options: LlamaServerRequestOptions): OllamaService.ChatResponse {
                    return RemoteAgentProtection.withProtection(baseUrl, "Running remote llama-server agent…") {
                        RemoteBackendResilience.runWithSingleRetry(
                            onRetry = { firstError ->
                                DebugLog.log(
                                    "[$TAG] Recoverable llama-server chat failure, retrying: " +
                                        RemoteBackendResilience.summarize(firstError)
                                )
                            },
                            shouldRetry = { !sawStreamOutput }
                        ) {
                            performChatWithToolsStreaming(
                                baseUrl,
                                messages,
                                tools,
                                modelLabel,
                                thinkingEnabled,
                                maxTokens,
                                samplingParams,
                                options,
                                onPromptProgress,
                                guardedOnChunk,
                                generation
                            )
                        }
                    }
                }
                val effectiveOptions = requestOptions.copy(
                    slotId = requestOptions.slotId ?: assignedSlot
                )
                try {
                    execute(effectiveOptions)
                } catch (error: Throwable) {
                    if (effectiveOptions.slotId != null &&
                        slotOwner != null &&
                        isRecognizedSlotSelectionError(error)
                    ) {
                        DebugLog.log(
                            "[$TAG] Slot selection unsupported for generation; retrying once without id_slot"
                        )
                        LlamaSlotManager.markSlotSelectionUnsupported(slotOwner.endpointGeneration)
                        execute(effectiveOptions.copy(slotId = null))
                    } else {
                        throw error
                    }
                }
            }
            Result.success(chatResponse)
        } catch (cancelled: CancellationException) {
            DebugLog.log("[$TAG] SSE stream cancelled because the owning Agent job stopped")
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (activeGeneration === generation) {
                activeGeneration = null
            }
        }
    }

    private fun performChatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        modelLabel: String?,
        thinkingEnabled: Boolean,
        maxTokens: Int?,
        samplingParams: LlamaServerSamplingParams,
        requestOptions: LlamaServerRequestOptions,
        onPromptProgress: (LlamaPromptProcessingProgress) -> Unit,
        onChunk: (String?, String?) -> Unit,
        generation: GenerationRequest
    ): OllamaService.ChatResponse {
        ensureGenerationActive(generation)
        val normalizedBaseUrl = HttpEndpointUrlSupport.normalizeBaseUrl(baseUrl)
            ?: throw IllegalArgumentException("Invalid llama-server URL")
        val requestUrl = HttpEndpointUrlSupport.appendPath(
            normalizedBaseUrl,
            "/v1/chat/completions"
        ) ?: throw IllegalArgumentException("Invalid llama-server URL")
        val url = URL(requestUrl)
        val conn = url.openConnection() as HttpURLConnection
        generation.connection = conn
        generation.baseUrl = normalizedBaseUrl
        generation.slotId = requestOptions.slotId
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
                maxTokens = maxTokens,
                samplingParams = samplingParams,
                requestOptions = requestOptions
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
                while (true) {
                    if (isGenerationCancelled(generation)) {
                        DebugLog.log("[$TAG] stop requested, breaking SSE stream")
                        conn.disconnect()
                        throw CancellationException("Stopped by user")
                    }

                    val data = reader.readLine() ?: break
                    if (isGenerationCancelled(generation)) {
                        DebugLog.log("[$TAG] stop requested after SSE read")
                        conn.disconnect()
                        throw CancellationException("Stopped by user")
                    }
                    if (!data.startsWith("data: ")) continue
                    val jsonStr = data.removePrefix("data: ").trim()
                    if (jsonStr == "[DONE]") break

                    try {
                        val chunk = JSONObject(jsonStr)
                        if (chunk.has("id_slot")) {
                            generation.slotId = chunk.optInt("id_slot")
                        }
                        parseLlamaPromptProcessingProgress(chunk)?.let(onPromptProgress)
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
                        when (classifySseProcessingFailure(e)) {
                            SseProcessingFailureKind.CANCELLATION -> {
                                DebugLog.log("[$TAG] SSE stream cancelled because the owning Agent job stopped")
                                cancelGeneration(generation)
                                throw e
                            }
                            SseProcessingFailureKind.MALFORMED_JSON -> {
                                DebugLog.log("[$TAG] SSE parse error: ${e.message} for line: $jsonStr")
                            }
                            SseProcessingFailureKind.PROCESSING -> {
                                DebugLog.log("[$TAG] SSE processing error: ${e.javaClass.simpleName}: ${e.message}")
                                throw e
                            }
                        }
                    }
                }
            }

            val toolCalls = if (toolCallBuilders.isNotEmpty()) {
                toolCallBuilders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
                    if (builder.name.isNotEmpty()) {
                        try {
                            val args = AgentRuntimeSupport.normalizeToolArguments(builder.arguments.toString())
                            DebugLog.log("[$TAG] Assembled tool call: ${builder.name} (id: ${builder.id})")
                            val rawArgumentsJson = builder.arguments.toString()
                                .takeIf { it.isNotBlank() }
                            OllamaService.ToolCall(
                                name = builder.name,
                                arguments = args,
                                id = builder.id.takeIf { it.isNotBlank() }
                                    ?: stableToolCallId(builder.name, rawArgumentsJson.orEmpty()),
                                rawArgumentsJson = rawArgumentsJson
                            )
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
            if (generation.connection === conn) {
                generation.connection = null
                generation.baseUrl = null
                generation.slotId = null
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
        val normalizedBaseUrl = normalizeLlamaServerBaseUrlForHealth(baseUrl) ?: return@withContext false
        repeat(HEALTH_CHECK_ATTEMPTS) { attempt ->
            val healthy = try {
                val url = URL(
                    HttpEndpointUrlSupport.appendPath(normalizedBaseUrl, "/health")
                        ?: return@withContext false
                )
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    llamaServerHealthResponseReady(conn.responseCode)
                } finally {
                    conn.disconnect()
                }
            } catch (cancelled: CancellationException) {
                // Stop/retry cancellation must unwind the agent turn. Treating it as an
                // offline server causes the canceled turn to append a false Needs Direction
                // pause and can re-block the next user retry.
                throw cancelled
            } catch (error: Exception) {
                DebugLog.log("[$TAG] llama-server health probe failed: ${error.message ?: error.javaClass.simpleName}")
                false
            }
            if (healthy) return@withContext true
            if (attempt + 1 < HEALTH_CHECK_ATTEMPTS) {
                delay(HEALTH_CHECK_RETRY_DELAY_MS)
            }
        }
        false
    }

    private fun ensureGenerationActive(generation: GenerationRequest) {
        if (isGenerationCancelled(generation)) {
            throw CancellationException("Generation stopped")
        }
    }

    private fun isGenerationCancelled(generation: GenerationRequest): Boolean =
        generation.cancelled.get() || (shouldStop && activeGeneration === generation)

    suspend fun discoverCapabilities(baseUrl: String): LlamaServerCapabilities = withContext(Dispatchers.IO) {
        val normalized = normalizeLlamaServerBaseUrlForHealth(baseUrl)
            ?: return@withContext LlamaServerCapabilities(false, null)
        var slotCount: Int? = null
        var sleeping = false

        runCatching {
            val conn = URL(
                HttpEndpointUrlSupport.appendPath(normalized, "/props")
                    ?: return@runCatching
            ).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val props = JSONObject(body)
                    slotCount = props.optInt("total_slots").takeIf { it > 0 }
                        ?: props.optJSONObject("default_generation_settings")
                            ?.optInt("n_slots")
                            ?.takeIf { it > 0 }
                    sleeping = props.optBoolean("is_sleeping", false) ||
                        props.optString("status").equals("sleeping", ignoreCase = true)
                }
            } finally {
                conn.disconnect()
            }
        }

        val slotsSupported = runCatching {
            val conn = URL(
                HttpEndpointUrlSupport.appendPath(normalized, "/slots")
                    ?: return@runCatching false
            ).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                if (conn.responseCode !in 200..299) return@runCatching false
                val slots = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                if (slots.length() > 0) slotCount = slots.length()
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

        LlamaServerCapabilities(
            supportsSlotSelection = slotsSupported,
            slotCount = slotCount,
            supportsPromptCaching = true,
            serverSleeping = sleeping
        )
    }

    private fun normalizeLlamaServerBaseUrlForHealth(baseUrl: String): String? {
        return HttpEndpointUrlSupport.normalizeBaseUrl(baseUrl)
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

    private fun sendBestEffortLlamaServerCancel(baseUrl: String, slotId: Int?) {
        val normalizedBase = HttpEndpointUrlSupport.normalizeBaseUrl(baseUrl) ?: return
        val candidateSlotIds = listOfNotNull(slotId, -1).distinct()
        for (candidate in candidateSlotIds) {
            runCatching {
                val conn = URL(
                    HttpEndpointUrlSupport.appendPath(normalizedBase, "/slots")
                        ?: return@runCatching
                ).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.doOutput = true
                    val body = JSONObject()
                        .put("id_slot", candidate)
                        .put("action", "cancel")
                        .toString()
                    OutputStreamWriter(conn.outputStream).use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                    val code = conn.responseCode
                    DebugLog.log("[$TAG] Best-effort cancel /slots id_slot=$candidate -> HTTP $code")
                    if (code in 200..299) return
                } finally {
                    conn.disconnect()
                }
            }.onFailure { error ->
                DebugLog.log("[$TAG] Best-effort cancel failed for id_slot=$candidate: ${error.message}")
            }
        }
    }
}

internal fun buildLlamaServerChatRequestPayload(
    messages: List<OllamaService.ChatMessage>,
    tools: List<AgentTool>,
    model: String? = null,
    thinkingEnabled: Boolean,
    maxTokens: Int? = null,
    samplingParams: LlamaServerSamplingParams = LlamaServerSamplingParams(),
    requestOptions: LlamaServerRequestOptions = LlamaServerRequestOptions()
): Map<String, Any?> {
    val normalizedMessages = normalizeLlamaServerMessageSequence(
        messages = normalizeLlamaServerSystemMessages(messages),
        thinkingEnabled = thinkingEnabled
    )
    val payload = linkedMapOf<String, Any?>(
        "stream" to true,
        "model" to (model?.ifBlank { null } ?: "local-model"),
        "stream_options" to mapOf("include_usage" to true),
        "return_progress" to requestOptions.returnPromptProgress,
        "sse_ping_interval" to 2,
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
                            linkedMapOf(
                                "id" to (tc.id ?: stableToolCallId(tc.name, canonicalToolArguments(tc))),
                                "type" to "function",
                                "function" to linkedMapOf(
                                    "name" to tc.name,
                                    "arguments" to canonicalToolArguments(tc)
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
        "chat_template_kwargs" to linkedMapOf("enable_thinking" to thinkingEnabled),
        "cache_prompt" to requestOptions.cachePrompt
    )

    requestOptions.slotId?.takeIf { it >= 0 }?.let { payload["id_slot"] = it }

    if (maxTokens != null && maxTokens > 0) {
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
        payload["tools"] = tools.sortedBy { it.name }.map { tool ->
            linkedMapOf(
                "type" to "function",
                "function" to linkedMapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to (
                        tool.schemaJson
                            ?.let { schema -> runCatching { JSONObject(schema).toMapRecursively() }.getOrNull() }
                            ?: linkedMapOf(
                                "type" to "object",
                                "properties" to linkedMapOf<String, Any?>().apply {
                                    tool.parameters.toSortedMap().forEach { (paramName, paramDesc) ->
                                        put(
                                            paramName,
                                            linkedMapOf(
                                                "type" to "string",
                                                "description" to paramDesc
                                            )
                                        )
                                    }
                                },
                                "required" to tool.requiredParams.sorted(),
                                "additionalProperties" to false
                            )
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

internal fun parseLlamaInputTokenCountBody(body: String): Int? {
    if (body.isBlank()) return null
    return runCatching {
        val json = JSONObject(body)
        json.optInt("input_tokens", -1).takeIf { it >= 0 }
            ?: json.optInt("prompt_tokens", -1).takeIf { it >= 0 }
            ?: json.optInt("tokens_count", -1).takeIf { it >= 0 }
            ?: json.optInt("n_tokens", -1).takeIf { it >= 0 }
            ?: json.optJSONObject("usage")
                ?.optInt("prompt_tokens", -1)
                ?.takeIf { it >= 0 }
            ?: when (val tokens = json.opt("tokens")) {
                is JSONArray -> tokens.length()
                is Number -> tokens.toInt().takeIf { it >= 0 }
                else -> null
            }
    }.getOrNull()
}

internal fun parseLlamaPromptProcessingProgress(
    chunk: JSONObject
): LlamaPromptProcessingProgress? {
    val progress = chunk.optJSONObject("prompt_progress") ?: return null
    val total = progress.optInt("total", 0)
    val processed = progress.optInt("processed", 0)
    if (total <= 0 || processed < 0) return null
    return LlamaPromptProcessingProgress(
        total = total,
        cached = progress.optInt("cache", 0).coerceAtLeast(0),
        processed = processed.coerceAtMost(total),
        timeMs = progress.optLong("time_ms", 0L).coerceAtLeast(0L)
    )
}

private fun JSONObject.toMapRecursively(): Map<String, Any?> =
    keys().asSequence().toList().sorted().associateWith { key ->
        when (val value = opt(key)) {
            is JSONObject -> value.toMapRecursively()
            is JSONArray -> (0 until value.length()).map { index ->
                when (val item = value.opt(index)) {
                    is JSONObject -> item.toMapRecursively()
                    is JSONArray -> (0 until item.length()).map(item::opt)
                    JSONObject.NULL -> null
                    else -> item
                }
            }
            JSONObject.NULL -> null
            else -> value
        }
    }

internal fun normalizeLlamaServerSystemMessages(
    messages: List<OllamaService.ChatMessage>
): List<OllamaService.ChatMessage> {
    var keptStableSystem = false
    return messages.mapNotNull { message ->
        if (message.role != "system") {
            message
        } else if (!keptStableSystem && message.content.isNotBlank()) {
            keptStableSystem = true
            message.copy(
                toolCalls = null,
                toolCallId = null,
                imagePath = null,
                audioPath = null
            )
        } else if (message.content.isNotBlank()) {
            // Keep changing runtime/recovery context at its original position.
            // Merging it into the first system message destroys the common prefix.
            message.copy(
                role = "user",
                content = "[Runtime context]\n${message.content}",
                toolCalls = null,
                toolCallId = null,
                imagePath = null,
                audioPath = null
            )
        } else {
            null
        }
    }
}

internal fun normalizeLlamaServerMessageSequence(
    messages: List<OllamaService.ChatMessage>,
    thinkingEnabled: Boolean = false
): List<OllamaService.ChatMessage> {
    return messages.dropLastWhile {
        it.role == "assistant" && it.content.isBlank() && it.toolCalls.isNullOrEmpty()
    }
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
