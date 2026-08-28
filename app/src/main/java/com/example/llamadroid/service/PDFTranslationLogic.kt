package com.example.llamadroid.service

import com.example.llamadroid.data.PdfTranslationQualityMode
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class PdfOcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0
}

data class PdfMappedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

object PDFTranslationLogic {
    const val CUSTOM_LANGUAGE_VALUE = "__custom__"
    const val DEFAULT_TRANSLATION_SYSTEM_PROMPT =
        "You are a precise document translator. Output only the translation and nothing else."
    const val DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT =
        "You are a professional manga, comic, and PDF localization editor. Reconstruct meaning from page context, produce natural concise dialogue in the requested language, and return only strict JSON."
    const val DEFAULT_TRANSLATION_CORRECTOR_SYSTEM_PROMPT =
        "You are a strict translation editor. Review translated comic/PDF text and return only JSON fixes."

    val commonTargetLanguages = listOf(
        "Spanish",
        "English",
        "Portuguese (Brazil)",
        "French",
        "German",
        "Italian",
        "Japanese",
        "Korean",
        "Chinese (Simplified)"
    )

    fun defaultTranslationLanguageForAppLanguage(appLanguage: String?): String {
        return when (appLanguage?.lowercase(Locale.US)) {
            "en" -> "English"
            "es" -> "Spanish"
            else -> "Spanish"
        }
    }

    fun mapBitmapBoxToPdfRect(
        box: PdfOcrBox,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): PdfMappedRect {
        val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
        val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)
        val left = box.left.coerceIn(0, safeBitmapWidth)
        val right = box.right.coerceIn(0, safeBitmapWidth)
        val top = box.top.coerceIn(0, safeBitmapHeight)
        val bottom = box.bottom.coerceIn(0, safeBitmapHeight)
        val x = left.toFloat() / safeBitmapWidth.toFloat() * pdfWidth
        val y = pdfHeight - (bottom.toFloat() / safeBitmapHeight.toFloat() * pdfHeight)
        val width = (right - left).coerceAtLeast(0).toFloat() / safeBitmapWidth.toFloat() * pdfWidth
        val height = (bottom - top).coerceAtLeast(0).toFloat() / safeBitmapHeight.toFloat() * pdfHeight
        return PdfMappedRect(x = x, y = y, width = width, height = height)
    }

    fun mapTextLayerBoxToPdfRect(
        x: Float,
        yFromTop: Float,
        width: Float,
        height: Float,
        pageHeight: Float
    ): PdfMappedRect {
        return PdfMappedRect(
            x = x.coerceAtLeast(0f),
            y = (pageHeight - yFromTop - height).coerceAtLeast(0f),
            width = width.coerceAtLeast(0f),
            height = height.coerceAtLeast(0f)
        )
    }

    fun buildTranslationUserPrompt(text: String, targetLanguage: String): String {
        return buildString {
            appendLine("Translate the following text to $targetLanguage.")
            appendLine("Preserve the original meaning, tone, names, punctuation, and paragraph intent.")
            appendLine("Output only the translated text. Do not add notes, explanations, quotes, labels, or Markdown.")
            appendLine()
            append(text.trim())
        }.trim()
    }

    data class PageTranslationBlock(
        val id: String,
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val readingOrder: Int,
        val sourceBlockCount: Int,
        val sourceLineCount: Int,
        val sourceLines: List<String> = emptyList()
    )

    data class PartialPageTranslationParse(
        val translations: LinkedHashMap<String, String>,
        val missingIds: Set<String>,
        val parseError: String?
    )

    data class TranslationCorrectionEntry(
        val id: String,
        val sourceText: String,
        val translatedText: String,
        val pageNumber: Int
    )

    fun buildPageTranslationUserPrompt(
        targetLanguage: String,
        pageNumber: Int,
        totalPages: Int,
        blocks: List<PageTranslationBlock>,
        hasImageContext: Boolean,
        completedTranslations: Map<String, String> = emptyMap(),
        chunkIndex: Int = 1,
        totalChunks: Int = 1,
        totalPageBlocks: Int = blocks.size,
        pageContext: String? = null,
        qualityMode: PdfTranslationQualityMode = PdfTranslationQualityMode.BALANCED,
        readingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT,
        continuityContext: String? = null
    ): String {
        return buildString {
            appendLine("Translate this PDF/comic page to $targetLanguage.")
            appendLine("Use the provided blocks together as page context so names, jokes, tone, nearby dialogue, sound effects, and panel order make sense.")
            appendLine("Translate ideas and dialogue naturally as a professional $targetLanguage comic localization; do not mirror awkward source word order or produce dictionary-style fragments.")
            appendLine("Silently repair an obvious OCR typo only when neighboring blocks and normal grammar make the intended wording clear. Never invent missing dialogue.")
            appendLine("If a block is too corrupted to reconstruct confidently, return its exact source text so the renderer can preserve the original artwork.")
            appendLine(
                when (readingDirection) {
                    MangaReadingDirection.RIGHT_TO_LEFT ->
                        "The resolved page reading direction is right-to-left. Respect the supplied reading-order numbers."
                    MangaReadingDirection.LEFT_TO_RIGHT ->
                        "The resolved page reading direction is left-to-right. Respect the supplied reading-order numbers."
                    MangaReadingDirection.AUTO ->
                        "Respect the supplied reading-order numbers."
                }
            )
            appendLine("Each listed ID is one complete physical bubble, caption, or tight text region. Translate every source fragment inside that ID as one coherent unit.")
            appendLine("For Japanese manga, localize naturally into $targetLanguage instead of translating word-by-word. Keep names consistent, infer omitted pronouns from page context, and preserve emotional punctuation.")
            if (qualityMode != PdfTranslationQualityMode.FASTER) {
                appendLine("Do not omit, move, or exchange words, clauses, or punchlines between region IDs.")
                appendLine("Translate sound effects only when a natural equivalent exists; otherwise keep them concise and readable.")
            }
            if (hasImageContext) {
                appendLine("A screenshot of the full page is attached. Use it only as context for speaker, placement, tone, and ambiguous OCR.")
            } else {
                appendLine("No screenshot is available. Use the IDs, reading order, and geometry as context.")
            }
            if (!pageContext.isNullOrBlank()) {
                appendLine()
                appendLine("Page understanding context:")
                appendLine(pageContext.trim())
            }
            if (!continuityContext.isNullOrBlank()) {
                appendLine()
                appendLine("Bounded continuity from nearby pages:")
                appendLine(continuityContext.trim())
            }
            if (totalChunks > 1) {
                appendLine("This is chunk $chunkIndex of $totalChunks for the same page. Translate only the listed block IDs, but keep page-level consistency.")
            }
            appendLine("Return only a valid JSON object whose keys are exactly the block IDs and whose values are only the translated text.")
            appendLine("Do not add Markdown, explanations, extra keys, nested objects, arrays, comments, or surrounding prose.")
            appendLine("Every listed id must appear exactly once. Its translation must cover every grouped source line shown for that region.")
            appendLine("Each ID is an independent paintable region. Never combine, split, rename, or reorder region IDs, even when nearby dialogue forms one sentence.")
            appendLine("Do not copy the source text unless it is already correct $targetLanguage, a name, or an intentional sound effect.")
            appendLine("Keep short bubble text short enough to fit the source bubble; prefer natural compact phrasing over literal word order.")
            appendLine("Credits, page numbers, names, and already-correct target-language text should remain unchanged.")
            appendLine("Preserve meaning, names, punctuation intent, and short comic-style phrasing.")
            appendLine("Use reading order for context only; keep every bubble or caption in its own supplied ID.")
            appendLine()
            appendLine("Page: $pageNumber / $totalPages")
            appendLine("Page blocks in this request: ${blocks.size} / $totalPageBlocks")
            if (completedTranslations.isNotEmpty()) {
                appendLine("Already translated nearby blocks from this page chunk:")
                completedTranslations.forEach { (id, translatedText) ->
                    appendLine("- id=$id; translation=${JSONObject.quote(translatedText)}")
                }
                appendLine()
            }
            appendLine("Blocks:")
            blocks.forEach { block ->
                appendLine(
                    """- id=${block.id}; order=${block.readingOrder}; sourceBlocks=${block.sourceBlockCount}; sourceLines=${block.sourceLineCount}; rect={x:${"%.2f".format(Locale.US, block.x)},y:${"%.2f".format(Locale.US, block.y)},w:${"%.2f".format(Locale.US, block.width)},h:${"%.2f".format(Locale.US, block.height)}}; text=${JSONObject.quote(block.text)}"""
                )
                if (block.sourceLines.size > 1) {
                    appendLine("  groupedSourceLines=${block.sourceLines.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }}")
                }
            }
            appendLine()
            appendLine("""Required JSON shape: {"${blocks.firstOrNull()?.id ?: "block_1"}":"translated text"}""")
        }.trim()
    }

    fun buildPageUnderstandingPrompt(
        targetLanguage: String,
        pageNumber: Int,
        totalPages: Int,
        blocks: List<PageTranslationBlock>,
        hasImageContext: Boolean,
        previousPageContext: String?
    ): String {
        return buildString {
            appendLine("Analyze this manga/comic/PDF page before translation.")
            appendLine("Return a compact plain-text context note, not JSON.")
            appendLine("Identify likely speakers, scene/tone, reading-order hints, recurring names, ambiguous OCR, and how fragmented bubbles should read naturally in $targetLanguage.")
            appendLine("Keep it under 120 words. Do not translate every block yet.")
            appendLine("Page: $pageNumber / $totalPages")
            if (!previousPageContext.isNullOrBlank()) {
                appendLine("Previous page context: ${previousPageContext.trim()}")
            }
            appendLine(if (hasImageContext) "A page screenshot is attached." else "No page screenshot is available.")
            appendLine("Blocks:")
            blocks.forEach { block ->
                appendLine("""- id=${block.id}; order=${block.readingOrder}; text=${JSONObject.quote(block.text)}""")
            }
        }.trim()
    }

    fun buildPageTranslationRepairPrompt(
        targetLanguage: String,
        blocks: List<PageTranslationBlock>,
        malformedOutput: String,
        completedTranslations: Map<String, String> = emptyMap()
    ): String {
        return buildString {
            appendLine("Repair the translation output into strict JSON for $targetLanguage.")
            appendLine("Return only a JSON object. Keys must be exactly these block IDs:")
            appendLine(blocks.joinToString(", ") { it.id })
            appendLine("Translate any missing, weak, or incomplete entries. Each ID is one complete physical region, so its value must cover every grouped source line without moving text to another ID.")
            appendLine("Do not copy the original block text unless it is already correct $targetLanguage, a name, or an intentional sound effect.")
            appendLine("Use nearby finalized translations to keep names, speaker flow, tone, and short bubble phrasing consistent.")
            if (completedTranslations.isNotEmpty()) {
                appendLine("These nearby translations are already finalized and must stay consistent:")
                completedTranslations.forEach { (id, translatedText) ->
                    appendLine("$id: ${JSONObject.quote(translatedText)}")
                }
            }
            blocks.forEach { block ->
                appendLine("${block.id}: ${block.text}")
                if (block.sourceLines.size > 1) {
                    appendLine(
                        "groupedSourceLines=" +
                            block.sourceLines.joinToString(prefix = "[", postfix = "]") {
                                JSONObject.quote(it)
                            }
                    )
                }
            }
            appendLine()
            appendLine("Malformed output:")
            append(malformedOutput.trim())
        }.trim()
    }

    fun buildTranslationCorrectionPrompt(
        targetLanguage: String,
        entries: List<TranslationCorrectionEntry>,
        pageContexts: Map<Int, String> = emptyMap(),
        qualityMode: PdfTranslationQualityMode = PdfTranslationQualityMode.BALANCED
    ): String {
        return buildString {
            appendLine("Review these completed translations to $targetLanguage.")
            appendLine("Check whether each translation preserves meaning, tone, names, pronouns, punctuation intent, and natural comic/PDF phrasing.")
            appendLine("Rewrite literal, robotic, or disconnected phrasing as concise professional comic localization. Correct only obvious OCR mistakes supported by surrounding context; never invent dialogue.")
            if (qualityMode != PdfTranslationQualityMode.FASTER) {
                appendLine("Review dialogue flow across nearby entries on the same page. Fix entries that sound isolated, literal, inconsistent, or disconnected from the bubble context.")
            }
            appendLine("Fix entries that leave source-language text untranslated, drop a listed ID's meaning, over-expand a short bubble, or sound like isolated fragments.")
            appendLine("Return only a strict JSON object with fixes for entries that need changes.")
            appendLine("Use the block ID as the key and the improved translation as the value.")
            appendLine("If no fixes are needed, return exactly {}.")
            appendLine("Do not include unchanged entries, notes, Markdown, explanations, nested objects, or arrays.")
            appendLine()
            pageContexts.toSortedMap().forEach { (page, context) ->
                if (context.isNotBlank()) {
                    appendLine("Page $page context: ${context.trim()}")
                }
            }
            if (pageContexts.isNotEmpty()) appendLine()
            entries.forEach { entry ->
                appendLine("id=${entry.id}; page=${entry.pageNumber}")
                appendLine("source=${JSONObject.quote(entry.sourceText)}")
                appendLine("translation=${JSONObject.quote(entry.translatedText)}")
                appendLine()
            }
        }.trim()
    }

    fun modelNameLikelySupportsVision(modelName: String?): Boolean {
        val normalized = modelName
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return false
        return listOf(
            "vision",
            "llava",
            "bakllava",
            "moondream",
            "minicpm-v",
            "qwen-vl",
            "qwen2-vl",
            "qwen2.5-vl",
            "gemma-3",
            "gemma3",
            "gemma-4",
            "gemma4",
            "vl-"
        ).any { token -> token in normalized }
    }

    fun isWeakPageTranslation(
        sourceText: String,
        translatedText: String,
        targetLanguage: String = "Spanish"
    ): Boolean {
        val source = sourceText.trim()
        val translated = translatedText.trim()
        if (translated.isBlank()) return true
        val compactSource = source.filterNot(Char::isWhitespace)
        val compactTranslated = translated.filterNot(Char::isWhitespace)
        if (compactSource.length >= 2 && compactSource.equals(compactTranslated, ignoreCase = true)) {
            return true
        }
        return containsCjk(source) &&
            containsCjk(translated) &&
            !targetLanguageAllowsCjk(targetLanguage) &&
            compactTranslated.length >= 2
    }

    private fun targetLanguageAllowsCjk(targetLanguage: String): Boolean {
        val normalized = targetLanguage.lowercase(Locale.US)
        return listOf(
            "japanese",
            "japonés",
            "japones",
            "korean",
            "coreano",
            "chinese",
            "chino",
            "mandarin",
            "cantonese"
        ).any { it in normalized }
    }

    private fun containsCjk(value: String): Boolean =
        value.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO
        }

    fun buildTranslationFixesRepairPrompt(malformedOutput: String): String {
        return buildString {
            appendLine("Repair this output into a strict JSON object of translation fixes.")
            appendLine("Return {} if there are no fixes.")
            appendLine("Return only JSON, with block IDs as keys and fixed translations as string values.")
            appendLine()
            append(malformedOutput.trim())
        }.trim()
    }

    fun buildSingleUnitPlainTextPrompt(
        targetLanguage: String,
        block: PageTranslationBlock
    ): String = buildString {
        appendLine("Translate the following text into $targetLanguage.")
        appendLine("Write natural, concise comic dialogue rather than a literal word-for-word rendering.")
        appendLine("Correct only an obvious OCR typo; if the text is too corrupted to understand confidently, return it unchanged.")
        appendLine("Return only the translated text. Do not return JSON, labels, notes, or quotation marks.")
        appendLine()
        append(block.text.trim())
    }.trim()

    fun parsePageTranslationJson(output: String, expectedIds: Set<String>): Map<String, String> {
        val obj = extractJsonObject(output)
        val parsed = linkedMapOf<String, String>()
        expectedIds.forEach { id ->
            val value = obj.optString(id, "").trim()
            if (value.isNotBlank()) {
                parsed[id] = value
            }
        }
        require(parsed.keys.containsAll(expectedIds)) { "translation_json_missing_ids" }
        return parsed
    }

    fun parsePartialPageTranslationJson(output: String, expectedIds: Set<String>): PartialPageTranslationParse {
        return runCatching {
            PartialPageTranslationParse(
                translations = LinkedHashMap(parsePageTranslationJson(output, expectedIds)),
                missingIds = emptySet(),
                parseError = null
            )
        }.getOrElse { error ->
            val recovered = extractFlexibleTranslations(output, expectedIds)
            PartialPageTranslationParse(
                translations = recovered,
                missingIds = expectedIds - recovered.keys,
                parseError = error.message
            )
        }
    }

    fun parseOptionalTranslationFixesJson(output: String): Map<String, String> {
        val obj = extractJsonObject(output)
        return buildMap {
            obj.keys().forEach { key ->
                val value = obj.optString(key, "").trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    put(key, value)
                }
            }
        }
    }

    fun isIncompleteMultiFragmentTranslation(
        block: PageTranslationBlock,
        translatedText: String,
        targetLanguage: String
    ): Boolean {
        if (block.sourceLineCount <= 1 || block.sourceLines.count { it.isNotBlank() } <= 1) return false
        val source = block.text.trim()
        val translated = translatedText.trim()
        if (source.isBlank() || translated.isBlank()) return true
        val sourceCharacters = source.count(Char::isLetterOrDigit)
        val translatedCharacters = translated.count(Char::isLetterOrDigit)
        if (sourceCharacters < 18) return false
        val targetIsCjk = targetLanguage.lowercase(Locale.ROOT).let { language ->
            "japanese" in language || "chinese" in language || "korean" in language
        }
        val minimumCharacterRatio = if (targetIsCjk) 0.14f else 0.30f
        if (translatedCharacters < sourceCharacters * minimumCharacterRatio) return true

        val sourceHasMostlyLatinWords = source.count { it in 'A'..'Z' || it in 'a'..'z' } >= sourceCharacters * 0.55f
        if (!targetIsCjk && sourceHasMostlyLatinWords) {
            val sourceWords = source.split(Regex("""\s+""")).count { token ->
                token.count(Char::isLetterOrDigit) >= 2
            }
            val translatedWords = translated.split(Regex("""\s+""")).count { token ->
                token.count(Char::isLetterOrDigit) >= 2
            }
            if (sourceWords >= 6 && translatedWords < sourceWords * 0.55f) return true
        }
        return false
    }

    private fun extractJsonObject(output: String): JSONObject {
        val cleaned = cleanTranslationOutput(output)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd >= jsonStart) { "translation_json_missing_object" }
        return JSONObject(cleaned.substring(jsonStart, jsonEnd + 1))
    }

    fun cleanTranslationOutput(output: String): String {
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(output).trim()
        if (cleaned.length >= 2) {
            val first = cleaned.first()
            val last = cleaned.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return cleaned.substring(1, cleaned.length - 1).trim()
            }
        }
        return cleaned
    }

    fun estimateTranslationMaxTokens(sourceText: String, configuredMaxTokens: Int, entryCount: Int = 1): Int {
        val sourceTokens = PDFSummaryLogic.approximateTokens(sourceText).coerceAtLeast(16)
        val structuralTokens = (entryCount.coerceAtLeast(1) * 22)
        val estimated = ceil((sourceTokens + structuralTokens) * 2.55).toInt()
        return estimated.coerceAtLeast(64).coerceAtMost(configuredMaxTokens.coerceAtLeast(64))
    }

    private fun extractCompleteAssignments(output: String, expectedIds: Set<String>): LinkedHashMap<String, String> {
        val cleaned = cleanTranslationOutput(output)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val recovered = linkedMapOf<String, String>()
        expectedIds.forEach { id ->
            val pattern = Regex(
                "\"${Regex.escape(id)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val match = pattern.find(cleaned) ?: return@forEach
            val value = decodeJsonString(match.groupValues[1]).trim()
            if (value.isNotBlank()) {
                recovered[id] = value
            }
        }
        return recovered
    }

    private fun extractFlexibleTranslations(
        output: String,
        expectedIds: Set<String>
    ): LinkedHashMap<String, String> {
        val recovered = extractCompleteAssignments(output, expectedIds)
        val cleaned = cleanTranslationOutput(output)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val parsed = runCatching { JSONTokener(cleaned).nextValue() }.getOrNull()
            ?: runCatching {
                val objectStart = cleaned.indexOf('{')
                val objectEnd = cleaned.lastIndexOf('}')
                if (objectStart >= 0 && objectEnd >= objectStart) {
                    JSONTokener(cleaned.substring(objectStart, objectEnd + 1)).nextValue()
                } else {
                    null
                }
            }.getOrNull()

        fun accept(id: String?, value: Any?) {
            val expectedId = id?.takeIf { it in expectedIds } ?: return
            val text = when (value) {
                is String -> value
                JSONObject.NULL, null -> ""
                else -> value.toString()
            }.trim()
            if (text.isNotBlank()) recovered[expectedId] = text
        }

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    expectedIds.forEach { id ->
                        if (value.has(id)) accept(id, value.opt(id))
                    }
                    val itemId = sequenceOf("id", "block_id", "blockId", "key")
                        .mapNotNull { key -> value.optString(key).takeIf(String::isNotBlank) }
                        .firstOrNull()
                    val itemTranslation = sequenceOf("translation", "translated_text", "translatedText", "text", "value")
                        .mapNotNull { key -> value.optString(key).takeIf(String::isNotBlank) }
                        .firstOrNull()
                    accept(itemId, itemTranslation)
                    sequenceOf("translations", "translation", "results", "items", "data")
                        .filter(value::has)
                        .forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) visit(value.opt(index))
                }
            }
        }
        visit(parsed)

        if (expectedIds.size == 1 && recovered.isEmpty()) {
            val onlyId = expectedIds.first()
            val singleValue = when (parsed) {
                is String -> parsed
                is JSONObject -> {
                    val values = parsed.keys().asSequence()
                        .mapNotNull { key -> parsed.optString(key).takeIf(String::isNotBlank) }
                        .toList()
                    values.singleOrNull()
                }
                is JSONArray -> if (parsed.length() == 1) parsed.optString(0) else null
                else -> cleaned.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            }
            accept(onlyId, singleValue)
        }
        return recovered
    }

    private fun decodeJsonString(value: String): String {
        return (JSONTokener("\"$value\"").nextValue() as? String).orEmpty()
    }

    fun naturalSortKey(value: String): List<String> {
        return Regex("""\d+|\D+""").findAll(value.lowercase(Locale.US))
            .map { match ->
                val part = match.value
                part.toLongOrNull()?.toString()?.padStart(12, '0') ?: part
            }
            .toList()
    }
}
