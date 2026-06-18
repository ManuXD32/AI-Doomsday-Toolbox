package com.example.llamadroid.service

import java.util.Locale
import kotlin.math.ceil
import org.json.JSONObject

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
        "You are a precise manga, comic, and PDF translator. Translate the page context into the requested language. Return only strict JSON."
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
        val height: Float
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
        hasImageContext: Boolean
    ): String {
        return buildString {
            appendLine("Translate this PDF/comic page to $targetLanguage.")
            appendLine("Use all blocks together as page context so names, jokes, tone, nearby dialogue, sound effects, and panel order make sense.")
            if (hasImageContext) {
                appendLine("A screenshot of the full page is attached. Use it only as context for speaker, placement, tone, and ambiguous OCR.")
            } else {
                appendLine("No screenshot is available. Use the IDs, reading order, and geometry as context.")
            }
            appendLine("Return only a valid JSON object whose keys are exactly the block IDs and whose values are only the translated text.")
            appendLine("Do not add Markdown, explanations, extra keys, nested objects, arrays, comments, or surrounding prose.")
            appendLine("Preserve meaning, names, punctuation intent, and short comic-style phrasing.")
            appendLine()
            appendLine("Page: $pageNumber / $totalPages")
            appendLine("Blocks:")
            blocks.forEach { block ->
                appendLine(
                    """- id=${block.id}; rect={x:${"%.2f".format(Locale.US, block.x)},y:${"%.2f".format(Locale.US, block.y)},w:${"%.2f".format(Locale.US, block.width)},h:${"%.2f".format(Locale.US, block.height)}}; text=${JSONObject.quote(block.text)}"""
                )
            }
            appendLine()
            appendLine("""Required JSON shape: {"${blocks.firstOrNull()?.id ?: "block_1"}":"translated text"}""")
        }.trim()
    }

    fun buildPageTranslationRepairPrompt(
        targetLanguage: String,
        blocks: List<PageTranslationBlock>,
        malformedOutput: String
    ): String {
        return buildString {
            appendLine("Repair the translation output into strict JSON for $targetLanguage.")
            appendLine("Return only a JSON object. Keys must be exactly these block IDs:")
            appendLine(blocks.joinToString(", ") { it.id })
            appendLine("Use the original block text below if a translation is missing.")
            blocks.forEach { appendLine("${it.id}: ${it.text}") }
            appendLine()
            appendLine("Malformed output:")
            append(malformedOutput.trim())
        }.trim()
    }

    fun buildTranslationCorrectionPrompt(
        targetLanguage: String,
        entries: List<TranslationCorrectionEntry>
    ): String {
        return buildString {
            appendLine("Review these completed translations to $targetLanguage.")
            appendLine("Check whether each translation preserves meaning, tone, names, pronouns, punctuation intent, and natural comic/PDF phrasing.")
            appendLine("Return only a strict JSON object with fixes for entries that need changes.")
            appendLine("Use the block ID as the key and the improved translation as the value.")
            appendLine("If no fixes are needed, return exactly {}.")
            appendLine("Do not include unchanged entries, notes, Markdown, explanations, nested objects, or arrays.")
            appendLine()
            entries.forEach { entry ->
                appendLine("id=${entry.id}; page=${entry.pageNumber}")
                appendLine("source=${JSONObject.quote(entry.sourceText)}")
                appendLine("translation=${JSONObject.quote(entry.translatedText)}")
                appendLine()
            }
        }.trim()
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

    fun estimateTranslationMaxTokens(sourceText: String, configuredMaxTokens: Int): Int {
        val estimated = ceil(PDFSummaryLogic.approximateTokens(sourceText).coerceAtLeast(16) * 2.5).toInt()
        return estimated.coerceAtLeast(64).coerceAtMost(configuredMaxTokens.coerceAtLeast(64))
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
