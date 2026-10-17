package com.example.llamadroid.ui.search

import java.text.Normalizer

/**
 * Text fields of one searchable item. [titles] weigh most (pass the English and Spanish title),
 * [keywords] next (English and Spanish) and [text] (descriptions, previews) least.
 */
data class SearchFields(
    val titles: List<String>,
    val keywords: List<String> = emptyList(),
    val text: List<String> = emptyList()
)

/**
 * Pure matching and ranking for global search. Matching is accent- and case-insensitive; every
 * query word must match somewhere in the item, and prefix matches outrank substring matches.
 */
object GlobalSearchMatcher {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    const val SCORE_TITLE_PREFIX = 1000
    const val SCORE_TITLE_WORD_PREFIX = 800
    const val SCORE_TITLE_CONTAINS = 600
    const val SCORE_KEYWORD_PREFIX = 400
    const val SCORE_KEYWORD_CONTAINS = 300
    const val SCORE_TEXT_CONTAINS = 100

    /** Lowercases, strips accents and collapses whitespace. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase()
            .replace(whitespace, " ")
            .trim()

    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /** Score of [fields] for [query]; 0 means no match (or an empty query). */
    fun score(query: String, fields: SearchFields): Int {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return 0
        val titles = fields.titles.map(::normalize).filter { it.isNotEmpty() }
        val keywords = fields.keywords.map(::normalize).filter { it.isNotEmpty() }
        val text = fields.text.map(::normalize).filter { it.isNotEmpty() }
        var total = 0
        for (token in queryTokens) {
            val tokenScore = tokenScore(token, titles, keywords, text)
            if (tokenScore == 0) return 0
            total += tokenScore
        }
        val whole = queryTokens.joinToString(" ")
        if (queryTokens.size > 1 && titles.any { it.startsWith(whole) }) total += SCORE_TITLE_PREFIX
        return total
    }

    private fun tokenScore(token: String, titles: List<String>, keywords: List<String>, text: List<String>): Int {
        var best = 0
        for (title in titles) {
            best = maxOf(
                best,
                when {
                    title.startsWith(token) -> SCORE_TITLE_PREFIX
                    title.split(' ').any { it.startsWith(token) } -> SCORE_TITLE_WORD_PREFIX
                    title.contains(token) -> SCORE_TITLE_CONTAINS
                    else -> 0
                }
            )
        }
        if (best >= SCORE_TITLE_PREFIX) return best
        for (keyword in keywords) {
            best = maxOf(
                best,
                when {
                    keyword.startsWith(token) || keyword.split(' ').any { it.startsWith(token) } -> SCORE_KEYWORD_PREFIX
                    keyword.contains(token) -> SCORE_KEYWORD_CONTAINS
                    else -> 0
                }
            )
        }
        if (best == 0 && text.any { it.contains(token) }) best = SCORE_TEXT_CONTAINS
        return best
    }

    /** Matching [items], best first; ties keep the original order. A blank query returns nothing. */
    fun <T> rank(query: String, items: List<T>, fields: (T) -> SearchFields): List<T> {
        if (tokens(query).isEmpty()) return emptyList()
        return items.mapIndexedNotNull { index, item ->
            val score = score(query, fields(item))
            if (score > 0) Triple(item, score, index) else null
        }.sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /** Adds [query] to the front of [recent] (de-duplicated ignoring case/accents), capped at [max]. */
    fun addRecent(recent: List<String>, query: String, max: Int = 8): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return recent
        val key = normalize(trimmed)
        return (listOf(trimmed) + recent.filter { normalize(it) != key }).take(max)
    }
}
