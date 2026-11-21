package com.example.llamadroid.branchy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, redacted text of a run's latest stage for the "Run log" card: the last [METRIC_LINES] lines of
 * `metrics.jsonl` rendered as `HH:mm:ss type key=value...` plus the tail of `stderr.log`. Safe to copy and share:
 * token-like values are replaced by `***`.
 */
object BranchyRunLog {
    const val METRIC_LINES = 300
    const val STDERR_LINES = 100
    const val MAX_LINE_CHARS = 400
    private const val MAX_TAIL_BYTES = 512L * 1024
    private const val MAX_VALUE_CHARS = 120
    private val SECRET_KEY = Regex("token|secret|hmac|password", RegexOption.IGNORE_CASE)
    private val SECRET_PAIR = Regex("""(?i)(\w*(?:token|secret|hmac|password)\w*)(\s*[=:]\s*)\S+""")
    private val LONG_RUN = Regex("""[A-Za-z0-9+_=-]{32,}""")
    private val SKIPPED_KEYS = setOf("type", "event", "ts", "time", "t", "timestamp")
    private val STAGE_DIR = Regex("""stage(\d+)""")

    /** Loads the log of the latest stage under [runDir] off the main thread. Empty string when there is nothing yet. */
    suspend fun load(runDir: File): String = withContext(Dispatchers.IO) {
        runCatching { render(latestStageDir(runDir)) }.getOrDefault("")
    }

    /** The highest `stage<N>` directory of [runDir] (stage 1 lives in [runDir] itself, like the service lays it out). */
    fun latestStageDir(runDir: File): File {
        val best = runDir.listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { dir -> STAGE_DIR.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to dir } }
            ?.filter { it.first >= 2 }
            ?.maxByOrNull { it.first }
        return best?.second ?: runDir
    }

    fun render(stageDir: File): String {
        val metrics = tailLines(File(stageDir, "metrics.jsonl"), METRIC_LINES).map { renderMetric(it) }
        val stderr = tailLines(File(stageDir, "stderr.log"), STDERR_LINES).map { redact(it).take(MAX_LINE_CHARS) }
        return buildString {
            if (metrics.isNotEmpty()) {
                append("--- metrics ---\n")
                metrics.forEach { append(it).append('\n') }
            }
            if (stderr.isNotEmpty()) {
                append("--- stderr ---\n")
                stderr.forEach { append(it).append('\n') }
            }
        }.trimEnd()
    }

    /** One metrics.jsonl line as `HH:mm:ss type key=value...` (non-JSON lines are kept as text), redacted and bounded. */
    fun renderMetric(line: String): String {
        val obj = runCatching { BranchyJson.json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return redact(line).take(MAX_LINE_CHARS)
        val type = (obj["type"] as? JsonPrimitive)?.content ?: (obj["event"] as? JsonPrimitive)?.content ?: "?"
        val rendered = buildString {
            append(timeText(obj)).append(' ').append(type)
            for ((key, value) in obj) {
                if (key in SKIPPED_KEYS) continue
                append(' ').append(key).append('=')
                append(if (SECRET_KEY.containsMatchIn(key) && !isNumber(value)) "***" else valueText(value))
            }
        }
        return redact(rendered).take(MAX_LINE_CHARS)
    }

    /** Replaces secret-looking `key=value` pairs and any 32+ character hex/base64-like run that mixes letters and digits. */
    fun redact(text: String): String =
        LONG_RUN.replace(SECRET_PAIR.replace(text) { m ->
            // Counts such as `tokens=32768` are not secrets.
            if (m.value.substringAfterLast('=').substringAfterLast(':').trim().all { c -> c.isDigit() || c == '.' || c == ',' }) m.value
            else "${m.groupValues[1]}${m.groupValues[2]}***"
        }) { m ->
            val v = m.value
            if (v.any { it.isDigit() } && v.any { it.isLetter() }) "***" else v
        }

    private fun isNumber(value: JsonElement): Boolean = value is JsonPrimitive && !value.isString && value !is JsonNull

    private fun valueText(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> value.content
        else -> value.toString()
    }.replace('\n', ' ').take(MAX_VALUE_CHARS)

    private fun timeText(obj: JsonObject): String {
        val raw = listOf("ts", "time", "t", "timestamp").firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content?.toDoubleOrNull() }
            ?: return "--:--:--"
        val ms = if (raw < 1e11) (raw * 1000.0).toLong() else raw.toLong()
        if (ms <= 0L) return "--:--:--"
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    }

    /** Last [max] lines of [file] without reading more than [MAX_TAIL_BYTES] from its end. */
    fun tailLines(file: File, max: Int): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - MAX_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                var lines = String(bytes, Charsets.UTF_8).lines()
                if (start > 0L && lines.isNotEmpty()) lines = lines.drop(1)
                lines.filter { it.isNotBlank() }.takeLast(max)
            }
        }.getOrDefault(emptyList())
    }
}
