package com.example.llamadroid.service

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeepResearchSearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val query: String = ""
)

data class DeepResearchFetchedSource(
    val finalUrl: String,
    val title: String,
    val text: String,
    val contentType: String
)

data class DeepResearchScore(
    val score: Int,
    val reason: String,
    val skip: Boolean = false
)

object DeepResearchSupport {
    const val DEFAULT_SOURCE_LIMIT = 20
    const val MIN_SOURCE_LIMIT = 1
    const val MAX_SOURCE_TEXT_CHARS = 120_000
    const val MIN_IMPORT_SCORE = 32
    const val MIN_FALLBACK_IMPORT_SCORE = 24
    private const val MAX_HTML_FETCH_BYTES = 4_000_000L
    private const val MAX_PDF_FETCH_BYTES = 32_000_000L
    private const val MIN_READABLE_CHARS = 600
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private val DUCK_RESULT_PATTERN =
        Regex("""<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LINK_PATTERN =
        Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TITLE_PATTERN =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_PATTERN = Regex("<[^>]+>")
    private val HTTP_URL_PATTERN = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

    fun normalizeSourceLimit(value: Int?): Int =
        (value ?: DEFAULT_SOURCE_LIMIT).coerceAtLeast(MIN_SOURCE_LIMIT)

    fun buildQueryVariants(
        query: String,
        focus: String? = null,
        sourceLimit: Int = DEFAULT_SOURCE_LIMIT
    ): List<String> {
        val base = listOfNotNull(query.trim(), focus?.trim()?.takeIf { it.isNotBlank() })
            .distinct()
            .joinToString(" ")
            .trim()
        if (base.isBlank()) return emptyList()
        val coreVariants = listOf(
            base,
            "$base systematic review",
            "$base clinical guideline",
            "$base review article",
            "$base filetype:pdf",
            "$base site:pubmed.ncbi.nlm.nih.gov",
            "$base site:pmc.ncbi.nlm.nih.gov",
            "$base site:.gov",
            "$base site:.edu",
            "$base professional society guideline",
            "$base DOI"
        )
        val evidenceTerms = listOf(
            "meta analysis",
            "evidence based",
            "position statement",
            "technical report",
            "best practice",
            "expert consensus",
            "practice guideline",
            "white paper",
            "research article",
            "full text PDF"
        )
        val trustedSites = listOf(
            "site:nih.gov",
            "site:who.int",
            "site:nice.org.uk",
            "site:ahrq.gov",
            "site:cochranelibrary.com",
            "site:sciencedirect.com",
            "site:springer.com",
            "site:nature.com",
            "site:bmj.com",
            "site:thelancet.com",
            "site:nejm.org",
            "site:jamanetwork.com"
        )
        val targetVariantCount = (normalizeSourceLimit(sourceLimit) * 2).coerceAtLeast(coreVariants.size)
        return sequence {
            coreVariants.forEach { yield(it) }
            evidenceTerms.forEach { term -> yield("$base $term") }
            trustedSites.forEach { site -> yield("$base $site") }
            trustedSites.forEach { site ->
                evidenceTerms.forEach { term ->
                    yield("$base $site $term")
                }
            }
        }.distinct().take(targetVariantCount).toList()
    }

    fun maxResultsPerQuery(sourceLimit: Int): Int =
        (normalizeSourceLimit(sourceLimit) / 2).coerceAtLeast(10)

    fun shouldImportScore(score: DeepResearchScore): Boolean =
        !score.skip && score.score >= MIN_IMPORT_SCORE

    fun shouldFallbackImportScore(score: DeepResearchScore): Boolean =
        !score.skip && score.score >= MIN_FALLBACK_IMPORT_SCORE

    suspend fun searchWeb(
        client: OkHttpClient = defaultClient(),
        query: String,
        maxResults: Int = 10
    ): List<DeepResearchSearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val url = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val html = response.body?.string().orEmpty()
            parseSearchResultsHtml(html, cleanQuery, maxResults)
        }
    }

    internal fun parseSearchResultsHtml(
        html: String,
        query: String,
        maxResults: Int = 10
    ): List<DeepResearchSearchResult> {
        val limit = maxResults.coerceAtLeast(1)
        val primary = DUCK_RESULT_PATTERN.findAll(html)
            .mapNotNull { match ->
                searchResultFromLink(match.groupValues[1], match.groupValues[2], query)
            }
            .distinctBy { normalizeUrl(it.url) }
            .take(limit)
            .toList()
        if (primary.isNotEmpty()) return primary

        return LINK_PATTERN.findAll(html)
            .mapNotNull { match ->
                searchResultFromLink(match.groupValues[1], match.groupValues[2], query)
            }
            .filter { isImportableSearchUrl(it.url) }
            .distinctBy { normalizeUrl(it.url) }
            .take(limit)
            .toList()
    }

    fun extractHttpUrls(value: String): List<String> =
        HTTP_URL_PATTERN.findAll(value)
            .map { it.value.trimEnd('.', ',', ';', ':') }
            .filter { isImportableSearchUrl(it) }
            .distinctBy(::normalizeUrl)
            .toList()

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching trimmed
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port${path.trimEnd('/')}$query"
        }.getOrDefault(trimmed.trimEnd('/'))
    }

    fun scoreCandidate(
        query: String,
        title: String,
        url: String,
        readableText: String,
        contentType: String = ""
    ): DeepResearchScore {
        val normalizedUrl = normalizeUrl(url).lowercase(Locale.US)
        val lowerTitle = title.lowercase(Locale.US)
        val lowerText = readableText.lowercase(Locale.US)
        val domain = runCatching { URI(normalizedUrl).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        if (isSkippedDomain(domain)) {
            return DeepResearchScore(0, "social/forum/shopping source skipped", skip = true)
        }
        if (readableText.trim().length < MIN_READABLE_CHARS) {
            return DeepResearchScore(10, "not enough readable text", skip = true)
        }

        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, reason: String) {
            score += points
            reasons += reason
        }

        if ("pubmed.ncbi.nlm.nih.gov" in domain || "pmc.ncbi.nlm.nih.gov" in domain) add(45, "PubMed/PMC")
        if ("doi.org" in domain || "doi:" in lowerText || "journal" in lowerText) add(38, "journal/DOI")
        if ("systematic review" in lowerTitle || "systematic review" in lowerText) add(35, "systematic review")
        if ("guideline" in lowerTitle || "guideline" in lowerText || "consensus" in lowerTitle) add(32, "guideline")
        if (domain.endsWith(".gov")) add(30, "government")
        if (domain.endsWith(".edu") || ".edu." in domain) add(28, "university")
        if (domain.endsWith(".org")) add(18, "organization")
        if (normalizedUrl.endsWith(".pdf") || "pdf" in contentType.lowercase(Locale.US)) add(16, "PDF")

        val terms = query.lowercase(Locale.US)
            .split(Regex("""[^a-z0-9áéíóúüñ]+"""))
            .filter { it.length >= 4 }
            .distinct()
        if (terms.isNotEmpty()) {
            val titleHits = terms.count { it in lowerTitle }
            val urlHits = terms.count { it in normalizedUrl }
            val textHits = terms.count { it in lowerText }
            val relevance = ((titleHits * 4 + urlHits * 2 + textHits).toFloat() / terms.size.toFloat() * 12f)
                .toInt()
                .coerceIn(0, 30)
            add(relevance, "relevance")
        }

        return DeepResearchScore(score, reasons.joinToString(", ").ifBlank { "readable source" })
    }

    suspend fun fetchReadableSource(
        client: OkHttpClient = defaultClient(),
        url: String,
        maxChars: Int = MAX_SOURCE_TEXT_CHARS,
        pdfTextExtractor: (ByteArray, Int) -> String = ::extractNativePdfTextFromBytes
    ): DeepResearchFetchedSource {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: error("Only HTTP and HTTPS URLs can be imported.")
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "URL returned HTTP ${response.code}." }
            val body = response.body ?: error("URL returned an empty body.")
            val contentType = body.contentType()?.toString().orEmpty()
            val finalUrl = response.request.url.toString()
            if (contentType.contains("pdf", ignoreCase = true) || finalUrl.lowercase(Locale.US).endsWith(".pdf")) {
                val bytes = readBodyBytes(body, MAX_PDF_FETCH_BYTES, "PDF")
                val text = pdfTextExtractor(bytes, maxChars).take(maxChars)
                return DeepResearchFetchedSource(
                    finalUrl = normalizeUrl(finalUrl),
                    title = titleFromUrl(finalUrl),
                    text = text,
                    contentType = contentType
                )
            }
            val bytes = readBodyBytes(body, MAX_HTML_FETCH_BYTES, "web page")
            val html = bytes.toString(Charsets.UTF_8)
            val title = TITLE_PATTERN.find(html)?.groupValues?.getOrNull(1)?.let(::stripHtml)?.trim()
                ?.ifBlank { null }
                ?: titleFromUrl(finalUrl)
            val text = stripHtml(html)
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(maxChars)
            return DeepResearchFetchedSource(
                finalUrl = normalizeUrl(finalUrl),
                title = title,
                text = text,
                contentType = contentType
            )
        }
    }

    private fun readBodyBytes(
        body: okhttp3.ResponseBody,
        maxBytes: Long,
        label: String
    ): ByteArray = body.source().use { source ->
        val hasMoreThanLimit = source.request(maxBytes + 1)
        require(!hasMoreThanLimit) {
            "The $label is too large to import safely (${maxBytes / 1_000_000L} MB limit)."
        }
        source.readByteArray()
    }

    fun titleFromUrl(url: String): String =
        runCatching {
            URI(url).path.substringAfterLast('/').ifBlank { URI(url).host.orEmpty() }
        }.getOrDefault(url)

    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private fun decodeDuckDuckGoUrl(url: String): String {
        val absoluteUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://duckduckgo.com$url"
            else -> url
        }
        val parsed = absoluteUrl.toHttpUrlOrNull()
        val uddg = parsed?.queryParameter("uddg")
        return if (!uddg.isNullOrBlank()) URLDecoder.decode(uddg, "UTF-8") else absoluteUrl
    }

    private fun searchResultFromLink(rawHref: String, rawTitle: String, query: String): DeepResearchSearchResult? {
        val rawUrl = decodeDuckDuckGoUrl(htmlDecode(rawHref))
        val title = stripHtml(rawTitle).trim()
        return if (rawUrl.isBlank() || title.isBlank()) {
            null
        } else {
            DeepResearchSearchResult(
                title = title,
                url = rawUrl,
                query = query
            )
        }
    }

    private fun isImportableSearchUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US)
        if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
        return parsed.scheme == "http" || parsed.scheme == "https"
    }

    private fun htmlDecode(value: String): String =
        value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun stripHtml(value: String): String =
        htmlDecode(value)
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(TAG_PATTERN, " ")

    private fun isSkippedDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        return listOf(
            "facebook.com",
            "x.com",
            "twitter.com",
            "instagram.com",
            "tiktok.com",
            "reddit.com",
            "quora.com",
            "pinterest.com",
            "amazon.",
            "ebay.",
            "mercadolibre."
        ).any { it in domain }
    }
}
