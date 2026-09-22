package com.example.llamadroid.harness

import java.net.URI

/**
 * Small, content-free helpers shared by the LAN proxy and its JVM tests.
 *
 * The query token is only a one-time browser bootstrap mechanism. The proxy
 * exchanges it for an HttpOnly cookie and never forwards it to Harness.
 */
internal object HarnessLanAccessSupport {
    const val COOKIE_NAME = "ADT_HARNESS_LAN"
    const val QUERY_PARAMETER = "adt_lan_token"

    fun isAuthorized(target: String, cookieHeader: String?, expectedToken: String): Boolean {
        val queryToken = queryParameter(target, QUERY_PARAMETER)
        if (queryToken != null && constantTimeEquals(queryToken, expectedToken)) return true
        return cookieHeader.orEmpty()
            .split(';')
            .asSequence()
            .map { it.trim() }
            .mapNotNull { cookie ->
                val separator = cookie.indexOf('=')
                if (separator <= 0) null else cookie.substring(0, separator).trim() to cookie.substring(separator + 1).trim()
            }
            .any { (name, value) -> name == COOKIE_NAME && constantTimeEquals(value, expectedToken) }
    }

    fun stripBootstrapToken(target: String): String {
        val question = target.indexOf('?')
        if (question < 0) return target
        val path = target.substring(0, question)
        val query = target.substring(question + 1)
            .split('&')
            .filter { it.isNotBlank() && !it.substringBefore('=').equals(QUERY_PARAMETER, ignoreCase = true) }
            .joinToString("&")
        return if (query.isBlank()) path else "$path?$query"
    }

    fun queryParameter(target: String, parameter: String): String? {
        val question = target.indexOf('?')
        if (question < 0) return null
        return target.substring(question + 1)
            .split('&')
            .asSequence()
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            .firstOrNull { (name, _) -> name == parameter }
            ?.second
    }

    fun bootstrapUrl(ip: String, port: Int, token: String): String =
        "http://${formatHost(ip)}:$port/?$QUERY_PARAMETER=$token"

    fun endpointHost(endpointOrigin: String): String = URI(endpointOrigin).host

    fun endpointPort(endpointOrigin: String): Int = URI(endpointOrigin).port

    /**
     * DSH's privileged routes trust same-origin browser requests. A LAN browser
     * has a different visible origin, so only rewrite Origin/Referer when they
     * match the proxy Host; cross-origin values are rejected by the proxy.
     */
    fun rewriteTrustedHeader(
        headerName: String,
        value: String,
        browserHost: String?,
        endpointOrigin: String
    ): String? {
        val visibleHost = browserHost?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val browserUri = runCatching { URI("http://$visibleHost") }.getOrNull() ?: return null
        val supplied = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!supplied.scheme.equals("http", true) || supplied.userInfo != null) return null
        if (supplied.host == null || supplied.port != browserUri.port ||
            !supplied.host.equals(browserUri.host, true)) return null
        if (headerName.equals("Origin", true)) return endpointOrigin
        val path = supplied.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        return endpointOrigin.trimEnd('/') + path
    }

    /**
     * HTTP LAN origins are not secure contexts on some WebView/browser builds.
     * Keep this narrowly scoped to the authenticated index response. The
     * settings mirror in the pinned DSH client deliberately disables Host
     * persistence for non-loopback pages; this marker is consumed only by the
     * app-patched settings bundle and is injected after LAN authorization.
     */
    fun patchIndexForLan(html: String): String {
        return HarnessWebGatewayBootstrap.patch(html)
    }

    private fun formatHost(host: String): String = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        left.indices.forEach { difference = difference or (left[it].code xor right[it].code) }
        return difference == 0
    }
}
