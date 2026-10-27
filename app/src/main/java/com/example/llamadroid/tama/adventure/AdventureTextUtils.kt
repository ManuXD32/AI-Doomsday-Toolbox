package com.example.llamadroid.tama.adventure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure (Android-free) helpers for the LLM text adventure: output cleanup, schematic parsing,
 * summary capping and prompt budgeting. Kept separate from [AdventureService] so they can be unit tested.
 */
internal object AdventureTextUtils {
    const val MIN_TOTAL_STAGES = 8
    const val MAX_TOTAL_STAGES = 20
    const val DEFAULT_TOTAL_STAGES = 10
    const val MAX_SUMMARY_CHARS = 1800
    const val CHARS_PER_TOKEN = 3
    private const val MIN_SCENE_CHARS = 200

    private val jsonTextKeys = listOf("content", "story", "prompt", "summary", "text")

    /**
     * Cleans model output that was already passed through the generic llama output cleaner.
     * JSON is only unwrapped when the whole reply is a JSON object; quotes inside prose are never touched.
     */
    fun cleanOutput(raw: String): String {
        var text = raw
            .replace(Regex("^```(?:json|text)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        if (text.startsWith("{") && text.endsWith("}")) {
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val extracted = obj?.let { o ->
                jsonTextKeys.firstNotNullOfOrNull { key ->
                    o.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                        ?.value
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                }
            }
            if (extracted != null) text = extracted.trim()
        }

        // Strip one pair of wrapping quotes only when the reply is a single quoted string.
        if (text.length >= 2 && text.first() == '"' && text.last() == '"' && text.count { it == '"' } == 2) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private val headerRegex = Regex(
        "^[\\s*#>_\\-•]*(TOTAL[_ ]STAGES|STORY[_ ]THREAD|KEY[_ ]EVENTS|POSSIBLE[_ ]ENDINGS|TONE|DIFFICULTY)[\\s*_]*:[\\s*_]*(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val bulletRegex = Regex("^\\s*(?:[-*•–]|\\d+[.)])\\s+")

    fun parseSchematic(response: String): StorySchematic {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (line in response.lines()) {
            val match = headerRegex.find(line)
            if (match != null) {
                current = match.groupValues[1].uppercase().replace(' ', '_')
                val rest = match.groupValues[2].trim().trim('*', '_').trim()
                sections.getOrPut(current) { mutableListOf() }
                if (rest.isNotEmpty()) sections.getValue(current).add(match.groupValues[2].trim())
            } else if (current != null) {
                sections.getValue(current).add(line)
            }
        }

        val totalStages = sections["TOTAL_STAGES"]
            ?.firstNotNullOfOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            ?: DEFAULT_TOTAL_STAGES

        val storyThread = sections["STORY_THREAD"]
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.joinToString(" ")?.trim('*', '_', ' ')
            ?.takeIf { it.isNotBlank() }
            ?: response.trim().take(300)

        val keyEvents = parseList(sections["KEY_EVENTS"]).ifEmpty { listOf("Adventure begins") }
        val possibleEndings = parseList(sections["POSSIBLE_ENDINGS"]).ifEmpty { listOf("Victory", "Defeat") }

        val tone = sections["TONE"]
            ?.map { it.trim().trim('*', '_', ' ') }?.firstOrNull { it.isNotEmpty() }
            ?.take(50)
            ?: "Dark Fantasy"

        val difficulty = sections["DIFFICULTY"]
            ?.joinToString(" ")
            ?.let { Regex("easy|medium|hard", RegexOption.IGNORE_CASE).find(it)?.value?.lowercase() }
            ?: "medium"

        return StorySchematic(
            totalStages = totalStages.coerceIn(MIN_TOTAL_STAGES, MAX_TOTAL_STAGES),
            storyThread = storyThread,
            keyEvents = keyEvents.take(5),
            possibleEndings = possibleEndings.take(3),
            tone = tone,
            difficulty = difficulty
        )
    }

    /** Line based list parsing: one item per bullet, wrapped continuation lines are joined, sentences are kept whole. */
    private fun parseList(lines: List<String>?): List<String> {
        if (lines == null) return emptyList()
        val items = mutableListOf<String>()
        var sawBullet = false
        for (raw in lines) {
            if (raw.isBlank()) continue
            val isBullet = bulletRegex.containsMatchIn(raw)
            val text = raw.replace(bulletRegex, "").trim().trim('*', '_').trim()
            if (text.isEmpty()) continue
            if (isBullet) sawBullet = true
            if (!isBullet && sawBullet && items.isNotEmpty()) {
                items[items.lastIndex] = items.last() + " " + text
            } else {
                items.add(text)
            }
        }
        return items.filter { it.length > 3 }
    }

    /** Appends [summary] to [existing] keeping the newest lines within [maxChars]. */
    fun appendCappedSummary(existing: String, summary: String, maxChars: Int = MAX_SUMMARY_CHARS): String {
        val lines = (existing.lines() + summary.trim().lines())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        return capLines(lines, maxChars)
    }

    /** Keeps the newest lines whose combined length fits [maxChars]; a single oversized line keeps its tail. */
    fun capLines(lines: List<String>, maxChars: Int): String {
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val cost = line.length + if (kept.isEmpty()) 0 else 1
            if (used + cost > maxChars) {
                if (kept.isEmpty()) kept.addFirst(line.takeLast(maxChars))
                break
            }
            kept.addFirst(line)
            used += cost
        }
        return kept.joinToString("\n")
    }

    /**
     * Characters available for the story-so-far plus recent scenes, given the backend context window.
     */
    fun historyBudgetChars(contextTokens: Int, maxOutputTokens: Int, fixedChars: Int): Int {
        val promptChars = (contextTokens - maxOutputTokens - 128).coerceAtLeast(512) * CHARS_PER_TOKEN
        return (promptChars - fixedChars).coerceAtLeast(MIN_SCENE_CHARS)
    }

    /**
     * Fits the cumulative summary and the recent scene blocks (oldest first) into [budgetChars].
     * The newest scene is kept first; older ones are dropped or truncated when the budget runs out.
     */
    fun fitHistory(summary: String, scenes: List<String>, budgetChars: Int): Pair<String, List<String>> {
        val summaryBudget = minOf(summary.length, MAX_SUMMARY_CHARS, budgetChars * 35 / 100)
        val fittedSummary = if (summary.isBlank()) "" else capLines(summary.lines().map { it.trim() }.filter { it.isNotEmpty() }, summaryBudget)
        var remaining = budgetChars - fittedSummary.length
        val fittedScenes = ArrayDeque<String>()
        for (scene in scenes.asReversed()) {
            if (remaining <= 0) break
            if (scene.length <= remaining) {
                fittedScenes.addFirst(scene)
                remaining -= scene.length + 2
            } else {
                if (remaining >= MIN_SCENE_CHARS) fittedScenes.addFirst(scene.take(remaining).trimEnd() + "...")
                break
            }
        }
        return fittedSummary to fittedScenes.toList()
    }

    /** Stable non-negative seed for all images of one adventure session. */
    fun sessionSeed(sessionId: String): Long = sessionId.hashCode().toLong() and 0x7fffffffL
}
