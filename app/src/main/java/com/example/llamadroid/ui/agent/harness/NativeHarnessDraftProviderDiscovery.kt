package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.HarnessDiscoveredModel
import com.example.llamadroid.harness.normalizeHarnessProviderEndpoint
import com.example.llamadroid.harness.parseHarnessLlamaCapabilities
import com.example.llamadroid.harness.parseHarnessOpenAiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A bounded, unsaved-provider probe. It never writes Harness settings. */
internal class NativeHarnessDraftDiscoveryException(
    val code: String,
    message: String = code,
) : Exception(message)

internal data class NativeHarnessDraftHttpResponse(
    val statusCode: Int,
    val body: String?,
)

private data class NativeHarnessDraftJsonResponse(
    val body: JSONObject?,
    val notFound: Boolean,
)

/** Injectable transport boundary keeps discovery deterministic in JVM tests. */
internal fun interface NativeHarnessDraftDiscoveryTransport {
    suspend fun get(
        url: String,
        request: NativeHarnessCustomProviderRequest,
    ): NativeHarnessDraftHttpResponse
}

internal object NativeHarnessDraftProviderDiscovery {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024L
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun discover(request: NativeHarnessCustomProviderRequest): List<HarnessDiscoveredModelUi> =
        discover(request) { url, draft -> requestHttp(url, draft) }

    internal suspend fun discover(
        request: NativeHarnessCustomProviderRequest,
        transport: NativeHarnessDraftDiscoveryTransport,
    ): List<HarnessDiscoveredModelUi> =
        withContext(Dispatchers.IO) {
            val endpoint = normalizeHarnessProviderEndpoint(request.baseUrl)
                ?: throw NativeHarnessDraftDiscoveryException("CUSTOM_PROVIDER_BASE_URL_INVALID")
            if (request.api !in NATIVE_CUSTOM_PROVIDER_PROTOCOLS) {
                throw NativeHarnessDraftDiscoveryException("CUSTOM_PROVIDER_PROTOCOL_INVALID")
            }
            val modelsResponse = requestJson(endpoint.modelsUrl, request, transport)
            val models = modelsResponse.body?.let { payload ->
                if (payload.optJSONArray("data") == null && payload.optJSONArray("models") == null) {
                    throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_INVALID_RESPONSE")
                }
                parseHarnessOpenAiModels(payload)
            }.orEmpty()
            // llama.cpp frequently returns only an ID from /v1/models. Probe /props when
            // metadata is missing, and use it only for a matching model (or a unique row).
            // A multi-model llama-swap catalog must never inherit the currently-loaded
            // model's context window by position.
            val shouldProbeProps = modelsResponse.notFound || models.isEmpty() ||
                models.any { it.contextLength == null || it.maxTokens == null }
            val propsResponse = if (shouldProbeProps) {
                try {
                    requestJson(endpoint.rootUrl + "/props", request, transport)
                } catch (error: NativeHarnessDraftDiscoveryException) {
                    // /props is optional when /models already gave us a usable catalog. If
                    // /models is absent, preserve the error because it is our only probe.
                    if (modelsResponse.notFound) throw error
                    null
                }
            } else {
                null
            }
            val withProps = if (models.isNotEmpty()) {
                enrichModelsFromProps(models, propsResponse?.body)
            } else if (propsResponse?.body != null) {
                discoverFromLlamaProps(requireNotNull(propsResponse.body))
            } else if (modelsResponse.notFound && propsResponse?.notFound == true) {
                throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_UNSUPPORTED")
            } else {
                emptyList()
            }
            withProps.map { model ->
                HarnessDiscoveredModelUi(
                    id = model.wireId,
                    name = model.displayName.takeIf { it != model.wireId },
                    contextWindow = model.contextLength?.toLong(),
                    maxTokens = model.maxTokens?.toLong(),
                    inputModalities = model.inputModalities,
                )
            }
        }

    private suspend fun discoverFromLlamaProps(
        props: JSONObject,
    ): List<HarnessDiscoveredModel> {
        val capabilities = parseHarnessLlamaCapabilities(props, slotsPayload = null)
        val model = capabilities.model?.takeIf(String::isNotBlank) ?: return emptyList()
        return listOf(
            HarnessDiscoveredModel(
                wireId = model,
                displayName = com.example.llamadroid.harness.harnessFriendlyModelLabel(model),
                contextLength = capabilities.contextLength,
                maxTokens = capabilities.maxTokens,
            )
        )
    }

    private fun enrichModelsFromProps(
        models: List<HarnessDiscoveredModel>,
        props: JSONObject?,
    ): List<HarnessDiscoveredModel> {
        if (props == null) return models
        val capabilities = parseHarnessLlamaCapabilities(props, slotsPayload = null)
        if (capabilities.contextLength == null && capabilities.maxTokens == null) return models
        val propsModel = capabilities.model?.trim()?.takeIf(String::isNotEmpty)
        val targetIndex = when {
            propsModel == null && models.size == 1 -> 0
            propsModel == null -> null
            else -> findPropsModelIndex(models, propsModel)
        } ?: return models
        return models.mapIndexed { index, model ->
            if (index != targetIndex) model else model.copy(
                contextLength = model.contextLength ?: capabilities.contextLength,
                maxTokens = model.maxTokens ?: capabilities.maxTokens,
            )
        }
    }

    private fun findPropsModelIndex(
        models: List<HarnessDiscoveredModel>,
        propsModel: String,
    ): Int? {
        models.indexOfFirst { it.wireId == propsModel }.takeIf { it >= 0 }?.let { return it }
        val propsBasename = modelBasename(propsModel)
        val basenameMatches = models.mapIndexedNotNull { index, model ->
            index.takeIf { modelBasename(model.wireId) == propsBasename }
        }
        return basenameMatches.singleOrNull()
    }

    private fun modelBasename(value: String): String =
        value.trim().replace('\\', '/').substringAfterLast('/').lowercase()

    private suspend fun requestJson(
        url: String,
        request: NativeHarnessCustomProviderRequest,
        transport: NativeHarnessDraftDiscoveryTransport,
    ): NativeHarnessDraftJsonResponse {
        val response = try {
            transport.get(url, request)
        } catch (error: NativeHarnessDraftDiscoveryException) {
            throw error
        } catch (error: IOException) {
            throw NativeHarnessDraftDiscoveryException(
                "PROVIDER_DISCOVERY_NETWORK",
                error.message ?: "Provider endpoint could not be reached",
            )
        }
        if (response.statusCode == 404) return NativeHarnessDraftJsonResponse(null, notFound = true)
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_UNAUTHORIZED")
        }
        if (response.statusCode !in 200..299) {
            throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_HTTP_FAILED")
        }
        val text = response.body ?: throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_INVALID_RESPONSE")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_TOO_LARGE")
        }
        val payload = runCatching { JSONObject(text) }.getOrElse {
            throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_INVALID_RESPONSE")
        }
        return NativeHarnessDraftJsonResponse(payload, notFound = false)
    }

    private suspend fun requestHttp(
        url: String,
        request: NativeHarnessCustomProviderRequest,
    ): NativeHarnessDraftHttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(url).get()
        val key = request.apiKey.trim()
        if (key.isNotEmpty()) {
            if (request.api == "anthropic-messages") builder.header("x-api-key", key)
            else builder.header("Authorization", "Bearer $key")
        }
        if (request.api == "anthropic-messages") builder.header("anthropic-version", "2023-06-01")
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        NativeHarnessDraftHttpResponse(
                            statusCode = it.code,
                            body = it.body?.let { body -> readBoundedBody(body) },
                        )
                    }
                }
                if (continuation.isCancelled) return
                continuation.resumeWith(result)
            }
        })
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_TOO_LARGE")
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw NativeHarnessDraftDiscoveryException("PROVIDER_DISCOVERY_TOO_LARGE")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
