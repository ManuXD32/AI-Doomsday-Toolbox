package com.example.llamadroid.worlds

/**
 * A detected three.js world inside model output.
 *
 * @property html a complete (or partial) HTML document, or null for a lone script
 * @property scripts sibling/lone JavaScript blocks
 * @property styles sibling CSS blocks
 * @property truncated true when the last code fence was never closed (cut-off output)
 * @property isThree true for a three.js world; false for a plain HTML page (no 3D overlay)
 */
data class WorldSource(
    val html: String?,
    val scripts: List<String>,
    val styles: List<String>,
    val truncated: Boolean = false,
    val isThree: Boolean = true,
)

/**
 * Pure Kotlin (no Android types) detection and page building for 3D worlds.
 *
 * NOTE: `tools/worlds_host_check.mjs` ports [detect], [build] and [rewriteUrl] to JavaScript so the
 * pages can be rendered in a desktop browser without Gradle. This file stays the source of truth;
 * keep the two in sync when changing the rules.
 */
object WorldPageBuilder {
    const val ORIGIN = "https://worlds.adt.local"
    private const val THREE_WRAPPER = "$ORIGIN/three/adt-three.js"
    private const val THREE_WRAPPER_R160 = "$ORIGIN/three/adt-three-r160.js"
    private const val ADDONS_PATH = "/three/r170/jsm/"
    private const val ADDONS_PATH_R160 = "/three/r160/jsm/"

    private data class Block(val lang: String, val body: String, val closed: Boolean)

    private val scriptLangs = setOf("js", "javascript", "jsx", "mjs")
    private val htmlLangs = setOf("html", "htm")
    private val cssLangs = setOf("css")

    private val threeRef = Regex(
        """THREE\.|from\s*['"]three['"]|three\.module|three\.min\.js|three@|/three/""",
    )
    private val fenceOpen = Regex("""^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_+#.\-]*)[^`]*$""")
    private val importMapTag = Regex(
        """<script\b[^>]*type\s*=\s*["']importmap["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val importsObject = Regex(""""imports"\s*:\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
    private val importEntry = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
    private val viewportMeta = Regex("""<meta\b[^>]*name\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val headOpen = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlOpen = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val doctype = Regex("""^\s*<!doctype""", RegexOption.IGNORE_CASE)
    private val threeLoader = Regex(
        """src\s*=\s*["'][^"']*three|from\s*['"][^'"]*three|import\s*['"][^'"]*three|import\s*\(|importmap""",
        RegexOption.IGNORE_CASE,
    )
    private val hasImportStatement = Regex("""(?m)^\s*(import\s|import\{|export\s)""")
    /** Pages written for three r160 (the 3D coder models' trained version) get the bundled r160 build. */
    private val r160Ref = Regex("""three[^"'\s]{0,20}(?<![0-9])(?:0\.160\.\d+|r160)(?![0-9])""")
    private val r160Version = Regex("""(?<![0-9])(?:0\.160\.\d+|r160)(?![0-9])""")
    private val pageMarkup = Regex("""<html\b|<body\b""", RegexOption.IGNORE_CASE)
    private val domUse = Regex("""\bdocument\.|getElementById|querySelector|addEventListener|createElement""")
    private val usesCanvasLookup = Regex("""getElementById|querySelector""")

    // ------------------------------------------------------------------ detection

    fun detect(markdown: String): WorldSource? {
        val blocks = parseBlocks(markdown)
        if (blocks.isEmpty()) return detectRawHtml(markdown)

        val html = blocks.filter { it.lang in htmlLangs }
        val js = blocks.filter { it.lang in scriptLangs }
        val css = blocks.filter { it.lang in cssLangs }
        val relevant = html + js
        if (relevant.none { threeRef.containsMatchIn(it.body) }) return detectPlain(blocks, html, js, css)

        val truncated = blocks.last().let { !it.closed && it.lang in (htmlLangs + scriptLangs) }
        val styles = css.map { it.body }
        if (html.isNotEmpty()) {
            val main = html.firstOrNull { threeRef.containsMatchIn(it.body) } ?: html.first()
            // A full page already carries its scripts; sibling JS blocks are usually repeats/explanations.
            val siblingScripts = if (main.body.contains("<script", ignoreCase = true)) {
                emptyList()
            } else {
                js.map { it.body }
            }
            val siblingStyles = if (main.body.contains("<style", ignoreCase = true)) emptyList() else styles
            return WorldSource(main.body, siblingScripts, siblingStyles, truncated || !main.closed)
        }
        val jsBlocks = js.filter { threeRef.containsMatchIn(it.body) }.ifEmpty { js }
        return WorldSource(null, jsBlocks.map { it.body }, styles, truncated)
    }

    private fun detectRawHtml(text: String): WorldSource? {
        val t = text.trim()
        // Only a reply that IS a page (not one that merely starts with a tag such as <think>).
        if (!doctype.containsMatchIn(t) && !t.startsWith("<html", ignoreCase = true)) return null
        val three = threeRef.containsMatchIn(t)
        if (!three && !pageMarkup.containsMatchIn(t)) return null
        return WorldSource(t, emptyList(), emptyList(), !t.contains("</html>", ignoreCase = true), isThree = three)
    }

    /** A complete plain page: an html block with `<html`/`<body`, or a js block that touches the DOM. */
    private fun detectPlain(all: List<Block>, html: List<Block>, js: List<Block>, css: List<Block>): WorldSource? {
        val truncated = all.last().let { !it.closed && it.lang in (htmlLangs + scriptLangs) }
        val styles = css.map { it.body }
        val page = html.firstOrNull { pageMarkup.containsMatchIn(it.body) }
        if (page != null) {
            val siblingScripts = if (page.body.contains("<script", ignoreCase = true)) {
                emptyList()
            } else {
                js.filter { domUse.containsMatchIn(it.body) }.map { it.body }
            }
            val siblingStyles = if (page.body.contains("<style", ignoreCase = true)) emptyList() else styles
            return WorldSource(page.body, siblingScripts, siblingStyles, truncated || !page.closed, isThree = false)
        }
        if (html.isNotEmpty()) return null
        val dom = js.filter { domUse.containsMatchIn(it.body) }
        if (dom.isEmpty()) return null
        return WorldSource(null, dom.map { it.body }, styles, truncated, isThree = false)
    }

    private fun parseBlocks(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        var fence: String? = null
        var lang = ""
        val body = StringBuilder()
        for (line in markdown.lineSequence()) {
            val open = fence
            if (open == null) {
                val m = fenceOpen.matchEntire(line) ?: continue
                fence = m.groupValues[1]
                lang = m.groupValues[2].lowercase()
                body.setLength(0)
            } else {
                val trimmed = line.trim()
                if (trimmed.length >= open.length && trimmed.all { it == open[0] }) {
                    out += Block(lang, body.toString().trimEnd('\n'), closed = true)
                    fence = null
                } else {
                    body.append(line).append('\n')
                }
            }
        }
        if (fence != null && body.isNotEmpty()) {
            out += Block(lang, body.toString().trimEnd('\n'), closed = false)
        }
        return out
    }

    // ------------------------------------------------------------------ page building

    fun build(source: WorldSource): String = build(source, emptyMap())

    /** [strings] are UI labels for the shim; they are embedded as JSON, never used as a format template. */
    fun build(source: WorldSource, strings: Map<String, String>): String {
        val allText = buildString {
            source.html?.let { append(it).append('\n') }
            source.scripts.forEach { append(it).append('\n') }
        }
        val pageControls = allText.contains("Controls")
        val htmlTruncated = source.html != null && !source.html.contains("</html>", ignoreCase = true)
        val truncated = source.truncated || htmlTruncated

        var doc = source.html ?: shellDocument(source)
        if (source.html != null) doc = addSiblings(doc, source)

        val r160 = r160Ref.containsMatchIn(doc)
        // Merge any import map the model wrote with ours (ours wins for three).
        val imports = LinkedHashMap<String, String>()
        importMapTag.findAll(doc).forEach { tag ->
            importsObject.find(tag.groupValues[1])?.groupValues?.get(1)?.let { inner ->
                importEntry.findAll(inner).forEach { imports[it.groupValues[1]] = it.groupValues[2] }
            }
        }
        doc = importMapTag.replace(doc, "")
        val addons = if (r160) ADDONS_PATH_R160 else ADDONS_PATH
        imports["three"] = if (r160) THREE_WRAPPER_R160 else THREE_WRAPPER
        imports["three/addons/"] = addons
        imports["three/examples/jsm/"] = addons

        doc = viewportMeta.replace(doc, "")

        val legacyNeeded = doc.contains("THREE.") && !threeLoader.containsMatchIn(doc)
        val head = buildString {
            append("""<script src="/adt-shim.js"></script>""")
            append("<script>window.__adtStrings=").append(jsonObject(strings)).append(';')
            if (!source.isThree) append("window.__adtPlainHtml=true;")
            if (pageControls && source.isThree) append("window.__adtPageControls=true;")
            if (truncated) append("window.__adtTruncated=true;")
            append("</script>")
            append("""<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">""")
            append("""<script type="importmap">""").append(importMapJson(imports)).append("</script>")
            if (legacyNeeded) append("""<script src="/three/r147/three.min.js"></script>""")
        }
        doc = insertIntoHead(doc, head)
        if (!doctype.containsMatchIn(doc)) doc = "<!DOCTYPE html>\n$doc"
        return doc
    }

    private fun shellDocument(source: WorldSource): String {
        if (!source.isThree) return plainShell(source)
        val scripts = source.scripts.joinToString("\n") { it.trim() }
        val needsCanvas = usesCanvasLookup.containsMatchIn(scripts)
        val prefixed = if (!hasImportStatement.containsMatchIn(scripts) && scripts.contains("THREE.")) {
            "import * as THREE from 'three';\n$scripts"
        } else {
            scripts
        }
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>3D world</title>")
            append("<style>html,body{margin:0;height:100%;overflow:hidden;background:#000}")
            append("canvas{display:block;width:100%;height:100%}</style>")
            source.styles.forEach { append("<style>").append(it).append("</style>") }
            append("</head><body>")
            if (needsCanvas) append("""<canvas id="c"></canvas>""")
            append("""<script type="module">""").append(escapeScript(prefixed)).append("</script>")
            append("</body></html>")
        }
    }

    /** Plain (non-3D) js-only page: classic script so inline handlers and globals work. */
    private fun plainShell(source: WorldSource): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>HTML page</title>")
        source.styles.forEach { append("<style>").append(it).append("</style>") }
        append("</head><body>")
        append("<script>").append(escapeScript(source.scripts.joinToString("\n") { it.trim() })).append("</script>")
        append("</body></html>")
    }

    /** A shell html block plus sibling js/css blocks: append them at the end of head/body. */
    private fun addSiblings(html: String, source: WorldSource): String {
        var doc = html
        if (source.styles.isNotEmpty()) {
            val css = source.styles.joinToString("") { "<style>$it</style>" }
            doc = insertBefore(doc, "</head>", css)
        }
        if (source.scripts.isNotEmpty()) {
            val js = source.scripts.joinToString("\n") { it.trim() }
            val prefixed = if (!hasImportStatement.containsMatchIn(js) && js.contains("THREE.")) {
                "import * as THREE from 'three';\n$js"
            } else {
                js
            }
            val tag = if (source.isThree) "<script type=\"module\">" else "<script>"
            doc = insertBefore(doc, "</body>", tag + escapeScript(prefixed) + "</script>")
        }
        return doc
    }

    private fun insertBefore(doc: String, marker: String, text: String): String {
        val i = doc.lastIndexOf(marker, ignoreCase = true)
        return if (i >= 0) doc.substring(0, i) + text + doc.substring(i) else doc + text
    }

    private fun insertIntoHead(doc: String, head: String): String {
        headOpen.find(doc)?.let { return doc.substring(0, it.range.last + 1) + head + doc.substring(it.range.last + 1) }
        htmlOpen.find(doc)?.let {
            return doc.substring(0, it.range.last + 1) + "<head>" + head + "</head>" + doc.substring(it.range.last + 1)
        }
        return "<html><head>$head</head><body>$doc</body></html>"
    }

    private fun escapeScript(js: String): String = js.replace("</script", "<\\/script", ignoreCase = true)

    private fun importMapJson(imports: Map<String, String>): String =
        "{\"imports\":" + jsonObject(imports) + "}"

    internal fun jsonObject(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { "${jsonString(it.key)}:${jsonString(it.value)}" }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '<' -> sb.append("\\u003c")
                c == ' ' || c == ' ' || c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------------ CDN rewriting

    private val cdnHosts = setOf(
        "unpkg.com", "cdn.jsdelivr.net", "cdnjs.cloudflare.com", "threejs.org", "esm.sh",
        "cdn.skypack.dev", "ajax.googleapis.com",
    )
    private val urlParts = Regex("""^https?://([^/:?#]+)(?::\d+)?([^?#]*)""", RegexOption.IGNORE_CASE)
    private val threeSegment = Regex("""(?:^|/)three(?:\.js)?(?:@[^/]*)?(?:/|$)""")
    private val jsmPath = Regex("""/(?:examples/jsm|addons)/(.+\.m?js)$""")
    private val legacyExamples = Regex("""/examples/js/(.+\.js)$""")
    private val fontPath = Regex("""/examples/fonts/(.+)$""")
    private val buildFile = Regex("""/(?:build/)?three(\.module)?(\.min)?\.m?js$""")
    private val packageRoot = Regex("""^/(?:v\d+/|stable/)?three(?:@[^/]*)?(?:/(?:es\d+|node|deno)/three(?:\.\w+)?\.m?js|/)?$""")

    /**
     * Maps a CDN URL to a path under the bundled `assets/worlds/` tree (starting with "/"), or null.
     * Module builds map to the wrapper (shared classes + render hook); legacy builds to r147.
     */
    fun rewriteUrl(url: String): String? {
        val m = urlParts.find(url.trim()) ?: return null
        val host = m.groupValues[1].lowercase()
        if (host !in cdnHosts) return null
        val path = m.groupValues[2]
        val isBuild = buildFile.find(path)
        if (host != "threejs.org" && !threeSegment.containsMatchIn(path) && isBuild == null) return null

        val r160 = r160Version.containsMatchIn(path)
        jsmPath.find(path)?.let {
            val name = it.groupValues[1]
            return (if (r160) ADDONS_PATH_R160 else ADDONS_PATH) + if (name.endsWith(".mjs")) name.dropLast(4) + ".js" else name
        }
        legacyExamples.find(path)?.let { return "/three/r147/js/" + it.groupValues[1] }
        fontPath.find(path)?.let { return "/three/fonts/" + it.groupValues[1] }
        if (isBuild != null) {
            val isModule = isBuild.groupValues[1].isNotEmpty() || path.endsWith(".mjs")
            return if (isModule) (if (r160) "/three/adt-three-r160.js" else "/three/adt-three.js") else "/three/r147/three.min.js"
        }
        if ((host == "esm.sh" || host == "cdn.skypack.dev") && packageRoot.matches(path)) {
            return if (r160) "/three/adt-three-r160.js" else "/three/adt-three.js"
        }
        return null
    }
}
