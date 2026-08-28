package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OllamaService - API client for Ollama with tool calling support
 * 
 * Supports:
 * - Custom server URL (default: localhost:11434)
 * - Model listing
 * - Chat with tool calling
 * - Streaming responses
 */
class OllamaService(private val context: Context) {
    
    companion object {
        private const val TAG = "OllamaService"
        const val DEFAULT_URL = AIConstants.Urls.OLLAMA_DEFAULT
        private const val CHAT_READ_TIMEOUT_MS = 0
        private const val CHAT_KEEP_ALIVE = "30m"

        // STATIC connection state - persists across screen navigations
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
        
        private val _availableModels = MutableStateFlow<List<OllamaModel>>(emptyList())
        val availableModels: StateFlow<List<OllamaModel>> = _availableModels.asStateFlow()
        
        // Track whether we've ever checked the connection
        private val _hasCheckedConnection = MutableStateFlow(false)
        val hasCheckedConnection: StateFlow<Boolean> = _hasCheckedConnection.asStateFlow()

        private val _isRecovering = MutableStateFlow(false)
        val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

        private val connectionProbeMutex = Mutex()
        private val activeChatRequests = AtomicInteger(0)
        private val activeConnectionsByJob = mutableMapOf<Job, MutableSet<HttpURLConnection>>()
        private val activeConnectionsLock = Any()

        private fun registerConnection(job: Job?, connection: HttpURLConnection) {
            if (job == null) return
            synchronized(activeConnectionsLock) {
                val connections = activeConnectionsByJob.getOrPut(job) {
                    job.invokeOnCompletion { stop(job) }
                    mutableSetOf()
                }
                connections += connection
            }
        }

        private fun unregisterConnection(job: Job?, connection: HttpURLConnection) {
            if (job == null) return
            synchronized(activeConnectionsLock) {
                val connections = activeConnectionsByJob[job] ?: return
                connections.remove(connection)
                if (connections.isEmpty()) {
                    activeConnectionsByJob.remove(job)
                }
            }
        }

        fun stop(job: Job) {
            val connections = synchronized(activeConnectionsLock) {
                activeConnectionsByJob.remove(job)?.toList().orEmpty()
            }
            connections.forEach { connection ->
                runCatching { connection.disconnect() }
            }
        }

        fun stop() {
            val connections = synchronized(activeConnectionsLock) {
                activeConnectionsByJob.values.flatMap { it.toList() }.also { activeConnectionsByJob.clear() }
            }
            connections.forEach { connection ->
                runCatching { connection.disconnect() }
            }
        }

        fun resetStop() = Unit

        internal fun buildChatRequestJson(
            model: String,
            messages: List<ChatMessage>,
            tools: List<AgentTool>,
            thinkingEnabled: Boolean,
            useMmap: Boolean,
            numThreads: Int,
            numCtx: Int,
            maxOutputTokens: Int? = null
        ): JSONObject = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("think", thinkingEnabled)
            put("keep_alive", CHAT_KEEP_ALIVE)

            put("messages", JSONArray().apply {
                for (msg in messages) {
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        if (!msg.thinking.isNullOrEmpty()) {
                            put("thinking", msg.thinking)
                        }
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.images?.takeIf { it.isNotEmpty() }?.let { imgs ->
                            put("images", JSONArray(imgs.map(::normalizeImagePayloadForRequest)))
                        }
                        msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                            put("tool_calls", JSONArray().apply {
                                for (tc in calls) {
                                    val rawArguments = canonicalToolArguments(tc)
                                    put(JSONObject().apply {
                                        put("id", tc.id ?: stableToolCallId(tc.name, rawArguments))
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", tc.name)
                                            put("arguments", JSONObject(rawArguments))
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
            })

            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    for (tool in tools.sortedBy { it.name }) {
                        put(JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    tool.schemaJson
                                        ?.let { schema -> runCatching { JSONObject(schema) }.getOrNull() }
                                        ?: JSONObject(legacyToolSchemaJson(tool))
                                )
                            })
                        })
                    }
                })
            }

            put("options", JSONObject().apply {
                put("use_mmap", useMmap)
                put("num_thread", numThreads)
                put("num_ctx", numCtx)
                maxOutputTokens?.takeIf { it > 0 }?.let { put("num_predict", it) }
            })
        }

        private fun normalizeImagePayloadForRequest(image: String): String {
            val marker = "base64,"
            val markerIndex = image.indexOf(marker, ignoreCase = true)
            return if (markerIndex >= 0) {
                image.substring(markerIndex + marker.length)
            } else {
                image
            }
        }
    }
    
    // Instance-specific state for URL configuration
    private val _baseUrl = MutableStateFlow(DEFAULT_URL)
    val baseUrl: StateFlow<String> = _baseUrl
    
    // Ollama options
    private val _useMmap = MutableStateFlow(false)
    val useMmap: StateFlow<Boolean> = _useMmap
    
    private val _numThreads = MutableStateFlow(4)
    val numThreads: StateFlow<Int> = _numThreads
    
    private val _numCtx = MutableStateFlow(4096)
    val numCtx: StateFlow<Int> = _numCtx
    
    fun setBaseUrl(url: String) {
        _baseUrl.value = url.trimEnd('/')
    }
    
    fun setUseMmap(enabled: Boolean) {
        _useMmap.value = enabled
    }
    
    fun setNumThreads(count: Int) {
        _numThreads.value = count
    }
    
    fun setNumCtx(count: Int) {
        _numCtx.value = count
    }
    
    // Load saved settings from SharedPreferences
    fun initFromSettings(prefix: String = "") {
        val prefs = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        val keyUrl = if (prefix.isEmpty()) "ollama_url" else "${prefix}ollama_url"
        val keyMmap = if (prefix.isEmpty()) "ollama_mmap" else "${prefix}ollama_mmap"
        val keyThreads = if (prefix.isEmpty()) "ollama_threads" else "${prefix}ollama_threads"
        val keyCtx = if (prefix.isEmpty()) "ollama_num_ctx" else "${prefix}ollama_num_ctx"

        _baseUrl.value = (prefs.getString(keyUrl, DEFAULT_URL) ?: DEFAULT_URL).trimEnd('/')
        _useMmap.value = prefs.getBoolean(keyMmap, false)
        _numThreads.value = prefs.getInt(keyThreads, 4)
        _numCtx.value = prefs.getInt(keyCtx, 4096)
    }

    data class OllamaModel(
        val name: String,
        val size: Long,
        val modifiedAt: String,
        val digest: String
    )
    
    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>,
        val id: String? = null,
        /**
         * Exact function-arguments JSON received from the backend.
         *
         * llama.cpp prompt-cache reuse depends on preserving the byte/token prefix
         * across tool continuations. Keep the original JSON when available instead
         * of rebuilding it from a Map with potentially different key ordering.
         */
        val rawArgumentsJson: String? = null
    )
    
    data class ChatMessage(
        val role: String,  // "user", "assistant", "tool"
        val content: String,
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        val images: List<String>? = null,  // Base64 encoded images for vision
        val imagePath: String? = null,
        val thinking: String? = null,      // Internal thought process
        val audioPath: String? = null,
        val audioDurationMs: Long? = null,
        val transcriptionStatus: String? = null,
        val transcribedText: String? = null,
        val transcriptionError: String? = null,
        val isSuspicious: Boolean = false, // Security flag for commands
        val toolName: String? = null,      // Name of tool for approval messages
        val needsApproval: Boolean = false, // Flag for tool call approval
        var isPlanApproved: Boolean = false, // Flag for propose_plan approval
        var timestamp: Long? = null        // Creation time for persistence
    )
    
    data class ChatResponse(
        val message: ChatMessage,
        val done: Boolean,
        val toolCalls: List<ToolCall>? = null,
        val usage: ChatUsage? = null
    )

    data class ChatUsage(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val totalDurationNs: Long? = null,
        val evalDurationNs: Long? = null,
        val backend: String? = null
    )

    private fun normalizeOllamaImagePayload(image: String): String {
        val marker = "base64,"
        val markerIndex = image.indexOf(marker, ignoreCase = true)
        return if (markerIndex >= 0) {
            image.substring(markerIndex + marker.length)
        } else {
            image
        }
    }

    private suspend fun <T> withTrackedConnection(
        connection: HttpURLConnection,
        block: suspend (HttpURLConnection) -> T
    ): T {
        val job = currentCoroutineContext()[Job]
        registerConnection(job, connection)
        return try {
            block(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                val cancellation = CancellationException("Ollama request cancelled")
                cancellation.initCause(e)
                throw cancellation
            }
            throw e
        } finally {
            unregisterConnection(job, connection)
            runCatching { connection.disconnect() }
        }
    }
    
    suspend fun checkConnection(): Boolean = withContext(Dispatchers.IO) {
        if (activeChatRequests.get() > 0) {
            return@withContext _isConnected.value || _isRecovering.value
        }
        if (!connectionProbeMutex.tryLock()) {
            return@withContext _isConnected.value || _isRecovering.value
        }

        val wasConnected = _isConnected.value
        try {
            RemoteBackendResilience.runWithSingleRetry(
                onRetry = { firstError ->
                    _isRecovering.value = true
                    DebugLog.log("[$TAG] Connection state: ${if (wasConnected) "connected" else "offline"} -> recovering (${RemoteBackendResilience.summarize(firstError)})")
                }
            ) {
                val url = URL("${_baseUrl.value}/api/tags")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        throw IllegalStateException("HTTP $responseCode")
                    }
                    val response = conn.inputStream.bufferedReader().readText()
                    _availableModels.value = parseModels(response)
                } finally {
                    conn.disconnect()
                }
            }

            _isConnected.value = true
            if (_isRecovering.value) {
                DebugLog.log("[$TAG] Connection state: recovering -> reconnected")
            }
            _isRecovering.value = false
            _hasCheckedConnection.value = true
            true
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Connection check failed: ${e.message}")
            val recoverable = RemoteBackendResilience.isRecoverable(e)
            if (recoverable) {
                _isRecovering.value = true
                DebugLog.log("[$TAG] Connection state: ${if (wasConnected) "connected" else "offline"} -> recovering (${RemoteBackendResilience.summarize(e)})")
            } else {
                _isRecovering.value = false
                _isConnected.value = false
                DebugLog.log("[$TAG] Connection state: ${if (wasConnected) "connected" else "offline"} -> offline (${RemoteBackendResilience.summarize(e)})")
            }
            _hasCheckedConnection.value = true
            wasConnected && recoverable
        } finally {
            if (connectionProbeMutex.isLocked) {
                connectionProbeMutex.unlock()
            }
        }
    }
    
    /**
     * Get list of installed models
     */
    suspend fun listModels(baseUrlOverride: String? = null): List<OllamaModel> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrlOverride
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?: _baseUrl.value.trimEnd('/')
            val url = URL("$effectiveBaseUrl/api/tags")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val models = parseModels(response)
                conn.disconnect()
                if (baseUrlOverride.isNullOrBlank()) {
                    _availableModels.value = models
                }
                models
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to list models: ${e.message}")
            emptyList()
        }
    }
    
    private fun parseModels(response: String): List<OllamaModel> {
        try {
            val json = JSONObject(response)
            val modelsArray = json.optJSONArray("models") ?: return emptyList()
            
            val models = mutableListOf<OllamaModel>()
            for (i in 0 until modelsArray.length()) {
                val model = modelsArray.getJSONObject(i)
                models.add(OllamaModel(
                    name = model.getString("name"),
                    size = model.optLong("size", 0),
                    modifiedAt = model.optString("modified_at", ""),
                    digest = model.optString("digest", "")
                ))
            }
            return models
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to parse models: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * Chat with tool calling support
     * 
     * @param model Model name (e.g., "qwen2.5-coder:3b")
     * @param messages Chat history
     * @param tools Available tools for the model to use
     * @param baseUrlOverride Optional endpoint override for a named agent configuration
     * @param onChunk Callback for (contentChunk, thinkingChunk)
     * @return Final response with potential tool calls
     */
    suspend fun chatWithToolsStreaming(
        model: String,
        messages: List<ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        thinkingEnabled: Boolean = true,
        numCtxOverride: Int? = null,
        maxOutputTokens: Int? = null,
        baseUrlOverride: String? = null,
        onChunk: (String?, String?) -> Unit = { _, _ -> }
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        activeChatRequests.incrementAndGet()
        var sawStreamOutput = false
        val guardedOnChunk: (String?, String?) -> Unit = { chunk, thinkingChunk ->
            if (!chunk.isNullOrBlank() || !thinkingChunk.isNullOrBlank()) {
                sawStreamOutput = true
            }
            onChunk(chunk, thinkingChunk)
        }

        try {
            val effectiveBaseUrl = baseUrlOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: _baseUrl.value
            val chatResponse = RemoteAgentProtection.withProtection(effectiveBaseUrl, "Running remote Ollama agent…") {
                RemoteBackendResilience.runWithSingleRetry(
                    onRetry = { firstError ->
                        _isRecovering.value = true
                        DebugLog.log("[$TAG] Connection state: connected -> recovering (${RemoteBackendResilience.summarize(firstError)})")
                    },
                    shouldRetry = { !sawStreamOutput }
                ) {
                    performChatWithToolsStreaming(
                        model,
                        messages,
                        tools,
                        thinkingEnabled,
                        numCtxOverride,
                        maxOutputTokens,
                        guardedOnChunk,
                        effectiveBaseUrl
                    )
                }
            }
            _isConnected.value = true
            if (_isRecovering.value) {
                DebugLog.log("[$TAG] Connection state: recovering -> reconnected")
            }
            _isRecovering.value = false
            Result.success(chatResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val recoverable = RemoteBackendResilience.isRecoverable(e)
            if (recoverable) {
                _isRecovering.value = true
            } else {
                _isRecovering.value = false
            }
            _isConnected.value = false
            Result.failure(e)
        } finally {
            activeChatRequests.decrementAndGet()
        }
    }

    private suspend fun performChatWithToolsStreaming(
        model: String,
        messages: List<ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean,
        numCtxOverride: Int?,
        maxOutputTokens: Int?,
        onChunk: (String?, String?) -> Unit,
        baseUrl: String
    ): ChatResponse {
        val url = URL("$baseUrl/api/chat")
        val conn = url.openConnection() as HttpURLConnection
        return withTrackedConnection(conn) { trackedConn ->
            trackedConn.requestMethod = "POST"
            trackedConn.setRequestProperty("Content-Type", "application/json")
            trackedConn.setRequestProperty("Connection", "keep-alive")
            trackedConn.doOutput = true
            trackedConn.connectTimeout = 30000
            trackedConn.readTimeout = CHAT_READ_TIMEOUT_MS

            val requestJson = buildChatRequestJson(
                model = model,
                messages = messages,
                tools = tools,
                thinkingEnabled = thinkingEnabled,
                useMmap = _useMmap.value,
                numThreads = _numThreads.value,
                numCtx = AgentRuntimeSupport.resolveChatNumCtx(_numCtx.value, numCtxOverride),
                maxOutputTokens = maxOutputTokens
            )

            OutputStreamWriter(trackedConn.outputStream).use { it.write(requestJson.toString()); it.flush() }

            val fullContent = StringBuilder()
            val fullThinking = StringBuilder()
            var toolCalls: MutableList<ToolCall>? = null
            var usage: ChatUsage? = null
            var insideThinkTag = false

            if (trackedConn.responseCode != 200) {
                val errorBody = try {
                    trackedConn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (_: Exception) {
                    "HTTP ${trackedConn.responseCode}"
                }
                throw Exception("Ollama error: $errorBody")
            }

            BufferedReader(InputStreamReader(trackedConn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    val jsonLine = line ?: continue
                    val chunk = JSONObject(jsonLine)
                    parseUsageChunk(chunk)?.let { usage = it }
                    val message = chunk.optJSONObject("message") ?: continue
                    val content = message.optString("content", "").takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
                    val thinkingField = message.optString("thinking", message.optString("reasoning_content", ""))
                        .takeUnless { it.equals("null", ignoreCase = true) }
                        .orEmpty()

                    if (thinkingField.isNotEmpty()) {
                        fullThinking.append(thinkingField)
                        onChunk(null, thinkingField)
                    }

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
                                        onChunk(null, parts[0])
                                    }
                                    insideThinkTag = false
                                    remaining = if (parts.size > 1) parts[1] else ""
                                } else {
                                    fullThinking.append(remaining)
                                    onChunk(null, remaining)
                                    remaining = ""
                                }
                            }
                        }
                    }

                    val tcArray = message.optJSONArray("tool_calls")
                    if (tcArray != null) {
                        if (toolCalls == null) toolCalls = mutableListOf()
                        for (i in 0 until tcArray.length()) {
                            val tcObj = tcArray.getJSONObject(i)
                            val id = tcObj.optString("id")
                            val funcObj = tcObj.getJSONObject("function")
                            val name = funcObj.getString("name")
                            val argsObj = funcObj.getJSONObject("arguments")
                            val args = mutableMapOf<String, String>()
                            argsObj.keys().forEach { args[it] = argsObj.get(it).toString() }

                            DebugLog.log("[$TAG] Detected tool call: $name (id: $id)")
                            toolCalls?.add(
                                ToolCall(
                                    name = name,
                                    arguments = args,
                                    id = id,
                                    rawArgumentsJson = argsObj.toString()
                                )
                            )
                        }
                    }
                }
            }

            DebugLog.log("[$TAG] Stream finished. Detected ${toolCalls?.size ?: 0} tool calls.")

            ChatResponse(
                message = ChatMessage(
                    role = "assistant",
                    content = fullContent.toString(),
                    toolCalls = toolCalls,
                    thinking = fullThinking.toString().ifEmpty { null }
                ),
                done = true,
                toolCalls = toolCalls,
                usage = usage
            )
        }
    }

    /**
     * Legacy streaming chat method (no tools check)
     */
    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        thinkingEnabled: Boolean = true,
        numCtxOverride: Int? = null,
        onChunk: (String) -> Unit = {}
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        activeChatRequests.incrementAndGet()
        try {
            val url = URL("${_baseUrl.value}/api/chat")
            val conn = url.openConnection() as HttpURLConnection
            val response = withTrackedConnection(conn) { trackedConn ->
                trackedConn.requestMethod = "POST"
                trackedConn.setRequestProperty("Content-Type", "application/json")
                trackedConn.setRequestProperty("Connection", "keep-alive")
                trackedConn.doOutput = true
                trackedConn.connectTimeout = 30000
                trackedConn.readTimeout = CHAT_READ_TIMEOUT_MS

                // Build request
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("stream", true)
                    put("think", thinkingEnabled)
                    put("keep_alive", CHAT_KEEP_ALIVE)

                    // Messages
                    val messagesArray = JSONArray()
                    for (msg in messages) {
                        val msgObj = JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)

                            msg.toolCallId?.let { put("tool_call_id", it) }

                            msg.toolCalls?.let { calls ->
                                val callsArray = JSONArray()
                                for (call in calls) {
                                    callsArray.put(JSONObject().apply {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", call.name)
                                            put("arguments", JSONObject(call.arguments))
                                        })
                                    })
                                }
                                put("tool_calls", callsArray)
                            }

                            msg.images?.let { imgs ->
                                val imgsArray = JSONArray()
                                imgs.forEach { imgsArray.put(normalizeOllamaImagePayload(it)) }
                                put("images", imgsArray)
                            }
                        }
                        messagesArray.put(msgObj)
                    }
                    put("messages", messagesArray)

                    // Tools (if any)
                    if (tools.isNotEmpty()) {
                        val toolsArray = JSONArray()
                        for (tool in tools) {
                            val toolObj = JSONObject().apply {
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put(
                                        "parameters",
                                        tool.schemaJson
                                            ?.let { schema -> runCatching { JSONObject(schema) }.getOrNull() }
                                            ?: JSONObject(legacyToolSchemaJson(tool))
                                    )
                                })
                            }
                            toolsArray.put(toolObj)
                        }
                        put("tools", toolsArray)
                    }
                }

                // Add runtime options (mmap, threads, context)
                requestJson.put("options", JSONObject().apply {
                    put("use_mmap", _useMmap.value)
                    put("num_thread", _numThreads.value)
                    put("num_ctx", AgentRuntimeSupport.resolveChatNumCtx(_numCtx.value, numCtxOverride))
                })

                // Send request
                OutputStreamWriter(trackedConn.outputStream).use { writer ->
                    writer.write(requestJson.toString())
                    writer.flush()
                }

                // Read streaming response
                val fullContent = StringBuilder()
                var toolCalls: List<ToolCall>? = null
                var usage: ChatUsage? = null

                BufferedReader(InputStreamReader(trackedConn.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        currentCoroutineContext().ensureActive()
                        line?.let { jsonLine ->
                            try {
                                val chunk = JSONObject(jsonLine)
                                parseUsageChunk(chunk)?.let { usage = it }
                                val message = chunk.optJSONObject("message")
                                val content = message?.optString("content", "") ?: ""

                                if (content.isNotEmpty()) {
                                    fullContent.append(content)
                                    onChunk(content)
                                }

                                // Check for tool calls
                                val toolCallsArray = message?.optJSONArray("tool_calls")
                                if (toolCallsArray != null && toolCallsArray.length() > 0) {
                                    toolCalls = mutableListOf<ToolCall>().apply {
                                        for (i in 0 until toolCallsArray.length()) {
                                            val tc = toolCallsArray.getJSONObject(i)
                                            val id = tc.optString("id")
                                            val function = tc.getJSONObject("function")
                                            val args = function.optJSONObject("arguments")
                                            add(ToolCall(
                                                name = function.getString("name"),
                                                arguments = args?.let { argsObj ->
                                                    val map = mutableMapOf<String, String>()
                                                    argsObj.keys().forEach { key ->
                                                        map[key] = argsObj.optString(key, "")
                                                    }
                                                    map
                                                } ?: emptyMap(),
                                                id = id,
                                                rawArgumentsJson = args?.toString()
                                            ))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip malformed chunks
                            }
                        }
                    }
                }

                ChatResponse(
                    message = ChatMessage(
                        role = "assistant",
                        content = fullContent.toString(),
                        toolCalls = toolCalls
                    ),
                    done = true,
                    toolCalls = toolCalls,
                    usage = usage
                )
            }

            Result.success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Chat failed: ${e.message}")
            Result.failure(e)
        } finally {
            activeChatRequests.decrementAndGet()
        }
    }

    private fun parseUsageChunk(chunk: JSONObject): ChatUsage? {
        val promptTokens = chunk.optInt("prompt_eval_count").takeIf { it > 0 }
        val completionTokens = chunk.optInt("eval_count").takeIf { it > 0 }
        val totalTokens = when {
            promptTokens != null && completionTokens != null -> promptTokens + completionTokens
            else -> null
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) return null
        return ChatUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            totalDurationNs = chunk.optLong("total_duration").takeIf { it > 0L },
            evalDurationNs = chunk.optLong("eval_duration").takeIf { it > 0L },
            backend = "ollama"
        )
    }
    
    /**
     * Pull (download) a model
     */
    suspend fun pullModel(modelName: String, onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${_baseUrl.value}/api/pull")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = (AIConstants.Timeouts.OLLAMA_MODEL_PULL_MINUTES * 60 * 1000).toInt()
            
            val requestJson = JSONObject().apply {
                put("name", modelName)
                put("stream", true)
            }
            
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }
            
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { jsonLine ->
                        try {
                            val chunk = JSONObject(jsonLine)
                            val status = chunk.optString("status", "")
                            onProgress(status)
                        } catch (e: Exception) {
                            // Skip
                        }
                    }
                }
            }
            
            conn.disconnect()
            // Refresh model list
            listModels()
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Pull failed: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * Tool definition for agent
 */
data class AgentTool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>,  // paramName -> description
    val requiredParams: List<String> = emptyList(),
    /**
     * Optional canonical JSON Schema. This is used for nested tools such as
     * structured questions while retaining the legacy flat parameter API.
     */
    val schemaJson: String? = null
)
