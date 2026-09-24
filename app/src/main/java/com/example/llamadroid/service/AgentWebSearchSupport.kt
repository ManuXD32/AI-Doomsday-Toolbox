package com.example.llamadroid.service

import java.net.URI
import java.net.URLDecoder

internal const val MAX_SEARCH_HTML_CHARS = 300_000
internal const val MAX_APP_WEB_SEARCH_RESULTS = 5
internal const val MAX_APP_WEB_SEARCH_QUERY_CHARS = 240
internal const val APP_WEB_SEARCH_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

internal data class DuckDuckGoSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
) {
    fun citation(): String {
        val label = title.replace("[", "\\[").replace("]", "\\]")
        val safeUrl = url.replace("(", "%28").replace(")", "%29")
        return "[$label]($safeUrl)"
    }
}

private val duckDuckGoResultLinkPattern = Regex(
    """<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val duckDuckGoResultSnippetPattern = Regex(
    """<(?:a|div|span)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div|span)>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val duckDuckGoDivTagPattern = Regex("""<\s*(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
private val htmlClassAttributePattern = Regex("""\bclass\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

private data class DuckDuckGoLinkCandidate(
    val url: String?,
    val title: String,
)

private data class DuckDuckGoResultContainers(
    val found: Boolean,
    val rows: List<String>,
)

/**
 * Parses only the bounded DuckDuckGo result document. It never fetches result URLs, so snippets
 * remain untrusted model input and citations cannot trigger an Android-side request to a target.
 */
internal fun parseDuckDuckGoSearchResults(html: String, maxResults: Int): List<DuckDuckGoSearchResult> {
    if (html.length > MAX_SEARCH_HTML_CHARS) return emptyList()
    val containers = extractDuckDuckGoResultContainers(html)
    val results = if (containers.found) {
        containers.rows.mapNotNull(::parseDuckDuckGoResultRow)
    } else {
        // Keep support for small/legacy fixtures and alternate DDG responses without result
        // wrappers. Preserve each link's original ordinal until after pairing its snippet so an
        // unsafe link cannot shift the snippet attached to a later safe citation.
        parseUnwrappedDuckDuckGoResults(html)
    }
    return results.distinctBy { it.url }
        .take(maxResults.coerceIn(1, MAX_APP_WEB_SEARCH_RESULTS))
}

private fun parseDuckDuckGoResultRow(row: String): DuckDuckGoSearchResult? {
    val link = duckDuckGoResultLinkPattern.find(row) ?: return null
    val url = resolveDuckDuckGoResultUrl(link.groupValues[1]) ?: return null
    val title = cleanSearchMarkup(link.groupValues[2]).take(240).ifBlank { return null }
    val snippet = duckDuckGoResultSnippetPattern.find(row)
        ?.groupValues
        ?.get(1)
        ?.let(::cleanSearchMarkup)
        ?.take(500)
        .orEmpty()
    return DuckDuckGoSearchResult(title, url, snippet)
}

private fun parseUnwrappedDuckDuckGoResults(html: String): List<DuckDuckGoSearchResult> {
    val links = duckDuckGoResultLinkPattern.findAll(html).map { match ->
        DuckDuckGoLinkCandidate(
            url = resolveDuckDuckGoResultUrl(match.groupValues[1]),
            title = cleanSearchMarkup(match.groupValues[2]).take(240),
        )
    }.toList()
    val snippets = duckDuckGoResultSnippetPattern.findAll(html)
        .map { cleanSearchMarkup(it.groupValues[1]).take(500) }
        .toList()
    return links.mapIndexedNotNull { index, candidate ->
        val url = candidate.url ?: return@mapIndexedNotNull null
        if (candidate.title.isBlank()) return@mapIndexedNotNull null
        DuckDuckGoSearchResult(candidate.title, url, snippets.getOrNull(index).orEmpty())
    }
}

/**
 * DDG's HTML groups each result link and snippet under an outer `div.result`, with nested divs
 * inside it. Scan div nesting in the already size-bounded document instead of using a non-greedy
 * regex that would stop at the first inner closing div.
 */
private fun extractDuckDuckGoResultContainers(html: String): DuckDuckGoResultContainers {
    val scanHtml = html
        .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style\\b[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
    val rows = mutableListOf<String>()
    var found = false
    var rowStart = -1
    var divDepth = 0

    duckDuckGoDivTagPattern.findAll(scanHtml).forEach { match ->
        val isClosingTag = match.groupValues[1].isNotEmpty()
        if (rowStart < 0) {
            if (!isClosingTag && hasDuckDuckGoResultClass(match.value)) {
                found = true
                rowStart = match.range.first
                divDepth = 1
            }
        } else if (isClosingTag) {
            divDepth -= 1
            if (divDepth == 0) {
                rows += scanHtml.substring(rowStart, match.range.last + 1)
                rowStart = -1
            }
        } else {
            divDepth += 1
        }
    }
    return DuckDuckGoResultContainers(found, rows)
}

private fun hasDuckDuckGoResultClass(openingDivTag: String): Boolean {
    val classMatch = htmlClassAttributePattern.find(openingDivTag) ?: return false
    val classes = classMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
    return classes.split(Regex("\\s+")).any { it == "result" }
}

private fun resolveDuckDuckGoResultUrl(rawHref: String): String? {
    val href = decodeHtmlEntities(rawHref.trim())
    val normalizedHref = if (href.startsWith("//")) "https:$href" else href
    val uri = runCatching { URI(normalizedHref) }.getOrNull() ?: return null
    val decoded = if (uri.host.equals("duckduckgo.com", ignoreCase = true) ||
        uri.host.equals("www.duckduckgo.com", ignoreCase = true)
    ) {
        val encodedTarget = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("uddg=", ignoreCase = true) }
            ?.substringAfter('=')
            ?: return null
        runCatching { URLDecoder.decode(encodedTarget, Charsets.UTF_8.name()) }.getOrNull() ?: return null
    } else {
        normalizedHref
    }
    val target = runCatching { URI(decoded) }.getOrNull() ?: return null
    val scheme = target.scheme?.lowercase() ?: return null
    if (scheme !in setOf("http", "https") || target.userInfo != null) return null
    val host = target.host?.trim('[', ']')?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host == "localhost.localdomain" || isPrivateAddressLiteral(host)
    ) return null
    return target.toASCIIString().takeIf { it.length <= 2_048 }
}

private fun isPrivateAddressLiteral(host: String): Boolean {
    if (':' in host) {
        val address = host.substringBefore('%')
        return address == "::" || address == "::1" ||
            address.startsWith("fc", ignoreCase = true) || address.startsWith("fd", ignoreCase = true) ||
            address.startsWith("fe8", ignoreCase = true) || address.startsWith("fe9", ignoreCase = true) ||
            address.startsWith("fea", ignoreCase = true) || address.startsWith("feb", ignoreCase = true)
    }
    val octets = host.split('.').map(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val (first, second) = octets.map { requireNotNull(it) }
    return first == 0 || first == 10 || first == 127 ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) || first >= 224
}

private fun cleanSearchMarkup(value: String): String {
    // The generic agent cleaner discards HTML entities before decoding them,
    // which turns visible text such as `Docs &amp; API` into `Docs API`.
    val plainText = value
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
    return decodeHtmlEntities(plainText)
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun decodeHtmlEntities(value: String): String {
    var decoded = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
    decoded = Regex("&#(\\d+);").replace(decoded) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
    }
    return Regex("&#x([0-9a-fA-F]+);").replace(decoded) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
    }
}
