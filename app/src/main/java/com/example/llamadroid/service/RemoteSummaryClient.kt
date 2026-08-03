package com.example.llamadroid.service

import android.content.Context
import android.util.Base64
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.advertisedLiteRtMaxContextTokens
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

data class RemoteSummaryBackendConfig(
    val backend: String,
    val baseUrl: String,
    val model: String?,
    val timeoutMinutes: Int,
    val context: Context? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = LITERT_BACKEND_AUTO,
    val liteRtMtpEnabled: Boolean = false
)

data class RemoteSummaryRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float,
    val thinkingEnabled: Boolean,
    val imageAttachments: List<RemoteSummaryImageAttachment> = emptyList(),
    val preferLlamaMultimodalCompletion: Boolean = false,
    val allowBlankOutput: Boolean = false
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
    val visionSupported: Boolean? = null,
    val llamaMediaMarker: String? = null,
    val serverBuildInfo: String? = null,
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
    val completionTokens: Int? = null,
    val stopType: String? = null,
    val runtimeFallbacks: Int = 0
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
    fun fromSnapshot(context: Context, snapshot: RemoteSummarySettingsSnapshot): RemoteSummaryClient =
        fromSnapshot(snapshot, context)

    fun fromSnapshot(snapshot: RemoteSummarySettingsSnapshot): RemoteSummaryClient {
        return fromSnapshot(snapshot, context = null)
    }

    private fun fromSnapshot(snapshot: RemoteSummarySettingsSnapshot, context: Context?): RemoteSummaryClient {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        val config = RemoteSummaryBackendConfig(
            backend = backend,
            baseUrl = when (backend) {
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> snapshot.llamaServerUrl
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapUrl
                SettingsRepository.PDF_BACKEND_LITERT -> "local"
                else -> snapshot.ollamaUrl
            }.trim().trimEnd('/'),
            model = when (backend) {
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> snapshot.llamaServerModelLabel
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapModel
                SettingsRepository.PDF_BACKEND_LITERT -> snapshot.liteRtModelId?.let { "litert:$it" }
                else -> snapshot.ollamaModel
            }?.trim()?.ifBlank { null },
            timeoutMinutes = snapshot.timeoutMinutes,
            context = context,
            liteRtModelId = snapshot.liteRtModelId,
            liteRtBackend = snapshot.liteRtBackend,
            liteRtMtpEnabled = snapshot.liteRtMtpEnabled
        )
        return fromConfig(config)
    }

    fun fromConfig(config: RemoteSummaryBackendConfig): RemoteSummaryClient {
        return when (SettingsRepository.normalizeOllamaOrLlamaBackend(config.backend)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> LlamaServerRemoteSummaryClient(config)
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> LlamaSwapRemoteSummaryClient(config)
            SettingsRepository.PDF_BACKEND_LITERT -> LiteRtRemoteSummaryClient(config)
            else -> OllamaRemoteSummaryClient(config)
        }
    }
}

class LiteRtRemoteSummaryClient(private val config: RemoteSummaryBackendConfig) : RemoteSummaryClient {
    private val appContext: Context = requireNotNull(config.context) {
        "LiteRT summary backend requires an Android context"
    }.applicationContext
    private val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }

    override suspend fun fetchMetadata(): Result<RemoteSummaryMetadata> = runCatching {
        val models = database.liteRtModelDao().observeAll().first()
        val selected = resolveModel(models)
        RemoteSummaryMetadata(
            backend = SettingsRepository.PDF_BACKEND_LITERT,
            baseUrl = "local",
            availableModels = models.map { it.displayName },
            selectedModel = selected.displayName,
            serverModelLabel = selected.displayName,
            serverContextTokens = selected.advertisedLiteRtMaxContextTokens(),
            serverContextLabel = selected.advertisedLiteRtMaxContextTokens()?.let { "$it tokens" },
            visionSupported = selected.supportsLiteRtVision(),
            tokenCountMode = TokenCountMode.APPROXIMATE
        )
    }

    override suspend fun countRenderedPromptTokens(
        systemPrompt: String,
        userPrompt: String
    ): RemoteSummaryTokenCount =
        RemoteSummaryTokenCount(
            totalTokens = estimateNativeChatTextTokens("$systemPrompt\n$userPrompt"),
            mode = TokenCountMode.APPROXIMATE
        )

    override suspend fun summarize(request: RemoteSummaryRequest): RemoteSummaryResponse {
        val model = resolveModel()
        val imageFiles = request.imageAttachments
            .takeIf { model.supportsLiteRtVision() }
            .orEmpty()
            .mapIndexedNotNull { index, attachment ->
                writeLiteRtSummaryImageAttachment(attachment, index)
            }
        if (request.imageAttachments.isNotEmpty() && imageFiles.isEmpty()) {
            DebugLog.log(
                "LiteRtRemoteSummaryClient: image attachments ignored because " +
                    "model=${model.displayName} vision=${model.supportsLiteRtVision()}"
            )
        } else if (imageFiles.size < request.imageAttachments.size) {
            DebugLog.log(
                "LiteRtRemoteSummaryClient: using ${imageFiles.size}/${request.imageAttachments.size} " +
                    "image attachments for ${model.displayName}"
            )
        }
        return try {
            val result = LiteRtTextGenerationClient(appContext).generate(
                model = model,
                title = "Remote summary",
                systemPrompt = request.systemPrompt,
                messages = emptyList(),
                userPrompt = request.userPrompt,
                contextSize = request.contextSize.takeIf { it > 0 }
                    ?: model.defaultLiteRtChatContextTokens()
                    ?: 4_000,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                thinkingEnabled = request.thinkingEnabled,
                backendMode = normalizeLiteRtBackend(config.liteRtBackend),
                mtpEnabled = config.liteRtMtpEnabled,
                userImagePath = imageFiles.firstOrNull()?.absolutePath
            )
            RemoteSummaryResponse(
                output = result.output,
                rawOutput = result.rawOutput,
                promptTokens = result.stats.promptTokens,
                completionTokens = result.stats.completionTokens,
                runtimeFallbacks = result.runtimeFallbacks
            )
        } finally {
            imageFiles.forEach { file -> runCatching { file.delete() } }
        }
    }

    override fun cancelActiveCall() = Unit

    private suspend fun resolveModel(models: List<LiteRtModelEntity>? = null): LiteRtModelEntity {
        val available = models ?: database.liteRtModelDao().observeAll().first()
        val selectedId = config.liteRtModelId
            ?: config.model?.removePrefix("litert:")?.toLongOrNull()
        return selectedId
            ?.let { id -> available.firstOrNull { it.id == id } }
            ?: available.firstOrNull()
            ?: throw IllegalStateException(appContext.getString(R.string.litert_error_model_missing))
    }

    private fun writeLiteRtSummaryImageAttachment(
        attachment: RemoteSummaryImageAttachment,
        index: Int
    ): File? = runCatching {
        val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
        val extension = when {
            attachment.mimeType.contains("png", ignoreCase = true) -> "png"
            attachment.mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val mediaDir = File(appContext.cacheDir, "litert_remote_summary_media").apply { mkdirs() }
        File(mediaDir, "summary_${System.currentTimeMillis()}_${index}.$extension")
            .apply { writeBytes(bytes) }
    }.getOrElse { error ->
        DebugLog.log("LiteRtRemoteSummaryClient: failed to stage image attachment: ${error.message}")
        null
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

internal fun buildLlamaServerMultimodalCompletionRequestPayload(
    request: RemoteSummaryRequest,
    mediaMarker: String = "<__media__>"
): Map<String, Any?> {
    require(request.imageAttachments.isNotEmpty()) {
        "A multimodal llama.cpp completion requires at least one attachment"
    }
    val resolvedMarker = mediaMarker.trim().ifBlank { "<__media__>" }
    val markers = List(request.imageAttachments.size) { resolvedMarker }.joinToString("\n")
    val promptText = buildString {
        append(markers)
        appendLine()
        if (request.systemPrompt.isNotBlank()) {
            appendLine(request.systemPrompt.trim())
        }
        append(request.userPrompt.trim())
    }.trim()
    return linkedMapOf(
        "prompt" to linkedMapOf(
            "prompt_string" to promptText,
            "multimodal_data" to request.imageAttachments.map { it.base64 }
        ),
        "stream" to false,
        "temperature" to request.temperature.toDouble(),
        "n_predict" to request.maxTokens,
        "cache_prompt" to false
    )
}

internal fun buildLlamaServerMultimodalCompletionRequestJson(
    request: RemoteSummaryRequest,
    mediaMarker: String = "<__media__>"
): JSONObject = buildJsonObject(
    buildLlamaServerMultimodalCompletionRequestPayload(request, mediaMarker)
)

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

internal fun parseLlamaServerMediaMarker(body: String): String? {
    return runCatching {
        JSONObject(body).optString("media_marker").trim().ifBlank { null }
    }.getOrNull()
}

internal fun parseLlamaServerBuildInfo(body: String): String? {
    return runCatching {
        JSONObject(body).optString("build_info").trim().ifBlank { null }
    }.getOrNull()
}

internal fun parseLlamaServerVisionSupport(body: String): Boolean? {
    return runCatching {
        JSONObject(body).optVisionCapability()
    }.getOrNull()
}

internal fun parseLlamaServerModelVisionSupport(body: String): Boolean? {
    return runCatching {
        val json = JSONObject(body)
        json.optVisionCapability()?.let { return@runCatching it }
        val models = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return@runCatching null
        var sawExplicitTextOnly = false
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            when (model.optVisionCapability()) {
                true -> return@runCatching true
                false -> sawExplicitTextOnly = true
                null -> Unit
            }
        }
        false.takeIf { sawExplicitTextOnly }
    }.getOrNull()
}

private fun JSONObject.optVisionCapability(): Boolean? {
    val modalities = optJSONObject("modalities")
    if (modalities != null && modalities.has("vision")) {
        return modalities.optNullableBoolean("vision")
    }
    val capabilityObject = optJSONObject("capabilities")
    if (capabilityObject != null) {
        capabilityObject.optNullableBoolean("multimodal")?.let { return it }
        capabilityObject.optNullableBoolean("vision")?.let { return it }
        capabilityObject.optNullableBoolean("image")?.let { return it }
    }
    val capabilities = optJSONArray("capabilities")
    if (capabilities != null) {
        return capabilities.containsAny("vision", "image", "multimodal").takeIf { it }
    }
    return optNullableBoolean("multimodal")
        ?: optNullableBoolean("vision")
        ?: optNullableBoolean("supports_vision")
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "vision", "multimodal" -> true
            "false", "0", "no", "none", "text" -> false
            else -> null
        }
        is Number -> value.toInt() != 0
        else -> null
    }
}

private fun JSONArray.containsAny(vararg values: String): Boolean {
    val expected = values.map { it.lowercase() }.toSet()
    for (index in 0 until length()) {
        val item = optString(index).trim().lowercase()
        if (item in expected) return true
    }
    return false
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

private data class LlamaServerProps(
    val contextTokens: Int? = null,
    val mediaMarker: String? = null,
    val visionSupported: Boolean? = null,
    val buildInfo: String? = null
)

private class LlamaServerRemoteSummaryClient(
    config: RemoteSummaryBackendConfig
) : BaseRemoteSummaryClient(config) {
    @Volatile
    private var cachedMediaMarker: String? = null
    @Volatile
    private var cachedProps: LlamaServerProps? = null

    override suspend fun fetchMetadata(): Result<RemoteSummaryMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val props = fetchProps()
            val modelLabel = fetchModelLabel()
            val contextTokens = props.contextTokens ?: fetchContextTokens()
            val visionSupported = props.visionSupported ?: fetchModelVisionSupport()
            RemoteSummaryMetadata(
                backend = config.backend,
                baseUrl = config.baseUrl,
                selectedModel = modelLabel,
                serverModelLabel = modelLabel,
                serverContextTokens = contextTokens,
                serverContextLabel = contextTokens?.let { "$it tokens" },
                visionSupported = visionSupported,
                llamaMediaMarker = props.mediaMarker,
                serverBuildInfo = props.buildInfo,
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
        if (request.preferLlamaMultimodalCompletion && request.imageAttachments.isNotEmpty()) {
            return@withContext summarizeMultimodalCompletion(request)
        }
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
        if (cleaned.isBlank() && !request.allowBlankOutput) {
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

    private suspend fun summarizeMultimodalCompletion(
        request: RemoteSummaryRequest
    ): RemoteSummaryResponse {
        val mediaMarker = fetchMediaMarker()
        val payload = buildLlamaServerMultimodalCompletionRequestJson(request = request, mediaMarker = mediaMarker)
        DebugLog.log(
            "LlamaServerRemoteSummaryClient: POST /completion multimodal attachments=${request.imageAttachments.size} " +
                "marker=${describeMediaMarker(mediaMarker)} allowBlank=${request.allowBlankOutput}"
        )
        val (status, body) = executeJson(
            path = "/completion",
            requestBuilder = Request.Builder()
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
        )
        if (status !in 200..299) {
            throw classifyFailure(parseErrorMessage(body, "llama-server multimodal OCR request failed"))
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            throw classifyFailure(parseErrorMessage(body, "llama-server multimodal OCR request failed"))
        }
        val rawOutput = json.optString("content")
            .ifBlank { json.optString("response") }
            .ifBlank { json.optString("text") }
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(rawOutput).trim()
        if (cleaned.isBlank() && !request.allowBlankOutput) {
            throw IllegalStateException("blank_output")
        }
        val tokensEvaluated = json.optInt("tokens_evaluated", -1).takeIf { it >= 0 }
        val tokensPredicted = json.optInt("tokens_predicted", -1).takeIf { it >= 0 }
        val stopType = json.optString("stop_type").trim().ifBlank { null }
        return RemoteSummaryResponse(
            output = cleaned,
            rawOutput = body,
            promptTokens = tokensEvaluated,
            completionTokens = tokensPredicted,
            stopType = stopType
        )
    }

    private suspend fun fetchMediaMarker(): String {
        cachedMediaMarker?.let { return it }
        val marker = fetchProps().mediaMarker ?: "<__media__>"
        cachedMediaMarker = marker
        return marker
    }

    private suspend fun fetchProps(): LlamaServerProps {
        cachedProps?.let { return it }
        val props = runCatching {
            val (status, body) = executeJson(
                path = "/props",
                requestBuilder = Request.Builder().get(),
                timeoutMinutes = 1
            )
            if (status !in 200..299) return@runCatching LlamaServerProps()
            LlamaServerProps(
                contextTokens = parseLlamaServerContextTokens(body),
                mediaMarker = parseLlamaServerMediaMarker(body),
                visionSupported = parseLlamaServerVisionSupport(body),
                buildInfo = parseLlamaServerBuildInfo(body)
            )
        }.getOrElse { error ->
            DebugLog.log("LlamaServerRemoteSummaryClient: /props unavailable for OCR metadata: ${error.message}")
            LlamaServerProps()
        }
        DebugLog.log(
            "LlamaServerRemoteSummaryClient: /props context=${props.contextTokens ?: "unknown"} " +
                "vision=${props.visionSupported?.toString() ?: "unknown"} " +
                "marker=${props.mediaMarker?.let(::describeMediaMarker) ?: "default"} " +
                "build=${props.buildInfo?.take(36) ?: "unknown"}"
        )
        props.mediaMarker?.let { cachedMediaMarker = it }
        cachedProps = props
        return props
    }

    private fun describeMediaMarker(marker: String): String {
        return if (marker.length <= 24) {
            marker
        } else {
            "${marker.take(12)}...(${marker.length})"
        }
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

    private suspend fun fetchModelVisionSupport(): Boolean? = withContext(Dispatchers.IO) {
        val timeoutMinutes = config.timeoutMinutes.coerceAtLeast(1)
        for (path in listOf("/models", "/v1/models")) {
            runCatching {
                val (status, body) = executeJson(
                    path = path,
                    requestBuilder = Request.Builder().get(),
                    timeoutMinutes = timeoutMinutes
                )
                if (status !in 200..299) return@runCatching null
                parseLlamaServerModelVisionSupport(body)
            }.getOrNull()?.let { vision ->
                DebugLog.log("LlamaServerRemoteSummaryClient: $path vision capability fallback=$vision")
                return@withContext vision
            }
        }
        null
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
