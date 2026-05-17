package com.example.llamadroid.service

import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

data class RemoteSummaryBackendConfig(
    val backend: String,
    val baseUrl: String,
    val model: String?,
    val timeoutMinutes: Int
)

data class RemoteSummaryRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val thinkingEnabled: Boolean,
    val imageAttachments: List<RemoteSummaryImageAttachment> = emptyList()
)

data class RemoteSummaryImageAttachment(
    val base64: String,
    val mimeType: String = "image/jpeg"
) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

data class RemoteSummaryMetadata(
    val backend: String,
    val baseUrl: String,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String? = null,
    val serverModelLabel: String? = null,
    val serverContextTokens: Int? = null,
    val serverContextLabel: String? = null,
    val tokenCountMode: TokenCountMode = TokenCountMode.APPROXIMATE
)

data class RemoteSummaryTokenCount(
    val totalTokens: Int,
    val mode: TokenCountMode
)

data class RemoteSummaryResponse(
    val output: String,
    val rawOutput: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

enum class TokenCountMode {
    EXACT,
    APPROXIMATE
}

interface RemoteSummaryClient {
    suspend fun fetchMetadata(): Result<RemoteSummaryMetadata>
    suspend fun countRenderedPromptTokens(systemPrompt: String, userPrompt: String): RemoteSummaryTokenCount
    suspend fun summarize(request: RemoteSummaryRequest): RemoteSummaryResponse
    fun cancelActiveCall()
}

object RemoteSummaryClientFactory {
    fun fromSnapshot(snapshot: RemoteSummarySettingsSnapshot): RemoteSummaryClient {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        val config = RemoteSummaryBackendConfig(
            backend = backend,
            baseUrl = when (backend) {
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> snapshot.llamaServerUrl
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapUrl
                else -> snapshot.ollamaUrl
            }.trim().trimEnd('/'),
            model = when (backend) {
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> snapshot.llamaServerModelLabel
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapModel
                else -> snapshot.ollamaModel
            }?.trim()?.ifBlank { null },
            timeoutMinutes = snapshot.timeoutMinutes
        )
        return fromConfig(config)
    }

    fun fromConfig(config: RemoteSummaryBackendConfig): RemoteSummaryClient {
        return when (SettingsRepository.normalizeOllamaOrLlamaBackend(config.backend)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> LlamaServerRemoteSummaryClient(config)
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> LlamaSwapRemoteSummaryClient(config)
            else -> OllamaRemoteSummaryClient(config)
        }
    }
}

internal fun buildOllamaSummaryRequestJson(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): JSONObject {
    return buildJsonObject(buildOllamaSummaryRequestPayload(config, request))
}

internal fun buildLlamaServerSummaryRequestJson(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): JSONObject {
    return buildJsonObject(buildLlamaServerSummaryRequestPayload(config, request))
}

internal fun buildLlamaSwapSummaryRequestJson(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): JSONObject {
    return buildJsonObject(buildLlamaSwapSummaryRequestPayload(config, request))
}

internal fun buildOllamaSummaryRequestPayload(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): Map<String, Any?> {
    val userMessage = linkedMapOf<String, Any?>(
        "role" to "user",
        "content" to request.userPrompt
    )
    if (request.imageAttachments.isNotEmpty()) {
        userMessage["images"] = request.imageAttachments.map { it.base64 }
    }
    return mapOf(
        "model" to config.model,
        "stream" to false,
        "think" to request.thinkingEnabled,
        "messages" to listOf(
            mapOf("role" to "system", "content" to request.systemPrompt),
            userMessage
        ),
        "options" to mapOf(
            "num_ctx" to request.contextSize,
            "num_predict" to request.maxTokens,
            "temperature" to request.temperature.toDouble()
        )
    )
}

internal fun buildLlamaServerSummaryRequestPayload(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): Map<String, Any?> {
    val payload = linkedMapOf<String, Any?>(
        "model" to (config.model ?: "local-model"),
        "stream" to false,
        "temperature" to request.temperature.toDouble(),
        "max_tokens" to request.maxTokens,
        "messages" to listOf(
            mapOf("role" to "system", "content" to request.systemPrompt),
            mapOf("role" to "user", "content" to openAiCompatibleUserContent(request))
        ),
        "chat_template_kwargs" to mapOf("enable_thinking" to request.thinkingEnabled)
    )
    if (!request.thinkingEnabled) {
        payload["reasoning_effort"] = "none"
        payload["reasoning"] = mapOf("effort" to "none")
    }
    return payload
}

internal fun buildLlamaSwapSummaryRequestPayload(
    config: RemoteSummaryBackendConfig,
    request: RemoteSummaryRequest
): Map<String, Any?> {
    val model = requireNotNull(config.model?.takeIf { it.isNotBlank() }) {
        "No llama-swap model selected"
    }
    val payload = linkedMapOf<String, Any?>(
        "model" to model,
        "stream" to false,
        "temperature" to request.temperature.toDouble(),
        "max_tokens" to request.maxTokens,
        "messages" to listOf(
            mapOf("role" to "system", "content" to request.systemPrompt),
            mapOf("role" to "user", "content" to openAiCompatibleUserContent(request))
        ),
        "chat_template_kwargs" to mapOf("enable_thinking" to request.thinkingEnabled)
    )
    if (!request.thinkingEnabled) {
        payload["reasoning_effort"] = "none"
        payload["reasoning"] = mapOf("effort" to "none")
    }
    return payload
}

private fun openAiCompatibleUserContent(request: RemoteSummaryRequest): Any {
    if (request.imageAttachments.isEmpty()) return request.userPrompt
    return buildList {
        add(mapOf("type" to "text", "text" to request.userPrompt))
        request.imageAttachments.forEach { image ->
            add(
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to image.dataUrl)
                )
            )
            Unit
        }
    }
}

internal fun buildJsonObject(payload: Map<String, Any?>): JSONObject {
    return JSONObject().apply {
        payload.forEach { (key, value) ->
            put(key, payloadToJsonValue(value))
        }
    }
}

private fun payloadToJsonValue(value: Any?): Any? {
    return when (value) {
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nestedValue) ->
                if (key is String) {
                    put(key, payloadToJsonValue(nestedValue))
                }
            }
        }
        is Iterable<*> -> JSONArray().apply {
            value.forEach { put(payloadToJsonValue(it)) }
        }
        else -> value
    }
}

internal fun parseLlamaServerContextTokens(body: String): Int? {
    val nested = Regex(
        "\"default_generation_settings\"\\s*:\\s*\\{[^}]*?\"n_ctx\"\\s*:\\s*(\\d+)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (nested != null) return nested
    return Regex("\"n_ctx\"\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun parseOpenAiModelIds(body: String): List<String> {
    val json = JSONObject(body)
    val data = json.optJSONArray("data") ?: JSONArray()
    return buildList {
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optString("id").orEmpty().ifBlank { null }
            if (id != null) {
                add(id)
            }
        }
    }
}

private abstract class BaseRemoteSummaryClient(
    protected val config: RemoteSummaryBackendConfig
) : RemoteSummaryClient {
    @Volatile
    private var activeCall: Call? = null

    protected suspend fun executeJson(
        path: String,
        requestBuilder: Request.Builder,
        timeoutMinutes: Int = config.timeoutMinutes
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val call = buildHttpClient(timeoutMinutes)
            .newCall(requestBuilder.url(config.baseUrl + path).build())
        activeCall = call
        try {
            call.execute().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        } finally {
            if (activeCall === call) {
                activeCall = null
            }
        }
    }

    override fun cancelActiveCall() {
        activeCall?.cancel()
    }

    protected fun buildHttpClient(timeoutMinutes: Int): OkHttpClient {
        val timeoutMillis = if (timeoutMinutes > 0) TimeUnit.MINUTES.toMillis(timeoutMinutes.toLong()) else 0L
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(if (timeoutMillis > 0) timeoutMillis else 0L, TimeUnit.MILLISECONDS)
            .writeTimeout(if (timeoutMillis > 0) timeoutMillis else 0L, TimeUnit.MILLISECONDS)
            .callTimeout(if (timeoutMillis > 0) timeoutMillis else 0L, TimeUnit.MILLISECONDS)
            .build()
    }

    protected fun parseErrorMessage(responseBody: String, fallbackPrefix: String): String {
        return try {
            val json = JSONObject(responseBody)
            when (val errorNode = json.opt("error")) {
                is JSONObject -> errorNode.optString("message").ifBlank { errorNode.toString() }
                is String -> errorNode.ifBlank { "$fallbackPrefix: $responseBody" }
                else -> "$fallbackPrefix: $responseBody"
            }
        } catch (_: Exception) {
            "$fallbackPrefix: ${responseBody.ifBlank { "unknown error" }}"
        }
    }

    protected fun parseTokenCount(responseBody: String): Int? {
        return try {
            val json = JSONObject(responseBody)
            when {
                json.has("n_tokens") -> json.optInt("n_tokens", -1).takeIf { it >= 0 }
                json.has("count") -> json.optInt("count", -1).takeIf { it >= 0 }
                json.has("token_count") -> json.optInt("token_count", -1).takeIf { it >= 0 }
                json.optJSONArray("tokens") != null -> json.optJSONArray("tokens")?.length()
                json.optJSONArray("token_ids") != null -> json.optJSONArray("token_ids")?.length()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    protected fun combinePromptForCounting(systemPrompt: String, userPrompt: String): String {
        return buildString {
            appendLine("System:")
            appendLine(systemPrompt.trim())
            appendLine()
            appendLine("User:")
            append(userPrompt.trim())
        }
    }

    protected fun classifyFailure(message: String, cause: Exception? = null): Exception {
        val normalized = message.lowercase()
        if (normalized.contains("exceed_context_size_error") ||
            normalized.contains("exceeds the available context size") ||
            normalized.contains("prompt does not fit in context") ||
            normalized.contains("out of context")
        ) {
            return IllegalStateException(message)
        }
        if (cause is InterruptedIOException) {
            return RuntimeException("timeout: $message", cause)
        }
        return RuntimeException(message, cause)
    }
}

private class OllamaRemoteSummaryClient(
    config: RemoteSummaryBackendConfig
) : BaseRemoteSummaryClient(config) {
    @Volatile
    private var tokenizerSupported: Boolean? = null

    override suspend fun fetchMetadata(): Result<RemoteSummaryMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val timeoutMinutes = config.timeoutMinutes.coerceAtLeast(1)
            val (status, body) = executeJson(
                path = "/api/tags",
                requestBuilder = Request.Builder().get(),
                timeoutMinutes = timeoutMinutes
            )
            if (status !in 200..299) {
                throw classifyFailure(parseErrorMessage(body, "Ollama metadata request failed"))
            }

            val models = mutableListOf<String>()
            val json = JSONObject(body)
            val modelsArray = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until modelsArray.length()) {
                val name = modelsArray.optJSONObject(i)?.optString("name").orEmpty().ifBlank { null }
                if (name != null) {
                    models += name
                }
            }

            RemoteSummaryMetadata(
                backend = config.backend,
                baseUrl = config.baseUrl,
                availableModels = models,
                selectedModel = config.model,
                tokenCountMode = if (tokenizerSupported == true) TokenCountMode.EXACT else TokenCountMode.APPROXIMATE
            )
        }
    }

    override suspend fun countRenderedPromptTokens(systemPrompt: String, userPrompt: String): RemoteSummaryTokenCount =
        withContext(Dispatchers.IO) {
            val fullPrompt = combinePromptForCounting(systemPrompt, userPrompt)
            val exactCount = tryOllamaTokenize(fullPrompt)
            if (exactCount != null) {
                RemoteSummaryTokenCount(exactCount, TokenCountMode.EXACT)
            } else {
                RemoteSummaryTokenCount(PDFSummaryLogic.approximateTokens(fullPrompt), TokenCountMode.APPROXIMATE)
            }
        }

    override suspend fun summarize(request: RemoteSummaryRequest): RemoteSummaryResponse = withContext(Dispatchers.IO) {
        val model = config.model ?: throw IllegalStateException("No Ollama model selected")
        val payload = buildOllamaSummaryRequestJson(config.copy(model = model), request)

        val (status, body) = executeJson(
            path = "/api/chat",
            requestBuilder = Request.Builder()
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
        )
        if (status !in 200..299) {
            throw classifyFailure(parseErrorMessage(body, "Ollama summary request failed"))
        }

        val json = JSONObject(body)
        if (json.has("error")) {
            throw classifyFailure(parseErrorMessage(body, "Ollama summary request failed"))
        }

        val message = json.optJSONObject("message")
        val rawOutput = message?.optString("content").orEmpty()
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(rawOutput)
        if (cleaned.isBlank()) {
            throw IllegalStateException("blank_output")
        }

        RemoteSummaryResponse(
            output = cleaned,
            rawOutput = rawOutput,
            promptTokens = json.optInt("prompt_eval_count", -1).takeIf { it >= 0 },
            completionTokens = json.optInt("eval_count", -1).takeIf { it >= 0 }
        )
    }

    private suspend fun tryOllamaTokenize(prompt: String): Int? = withContext(Dispatchers.IO) {
        if (tokenizerSupported == false || config.model.isNullOrBlank()) return@withContext null

        val payloads = listOf(
            JSONObject().apply {
                put("model", config.model)
                put("text", prompt)
            },
            JSONObject().apply {
                put("model", config.model)
                put("prompt", prompt)
            },
            JSONObject().apply {
                put("model", config.model)
                put("content", prompt)
            }
        )

        for (payload in payloads) {
            runCatching {
                val (status, body) = executeJson(
                    path = "/api/tokenize",
                    requestBuilder = Request.Builder()
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Content-Type", "application/json"),
                    timeoutMinutes = 1
                )
                if (status !in 200..299) return@runCatching null
                parseTokenCount(body)
            }.getOrNull()?.let { count ->
                tokenizerSupported = true
                return@withContext count
            }
        }

        tokenizerSupported = false
        null
    }
}

private class LlamaServerRemoteSummaryClient(
    config: RemoteSummaryBackendConfig
) : BaseRemoteSummaryClient(config) {
    override suspend fun fetchMetadata(): Result<RemoteSummaryMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val modelLabel = fetchModelLabel()
            val contextTokens = fetchContextTokens()
            RemoteSummaryMetadata(
                backend = config.backend,
                baseUrl = config.baseUrl,
                selectedModel = modelLabel,
                serverModelLabel = modelLabel,
                serverContextTokens = contextTokens,
                serverContextLabel = contextTokens?.let { "$it tokens" },
                tokenCountMode = TokenCountMode.EXACT
            )
        }
    }

    override suspend fun countRenderedPromptTokens(systemPrompt: String, userPrompt: String): RemoteSummaryTokenCount =
        withContext(Dispatchers.IO) {
            val prompt = combinePromptForCounting(systemPrompt, userPrompt)
            val payloads = listOf(
                JSONObject().apply {
                    put("content", prompt)
                    put("add_special", true)
                    put("with_pieces", false)
                },
                JSONObject().apply {
                    put("text", prompt)
                    put("add_special", true)
                },
                JSONObject().apply {
                    put("prompt", prompt)
                }
            )

            for (payload in payloads) {
                runCatching {
                    val (status, body) = executeJson(
                        path = "/tokenize",
                        requestBuilder = Request.Builder()
                            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .header("Content-Type", "application/json"),
                        timeoutMinutes = 1
                    )
                    if (status !in 200..299) return@runCatching null
                    parseTokenCount(body)
                }.getOrNull()?.let { count ->
                    return@withContext RemoteSummaryTokenCount(count, TokenCountMode.EXACT)
                }
            }

            RemoteSummaryTokenCount(PDFSummaryLogic.approximateTokens(prompt), TokenCountMode.APPROXIMATE)
        }

    override suspend fun summarize(request: RemoteSummaryRequest): RemoteSummaryResponse = withContext(Dispatchers.IO) {
        val payload = buildLlamaServerSummaryRequestJson(config, request)

        val (status, body) = executeJson(
            path = "/v1/chat/completions",
            requestBuilder = Request.Builder()
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
        )
        if (status !in 200..299) {
            throw classifyFailure(parseErrorMessage(body, "llama-server summary request failed"))
        }

        val json = JSONObject(body)
        if (json.has("error")) {
            throw classifyFailure(parseErrorMessage(body, "llama-server summary request failed"))
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val rawContent = buildString {
            append(message?.optString("reasoning_content").orEmpty())
            if (isNotBlank()) appendLine()
            append(message?.optString("content").orEmpty())
        }.trim()
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(rawContent)
        if (cleaned.isBlank()) {
            throw IllegalStateException("blank_output")
        }

        val usage = json.optJSONObject("usage")
        RemoteSummaryResponse(
            output = cleaned,
            rawOutput = body,
            promptTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
            completionTokens = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }
        )
    }

    private suspend fun fetchModelLabel(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val timeoutMinutes = config.timeoutMinutes.coerceAtLeast(1)
            val (status, body) = executeJson(
                path = "/v1/models",
                requestBuilder = Request.Builder().get(),
                timeoutMinutes = timeoutMinutes
            )
            if (status !in 200..299) return@runCatching null
            parseOpenAiModelIds(body).firstOrNull()
        }.getOrNull()
    }

    private suspend fun fetchContextTokens(): Int? = withContext(Dispatchers.IO) {
        val timeoutMinutes = config.timeoutMinutes.coerceAtLeast(1)
        val getAttempt = runCatching {
            val (status, body) = executeJson(
                path = "/props",
                requestBuilder = Request.Builder().get(),
                timeoutMinutes = timeoutMinutes
            )
            if (status !in 200..299) return@runCatching null
            parseLlamaServerContextTokens(body)
        }.getOrNull()
        if (getAttempt != null) return@withContext getAttempt

        runCatching {
            val payload = JSONObject()
            val (status, body) = executeJson(
                path = "/props",
                requestBuilder = Request.Builder()
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Content-Type", "application/json"),
                timeoutMinutes = timeoutMinutes
            )
            if (status !in 200..299) return@runCatching null
            parseLlamaServerContextTokens(body)
        }.getOrNull()
    }
}

private class LlamaSwapRemoteSummaryClient(
    config: RemoteSummaryBackendConfig
) : BaseRemoteSummaryClient(config) {
    override suspend fun fetchMetadata(): Result<RemoteSummaryMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val timeoutMinutes = config.timeoutMinutes.coerceAtLeast(1)
            val (status, body) = executeJson(
                path = "/v1/models",
                requestBuilder = Request.Builder().get(),
                timeoutMinutes = timeoutMinutes
            )
            if (status !in 200..299) {
                throw classifyFailure(parseErrorMessage(body, "llama-swap metadata request failed"))
            }
            val models = parseOpenAiModelIds(body)
            RemoteSummaryMetadata(
                backend = config.backend,
                baseUrl = config.baseUrl,
                availableModels = models,
                selectedModel = config.model,
                serverModelLabel = config.model,
                tokenCountMode = TokenCountMode.APPROXIMATE
            )
        }
    }

    override suspend fun countRenderedPromptTokens(systemPrompt: String, userPrompt: String): RemoteSummaryTokenCount =
        withContext(Dispatchers.IO) {
            val prompt = combinePromptForCounting(systemPrompt, userPrompt)
            RemoteSummaryTokenCount(PDFSummaryLogic.approximateTokens(prompt), TokenCountMode.APPROXIMATE)
        }

    override suspend fun summarize(request: RemoteSummaryRequest): RemoteSummaryResponse = withContext(Dispatchers.IO) {
        val model = config.model?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No llama-swap model selected")
        val payload = buildLlamaSwapSummaryRequestJson(config.copy(model = model), request)

        val (status, body) = executeJson(
            path = "/v1/chat/completions",
            requestBuilder = Request.Builder()
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
        )
        if (status !in 200..299) {
            throw classifyFailure(parseErrorMessage(body, "llama-swap summary request failed"))
        }

        val json = JSONObject(body)
        if (json.has("error")) {
            throw classifyFailure(parseErrorMessage(body, "llama-swap summary request failed"))
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val rawContent = buildString {
            append(message?.optString("reasoning_content").orEmpty())
            if (isNotBlank()) appendLine()
            append(message?.optString("content").orEmpty())
        }.trim()
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(rawContent)
        if (cleaned.isBlank()) {
            throw IllegalStateException("blank_output")
        }

        val usage = json.optJSONObject("usage")
        RemoteSummaryResponse(
            output = cleaned,
            rawOutput = body,
            promptTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
            completionTokens = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }
        )
    }
}
