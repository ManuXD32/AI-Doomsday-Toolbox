package com.example.llamadroid.training

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** On-disk file kinds `branchy prepare` can read. */
enum class DatasetFileKind(val extensions: List<String>) {
    JSONL(listOf("jsonl", "ndjson")), JSON(listOf("json")), TXT(listOf("txt", "text", "md")),
    PARQUET(listOf("parquet")), CSV(listOf("csv", "tsv"))
}

/** Pure, unit-testable helpers for dataset import and sizing. */
object DatasetHelpers {
    fun fileKind(fileName: String): DatasetFileKind? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return null
        return DatasetFileKind.entries.firstOrNull { ext in it.extensions }
    }

    /** Plain text files are raw text; every structured file lets prepare auto-detect chat/instruction/text. */
    fun detectFormat(fileName: String): DatasetFormat = when (fileKind(fileName)) {
        DatasetFileKind.TXT -> DatasetFormat.TEXT
        else -> DatasetFormat.AUTO
    }

    fun isSupported(fileName: String): Boolean = fileKind(fileName) != null

    /** Rough bytes per row on disk; parquet is compressed so rows are smaller. Estimates only. */
    private fun bytesPerRow(kind: DatasetFileKind?, format: DatasetFormat): Long = when (kind) {
        DatasetFileKind.PARQUET -> if (format == DatasetFormat.TEXT) 2_500L else 900L
        DatasetFileKind.CSV -> 900L
        DatasetFileKind.TXT -> 600L
        DatasetFileKind.JSON, DatasetFileKind.JSONL, null -> 1_400L
    }

    /** Estimated row count of [sizeBytes] of data; at least 1 for a non-empty file. */
    fun estimateRows(sizeBytes: Long, kind: DatasetFileKind?, format: DatasetFormat): Long {
        if (sizeBytes <= 0L) return 0L
        return (sizeBytes / bytesPerRow(kind, format)).coerceAtLeast(1L)
    }

    /** Rows corresponding to a cap in MB of raw data (0 or negative = no cap -> null). */
    fun capToRows(capMb: Int, kind: DatasetFileKind?, format: DatasetFormat): Long? {
        if (capMb <= 0) return null
        return estimateRows(capMb * 1024L * 1024L, kind, format)
    }

    /** Whether a cap actually limits a dataset of [sizeBytes]. */
    fun capApplies(capMb: Int, sizeBytes: Long): Boolean = capMb > 0 && sizeBytes > capMb * 1024L * 1024L

    fun columnMapJson(map: Map<String, String>): String =
        buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()

    fun parseColumnMap(json: String): Map<String, String> {
        val obj = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
    }

    /** The `--columns k=v,k=v` argument for `branchy prepare`, or empty when no map. */
    fun columnsArg(map: Map<String, String>): String = map.entries.joinToString(",") { "${it.key}=${it.value}" }

    /** A file name safe to place inside a dataset directory (no separators or control characters). */
    fun safeFileName(name: String, fallback: String = "dataset"): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.')
        return cleaned.ifEmpty { fallback }.take(120)
    }
}
