package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.db.AgentRuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded model discovery for a remote endpoint selected in an agent profile.
 *
 * This deliberately does not write to the global Ollama model flow or to a
 * managed-server catalog: discovery results belong only to the editing agent
 * card until the user chooses a model there.
 */
object AgentRuntimeEndpointModelDiscovery {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun fetch(
        backend: AgentRuntimeBackend,
        baseUrl: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/').also {
                require(it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) {
                    "Remote endpoint URL must use HTTP(S)"
                }
            }
            val path = when (backend) {
                AgentRuntimeBackend.OLLAMA -> "/api/tags"
                AgentRuntimeBackend.LLAMA_SERVER,
                AgentRuntimeBackend.LLAMA_SWAP -> "/v1/models"
                AgentRuntimeBackend.LITERT -> error("LiteRT does not expose remote model discovery")
            }
            val connection = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    error("Remote model discovery failed with HTTP $status")
                }
                parseModelNames(backend, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Parses Ollama `/api/tags` and OpenAI-compatible `/v1/models` payloads. */
    internal fun parseModelNames(
        backend: AgentRuntimeBackend,
        body: String
    ): List<String> {
        val json = JSONObject(body)
        val array = when (backend) {
            AgentRuntimeBackend.OLLAMA -> json.optJSONArray("models")
            AgentRuntimeBackend.LLAMA_SERVER,
            AgentRuntimeBackend.LLAMA_SWAP -> json.optJSONArray("data") ?: json.optJSONArray("models")
            AgentRuntimeBackend.LITERT -> null
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = when (backend) {
                    AgentRuntimeBackend.OLLAMA -> item.optString("name")
                    AgentRuntimeBackend.LLAMA_SERVER,
                    AgentRuntimeBackend.LLAMA_SWAP -> item.optString("id").ifBlank {
                        item.optString("name")
                    }
                    AgentRuntimeBackend.LITERT -> ""
                }.trim()
                if (name.isNotEmpty() && !contains(name)) add(name)
            }
        }
    }
}
