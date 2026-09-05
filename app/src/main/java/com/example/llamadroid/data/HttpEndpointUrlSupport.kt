package com.example.llamadroid.data

import java.net.URI
import java.util.Locale

/**
 * Normalizes HTTP endpoints at the boundary where persisted host/port values
 * become request URLs.  Agent runtime data is allowed to contain either a
 * complete URL or a host (including an IPv6 host); scheme-less values default
 * to HTTP.  Invalid values return null so callers can stop with Needs direction
 * instead of handing a relative URL to URL/OkHttp.
 */
object HttpEndpointUrlSupport {
    private const val DEFAULT_SCHEME = "http"
    private const val INVALID_PORT = -2
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun normalizeBaseUrl(
        input: String?,
        defaultScheme: String = DEFAULT_SCHEME,
        defaultPort: Int? = null
    ): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val fallbackScheme = defaultScheme.trim().lowercase(Locale.US)
            .takeIf { it == "http" || it == "https" }
            ?: return null
        val candidate = if (SCHEME_REGEX.containsMatchIn(trimmed)) {
            trimmed
        } else {
            "$fallbackScheme://$trimmed"
        }
        val parsed = parseUri(candidate, fallbackScheme) ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.US)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = parsed.host?.trim()?.trim('[', ']')?.takeIf { it.isNotBlank() }
            ?: return null
        val explicitPort = explicitPort(parsed.rawAuthority)
        if (explicitPort == INVALID_PORT) return null
        val port = explicitPort ?: defaultPort
        if (port != null && port !in 1..65535) return null
        val path = parsed.rawPath.orEmpty().trimEnd('/')
        return render(parsed, scheme, host, port ?: -1, path)
    }

    /** Builds a URL from a managed server's host and port without duplicating either. */
    fun fromHostPort(
        host: String?,
        port: Int,
        defaultScheme: String = DEFAULT_SCHEME
    ): String? {
        if (port !in 1..65535) return null
        val normalized = normalizeBaseUrl(host, defaultScheme = defaultScheme) ?: return null
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
        val parsedHost = parsed.host?.trim()?.trim('[', ']')?.takeIf { it.isNotBlank() }
            ?: return null
        return render(
            parsed = parsed,
            scheme = parsed.scheme.lowercase(Locale.US),
            host = parsedHost,
            port = port,
            path = parsed.rawPath.orEmpty()
        )
    }

    /**
     * Appends an API path while preserving a configured reverse-proxy prefix.
     * A repeated prefix (for example base `/v1` plus `/v1/models`) is joined
     * only once; query and fragment components remain attached to the URL.
     */
    fun appendPath(baseUrl: String?, path: String): String? {
        val normalized = normalizeBaseUrl(baseUrl) ?: return null
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = parsed.host?.trim()?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: return null
        val baseSegments = parsed.rawPath.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val suffixSegments = path.trim().trim('/').split('/').filter { it.isNotBlank() }
        if (suffixSegments.isEmpty()) return normalized
        val overlap = (minOf(baseSegments.size, suffixSegments.size) downTo 1)
            .firstOrNull { count ->
                baseSegments.takeLast(count) == suffixSegments.take(count)
            } ?: 0
        val joinedSegments = baseSegments + suffixSegments.drop(overlap)
        val joinedPath = "/" + joinedSegments.joinToString("/")
        return render(
            parsed = parsed,
            scheme = parsed.scheme.lowercase(Locale.US),
            host = host,
            port = parsed.port,
            path = joinedPath
        )
    }

    private fun parseUri(candidate: String, fallbackScheme: String): URI? {
        runCatching { URI(candidate) }.getOrNull()
            ?.takeIf { !it.host.isNullOrBlank() }
            ?.let { return it }

        // Java URI correctly requires brackets around IPv6 literals. Accept a
        // legacy unbracketed literal at this boundary and canonicalize it.
        val scheme = candidate.substringBefore("://", missingDelimiterValue = fallbackScheme)
        val afterScheme = candidate.substringAfter("://", missingDelimiterValue = "")
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .takeIf { it >= 0 } ?: afterScheme.length
        val authority = afterScheme.substring(0, authorityEnd)
        val remainder = afterScheme.substring(authorityEnd)
        if (authority.isBlank() || !authority.contains(':') || authority.startsWith('[')) {
            return null
        }
        return runCatching { URI("$scheme://[$authority]$remainder") }.getOrNull()
    }

    private fun explicitPort(rawAuthority: String?): Int? {
        val authority = rawAuthority?.substringAfterLast('@') ?: return null
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return INVALID_PORT
            val suffix = authority.substring(close + 1)
            if (suffix.isEmpty()) return null
            if (!suffix.startsWith(':')) return INVALID_PORT
            return suffix.substring(1).toIntOrNull()?.takeIf { it >= 0 } ?: INVALID_PORT
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return null
        if (authority.indexOf(':') != colon) return INVALID_PORT
        return authority.substring(colon + 1).toIntOrNull()?.takeIf { it >= 0 } ?: INVALID_PORT
    }

    private fun render(
        parsed: URI,
        scheme: String,
        host: String,
        port: Int,
        path: String
    ): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return buildString {
            append(scheme)
            append("://")
            parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append('@')
            }
            append(hostPart)
            if (port >= 0) {
                append(':')
                append(port)
            }
            append(path.trimEnd('/'))
            parsed.rawQuery?.let { append('?').append(it) }
            parsed.rawFragment?.let { append('#').append(it) }
        }
    }
}
