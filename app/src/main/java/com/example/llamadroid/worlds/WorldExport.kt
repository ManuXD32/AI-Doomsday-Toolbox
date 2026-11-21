package com.example.llamadroid.worlds

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure helpers that turn a saved/detected 3D world into a standalone HTML file that opens in a
 * normal desktop or mobile browser (no ADT shim, no bundled three.js, real CDN import map).
 */
object WorldExport {
    const val THREE_VERSION = "0.170.0"
    private const val THREE_BASE = "https://unpkg.com/three@$THREE_VERSION"
    const val MIME_TYPE = "text/html"

    private val importMapTag = Regex("""<script\b[^>]*type\s*=\s*["']importmap["']""", RegexOption.IGNORE_CASE)
    private val bareThree = Regex("""from\s*['"]three(?:/addons/[^'"]*)?['"]|import\s*['"]three['"]""")
    private val hasImportStatement = Regex("""(?m)^\s*(import\s|import\{|export\s)""")
    private val usesCanvasLookup = Regex("""getElementById|querySelector""")

    /** Standalone page for [markdown] (a chat reply or saved source), or null when no world is detected. */
    fun fromMarkdown(markdown: String): String? = WorldPageBuilder.detect(markdown)?.let(::toHtml)

    fun toHtml(source: WorldSource): String {
        val html = source.html
        if (html != null) {
            var doc = html
            if (source.styles.isNotEmpty()) {
                doc = insertBefore(doc, "</head>", source.styles.joinToString("") { "<style>$it</style>" })
            }
            if (source.scripts.isNotEmpty()) {
                val js = source.scripts.joinToString("\n") { it.trim() }
                doc = insertBefore(doc, "</body>", """<script type="module">${escapeScript(js)}</script>""")
            }
            if (!importMapTag.containsMatchIn(doc) && bareThree.containsMatchIn(doc)) {
                doc = insertBefore(doc, "</head>", importMapScript())
            }
            return doc
        }
        val scripts = source.scripts.joinToString("\n") { it.trim() }
        val body = if (!hasImportStatement.containsMatchIn(scripts) && scripts.contains("THREE.")) {
            "import * as THREE from 'three';\n$scripts"
        } else {
            scripts
        }
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
            append("<title>3D world</title>\n")
            append("<style>html,body{margin:0;height:100%;overflow:hidden;background:#000}")
            append("canvas{display:block;width:100%;height:100%}</style>\n")
            source.styles.forEach { append("<style>").append(it).append("</style>\n") }
            append(importMapScript()).append('\n')
            append("</head>\n<body>\n")
            if (usesCanvasLookup.containsMatchIn(scripts)) append("<canvas id=\"c\"></canvas>\n")
            append("<script type=\"module\">\n").append(escapeScript(body)).append("\n</script>\n")
            append("</body>\n</html>\n")
        }
    }

    private fun importMapScript(): String =
        "<script type=\"importmap\">{\"imports\":{" +
            "\"three\":\"$THREE_BASE/build/three.module.js\"," +
            "\"three/addons/\":\"$THREE_BASE/examples/jsm/\"," +
            "\"three/examples/jsm/\":\"$THREE_BASE/examples/jsm/\"}}</script>"

    private fun escapeScript(js: String): String = js.replace("</script", "<\\/script", ignoreCase = true)

    private fun insertBefore(doc: String, marker: String, text: String): String {
        val i = doc.lastIndexOf(marker, ignoreCase = true)
        return if (i >= 0) doc.substring(0, i) + text + doc.substring(i) else doc + text
    }

    /** `<sanitized title>.html`; never empty, no path separators or reserved characters, at most 80 chars of title. */
    fun suggestedFileName(title: String): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .take(80)
            .trim()
        return (cleaned.ifEmpty { "3d-world" }) + ".html"
    }

    /** Writes [WorldRepository.source] of [worldId] as standalone HTML to [uri] (SAF). Returns true on success. */
    suspend fun writeSavedWorld(context: Context, repo: WorldRepository, worldId: String, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val html = repo.source(worldId)?.let(::fromMarkdown) ?: return@withContext false
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                    ?: return@withContext false
                true
            } catch (_: Exception) {
                false
            }
        }
}
