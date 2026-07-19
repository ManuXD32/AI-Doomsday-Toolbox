package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class MangaTranslationSourceKind {
    CBZ,
    PDF
}

data class MangaTranslationCheckpoint(
    val jobId: String,
    val sourceName: String,
    val sourceKind: MangaTranslationSourceKind,
    val exportPdf: Boolean,
    val exportCbz: Boolean,
    val totalPages: Int = 0,
    val completedPageIndexes: Set<Int> = emptySet(),
    val translations: Map<String, String> = emptyMap(),
    val status: String = MangaTranslationSupport.STATUS_RUNNING
) {
    fun completedTranslationCount(): Int = translations.size
}

object MangaTranslationSupport {
    const val STATUS_RUNNING = "running"
    const val STATUS_COMPLETE = "complete"
    const val STATUS_FAILED = "failed"

    val primaryPickerMimeTypes: Array<String> = arrayOf(
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "application/pdf",
        "application/octet-stream"
    )

    val fallbackPickerMimeTypes: Array<String> = arrayOf("*/*")

    fun sourceKindFor(name: String?, mimeType: String?): MangaTranslationSourceKind? {
        val lowerName = name.orEmpty().lowercase(Locale.US)
        val normalizedMime = mimeType.orEmpty().lowercase(Locale.US).substringBefore(';').trim()
        return when {
            lowerName.endsWith(".pdf") || normalizedMime == "application/pdf" -> MangaTranslationSourceKind.PDF
            lowerName.endsWith(".cbz") ||
                lowerName.endsWith(".zip") ||
                normalizedMime == "application/vnd.comicbook+zip" ||
                normalizedMime == "application/x-cbz" ||
                normalizedMime == "application/zip" ||
                normalizedMime == "application/octet-stream" -> MangaTranslationSourceKind.CBZ
            else -> null
        }
    }

    fun isSupportedSource(name: String?, mimeType: String?): Boolean =
        sourceKindFor(name, mimeType) != null

    fun isSafeComicZipEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) return false
        return name.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }
    }

    fun checkpointToJson(checkpoint: MangaTranslationCheckpoint): JSONObject {
        val completedPages = JSONArray().apply {
            checkpoint.completedPageIndexes.sorted().forEach { put(it) }
        }
        val translations = JSONObject().apply {
            checkpoint.translations.toSortedMap().forEach { (id, text) -> put(id, text) }
        }
        return JSONObject()
            .put("jobId", checkpoint.jobId)
            .put("sourceName", checkpoint.sourceName)
            .put("sourceKind", checkpoint.sourceKind.name)
            .put("exportPdf", checkpoint.exportPdf)
            .put("exportCbz", checkpoint.exportCbz)
            .put("totalPages", checkpoint.totalPages)
            .put("completedPageIndexes", completedPages)
            .put("translations", translations)
            .put("status", checkpoint.status)
    }

    fun checkpointFromJson(json: JSONObject): MangaTranslationCheckpoint {
        val completedPages = buildSet {
            val array = json.optJSONArray("completedPageIndexes") ?: JSONArray()
            for (index in 0 until array.length()) add(array.optInt(index))
        }
        val translationsJson = json.optJSONObject("translations") ?: JSONObject()
        val translations = linkedMapOf<String, String>()
        translationsJson.keys().forEach { key ->
            val value = translationsJson.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) translations[key] = value
        }
        return MangaTranslationCheckpoint(
            jobId = json.optString("jobId").ifBlank { "manga_${System.currentTimeMillis()}" },
            sourceName = json.optString("sourceName").ifBlank { "comic" },
            sourceKind = runCatching {
                MangaTranslationSourceKind.valueOf(json.optString("sourceKind"))
            }.getOrDefault(MangaTranslationSourceKind.CBZ),
            exportPdf = json.optBoolean("exportPdf", true),
            exportCbz = json.optBoolean("exportCbz", true),
            totalPages = json.optInt("totalPages", 0).coerceAtLeast(0),
            completedPageIndexes = completedPages,
            translations = translations,
            status = json.optString("status").ifBlank { STATUS_RUNNING }
        )
    }

    fun readCheckpoint(file: File): MangaTranslationCheckpoint? =
        runCatching { checkpointFromJson(JSONObject(file.readText())) }.getOrNull()

    fun writeCheckpoint(file: File, checkpoint: MangaTranslationCheckpoint) {
        file.parentFile?.mkdirs()
        file.writeText(checkpointToJson(checkpoint).toString(2))
    }

    fun remainingTranslationIds(
        expectedIds: Collection<String>,
        checkpoint: MangaTranslationCheckpoint?
    ): List<String> {
        val completed = checkpoint?.translations?.keys.orEmpty()
        return expectedIds.filterNot { it in completed }
    }

    fun expandedBubbleRect(
        rect: PdfMappedRect,
        pageWidth: Float,
        pageHeight: Float
    ): PdfMappedRect {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val skinny = rect.width < rect.height * 0.58f
        val narrow = rect.width / safePageWidth < 0.10f
        val paddingX = when {
            skinny -> max(rect.width * 0.95f, safePageWidth * 0.025f)
            narrow -> max(rect.width * 0.55f, safePageWidth * 0.018f)
            else -> max(rect.width * 0.18f, safePageWidth * 0.006f)
        }
        val paddingY = when {
            skinny -> max(rect.height * 0.14f, safePageHeight * 0.004f)
            else -> max(rect.height * 0.16f, safePageHeight * 0.004f)
        }
        val left = (rect.x - paddingX).coerceAtLeast(0f)
        val bottom = (rect.y - paddingY).coerceAtLeast(0f)
        val right = (rect.x + rect.width + paddingX).coerceAtMost(safePageWidth)
        val top = (rect.y + rect.height + paddingY).coerceAtMost(safePageHeight)
        return PdfMappedRect(
            x = left,
            y = bottom,
            width = (right - left).coerceAtLeast(rect.width),
            height = (top - bottom).coerceAtLeast(rect.height)
        )
    }

    fun mergedRegionIsTooLarge(rect: PdfMappedRect, pageWidth: Float, pageHeight: Float): Boolean {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val widthRatio = rect.width / safePageWidth
        val heightRatio = rect.height / safePageHeight
        val areaRatio = rect.width * rect.height / (safePageWidth * safePageHeight)
        return areaRatio > 0.105f ||
            heightRatio > 0.34f ||
            (widthRatio > 0.62f && heightRatio > 0.14f) ||
            (widthRatio > 0.46f && heightRatio > 0.23f)
    }

    fun fittedTextSize(
        lineCount: Int,
        maxWidth: Float,
        maxHeight: Float,
        preferredMaxSize: Float = 42f,
        minSize: Float = 5f
    ): Float {
        val safeLines = lineCount.coerceAtLeast(1)
        val widthConstrained = maxWidth / 7.5f
        val heightConstrained = maxHeight / (safeLines * 1.05f)
        return min(preferredMaxSize, min(widthConstrained, heightConstrained))
            .coerceIn(minSize, preferredMaxSize)
    }
}
