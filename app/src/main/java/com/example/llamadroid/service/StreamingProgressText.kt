package com.example.llamadroid.service

/**
 * Builds the foreground-notification progress line. The model text is concatenated, never part of
 * a format template, because generated text (CSS such as `100%;`) would break `String.format`.
 */
internal fun streamingProgressText(tokenCount: Int, tps: Double, context: String): String {
    val words = context.takeLast(512).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val lastWords = words.takeLast(7).joinToString(" ")
    val head = "%d tok · %.1f t/s".format(tokenCount, tps)
    return if (lastWords.isBlank()) head else "$head | ...$lastWords"
}
