package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.runtime.AgentRuntimeProfileRuntime
import com.example.llamadroid.data.runtime.ManagedLlamaServerState
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.service.LITERT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.LITERT_PARAM_MTP_ENABLED
import com.example.llamadroid.service.LITERT_PARAM_ENGINE_OWNER
import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.LlamaPromptProcessingProgress
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.LlamaServerSamplingParams
import com.example.llamadroid.service.LlamaSlotAffinityMode
import com.example.llamadroid.service.LlamaSlotOwnerKey
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.LiteRtConversationMessage
import com.example.llamadroid.service.LiteRtConversationOverride
import com.example.llamadroid.service.LiteRtLmChatRequest
import com.example.llamadroid.service.LiteRtLmWorkerClient
import com.example.llamadroid.service.LiteRtToolCallSpec
import com.example.llamadroid.service.LiteRtToolDefinition
import com.example.llamadroid.service.resolveAgentLiteRtContextTokens
import com.example.llamadroid.service.resolveAgentLiteRtMaxOutputTokens
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Provider adapter uses existing catalogs and workers; Harness remains the only agent loop. */
class HarnessLocalModels(private val context: Context, private val database: AppDatabase) {
    private val settings: SettingsRepository get() = SettingsRepository(context)
    /** Streaming inference has no wall-clock read deadline; cancellation owns the call. */
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val inference = Mutex()
    private val activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val mutableGenerationActivity = MutableStateFlow<Map<String, HarnessGenerationActivity>>(emptyMap())
    /** Active request metadata for native waiting/prefill presentation. */
    val generationActivity: StateFlow<Map<String, HarnessGenerationActivity>> =
        mutableGenerationActivity.asStateFlow()
    private val ownership = context.getSharedPreferences("harness_inference_owners", Context.MODE_PRIVATE)
    private val ownershipLock = Any()
    @Volatile private var owner = "harness-${UUID.randomUUID()}"

    fun beginRuntime(generation: String) { owner = "harness-$generation" }

    suspend fun releaseOwnedResources(retainActiveRuntime: Boolean = false) {
        val retainedOwner = owner.takeIf { retainActiveRuntime }
        val previous = synchronized(ownershipLock) {
            ownership.getStringSet("owners", emptySet()).orEmpty().filterNot { it == retainedOwner }
        }
        previous.forEach { token ->
            LiteRtLmWorkerClient(context).releaseOwnedEngines(token)
            synchronized(ownershipLock) {
                val remaining = ownership.getStringSet("owners", emptySet()).orEmpty().toMutableSet().apply { remove(token) }
                persistOwners(remaining)
            }
        }
    }

    suspend fun models(): JSONObject {
        val settings = this.settings
        val data = JSONArray()
        database.liteRtModelDao().getAllOnce().filter { File(it.path).exists() }.forEach {
            data.put(JSONObject().put("id", "litert:${it.id}").put("object", "model")
                .put("owned_by", "adt-litert").put("name", it.displayName).put("context_length", it.maxContextTokens ?: 4096)
                .put("input_modalities", JSONArray().put("text").apply { if (it.supportsVision) put("image"); if (it.supportsAudio) put("audio") }))
        }
        AgentRuntimeProfileRuntime.repositoryState.value?.managedServerCatalog?.observeServers()?.first()
            ?.filter { it.state == ManagedLlamaServerState.RUNNING }?.forEach {
                data.put(JSONObject().put("id", "llama:${it.id}").put("object", "model")
                    .put("owned_by", "adt-llama-server").put("name", it.displayName))
            }
        val saved = database.ollamaServerDao().getAllServers().first().take(32)
        val endpoints = (listOf("default" to settings.ollamaUrl.value) + saved.map { it.id.toString() to it.url }).distinctBy { it.second.trimEnd('/') }
        val discovered = coroutineScope { endpoints.map { (key, url) -> async(Dispatchers.IO) {
            runCatching {
                client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url.trimEnd('/') + "/api/tags").build()).execute().use { response ->
                        check(response.isSuccessful)
                        val tags = JSONObject(requireNotNull(response.body).byteStream().use { String(it.readBounded(1024 * 1024), Charsets.UTF_8) })
                            .optJSONArray("models") ?: JSONArray()
                        tags.objects().take(256).map { item ->
                            val name = item.getString("name")
                            JSONObject().put("id", "ollama:$key:$name").put("object", "model").put("owned_by", "adt-ollama").put("name", name)
                        }
                    }
            }.getOrDefault(emptyList())
        } }.awaitAll().flatten() }
        discovered.forEach { data.put(it) }
        val llamaSwapUrl = settings.agentLlamaSwapUrl.value.trim()
        if (llamaSwapUrl.isNotEmpty()) {
            runCatching {
                discoverLlamaSwapModels(llamaSwapUrl)
            }.getOrDefault(emptyList()).forEach { model ->
                data.put(
                        JSONObject().put("id", "llama-swap:${model.wireId}")
                            .put("object", "model").put("owned_by", "adt-llama-swap")
                            .put("name", model.displayName)
                        .apply {
                            model.contextLength?.let { put("context_length", it) }
                            if (model.running) put("running", true)
                        }
                )
            }
        }
        return JSONObject().put("object", "list").put("data", data)
    }

    suspend fun stream(request: JSONObject, emit: suspend (JSONObject) -> Unit) {
        val job = requireNotNull(currentCoroutineContext()[Job])
        activeRequests.add(job)
        val activity = beginGenerationActivity(request)
        val activityEmit: suspend (JSONObject) -> Unit = { chunk ->
            updateGenerationActivityFromChunk(activity.requestId, chunk)
            emit(chunk)
        }
        try {
        val id = request.getString("model")
        when {
            id.startsWith("litert:") -> inference.withLock { streamLiteRt(request, id.removePrefix("litert:").toLong(), activityEmit) }
            id.startsWith("llama:") -> {
                val server = AgentRuntimeProfileRuntime.repositoryState.value?.managedServerCatalog?.getServer(id.removePrefix("llama:").toLong())
                    ?: error("MANAGED_MODEL_NOT_FOUND")
                check(server.state == ManagedLlamaServerState.RUNNING) { "MANAGED_MODEL_NOT_RUNNING" }
                streamLlamaServer(
                    request = request,
                    baseUrl = HttpEndpointUrlSupport.fromHostPort(server.host, server.port)
                        ?: error("MANAGED_MODEL_URL_INVALID"),
                    model = server.modelName ?: "default",
                    emit = activityEmit,
                    endpointGeneration = "managed:${server.id}",
                    activityKey = activity.requestId,
                )
            }
            id.startsWith("llama-swap:") -> {
                streamLlamaServer(
                    request = request,
                    baseUrl = settings.agentLlamaSwapUrl.value,
                    model = id.removePrefix("llama-swap:"),
                    emit = activityEmit,
                    endpointGeneration = "swap:${settings.agentLlamaSwapUrl.value}",
                    activityKey = activity.requestId,
                )
            }
            id.startsWith("ollama:") -> {
                val selected = id.removePrefix("ollama:")
                val serverId = selected.substringBefore(':')
                val base = if (serverId == "default") settings.ollamaUrl.value else
                    requireNotNull(database.ollamaServerDao().getServerById(serverId.toLong())) { "MANAGED_MODEL_NOT_FOUND" }.url
                proxy(request, base.trimEnd('/') + "/v1/chat/completions", selected.substringAfter(':'), activityEmit)
            }
            else -> error("MODEL_NOT_FOUND")
        }
        } finally {
            mutableGenerationActivity.update { current -> current - activity.requestId }
            activeRequests.remove(job)
        }
    }

    private fun beginGenerationActivity(request: JSONObject): HarnessGenerationActivity {
        val requestId = request.optString("requestId").trim().takeIf { SAFE_REQUEST_ID.matches(it) }
            ?: "native-${UUID.randomUUID()}"
        val sessionId = request.optString("sessionId").trim().takeIf { it.isNotEmpty() }
        val attemptId = request.optString("attemptId").trim().takeIf { it.isNotEmpty() }
            ?: "attempt-${UUID.randomUUID()}"
        val startedAtMs = System.currentTimeMillis()
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        // The bridge normally supplies these fields. Filling them for direct
        // native callers keeps activity and stream metadata correlated too.
        request.put("requestId", requestId)
        request.put("attemptId", attemptId)
        val activity = HarnessGenerationActivity(
            requestId = requestId,
            sessionId = sessionId,
            attemptId = attemptId,
            model = request.optString("model"),
            phase = "waiting",
            startedAtMs = startedAtMs,
            startedAtElapsedMs = startedAtElapsedMs,
            updatedAtMs = startedAtMs,
        )
        mutableGenerationActivity.update { current -> current + (requestId to activity) }
        return activity
    }

    private fun updateGenerationActivity(
        requestId: String,
        progress: LlamaPromptProcessingProgress,
    ) {
        mutableGenerationActivity.update { current ->
            val previous = current[requestId] ?: return@update current
            current + (requestId to previous.copy(
                phase = if (previous.phase == "generating") "generating" else "prefill",
                known = progress.total > 0,
                total = progress.total.takeIf { it > 0 },
                cached = progress.cached.takeIf { it >= 0 },
                processed = progress.processed.takeIf { it >= 0 },
                timeMs = progress.timeMs.takeIf { it >= 0L },
                updatedAtMs = System.currentTimeMillis(),
            ))
        }
    }

    /**
     * Promote waiting/prefill to generating only when a real model delta is
     * emitted. Role-only, finish, usage, and prompt-progress frames must not
     * make the UI claim that output has started.
     */
    private fun updateGenerationActivityFromChunk(requestId: String, chunk: JSONObject) {
        val choices = chunk.optJSONArray("choices") ?: return
        val hasGenerationDelta = (0 until choices.length()).any { index ->
            val delta = choices.optJSONObject(index)?.optJSONObject("delta") ?: return@any false
            val hasText = listOf("content", "reasoning_content", "reasoning", "thinking").any { key ->
                delta.optString(key).isNotEmpty()
            }
            hasText || (delta.optJSONArray("tool_calls")?.length() ?: 0) > 0
        }
        if (!hasGenerationDelta) return
        mutableGenerationActivity.update { current ->
            val previous = current[requestId] ?: return@update current
            if (previous.phase == "generating") current
            else current + (requestId to previous.copy(
                phase = "generating",
                updatedAtMs = System.currentTimeMillis(),
            ))
        }
    }

    fun cancelOwnedRequests() {
        client.dispatcher.cancelAll()
        activeRequests.toList().forEach { it.cancel() }
    }

    /**
     * Ownership receipts must be durable before an engine is used or released.
     * KTX `edit` discards the Boolean returned by `commit`, so retain the
     * checked synchronous write with this narrow suppression.
     */
    @SuppressLint("UseKtx")
    private fun persistOwners(owners: Set<String>) {
        check(ownership.edit().putStringSet("owners", owners).commit()) { "INFERENCE_RECEIPT_WRITE_FAILED" }
    }

    private suspend fun streamLiteRt(request: JSONObject, modelId: Long, emit: suspend (JSONObject) -> Unit) {
        val settings = this.settings
        val model = requireNotNull(database.liteRtModelDao().getById(modelId)) { "MODEL_NOT_FOUND" }
        val messages = request.getJSONArray("messages").objects()
        val media = HarnessLiteRtMedia(context, model, client)
        try {
        media.prepare(messages)
        val system = messages.filter { it.optString("role") in setOf("system", "developer") }.joinToString("\n\n") { content(it) }
        val conversation = messages.filter { it.optString("role") !in setOf("system", "developer") }
        val lastUser = conversation.lastOrNull()?.takeIf { it.optString("role") == "user" }
        val initial = if (lastUser == null) conversation else conversation.dropLast(1)
        val definitions = request.optJSONArray("tools")?.objects().orEmpty().mapNotNull { item ->
            val function = item.optJSONObject("function") ?: return@mapNotNull null
            val schema = function.optJSONObject("parameters") ?: JSONObject()
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            LiteRtToolDefinition(function.getString("name"), function.optString("description"),
                properties.keys().asSequence().mapNotNull { key ->
                    properties.optJSONObject(key)?.let { key to it.optString("description", key) }
                }.toMap(),
                schema.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
                parameterSchemaJson = schema.toString())
        }
        val toolNames = messages.flatMap { it.optJSONArray("tool_calls")?.objects().orEmpty() }
            .associate { it.optString("id") to it.getJSONObject("function").getString("name") }
        val history = initial.map { message ->
            LiteRtConversationMessage(
                role = message.getString("role"), content = content(message),
                imagePath = media.image(message), audioPath = media.audio(message),
                toolName = message.optString("name").takeIf { it.isNotEmpty() } ?: toolNames[message.optString("tool_call_id")],
                toolCalls = message.optJSONArray("tool_calls")?.objects().orEmpty().map { call ->
                    val function = call.getJSONObject("function")
                    val args = JSONObject(function.optString("arguments", "{}"))
                    LiteRtToolCallSpec(function.getString("name"), args.keys().asSequence().associateWith { args.get(it) })
                }
            )
        }
        val contextTokens = resolveAgentLiteRtContextTokens(settings.agentLiteRtContextTokens.value, model)
        val outputTokens = resolveAgentLiteRtMaxOutputTokens(
            request.optInt("max_completion_tokens", request.optInt("max_tokens", settings.agentLiteRtMaxOutputTokens.value)),
            contextTokens, model)
        val params = mapOf(
            "temperature" to request.optDouble("temperature", 0.7),
            "enable_thinking" to settings.agentLiteRtThinkingEnabled.value,
            LITERT_PARAM_MTP_ENABLED to settings.agentLiteRtMtpEnabled.value,
            LITERT_PARAM_ENGINE_OWNER to owner,
            LITERT_PARAM_MAX_OUTPUT_TOKENS to outputTokens
        )
        val workerRequest = LiteRtLmChatRequest(
            model = model,
            chat = LlamaChatEntity(id = HARNESS_CHAT_OWNER, title = "DeepSeek Harness", systemPrompt = system,
                contextSize = contextTokens),
            history = emptyList(), backendMode = settings.agentLiteRtBackend.value, params = params,
            conversationOverride = LiteRtConversationOverride(system, history, lastUser?.let(::content) ?: "Continue using the tool results.",
                userImagePath = lastUser?.let(media::image), userAudioPath = lastUser?.let(media::audio), tools = definitions)
        )
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        // CPU/GPU caches live in the same canonical worker. Persist only their owner token
        // so app-process recreation can clean them without saving prompts or model inputs.
        synchronized(ownershipLock) {
            val owners = ownership.getStringSet("owners", emptySet()).orEmpty().toMutableSet()
            owners.add(params.getValue(LITERT_PARAM_ENGINE_OWNER).toString())
            persistOwners(owners)
        }
        val modelName = request.getString("model")
        suspend fun delta(value: JSONObject, finish: String? = null) = emit(JSONObject()
            .put("id", completionId).put("object", "chat.completion.chunk").put("created", System.currentTimeMillis() / 1000)
            .put("model", modelName).put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", value).put("finish_reason", finish ?: JSONObject.NULL))))
        delta(JSONObject().put("role", "assistant"))
        val worker = LiteRtLmWorkerClient(context)
        val onChunk: suspend (String) -> Unit = { delta(JSONObject().put("content", it)) }
        val onThinking: suspend (String) -> Unit = { delta(JSONObject().put("reasoning_content", it)) }
        val stats = if (workerRequest.backendMode == LITERT_BACKEND_GPU) {
            worker.streamGpuChat(workerRequest, {}, onChunk, onThinking)
        } else worker.streamCpuChat(workerRequest, {}, onChunk, onThinking)
        stats.toolCalls.forEachIndexed { index, call ->
            delta(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index)
                .put("id", call.id ?: "call-${UUID.randomUUID()}").put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", JSONObject(call.arguments).toString())))))
        }
        delta(JSONObject(), if (stats.toolCalls.isEmpty()) "stop" else "tool_calls")
        emit(JSONObject().put("id", completionId).put("object", "chat.completion.chunk").put("model", modelName)
            .put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", stats.promptTokens)
                .put("completion_tokens", stats.completionTokens).put("total_tokens", stats.promptTokens + stats.completionTokens)))
        } finally { media.close() }
    }

    /**
     * Routes llama.cpp/llama-swap through the existing slot-aware service.
     * That service owns the bounded control probes, prompt-progress parser,
     * single confirmed-ended recovery, and per-request slot cancellation.
     */
    private suspend fun streamLlamaServer(
        request: JSONObject,
        baseUrl: String,
        model: String,
        emit: suspend (JSONObject) -> Unit,
        endpointGeneration: String,
        activityKey: String,
    ) = coroutineScope {
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        val modelName = request.optString("model").ifBlank { model }
        // Keep the correlation tuple on every metadata-only progress frame so
        // native presentation can reject stale frames without retaining input.
        val sessionId = request.optString("sessionId").trim().takeIf { it.isNotEmpty() }
        val requestId = request.optString("requestId").trim().takeIf { it.isNotEmpty() } ?: completionId
        val attemptId = request.optString("attemptId").trim().takeIf { it.isNotEmpty() } ?: completionId
        // Bound the response queue while preserving every frame. The service's
        // suspending callbacks wait for the native bridge to consume frames,
        // so fast servers cannot grow an unbounded response or drop deltas.
        val channel = Channel<JSONObject>(capacity = 64)
        val llamaServer = LlamaServerChatService()
        channel.send(openAiChunk(completionId, modelName, JSONObject().put("role", "assistant")))
        val worker = async(Dispatchers.IO) {
            try {
                val owner = LlamaSlotOwnerKey(
                    endpointGeneration = "$endpointGeneration|$baseUrl",
                    modelConfiguration = model,
                    conversationId = sessionId ?: "unsaved",
                    agentSessionId = attemptId
                )
                val requestedToolChoice = parseHarnessToolChoice(request)
                val tools = llamaTools(request).takeUnless { requestedToolChoice == "none" }.orEmpty()
                val result = llamaServer.chatWithToolsStreaming(
                    baseUrl = baseUrl,
                    messages = llamaMessages(request),
                    tools = tools,
                    modelLabel = model,
                    thinkingEnabled = request.optBoolean("enable_thinking", true),
                    maxTokens = request.optInt("max_completion_tokens", request.optInt("max_tokens", 0))
                        .takeIf { it > 0 },
                    samplingParams = LlamaServerSamplingParams(
                        temperature = request.optDouble("temperature", Double.NaN).toFloatOrNull(),
                        topP = request.optDouble("top_p", Double.NaN).toFloatOrNull(),
                        topK = request.optInt("top_k", 0).takeIf { it > 0 },
                        minP = request.optDouble("min_p", Double.NaN).toFloatOrNull(),
                        seed = request.optInt("seed", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                    ),
                    requestOptions = LlamaServerRequestOptions(
                        cachePrompt = request.optBoolean("cache_prompt", true),
                        returnPromptProgress = true,
                        requireToolCall = requestedToolChoice == "required",
                        toolChoice = requestedToolChoice?.takeUnless { it == "none" },
                        thinkingBudgetTokens = request.optInt("thinking_budget", 0).takeIf { it > 0 },
                        allowRecoveryAfterConfirmedEnd = false,
                        streamReadTimeoutMs = 0
                    ),
                    slotOwner = owner,
                    slotAffinityMode = LlamaSlotAffinityMode.fromValue(settings.agentLlamaSlotAffinityMode.value),
                    onPromptProgressSuspending = { progress ->
                        updateGenerationActivity(activityKey, progress)
                        channel.send(
                            openAiActivityChunk(
                                completionId,
                                modelName,
                                progress,
                                requestId = requestId,
                                sessionId = sessionId,
                                attemptId = attemptId,
                            )
                        )
                    },
                    onChunkSuspending = { content, thinking ->
                        if (!content.isNullOrEmpty()) {
                            channel.send(openAiChunk(completionId, modelName, JSONObject().put("content", content)))
                        }
                        if (!thinking.isNullOrEmpty()) {
                            channel.send(openAiChunk(completionId, modelName, JSONObject().put("reasoning_content", thinking)))
                        }
                    }
                )
                val response = result.getOrThrow()
                val toolCalls = response.toolCalls ?: response.message.toolCalls.orEmpty()
                toolCalls.forEachIndexed { index, call ->
                    channel.send(
                        openAiChunk(
                            completionId,
                            modelName,
                            JSONObject().put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject().put("index", index)
                                        .put("id", call.id ?: "call-${UUID.randomUUID()}")
                                        .put("type", "function")
                                        .put("function", JSONObject().put("name", call.name)
                                            .put("arguments", call.rawArgumentsJson ?: JSONObject(call.arguments).toString()))
                                )
                            )
                        )
                    )
                }
                val finish = response.finishReason ?: if (toolCalls.isEmpty()) "stop" else "tool_calls"
                channel.send(openAiChunk(completionId, modelName, JSONObject(), finish))
                response.usage?.let { usage ->
                    channel.send(
                        JSONObject().put("id", completionId).put("object", "chat.completion.chunk")
                            .put("created", System.currentTimeMillis() / 1000).put("model", modelName)
                            .put("choices", JSONArray()).put("usage", JSONObject().apply {
                                usage.promptTokens?.let { put("prompt_tokens", it) }
                                usage.completionTokens?.let { put("completion_tokens", it) }
                                usage.totalTokens?.let { put("total_tokens", it) }
                            })
                    )
                }
            } finally {
                channel.close()
            }
        }
        try {
            for (chunk in channel) emit(chunk)
            worker.await()
        } finally {
            if (!worker.isCompleted) {
                worker.cancel()
                llamaServer.stopGeneration()
            }
        }
    }

    private fun llamaMessages(request: JSONObject): List<OllamaService.ChatMessage> =
        request.optJSONArray("messages")?.objects().orEmpty().map { message ->
            val calls = message.optJSONArray("tool_calls")?.objects().orEmpty().mapNotNull { call ->
                val function = call.optJSONObject("function") ?: return@mapNotNull null
                val name = function.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val raw = function.optString("arguments", "{}")
                val arguments = runCatching { JSONObject(raw).keys().asSequence().associateWith { key ->
                    JSONObject(raw).opt(key)?.toString().orEmpty()
                } }.getOrDefault(emptyMap())
                OllamaService.ToolCall(name, arguments, call.optString("id").takeIf { it.isNotEmpty() }, raw)
            }
            OllamaService.ChatMessage(
                role = message.optString("role", "user"),
                content = content(message),
                toolCalls = calls.takeIf { it.isNotEmpty() },
                toolCallId = message.optString("tool_call_id").takeIf { it.isNotEmpty() },
                toolName = message.optString("name").takeIf { it.isNotEmpty() }
            )
        }

    private fun llamaTools(request: JSONObject): List<AgentTool> =
        request.optJSONArray("tools")?.objects().orEmpty().mapNotNull { item ->
            val function = item.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val schema = function.optJSONObject("parameters") ?: JSONObject()
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            AgentTool(
                name = name,
                description = function.optString("description"),
                parameters = properties.keys().asSequence().associateWith { key ->
                    properties.optJSONObject(key)?.optString("description", key) ?: key
                },
                requiredParams = schema.optJSONArray("required")?.strings().orEmpty(),
                schemaJson = schema.toString()
            )
        }

    /** Preserve the caller's OpenAI tool policy instead of forcing a tool turn. */
    private fun parseHarnessToolChoice(request: JSONObject): String? {
        val raw = request.opt("tool_choice")
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw !is String) error("UNSUPPORTED_TOOL_CHOICE")
        return when (val choice = raw.trim().lowercase(Locale.ROOT)) {
            "auto", "required", "none" -> choice
            else -> error("UNSUPPORTED_TOOL_CHOICE")
        }
    }

    private fun openAiChunk(
        completionId: String,
        model: String,
        delta: JSONObject,
        finish: String? = null
    ): JSONObject = JSONObject().put("id", completionId).put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1000).put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", finish ?: JSONObject.NULL)))

    private fun openAiActivityChunk(
        completionId: String,
        model: String,
        progress: LlamaPromptProcessingProgress,
        requestId: String? = null,
        sessionId: String? = null,
        attemptId: String? = null,
    ): JSONObject = JSONObject().put("id", completionId).put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1000).put("model", model)
        .put("choices", JSONArray()).put(
            "adt",
            harnessPromptProgressActivity(
                phase = "prefill",
                total = progress.total,
                cached = progress.cached,
                processed = progress.processed,
                timeMs = progress.timeMs,
                requestId = requestId,
                sessionId = sessionId,
                attemptId = attemptId,
            )
        )

    private fun Double.toFloatOrNull(): Float? = takeIf { isFinite() }?.toFloat()

    private suspend fun discoverLlamaSwapModels(
        baseUrl: String
    ): List<HarnessDiscoveredModel> = withContext(Dispatchers.IO) {
        val endpoint = normalizeHarnessProviderEndpoint(baseUrl) ?: return@withContext emptyList()
        val discoveryClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val models = fetchDiscoveryJson(
            discoveryClient,
            HttpEndpointUrlSupport.appendPath(endpoint.v1BaseUrl, "/models") ?: return@withContext emptyList()
        ) ?: return@withContext emptyList()
        val running = HttpEndpointUrlSupport.appendPath(endpoint.rootUrl, "/running")
            ?.let { fetchDiscoveryJson(discoveryClient, it) }
        parseHarnessLlamaSwapModels(models, running)
    }

    private fun fetchDiscoveryJson(client: OkHttpClient, url: String): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            JSONObject(body.byteStream().use { it.readBounded(1024 * 1024) }.toString(Charsets.UTF_8))
        }
    }.getOrNull()

    private suspend fun proxy(request: JSONObject, url: String, model: String, emit: suspend (JSONObject) -> Unit) = coroutineScope {
        val body = JSONObject(request.toString()).put("model", model).put("stream", true)
        val call = client.newCall(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "PROVIDER_HTTP_${response.code}" }
                    val source = requireNotNull(response.body).source()
                    while (true) {
                        if (source.exhausted()) break
                        val line = source.readUtf8LineStrict(1024L * 1024L)
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.isNotBlank()) emit(JSONObject(data))
                    }
                }
            }
        } finally { cancellation.cancel(); call.cancel() }
    }

    private fun content(message: JSONObject): String = when (val value = message.opt("content")) {
        is String -> value
        is JSONArray -> value.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }
    private companion object {
        const val HARNESS_CHAT_OWNER = -73501L
        val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    }
}
