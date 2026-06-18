package com.example.llamadroid.data

import java.net.URI

data class RemoteBackendUrlParts(
    val normalizedUrl: String,
    val host: String,
    val port: Int
)

object RemoteBackendUrlSupport {
    private const val DEFAULT_HOST = "localhost"
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun parseForStorage(
        input: String?,
        defaultPort: Int,
        defaultHost: String = DEFAULT_HOST
    ): RemoteBackendUrlParts {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return fromHostPort(defaultHost, defaultPort, defaultPort)
        }

        val candidate = if (SCHEME_REGEX.containsMatchIn(trimmed)) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return runCatching {
            val uri = URI(candidate)
            val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "http"
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: defaultHost
            val port = uri.port.takeIf { it > 0 } ?: defaultPort
            val normalizedUrl = URI(
                scheme,
                uri.rawUserInfo,
                host,
                port,
                uri.rawPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
            RemoteBackendUrlParts(
                normalizedUrl = normalizedUrl,
                host = host,
                port = port
            )
        }.getOrElse {
            fromHostPort(defaultHost, defaultPort, defaultPort)
        }
    }

    fun resolveStoredUrl(
        storedUrl: String?,
        legacyHost: String?,
        legacyPort: Int,
        defaultPort: Int,
        defaultHost: String = DEFAULT_HOST
    ): String {
        val candidate = storedUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?: fromHostPort(legacyHost, legacyPort, defaultPort, defaultHost).normalizedUrl
        return parseForStorage(candidate, defaultPort, defaultHost).normalizedUrl
    }

    fun fromHostPort(
        host: String?,
        port: Int,
        defaultPort: Int,
        defaultHost: String = DEFAULT_HOST
    ): RemoteBackendUrlParts {
        val cleanHost = host?.trim()?.ifBlank { defaultHost } ?: defaultHost
        val cleanPort = port.takeIf { it > 0 } ?: defaultPort
        return RemoteBackendUrlParts(
            normalizedUrl = URI("http", null, cleanHost, cleanPort, null, null, null).toString(),
            host = cleanHost,
            port = cleanPort
        )
    }
}
