package com.example.llamadroid.service

import com.example.llamadroid.data.db.CustomToolEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Executes the useful, non-shell subset of curl custom-tool templates.
 *
 * This lets users describe HTTP APIs with familiar curl examples while keeping parameter values
 * as argv entries. File uploads, command expansion, proxies and arbitrary curl flags are rejected.
 */
object CustomToolHttpExecutor {
    private const val MAX_RESPONSE_CHARS = 16_384
    private const val MAX_REDIRECTS = 4

    data class PreparedRequest(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String?,
        val contentType: String?,
        val connectTimeoutSeconds: Int,
        val maxTimeSeconds: Int
    )

    fun supports(template: String): Boolean {
        val first = AgentRuntimeSupport.stripShellPrefix(template)
            .trimStart()
            .substringBefore(' ')
            .trim('"', '\'')
        return first.substringAfterLast('/').equals("curl", ignoreCase = true)
    }

    fun prepare(tool: CustomToolEntity, arguments: Map<String, String>): PreparedRequest {
        val tokens = AgentRuntimeSupport.tokenizeArgvTemplate(tool.commandTemplate, arguments)
        require(tokens.firstOrNull()?.substringAfterLast('/')?.equals("curl", ignoreCase = true) == true) {
            "Only curl-style API templates are supported in a local sandbox."
        }

        var method: String? = null
        var url: String? = null
        var body: String? = null
        var contentType: String? = null
        var connectTimeoutSeconds = 20
        var maxTimeSeconds = 60
        var queryMode = false
        val headers = mutableListOf<Pair<String, String>>()
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            fun nextValue(flag: String): String {
                index += 1
                require(index < tokens.size) { "Custom tool curl option `$flag` needs a value." }
                return tokens[index]
            }
            when (token) {
                "-s", "-S", "-sS", "--silent", "--show-error", "--fail", "--fail-with-body" -> Unit
                "-L", "--location" -> Unit // Redirect targets are revalidated below.
                "-G", "--get" -> queryMode = true
                "-X", "--request" -> method = nextValue(token).uppercase()
                "-H", "--header" -> {
                    val raw = nextValue(token)
                    val split = raw.indexOf(':')
                    require(split > 0) { "Custom tool curl header must use `Name: value`." }
                    val name = raw.substring(0, split).trim()
                    val value = raw.substring(split + 1).trim()
                    require(name.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Custom tool curl header name is invalid." }
                    headers += name to value
                    if (name.equals("Content-Type", ignoreCase = true)) contentType = value
                }
                "-d", "--data", "--data-raw", "--data-binary" -> {
                    val value = nextValue(token)
                    require(!value.startsWith("@")) { "Local API tools cannot read request bodies from files." }
                    body = listOfNotNull(body, value).joinToString("&")
                }
                "--json" -> {
                    body = nextValue(token)
                    contentType = "application/json"
                    headers.removeAll { it.first.equals("Content-Type", ignoreCase = true) }
                    headers += "Content-Type" to "application/json"
                }
                "--url" -> url = nextValue(token)
                "--connect-timeout", "--max-time" -> {
                    val seconds = nextValue(token).toIntOrNull()
                        ?.also { require(it in 1..120) { "$token must be between 1 and 120 seconds." } }
                        ?: throw IllegalArgumentException("$token must be a number of seconds.")
                    if (token == "--connect-timeout") connectTimeoutSeconds = seconds
                    else maxTimeSeconds = seconds
                }
                else -> {
                    require(!token.startsWith("-")) { "Unsupported local curl option `$token`." }
                    require(url == null) { "Custom tool curl template contains more than one URL." }
                    url = token
                }
            }
            index += 1
        }

        var resolvedUrl = url?.trim().orEmpty()
        require(resolvedUrl.isNotBlank()) { "Custom tool curl template needs an HTTP or HTTPS URL." }
        require(resolvedUrl.length <= 2_048) { "Custom tool URL is too long." }
        val uri = runCatching { URI(resolvedUrl) }.getOrNull()
            ?: throw IllegalArgumentException("Custom tool URL is invalid.")
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Custom API tools support HTTP and HTTPS only."
        }
        AgentRuntimeSupport.blockedUrlReason(resolvedUrl)?.let { throw IllegalArgumentException(it) }

        if (queryMode && !body.isNullOrBlank()) {
            val queryBuilder = resolvedUrl.toHttpUrl().newBuilder()
            body.split('&').filter(String::isNotBlank).forEach { field ->
                val separator = field.indexOf('=')
                if (separator < 0) queryBuilder.addQueryParameter(field, "")
                else queryBuilder.addQueryParameter(
                    field.substring(0, separator),
                    field.substring(separator + 1)
                )
            }
            resolvedUrl = queryBuilder.build().toString()
            body = null
        }
        val resolvedMethod = method ?: if (body == null) "GET" else "POST"
        require(resolvedMethod in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "Unsupported HTTP method `$resolvedMethod`."
        }
        require(headers.size <= 32) { "Custom API tools support at most 32 headers." }
        require((body?.length ?: 0) <= 16_384) { "Custom API request body exceeds 16 KiB." }
        return PreparedRequest(
            resolvedMethod,
            resolvedUrl,
            headers,
            body,
            contentType,
            connectTimeoutSeconds,
            maxTimeSeconds
        )
    }

    suspend fun execute(
        tool: CustomToolEntity,
        arguments: Map<String, String>,
        clientFactory: ((PreparedRequest) -> OkHttpClient)? = null
    ): String = withContext(Dispatchers.IO) {
        val prepared = prepare(tool, arguments)
        val client = clientFactory?.invoke(prepared) ?: OkHttpClient.Builder()
            .connectTimeout(prepared.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(prepared.maxTimeSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(prepared.maxTimeSeconds.toLong(), TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        var url = prepared.url
        var redirects = 0
        while (true) {
            AgentRuntimeSupport.blockedUrlReason(url)?.let { throw IllegalArgumentException(it) }
            val builder = Request.Builder().url(url)
            prepared.headers.forEach { (name, value) -> builder.header(name, value) }
            val requestBody = prepared.body?.toRequestBody(prepared.contentType?.toMediaTypeOrNull())
            builder.method(prepared.method, if (prepared.method in setOf("GET", "HEAD")) null else requestBody ?: "".toRequestBody())
            var redirectTarget: String? = null
            val completed = client.newCall(builder.build()).execute().use { response ->
                if (response.isRedirect) {
                    require(redirects < MAX_REDIRECTS) { "Custom API tool exceeded $MAX_REDIRECTS redirects." }
                    val location = response.header("Location")
                        ?: throw IllegalArgumentException("Custom API redirect did not include a destination.")
                    redirectTarget = URI(url).resolve(location).toString()
                    null
                } else {
                    val (responseText, truncated) = readBoundedBody(response.body)
                    buildString {
                        appendLine("status: ${if (response.isSuccessful) "success" else "error"}")
                        appendLine("http_status: ${response.code}")
                        appendLine("content_type: ${response.header("Content-Type").orEmpty()}")
                        append(responseText.take(MAX_RESPONSE_CHARS))
                        if (truncated) {
                            append("\n[response truncated at $MAX_RESPONSE_CHARS characters]")
                        }
                    }.trim()
                }
            }
            if (completed != null) return@withContext completed
            url = requireNotNull(redirectTarget)
            redirects += 1
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun readBoundedBody(body: ResponseBody?): Pair<String, Boolean> {
        if (body == null) return "" to false
        val reader = body.charStream()
        val buffer = CharArray(MAX_RESPONSE_CHARS + 1)
        var total = 0
        while (total < buffer.size) {
            val read = reader.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        val truncated = total > MAX_RESPONSE_CHARS
        return String(buffer, 0, total.coerceAtMost(MAX_RESPONSE_CHARS)) to truncated
    }
}
