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
import java.io.IOException
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
    val returnPromptProgress: Boolean = true,
    val requireToolCall: Boolean = false,
    /** Per-request llama.cpp reasoning cap. Null keeps the server default. */
    val thinkingBudgetTokens: Int? = null,
    /** OpenAI tool choice after the Harness adapter has validated its shape. */
    val toolChoice: String? = null,
    /**
     * Network retries are safe only when the transport proves the old request
     * was rejected before admission. Harness requests disable the historical
     * retry because a dropped connection cannot prove server-side generation
     * termination; the owning request/session identity then remains unique.
     */
    val allowRecoveryAfterConfirmedEnd: Boolean = true,
    /**
     * Read deadline for the generation SSE stream. The existing 30-minute
     * default remains for ordinary callers; an owned Harness request sets this
     * to zero and lets request cancellation/slot ownership end the stream.
     */
    val streamReadTimeoutMs: Int = 1_800_000
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
        /** Optional usage/[DONE] frames get a short drain window after finish. */
        private const val POST_FINISH_DRAIN_TIMEOUT_MS = 1_000
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

    /**
     * Bounds the optional tail after a terminal SSE choice even when the
     * platform socket ignores a read-timeout change made after connect.
     */
    private class PostFinishDrainGuard(
        private val connection: HttpURLConnection,
        private val timeoutMs: Long
    ) {
        private val cancelled = AtomicBoolean(false)
        private var worker: Thread? = null

        fun arm() {
            if (worker != null) return
            val thread = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    if (cancelled.compareAndSet(false, true)) {
                        runCatching { connection.disconnect() }
                    }
                } catch (_: InterruptedException) {
                    // The optional usage/[DONE] tail arrived before the bound.
                }
            }.apply {
                name = "llama-server-finish-drain"
                isDaemon = true
            }
            worker = thread
            thread.start()
        }

        fun cancel() {
            cancelled.set(true)
            worker?.interrupt()
        }
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
        thinkingEnabled: Boolean = true,
        requireToolCall: Boolean = false
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
                    returnPromptProgress = false,
                    requireToolCall = requireToolCall
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
     * @param onStreamDiagnostics metadata-only response-channel counts callback
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
        onStreamDiagnostics: (LlamaServerStreamChannelCounts) -> Unit = {},
        onChunk: (String?, String?) -> Unit = { _, _ -> },
        /** Optional suspending delivery path for consumers that require backpressure. */
        onPromptProgressSuspending: (suspend (LlamaPromptProcessingProgress) -> Unit)? = null,
        /** Optional suspending delivery path for consumers that require backpressure. */
        onChunkSuspending: (suspend (String?, String?) -> Unit)? = null
    ): Result<OllamaService.ChatResponse> = withContext(Dispatchers.IO) {
        val generation = GenerationRequest()
        activeGeneration = generation
        shouldStop = false
        var sawStreamOutput = false
        val guardedOnPromptProgress: suspend (LlamaPromptProcessingProgress) -> Unit = { progress ->
            if (onPromptProgressSuspending != null) {
                onPromptProgressSuspending.invoke(progress)
            } else {
                onPromptProgress(progress)
            }
        }
        val guardedOnChunk: suspend (String?, String?) -> Unit = { chunk, thinkingChunk ->
            if (!chunk.isNullOrBlank() || !thinkingChunk.isNullOrBlank()) {
                sawStreamOutput = true
            }
            if (onChunkSuspending != null) {
                onChunkSuspending.invoke(chunk, thinkingChunk)
            } else {
                onChunk(chunk, thinkingChunk)
            }
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
                        suspend fun perform(): OllamaService.ChatResponse = performChatWithToolsStreaming(
                            baseUrl,
                            messages,
                            tools,
                            modelLabel,
                            thinkingEnabled,
                            maxTokens,
                            samplingParams,
                            options,
                            guardedOnPromptProgress,
                            guardedOnChunk,
                            onStreamDiagnostics,
                            generation
                        )
                        if (options.allowRecoveryAfterConfirmedEnd) {
                            RemoteBackendResilience.runWithSingleRetry(
                                onRetry = { firstError ->
                                    DebugLog.log(
                                        "[$TAG] Recoverable llama-server chat failure, retrying: " +
                                            firstError.javaClass.simpleName
                                    )
                                },
                                // A retry is allowed only before any output;
                                // Harness disables this path because an EOF or
                                // socket reset does not prove old generation
                                // termination to a remote server.
                                shouldRetry = { !sawStreamOutput }
                            ) { perform() }
                        } else {
                            perform()
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

    private suspend fun performChatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        modelLabel: String?,
        thinkingEnabled: Boolean,
        maxTokens: Int?,
        samplingParams: LlamaServerSamplingParams,
        requestOptions: LlamaServerRequestOptions,
        onPromptProgress: suspend (LlamaPromptProcessingProgress) -> Unit,
        onChunk: suspend (String?, String?) -> Unit,
        onStreamDiagnostics: (LlamaServerStreamChannelCounts) -> Unit,
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
        conn.readTimeout = requestOptions.streamReadTimeoutMs.coerceAtLeast(0)
        var postFinishDrain: PostFinishDrainGuard? = null

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
            var terminalFinishReason: String? = null
            var deltaChoiceChunks = 0
            var messageFallbackChoiceChunks = 0
            var contentChannelChunks = 0
            var reasoningContentChannelChunks = 0
            var thinkingChannelChunks = 0
            var reasoningChannelChunks = 0
            var toolCallChannelChunks = 0
            val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
            val fallbackToolArgumentSnapshots = mutableMapOf<Int, String>()
            var fallbackContentSnapshot = ""
            val fallbackReasoningSnapshots = mutableMapOf<String, String>()
            val responseBudget = LlamaServerSseResponseBudget(maxTokens)
            var terminalFinishSeen = false
            var terminalBlankLines = 0

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    if (isGenerationCancelled(generation)) {
                        DebugLog.log("[$TAG] stop requested, breaking SSE stream")
                        conn.disconnect()
                        throw CancellationException("Stopped by user")
                    }

                    if (terminalFinishSeen && postFinishDrain == null) {
                        // Harness requests intentionally use readTimeout=0 while
                        // a model is pre-filling or reasoning. Once a terminal
                        // finish is observed, only an optional usage frame or
                        // [DONE] is useful; a server that keeps the socket open
                        // must not hold the turn forever.
                        postFinishDrain = PostFinishDrainGuard(
                            connection = conn,
                            timeoutMs = POST_FINISH_DRAIN_TIMEOUT_MS.toLong()
                        ).also { it.arm() }
                    }
                    val line = try {
                        readBoundedLlamaServerSseLine(reader, responseBudget)
                    } catch (io: IOException) {
                        if (terminalFinishSeen) break
                        throw io
                    }
                    val data = line.text
                    if (data.isEmpty() && line.reachedEof) break
                    if (isGenerationCancelled(generation)) {
                        DebugLog.log("[$TAG] stop requested after SSE read")
                        conn.disconnect()
                        throw CancellationException("Stopped by user")
                    }

                    // A terminal choice may be followed by one usage frame.
                    // Consume only that bounded frame, then stop even when a
                    // compatible server omits [DONE] or keeps heartbeats open.
                    if (terminalFinishSeen) {
                        when (classifyLlamaServerTerminalLine(data, terminalBlankLines)) {
                            LlamaServerTerminalLineAction.SKIP_BLANK -> {
                                terminalBlankLines++
                                continue
                            }
                            LlamaServerTerminalLineAction.STOP -> break
                            LlamaServerTerminalLineAction.PARSE_USAGE -> {
                                val jsonStr = data.removePrefix("data: ").trim()
                                runCatching { parseLlamaServerUsage(JSONObject(jsonStr)) }
                                    .getOrNull()
                                    ?.let { usage = it }
                                break
                            }
                        }
                    }

                    if (!data.startsWith("data: ")) continue
                    val jsonStr = data.removePrefix("data: ").trim()
                    if (jsonStr == "[DONE]") break

                    try {
                        val chunk = JSONObject(jsonStr)
                        if (chunk.has("id_slot")) {
                            generation.slotId = chunk.optInt("id_slot")
                        }
                        parseLlamaPromptProcessingProgress(chunk)?.let { onPromptProgress(it) }
                        parseLlamaServerUsage(chunk)?.let { usage = it }
                        val choices = chunk.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        choice.optString("finish_reason", "")
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?.let {
                                terminalFinishReason = it
                                if (isTerminalLlamaServerFinishReason(it)) {
                                    terminalFinishSeen = true
                                }
                            }
                        val responseChannel = selectLlamaServerChoicePayload(choice) ?: continue
                        when (responseChannel.source) {
                            LlamaServerChoiceSource.DELTA -> deltaChoiceChunks++
                            LlamaServerChoiceSource.MESSAGE_FALLBACK -> messageFallbackChoiceChunks++
                        }
                        val choicePayload = responseChannel.payload

                        val contentChannel = firstLlamaServerStringChannel(choicePayload, "content")
                        val rawContent = contentChannel?.value.orEmpty()
                        val content = if (responseChannel.source == LlamaServerChoiceSource.MESSAGE_FALLBACK) {
                            val delta = llamaServerFallbackSnapshotDelta(fallbackContentSnapshot, rawContent)
                            if (rawContent.isNotEmpty()) fallbackContentSnapshot = rawContent
                            delta
                        } else {
                            rawContent
                        }
                        if (content.isNotEmpty()) contentChannelChunks++
                        if (content.isNotEmpty()) {
                            responseBudget.accountSemanticCharacters(content.length)
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

                        val reasoningChannel = firstLlamaServerStringChannel(
                            choicePayload,
                            "reasoning_content",
                            "thinking",
                            "reasoning"
                        )
                        val rawReasoningContent = reasoningChannel?.value.orEmpty()
                        val reasoningContent = if (
                            responseChannel.source == LlamaServerChoiceSource.MESSAGE_FALLBACK &&
                            reasoningChannel != null
                        ) {
                            val previous = fallbackReasoningSnapshots[reasoningChannel.name].orEmpty()
                            val delta = llamaServerFallbackSnapshotDelta(previous, rawReasoningContent)
                            if (rawReasoningContent.isNotEmpty()) {
                                fallbackReasoningSnapshots[reasoningChannel.name] = rawReasoningContent
                            }
                            delta
                        } else {
                            rawReasoningContent
                        }
                        if (reasoningContent.isNotEmpty()) {
                            when (reasoningChannel?.name) {
                                "reasoning_content" -> reasoningContentChannelChunks++
                                "thinking" -> thinkingChannelChunks++
                                "reasoning" -> reasoningChannelChunks++
                            }
                            responseBudget.accountSemanticCharacters(reasoningContent.length)
                            fullThinking.append(reasoningContent)
                            if (thinkingEnabled) onChunk(null, reasoningContent)
                        }

                        val tcArray = choicePayload.optJSONArray("tool_calls")
                        if (tcArray != null && tcArray.length() > 0) toolCallChannelChunks++
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
                                    val argumentDelta = if (responseChannel.source == LlamaServerChoiceSource.MESSAGE_FALLBACK) {
                                        val previous = fallbackToolArgumentSnapshots[index].orEmpty()
                                        val delta = llamaServerFallbackSnapshotDelta(previous, args)
                                        if (args.isNotEmpty()) fallbackToolArgumentSnapshots[index] = args
                                        delta
                                    } else {
                                        args
                                    }
                                    responseBudget.accountSemanticCharacters(argumentDelta.length)
                                    builder.arguments.append(argumentDelta)
                                }
                            }
                        }

                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason.isNotBlank() && finishReason != "null") {
                            terminalFinishReason = finishReason
                            if (isTerminalLlamaServerFinishReason(finishReason)) {
                                terminalFinishSeen = true
                            }
                        }
                    } catch (e: Exception) {
                        when (classifySseProcessingFailure(e)) {
                            SseProcessingFailureKind.CANCELLATION -> {
                                DebugLog.log("[$TAG] SSE stream cancelled because the owning Agent job stopped")
                                cancelGeneration(generation)
                                throw e
                            }
                            SseProcessingFailureKind.MALFORMED_JSON -> {
                                DebugLog.log("[$TAG] SSE parse error: ${e.javaClass.simpleName}")
                            }
                            SseProcessingFailureKind.PROCESSING -> {
                                DebugLog.log("[$TAG] SSE processing error: ${e.javaClass.simpleName}")
                                throw e
                            }
                        }
                    }
                }
            }

            val toolCalls = if (toolCallBuilders.isNotEmpty()) {
                toolCallBuilders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
                    assembleLlamaServerToolCall(
                        name = builder.name,
                        id = builder.id,
                        rawArgumentsJson = builder.arguments.toString()
                    )
                }
            } else {
                null
            }

            onStreamDiagnostics(
                LlamaServerStreamChannelCounts(
                    deltaChoiceChunks = deltaChoiceChunks,
                    messageFallbackChoiceChunks = messageFallbackChoiceChunks,
                    contentChannelChunks = contentChannelChunks,
                    reasoningContentChannelChunks = reasoningContentChannelChunks,
                    thinkingChannelChunks = thinkingChannelChunks,
                    reasoningChannelChunks = reasoningChannelChunks,
                    toolCallChannelChunks = toolCallChannelChunks
                )
            )
            DebugLog.log(
                "[$TAG] Stream finished. ${toolCalls?.size ?: 0} tool calls detected; " +
                    "channels=delta:$deltaChoiceChunks message:$messageFallbackChoiceChunks " +
                    "content:$contentChannelChunks reasoning_content:$reasoningContentChannelChunks " +
                    "thinking:$thinkingChannelChunks reasoning:$reasoningChannelChunks " +
                    "tool_calls:$toolCallChannelChunks"
            )

            return OllamaService.ChatResponse(
                message = OllamaService.ChatMessage(
                    role = "assistant",
                    content = fullContent.toString(),
                    toolCalls = toolCalls,
                    thinking = fullThinking.toString().ifEmpty { null }
                ),
                done = true,
                toolCalls = toolCalls,
                usage = usage,
                finishReason = terminalFinishReason
            )
        } finally {
            postFinishDrain?.cancel()
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
                DebugLog.log("[$TAG] llama-server health probe failed: ${error.javaClass.simpleName}")
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
        // A missing slot identity is intentionally not replaced with llama.cpp's
        // `-1` broadcast sentinel. Disconnecting the owned HTTP connection is
        // safe in that case; a broadcast cancel could terminate another user's
        // generation or every active slot on a shared server.
        val candidateSlotIds = listOfNotNull(slotId).distinct()
        if (candidateSlotIds.isEmpty()) return
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
                DebugLog.log("[$TAG] Best-effort cancel failed for id_slot=$candidate: ${error.javaClass.simpleName}")
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
    if (thinkingEnabled) {
        requestOptions.thinkingBudgetTokens
            ?.takeIf { it > 0 }
            ?.let { payload["thinking_budget_tokens"] = it }
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
        val toolChoice = requestOptions.toolChoice?.trim()?.lowercase()
            ?: if (requestOptions.requireToolCall) "required" else "auto"
        require(toolChoice == "auto" || toolChoice == "required") {
            "Unsupported llama-server tool choice: $toolChoice"
        }
        payload["tool_choice"] = toolChoice
        if (toolChoice == "required") payload["parallel_tool_calls"] = false
    }

    if (!thinkingEnabled) {
        payload["reasoning_effort"] = "none"
        payload["reasoning"] = mapOf("effort" to "none")
    }

    return payload
}

/**
 * Preserve an output-limited native tool argument as bounded transport state.
 * The Direct runtime can safely stage an incomplete write/edit prefix; every
 * other caller still receives empty parsed arguments and must reject it during
 * normal validation. Dropping the call here loses the only recoverable mutation
 * boundary and makes a 4K-output model repeat the same oversized call forever.
 */
internal fun assembleLlamaServerToolCall(
    name: String,
    id: String?,
    rawArgumentsJson: String
): OllamaService.ToolCall? {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty()) return null
    val raw = rawArgumentsJson.takeIf { it.isNotBlank() }
    val arguments = runCatching {
        AgentRuntimeSupport.normalizeToolArguments(rawArgumentsJson)
    }.getOrElse {
        DebugLog.log("[LlamaServerChat] Preserving incomplete tool call: $normalizedName")
        emptyMap()
    }
    return OllamaService.ToolCall(
        name = normalizedName,
        arguments = arguments,
        id = id?.takeIf { it.isNotBlank() }
            ?: stableToolCallId(normalizedName, raw.orEmpty()),
        rawArgumentsJson = raw
    )
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

internal enum class LlamaServerChoiceSource {
    DELTA,
    MESSAGE_FALLBACK
}

internal data class LlamaServerChoicePayload(
    val source: LlamaServerChoiceSource,
    val payload: JSONObject
)

/**
 * Prefer a delta whenever the server supplied one, including an empty delta.
 * A full message is only a fallback for servers that omit delta entirely.
 */
internal fun selectLlamaServerChoicePayload(choice: JSONObject): LlamaServerChoicePayload? {
    if (choice.has("delta")) {
        val delta = choice.optJSONObject("delta") ?: return null
        return LlamaServerChoicePayload(LlamaServerChoiceSource.DELTA, delta)
    }
    return choice.optJSONObject("message")?.let {
        LlamaServerChoicePayload(LlamaServerChoiceSource.MESSAGE_FALLBACK, it)
    }
}

internal data class LlamaServerStringChannel(
    val name: String,
    val value: String
)

/** Read only string-valued response channels; objects and JSON null are ignored. */
internal fun firstLlamaServerStringChannel(
    payload: JSONObject,
    vararg names: String
): LlamaServerStringChannel? {
    for (name in names) {
        val value = payload.opt(name) as? String ?: continue
        if (value.equals("null", ignoreCase = true) || value.isEmpty()) continue
        return LlamaServerStringChannel(name, value)
    }
    return null
}

/** Non-content metadata for diagnosing alternate streaming response channels. */
internal data class LlamaServerStreamChannelCounts(
    val deltaChoiceChunks: Int = 0,
    val messageFallbackChoiceChunks: Int = 0,
    val contentChannelChunks: Int = 0,
    val reasoningContentChannelChunks: Int = 0,
    val thinkingChannelChunks: Int = 0,
    val reasoningChannelChunks: Int = 0,
    val toolCallChannelChunks: Int = 0
)

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
